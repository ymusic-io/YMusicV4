#ifdef __APPLE__

#include "mse_vt_blit.h"

#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>
#include <stdint.h>
#include <string.h>

int mse_vt_blit_fit(int src_w, int src_h, int hole_w, int hole_h, int *dw, int *dh)
{
    if (src_w <= 0 || src_h <= 0)
        return -1;
    *dw = src_w;
    *dh = src_h;
    /* 0 on a side = unconstrained, not "use native". */
    if (hole_w <= 0 && hole_h <= 0)
        return 0;
    if (hole_w <= 0) {
        if (src_h > hole_h) {
            *dh = hole_h;
            *dw = (int)((int64_t)src_w * hole_h / src_h);
        }
    } else if (hole_h <= 0) {
        if (src_w > hole_w) {
            *dw = hole_w;
            *dh = (int)((int64_t)src_h * hole_w / src_w);
        }
    } else if (src_w > hole_w || src_h > hole_h) {
        if ((int64_t)src_w * hole_h <= (int64_t)src_h * hole_w) {
            *dh = hole_h;
            *dw = (int)((int64_t)src_w * hole_h / src_h);
        } else {
            *dw = hole_w;
            *dh = (int)((int64_t)src_h * hole_w / src_w);
        }
    }
    if (*dw < 1)
        *dw = 1;
    if (*dh < 1)
        *dh = 1;
    return 0;
}

void mse_vt_blit_free(MseVtBlit *s)
{
    if (!s)
        return;
    if (s->xfer) {
        if (__builtin_available(macOS 10.8, iOS 16.0, *))
            VTPixelTransferSessionInvalidate((VTPixelTransferSessionRef)s->xfer);
        CFRelease(s->xfer);
        s->xfer = NULL;
    }
    if (s->dst) {
        CVPixelBufferRelease((CVPixelBufferRef)s->dst);
        s->dst = NULL;
    }
    s->dw = s->dh = 0;
}

int mse_vt_blit_size(void *cv_pixel_buffer, int hole_w, int hole_h, int *w, int *h)
{
    CVPixelBufferRef src;
    int src_w, src_h;
    if (!cv_pixel_buffer || !w || !h)
        return -1;
    src = (CVPixelBufferRef)cv_pixel_buffer;
    src_w = (int)CVPixelBufferGetWidth(src);
    src_h = (int)CVPixelBufferGetHeight(src);
    return mse_vt_blit_fit(src_w, src_h, hole_w, hole_h, w, h);
}

int mse_vt_blit_sw(MseVtBlit *s, unsigned cv_pix_fmt,
                   const uint8_t *const *data, const int *linesize,
                   int src_w, int src_h, uint8_t *out, int w, int h)
{
    CVPixelBufferRef src = NULL;
    void *addrs[3];
    size_t widths[3], heights[3], strides[3];
    size_t nplane;
    int i, r;
    if (!s || !data || !linesize || !out || src_w <= 0 || src_h <= 0)
        return -1;
    if (cv_pix_fmt == MSE_VT_BLIT_NV12) {
        nplane = 2;
        addrs[0] = (void *)data[0];
        addrs[1] = (void *)data[1];
        widths[0] = (size_t)src_w;
        widths[1] = (size_t)(src_w + 1) / 2;
        heights[0] = (size_t)src_h;
        heights[1] = (size_t)(src_h + 1) / 2;
        strides[0] = (size_t)linesize[0];
        strides[1] = (size_t)linesize[1];
    } else if (cv_pix_fmt == MSE_VT_BLIT_YUV420) {
        nplane = 3;
        addrs[0] = (void *)data[0];
        addrs[1] = (void *)data[1];
        addrs[2] = (void *)data[2];
        widths[0] = (size_t)src_w;
        widths[1] = widths[2] = (size_t)(src_w + 1) / 2;
        heights[0] = (size_t)src_h;
        heights[1] = heights[2] = (size_t)(src_h + 1) / 2;
        strides[0] = (size_t)linesize[0];
        strides[1] = (size_t)linesize[1];
        strides[2] = (size_t)linesize[2];
    } else {
        return -1;
    }
    for (i = 0; i < (int)nplane; i++) {
        if (!addrs[i] || strides[i] == 0)
            return -1;
    }
    if (CVPixelBufferCreateWithPlanarBytes(kCFAllocatorDefault, (size_t)src_w, (size_t)src_h,
                                           (OSType)cv_pix_fmt, NULL, 0, nplane, addrs, widths,
                                           heights, strides, NULL, NULL, NULL, &src) != kCVReturnSuccess ||
        !src)
        return -1;
    r = mse_vt_blit_copy(s, src, out, w, h);
    CVPixelBufferRelease(src);
    return r;
}

