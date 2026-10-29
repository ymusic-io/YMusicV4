#include "vpipe.h"
#include "vpipe_hw.h"
#include "mse_ffmpeg.h"

#include <libavutil/cpu.h>
#include <libavutil/pixdesc.h>

#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VP_REPLAY_MAX 8
#define VP_PEND_MAX 32
#define VP_LATCH_MAX 8
#define VP_HW_ERRS 3
#define VP_SW_ERRS 8
#define VP_SW_REOPENS 2
/* Internal: the decoder was replaced, feed pend[0] again. */
#define VP_RETRY 1

enum { FB_REJECTED, FB_CONSUMED, FB_RECEIVE };

typedef struct {
    AVCodecContext *ctx;
    void *sess;
    int hw;
    /* get_format chose a software format on a hardware context. */
    int sw_pick;
    int got_frame;
    const char *name;
} VpDec;

struct VPipe {
    VpParams prm;
    const VpHw *hw;
    int hw_init;
    AVCodecParameters *par;    /* written under pmu */
    AVRational tb;
    VpDec cur;
    /* Draining after a reconfig; its frames come out before cur's. */
    VpDec old;
    /* What cur took before its first frame, replayed into a fallback decoder. */
    AVPacket *q[VP_REPLAY_MAX];
    int qn, q_off;
    /* Accepted, not yet in the decoder; a drain follows them when pend_drain. */
    AVPacket *pend[VP_PEND_MAX + VP_REPLAY_MAX];
    int pend_rap[VP_PEND_MAX + VP_REPLAY_MAX];
    int pend_n, pend_drain;
    enum AVCodecID latched[VP_LATCH_MAX];
    int latched_n;
    int hw_errs;
    /* Errors and error reopens since the last frame out. */
    int errs;
    int err_reopens;
    char failure[192];
    char desc[64];
    pthread_mutex_t pmu;
    VPresenter *vp;            /* pmu */
    const void *engine;        /* pmu */
    /* The last non-NULL presenter: the decoder choice follows what it can draw. pmu. */
    VPresenter *caps;
    atomic_int reconfig;
    atomic_int frames;
};

static const char *tag(const VPipe *vp)
{
    return vp->prm.tag ? vp->prm.tag : "vpipe";
}

const VpHw *vpipe_hw_platform(void)
{
#if defined(_WIN32)
    return vpipe_hw_d3d11();
#elif defined(__APPLE__)
    return vpipe_hw_vt();
#elif defined(__linux__) && !defined(__ANDROID__)
    return vpipe_hw_linux();
#else
    return NULL;
#endif
}

static const VpHw *hw_of(VPipe *vp)
{
    if (!vp->hw_init) {
        vp->hw = vpipe_hw_platform();
        vp->hw_init = 1;
    }
    return vp->hw;
}

static int dec_open(const VpDec *d)
{
    return d->ctx || d->sess;
}

static void dec_free(VPipe *vp, VpDec *d)
{
    if (d->sess)
        vp->hw->session_close(d->sess);
    avcodec_free_context(&d->ctx);
    memset(d, 0, sizeof(*d));
}

static int dec_send(VPipe *vp, VpDec *d, const AVPacket *pkt)
{
    return d->sess ? vp->hw->session_send(d->sess, pkt) : avcodec_send_packet(d->ctx, pkt);
}

static int dec_receive(VPipe *vp, VpDec *d, AVFrame *out)
{
    return d->sess ? vp->hw->session_receive(d->sess, out) : avcodec_receive_frame(d->ctx, out);
}

static void q_clear(VPipe *vp)
{
    int i;
    for (i = 0; i < vp->qn; i++)
        av_packet_free(&vp->q[i]);
    vp->qn = 0;
    vp->q_off = 0;
}

static void q_track(VPipe *vp, const AVPacket *pkt)
{
    if (vp->cur.got_frame || vp->q_off)
        return;
    if (vp->qn == VP_REPLAY_MAX || !(vp->q[vp->qn] = av_packet_clone(pkt))) {
        q_clear(vp);
        vp->q_off = 1;
        return;
    }
    vp->qn++;
}

static void pend_pop(VPipe *vp)
{
    if (vp->pend_n <= 0)
        return;
    av_packet_free(&vp->pend[0]);
    vp->pend_n--;
    memmove(&vp->pend[0], &vp->pend[1], (size_t)vp->pend_n * sizeof(vp->pend[0]));
    memmove(&vp->pend_rap[0], &vp->pend_rap[1], (size_t)vp->pend_n * sizeof(vp->pend_rap[0]));
}

