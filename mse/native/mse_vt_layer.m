#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_vt_blit.h"
#import "mse_ffmpeg.h"
#import <TargetConditionals.h>
#import <AVFoundation/AVFoundation.h>
#import <CoreGraphics/CoreGraphics.h>
#import <QuartzCore/QuartzCore.h>
#import <CoreImage/CoreImage.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>
#import <os/log.h>
#import <limits.h>
#import <stdint.h>
#import <stdlib.h>
#import <string.h>

static CVPixelBufferRef mse_still_pb;
static MseVtBlit mse_still_blit;
/* Set when vt-show skipped because the app was backgrounded; cleared on a
   successful present. Last-still blit can still succeed while the layer is frozen. */
static int vt_missed_bg;
static CALayer *vt_hold_layer;

static void mse_vt_drop_hold(void)
{
    [vt_hold_layer removeFromSuperlayer];
    vt_hold_layer = nil;
}

static void mse_keep_still(CVPixelBufferRef pb)
{
    if (mse_still_pb == pb)
        return;
    if (mse_still_pb)
        CFRelease(mse_still_pb);
    mse_still_pb = pb;
    if (pb)
        CFRetain(pb);
}

static int mse_rgb_mean(const uint8_t *p, int n)
{
    long long s = 0;
    int i, pix;
    pix = n / 4;
    if (pix <= 0)
        return 0;
    for (i = 0; i < pix; i++)
        s += (int)p[i * 4] + p[i * 4 + 1] + p[i * 4 + 2];
    return (int)(s / ((long long)pix * 3));
}

static int mse_cg_copy(CVPixelBufferRef pb, uint8_t *dst, int w, int h)
{
    CGImageRef cg = NULL;
    CGContextRef ctx;
    CGColorSpaceRef cs;
    if (!pb || !dst || w <= 0 || h <= 0)
        return -1;
    if (VTCreateCGImageFromCVPixelBuffer(pb, NULL, &cg) != noErr || !cg)
        return -1;
    cs = CGColorSpaceCreateDeviceRGB();
    ctx = CGBitmapContextCreate(
        dst, (size_t)w, (size_t)h, 8, (size_t)w * 4, cs,
        kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little);
    if (!ctx) {
        CGImageRelease(cg);
        CGColorSpaceRelease(cs);
        return -1;
    }
    CGContextSetBlendMode(ctx, kCGBlendModeCopy);
    CGContextDrawImage(ctx, CGRectMake(0, 0, w, h), cg);
    CGContextRelease(ctx);
    CGImageRelease(cg);
    CGColorSpaceRelease(cs);
    return 0;
}

static int mse_ci_copy(CVPixelBufferRef pb, uint8_t *dst, int w, int h)
{
    CIImage *ci;
    CIContext *ctx;
    CGColorSpaceRef cs;
    if (!pb || !dst || w <= 0 || h <= 0)
        return -1;
    ci = [CIImage imageWithCVPixelBuffer:pb];
    if (!ci)
        return -1;
    ctx = [CIContext contextWithOptions:nil];
    if (!ctx)
        return -1;
    cs = CGColorSpaceCreateDeviceRGB();
    if (!cs)
        return -1;
    [ctx render:ci
       toBitmap:dst
       rowBytes:(size_t)w * 4
         bounds:CGRectMake(0, 0, w, h)
         format:kCIFormatBGRA8
     colorSpace:cs];
    CGColorSpaceRelease(cs);
    return 0;
}

static int mse_copy_try(CVPixelBufferRef pb, uint8_t *dst, int w, int h, const char **how)
{
    OSType fmt;
    if (!pb || !dst)
        return -1;
    fmt = CVPixelBufferGetPixelFormatType(pb);
    if (mse_vt_blit_copy(&mse_still_blit, pb, dst, w, h) == 0) {
        *how = "xfer";
        return 0;
    }
    if (mse_ci_copy(pb, dst, w, h) == 0) {
        *how = "ci";
        return 0;
    }
    if (mse_cg_copy(pb, dst, w, h) == 0) {
        *how = "cg";
        return 0;
    }
    mse_critf(MSE_LOG_WARN, "mse-v still fail fmt=%c%c%c%c %dx%d",
             (char)(fmt >> 24), (char)(fmt >> 16), (char)(fmt >> 8), (char)fmt, w, h);
    return -1;
}

