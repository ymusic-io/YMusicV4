#ifndef YMUSIC_VPRESENT_IMPL_H
#define YMUSIC_VPRESENT_IMPL_H

/* Presenter internals shared by the kinds (vpresent_*.c). */

#include "vpresent.h"

#include <pthread.h>
#include <stdatomic.h>

typedef struct {
    const char *kind;
    /* GPU mode, under present_mu. */
    int (*show)(VPresenter *p, const struct AVFrame *f, VpColor c);
    int (*show_cvpb)(VPresenter *p, void *cvpb, VpColor c);
    void (*flush)(VPresenter *p);
    /* Under present_mu, once. */
    void (*detach)(VPresenter *p);
    int (*zero_copy)(VPresenter *p, int av_pix_fmt);
    /* Last reference gone; no lock held. */
    void (*destroy)(VPresenter *p);
} VpOps;

struct VPresenter {
    atomic_int refs;
    pthread_mutex_t present_mu;
    pthread_mutex_t mu;
    const VpOps *ops;
    void *impl;
    VpSink sink;
    const void *bound;   /* mu */
    int detached;        /* mu */
    int fit_w, fit_h;    /* mu */
    atomic_int mode;     /* written under mu */
    VpCpu *cpu;          /* present_mu */
};

VPresenter *vp_alloc(const VpOps *ops, void *impl, const VpSink *s, int mode);
/* Fires on_mode after mu is released. */
void vp_set_mode(VPresenter *p, int mode);
/* The surface died under a live presenter: GPU mode becomes CPU (detached stays NONE). */
void vp_gpu_lost(VPresenter *p);
/* A reference unless the presenter is already being destroyed. */
int vp_retain_live(VPresenter *p);
void vp_fit_size(VPresenter *p, int *w, int *h);

#ifdef _WIN32
/* mse_d3d11_check_lost: drops every D3D11 presenter's swapchain and turns it CPU. */
void vpresent_d3d11_device_lost(void);
#endif

/* CPU mode, under present_mu: convert at the fit size and hand the BGRA to the sink. */
int vp_cpu_show(VPresenter *p, const struct AVFrame *f, VpColor c);
#ifdef __APPLE__
int vp_cpu_show_cvpb(VPresenter *p, void *cvpb, VpColor c);
#endif
void vp_cpu_flush(VPresenter *p);
int vp_cpu_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h);

#endif
