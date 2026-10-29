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
    /* GPU mode, under present_mu: the picture on screen, as vpresent_snapshot. */
    int (*snapshot)(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h);
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

#ifdef __APPLE__
#include <TargetConditionals.h>

/* vpresent_vt.c. Takes `overlay` (macOS) on success; NULL leaves it with the caller. */
VPresenter *vp_vt_create(void *layer, void *overlay, const VpSink *s);
/* NULL unless p is a VT presenter. */
void *vp_vt_layer(VPresenter *p);
void *vp_vt_overlay(VPresenter *p);

#if TARGET_OS_OSX
/* mse_vt_present.m: a VT presenter's JAWT overlay, a retained opaque handle. Once bound, a failed
   view install turns p CPU; p's destroy unbinds with NULL. */
void vt_overlay_bind(void *ov, VPresenter *p);
/* 1 while the view is in the window and not unhosted. */
int vt_overlay_hosted(void *ov);
void vt_overlay_layout(void *ov, int x, int y, int w, int h);
void vt_overlay_set_hidden(void *ov, int hidden);
/* After vt_overlay_set_hidden(0): shows the view; a presented frame calls it. */
void vt_overlay_reveal(void *ov);
/* Removes the view from the window; the layer stays alive until vt_overlay_release. */
void vt_overlay_unhost(void *ov);
void vt_overlay_release(void *ov);
#endif
#endif

/* CPU mode, under present_mu: convert at the fit size and hand the BGRA to the sink. */
int vp_cpu_show(VPresenter *p, const struct AVFrame *f, VpColor c);
#ifdef __APPLE__
int vp_cpu_show_cvpb(VPresenter *p, void *cvpb, VpColor c);
#endif
void vp_cpu_flush(VPresenter *p);
int vp_cpu_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h);

#endif
