/* Pull-based libav MSE decoder. Kotlin owns the clock and sinks. */
#include "mse_ffmpeg.h"
#include "vpresent.h"

#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/channel_layout.h>
#include <libavutil/cpu.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libavutil/pixfmt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#ifdef __APPLE__
#include <TargetConditionals.h>
#include <os/log.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixdesc.h>
#include <CoreVideo/CoreVideo.h>
#include "mse_vt_present.h"
#include "mse_vt_blit.h"
#endif
#if defined(__linux__) && !defined(__ANDROID__)
#define MSE_LINUX_HW 1
#include "mse_hwdev.h"
#endif
#ifdef MSE_WIN_HW
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
#include "mse_d3d11.h"
#endif

#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef __ANDROID__
#include <stdbool.h>
#endif
#ifdef __ANDROID__
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#endif

typedef struct {
    double pts;
    double dts;
    double dur;
    int key;
    int sent;
    uint8_t *data;
    size_t n;
} Cf;

typedef struct {
    Cf *f;
    size_t n;
    size_t cap;
    size_t rd;
    size_t bytes;
} Cfb;

struct MseAv {
    pthread_mutex_t mu;
    pthread_mutex_t amu;
    uint8_t *ainit;
    size_t ainit_n;
    uint8_t *vinit;
    size_t vinit_n;
    Cfb af;
    Cfb vf;
    AVCodecContext *adec;
    AVCodecContext *vdec;
    SwrContext *swr;
    int swr_rate;
    int swr_ch;
    int swr_fmt;
    uint8_t *pcm;
    size_t pcm_n;
    AVFrame *afr;
    VPresenter *cpu;
    /* Last picture cpu handed back; written by its sink on the video thread. */
    uint8_t *cpu_bgra;
    int cpu_w, cpu_h;
#if defined(MSE_LINUX_HW) || defined(MSE_WIN_HW)
    int hw_errs; /* decode errors since the last good GPU frame */
#endif
#ifdef MSE_WIN_HW
    /* Codec d3d11va failed on; it decodes in software (libvpx / libdav1d) until changeType. */
    enum AVCodecID d3d11_fail_id;
    const void *present_owner;
#endif
    int rate;
    int channels;
    double duration;
    double a_sent;
    double v_sent;
    uint8_t *pend[2];
    size_t pend_n[2];
    size_t pend_cap[2];
    /* Cluster Timecode (0xE7) from a prefix demux, prepended onto SimpleBlock
       continuations so PTS stay cluster-relative. */
    uint8_t cluster_tc[2][16];
    size_t cluster_tc_n[2];
    int need_rap[2];
    int v_reopen;
    double starve_log_pts;
    double starve_log_clock;
    AVFrame *vfr;
    AVFrame *vhold;
    AVBSFContext *vbsf;
    AVCodecParameters *vpar;
    /* New quality (size/extradata) while leftover still covers currentTime.
       Chrome changeType keeps those packets; switch the decoder at the new GOP. */
    AVCodecParameters *vpar_next;
    double v_switch_pts;
    int v_rest;
#ifdef __APPLE__
    /* iOS app background: decoder closed, leftover kept. Not JS audio-only rest. */
    int v_bg_park;
#endif
    /* After seek: present RAP-to-playhead even if pts is behind clock. */
    int show_late;
    /* 0 until a picture is released to the surface (seek sets v_sent). */
    int v_pictured;
    /* H.264: AV_PKT_FLAG_KEY can be a recovery point, not IDR. Don't show
       until an IDR NAL has been sent (open-GOP seek = broken picture). */
    int v_seen_idr;
#ifdef __APPLE__
    void *vt_layer;
    void *vt_dec;
    /* Native-size BGRA for the layer when it cannot take the frame's YUV. */
    VpCpu *vt_cpu;
    /* Decoded pictures waiting in PTS (display) order. H.264 B-frames are
       sent in DTS order; showing them as they decode is the broken picture. */
    void *vt_q_pb[8];
    double vt_q_pts[8];
    int vt_q_w[8], vt_q_h[8];
    int vt_q_n;
    /* Codec that failed VideoToolbox. Skip VT for that id (simulator AV1). */
    enum AVCodecID vt_fail_id;
#endif
#ifdef __ANDROID__
    AMediaCodec *mc;
    ANativeWindow *mc_win;
    enum AVCodecID mc_codec_id;
    int mc_fail;
    /* Failed opens in a row; the next try waits until mc_retry_us. */
    int mc_open_tries;
    int64_t mc_retry_us;
    /* Set once MediaCodec gave up on this stream; the player fails playback on it. */
    char mc_failure[128];
    int mc_hold;
    int mc_hold_ix;
    int64_t mc_hold_pts_us;
    /* vbsf output MediaCodec had no input slot for; queued before anything else. */
    AVPacket *mc_bsf_out;
    int mc_w;
    int mc_h;
    int mc_cfg_w;
    int mc_cfg_h;
    double cost_q_pts[32];
    int64_t cost_q_t[32];
    int cost_q_i;
    double mc_dec_ema;
    int64_t mc_pts0;
    int mc_pts0_set;
    int mc_eos;
    double mc_in_pts;
    int mc_n_shown;
    int mc_n_dropped;
    int64_t mc_stats_t0;
#endif
};

#if !PRODUCTION
static int vdbg_n;
#endif
static void vf_resync_key(MseAv *a, double t);
static void close_video_decoder(MseAv *a);
#ifdef __ANDROID__
static int64_t mc_now_us(void);
static void mc_flush(MseAv *a);
static void mc_hold_drop(MseAv *a, int render);
static void mc_cost_note_in(MseAv *a, double pts);
static void mc_cost_note_out(MseAv *a, double pts);
#endif
static void maybe_apply_video_rep(MseAv *a);

#ifdef __ANDROID__
#define MSE_SEND_AHEAD 0.25
#define MC_OPEN_WAIT 1
#define MC_OPEN_TRIES 3
#define MC_OPEN_RETRY_US 300000
#else
#define MSE_SEND_AHEAD 0.08
#endif

static double mc_send_ahead(const MseAv *a)
{
#ifdef __ANDROID__
    double ahead;
    /* Live head: keep ~1s in-flight until the first picture. */
    if (!a->v_pictured)
        return 1.0;
    if (a->mc_dec_ema <= 0)
        return MSE_SEND_AHEAD;
    /* Submit a bit more than measured queue→output so the 80ms late
       window does not see decode latency as lateness. */
    ahead = a->mc_dec_ema * 1.5 + 0.05;
    if (ahead < 0.12)
        ahead = 0.12;
    if (ahead > 0.45)
        ahead = 0.45;
    return ahead;
#else
    (void)a;
    return MSE_SEND_AHEAD;
#endif
}

#ifdef __ANDROID__
/* C2 treats flush-before-any-input as lastFlushIndex=0, then the IDR
   (frameIndex=0) is ignored. Live seek hits that; VOD does not. */
static void mc_flush(MseAv *a)
{
    if (!a->mc)
        return;
    mc_hold_drop(a, 0);
    if (a->mc_bsf_out)
        av_packet_unref(a->mc_bsf_out);
    if (a->vbsf)
        av_bsf_flush(a->vbsf);
    if (!a->mc_pts0_set)
        return;
    AMediaCodec_flush(a->mc);
    a->mc_pts0_set = 0;
    a->mc_eos = 0;
    a->v_pictured = 0;
}
#endif

static int h264_nal_len_size(const AVCodecParameters *par)
{
    if (!par || !par->extradata || par->extradata_size < 5)
        return 4;
    if (par->extradata[0] == 1)
        return (par->extradata[4] & 3) + 1;
    return 4;
}

static int h264_is_idr(const uint8_t *p, size_t n, int nal_len)
{
    size_t i = 0;
    int avcc = nal_len >= 1 && nal_len <= 4;
    if (!p || n < 2)
        return 0;
    if (avcc) {
        while (i + (size_t)nal_len < n) {
            uint32_t sz = 0;
            int k;
            for (k = 0; k < nal_len; k++)
                sz = (sz << 8) | p[i + (size_t)k];
            i += (size_t)nal_len;
            if (sz < 1 || i + sz > n)
                return 0;
            if ((p[i] & 0x1F) == 5)
                return 1;
            i += sz;
        }
        return 0;
    }
    for (i = 0; i + 4 < n; i++) {
        size_t nal;
        if (p[i] || p[i + 1])
            continue;
        if (p[i + 2] == 1)
            nal = i + 3;
        else if (p[i + 2] == 0 && p[i + 3] == 1)
            nal = i + 4;
        else
            continue;
        if (nal < n && (p[nal] & 0x1F) == 5)
            return 1;
    }
    return 0;
}

static int coded_is_rap(const MseAv *a, const Cf *c)
{
    if (!c)
        return 0;
    if (a->vpar && a->vpar->codec_id == AV_CODEC_ID_H264)
        return h264_is_idr(c->data, c->n, h264_nal_len_size(a->vpar));
    return c->key;
}

static void (*g_log_cb)(int level, const char *msg);

static void mse_emit(int level, char *msg)
{
    size_t n;
    void (*fn)(int, const char *);
    if (!msg)
        return;
    n = strlen(msg);
    while (n && (msg[n - 1] == '\n' || msg[n - 1] == '\r'))
        msg[--n] = 0;
    if (!n)
        return;
    fn = g_log_cb;
    if (fn)
        fn(level, msg);
}

#ifdef __APPLE__
static os_log_t mse_oslog(void)
{
    static os_log_t log;
    static int once;
    if (!once) {
        once = 1;
        log = os_log_create("com.ymusic.mse", "v");
    }
    return log;
}

#endif

static void mse_av_log(void *ptr, int level, const char *fmt, va_list vl)
{
    if (level <= AV_LOG_ERROR) {
        char buf[512];
        char line[528];
        va_list copy;
        size_t n;
        va_copy(copy, vl);
        vsnprintf(buf, sizeof(buf), fmt, copy);
        va_end(copy);
        n = strlen(buf);
        while (n && (buf[n - 1] == '\n' || buf[n - 1] == '\r'))
            buf[--n] = 0;
#ifdef __APPLE__
        os_log_with_type(mse_oslog(), OS_LOG_TYPE_ERROR, "ffmpeg %{public}s", buf);
        if (fmt && strstr(fmt, "hwaccel initialisation returned error")) {
            AVCodecContext *ctx = ptr;
            MseAv *a = ctx && ctx->opaque ? (MseAv *)ctx->opaque : NULL;
            if (a && ctx->codec_id)
                a->vt_fail_id = ctx->codec_id;
        }
#endif
#ifdef MSE_LINUX_HW
        if (fmt && strstr(fmt, "hwaccel initialisation returned error")) {
            AVCodecContext *ctx = ptr;
            if (ctx && ctx->codec_id)
                mse_hwdev_mark_failed(ctx->codec_id);
        }
#endif
        snprintf(line, sizeof(line), "ffmpeg %s", buf);
        mse_emit(MSE_LOG_WARN, line);
    } else {
        (void)ptr;
        (void)fmt;
        (void)vl;
    }
}

void mse_set_log_callback(void (*fn)(int level, const char *msg))
{
    g_log_cb = fn;
    av_log_set_level(AV_LOG_ERROR);
    av_log_set_callback(mse_av_log);
}

#if !PRODUCTION
void mse_logf(const char *fmt, ...)
{
    char msg[768];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    mse_emit(MSE_LOG_DEBUG, msg);
}
#endif

void mse_critf(int level, const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    mse_emit(level, buf);
}

#if !PRODUCTION
static void vlog(const char *fmt, ...)
{
    char buf[512];
    char line[528];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    snprintf(line, sizeof(line), "mse-v %s", buf);
    mse_emit(MSE_LOG_DEBUG, line);
}
#else
#define vlog(...) ((void)0)
#endif

static double now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

#if !PRODUCTION
static int vdbg_ok(void)
{
    vdbg_n++;
    return vdbg_n <= 120 || (vdbg_n % 40) == 0;
}

static void vlog_state(MseAv *a, double clock, const char *why, double extra)
{
    double p0 = -1, p1 = -1, nk = -1, hp = -1, np = -1;
    int nkey = 0;
    size_t i;
    if (a->vf.n) {
        p0 = a->vf.f[0].pts;
        p1 = a->vf.f[a->vf.n - 1].pts;
    }
    if (a->vf.rd < a->vf.n)
        np = a->vf.f[a->vf.rd].pts;
    for (i = a->vf.rd; i < a->vf.n; i++) {
        if (a->vf.f[i].key) {
            if (nk < 0)
                nk = a->vf.f[i].pts;
            nkey++;
        }
    }
    if (a->vhold && a->vhold->pts != AV_NOPTS_VALUE)
        hp = a->vhold->pts / 1000.0;
    vlog("%s clock=%.3f extra=%.3f v_sent=%.3f hold=%.3f vf rd=%zu/%zu next=%.3f q=%.3f..%.3f nkey=%d next_key=%.3f rap=%d delay=%d bframes=%d",
         why, clock, extra, a->v_sent, hp, a->vf.rd, a->vf.n, np, p0, p1, nkey, nk, a->need_rap[0],
         a->vdec ? a->vdec->delay : -1, a->vdec ? a->vdec->has_b_frames : -1);
}
#else
#define vdbg_ok() 0
#define vlog_state(...) ((void)0)
#endif

static size_t vf_send_rd(const MseAv *a)
{
    size_t rd = a->vf.rd;
    while (rd < a->vf.n && a->need_rap[0] && !coded_is_rap(a, &a->vf.f[rd]))
        rd++;
    return rd;
}

static void vlog_hold_far(const char *why, double clock, double extra)
{
    static double last;
    double now = now_ms();
    if (extra < 1.0)
        return;
    if (now - last < 1000.0)
        return;
    last = now;
    mse_critf(MSE_LOG_INFO, "mse-v %s clock=%.3f extra=%.3f", why, clock, extra);
}

static void vlog_starve_ahead(MseAv *a, double clock, size_t rd)
{
    double nxt = a->vf.f[rd].pts;
    double extra = nxt - clock;
    /* Under 1s ahead is ordinary send pacing (every frame), not a gap. */
    if (extra < 1.0)
        return;
    if (nxt == a->starve_log_pts && clock - a->starve_log_clock < 1.0)
        return;
    a->starve_log_pts = nxt;
    a->starve_log_clock = clock;
    mse_critf(MSE_LOG_INFO, "mse-v starve-ahead clock=%.3f extra=%.3f vf=%zu/%zu next=%.3f",
              clock, extra, rd, a->vf.n, nxt);
    vlog_state(a, clock, "starve-ahead", extra);
}

static void vlog_starve_empty(MseAv *a, double clock, double extra)
{
    static double last_empty;
    double now = now_ms();
    if (now - last_empty <= 1000.0)
        return;
    last_empty = now;
    mse_critf(MSE_LOG_INFO, "mse-v starve-empty clock=%.3f vf=%zu", clock, a->vf.n);
    vlog_state(a, clock, "starve-empty", extra);
    (void)extra;
}

static int cf_cmp(const void *a, const void *b)
{
    double d = ((const Cf *)a)->dts - ((const Cf *)b)->dts;
    if (d == 0)
        d = ((const Cf *)a)->pts - ((const Cf *)b)->pts;
    return (d > 0) - (d < 0);
}

static void cfb_free(Cfb *b)
{
    size_t i;
    for (i = 0; i < b->n; i++)
        free(b->f[i].data);
    free(b->f);
    memset(b, 0, sizeof(*b));
}

static int cfb_push(Cfb *b, double pts, double dts, double dur, int key, const uint8_t *p, size_t n)
{
    uint8_t *d;
    if (!p || !n)
        return 0;
    if (b->n >= b->cap) {
        size_t c = b->cap ? b->cap * 2 : 256;
        Cf *nf = realloc(b->f, c * sizeof(*nf));
        if (!nf)
            return -1;
        b->f = nf;
        b->cap = c;
    }
    d = malloc(n);
    if (!d)
        return -1;
    memcpy(d, p, n);
    b->f[b->n].pts = pts;
    b->f[b->n].dts = dts;
    b->f[b->n].dur = dur > 0 ? dur : 0.02;
    b->f[b->n].key = key;
    b->f[b->n].sent = 0;
    b->f[b->n].data = d;
    b->f[b->n].n = n;
    b->n++;
    b->bytes += n;
    return 0;
}

static void cfb_drop(Cfb *b, double start, double end)
{
    size_t i, w = 0, dropped_before = 0;
    for (i = 0; i < b->n; i++) {
        if (b->f[i].pts + 1e-4 >= start && b->f[i].pts < end) {
            if (i < b->rd)
                dropped_before++;
            if (b->bytes >= b->f[i].n)
                b->bytes -= b->f[i].n;
            else
                b->bytes = 0;
            free(b->f[i].data);
            continue;
        }
        b->f[w++] = b->f[i];
    }
    b->n = w;
    if (b->rd > dropped_before)
        b->rd -= dropped_before;
    else
        b->rd = 0;
    if (b->rd > b->n)
        b->rd = b->n;
}

static uint64_t le64(const uint8_t *p)
{
    return (uint64_t)p[0]
        | ((uint64_t)p[1] << 8)
        | ((uint64_t)p[2] << 16)
        | ((uint64_t)p[3] << 24)
        | ((uint64_t)p[4] << 32)
        | ((uint64_t)p[5] << 40)
        | ((uint64_t)p[6] << 48)
        | ((uint64_t)p[7] << 56);
}

/* Rest-mode video UMP: YMF0 init, YMF1 + start_ms + dur_ms media. Not a container. */
static int fake_ump(const uint8_t *p, int n, int *is_init, double *pts, double *dur)
{
    if (!p || n < 4 || p[0] != 'Y' || p[1] != 'M' || p[2] != 'F')
        return 0;
    if (p[3] == '0') {
        *is_init = 1;
        *pts = 0;
        *dur = 0;
        return 1;
    }
    if (p[3] == '1' && n >= 20) {
        *is_init = 0;
        *pts = (double)le64(p + 4) / 1000.0;
        *dur = (double)le64(p + 12) / 1000.0;
        if (*dur <= 0)
            *dur = 1.0;
        return 1;
    }
    return 0;
}

static void cfb_drop_fake(Cfb *b)
{
    size_t i, w = 0, dropped_before = 0;
    for (i = 0; i < b->n; i++) {
        int fake = b->f[i].n >= 4 && b->f[i].data
            && b->f[i].data[0] == 'Y' && b->f[i].data[1] == 'M'
            && b->f[i].data[2] == 'F';
        if (fake) {
            if (i < b->rd)
                dropped_before++;
            if (b->bytes >= b->f[i].n)
                b->bytes -= b->f[i].n;
            else
                b->bytes = 0;
            free(b->f[i].data);
            continue;
        }
        b->f[w++] = b->f[i];
    }
    b->n = w;
    if (b->rd > dropped_before)
        b->rd -= dropped_before;
    else
        b->rd = 0;
    if (b->rd > b->n)
        b->rd = b->n;
}

