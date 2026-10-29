#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <VideoToolbox/VideoToolbox.h>
#import <os/log.h>
#import <stdint.h>
#import <stdlib.h>
#import <string.h>

struct MseVtDec {
    VTDecompressionSessionRef session;
    CMVideoFormatDescriptionRef fmt;
    int nal_len;
    int w, h;
    int logged;
    OSStatus last_st;
    CVPixelBufferRef out;
    uint8_t *pkt;
    size_t pkt_n;
};

static os_log_t vt_oslog(void)
{
    static os_log_t log;
    static int once;
    if (!once) {
        once = 1;
        log = os_log_create("com.ymusic.mse", "v");
    }
    return log;
}

static void vt_dec_cb(void *refcon, void *src, OSStatus status, VTDecodeInfoFlags flags,
                      CVImageBufferRef img, CMTime pts, CMTime dur)
{
    struct MseVtDec *d = refcon;
    (void)src;
    (void)flags;
    (void)pts;
    (void)dur;
    if (!d)
        return;
    d->last_st = status;
    if (status != noErr || !img) {
        if (status != noErr)
            os_log(vt_oslog(), "mse-v vt-dec cb status=%d", (int)status);
        return;
    }
    if (d->out)
        CVPixelBufferRelease(d->out);
    d->out = CVPixelBufferRetain((CVPixelBufferRef)img);
}

static CFMutableDictionaryRef vt_dst_attrs(int w, int h)
{
    int32_t pix = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
    int32_t ww = w, hh = h;
    CFNumberRef pixn, wn, hn;
    CFMutableDictionaryRef iosurf, dst;
    pixn = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &pix);
    wn = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &ww);
    hn = CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, &hh);
    iosurf = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
                                       &kCFTypeDictionaryKeyCallBacks,
                                       &kCFTypeDictionaryValueCallBacks);
    dst = CFDictionaryCreateMutable(kCFAllocatorDefault, 8,
                                    &kCFTypeDictionaryKeyCallBacks,
                                    &kCFTypeDictionaryValueCallBacks);
    CFDictionarySetValue(dst, kCVPixelBufferPixelFormatTypeKey, pixn);
    CFDictionarySetValue(dst, kCVPixelBufferWidthKey, wn);
    CFDictionarySetValue(dst, kCVPixelBufferHeightKey, hn);
    CFDictionarySetValue(dst, kCVPixelBufferIOSurfacePropertiesKey, iosurf);
    CFDictionarySetValue(dst, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue);
    CFRelease(pixn);
    CFRelease(wn);
    CFRelease(hn);
    CFRelease(iosurf);
    return dst;
}

static int vt_create_session(struct MseVtDec *d)
{
    CFMutableDictionaryRef spec, dst;
    VTDecompressionOutputCallbackRecord cb;
    OSStatus st;
    spec = CFDictionaryCreateMutable(kCFAllocatorDefault, 1,
                                     &kCFTypeDictionaryKeyCallBacks,
                                     &kCFTypeDictionaryValueCallBacks);
    /* Enable HW; do not Require it. Simulator / Intel use Apple software VT. iOS before 17 has no
       such key and decodes in hardware where it can. */
    if (__builtin_available(macOS 10.9, iOS 17.0, *))
        CFDictionarySetValue(spec, kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder,
                             kCFBooleanTrue);
    dst = vt_dst_attrs(d->w, d->h);
    cb.decompressionOutputCallback = vt_dec_cb;
    cb.decompressionOutputRefCon = d;
    st = VTDecompressionSessionCreate(kCFAllocatorDefault, d->fmt, spec, dst, &cb, &d->session);
    CFRelease(dst);
    CFRelease(spec);
    if (st != noErr || !d->session) {
        os_log(vt_oslog(), "mse-v vt-dec create status=%d", (int)st);
        mse_critf(MSE_LOG_WARN, "mse-v vt-dec create status=%d", (int)st);
        return -1;
    }
    return 0;
}

