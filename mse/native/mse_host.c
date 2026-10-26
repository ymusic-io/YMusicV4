#include "mse_host.h"
#include "mse_ffmpeg.h"
#include "audio_fx.h"

#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <AudioToolbox/AudioToolbox.h>
#include <dispatch/dispatch.h>
#define MSE_AQ 1
#elif defined(__linux__) && !defined(__ANDROID__)
#include "aq_pulse.h"
#define MSE_AQ 1
#elif defined(_WIN32)
#include <windows.h>
#endif
#ifdef __APPLE__
#include <mach/mach_time.h>
#include <os/log.h>
#include <sys/sysctl.h>
#include <sys/types.h>
#include "mse_vt_present.h"
#endif
#ifdef __ANDROID__
#include <android/native_window.h>
#endif

#if defined(MSE_AQ) || defined(__ANDROID__)
#define MSE_DAC 1
#define DAC_CHECKPOINTS 8
/* Handover holds cover queue latency, not a wall clock that ran away while audio was missing. */
#define DAC_HANDOVER_MAX_S 0.5
#define DAC_START_WAIT_NS 2e9
#endif
#ifdef __ANDROID__
#define DAC_FIRST_REPORT_NS 1e9
/* Longest the clock runs ahead of the sink's last position report. */
#define DAC_EXTRAPOLATE_S 0.05
#endif

static void (*g_js_event)(const char *name);

void mse_host_set_js_event(void (*fn)(const char *name))
{
    g_js_event = fn;
}

struct MseHost {
    pthread_mutex_t mu;
    pthread_mutex_t frame_mu;
    /* Guards the av pointer for callers off the JS thread: reset frees and replaces it. */
    pthread_mutex_t av_mu;
    pthread_cond_t cv;
    pthread_t dec;
    pthread_t vdec;
    int started;
    int released;
    int playing;
    int busy;
    int vbusy;
    int gate;
    int eos;
    int ended_sent;
    double hold;
    double origin_ns;
    double seek_base;
    double yt_dur;
    MseAv *av;
    MseFrameFn on_frame;
    MsePcmFn on_pcm;
    MseProgressFn on_prog;
    MseAudioFlushFn on_audio_flush;
    void *audio_flush_user;
    void *user;
    void *window;
    int vw;
    int vh;
    /* JVM hole. Survives mse_host_reset (new MseAv). 0×0 = decode size. */
    int blit_w;
    int blit_h;
    /* Atomic, not under mu: the UI thread polls it and must never wait on the decode lock. */
    atomic_int frames;
    uint8_t *last_bgra; /* borrowed from MseAv ping-pong; do not free */
    int last_w;
    int last_h;
    int frame_gen;
    double vpts;
    int rate;
    int ch;
    float speed;
    int64_t samples_played;
    int64_t samples_pulled;
    AudioFx *fx;
    int16_t *fx_pcm;
    size_t fx_pcm_n;
#ifdef MSE_DAC
    uint8_t *ring;
    int ring_cap;
    int ring_r;
    int ring_w;
    int ring_n;
    /* Bumped by every ring flush; a chunk pulled before a flush must not be written after it. */
    unsigned ring_gen;
    int aq_started;
    int aq_buf_bytes;
    int flushing;
    double dac_tick_ns;
    /* dec_loop only, no lock: end of its last fill pass while playing; 0 after a paused wait. */
    double fill_end_ns;
    double fill_gap_logged_ns;
    /* Last clock handed out, and whether it came from the audio device (see dac_handover). */
    double clk_last;
    int clk_dac;
    double flush_ns;
    /* Device frames since the last ring flush: dac_pos audible (Android: the sink's report;
       AudioQueue: handed to the queue), dac_polled read out of the ring. samples_played is
       dac_pos - dac_base. */
    int64_t dac_pos;
    int64_t dac_base;
    int64_t dac_polled;
    /* PCM is stretched before the ring, so a rate change takes effect at the frame where the old
       rate's audio ends: device frames before dac_cp_edge[i] play at dac_cp_speed[i]. */
    int64_t dac_cp_edge[DAC_CHECKPOINTS];
    double dac_cp_speed[DAC_CHECKPOINTS];
    int dac_cp_n;
    /* Set by every ring flush: the first PCM written after it puts seek_base at its own pts. */
    int pcm_anchor;
    /* The audio stream ended (endOfStream, all of it played) while the media runs on: the clock
       continues on wall time instead of freezing at the last sample. */
    int audio_out;
#endif
#ifdef MSE_AQ
    AudioQueueRef aq;
    /* Pending fade-out pause (see aq_fade_fire). */
    struct AqFadeJob *aq_fade;
#endif
#ifdef __ANDROID__
    /* The ring is drained by the Kotlin AudioSink thread (MseAudioOutput) through mse_host_dac_*.
       dac_* positions are sink output frames since the last ring flush. */
    int dac_attached;
    int dac_live;
    /* audio_done, refreshed by the decode loop: the poll must not touch av (mse_host_reset swaps it). */
    int dac_drain;
    int dac_pending;
    /* The track's own buffer: decoding must run that far past the clock or a full-buffer start never comes. */
    double dac_buffer_s;
    double dac_start_ns;
    int64_t dac_written;
    _Atomic int dac_session;
#endif
};

static int16_t *fx_scratch(MseHost *h, int frames, int ch)
{
    size_t n;
    int16_t *p;
    if (frames <= 0 || ch <= 0)
        return NULL;
    n = (size_t)frames * (size_t)ch * sizeof(int16_t);
    if (h->fx_pcm && h->fx_pcm_n >= n)
        return h->fx_pcm;
    p = (int16_t *)realloc(h->fx_pcm, n);
    if (!p)
        return NULL;
    h->fx_pcm = p;
    h->fx_pcm_n = n;
    return p;
}

static void host_stretch(MseHost *h, int16_t **pcm, int *frames, int ch, int rate)
{
    int16_t *tmp;
    int nout, cap;
    if (!h->fx || !*pcm || *frames <= 0)
        return;
    audio_fx_set_rate(h->fx, rate);
    cap = *frames * 4 + 1024;
    if (cap < *frames)
        cap = *frames;
    tmp = fx_scratch(h, cap, ch);
    if (!tmp)
        return;
    nout = audio_fx_stretch(h->fx, *pcm, *frames, tmp, cap, ch);
    *pcm = tmp;
    *frames = nout;
}

static double now_ns(void)
{
#ifdef __APPLE__
    static mach_timebase_info_data_t tb;
    static int init;
    if (!init) {
        mach_timebase_info(&tb);
        init = 1;
    }
    return (double)mach_absolute_time() * (double)tb.numer / (double)tb.denom;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
#endif
}

static double media_dur(MseHost *h)
{
    double d = mse_av_duration(h->av);
    if (h->yt_dur > d)
        d = h->yt_dur;
    return d;
}

static double play_speed(MseHost *h)
{
    float s = h->speed;
    if (s <= 0.f)
        return 1;
    if (s < 0.25f)
        return 0.25;
    if (s > 4.f)
        return 4;
    return (double)s;
}

#ifdef MSE_DAC
/* h->mu held. Media seconds of `frames` device frames past dac_base. */
static double dac_media_s(MseHost *h, double frames, double sp)
{
    double t = 0, at = 0;
    int i;
    if (h->rate <= 0)
        return 0;
    for (i = 0; i < h->dac_cp_n; i++) {
        double end = (double)(h->dac_cp_edge[i] - h->dac_base);
        if (end <= at)
            continue;
        if (frames <= end)
            return t + (frames - at) * h->dac_cp_speed[i] / (double)h->rate;
        t += (end - at) * h->dac_cp_speed[i] / (double)h->rate;
        at = end;
    }
    return t + (frames - at) * sp / (double)h->rate;
}

/* h->mu held. Fold checkpoints the audible position has passed into seek_base. */
static void dac_cp_fold(MseHost *h, int64_t audible)
{
    while (h->dac_cp_n > 0 && audible >= h->dac_cp_edge[0]) {
        if (h->rate > 0 && h->dac_cp_edge[0] > h->dac_base) {
            h->seek_base += (double)(h->dac_cp_edge[0] - h->dac_base) / (double)h->rate * h->dac_cp_speed[0];
            h->dac_base = h->dac_cp_edge[0];
        }
        h->dac_cp_n--;
        memmove(h->dac_cp_edge, h->dac_cp_edge + 1, (size_t)h->dac_cp_n * sizeof(h->dac_cp_edge[0]));
        memmove(h->dac_cp_speed, h->dac_cp_speed + 1, (size_t)h->dac_cp_n * sizeof(h->dac_cp_speed[0]));
    }
    h->samples_played = h->dac_pos > h->dac_base ? h->dac_pos - h->dac_base : 0;
}

/* h->mu held. The caller moved seek_base to the media time `keep` frames before dac_pos. */
static void dac_rebase(MseHost *h, int64_t keep)
{
    int i, w = 0;
    h->dac_base = h->dac_pos - keep;
    h->samples_played = keep;
    for (i = 0; i < h->dac_cp_n; i++) {
        if (h->dac_cp_edge[i] > h->dac_base) {
            h->dac_cp_edge[w] = h->dac_cp_edge[i];
            h->dac_cp_speed[w] = h->dac_cp_speed[i];
            w++;
        }
    }
    h->dac_cp_n = w;
}