static int looks_ebml(const uint8_t *p, size_t n)
{
    return n >= 4 && p[0] == 0x1A && p[1] == 0x45 && p[2] == 0xDF && p[3] == 0xA3;
}

static uint32_t be32(const uint8_t *p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

#define BOX_FTYP 0x66747970u
#define BOX_MOOV 0x6d6f6f76u
#define BOX_MOOF 0x6d6f6f66u
#define BOX_MDAT 0x6d646174u
#define BOX_STYP 0x73747970u
#define BOX_SIDX 0x73696478u
#define BOX_FREE 0x66726565u
#define BOX_SKIP 0x736b6970u

static int mp4_box(const uint8_t *p, size_t n, size_t *total, uint32_t *type)
{
    uint32_t sz;
    if (n < 8)
        return 0;
    sz = be32(p);
    *type = be32(p + 4);
    if (sz == 1) {
        uint64_t z;
        if (n < 16)
            return 0;
        z = ((uint64_t)be32(p + 8) << 32) | be32(p + 12);
        if (z < 16 || z > (uint64_t)n)
            return 0;
        *total = (size_t)z;
        return 1;
    }
    if (sz < 8 || (size_t)sz > n)
        return 0;
    *total = (size_t)sz;
    return 1;
}

static int looks_fmp4(const uint8_t *p, size_t n)
{
    uint32_t type;
    if (n < 8)
        return 0;
    type = be32(p + 4);
    return type == BOX_FTYP || type == BOX_MOOV || type == BOX_MOOF || type == BOX_MDAT ||
           type == BOX_STYP || type == BOX_SIDX || type == BOX_FREE || type == BOX_SKIP;
}

static int fmp4_init_len(const uint8_t *p, size_t n, size_t *len)
{
    size_t off = 0;
    while (off + 8 <= n) {
        size_t tot;
        uint32_t type;
        if (!mp4_box(p + off, n - off, &tot, &type))
            return 0;
        if (type == BOX_MOOF)
            return 0;
        off += tot;
        if (type == BOX_MOOV) {
            *len = off;
            return 1;
        }
    }
    return 0;
}

static int fmp4_frag_len(const uint8_t *p, size_t n, size_t *len)
{
    size_t off = 0;
    int saw_moof = 0, saw_mdat = 0;
    while (off + 8 <= n) {
        size_t tot;
        uint32_t type;
        if (!mp4_box(p + off, n - off, &tot, &type))
            return 0;
        off += tot;
        if (type == BOX_MOOF)
            saw_moof = 1;
        if (type == BOX_MDAT)
            saw_mdat = 1;
        if (saw_moof && saw_mdat) {
            *len = off;
            return 1;
        }
    }
    return 0;
}

static int has_marker(const uint8_t *p, size_t n, const uint8_t *m, size_t mn)
{
    size_t i;
    if (n < mn)
        return 0;
    for (i = 0; i + mn <= n; i++) {
        if (memcmp(p + i, m, mn) == 0)
            return 1;
    }
    return 0;
}

#define MSE_ID_EBML         0x1A45DFA3u
#define MSE_ID_SEGMENT      0x18538067u
#define MSE_ID_CLUSTER      0x1F43B675u
#define MSE_ID_SIMPLEBLOCK  0xA3u
#define MSE_ID_BLOCKGROUP   0xA0u
#define MSE_ID_BLOCK        0xA1u
#define MSE_ID_TIMECODE     0xE7u
#define MSE_ID_PREVSIZE     0xABu
/* log28: 1440p60 itag 308 Cluster clen=9425394. 8MB treated that GOP as
   incomplete, leftover stayed empty, SABR would not refetch. 4K GOPs are larger. */
#define MSE_CLUSTER_MAX (128ull * 1024ull * 1024ull)

static int ebml_head(const uint8_t *p, size_t n, uint32_t *id, uint64_t *sz, int *unk, int *hdr)
{
    unsigned mask;
    int idw, szw, i;
    uint64_t v;
    if (!p || n < 2)
        return 0;
    mask = 0x80;
    idw = 1;
    while (idw <= 4 && (p[0] & mask) == 0) {
        mask >>= 1;
        idw++;
    }
    if (idw > 4 || (size_t)idw >= n)
        return 0;
    v = 0;
    for (i = 0; i < idw; i++)
        v = (v << 8) | p[i];
    *id = (uint32_t)v;
    mask = 0x80;
    szw = 1;
    while (szw <= 8 && (p[idw] & mask) == 0) {
        mask >>= 1;
        szw++;
    }
    if (szw > 8 || (size_t)(idw + szw) > n)
        return 0;
    v = (uint64_t)(p[idw] & (mask - 1));
    *unk = (v == (uint64_t)(mask - 1));
    for (i = 1; i < szw; i++) {
        if (p[idw + i] != 0xFF)
            *unk = 0;
        v = (v << 8) | p[idw + i];
    }
    *sz = v;
    *hdr = idw + szw;
    return 1;
}

/* 1 = walked; *off_out = first Cluster offset, or n if the buffer is init-only. 0 = need more. */
static int find_cluster(const uint8_t *p, size_t n, size_t *off_out)
{
    size_t off = 0;
    while (off < n) {
        uint32_t id;
        uint64_t sz;
        int unk, hdr;
        if (!ebml_head(p + off, n - off, &id, &sz, &unk, &hdr))
            return 0;
        if (id == MSE_ID_CLUSTER) {
            *off_out = off;
            return 1;
        }
        /* MSE init is EBML + Segment whose size covers the whole stream
           (tens of MB). Enter those containers; do not wait for the Segment
           payload to arrive before peeling Info/Tracks. */
        if (unk || id == MSE_ID_SEGMENT || id == MSE_ID_EBML) {
            off += (size_t)hdr;
            continue;
        }
        if (sz > 64u * 1024u * 1024u)
            return 0;
        if (off + (size_t)hdr + (size_t)sz > n)
            return 0;
        off += (size_t)hdr + (size_t)sz;
    }
    *off_out = n;
    return 1;
}

typedef struct {
    const uint8_t *p;
    size_t n;
    size_t pos;
} MemIO;

static int mem_read(void *opaque, uint8_t *buf, int buf_size)
{
    MemIO *m = opaque;
    size_t left;
    if (!m || m->pos >= m->n)
        return AVERROR_EOF;
    left = m->n - m->pos;
    if (left > (size_t)buf_size)
        left = (size_t)buf_size;
    memcpy(buf, m->p + m->pos, left);
    m->pos += left;
    return (int)left;
}

static int64_t mem_seek(void *opaque, int64_t offset, int whence)
{
    MemIO *m = opaque;
    if (!m)
        return -1;
    if (whence == AVSEEK_SIZE)
        return (int64_t)m->n;
    if (whence == SEEK_CUR)
        offset = (int64_t)m->pos + offset;
    else if (whence == SEEK_END)
        offset = (int64_t)m->n + offset;
    else if (whence != SEEK_SET)
        return -1;
    if (offset < 0 || offset > (int64_t)m->n)
        return -1;
    m->pos = (size_t)offset;
    return offset;
}

static int near_std_fps(double fps)
{
    static const double want[] = { 24, 25, 30, 48, 50, 60 };
    int i;
    for (i = 0; i < 6; i++) {
        double d = fps - want[i];
        if (d < 0)
            d = -d;
        if (d < 1.5)
            return 1;
    }
    return 0;
}

/* SABR WebM 60fps clusters often demux at 12fps ×5 duration (18s → 90s,
   266s → 1321s, log21 tb=1/11988). Scale so GOP fps is a standard rate.
   Do not use the playhead (90/3.7 ≈ 24 is not a timescale). WebM only: an MP4 at a real 12 fps
   matches the same pattern. Times scale around ts_off, the SourceBuffer timestampOffset. */
static void scale_gop_pts(Cfb *tmp, double ts_off)
{
    double first, last, span, fps;
    int n, k, i;
    if (!tmp || tmp->n < 24)
        return;
    n = (int)tmp->n;
    first = tmp->f[0].pts;
    last = tmp->f[n - 1].pts + tmp->f[n - 1].dur;
    span = last - first;
    if (span < 0.5)
        return;
    fps = (n - 1) / span;
    if (near_std_fps(fps))
        return;
    {
        int best_k = 0;
        double best = 1e9;
        for (k = 2; k <= 10; k++) {
            double f = fps * (double)k;
            double score;
            if (!near_std_fps(f))
                continue;
            score = f;
            /* Prefer 30/60 over 24 when 12fps ×2 and ×5 both match. */
            if ((f > 29 && f < 31) || (f > 59 && f < 61))
                score -= 100;
            else
                score += 10;
            if (score < best) {
                best = score;
                best_k = k;
            }
        }
        if (best_k > 1) {
            vlog("pts/%d fps=%.2f->%.2f first=%.3f n=%d", best_k, fps, fps * best_k, first, n);
            for (i = 0; i < n; i++) {
                tmp->f[i].pts = ts_off + (tmp->f[i].pts - ts_off) / (double)best_k;
                tmp->f[i].dts = ts_off + (tmp->f[i].dts - ts_off) / (double)best_k;
                tmp->f[i].dur /= (double)best_k;
            }
        }
    }
}

static int demux_packets(const uint8_t *init, size_t init_n, const uint8_t *seg, size_t seg_n,
                         double ts_off, int want_video, Cfb *dst, AVCodecParameters **par,
                         double *fmt_dur)
{
    uint8_t *blob = NULL;
    size_t bn = init_n + seg_n;
    unsigned char *iobuf;
    AVIOContext *avio = NULL;
    AVFormatContext *fmt = NULL;
    MemIO mem;
    AVPacket *pkt = NULL;
    int st_idx, nadd = 0;
    enum AVMediaType want = want_video ? AVMEDIA_TYPE_VIDEO : AVMEDIA_TYPE_AUDIO;
    static const uint8_t cl[4] = { 0x1F, 0x43, 0xB6, 0x75 };

    if (init_n && seg_n && looks_ebml(seg, seg_n) &&
        !has_marker(seg, seg_n < 64 ? seg_n : 64, cl, 4) && looks_ebml(init, init_n))
        init_n = 0;
    bn = init_n + seg_n;
    if (!bn)
        return 0;
    blob = malloc(bn);
    if (!blob)
        return -1;
    if (init_n)
        memcpy(blob, init, init_n);
    if (seg_n)
        memcpy(blob + init_n, seg, seg_n);
    mem.p = blob;
    mem.n = bn;
    mem.pos = 0;
    iobuf = av_malloc(4096);
    if (!iobuf) {
        free(blob);
        return -1;
    }
    avio = avio_alloc_context(iobuf, 4096, 0, &mem, mem_read, NULL, mem_seek);
    if (!avio) {
        av_free(iobuf);
        free(blob);
        return -1;
    }
    fmt = avformat_alloc_context();
    if (!fmt)
        goto fail;
    fmt->pb = avio;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    fmt->probesize = (int64_t)(bn + 2048);
    if (avformat_open_input(&fmt, NULL, NULL, NULL) < 0)
        goto fail;
    st_idx = av_find_best_stream(fmt, want, -1, -1, NULL, 0);
    if (st_idx < 0)
        goto done;
    if (par && !*par && fmt->streams[st_idx]->codecpar) {
        *par = avcodec_parameters_alloc();
        if (*par)
            avcodec_parameters_copy(*par, fmt->streams[st_idx]->codecpar);
    }
    if (fmt_dur && fmt->duration != AV_NOPTS_VALUE) {
        double d = fmt->duration / (double)AV_TIME_BASE;
        if (d > *fmt_dur)
            *fmt_dur = d;
    }
    pkt = av_packet_alloc();
    if (!pkt)
        goto fail;
    while (av_read_frame(fmt, pkt) >= 0) {
        if (pkt->stream_index == st_idx && pkt->size > 0) {
            AVStream *st = fmt->streams[st_idx];
            double pts = 0, dts = 0, dur = 0;
            int64_t pts64 = pkt->pts;
            int64_t dts64 = pkt->dts;
            double tb = av_q2d(st->time_base);
            if (pts64 == AV_NOPTS_VALUE)
                pts64 = dts64;
            if (dts64 == AV_NOPTS_VALUE)
                dts64 = pts64;
            if (pts64 != AV_NOPTS_VALUE)
                pts = pts64 * tb;
            if (dts64 != AV_NOPTS_VALUE)
                dts = dts64 * tb;
            if (pkt->duration > 0)
                dur = pkt->duration * tb;
            if (want_video && nadd == 0)
                vlog("webm tb=%d/%d pts=%.3f", st->time_base.num, st->time_base.den, pts);
            /* WebM Cluster Timecode is milliseconds. A TimestampScale of
               2–10ms/tick (libav time_base 1/200 = 5ms) turns 18.018s into
               90.180s and a seek to 266s into 1321s (log21). 1/60000 is a
               real 60fps tick — do not treat those as milliseconds. */
            if (want_video && tb > 0.002) {
                double k = tb / 0.001;
                int ik = (int)(k + 0.5);
                double err = k - ik;
                if (err < 0)
                    err = -err;
                if (ik >= 2 && ik <= 10 && err < 0.08) {
                    if (nadd == 0)
                        vlog("webm tb=%d/%d -> 1ms", st->time_base.num, st->time_base.den);
                    if (pts64 != AV_NOPTS_VALUE)
                        pts = pts64 * 0.001;
                    if (dts64 != AV_NOPTS_VALUE)
                        dts = dts64 * 0.001;
                    if (pkt->duration > 0)
                        dur = pkt->duration * 0.001;
                }
            }
            pts += ts_off;
            dts += ts_off;
            if (cfb_push(dst, pts, dts, dur, !!(pkt->flags & AV_PKT_FLAG_KEY),
                         pkt->data, (size_t)pkt->size) == 0)
                nadd++;
        }
        av_packet_unref(pkt);
    }
done:
    av_packet_free(&pkt);
    if (fmt) {
        if (fmt->pb == avio)
            fmt->pb = NULL;
        avformat_close_input(&fmt);
    }
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    free(blob);
    return nadd;
fail:
    av_packet_free(&pkt);
    if (fmt) {
        if (fmt->pb == avio)
            fmt->pb = NULL;
        avformat_close_input(&fmt);
    }
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    free(blob);
    return -1;
}

#ifdef __APPLE__
static enum AVPixelFormat get_hw_format(AVCodecContext *ctx, const enum AVPixelFormat *pix_fmts)
{
    const enum AVPixelFormat *p;
    MseAv *a = ctx && ctx->opaque ? (MseAv *)ctx->opaque : NULL;
    int allow_vt = !a || a->vt_fail_id != ctx->codec_id;
    if (allow_vt) {
        for (p = pix_fmts; *p != AV_PIX_FMT_NONE; p++) {
            if (*p == AV_PIX_FMT_VIDEOTOOLBOX)
                return *p;
        }
    }
    for (p = pix_fmts; *p != AV_PIX_FMT_NONE; p++) {
        if (*p != AV_PIX_FMT_VIDEOTOOLBOX)
            return *p;
    }
    return pix_fmts[0];
}
#endif

#ifdef __ANDROID__
static const char *mc_mime(enum AVCodecID id)
{
    switch (id) {
    case AV_CODEC_ID_H264:
        return "video/avc";
    case AV_CODEC_ID_HEVC:
        return "video/hevc";
    case AV_CODEC_ID_VP9:
        return "video/x-vnd.on2.vp9";
    case AV_CODEC_ID_VP8:
        return "video/x-vnd.on2.vp8";
    case AV_CODEC_ID_AV1:
        return "video/av01";
    case AV_CODEC_ID_MPEG4:
        return "video/mp4v-es";
    default:
        return NULL;
    }
}

static void mc_hold_drop(MseAv *a, int render)
{
    if (!a->mc || !a->mc_hold)
        return;
    AMediaCodec_releaseOutputBuffer(a->mc, (size_t)a->mc_hold_ix, render);
    a->mc_hold = 0;
}

static void mc_close(MseAv *a)
{
    if (!a->mc)
        return;
    mc_hold_drop(a, 0);
    if (a->mc_bsf_out)
        av_packet_unref(a->mc_bsf_out);
    AMediaCodec_stop(a->mc);
    AMediaCodec_delete(a->mc);
    a->mc = NULL;
    a->mc_eos = 0;
    vlog("close mediacodec");
}

static int mc_set_avcc_csd(AMediaFormat *fmt, const uint8_t *p, int n)
{
    int i, nsps, npps, sl, pl;
    uint8_t *sps, *pps;
    if (n < 7 || p[0] != 1)
        return -1;
    i = 5;
    nsps = p[i++] & 0x1f;
    if (nsps < 1 || i + 2 > n)
        return -1;
    sl = (p[i] << 8) | p[i + 1];
    i += 2;
    if (sl <= 0 || i + sl > n)
        return -1;
    sps = malloc((size_t)sl + 4);
    if (!sps)
        return -1;
    sps[0] = sps[1] = sps[2] = 0;
    sps[3] = 1;
    memcpy(sps + 4, p + i, (size_t)sl);
    i += sl;
    if (i >= n) {
        free(sps);
        return -1;
    }
    npps = p[i++];
    if (npps < 1 || i + 2 > n) {
        free(sps);
        return -1;
    }
    pl = (p[i] << 8) | p[i + 1];
    i += 2;
    if (pl <= 0 || i + pl > n) {
        free(sps);
        return -1;
    }
    pps = malloc((size_t)pl + 4);
    if (!pps) {
        free(sps);
        return -1;
    }
    pps[0] = pps[1] = pps[2] = 0;
    pps[3] = 1;
    memcpy(pps + 4, p + i, (size_t)pl);
    AMediaFormat_setBuffer(fmt, "csd-0", sps, (size_t)sl + 4);
    AMediaFormat_setBuffer(fmt, "csd-1", pps, (size_t)pl + 4);
    free(sps);
    free(pps);
    return 0;
}

static void mc_set_csd(AMediaFormat *fmt, AVCodecParameters *par)
{
    const uint8_t *p = par->extradata;
    int n = par->extradata_size;
    if (!p || n <= 0)
        return;
    if (par->codec_id == AV_CODEC_ID_H264 && mc_set_avcc_csd(fmt, p, n) == 0)
        return;
    AMediaFormat_setBuffer(fmt, "csd-0", p, (size_t)n);
}

static int mc_open(MseAv *a)
{
    const char *mime;
    AMediaFormat *fmt;
    media_status_t st;
    int w, h;
    if (!a->mc_win || !a->vpar)
        return -1;
    mime = mc_mime(a->vpar->codec_id);
    if (!mime) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec no mime codec_id=%d", (int)a->vpar->codec_id);
        return -1;
    }
    mc_close(a);
    a->mc = AMediaCodec_createDecoderByType(mime);
    if (!a->mc) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec createDecoderByType %s failed codec_id=%d",
                  mime, (int)a->vpar->codec_id);
        return -1;
    }
    fmt = AMediaFormat_new();
    if (!fmt) {
        mc_close(a);
        return -1;
    }
    w = a->vpar->width > 0 ? a->vpar->width : 64;
    h = a->vpar->height > 0 ? a->vpar->height : 64;
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, w);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, h);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
    mc_set_csd(fmt, a->vpar);
    st = AMediaCodec_configure(a->mc, fmt, a->mc_win, NULL, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec configure %s %dx%d fail=%d", mime, w, h,
                  (int)st);
        mc_close(a);
        return -1;
    }
    st = AMediaCodec_start(a->mc);
    if (st != AMEDIA_OK) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec start fail=%d", (int)st);
        mc_close(a);
        return -1;
    }
    a->mc_codec_id = a->vpar->codec_id;
    a->mc_w = w;
    a->mc_h = h;
    a->mc_cfg_w = w;
    a->mc_cfg_h = h;
    a->mc_hold = 0;
    a->v_pictured = 0;
    a->mc_dec_ema = 0;
    a->mc_pts0 = 0;
    a->mc_pts0_set = 0;
    {
        double t = a->v_sent >= 0 ? a->v_sent : 0;
        size_t i, key = a->vf.n;
        for (i = 0; i < a->vf.n; i++) {
            if (a->vf.f[i].key && a->vf.f[i].pts <= t + 0.05)
                key = i;
        }
        if (key < a->vf.n) {
            a->vf.rd = key;
            for (i = key; i < a->vf.n; i++)
                a->vf.f[i].sent = 0;
            a->need_rap[0] = 1;
        }
    }
    if (a->vpar->codec_id == AV_CODEC_ID_VP9 && !a->vbsf) {
        const AVBitStreamFilter *f = av_bsf_get_by_name("vp9_superframe");
        if (f && av_bsf_alloc(f, &a->vbsf) == 0) {
            avcodec_parameters_copy(a->vbsf->par_in, a->vpar);
            a->vbsf->time_base_in = (AVRational){ 1, 1000 };
            if (av_bsf_init(a->vbsf) < 0)
                av_bsf_free(&a->vbsf);
        }
    }
    mse_critf(MSE_LOG_INFO, "mse-v mediacodec open %s %dx%d win=%p", mime, w, h,
              (void *)a->mc_win);
    vlog("open mediacodec %s %dx%d", mime, w, h);
    return 0;
}

