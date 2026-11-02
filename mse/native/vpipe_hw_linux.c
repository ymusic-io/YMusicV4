#if defined(__linux__) && !defined(__ANDROID__)

#include "vpipe_hw.h"
#include "mse_hwdev.h"

#include <pthread.h>

static const AVCodec *lx_decoder(enum AVCodecID id)
{
    switch (id) {
    case AV_CODEC_ID_H264:
        return avcodec_find_decoder_by_name("h264");
    case AV_CODEC_ID_HEVC:
        return avcodec_find_decoder_by_name("hevc");
    case AV_CODEC_ID_VP9:
        return avcodec_find_decoder_by_name("vp9");
    case AV_CODEC_ID_AV1:
        return avcodec_find_decoder_by_name("av1");
    default:
        return NULL;
    }
}

static int lx_usable(const AVCodecParameters *par)
{
    return lx_decoder(par->codec_id) && mse_hwdev_usable(par->codec_id);
}

static AVBufferRef *lx_device_ref(void)
{
    AVBufferRef *dev = mse_hwdev_get();
    return dev ? av_buffer_ref(dev) : NULL;
}

static VpHw g_hw;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void lx_init(void)
{
    if (!mse_hwdev_get())
        return;
    g_hw.name = mse_hwdev_name();
    g_hw.pix_fmt = mse_hwdev_pix_fmt();
    g_hw.readback_ok = 1;
    g_hw.usable = lx_usable;
    g_hw.decoder = lx_decoder;
    g_hw.device_ref = lx_device_ref;
    g_hw.get_format = mse_hwdev_get_format;
    g_hw.reject = mse_hwdev_mark_failed;
}

const VpHw *vpipe_hw_linux(void)
{
    pthread_once(&g_once, lx_init);
    return g_hw.usable ? &g_hw : NULL;
}

#endif