#ifdef MSE_AQ
/* Frames handed to the AudioQueue that have not reached the speaker: ~4 buffers. */
static int64_t aq_latency_frames(MseHost *h)
{
    int fb = h->ch > 0 ? h->ch * 2 : 4;
    int one = h->aq_buf_bytes > 0 ? h->aq_buf_bytes / fb : (int)(0.02 * h->rate);
    return (int64_t)one * 4;
}

/* Wall seconds the device has played since the last callback handed it a buffer, up to one buffer. */
static double aq_tick_extra_s(MseHost *h)
{
    double one = 0.02, extra;
    if (h->dac_tick_ns <= 0)
        return 0;
    if (h->aq_buf_bytes > 0 && h->ch > 0 && h->rate > 0)
        one = (double)h->aq_buf_bytes / (double)(h->rate * h->ch * 2);
    extra = (now_ns() - h->dac_tick_ns) / 1e9;
    if (extra > one)
        extra = one;
    return extra > 0 ? extra : 0;
}
#endif
#endif

#ifdef MSE_DAC
/* h->mu held. Wall time → audio clock never steps back (Media3 DefaultMediaClock): while the
   device clock is behind what was already handed out, hold there until it catches up. */
static double dac_handover(MseHost *h, double dac)
{
    if (!h->clk_dac) {
        if (h->playing && dac < h->clk_last && dac > h->clk_last - DAC_HANDOVER_MAX_S)
            return h->clk_last;
        h->clk_dac = 1;
    }
    return dac;
}
#endif

#ifdef __ANDROID__
/* h->mu held. Wall seconds played since the sink's last position report, up to DAC_EXTRAPOLATE_S. */
static double sink_tick_extra_s(MseHost *h)
{
    double extra;
    if (!h->playing || h->dac_tick_ns <= 0)
        return 0;
    extra = (now_ns() - h->dac_tick_ns) / 1e9;
    if (extra > DAC_EXTRAPOLATE_S)
        extra = DAC_EXTRAPOLATE_S;
    return extra > 0 ? extra : 0;
}
#endif

/* h->mu held. Do not take MseAv locks: the DAC callback/poll needs h->mu, and
   MediaCodec configure holds a->mu for tens of ms (audio-only → Auto). */
/* Caller holds mu. */
static void rebase_hold(MseHost *h, double t)
{
    h->hold = t;
    h->origin_ns = now_ns();
#ifdef MSE_DAC
    h->seek_base = t;
#ifdef MSE_AQ
    /* t is the audible position: behind it sit the frames the queue still holds, ahead of the last
       tick the extrapolated ones. Both stay counted and the tick stays, so the clock carries on
       from t instead of losing a queue (or banking the extrapolation) per rebase. */
    dac_rebase(h, aq_latency_frames(h) - (int64_t)(aq_tick_extra_s(h) * h->rate));
#else
    /* t already carries the extrapolation past the sink's last report: count it as played. */
    dac_rebase(h, -(int64_t)(sink_tick_extra_s(h) * h->rate));
#endif
#endif
}

static double clock_with_span(MseHost *h, int have, double first, double last, int has_video)
{
    double t, hold;
    int out = 0;

    hold = h->hold < 0 ? 0 : h->hold;
    t = hold;
    have = have && last > first;
    /* Seek/hold outside leftover: keep currentTime at the seek so JS/SABR
       fetch that time. Clamping to `last` jumped the playhead to the
       buffered edge, then stalled (iOS) until leftover grew. */
    if (have)
        out = (hold > last + 0.25) || (hold + 0.25 < first);
    if (out || !have) {
        /* Empty leftover, or hold outside leftover: do not run the gold
           clock. Chrome currentTime stays at the seek until buffered
           covers it (log34: clock 0→6s with mse=[a: v:]). */
        if (h->playing)
            h->origin_ns = now_ns();
        t = hold;
#ifdef MSE_DAC
        h->clk_dac = 0;
#endif
    } else {
        int wait_audio = 0;
#ifdef MSE_DAC
        /* After a flush, a stream with audio holds the clock until the device starts (Chrome keeps
           currentTime until audio covers it) rather than running ahead on wall time: the audio
           would start behind it. A device that never starts falls back to wall time. */
        wait_audio = h->rate > 0 && !h->aq_started && !h->audio_out &&
                     now_ns() - h->flush_ns < DAC_START_WAIT_NS;
#endif
        if (wait_audio && h->playing)
            h->origin_ns = now_ns();
        else if (h->playing && h->origin_ns > 0)
            t += (now_ns() - h->origin_ns) / 1e9 * play_speed(h);
#ifdef MSE_DAC
        /* Audio is the master clock while the DAC is running (Chrome
           AudioRendererImpl). Wall time was winning after sink-delay
           subtract because dac < wall, so video stayed ~100ms early. */
#ifdef __ANDROID__
        /* The sink reports what has left the speaker. Until its first report the clock holds at the
           start (the AudioTrack is still filling); a sink that never reports falls back to wall time. */
        if (h->aq_started && h->rate > 0 && !h->audio_out &&
            (h->dac_live || now_ns() - h->dac_start_ns < DAC_FIRST_REPORT_NS)) {
            double sp = play_speed(h);
            double dac = h->seek_base + dac_media_s(h, (double)h->samples_played, sp);
            /* Wall time stands still while the clock holds, so a sink that never reports falls back
               from the held position instead of a second ahead of it. */
            if (!h->dac_live && h->playing)
                h->origin_ns = now_ns();
            dac += sink_tick_extra_s(h) * sp;
            t = dac_handover(h, dac);
        } else {
            h->clk_dac = 0;
        }
#else
        if (h->aq_started && h->rate > 0 && h->samples_played > 0 && !h->audio_out) {
            double sp = play_speed(h);
            double dac = h->seek_base +
                         dac_media_s(h, (double)(h->samples_played - aq_latency_frames(h)), sp);
            dac += aq_tick_extra_s(h) * sp;
            t = dac_handover(h, dac);
        } else {
            h->clk_dac = 0;
        }
#endif
#endif
        /* Linear play: stay inside leftover. Sit 1s behind leftover end
           (live head of what we have) so SABR can fetch+parse the next
           second before we empty. Rebase origin on clamp or wall time
           accrues through the wait and the next chunk is recv-late. */
        if (have) {
            double cap = last;
            /* VOD catching the real end — not live leftover past a stale
               MediaSource duration (log23 recreate: yt_dur=17253, vf to 17266). */
            /* Audio-only leftover often ends ~0.5–1s before yt_dur (fake-144
               cover is 0..dur; audio last is short). 0.5s was too tight. */
            int near_eos = h->yt_dur > 0 && last + 1.5 >= h->yt_dur &&
                           last <= h->yt_dur + 0.75;
            /* 1s behind leftover end is for video readahead. Audio-only
               (no C video) must play to audio last or the clock freezes
               1s early and SABR audio never appends (log2: clock=570.884). */
            /* Live leftover is often ~2s. Keep 1s video readahead on long
               VOD; 0.35s on a short live window so we do not starve-empty. */
            if (!near_eos && has_video) {
                double span = last - first;
                double keep = span >= 4.0 ? 1.0 : 0.35;
                if (span > keep)
                    cap = last - keep;
            }
            if (t < first)
                t = first;
            else if (t > cap) {
                t = cap;
                rebase_hold(h, cap);
            }
        }
    }
    /* Live leftover grows past a snapshot duration. Chrome duration is
       Infinity; clamping currentTime there freezes the playhead (log23). */
    if (h->yt_dur > 0 && t > h->yt_dur && (!have || last <= h->yt_dur + 0.75))
        t = h->yt_dur;
    if (t < 0)
        t = 0;
#ifdef MSE_DAC
    h->clk_last = t;
#endif
    return t;
}

static double host_clock(MseHost *h)
{
    double first = 0, last = 0, t;
    int have, has_v;
    have = mse_av_span(h->av, &first, &last);
    has_v = mse_av_has_video(h->av);
    pthread_mutex_lock(&h->mu);
    t = clock_with_span(h, have, first, last, has_v);
    pthread_mutex_unlock(&h->mu);
    return t;
}

/* The clock holds at `hold` once leftover no longer covers it, so dropping the head of leftover
   while playing must first move `hold` to the playhead or currentTime snaps back to it. */
static void hold_at_playhead(MseHost *h)
{
    double t = host_clock(h);
    pthread_mutex_lock(&h->mu);
    if (h->playing && h->hold < t)
        rebase_hold(h, t);
    pthread_mutex_unlock(&h->mu);
}

#ifdef MSE_DAC
static int frame_bytes(MseHost *h)
{
    return h->ch > 0 ? h->ch * 2 : 4;
}

static void ring_write(MseHost *h, const uint8_t *p, int n)
{
    while (n > 0) {
        int space, chunk;
        /* Do not wait while holding mu — AudioQueue cannot drain then. */
        if (h->ring_n >= h->ring_cap || h->released || h->flushing)
            return;
        space = h->ring_cap - h->ring_n;
        chunk = n < space ? n : space;
        if (h->ring_w + chunk > h->ring_cap) {
            int first = h->ring_cap - h->ring_w;
            memcpy(h->ring + h->ring_w, p, (size_t)first);
            memcpy(h->ring, p + first, (size_t)(chunk - first));
            h->ring_w = chunk - first;
        } else {
            memcpy(h->ring + h->ring_w, p, (size_t)chunk);
            h->ring_w += chunk;
        }
        h->ring_n += chunk;
        p += chunk;
        n -= chunk;
        pthread_cond_broadcast(&h->cv);
    }
}