static int h264_avcc_to_annexb(const uint8_t *src, int n, int nal_len, uint8_t *dst, int dst_cap, int *dn)
{
    int i = 0, o = 0;
    /* fMP4/avcC samples are length-prefixed. Do not sniff 00 00 01: an AVCC
       size of 0x000001xx (256–511 byte NALs) looks like a 3-byte start code. */
    if (nal_len < 1 || nal_len > 4 || !dst || dst_cap < 4)
        return -1;
    while (i + nal_len <= n) {
        uint32_t sz = 0;
        int k;
        for (k = 0; k < nal_len; k++)
            sz = (sz << 8) | src[i + k];
        i += nal_len;
        if (sz < 1 || i + (int)sz > n)
            break;
        if (o + 4 + (int)sz > dst_cap)
            return -1;
        dst[o] = 0;
        dst[o + 1] = 0;
        dst[o + 2] = 0;
        dst[o + 3] = 1;
        o += 4;
        memcpy(dst + o, src + i, sz);
        o += (int)sz;
        i += (int)sz;
    }
    if (o <= 0 || i != n)
        return -1;
    *dn = o;
    return 0;
}

static int64_t mc_now_us(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

static void mc_cost_note_in(MseAv *a, double pts)
{
    int i = a->cost_q_i++ & 31;
    a->cost_q_pts[i] = pts;
    a->cost_q_t[i] = mc_now_us();
}

static void mc_cost_note_out(MseAv *a, double pts)
{
    int k;
    int64_t now = mc_now_us();
    for (k = 0; k < 32; k++) {
        int i = (a->cost_q_i - 1 - k) & 31;
        double d = a->cost_q_pts[i] - pts;
        if (d < 0)
            d = -d;
        if (a->cost_q_t[i] && d < 0.002) {
            double lat = (now - a->cost_q_t[i]) / 1e6;
            a->cost_q_t[i] = 0;
            if (a->mc_dec_ema <= 0)
                a->mc_dec_ema = lat;
            else
                a->mc_dec_ema = 0.8 * a->mc_dec_ema + 0.2 * lat;
            return;
        }
    }
}

static int mc_queue(MseAv *a, const uint8_t *data, int n, int64_t pts_us, int key)
{
    ssize_t ix;
    size_t cap = 0;
    uint8_t *buf;
    uint32_t flags = 0;
    if (!a->mc || n <= 0)
        return 0;
    ix = AMediaCodec_dequeueInputBuffer(a->mc, 0);
    if (ix < 0)
        return AVERROR(EAGAIN);
    buf = AMediaCodec_getInputBuffer(a->mc, (size_t)ix, &cap);
    if (!buf)
        return -1;
    if (a->vpar && a->vpar->codec_id == AV_CODEC_ID_H264) {
        int an = 0;
        if (h264_avcc_to_annexb(data, n, h264_nal_len_size(a->vpar), buf, (int)cap, &an) == 0)
            n = an;
        else if (cap >= (size_t)n)
            memcpy(buf, data, (size_t)n);
        else
            return -1;
    } else if (cap >= (size_t)n) {
        memcpy(buf, data, (size_t)n);
    } else {
        return -1;
    }
#ifdef AMEDIACODEC_BUFFER_FLAG_KEY_FRAME
    if (key)
        flags |= AMEDIACODEC_BUFFER_FLAG_KEY_FRAME;
#else
    (void)key;
#endif
    if (!a->mc_pts0_set) {
        a->mc_pts0 = pts_us;
        a->mc_pts0_set = 1;
    }
    if (AMediaCodec_queueInputBuffer(a->mc, (size_t)ix, 0, (size_t)n, pts_us - a->mc_pts0, flags) != AMEDIA_OK)
        return -1;
    a->mc_in_pts = pts_us / 1e6;
    mc_cost_note_in(a, pts_us / 1e6);
    return 0;
}

static int mc_drain_bsf(MseAv *a)
{
    int r;
    if (!a->mc_bsf_out && !(a->mc_bsf_out = av_packet_alloc()))
        return -1;
    for (;;) {
        if (a->mc_bsf_out->size <= 0) {
            r = av_bsf_receive_packet(a->vbsf, a->mc_bsf_out);
            if (r == AVERROR(EAGAIN) || r == AVERROR_EOF)
                return 0;
            if (r < 0)
                return r;
        }
        r = mc_queue(a, a->mc_bsf_out->data, a->mc_bsf_out->size, a->mc_bsf_out->pts * 1000,
                     !!(a->mc_bsf_out->flags & AV_PKT_FLAG_KEY));
        if (r == AVERROR(EAGAIN))
            return r;
        av_packet_unref(a->mc_bsf_out);
        if (r < 0)
            return r;
    }
}

static int mc_send_one(MseAv *a)
{
    Cf *cf;
    int r;
    if (!a->mc || a->vf.rd >= a->vf.n)
        return 0;
    cf = &a->vf.f[a->vf.rd];
    if (a->vbsf) {
        AVPacket *pkt;
        r = mc_drain_bsf(a);
        if (r == AVERROR(EAGAIN))
            return 0;
        if (r < 0)
            return r;
        pkt = av_packet_alloc();
        if (!pkt)
            return -1;
        if (av_new_packet(pkt, (int)cf->n) < 0) {
            av_packet_free(&pkt);
            return -1;
        }
        memcpy(pkt->data, cf->data, cf->n);
        pkt->pts = llround(cf->pts * 1000.0);
        pkt->dts = llround(cf->dts * 1000.0);
        pkt->time_base = (AVRational){ 1, 1000 };
        if (cf->key)
            pkt->flags |= AV_PKT_FLAG_KEY;
        r = av_bsf_send_packet(a->vbsf, pkt);
        av_packet_free(&pkt);
        if (r == AVERROR(EAGAIN))
            return 0;
        if (r < 0)
            return r;
        cf->sent = 1;
        a->vf.rd++;
        r = mc_drain_bsf(a);
        return r == AVERROR(EAGAIN) ? 0 : r;
    }
    r = mc_queue(a, cf->data, (int)cf->n, (int64_t)(cf->pts * 1e6), cf->key);
    if (r == AVERROR(EAGAIN))
        return 0;
    cf->sent = 1;
    a->vf.rd++;
    return r < 0 ? r : 0;
}

static int mc_out_late(const MseAv *a, double p, double clock)
{
    const double late = 0.08;
    /* Do not present a 0.9s-late first IDR then return — that leaves every
       following picture late and FPS collapses. Skip until within 350ms. */
    if (!a->v_pictured)
        return p < clock - 0.35;
    return p < clock - late && !(a->show_late && p >= clock - 0.35);
}

/* The old session drains to EOS before a rep switch reopens it: the next
   rep's AUs never enter it, and its last pictures still play. */
static void mc_drain_for_switch(MseAv *a, double clock)
{
    ssize_t ix;
    if (a->mc_eos)
        return;
    if (!a->mc_pts0_set) {
        a->mc_eos = 2;
        return;
    }
    ix = AMediaCodec_dequeueInputBuffer(a->mc, 0);
    if (ix < 0)
        return;
    if (AMediaCodec_queueInputBuffer(a->mc, (size_t)ix, 0, 0, 0,
                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != AMEDIA_OK) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec eos queue failed, switching now");
        a->mc_eos = 2;
        return;
    }
    a->mc_eos = 1;
    vlog_state(a, clock, "switch-drain", a->v_switch_pts - clock);
}

/* Frames at/after a pending rep switch are lost when the decoder reopens, so
   only that window keeps clock pacing. */
static int mc_feed_paced(MseAv *a, double clock, double pts)
{
    double ahead = mc_send_ahead(a);
    if (!a->vpar_next)
        return 0;
    if (a->v_switch_pts >= 0 && pts < a->v_switch_pts - ahead)
        return 0;
    return pts > clock + ahead;
}

/* Fill every free input slot: a decoder that emits only once several AUs are
   queued starves under clock pacing and hands back pictures in late clumps. */
static int mc_feed(MseAv *a, double clock)
{
    int sent = 0, i;
    if (a->mc && a->mc_eos && !a->vpar_next) {
        vlog_state(a, clock, "switch-cancel", -1);
        mc_flush(a);
        a->mc_eos = 0;
        a->need_rap[0] = 1;
    }
    if (a->mc_eos == 1 &&
        clock > (a->mc_in_pts > a->v_switch_pts ? a->mc_in_pts : a->v_switch_pts) + 0.3) {
        mse_critf(MSE_LOG_WARN, "mse-v mediacodec no eos 0.3s past the last input clock=%.3f in=%.3f",
                  clock, a->mc_in_pts);
        a->mc_eos = 2;
    }
    for (i = 0; i < 64; i++) {
        size_t rd0 = a->vf.rd;
        double sp;
        int key, sr;
        maybe_apply_video_rep(a);
        if (!a->mc)
            break;
        while (a->vf.rd < a->vf.n && a->need_rap[0] &&
               !coded_is_rap(a, &a->vf.f[a->vf.rd])) {
            a->vf.f[a->vf.rd].sent = 1;
            a->vf.rd++;
        }
        if (a->vf.rd != rd0)
            vlog_state(a, clock, "rap-skip", a->vf.rd < a->vf.n ? a->vf.f[a->vf.rd].pts : -1);
        if (a->vf.rd >= a->vf.n)
            break;
        sp = a->vf.f[a->vf.rd].pts;
        key = a->vf.f[a->vf.rd].key;
        if (a->vpar_next && a->v_switch_pts >= 0 && sp >= a->v_switch_pts - 0.001) {
            mc_drain_for_switch(a, clock);
            break;
        }
        if (mc_feed_paced(a, clock, sp)) {
            vlog_starve_ahead(a, clock, a->vf.rd);
            break;
        }
        rd0 = a->vf.rd;
        sr = mc_send_one(a);
        if (sr < 0) {
            mse_critf(MSE_LOG_WARN, "mse-v mediacodec send-err pts=%.3f key=%d r=%d",
                      sp, key, sr);
            a->need_rap[0] = 1;
            continue;
        }
        if (a->vf.rd == rd0)
            break;
        if (key)
            a->need_rap[0] = 0;
        sent++;
        if (vdbg_ok())
            vlog("send pts=%.3f key=%d clock=%.3f", sp, key, clock);
    }
    return sent;
}

/* A frozen picture with these counts still rising means the window's consumer stopped. */
static void mc_stats(MseAv *a, double clock)
{
    int64_t now = mc_now_us();
    if (!a->mc_stats_t0) {
        a->mc_stats_t0 = now;
        return;
    }
    if (now - a->mc_stats_t0 < 5000000)
        return;
    mse_critf(MSE_LOG_INFO, "mse-v stats 5s shown=%d dropped=%d clock=%.3f v_sent=%.3f win=%p",
              a->mc_n_shown, a->mc_n_dropped, clock, a->v_sent, (void *)a->mc_win);
    a->mc_n_shown = 0;
    a->mc_n_dropped = 0;
    a->mc_stats_t0 = now;
}

static int pull_mc(MseAv *a, double clock, int *w, int *h, double *pts)
{
    const double early = 0.04;
    int i;
    mc_feed(a, clock);
    if (!a->mc)
        return 0;
    mc_stats(a, clock);
    if (a->mc_hold) {
        double p = (a->mc_hold_pts_us + a->mc_pts0) / 1e6;
        double d = p - clock;
        if (p > clock + early) {
            if (d > 0.2)
                vlog_hold_far("HOLD-FAR", clock, d);
            if (vdbg_ok() || d > 0.2)
                vlog_state(a, clock, d > 0.2 ? "HOLD-FAR" : "hold", d);
            return 0;
        }
        if (mc_out_late(a, p, clock)) {
            vlog_state(a, clock, "hold-late", d);
            mc_cost_note_out(a, p);
            mc_hold_drop(a, 0);
            a->mc_n_dropped++;
        } else {
            mc_hold_drop(a, 1);
            a->mc_n_shown++;
            *pts = p;
            a->v_sent = p;
            a->v_pictured = 1;
            mc_cost_note_out(a, p);
            a->show_late = 0;
            *w = a->mc_w;
            *h = a->mc_h;
            if (vdbg_ok())
                vlog_state(a, clock, "show-hold", d);
            return 1;
        }
    }
    for (i = 0; i < 96; i++) {
        AMediaCodecBufferInfo info;
        ssize_t ox = AMediaCodec_dequeueOutputBuffer(a->mc, &info, 0);
        if (ox >= 0 && (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)) {
            a->mc_eos = 2;
            vlog_state(a, clock, "switch-drained", a->v_switch_pts - clock);
            if (info.size <= 0) {
                AMediaCodec_releaseOutputBuffer(a->mc, (size_t)ox, 0);
                mc_feed(a, clock);
                if (!a->mc)
                    return 0;
                continue;
            }
        }
        if (ox >= 0) {
            double p = (info.presentationTimeUs + a->mc_pts0) / 1e6;
            double d = p - clock;
            if (p > clock + early) {
                a->mc_hold = 1;
                a->mc_hold_ix = (int)ox;
                a->mc_hold_pts_us = info.presentationTimeUs;
                if (d > 0.2)
                    vlog_hold_far("RECV-HOLD-FAR", clock, d);
                if (vdbg_ok() || d > 0.2)
                    vlog_state(a, clock, d > 0.2 ? "RECV-HOLD-FAR" : "recv-hold", d);
                return 0;
            }
            if (mc_out_late(a, p, clock)) {
                if (vdbg_ok())
                    vlog_state(a, clock, "recv-late", d);
                mc_cost_note_out(a, p);
                AMediaCodec_releaseOutputBuffer(a->mc, (size_t)ox, 0);
                a->mc_n_dropped++;
                continue;
            }
            AMediaCodec_releaseOutputBuffer(a->mc, (size_t)ox, 1);
            a->mc_n_shown++;
            *pts = p;
            a->v_sent = p;
            a->v_pictured = 1;
            mc_cost_note_out(a, p);
            a->show_late = 0;
            *w = a->mc_w;
            *h = a->mc_h;
            if (vdbg_ok())
                vlog_state(a, clock, "show", d);
            return 1;
        }
        if (ox == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *fmt = AMediaCodec_getOutputFormat(a->mc);
            if (fmt) {
                int32_t mw = 0, mh = 0, cl = 0, ct = 0, cr = 0, cb = 0;
                int have_crop;
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &mw);
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &mh);
                have_crop =
                    AMediaFormat_getInt32(fmt, "crop-left", &cl) == AMEDIA_OK &&
                    AMediaFormat_getInt32(fmt, "crop-top", &ct) == AMEDIA_OK &&
                    AMediaFormat_getInt32(fmt, "crop-right", &cr) == AMEDIA_OK &&
                    AMediaFormat_getInt32(fmt, "crop-bottom", &cb) == AMEDIA_OK;
                if (have_crop) {
                    int cw = cr - cl + 1, ch = cb - ct + 1;
                    /* i.MX reports 0,0,0,0 → 1x1, or crop=buffer stride. */
                    if (cw >= 16 && ch >= 16 &&
                        (a->mc_cfg_w <= 0 || cw <= a->mc_cfg_w + 16)) {
                        mw = cw;
                        mh = ch;
                    } else {
                        have_crop = 0;
                    }
                }
                if (!have_crop) {
                    /* i.MX stride (1280 coded → 1536 output). Keep coded size. */
                    if (a->mc_cfg_w > 0 && mw > a->mc_cfg_w)
                        mw = a->mc_cfg_w;
                    if (a->mc_cfg_h > 0 && mh > a->mc_cfg_h)
                        mh = a->mc_cfg_h;
                }
                if (mw > 0)
                    a->mc_w = mw;
                if (mh > 0)
                    a->mc_h = mh;
                AMediaFormat_delete(fmt);
                vlog("mediacodec size %dx%d", a->mc_w, a->mc_h);
            }
            continue;
        }
        if (ox == AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
            ox == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            if (mc_feed(a, clock) > 0)
                continue;
            if (a->mc && a->vf.rd >= a->vf.n)
                vlog_starve_empty(a, clock, 0);
        }
        break;
    }
    return 0;
}

/* A configure can fail while the window is still connected to the codec just closed (Codec2
   disconnects asynchronously): retry before giving up on the stream. */
static int mc_try_open(MseAv *a)
{
    if (a->v_rest || !a->mc_win || !a->vpar || a->mc_fail)
        return -1;
    if (a->mc)
        return 0;
    if (a->mc_open_tries > 0 && mc_now_us() < a->mc_retry_us)
        return MC_OPEN_WAIT;
    if (mc_open(a) < 0) {
        if (++a->mc_open_tries < MC_OPEN_TRIES) {
            a->mc_retry_us = mc_now_us() + MC_OPEN_RETRY_US;
            return MC_OPEN_WAIT;
        }
        a->mc_fail = 1;
        if (!a->mc_failure[0])
            snprintf(a->mc_failure, sizeof(a->mc_failure), "%s %dx%d: no MediaCodec after %d tries",
                     avcodec_get_name(a->vpar->codec_id), a->vpar->width, a->vpar->height,
                     a->mc_open_tries);
        mse_critf(MSE_LOG_ERROR, "mse-v %s", a->mc_failure);
        return -1;
    }
    a->mc_open_tries = 0;
    if (a->vdec) {
        av_frame_free(&a->vhold);
        avcodec_free_context(&a->vdec);
    }
    return 0;
}
#endif

