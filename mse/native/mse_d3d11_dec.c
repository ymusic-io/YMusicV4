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

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_hwdev;      /* process-wide; borrow via mse_d3d11_hwdev_ref() */

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

#endif /* _WIN32 */