static int ring_read(MseHost *h, uint8_t *p, int n, int color)
{
    int got = 0;
    int fb = frame_bytes(h);
    if (fb > 1)
        n -= n % fb;
    while (got < n && h->ring_n > 0) {
        int chunk = n - got;
        if (chunk > h->ring_n)
            chunk = h->ring_n;
        if (h->ring_r + chunk > h->ring_cap) {
            int first = h->ring_cap - h->ring_r;
            memcpy(p + got, h->ring + h->ring_r, (size_t)first);
            memcpy(p + got + first, h->ring, (size_t)(chunk - first));
            h->ring_r = chunk - first;
        } else {
            memcpy(p + got, h->ring + h->ring_r, (size_t)chunk);
            h->ring_r += chunk;
        }
        h->ring_n -= chunk;
        got += chunk;
    }
    if (color && got > 0 && h->fx && h->ch > 0) {
        int fb = frame_bytes(h);
        if (fb > 0)
            audio_fx_color(h->fx, (int16_t *)p, got / fb, h->ch);
    }
    return got;
}

/* h->mu held. Drops queued PCM; the DAC must drop what it holds of the old generation too. */
static void ring_flush(MseHost *h)
{
    h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    h->flush_ns = now_ns();
    h->dac_pos = h->dac_base = h->dac_polled = 0;
    h->dac_cp_n = 0;
    h->pcm_anchor = 1;
    h->audio_out = 0;
#ifdef __ANDROID__
    h->dac_live = 0;
    h->dac_drain = 0;
    h->dac_pending = 0;
    h->dac_written = 0;
#endif
}

#ifdef MSE_AQ
static void aq_callback(void *user, AudioQueueRef q, AudioQueueBufferRef buf)
{
    MseHost *h = (MseHost *)user;
    int n = 0;
    int cap;
    int live;
    pthread_mutex_lock(&h->mu);
    cap = h->aq_buf_bytes > 0 ? h->aq_buf_bytes : (int)buf->mAudioDataBytesCapacity;
    if (cap > (int)buf->mAudioDataBytesCapacity)
        cap = (int)buf->mAudioDataBytesCapacity;
    /* Always EnqueueBuffer. Returning without it leaves the buffer outstanding
       and AudioQueueStop(true) in aq_kill blocks ~20s (src="" MSE recycle). */
    if (h->released || h->flushing || !h->aq) {
        pthread_mutex_unlock(&h->mu);
        memset(buf->mAudioData, 0, (size_t)cap);
        buf->mAudioDataByteSize = (UInt32)cap;
        AudioQueueEnqueueBuffer(q, buf, 0, NULL);
        return;
    }
    /* Not gated on playing: the queue still calls back about one buffer after AudioQueuePause, and that
     * buffer plays right after the ones already queued on resume. Silence there was a ~43 ms hole
     * ~100 ms into every resume; the ring's next audio is what belongs in it. */
    live = h->ring != NULL;
    if (live)
        n = ring_read(h, (uint8_t *)buf->mAudioData, cap, 1);
    if (n > 0 && h->rate > 0 && h->ch > 0) {
        h->dac_polled += n / frame_bytes(h);
        h->dac_pos += n / frame_bytes(h);
        dac_cp_fold(h, h->dac_pos - aq_latency_frames(h));
        h->dac_tick_ns = now_ns();
    }
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (n <= 0) {
        memset(buf->mAudioData, 0, (size_t)cap);
        n = cap;
    }
    buf->mAudioDataByteSize = (UInt32)n;
    AudioQueueEnqueueBuffer(q, buf, 0, NULL);
}

/*
 * AudioQueuePause / Start cut the waveform mid-cycle, which clicks. Pause ramps the queue volume to 0 and
 * pauses it AQ_FADE_PAUSE_NS later from a dispatch job; play and every (re)start ramp back up. The job is
 * ref-counted (MseHost + dispatch) and cancelled before the queue is resumed, flushed or disposed, so it
 * never pauses a queue it no longer owns. Lock order: job->mu, then h->mu.
 */
#define AQ_FADE_S 0.015
#define AQ_FADE_PAUSE_NS (25 * 1000 * 1000)
#define AQ_STALL_NS 1e9

typedef struct AqFadeJob {
    pthread_mutex_t mu;
    int refs;
    int cancelled;
    MseHost *h;
    AudioQueueRef aq;
} AqFadeJob;

static void aq_fade_release_locked(AqFadeJob *j)
{
    int last = --j->refs == 0;
    pthread_mutex_unlock(&j->mu);
    if (last) {
        pthread_mutex_destroy(&j->mu);
        free(j);
    }
}

/* Under h->mu: detach the pending job; pass it to aq_fade_cancel after unlocking. */
static AqFadeJob *aq_fade_take(MseHost *h)
{
    AqFadeJob *j = h->aq_fade;
    h->aq_fade = NULL;
    return j;
}

static void aq_fade_cancel(AqFadeJob *j)
{
    if (!j)
        return;
    pthread_mutex_lock(&j->mu);
    j->cancelled = 1;
    aq_fade_release_locked(j);
}

static void aq_fade_fire(void *ctx)
{
    AqFadeJob *j = (AqFadeJob *)ctx;
    pthread_mutex_lock(&j->mu);
    if (!j->cancelled) {
        MseHost *h = j->h;
        int owned;
        pthread_mutex_lock(&h->mu);
        owned = h->aq_fade == j;
        if (owned)
            h->aq_fade = NULL;
        pthread_mutex_unlock(&h->mu);
        AudioQueuePause(j->aq);
        if (owned)
            j->refs--;
    }
    aq_fade_release_locked(j);
}

/* Start (or keep running) at volume 0 and ramp up, so a resume never starts mid-waveform. */
static void aq_start_faded(AudioQueueRef aq, int running)
{
    if (!running) {
        AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, 0);
        AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 0);
        AudioQueueStart(aq, NULL);
    }
    AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, AQ_FADE_S);
    AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 1);
}

static int start_aq(MseHost *h, int rate, int ch)
{
    AudioStreamBasicDescription fmt;
    AudioQueueRef aq = NULL;
    uint8_t *ring = NULL;
    int i, buf_bytes, cap;
    if (h->aq)
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
    cap = rate * ch * 2; /* 1s */
    buf_bytes = 2048 * ch * 2;
    pthread_mutex_unlock(&h->mu);
    if (AudioQueueNewOutput(&fmt, aq_callback, h, NULL, kCFRunLoopCommonModes, 0, &aq) != 0) {
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    ring = (uint8_t *)calloc(1, (size_t)cap);
    if (!ring) {
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&h->mu);
        return -1;
    }
    pthread_mutex_lock(&h->mu);
    if (h->aq || h->released) {
        pthread_mutex_unlock(&h->mu);
        free(ring);
        AudioQueueDispose(aq, true);
        pthread_mutex_lock(&h->mu);
        return 0;
    }
    h->aq = aq;
    h->rate = rate;
    h->ch = ch;
    h->ring = ring;
    h->ring_cap = cap;
    ring_flush(h);
    h->aq_buf_bytes = buf_bytes;
    pthread_mutex_unlock(&h->mu);
    for (i = 0; i < 4; i++) {
        AudioQueueBufferRef buf;
        if (AudioQueueAllocateBuffer(aq, (UInt32)buf_bytes, &buf) != 0)
            break;
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
        buf->mAudioDataByteSize = (UInt32)buf_bytes;
        AudioQueueEnqueueBuffer(aq, buf, 0, NULL);
    }
    pthread_mutex_lock(&h->mu);
    return 0;
}
#endif

#ifdef __ANDROID__
/* h->mu held. Format change: a fresh ring the sink re-configures for (it sees the new gen). */
static int dac_setup(MseHost *h, int rate, int ch)
{
    int cap = rate * ch * 2; /* 1s */
    uint8_t *ring = (uint8_t *)calloc(1, (size_t)cap);
    if (!ring)
        return -1;
    free(h->ring);
    h->ring = ring;
    h->ring_cap = cap;
    h->rate = rate;
    h->ch = ch;
    h->aq_started = 0;
    h->aq_buf_bytes = 2048 * ch * 2;
    ring_flush(h);
    return 0;
}
#endif

static int ring_ms(MseHost *h)
{
    int bps;
    if (h->rate <= 0 || h->ch <= 0)
        return 0;
    bps = h->rate * h->ch * 2;
    if (bps <= 0)
        return 0;
    return (int)((int64_t)h->ring_n * 1000 / bps);
}
#endif

/* Decode thread pulls frames; JS thread only appends/seeks. on_frame/on_pcm
   run on the decode thread. Do not touch MseHost from a third thread. */
static void keep_bgra(MseHost *h, int w, int ht, uint8_t *bgra)
{
    if (!bgra || w <= 0 || ht <= 0)
        return;
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = bgra;
    h->last_w = w;
    h->last_h = ht;
    h->frame_gen++;
    pthread_mutex_unlock(&h->frame_mu);
}

static void maybe_notify_ended(MseHost *h)
{
    int playing;
    void (*fn)(const char *name);
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    playing = h->playing && !h->ended_sent;
    pthread_mutex_unlock(&h->mu);
    if (!playing)
        return;
    if (!mse_host_ended(h))
        return;
    pthread_mutex_lock(&h->mu);
    if (h->ended_sent || !h->playing) {
        pthread_mutex_unlock(&h->mu);
        return;
    }
    h->ended_sent = 1;
    pthread_mutex_unlock(&h->mu);
    fn = g_js_event;
    if (fn)
        fn("mse_ended");
    mse_critf(MSE_LOG_INFO, "mse ended notify");
}

