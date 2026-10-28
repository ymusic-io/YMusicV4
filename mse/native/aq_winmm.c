#include "aq_winmm.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <mmsystem.h>

#include "mse_ffmpeg.h"

#define AQW_MAX_BUFS 16
/* Bounds the wait for the driver to hand headers back after a reset, and to confirm a close. */
#define AQW_DEVICE_WAIT_MS 2000

typedef struct {
    AudioQueueBuffer pub; /* first: an AudioQueueBufferRef points at it */
    int idx;
} AqwBuf;

struct OpaqueAudioQueue {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    /* Orders the worker's waveOutWrite against AudioQueueFlush's reset. Taken before mu, never under it. */
    pthread_mutex_t dev_mu;
    pthread_t th;
    HWAVEOUT hwo;
    AudioQueueOutputCallback cb;
    void *user;
    int rate;
    int ch;
    int frame_bytes;

    /* User-allocated buffers; each gets a parallel WAVEHDR prepared once. */
    AqwBuf *bufs[AQW_MAX_BUFS];
    WAVEHDR hdrs[AQW_MAX_BUFS];
    int prepared[AQW_MAX_BUFS];
    int nbufs;

    /* Enqueued, waiting to go into waveOutWrite. */
    AudioQueueBufferRef fifo[AQW_MAX_BUFS];
    int fifo_r;
    int fifo_n;

    /* Returned by WOM_DONE, waiting to go into the user cb. */
    AudioQueueBufferRef done[AQW_MAX_BUFS];
    int done_r;
    int done_n;

    /* Headers handed to waveOutWrite and not yet back (WOM_DONE). */
    int inflight;
    /* WOM_CLOSE seen since the last device_close began. */
    int closed;
    /* Bumped by AudioQueueFlush: a buffer popped before it must not reach the device. */
    unsigned flush_gen;

    int running;
    int quit;
    float vol;
    float vol_target;
    float ramp_s;
    int ramp_left;
};

static void sleep_s(double s)
{
    struct timespec ts;
    if (s <= 0)
        return;
    ts.tv_sec = (time_t)s;
    ts.tv_nsec = (long)((s - (double)ts.tv_sec) * 1e9);
    nanosleep(&ts, NULL);
}

/* q->mu held. Every buffer is in exactly one of fifo, the device, done or the user callback. */
static void done_push(AudioQueueRef q, AudioQueueBufferRef buf)
{
    q->done[(q->done_r + q->done_n) % AQW_MAX_BUFS] = buf;
    q->done_n++;
}

/* q->mu held. */
static void apply_gain(AudioQueueRef q, int16_t *s, int frames)
{
    int i, c;
    if (q->ramp_left <= 0 && q->vol >= 0.9999f)
        return;
    for (i = 0; i < frames; i++) {
        if (q->ramp_left > 0) {
            q->vol += (q->vol_target - q->vol) / (float)q->ramp_left;
            q->ramp_left--;
        }
        for (c = 0; c < q->ch; c++) {
            float v = (float)s[i * q->ch + c] * q->vol;
            s[i * q->ch + c] = (int16_t)(v > 32767.f ? 32767.f : (v < -32768.f ? -32768.f : v));
        }
    }
}

/* waveOutProc runs on an OS audio-thread. Per MSDN it must NOT call any waveOut* function;
   stash the buffer pointer and wake the worker, which does the user cb (and the next write). */
static void CALLBACK wave_proc(HWAVEOUT hwo, UINT msg, DWORD_PTR instance, DWORD_PTR p1, DWORD_PTR p2)
{
    AudioQueueRef q = (AudioQueueRef)instance;
    (void)hwo;
    (void)p2;
    if (msg != WOM_DONE && msg != WOM_CLOSE)
        return;
    pthread_mutex_lock(&q->mu);
    if (msg == WOM_CLOSE) {
        q->closed = 1;
    } else {
        q->inflight--;
        done_push(q, (AudioQueueBufferRef)((WAVEHDR *)p1)->dwUser);
    }
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
}

static void deadline_in(struct timespec *ts, int ms)
{
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_sec += ms / 1000;
    ts->tv_nsec += (long)(ms % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec++;
        ts->tv_nsec -= 1000000000L;
    }
}

/* No lock held, worker stopped. Takes every header back and closes the device, waiting until the
   driver is done with q. -1: it was not; q must then never be freed. */