int mse_vt_copy_last(uint8_t *dst, int cap, int *w, int *h)
{
    CVPixelBufferRef pb;
    int tw, th, n, mean;
    const char *how = "none";
    if (!mse_still_pb)
        return 0;
    pb = mse_still_pb;
    CFRetain(pb);
    tw = (int)CVPixelBufferGetWidth(pb);
    th = (int)CVPixelBufferGetHeight(pb);
    if (tw <= 0 || th <= 0) {
        CFRelease(pb);
        return 0;
    }
    if ((uint64_t)tw * (uint64_t)th > (uint64_t)(INT_MAX / 4)) {
        CFRelease(pb);
        return 0;
    }
    n = tw * th * 4;
    if (w)
        *w = tw;
    if (h)
        *h = th;
    if (!dst) {
        CFRelease(pb);
        return n;
    }
    if (cap < n) {
        CFRelease(pb);
        return 0;
    }
    if (mse_copy_try(pb, dst, tw, th, &how) != 0) {
        CFRelease(pb);
        return 0;
    }
    mean = mse_rgb_mean(dst, n);
    mse_logf("mse-v still %s %dx%d mean=%d\n", how, tw, th, mean);
    if (mean < 8) {
        mse_critf(MSE_LOG_WARN, "mse-v still black, drop");
        CFRelease(pb);
        return 0;
    }
    CFRelease(pb);
    return n;
}

int mse_vt_picture_ok(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (p) {
        layer = (__bridge AVSampleBufferDisplayLayer *)p;
        if (layer.status == AVQueuedSampleBufferRenderingStatusFailed)
            return 0;
    }
    /* A CPU blit of the last still can succeed while the display is frozen.
       Live means we presented after the last background skip. */
    return !vt_missed_bg;
}

void mse_vt_detach(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
    mse_vt_drop_hold();
}

int mse_vt_show(void *p, void *cv_pixel_buffer, int w, int h)
{
    AVSampleBufferDisplayLayer *layer;
    CVPixelBufferRef pb = (CVPixelBufferRef)cv_pixel_buffer;
    CMVideoFormatDescriptionRef fmt = NULL;
    CMSampleBufferRef sbuf = NULL;
    CMSampleTimingInfo timing = kCMTimingInfoInvalid;
    OSStatus st;
    CFArrayRef atts;
    if (!p || !pb)
        return -1;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    /* Keep enqueueing while backgrounded: AVPictureInPictureController shows this
       same layer. Skipping here froze PiP on the last in-app frame and set
       vt_missed_bg, which made recover-on-foreground intermittent black. */
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        NSError *err = layer.error;
        os_log(os_log_create("com.ymusic.mse", "v"),
               "mse-v vt-show layer-failed %{public}s",
               err.localizedDescription.UTF8String ?: "?");
        [layer flush];
    }
    st = CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pb, &fmt);
    if (st != noErr || !fmt)
        return -1;
    st = CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, pb, fmt, &timing, &sbuf);
    CFRelease(fmt);
    if (st != noErr || !sbuf)
        return -1;
    atts = CMSampleBufferGetSampleAttachmentsArray(sbuf, YES);
    if (atts && CFArrayGetCount(atts) > 0) {
        CFMutableDictionaryRef d = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(atts, 0);
        CFDictionarySetValue(d, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
    }
    if (@available(iOS 17.2, macOS 14.0, *)) {
        AVSampleBufferVideoRenderer *r = layer.sampleBufferRenderer;
        if (r)
            [r enqueueSampleBuffer:sbuf];
        else
            [layer enqueueSampleBuffer:sbuf];
    } else {
        [layer enqueueSampleBuffer:sbuf];
    }
    CFRelease(sbuf);
    mse_keep_still(pb);
    vt_missed_bg = 0;
    mse_vt_drop_hold();
    {
        static int shown;
        if (shown < 3) {
            shown++;
            mse_critf(MSE_LOG_INFO, "mse-v vt-show %dx%d layer=%.0fx%.0f status=%d",
                     w, h, layer.bounds.size.width, layer.bounds.size.height, (int)layer.status);
        }
    }
    return 0;
}

int mse_vt_show_bgra(void *p, const uint8_t *bgra, int w, int h)
{
    CVPixelBufferRef pb = NULL;
    uint8_t *dst;
    size_t stride;
    int y, r;
    if (!p || !bgra || w <= 0 || h <= 0)
        return -1;
    if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                            kCVPixelFormatType_32BGRA, NULL, &pb) != kCVReturnSuccess ||
        !pb)
        return -1;
    CVPixelBufferLockBaseAddress(pb, 0);
    dst = CVPixelBufferGetBaseAddress(pb);
    stride = CVPixelBufferGetBytesPerRow(pb);
    if (!dst) {
        CVPixelBufferUnlockBaseAddress(pb, 0);
        CVPixelBufferRelease(pb);
        return -1;
    }
    for (y = 0; y < h; y++)
        memcpy(dst + (size_t)y * stride, bgra + (size_t)y * (size_t)w * 4, (size_t)w * 4);
    CVPixelBufferUnlockBaseAddress(pb, 0);
    r = mse_vt_show(p, pb, w, h);
    CVPixelBufferRelease(pb);
    return r;
}