static int avcc_sets(const uint8_t *p, int n, const uint8_t **sets, size_t *setn, int *nset, int *nal_len)
{
    int off, nsps, npps, i, k = 0;
    if (!p || n < 7 || p[0] != 1)
        return -1;
    *nal_len = (p[4] & 3) + 1;
    nsps = p[5] & 0x1F;
    off = 6;
    for (i = 0; i < nsps; i++) {
        int len;
        if (off + 2 > n)
            return -1;
        len = (p[off] << 8) | p[off + 1];
        off += 2;
        if (len < 1 || off + len > n || k >= 8)
            return -1;
        sets[k] = p + off;
        setn[k] = (size_t)len;
        k++;
        off += len;
    }
    if (off >= n)
        return -1;
    npps = p[off++];
    for (i = 0; i < npps; i++) {
        int len;
        if (off + 2 > n)
            return -1;
        len = (p[off] << 8) | p[off + 1];
        off += 2;
        if (len < 1 || off + len > n || k >= 8)
            return -1;
        sets[k] = p + off;
        setn[k] = (size_t)len;
        k++;
        off += len;
    }
    *nset = k;
    return 0;
}

void *mse_vt_dec_open(const uint8_t *avcc, int avcc_n, int w, int h)
{
    struct MseVtDec *d;
    const uint8_t *sets[8];
    size_t setn[8];
    int nset = 0, nal_len = 4;
    OSStatus st;
    if (!avcc || avcc_n < 7 || w <= 0 || h <= 0)
        return NULL;
    if (avcc_sets(avcc, avcc_n, sets, setn, &nset, &nal_len) < 0 || nset < 2)
        return NULL;
    d = calloc(1, sizeof(*d));
    if (!d)
        return NULL;
    d->nal_len = nal_len;
    d->w = w;
    d->h = h;
    st = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, (size_t)nset, sets, setn, nal_len, &d->fmt);
    if (st != noErr || !d->fmt) {
        free(d);
        return NULL;
    }
    /* AVSampleBufferDisplayLayer needs IOSurface-backed NV12. NULL dest
       lets software VT emit planar 420 with no IOSurface → enqueue
       succeeds, picture stays black (61 fps, empty view). */
    if (vt_create_session(d) < 0) {
        CFRelease(d->fmt);
        free(d);
        return NULL;
    }
    return d;
}

void *mse_vt_dec_open_vp9(const uint8_t *vpcc, int vpcc_n, int w, int h)
{
    struct MseVtDec *d;
    CFMutableDictionaryRef atoms = NULL, ext = NULL;
    CFDataRef data = NULL;
    uint8_t syn[12];
    OSStatus st;
    if (w <= 0 || h <= 0)
        return NULL;
    if (!vpcc || vpcc_n < 8) {
        /* FullBox vpcC: version=1, profile 0, 8-bit 4:2:0 BT.709. */
        memset(syn, 0, sizeof(syn));
        syn[0] = 1;
        syn[4] = 0;
        syn[5] = 0;
        syn[6] = (8 << 4) | (1 << 1);
        syn[7] = 1;
        syn[8] = 1;
        syn[9] = 1;
        vpcc = syn;
        vpcc_n = 12;
    }
    /* Without the supplemental decoder (iOS before 26.2) the session may not open: VP9 goes software. */
    if (__builtin_available(macOS 11.0, iOS 26.2, *)) {
        VTRegisterSupplementalVideoDecoderIfAvailable(kCMVideoCodecType_VP9);
        mse_critf(MSE_LOG_INFO, "mse-v vt-dec vp9 hw=%d",
                 (int)VTIsHardwareDecodeSupported(kCMVideoCodecType_VP9));
    }
    d = calloc(1, sizeof(*d));
    if (!d)
        return NULL;
    d->w = w;
    d->h = h;
    data = CFDataCreate(kCFAllocatorDefault, vpcc, vpcc_n);
    atoms = CFDictionaryCreateMutable(kCFAllocatorDefault, 1,
                                      &kCFTypeDictionaryKeyCallBacks,
                                      &kCFTypeDictionaryValueCallBacks);
    ext = CFDictionaryCreateMutable(kCFAllocatorDefault, 1,
                                    &kCFTypeDictionaryKeyCallBacks,
                                    &kCFTypeDictionaryValueCallBacks);
    if (!data || !atoms || !ext) {
        if (data)
            CFRelease(data);
        if (atoms)
            CFRelease(atoms);
        if (ext)
            CFRelease(ext);
        free(d);
        return NULL;
    }
    CFDictionarySetValue(atoms, CFSTR("vpcC"), data);
    CFDictionarySetValue(ext, kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms, atoms);
    st = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_VP9, w, h, ext, &d->fmt);
    CFRelease(data);
    CFRelease(atoms);
    CFRelease(ext);
    if (st != noErr || !d->fmt) {
        os_log(vt_oslog(), "mse-v vt-dec vp9 fmt status=%d", (int)st);
        mse_critf(MSE_LOG_WARN, "mse-v vt-dec vp9 fmt status=%d", (int)st);
        free(d);
        return NULL;
    }
    if (vt_create_session(d) < 0) {
        CFRelease(d->fmt);
        free(d);
        return NULL;
    }
    os_log(vt_oslog(), "mse-v vt-dec vp9 %dx%d", w, h);
    return d;
}

