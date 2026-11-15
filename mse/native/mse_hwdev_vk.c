#if defined(__linux__) && !defined(__ANDROID__)

/* Ubuntu 22.04's Vulkan headers still list the video extensions as provisional. */
#define VK_ENABLE_BETA_EXTENSIONS
#include "mse_hwdev.h"

#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vulkan.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "mse_ffmpeg.h"

#define MAX_FAMILIES 8

/* VkPhysicalDeviceVideoDecodeVP9FeaturesKHR: newer than the oldest headers this builds with. */
typedef struct {
    VkStructureType sType;
    void *pNext;
    VkBool32 videoDecodeVP9;
} Vp9Features;

#define VP9_FEATURES ((VkStructureType)1000514000)

typedef struct {
    MseVkDevice d;
    char **instance_extensions;
    char **device_extensions;
    VkPhysicalDeviceSynchronization2Features sync2;
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline;
    Vp9Features vp9;
    /* The decoder's own queues, one per family. */
    pthread_mutex_t family_mu[MAX_FAMILIES];
} SharedVk;

static int family_slot(const SharedVk *s, uint32_t family)
{
    int i;
    for (i = 0; i < s->d.family_count; i++) {
        if (s->d.families[i].index == family)
            return i;
    }
    return 0;
}

static void lock_queue(AVHWDeviceContext *ctx, uint32_t family, uint32_t index)
{
    SharedVk *s = ctx->user_opaque;
    if (family == s->d.render_family && index == s->d.render_index)
        s->d.lock_queue(s->d.user);
    else
        pthread_mutex_lock(&s->family_mu[family_slot(s, family)]);
}

static void unlock_queue(AVHWDeviceContext *ctx, uint32_t family, uint32_t index)
{
    SharedVk *s = ctx->user_opaque;
    if (family == s->d.render_family && index == s->d.render_index)
        s->d.unlock_queue(s->d.user);
    else
        pthread_mutex_unlock(&s->family_mu[family_slot(s, family)]);
}

static char **copy_strings(const char *const *v, int n)
{
    char **out = calloc(n > 0 ? (size_t)n : 1, sizeof(*out));
    int i;
    for (i = 0; out && i < n; i++) {
        out[i] = strdup(v[i]);
        if (!out[i])
            return out;
    }
    return out;
}

static int strings_ok(char **v, int n)
{
    int i;
    for (i = 0; v && i < n; i++) {
        if (!v[i])
            return 0;
    }
    return v != NULL;
}

static void free_strings(char **v, int n)
{
    int i;
    for (i = 0; v && i < n; i++)
        free(v[i]);
    free(v);
}

static void shared_free(AVHWDeviceContext *ctx)
{
    SharedVk *s = ctx->user_opaque;
    int i;
    for (i = 0; i < MAX_FAMILIES; i++)
        pthread_mutex_destroy(&s->family_mu[i]);
    free_strings(s->instance_extensions, s->d.instance_extension_count);
    free_strings(s->device_extensions, s->d.device_extension_count);
    if (s->d.release)
        s->d.release(s->d.user);
    free(s);
}

uint64_t mse_hwdev_vk_generation(const AVBufferRef *device_ref)
{
    const AVHWDeviceContext *ctx = device_ref ? (const AVHWDeviceContext *)device_ref->data : NULL;
    if (!ctx || ctx->type != AV_HWDEVICE_TYPE_VULKAN || ctx->free != shared_free)
        return 0;
    return ((const SharedVk *)ctx->user_opaque)->d.generation;
}

