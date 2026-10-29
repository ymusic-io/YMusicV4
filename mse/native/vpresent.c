#include "vpresent_impl.h"
#include "mse_ffmpeg.h"

#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>

#include <stdlib.h>

static const char *mode_name(int mode)
{
    return mode == VP_MODE_GPU ? "gpu" : mode == VP_MODE_CPU ? "cpu" : "none";
}

VPresenter *vp_alloc(const VpOps *ops, void *impl, const VpSink *s, int mode)
{
    VPresenter *p = calloc(1, sizeof(*p));
    if (!p)
        return NULL;
    atomic_init(&p->refs, 1);
    atomic_init(&p->mode, mode);
    pthread_mutex_init(&p->present_mu, NULL);
    pthread_mutex_init(&p->mu, NULL);
    p->ops = ops;
    p->impl = impl;
    if (s)
        p->sink = *s;
    return p;
}

static void mode_changed(VPresenter *p, int mode)
{
    mse_critf(MSE_LOG_INFO, "vpresent mode %s kind=%s", mode_name(mode), p->ops->kind);
    if (p->sink.on_mode)
        p->sink.on_mode(p->sink.user, mode);
}

void vp_set_mode(VPresenter *p, int mode)
{
    int old;
    pthread_mutex_lock(&p->mu);
    old = atomic_load(&p->mode);
    atomic_store(&p->mode, mode);
    pthread_mutex_unlock(&p->mu);
    if (old != mode)
        mode_changed(p, mode);
}

void vp_gpu_lost(VPresenter *p)
{
    int flip;
    pthread_mutex_lock(&p->mu);
    flip = !p->detached && atomic_load(&p->mode) == VP_MODE_GPU;
    if (flip)
        atomic_store(&p->mode, VP_MODE_CPU);
    pthread_mutex_unlock(&p->mu);
    if (flip)
        mode_changed(p, VP_MODE_CPU);
}

int vp_retain_live(VPresenter *p)
{
    int n = atomic_load(&p->refs);
    while (n > 0) {
        if (atomic_compare_exchange_weak(&p->refs, &n, n + 1))
            return 1;
    }
    return 0;
}

void vp_fit_size(VPresenter *p, int *w, int *h)
{
    pthread_mutex_lock(&p->mu);
    *w = p->fit_w;
    *h = p->fit_h;
    pthread_mutex_unlock(&p->mu);
}

void vpresent_retain(VPresenter *p)
{
    if (p)
        atomic_fetch_add(&p->refs, 1);
}

void vpresent_release(VPresenter *p)
{
    if (!p || atomic_fetch_sub(&p->refs, 1) != 1)
        return;
    if (p->ops->destroy)
        p->ops->destroy(p);
    vp_cpu_destroy(p->cpu);
    pthread_mutex_destroy(&p->mu);
    pthread_mutex_destroy(&p->present_mu);
    free(p);
}

void vpresent_detach(VPresenter *p)
{
    int was;
    if (!p)
        return;
    pthread_mutex_lock(&p->present_mu);
    pthread_mutex_lock(&p->mu);
    was = p->detached;
    p->detached = 1;
    pthread_mutex_unlock(&p->mu);
    if (!was && p->ops->detach)
        p->ops->detach(p);
    pthread_mutex_unlock(&p->present_mu);
    if (!was)
        vp_set_mode(p, VP_MODE_NONE);
}

/* Under present_mu: the mode `engine` shows in, VP_MODE_NONE when it may not show. */
static int show_mode(VPresenter *p, const void *engine)
{
    int mode;
    pthread_mutex_lock(&p->mu);
    mode = p->detached || p->bound != engine ? VP_MODE_NONE : atomic_load(&p->mode);
    pthread_mutex_unlock(&p->mu);
    return mode;
}

static void flush_locked(VPresenter *p, int mode)
{
    if (mode == VP_MODE_CPU)
        vp_cpu_flush(p);
    else if (mode == VP_MODE_GPU && p->ops->flush)
        p->ops->flush(p);
}

void vpresent_bind(VPresenter *p, const void *engine)
{
    const void *prev;
    int mode;
    if (!p)
        return;
    pthread_mutex_lock(&p->present_mu);
    pthread_mutex_lock(&p->mu);
    prev = p->bound;
    p->bound = engine;
    mode = p->detached ? VP_MODE_NONE : atomic_load(&p->mode);
    pthread_mutex_unlock(&p->mu);
    if (prev && prev != engine)
        flush_locked(p, mode);
    pthread_mutex_unlock(&p->present_mu);
    if (prev && prev != engine)
        mse_critf(MSE_LOG_INFO, "vpresent bind kind=%s %p -> %p", p->ops->kind, prev, engine);
    else if (prev != engine)
        mse_logf("vpresent bind kind=%s %p", p->ops->kind, engine);
}

