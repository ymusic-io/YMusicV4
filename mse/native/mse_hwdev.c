#include "mse_hwdev.h"

#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "mse_caps.h"
#include "mse_ffmpeg.h"

#define MAX_FAILED 8

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static AVBufferRef *g_dev;
static enum AVPixelFormat g_pix = AV_PIX_FMT_NONE;
static const char *g_name = "none";

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static enum AVCodecID g_failed[MAX_FAILED];
static int g_failed_n;

static void hwdev_init(void)
{
    static const struct {
        enum AVHWDeviceType type;
        enum AVPixelFormat pix;
        const char *name;
    } kinds[] = {
        { AV_HWDEVICE_TYPE_CUDA, AV_PIX_FMT_CUDA, "cuda" },
        { AV_HWDEVICE_TYPE_VULKAN, AV_PIX_FMT_VULKAN, "vulkan" },
    };
    const char *want = getenv("YMUSIC_HWDEC");
    size_t i;
    if (want && strcmp(want, "none") == 0) {
        mse_critf(MSE_LOG_INFO, "hwdec off (YMUSIC_HWDEC=none)");
        return;
    }
    for (i = 0; i < sizeof(kinds) / sizeof(kinds[0]); i++) {
        int err;
        if (want && *want && strcmp(want, kinds[i].name) != 0)
            continue;
        err = av_hwdevice_ctx_create(&g_dev, kinds[i].type, NULL, NULL, 0);
        if (err >= 0) {
            g_pix = kinds[i].pix;
            g_name = kinds[i].name;
            mse_critf(MSE_LOG_INFO, "hwdec device %s", g_name);
            return;
        }
        mse_critf(MSE_LOG_INFO, "hwdec %s unavailable (%s)", kinds[i].name, av_err2str(err));
    }
    mse_critf(MSE_LOG_INFO, "hwdec none: software decode");
}

AVBufferRef *mse_hwdev_get(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_dev;
}

enum AVPixelFormat mse_hwdev_pix_fmt(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_pix;
}

const char *mse_hwdev_name(void)
{
    pthread_once(&g_once, hwdev_init);
    return g_name;
}

int mse_hwdev_usable(enum AVCodecID id)
{
    int i, ok;
    if (!mse_hwdev_get())
        return 0;
    pthread_mutex_lock(&g_mu);
    ok = 1;
    for (i = 0; i < g_failed_n; i++) {
        if (g_failed[i] == id)
            ok = 0;
    }
    pthread_mutex_unlock(&g_mu);
    return ok;
}

void mse_hwdev_mark_failed(enum AVCodecID id)
{
    int i;
    pthread_mutex_lock(&g_mu);
    for (i = 0; i < g_failed_n; i++) {
        if (g_failed[i] == id) {
            pthread_mutex_unlock(&g_mu);
            return;
        }
    }
    if (g_failed_n < MAX_FAILED)
        g_failed[g_failed_n++] = id;
    pthread_mutex_unlock(&g_mu);
    mse_critf(MSE_LOG_WARN, "hwdec %s rejected codec %s: software from now on", g_name,
              avcodec_get_name(id));
}

enum AVPixelFormat mse_hwdev_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    if (mse_hwdev_usable(ctx->codec_id)) {
        for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
            if (*p == g_pix)
                return *p;
        }
        /* libavcodec re-offers the list without the device format after its hwaccel init failed. */
        if (ctx->hw_device_ctx)
            mse_hwdev_mark_failed(ctx->codec_id);
    }
    for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (d && !(d->flags & AV_PIX_FMT_FLAG_HWACCEL))
            return *p;
    }
    return AV_PIX_FMT_NONE;
}

/* ffnvcodec's headers are not in FFMPEG_ROOT: CUVIDDECODECAPS as the Video Codec SDK lays it out
   (later fields took reserved space, so the size never changed), resolved the way its loader does. */