static void *vid_loop(void *arg)
{
    MseHost *h = (MseHost *)arg;
    while (1) {
        double clock, dur;
        uint8_t *bgra = NULL;
        uint8_t *show = NULL;
        int w = 0, ht = 0, show_w = 0, show_ht = 0;
        double pts = 0;
        pthread_mutex_lock(&h->mu);
        h->vbusy = 0;
        pthread_cond_broadcast(&h->cv);
        for (;;) {
            while (!h->released && (!h->playing || h->gate))
                pthread_cond_wait(&h->cv, &h->mu);
            if (h->released) {
                pthread_mutex_unlock(&h->mu);
                return NULL;
            }
            pthread_mutex_unlock(&h->mu);
            /* v_rest is on a->mu. Do not nest under h->mu: rest-off's
               mc_close holds a->mu and the DAC needs h->mu. */
            if (!mse_av_video_rested(h->av)) {
                pthread_mutex_lock(&h->mu);
                if (h->released) {
                    pthread_mutex_unlock(&h->mu);
                    return NULL;
                }
                if (!h->playing || h->gate)
                    continue;
                break;
            }
            pthread_mutex_lock(&h->mu);
            if (h->released) {
                pthread_mutex_unlock(&h->mu);
                return NULL;
            }
            if (!h->playing || h->gate)
                continue;
            pthread_cond_wait(&h->cv, &h->mu);
        }
        h->vbusy = 1;
        pthread_mutex_unlock(&h->mu);
        clock = host_clock(h);
        dur = mse_av_duration(h->av);

        if (mse_av_pull_video(h->av, clock, &bgra, &w, &ht, &pts) > 0) {
            if (bgra) {
                keep_bgra(h, w, ht, bgra);
                show = bgra;
                show_w = w;
                show_ht = ht;
            }
            pthread_mutex_lock(&h->mu);
            h->vw = w;
            h->vh = ht;
            h->vpts = pts;
            pthread_mutex_unlock(&h->mu);
            atomic_fetch_add_explicit(&h->frames, 1, memory_order_relaxed);
        }

        if (show && h->on_frame)
            h->on_frame(h->user, show_w, show_ht, show);
        if (h->on_prog)
            h->on_prog(h->user, clock, dur, w, ht);
        maybe_notify_ended(h);
        {
            struct timespec sl;
            sl.tv_sec = 0;
            sl.tv_nsec = 4 * 1000 * 1000;
            nanosleep(&sl, NULL);
        }
    }
}

static int audio_done(MseHost *h);

#ifdef MSE_DAC
/* Media3 hands the clock to wall time when the audio renderer has read its stream to the end but
   the period runs on (DefaultMediaClock, ExoPlayer #1874): a shorter audio track must not freeze
   the video at its last sample. Only after endOfStream; a SABR starve stays waiting. */
static void audio_out_check(MseHost *h)
{
    int out;
    double t;
    if (mse_av_audio_pending(h->av))
        return;
    pthread_mutex_lock(&h->mu);
    out = h->playing && h->eos && h->aq_started && !h->audio_out && h->ring && h->ring_n <= 0;
#ifdef __ANDROID__
    out = out && !h->dac_pending;
#endif
    pthread_mutex_unlock(&h->mu);
    if (!out)
        return;
    t = host_clock(h);
    pthread_mutex_lock(&h->mu);
    if (h->playing && h->eos && !h->audio_out && h->ring_n <= 0) {
        h->hold = t;
        h->origin_ns = now_ns();
        h->audio_out = 1;
    }
    pthread_mutex_unlock(&h->mu);
}
#endif

#ifdef MSE_AQ
static void aq_kill(MseHost *h);

/* A queue that stops calling back (route change, audio server restart, a vendor bug) freezes the
   clock with audio waiting in the ring. Media3 resets an output stuck this way after 200 ms; the
   queue's own callback cadence is coarser, so wait 1 s, then reopen it from where playback is. */
static void aq_watchdog(MseHost *h)
{
    double t;
    pthread_mutex_lock(&h->mu);
    if (!h->playing || !h->aq || !h->aq_started || h->flushing || h->aq_fade || h->ring_n <= 0 ||
        h->dac_tick_ns <= 0 || now_ns() - h->dac_tick_ns < AQ_STALL_NS) {
        pthread_mutex_unlock(&h->mu);
        return;
    }
    h->dac_tick_ns = now_ns();
    pthread_mutex_unlock(&h->mu);
    t = host_clock(h);
    mse_critf(MSE_LOG_WARN, "mse audio queue stalled with audio queued, reopening at %.3f", t);
    aq_kill(h);
    pthread_mutex_lock(&h->mu);
    h->hold = t;
    h->seek_base = t;
    h->origin_ns = now_ns();
    ring_flush(h);
    pthread_mutex_unlock(&h->mu);
}
#endif

#ifdef MSE_DAC
#define FILL_GAP_LOG_MS 150.0
#define FILL_GAP_LOW_RING_MS 100

/* At a pass end. Passes run every ~2 ms, so a long gap that left the ring nearly empty is this
   thread starved or stuck while the device ran out of audio. */
static void log_fill_gap(MseHost *h, int ring_at_wake)
{
    double now = now_ns(), gap = h->fill_end_ns > 0 ? (now - h->fill_end_ns) / 1e6 : 0;
    h->fill_end_ns = now;
    if (gap < FILL_GAP_LOG_MS || ring_at_wake >= FILL_GAP_LOW_RING_MS)
        return;
    if (now - h->fill_gap_logged_ns < 10e9)
        return;
    h->fill_gap_logged_ns = now;
    mse_critf(MSE_LOG_WARN, "mse audio fill gap %.0fms ring=%dms", gap, ring_at_wake);
}
#endif

static void *dec_loop(void *arg)
{
    MseHost *h = (MseHost *)arg;
    while (1) {
        double clock;
        int16_t *pcm = NULL;
        int frames = 0, rate = 0, ch = 0;
        double pts = 0;
#ifdef MSE_DAC
        int filled;
        int ring_at_wake;
#ifdef MSE_AQ
        AudioQueueRef start_q = NULL;
#endif
#endif
        pthread_mutex_lock(&h->mu);
        h->busy = 0;
        pthread_cond_broadcast(&h->cv);
        while (!h->released && (!h->playing || h->gate)) {
#ifdef MSE_DAC
            h->fill_end_ns = 0;
#endif
            pthread_cond_wait(&h->cv, &h->mu);
        }
        if (h->released) {
            pthread_mutex_unlock(&h->mu);
            return NULL;
        }
        h->busy = 1;
#ifdef MSE_DAC
        ring_at_wake = ring_ms(h);
#endif
        pthread_mutex_unlock(&h->mu);
        clock = host_clock(h);

#ifdef MSE_DAC
        /* Own thread: a late 4K GOP must not starve AudioQueue. */
        filled = 0;
        while (filled < 8) {
            int want;
            int dac = 0;
            unsigned gen;
            double lead = 0.35;
            double until;
            double from = clock;
            pthread_mutex_lock(&h->mu);
            want = ring_ms(h) < 400;
            gen = h->ring_gen;
            /* The lead is output time: the device queue sits on top of it, and at 2x it covers
               twice the media. */
#ifdef __ANDROID__
            lead += h->dac_buffer_s;
#endif
#ifdef MSE_AQ
            if (h->rate > 0)
                lead += (double)aq_latency_frames(h) / (double)h->rate;
#endif
            lead *= play_speed(h);
            until = clock + lead;
#ifdef __ANDROID__
            /* Until the sink reports playout the clock holds, and a streaming AudioTrack starts only
               once full: decode as far as ring + sink take (Media3 feeds until handleBuffer refuses). */
            if (!h->dac_live)
                until = clock + 3600.0;
            /* A track that has not reported yet may still be opening (a hung audioserver takes
               seconds) while the clock runs on wall time: the audio behind that clock is what the
               track plays first, and the clock steps back to it once the sink reports. */
            if (h->aq_started && !h->dac_live && from > h->seek_base)
                from = h->seek_base;
#endif
            pthread_mutex_unlock(&h->mu);
            if (!want)
                break;
            pcm = NULL;
            if (mse_av_pull_audio(h->av, from, until, &pcm, &frames, &rate, &ch, &pts) <= 0 || !pcm)
                break;
            host_stretch(h, &pcm, &frames, ch, rate);
            if (frames <= 0) {
                pcm = NULL;
                filled++;
                continue;
            }
            {
                int nbytes = frames * ch * (int)sizeof(int16_t);
                        pthread_mutex_lock(&h->mu);
#ifdef MSE_AQ
                if (h->aq && (h->rate != rate || h->ch != ch)) {
                    AudioQueueRef old = h->aq;
                    AqFadeJob *fade = aq_fade_take(h);
                    h->aq = NULL;
                    h->aq_started = 0;
                    free(h->ring);
                    h->ring = NULL;
                    pthread_mutex_unlock(&h->mu);
                    aq_fade_cancel(fade);
                    AudioQueueStop(old, true);
                    AudioQueueDispose(old, true);
                    pthread_mutex_lock(&h->mu);
                }
                if (!h->aq && rate > 0 && ch > 0)
                    start_aq(h, rate, ch);
                dac = h->aq != NULL;
#endif
#ifdef __ANDROID__
                if (h->dac_attached && rate > 0 && ch > 0 &&
                    (!h->ring || h->rate != rate || h->ch != ch)) {
                    /* The chunk was pulled for this format: it opens the new generation. */
                    int fresh = gen == h->ring_gen;
                    if (dac_setup(h, rate, ch) == 0 && fresh)
                        gen = h->ring_gen;
                }
                dac = h->dac_attached && h->ring != NULL;
#endif
                h->samples_pulled += frames;
                if (dac && !h->flushing && gen == h->ring_gen) {
                    /* The audio clock starts at the first sample's own time, not the seek target
                       (Media3 takes startMediaTimeUs from the first buffer): a seek lands up to a
                       frame early, and a reopened queue resumes past the audio its ring lost. */
                    if (h->pcm_anchor && h->dac_polled == 0) {
                        h->seek_base = pts;
                        h->pcm_anchor = 0;
                    }
                    ring_write(h, (const uint8_t *)pcm, nbytes);
                }
                if (dac && !h->aq_started && ring_ms(h) >= 160) {
                    h->aq_started = 1;
                    dac_rebase(h, 0);
                    h->dac_tick_ns = 0;
#ifdef MSE_AQ
                    start_q = h->aq;
#endif
#ifdef __ANDROID__
                    h->dac_start_ns = now_ns();
#endif
                }
                pthread_mutex_unlock(&h->mu);
            }
            pcm = NULL;
            filled++;
        }
#ifdef MSE_AQ
        if (start_q)
            aq_start_faded(start_q, 0);
#endif
#else
        if (mse_av_pull_audio(h->av, clock, clock + 0.35, &pcm, &frames, &rate, &ch, &pts) > 0 && pcm) {
            int nbytes;
            host_stretch(h, &pcm, &frames, ch, rate);
            if (h->fx && frames > 0)
                audio_fx_color(h->fx, pcm, frames, ch);
            if (frames > 0) {
                nbytes = frames * ch * (int)sizeof(int16_t);
                if (h->on_pcm)
                    h->on_pcm(h->user, rate, ch, pcm, nbytes);
                pthread_mutex_lock(&h->mu);
                h->samples_pulled += frames;
                pthread_mutex_unlock(&h->mu);
            }
        }
#endif

#ifdef MSE_AQ
        aq_watchdog(h);
#endif
#ifdef MSE_DAC
        audio_out_check(h);
#endif
#ifdef __ANDROID__
        {
            int done = audio_done(h);
            pthread_mutex_lock(&h->mu);
            h->dac_drain = done;
            pthread_mutex_unlock(&h->mu);
        }
#endif
        maybe_notify_ended(h);
#ifdef MSE_DAC
        log_fill_gap(h, ring_at_wake);
#endif
        {
            struct timespec sl;
            /* Never 0: pull_audio returning 0 with packets still pending
               was while(true){}. 2ms is plenty for DAC fill. */
            sl.tv_sec = 0;
            sl.tv_nsec = 2 * 1000 * 1000;
            nanosleep(&sl, NULL);
        }
    }
}