API_AVAILABLE(macos(10.8), ios(16.0))
static int xfer_copy(MseVtBlit *s, CVPixelBufferRef src, uint8_t *out, int w, int h)
{
    CVPixelBufferRef dst;
    VTPixelTransferSessionRef xfer;
    uint8_t *base;
    size_t stride;
    int y;
    CVBufferSetAttachment(src, kCVImageBufferYCbCrMatrixKey,
                          kCVImageBufferYCbCrMatrix_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCVImageBufferColorPrimariesKey,
                          kCVImageBufferColorPrimaries_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCVImageBufferTransferFunctionKey,
                          kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCMFormatDescriptionExtension_FullRangeVideo,
                          kCFBooleanFalse, kCVAttachmentMode_ShouldPropagate);
    if (!s->xfer) {
        if (VTPixelTransferSessionCreate(NULL, &xfer) != noErr)
            return -1;
        s->xfer = xfer;
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationColorPrimaries,
                             kCVImageBufferColorPrimaries_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationTransferFunction,
                             kCVImageBufferTransferFunction_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationYCbCrMatrix,
                             kCVImageBufferYCbCrMatrix_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_RealTime, kCFBooleanTrue);
    }
    xfer = (VTPixelTransferSessionRef)s->xfer;
    if (!s->dst || s->dw != w || s->dh != h) {
        if (s->dst)
            CVPixelBufferRelease((CVPixelBufferRef)s->dst);
        s->dst = NULL;
        if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                                kCVPixelFormatType_32BGRA, NULL, &dst) != kCVReturnSuccess)
            return -1;
        s->dst = dst;
        s->dw = w;
        s->dh = h;
        CVBufferSetAttachment(dst, kCVImageBufferColorPrimariesKey,
                              kCVImageBufferColorPrimaries_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
        CVBufferSetAttachment(dst, kCVImageBufferTransferFunctionKey,
                              kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
        CVBufferSetAttachment(dst, kCVImageBufferYCbCrMatrixKey,
                              kCVImageBufferYCbCrMatrix_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    }
    dst = (CVPixelBufferRef)s->dst;
    if (VTPixelTransferSessionTransferImage(xfer, src, dst) != noErr)
        return -1;
    if (CVPixelBufferLockBaseAddress(dst, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
        return -1;
    base = CVPixelBufferGetBaseAddress(dst);
    stride = CVPixelBufferGetBytesPerRow(dst);
    if (stride == (size_t)w * 4)
        memcpy(out, base, (size_t)w * (size_t)h * 4);
    else
        for (y = 0; y < h; y++)
            memcpy(out + (size_t)y * (size_t)w * 4, base + (size_t)y * stride, (size_t)w * 4);
    CVPixelBufferUnlockBaseAddress(dst, kCVPixelBufferLock_ReadOnly);
    return 0;
}

int mse_vt_blit_copy(MseVtBlit *s, void *cv_pixel_buffer, uint8_t *out, int w, int h)
{
    if (!s || !cv_pixel_buffer || !out || w <= 0 || h <= 0)
        return -1;
    /* No VTPixelTransferSession before iOS 16: callers take their CPU path. */
    if (__builtin_available(macOS 10.8, iOS 16.0, *))
        return xfer_copy(s, (CVPixelBufferRef)cv_pixel_buffer, out, w, h);
    return -1;
}

#endif
