#ifdef __APPLE__

#include "vpresent_impl.h"
#include "mse_ffmpeg.h"
#include "mse_vt_blit.h"
#include "mse_vt_present.h"

#include <CoreVideo/CoreVideo.h>
#include <libavutil/frame.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>

#include <limits.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct {
    void *layer;     /* AVSampleBufferDisplayLayer: borrowed, or kept alive by the overlay */
    void *overlay;   /* macOS JAWT overlay, owned; NULL for a borrowed layer */
    int fail_logged; /* present_mu */
} VtPresent;

static void reveal(VtPresent *v)
{
#if TARGET_OS_OSX
    if (v->overlay)
        vt_overlay_reveal(v->overlay);
#else
    (void)v;
#endif
}

/* One WARN per failing streak. */
static int shown(VtPresent *v, int r, const char *what, int fmt, int w, int h)
{
    if (r == 0) {
        v->fail_logged = 0;
        reveal(v);
        return VP_OK;
    }
    if (!v->fail_logged) {
        const char *name = av_get_pix_fmt_name((enum AVPixelFormat)fmt);
        v->fail_logged = 1;
        mse_critf(MSE_LOG_WARN, "vpresent vt %s %s %dx%d failed", what, name ? name : "?", w, h);
    }
    return VP_FAIL;
}

static int matrix_of(VpColor c)
{
    if (c.matrix == AVCOL_SPC_SMPTE170M)
        return MSE_VT_MATRIX_601;
    if (c.matrix == AVCOL_SPC_BT2020_NCL)
        return MSE_VT_MATRIX_2020;
    return MSE_VT_MATRIX_709;
}

/* The tv-range CoreVideo format f's planes copy into; 0 = convert to BGRA instead. */
static unsigned yuv_fmt(const AVFrame *f, VpColor c)
{
    if (c.full_range)
        return 0;
    if (f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P)
        return MSE_VT_BLIT_YUV420;
    if (f->format == AV_PIX_FMT_NV12)
        return MSE_VT_BLIT_NV12;
    return 0;
}

static int vt_show(VPresenter *p, const AVFrame *f, VpColor c)
{
    VtPresent *v = p->impl;
    const uint8_t *bgra;
    unsigned fmt = yuv_fmt(f, c);
    int w, h, r = -1;
    if (f->format == AV_PIX_FMT_VIDEOTOOLBOX && f->data[3])
        r = mse_vt_show(v->layer, f->data[3], f->width, f->height);
    else if (fmt)
        r = mse_vt_show_yuv(v->layer, fmt, (const uint8_t *const *)f->data, f->linesize,
                            f->width, f->height, matrix_of(c));
    if (r == 0)
        return shown(v, 0, NULL, 0, 0, 0);
    /* The layer scales (ResizeAspect): a native-size picture, never one fitted on the CPU. */
    if (!p->cpu)
        p->cpu = vp_cpu_create();
    if (!p->cpu || vp_cpu_convert(p->cpu, f, c, 0, 0, &bgra, &w, &h) != 0)
        return VP_FAIL;
    return shown(v, mse_vt_show_bgra(v->layer, bgra, w, h), "show", f->format, w, h);
}

static int vt_show_cvpb(VPresenter *p, void *cvpb, VpColor c)
{
    VtPresent *v = p->impl;
    CVPixelBufferRef pb = (CVPixelBufferRef)cvpb;
    int w = (int)CVPixelBufferGetWidth(pb), h = (int)CVPixelBufferGetHeight(pb);
    (void)c;
    return shown(v, mse_vt_show(v->layer, cvpb, w, h), "show_cvpb", AV_PIX_FMT_VIDEOTOOLBOX, w,
                 h);
}

static void vt_flush(VPresenter *p)
{
    VtPresent *v = p->impl;
    mse_vt_flush(v->layer);
}

/* The last picture any VT layer showed: with one presenter per Canvas, this one's. */
static int vt_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h)
{
    int n = mse_vt_copy_last(NULL, 0, w, h) / 4;
    (void)p;
    if (n <= 0)
        return 0;
    if (xrgb && cap_px >= n &&
        mse_vt_copy_last((uint8_t *)xrgb, cap_px > INT_MAX / 4 ? INT_MAX : cap_px * 4, w, h) <= 0)
        return 0;
    return n;
}

static void vt_detach(VPresenter *p)
{
    VtPresent *v = p->impl;
    mse_vt_detach(v->layer);
#if TARGET_OS_OSX
    if (v->overlay)
        vt_overlay_unhost(v->overlay);
#endif
}

static int vt_zero_copy(VPresenter *p, int av_pix_fmt)
{
    (void)p;
    return av_pix_fmt == AV_PIX_FMT_VIDEOTOOLBOX;
}

static void vt_destroy(VPresenter *p)
{
    VtPresent *v = p->impl;
#if TARGET_OS_OSX
    if (v->overlay) {
        vt_overlay_bind(v->overlay, NULL);
        vt_overlay_unhost(v->overlay);
        vt_overlay_release(v->overlay);
    }
#endif
    mse_critf(MSE_LOG_INFO, "vpresent destroy kind=vt %p", (void *)p);
    free(v);
}

static const VpOps k_vt_ops = {
    .kind = "vt",
    .show = vt_show,
    .show_cvpb = vt_show_cvpb,
    .flush = vt_flush,
    .snapshot = vt_snapshot,
    .detach = vt_detach,
    .zero_copy = vt_zero_copy,
    .destroy = vt_destroy,
};

static VtPresent *vt_of(VPresenter *p)
{
    return p && p->ops == &k_vt_ops ? p->impl : NULL;
}

VPresenter *vp_vt_create(void *layer, void *overlay, const VpSink *s)
{
    VtPresent *v;
    VPresenter *p;
    if (!layer)
        return NULL;
    v = calloc(1, sizeof(*v));
    if (!v)
        return NULL;
    v->layer = layer;
    v->overlay = overlay;
    p = vp_alloc(&k_vt_ops, v, s, VP_MODE_GPU);
    if (!p) {
        free(v);
        return NULL;
    }
    mse_critf(MSE_LOG_INFO, "vpresent create kind=vt %p layer=%p overlay=%d", (void *)p, layer,
              overlay ? 1 : 0);
#if TARGET_OS_OSX
    if (overlay)
        vt_overlay_bind(overlay, p);
#endif
    return p;
}

VPresenter *vpresent_create_vt_layer(void *layer, const VpSink *s)
{
    return vp_vt_create(layer, NULL, s);
}

static void *vp_vt_layer(VPresenter *p)
{
    VtPresent *v = vt_of(p);
    return v ? v->layer : NULL;
}

void vpresent_vt_layout(VPresenter *p, int x, int y, int w, int h)
{
#if TARGET_OS_OSX
    VtPresent *v = vt_of(p);
    if (v && v->overlay)
        vt_overlay_layout(v->overlay, x, y, w, h);
#else
    (void)p;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
#endif
}

void vpresent_vt_set_hidden(VPresenter *p, int hidden)
{
#if TARGET_OS_OSX
    VtPresent *v = vt_of(p);
    if (v && v->overlay)
        vt_overlay_set_hidden(v->overlay, hidden);
#else
    (void)p;
    (void)hidden;
#endif
}

int vpresent_vt_picture_ok(VPresenter *p)
{
    return mse_vt_picture_ok(vp_vt_layer(p));
}

void vpresent_vt_resume(VPresenter *p)
{
    mse_vt_resume_presents(vp_vt_layer(p));
}

#endif /* __APPLE__ */
