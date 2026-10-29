#ifndef YMUSIC_MSE_VT_PRESENT_H
#define YMUSIC_MSE_VT_PRESENT_H

#ifdef __APPLE__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* macOS JVM, owner-token API over one process-wide JAWT overlay presenter (mse_vt_present.m).
   Returns the overlay's layer, now held by `owner` until mse_vt_release_component: a newer attach
   replaces the overlay on screen but never frees one an owner still holds. */
void *mse_vt_attach_component(void *env, void *component, const void *owner);
/* Drops owner's holds on every overlay but the one whose layer is `keep` (NULL: all). Call once
   the owner no longer presents into them. */
void mse_vt_release_component(const void *owner, const void *keep);
/* mse_vt_show on macOS: reveals the process-wide overlay after mse_vt_set_overlay_hidden(0). */
void mse_vt_shim_shown(void *layer);
/* Hide the overlay NSView without flushing the layer (menu still on the canvas); unhiding waits
   for the next presented frame. */
void mse_vt_set_overlay_hidden(int hid);
/* Canvas screen rect (AWT y-down). Async; does not hop the AWT thread. */
void mse_vt_layout_screen(int x, int y, int w, int h);
/* Place AWT window [below] immediately under [above] without making it key. */
void mse_vt_order_window_below(void *env, void *below, void *above);
void mse_vt_prepare_fullscreen(void *env, void *primary);
void mse_vt_toggle_fullscreen(void *env, void *primary);

/* Borrowed AVSampleBufferDisplayLayer. Caller owns the layer. */
void mse_vt_detach(void *layer);
int mse_vt_show(void *layer, void *cv_pixel_buffer, int w, int h);
int mse_vt_show_bgra(void *layer, const uint8_t *bgra, int w, int h);
/* Last presented overlay frame as packed BGRA. dst=NULL returns the byte count. */
int mse_vt_copy_last(uint8_t *dst, int cap, int *w, int *h);
/* Software I420/NV12 → overlay. Layer ResizeAspect; no CPU BGRA. cv_pix_fmt is
   kCVPixelFormatType_420YpCbCr8Planar or BiPlanarVideoRange. */
int mse_vt_show_sw(void *layer, unsigned cv_pix_fmt,
                   const uint8_t *const *data, const int *linesize, int w, int h);
enum { MSE_VT_MATRIX_709 = 0, MSE_VT_MATRIX_601 = 1, MSE_VT_MATRIX_2020 = 2 };
/* mse_vt_show_sw tagged with `matrix` (MSE_VT_MATRIX_*) instead of BT.709. */
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
void *mse_vt_dec_open_vp9(const uint8_t *vpcc, int vpcc_n, int w, int h);
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
