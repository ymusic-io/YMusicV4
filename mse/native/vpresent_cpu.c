#include "vpresent_impl.h"
#include "mse_ffmpeg.h"

#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
#ifdef __APPLE__
#include <CoreVideo/CoreVideo.h>
#include "mse_vt_blit.h"
#endif

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Largest picture swscale converts with accurate rounding and full chroma; bigger ones go
   bilinear, which keeps 1440p+ in real time. */
#define VP_HQ_PIXELS (1920 * 1080)

struct VpCpu {
    struct SwsContext *sws;
    int sws_sw, sws_sh, sws_fmt, sws_dw, sws_dh, sws_flags, sws_matrix, sws_full;
    uint8_t *buf[2];
    size_t buf_n[2];
    int buf_i;
    int last_w, last_h;
    uint8_t *pad;
    size_t pad_n;
    AVFrame *hw;
    int fail_logged;
    int log_sw, log_sh, log_w, log_h;
#ifdef __APPLE__
    MseVtBlit vt;
#endif
};

VpCpu *vp_cpu_create(void)
{
    VpCpu *c = calloc(1, sizeof(*c));
    if (c)
        c->sws_fmt = -1;
    return c;
}

void vp_cpu_destroy(VpCpu *c)
{
    if (!c)
        return;
    sws_freeContext(c->sws);
    free(c->buf[0]);
    free(c->buf[1]);
    free(c->pad);
    av_frame_free(&c->hw);
#ifdef __APPLE__
    mse_vt_blit_free(&c->vt);
#endif
    free(c);
}

/* One WARN per failing streak; the next good picture re-arms it. */
static int fail(VpCpu *c, const char *what, const AVFrame *f, int err)
{
    if (!c->fail_logged) {
        const char *fmt = av_get_pix_fmt_name((enum AVPixelFormat)f->format);
        c->fail_logged = 1;
        mse_critf(MSE_LOG_WARN, "vpresent cpu %s %s %dx%d err=%d", what, fmt ? fmt : "?",
                  f->width, f->height, err);
    }
    return -1;
}

static void fit(int src_w, int src_h, int box_w, int box_h, int *w, int *h)
{
    *w = src_w;
    *h = src_h;
    if (box_w <= 0 && box_h <= 0)
        return;
    if (box_w <= 0) {
        if (src_h > box_h) {
            *h = box_h;
            *w = (int)((int64_t)src_w * box_h / src_h);
        }
    } else if (box_h <= 0) {
        if (src_w > box_w) {
            *w = box_w;
            *h = (int)((int64_t)src_h * box_w / src_w);
        }
    } else if (src_w > box_w || src_h > box_h) {
        if ((int64_t)src_w * box_h <= (int64_t)src_h * box_w) {
            *h = box_h;
            *w = (int)((int64_t)src_w * box_h / src_h);
        } else {
            *w = box_w;
            *h = (int)((int64_t)src_h * box_w / src_w);
        }
    }
    if (*w < 1)
        *w = 1;
    if (*h < 1)
        *h = 1;
}

static uint8_t *out_buf(VpCpu *c, int i, int w, int h)
{
    size_t n;
    uint8_t *p;
    if (w <= 0 || h <= 0 || (int64_t)w * h > INT_MAX / 4)
        return NULL;
    n = (size_t)w * (size_t)h * 4;
    if (c->buf[i] && c->buf_n[i] >= n)
        return c->buf[i];
    p = realloc(c->buf[i], n);
    if (!p)
        return NULL;
    c->buf[i] = p;
    c->buf_n[i] = n;
    return p;
}

static void commit(VpCpu *c, int i, const char *path, int src_w, int src_h, int w, int h,
                   const uint8_t **out, int *ow, int *oh)
{
    (void)path;
    c->buf_i = i;
    c->last_w = w;
    c->last_h = h;
    c->fail_logged = 0;
    if (src_w != c->log_sw || src_h != c->log_sh || w != c->log_w || h != c->log_h) {
        c->log_sw = src_w;
        c->log_sh = src_h;
        c->log_w = w;
        c->log_h = h;
        mse_logf("vpresent cpu %s %dx%d -> %dx%d", path, src_w, src_h, w, h);
    }
    *out = c->buf[i];
    *ow = w;
    *oh = h;
}

