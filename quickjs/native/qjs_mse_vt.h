#ifndef YMUSIC_QJS_MSE_VT_H
#define YMUSIC_QJS_MSE_VT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct QjsMseVt {
    double (*current_time)(void *);
    void (*set_current_time)(void *, double);
    double (*duration)(void *);
    int (*paused)(void *);
    int (*ready_state)(void *);
    void (*play)(void *);
    void (*pause)(void *);
    void (*reset)(void *);
    int (*append)(void *, int audio, const uint8_t *p, int n, double ts, double ws, double we);
    void (*remove)(void *, int audio, double start, double end);
    void (*abort)(void *, int audio);
    void (*set_duration)(void *, double);
    int (*buffered)(void *, int audio, double *pairs, int max_pairs);
    int (*video_size)(void *, int *w, int *h);
    void (*change_type)(void *, int audio);
    void (*rest_video)(void *, int on);
    void (*set_speed)(void *, double speed, double tempo);
    double (*playback_rate)(void *);
    int (*ended)(void *);
    void (*end_of_stream)(void *);
    int (*prepare_append)(void *, int audio, int n);
    void (*set_pitch)(void *, double);
    /* Process-wide, no host: MediaCapabilities bits (1 supported, 2 smooth, 4 powerEfficient), -1 no
       decoder table, -2 still probing. Never waits. */
    int (*decoding_info)(const char *content_type, int w, int h, double fps, double bitrate);
    void (*video_quality)(void *, int *total, int *dropped);
} QjsMseVt;

#ifdef __cplusplus
}
#endif

#endif