typedef struct {
    int eCodecType;
    int eChromaFormat;
    unsigned int nBitDepthMinus8;
    unsigned int reserved1[3];
    unsigned char bIsSupported;
    unsigned char nNumNVDECs;
    unsigned short nOutputFormatMask;
    unsigned int nMaxWidth;
    unsigned int nMaxHeight;
    unsigned int nMaxMBCount;
    unsigned short nMinWidth;
    unsigned short nMinHeight;
    unsigned char bIsHistogramSupported;
    unsigned char nCounterBitDepth;
    unsigned short nMaxHistogramBins;
    unsigned int reserved3[10];
} CuvidDecodeCaps;

typedef int (*CuCtxPushFn)(void *ctx);
typedef int (*CuCtxPopFn)(void **ctx);
typedef int (*CuvidGetDecoderCapsFn)(CuvidDecodeCaps *caps);

typedef void (*VkVoidFn)(void);

/* The leading members of AVVulkanDeviceContext. */
typedef struct {
    const void *alloc;
    VkVoidFn (*get_proc_addr)(void *instance, const char *name);
    void *inst;
    void *phys_dev;
} VkDeviceHead;

typedef struct {
    char name[256];
    uint32_t spec_version;
} VkExtProps;

typedef int (*VkEnumDeviceExtFn)(void *phys_dev, const char *layer, uint32_t *count, VkExtProps *props);

static const struct {
    int cap;
    const char *decoder;
    int cuvid;
    const char *vk_ext;
} k_hw_codecs[] = {
    { MSE_CAP_H264, "h264", 4, "VK_KHR_video_decode_h264" },
    { MSE_CAP_HEVC, "hevc", 8, "VK_KHR_video_decode_h265" },
    { MSE_CAP_VP9, "vp9", 10, "VK_KHR_video_decode_vp9" },
    { MSE_CAP_AV1, "av1", 11, "VK_KHR_video_decode_av1" },
};

static int has_hwaccel(const char *decoder, enum AVHWDeviceType type)
{
    const AVCodec *c = avcodec_find_decoder_by_name(decoder);
    const AVCodecHWConfig *cfg;
    int i;
    for (i = 0; c && (cfg = avcodec_get_hw_config(c, i)) != NULL; i++) {
        if (cfg->device_type == type && (cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX))
            return 1;
    }
    return 0;
}