#ifdef __APPLE__
static MseHost *g_ios_host;
void mse_ios_log(const char *msg);
#endif

MseHost *mse_host_create(void)
{
    MseHost *h = (MseHost *)calloc(1, sizeof(MseHost));
    if (!h)
        return NULL;
    pthread_mutex_init(&h->mu, NULL);
    pthread_mutex_init(&h->frame_mu, NULL);
    pthread_mutex_init(&h->av_mu, NULL);
    pthread_cond_init(&h->cv, NULL);
    h->av = mse_av_create();
    h->fx = audio_fx_create();
    h->speed = 1.f;
    h->playing = 0;
    h->vpts = -1;
    if (!h->av) {
        free(h);
        return NULL;
    }
#ifdef __APPLE__
    g_ios_host = h;
#endif
    return h;
}

#ifdef MSE_DAC
#ifdef MSE_AQ
/* video.src swap: Pause+Flush left in-flight callbacks free to Enqueue old PCM. */
static void aq_kill(MseHost *h)
{
    AudioQueueRef aq;
    uint8_t *ring;
    AqFadeJob *fade;
    pthread_mutex_lock(&h->mu);
    h->flushing = 1;
    h->aq_started = 0;
    fade = aq_fade_take(h);
    aq = h->aq;
    h->aq = NULL;
    ring = h->ring;
    h->ring = NULL;
    h->ring_cap = h->ring_r = h->ring_w = h->ring_n = 0;
    h->ring_gen++;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    aq_fade_cancel(fade);
    if (aq) {
        AudioQueueStop(aq, false);
        AudioQueueDispose(aq, true);
    }
    free(ring);
    pthread_mutex_lock(&h->mu);
    h->flushing = 0;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}
#endif

static void aq_pause_flush(MseHost *h)
{
#ifdef MSE_AQ
    AudioQueueRef aq;
    AqFadeJob *fade;
#endif
    pthread_mutex_lock(&h->mu);
    h->flushing = 1;
    h->aq_started = 0;
    ring_flush(h);
    pthread_cond_broadcast(&h->cv);
#ifdef MSE_AQ
    aq = h->aq;
    fade = aq_fade_take(h);
#endif
    pthread_mutex_unlock(&h->mu);
#ifdef MSE_AQ
    aq_fade_cancel(fade);
    if (aq) {
        AudioQueuePause(aq);
        AudioQueueFlush(aq);
    }
#endif
    pthread_mutex_lock(&h->mu);
    h->flushing = 0;
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}
#endif

static void wait_idle(MseHost *h)
{
    while ((h->busy || h->vbusy) && !h->released)
        pthread_cond_wait(&h->cv, &h->mu);
}

void mse_host_destroy(MseHost *h)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->released = 1;
    h->playing = 0;
#ifdef MSE_DAC
    h->flushing = 1;
#endif
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (h->started) {
        pthread_join(h->dec, NULL);
        pthread_join(h->vdec, NULL);
    }
#ifdef __APPLE__
    if (g_ios_host == h)
        g_ios_host = NULL;
#endif
#ifdef MSE_AQ
    pthread_mutex_lock(&h->mu);
    {
        AqFadeJob *fade = aq_fade_take(h);
        pthread_mutex_unlock(&h->mu);
        aq_fade_cancel(fade);
    }
    if (h->aq) {
        AudioQueueStop(h->aq, true);
        AudioQueueDispose(h->aq, true);
        h->aq = NULL;
    }
#endif
#ifdef MSE_DAC
    free(h->ring);
    h->ring = NULL;
#endif
#ifdef __ANDROID__
    mse_av_set_window(h->av, NULL);
    if (h->window)
        ANativeWindow_release((ANativeWindow *)h->window);
    h->window = NULL;
#endif
#ifdef __APPLE__
    mse_av_set_window(h->av, NULL);
    if (h->window) {
        mse_vt_detach(h->window);
        h->window = NULL;
    }
#endif
#ifdef _WIN32
    if (h->window) {
        extern void mse_d3d11_present_detach(void);
        mse_d3d11_present_detach();
        h->window = NULL;
    }
#endif
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = NULL;
    h->last_w = 0;
    h->last_h = 0;
    pthread_mutex_unlock(&h->frame_mu);
    pthread_mutex_lock(&h->av_mu);
    mse_av_destroy(h->av);
    h->av = NULL;
    pthread_mutex_unlock(&h->av_mu);
    audio_fx_destroy(h->fx);
    free(h->fx_pcm);
    pthread_mutex_destroy(&h->frame_mu);
    pthread_mutex_destroy(&h->av_mu);
    pthread_mutex_destroy(&h->mu);
    pthread_cond_destroy(&h->cv);
    free(h);
}

void mse_host_set_video_surface(MseHost *h, void *window)
{
    void *old;
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    old = h->window;
    h->window = window;
    pthread_mutex_unlock(&h->mu);
#ifdef __ANDROID__
    pthread_mutex_lock(&h->av_mu);
    mse_av_set_window(h->av, window);
    pthread_mutex_unlock(&h->av_mu);
    if (old)
        ANativeWindow_release((ANativeWindow *)old);
#elif defined(__APPLE__)
    pthread_mutex_lock(&h->av_mu);
    mse_av_set_window(h->av, window);
    pthread_mutex_unlock(&h->av_mu);
    if (old && old != window)
        mse_vt_detach(old);
#elif defined(_WIN32)
    (void)old;
    /* Windows: on attach (window set to the HWND sentinel), nudge the video decoder to reopen so
     * open_decoder's d3d11va gate re-evaluates with the swapchain now active. SW→HW transition. */
    if (window) {
        pthread_mutex_lock(&h->av_mu);
        mse_av_reopen_video(h->av);
        pthread_mutex_unlock(&h->av_mu);
    }
#else
    (void)window;
    (void)old;
#endif
}

void mse_host_set_sinks(MseHost *h, MseFrameFn on_frame, MsePcmFn on_pcm, MseProgressFn on_prog, void *user)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->on_frame = on_frame;
    h->on_pcm = on_pcm;
    h->on_prog = on_prog;
    h->user = user;
    pthread_mutex_unlock(&h->mu);
}

void mse_host_set_audio_flush(MseHost *h, MseAudioFlushFn fn, void *user)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->on_audio_flush = fn;
    h->audio_flush_user = user;
    pthread_mutex_unlock(&h->mu);
}

void mse_host_set_blit_size(MseHost *h, int w, int ht)
{
    if (!h)
        return;
    if (w < 0)
        w = 0;
    if (ht < 0)
        ht = 0;
    pthread_mutex_lock(&h->mu);
    h->blit_w = w;
    h->blit_h = ht;
    mse_av_set_blit_size(h->av, w, ht);
    pthread_mutex_unlock(&h->mu);
}

