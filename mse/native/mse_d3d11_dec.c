#ifdef _WIN32

#include "mse_d3d11.h"
#include "mse_caps.h"
#include "mse_ffmpeg.h"

#include <libavutil/buffer.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libavcodec/avcodec.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_hwdev;      /* process-wide; borrow via mse_d3d11_hwdev_ref() */

/* The 8-bit DXVA decoder profiles ffmpeg's d3d11va opens (libavcodec/dxva2.c). */
static const GUID k_h264_e =
    { 0x1b81be68, 0xa0c7, 0x11d3, { 0xb9, 0x84, 0x00, 0xc0, 0x4f, 0x2e, 0x73, 0xc5 } };
static const GUID k_h264_f =
    { 0x1b81be69, 0xa0c7, 0x11d3, { 0xb9, 0x84, 0x00, 0xc0, 0x4f, 0x2e, 0x73, 0xc5 } };
static const GUID k_h264_intel =
    { 0x604f8e68, 0x4951, 0x4c54, { 0x88, 0xfe, 0xab, 0xd2, 0x5c, 0x15, 0xb3, 0xd6 } };
static const GUID k_hevc_main =
    { 0x5b11d51b, 0x2f4c, 0x4452, { 0xbc, 0xc3, 0x09, 0xf2, 0xa1, 0x16, 0x0c, 0xc0 } };
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
    if (!g_hwdev || mse_d3d11_lost())
        return NULL;
    return av_buffer_ref(g_hwdev);
}

/* The present shader samples NV12 through R8/R8G8 views: 8-bit 4:2:0 only. */
static int nv12_fits(enum AVPixelFormat f)
{
    return f == AV_PIX_FMT_YUV420P || f == AV_PIX_FMT_YUVJ420P || f == AV_PIX_FMT_NV12;
}

enum AVPixelFormat mse_d3d11_pick(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    if (nv12_fits(ctx->sw_pix_fmt) && !mse_d3d11_lost()) {
        for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
            if (*p == AV_PIX_FMT_D3D11)
                return *p;
        }
    }
    /* Also the path after a failed hwaccel init: ffmpeg re-offers without that format. */
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (d && !(d->flags & AV_PIX_FMT_FLAG_HWACCEL))
            return *p;
    }
    return AV_PIX_FMT_NONE;
}

int mse_d3d11_par_ok(const AVCodecParameters *par)
{
    if (par->bits_per_raw_sample > 8)
        return 0;
    if (par->format != AV_PIX_FMT_NONE && !nv12_fits((enum AVPixelFormat)par->format))
        return 0;
    switch (par->codec_id) {
    case AV_CODEC_ID_VP9:
        return par->profile == AV_PROFILE_UNKNOWN || par->profile == AV_PROFILE_VP9_0;
    case AV_CODEC_ID_HEVC:
        return par->profile == AV_PROFILE_UNKNOWN || par->profile == AV_PROFILE_HEVC_MAIN ||
               par->profile == AV_PROFILE_HEVC_MAIN_STILL_PICTURE;
    case AV_CODEC_ID_AV1:
        /* av1C: byte 2 bit 6 is high_bitdepth. */
        if (par->extradata && par->extradata_size >= 4 && (par->extradata[0] & 0x80) &&
            (par->extradata[2] & 0x40))
            return 0;
        return par->profile == AV_PROFILE_UNKNOWN || par->profile == AV_PROFILE_AV1_MAIN;
    default:
        return 1;
    }
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
    if (!g_hwdev || mse_d3d11_lost())
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

static int decodes_at(ID3D11VideoDevice *vd, const GUID *g, UINT w, UINT h)
{
    D3D11_VIDEO_DECODER_DESC desc;
    UINT n = 0;
    memset(&desc, 0, sizeof(desc));
    desc.Guid = *g;
    desc.SampleWidth = w;
    desc.SampleHeight = h;
    desc.OutputFormat = DXGI_FORMAT_NV12;
    return SUCCEEDED(ID3D11VideoDevice_GetVideoDecoderConfigCount(vd, &desc, &n)) && n > 0;
}

int mse_d3d11_caps(MseCapRow *rows, int cap)
{
    static const struct {
        int codec;
        const char *decoder;
        const GUID *guid[3];
    } kinds[] = {
        { MSE_CAP_H264, "h264", { &k_h264_f, &k_h264_e, &k_h264_intel } },
        { MSE_CAP_HEVC, "hevc", { &k_hevc_main, NULL, NULL } },
        { MSE_CAP_VP9, "vp9", { &k_vp9_profile0, NULL, NULL } },
        { MSE_CAP_AV1, "av1", { &k_av1_profile0, NULL, NULL } },
    };
    static const UINT sizes[][2] = {
        { 1280, 720 }, { 1920, 1088 }, { 2560, 1440 }, { 3840, 2160 },
        { 4096, 2304 }, { 7680, 4320 }, { 8192, 4352 },
    };
    ID3D11VideoDevice *vd = NULL;
    HRESULT hr;
    size_t k, g, s;
    int n = 0;
    pthread_once(&g_once, hwdev_init);
    if (!g_hwdev || mse_d3d11_lost())
        return 0;
    hr = ID3D11Device_QueryInterface(mse_d3d11_device(), &IID_ID3D11VideoDevice, (void **)&vd);
    if (FAILED(hr) || !vd) {
        mse_critf(MSE_LOG_WARN, "d3d11 caps: no ID3D11VideoDevice hr=0x%08lx", (unsigned long)hr);
        return 0;
    }
    for (k = 0; k < sizeof(kinds) / sizeof(kinds[0]) && n < cap; k++) {
        const AVCodec *c = avcodec_find_decoder_by_name(kinds[k].decoder);
        UINT bw = 0, bh = 0;
        if (!c || !has_d3d11_config(c))
            continue;
        for (g = 0; g < 3 && kinds[k].guid[g]; g++) {
            if (!has_profile(vd, kinds[k].guid[g]))
                continue;
            for (s = 0; s < sizeof(sizes) / sizeof(sizes[0]); s++) {
                if (!decodes_at(vd, kinds[k].guid[g], sizes[s][0], sizes[s][1]))
                    break;
                if ((uint64_t)sizes[s][0] * sizes[s][1] > (uint64_t)bw * bh) {
                    bw = sizes[s][0];
                    bh = sizes[s][1];
                }
            }
        }
        if (!bw)
            continue;
        rows[n].codec = kinds[k].codec;
        rows[n].max_bits = 8;
        rows[n].chroma = MSE_CAP_420;
        rows[n].max_w = (int)bw;
        rows[n].max_h = (int)bh;
        rows[n].max_fps = 0;
        rows[n].hw = 1;
        n++;
    }
    ID3D11VideoDevice_Release(vd);
    return n;
}

#endif /* _WIN32 */