/* sw_only: skip VideoToolbox-hosted `av1` (simulator / vt_fail).
   Prefer libvpx-vp9. Native `vp9` is software-only fallback when the pack
   omitted libvpx (do not attach VT hwaccel: that path SIGILL). */
static const AVCodec *find_video_codec(const AVCodecParameters *par, int sw_only)
{
    enum AVCodecID id = par->codec_id;
    const AVCodec *c;
#ifdef MSE_LINUX_HW
    if (!sw_only && (id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_AV1) && mse_hwdev_usable(id)) {
        c = avcodec_find_decoder_by_name(id == AV_CODEC_ID_VP9 ? "vp9" : "av1");
        if (c)
            return c;
    }
#endif
#ifdef MSE_WIN_HW
    /* d3d11va only drives the native decoders; libdav1d / libvpx-vp9 stay software. Native av1
       has no software path: only a stream D3D11 takes may pick it. */
    if (!sw_only && (id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_AV1) && mse_d3d11_par_ok(par)) {
        c = avcodec_find_decoder_by_name(id == AV_CODEC_ID_VP9 ? "vp9" : "av1");
        if (c && mse_d3d11_can_decode(c))
            return c;
    }
#endif
    if (id == AV_CODEC_ID_VP9) {
        c = avcodec_find_decoder_by_name("libvpx-vp9");
        if (c)
            return c;
        return avcodec_find_decoder_by_name("vp9");
    }
    if (id == AV_CODEC_ID_VP8) {
        c = avcodec_find_decoder_by_name("libvpx");
        if (c)
            return c;
        return NULL;
    }
    if (id == AV_CODEC_ID_AV1) {
        if (sw_only) {
            c = avcodec_find_decoder_by_name("libdav1d");
            return c;
        }
#if defined(__APPLE__) && TARGET_OS_SIMULATOR
        c = avcodec_find_decoder_by_name("libdav1d");
        if (c)
            return c;
        return NULL;
#endif
#ifdef __APPLE__
        /* find_decoder(AV1) returns libdav1d once it is linked. Native `av1`
           is the VideoToolbox wrapper — 4K stays on the GPU. */
        c = avcodec_find_decoder_by_name("av1");
        if (c)
            return c;
#endif
        c = avcodec_find_decoder_by_name("libdav1d");
        if (c)
            return c;
        return avcodec_find_decoder(id);
    }
    return avcodec_find_decoder(id);
}

/* Same itag/config: reuse the decoder. A new quality (size or sequence
   header) must not feed leftover packets into the new decoder — keep
   leftover covering currentTime and switch at the new GOP. */
static int vpar_compat(const AVCodecParameters *have, const AVCodecParameters *par)
{
    if (!have || !par)
        return 0;
    if (have->codec_id != par->codec_id)
        return 0;
    if (par->width > 0 && have->width > 0 &&
        (par->width != have->width || par->height != have->height))
        return 0;
    if (have->extradata_size != par->extradata_size)
        return 0;
    if (have->extradata_size > 0 && have->extradata && par->extradata &&
        memcmp(have->extradata, par->extradata, (size_t)have->extradata_size) != 0)
        return 0;
    return 1;
}

#ifdef __APPLE__
/* Intel Mac: no VP9 VT decoder (kVTCouldNotFindVideoDecoderErr -12906). */
static int vp9_vt_unavailable;

/* H.264 and VP9 decode through native VTDecompressionSession, not ffmpeg's
   videotoolbox hwaccel (native `vp9` SIGILL). */
static int apple_vt_codec(const AVCodecParameters *par)
{
    if (!par)
        return 0;
    if (par->codec_id == AV_CODEC_ID_H264)
        return par->extradata && par->extradata_size >= 7;
    if (par->codec_id == AV_CODEC_ID_VP9)
#if defined(__x86_64__)
        return 0; /* Intel: no VT VP9 (-12906). */
#else
        return par->width > 0 && par->height > 0;
#endif
    return 0;
}

static void vp9_vpcc_from_par(uint8_t out[12], const AVCodecParameters *par,
                              const uint8_t *frame, int n)
{
    int profile = 0, bpp = 8;
    memset(out, 0, 12);
    if (par->profile >= 0 && par->profile <= 3)
        profile = par->profile;
    if (frame && n >= 1 && (frame[0] >> 6) == 2) {
        profile = (frame[0] >> 4) & 3;
        if (profile >= 2)
            bpp = 10;
    }
    out[0] = 1;
    out[4] = (uint8_t)profile;
    out[6] = (uint8_t)((bpp << 4) | (1 << 1));
    out[7] = 1;
    out[8] = 1;
    out[9] = 1;
}

static void *open_apple_vt(MseAv *a)
{
    if (!a->vpar)
        return NULL;
    if (a->vpar->codec_id == AV_CODEC_ID_H264)
        return mse_vt_dec_open(a->vpar->extradata, a->vpar->extradata_size,
                               a->vpar->width, a->vpar->height);
    if (a->vpar->codec_id == AV_CODEC_ID_VP9) {
        uint8_t vpcc[12];
        if (vp9_vt_unavailable)
            return NULL;
        const uint8_t *extra = a->vpar->extradata;
        int extra_n = a->vpar->extradata_size;
        const uint8_t *frame = NULL;
        int fn = 0;
        if (a->vf.rd < a->vf.n) {
            frame = a->vf.f[a->vf.rd].data;
            fn = (int)a->vf.f[a->vf.rd].n;
        }
        if (!extra || extra_n < 12 || extra[0] != 1) {
            vp9_vpcc_from_par(vpcc, a->vpar, frame, fn);
            extra = vpcc;
            extra_n = 12;
        }
        return mse_vt_dec_open_vp9(extra, extra_n, a->vpar->width, a->vpar->height);
    }
    return NULL;
}
#endif

#ifdef MSE_WIN_HW
/* A software pick (hwaccel init failed, or the stream does not fit D3D11) latches the codec so
   d3d11_reopen_sw swaps in a decoder configured for software. */
static enum AVPixelFormat get_d3d11_format(AVCodecContext *ctx, const enum AVPixelFormat *fmts)
{
    enum AVPixelFormat f = mse_d3d11_pick(ctx, fmts);
    MseAv *a = ctx->opaque;
    if (f != AV_PIX_FMT_D3D11 && a && a->d3d11_fail_id != ctx->codec_id) {
        a->d3d11_fail_id = ctx->codec_id;
        mse_critf(MSE_LOG_INFO, "d3d11va off id=%d sw=%s: reopening in software",
                  (int)ctx->codec_id, av_get_pix_fmt_name(ctx->sw_pix_fmt));
    }
    return f;
}
#endif

static int open_decoder(MseAv *a, int video, AVCodecParameters *par)
{
    const AVCodec *codec;
    AVCodecContext **ctxp = video ? &a->vdec : &a->adec;
    if (video && (a->v_rest
#ifdef __APPLE__
        || a->v_bg_park
#endif
    ))
        return 0;
#ifdef __ANDROID__
    /* Video decodes in MediaCodec only: a CPU blit would hold the window from it for good. */
    if (video)
        return -1;
#endif
    int vp9 = video && (par->codec_id == AV_CODEC_ID_VP9 || par->codec_id == AV_CODEC_ID_VP8);
    if (*ctxp) {
        int same = (*ctxp)->codec_id == par->codec_id;
        int cw = (*ctxp)->width > 0 ? (*ctxp)->width : (*ctxp)->coded_width;
        int ch = (*ctxp)->height > 0 ? (*ctxp)->height : (*ctxp)->coded_height;
        if (same && par->width > 0 && cw > 0 &&
            (par->width != cw || par->height != ch)) {
            vlog("reopen size %dx%d -> %dx%d", cw, ch, par->width, par->height);
            same = 0;
        } else if (!same)
            vlog("reopen codec %d -> %d", (int)(*ctxp)->codec_id, (int)par->codec_id);
        if (same)
            return 0;
        avcodec_free_context(ctxp);
        if (video) {
            av_bsf_free(&a->vbsf);
#ifdef __ANDROID__
            if (a->mc_bsf_out)
                av_packet_unref(a->mc_bsf_out);
#endif
            a->need_rap[0] = 1;
            a->v_reopen = 1;
        }
    }
    codec = video ? find_video_codec(par,
#ifdef __APPLE__
        par->codec_id == a->vt_fail_id
#elif defined(MSE_WIN_HW)
        par->codec_id == a->d3d11_fail_id
#else
        0
#endif
        ) : avcodec_find_decoder(par->codec_id);
    if (!codec) {
        static int logged_id;
        if (logged_id != (int)par->codec_id) {
            logged_id = (int)par->codec_id;
            mse_critf(MSE_LOG_WARN, "mse-v no decoder id=%d", (int)par->codec_id);
        }
        return -1;
    }
#ifdef __APPLE__
    /* VT-failed AV1 with no dav1d: do not reopen the hwaccel-only `av1`. */
    if (video && par->codec_id == AV_CODEC_ID_AV1 && par->codec_id == a->vt_fail_id &&
        !avcodec_find_decoder_by_name("libdav1d"))
        return -1;
#endif
    *ctxp = avcodec_alloc_context3(codec);
    if (!*ctxp)
        return -1;
    if (avcodec_parameters_to_context(*ctxp, par) < 0) {
        avcodec_free_context(ctxp);
        return -1;
    }
    /* libvpx VP9: Chrome uses ~4 decoder threads (VpxVideoDecoder).
       libdav1d: one thread cannot keep 1080p; Alder Lake AVX-512 SIGILL
       was workers=1. Use cores (capped) for AV1 software. */
    if (video) {
        if (vp9) {
            (*ctxp)->thread_count = 4;
            (*ctxp)->thread_type = FF_THREAD_FRAME;
        } else if (par->codec_id == AV_CODEC_ID_AV1) {
            int n = av_cpu_count();
            if (n < 2)
                n = 2;
            if (n > 8)
                n = 8;
            (*ctxp)->thread_count = n;
            (*ctxp)->thread_type = FF_THREAD_FRAME;
        } else {
            (*ctxp)->thread_count = 1;
            (*ctxp)->thread_type = 0;
        }
    }
#ifdef __APPLE__
    /* H.264/AV1 VideoToolbox. ffmpeg used to Require HW; simulator has none
       so session create failed. After the allow-sw patch, VT still runs
       (Apple software decoder on sim, HW on device). */
    (*ctxp)->opaque = a;
    {
        int use_vt = video && !vp9 && par->codec_id != a->vt_fail_id;
        if (use_vt) {
            AVBufferRef *hw = NULL;
            if (av_hwdevice_ctx_create(&hw, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0) >= 0) {
                (*ctxp)->hw_device_ctx = av_buffer_ref(hw);
                (*ctxp)->get_format = get_hw_format;
                av_buffer_unref(&hw);
            }
        } else if (video && !vp9) {
            vlog("vt skip id=%d fail=%d", (int)par->codec_id, (int)a->vt_fail_id);
        }
    }
#endif
#ifdef MSE_LINUX_HW
    if (video && mse_hwdev_usable(par->codec_id) &&
        (par->codec_id == AV_CODEC_ID_H264 || par->codec_id == AV_CODEC_ID_HEVC ||
         par->codec_id == AV_CODEC_ID_VP9 || par->codec_id == AV_CODEC_ID_AV1)) {
        (*ctxp)->hw_device_ctx = av_buffer_ref(mse_hwdev_get());
        (*ctxp)->get_format = mse_hwdev_get_format;
        /* vhold + the receive frame sit outside the decoder's fixed CUDA surface pool. */
        (*ctxp)->extra_hw_frames = 4;
        (*ctxp)->thread_count = 1;
        (*ctxp)->thread_type = 0;
    }
#endif
#ifdef MSE_WIN_HW
    /* D3D11VA wraps the process-wide device, not a swapchain: it decodes with or without an
     * attached Canvas. */
    (*ctxp)->opaque = a;
    if (video && par->codec_id != a->d3d11_fail_id && mse_d3d11_par_ok(par) &&
        mse_d3d11_can_decode(codec)) {
        AVBufferRef *hw = mse_d3d11_hwdev_ref();
        if (hw) {
            (*ctxp)->hw_device_ctx = hw;
            (*ctxp)->get_format = get_d3d11_format;
            (*ctxp)->extra_hw_frames = 4;
            (*ctxp)->thread_count = 1;
            (*ctxp)->thread_type = 0;
        }
    }
#endif
    if (avcodec_open2(*ctxp, codec, NULL) < 0) {
#ifdef __APPLE__
        if (video && (*ctxp)->hw_device_ctx) {
            av_buffer_unref(&(*ctxp)->hw_device_ctx);
            (*ctxp)->hw_device_ctx = NULL;
            (*ctxp)->get_format = NULL;
            a->vt_fail_id = par->codec_id;
            if (avcodec_open2(*ctxp, codec, NULL) >= 0)
                goto opened;
        }
#endif
#ifdef MSE_LINUX_HW
        if (video && (*ctxp)->hw_device_ctx) {
            mse_hwdev_mark_failed(par->codec_id);
            avcodec_free_context(ctxp);
            return open_decoder(a, video, par);
        }
#endif
#ifdef MSE_WIN_HW
        if (video && (*ctxp)->hw_device_ctx) {
            a->d3d11_fail_id = par->codec_id;
            mse_critf(MSE_LOG_WARN, "d3d11va open-fail id=%d: software from now on",
                      (int)par->codec_id);
            avcodec_free_context(ctxp);
            return open_decoder(a, video, par);
        }
#endif
        mse_critf(MSE_LOG_WARN, "mse-v open decoder fail id=%d %s", (int)par->codec_id,
                  codec->name);
        avcodec_free_context(ctxp);
        return -1;
    }
#ifdef __APPLE__
    if (video && a->vt_fail_id == par->codec_id && (*ctxp)->hw_device_ctx) {
        avcodec_free_context(ctxp);
        return open_decoder(a, video, par);
    }
#endif
#ifdef MSE_LINUX_HW
    if (video && (*ctxp)->hw_device_ctx && !mse_hwdev_usable(par->codec_id)) {
        avcodec_free_context(ctxp);
        return open_decoder(a, video, par);
    }
#endif
opened:
    (*ctxp)->pkt_timebase = (AVRational){ 1, 1000 };
    (*ctxp)->time_base = (AVRational){ 1, 1000 };
    if (video)
        vlog("open %s %dx%d threads=%d vt=%d", codec->name, (*ctxp)->width, (*ctxp)->height,
             (*ctxp)->thread_count,
#if defined(__APPLE__) || defined(MSE_LINUX_HW) || defined(MSE_WIN_HW)
             (*ctxp)->hw_device_ctx ? 1 : 0
#else
             0
#endif
        );
    if (video && par->codec_id == AV_CODEC_ID_VP9 && !a->vbsf) {
        const AVBitStreamFilter *f = av_bsf_get_by_name("vp9_superframe");
        if (f && av_bsf_alloc(f, &a->vbsf) == 0) {
            avcodec_parameters_copy(a->vbsf->par_in, par);
            a->vbsf->time_base_in = (AVRational){ 1, 1000 };
            if (av_bsf_init(a->vbsf) < 0)
                av_bsf_free(&a->vbsf);
            else
                vlog("vp9_superframe bsf on");
        }
    }
    if (!video) {
        a->rate = (*ctxp)->sample_rate > 0 ? (*ctxp)->sample_rate : 48000;
        a->channels = (*ctxp)->ch_layout.nb_channels > 0 ? (*ctxp)->ch_layout.nb_channels : 2;
    }
    return 0;
}

/* 1 while leftover of the old quality still has to be decoded/shown. */
static int leftover_before_switch(const MseAv *a)
{
    if (!a->vpar_next)
        return 0;
    if (a->v_switch_pts < 0)
        return 1;
    if (a->vhold && a->vhold->pts != AV_NOPTS_VALUE &&
        a->vhold->pts / 1000.0 < a->v_switch_pts - 0.001)
        return 1;
#ifdef __ANDROID__
    if (a->mc_hold && a->mc_hold_pts_us / 1e6 < a->v_switch_pts - 0.001)
        return 1;
#endif
#ifdef __APPLE__
    if (a->vt_q_n > 0 && a->vt_q_pts[0] < a->v_switch_pts - 0.001)
        return 1;
#endif
    if (a->vf.rd < a->vf.n && a->vf.f[a->vf.rd].pts < a->v_switch_pts - 0.001)
        return 1;
#ifdef __ANDROID__
    if (a->mc && a->mc_eos != 2)
        return 1;
#endif
    return 0;
}

static void apply_pending_video_rep(MseAv *a)
{
#ifdef __ANDROID__
    double sw = a->v_switch_pts;
#endif
    if (!a->vpar_next)
        return;
    vlog("video-rep apply %dx%d -> %dx%d switch=%.3f vf=%zu rd=%zu",
         a->vpar ? a->vpar->width : 0, a->vpar ? a->vpar->height : 0,
         a->vpar_next->width, a->vpar_next->height, a->v_switch_pts, a->vf.n,
         a->vf.rd);
    close_video_decoder(a);
    avcodec_parameters_free(&a->vpar);
    a->vpar = a->vpar_next;
    a->vpar_next = NULL;
    a->v_switch_pts = -1;
#ifdef __ANDROID__
    a->mc_fail = 0;
    a->mc_open_tries = 0;
    if (mc_try_open(a) == 0) {
        /* mc_open resyncs to the key before v_sent: an old-rep GOP. */
        if (sw >= 0) {
            size_t i;
            for (i = 0; i < a->vf.n && a->vf.f[i].pts < sw - 0.001; i++)
                ;
            a->vf.rd = i;
            for (; i < a->vf.n; i++)
                a->vf.f[i].sent = 0;
        }
        return;
    }
#endif
#ifdef __APPLE__
    if (apple_vt_codec(a->vpar)) {
        a->v_reopen = 0;
        return;
    }
#endif
    open_decoder(a, 1, a->vpar);
    a->v_reopen = 0;
}

static void maybe_apply_video_rep(MseAv *a)
{
    if (!a->vpar_next)
        return;
    if (leftover_before_switch(a))
        return;
    apply_pending_video_rep(a);
}

