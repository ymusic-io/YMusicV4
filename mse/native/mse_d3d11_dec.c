#ifdef _WIN32

#include "mse_d3d11.h"
#include "mse_ffmpeg.h"

#include <libavutil/buffer.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixfmt.h>
#include <libavcodec/avcodec.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_hwdev;      /* process-wide; borrow via mse_d3d11_hwdev_ref() */

/* The 8-bit DXVA decoder profiles ffmpeg's d3d11va opens for VP9 / AV1 (libavcodec/dxva2.c). */
static const GUID k_vp9_profile0 =
    { 0x463707f8, 0xa1d0, 0x4585, { 0x87, 0x6d, 0x83, 0xaa, 0x6d, 0x60, 0xb8, 0x9e } };
static const GUID k_av1_profile0 =
    { 0xb8be4ccb, 0xcf53, 0x46ba, { 0x8d, 0x59, 0xd6, 0xb8, 0xa6, 0xda, 0x5d, 0x2a } };
static pthread_once_t g_caps_once = PTHREAD_ONCE_INIT;
static int g_cap_vp9, g_cap_av1;

static void hwdev_lock_cb(void *ctx)
{
    (void)ctx;
    mse_d3d11_lock();
}

static void hwdev_unlock_cb(void *ctx)
{
    (void)ctx;
    mse_d3d11_unlock();
}

static void hwdev_init(void)
{
    AVBufferRef *ref;
    AVHWDeviceContext *dc;
    AVD3D11VADeviceContext *d3d;
    ID3D11Device *dev;

    dev = mse_d3d11_device();
    if (!dev)
        return;
    ref = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);
    if (!ref)
        return;
    dc = (AVHWDeviceContext *)ref->data;
    d3d = (AVD3D11VADeviceContext *)dc->hwctx;
    /* ffmpeg's hwcontext takes ownership and Releases on free; AddRef so our
       singleton outlives any av_buffer_unref of this device context. */
    ID3D11Device_AddRef(dev);
    d3d->device = dev;
    /* Leave device_context NULL: hwcontext_d3d11va fills it from `device`. */
    d3d->lock = hwdev_lock_cb;
    d3d->unlock = hwdev_unlock_cb;
    d3d->lock_ctx = NULL;
    /* Decode output must also be bindable as an SRV on the same device so
       the NV12 pixel shader can sample it with no cross-device copy. */
    d3d->BindFlags = D3D11_BIND_DECODER | D3D11_BIND_SHADER_RESOURCE;
    if (av_hwdevice_ctx_init(ref) < 0) {
        mse_critf(MSE_LOG_WARN, "d3d11va hwdevice_ctx_init failed");
        av_buffer_unref(&ref);
        return;
    }
    g_hwdev = ref;
    mse_critf(MSE_LOG_INFO, "d3d11va hwdevice ready");
}

AVBufferRef *mse_d3d11_hwdev_ref(void)
{
    pthread_once(&g_once, hwdev_init);
    if (!g_hwdev)
        return NULL;
    return av_buffer_ref(g_hwdev);
}

enum AVPixelFormat mse_d3d11_pick(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    (void)ctx;
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        if (*p == AV_PIX_FMT_D3D11)
            return *p;
    }
    /* ffmpeg re-offers without the hw format when its hwaccel init fails;
       fall through to the first software pixfmt so the decoder can keep
       running on CPU. The caller will route the sw AVFrame via scale_bgra. */
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        if (*p != AV_PIX_FMT_D3D11)
            return *p;
    }
    return AV_PIX_FMT_NONE;
}

static int has_profile(ID3D11VideoDevice *vd, const GUID *want)
{
    UINT i, n = ID3D11VideoDevice_GetVideoDecoderProfileCount(vd);
    for (i = 0; i < n; i++) {
        GUID g;
        BOOL ok = FALSE;
        if (FAILED(ID3D11VideoDevice_GetVideoDecoderProfile(vd, i, &g)) ||
            memcmp(&g, want, sizeof(g)) != 0)
            continue;
        return SUCCEEDED(ID3D11VideoDevice_CheckVideoDecoderFormat(vd, want, DXGI_FORMAT_NV12,
                                                                   &ok)) && ok;
    }
    return 0;
}

static void caps_init(void)
{
    ID3D11Device *dev = mse_d3d11_device();
    ID3D11VideoDevice *vd = NULL;
    HRESULT hr;
    if (!dev)
        return;
    hr = ID3D11Device_QueryInterface(dev, &IID_ID3D11VideoDevice, (void **)&vd);
    if (FAILED(hr) || !vd) {
        mse_critf(MSE_LOG_WARN, "d3d11 no ID3D11VideoDevice hr=0x%08lx: VP9/AV1 decode in software",
                  (unsigned long)hr);
        return;
    }
    g_cap_vp9 = has_profile(vd, &k_vp9_profile0);
    g_cap_av1 = has_profile(vd, &k_av1_profile0);
    ID3D11VideoDevice_Release(vd);
    mse_critf(MSE_LOG_INFO, "d3d11 decoder profiles vp9=%d av1=%d", g_cap_vp9, g_cap_av1);
}

static int has_d3d11_config(const AVCodec *codec)
{
    const AVCodecHWConfig *cfg;
    int i;
    for (i = 0; (cfg = avcodec_get_hw_config(codec, i)) != NULL; i++) {
        if (cfg->device_type == AV_HWDEVICE_TYPE_D3D11VA && cfg->pix_fmt == AV_PIX_FMT_D3D11 &&
            (cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX))
            return 1;
    }
    return 0;
}

int mse_d3d11_can_decode(const AVCodec *codec)
{
    if (!codec || !has_d3d11_config(codec))
        return 0;
    pthread_once(&g_once, hwdev_init);
    if (!g_hwdev)
        return 0;
    switch (codec->id) {
    case AV_CODEC_ID_H264:
    case AV_CODEC_ID_HEVC:
        return 1;
    case AV_CODEC_ID_VP9:
        pthread_once(&g_caps_once, caps_init);
        return g_cap_vp9;
    case AV_CODEC_ID_AV1:
        pthread_once(&g_caps_once, caps_init);
        return g_cap_av1;
    default:
        return 0;
    }
}

#endif /* _WIN32 */
