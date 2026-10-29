#ifndef YMUSIC_VPIPE_HW_H
#define YMUSIC_VPIPE_HW_H

/* A platform's hardware video decode for vpipe. */

#include <libavcodec/avcodec.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VpHw {
    const char *name;
    enum AVPixelFormat pix_fmt;
    /* Its frames reach a presenter that cannot draw them directly (downloaded to the CPU). */
    int readback_ok;
    /* Codec, profile, bit depth and device state allow it. */
    int (*usable)(const AVCodecParameters *par);
    /* The native decoder carrying the hwaccel; NULL when the codec decodes through a session. */
    const AVCodec *(*decoder)(enum AVCodecID id);
    /* New reference to the device; NULL = none. */
    AVBufferRef *(*device_ref)(void);
    enum AVPixelFormat (*get_format)(AVCodecContext *ctx, const enum AVPixelFormat *fmts);
    /* The device is gone: decoders reopen in software. NULL = never. */
    int (*lost)(void);
    /* vpipe gave up on the codec on this device; a backend may remember it past the stream. */
    void (*reject)(enum AVCodecID id);
    /* Optional session decoders (decoder() NULL for the codec), only for a VpParams.hw_session
       engine: frames come out as pix_fmt frames. open: NULL = no session. send/receive follow
       avcodec_send_packet / avcodec_receive_frame. */
    void *(*session_open)(const AVCodecParameters *par, AVRational pkt_timebase);
    int (*session_send)(void *s, const AVPacket *pkt);
    int (*session_receive)(void *s, AVFrame *out);
    void (*session_flush)(void *s);
    void (*session_close)(void *s);
} VpHw;

/* NULL when the platform has none or no device. */
const VpHw *vpipe_hw_platform(void);

#if defined(_WIN32)
const VpHw *vpipe_hw_d3d11(void);
#elif defined(__APPLE__)
const VpHw *vpipe_hw_vt(void);
#elif defined(__linux__) && !defined(__ANDROID__)
const VpHw *vpipe_hw_linux(void);
#endif

#ifdef __cplusplus
}
#endif

#endif