static int send_coded(Cfb *b, AVCodecContext *dec, AVBSFContext *bsf)
{
    AVPacket *pkt;
    Cf *cf;
    int r;
    if (!dec || b->rd >= b->n)
        return 0;
    cf = &b->f[b->rd];
    pkt = av_packet_alloc();
    if (!pkt)
        return -1;
    if (av_new_packet(pkt, (int)cf->n) < 0) {
        av_packet_free(&pkt);
        return -1;
    }
    memcpy(pkt->data, cf->data, cf->n);
    pkt->pts = (int64_t)(cf->pts * 1000.0);
    pkt->dts = (int64_t)(cf->dts * 1000.0);
    pkt->time_base = (AVRational){ 1, 1000 };
    if (cf->key)
        pkt->flags |= AV_PKT_FLAG_KEY;
    if (bsf) {
        r = av_bsf_send_packet(bsf, pkt);
        av_packet_free(&pkt);
        if (r == AVERROR(EAGAIN))
            return 0;
        if (r < 0)
            return r;
        cf->sent = 1;
        b->rd++;
        pkt = av_packet_alloc();
        if (!pkt)
            return -1;
        for (;;) {
            r = av_bsf_receive_packet(bsf, pkt);
            if (r == AVERROR(EAGAIN) || r == AVERROR_EOF)
                break;
            if (r < 0) {
                av_packet_free(&pkt);
                return r;
            }
            r = avcodec_send_packet(dec, pkt);
            av_packet_unref(pkt);
            if (r == AVERROR(EAGAIN))
                continue;
            if (r < 0 && r != AVERROR_EOF) {
                av_packet_free(&pkt);
                return r;
            }
        }
        av_packet_free(&pkt);
        return 0;
    }
    r = avcodec_send_packet(dec, pkt);
    av_packet_free(&pkt);
    if (r == AVERROR(EAGAIN))
        return 0;
    if (r < 0 && r != AVERROR_EOF)
        return r;
    cf->sent = 1;
    b->rd++;
    return 0;
}

static void cpu_frame(void *user, int w, int h, const uint8_t *bgra)
{
    MseAv *a = (MseAv *)user;
    /* The host blanks the delivered picture in place (show_black). */
    a->cpu_bgra = (uint8_t *)bgra;
    a->cpu_w = w;
    a->cpu_h = h;
}

MseAv *mse_av_create(void)
{
    VpSink sink = { cpu_frame, NULL, NULL };
    MseAv *a = calloc(1, sizeof(*a));
    if (!a)
        return NULL;
    pthread_mutex_init(&a->mu, NULL);
    pthread_mutex_init(&a->amu, NULL);
    sink.user = a;
    a->cpu = vpresent_create_cpu(&sink);
    vpresent_bind(a->cpu, a);
    a->a_sent = -1;
    a->v_sent = -1;
    a->v_switch_pts = -1;
    a->need_rap[0] = 1;
    a->need_rap[1] = 1;
    av_log_set_level(AV_LOG_ERROR);
    av_log_set_callback(mse_av_log);
    {
        /* Alder Lake E-cores have no AVX-512. ffmpeg probes on a P-core then
           a decode worker SIGILLs (log16 leave-audio, crash thread already
           gone; dec_loop was in nanosleep). */
        int flags = av_get_cpu_flags();
        if (flags & (AV_CPU_FLAG_AVX512 | AV_CPU_FLAG_AVX512ICL)) {
            flags &= ~(AV_CPU_FLAG_AVX512 | AV_CPU_FLAG_AVX512ICL);
            av_force_cpu_flags(flags);
            vlog("cpu mask avx512 off");
        }
    }
    return a;
}

static void av_lock(MseAv *a, int audio)
{
    pthread_mutex_lock(audio ? &a->amu : &a->mu);
}

static void av_unlock(MseAv *a, int audio)
{
    pthread_mutex_unlock(audio ? &a->amu : &a->mu);
}

static void av_lock_all(MseAv *a)
{
    pthread_mutex_lock(&a->mu);
    pthread_mutex_lock(&a->amu);
}

static void av_unlock_all(MseAv *a)
{
    pthread_mutex_unlock(&a->amu);
    pthread_mutex_unlock(&a->mu);
}

void mse_av_destroy(MseAv *a)
{
    if (!a)
        return;
    av_lock_all(a);
    avcodec_free_context(&a->adec);
    avcodec_free_context(&a->vdec);
    av_bsf_free(&a->vbsf);
    avcodec_parameters_free(&a->vpar);
    avcodec_parameters_free(&a->vpar_next);
#ifdef __ANDROID__
    mc_close(a);
    av_packet_free(&a->mc_bsf_out);
#endif
    if (a->swr)
        swr_free(&a->swr);
    free(a->pcm);
    av_frame_free(&a->afr);
    av_frame_free(&a->vfr);
    vpresent_release(a->cpu);
#ifdef __APPLE__
    vp_cpu_destroy(a->vt_cpu);
#endif
    free(a->ainit);
    free(a->vinit);
    free(a->pend[0]);
    free(a->pend[1]);
    cfb_free(&a->af);
    cfb_free(&a->vf);
    av_frame_free(&a->vhold);
    av_unlock_all(a);
    pthread_mutex_destroy(&a->amu);
    pthread_mutex_destroy(&a->mu);
    free(a);
}

static int pend_add(MseAv *a, int audio, const uint8_t *p, int n)
{
    size_t need;
    uint8_t *np;
    int i = audio ? 1 : 0;
    need = a->pend_n[i] + (size_t)n;
    if (need > a->pend_cap[i]) {
        size_t cap = a->pend_cap[i] ? a->pend_cap[i] * 2 : 4096;
        while (cap < need)
            cap *= 2;
        np = realloc(a->pend[i], cap);
        if (!np)
            return -1;
        a->pend[i] = np;
        a->pend_cap[i] = cap;
    }
    memcpy(a->pend[i] + a->pend_n[i], p, (size_t)n);
    a->pend_n[i] += (size_t)n;
    return 0;
}

static int cluster_total(const uint8_t *p, size_t n, size_t *total)
{
    uint32_t id;
    uint64_t sz;
    int unk, hdr;
    size_t off;
    if (!ebml_head(p, n, &id, &sz, &unk, &hdr))
        return 0;
    if (id != MSE_ID_CLUSTER)
        return 0;
    if (!unk) {
        if (sz > MSE_CLUSTER_MAX)
            return 0;
        if ((size_t)hdr + (size_t)sz > n)
            return 0;
        *total = (size_t)hdr + (size_t)sz;
        return 1;
    }
    /* Unknown-size Cluster: walk children at element boundaries (not a raw Cluster-ID scan,
       which false-completes inside VP9/Opus payloads). Complete at the next Cluster/EBML. */
    off = (size_t)hdr;
    while (off < n) {
        uint32_t cid;
        uint64_t csz;
        int cunk, chdr;
        if (!ebml_head(p + off, n - off, &cid, &csz, &cunk, &chdr))
            return 0;
        if (cid == MSE_ID_CLUSTER || cid == MSE_ID_EBML) {
            if (off == (size_t)hdr)
                return 0;
            *total = off;
            return 1;
        }
        if (cunk)
            return 0;
        if (csz > MSE_CLUSTER_MAX)
            return 0;
        if (off + (size_t)chdr + (size_t)csz > n)
            return 0;
        off += (size_t)chdr + (size_t)csz;
    }
    return 0;
}

/* WebM media segment that continues a Cluster: SimpleBlock/Timecode
   without a new Cluster header. libavformat needs a Cluster wrapper. */
static int continuation_total(const uint8_t *p, size_t n, size_t *total)
{
    size_t off = 0;
    uint32_t id0;
    uint64_t sz0;
    int unk0, hdr0;
    if (!ebml_head(p, n, &id0, &sz0, &unk0, &hdr0))
        return 0;
    if (id0 == MSE_ID_CLUSTER || id0 == MSE_ID_EBML || id0 == MSE_ID_SEGMENT)
        return 0;
    if (id0 != MSE_ID_SIMPLEBLOCK && id0 != MSE_ID_BLOCKGROUP && id0 != MSE_ID_BLOCK &&
        id0 != MSE_ID_TIMECODE && id0 != MSE_ID_PREVSIZE)
        return 0;
    while (off < n) {
        uint32_t id;
        uint64_t sz;
        int unk, hdr;
        if (!ebml_head(p + off, n - off, &id, &sz, &unk, &hdr))
            break;
        if (id == MSE_ID_CLUSTER || id == MSE_ID_EBML) {
            if (off == 0)
                return 0;
            *total = off;
            return 1;
        }
        if (unk)
            return 0;
        if (sz > MSE_CLUSTER_MAX)
            return 0;
        if (off + (size_t)hdr + (size_t)sz > n) {
            if (off > 0) {
                *total = off;
                return 1;
            }
            return 0;
        }
        off += (size_t)hdr + (size_t)sz;
    }
    if (off == 0 || off != n)
        return 0;
    *total = n;
    return 1;
}

/* Incomplete Cluster: Cluster header length and complete children we can
   demux now. A 4K GOP is one 20MB Cluster; waiting for clen holds every
   SimpleBlock until leftover is already empty. */
static int cluster_prefix(const uint8_t *p, size_t n, size_t *hdr_out, size_t *body_out,
                          int *nblocks)
{
    uint32_t id;
    uint64_t sz;
    int unk, hdr;
    size_t off, last, cluster_end;
    int blocks = 0;
    if (!ebml_head(p, n, &id, &sz, &unk, &hdr))
        return 0;
    if (id != MSE_ID_CLUSTER)
        return 0;
    off = (size_t)hdr;
    last = 0;
    if (!unk) {
        if (sz > MSE_CLUSTER_MAX)
            return 0;
        cluster_end = (size_t)hdr + (size_t)sz;
        if (cluster_end <= n)
            return 0;
    } else {
        cluster_end = (size_t)-1;
    }
    while (off < n && off < cluster_end) {
        uint32_t cid;
        uint64_t csz;
        int cunk, chdr;
        if (!ebml_head(p + off, n - off, &cid, &csz, &cunk, &chdr))
            break;
        if (cid == MSE_ID_CLUSTER || cid == MSE_ID_EBML)
            break;
        if (cunk)
            break;
        if (csz > MSE_CLUSTER_MAX)
            break;
        if (off + (size_t)chdr + (size_t)csz > n)
            break;
        if (off + (size_t)chdr + (size_t)csz > cluster_end)
            break;
        off += (size_t)chdr + (size_t)csz;
        last = off;
        if (cid == MSE_ID_SIMPLEBLOCK || cid == MSE_ID_BLOCKGROUP)
            blocks++;
    }
    if (blocks < 1 || last <= (size_t)hdr)
        return 0;
    *hdr_out = (size_t)hdr;
    *body_out = last - (size_t)hdr;
    *nblocks = blocks;
    return 1;
}

static void save_cluster_tc(MseAv *a, int idx, const uint8_t *p, size_t n)
{
    size_t off = 0;
    while (off < n) {
        uint32_t id;
        uint64_t sz;
        int unk, hdr;
        if (!ebml_head(p + off, n - off, &id, &sz, &unk, &hdr))
            break;
        if (unk || off + (size_t)hdr + (size_t)sz > n)
            break;
        if (id == MSE_ID_TIMECODE) {
            size_t el = (size_t)hdr + (size_t)sz;
            if (el <= sizeof(a->cluster_tc[idx])) {
                memcpy(a->cluster_tc[idx], p + off, el);
                a->cluster_tc_n[idx] = el;
            }
            return;
        }
        off += (size_t)hdr + (size_t)sz;
    }
}

static int ebml_id_at(const uint8_t *p, size_t n, uint32_t want)
{
    uint32_t id;
    uint64_t sz;
    int unk, hdr;
    if (!ebml_head(p, n, &id, &sz, &unk, &hdr))
        return 0;
    return id == want;
}

static void keep_coded(MseAv *a, int idx, Cfb *tmp, Cfb *dst, double win_start, double win_end,
                       MseAvIngest *r)
{
    Cfb kept;
    int i;
    memset(&kept, 0, sizeof(kept));
    for (i = 0; i < (int)tmp->n; i++) {
        double pts = tmp->f[i].pts;
        double end = pts + tmp->f[i].dur;
        if (pts < win_start || end > win_end) {
            a->need_rap[idx] = 1;
            continue;
        }
        if (a->need_rap[idx] && (idx ? !tmp->f[i].key : !coded_is_rap(a, &tmp->f[i])))
            continue;
        a->need_rap[idx] = 0;
        cfb_push(&kept, tmp->f[i].pts, tmp->f[i].dts, tmp->f[i].dur, tmp->f[i].key,
                 tmp->f[i].data, tmp->f[i].n);
    }
    if (kept.n) {
        double first = kept.f[0].pts;
        double last = kept.f[kept.n - 1].pts + kept.f[kept.n - 1].dur;
        double *sp = NULL;
        int nsp = 0, j;
        size_t rd0 = dst->rd;
        double rd_pts = rd0 < dst->n ? dst->f[rd0].pts : -1;
        if (r->n == 0)
            r->first = first;
        r->last = last;
        r->n += (int)kept.n;
        /* Overlapping append: dropping already-sent packets and inserting
           sent=0 copies rewinds rd and re-feeds those PTS into decoder/DAC.
           Remember sent PTS. */
        for (i = 0; i < (int)dst->n; i++) {
            if (dst->f[i].sent && dst->f[i].pts + 1e-4 >= first && dst->f[i].pts < last)
                nsp++;
        }
        if (nsp > 0) {
            sp = malloc((size_t)nsp * sizeof(*sp));
            if (sp) {
                int w = 0;
                for (i = 0; i < (int)dst->n; i++) {
                    if (dst->f[i].sent && dst->f[i].pts + 1e-4 >= first &&
                        dst->f[i].pts < last)
                        sp[w++] = dst->f[i].pts;
                }
                nsp = w;
            } else {
                nsp = 0;
            }
        }
        cfb_drop(dst, first, last);
        for (i = 0; i < (int)kept.n; i++)
            cfb_push(dst, kept.f[i].pts, kept.f[i].dts, kept.f[i].dur, kept.f[i].key,
                     kept.f[i].data, kept.f[i].n);
        qsort(dst->f, dst->n, sizeof(Cf), cf_cmp);
        if (sp) {
            double sent_max = -1e300;
            for (j = 0; j < nsp; j++) {
                if (sp[j] > sent_max)
                    sent_max = sp[j];
            }
            for (i = 0; i < (int)dst->n; i++) {
                if (dst->f[i].sent)
                    continue;
                /* Audio frames stand alone: like Chrome's demuxer, never feed a replacement that
                   starts before audio already fed (a gapless next track overlapping the tail). */
                if (idx && dst->f[i].pts < sent_max - 0.001) {
                    dst->f[i].sent = 1;
                    continue;
                }
                for (j = 0; j < nsp; j++) {
                    double d = dst->f[i].pts - sp[j];
                    if (d < 0)
                        d = -d;
                    if (d < 0.002) {
                        dst->f[i].sent = 1;
                        break;
                    }
                }
            }
            free(sp);
        }
        /* rd = first unsent at/after the playhead, not an older island
           (seek 526s + leftover 0–30s) and not a later append's start
           (Auto at 5.3s + 10–14s segment jumped rd to 23s → starve-ahead).
           v_sent/a_sent < 0 means nothing shown yet (audio-only → video):
           keep sequential rd or leftover starts ahead of the clock forever. */
        /* A later segment leaves the read position alone: v_sent can sit ahead of the decoder
           (a seek sets it to the target while the clock holds behind the buffered end), and a
           recompute from it skipped unsent references, then rewound onto frames already fed. */
        if (nsp == 0 && rd_pts >= 0 && first >= rd_pts) {
            dst->rd = rd0;
        } else {
            double want = idx ? a->a_sent : a->v_sent;
            double floor_pts = first;
            if (want < 0)
                floor_pts = -1e9;
            else if (want < first)
                floor_pts = want;
            dst->rd = dst->n;
            for (i = 0; i < (int)dst->n; i++) {
                if (dst->f[i].sent)
                    continue;
                if (dst->f[i].pts + dst->f[i].dur < floor_pts - 0.5)
                    continue;
                dst->rd = (size_t)i;
                break;
            }
        }
        if (nsp > 0 || dst->rd + 2 < rd0)
            vlog("overlap-keep a=%d first=%.3f last=%.3f restored=%d rd=%zu->%zu n=%zu",
                 idx, first, last, nsp, rd0, dst->rd, dst->n);
    }
    cfb_free(&kept);
}