static void pend_clear(VPipe *vp)
{
    while (vp->pend_n > 0)
        pend_pop(vp);
    vp->pend_drain = 0;
}

static int latched(const VPipe *vp, enum AVCodecID id)
{
    int i;
    for (i = 0; i < vp->latched_n; i++) {
        if (vp->latched[i] == id)
            return 1;
    }
    return 0;
}

static void latch(VPipe *vp, enum AVCodecID id, const char *why)
{
    if (latched(vp, id))
        return;
    if (vp->latched_n < VP_LATCH_MAX)
        vp->latched[vp->latched_n++] = id;
    mse_critf(MSE_LOG_INFO, "vpipe %s latch %s off %s: %s", tag(vp), avcodec_get_name(id), vp->hw->name,
              why);
    if (vp->hw->reject)
        vp->hw->reject(id);
}

static int dead(VPipe *vp, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(vp->failure, sizeof(vp->failure), fmt, ap);
    va_end(ap);
    mse_critf(MSE_LOG_WARN, "vpipe %s dead: %s", tag(vp), vp->failure);
    dec_free(vp, &vp->old);
    dec_free(vp, &vp->cur);
    q_clear(vp);
    pend_clear(vp);
    vp->desc[0] = 0;
    return VP_DEAD;
}

static enum AVPixelFormat vp_get_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    VPipe *vp = ctx->opaque;
    enum AVPixelFormat f = vp->hw->get_format(ctx, fmts);
    if (f != vp->hw->pix_fmt && ctx == vp->cur.ctx && !vp->cur.sw_pick) {
        char why[64];
        vp->cur.sw_pick = 1;
        snprintf(why, sizeof(why), "software format %s", av_get_pix_fmt_name(ctx->sw_pix_fmt));
        latch(vp, ctx->codec_id, why);
    }
    return f;
}

static const AVCodec *sw_decoder(enum AVCodecID id)
{
    const AVCodec *c;
    switch (id) {
    case AV_CODEC_ID_VP9:
        c = avcodec_find_decoder_by_name("libvpx-vp9");
        return c ? c : avcodec_find_decoder_by_name("vp9");
    case AV_CODEC_ID_VP8:
        c = avcodec_find_decoder_by_name("libvpx");
        return c ? c : avcodec_find_decoder_by_name("vp8");
    case AV_CODEC_ID_AV1:
        /* Native av1 decodes only through a hwaccel. */
        return avcodec_find_decoder_by_name("libdav1d");
    default:
        return avcodec_find_decoder(id);
    }
}

static void sw_threads(const VPipe *vp, AVCodecContext *ctx, enum AVCodecID id)
{
    int n;
    if (!vp->prm.low_delay) {
        ctx->thread_count = 0;
        ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
        return;
    }
    switch (id) {
    case AV_CODEC_ID_VP9:
    case AV_CODEC_ID_VP8:
        ctx->thread_count = 4;
        ctx->thread_type = FF_THREAD_FRAME;
        break;
    case AV_CODEC_ID_AV1:
        n = av_cpu_count();
        ctx->thread_count = n < 2 ? 2 : n > 8 ? 8 : n;
        ctx->thread_type = FF_THREAD_FRAME;
        break;
    default:
        ctx->thread_count = 1;
        ctx->thread_type = 0;
        break;
    }
}

/* NULL when the stream decodes on the hardware; else why not. */
static const char *hw_refusal(VPipe *vp, int *zero_copy)
{
    const VpHw *hw = hw_of(vp);
    int zc;
    *zero_copy = 0;
    if (!hw)
        return "no-device";
    if (latched(vp, vp->par->codec_id))
        return "latched";
    if (!hw->usable(vp->par))
        return "unsupported";
    pthread_mutex_lock(&vp->pmu);
    zc = vpresent_zero_copy(vp->caps, hw->pix_fmt);
    pthread_mutex_unlock(&vp->pmu);
    if (!zc && !hw->readback_ok)
        return "presenter";
    *zero_copy = zc;
    return NULL;
}