static int device_close(AudioQueueRef q)
{
    struct timespec dl;
    MMRESULT mr;
    int i, left;
    mr = waveOutReset(q->hwo);
    if (mr != MMSYSERR_NOERROR)
        mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutReset failed (%u)", (unsigned)mr);
    pthread_mutex_lock(&q->mu);
    deadline_in(&dl, AQW_DEVICE_WAIT_MS);
    while (q->inflight > 0 && pthread_cond_timedwait(&q->cv, &q->mu, &dl) == 0)
        ;
    left = q->inflight;
    q->closed = 0;
    pthread_mutex_unlock(&q->mu);
    if (left > 0) {
        mse_critf(MSE_LOG_ERROR, "aq-winmm: %d buffers still at the device after reset", left);
        return -1;
    }
    for (i = 0; i < q->nbufs; i++) {
        if (!q->prepared[i])
            continue;
        mr = waveOutUnprepareHeader(q->hwo, &q->hdrs[i], sizeof(q->hdrs[i]));
        if (mr != MMSYSERR_NOERROR)
            mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutUnprepareHeader failed (%u)", (unsigned)mr);
        q->prepared[i] = 0;
    }
    mr = waveOutClose(q->hwo);
    if (mr != MMSYSERR_NOERROR) {
        mse_critf(MSE_LOG_ERROR, "aq-winmm: waveOutClose failed (%u)", (unsigned)mr);
        return -1;
    }
    pthread_mutex_lock(&q->mu);
    deadline_in(&dl, AQW_DEVICE_WAIT_MS);
    while (!q->closed && pthread_cond_timedwait(&q->cv, &q->mu, &dl) == 0)
        ;
    left = !q->closed;
    pthread_mutex_unlock(&q->mu);
    if (left) {
        mse_critf(MSE_LOG_ERROR, "aq-winmm: no WOM_CLOSE after waveOutClose");
        return -1;
    }
    return 0;
}

static void *aqw_loop(void *arg)
{
    AudioQueueRef q = (AudioQueueRef)arg;
    pthread_mutex_lock(&q->mu);
    for (;;) {
        AudioQueueBufferRef write_buf = NULL;
        AudioQueueBufferRef done_buf = NULL;
        MMRESULT mr = MMSYSERR_NOERROR;
        unsigned gen;
        int idx, size, stale;
        WAVEHDR *hdr;
        /* Drain callbacks first — frees up buffers to re-enqueue. */
        while (!q->quit && q->done_n == 0 && (!q->running || q->fifo_n == 0))
            pthread_cond_wait(&q->cv, &q->mu);
        if (q->quit)
            break;
        if (q->done_n > 0) {
            done_buf = q->done[q->done_r];
            q->done_r = (q->done_r + 1) % AQW_MAX_BUFS;
            q->done_n--;
            pthread_mutex_unlock(&q->mu);
            q->cb(q->user, q, done_buf);
            pthread_mutex_lock(&q->mu);
            continue;
        }
        /* Pull the next pending buffer and hand it to waveOut. */
        write_buf = q->fifo[q->fifo_r];
        q->fifo_r = (q->fifo_r + 1) % AQW_MAX_BUFS;
        q->fifo_n--;
        idx = ((AqwBuf *)write_buf)->idx;
        size = (int)write_buf->mAudioDataByteSize;
        if (size > (int)write_buf->mAudioDataBytesCapacity)
            size = (int)write_buf->mAudioDataBytesCapacity;
        size -= size % q->frame_bytes;
        if (size <= 0) {
            done_push(q, write_buf);
            continue;
        }
        gen = q->flush_gen;
        pthread_mutex_unlock(&q->mu);
        /* The flush_gen check and the write are atomic against a flush's reset under dev_mu. */
        pthread_mutex_lock(&q->dev_mu);
        pthread_mutex_lock(&q->mu);
        stale = gen != q->flush_gen;
        if (!stale) {
            apply_gain(q, (int16_t *)write_buf->mAudioData, size / q->frame_bytes);
            q->inflight++;
        }
        pthread_mutex_unlock(&q->mu);
        if (!stale) {
            hdr = &q->hdrs[idx];
            hdr->lpData = (LPSTR)write_buf->mAudioData;
            hdr->dwBufferLength = (DWORD)size;
            hdr->dwBytesRecorded = 0;
            hdr->dwUser = (DWORD_PTR)write_buf;
            hdr->dwLoops = 0;
            /* dwFlags untouched: without WHDR_PREPARED every waveOutWrite fails WAVERR_UNPREPARED. */
            if (!q->prepared[idx]) {
                if (waveOutPrepareHeader(q->hwo, hdr, sizeof(*hdr)) == MMSYSERR_NOERROR)
                    q->prepared[idx] = 1;
            }
            mr = waveOutWrite(q->hwo, hdr, sizeof(*hdr));
        }
        pthread_mutex_unlock(&q->dev_mu);
        pthread_mutex_lock(&q->mu);
        if (stale) {
            done_push(q, write_buf);
            continue;
        }
        if (mr != MMSYSERR_NOERROR) {
            q->inflight--;
            mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutWrite failed (%u) size=%d", (unsigned)mr, size);
            /* Device went away mid-playback. Loop the buffer back so the user keeps draining. */
            done_push(q, write_buf);
            pthread_cond_broadcast(&q->cv);
            pthread_mutex_unlock(&q->mu);
            sleep_s((double)size / (double)(q->frame_bytes * q->rate));
            pthread_mutex_lock(&q->mu);
        }
    }
    pthread_mutex_unlock(&q->mu);
    return NULL;
}

