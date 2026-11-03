#ifndef YMUSIC_MSE_NATIVE_H
#define YMUSIC_MSE_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* KN cinterop surface. Same C host as JNI MseHostNative.create/vtable/frames/copyFrame. */
void *mse_host_create(void);
void mse_host_destroy(void *h);
const void *mse_host_vtable(void);
int mse_host_frames(void *h);
int mse_host_dropped_frames(void *h);
void mse_host_video_decoder(void *h, char *out, int cap);
int mse_host_video_size(void *h, int *w, int *ht);
int64_t mse_host_samples_played(void *h);
int mse_host_copy_frame(void *h, uint8_t *dst, int cap, int *w, int *ht, int *gen);
void mse_host_set_video_surface(void *h, void *window);
void mse_host_set_js_event(void (*fn)(const char *name));
/* JNI/Apple: C→Kotlin hop for critical native lines (starve, ffmpeg, park). */
void mse_set_log_callback(void (*fn)(int level, const char *msg));
void mse_ios_recover_playback(void);
void mse_ios_park_video(void);
/* Unified log on iOS (os_log); stderr elsewhere. Safe for any message text. */
void mse_ios_log(const char *msg);
void mse_host_set_volume(void *h, float vol);
void mse_host_set_speed(void *h, float speed, float tempo);
float mse_host_playback_rate(void *h);
void mse_host_set_pitch(void *h, float pitch);
void mse_host_set_eq(void *h, const float *bands_db);
void mse_host_set_bass(void *h, float db);
void mse_host_set_virtualizer(void *h, float width);
void mse_host_set_preamp(void *h, float db);
int mse_host_pcm_levels(void *h, float *out, int n);

#ifdef __cplusplus
}
#endif

#endif
