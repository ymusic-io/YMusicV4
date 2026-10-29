#ifndef YMUSIC_FILE_AV_H
#define YMUSIC_FILE_AV_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* In-process libav file player. Separate from leftover MSE (no append/seek window). */

typedef struct FileAv FileAv;
struct VPresenter;

typedef void (*FileAvPcmFn)(void *user, int rate, int ch, const int16_t *s16, int nbytes);
typedef void (*FileAvProgressFn)(void *user, double pos, double dur, int w, int h);
typedef void (*FileAvEventFn)(void *user, int ended, const char *err);
typedef void (*FileAvCaptionFn)(void *user, int id, double start, double end, const char *text);

FileAv *file_av_create(void);
void file_av_destroy(FileAv *p);

int file_av_open(FileAv *p, const char *path);
void file_av_close(FileAv *p);

void file_av_play(FileAv *p);
void file_av_pause(FileAv *p);
void file_av_seek(FileAv *p, double seconds);
/* One present tick. Video decode runs on an internal thread. */
int file_av_step(FileAv *p);
void file_av_set_speed(FileAv *p, double speed);
void file_av_set_volume(FileAv *p, double vol);
void file_av_set_pitch(FileAv *p, double pitch);
void file_av_set_eq(FileAv *p, const float bands_db[10]);
void file_av_set_bass(FileAv *p, float db);
void file_av_set_virtualizer(FileAv *p, float width);
void file_av_set_preamp(FileAv *p, float db);
int file_av_pcm_levels(FileAv *p, float *out, int n);
void file_av_set_audio_index(FileAv *p, int stream_index);
/* -1 = captions off. Text subtitle stream index from file_av_stream_at. */
void file_av_set_subtitle_index(FileAv *p, int stream_index);
void file_av_set_video_enabled(FileAv *p, int on);
/* Where pictures go, bound with this FileAv as the token (NULL unbinds; video waits for one). Any
   thread; FileAv holds its own reference. Windows decodes on D3D11VA only into a presenter that
   draws D3D11 textures: a change reopens the decoder. */
void file_av_bind_presenter(FileAv *p, struct VPresenter *vp);
/* Pictures the presenter showed. */
int file_av_frames(FileAv *p);

double file_av_duration(FileAv *p);
double file_av_position(FileAv *p);
int file_av_ended(FileAv *p);
int file_av_video_size(FileAv *p, int *w, int *h);
const char *file_av_last_error(FileAv *p);

int file_av_stream_count(FileAv *p);
/* kind 0=audio 1=video 2=text. lang/codec are 0-terminated; may be empty. */
int file_av_stream_at(FileAv *p, int i, int *kind, int *index, int *w, int *h,
                      char *lang, int lang_n, char *codec, int codec_n);

void file_av_set_sinks(FileAv *p, FileAvPcmFn on_pcm, FileAvProgressFn on_prog,
                       FileAvEventFn on_event, FileAvCaptionFn on_caption, void *user);

/* Read every text sample on [stream_index] (MP4 index, other streams discarded). */
int file_av_dump_captions(FileAv *p, int stream_index, FileAvCaptionFn on_cue, void *user);
void *file_av_user(FileAv *p);

#ifdef __cplusplus
}
#endif

#endif