OSStatus AudioQueueNewOutput(const AudioStreamBasicDescription *fmt, AudioQueueOutputCallback cb, void *user,
                             void *run_loop, const void *run_loop_mode, UInt32 flags, AudioQueueRef *out)
{
    AudioQueueRef q;
    WAVEFORMATEX wfx;
    MMRESULT mr;
    (void)run_loop;
    (void)run_loop_mode;
    (void)flags;
    *out = NULL;
    if (!fmt || !cb || fmt->mBitsPerChannel != 16 || fmt->mChannelsPerFrame < 1 || fmt->mSampleRate <= 0)
        return -50;
    q = (AudioQueueRef)calloc(1, sizeof(*q));
    if (!q)
        return -108;
    q->cb = cb;
    q->user = user;
    q->rate = (int)fmt->mSampleRate;
    q->ch = (int)fmt->mChannelsPerFrame;
    q->frame_bytes = q->ch * 2;
    q->vol = q->vol_target = 1.f;
    memset(&wfx, 0, sizeof(wfx));
    wfx.wFormatTag = WAVE_FORMAT_PCM;
    wfx.nChannels = (WORD)q->ch;
    wfx.nSamplesPerSec = (DWORD)q->rate;
    wfx.wBitsPerSample = 16;
    wfx.nBlockAlign = (WORD)(q->ch * 2);
    wfx.nAvgBytesPerSec = (DWORD)q->rate * (DWORD)(q->ch * 2);
    wfx.cbSize = 0;
    pthread_mutex_init(&q->mu, NULL);
    pthread_mutex_init(&q->dev_mu, NULL);
    pthread_cond_init(&q->cv, NULL);
    mr = waveOutOpen(&q->hwo, WAVE_MAPPER, &wfx, (DWORD_PTR)wave_proc, (DWORD_PTR)q, CALLBACK_FUNCTION);
    if (mr != MMSYSERR_NOERROR) {
        mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutOpen failed (%u)", (unsigned)mr);
        pthread_cond_destroy(&q->cv);
        pthread_mutex_destroy(&q->dev_mu);
        pthread_mutex_destroy(&q->mu);
        free(q);
        return -1;
    }
    mse_logf("aq-winmm: waveOutOpen OK rate=%d ch=%d", q->rate, q->ch);
    if (pthread_create(&q->th, NULL, aqw_loop, q) != 0) {
        if (device_close(q) == 0) {
            pthread_cond_destroy(&q->cv);
            pthread_mutex_destroy(&q->dev_mu);
            pthread_mutex_destroy(&q->mu);
            free(q);
        }
        return -1;
    }
    *out = q;
    return 0;
}

