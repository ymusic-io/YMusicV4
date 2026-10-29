#ifndef YMUSIC_VPIPE_H
#define YMUSIC_VPIPE_H

/*
 * Video decode pipeline shared by the desktop engines: picks the decoder and hardware backend for a
 * stream and the presenter it feeds, falls back to software when the hardware turns the stream
 * down, and shows decoded frames on the presenter.
 *
 * Threads: open / close / flush / send / receive / new_stream / failure / describe are serialised
 * by the caller (the engine's decode thread, or another thread while that one is idle).
 * set_presenter, present, flush_picture and frames may run on any thread.
 */

#include <libavcodec/avcodec.h>

#include "vpresent.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VPipe VPipe;

/* The decoder was reopened mid-GOP: feed it from a keyframe. */
#define VP_RESYNC (-0x7650)
/* No decoder takes the stream (it is closed); the reason is in vpipe_failure. */
#define VP_DEAD (-0x7651)

typedef struct {
    const char *tag;          /* log tag: "mse-v", "file-av" */
    const void *engine;       /* presenter bind token */
    int held_frames;          /* decoded frames the engine keeps outside the decoder */
    int low_delay;            /* software decoders keep the engine's tuned thread counts */
    int sw_error_reopen;      /* a software decode error reopens the decoder (VP_RESYNC) */
} VpParams;

VPipe *vpipe_create(const VpParams *prm);
void vpipe_destroy(VPipe *vp);

/* 0 or VP_DEAD. Replaces an open decoder. */
int vpipe_open(VPipe *vp, const AVCodecParameters *par, AVRational pkt_timebase);
void vpipe_close(VPipe *vp);
int vpipe_is_open(const VPipe *vp);
/* Seek: drops queued packets and frames. */
void vpipe_flush(VPipe *vp);
/* NULL drains. Takes its own reference to pkt. 0 = consumed (queued when the decoder is full),
   VP_RESYNC, VP_DEAD, or the AVERROR of a packet the decoder rejected (logged, decoder kept). */
int vpipe_send(VPipe *vp, const AVPacket *pkt, int is_rap);
/* 0 = a frame, AVERROR(EAGAIN), AVERROR_EOF, VP_RESYNC, VP_DEAD, or a decode AVERROR. */
int vpipe_receive(VPipe *vp, AVFrame *out);

/* NULL keeps the last presenter's capabilities for the decoder choice. A change of them reopens
   the decoder at the next keyframe sent. */
void vpipe_set_presenter(VPipe *vp, VPresenter *p);
/* VP_OK / VP_DROPPED (no presenter, or not bound to this engine) / VP_FAIL. */
int vpipe_present(VPipe *vp, const AVFrame *f);
void vpipe_flush_picture(VPipe *vp);
/* Frames the presenter showed (VP_OK). */
int vpipe_frames(VPipe *vp);

/* A new stream (changeType, new file): codecs the hardware turned down get another chance. */
void vpipe_new_stream(VPipe *vp);
/* Copies why the last open or decode gave up; 0 when it did not. */
int vpipe_failure(const VPipe *vp, char *out, int cap);
/* "libdav1d sw threads=auto", "h264 d3d11va", "" when closed. */
const char *vpipe_describe(const VPipe *vp);

#ifdef __cplusplus
}
#endif

#endif
