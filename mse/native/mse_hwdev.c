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
#define MAX_ROWS 8

static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
/* g_mu */
static AVBufferRef *g_dev;
static enum AVPixelFormat g_pix = AV_PIX_FMT_NONE;
static const char *g_name = "none";
/* The renderer picked the device (mse_hwdev_adopt): the process opens none of its own. */
static int g_adopted;
static uint32_t g_vk_ops;
static uint64_t g_generation;
static int g_mask_known;
static unsigned g_mask;
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
    int adopted;
    pthread_mutex_lock(&g_mu);
    adopted = g_adopted;
    pthread_mutex_unlock(&g_mu);
    if (adopted)
        return;
    if (want && strcmp(want, "none") == 0) {
        mse_critf(MSE_LOG_INFO, "hwdec off (YMUSIC_HWDEC=none)");
        return;
    }
    for (i = 0; i < sizeof(kinds) / sizeof(kinds[0]); i++) {
        AVBufferRef *dev = NULL;
        int err;
        if (want && *want && strcmp(want, kinds[i].name) != 0)
            continue;
        err = av_hwdevice_ctx_create(&dev, kinds[i].type, NULL, NULL, 0);
        if (err >= 0) {
            pthread_mutex_lock(&g_mu);
            if (!g_adopted) {
                g_dev = dev;
                dev = NULL;
                g_pix = kinds[i].pix;
                g_name = kinds[i].name;
            }
            pthread_mutex_unlock(&g_mu);
            av_buffer_unref(&dev);
            mse_critf(MSE_LOG_INFO, "hwdec device %s", kinds[i].name);
            return;
        }
        mse_critf(MSE_LOG_INFO, "hwdec %s unavailable (%s)", kinds[i].name, av_err2str(err));
    }
    mse_critf(MSE_LOG_INFO, "hwdec none: software decode");
}

void mse_hwdev_adopt(AVBufferRef *dev, uint32_t decode_ops, uint64_t generation)
{
    AVBufferRef *old;
    pthread_mutex_lock(&g_mu);
    old = g_dev;
    g_dev = dev;
    g_pix = dev ? AV_PIX_FMT_VULKAN : AV_PIX_FMT_NONE;
    g_name = dev ? "vulkan" : "none";
    g_adopted = 1;
    g_vk_ops = dev ? decode_ops : 0;
    g_generation = dev ? generation : 0;
    g_mask_known = 0;
    g_failed_n = 0;
    pthread_mutex_unlock(&g_mu);
    av_buffer_unref(&old);
    if (dev)
        mse_critf(MSE_LOG_INFO, "hwdec device vulkan: the renderer's (decode ops 0x%x)", decode_ops);
    else
        mse_critf(MSE_LOG_INFO, "hwdec none: the renderer shares no decode device");
}

uint64_t mse_hwdev_shared_generation(void)
{
    uint64_t generation;
    pthread_mutex_lock(&g_mu);
    generation = g_generation;
    pthread_mutex_unlock(&g_mu);
    return generation;
}

AVBufferRef *mse_hwdev_ref(void)
{
    AVBufferRef *dev;
    pthread_once(&g_once, hwdev_init);
    pthread_mutex_lock(&g_mu);
    dev = g_dev ? av_buffer_ref(g_dev) : NULL;
    pthread_mutex_unlock(&g_mu);
    return dev;
}

AVBufferRef *mse_hwdev_get(void)
{
    AVBufferRef *dev;
    pthread_once(&g_once, hwdev_init);
    pthread_mutex_lock(&g_mu);
    dev = g_dev;
    pthread_mutex_unlock(&g_mu);
    return dev;
}

enum AVPixelFormat mse_hwdev_pix_fmt(void)
{
    enum AVPixelFormat pix;
    pthread_once(&g_once, hwdev_init);
    pthread_mutex_lock(&g_mu);
    pix = g_pix;
    pthread_mutex_unlock(&g_mu);
    return pix;
}

const char *mse_hwdev_name(void)
{
    const char *name;
    pthread_once(&g_once, hwdev_init);
    pthread_mutex_lock(&g_mu);
    name = g_name;
    pthread_mutex_unlock(&g_mu);
    return name;
}

static int cap_of(enum AVCodecID id)
{
    switch (id) {
    case AV_CODEC_ID_H264:
        return MSE_CAP_H264;
    case AV_CODEC_ID_HEVC:
        return MSE_CAP_HEVC;
    case AV_CODEC_ID_VP8:
        return MSE_CAP_VP8;
    case AV_CODEC_ID_VP9:
        return MSE_CAP_VP9;
    case AV_CODEC_ID_AV1:
        return MSE_CAP_AV1;
    default:
        return 0;
    }
}

