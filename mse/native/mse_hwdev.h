#ifndef MSE_HWDEV_H
#define MSE_HWDEV_H

/*
 * Desktop Linux GPU decode: one process-wide device. The renderer's own Vulkan device when the
 * app shares one (mse_hwdev_use_vulkan), else CUDA (NVDEC) first, then Vulkan video of its own.
 * The drivers are dlopen'd by libav, so a machine without them just gets NULL (software).
 * YMUSIC_HWDEC=none|cuda|vulkan overrides the choice (only none for a shared device).
 */

#include <libavcodec/avcodec.h>
#include <stdint.h>

typedef struct MseVkQueueFamily {
    uint32_t index;
    /* Queues 0..count-1 exist. */
    uint32_t count;
    /* VkQueueFlags the decoder may use the family for. */
    uint32_t flags;
    /* VkVideoCodecOperationFlagsKHR of a decode family. */
    uint32_t video_ops;
} MseVkQueueFamily;

/* The renderer's Vulkan device, for decoding on it. `release` runs once libav no longer uses the
   handles, also when the device is refused. A submission to render_family/render_index happens
   between lock_queue and unlock_queue. */
typedef struct MseVkDevice {
    void *get_instance_proc_addr;
    uint64_t instance, physical_device, device;
    const char *const *instance_extensions;
    int instance_extension_count;
    const char *const *device_extensions;
    int device_extension_count;
    /* timelineSemaphore and synchronization2 are enabled; videoDecodeVP9 when set. */
    int video_decode_vp9;
    /* Preferred first. */
    MseVkQueueFamily families[8];
    int family_count;
    uint32_t render_family, render_index;
    /* Tells the renderer's devices apart (vp_picture_info). */
    uint64_t generation;
    void (*lock_queue)(void *user);
    void (*unlock_queue)(void *user);
    void (*release)(void *user);
    void *user;
} MseVkDevice;

/* An app drawing pictures itself (vpresent_create_frames) calls this before decoding: hardware
   decode then runs on `d`, zero-copy; NULL = software only. Without a call the process opens a
   device of its own. A later call replaces it for decoders opened after. 0 when `d` is used.
   mse_hwdev_vk.c, which needs the Vulkan headers. */
int mse_hwdev_use_vulkan(const MseVkDevice *d);
/* The generation of the renderer's device `device_ref` is; 0 for any other device. */
uint64_t mse_hwdev_vk_generation(const AVBufferRef *device_ref);

/* Takes `dev` (the renderer's device; NULL = software) as the process-wide device. */
void mse_hwdev_adopt(AVBufferRef *dev, uint32_t decode_ops, uint64_t generation);
/* The renderer's device generation while decoding runs on it, else 0. */
uint64_t mse_hwdev_shared_generation(void);
/* New reference to the device; NULL = none. */
AVBufferRef *mse_hwdev_ref(void);

/* Borrowed; NULL = no usable device. */
AVBufferRef *mse_hwdev_get(void);
enum AVPixelFormat mse_hwdev_pix_fmt(void);
const char *mse_hwdev_name(void);

/* A codec without a hardware caps row, or one the device turned down, stays software. */
int mse_hwdev_usable(enum AVCodecID id);
void mse_hwdev_mark_failed(enum AVCodecID id);

/* Picks the device's pixel format unless the codec failed; otherwise the first software one. */
enum AVPixelFormat mse_hwdev_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts);

/* mse_caps rows for the device: CUDA asks NVDEC per codec and bit depth; Vulkan has no size query
   here, so its decode extensions (the renderer's device: its decode queue's codec operations)
   count as 8-bit 4:2:0 up to 4096x2304. Returns the count. */
struct MseCapRow;
int mse_hwdev_caps(struct MseCapRow *rows, int cap);

#endif
