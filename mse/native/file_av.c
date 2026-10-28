#include "file_av.h"
#include "audio_fx.h"
#include "mse_ffmpeg.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/pixfmt.h>
#include <libavutil/rational.h>
#include <libavutil/samplefmt.h>
#include <libavutil/time.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(__APPLE__)
#include <AudioToolbox/AudioToolbox.h>
#define FILE_AV_AQ 1
#elif defined(__linux__) && !defined(__ANDROID__)
#include "aq_pulse.h"
#include "mse_hwdev.h"
#define FILE_AV_AQ 1
#define FILE_AV_LINUX_HW 1
#elif defined(_WIN32)
#include "aq_winmm.h"
#define FILE_AV_AQ 1
#define FILE_AV_WIN_HW 1
#include "mse_d3d11.h"
#endif
#ifdef __APPLE__
#include <mach/mach_time.h>
#include "mse_vt_blit.h"
#include "mse_vt_present.h"
#endif
#include "mse_ffmpeg.h"

#define OUT_RATE 48000
#define AQ_NBUFS 4
#define OUT_CH 2
#define V_EARLY 0.001
#define V_STALE 0.020
#define VOUT_N 4
#define MAX_DROP 2
#define IDLE_NS_MAX (16 * 1000 * 1000)


enum { ST_AUDIO = 0, ST_VIDEO = 1, ST_TEXT = 2 };

typedef struct {
    int kind;
    int index;
    int w, h;
    char lang[16];
    char codec[32];
} StreamInfo;

typedef struct {
    AVPacket **p;
    int n, cap, rd;
} Pq;

struct FileAv {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    pthread_t th;
    int th_on;
#ifdef FILE_AV_AQ
    pthread_t ath;
    int ath_on;
    int a_busy;
#endif
    int stop;
    int dec_busy;
    int dec_epoch;
    int fmt_eof;
    int dec_drain;
    int opened;
    int playing;
    int ended;
    int want_video;
    int v_need_key; /* after rest: drop until a key at/after the clock */
#ifdef FILE_AV_LINUX_HW
    AVPacket *v_first; /* first packet into the current vdec, until it yields a frame */
#endif
    int audio_index; /* -1 = first audio */
    int blit_w;
    int blit_h;
    double speed;
    AudioFx *fx;
    double hold;
    double origin_ns;
    double duration;
    double last_prog_ns;
    double last_vpts;
    int64_t idle_ns;
    int logged_drop;
    char err[256];

    AVFormatContext *fmt;
    AVCodecContext *adec;
    AVCodecContext *vdec;
    AVCodecContext *sdec;
    int a_st, v_st, s_st;
    int subtitle_index; /* -1 off; kept across close so reopen restores */
    SwrContext *swr;
    SwrContext *swr_speed;
    double swr_speed_in;
    uint8_t *pcm;
    size_t pcm_n;
    uint8_t *pcm2;
    size_t pcm2_n;
    struct SwsContext *sws;
    int sws_dw, sws_dh, sws_sw, sws_sh, sws_fmt;
    uint8_t *bgra;
    int bgra_n;
    int vw, vh;
    Pq aq, vq;
    AVFrame *afr, *vfr, *vsw;
    AVFrame *vout[VOUT_N];
    int vout_rd, vout_n;
    StreamInfo *streams;
    int nstreams;

    FileAvFrameFn on_frame;
    FileAvPcmFn on_pcm;
    FileAvProgressFn on_prog;
    FileAvEventFn on_event;
    FileAvCaptionFn on_caption;
    void *user;
    int cap_id, cap_n;
    struct {
        int id;
        double start, end;
        char text[1024];
    } cap_q[8];

    enum AVColorSpace col_space;
    enum AVColorRange col_range;
#ifdef __APPLE__
    MseVtBlit vt_blit;
    void *vt_layer;
#endif
#ifdef FILE_AV_WIN_HW
    /* Canvas HWND the JNI attached the D3D11 presenter to (not owned); NULL = CPU blit. */
    void *d3d11_hwnd;
    /* The HWND came or went: file_av_step re-checks whether the video decoder runs on D3D11VA. */
    int reopen_video;
#endif
#ifdef FILE_AV_AQ
    /* AudioQueue DAC. samples_played is the gold clock (same as leftover MSE). */
    AudioQueueRef dac;
    uint8_t *ring;
    int ring_cap, ring_r, ring_w, ring_n;
    int dac_started, dac_buf_bytes, flushing;
    int out_rate, out_ch;
    int64_t samples_played;
    double seek_base;
    double dac_tick_ns;
    float volume;
#endif
};

static double now_ns(void)
{
#ifdef __APPLE__
    static mach_timebase_info_data_t tb;
    static int once;
    if (!once) {
        mach_timebase_info(&tb);
        once = 1;
    }
    return (double)mach_absolute_time() * (double)tb.numer / (double)tb.denom;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
#endif
}

static void set_err(FileAv *p, const char *msg)
{
    snprintf(p->err, sizeof(p->err), "%s", msg ? msg : "");
}

static void pq_clear(Pq *q)
{
    int i;
    /* Popped slots (0..rd-1) were already av_packet_free'd. Freeing them
       again is the seek SIGSEGV in av_freep. */
    for (i = q->rd; i < q->n; i++)
        av_packet_free(&q->p[i]);
    free(q->p);
    q->p = NULL;
    q->n = q->cap = q->rd = 0;
}

static int pq_push(Pq *q, AVPacket *pkt)
{
    if (q->n >= q->cap) {
        int cap = q->cap ? q->cap * 2 : 16;
        AVPacket **n = realloc(q->p, (size_t)cap * sizeof(*n));
        if (!n)
            return -1;
        q->p = n;
        q->cap = cap;
    }
    q->p[q->n++] = pkt;
    return 0;
}

static uint8_t *grow_bytes(uint8_t **p, size_t *cap, size_t n)
{
    uint8_t *np;
    if (*p && *cap >= n)
        return *p;
    np = realloc(*p, n);
    if (!np)
        return NULL;
    *p = np;
    *cap = n;
    return np;
}

static AVPacket *pq_pop(Pq *q)
{
    AVPacket *pkt;
    if (q->rd >= q->n)
        return NULL;
    pkt = q->p[q->rd];
    q->p[q->rd] = NULL;
    q->rd++;
    if (q->rd == q->n)
        q->rd = q->n = 0;
    return pkt;
}

static void vout_clear(FileAv *p)
{
    while (p->vout_n > 0) {
        av_frame_unref(p->vout[p->vout_rd]);
        p->vout_rd = (p->vout_rd + 1) % VOUT_N;
        p->vout_n--;
    }
}

static AVFrame *vout_head(FileAv *p)
{
    return p->vout_n > 0 ? p->vout[p->vout_rd] : NULL;
}

static void vout_pop(FileAv *p)
{
    if (p->vout_n <= 0)
        return;
    av_frame_unref(p->vout[p->vout_rd]);
    p->vout_rd = (p->vout_rd + 1) % VOUT_N;
    p->vout_n--;
    pthread_cond_broadcast(&p->cv);
}

static int vout_push(FileAv *p, AVFrame *src)
{
    int i;
    if (p->vout_n >= VOUT_N || !src)
        return -1;
    i = (p->vout_rd + p->vout_n) % VOUT_N;
    if (!p->vout[i]) {
        p->vout[i] = av_frame_alloc();
        if (!p->vout[i])
            return -1;
    }
    av_frame_unref(p->vout[i]);
    if (av_frame_ref(p->vout[i], src) < 0)
        return -1;
    p->vout_n++;
    return 0;
}

static const AVCodec *find_vdec(enum AVCodecID id)
{
    const AVCodec *c;
#ifdef FILE_AV_LINUX_HW
    if ((id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_AV1) && mse_hwdev_usable(id)) {
        c = avcodec_find_decoder_by_name(id == AV_CODEC_ID_VP9 ? "vp9" : "av1");
        if (c)
            return c;
    }
#endif
    if (id == AV_CODEC_ID_VP9) {
        c = avcodec_find_decoder_by_name("libvpx-vp9");
        if (c)
            return c;
        return avcodec_find_decoder_by_name("vp9");
    }
    if (id == AV_CODEC_ID_VP8)
        return avcodec_find_decoder_by_name("libvpx");
    if (id == AV_CODEC_ID_AV1) {
        c = avcodec_find_decoder_by_name("libdav1d");
        if (c)
            return c;
    }
    return avcodec_find_decoder(id);
}

#ifdef FILE_AV_WIN_HW
/* The native decoder to run on D3D11VA, or NULL for software: only with a Canvas to present
   into (readback + swscale is slower than software decoding at 4K60). */
static const AVCodec *d3d11_vdec(const FileAv *p, const AVCodecParameters *par)
{
    const char *name;
    const AVCodec *c;
    if (!p || !p->d3d11_hwnd || !mse_d3d11_par_ok(par))
        return NULL;
    switch (par->codec_id) {
    case AV_CODEC_ID_H264:
        name = "h264";
        break;
    case AV_CODEC_ID_HEVC:
        name = "hevc";
        break;
    case AV_CODEC_ID_VP9:
        name = "vp9";
        break;
    case AV_CODEC_ID_AV1:
        name = "av1";
        break;
    default:
        return NULL;
    }
    c = avcodec_find_decoder_by_name(name);
    return c && mse_d3d11_can_decode(c) ? c : NULL;
}
#endif

/* Prefer VideoToolbox/etc so 4K HEVC can keep up; blit still goes through swscale
 * after av_hwframe_transfer_data. Fall back to the first software pixfmt. */
static enum AVPixelFormat get_format_prefer_hw(AVCodecContext *s, const enum AVPixelFormat *fmt)
{
    const enum AVPixelFormat *p;
    enum AVPixelFormat sw = AV_PIX_FMT_NONE;
    (void)s;
    for (p = fmt; *p != AV_PIX_FMT_NONE; p++) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(*p);
        if (!d)
            continue;
        if (d->flags & AV_PIX_FMT_FLAG_HWACCEL)
            return *p;
        if (sw == AV_PIX_FMT_NONE)
            sw = *p;
    }
    return sw;
}