MseAvIngest mse_av_append(MseAv *a, int audio, const uint8_t *p, int n, double ts_off,
                          double win_start, double win_end)
{
    MseAvIngest r = { 0 };
    Cfb *dst;
    uint8_t **initp;
    size_t *initn;
    int idx;
    int wrap;

    if (!a || !p || n <= 0)
        return r;
    av_lock(a, audio);
    idx = audio ? 1 : 0;
    dst = audio ? &a->af : &a->vf;
    {
        int is_init = 0;
        double pts = 0, dur = 0;
        if (!audio && fake_ump(p, n, &is_init, &pts, &dur)) {
            r.n = 0;
            r.is_init = is_init;
            if (!is_init && a->v_rest) {
                pts += ts_off;
                if (!(pts + dur < win_start || pts > win_end)) {
                    if (cfb_push(dst, pts, pts, dur, 1, p, (size_t)n) == 0) {
                        r.n = 1;
                        r.first = pts;
                        r.last = pts + dur;
                        vlog("fake-cover start=%.3f dur=%.3f vf=%zu", pts, dur, dst->n);
                    }
                }
            }
            av_unlock(a, audio);
            return r;
        }
    }
    if (pend_add(a, audio, p, n) < 0) {
        av_unlock(a, audio);
        return r;
    }
    initp = audio ? &a->ainit : &a->vinit;
    initn = audio ? &a->ainit_n : &a->vinit_n;
    for (;;) {
        size_t total = 0, coff = 0, prefix_hdr = 0;
        Cfb tmp;
        AVCodecParameters *par = NULL;
        double dur;
        int nadd;
        int prefix = 0;
        wrap = 0;
        if (looks_ebml(a->pend[idx], a->pend_n[idx])) {
            if (*initn && looks_fmp4(*initp, *initn)) {
                free(*initp);
                *initp = NULL;
                *initn = 0;
            }
            if (!find_cluster(a->pend[idx], a->pend_n[idx], &coff)) {
                if (!audio && a->pend_n[idx] > 0 && (a->pend_n[idx] < 64 || a->pend_n[idx] % 4096 == 0))
                    vlog("webm wait-ebml n=%zu b0=%02x", a->pend_n[idx], a->pend[idx][0]);
                break;
            }
            if (coff > 0) {
                uint8_t *np = realloc(*initp, coff);
                if (np) {
                    *initp = np;
                    memcpy(np, a->pend[idx], coff);
                    *initn = coff;
                }
                if (a->pend_n[idx] > coff)
                    memmove(a->pend[idx], a->pend[idx] + coff, a->pend_n[idx] - coff);
                a->pend_n[idx] -= coff;
                r.is_init = 1;
                if (a->pend_n[idx] == 0)
                    break;
                continue;
            }
        } else if (looks_fmp4(a->pend[idx], a->pend_n[idx])) {
            size_t ilen = 0, flen = 0;
            if (*initn && looks_ebml(*initp, *initn)) {
                free(*initp);
                *initp = NULL;
                *initn = 0;
            }
            /* A later ftyp/moov on this SourceBuffer is a new init (MSE). */
            if (fmp4_init_len(a->pend[idx], a->pend_n[idx], &ilen) && ilen > 0) {
                if (*initn)
                    vlog("fmp4 init-replace n=%zu was=%zu a=%d", ilen, *initn, audio);
                uint8_t *np = realloc(*initp, ilen);
                if (np) {
                    *initp = np;
                    memcpy(np, a->pend[idx], ilen);
                    *initn = ilen;
                }
                if (a->pend_n[idx] > ilen)
                    memmove(a->pend[idx], a->pend[idx] + ilen, a->pend_n[idx] - ilen);
                a->pend_n[idx] -= ilen;
                r.is_init = 1;
                if (a->pend_n[idx] == 0)
                    break;
                continue;
            }
            if (*initn && fmp4_frag_len(a->pend[idx], a->pend_n[idx], &flen) && flen > 0) {
                total = flen;
            } else {
                if (!audio && !*initn)
                    vlog("fmp4 wait-init n=%zu b0=%02x", a->pend_n[idx], a->pend[idx][0]);
                break;
            }
        } else if (!cluster_total(a->pend[idx], a->pend_n[idx], &total) || total == 0 ||
                   a->pend_n[idx] < total) {
            if (!continuation_total(a->pend[idx], a->pend_n[idx], &total) || total == 0 ||
                a->pend_n[idx] < total) {
                size_t body = 0;
                int nblocks = 0;
                int urgent = (dst->n == 0 || dst->rd + 8 >= dst->n);
                if (cluster_prefix(a->pend[idx], a->pend_n[idx], &prefix_hdr, &body, &nblocks) &&
                    nblocks > 0 && (body >= 128u * 1024u || nblocks >= 8 || urgent)) {
                    total = prefix_hdr + body;
                    wrap = 1;
                    prefix = 1;
                } else {
                    if (!audio && a->pend_n[idx] > 0 &&
                        (a->pend_n[idx] < 96 || a->pend_n[idx] % (1024u * 1024u) == 0))
                        vlog("webm wait-cluster n=%zu b0=%02x", a->pend_n[idx], a->pend[idx][0]);
                    break;
                }
            } else {
                wrap = 1;
            }
        }
        if (total == 0) {
            if (!cluster_total(a->pend[idx], a->pend_n[idx], &total) || total == 0 ||
                a->pend_n[idx] < total)
                break;
        }
        memset(&tmp, 0, sizeof(tmp));
        dur = a->duration;
        if (wrap) {
            const uint8_t *src = a->pend[idx];
            size_t src_n = total;
            size_t tc_n = 0;
            uint8_t *wseg;
            if (prefix) {
                save_cluster_tc(a, idx, a->pend[idx] + prefix_hdr, total - prefix_hdr);
                src = a->pend[idx] + prefix_hdr;
                src_n = total - prefix_hdr;
            } else if (!ebml_id_at(src, src_n, MSE_ID_TIMECODE)) {
                tc_n = a->cluster_tc_n[idx];
            } else {
                save_cluster_tc(a, idx, src, src_n);
            }
            wseg = malloc(5 + tc_n + src_n);
            if (!wseg)
                break;
            wseg[0] = 0x1F;
            wseg[1] = 0x43;
            wseg[2] = 0xB6;
            wseg[3] = 0x75;
            wseg[4] = 0xFF;
            if (tc_n)
                memcpy(wseg + 5, a->cluster_tc[idx], tc_n);
            memcpy(wseg + 5 + tc_n, src, src_n);
            nadd = demux_packets(*initp, *initn, wseg, 5 + tc_n + src_n, ts_off, !audio, &tmp, &par,
                                 &dur);
            free(wseg);
        } else {
            nadd = demux_packets(*initp, *initn, a->pend[idx], total, ts_off, !audio, &tmp, &par, &dur);
            a->cluster_tc_n[idx] = 0;
        }
        if (nadd < 0)
            nadd = 0;
        if (prefix && nadd == 0) {
            if (par)
                avcodec_parameters_free(&par);
            cfb_free(&tmp);
            break;
        }
        if (!audio && nadd > 0 && !wrap && *initn >= 4 && be32(*initp) == MSE_ID_EBML)
            scale_gop_pts(&tmp, ts_off);
        if (dur > a->duration)
            a->duration = dur;
        if (par) {
            if (audio) {
                if (a->adec && a->adec->codec_id != par->codec_id) {
                    avcodec_free_context(&a->adec);
                    if (a->swr)
                        swr_free(&a->swr);
                }
                open_decoder(a, 0, par);
                avcodec_parameters_free(&par);
                par = NULL;
            } else {
                int pending_same = a->vpar_next && vpar_compat(a->vpar_next, par);
                int incompat = a->vpar && !vpar_compat(a->vpar, par);
                if (pending_same || incompat) {
                    if (!pending_same) {
                        avcodec_parameters_free(&a->vpar_next);
                        a->vpar_next = avcodec_parameters_alloc();
                        if (a->vpar_next)
                            avcodec_parameters_copy(a->vpar_next, par);
                        a->v_switch_pts = -1;
                        vlog("video-rep %dx%d -> %dx%d keep vf=%zu",
                             a->vpar->width, a->vpar->height, par->width, par->height,
                             a->vf.n);
                        if (!a->vdec
#ifdef __APPLE__
                            && !apple_vt_codec(a->vpar)
#endif
#ifdef __ANDROID__
                            && !a->mc
#endif
                        )
                            open_decoder(a, 1, a->vpar);
                    }
                    avcodec_parameters_free(&par);
                    par = NULL;
                } else {
                    if (a->vpar_next) {
                        avcodec_parameters_free(&a->vpar_next);
                        a->v_switch_pts = -1;
                    }
                    avcodec_parameters_free(&a->vpar);
                    a->vpar = avcodec_parameters_alloc();
                    if (a->vpar)
                        avcodec_parameters_copy(a->vpar, par);
#ifdef __ANDROID__
                    if (a->mc &&
                        (a->mc_codec_id != par->codec_id ||
                         (par->width > 0 && a->mc_cfg_w > 0 && par->width != a->mc_cfg_w) ||
                         (par->height > 0 && a->mc_cfg_h > 0 && par->height != a->mc_cfg_h))) {
                        mc_close(a);
                        a->mc_fail = 0;
                        a->mc_open_tries = 0;
                    }
                    /* MediaCodec opens on the video thread (pull_video): a configure here holds the
                       JS thread, and the audio appends queued behind it, for tens of ms. */
                    avcodec_parameters_free(&par);
                    par = NULL;
#endif
#ifdef __APPLE__
                    /* Native VTDecompressionSession in pull_video. A second
                       ffmpeg h264_videotoolbox session on the simulator
                       enqueued black frames (61 fps, empty picture). VP9
                       uses the same Apple session — ffmpeg's VT hwaccel
                       wraps native `vp9` (log15 SIGILL). */
                    if (par && apple_vt_codec(par)) {
                        avcodec_parameters_free(&par);
                        par = NULL;
                    }
#endif
                    if (par) {
                        open_decoder(a, 1, par);
                        avcodec_parameters_free(&par);
                        par = NULL;
                    }
                }
            }
        }
        keep_coded(a, idx, &tmp, dst, win_start, win_end, &r);
        if (!audio && a->vpar_next && a->v_switch_pts < 0) {
            size_t ki;
            for (ki = 0; ki < tmp.n; ki++) {
                if (tmp.f[ki].key) {
                    a->v_switch_pts = tmp.f[ki].pts;
                    break;
                }
            }
            if (a->v_switch_pts < 0 && tmp.n)
                a->v_switch_pts = tmp.f[0].pts;
        }
        if (!audio)
            maybe_apply_video_rep(a);
        if (!audio && a->v_reopen) {
            vf_resync_key(a, a->v_sent >= 0 ? a->v_sent : r.first);
            a->v_reopen = 0;
        }
        cfb_free(&tmp);
        if (a->pend_n[idx] > total)
            memmove(a->pend[idx], a->pend[idx] + total, a->pend_n[idx] - total);
        if (a->pend_n[idx] >= total)
            a->pend_n[idx] -= total;
        else
            a->pend_n[idx] = 0;
        if (prefix && !audio)
            vlog("webm prefix-cluster n=%d body=%zu pend=%zu", nadd, total - prefix_hdr,
                 a->pend_n[idx]);
        if (a->pend_n[idx] == 0)
            break;
    }
    if (!audio && r.n)
        vlog("append n=%d first=%.3f last=%.3f vf=%zu rd=%zu rap=%d",
             r.n, r.first, r.last, a->vf.n, a->vf.rd, a->need_rap[0]);
    av_unlock(a, audio);
    return r;
}

/* Caller holds a->mu. */
static void close_video_decoder(MseAv *a)
{
    if (a->vdec
#ifdef __APPLE__
        || a->vt_dec
#endif
    )
        vlog("close-video");
    av_frame_free(&a->vhold);
    avcodec_free_context(&a->vdec);
    av_bsf_free(&a->vbsf);
#ifdef __ANDROID__
    if (a->mc_bsf_out)
        av_packet_unref(a->mc_bsf_out);
    mc_close(a);
#endif
#ifdef __APPLE__
    if (a->vt_layer)
        mse_vt_flush(a->vt_layer);
    mse_vt_dec_close(a->vt_dec);
    a->vt_dec = NULL;
#endif
    a->need_rap[0] = 1;
    a->v_seen_idr = 0;
    a->v_reopen = 0;
#ifdef __APPLE__
    while (a->vt_q_n > 0) {
        a->vt_q_n--;
        mse_vt_pb_release(a->vt_q_pb[a->vt_q_n]);
        a->vt_q_pb[a->vt_q_n] = NULL;
    }
#endif
    /* Do not zero pend_n[0]: mse_av_append can close the decoder mid-cluster
       (quality switch). Wiping pend then subtracting `total` underflows and
       memmove crashes (log22). Abort/changeType/remove/seek clear pend themselves.
       Keep vinit. Leave-audio re-appends moof/clusters with no moov/EBML
       (log17: 1.1MB of n=0 after close-video wiped init → black). Container
       switch already drops the other header in mse_av_append. */
}

void mse_av_abort(MseAv *a, int audio)
{
    int idx;
    if (!a)
        return;
    av_lock(a, audio);
    idx = audio ? 1 : 0;
    a->pend_n[idx] = 0;
    a->cluster_tc_n[idx] = 0;
    a->need_rap[idx] = 1;
    if (audio && a->adec)
        avcodec_flush_buffers(a->adec);
    if (!audio && a->vdec)
        avcodec_flush_buffers(a->vdec);
    if (!audio && a->vbsf)
        av_bsf_flush(a->vbsf);
#ifdef __ANDROID__
    if (!audio)
        mc_flush(a);
#endif
    av_unlock(a, audio);
}

void mse_av_change_type(MseAv *a, int audio)
{
    if (!a)
        return;
    av_lock(a, audio);
    if (audio) {
        a->pend_n[1] = 0;
        a->cluster_tc_n[1] = 0;
        a->need_rap[1] = 1;
        free(a->ainit);
        a->ainit = NULL;
        a->ainit_n = 0;
        avcodec_free_context(&a->adec);
        if (a->swr)
            swr_free(&a->swr);
    } else {
        a->pend_n[0] = 0;
        a->cluster_tc_n[0] = 0;
        free(a->vinit);
        a->vinit = NULL;
        a->vinit_n = 0;
        close_video_decoder(a);
#ifdef MSE_WIN_HW
        /* New stream: give D3D11VA a fresh chance even if the previous stream's codec latched off.
         * The fail latch is per-session (one problematic stream), not permanent. */
        a->d3d11_fail_id = AV_CODEC_ID_NONE;
#endif
    }
    vlog("changeType a=%d", audio);
    av_unlock(a, audio);
}

void mse_av_rest_video(MseAv *a, int on, double clock)
{
    if (!a)
        return;
    pthread_mutex_lock(&a->mu);
    on = on ? 1 : 0;
    if (a->v_rest == on) {
        pthread_mutex_unlock(&a->mu);
        return;
    }
    a->v_rest = on;
    if (on)
        close_video_decoder(a);
    else {
        double t;
        cfb_drop_fake(&a->vf);
        /* v_sent froze at rest-on. Resync to the playhead. */
        t = clock > 0 ? clock : (a->v_sent >= 0 ? a->v_sent : 0);
        if (t <= 0 && a->vf.rd < a->vf.n)
            t = a->vf.f[a->vf.rd].pts;
        vf_resync_key(a, t);
        a->show_late = 1;
        a->v_pictured = 0;
#ifdef __ANDROID__
        mc_close(a);
        a->mc_fail = 0;
        a->mc_open_tries = 0;
#endif
        if (a->vf.rd < a->vf.n)
            a->v_sent = a->vf.f[a->vf.rd].pts;
    }
    mse_critf(MSE_LOG_INFO, "mse-v rest-video %d vf=%zu clock=%.3f", on, a->vf.n, clock);
    pthread_mutex_unlock(&a->mu);
}

int mse_av_video_resting(const MseAv *a)
{
    return a && a->v_rest;
}

#ifdef __APPLE__
void mse_av_park_video(MseAv *a)
{
    if (!a)
        return;
    pthread_mutex_lock(&a->mu);
    if (a->v_rest || a->v_bg_park) {
        pthread_mutex_unlock(&a->mu);
        return;
    }
    a->v_bg_park = 1;
    close_video_decoder(a);
    vlog("bg-park vf=%zu", a->vf.n);
    pthread_mutex_unlock(&a->mu);
}

int mse_av_video_parked(const MseAv *a)
{
    return a && a->v_bg_park;
}
#endif

size_t mse_av_leftover_bytes(MseAv *a, int audio)
{
    Cfb *b;
    size_t n;
    if (!a)
        return 0;
    av_lock(a, audio);
    b = audio ? &a->af : &a->vf;
    n = b->bytes;
    av_unlock(a, audio);
    return n;
}

/* Frames [from, to) leave the buffer; the read position keeps pointing at the same frame. */
static void cfb_drop_span(Cfb *b, size_t from, size_t to)
{
    size_t i;
    if (from >= to)
        return;
    for (i = from; i < to; i++) {
        b->bytes = b->bytes >= b->f[i].n ? b->bytes - b->f[i].n : 0;
        free(b->f[i].data);
    }
    memmove(&b->f[from], &b->f[to], (b->n - to) * sizeof(Cf));
    b->n -= to - from;
    if (b->rd >= to)
        b->rd -= to - from;
    else if (b->rd > from)
        b->rd = from;
}

void mse_av_evict_before(MseAv *a, int audio, double before, size_t need)
{
    Cfb *b;
    size_t i = 0, freed = 0;
    if (!a || need == 0)
        return;
    av_lock(a, audio);
    b = audio ? &a->af : &a->vf;
    while (i < b->n && freed < need) {
        size_t end = i + 1, bytes = b->f[i].n;
        int fits = b->f[i].pts + b->f[i].dur <= before;
        while (!audio && end < b->n && !coded_is_rap(a, &b->f[end])) {
            if (b->f[end].pts + b->f[end].dur > before)
                fits = 0;
            bytes += b->f[end].n;
            end++;
        }
        if (!fits)
            break;
        freed += bytes;
        i = end;
    }
    cfb_drop_span(b, 0, i);
    av_unlock(a, audio);
}

void mse_av_evict_ahead(MseAv *a, int audio, double keep_until, size_t need)
{
    Cfb *b;
    size_t i, freed = 0;
    if (!a || need == 0)
        return;
    av_lock(a, audio);
    b = audio ? &a->af : &a->vf;
    for (i = b->n; i > 0 && freed < need && b->f[i - 1].pts >= keep_until; i--)
        freed += b->f[i - 1].n;
    cfb_drop_span(b, i, b->n);
    av_unlock(a, audio);
}

void mse_av_remove(MseAv *a, int audio, double start, double end)
{
    if (!a)
        return;
    av_lock(a, audio);
    cfb_drop(audio ? &a->af : &a->vf, start, end);
    /* Audio-only (or a full video wipe): nothing left to decode. */
    if (!audio && a->vf.n == 0) {
        a->pend_n[0] = 0;
        a->cluster_tc_n[0] = 0;
        close_video_decoder(a);
    }
    if (!audio)
        maybe_apply_video_rep(a);
    av_unlock(a, audio);
}

void mse_av_flush(MseAv *a, int audio, int video)
{
    if (!a)
        return;
    av_lock_all(a);
    if (audio && a->adec)
        avcodec_flush_buffers(a->adec);
    if (video && a->vdec)
        avcodec_flush_buffers(a->vdec);
    if (video && a->vbsf)
        av_bsf_flush(a->vbsf);
#ifdef __ANDROID__
    if (video)
        mc_flush(a);
#endif
    if (audio) {
        a->pend_n[1] = 0;
        a->cluster_tc_n[1] = 0;
        a->need_rap[1] = 1;
    }
    if (video) {
        a->pend_n[0] = 0;
        a->cluster_tc_n[0] = 0;
        a->need_rap[0] = 1;
        av_frame_free(&a->vhold);
    }
    av_unlock_all(a);
}

static int cfb_span(const Cfb *b, double *first, double *last)
{
    if (!b->n)
        return 0;
    *first = b->f[0].pts;
    *last = b->f[b->n - 1].pts + b->f[b->n - 1].dur;
    return 1;
}

int mse_av_span(MseAv *a, double *first, double *last)
{
    double af = 0, al = 0, vf = 0, vl = 0;
    int ha, hv;
    if (!a || !first || !last)
        return 0;
    av_lock_all(a);
    ha = cfb_span(&a->af, &af, &al);
    /* Resting decoder: leftover stays in vf but the clock must follow
       audio or currentTime snaps when video last is behind playhead. */
    hv = a->v_rest ? 0 : cfb_span(&a->vf, &vf, &vl);
    av_unlock_all(a);
    if (!ha && !hv)
        return 0;
    if (ha && hv) {
        *first = af < vf ? af : vf;
        *last = al > vl ? al : vl;
    } else if (ha) {
        *first = af;
        *last = al;
    } else {
        *first = vf;
        *last = vl;
    }
    return 1;
}

int mse_av_drained(MseAv *a)
{
    int had, a_done, v_done;
    if (!a)
        return 0;
    av_lock_all(a);
    had = a->af.n > 0 || (!a->v_rest && a->vf.n > 0);
    a_done = a->af.n == 0 || a->af.rd >= a->af.n;
    v_done = a->v_rest || a->vf.n == 0 || a->vf.rd >= a->vf.n;
    av_unlock_all(a);
    return had && a_done && v_done;
}

int mse_av_video_pending(MseAv *a)
{
    int n;
    if (!a)
        return 0;
    pthread_mutex_lock(&a->mu);
    n = a->vf.rd < a->vf.n;
    pthread_mutex_unlock(&a->mu);
    return n;
}

int mse_av_video_due(MseAv *a, double clock)
{
    size_t rd;
    int due;
    if (!a)
        return 0;
    pthread_mutex_lock(&a->mu);
    if (a->v_rest
#ifdef __APPLE__
        || a->v_bg_park
#endif
    ) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
#ifdef __ANDROID__
    if (a->mc_hold) {
        due = a->mc_hold_pts_us / 1e6 <= clock + 0.04;
        pthread_mutex_unlock(&a->mu);
        return due;
    }
#endif
    if (a->vhold && a->vhold->pts != AV_NOPTS_VALUE) {
        due = a->vhold->pts / 1000.0 <= clock + 0.04;
        pthread_mutex_unlock(&a->mu);
        return due;
    }
    rd = vf_send_rd(a);
    due = rd < a->vf.n && a->vf.f[rd].pts <= clock + mc_send_ahead(a);
    pthread_mutex_unlock(&a->mu);
    return due;
}

