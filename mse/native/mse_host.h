#ifndef YMUSIC_MSE_HOST_H
#define YMUSIC_MSE_HOST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MseHost MseHost;

typedef void (*MseFrameFn)(void *user, int w, int h, const uint8_t *bgra);
typedef void (*MsePcmFn)(void *user, int rate, int ch, const int16_t *s16, int nbytes);
typedef void (*MseProgressFn)(void *user, double pos, double dur, int w, int h);

MseHost *mse_host_create(void);
void mse_host_destroy(MseHost *h);
/* Function table for QuickJS `__mse*` trampolines. */
const void *mse_host_vtable(void);
void mse_host_set_sinks(MseHost *h, MseFrameFn on_frame, MsePcmFn on_pcm, MseProgressFn on_prog, void *user);
/* Host→JS `"event"` pipe. JNI/Apple set this to MseJsEventBridge.fire. */
void mse_host_set_js_event(void (*fn)(const char *name));
/* JNI/app: the only host call. JS drives play/pause/currentTime via C trampolines. */
void mse_host_set_video_surface(MseHost *h, void *window);
/* JVM desktop: convert/scale dest. 0×0 = decode size. See mse_av_set_blit_size. */
void mse_host_set_blit_size(MseHost *h, int w, int ht);

void mse_host_play(MseHost *h);
void mse_host_pause(MseHost *h);
void mse_host_reset(MseHost *h);

int mse_host_paused(MseHost *h);
int mse_host_ready_state(MseHost *h);
double mse_host_current_time(MseHost *h);
void mse_host_set_current_time(MseHost *h, double seconds);
double mse_host_duration(MseHost *h);
void mse_host_set_duration(MseHost *h, double seconds);
/* endOfStream and the audio clock has finished (leftover + DAC ring). Video leftover is ignored. */
int mse_host_ended(MseHost *h);
void mse_host_end_of_stream(MseHost *h);
int mse_host_video_size(MseHost *h, int *w, int *ht);
int mse_host_frames(MseHost *h);
/* 1 and the reason in out when video cannot be decoded; the player fails playback. */
int mse_host_video_failure(MseHost *h, char *out, int cap);
int64_t mse_host_samples_played(MseHost *h);
#ifdef __ANDROID__
/* Android plays the PCM ring through a Kotlin AudioSink thread (MseAudioOutput) driving these. */
enum { MSE_DAC_GEN, MSE_DAC_RATE, MSE_DAC_CH, MSE_DAC_RUN, MSE_DAC_DRAIN, MSE_DAC_STATE_N };
/* The sink's audio session id, 0 while there is none. */
int mse_host_audio_session(MseHost *h);
/* No PCM reaches the ring until a DAC is attached. Detach before mse_host_destroy. */
void mse_host_dac_attach(MseHost *h, int attached);
/* Copies up to cap bytes of ring PCM into dst while the DAC should run; while paused, first waits up
   to wait_ms for a state change. Fills state: GEN changes on every flush (the DAC drops what it holds), RUN = play, DRAIN =
   the stream ended and the ring is empty. Returns bytes copied, -1 once the host is released. */
int mse_host_dac_poll(MseHost *h, uint8_t *dst, int cap, int wait_ms, int state[MSE_DAC_STATE_N]);
/* Frames played out (-1 = unknown) / written since generation gen began; pending = the DAC still holds
   audio; buffer_us = the DAC's own buffer (0 = unknown). */
void mse_host_dac_report(MseHost *h, int gen, int64_t position, int64_t written, int pending, int64_t buffer_us);
void mse_host_dac_session(MseHost *h, int session);
#endif
/* Last decoded BGRA. dst=NULL or cap too small → returns bytes needed (w/h/gen still set). 0 = none. */
int mse_host_copy_frame(MseHost *h, uint8_t *dst, int cap, int *w, int *ht, int *gen);

int mse_host_append(MseHost *h, int audio, const uint8_t *p, int n, double ts_off, double win_start, double win_end);
/* Chrome prepareAppend: evict then 0 if still over quota (RAM class 12/150, 5/80, 2/30, 1/15). */
int mse_host_prepare_append(MseHost *h, int audio, int n);
void mse_host_remove(MseHost *h, int audio, double start, double end);
void mse_host_abort(MseHost *h, int audio);
void mse_host_change_type(MseHost *h, int audio);
void mse_host_rest_video(MseHost *h, int on);
int mse_host_buffered(MseHost *h, int audio, double *pairs, int max_pairs);

void mse_host_set_volume(MseHost *h, float vol);
/* speed = HTMLMediaElement.playbackRate (currentTime). tempo = PCM stretch. Pair them. */
void mse_host_set_speed(MseHost *h, float speed, float tempo);
float mse_host_playback_rate(MseHost *h);
void mse_host_set_pitch(MseHost *h, float pitch);
void mse_host_set_eq(MseHost *h, const float bands_db[10]);
void mse_host_set_bass(MseHost *h, float db);
void mse_host_set_virtualizer(MseHost *h, float width);
void mse_host_set_preamp(MseHost *h, float db);
/* Fixed-position log-frequency bars in 0..1 from the PCM color path. */
int mse_host_pcm_levels(MseHost *h, float *out, int n);

#ifdef __cplusplus
}
#endif

#endif