static AVCodecContext *open_codec(FileAv *p, AVStream *st, int video)
{
    const AVCodec *c = video ? find_vdec(st->codecpar->codec_id)
                             : avcodec_find_decoder(st->codecpar->codec_id);
    AVCodecContext *ctx;
#ifdef FILE_AV_WIN_HW
    const AVCodec *d3d11_codec = video ? d3d11_vdec(p, st->codecpar) : NULL;
    if (d3d11_codec)
        c = d3d11_codec;
#endif
    if (!c)
        return NULL;
    ctx = avcodec_alloc_context3(c);
    if (!ctx)
        return NULL;
    if (avcodec_parameters_to_context(ctx, st->codecpar) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    ctx->pkt_timebase = st->time_base;
    if (video) {
        enum AVCodecID id = st->codecpar->codec_id;
        int try_vt = (id == AV_CODEC_ID_H264 || id == AV_CODEC_ID_HEVC);
#ifdef FILE_AV_LINUX_HW
        if ((try_vt || id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_AV1) && mse_hwdev_usable(id)) {
            ctx->get_format = mse_hwdev_get_format;
            ctx->hw_device_ctx = av_buffer_ref(mse_hwdev_get());
            /* vout[] + vfr hold surfaces outside the decoder's fixed CUDA pool. */
            ctx->extra_hw_frames = VOUT_N + 2;
        }
        try_vt = 0;
#endif
#ifdef FILE_AV_WIN_HW
        try_vt = 0;
#endif
        if (try_vt) {
            AVBufferRef *hw = NULL;
            ctx->get_format = get_format_prefer_hw;
            if (av_hwdevice_ctx_create(&hw, AV_HWDEVICE_TYPE_VIDEOTOOLBOX, NULL, NULL, 0) == 0) {
                ctx->hw_device_ctx = hw;
                ctx->extra_hw_frames = 4;
            }
        }
#ifdef FILE_AV_WIN_HW
        /* The mse host's process-wide device. file_av_step reopens when the HWND comes or goes. */
        if (d3d11_codec) {
            AVBufferRef *dev = mse_d3d11_hwdev_ref();
            if (dev) {
                ctx->get_format = mse_d3d11_pick;
                ctx->hw_device_ctx = dev;
                ctx->extra_hw_frames = VOUT_N + 2;
            }
        }
#endif
        if ((id == AV_CODEC_ID_VP9 || id == AV_CODEC_ID_VP8) && !ctx->hw_device_ctx) {
            ctx->thread_count = 4;
            ctx->thread_type = FF_THREAD_FRAME;
        }
    }
    if (avcodec_open2(ctx, c, NULL) < 0) {
        avcodec_free_context(&ctx);
        return NULL;
    }
    if (video)
        mse_critf(MSE_LOG_INFO, "file-av open %s %dx%d vt=%d", c->name, ctx->width, ctx->height,
                  ctx->hw_device_ctx ? 1 : 0);
    return ctx;
}

static double frame_pts(AVStream *st, AVFrame *fr, double fallback)
{
    int64_t t = fr->best_effort_timestamp;
    if (t == AV_NOPTS_VALUE)
        t = fr->pts;
    if (t == AV_NOPTS_VALUE || !st)
        return fallback;
    return t * av_q2d(st->time_base);
}

static double media_time_locked(FileAv *p)
{
    double t = p->hold < 0 ? 0 : p->hold;
#ifdef FILE_AV_AQ
    /* Gold: AudioQueue samples_played, interpolated between callbacks.
       Counting only at 2048-frame (~42ms) callbacks jumped the clock past
       V_STALE and dropped every other 60fps picture. */
    if (p->playing && p->dac_started && p->out_rate > 0) {
        t = p->seek_base + (double)p->samples_played / (double)p->out_rate * p->speed;
        if (p->dac_tick_ns > 0) {
            double extra = (now_ns() - p->dac_tick_ns) / 1e9 * p->speed;
            double one = 0.012;
            if (p->dac_buf_bytes > 0) {
                int fb = p->out_ch > 0 ? p->out_ch * 2 : OUT_CH * 2;
                one = (double)p->dac_buf_bytes / (double)(p->out_rate * fb) * p->speed;
            }
            if (extra > one)
                extra = one;
            if (extra > 0)
                t += extra;
        }
        if (t < 0)
            t = 0;
        if (p->duration > 0 && t > p->duration)
            t = p->duration;
        return t;
    }
#endif
    if (p->playing && p->origin_ns > 0)
        t += (now_ns() - p->origin_ns) / 1e9 * p->speed;
    if (t < 0)
        t = 0;
    if (p->duration > 0 && t > p->duration)
        t = p->duration;
    return t;
}

/* vdec idle. Does not wait on dec_loop. */
static void drop_video_state_locked(FileAv *p)
{
    avcodec_free_context(&p->vdec);
#ifdef FILE_AV_LINUX_HW
    av_packet_free(&p->v_first);
#endif
    pq_clear(&p->vq);
    vout_clear(p);
    av_frame_unref(p->vfr);
    if (p->sws) {
        sws_freeContext(p->sws);
        p->sws = NULL;
    }
    p->sws_dw = p->sws_dh = p->sws_sw = p->sws_sh = 0;
    p->sws_fmt = -1;
    av_freep(&p->bgra);
    p->bgra_n = 0;
#ifdef __APPLE__
    mse_vt_blit_free(&p->vt_blit);
    if (p->vt_layer)
        mse_vt_flush(p->vt_layer);
#endif
    p->dec_drain = 0;
    p->last_vpts = -1;
    p->v_need_key = 1;
}

/* Close the video decoder. Audio clock / demux position stay. */
static void rest_video_locked(FileAv *p)
{
    p->dec_epoch++;
    pthread_cond_broadcast(&p->cv);
    while (p->dec_busy)
        pthread_cond_wait(&p->cv, &p->mu);
    drop_video_state_locked(p);
}

static int start_video_locked(FileAv *p)
{
    AVStream *st;
    if (p->v_st < 0 || p->vdec || !p->fmt)
        return 0;
    st = p->fmt->streams[p->v_st];
    p->vdec = open_codec(p, st, 1);
    if (!p->vdec) {
        set_err(p, "video decoder");
        return -1;
    }
    p->vw = st->codecpar->width;
    p->vh = st->codecpar->height;
    p->col_space = st->codecpar->color_space;
    p->col_range = st->codecpar->color_range;
    p->v_need_key = 1;
    p->dec_drain = 0;
    pthread_cond_broadcast(&p->cv);
    return 0;
}

static void free_codecs(FileAv *p)
{
    avcodec_free_context(&p->adec);
    avcodec_free_context(&p->sdec);
    p->s_st = -1;
    p->cap_n = 0;
    drop_video_state_locked(p);
    swr_free(&p->swr);
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    pq_clear(&p->aq);
    av_frame_free(&p->vsw);
}

static double pkt_pts_sec(const FileAv *p, const AVPacket *pkt)
{
    AVStream *st;
    int64_t t;
    if (!p->fmt || p->v_st < 0 || !pkt)
        return -1;
    st = p->fmt->streams[p->v_st];
    t = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
    if (t == AV_NOPTS_VALUE || !st)
        return -1;
    return t * av_q2d(st->time_base);
}

static double stream_pkt_pts(const FileAv *p, const AVPacket *pkt)
{
    AVStream *st;
    int64_t t;
    if (!p->fmt || !pkt || pkt->stream_index < 0 ||
        (unsigned)pkt->stream_index >= p->fmt->nb_streams)
        return -1;
    st = p->fmt->streams[pkt->stream_index];
    t = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
    if (t == AV_NOPTS_VALUE)
        return -1;
    return t * av_q2d(st->time_base);
}

static void strip_ass_codes(const char *in, char *out, int n)
{
    int o = 0, skip = 0;
    for (; in && *in && o < n - 1; in++) {
        if (*in == '{') {
            skip = 1;
            continue;
        }
        if (skip) {
            if (*in == '}')
                skip = 0;
            continue;
        }
        if (in[0] == '\\' && (in[1] == 'N' || in[1] == 'n')) {
            out[o++] = '\n';
            in++;
            continue;
        }
        out[o++] = *in;
    }
    out[o] = 0;
}

static void ass_body(const char *ass, char *out, int n)
{
    const char *p = ass;
    int commas = 0;
    out[0] = 0;
    if (!p)
        return;
    if (!strncmp(p, "Dialogue:", 9)) {
        p += 9;
        while (*p && commas < 9) {
            if (*p++ == ',')
                commas++;
        }
    }
    strip_ass_codes(p, out, n);
}

static void cap_push(FileAv *p, double start, double end, const char *text)
{
    int i;
    if (!text || !text[0])
        return;
    if (end <= start)
        end = start + 5.0;
    if (p->cap_n >= 8) {
        memmove(&p->cap_q[0], &p->cap_q[1], sizeof(p->cap_q[0]) * 7);
        p->cap_n = 7;
    }
    i = p->cap_n++;
    p->cap_q[i].id = ++p->cap_id;
    p->cap_q[i].start = start;
    p->cap_q[i].end = end;
    snprintf(p->cap_q[i].text, sizeof(p->cap_q[i].text), "%s", text);
}

static int copy_text(const uint8_t *data, int len, char *out, int n)
{
    if (!data || len <= 0 || n <= 1)
        return -1;
    if (len >= n)
        len = n - 1;
    memcpy(out, data, (size_t)len);
    out[len] = 0;
    return 0;
}

/* Bundled libav often has no subtitle decoder. 3GPP timed text is length+UTF-8. */
static int parse_mov_text(const uint8_t *data, int size, char *out, int n)
{
    int len;
    if (size < 2)
        return -1;
    len = (data[0] << 8) | data[1];
    if (len <= 0)
        return -1;
    if (len > size - 2)
        len = size - 2;
    return copy_text(data + 2, len, out, n);
}

static void decode_sub_pkt(FileAv *p, AVPacket *pkt)
{
    AVStream *st;
    enum AVCodecID id;
    double pts, dur, start, end;
    char text[1024];

    if (!p->fmt || p->s_st < 0 || !pkt || !pkt->data)
        return;
    st = p->fmt->streams[p->s_st];
    id = st->codecpar->codec_id;
    pts = stream_pkt_pts(p, pkt);
    if (pts < 0)
        pts = 0;
    dur = 0;
    if (pkt->duration > 0)
        dur = pkt->duration * av_q2d(st->time_base);
    start = pts;
    end = dur > 0 ? pts + dur : pts;
    text[0] = 0;
    if (p->sdec) {
        AVSubtitle sub;
        int got = 0;
        unsigned i;
        size_t n = 0;
        memset(&sub, 0, sizeof(sub));
        if (avcodec_decode_subtitle2(p->sdec, &sub, &got, pkt) >= 0 && got) {
            if (sub.pts != AV_NOPTS_VALUE)
                pts = sub.pts / (double)AV_TIME_BASE;
            start = pts + sub.start_display_time / 1000.0;
            end = pts + sub.end_display_time / 1000.0;
            for (i = 0; i < sub.num_rects; i++) {
                AVSubtitleRect *r = sub.rects[i];
                char piece[1024];
                piece[0] = 0;
                if (!r)
                    continue;
                if (r->type == SUBTITLE_ASS && r->ass)
                    ass_body(r->ass, piece, (int)sizeof(piece));
                else if (r->text && r->text[0])
                    snprintf(piece, sizeof(piece), "%s", r->text);
                else if (r->ass && r->ass[0])
                    ass_body(r->ass, piece, (int)sizeof(piece));
                if (!piece[0])
                    continue;
                if (n && n < sizeof(text) - 2) {
                    text[n++] = '\n';
                    text[n] = 0;
                }
                snprintf(text + n, sizeof(text) - n, "%s", piece);
                n = strlen(text);
            }
            avsubtitle_free(&sub);
        }
    }
    if (!text[0]) {
        if (id == AV_CODEC_ID_MOV_TEXT)
            parse_mov_text(pkt->data, pkt->size, text, (int)sizeof(text));
        else if (id == AV_CODEC_ID_SUBRIP || id == AV_CODEC_ID_SRT ||
                 id == AV_CODEC_ID_WEBVTT || id == AV_CODEC_ID_TEXT)
            copy_text(pkt->data, pkt->size, text, (int)sizeof(text));
        else if (id == AV_CODEC_ID_ASS || id == AV_CODEC_ID_SSA)
            ass_body((const char *)pkt->data, text, (int)sizeof(text));
    }
    cap_push(p, start, end, text);
}

static void open_sdec_locked(FileAv *p)
{
    AVStream *st;
    avcodec_free_context(&p->sdec);
    p->s_st = -1;
    p->cap_n = 0;
    if (p->subtitle_index < 0 || !p->fmt)
        return;
    if ((unsigned)p->subtitle_index >= p->fmt->nb_streams)
        return;
    st = p->fmt->streams[p->subtitle_index];
    if (!st || st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE)
        return;
    p->sdec = open_codec(p, st, 0);
    /* Decoder is optional: bundled libav often ships without mov_text. */
    p->s_st = p->subtitle_index;
}

static void emit_captions(FileAv *p)
{
    struct {
        int id;
        double start, end;
        char text[1024];
    } tmp[8];
    int n, i;
    FileAvCaptionFn cap;
    void *user;
    if (p->cap_n <= 0 || !p->on_caption)
        return;
    n = p->cap_n;
    memcpy(tmp, p->cap_q, (size_t)n * sizeof(tmp[0]));
    p->cap_n = 0;
    cap = p->on_caption;
    user = p->user;
    pthread_mutex_unlock(&p->mu);
    for (i = 0; i < n; i++)
        cap(user, tmp[i].id, tmp[i].start, tmp[i].end, tmp[i].text);
    pthread_mutex_lock(&p->mu);
}

static void collect_streams(FileAv *p)
{
    unsigned i;
    free(p->streams);
    p->streams = NULL;
    p->nstreams = 0;
    if (!p->fmt)
        return;
    p->streams = calloc(p->fmt->nb_streams, sizeof(StreamInfo));
    if (!p->streams)
        return;
    for (i = 0; i < p->fmt->nb_streams; i++) {
        AVStream *st = p->fmt->streams[i];
        AVDictionaryEntry *lang;
        StreamInfo *s = &p->streams[p->nstreams];
        const char *name = avcodec_get_name(st->codecpar->codec_id);
        if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
            s->kind = ST_AUDIO;
        else if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            if (st->disposition & AV_DISPOSITION_ATTACHED_PIC)
                continue;
            s->kind = ST_VIDEO;
            s->w = st->codecpar->width;
            s->h = st->codecpar->height;
        } else if (st->codecpar->codec_type == AVMEDIA_TYPE_SUBTITLE)
            s->kind = ST_TEXT;
        else
            continue;
        s->index = (int)i;
        lang = av_dict_get(st->metadata, "language", NULL, 0);
        if (lang && lang->value)
            snprintf(s->lang, sizeof(s->lang), "%s", lang->value);
        if (name)
            snprintf(s->codec, sizeof(s->codec), "%s", name);
        p->nstreams++;
    }
}

