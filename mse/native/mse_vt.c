#include "mse_host.h"
#include "mse_caps.h"
#include "qjs_mse_vt.h"

static void vt_set_speed(void *h, double speed, double tempo)
{
    mse_host_set_speed((MseHost *)h, (float)speed, (float)tempo);
}

static double vt_playback_rate(void *h)
{
    return (double)mse_host_playback_rate((MseHost *)h);
}

static void vt_set_pitch(void *h, double pitch)
{
    mse_host_set_pitch((MseHost *)h, (float)pitch);
}

static const QjsMseVt kVt = {
    .current_time = (double (*)(void *))mse_host_current_time,
    .set_current_time = (void (*)(void *, double))mse_host_set_current_time,
    .duration = (double (*)(void *))mse_host_duration,
    .paused = (int (*)(void *))mse_host_paused,
    .ready_state = (int (*)(void *))mse_host_ready_state,
    .play = (void (*)(void *))mse_host_play,
    .pause = (void (*)(void *))mse_host_pause,
    .reset = (void (*)(void *))mse_host_reset,
    .append = (int (*)(void *, int, const uint8_t *, int, double, double, double))mse_host_append,
    .remove = (void (*)(void *, int, double, double))mse_host_remove,
    .abort = (void (*)(void *, int))mse_host_abort,
    .set_duration = (void (*)(void *, double))mse_host_set_duration,
    .buffered = (int (*)(void *, int, double *, int))mse_host_buffered,
    .video_size = (int (*)(void *, int *, int *))mse_host_video_size,
    .change_type = (void (*)(void *, int))mse_host_change_type,
    .rest_video = (void (*)(void *, int))mse_host_rest_video,
    .set_speed = vt_set_speed,
    .playback_rate = vt_playback_rate,
    .ended = (int (*)(void *))mse_host_ended,
    .end_of_stream = (void (*)(void *))mse_host_end_of_stream,
    .prepare_append = (int (*)(void *, int, int))mse_host_prepare_append,
    .set_pitch = vt_set_pitch,
    .decoding_info = mse_caps_decoding_info,
    .video_quality = (void (*)(void *, int *, int *))mse_host_video_quality,
};

const void *mse_host_vtable(void)
{
    return &kVt;
}