static void ensure_thread(MseHost *h)
{
    if (h->started)
        return;
    h->started = 1;
    pthread_create(&h->dec, NULL, dec_loop, h);
    pthread_create(&h->vdec, NULL, vid_loop, h);
}

void mse_host_play(MseHost *h)
{
    double dur, clock;
#ifdef MSE_AQ
    AudioQueueRef aq = NULL;
    AqFadeJob *fade;
    int started;
#endif
    if (!h)
        return;
    dur = mse_av_duration(h->av);
    {
        double first = 0, last = 0;
        int have = mse_av_span(h->av, &first, &last);
        int has_v = mse_av_has_video(h->av);
        pthread_mutex_lock(&h->mu);
        clock = clock_with_span(h, have, first, last, has_v);
    }
    if (h->eos && dur > 0 && clock >= dur - 0.05) {
        h->hold = 0;
        h->seek_base = 0;
        h->eos = 0;
        h->ended_sent = 0;
#ifdef MSE_DAC
        h->audio_out = 0;
        dac_rebase(h, 0);
        h->dac_cp_n = 0;
        h->aq_started = 0;
        h->dac_tick_ns = 0;
#endif
#ifdef __ANDROID__
        ring_flush(h);
#endif
        pthread_mutex_unlock(&h->mu);
        mse_av_seek(h->av, 0);
        pthread_mutex_lock(&h->mu);
    }
    if (!h->playing) {
        h->origin_ns = now_ns();
        h->playing = 1;
#ifdef __ANDROID__
        /* No extrapolation from a report taken while paused: the track restarts from that position. */
        h->dac_tick_ns = 0;
#elif defined(MSE_AQ)
        /* The stall watchdog times from the resume, not from the last callback before the pause. */
        if (h->dac_tick_ns > 0)
            h->dac_tick_ns = now_ns();
#endif
    }
    ensure_thread(h);
    pthread_cond_broadcast(&h->cv);
#ifdef MSE_AQ
    aq = h->aq;
    started = h->aq_started;
    fade = aq_fade_take(h);
#endif
    pthread_mutex_unlock(&h->mu);
#ifdef MSE_AQ
    /* A pause still fading out never stopped the queue: just ramp back up from where it is. */
    aq_fade_cancel(fade);
    if (aq && started)
        aq_start_faded(aq, fade != NULL);
#endif
}

void mse_host_pause(MseHost *h)
{
    double t;
#ifdef MSE_AQ
    AudioQueueRef aq;
#endif
    if (!h)
        return;
    t = host_clock(h);
    /* Stop the device before clearing playing: a callback that sees playing == 0 while the device still
     * runs enqueues silence, which then plays ahead of the music on resume (an audible gap). */
#ifdef MSE_AQ
    {
        AqFadeJob *prev, *job = NULL;
        pthread_mutex_lock(&h->mu);
        aq = h->aq;
        prev = aq_fade_take(h);
        if (aq && h->playing && h->aq_started) {
            job = (AqFadeJob *)calloc(1, sizeof(AqFadeJob));
            if (job) {
                pthread_mutex_init(&job->mu, NULL);
                job->refs = 2;
                job->h = h;
                job->aq = aq;
                h->aq_fade = job;
            }
        }
        pthread_mutex_unlock(&h->mu);
        aq_fade_cancel(prev);
        if (job) {
            AudioQueueSetParameter(aq, kAudioQueueParam_VolumeRampTime, AQ_FADE_S);
            AudioQueueSetParameter(aq, kAudioQueueParam_Volume, 0);
            dispatch_after_f(dispatch_time(DISPATCH_TIME_NOW, AQ_FADE_PAUSE_NS),
                             dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), job, aq_fade_fire);
        } else if (aq) {
            AudioQueuePause(aq);
        }
    }
#endif
    pthread_mutex_lock(&h->mu);
    if (h->playing) {
        h->hold = t;
        h->playing = 0;
    }
    pthread_cond_broadcast(&h->cv);
    {
        MseAudioFlushFn fn = h->on_audio_flush;
        void *user = h->audio_flush_user;
        pthread_mutex_unlock(&h->mu);
        if (fn)
            fn(user);
    }
}

static void seek_common(MseHost *h, double seconds)
{
    double prev;
    mse_logf("mse seek t=%.3f", seconds);
    pthread_mutex_lock(&h->mu);
    prev = h->playing ? 0 : (h->hold < 0 ? 0 : h->hold);
    pthread_mutex_unlock(&h->mu);
    if (h->playing)
        prev = host_clock(h);
#ifdef MSE_DAC
    /* Far seek: Pause+Flush leaves the same AudioQueue; in-flight buffers
       are the previous playhead (~0.5s smash after a few jumps). */
    if (seconds > prev + 1.0 || prev > seconds + 1.0) {
#ifdef MSE_AQ
        aq_kill(h);
#else
        aq_pause_flush(h);
#endif
    } else {
        aq_pause_flush(h);
    }
#endif
    pthread_mutex_lock(&h->mu);
    h->gate = 1;
    pthread_cond_broadcast(&h->cv);
    wait_idle(h);
    h->hold = seconds;
    h->seek_base = seconds;
    h->origin_ns = now_ns();
    h->eos = 0;
    h->ended_sent = 0;
#ifdef MSE_DAC
    h->samples_played = 0;
    h->aq_started = 0;
    h->dac_tick_ns = 0;
    ring_flush(h);
#endif
    h->vpts = -1;
    pthread_mutex_unlock(&h->mu);
    mse_av_seek(h->av, seconds);
    pthread_mutex_lock(&h->mu);
    h->gate = 0;
    pthread_cond_broadcast(&h->cv);
    {
        MseAudioFlushFn fn = h->on_audio_flush;
        void *user = h->audio_flush_user;
        pthread_mutex_unlock(&h->mu);
        if (fn)
            fn(user);
    }
}

void mse_host_reset(MseHost *h)
{
    if (!h)
        return;
#ifdef MSE_DAC
#ifdef MSE_AQ
    aq_kill(h);
#endif
#endif
    pthread_mutex_lock(&h->mu);
    h->playing = 0;
    h->gate = 1;
    pthread_cond_broadcast(&h->cv);
    wait_idle(h);
    h->gate = 0;
    h->hold = 0;
    h->seek_base = 0;
    h->yt_dur = 0;
    h->origin_ns = 0;
    h->eos = 0;
    h->ended_sent = 0;
    h->vw = 0;
    h->vh = 0;
#ifdef MSE_DAC
    h->samples_played = 0;
    h->aq_started = 0;
    h->dac_tick_ns = 0;
    ring_flush(h);
#endif
    h->vpts = -1;
    pthread_mutex_lock(&h->frame_mu);
    h->last_bgra = NULL;
    h->last_w = 0;
    h->last_h = 0;
    pthread_mutex_unlock(&h->frame_mu);
    pthread_mutex_lock(&h->av_mu);
    mse_av_destroy(h->av);
    h->av = mse_av_create();
    /* MediaSource reset drops MseAv (and mc_win / VT layer / blit size).
       Keep the attached window so AV1/MediaCodec can open after load.
       Keep the hole so 4K does not sws 3840×2160 (log28 hole=0x0, ~1 fps). */
    mse_av_set_window(h->av, h->window);
    mse_av_set_blit_size(h->av, h->blit_w, h->blit_h);
    pthread_mutex_unlock(&h->av_mu);
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}

int mse_host_paused(MseHost *h)
{
    int p;
    if (!h)
        return 1;
    pthread_mutex_lock(&h->mu);
    p = !h->playing;
    pthread_mutex_unlock(&h->mu);
    return p;
}

int mse_host_ready_state(MseHost *h)
{
    double d;
    if (!h)
        return 0;
    d = mse_av_duration(h->av);
    if (d > 0)
        return 4;
    return 1;
}

double mse_host_current_time(MseHost *h)
{
    if (!h)
        return 0;
    return host_clock(h);
}

void mse_host_set_current_time(MseHost *h, double seconds)
{
    double now, d;
    if (!h)
        return;
    if (seconds < 0)
        seconds = 0;
    now = host_clock(h);
    pthread_mutex_lock(&h->mu);
    d = seconds - now;
    if (d < 0)
        d = -d;
    pthread_mutex_unlock(&h->mu);
    if (d < 0.05)
        return;
    seek_common(h, seconds);
}

double mse_host_duration(MseHost *h)
{
    if (!h)
        return 0;
    return media_dur(h);
}

void mse_host_set_duration(MseHost *h, double seconds)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    if (seconds <= 0)
        h->yt_dur = 0;
    else if (seconds > h->yt_dur)
        h->yt_dur = seconds;
    pthread_mutex_unlock(&h->mu);
}

/* All audio has been handed to the DAC and the stream is over; what the DAC still holds is not checked. */
static int audio_done(MseHost *h)
{
    int eos, ring_empty, near_dur, out = 0;
    double last, yt_dur;
    if (!h)
        return 0;
    if (mse_av_audio_pending(h->av))
        return 0;
    last = mse_av_duration(h->av);
    pthread_mutex_lock(&h->mu);
    eos = h->eos;
    yt_dur = h->yt_dur;
#ifdef MSE_DAC
    ring_empty = !h->ring || h->ring_n <= 0;
    out = h->audio_out;
#else
    ring_empty = 1;
#endif
    pthread_mutex_unlock(&h->mu);
    if (!ring_empty)
        return 0;
    /* A seek also empties the ring while the rest of the media is buffered: the playhead must be
       there. Past the end of a shorter audio track the wall clock plays the video out. */
    if (host_clock(h) + (out ? 0.05 : 1.0) < last)
        return 0;
    /* Same window as clock_with_span near_eos. Miller skips endOfStream on
       VOD audio-only (40=1 + fake 144p); last ≈ duration is Chrome's other
       IsEnded path. SABR starve (last ≪ yt_dur) stays waiting. */
    near_dur = yt_dur > 0 && last + 1.5 >= yt_dur && last <= yt_dur + 0.75;
    return eos || near_dur;
}