static int pick_audio(FileAv *p)
{
    int i, first = -1;
    for (i = 0; i < p->nstreams; i++) {
        if (p->streams[i].kind != ST_AUDIO)
            continue;
        if (first < 0)
            first = p->streams[i].index;
        if (p->audio_index >= 0 && p->streams[i].index == p->audio_index)
            return p->streams[i].index;
    }
    return first;
}

static int pick_video(FileAv *p)
{
    int i, best = -1, best_area = -1;
    for (i = 0; i < p->nstreams; i++) {
        int area;
        if (p->streams[i].kind != ST_VIDEO)
            continue;
        area = p->streams[i].w * p->streams[i].h;
        if (area > best_area) {
            best_area = area;
            best = p->streams[i].index;
        }
    }
    return best;
}

static int need_video(const FileAv *p)
{
    return p->want_video && p->vdec != NULL;
}

static int setup_decoders(FileAv *p)
{
    p->a_st = pick_audio(p);
    p->v_st = pick_video(p);
    if (p->a_st >= 0) {
        p->adec = open_codec(p, p->fmt->streams[p->a_st], 0);
        if (!p->adec) {
            set_err(p, "audio decoder");
            return -1;
        }
    }
    if (p->v_st >= 0) {
        AVStream *st = p->fmt->streams[p->v_st];
        p->vw = st->codecpar->width;
        p->vh = st->codecpar->height;
        p->col_space = st->codecpar->color_space;
        p->col_range = st->codecpar->color_range;
        if (p->want_video && start_video_locked(p) != 0)
            return -1;
    }
    if (p->a_st < 0 && p->v_st < 0) {
        set_err(p, "no audio or video stream");
        return -1;
    }
    open_sdec_locked(p);
    return 0;
}

static int fill_packets(FileAv *p)
{
    int i;
    if (!p->want_video && p->a_st < 0)
        return 0; /* video-only rest: do not race the file to EOF */
    for (i = 0; i < 24; i++) {
        AVPacket *pkt;
        if (p->aq.n - p->aq.rd > 8 && (!p->want_video || p->v_st < 0 || p->vq.n - p->vq.rd > 8))
            return 0;
        pkt = av_packet_alloc();
        if (!pkt)
            return -1;
        if (av_read_frame(p->fmt, pkt) < 0) {
            av_packet_free(&pkt);
            return 1; /* eof */
        }
        if (pkt->stream_index == p->a_st) {
            if (pq_push(&p->aq, pkt) < 0)
                av_packet_free(&pkt);
        } else if (pkt->stream_index == p->v_st && p->want_video && p->vdec) {
            if (p->v_need_key) {
                double t = pkt_pts_sec(p, pkt);
                double clock = media_time_locked(p);
                if (!(pkt->flags & AV_PKT_FLAG_KEY) || (t >= 0 && t + 0.04 < clock)) {
                    av_packet_free(&pkt);
                    continue;
                }
                p->v_need_key = 0;
            }
            if (pq_push(&p->vq, pkt) < 0)
                av_packet_free(&pkt);
        } else if (pkt->stream_index == p->s_st) {
            decode_sub_pkt(p, pkt);
            av_packet_free(&pkt);
        } else {
            av_packet_free(&pkt);
        }
    }
    return 0;
}

static int ensure_swr(FileAv *p, AVFrame *fr)
{
    AVChannelLayout out_ch;
    int in_rate = fr->sample_rate > 0 ? fr->sample_rate : OUT_RATE;
    if (p->swr)
        return 0;
    memset(&out_ch, 0, sizeof(out_ch));
    av_channel_layout_default(&out_ch, OUT_CH);
    if (swr_alloc_set_opts2(&p->swr, &out_ch, AV_SAMPLE_FMT_S16, OUT_RATE,
                            &fr->ch_layout, (enum AVSampleFormat)fr->format,
                            in_rate, 0, NULL) < 0 ||
        swr_init(p->swr) < 0) {
        av_channel_layout_uninit(&out_ch);
        swr_free(&p->swr);
        return -1;
    }
    av_channel_layout_uninit(&out_ch);
    return 0;
}

static int ensure_speed_swr(FileAv *p, double speed)
{
    AVChannelLayout ch;
    double in_rate;
    if (speed < 0.05)
        speed = 0.05;
    if (speed > 4)
        speed = 4;
    in_rate = (double)OUT_RATE * speed;
    if (p->swr_speed && p->swr_speed_in == in_rate)
        return 0;
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    if (speed > 0.99 && speed < 1.01)
        return 0;
    memset(&ch, 0, sizeof(ch));
    av_channel_layout_default(&ch, OUT_CH);
    if (swr_alloc_set_opts2(&p->swr_speed, &ch, AV_SAMPLE_FMT_S16, OUT_RATE,
                            &ch, AV_SAMPLE_FMT_S16, (int)(in_rate + 0.5),
                            0, NULL) < 0 ||
        swr_init(p->swr_speed) < 0) {
        av_channel_layout_uninit(&ch);
        swr_free(&p->swr_speed);
        return -1;
    }
    av_channel_layout_uninit(&ch);
    p->swr_speed_in = in_rate;
    return 0;
}

static void blit_size(FileAv *p, int src_w, int src_h, int *dw, int *dh)
{
    int box_w = p->blit_w, box_h = p->blit_h;
    *dw = src_w;
    *dh = src_h;
    if (src_w <= 0 || src_h <= 0)
        return;
    if (box_w <= 0 && box_h <= 0)
        return;
    if (box_w <= 0) {
        if (src_h <= box_h)
            return;
        *dh = box_h;
        *dw = src_w * box_h / src_h;
    } else if (box_h <= 0) {
        if (src_w <= box_w)
            return;
        *dw = box_w;
        *dh = src_h * box_w / src_w;
    } else if (src_w > box_w || src_h > box_h) {
        if ((int64_t)src_w * box_h > (int64_t)box_w * src_h) {
            *dw = box_w;
            *dh = src_h * box_w / src_w;
        } else {
            *dh = box_h;
            *dw = src_w * box_h / src_h;
        }
    }
    if (*dw < 2)
        *dw = 2;
    if (*dh < 2)
        *dh = 2;
}

static AVFrame *sw_video_frame(FileAv *p, AVFrame *fr)
{
    const AVPixFmtDescriptor *d;
    if (!fr)
        return NULL;
    if (!fr->hw_frames_ctx)
        return fr;
    if (!p->vsw)
        p->vsw = av_frame_alloc();
    if (!p->vsw)
        return NULL;
    av_frame_unref(p->vsw);
    if (av_hwframe_transfer_data(p->vsw, fr, 0) < 0)
        return NULL;
    p->vsw->pts = fr->pts;
    p->vsw->best_effort_timestamp = fr->best_effort_timestamp;
    p->vsw->colorspace = fr->colorspace;
    p->vsw->color_range = fr->color_range;
    p->vsw->color_primaries = fr->color_primaries;
    p->vsw->color_trc = fr->color_trc;
    d = av_pix_fmt_desc_get((enum AVPixelFormat)p->vsw->format);
    if (d && (d->flags & AV_PIX_FMT_FLAG_HWACCEL))
        return NULL;
    return p->vsw;
}

