#if defined(__linux__) && !defined(__ANDROID__)

/* Ubuntu 22.04's Vulkan headers still list the video extensions as provisional. */
#define VK_ENABLE_BETA_EXTENSIONS
#include "vpresent_impl.h"
#include "mse_ffmpeg.h"
#include "mse_hwdev.h"

#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_vulkan.h>
#include <libavutil/pixdesc.h>

#include <stdlib.h>

struct VpPicture {
    AVFrame *frame;
    VpPictureInfo info;
};

typedef struct {
    VpFrameSink sink;
    /* present_mu */
    int refused;
} Frames;

static int plane_kind(int format)
{
    switch (format) {
    case AV_PIX_FMT_YUV420P:
    case AV_PIX_FMT_YUVJ420P:
        return VP_PIC_I420;
    case AV_PIX_FMT_NV12:
        return VP_PIC_NV12;
    case AV_PIX_FMT_YUV420P10LE:
        return VP_PIC_I420P10;
    default:
        return 0;
    }
}

/* NULL for a picture the sink cannot draw: another device's, or not 4:2:0. */
static VpPicture *picture_of(const AVFrame *f, VpColor c, const char **why)
{
    VpPicture *pic;
    VpPictureInfo info = { 0 };
    int i;
    info.width = f->width;
    info.height = f->height;
    info.color = c;
    if (f->format == AV_PIX_FMT_VULKAN) {
        const AVHWFramesContext *fc = (const AVHWFramesContext *)f->hw_frames_ctx->data;
        const AVVulkanFramesContext *vfc = fc->hwctx;
        info.generation = mse_hwdev_vk_generation(fc->device_ref);
        if (!info.generation) {
            *why = "vulkan image of another device";
            return NULL;
        }
        if (vfc->format[0] != VK_FORMAT_G8_B8R8_2PLANE_420_UNORM &&
            vfc->format[0] != VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16) {
            *why = "vulkan image format";
            return NULL;
        }
        info.kind = VP_PIC_VULKAN;
        info.coded_width = fc->width;
        info.coded_height = fc->height;
        info.vk_format = vfc->format[0];
    } else {
        info.kind = plane_kind(f->format);
        if (!info.kind) {
            *why = av_get_pix_fmt_name((enum AVPixelFormat)f->format);
            return NULL;
        }
        for (i = 0; i < 3; i++) {
            info.data[i] = f->data[i];
            info.linesize[i] = f->linesize[i];
        }
    }
    pic = calloc(1, sizeof(*pic));
    if (!pic) {
        *why = "out of memory";
        return NULL;
    }
    pic->frame = av_frame_clone(f);
    if (!pic->frame) {
        free(pic);
        *why = "out of memory";
        return NULL;
    }
    pic->info = info;
    for (i = 0; i < 3 && info.kind != VP_PIC_VULKAN; i++)
        pic->info.data[i] = pic->frame->data[i];
    return pic;
}

static int frames_show(VPresenter *p, const AVFrame *f, VpColor c)
{
    Frames *fr = p->impl;
    const char *why = "";
    VpPicture *pic = picture_of(f, c, &why);
    if (!pic) {
        if (!fr->refused)
            mse_critf(MSE_LOG_WARN, "vpresent frames refused a picture (%s)", why ? why : "?");
        fr->refused = 1;
        return VP_FAIL;
    }
    fr->sink.on_picture(fr->sink.user, pic);
    return VP_OK;
}

static void frames_flush(VPresenter *p)
{
    Frames *fr = p->impl;
    if (fr->sink.on_clear)
        fr->sink.on_clear(fr->sink.user);
}

static int frames_zero_copy(VPresenter *p, int av_pix_fmt)
{
    (void)p;
    return av_pix_fmt == AV_PIX_FMT_VULKAN && mse_hwdev_shared_generation() != 0;
}

static void frames_destroy(VPresenter *p)
{
    free(p->impl);
}

static const VpOps k_frames_ops = {
    .kind = "frames",
    .show = frames_show,
    .flush = frames_flush,
    .detach = frames_flush,
    .zero_copy = frames_zero_copy,
    .destroy = frames_destroy,
};

VPresenter *vpresent_create_frames(const VpFrameSink *s)
{
    Frames *fr;
    VPresenter *p;
    VpSink base = { 0 };
    if (!s || !s->on_picture)
        return NULL;
    fr = calloc(1, sizeof(*fr));
    if (!fr)
        return NULL;
    fr->sink = *s;
    base.release = s->release;
    base.user = s->user;
    p = vp_alloc(&k_frames_ops, fr, &base, VP_MODE_GPU);
    if (!p) {
        free(fr);
        return NULL;
    }
    mse_critf(MSE_LOG_INFO, "vpresent frames created");
    return p;
}

void vp_picture_info(const VpPicture *pic, VpPictureInfo *out)
{
    *out = pic->info;
}

void vp_picture_vk_acquire(VpPicture *pic, uint64_t *image, int32_t *layout, uint64_t *semaphore,
                           uint64_t *wait_value)
{
    AVHWFramesContext *fc = (AVHWFramesContext *)pic->frame->hw_frames_ctx->data;
    AVVulkanFramesContext *vfc = fc->hwctx;
    AVVkFrame *vkf = (AVVkFrame *)pic->frame->data[0];
    vfc->lock_frame(fc, vkf);
    *image = (uint64_t)(uintptr_t)vkf->img[0];
    *layout = vkf->layout[0];
    *semaphore = (uint64_t)(uintptr_t)vkf->sem[0];
    *wait_value = vkf->sem_value[0];
}

void vp_picture_vk_release(VpPicture *pic, int submitted, int32_t layout)
{
    AVHWFramesContext *fc = (AVHWFramesContext *)pic->frame->hw_frames_ctx->data;
    AVVulkanFramesContext *vfc = fc->hwctx;
    AVVkFrame *vkf = (AVVkFrame *)pic->frame->data[0];
    if (submitted) {
        vkf->layout[0] = (VkImageLayout)layout;
        vkf->access[0] = VK_ACCESS_2_SHADER_READ_BIT;
        vkf->queue_family[0] = VK_QUEUE_FAMILY_IGNORED;
        vkf->sem_value[0]++;
    }
    vfc->unlock_frame(fc, vkf);
}

void vp_picture_free(VpPicture *pic)
{
    if (!pic)
        return;
    av_frame_free(&pic->frame);
    free(pic);
}

#endif