OSStatus AudioQueueAllocateBuffer(AudioQueueRef q, UInt32 bytes, AudioQueueBufferRef *out)
{
    AqwBuf *buf;
    *out = NULL;
    pthread_mutex_lock(&q->mu);
    if (q->nbufs >= AQW_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    buf = (AqwBuf *)calloc(1, sizeof(*buf) + bytes);
    if (!buf) {
        pthread_mutex_unlock(&q->mu);
        return -108;
    }
    buf->pub.mAudioData = buf + 1;
    buf->pub.mAudioDataBytesCapacity = bytes;
    buf->idx = q->nbufs;
    q->bufs[q->nbufs++] = buf;
    pthread_mutex_unlock(&q->mu);
    *out = &buf->pub;
    return 0;
}

OSStatus AudioQueueEnqueueBuffer(AudioQueueRef q, AudioQueueBufferRef buf, UInt32 n_packet_descs,
                                 const void *packet_descs)
{
    (void)n_packet_descs;
    (void)packet_descs;
    pthread_mutex_lock(&q->mu);
    if (q->fifo_n >= AQW_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    q->fifo[(q->fifo_r + q->fifo_n) % AQW_MAX_BUFS] = buf;
    q->fifo_n++;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    return 0;
}

OSStatus AudioQueueStart(AudioQueueRef q, const void *start_time)
{
    (void)start_time;
    pthread_mutex_lock(&q->mu);
    q->running = 1;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    waveOutRestart(q->hwo);
    return 0;
}

OSStatus AudioQueuePause(AudioQueueRef q)
{
    pthread_mutex_lock(&q->mu);
    q->running = 0;
    pthread_mutex_unlock(&q->mu);
    waveOutPause(q->hwo);
    return 0;
}

OSStatus AudioQueueFlush(AudioQueueRef q)
{
    MMRESULT mr;
    int i;
    pthread_mutex_lock(&q->mu);
    q->flush_gen++;
    for (i = 0; i < q->fifo_n; i++) {
        AudioQueueBufferRef buf = q->fifo[(q->fifo_r + i) % AQW_MAX_BUFS];
        memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
    }
    pthread_mutex_unlock(&q->mu);
    /* waveOutReset returns every in-flight header via WOM_DONE, which the worker folds into done. */
    pthread_mutex_lock(&q->dev_mu);
    mr = waveOutReset(q->hwo);
    pthread_mutex_unlock(&q->dev_mu);
    if (mr != MMSYSERR_NOERROR)
        mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutReset failed (%u)", (unsigned)mr);
    return 0;
}

OSStatus AudioQueueStop(AudioQueueRef q, bool immediate)
{
    if (immediate)
        AudioQueueFlush(q);
    return AudioQueuePause(q);
}

OSStatus AudioQueueDispose(AudioQueueRef q, bool immediate)
{
    int i;
    (void)immediate;
    if (!q)
        return 0;
    /* Worker first: a reset with it running would hand the headers back to the user, who re-enqueues. */
    pthread_mutex_lock(&q->mu);
    q->quit = 1;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    pthread_join(q->th, NULL);
    if (device_close(q) != 0) {
        mse_critf(MSE_LOG_ERROR, "aq-winmm: queue leaked, its device may still call back");
        return -1;
    }
    for (i = 0; i < q->nbufs; i++)
        free(q->bufs[i]);
    pthread_cond_destroy(&q->cv);
    pthread_mutex_destroy(&q->dev_mu);
    pthread_mutex_destroy(&q->mu);
    free(q);
    return 0;
}

OSStatus AudioQueueSetParameter(AudioQueueRef q, AudioQueueParameterID id, AudioQueueParameterValue v)
{
    pthread_mutex_lock(&q->mu);
    if (id == kAudioQueueParam_VolumeRampTime) {
        q->ramp_s = v > 0 ? v : 0;
    } else if (id == kAudioQueueParam_Volume) {
        q->vol_target = v < 0 ? 0 : (v > 1 ? 1 : v);
        q->ramp_left = (int)(q->ramp_s * (float)q->rate);
        if (q->ramp_left <= 0)
            q->vol = q->vol_target;
    }
    pthread_mutex_unlock(&q->mu);
    return 0;
}

dispatch_time_t dispatch_time(dispatch_time_t when, int64_t delta_ns)
{
    return when + (dispatch_time_t)(delta_ns > 0 ? delta_ns : 0);
}

dispatch_queue_t dispatch_get_global_queue(long identifier, unsigned long flags)
{
    (void)identifier;
    (void)flags;
    return NULL;
}

typedef struct {
    dispatch_time_t delay_ns;
    void *ctx;
    dispatch_function_t fn;
} AfterJob;

static void *after_loop(void *arg)
{
    AfterJob job = *(AfterJob *)arg;
    free(arg);
    sleep_s((double)job.delay_ns / 1e9);
    job.fn(job.ctx);
    return NULL;
}

void dispatch_after_f(dispatch_time_t when, dispatch_queue_t queue, void *ctx, dispatch_function_t fn)
{
    pthread_t th;
    pthread_attr_t attr;
    AfterJob *job = (AfterJob *)malloc(sizeof(AfterJob));
    (void)queue;
    if (!job) {
        fn(ctx);
        return;
    }
    job->delay_ns = when;
    job->ctx = ctx;
    job->fn = fn;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &attr, after_loop, job) != 0) {
        free(job);
        fn(ctx);
    }
    pthread_attr_destroy(&attr);
}