static enum AVColorSpace frame_cs(const FileAv *p, const AVFrame *fr)
{
    if (fr->colorspace != AVCOL_SPC_UNSPECIFIED)
        return fr->colorspace;
    if (p->col_space != AVCOL_SPC_UNSPECIFIED)
        return p->col_space;
    return fr->height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
}

static enum AVColorRange frame_range(const FileAv *p, const AVFrame *fr)
{
    /* Codec/container tags win: VideoToolbox often reports full-range NV12 for tv-range HEVC. */
    if (p->col_range == AVCOL_RANGE_MPEG || p->col_range == AVCOL_RANGE_JPEG)
        return p->col_range;
    if (fr->color_range != AVCOL_RANGE_UNSPECIFIED)
        return fr->color_range;
    return AVCOL_RANGE_MPEG;
}

static int sws_cs(enum AVColorSpace spc, int height)
{
    switch (spc) {
    case AVCOL_SPC_BT709:
        return SWS_CS_ITU709;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
        return SWS_CS_BT2020;
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_BT470BG:
        return SWS_CS_ITU601;
    default:
        return height > 576 ? SWS_CS_ITU709 : SWS_CS_ITU601;
    }
}

static void sws_apply_color(struct SwsContext *sws, const FileAv *p, const AVFrame *fr)
{
    int cs = sws_cs(frame_cs(p, fr), fr->height);
    int src_range = frame_range(p, fr) == AVCOL_RANGE_JPEG ? 1 : 0;
    const int *coeff = sws_getCoefficients(cs);
    /* Same matrix both sides; dst_range=1 expands tv (16-235) to full RGB like QuickTime. */
    sws_setColorspaceDetails(sws, coeff, src_range, coeff, 1, 0, 1 << 16, 1 << 16);
}

#ifdef __APPLE__
/* Prefer the overlay; CPU bitmap only if the layer cannot take BGRA. */
static void emit_frame(FileAv *p, int w, int h)
{
    if (p->vt_layer && p->bgra &&
        mse_vt_show_bgra(p->vt_layer, p->bgra, w, h) == 0) {
        if (p->on_frame)
            p->on_frame(p->user, w, h, NULL);
        return;
    }
    if (p->on_frame)
        p->on_frame(p->user, w, h, p->bgra);
}

static int present_vt(FileAv *p, AVFrame *fr)
{
    int w, h, n;
    if (!fr->data[3])
        return -1;
    if (p->vt_layer &&
        mse_vt_show(p->vt_layer, fr->data[3], fr->width, fr->height) == 0) {
        p->vw = fr->width;
        p->vh = fr->height;
        if (p->on_frame)
            p->on_frame(p->user, fr->width, fr->height, NULL);
        return 0;
    }
    if (p->vt_layer)
        return -1;
    if (mse_vt_blit_size(fr->data[3], p->blit_w, p->blit_h, &w, &h) != 0)
        return -1;
    n = w * h * 4;
    if (n > p->bgra_n) {
        uint8_t *b = av_realloc(p->bgra, (size_t)n);
        if (!b)
            return -1;
        p->bgra = b;
        p->bgra_n = n;
    }
    if (mse_vt_blit_copy(&p->vt_blit, fr->data[3], p->bgra, w, h) != 0)
        return -1;
    p->vw = w;
    p->vh = h;
    emit_frame(p, w, h);
    return 0;
}

static unsigned sw_cv_fmt(enum AVPixelFormat f)
{
    if (f == AV_PIX_FMT_YUV420P || f == AV_PIX_FMT_YUVJ420P)
        return MSE_VT_BLIT_YUV420;
    if (f == AV_PIX_FMT_NV12)
        return MSE_VT_BLIT_NV12;
    return 0;
}

static int present_sw_vt(FileAv *p, AVFrame *fr)
{
    unsigned fmt = sw_cv_fmt((enum AVPixelFormat)fr->format);
    int w, h, n;
    if (!fmt)
        return -1;
    if (p->vt_layer &&
        mse_vt_show_sw(p->vt_layer, fmt, (const uint8_t *const *)fr->data, fr->linesize,
                       fr->width, fr->height) == 0) {
        p->vw = fr->width;
        p->vh = fr->height;
        if (p->on_frame)
            p->on_frame(p->user, fr->width, fr->height, NULL);
        return 0;
    }
    if (p->vt_layer)
        return -1;
    if (mse_vt_blit_fit(fr->width, fr->height, p->blit_w, p->blit_h, &w, &h) != 0)
        return -1;
    n = w * h * 4;
    if (n > p->bgra_n) {
        uint8_t *b = av_realloc(p->bgra, (size_t)n);
        if (!b)
            return -1;
        p->bgra = b;
        p->bgra_n = n;
    }
    if (mse_vt_blit_sw(&p->vt_blit, fmt, (const uint8_t *const *)fr->data, fr->linesize,
                       fr->width, fr->height, p->bgra, w, h) != 0)
        return -1;
    p->vw = w;
    p->vh = h;
    emit_frame(p, w, h);
    return 0;
}
#endif

#ifdef FILE_AV_WIN_HW
/* Zero-copy present: hand the decoder's AV_PIX_FMT_D3D11 texture straight to the
   swapchain the JAWT bridge opened on p->d3d11_hwnd. 0 also when the frame is dropped
   because the swapchain is gone or another engine's. */
static int present_d3d11(FileAv *p, AVFrame *fr)
{
    ID3D11Texture2D *tex;
    UINT slice;
    int rc;
    if (!p->d3d11_hwnd || fr->format != AV_PIX_FMT_D3D11 || !fr->data[0])
        return -1;
    tex = (ID3D11Texture2D *)fr->data[0];
    slice = (UINT)(uintptr_t)fr->data[1];
    rc = mse_d3d11_present_show(tex, slice, fr->width, fr->height, p);
    if (rc == -2)
        return 0;
    if (rc != 0)
        return -1;
    p->vw = fr->width;
    p->vh = fr->height;
    if (p->on_frame)
        p->on_frame(p->user, fr->width, fr->height, NULL);
    return 0;
}
#endif

static int present_video(FileAv *p, AVFrame *fr)
{
    uint8_t *dst[4] = {0};
    int dst_ls[4] = {0};
    int dw, dh, n;
    const AVPixFmtDescriptor *d;
    if (!p->want_video)
        return 0;
#ifdef __APPLE__
    if ((fr->format == AV_PIX_FMT_VIDEOTOOLBOX || fr->hw_frames_ctx || fr->data[3]) &&
        present_vt(p, fr) == 0)
        return 0;
#endif
#ifdef FILE_AV_WIN_HW
    if (fr->format == AV_PIX_FMT_D3D11 && present_d3d11(p, fr) == 0)
        return 0;
#endif
    fr = sw_video_frame(p, fr);
    if (!fr || fr->width <= 0 || fr->height <= 0 || !fr->data[0])
        return -1;
#ifdef __APPLE__
    if (present_sw_vt(p, fr) == 0)
        return 0;
    if (p->vt_layer)
        return -1;
#endif
    fr->colorspace = frame_cs(p, fr);
    fr->color_range = frame_range(p, fr);
    d = av_pix_fmt_desc_get((enum AVPixelFormat)fr->format);
    if (d && (d->flags & AV_PIX_FMT_FLAG_HWACCEL))
        return -1;
    blit_size(p, fr->width, fr->height, &dw, &dh);
    if (!p->sws || p->sws_dw != dw || p->sws_dh != dh ||
        p->sws_sw != fr->width || p->sws_sh != fr->height || p->sws_fmt != fr->format) {
        int flags = SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT | SWS_FULL_CHR_H_INP;
        flags |= (dw != fr->width || dh != fr->height) ? SWS_LANCZOS : SWS_BILINEAR;
        if (p->sws)
            sws_freeContext(p->sws);
        p->sws = sws_getContext(fr->width, fr->height, (enum AVPixelFormat)fr->format,
                                dw, dh, AV_PIX_FMT_BGRA, flags, NULL, NULL, NULL);
        p->sws_dw = dw;
        p->sws_dh = dh;
        p->sws_sw = fr->width;
        p->sws_sh = fr->height;
        p->sws_fmt = fr->format;
        if (p->sws)
            sws_apply_color(p->sws, p, fr);
    }
    if (!p->sws)
        return -1;
    n = dw * dh * 4;
    if (n > p->bgra_n) {
        uint8_t *b = av_realloc(p->bgra, (size_t)n);
        if (!b)
            return -1;
        p->bgra = b;
        p->bgra_n = n;
    }
    dst[0] = p->bgra;
    dst_ls[0] = dw * 4;
    if (sws_scale(p->sws, (const uint8_t *const *)fr->data, fr->linesize, 0, fr->height, dst, dst_ls) < 0)
        return -1;
    /* Report NATIVE frame dimensions, not swscale output. The host UI uses this for the
       video surface aspect ratio; feeding it dw/dh (which itself depends on the UI's
       current box size) creates a layout feedback loop that oscillates ±1px per compose
       pass and visibly blinks the picture at ~9Hz. */
    p->vw = fr->width;
    p->vh = fr->height;
#ifdef __APPLE__
    emit_frame(p, dw, dh);
#else
    if (p->on_frame)
        p->on_frame(p->user, dw, dh, p->bgra);
#endif
    return 0;
}

#ifdef FILE_AV_AQ
static int ring_fb(const FileAv *p)
{
    return p->out_ch > 0 ? p->out_ch * 2 : OUT_CH * 2;
}

static int ring_ms(const FileAv *p)
{
    int rate = p->out_rate > 0 ? p->out_rate : OUT_RATE;
    int fb = ring_fb(p);
    if (!p->ring || rate <= 0 || fb <= 0)
        return 0;
    return (int)((int64_t)p->ring_n * 1000 / ((int64_t)rate * fb));
}

static void ring_write(FileAv *p, const uint8_t *src, int n)
{
    while (n > 0) {
        int space, chunk;
        if (p->ring_n >= p->ring_cap || p->flushing || !p->playing || !p->ring)
            return;
        space = p->ring_cap - p->ring_n;
        chunk = n < space ? n : space;
        if (p->ring_w + chunk > p->ring_cap) {
            int first = p->ring_cap - p->ring_w;
            memcpy(p->ring + p->ring_w, src, (size_t)first);
            memcpy(p->ring, src + first, (size_t)(chunk - first));
            p->ring_w = chunk - first;
        } else {
            memcpy(p->ring + p->ring_w, src, (size_t)chunk);
            p->ring_w += chunk;
        }
        p->ring_n += chunk;
        src += chunk;
        n -= chunk;
        pthread_cond_broadcast(&p->cv);
    }
}