static const AVFrame *download(VpCpu *c, const AVFrame *f)
{
    int r;
    if (!c->hw && !(c->hw = av_frame_alloc())) {
        fail(c, "hwframe alloc", f, AVERROR(ENOMEM));
        return NULL;
    }
    av_frame_unref(c->hw);
    r = av_hwframe_transfer_data(c->hw, f, 0);
    if (r < 0) {
        fail(c, "hwframe download", f, r);
        return NULL;
    }
    return c->hw;
}

/* Every plane holds `w` columns, so swscale may read that far. */
static int padded_to(const AVFrame *f, int w)
{
    int ls[4], i;
    if (av_image_fill_linesizes(ls, (enum AVPixelFormat)f->format, w) < 0)
        return 0;
    for (i = 0; i < 4; i++) {
        if (ls[i] > 0 && (!f->data[i] || f->linesize[i] < ls[i]))
            return 0;
    }
    return 1;
}

static void apply_color(VpCpu *c, const AVFrame *f, VpColor col)
{
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat)f->format);
    const int *coeff;
    int cs, r;
    if (!d || (d->flags & AV_PIX_FMT_FLAG_RGB))
        return;
    cs = col.matrix == AVCOL_SPC_BT709 ? SWS_CS_ITU709
         : col.matrix == AVCOL_SPC_BT2020_NCL ? SWS_CS_BT2020 : SWS_CS_ITU601;
    coeff = sws_getCoefficients(cs);
    /* Same matrix both sides; dst range 1 expands tv range to full RGB. */
    r = sws_setColorspaceDetails(c->sws, coeff, col.full_range, coeff, 1, 0, 1 << 16, 1 << 16);
    if (r < 0)
        mse_critf(MSE_LOG_WARN, "vpresent cpu colour details %s err=%d", d->name, r);
}

static int sws_convert(VpCpu *c, const AVFrame *sw, VpColor col, int fit_w, int fit_h,
                       const uint8_t **out, int *ow, int *oh)
{
    int vis_w = sw->width, vis_h = sw->height, src_w = vis_w;
    int w, h, dst_w, flags, y, r, i = c->buf_i ^ 1;
    uint8_t *dst, *dsts[4] = { 0 };
    int dst_ls[4] = { 0 };

    fit(vis_w, vis_h, fit_w, fit_h, &w, &h);
    dst = out_buf(c, i, w, h);
    if (!dst)
        return fail(c, "alloc", sw, AVERROR(ENOMEM));
    if ((int64_t)vis_w * vis_h <= VP_HQ_PIXELS) {
        flags = SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT | SWS_FULL_CHR_H_INP;
        flags |= w != vis_w || h != vis_h ? SWS_LANCZOS : SWS_BILINEAR;
    } else {
        flags = SWS_BILINEAR;
        /* Bilinear SIMD drops the last (width % 8) columns: convert an 8-aligned width when the
           planes are padded that far (dav1d stride 896 for 854), else round accurately. */
        if (w == vis_w && h == vis_h && (vis_w & 7)) {
            if (padded_to(sw, (vis_w + 7) & ~7))
                src_w = (vis_w + 7) & ~7;
            else
                flags |= SWS_ACCURATE_RND;
        }
    }
    dst_w = src_w != vis_w ? src_w : w;
    if (!c->sws || c->sws_sw != src_w || c->sws_sh != vis_h || c->sws_fmt != sw->format ||
        c->sws_dw != dst_w || c->sws_dh != h || c->sws_flags != flags ||
        c->sws_matrix != col.matrix || c->sws_full != col.full_range) {
        sws_freeContext(c->sws);
        c->sws = sws_getContext(src_w, vis_h, (enum AVPixelFormat)sw->format, dst_w, h,
                                AV_PIX_FMT_BGRA, flags, NULL, NULL, NULL);
        if (!c->sws)
            return fail(c, "sws_getContext", sw, AVERROR(EINVAL));
        c->sws_sw = src_w;
        c->sws_sh = vis_h;
        c->sws_fmt = sw->format;
        c->sws_dw = dst_w;
        c->sws_dh = h;
        c->sws_flags = flags;
        c->sws_matrix = col.matrix;
        c->sws_full = col.full_range;
        apply_color(c, sw, col);
    }
    if (src_w != vis_w) {
        size_t n = (size_t)src_w * (size_t)vis_h * 4;
        if (!c->pad || c->pad_n < n) {
            uint8_t *p = realloc(c->pad, n);
            if (!p)
                return fail(c, "alloc", sw, AVERROR(ENOMEM));
            c->pad = p;
            c->pad_n = n;
        }
        dsts[0] = c->pad;
        dst_ls[0] = src_w * 4;
    } else {
        dsts[0] = dst;
        dst_ls[0] = w * 4;
    }
    r = sws_scale(c->sws, (const uint8_t *const *)sw->data, sw->linesize, 0, vis_h, dsts, dst_ls);
    if (r < 0)
        return fail(c, "sws_scale", sw, r);
    if (src_w != vis_w) {
        size_t row = (size_t)vis_w * 4;
        for (y = 0; y < vis_h; y++)
            memcpy(dst + (size_t)y * row, c->pad + (size_t)y * (size_t)src_w * 4, row);
    }
    commit(c, i, "sws", vis_w, vis_h, w, h, out, ow, oh);
    return 0;
}

