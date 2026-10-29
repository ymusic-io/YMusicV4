#ifdef __APPLE__

#include "vpipe_hw.h"
#include "mse_ffmpeg.h"

#include <TargetConditionals.h>
#include <VideoToolbox/VideoToolbox.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>

#include <pthread.h>

/* VP9 stays off: ffmpeg's VideoToolbox hwaccel on native vp9 SIGILLs. */
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

static int vt_usable(const AVCodecParameters *par)
{
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

static const VpHw k_vt = {
    .name = "videotoolbox",
    .pix_fmt = AV_PIX_FMT_VIDEOTOOLBOX,
    .readback_ok = 1,
    .usable = vt_usable,
    .decoder = vt_decoder,
    .device_ref = vt_device_ref,
    .get_format = vt_get_format,
};

const VpHw *vpipe_hw_vt(void)
{
    return &k_vt;
}

#endif /* __APPLE__ */