static int ring_read(FileAv *p, uint8_t *dst, int n)
{
    int got = 0;
    int fb = ring_fb(p);
    if (fb > 1)
        n -= n % fb;
    while (got < n && p->ring_n > 0) {
        int chunk = n - got;
        if (chunk > p->ring_n)
            chunk = p->ring_n;
        if (p->ring_r + chunk > p->ring_cap) {
            int first = p->ring_cap - p->ring_r;
            memcpy(dst + got, p->ring + p->ring_r, (size_t)first);
            memcpy(dst + got + first, p->ring, (size_t)(chunk - first));
            p->ring_r = chunk - first;
        } else {
            memcpy(dst + got, p->ring + p->ring_r, (size_t)chunk);
            p->ring_r += chunk;
        }
        p->ring_n -= chunk;
        got += chunk;
    }
    return got;
}

static void aq_callback(void *user, AudioQueueRef q, AudioQueueBufferRef buf)
{
    FileAv *p = user;
    int n = 0;
    int cap;
    pthread_mutex_lock(&p->mu);
    cap = p->dac_buf_bytes > 0 ? p->dac_buf_bytes : (int)buf->mAudioDataBytesCapacity;
    if (cap > (int)buf->mAudioDataBytesCapacity)
        cap = (int)buf->mAudioDataBytesCapacity;
    if (!p->stop && !p->flushing && p->playing && p->ring)
        n = ring_read(p, (uint8_t *)buf->mAudioData, cap);
    /* Colored at the device pull so EQ is instant and the spectrum matches what is heard. */
    if (n > 0 && p->fx)
        audio_fx_color(p->fx, (int16_t *)buf->mAudioData, n / ring_fb(p), ring_fb(p) / 2);
    if (n > 0 && p->out_rate > 0) {
        p->samples_played += n / ring_fb(p);
        p->dac_tick_ns = now_ns();
    }
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
    /* Always hand the device a full-length buffer. A short write would make the DAC burn
       through buffers faster than real time (WOM_DONE every ~1ms instead of ~every 10ms)
       and the aqw_loop can't keep up — the user hears a rapid crackle while the clock
       drifts forward. Pad any shortfall with silence so each buffer plays for the full
       cap duration and WOM_DONEs stay on the device-rate cadence. */
    if (n < cap)
        memset((uint8_t *)buf->mAudioData + (n > 0 ? n : 0), 0, (size_t)(cap - (n > 0 ? n : 0)));
    buf->mAudioDataByteSize = (UInt32)cap;
    AudioQueueEnqueueBuffer(q, buf, 0, NULL);
}

static int start_aq(FileAv *p)
{
    AudioStreamBasicDescription fmt;
    AudioQueueRef aq = NULL;
    uint8_t *ring = NULL;
    int i, buf_bytes, cap, rate = OUT_RATE, ch = OUT_CH;
    if (p->dac)
        return 0;
    memset(&fmt, 0, sizeof(fmt));
    fmt.mSampleRate = rate;
    fmt.mFormatID = kAudioFormatLinearPCM;
    fmt.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked |
                       kAudioFormatFlagsNativeEndian;
    fmt.mBytesPerPacket = (UInt32)(ch * 2);
    fmt.mFramesPerPacket = 1;
    fmt.mBytesPerFrame = (UInt32)(ch * 2);
    fmt.mChannelsPerFrame = (UInt32)ch;
    fmt.mBitsPerChannel = 16;
    cap = rate * ch * 2;
    /* 2048 frames @ 48 kHz = ~43ms per buffer × AQ_NBUFS buffers = ~170ms queue depth.
       512 frames (~11ms) was too small for waveOut's scheduling window on Windows under
       UI load — buffers drained in less time than aqw_loop could refill, device went
       idle in between, audible as periodic vibration. */
    buf_bytes = 2048 * ch * 2;
    pthread_mutex_unlock(&p->mu);
    if (AudioQueueNewOutput(&fmt, aq_callback, p, NULL, kCFRunLoopCommonModes, 0, &aq) != 0) {
        pthread_mutex_lock(&p->mu);
        return -1;
    }
    ring = calloc(1, (size_t)cap);
    if (!ring) {
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&p->mu);
        return -1;
    }
    pthread_mutex_lock(&p->mu);
    if (p->dac || p->stop) {
        pthread_mutex_unlock(&p->mu);
        free(ring);
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&p->mu);
        return 0;
    }
    p->dac = aq;
    p->out_rate = rate;
    p->out_ch = ch;
    p->ring = ring;
    p->ring_cap = cap;
    p->ring_r = p->ring_w = p->ring_n = 0;
    p->dac_buf_bytes = buf_bytes;
    AudioQueueSetParameter(aq, kAudioQueueParam_Volume, p->volume);
    pthread_mutex_unlock(&p->mu);
    for (i = 0; i < AQ_NBUFS; i++) {
        AudioQueueBufferRef buf;
        if (AudioQueueAllocateBuffer(aq, (UInt32)buf_bytes, &buf) != 0)
            break;
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
        buf->mAudioDataByteSize = (UInt32)buf_bytes;
        AudioQueueEnqueueBuffer(aq, buf, 0, NULL);
    }
    pthread_mutex_lock(&p->mu);
    return 0;
}

static void aq_pause_flush(FileAv *p)
{
    AudioQueueRef aq;
    pthread_mutex_lock(&p->mu);
    p->flushing = 1;
    p->dac_started = 0;
    p->ring_r = p->ring_w = p->ring_n = 0;
    aq = p->dac;
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
    if (aq) {
        AudioQueuePause(aq);
        AudioQueueFlush(aq);
    }
    pthread_mutex_lock(&p->mu);
    p->flushing = 0;
    pthread_mutex_unlock(&p->mu);
}

static void stop_aq(FileAv *p)
{
    AudioQueueRef aq;
    uint8_t *ring;
    pthread_mutex_lock(&p->mu);
    aq = p->dac;
    ring = p->ring;
    p->dac = NULL;
    p->ring = NULL;
    p->dac_started = 0;
    p->ring_cap = p->ring_r = p->ring_w = p->ring_n = 0;
    pthread_mutex_unlock(&p->mu);
    if (aq) {
        AudioQueueStop(aq, true);
        AudioQueueDispose(aq, true);
    }
    free(ring);
}

/* Decode one AAC frame to 48k s16. mu held on entry/exit. 1 = *out set. */
static int decode_pcm(FileAv *p, uint8_t **out, int *nbytes)
{
    AVPacket *pkt;
    int got, max_out;
    uint8_t *tmp = NULL, *sped = NULL;
    uint8_t *dsts[1];
    *out = NULL;
    *nbytes = 0;
    for (;;) {
        got = avcodec_receive_frame(p->adec, p->afr);
        if (got == AVERROR(EAGAIN)) {
            pkt = pq_pop(&p->aq);
            if (!pkt)
                return 0;
            if (avcodec_send_packet(p->adec, pkt) < 0) {
                av_packet_free(&pkt);
                return 0;
            }
            av_packet_free(&pkt);
            continue;
        }
        if (got < 0)
            return 0;
        if (ensure_swr(p, p->afr) < 0) {
            av_frame_unref(p->afr);
            return -1;
        }
        max_out = (int)swr_get_out_samples(p->swr, p->afr->nb_samples) + 256;
        if (max_out < 1)
            max_out = p->afr->nb_samples + 256;
        tmp = grow_bytes(&p->pcm, &p->pcm_n, (size_t)max_out * OUT_CH * 2);
        if (!tmp) {
            av_frame_unref(p->afr);
            return -1;
        }
        dsts[0] = tmp;
        got = swr_convert(p->swr, dsts, max_out,
                          (const uint8_t **)p->afr->extended_data, p->afr->nb_samples);
        av_frame_unref(p->afr);
        if (got <= 0)
            return 0;
        if (p->fx) {
            int cap = got * 4 + 1024;
            int16_t *stretched = (int16_t *)grow_bytes(&p->pcm2, &p->pcm2_n,
                                                      (size_t)cap * OUT_CH * sizeof(int16_t));
            int nout;
            audio_fx_set_rate(p->fx, OUT_RATE);
            audio_fx_set_speed(p->fx, (float)p->speed);
            if (stretched) {
                nout = audio_fx_stretch(p->fx, (int16_t *)tmp, got, stretched, cap, OUT_CH);
                tmp = (uint8_t *)stretched;
                got = nout;
            }
        } else if (p->swr_speed) {
            int mout = (int)swr_get_out_samples(p->swr_speed, got) + 256;
            uint8_t *ins[1] = {tmp};
            if (mout < 1)
                mout = got + 256;
            sped = grow_bytes(&p->pcm2, &p->pcm2_n, (size_t)mout * OUT_CH * 2);
            if (!sped)
                return -1;
            dsts[0] = sped;
            got = swr_convert(p->swr_speed, dsts, mout, (const uint8_t **)ins, got);
            tmp = sped;
            if (got <= 0)
                return 0;
        }
        *nbytes = got * OUT_CH * 2;
        *out = tmp;
        return 1;
    }
}