#ifdef __APPLE__
static unsigned cv_fmt(int f)
{
    if (f == AV_PIX_FMT_YUV420P || f == AV_PIX_FMT_YUVJ420P)
        return MSE_VT_BLIT_YUV420;
    if (f == AV_PIX_FMT_NV12)
        return MSE_VT_BLIT_NV12;
    return 0;
}

static int blit_cvpb(VpCpu *c, void *cvpb, int fit_w, int fit_h, const uint8_t **out, int *ow,
                     int *oh)
{
    int w, h, i = c->buf_i ^ 1;
    uint8_t *dst;
    if (mse_vt_blit_size(cvpb, fit_w, fit_h, &w, &h) != 0)
        return -1;
    dst = out_buf(c, i, w, h);
    if (!dst || mse_vt_blit_copy(&c->vt, cvpb, dst, w, h) != 0)
        return -1;
    commit(c, i, "vt", (int)CVPixelBufferGetWidth((CVPixelBufferRef)cvpb),
           (int)CVPixelBufferGetHeight((CVPixelBufferRef)cvpb), w, h, out, ow, oh);
    return 0;
}

static int blit_sw(VpCpu *c, const AVFrame *sw, int fit_w, int fit_h, const uint8_t **out,
                   int *ow, int *oh)
{
    unsigned fmt = cv_fmt(sw->format);
    int w, h, i = c->buf_i ^ 1;
    uint8_t *dst;
    if (!fmt || mse_vt_blit_fit(sw->width, sw->height, fit_w, fit_h, &w, &h) != 0)
        return -1;
    dst = out_buf(c, i, w, h);
    if (!dst || mse_vt_blit_sw(&c->vt, fmt, (const uint8_t *const *)sw->data, sw->linesize,
                               sw->width, sw->height, dst, w, h) != 0)
        return -1;
    commit(c, i, "vt-sw", sw->width, sw->height, w, h, out, ow, oh);
    return 0;
}
#endif

int vp_cpu_convert(VpCpu *c, const AVFrame *f, VpColor col, int fit_w, int fit_h,
                   const uint8_t **bgra, int *w, int *h)
{
    const AVFrame *sw = f;
    const AVPixFmtDescriptor *d;
    if (!c || !f || !bgra || !w || !h || f->width <= 0 || f->height <= 0)
        return -1;
#ifdef __APPLE__
    /* GPU-scales on the VT surface; falls back to the download below. */
    if (f->format == AV_PIX_FMT_VIDEOTOOLBOX && f->data[3] &&
        blit_cvpb(c, f->data[3], fit_w, fit_h, bgra, w, h) == 0)
        return 0;
#endif
    if (f->hw_frames_ctx) {
        sw = download(c, f);
        if (!sw)
            return -1;
    }
    d = av_pix_fmt_desc_get((enum AVPixelFormat)sw->format);
    if (!d || (d->flags & AV_PIX_FMT_FLAG_HWACCEL) || !sw->data[0])
        return fail(c, "format", sw, AVERROR(EINVAL));
#ifdef __APPLE__
    if (blit_sw(c, sw, fit_w, fit_h, bgra, w, h) == 0)
        return 0;
#endif
    return sws_convert(c, sw, col, fit_w, fit_h, bgra, w, h);
}

