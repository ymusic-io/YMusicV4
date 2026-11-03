#include "mse_native.h"
#include <stddef.h>

void *mse_host_create(void)
{
    return NULL;
}

void mse_host_destroy(void *h)
{
    (void)h;
}

const void *mse_host_vtable(void)
{
    return NULL;
}

int mse_host_frames(void *h)
{
    (void)h;
    return 0;
}

int mse_host_dropped_frames(void *h)
{
    (void)h;
    return 0;
}

void mse_host_video_decoder(void *h, char *out, int cap)
{
    (void)h;
    if (cap > 0)
        out[0] = 0;
}

int64_t mse_host_samples_played(void *h)
{
    (void)h;
    return 0;
}

int mse_host_copy_frame(void *h, uint8_t *dst, int cap, int *w, int *ht, int *gen)
{
    (void)h;
    (void)dst;
    (void)cap;
    if (w)
        *w = 0;
    if (ht)
        *ht = 0;
    if (gen)
        *gen = 0;
    return 0;
}

void mse_host_set_video_surface(void *h, void *window)
{
    (void)h;
    (void)window;
}

void mse_host_set_js_event(void (*fn)(const char *name))
{
    (void)fn;
}

void mse_set_log_callback(void (*fn)(int level, const char *msg))
{
    (void)fn;
}

void mse_ios_log(const char *msg)
{
    (void)msg;
}

int mse_host_video_size(void *h, int *w, int *ht)
{
    (void)h;
    if (w)
        *w = 0;
    if (ht)
        *ht = 0;
    return 0;
}

int mse_host_pcm_levels(void *h, float *out, int n)
{
    (void)h;
    (void)out;
    (void)n;
    return 0;
}
