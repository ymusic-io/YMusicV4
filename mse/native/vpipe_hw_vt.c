#ifdef __APPLE__

#include "vpipe_hw.h"
#include "mse_ffmpeg.h"
#include "mse_vt_present.h"

#include <CoreVideo/CoreVideo.h>
#include <TargetConditionals.h>
#include <VideoToolbox/VideoToolbox.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>

#include <pthread.h>
#include <stdlib.h>

/* VP9 decodes on a session instead: ffmpeg's VideoToolbox hwaccel on native vp9 SIGILLs. */
static const AVCodec *vt_decoder(enum AVCodecID id)
{
    const AVCodecHWConfig *cfg;
    const AVCodec *c;
    int i;
    switch (id) {
    case AV_CODEC_ID_H264:
        c = avcodec_find_decoder_by_name("h264");
        break;
    case AV_CODEC_ID_HEVC:
        c = avcodec_find_decoder_by_name("hevc");
        break;
    case AV_CODEC_ID_AV1:
        c = avcodec_find_decoder_by_name("av1");
        break;
    default:
        return NULL;
    }
    for (i = 0; c && (cfg = avcodec_get_hw_config(c, i)) != NULL; i++) {
        if (cfg->device_type == AV_HWDEVICE_TYPE_VIDEOTOOLBOX &&
            (cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX))
            return c;
    }
    return NULL;
}

static pthread_once_t g_av1_once = PTHREAD_ONCE_INIT;
static int g_av1;

/* Apple's AV1 decoder exists only in hardware (M3 / A17 Pro and later). */
static void av1_probe(void)
{
#if TARGET_OS_SIMULATOR
    g_av1 = 0;
#else
    const CMVideoCodecType av01 = ('a' << 24) | ('v' << 16) | ('0' << 8) | '1';
    g_av1 = VTIsHardwareDecodeSupported(av01) ? 1 : 0;
#endif
    mse_critf(MSE_LOG_INFO, "videotoolbox av1 decode %s", g_av1 ? "in hardware" : "unavailable");
}

static pthread_once_t g_vp9_once = PTHREAD_ONCE_INIT;
static int g_vp9;

/* Apple silicon's VP9 decoder is supplemental: registered before the hardware check. */
static void vp9_probe(void)
{
#if defined(__aarch64__) && !TARGET_OS_SIMULATOR
    if (__builtin_available(macOS 11.0, iOS 26.2, *)) {
        VTRegisterSupplementalVideoDecoderIfAvailable(kCMVideoCodecType_VP9);
        g_vp9 = VTIsHardwareDecodeSupported(kCMVideoCodecType_VP9) ? 1 : 0;
    }
#endif
    mse_critf(MSE_LOG_INFO, "videotoolbox vp9 decode %s", g_vp9 ? "in hardware" : "unavailable");
}

/* The session writes 8-bit 4:2:0 (NV12): other depths and chroma layouts stay in software. */
static int vp9_8bit_420(const AVCodecParameters *par)
{
    if (par->profile != AV_PROFILE_UNKNOWN)
        return par->profile == AV_PROFILE_VP9_0;
    return par->format == AV_PIX_FMT_YUV420P;
}

static int vt_usable(const AVCodecParameters *par)
{
    if (par->codec_id == AV_CODEC_ID_VP9) {
        if (par->width <= 0 || par->height <= 0 || !vp9_8bit_420(par))
            return 0;
        pthread_once(&g_vp9_once, vp9_probe);
        return g_vp9;
    }
    if (!vt_decoder(par->codec_id))
        return 0;
    if (par->codec_id == AV_CODEC_ID_AV1) {
        pthread_once(&g_av1_once, av1_probe);
        return g_av1;
    }
    return 1;
}

static pthread_once_t g_dev_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_dev;

static void dev_init(void)
{
    int r = av_hwdevice_ctx_create(&g_dev, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0);
    if (r < 0) {
        g_dev = NULL;
        mse_critf(MSE_LOG_WARN, "videotoolbox device: %s", av_err2str(r));
    }
}

static AVBufferRef *vt_device_ref(void)
{
    pthread_once(&g_dev_once, dev_init);
    return g_dev ? av_buffer_ref(g_dev) : NULL;
}