static void *a_loop(void *arg)
{
    FileAv *p = arg;
    for (;;) {
        uint8_t *pcm = NULL;
        int nbytes = 0, filled = 0;
        AudioQueueRef start_q = NULL;
        pthread_mutex_lock(&p->mu);
        p->a_busy = 0;
        pthread_cond_broadcast(&p->cv);
        while (!p->stop && !(p->opened && p->playing && p->adec))
            pthread_cond_wait(&p->cv, &p->mu);
        if (p->stop) {
            pthread_mutex_unlock(&p->mu);
            return NULL;
        }
        p->a_busy = 1;
        while (filled < 8 && ring_ms(p) < 400) {
            int r;
            if (!p->opened || !p->playing || !p->adec || p->flushing)
                break;
            r = decode_pcm(p, &pcm, &nbytes);
            if (r <= 0 || !pcm)
                break;
            if (!p->dac && start_aq(p) != 0) {
                pcm = NULL;
                if (p->origin_ns == 0)
                    p->origin_ns = now_ns();
                break;
            }
            if (!p->flushing)
                ring_write(p, pcm, nbytes);
            pcm = NULL;
            filled++;
            if (p->dac && !p->dac_started && ring_ms(p) >= 160) {
                p->dac_started = 1;
                p->samples_played = 0;
                p->dac_tick_ns = now_ns();
                start_q = p->dac;
            }
        }
        p->a_busy = 0;
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
        if (start_q)
            AudioQueueStart(start_q, NULL);
        if (filled == 0) {
            struct timespec sl = {0, 4 * 1000 * 1000};
            nanosleep(&sl, NULL);
        }
    }
}
#else
static int pump_audio(FileAv *p, double clock)
{
    AVPacket *pkt;
    int got, max_out;
    uint8_t *tmp = NULL, *sped = NULL;
    uint8_t *dsts[1];
    int nbytes;
    if (!p->adec)
        return 0;
    for (;;) {
        got = avcodec_receive_frame(p->adec, p->afr);
        if (got == AVERROR(EAGAIN)) {
            pkt = pq_pop(&p->aq);
            if (!pkt)
                return 0;
            if (avcodec_send_packet(p->adec, pkt) < 0) {
                av_packet_free(&pkt);
                return 0;
            }
            av_packet_free(&pkt);
            continue;
        }
        if (got < 0)
            return 0;
        if (ensure_swr(p, p->afr) < 0) {
            av_frame_unref(p->afr);
            return -1;
        }
        max_out = (int)swr_get_out_samples(p->swr, p->afr->nb_samples) + 256;
        if (max_out < 1)
            max_out = p->afr->nb_samples + 256;
        tmp = grow_bytes(&p->pcm, &p->pcm_n, (size_t)max_out * OUT_CH * 2);
        if (!tmp) {
            av_frame_unref(p->afr);
            return -1;
        }
        dsts[0] = tmp;
        got = swr_convert(p->swr, dsts, max_out,
                          (const uint8_t **)p->afr->extended_data, p->afr->nb_samples);
        av_frame_unref(p->afr);
        if (got <= 0)
            return 0;
        if (p->fx) {
            int cap = got * 4 + 1024;
            int16_t *stretched = (int16_t *)grow_bytes(&p->pcm2, &p->pcm2_n,
                                                      (size_t)cap * OUT_CH * sizeof(int16_t));
            int nout;
            audio_fx_set_rate(p->fx, OUT_RATE);
            audio_fx_set_speed(p->fx, (float)p->speed);
            if (stretched) {
                nout = audio_fx_stretch(p->fx, (int16_t *)tmp, got, stretched, cap, OUT_CH);
                tmp = (uint8_t *)stretched;
                got = nout;
            }
            if (got > 0)
                audio_fx_color(p->fx, (int16_t *)tmp, got, OUT_CH);
        } else if (ensure_speed_swr(p, p->speed) == 0 && p->swr_speed) {
            int mout = (int)swr_get_out_samples(p->swr_speed, got) + 256;
            uint8_t *ins[1] = {tmp};
            if (mout < 1)
                mout = got + 256;
            sped = grow_bytes(&p->pcm2, &p->pcm2_n, (size_t)mout * OUT_CH * 2);
            if (!sped)
                return -1;
            dsts[0] = sped;
            got = swr_convert(p->swr_speed, dsts, mout, (const uint8_t **)ins, got);
            tmp = sped;
            if (got <= 0)
                return 0;
        }
        nbytes = got * OUT_CH * 2;
        pthread_mutex_unlock(&p->mu);
        if (p->on_pcm)
            p->on_pcm(p->user, OUT_RATE, OUT_CH, (const int16_t *)tmp, nbytes);
        pthread_mutex_lock(&p->mu);
        (void)clock;
        if (!p->opened || !p->adec)
            return 0;
        return 0;
    }
}
#endif

static void set_idle(FileAv *p, double sec)
{
    int64_t ns;
    if (sec < 0)
        sec = 0;
    ns = (int64_t)(sec * 1e9);
    if (ns > IDLE_NS_MAX)
        ns = IDLE_NS_MAX;
    p->idle_ns = ns;
}

static int hole_ready(const FileAv *p)
{
#ifdef __APPLE__
    if (p->vt_layer)
        return 1;
#endif
    return p->blit_w > 0 || p->blit_h > 0;
}

static int present_head(FileAv *p)
{
    AVFrame *fr = vout_head(p);
    AVStream *st = (p->fmt && p->v_st >= 0) ? p->fmt->streams[p->v_st] : NULL;
    double pts;
    if (!fr)
        return 0;
    pts = frame_pts(st, fr, 0);
    p->idle_ns = 0;
    p->last_vpts = pts;
    pthread_mutex_unlock(&p->mu);
    present_video(p, fr);
    pthread_mutex_lock(&p->mu);
    if (p->playing && p->origin_ns == 0)
        p->origin_ns = now_ns();
    vout_pop(p);
    return 0;
}

/* 1x clock. Show the due head; drop at most two already-stale pictures. */
static int pump_video(FileAv *p, double clock)
{
    AVStream *st = (p->fmt && p->v_st >= 0) ? p->fmt->streams[p->v_st] : NULL;
    AVFrame *fr;
    double pts;
    int drops;
    if (!p->vdec || !p->want_video)
        return 0;
    if (!hole_ready(p)) {
        set_idle(p, 0.004);
        return 0;
    }
    clock = media_time_locked(p);
    drops = 0;
    while (drops < MAX_DROP && p->vout_n >= 2) {
        pts = frame_pts(st, vout_head(p), clock);
        if (pts + V_STALE >= clock)
            break;
        if (p->logged_drop < 8) {
            p->logged_drop++;
            mse_critf(MSE_LOG_INFO, "file-av drop-stale pts=%.3f clock=%.3f", pts, clock);
        }
        vout_pop(p);
        drops++;
    }
    fr = vout_head(p);
    if (!fr) {
        set_idle(p, 0.004);
        return 0;
    }
    pts = frame_pts(st, fr, clock);
    if (p->origin_ns == 0)
        return present_head(p);
    if (pts > clock + V_EARLY) {
        set_idle(p, pts - clock - V_EARLY);
        return 0;
    }
    return present_head(p);
}

#ifdef FILE_AV_LINUX_HW
/* The GPU turned the codec down (native av1 has no software path): swap in a software decoder and
   replay the packet it started from, so the queued GOP stays decodable. Caller holds mu, dec_busy set. */
static void reopen_if_hw_rejected_locked(FileAv *p)
{
    AVStream *st;
    if (!p->vdec || !p->vdec->hw_device_ctx || mse_hwdev_usable(p->vdec->codec_id) || !p->fmt)
        return;
    mse_critf(MSE_LOG_INFO, "file-av reopen %s in software", avcodec_get_name(p->vdec->codec_id));
    avcodec_free_context(&p->vdec);
    st = p->fmt->streams[p->v_st];
    p->vdec = open_codec(p, st, 1);
    if (!p->vdec) {
        av_packet_free(&p->v_first);
        set_err(p, "video decoder");
        return;
    }
    p->dec_drain = 0;
    if (p->v_first) {
        AVPacket *first = p->v_first;
        AVCodecContext *dec = p->vdec;
        pthread_mutex_unlock(&p->mu);
        avcodec_send_packet(dec, first);
        pthread_mutex_lock(&p->mu);
    }
}
#endif

static void *dec_loop(void *arg)
{
    FileAv *p = arg;
    for (;;) {
        AVPacket *pkt;
        int got, epoch;
        pthread_mutex_lock(&p->mu);
        for (;;) {
            if (p->stop) {
                pthread_mutex_unlock(&p->mu);
                return NULL;
            }
            if (p->opened && p->playing && p->vdec && p->want_video && p->vout_n < VOUT_N)
                break;
            pthread_cond_wait(&p->cv, &p->mu);
        }
        epoch = p->dec_epoch;
        p->dec_busy = 1;
        pthread_mutex_unlock(&p->mu);

        got = avcodec_receive_frame(p->vdec, p->vfr);
        pthread_mutex_lock(&p->mu);
        if (p->stop || !p->opened || !p->vdec || epoch != p->dec_epoch) {
            av_frame_unref(p->vfr);
            p->dec_busy = 0;
            pthread_cond_broadcast(&p->cv);
            pthread_mutex_unlock(&p->mu);
            continue;
        }
        if (got == AVERROR(EAGAIN)) {
            pkt = pq_pop(&p->vq);
            if (!pkt && p->fmt_eof && !p->dec_drain) {
                p->dec_drain = 1;
                pthread_mutex_unlock(&p->mu);
                avcodec_send_packet(p->vdec, NULL);
                pthread_mutex_lock(&p->mu);
                p->dec_busy = 0;
                pthread_cond_broadcast(&p->cv);
                pthread_mutex_unlock(&p->mu);
                continue;
            }
            if (!pkt) {
                p->dec_busy = 0;
                pthread_cond_broadcast(&p->cv);
                pthread_cond_wait(&p->cv, &p->mu);
                pthread_mutex_unlock(&p->mu);
                continue;
            }
#ifdef FILE_AV_LINUX_HW
            if (!p->v_first && p->vdec->hw_device_ctx)
                p->v_first = av_packet_clone(pkt);
#endif
            pthread_mutex_unlock(&p->mu);
            avcodec_send_packet(p->vdec, pkt);
            av_packet_free(&pkt);
            pthread_mutex_lock(&p->mu);
#ifdef FILE_AV_LINUX_HW
            reopen_if_hw_rejected_locked(p);
#endif
            p->dec_busy = 0;
            pthread_cond_broadcast(&p->cv);
            pthread_mutex_unlock(&p->mu);
            continue;
        }
        if (got < 0) {
            av_frame_unref(p->vfr);
#ifdef FILE_AV_LINUX_HW
            reopen_if_hw_rejected_locked(p);
#endif
            p->dec_busy = 0;
            pthread_cond_broadcast(&p->cv);
            pthread_mutex_unlock(&p->mu);
            continue;
        }
#ifdef FILE_AV_LINUX_HW
        av_packet_free(&p->v_first);
#endif
        p->dec_busy = 0;
        pthread_cond_broadcast(&p->cv);
        while (!p->stop && p->opened && epoch == p->dec_epoch && p->vout_n >= VOUT_N)
            pthread_cond_wait(&p->cv, &p->mu);
        if (!p->stop && p->opened && epoch == p->dec_epoch)
            vout_push(p, p->vfr);
        av_frame_unref(p->vfr);
        pthread_cond_broadcast(&p->cv);
        pthread_mutex_unlock(&p->mu);
    }
}