static int cuda_caps(MseCapRow *rows, int cap)
{
    void *cuda = dlopen("libcuda.so.1", RTLD_NOW | RTLD_LOCAL);
    void *cuvid = dlopen("libnvcuvid.so.1", RTLD_NOW | RTLD_LOCAL);
    /* AVCUDADeviceContext.cuda_ctx is its first member. */
    void *ctx = *(void **)((AVHWDeviceContext *)g_dev->data)->hwctx;
    CuCtxPushFn push = NULL;
    CuCtxPopFn pop = NULL;
    CuvidGetDecoderCapsFn get_caps = NULL;
    void *popped = NULL;
    size_t k;
    int n = 0, err;
    if (cuda && cuvid) {
        push = (CuCtxPushFn)dlsym(cuda, "cuCtxPushCurrent_v2");
        pop = (CuCtxPopFn)dlsym(cuda, "cuCtxPopCurrent_v2");
        get_caps = (CuvidGetDecoderCapsFn)dlsym(cuvid, "cuvidGetDecoderCaps");
    }
    if (!push || !pop || !get_caps) {
        mse_critf(MSE_LOG_WARN, "hwdec cuda caps: no cuvidGetDecoderCaps (cuda=%p nvcuvid=%p)", cuda,
                  cuvid);
        goto out;
    }
    err = push(ctx);
    if (err != 0) {
        mse_critf(MSE_LOG_WARN, "hwdec cuda caps: cuCtxPushCurrent failed (%d)", err);
        goto out;
    }
    for (k = 0; k < sizeof(k_hw_codecs) / sizeof(k_hw_codecs[0]) && n < cap; k++) {
        unsigned int w = 0, h = 0, mbs = 0;
        int bits, best = 0;
        if (!has_hwaccel(k_hw_codecs[k].decoder, AV_HWDEVICE_TYPE_CUDA))
            continue;
        for (bits = 8; bits <= 12; bits += 2) {
            CuvidDecodeCaps c;
            memset(&c, 0, sizeof(c));
            c.eCodecType = k_hw_codecs[k].cuvid;
            c.eChromaFormat = 1;
            c.nBitDepthMinus8 = (unsigned int)(bits - 8);
            err = get_caps(&c);
            if (err != 0) {
                mse_critf(MSE_LOG_WARN, "hwdec cuda caps: %s %d-bit query failed (%d)",
                          k_hw_codecs[k].decoder, bits, err);
                break;
            }
            if (!c.bIsSupported)
                break;
            best = bits;
            if (bits == 8) {
                w = c.nMaxWidth;
                h = c.nMaxHeight;
                mbs = c.nMaxMBCount;
            }
        }
        if (!best || !w || !h)
            continue;
        /* CodedWidth * CodedHeight / 256 <= nMaxMBCount. */
        if (mbs && (uint64_t)w * h / 256 > mbs)
            h = (unsigned int)((uint64_t)mbs * 256 / w);
        rows[n].codec = k_hw_codecs[k].cap;
        rows[n].max_bits = best;
        rows[n].chroma = MSE_CAP_420;
        rows[n].max_w = (int)w;
        rows[n].max_h = (int)h;
        rows[n].max_fps = 0;
        rows[n].hw = 1;
        n++;
    }
    pop(&popped);
out:
    if (cuvid)
        dlclose(cuvid);
    if (cuda)
        dlclose(cuda);
    return n;
}

static int vulkan_caps(MseCapRow *rows, int cap)
{
    const VkDeviceHead *d = (const VkDeviceHead *)((AVHWDeviceContext *)g_dev->data)->hwctx;
    VkEnumDeviceExtFn en = (VkEnumDeviceExtFn)d->get_proc_addr(d->inst,
                                                               "vkEnumerateDeviceExtensionProperties");
    VkExtProps *ext;
    uint32_t count = 0, i;
    size_t k;
    int n = 0, err;
    if (!en) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan caps: no vkEnumerateDeviceExtensionProperties");
        return 0;
    }
    err = en(d->phys_dev, NULL, &count, NULL);
    if (err < 0 || count == 0) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan caps: extension count failed (%d)", err);
        return 0;
    }
    ext = calloc(count, sizeof(*ext));
    if (!ext) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan caps: out of memory for %u extensions", count);
        return 0;
    }
    err = en(d->phys_dev, NULL, &count, ext);
    if (err < 0) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan caps: extension list failed (%d)", err);
        count = 0;
    }
    for (k = 0; k < sizeof(k_hw_codecs) / sizeof(k_hw_codecs[0]) && n < cap; k++) {
        if (!has_hwaccel(k_hw_codecs[k].decoder, AV_HWDEVICE_TYPE_VULKAN))
            continue;
        for (i = 0; i < count; i++) {
            if (strncmp(ext[i].name, k_hw_codecs[k].vk_ext, sizeof(ext[i].name)) != 0)
                continue;
            rows[n].codec = k_hw_codecs[k].cap;
            rows[n].max_bits = 8;
            rows[n].chroma = MSE_CAP_420;
            rows[n].max_w = 4096;
            rows[n].max_h = 2304;
            rows[n].max_fps = 0;
            rows[n].hw = 1;
            n++;
            break;
        }
    }
    free(ext);
    return n;
}

int mse_hwdev_caps(MseCapRow *rows, int cap)
{
    if (!mse_hwdev_get())
        return 0;
    if (g_pix == AV_PIX_FMT_CUDA)
        return cuda_caps(rows, cap);
    if (g_pix == AV_PIX_FMT_VULKAN)
        return vulkan_caps(rows, cap);
    return 0;
}