static enum AVPixelFormat vt_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    (void)ctx;
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        if (*p == AV_PIX_FMT_VIDEOTOOLBOX)
            return *p;
    }
    /* Also the path after a failed hwaccel init: libavcodec re-offers without that format. */
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (d && !(d->flags & AV_PIX_FMT_FLAG_HWACCEL))
            return *p;
    }
    return AV_PIX_FMT_NONE;
}

/* mse_vt_dec decodes each packet synchronously into one slot and gives no pts: one picture per
   packet holds only for a codec without reordering (VP9). */
typedef struct {
    void *dec;
    CVPixelBufferRef pic; /* decoded, not yet received */
    int64_t pts, dts, duration;
    AVRational sar;
    int draining;
} VtSess;

static void *sess_open(const AVCodecParameters *par, AVRational pkt_timebase)
{
    VtSess *s;
    (void)pkt_timebase;
    if (par->codec_id != AV_CODEC_ID_VP9)
        return NULL;
    s = calloc(1, sizeof(*s));
    if (!s)
        return NULL;
    s->dec = mse_vt_dec_open_vp9(par->extradata, par->extradata_size, par->profile, NULL, 0,
                                 par->width, par->height);
    if (!s->dec) {
        free(s);
        return NULL;
    }
    s->sar = par->sample_aspect_ratio;
    return s;
}

static void sess_drop_pic(VtSess *s)
{
    if (s->pic)
        CVPixelBufferRelease(s->pic);
    s->pic = NULL;
}

static int sess_send(void *sess, const AVPacket *pkt)
{
    VtSess *s = sess;
    void *pb = NULL;
    int w, h;
    if (s->draining)
        return AVERROR_EOF;
    if (!pkt || (!pkt->data && !pkt->size)) {
        s->draining = 1;
        return 0;
    }
    if (s->pic)
        return AVERROR(EAGAIN);
    if (!pkt->data || pkt->size <= 0)
        return AVERROR(EINVAL);
    if (mse_vt_dec_send(s->dec, pkt->data, pkt->size, 1) < 0)
        return AVERROR_INVALIDDATA;
    if (mse_vt_dec_recv(s->dec, &pb, &w, &h) && pb) {
        s->pic = (CVPixelBufferRef)pb;
        s->pts = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
        s->dts = pkt->dts;
        s->duration = pkt->duration;
    }
    return 0;
}

static void pic_free(void *opaque, uint8_t *data)
{
    (void)opaque;
    CVPixelBufferRelease((CVPixelBufferRef)data);
}

static int sess_receive(void *sess, AVFrame *out)
{
    VtSess *s = sess;
    av_frame_unref(out);
    if (!s->pic)
        return s->draining ? AVERROR_EOF : AVERROR(EAGAIN);
    out->buf[0] = av_buffer_create((uint8_t *)s->pic, sizeof(s->pic), pic_free, NULL,
                                   AV_BUFFER_FLAG_READONLY);
    if (!out->buf[0])
        return AVERROR(ENOMEM);
    out->format = AV_PIX_FMT_VIDEOTOOLBOX;
    out->width = (int)CVPixelBufferGetWidth(s->pic);
    out->height = (int)CVPixelBufferGetHeight(s->pic);
    out->data[3] = (uint8_t *)s->pic;
    out->pts = s->pts;
    out->best_effort_timestamp = s->pts;
    out->pkt_dts = s->dts;
    out->duration = s->duration;
    out->sample_aspect_ratio = s->sar;
    s->pic = NULL;
    return 0;
}

static void sess_flush(void *sess)
{
    VtSess *s = sess;
    mse_vt_dec_flush(s->dec);
    sess_drop_pic(s);
    s->draining = 0;
}

static void sess_close(void *sess)
{
    VtSess *s = sess;
    sess_drop_pic(s);
    mse_vt_dec_close(s->dec);
    free(s);
}

static const VpHw k_vt = {
    .name = "videotoolbox",
    .pix_fmt = AV_PIX_FMT_VIDEOTOOLBOX,
    .readback_ok = 1,
    .usable = vt_usable,
    .decoder = vt_decoder,
    .device_ref = vt_device_ref,
    .get_format = vt_get_format,
    .session_open = sess_open,
    .session_send = sess_send,
    .session_receive = sess_receive,
    .session_flush = sess_flush,
    .session_close = sess_close,
};

const VpHw *vpipe_hw_vt(void)
{
    return &k_vt;
}

#endif /* __APPLE__ */