void mse_vt_dec_close(void *dec)
{
    struct MseVtDec *d = dec;
    if (!d)
        return;
    if (d->session) {
        VTDecompressionSessionInvalidate(d->session);
        CFRelease(d->session);
    }
    if (d->fmt)
        CFRelease(d->fmt);
    if (d->out)
        CVPixelBufferRelease(d->out);
    free(d->pkt);
    free(d);
}

void mse_vt_dec_flush(void *dec)
{
    struct MseVtDec *d = dec;
    if (!d || !d->session)
        return;
    VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    if (d->out) {
        CVPixelBufferRelease(d->out);
        d->out = NULL;
    }
}

int mse_vt_dec_send(void *dec, const uint8_t *data, int n, int output)
{
    struct MseVtDec *d = dec;
    CMBlockBufferRef bb = NULL;
    CMSampleBufferRef sbuf = NULL;
    OSStatus st;
    size_t sz;
    VTDecodeFrameFlags flags = output ? 0 : kVTDecodeFrame_DoNotOutputFrame;
    if (!d || !d->session || !data || n <= 0)
        return -1;
    if (!d->logged) {
        d->logged = 1;
        os_log(vt_oslog(), "mse-v vt-dec pkt n=%d b=%02x%02x%02x%02x nal_len=%d",
               n, data[0], data[1], data[2], data[3], d->nal_len);
    }
    if ((size_t)n > d->pkt_n) {
        uint8_t *p = realloc(d->pkt, (size_t)n);
        if (!p)
            return -1;
        d->pkt = p;
        d->pkt_n = (size_t)n;
    }
    memcpy(d->pkt, data, (size_t)n);
    st = CMBlockBufferCreateWithMemoryBlock(kCFAllocatorDefault, d->pkt, (size_t)n,
                                            kCFAllocatorNull, NULL, 0, (size_t)n, 0, &bb);
    if (st != noErr || !bb)
        return -1;
    sz = (size_t)n;
    st = CMSampleBufferCreateReady(kCFAllocatorDefault, bb, d->fmt, 1, 0, NULL, 1, &sz, &sbuf);
    CFRelease(bb);
    if (st != noErr || !sbuf)
        return -1;
    d->last_st = noErr;
    st = VTDecompressionSessionDecodeFrame(d->session, sbuf, flags, NULL, NULL);
    CFRelease(sbuf);
    if (st != noErr)
        return -1;
    VTDecompressionSessionWaitForAsynchronousFrames(d->session);
    if (output && d->last_st != noErr)
        return -1;
    return 0;
}

int mse_vt_dec_recv(void *dec, void **cvpb, int *w, int *h)
{
    struct MseVtDec *d = dec;
    if (!d || !cvpb || !w || !h)
        return 0;
    if (!d->out)
        return 0;
    if (d->logged == 1) {
        OSType fmt = CVPixelBufferGetPixelFormatType(d->out);
        d->logged = 2;
        os_log(vt_oslog(), "mse-v vt-dec pixfmt=%c%c%c%c iosurface=%d %dx%d",
               (int)((fmt >> 24) & 0xFF), (int)((fmt >> 16) & 0xFF),
               (int)((fmt >> 8) & 0xFF), (int)(fmt & 0xFF),
               CVPixelBufferGetIOSurface(d->out) ? 1 : 0,
               (int)CVPixelBufferGetWidth(d->out), (int)CVPixelBufferGetHeight(d->out));
    }
    *cvpb = d->out;
    d->out = NULL;
    *w = (int)CVPixelBufferGetWidth((CVPixelBufferRef)*cvpb);
    *h = (int)CVPixelBufferGetHeight((CVPixelBufferRef)*cvpb);
    return 1;
}

void mse_vt_pb_release(void *cvpb)
{
    if (cvpb)
        CVPixelBufferRelease((CVPixelBufferRef)cvpb);
}
#endif