int mse_av_audio_pending(MseAv *a)
{
    int n;
    if (!a)
        return 0;
    av_lock(a, 1);
    n = a->af.rd < a->af.n;
    av_unlock(a, 1);
    return n;
}

int mse_av_has_video(MseAv *a)
{
    int n;
    if (!a)
        return 0;
    pthread_mutex_lock(&a->mu);
    n = !a->v_rest && a->vf.n > 0;
    pthread_mutex_unlock(&a->mu);
    return n;
}

int mse_av_video_failure(MseAv *a, char *out, int cap)
{
    int failed = 0;
    if (!a || !out || cap <= 0)
        return 0;
    out[0] = 0;
#ifdef __ANDROID__
    pthread_mutex_lock(&a->mu);
    failed = a->mc_failure[0] != 0;
    if (failed)
        snprintf(out, (size_t)cap, "%s", a->mc_failure);
    pthread_mutex_unlock(&a->mu);
#endif
    return failed;
}

int mse_av_video_rested(MseAv *a)
{
    int on;
    if (!a)
        return 0;
    pthread_mutex_lock(&a->mu);
    on = a->v_rest;
    pthread_mutex_unlock(&a->mu);
    return on;
}

static void cfb_clear_sent(Cfb *b)
{
    size_t i;
    for (i = 0; i < b->n; i++)
        b->f[i].sent = 0;
}

static void cfb_seek_rd(Cfb *b, double t)
{
    size_t i;
    b->rd = b->n;
    for (i = 0; i < b->n; i++) {
        if (b->f[i].pts + b->f[i].dur >= t - 0.05) {
            b->rd = i;
            return;
        }
    }
}

/* Last keyframe with pts <= t, but only if leftover actually covers t.
 * A RAP in an older island (pts << t) would decode that island and the
 * picture jumps to the buffered edge before SABR fills the seek. */
static void cfb_seek_rd_key(Cfb *b, double t)
{
    size_t i, last_key = b->n;
    int covered = 0;
    for (i = 0; i < b->n; i++) {
        if (b->f[i].key && b->f[i].pts <= t + 0.05)
            last_key = i;
        if (b->f[i].pts <= t + 0.05 && b->f[i].pts + b->f[i].dur >= t - 0.05)
            covered = 1;
    }
    if (!covered || last_key >= b->n) {
        /* No key at/before t: do not start on a P-frame (VP9 SIGILL after -10s). */
        b->rd = b->n;
        return;
    }
    b->rd = last_key;
}

/* After a decoder reset, feed the last key at/before t (one GOP), not the
 * next key 3s ahead (log21: reopen 720p mid-GOP, rap-skip 944→948, freeze). */
static void vf_resync_key(MseAv *a, double t)
{
    size_t i, prev = a->vf.n, next = a->vf.n, key;
    for (i = 0; i < a->vf.n; i++) {
        if (!a->vf.f[i].key)
            continue;
        if (a->vf.f[i].pts <= t + 0.05)
            prev = i;
        else if (next == a->vf.n)
            next = i;
    }
    if (prev < a->vf.n && t - a->vf.f[prev].pts <= 8.0)
        key = prev;
    else
        key = next;
    if (key >= a->vf.n)
        return;
    for (i = key; i < a->vf.n; i++)
        a->vf.f[i].sent = 0;
    a->vf.rd = key;
    a->need_rap[0] = 1;
    vlog("resync key=%.3f rd=%zu n=%zu t=%.3f", a->vf.f[key].pts, key, a->vf.n, t);
}

/* Drop a RAP that is already behind the DAC only when there is nothing
   left to play in this GOP: no later key, or the next key is already due.
   A key 0.7s late with the next key 5s ahead must be decoded — skipping
   it is the 0fps hole at clock 18s/25s (log28 20:39 skip-stale 13.847→19.019). */
static int vf_skip_stale(MseAv *a, double clock)
{
    const double stale = 0.50;
    size_t i, next;
    double kpts, npts;
    if (a->vf.rd >= a->vf.n)
        return 0;
    if (!coded_is_rap(a, &a->vf.f[a->vf.rd]))
        return 0;
    kpts = a->vf.f[a->vf.rd].pts;
    if (kpts >= clock - stale)
        return 0;
    next = a->vf.n;
    for (i = a->vf.rd + 1; i < a->vf.n; i++) {
        if (coded_is_rap(a, &a->vf.f[i])) {
            next = i;
            break;
        }
    }
    npts = next < a->vf.n ? a->vf.f[next].pts : -1;
    if (next < a->vf.n && npts > clock + 0.04)
        return 0;
    /* Last GOP: keep it. Dropping it at EOS/seek-into-tail leaves vf empty
       (log34 skip-stale key=185 next=-1 clock=188 → starve-empty, no ended). */
    if (next >= a->vf.n)
        return 0;
    for (i = a->vf.rd; i < next; i++)
        a->vf.f[i].sent = 1;
    a->vf.rd = next;
    a->need_rap[0] = 1;
#ifdef __ANDROID__
    mc_flush(a);
#endif
    vlog("skip-stale key=%.3f next=%.3f clock=%.3f", kpts, npts, clock);
    return 1;
}

void mse_av_seek(MseAv *a, double seconds)
{
    if (!a)
        return;
    av_lock_all(a);
    if (a->swr)
        swr_free(&a->swr);
    cfb_clear_sent(&a->af);
    cfb_clear_sent(&a->vf);
    cfb_seek_rd(&a->af, seconds);
    {
        /* Chrome currentTime / abort() keep leftover; only remove() drops it.
           If t is not in any island, do not start reading a later island
           (160s leftover while seeking to 60s). Wait for SABR to append. */
        int acov = 0;
        size_t i;
        for (i = 0; i < a->af.n; i++) {
            if (a->af.f[i].pts <= seconds + 0.05 &&
                a->af.f[i].pts + a->af.f[i].dur >= seconds - 0.05)
                acov = 1;
        }
        if (!acov)
            a->af.rd = a->af.n;
    }
    cfb_seek_rd_key(&a->vf, seconds);
    vlog("seek t=%.3f af=%zu vf=%zu ard=%zu vrd=%zu vkey=%.3f", seconds, a->af.n, a->vf.n,
         a->af.rd, a->vf.rd, a->vf.rd < a->vf.n ? a->vf.f[a->vf.rd].pts : -1);
    a->a_sent = seconds - 0.05;
    a->v_sent = seconds - 0.05;
    a->need_rap[0] = 1;
    a->need_rap[1] = 1;
    if (a->adec)
        avcodec_flush_buffers(a->adec);
    /* Chrome currentTime keeps leftover; only remove() drops ranges.
       If t is not on a RAP, park rd (already) and close the decoder so
       we do not present the old GOP at the new clock. SABR appends the
       new island onto vf; buffered still shows the old range.
       Keep pend/cluster_tc: like Chrome, a seek never resets the append
       parser, and the next append continues the fragment mid-mdat. */
    if (a->vf.rd >= a->vf.n || !coded_is_rap(a, &a->vf.f[a->vf.rd])) {
#ifdef __ANDROID__
        /* MediaCodec drops the old GOP in the flush below and need_rap feeds a key next: a reopen
           would hold this (JS) thread ~20 ms, and another on the video thread. */
        if (!a->mc)
#endif
            close_video_decoder(a);
    } else {
        if (a->vdec)
            avcodec_flush_buffers(a->vdec);
#ifdef __APPLE__
        if (a->vt_dec)
            mse_vt_dec_flush(a->vt_dec);
#endif
    }
    a->show_late = 1;
    a->v_pictured = 0;
#ifdef __APPLE__
    while (a->vt_q_n > 0) {
        a->vt_q_n--;
        mse_vt_pb_release(a->vt_q_pb[a->vt_q_n]);
        a->vt_q_pb[a->vt_q_n] = NULL;
    }
#endif
    if (a->vbsf)
        av_bsf_flush(a->vbsf);
#ifdef __ANDROID__
    mc_flush(a);
#endif
#ifdef __APPLE__
    if (a->vt_layer)
        mse_vt_flush(a->vt_layer);
#endif
    av_frame_free(&a->vhold);
#if !PRODUCTION
    vdbg_n = 0;
#endif
    maybe_apply_video_rep(a);
    av_unlock_all(a);
}

#ifdef __APPLE__
static unsigned sw_cv_fmt(enum AVPixelFormat f)
{
    if (f == AV_PIX_FMT_YUV420P || f == AV_PIX_FMT_YUVJ420P)
        return MSE_VT_BLIT_YUV420;
    if (f == AV_PIX_FMT_NV12)
        return MSE_VT_BLIT_NV12;
    return 0;
}
#endif

/* *w, *h: the decoded size; a CPU picture comes back in *bgra (cpu_w × cpu_h). */
static int present_frame(MseAv *a, AVFrame *frame, uint8_t **bgra, int *w, int *h)
{
    VpColor col = vp_color_of(frame, a->vpar);
    *bgra = NULL;
    *w = frame->width;
    *h = frame->height;
#ifdef __APPLE__
    if (a->vt_layer && frame->format == AV_PIX_FMT_VIDEOTOOLBOX && frame->data[3] &&
        a->vt_fail_id != (a->vdec ? a->vdec->codec_id : (enum AVCodecID)0) &&
        mse_vt_show(a->vt_layer, frame->data[3], frame->width, frame->height) == 0)
        return 0;
    /* Layer ResizeAspect-scales. Never vImage/sws to the hole (fullscreen ~500%). */
    if (a->vt_layer) {
        unsigned fmt = sw_cv_fmt((enum AVPixelFormat)frame->format);
        const uint8_t *pic;
        int pw, ph;
        if (fmt &&
            mse_vt_show_sw(a->vt_layer, fmt, (const uint8_t *const *)frame->data, frame->linesize,
                           frame->width, frame->height) == 0)
            return 0;
        if (!a->vt_cpu)
            a->vt_cpu = vp_cpu_create();
        if (!a->vt_cpu || vp_cpu_convert(a->vt_cpu, frame, col, 0, 0, &pic, &pw, &ph) != 0)
            return -1;
        if (mse_vt_show_bgra(a->vt_layer, pic, pw, ph) == 0)
            return 0;
        cpu_frame(a, pw, ph, pic);
        *bgra = a->cpu_bgra;
        return 0;
    }
#endif
#ifdef MSE_WIN_HW
    if (frame->format == AV_PIX_FMT_D3D11) {
        a->hw_errs = 0;
        /* Unpresented frames drop (present_show logs why): a CPU blit via on_frame would turn the
           Kotlin side to SW painting on the Canvas the swapchain still owns. */
        if (frame->data[0])
            mse_d3d11_present_show((ID3D11Texture2D *)frame->data[0],
                                   (UINT)(uintptr_t)frame->data[1], frame->width, frame->height,
                                   a->present_owner);
        return 0;
    }
#endif
    if (vpresent_show_frame(a->cpu, a, frame, col) != VP_OK)
        return -1;
#ifdef MSE_LINUX_HW
    if (frame->hw_frames_ctx)
        a->hw_errs = 0;
#endif
    *bgra = a->cpu_bgra;
    return 0;
}

static uint8_t *pcm_scratch(MseAv *a, size_t n)
{
    uint8_t *p;
    if (a->pcm && a->pcm_n >= n)
        return a->pcm;
    p = realloc(a->pcm, n);
    if (!p)
        return NULL;
    a->pcm = p;
    a->pcm_n = n;
    return p;
}

void mse_av_set_window(MseAv *a, void *window)
{
    if (!a)
        return;
#ifdef __ANDROID__
    {
        ANativeWindow *win = (ANativeWindow *)window;
        pthread_mutex_lock(&a->mu);
        if (a->mc_win == win) {
            pthread_mutex_unlock(&a->mu);
            return;
        }
        mc_close(a);
        a->mc_win = win;
        a->mc_fail = 0;
        a->mc_open_tries = 0;
        vlog("window %p vpar=%d %dx%d", (void *)win, a->vpar ? 1 : 0,
             a->vpar ? a->vpar->width : 0, a->vpar ? a->vpar->height : 0);
        if (win)
            mc_try_open(a);
        pthread_mutex_unlock(&a->mu);
    }
#elif defined(__APPLE__)
    pthread_mutex_lock(&a->mu);
    if (a->vt_layer == window) {
        pthread_mutex_unlock(&a->mu);
        return;
    }
    if (a->vt_layer)
        mse_vt_flush(a->vt_layer);
    a->vt_layer = window;
    vlog("vt-layer %p", window);
    pthread_mutex_unlock(&a->mu);
#else
    (void)window;
#endif
}

void mse_av_set_present_owner(MseAv *a, const void *owner)
{
    if (!a)
        return;
#ifdef MSE_WIN_HW
    pthread_mutex_lock(&a->mu);
    a->present_owner = owner;
    pthread_mutex_unlock(&a->mu);
#else
    (void)owner;
#endif
}

#ifdef __APPLE__
void mse_av_recover_display(MseAv *a, double clock)
{
    int i;
    if (!a)
        return;
    pthread_mutex_lock(&a->mu);
    for (i = 0; i < a->vt_q_n; i++)
        mse_vt_pb_release(a->vt_q_pb[i]);
    a->vt_q_n = 0;
    if (a->v_rest) {
        /* JS audio-only rest: do not turn video back on. */
        mse_vt_resume_presents(a->vt_layer);
        pthread_mutex_unlock(&a->mu);
        return;
    }
    if (!a->v_bg_park) {
        mse_vt_resume_presents(a->vt_layer);
        pthread_mutex_unlock(&a->mu);
        return;
    }
    a->v_bg_park = 0;
    cfb_drop_fake(&a->vf);
    vf_resync_key(a, clock);
    a->show_late = 1;
    a->v_pictured = 0;
    if (a->vt_dec) {
        mse_vt_dec_close(a->vt_dec);
        a->vt_dec = NULL;
    }
    mse_vt_resume_presents(a->vt_layer);
    if (a->vf.rd < a->vf.n)
        a->v_sent = a->vf.f[a->vf.rd].pts;
    os_log(os_log_create("com.ymusic.mse", "v"),
           "mse-v bg-unpark resync clock=%.3f rd=%zu/%zu", clock, a->vf.rd, a->vf.n);
    pthread_mutex_unlock(&a->mu);
}
#endif

void mse_av_set_blit_size(MseAv *a, int w, int h)
{
    if (a)
        vpresent_set_size(a->cpu, w, h);
}

/* After INVALIDDATA dav1d stays poisoned: flush is not enough — later
   keys also fail (log21 12.045/18.018 key=1) and the clock starve-aheads
   with a frozen picture. Close and reopen, then resync from the previous
   key near the playhead. Split VP9 superframes without a BSF are
   INVALIDDATA on their own — those must not tear the decoder down. */
static void video_decode_error(MseAv *a, double pts, int err)
{
    if (a->vpar && a->vpar->codec_id == AV_CODEC_ID_VP9 && !a->vbsf)
        return;
#ifdef __APPLE__
    if (a->vdec && a->vdec->hw_device_ctx) {
        a->vt_fail_id = a->vdec->codec_id;
        mse_critf(MSE_LOG_WARN, "mse-v vt-fail software id=%d", (int)a->vt_fail_id);
    } else if (a->vdec && err == AVERROR(ENOSYS)) {
        /* Software `av1` send() is ENOSYS without libdav1d. Do not reopen.
           dav1d Invalid OBU after rest is a missing key, not ENOSYS. */
        a->vt_fail_id = a->vdec->codec_id;
        mse_critf(MSE_LOG_WARN, "mse-v decode-dead id=%d t=%.3f", (int)a->vt_fail_id, pts);
        close_video_decoder(a);
        return;
    }
#endif
#ifdef MSE_LINUX_HW
    /* Stream switches and seeks error in software too; only a run of them condemns the GPU. */
    if (a->vdec && a->vdec->hw_device_ctx && ++a->hw_errs >= 3) {
        a->hw_errs = 0;
        mse_hwdev_mark_failed(a->vdec->codec_id);
    }
#endif
#ifdef MSE_WIN_HW
    /* ENOSYS: native av1 opened without the device (lost after the pick); the latch makes the
       reopen below software. A removed device fails every codec; that reopen goes software
       without a latch. */
    if (a->vdec && err == AVERROR(ENOSYS) && a->d3d11_fail_id != a->vdec->codec_id) {
        a->hw_errs = 0;
        a->d3d11_fail_id = a->vdec->codec_id;
        mse_critf(MSE_LOG_WARN, "mse-v decode-dead id=%d %s: software from now on",
                  (int)a->vdec->codec_id, a->vdec->codec ? a->vdec->codec->name : "?");
    } else if (a->vdec && a->vdec->hw_device_ctx && !mse_d3d11_check_lost() && ++a->hw_errs >= 3) {
        a->hw_errs = 0;
        a->d3d11_fail_id = a->vdec->codec_id;
        mse_critf(MSE_LOG_WARN, "d3d11va rejected id=%d: software from now on",
                  (int)a->vdec->codec_id);
    }
#endif
    mse_critf(MSE_LOG_WARN, "mse-v decode-err close-reopen t=%.3f", pts);
    close_video_decoder(a);
    if (a->vpar)
        open_decoder(a, 1, a->vpar);
    vf_resync_key(a, a->v_sent >= 0 ? a->v_sent : pts);
}

#ifdef MSE_WIN_HW
/* A latched codec (or any, once the device is lost) still in its D3D11-configured context
   decodes single-threaded or not at all (native av1); reopen it as libvpx / libdav1d /
   threaded. 1 if it reopened. */
static int d3d11_reopen_sw(MseAv *a, double t)
{
    if (!a->vdec || !a->vdec->hw_device_ctx ||
        (a->vdec->codec_id != a->d3d11_fail_id && !mse_d3d11_lost()))
        return 0;
    close_video_decoder(a);
    if (a->vpar)
        open_decoder(a, 1, a->vpar);
    vf_resync_key(a, a->v_sent >= 0 ? a->v_sent : t);
    return 1;
}
#endif

#ifdef __APPLE__
/* As present_frame, for a picture of the own VT session. */
static int present_cvpb(MseAv *a, void *pb, uint8_t **bgra, int *w, int *h)
{
    CVPixelBufferRef cv = (CVPixelBufferRef)pb;
    *bgra = NULL;
    *w = (int)CVPixelBufferGetWidth(cv);
    *h = (int)CVPixelBufferGetHeight(cv);
    if (a->vt_layer)
        return mse_vt_show(a->vt_layer, pb, *w, *h) == 0 ? 0 : -1;
    if (vpresent_show_cvpb(a->cpu, a, pb, vp_color_of(NULL, a->vpar)) != VP_OK)
        return -1;
    *bgra = a->cpu_bgra;
    return 0;
}