int mse_host_ended(MseHost *h)
{
#ifdef __ANDROID__
    int pending;
#endif
    if (!audio_done(h))
        return 0;
#ifdef __ANDROID__
    pthread_mutex_lock(&h->mu);
    pending = h->dac_attached && h->dac_pending;
    pthread_mutex_unlock(&h->mu);
    if (pending)
        return 0;
#endif
    return 1;
}

void mse_host_end_of_stream(MseHost *h)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->eos = 1;
    pthread_mutex_unlock(&h->mu);
    maybe_notify_ended(h);
}

int mse_host_video_size(MseHost *h, int *w, int *ht)
{
    if (!h || !w || !ht)
        return 0;
    pthread_mutex_lock(&h->mu);
    *w = h->vw;
    *ht = h->vh;
    pthread_mutex_unlock(&h->mu);
    return *w > 0 && *ht > 0;
}

int mse_host_copy_frame(MseHost *h, uint8_t *dst, int cap, int *w, int *ht, int *gen)
{
    int n;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->frame_mu);
    if (!h->last_bgra || h->last_w <= 0 || h->last_h <= 0) {
        pthread_mutex_unlock(&h->frame_mu);
        return 0;
    }
    if ((uint64_t)h->last_w * (uint64_t)h->last_h > (uint64_t)(INT_MAX / 4)) {
        pthread_mutex_unlock(&h->frame_mu);
        return 0;
    }
    n = h->last_w * h->last_h * 4;
    if (w)
        *w = h->last_w;
    if (ht)
        *ht = h->last_h;
    if (gen)
        *gen = h->frame_gen;
    if (dst && cap >= n)
        memcpy(dst, h->last_bgra, (size_t)n);
    pthread_mutex_unlock(&h->frame_mu);
    return n;
}

int mse_host_video_failure(MseHost *h, char *out, int cap)
{
    int failed;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->av_mu);
    failed = mse_av_video_failure(h->av, out, cap);
    pthread_mutex_unlock(&h->av_mu);
    return failed;
}

int mse_host_frames(MseHost *h)
{
    if (!h)
        return 0;
    return atomic_load_explicit(&h->frames, memory_order_relaxed);
}

#ifdef __ANDROID__
int mse_host_audio_session(MseHost *h)
{
    return h ? atomic_load(&h->dac_session) : 0;
}

void mse_host_dac_attach(MseHost *h, int attached)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    h->dac_attached = attached;
    if (!attached) {
        h->aq_started = 0;
        ring_flush(h);
        atomic_store(&h->dac_session, 0);
    }
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
}

int mse_host_dac_poll(MseHost *h, uint8_t *dst, int cap, int wait_ms, int state[MSE_DAC_STATE_N])
{
    int n = 0, run, ch;
    if (!h)
        return -1;
    pthread_mutex_lock(&h->mu);
    /* Only paused waits here: while playing, the decode loop signals cv every few ms. */
    if (wait_ms > 0 && !h->released && !h->playing && h->dac_attached) {
        struct timespec until;
        clock_gettime(CLOCK_REALTIME, &until);
        until.tv_nsec += (long)wait_ms * 1000000L;
        until.tv_sec += until.tv_nsec / 1000000000L;
        until.tv_nsec %= 1000000000L;
        pthread_cond_timedwait(&h->cv, &h->mu, &until);
    }
    if (h->released) {
        pthread_mutex_unlock(&h->mu);
        return -1;
    }
    run = h->playing && h->aq_started && h->ring && !h->flushing;
    if (run && dst && cap > 0)
        n = ring_read(h, dst, cap, 0);
    if (n > 0) {
        /* Until the sink's next report: the chunk is out of the ring but not in the track yet. */
        h->dac_pending = 1;
        h->dac_polled += n / frame_bytes(h);
    }
    ch = h->ch;
    state[MSE_DAC_GEN] = (int)h->ring_gen;
    state[MSE_DAC_RATE] = h->rate;
    state[MSE_DAC_CH] = h->ch;
    state[MSE_DAC_RUN] = run;
    state[MSE_DAC_DRAIN] = h->dac_drain && h->ring_n <= 0;
    pthread_mutex_unlock(&h->mu);
    if (n > 0 && h->fx && ch > 0)
        audio_fx_color(h->fx, (int16_t *)dst, n / (ch * 2), ch);
    return n;
}

void mse_host_dac_report(MseHost *h, int gen, int64_t position, int64_t written, int pending, int64_t buffer_us)
{
    if (!h)
        return;
    pthread_mutex_lock(&h->mu);
    if (buffer_us > 0)
        h->dac_buffer_s = (double)buffer_us / 1e6;
    if ((unsigned)gen == h->ring_gen) {
        h->dac_written = written;
        h->dac_pending = pending;
    }
    if ((unsigned)gen == h->ring_gen && position >= 0) {
        h->dac_pos = position;
        dac_cp_fold(h, position);
        if (h->aq_started) {
            h->dac_tick_ns = now_ns();
            if (position > 0)
                h->dac_live = 1;
        }
    }
    pthread_mutex_unlock(&h->mu);
}

void mse_host_dac_session(MseHost *h, int session)
{
    if (h)
        atomic_store(&h->dac_session, session);
}
#endif

int64_t mse_host_samples_played(MseHost *h)
{
    int64_t n;
    if (!h)
        return 0;
    pthread_mutex_lock(&h->mu);
    n = h->samples_pulled;
    pthread_mutex_unlock(&h->mu);
    return n;
}

#define MSE_MIB ((size_t)1024 * 1024)

/* Chromium demuxer_memory_limit: default 12/150, medium 5/80, low 2/30, very-low 1/15. */
static size_t phys_mib(void)
{
#ifdef __APPLE__
    uint64_t mem = 0;
    size_t len = sizeof(mem);
    if (sysctlbyname("hw.memsize", &mem, &len, NULL, 0) != 0 || mem == 0)
        return 8192;
    return (size_t)(mem / MSE_MIB);
#elif defined(_WIN32)
    MEMORYSTATUSEX ms;
    ms.dwLength = sizeof(ms);
    if (!GlobalMemoryStatusEx(&ms) || ms.ullTotalPhys == 0)
        return 4096;
    return (size_t)(ms.ullTotalPhys / MSE_MIB);
#else
    long pages = sysconf(_SC_PHYS_PAGES);
    long psz = sysconf(_SC_PAGE_SIZE);
    if (pages <= 0 || psz <= 0)
        return 4096;
    return (size_t)(((uint64_t)pages * (uint64_t)psz) / MSE_MIB);
#endif
}

static void mse_quota_caps(size_t *audio, size_t *video)
{
    static size_t a, v;
    static int once;
    size_t mib;
    if (once) {
        *audio = a;
        *video = v;
        return;
    }
    mib = phys_mib();
    /* Chrome Android: <=512 Go very-low; IsLowEndDevice (~1GiB) low;
       PartialLowEndModeOn3GbDevices medium; else default. */
    if (mib <= 512) {
        a = 1 * MSE_MIB;
        v = 15 * MSE_MIB;
    } else if (mib <= 1024) {
        a = 2 * MSE_MIB;
        v = 30 * MSE_MIB;
    } else if (mib <= 3072) {
        a = 5 * MSE_MIB;
        v = 80 * MSE_MIB;
    } else {
        a = 12 * MSE_MIB;
        v = 150 * MSE_MIB;
    }
    once = 1;
    mse_critf(MSE_LOG_INFO, "mse quota class ram=%zuMiB audio=%zu video=%zu", mib, a, v);
    *audio = a;
    *video = v;
}

int mse_host_prepare_append(MseHost *h, int audio, int n)
{
    size_t cap, have, qa, qv;
    double t;
    if (!h)
        return 0;
    if (n <= 0)
        return 1;
    mse_quota_caps(&qa, &qv);
    cap = audio ? qa : qv;
    have = mse_av_leftover_bytes(h->av, audio);
    if (have + (size_t)n <= cap)
        return 1;
    t = mse_host_current_time(h);
    hold_at_playhead(h);
    mse_av_evict_before(h->av, audio, t - 1.0, have + (size_t)n - cap);
    have = mse_av_leftover_bytes(h->av, audio);
    if (have + (size_t)n <= cap)
        return 1;
    mse_av_evict_ahead(h->av, audio, t + 1.0, have + (size_t)n - cap);
    have = mse_av_leftover_bytes(h->av, audio);
    mse_logf("mse gc ahead a=%d have=%zu need=%d cap=%zu clock=%.3f", audio, have, n, cap, t);
    if (have + (size_t)n <= cap)
        return 1;
    mse_critf(MSE_LOG_WARN, "mse quota a=%d have=%zu need=%d cap=%zu clock=%.3f", audio, have, n,
              cap, t);
    return 0;
}