/* Bit per MSE_CAP_* codec with a hardware caps row on the current device. */
static unsigned hw_mask(void)
{
    MseCapRow rows[MAX_ROWS];
    const AVBufferRef *seen;
    unsigned mask;
    int known, i, n;
    pthread_mutex_lock(&g_mu);
    known = g_mask_known;
    mask = g_mask;
    seen = g_dev;
    pthread_mutex_unlock(&g_mu);
    if (known)
        return mask;
    n = mse_hwdev_caps(rows, MAX_ROWS);
    mask = 0;
    for (i = 0; i < n; i++)
        mask |= 1u << rows[i].codec;
    pthread_mutex_lock(&g_mu);
    if (g_dev == seen) {
        g_mask = mask;
        g_mask_known = 1;
    }
    pthread_mutex_unlock(&g_mu);
    return mask;
}

int mse_hwdev_usable(enum AVCodecID id)
{
    int i, ok, cap = cap_of(id);
    if (!mse_hwdev_get() || !cap || !(hw_mask() & (1u << cap)))
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
    mse_critf(MSE_LOG_WARN, "hwdec %s rejected codec %s: software from now on", mse_hwdev_name(),
              avcodec_get_name(id));
}

enum AVPixelFormat mse_hwdev_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    const enum AVPixelFormat *p;
    if (mse_hwdev_usable(ctx->codec_id)) {
        enum AVPixelFormat pix = mse_hwdev_pix_fmt();
        for (p = fmts; *p != AV_PIX_FMT_NONE; p++) {
            if (*p == pix)
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
    uint32_t vk_op;
} k_hw_codecs[] = {
    { MSE_CAP_H264, "h264", 4, "VK_KHR_video_decode_h264", 0x1 },
    { MSE_CAP_HEVC, "hevc", 8, "VK_KHR_video_decode_h265", 0x2 },
    { MSE_CAP_VP9, "vp9", 10, "VK_KHR_video_decode_vp9", 0x8 },
    { MSE_CAP_AV1, "av1", 11, "VK_KHR_video_decode_av1", 0x4 },
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

static void vulkan_row(MseCapRow *row, int codec)
{
    row->codec = codec;
    row->max_bits = 8;
    row->chroma = MSE_CAP_420;
    row->max_w = 4096;
    row->max_h = 2304;
    row->max_fps = 0;
    row->hw = 1;
}

static int shared_caps(uint32_t ops, MseCapRow *rows, int cap)
{
    size_t k;
    int n = 0;
    for (k = 0; k < sizeof(k_hw_codecs) / sizeof(k_hw_codecs[0]) && n < cap; k++) {
        if ((ops & k_hw_codecs[k].vk_op) && has_hwaccel(k_hw_codecs[k].decoder, AV_HWDEVICE_TYPE_VULKAN))
            vulkan_row(&rows[n++], k_hw_codecs[k].cap);
    }
    return n;
}

static int cuda_caps(AVBufferRef *dev, MseCapRow *rows, int cap)
{
    void *cuda = dlopen("libcuda.so.1", RTLD_NOW | RTLD_LOCAL);
    void *cuvid = dlopen("libnvcuvid.so.1", RTLD_NOW | RTLD_LOCAL);
    /* AVCUDADeviceContext.cuda_ctx is its first member. */
    void *ctx = *(void **)((AVHWDeviceContext *)dev->data)->hwctx;
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

static int vulkan_caps(AVBufferRef *dev, MseCapRow *rows, int cap)
{
    const VkDeviceHead *d = (const VkDeviceHead *)((AVHWDeviceContext *)dev->data)->hwctx;
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
            vulkan_row(&rows[n++], k_hw_codecs[k].cap);
            break;
        }
    }
    free(ext);
    return n;
}

int mse_hwdev_caps(MseCapRow *rows, int cap)
{
    AVBufferRef *dev;
    enum AVPixelFormat pix;
    uint32_t ops;
    int adopted, n = 0;
    pthread_once(&g_once, hwdev_init);
    pthread_mutex_lock(&g_mu);
    dev = g_dev ? av_buffer_ref(g_dev) : NULL;
    pix = g_pix;
    ops = g_vk_ops;
    adopted = g_adopted;
    pthread_mutex_unlock(&g_mu);
    if (!dev)
        return 0;
    if (adopted)
        n = shared_caps(ops, rows, cap);
    else if (pix == AV_PIX_FMT_CUDA)
        n = cuda_caps(dev, rows, cap);
    else if (pix == AV_PIX_FMT_VULKAN)
        n = vulkan_caps(dev, rows, cap);
    av_buffer_unref(&dev);
    return n;
}