static void vt_q_put(MseAv *a, void *pb, int w, int h, double pts)
{
    int i, j;
    if (a->vt_q_n >= 8) {
        /* Full: drop the newest (end), never the next-to-show at [0]. */
        if (pts >= a->vt_q_pts[a->vt_q_n - 1]) {
            mse_vt_pb_release(pb);
            return;
        }
        mse_vt_pb_release(a->vt_q_pb[a->vt_q_n - 1]);
        a->vt_q_n--;
    }
    for (i = 0; i < a->vt_q_n && a->vt_q_pts[i] <= pts; i++)
        ;
    for (j = a->vt_q_n; j > i; j--) {
        a->vt_q_pb[j] = a->vt_q_pb[j - 1];
        a->vt_q_pts[j] = a->vt_q_pts[j - 1];
        a->vt_q_w[j] = a->vt_q_w[j - 1];
        a->vt_q_h[j] = a->vt_q_h[j - 1];
    }
    a->vt_q_pb[i] = pb;
    a->vt_q_pts[i] = pts;
    a->vt_q_w[i] = w;
    a->vt_q_h[i] = h;
    a->vt_q_n++;
}

static int vt_q_take(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts)
{
    const double early = 0.04;
    const double late = 0.08;
    while (a->vt_q_n > 0) {
        double p = a->vt_q_pts[0];
        void *pb;
        int i;
        if (p > clock + early)
            return 0;
        pb = a->vt_q_pb[0];
        if (p < clock - late) {
            mse_vt_pb_release(pb);
        } else if (present_cvpb(a, pb, bgra, w, h) == 0) {
            mse_vt_pb_release(pb);
            *pts = p;
            a->v_sent = p;
            a->show_late = 0;
            a->vt_q_n--;
            for (i = 0; i < a->vt_q_n; i++) {
                a->vt_q_pb[i] = a->vt_q_pb[i + 1];
                a->vt_q_pts[i] = a->vt_q_pts[i + 1];
                a->vt_q_w[i] = a->vt_q_w[i + 1];
                a->vt_q_h[i] = a->vt_q_h[i + 1];
            }
            if (vdbg_ok())
                vlog("vt-show pts=%.3f clock=%.3f q=%d", p, clock, a->vt_q_n);
            return 1;
        } else {
            mse_vt_pb_release(pb);
        }
        a->vt_q_n--;
        for (i = 0; i < a->vt_q_n; i++) {
            a->vt_q_pb[i] = a->vt_q_pb[i + 1];
            a->vt_q_pts[i] = a->vt_q_pts[i + 1];
            a->vt_q_w[i] = a->vt_q_w[i + 1];
            a->vt_q_h[i] = a->vt_q_h[i + 1];
        }
    }
    return 0;
}

static int pull_vt(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts)
{
    const double late = 0.08;
    int i;
    if (!a->vt_dec)
        return 0;
    if (vt_q_take(a, clock, bgra, w, h, pts))
        return 1;
    if (a->vt_q_n >= 4)
        return 0;
    for (i = 0; i < 96; i++) {
        void *pb = NULL;
        int rw = 0, rh = 0, output;
        Cf *c;
        while (a->vf.rd < a->vf.n && a->need_rap[0] &&
               !coded_is_rap(a, &a->vf.f[a->vf.rd])) {
            a->vf.f[a->vf.rd].sent = 1;
            a->vf.rd++;
        }
        if (a->vf.rd >= a->vf.n)
            return vt_q_take(a, clock, bgra, w, h, pts);
        c = &a->vf.f[a->vf.rd];
        /* Send in DTS order. A P 50ms ahead is a ref for the B due now. */
        if (c->dts > clock + MSE_SEND_AHEAD && c->pts > clock + MSE_SEND_AHEAD)
            return vt_q_take(a, clock, bgra, w, h, pts);
        output = (a->v_seen_idr || coded_is_rap(a, c)) && c->pts >= clock - late;
        if (mse_vt_dec_send(a->vt_dec, c->data, (int)c->n, output) < 0) {
            c->sent = 1;
            a->vf.rd++;
            continue;
        }
        if (coded_is_rap(a, c)) {
            a->need_rap[0] = 0;
            a->v_seen_idr = 1;
        }
        c->sent = 1;
        a->vf.rd++;
        if (output && mse_vt_dec_recv(a->vt_dec, &pb, &rw, &rh) && pb)
            vt_q_put(a, pb, rw, rh, c->pts);
        if (vt_q_take(a, clock, bgra, w, h, pts))
            return 1;
        if (a->vt_q_n >= 4)
            return 0;
    }
    return 0;
}
#endif

static int pull_video(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts)
{
    AVFrame *frame;
    int i, got;
    const double early = 0.04;
    const double late = 0.08;

    if (!a || !bgra || !w || !h || !pts)
        return -1;
    *bgra = NULL;
    pthread_mutex_lock(&a->mu);
    if (a->v_rest
#ifdef __APPLE__
        || a->v_bg_park
#endif
    ) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
    maybe_apply_video_rep(a);
    while (vf_skip_stale(a, clock))
        ;
    /* A running MediaCodec takes the next GOP early; only a resync waits. */
    if (a->vf.rd < a->vf.n && coded_is_rap(a, &a->vf.f[a->vf.rd]) &&
        a->vf.f[a->vf.rd].pts > clock + early
#ifdef __ANDROID__
        && !(a->mc && !a->need_rap[0])
#endif
    ) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
#ifdef __APPLE__
    if (a->vpar && apple_vt_codec(a->vpar) && a->vt_fail_id != a->vpar->codec_id) {
        if (!a->vt_dec && a->vf.rd < a->vf.n &&
            (!a->need_rap[0] || coded_is_rap(a, &a->vf.f[a->vf.rd]))) {
            a->vt_dec = open_apple_vt(a);
            if (a->vt_dec)
                vlog("open %s vt-dec %dx%d",
                     a->vpar->codec_id == AV_CODEC_ID_VP9 ? "vp9" : "h264",
                     a->vpar->width, a->vpar->height);
            else {
                vlog("vt-dec open fail id=%d", (int)a->vpar->codec_id);
                a->vt_fail_id = a->vpar->codec_id;
                if (a->vpar->codec_id == AV_CODEC_ID_VP9)
                    vp9_vt_unavailable = 1;
            }
        }
        if (a->vt_dec) {
            int r = pull_vt(a, clock, bgra, w, h, pts);
            pthread_mutex_unlock(&a->mu);
            return r;
        }
    }
#endif
#ifdef __ANDROID__
    if (a->vf.n == 0) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
    if (mc_try_open(a) == MC_OPEN_WAIT) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
    if (a->mc) {
        int r = pull_mc(a, clock, w, h, pts);
        pthread_mutex_unlock(&a->mu);
        return r;
    }
#endif
    if (!a->vdec && a->vpar && a->vf.rd < a->vf.n &&
        (!a->need_rap[0] || coded_is_rap(a, &a->vf.f[a->vf.rd])))
        open_decoder(a, 1, a->vpar);
#ifdef __APPLE__
    if (a->vdec && a->vdec->hw_device_ctx && a->vt_fail_id == a->vdec->codec_id)
        video_decode_error(a, clock, 0);
#endif
#ifdef MSE_WIN_HW
    d3d11_reopen_sw(a, clock);
#endif
    if (!a->vdec || a->vf.n == 0) {
        pthread_mutex_unlock(&a->mu);
        return 0;
    }
    if (a->vhold) {
        double p = a->vhold->pts != AV_NOPTS_VALUE ? a->vhold->pts / 1000.0 : clock;
        double d = p - clock;
        if (p > clock + early) {
            if (d > 0.2)
                vlog_hold_far("HOLD-FAR", clock, d);
            if (vdbg_ok() || d > 0.2)
                vlog_state(a, clock, d > 0.2 ? "HOLD-FAR" : "hold", d);
            pthread_mutex_unlock(&a->mu);
            return 0;
        }
        if (p < clock - late && !(a->show_late && p >= clock - 0.35)) {
            vlog_state(a, clock, "hold-late", d);
            av_frame_free(&a->vhold);
        } else if (!a->v_seen_idr) {
            av_frame_free(&a->vhold);
        } else if (present_frame(a, a->vhold, bgra, w, h) == 0) {
            *pts = p;
            a->v_sent = p;
            a->show_late = 0;
            if (vdbg_ok())
                vlog_state(a, clock, "show-hold", d);
            av_frame_free(&a->vhold);
            pthread_mutex_unlock(&a->mu);
            return 1;
        } else {
            av_frame_free(&a->vhold);
        }
    }
    if (!a->vfr) {
        a->vfr = av_frame_alloc();
        if (!a->vfr) {
            pthread_mutex_unlock(&a->mu);
            return -1;
        }
    }
    frame = a->vfr;
    {
    int late_skips = 0;
    for (i = 0; i < 96; i++) {
        if (!a->vdec)
            break;
        got = avcodec_receive_frame(a->vdec, frame);
        if (got == 0) {
            double p = frame->pts != AV_NOPTS_VALUE ? frame->pts / 1000.0 : clock;
            double d = p - clock;
            if (p > clock + early && frame->pts != AV_NOPTS_VALUE) {
                if (!a->vhold) {
                    a->vhold = av_frame_alloc();
                    if (!a->vhold) {
                        pthread_mutex_unlock(&a->mu);
                        return -1;
                    }
                } else {
                    av_frame_unref(a->vhold);
                }
                av_frame_move_ref(a->vhold, frame);
                if (d > 0.2)
                    vlog_hold_far("RECV-HOLD-FAR", clock, d);
                if (vdbg_ok() || d > 0.2)
                    vlog_state(a, clock, d > 0.2 ? "RECV-HOLD-FAR" : "recv-hold", d);
                pthread_mutex_unlock(&a->mu);
                return 0;
            }
            /* show_late: decode the GOP but only present near the playhead.
               Showing pts=1890 at clock=1895 after seek is the smashed picture. */
            if (p < clock - late && frame->pts != AV_NOPTS_VALUE &&
                !(a->show_late && p >= clock - 0.35) &&
                !(late_skips > 0 && p >= clock - 1.0)) {
                if (vdbg_ok() || d < -0.25)
                    vlog_state(a, clock, "recv-late", d);
                av_frame_unref(frame);
                /* 4K libvpx sits ~0.5s behind. Breaking here never reached
                   the 1s catch-up present → 0fps at clock 18s/25s (log28 20:47). */
                if (late_skips++ < 8)
                    i--;
                continue;
            }
            if (!a->v_seen_idr) {
                av_frame_unref(frame);
                continue;
            }
            if (present_frame(a, frame, bgra, w, h) < 0)
                break;
            *pts = p;
            a->v_sent = p;
            a->show_late = 0;
            if (vdbg_ok())
                vlog_state(a, clock, "show", d);
            av_frame_unref(frame);
            pthread_mutex_unlock(&a->mu);
            return 1;
        }
        maybe_apply_video_rep(a);
        if (!a->vdec && a->vpar && a->vf.rd < a->vf.n &&
            (!a->need_rap[0] || coded_is_rap(a, &a->vf.f[a->vf.rd])))
            open_decoder(a, 1, a->vpar);
        if (!a->vdec)
            break;
        {
            size_t rd0 = a->vf.rd;
            while (a->vf.rd < a->vf.n && a->need_rap[0] &&
                   !coded_is_rap(a, &a->vf.f[a->vf.rd])) {
                a->vf.f[a->vf.rd].sent = 1;
                a->vf.rd++;
            }
            if (a->vf.rd != rd0)
                vlog_state(a, clock, "rap-skip", a->vf.rd < a->vf.n ? a->vf.f[a->vf.rd].pts : -1);
        }
        if (vf_skip_stale(a, clock))
            break;
        if (a->vf.rd >= a->vf.n) {
            vlog_starve_empty(a, clock, got);
            break;
        }
        if (a->vf.f[a->vf.rd].pts > clock + MSE_SEND_AHEAD) {
            vlog_starve_ahead(a, clock, a->vf.rd);
            break;
        }
        {
            double sp = a->vf.f[a->vf.rd].pts;
            int key = a->vf.f[a->vf.rd].key;
            int rap = coded_is_rap(a, &a->vf.f[a->vf.rd]);
            int sr = send_coded(&a->vf, a->vdec, a->vbsf);
#ifdef MSE_WIN_HW
            if (d3d11_reopen_sw(a, clock))
                continue;
#endif
            if (sr < 0) {
                char err[64];
                av_strerror(sr, err, sizeof(err));
                mse_critf(MSE_LOG_WARN, "mse-v send-err pts=%.3f key=%d r=%d %s", sp, key, sr,
                          err);
                vlog_state(a, clock, "send-fail", sp);
                /* Split VP9 superframes without a BSF are INVALIDDATA on
                   their own — drop this packet, keep the GOP. */
                if (a->vpar && a->vpar->codec_id == AV_CODEC_ID_VP9 && !a->vbsf) {
                    a->vf.f[a->vf.rd].sent = 1;
                    a->vf.rd++;
                } else {
                    video_decode_error(a, sp, sr);
                }
                continue;
            }
            if (rap) {
                a->need_rap[0] = 0;
                a->v_seen_idr = 1;
            }
            if (vdbg_ok())
                vlog("send pts=%.3f key=%d clock=%.3f", sp, key, clock);
        }
    }
    }
    av_frame_unref(frame);
    pthread_mutex_unlock(&a->mu);
    return 0;
}

int mse_av_pull_video(MseAv *a, double clock, uint8_t **bgra, int *bw, int *bh, int *w, int *h,
                      double *pts)
{
    int r;
    if (!bw || !bh)
        return -1;
    r = pull_video(a, clock, bgra, w, h, pts);
    /* cpu_w/h: written by this thread's present_frame, which produced *bgra. */
    *bw = r > 0 && *bgra ? a->cpu_w : 0;
    *bh = r > 0 && *bgra ? a->cpu_h : 0;
    return r;
}

int mse_av_pull_audio(MseAv *a, double clock, double until, int16_t **s16, int *frames, int *rate, int *ch,
                      double *pts)
{
    AVFrame *frame;
    uint8_t *tmp;
    uint8_t *dsts[1];
    int got, max_out;
    AVChannelLayout out_ch;

    if (!a || !s16 || !frames || !rate || !ch || !pts)
        return -1;
    *s16 = NULL;
    av_lock(a, 1);
    if (!a->adec) {
        av_unlock(a, 1);
        return 0;
    }
    if (!a->afr) {
        a->afr = av_frame_alloc();
        if (!a->afr) {
            av_unlock(a, 1);
            return -1;
        }
    }
    frame = a->afr;
    got = 1;
    {
        int i;
        /* Skip packets already behind the playhead so an overlapping append
           cannot dump old PTS into the ring. */
        const double behind = clock - 0.05;
        for (i = 0; i < 16; i++) {
            got = avcodec_receive_frame(a->adec, frame);
            if (got == 0) {
                double p = frame->pts != AV_NOPTS_VALUE ? frame->pts / 1000.0 : clock;
                if (frame->pts != AV_NOPTS_VALUE && p < behind) {
                    av_frame_unref(frame);
                    continue;
                }
                break;
            }
            while (a->af.rd < a->af.n && a->af.f[a->af.rd].pts < behind) {
                a->af.f[a->af.rd].sent = 1;
                a->af.rd++;
            }
            if (a->af.rd >= a->af.n || a->af.f[a->af.rd].pts > until + 0.08)
                break;
            if (send_coded(&a->af, a->adec, NULL) < 0)
                break;
        }
    }
    if (got != 0) {
        av_frame_unref(frame);
        av_unlock(a, 1);
        return 0;
    }
    {
        int ch = frame->ch_layout.nb_channels > 0 ? frame->ch_layout.nb_channels
                                                  : (a->channels > 0 ? a->channels : 2);
        int rate = frame->sample_rate > 0 ? frame->sample_rate
                                          : (a->rate > 0 ? a->rate : 48000);
        int fmt = frame->format;
        if (a->swr && (a->swr_ch != ch || a->swr_rate != rate || a->swr_fmt != fmt))
            swr_free(&a->swr);
        a->channels = ch;
        a->rate = rate;
        if (!a->swr) {
            memset(&out_ch, 0, sizeof(out_ch));
            av_channel_layout_default(&out_ch, ch);
            if (swr_alloc_set_opts2(&a->swr, &out_ch, AV_SAMPLE_FMT_S16, rate,
                                    &frame->ch_layout, (enum AVSampleFormat)fmt,
                                    rate, 0, NULL) < 0 ||
                swr_init(a->swr) < 0) {
                av_channel_layout_uninit(&out_ch);
                av_frame_unref(frame);
                av_unlock(a, 1);
                return -1;
            }
            av_channel_layout_uninit(&out_ch);
            a->swr_ch = ch;
            a->swr_rate = rate;
            a->swr_fmt = fmt;
        }
    }
    max_out = (int)swr_get_out_samples(a->swr, frame->nb_samples) + 256;
    if (max_out < 0)
        max_out = frame->nb_samples + 256;
    tmp = pcm_scratch(a, (size_t)max_out * (size_t)a->channels * 2);
    if (!tmp) {
        av_frame_unref(frame);
        av_unlock(a, 1);
        return -1;
    }
    dsts[0] = tmp;
    got = swr_convert(a->swr, dsts, max_out,
                      (const uint8_t **)frame->extended_data, frame->nb_samples);
    *pts = frame->pts != AV_NOPTS_VALUE ? frame->pts / 1000.0 : clock;
    av_frame_unref(frame);
    if (got <= 0) {
        av_unlock(a, 1);
        return 0;
    }
    a->a_sent = *pts;
    *s16 = (int16_t *)tmp;
    *frames = got;
    *rate = a->rate;
    *ch = a->channels;
    av_unlock(a, 1);
    return 1;
}

double mse_av_duration(MseAv *a)
{
    double d;
    if (!a)
        return 0;
    pthread_mutex_lock(&a->mu);
    d = a->duration;
    pthread_mutex_unlock(&a->mu);
    return d;
}

int mse_av_buffered(MseAv *a, int audio, double *pairs, int max_pairs)
{
    Cfb *b;
    size_t i;
    int n = 0;
    double rs, re;
    if (!a || !pairs || max_pairs <= 0)
        return 0;
    av_lock(a, audio);
    b = audio ? &a->af : &a->vf;
    if (b->n == 0) {
        av_unlock(a, audio);
        return 0;
    }
    rs = b->f[0].pts;
    re = b->f[0].pts + b->f[0].dur;
    for (i = 1; i < b->n && n < max_pairs; i++) {
        double ps = b->f[i].pts;
        double pe = ps + b->f[i].dur;
        if (ps <= re + 0.4) {
            if (pe > re)
                re = pe;
        } else {
            pairs[n * 2] = rs;
            pairs[n * 2 + 1] = re;
            n++;
            rs = ps;
            re = pe;
        }
    }
    if (n < max_pairs) {
        pairs[n * 2] = rs;
        pairs[n * 2 + 1] = re;
        n++;
    }
    av_unlock(a, audio);
    return n;
}