#ifdef __APPLE__
int vp_cpu_convert_cvpb(VpCpu *c, void *cvpb, VpColor col, int fit_w, int fit_h,
                        const uint8_t **bgra, int *w, int *h)
{
    CVPixelBufferRef cv = (CVPixelBufferRef)cvpb;
    OSType fmt;
    AVFrame tmp;
    int r;
    if (!c || !cvpb || !bgra || !w || !h)
        return -1;
    if (blit_cvpb(c, cvpb, fit_w, fit_h, bgra, w, h) == 0)
        return 0;
    fmt = CVPixelBufferGetPixelFormatType(cv);
    if (CVPixelBufferLockBaseAddress(cv, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
        return -1;
    memset(&tmp, 0, sizeof(tmp));
    tmp.width = (int)CVPixelBufferGetWidth(cv);
    tmp.height = (int)CVPixelBufferGetHeight(cv);
    if (fmt == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange ||
        fmt == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange) {
        tmp.format = AV_PIX_FMT_NV12;
        tmp.data[0] = CVPixelBufferGetBaseAddressOfPlane(cv, 0);
        tmp.data[1] = CVPixelBufferGetBaseAddressOfPlane(cv, 1);
        tmp.linesize[0] = (int)CVPixelBufferGetBytesPerRowOfPlane(cv, 0);
        tmp.linesize[1] = (int)CVPixelBufferGetBytesPerRowOfPlane(cv, 1);
    } else if (fmt == kCVPixelFormatType_32BGRA) {
        tmp.format = AV_PIX_FMT_BGRA;
        tmp.data[0] = CVPixelBufferGetBaseAddress(cv);
        tmp.linesize[0] = (int)CVPixelBufferGetBytesPerRow(cv);
    } else {
        CVPixelBufferUnlockBaseAddress(cv, kCVPixelBufferLock_ReadOnly);
        if (!c->fail_logged) {
            c->fail_logged = 1;
            mse_critf(MSE_LOG_WARN, "vpresent cpu cvpb format 0x%08x", (unsigned)fmt);
        }
        return -1;
    }
    r = vp_cpu_convert(c, &tmp, col, fit_w, fit_h, bgra, w, h);
    CVPixelBufferUnlockBaseAddress(cv, kCVPixelBufferLock_ReadOnly);
    return r;
}
#endif

static const VpOps k_cpu_ops = { .kind = "cpu" };

VPresenter *vpresent_create_cpu(const VpSink *s)
{
    VPresenter *p = vp_alloc(&k_cpu_ops, NULL, s, VP_MODE_CPU);
    if (p)
        mse_logf("vpresent create kind=cpu %p", (void *)p);
    return p;
}

static VpCpu *cpu_of(VPresenter *p)
{
    if (!p->cpu)
        p->cpu = vp_cpu_create();
    return p->cpu;
}

int vp_cpu_show(VPresenter *p, const AVFrame *f, VpColor c)
{
    VpCpu *cpu;
    const uint8_t *bgra;
    int fw, fh, w, h;
    if (!p->sink.on_frame)
        return VP_DROPPED;
    cpu = cpu_of(p);
    if (!cpu)
        return VP_FAIL;
    vp_fit_size(p, &fw, &fh);
    if (vp_cpu_convert(cpu, f, c, fw, fh, &bgra, &w, &h) != 0)
        return VP_FAIL;
    p->sink.on_frame(p->sink.user, w, h, bgra);
    return VP_OK;
}

#ifdef __APPLE__
int vp_cpu_show_cvpb(VPresenter *p, void *cvpb, VpColor c)
{
    VpCpu *cpu;
    const uint8_t *bgra;
    int fw, fh, w, h;
    if (!p->sink.on_frame)
        return VP_DROPPED;
    cpu = cpu_of(p);
    if (!cpu)
        return VP_FAIL;
    vp_fit_size(p, &fw, &fh);
    if (vp_cpu_convert_cvpb(cpu, cvpb, c, fw, fh, &bgra, &w, &h) != 0)
        return VP_FAIL;
    p->sink.on_frame(p->sink.user, w, h, bgra);
    return VP_OK;
}
#endif

void vp_cpu_flush(VPresenter *p)
{
    VpCpu *c = p->cpu;
    uint8_t *last;
    if (!c || c->last_w <= 0 || c->last_h <= 0 || !p->sink.on_frame)
        return;
    last = c->buf[c->buf_i];
    memset(last, 0, (size_t)c->last_w * (size_t)c->last_h * 4);
    p->sink.on_frame(p->sink.user, c->last_w, c->last_h, last);
}

int vp_cpu_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h)
{
    VpCpu *c = p->cpu;
    int n;
    if (!c || c->last_w <= 0 || c->last_h <= 0)
        return 0;
    n = c->last_w * c->last_h;
    if (w)
        *w = c->last_w;
    if (h)
        *h = c->last_h;
    /* Little-endian BGRA bytes are XRGB ints. */
    if (xrgb && cap_px >= n)
        memcpy(xrgb, c->buf[c->buf_i], (size_t)n * 4);
    return n;
}