/* libav's context on the renderer's handles; NULL when libav refuses it. */
static AVBufferRef *shared_device(const MseVkDevice *d, uint32_t *decode_ops)
{
    AVBufferRef *ref;
    AVHWDeviceContext *ctx;
    AVVulkanDeviceContext *h;
    SharedVk *s;
    int i, err;
    *decode_ops = 0;
    if (d->family_count < 1 || d->family_count > MAX_FAMILIES) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan: %d queue families", d->family_count);
        if (d->release)
            d->release(d->user);
        return NULL;
    }
    s = calloc(1, sizeof(*s));
    ref = s ? av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_VULKAN) : NULL;
    if (!ref) {
        free(s);
        mse_critf(MSE_LOG_WARN, "hwdec vulkan: out of memory");
        if (d->release)
            d->release(d->user);
        return NULL;
    }
    s->d = *d;
    for (i = 0; i < MAX_FAMILIES; i++)
        pthread_mutex_init(&s->family_mu[i], NULL);
    s->instance_extensions = copy_strings(d->instance_extensions, d->instance_extension_count);
    s->device_extensions = copy_strings(d->device_extensions, d->device_extension_count);
    ctx = (AVHWDeviceContext *)ref->data;
    ctx->user_opaque = s;
    ctx->free = shared_free;
    if (!strings_ok(s->instance_extensions, d->instance_extension_count) ||
        !strings_ok(s->device_extensions, d->device_extension_count)) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan: out of memory");
        av_buffer_unref(&ref);
        return NULL;
    }

    h = ctx->hwctx;
    h->get_proc_addr = (PFN_vkGetInstanceProcAddr)d->get_instance_proc_addr;
    h->inst = (VkInstance)(uintptr_t)d->instance;
    h->phys_dev = (VkPhysicalDevice)(uintptr_t)d->physical_device;
    h->act_dev = (VkDevice)(uintptr_t)d->device;
    s->sync2 = (VkPhysicalDeviceSynchronization2Features){
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES,
        .synchronization2 = VK_TRUE,
    };
    s->timeline = (VkPhysicalDeviceTimelineSemaphoreFeatures){
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES,
        .pNext = &s->sync2,
        .timelineSemaphore = VK_TRUE,
    };
    s->vp9 = (Vp9Features){ .sType = VP9_FEATURES, .pNext = &s->timeline, .videoDecodeVP9 = VK_TRUE };
    h->device_features = (VkPhysicalDeviceFeatures2){
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = d->video_decode_vp9 ? (void *)&s->vp9 : (void *)&s->timeline,
    };
    h->enabled_inst_extensions = (const char *const *)s->instance_extensions;
    h->nb_enabled_inst_extensions = d->instance_extension_count;
    h->enabled_dev_extensions = (const char *const *)s->device_extensions;
    h->nb_enabled_dev_extensions = d->device_extension_count;
    for (i = 0; i < d->family_count; i++) {
        h->qf[i].idx = (int)d->families[i].index;
        h->qf[i].num = (int)d->families[i].count;
        h->qf[i].flags = (VkQueueFlagBits)d->families[i].flags;
        h->qf[i].video_caps = (VkVideoCodecOperationFlagBitsKHR)d->families[i].video_ops;
        if (d->families[i].flags & VK_QUEUE_VIDEO_DECODE_BIT_KHR)
            *decode_ops |= d->families[i].video_ops;
    }
    h->nb_qf = d->family_count;
#if FF_API_VULKAN_SYNC_QUEUES
    h->lock_queue = lock_queue;
    h->unlock_queue = unlock_queue;
#endif
    err = av_hwdevice_ctx_init(ref);
    if (err < 0) {
        mse_critf(MSE_LOG_WARN, "hwdec vulkan on the renderer's device failed (%s)", av_err2str(err));
        av_buffer_unref(&ref);
        *decode_ops = 0;
        return NULL;
    }
    return ref;
}

int mse_hwdev_use_vulkan(const MseVkDevice *d)
{
    const char *want = getenv("YMUSIC_HWDEC");
    AVBufferRef *dev = NULL;
    uint32_t ops = 0;
    uint64_t generation = 0;
    if (d && want && strcmp(want, "none") == 0) {
        mse_critf(MSE_LOG_INFO, "hwdec off (YMUSIC_HWDEC=none)");
        if (d->release)
            d->release(d->user);
        d = NULL;
    }
    if (d) {
        generation = d->generation;
        dev = shared_device(d, &ops);
    }
    mse_hwdev_adopt(dev, ops, dev ? generation : 0);
    return dev ? 0 : -1;
}

#endif