static void log_open(VPipe *vp, const char *why, const char *on)
{
    const AVCodecContext *ctx = vp->cur.ctx;
    char th[24];
    if (!ctx)
        snprintf(th, sizeof(th), "session");
    else if (ctx->thread_count <= 0)
        snprintf(th, sizeof(th), "auto");
    else
        snprintf(th, sizeof(th), "%d%s", ctx->thread_count,
                 ctx->active_thread_type == FF_THREAD_FRAME   ? "/frame"
                 : ctx->active_thread_type == FF_THREAD_SLICE ? "/slice"
                                                              : "");
    snprintf(vp->desc, sizeof(vp->desc), "%s %s threads=%s", vp->cur.name,
             vp->cur.hw ? vp->hw->name : "sw", th);
    mse_critf(MSE_LOG_INFO, "vpipe open %s %s hw=%s reason=%s threads=%s %dx%d on=%s", tag(vp),
              vp->cur.name, vp->cur.hw ? vp->hw->name : "none", why, th, vp->par->width,
              vp->par->height, on);
}

static AVCodecContext *alloc_ctx(VPipe *vp, const AVCodec *c)
{
    AVCodecContext *ctx = avcodec_alloc_context3(c);
    if (!ctx)
        return NULL;
    if (avcodec_parameters_to_context(ctx, vp->par) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    ctx->pkt_timebase = vp->tb;
    ctx->opaque = vp;
    return ctx;
}

/* NULL once cur decodes on the hardware; else why it does not. */
static const char *open_hw(VPipe *vp, int zero_copy, const char *on)
{
    const VpHw *hw = vp->hw;
    enum AVCodecID id = vp->par->codec_id;
    const AVCodec *c = hw->decoder(id);
    char why[96];
    int r;
    if (!c) {
        void *s = hw->session_open ? hw->session_open(vp->par, vp->tb) : NULL;
        if (!s) {
            if (hw->session_open)
                latch(vp, id, "session open failed");
            return hw->session_open ? "hw-open-failed" : "unsupported";
        }
        vp->cur.sess = s;
        vp->cur.hw = 1;
        vp->cur.name = hw->name;
        log_open(vp, zero_copy ? "zero-copy" : "readback", on);
        return NULL;
    }
    vp->cur.ctx = alloc_ctx(vp, c);
    if (!vp->cur.ctx)
        return "no-memory";
    vp->cur.ctx->hw_device_ctx = hw->device_ref();
    if (!vp->cur.ctx->hw_device_ctx) {
        dec_free(vp, &vp->cur);
        return "no-device";
    }
    vp->cur.ctx->get_format = vp_get_format;
    vp->cur.ctx->extra_hw_frames = vp->prm.held_frames + 2;
    vp->cur.ctx->thread_count = 1;
    vp->cur.ctx->thread_type = 0;
    vp->cur.hw = 1;
    vp->cur.name = c->name;
    r = avcodec_open2(vp->cur.ctx, c, NULL);
    if (r < 0) {
        snprintf(why, sizeof(why), "open %s: %s", c->name, av_err2str(r));
        latch(vp, id, why);
    }
    if (r < 0 || vp->cur.sw_pick) {
        dec_free(vp, &vp->cur);
        return "hw-open-failed";
    }
    log_open(vp, zero_copy ? "zero-copy" : "readback", on);
    return NULL;
}

static int open_sw(VPipe *vp, const char *why, const char *on)
{
    enum AVCodecID id = vp->par->codec_id;
    const AVCodec *c = sw_decoder(id);
    int r;
    if (!c)
        return dead(vp, "no software decoder for %s", avcodec_get_name(id));
    vp->cur.ctx = alloc_ctx(vp, c);
    if (!vp->cur.ctx)
        return dead(vp, "out of memory opening %s", c->name);
    sw_threads(vp, vp->cur.ctx, id);
    vp->cur.name = c->name;
    r = avcodec_open2(vp->cur.ctx, c, NULL);
    if (r < 0)
        return dead(vp, "open %s %dx%d: %s", c->name, vp->par->width, vp->par->height, av_err2str(r));
    log_open(vp, why, on);
    return 0;
}

/* cur closed on entry. 0 or VP_DEAD. */
static int open_dec(VPipe *vp, const char *on)
{
    const char *refusal;
    int zero_copy;
    atomic_store(&vp->reconfig, 0);
    refusal = hw_refusal(vp, &zero_copy);
    if (!refusal) {
        refusal = open_hw(vp, zero_copy, on);
        if (!refusal)
            return 0;
    }
    return open_sw(vp, refusal, on);
}

/* cur gave up on the stream at pend[0] (FB_CONSUMED: pend[0] went in) or at a receive. */
static int fallback(VPipe *vp, int at, const char *why)
{
    AVPacket *rp[VP_REPLAY_MAX];
    int rn = 0, i, r;
    int resend = at != FB_RECEIVE && vp->pend_n > 0 && vp->pend_rap[0];
    int room = VP_PEND_MAX + VP_REPLAY_MAX - vp->pend_n + (at == FB_CONSUMED);
    int replay = !resend && !vp->cur.got_frame && !vp->q_off && vp->qn <= room;
    mse_critf(MSE_LOG_INFO, "vpipe %s %s on %s gives up (%s): %s", tag(vp), vp->cur.name, vp->hw->name,
              why, resend ? "reopen at this keyframe" : replay ? "replaying" : "resync");
    if (replay) {
        rn = vp->qn;
        memcpy(rp, vp->q, (size_t)rn * sizeof(rp[0]));
        vp->qn = 0;
    }
    q_clear(vp);
    dec_free(vp, &vp->cur);
    vp->errs = 0;
    if (at == FB_CONSUMED && !resend)
        pend_pop(vp);
    r = open_dec(vp, "fallback");
    if (r < 0 || !(resend || replay)) {
        for (i = 0; i < rn; i++)
            av_packet_free(&rp[i]);
        if (r < 0)
            return r;
        pend_clear(vp);
        return VP_RESYNC;
    }
    memmove(&vp->pend[rn], &vp->pend[0], (size_t)vp->pend_n * sizeof(vp->pend[0]));
    memmove(&vp->pend_rap[rn], &vp->pend_rap[0], (size_t)vp->pend_n * sizeof(vp->pend_rap[0]));
    for (i = 0; i < rn; i++) {
        vp->pend[i] = rp[i];
        vp->pend_rap[i] = 0;
    }
    vp->pend_n += rn;
    return VP_RETRY;
}

static int reopen_resync(VPipe *vp)
{
    int r;
    q_clear(vp);
    pend_clear(vp);
    dec_free(vp, &vp->cur);
    r = open_dec(vp, "errors");
    return r < 0 ? r : VP_RESYNC;
}

static int decode_error(VPipe *vp, int err, int at)
{
    const char *name = vp->cur.name;
    enum AVCodecID id = vp->par->codec_id;
    if (vp->errs++ == 0 && !(vp->cur.hw && err == AVERROR(ENOSYS)))
        mse_critf(MSE_LOG_WARN, "vpipe %s %s decode error: %s", tag(vp), name, av_err2str(err));
    if (vp->cur.hw) {
        if (vp->hw->lost && vp->hw->lost())
            return fallback(vp, at, "device lost");
        if (err == AVERROR(ENOSYS) || vp->cur.sw_pick || ++vp->hw_errs >= VP_HW_ERRS) {
            vp->hw_errs = 0;
            latch(vp, id, err == AVERROR(ENOSYS) ? "decoder unavailable" : "decode errors");
            return fallback(vp, at, av_err2str(err));
        }
        return err;
    }
    if (err == AVERROR(ENOSYS))
        return dead(vp, "%s cannot decode %s", name, avcodec_get_name(id));
    if (vp->prm.sw_error_reopen) {
        mse_critf(MSE_LOG_WARN, "vpipe %s %s reopens after a decode error: %s", tag(vp), name,
                  av_err2str(err));
        return reopen_resync(vp);
    }
    if (vp->errs < VP_SW_ERRS)
        return err;
    if (vp->err_reopens >= VP_SW_REOPENS)
        return dead(vp, "%s: no picture through %d reopens: %s", name, vp->err_reopens,
                    av_err2str(err));
    mse_critf(MSE_LOG_WARN, "vpipe %s %s reopens after %d decode errors: %s", tag(vp), name, vp->errs,
              av_err2str(err));
    vp->errs = 0;
    vp->err_reopens++;
    return reopen_resync(vp);
}

/* pend[0] into cur: 0 = in, EAGAIN = cur is full, VP_RETRY, VP_RESYNC, VP_DEAD, or the error the
   packet was dropped for. */
static int feed(VPipe *vp)
{
    AVPacket *pkt = vp->pend[0];
    int r;
    if (vp->cur.hw && vp->hw->lost && vp->hw->lost())
        return fallback(vp, FB_REJECTED, "device lost");
    r = dec_send(vp, &vp->cur, pkt);
    if (r == AVERROR(EAGAIN))
        return r;
    if (r >= 0) {
        q_track(vp, pkt);
        if (vp->cur.hw && vp->cur.sw_pick)
            return fallback(vp, FB_CONSUMED, "software format");
        return 0;
    }
    if (r == AVERROR_EOF)
        return r;
    return decode_error(vp, r, FB_REJECTED);
}

static int push(VPipe *vp)
{
    int r;
    while (vp->pend_n > 0) {
        r = feed(vp);
        if (r == VP_RETRY)
            continue;
        if (r == AVERROR(EAGAIN) || r == VP_RESYNC || r == VP_DEAD)
            return r;
        pend_pop(vp);
        if (r < 0)
            return r;
    }
    if (vp->pend_drain) {
        r = dec_send(vp, &vp->cur, NULL);
        if (r == AVERROR(EAGAIN))
            return r;
        vp->pend_drain = 0;
        if (r < 0 && r != AVERROR_EOF)
            return r;
    }
    return 0;
}

static int pend_add(VPipe *vp, const AVPacket *pkt, int is_rap)
{
    AVPacket *c = av_packet_clone(pkt);
    if (!c)
        return AVERROR(ENOMEM);
    vp->pend[vp->pend_n] = c;
    vp->pend_rap[vp->pend_n] = is_rap;
    vp->pend_n++;
    return 0;
}

/* A different presenter was set: if it changes the hardware decision, cur drains into old and a
   new decoder starts at this keyframe. */
static int reconfig_at_key(VPipe *vp)
{
    const char *refusal;
    int zero_copy;
    if (vp->pend_n > 0 || dec_open(&vp->old))
        return 0;
    atomic_store(&vp->reconfig, 0);
    refusal = hw_refusal(vp, &zero_copy);
    if ((refusal == NULL) == vp->cur.hw)
        return 0;
    mse_critf(MSE_LOG_INFO, "vpipe %s presenter changed: %s leaves %s at a keyframe", tag(vp),
              vp->cur.name, vp->cur.hw ? vp->hw->name : "software");
    vp->old = vp->cur;
    memset(&vp->cur, 0, sizeof(vp->cur));
    q_clear(vp);
    if (dec_send(vp, &vp->old, NULL) < 0)
        dec_free(vp, &vp->old);
    return open_dec(vp, "reconfig");
}

VPipe *vpipe_create(const VpParams *prm)
{
    VPipe *vp = calloc(1, sizeof(*vp));
    if (!vp)
        return NULL;
    vp->prm = *prm;
    pthread_mutex_init(&vp->pmu, NULL);
    atomic_init(&vp->reconfig, 0);
    atomic_init(&vp->frames, 0);
    return vp;
}

void vpipe_destroy(VPipe *vp)
{
    if (!vp)
        return;
    vpipe_close(vp);
    vpresent_release(vp->vp);
    vpresent_release(vp->caps);
    pthread_mutex_destroy(&vp->pmu);
    free(vp);
}

int vpipe_open(VPipe *vp, const AVCodecParameters *par, AVRational pkt_timebase)
{
    AVCodecParameters *cp = avcodec_parameters_alloc();
    vpipe_close(vp);
    vp->failure[0] = 0;
    vp->errs = vp->hw_errs = vp->err_reopens = 0;
    if (!cp || avcodec_parameters_copy(cp, par) < 0) {
        avcodec_parameters_free(&cp);
        return dead(vp, "out of memory");
    }
    pthread_mutex_lock(&vp->pmu);
    vp->par = cp;
    pthread_mutex_unlock(&vp->pmu);
    vp->tb = pkt_timebase;
    return open_dec(vp, "open");
}

void vpipe_close(VPipe *vp)
{
    AVCodecParameters *par;
    dec_free(vp, &vp->old);
    dec_free(vp, &vp->cur);
    q_clear(vp);
    pend_clear(vp);
    vp->desc[0] = 0;
    pthread_mutex_lock(&vp->pmu);
    par = vp->par;
    vp->par = NULL;
    pthread_mutex_unlock(&vp->pmu);
    avcodec_parameters_free(&par);
}

int vpipe_is_open(const VPipe *vp)
{
    return dec_open(&vp->cur);
}

void vpipe_flush(VPipe *vp)
{
    dec_free(vp, &vp->old);
    q_clear(vp);
    pend_clear(vp);
    vp->errs = 0;
    vp->err_reopens = 0;
    if (vp->cur.sess)
        vp->hw->session_flush(vp->cur.sess);
    else if (vp->cur.ctx)
        avcodec_flush_buffers(vp->cur.ctx);
    vp->cur.got_frame = 0;
}

int vpipe_send(VPipe *vp, const AVPacket *pkt, int is_rap)
{
    int r;
    if (!dec_open(&vp->cur))
        return vp->failure[0] ? VP_DEAD : AVERROR(EINVAL);
    if (!pkt) {
        vp->pend_drain = 1;
    } else {
        if (is_rap && atomic_load(&vp->reconfig) && (r = reconfig_at_key(vp)) < 0)
            return r;
        if (vp->pend_n >= VP_PEND_MAX)
            return AVERROR(EAGAIN);
        if ((r = pend_add(vp, pkt, is_rap)) < 0)
            return r;
    }
    r = push(vp);
    return r == AVERROR(EAGAIN) ? 0 : r;
}

static void frame_out(VPipe *vp)
{
    if (vp->errs > 1)
        mse_logf("vpipe %s %s recovered after %d errors", tag(vp), vp->cur.name, vp->errs);
    vp->errs = 0;
    vp->err_reopens = 0;
    if (vp->cur.hw)
        vp->hw_errs = 0;
    if (!vp->cur.got_frame) {
        vp->cur.got_frame = 1;
        q_clear(vp);
    }
}

int vpipe_receive(VPipe *vp, AVFrame *out)
{
    int r, i;
    if (dec_open(&vp->old)) {
        r = dec_receive(vp, &vp->old, out);
        if (r == 0)
            return 0;
        dec_free(vp, &vp->old);
    }
    for (i = 0; i < 64; i++) {
        if (!dec_open(&vp->cur))
            return vp->failure[0] ? VP_DEAD : AVERROR(EINVAL);
        r = dec_receive(vp, &vp->cur, out);
        if (r == 0) {
            frame_out(vp);
            return 0;
        }
        if (r == AVERROR(EAGAIN) && (vp->pend_n > 0 || vp->pend_drain)) {
            r = push(vp);
            if (r == 0 || r == AVERROR(EAGAIN))
                continue;
            return r;
        }
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF)
            return r;
        r = decode_error(vp, r, FB_RECEIVE);
        if (r != VP_RETRY)
            return r;
    }
    return AVERROR(EAGAIN);
}

