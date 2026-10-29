#ifndef MSE_HWDEV_H
#define MSE_HWDEV_H

/*
 * Desktop Linux GPU decode: one process-wide device, CUDA (NVDEC) first, else Vulkan video.
 * Both drivers are dlopen'd by libav, so a machine without them just gets NULL (software).
 * YMUSIC_HWDEC=none|cuda|vulkan overrides the choice.
 */

#include <libavcodec/avcodec.h>

/* Borrowed; NULL = no usable device. */
AVBufferRef *mse_hwdev_get(void);
enum AVPixelFormat mse_hwdev_pix_fmt(void);
const char *mse_hwdev_name(void);

/* A codec the device turned down (e.g. AV1 on a Pascal GPU) stays software for the process. */
int mse_hwdev_usable(enum AVCodecID id);
void mse_hwdev_mark_failed(enum AVCodecID id);

/* Picks the device's pixel format unless the codec failed; otherwise the first software one. */
enum AVPixelFormat mse_hwdev_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts);

/* mse_caps rows for the device: CUDA asks NVDEC per codec and bit depth; Vulkan has no size query
   here, so its decode extensions count as 8-bit 4:2:0 up to 4096x2304. Returns the count. */
struct MseCapRow;
int mse_hwdev_caps(struct MseCapRow *rows, int cap);

#endif