int mse_host_append(MseHost *h, int audio, const uint8_t *p, int n, double ts_off, double win_start, double win_end)
{
    MseAvIngest r;
    if (!h || !p)
        return 0;
    if (n <= 0)
        return 1;
    r = mse_av_append(h->av, audio, p, n, ts_off, win_start, win_end);
    mse_logf(
            "mse append a=%d bytes=%d n=%d init=%d first=%.3f last=%.3f tsOff=%.3f win=%.3g..%.3g clock=%.3f",
            audio, n, r.n, r.is_init, r.first, r.last, ts_off, win_start, win_end,
            mse_host_current_time(h));
    return r.n >= 0 ? 1 : 0;
}

void mse_host_remove(MseHost *h, int audio, double start, double end)
{
    double pairs[2];
    if (!h)
        return;
    hold_at_playhead(h);
    mse_av_remove(h->av, audio, start, end);
    /* Audio-only (or a full video wipe) — stop showing the last decoded frame. */
    if (!audio && mse_av_buffered(h->av, 0, pairs, 1) <= 0) {
        uint8_t *black = NULL;
        int w = 0, ht = 0;
        pthread_mutex_lock(&h->frame_mu);
        if (h->last_bgra && h->last_w > 0 && h->last_h > 0 &&
            (uint64_t)h->last_w * (uint64_t)h->last_h <= (uint64_t)(INT_MAX / 4)) {
            memset(h->last_bgra, 0, (size_t)h->last_w * (size_t)h->last_h * 4);
            black = h->last_bgra;
            w = h->last_w;
            ht = h->last_h;
            h->frame_gen++;
        }
        pthread_mutex_unlock(&h->frame_mu);
        if (black) {
            if (h->on_frame)
                h->on_frame(h->user, w, ht, black);
        }
        pthread_mutex_lock(&h->frame_mu);
        h->last_bgra = NULL;
        h->last_w = 0;
        h->last_h = 0;
        pthread_mutex_unlock(&h->frame_mu);
        pthread_mutex_lock(&h->mu);
        h->vw = 0;
        h->vh = 0;
        pthread_mutex_unlock(&h->mu);
    }
}

void mse_host_abort(MseHost *h, int audio)
{
    if (h)
        mse_av_abort(h->av, audio);
}

void mse_host_change_type(MseHost *h, int audio)
{
    if (h)
        mse_av_change_type(h->av, audio);
}

static void show_black(MseHost *h)
{
    uint8_t *black = NULL;
    int w = 0, ht = 0;
    if (!h)
        return;
    pthread_mutex_lock(&h->frame_mu);
    if (!h->last_bgra || h->last_w <= 0 || h->last_h <= 0) {
        pthread_mutex_unlock(&h->frame_mu);
        return;
    }
    memset(h->last_bgra, 0, (size_t)h->last_w * (size_t)h->last_h * 4);
    w = h->last_w;
    ht = h->last_h;
    black = h->last_bgra;
    if (black)
        h->frame_gen++;
    pthread_mutex_unlock(&h->frame_mu);
    if (!black)
        return;
    if (h->on_frame)
        h->on_frame(h->user, w, ht, black);
    pthread_mutex_lock(&h->mu);
    h->vw = w;
    h->vh = ht;
    pthread_mutex_unlock(&h->mu);
}

void mse_host_rest_video(MseHost *h, int on)
{
    double clock = 0;
    if (!h)
        return;
    clock = host_clock(h);
    mse_av_rest_video(h->av, on, clock);
    pthread_mutex_lock(&h->mu);
    pthread_cond_broadcast(&h->cv);
    pthread_mutex_unlock(&h->mu);
    if (on)
        show_black(h);
}

int mse_host_buffered(MseHost *h, int audio, double *pairs, int max_pairs)
{
    if (!h)
        return 0;
    return mse_av_buffered(h->av, audio, pairs, max_pairs);
}

void mse_host_set_volume(MseHost *h, float vol)
{
    if (h && h->fx)
        audio_fx_set_volume(h->fx, vol);
}

float mse_host_playback_rate(MseHost *h)
{
    if (!h)
        return 1.f;
    return h->speed > 0.f ? h->speed : 1.f;
}

void mse_host_set_speed(MseHost *h, float speed, float tempo)
{
    float old;
    if (!h)
        return;
    if (speed < 0.25f)
        speed = 0.25f;
    if (speed > 4.f)
        speed = 4.f;
    if (tempo < 0.25f)
        tempo = 0.25f;
    if (tempo > 4.f)
        tempo = 4.f;
    pthread_mutex_lock(&h->mu);
    old = h->speed > 0.f ? h->speed : 1.f;
    if (old == speed) {
        if (h->fx)
            audio_fx_set_speed(h->fx, tempo);
        pthread_mutex_unlock(&h->mu);
        return;
    }
    h->speed = speed;
#ifdef MSE_DAC
    /* What is still queued (ring + device) was stretched at the old rate and plays at it. */
    {
        int64_t edge = h->dac_polled + (h->ch > 0 ? h->ring_n / frame_bytes(h) : 0);
        int64_t audible = h->dac_pos;
#ifdef MSE_AQ
        audible -= aq_latency_frames(h);
#endif
        if (edge > audible && edge > h->dac_base) {
            if (h->dac_cp_n < DAC_CHECKPOINTS) {
                h->dac_cp_edge[h->dac_cp_n] = edge;
                h->dac_cp_speed[h->dac_cp_n] = (double)old;
                h->dac_cp_n++;
            } else {
                /* Full: widen the last span to the new edge at its frame-weighted speed, so the
                   media time across it stays exact. */
                int last = DAC_CHECKPOINTS - 1;
                int64_t from = h->dac_cp_edge[last - 1];
                double kept = (double)(h->dac_cp_edge[last] - from);
                double added = (double)(edge - h->dac_cp_edge[last]);
                if (kept < 0)
                    kept = 0;
                if (kept + added > 0)
                    h->dac_cp_speed[last] = (kept * h->dac_cp_speed[last] + added * (double)old) / (kept + added);
                h->dac_cp_edge[last] = edge;
            }
        }
    }
#else
    if (h->origin_ns > 0) {
        double n = now_ns();
        h->origin_ns = n - (n - h->origin_ns) * (double)old / (double)speed;
    }
#endif
    if (h->fx)
        audio_fx_set_speed(h->fx, tempo);
    pthread_mutex_unlock(&h->mu);
}

void mse_host_set_pitch(MseHost *h, float pitch)
{
    if (h && h->fx)
        audio_fx_set_pitch(h->fx, pitch);
}

void mse_host_set_eq(MseHost *h, const float bands_db[10])
{
    if (h && h->fx)
        audio_fx_set_eq(h->fx, bands_db);
}

void mse_host_set_bass(MseHost *h, float db)
{
    if (h && h->fx)
        audio_fx_set_bass(h->fx, db);
}

void mse_host_set_preamp(MseHost *h, float db)
{
    if (h && h->fx)
        audio_fx_set_preamp(h->fx, db);
}

void mse_host_set_virtualizer(MseHost *h, float width)
{
    if (h && h->fx)
        audio_fx_set_virtualizer(h->fx, width);
}

/* Frames colored at the DAC pull but still queued ahead of the playhead (same model as the clock). */
static int vis_delay_frames(MseHost *h)
{
    int delay = 0;
#ifdef MSE_DAC
    pthread_mutex_lock(&h->mu);
#ifdef __ANDROID__
    if (h->aq_started && h->dac_written > h->dac_pos)
        delay = (int)(h->dac_written - h->dac_pos);
#else
    if (h->aq_started && h->rate > 0 && h->aq_buf_bytes > 0) {
        int one = h->aq_buf_bytes / frame_bytes(h);
        int nbufs = 4;
        int extra = 0;
        if (h->dac_tick_ns > 0)
            extra = (int)((now_ns() - h->dac_tick_ns) / 1e9 * h->rate);
        if (extra > one)
            extra = one;
        delay = one * nbufs - (extra > 0 ? extra : 0);
    }
#endif
    pthread_mutex_unlock(&h->mu);
#endif
    return delay;
}

int mse_host_pcm_levels(MseHost *h, float *out, int n)
{
    if (!h || !h->fx)
        return 0;
    return audio_fx_levels(h->fx, out, n, vis_delay_frames(h));
}

void mse_ios_log(const char *msg)
{
    if (!msg)
        return;
    mse_critf(MSE_LOG_INFO, "%s", msg);
#ifdef __APPLE__
    os_log_with_type(os_log_create("com.ymusic.mse", "v"), OS_LOG_TYPE_INFO, "%{public}s", msg);
#endif
}

#ifdef __APPLE__
void mse_ios_park_video(void)
{
    if (!g_ios_host || !g_ios_host->av)
        return;
    mse_ios_log("mse-v park");
    mse_av_park_video(g_ios_host->av);
}

void mse_ios_recover_playback(void)
{
    void *layer;
    double clock;
    if (!g_ios_host || !g_ios_host->av)
        return;
    layer = g_ios_host->window;
    if (mse_av_video_resting(g_ios_host->av)) {
        mse_ios_log("mse-v recover-skip audio-only rest");
        return;
    }
    /* Enqueue still succeeds on a frozen background layer, so picture-ok is
       true after a failed PiP leave. Parked sessions must unpark anyway. */
    if (!mse_av_video_parked(g_ios_host->av) && mse_vt_picture_ok(layer)) {
        mse_ios_log("mse-v recover-skip picture-ok");
        return;
    }
    mse_ios_log("mse-v recover bg-unpark");
    clock = host_clock(g_ios_host);
    mse_av_recover_display(g_ios_host->av, clock);
}
#endif