int file_av_step(FileAv *p)
{
    double clock;
    int eof, a_empty, v_empty;
    int64_t idle_ns;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    if (!p->opened || !p->playing) {
        pthread_mutex_unlock(&p->mu);
        return 0;
    }
#ifdef FILE_AV_WIN_HW
    /* Once the demuxer hit EOF the queued packets are all the video left: a new decoder would
       get none and the ended check would fire early. The flag waits for a seek to clear fmt_eof. */
    if (p->vdec && !p->fmt_eof &&
        (p->reopen_video || (p->vdec->hw_device_ctx && mse_d3d11_check_lost()))) {
        int hw = d3d11_vdec(p, p->fmt->streams[p->v_st]->codecpar) != NULL;
        p->reopen_video = 0;
        if (hw != (p->vdec->hw_device_ctx != NULL)) {
            mse_critf(MSE_LOG_INFO, "file-av video decoder reopens %s",
                      hw ? "on d3d11va" : "in software");
            rest_video_locked(p);
            start_video_locked(p);
        }
    }
#endif
    p->idle_ns = 4 * 1000 * 1000;
    if (p->origin_ns == 0 && !p->adec && !p->want_video)
        p->origin_ns = now_ns();
    eof = fill_packets(p);
    if (eof == 1)
        p->fmt_eof = 1;
    pthread_cond_broadcast(&p->cv);
    clock = media_time_locked(p);
    if (need_video(p))
        pump_video(p, clock);
#ifndef FILE_AV_AQ
    if (p->adec)
        pump_audio(p, media_time_locked(p));
#endif
    clock = media_time_locked(p);
    if (p->on_prog && now_ns() - p->last_prog_ns > 1e8) {
        FileAvProgressFn prog = p->on_prog;
        void *user = p->user;
        double dur = p->duration;
        int vw = p->vw, vh = p->vh;
        p->last_prog_ns = now_ns();
        pthread_mutex_unlock(&p->mu);
        prog(user, clock, dur, vw, vh);
        pthread_mutex_lock(&p->mu);
    }
    emit_captions(p);
#ifdef FILE_AV_AQ
    a_empty = !p->adec || ((p->aq.rd >= p->aq.n) && p->ring_n == 0 && !p->a_busy);
#else
    a_empty = !p->adec || (p->aq.rd >= p->aq.n);
#endif
    v_empty = !need_video(p) || ((p->vq.rd >= p->vq.n) && p->vout_n == 0 && !p->dec_busy);
    if (eof && a_empty && v_empty && !p->ended && (p->adec || need_video(p))) {
        p->ended = 1;
        p->playing = 0;
        p->hold = p->duration > 0 ? p->duration : clock;
        pthread_mutex_unlock(&p->mu);
        if (p->on_event)
            p->on_event(p->user, 1, NULL);
        return 0;
    }
    idle_ns = p->idle_ns;
    pthread_mutex_unlock(&p->mu);
    if (idle_ns > 0) {
        struct timespec sl = {0, idle_ns};
        nanosleep(&sl, NULL);
    }
    return 1;
}

FileAv *file_av_create(void)
{
    FileAv *p = calloc(1, sizeof(*p));
    if (!p)
        return NULL;
    pthread_mutex_init(&p->mu, NULL);
    pthread_cond_init(&p->cv, NULL);
    p->speed = 1.0;
    p->fx = audio_fx_create();
    p->want_video = 1;
    p->v_need_key = 0;
    p->audio_index = -1;
    p->last_vpts = -1;
    p->blit_w = 0;
    p->blit_h = 0;
    p->col_space = AVCOL_SPC_UNSPECIFIED;
    p->col_range = AVCOL_RANGE_UNSPECIFIED;
    p->a_st = p->v_st = p->s_st = -1;
    p->subtitle_index = -1;
    p->afr = av_frame_alloc();
    p->vfr = av_frame_alloc();
#ifdef FILE_AV_AQ
    p->volume = 1.f;
    p->out_rate = OUT_RATE;
    p->out_ch = OUT_CH;
#endif
    if (!p->afr || !p->vfr) {
        file_av_destroy(p);
        return NULL;
    }
    if (pthread_create(&p->th, NULL, dec_loop, p) != 0) {
        file_av_destroy(p);
        return NULL;
    }
    p->th_on = 1;
#ifdef FILE_AV_AQ
    if (pthread_create(&p->ath, NULL, a_loop, p) != 0) {
        file_av_destroy(p);
        return NULL;
    }
    p->ath_on = 1;
#endif
    return p;
}

void file_av_destroy(FileAv *p)
{
    int i;
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->stop = 1;
    p->playing = 0;
    p->opened = 0;
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
    if (p->th_on) {
        pthread_join(p->th, NULL);
        p->th_on = 0;
    }
#ifdef FILE_AV_AQ
    if (p->ath_on) {
        pthread_join(p->ath, NULL);
        p->ath_on = 0;
    }
    stop_aq(p);
#endif
    file_av_close(p);
#ifdef __APPLE__
    if (p->vt_layer) {
        mse_vt_detach(p->vt_layer);
        p->vt_layer = NULL;
    }
#endif
    av_frame_free(&p->afr);
    av_frame_free(&p->vfr);
    av_frame_free(&p->vsw);
    free(p->pcm);
    free(p->pcm2);
    for (i = 0; i < VOUT_N; i++)
        av_frame_free(&p->vout[i]);
    audio_fx_destroy(p->fx);
    pthread_cond_destroy(&p->cv);
    pthread_mutex_destroy(&p->mu);
    free(p);
}

int file_av_open(FileAv *p, const char *path)
{
    int r;
    if (!p || !path)
        return -1;
    file_av_close(p);
    pthread_mutex_lock(&p->mu);
    p->err[0] = 0;
    p->ended = 0;
    p->hold = 0;
    p->origin_ns = 0;
#ifdef FILE_AV_AQ
    p->seek_base = 0;
    p->samples_played = 0;
    p->dac_started = 0;
    p->dac_tick_ns = 0;
#endif
    p->last_vpts = -1;
    p->logged_drop = 0;
    p->fmt_eof = 0;
    p->dec_drain = 0;
    p->fmt = NULL;
    r = avformat_open_input(&p->fmt, path, NULL, NULL);
    if (r < 0) {
        char buf[128];
        av_strerror(r, buf, sizeof(buf));
        set_err(p, buf);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    if (avformat_find_stream_info(p->fmt, NULL) < 0) {
        set_err(p, "stream info");
        avformat_close_input(&p->fmt);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    if (p->fmt->duration > 0)
        p->duration = p->fmt->duration / (double)AV_TIME_BASE;
    else
        p->duration = 0;
    collect_streams(p);
    if (setup_decoders(p) < 0) {
        free_codecs(p);
        avformat_close_input(&p->fmt);
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    p->opened = 1;
    p->ended = 0;
    pthread_mutex_unlock(&p->mu);
    return 0;
}

void file_av_close(FileAv *p)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->opened = 0;
    p->playing = 0;
    pthread_cond_broadcast(&p->cv);
    while (p->dec_busy)
        pthread_cond_wait(&p->cv, &p->mu);
#ifdef FILE_AV_AQ
    while (p->a_busy)
        pthread_cond_wait(&p->cv, &p->mu);
    pthread_mutex_unlock(&p->mu);
    stop_aq(p);
    pthread_mutex_lock(&p->mu);
    p->samples_played = 0;
    p->seek_base = 0;
#endif
    p->fmt_eof = 0;
    p->dec_drain = 0;
#ifdef FILE_AV_WIN_HW
    p->reopen_video = 0;
#endif
    free_codecs(p);
    if (p->fmt)
        avformat_close_input(&p->fmt);
    free(p->streams);
    p->streams = NULL;
    p->nstreams = 0;
    p->a_st = p->v_st = p->s_st = -1;
    p->vw = p->vh = 0;
#ifdef __APPLE__
    if (p->vt_layer)
        mse_vt_flush(p->vt_layer);
#endif
    pthread_mutex_unlock(&p->mu);
}

void file_av_play(FileAv *p)
{
#ifdef FILE_AV_AQ
    AudioQueueRef aq = NULL;
    int started = 0;
#endif
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->opened && !p->playing) {
        p->playing = 1;
        p->ended = 0;
        /* Video-only: clock starts on first present. Audio: AudioQueue samples_played. */
        p->origin_ns = 0;
#ifdef FILE_AV_AQ
        if (!p->dac_started)
            p->seek_base = p->hold;
        else
            p->dac_tick_ns = now_ns();
        aq = p->dac;
        started = p->dac_started;
#endif
        pthread_cond_broadcast(&p->cv);
    }
    pthread_mutex_unlock(&p->mu);
#ifdef FILE_AV_AQ
    if (aq && started)
        AudioQueueStart(aq, NULL);
#endif
}

void file_av_pause(FileAv *p)
{
#ifdef FILE_AV_AQ
    AudioQueueRef aq = NULL;
#endif
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->playing) {
        p->hold = media_time_locked(p);
        p->playing = 0;
        p->origin_ns = 0;
#ifdef FILE_AV_AQ
        p->dac_tick_ns = 0;
        aq = p->dac;
#endif
        pthread_cond_broadcast(&p->cv);
    }
    pthread_mutex_unlock(&p->mu);
#ifdef FILE_AV_AQ
    if (aq)
        AudioQueuePause(aq);
#endif
}

void file_av_seek(FileAv *p, double seconds)
{
    int64_t ts;
    if (!p)
        return;
#ifdef FILE_AV_AQ
    aq_pause_flush(p);
#endif
    pthread_mutex_lock(&p->mu);
    if (!p->fmt) {
        pthread_mutex_unlock(&p->mu);
        return;
    }
    if (seconds < 0)
        seconds = 0;
    if (p->duration > 0 && seconds > p->duration)
        seconds = p->duration;
    ts = (int64_t)(seconds * AV_TIME_BASE);
    p->fmt_eof = 0;
    p->dec_drain = 0;
    p->dec_epoch++;
    pthread_cond_broadcast(&p->cv);
    while (p->dec_busy)
        pthread_cond_wait(&p->cv, &p->mu);
#ifdef FILE_AV_AQ
    while (p->a_busy)
        pthread_cond_wait(&p->cv, &p->mu);
#endif
    av_seek_frame(p->fmt, -1, ts, AVSEEK_FLAG_BACKWARD);
    if (p->adec)
        avcodec_flush_buffers(p->adec);
    if (p->vdec)
        avcodec_flush_buffers(p->vdec);
    if (p->sdec)
        avcodec_flush_buffers(p->sdec);
    p->cap_n = 0;
    pq_clear(&p->aq);
    pq_clear(&p->vq);
    av_frame_unref(p->afr);
    av_frame_unref(p->vfr);
    vout_clear(p);
    p->hold = seconds;
    p->ended = 0;
    p->last_vpts = -1;
    p->origin_ns = 0;
#ifdef FILE_AV_AQ
    p->seek_base = seconds;
    p->samples_played = 0;
    p->dac_started = 0;
    p->dac_tick_ns = 0;
    p->ring_r = p->ring_w = p->ring_n = 0;
#endif
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_speed(FileAv *p, double speed)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->playing)
        p->hold = media_time_locked(p);
    p->speed = speed > 0.05 ? speed : 0.05;
    if (p->fx)
        audio_fx_set_speed(p->fx, (float)p->speed);