void vpresent_unbind(VPresenter *p, const void *engine)
{
    if (!p || !engine)
        return;
    /* present_mu: no show from `engine` is in flight once this returns. */
    pthread_mutex_lock(&p->present_mu);
    pthread_mutex_lock(&p->mu);
    if (p->bound == engine)
        p->bound = NULL;
    pthread_mutex_unlock(&p->mu);
    pthread_mutex_unlock(&p->present_mu);
}

void vpresent_set_size(VPresenter *p, int w, int h)
{
    int changed;
    if (!p)
        return;
    if (w < 0)
        w = 0;
    if (h < 0)
        h = 0;
    pthread_mutex_lock(&p->mu);
    changed = p->fit_w != w || p->fit_h != h;
    p->fit_w = w;
    p->fit_h = h;
    pthread_mutex_unlock(&p->mu);
    if (changed)
        mse_logf("vpresent size kind=%s %dx%d", p->ops->kind, w, h);
}

int vpresent_zero_copy(VPresenter *p, int av_pix_fmt)
{
    if (!p || atomic_load(&p->mode) != VP_MODE_GPU || !p->ops->zero_copy)
        return 0;
    return p->ops->zero_copy(p, av_pix_fmt);
}

int vpresent_mode(VPresenter *p)
{
    return p ? atomic_load(&p->mode) : VP_MODE_NONE;
}

int vpresent_show_frame(VPresenter *p, const void *engine, const AVFrame *f, VpColor c)
{
    int mode, r;
    if (!p || !f)
        return VP_FAIL;
    pthread_mutex_lock(&p->present_mu);
    mode = show_mode(p, engine);
    if (mode == VP_MODE_CPU)
        r = vp_cpu_show(p, f, c);
    else if (mode == VP_MODE_GPU && p->ops->show)
        r = p->ops->show(p, f, c);
    else
        r = VP_DROPPED;
    pthread_mutex_unlock(&p->present_mu);
    return r;
}

#ifdef __APPLE__
int vpresent_show_cvpb(VPresenter *p, const void *engine, void *cvpb, VpColor c)
{
    int mode, r;
    if (!p || !cvpb)
        return VP_FAIL;
    pthread_mutex_lock(&p->present_mu);
    mode = show_mode(p, engine);
    if (mode == VP_MODE_CPU)
        r = vp_cpu_show_cvpb(p, cvpb, c);
    else if (mode == VP_MODE_GPU && p->ops->show_cvpb)
        r = p->ops->show_cvpb(p, cvpb, c);
    else
        r = VP_DROPPED;
    pthread_mutex_unlock(&p->present_mu);
    return r;
}
#endif

void vpresent_flush(VPresenter *p, const void *engine)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->present_mu);
    flush_locked(p, show_mode(p, engine));
    pthread_mutex_unlock(&p->present_mu);
}

int vpresent_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h)
{
    int n;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->present_mu);
    /* In GPU mode the CPU buffers hold upload scratch, not the picture on screen. */
    n = atomic_load(&p->mode) == VP_MODE_CPU ? vp_cpu_snapshot(p, xrgb, cap_px, w, h) : 0;
    pthread_mutex_unlock(&p->present_mu);
    return n;
}

VpColor vp_color_of(const AVFrame *f, const AVCodecParameters *par)
{
    VpColor c;
    int spc = f ? (int)f->colorspace : AVCOL_SPC_UNSPECIFIED;
    int height = f ? f->height : 0;
    int range = AVCOL_RANGE_UNSPECIFIED;
    if (spc == AVCOL_SPC_UNSPECIFIED && par)
        spc = par->color_space;
    if (height <= 0 && par)
        height = par->height;
    switch (spc) {
    case AVCOL_SPC_BT709:
        c.matrix = AVCOL_SPC_BT709;
        break;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
        c.matrix = AVCOL_SPC_BT2020_NCL;
        break;
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_BT470BG:
        c.matrix = AVCOL_SPC_SMPTE170M;
        break;
    default:
        c.matrix = height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
        break;
    }
    if (par && (par->color_range == AVCOL_RANGE_MPEG || par->color_range == AVCOL_RANGE_JPEG))
        range = par->color_range;
    else if (f && f->color_range != AVCOL_RANGE_UNSPECIFIED)
        range = f->color_range;
    c.full_range = range == AVCOL_RANGE_JPEG;
    return c;
}