void vpipe_set_presenter(VPipe *vp, VPresenter *p, const void *engine)
{
    VPresenter *old, *old_caps = NULL;
    vpresent_retain(p);
    pthread_mutex_lock(&vp->pmu);
    old = vp->vp;
    vp->vp = p;
    vp->engine = engine;
    if (p && p != vp->caps) {
        old_caps = vp->caps;
        vpresent_retain(p);
        vp->caps = p;
        atomic_store(&vp->reconfig, 1);
    }
    pthread_mutex_unlock(&vp->pmu);
    vpresent_release(old);
    vpresent_release(old_caps);
}

int vpipe_present(VPipe *vp, const AVFrame *f)
{
    VPresenter *p;
    const void *engine;
    VpColor c;
    int r;
    pthread_mutex_lock(&vp->pmu);
    p = vp->vp;
    engine = vp->engine;
    vpresent_retain(p);
    c = vp_color_of(f, vp->par);
    pthread_mutex_unlock(&vp->pmu);
    r = p ? vpresent_show_frame(p, engine, f, c) : VP_DROPPED;
    vpresent_release(p);
    if (r == VP_OK)
        atomic_fetch_add(&vp->frames, 1);
    return r;
}

void vpipe_flush_picture(VPipe *vp)
{
    VPresenter *p;
    const void *engine;
    pthread_mutex_lock(&vp->pmu);
    p = vp->vp;
    engine = vp->engine;
    vpresent_retain(p);
    pthread_mutex_unlock(&vp->pmu);
    vpresent_flush(p, engine);
    vpresent_release(p);
}

int vpipe_frames(VPipe *vp)
{
    return atomic_load(&vp->frames);
}

void vpipe_new_stream(VPipe *vp)
{
    vp->latched_n = 0;
}

int vpipe_failure(const VPipe *vp, char *out, int cap)
{
    if (cap > 0)
        snprintf(out, (size_t)cap, "%s", vp->failure);
    return (int)strlen(vp->failure);
}

const char *vpipe_describe(const VPipe *vp)
{
    return vp->desc;
}
