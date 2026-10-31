#ifndef YMUSIC_MSE_VT_PRESENT_H
#define YMUSIC_MSE_VT_PRESENT_H

#ifdef __APPLE__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* macOS JVM (mse_vt_present.m). Place AWT window [below] immediately under [above] without
   making it key. */
void mse_vt_order_window_below(void *env, void *below, void *above);
void mse_vt_prepare_fullscreen(void *env, void *primary);
void mse_vt_toggle_fullscreen(void *env, void *primary);

/* Borrowed AVSampleBufferDisplayLayer. Caller owns the layer. */
void mse_vt_detach(void *layer);
int mse_vt_show(void *layer, void *cv_pixel_buffer, int w, int h);
int mse_vt_show_bgra(void *layer, const uint8_t *bgra, int w, int h);
/* Last presented overlay frame as packed BGRA. dst=NULL returns the byte count. */
int mse_vt_copy_last(uint8_t *dst, int cap, int *w, int *h);
enum { MSE_VT_MATRIX_709 = 0, MSE_VT_MATRIX_601 = 1, MSE_VT_MATRIX_2020 = 2 };
/* Software I420/NV12 → layer, tagged with `matrix` (MSE_VT_MATRIX_*). Layer ResizeAspect; no CPU
   BGRA. cv_pix_fmt is kCVPixelFormatType_420YpCbCr8Planar or BiPlanarVideoRange. */
int mse_vt_show_yuv(void *layer, unsigned cv_pix_fmt, const uint8_t *const *data,
                    const int *linesize, int w, int h, int matrix);
void mse_vt_flush(void *layer);
/* 1 if we have presented since the last background skip (layer not Failed). */
int mse_vt_picture_ok(void *layer);
void mse_vt_resume_presents(void *layer);

/* H.264 / VP9 VTDecompressionSession. Enable HW, do not Require it
   (simulator / Intel use Apple software VT). NULL if create fails.
   ffmpeg's videotoolbox hwaccel wraps native `vp9` (SIGILL); this is
   Apple's session, the Chrome path. */
void *mse_vt_dec_open(const uint8_t *avcc, int avcc_n, int w, int h);
/* extradata is used when it is a vpcC box, else one is synthesised from profile (< 0 = unknown)
   or, when given, from frame: the stream's first coded frame. */
void *mse_vt_dec_open_vp9(const uint8_t *extradata, int extradata_n, int profile,
                          const uint8_t *frame, int frame_n, int w, int h);
void mse_vt_dec_close(void *dec);
void mse_vt_dec_flush(void *dec);
int mse_vt_dec_send(void *dec, const uint8_t *data, int n, int output);
/* 1 = pixel buffer in *cvpb (caller CVPixelBufferRelease), 0 = none. */
int mse_vt_dec_recv(void *dec, void **cvpb, int *w, int *h);
void mse_vt_pb_release(void *cvpb);

#ifdef __cplusplus
}
#endif

#endif

#endif