#ifdef FILE_AV_AQ
    p->seek_base = p->hold;
    p->samples_played = 0;
    p->origin_ns = 0;
    p->dac_tick_ns = p->playing ? now_ns() : 0;
    p->ring_r = p->ring_w = p->ring_n = 0;
#else
    if (p->playing)
        p->origin_ns = now_ns();
#endif
    swr_free(&p->swr_speed);
    p->swr_speed_in = 0;
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_audio_index(FileAv *p, int stream_index)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->audio_index = stream_index;
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_subtitle_index(FileAv *p, int stream_index)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->subtitle_index = stream_index;
    if (p->opened)
        open_sdec_locked(p);
    pthread_mutex_unlock(&p->mu);
}

int file_av_dump_captions(FileAv *p, int stream_index, FileAvCaptionFn on_cue, void *user)
{
    AVFormatContext *fmt = NULL;
    AVPacket *pkt;
    AVStream *st;
    const char *url;
    unsigned i;
    int n = 0;

    if (!p || !on_cue || stream_index < 0)
        return 0;
    pthread_mutex_lock(&p->mu);
    url = (p->fmt && p->fmt->url) ? p->fmt->url : NULL;
    pthread_mutex_unlock(&p->mu);
    if (!url || avformat_open_input(&fmt, url, NULL, NULL) < 0)
        return 0;
    if (avformat_find_stream_info(fmt, NULL) < 0) {
        avformat_close_input(&fmt);
        return 0;
    }
    if ((unsigned)stream_index >= fmt->nb_streams) {
        avformat_close_input(&fmt);
        return 0;
    }
    st = fmt->streams[stream_index];
    if (st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
        avformat_close_input(&fmt);
        return 0;
    }
    for (i = 0; i < fmt->nb_streams; i++)
        fmt->streams[i]->discard = i == (unsigned)stream_index ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
    av_seek_frame(fmt, stream_index, 0, AVSEEK_FLAG_BACKWARD);
    pkt = av_packet_alloc();
    if (!pkt) {
        avformat_close_input(&fmt);
        return 0;
    }
    while (av_read_frame(fmt, pkt) >= 0) {
        char text[1024];
        double pts, dur, start, end;
        if (pkt->stream_index != stream_index) {
            av_packet_unref(pkt);
            continue;
        }
        text[0] = 0;
        if (st->codecpar->codec_id == AV_CODEC_ID_MOV_TEXT)
            parse_mov_text(pkt->data, pkt->size, text, (int)sizeof(text));
        else if (st->codecpar->codec_id == AV_CODEC_ID_SUBRIP ||
                 st->codecpar->codec_id == AV_CODEC_ID_SRT ||
                 st->codecpar->codec_id == AV_CODEC_ID_WEBVTT ||
                 st->codecpar->codec_id == AV_CODEC_ID_TEXT)
            copy_text(pkt->data, pkt->size, text, (int)sizeof(text));
        else if (st->codecpar->codec_id == AV_CODEC_ID_ASS ||
                 st->codecpar->codec_id == AV_CODEC_ID_SSA)
            ass_body((const char *)pkt->data, text, (int)sizeof(text));
        if (text[0]) {
            int64_t t = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
            pts = t != AV_NOPTS_VALUE ? t * av_q2d(st->time_base) : 0;
            dur = pkt->duration > 0 ? pkt->duration * av_q2d(st->time_base) : 5.0;
            start = pts;
            end = pts + (dur > 0 ? dur : 5.0);
            on_cue(user, n + 1, start, end, text);
            n++;
        }
        av_packet_unref(pkt);
        if (n >= 8000)
            break;
    }
    av_packet_free(&pkt);
    avformat_close_input(&fmt);
    return n;
}

void file_av_set_video_enabled(FileAv *p, int on)
{
    int want = on ? 1 : 0;
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if (p->want_video == want) {
        pthread_mutex_unlock(&p->mu);
        return;
    }
    p->want_video = want;
    if (!want)
        rest_video_locked(p);
    else if (p->opened)
        start_video_locked(p);
    pthread_cond_broadcast(&p->cv);
    pthread_mutex_unlock(&p->mu);
}

void file_av_set_blit_max(FileAv *p, int max_height)
{
    file_av_set_blit_size(p, 0, max_height);
}

void file_av_set_blit_size(FileAv *p, int w, int h)
{
    int nw, nh;
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    nw = w > 0 ? w : 0;
    nh = h > 0 ? h : 0;
    p->blit_w = nw;
    p->blit_h = nh;
    pthread_mutex_unlock(&p->mu);
    mse_critf(MSE_LOG_INFO, "file-av blit-size %dx%d", nw, nh);
}

#ifdef __APPLE__
void file_av_set_video_layer(FileAv *p, void *layer)
{
    void *old;
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    old = p->vt_layer;
    p->vt_layer = layer;
    pthread_mutex_unlock(&p->mu);
    if (old && old != layer)
        mse_vt_detach(old);
}

#endif

#ifdef FILE_AV_WIN_HW
void file_av_set_video_hwnd(FileAv *p, void *hwnd)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    if ((p->d3d11_hwnd == NULL) != (hwnd == NULL))
        p->reopen_video = 1;
    p->d3d11_hwnd = hwnd;
    pthread_mutex_unlock(&p->mu);
}
#endif

void file_av_set_volume(FileAv *p, double vol)
{
#ifdef FILE_AV_AQ
    AudioQueueRef aq;
#endif
    if (!p)
        return;
    if (vol < 0)
        vol = 0;
    if (vol > 1)
        vol = 1;
    pthread_mutex_lock(&p->mu);
#ifdef FILE_AV_AQ
    p->volume = (float)vol;
    aq = p->dac;
#endif
    if (p->fx)
        audio_fx_set_volume(p->fx, (float)vol);
    pthread_mutex_unlock(&p->mu);
#ifdef FILE_AV_AQ
    if (aq)
        AudioQueueSetParameter(aq, kAudioQueueParam_Volume, (Float32)vol);
#endif
}

void file_av_set_pitch(FileAv *p, double pitch)
{
    if (p && p->fx)
        audio_fx_set_pitch(p->fx, (float)pitch);
}

void file_av_set_eq(FileAv *p, const float bands_db[10])
{
    if (p && p->fx)
        audio_fx_set_eq(p->fx, bands_db);
}

void file_av_set_preamp(FileAv *p, float db)
{
    if (p && p->fx)
        audio_fx_set_preamp(p->fx, db);
}

void file_av_set_bass(FileAv *p, float db)
{
    if (p && p->fx)
        audio_fx_set_bass(p->fx, db);
}

/* Frames colored at the pull but still queued ahead of the AudioQueue playhead. */
static int vis_delay_frames(FileAv *p)
{
    int delay = 0;
#ifdef FILE_AV_AQ
    pthread_mutex_lock(&p->mu);
    if (p->dac && p->out_rate > 0 && p->dac_buf_bytes > 0) {
        int one = p->dac_buf_bytes / ring_fb(p);
        int extra = 0;
        if (p->dac_tick_ns > 0)
            extra = (int)((now_ns() - p->dac_tick_ns) / 1e9 * p->out_rate);
        if (extra > one)
            extra = one;
        delay = one * AQ_NBUFS - (extra > 0 ? extra : 0);
    }
    pthread_mutex_unlock(&p->mu);
#endif
    return delay;
}

int file_av_pcm_levels(FileAv *p, float *out, int n)
{
    if (!p || !p->fx)
        return 0;
    return audio_fx_levels(p->fx, out, n, vis_delay_frames(p));
}

void file_av_set_virtualizer(FileAv *p, float width)
{
    if (p && p->fx)
        audio_fx_set_virtualizer(p->fx, width);
}

double file_av_duration(FileAv *p)
{
    double d;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    d = p->duration;
    pthread_mutex_unlock(&p->mu);
    return d;
}

double file_av_position(FileAv *p)
{
    double d;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    d = media_time_locked(p);
    pthread_mutex_unlock(&p->mu);
    return d;
}

int file_av_ended(FileAv *p)
{
    int e;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    e = p->ended;
    pthread_mutex_unlock(&p->mu);
    return e;
}

int file_av_video_size(FileAv *p, int *w, int *h)
{
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    if (w)
        *w = p->vw;
    if (h)
        *h = p->vh;
    pthread_mutex_unlock(&p->mu);
    return p && p->vw > 0 && p->vh > 0;
}

const char *file_av_last_error(FileAv *p)
{
    return p ? p->err : "nil";
}

int file_av_stream_count(FileAv *p)
{
    int n;
    if (!p)
        return 0;
    pthread_mutex_lock(&p->mu);
    n = p->nstreams;
    pthread_mutex_unlock(&p->mu);
    return n;
}

int file_av_stream_at(FileAv *p, int i, int *kind, int *index, int *w, int *h,
                      char *lang, int lang_n, char *codec, int codec_n)
{
    StreamInfo s;
    if (!p)
        return -1;
    pthread_mutex_lock(&p->mu);
    if (i < 0 || i >= p->nstreams) {
        pthread_mutex_unlock(&p->mu);
        return -1;
    }
    s = p->streams[i];
    pthread_mutex_unlock(&p->mu);
    if (kind)
        *kind = s.kind;
    if (index)
        *index = s.index;
    if (w)
        *w = s.w;
    if (h)
        *h = s.h;
    if (lang && lang_n > 0)
        snprintf(lang, (size_t)lang_n, "%s", s.lang);
    if (codec && codec_n > 0)
        snprintf(codec, (size_t)codec_n, "%s", s.codec);
    return 0;
}

void file_av_set_sinks(FileAv *p, FileAvFrameFn on_frame, FileAvPcmFn on_pcm,
                       FileAvProgressFn on_prog, FileAvEventFn on_event,
                       FileAvCaptionFn on_caption, void *user)
{
    if (!p)
        return;
    pthread_mutex_lock(&p->mu);
    p->on_frame = on_frame;
    p->on_pcm = on_pcm;
    p->on_prog = on_prog;
    p->on_event = on_event;
    p->on_caption = on_caption;
    p->user = user;
    pthread_mutex_unlock(&p->mu);
}

void *file_av_user(FileAv *p)
{
    void *u;
    if (!p)
        return NULL;
    pthread_mutex_lock(&p->mu);
    u = p->user;
    pthread_mutex_unlock(&p->mu);
    return u;
}