int mse_vt_show_yuv(void *p, unsigned cv_pix_fmt, const uint8_t *const *data,
                    const int *linesize, int w, int h, int matrix)
{
    CVPixelBufferRef pb = NULL;
    CFStringRef ycbcr, primaries;
    size_t nplane, i, y;
    int r;
    if (!p || !data || !linesize || w <= 0 || h <= 0)
        return -1;
    if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                            (OSType)cv_pix_fmt, NULL, &pb) != kCVReturnSuccess ||
        !pb)
        return -1;
    switch (matrix) {
    case MSE_VT_MATRIX_601:
        ycbcr = kCVImageBufferYCbCrMatrix_ITU_R_601_4;
        primaries = kCVImageBufferColorPrimaries_SMPTE_C;
        break;
    case MSE_VT_MATRIX_2020:
        ycbcr = kCVImageBufferYCbCrMatrix_ITU_R_2020;
        primaries = kCVImageBufferColorPrimaries_ITU_R_2020;
        break;
    default:
        ycbcr = kCVImageBufferYCbCrMatrix_ITU_R_709_2;
        primaries = kCVImageBufferColorPrimaries_ITU_R_709_2;
        break;
    }
    CVBufferSetAttachment(pb, kCVImageBufferYCbCrMatrixKey, ycbcr,
                          kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pb, kCVImageBufferColorPrimariesKey, primaries,
                          kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pb, kCVImageBufferTransferFunctionKey,
                          kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVPixelBufferLockBaseAddress(pb, 0);
    nplane = CVPixelBufferGetPlaneCount(pb);
    if (nplane < 2 || nplane > 3) {
        CVPixelBufferUnlockBaseAddress(pb, 0);
        CVPixelBufferRelease(pb);
        return -1;
    }
    for (i = 0; i < nplane; i++) {
        uint8_t *dst = CVPixelBufferGetBaseAddressOfPlane(pb, i);
        size_t dst_str = CVPixelBufferGetBytesPerRowOfPlane(pb, i);
        size_t rows = CVPixelBufferGetHeightOfPlane(pb, i);
        size_t pw = CVPixelBufferGetWidthOfPlane(pb, i);
        const uint8_t *src = data[i];
        int src_str = linesize[i];
        size_t rowb, bpp;
        if (!dst || !src || src_str <= 0) {
            CVPixelBufferUnlockBaseAddress(pb, 0);
            CVPixelBufferRelease(pb);
            return -1;
        }
        /* NV12 chroma plane is 2 bytes per sample; I420 planes are 1. */
        bpp = (nplane == 2 && i == 1) ? 2 : 1;
        rowb = pw * bpp;
        if (rowb > (size_t)src_str)
            rowb = (size_t)src_str;
        if (rowb > dst_str)
            rowb = dst_str;
        for (y = 0; y < rows; y++)
            memcpy(dst + y * dst_str, src + y * (size_t)src_str, rowb);
    }
    CVPixelBufferUnlockBaseAddress(pb, 0);
    r = mse_vt_show(p, pb, w, h);
    CVPixelBufferRelease(pb);
    return r;
}

void mse_vt_flush(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
    vt_missed_bg = 0;
    mse_vt_drop_hold();
}

void mse_vt_resume_presents(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    vt_missed_bg = 0;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed)
        [layer flush];
}

void mse_vt_hold_last(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    CALayer *parent;
    CIImage *ci;
    CIContext *ctx;
    CGImageRef cg;
    if (!p || !mse_still_pb)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    parent = layer.superlayer;
    if (!parent)
        return;
    ci = [CIImage imageWithCVPixelBuffer:mse_still_pb];
    if (!ci)
        return;
    ctx = [CIContext contextWithOptions:nil];
    if (!ctx)
        return;
    cg = [ctx createCGImage:ci fromRect:[ci extent]];
    if (!cg)
        return;
    mse_vt_drop_hold();
    vt_hold_layer = [CALayer layer];
    vt_hold_layer.frame = layer.frame;
    vt_hold_layer.contents = (__bridge id)cg;
    vt_hold_layer.contentsGravity = kCAGravityResize;
    [parent insertSublayer:vt_hold_layer above:layer];
    CGImageRelease(cg);
    os_log(os_log_create("com.ymusic.mse", "v"), "mse-v hold-last still pinned");
}
#endif
