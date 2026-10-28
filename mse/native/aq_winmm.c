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

struct OpaqueAudioQueue {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    pthread_t th;
    HWAVEOUT hwo;
    AudioQueueOutputCallback cb;
    void *user;
    int rate;
    int ch;
    int frame_bytes;

    /* User-allocated buffers; each gets a parallel WAVEHDR prepared once. */
    AudioQueueBufferRef bufs[AQW_MAX_BUFS];
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

static int buf_index(AudioQueueRef q, AudioQueueBufferRef buf)
{
    int i;
    for (i = 0; i < q->nbufs; i++)
        if (q->bufs[i] == buf)
            return i;
    return -1;
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
    WAVEHDR *hdr;
    AudioQueueBufferRef buf;
    (void)hwo;
    (void)p2;
    if (msg != WOM_DONE)
        return;
    hdr = (WAVEHDR *)p1;
    buf = (AudioQueueBufferRef)hdr->dwUser;
    pthread_mutex_lock(&q->mu);
    if (q->done_n < AQW_MAX_BUFS) {
        q->done[(q->done_r + q->done_n) % AQW_MAX_BUFS] = buf;
        q->done_n++;
        pthread_cond_broadcast(&q->cv);
    }
    pthread_mutex_unlock(&q->mu);
}

static void *aqw_loop(void *arg)
{
    AudioQueueRef q = (AudioQueueRef)arg;
    pthread_mutex_lock(&q->mu);
    for (;;) {
        AudioQueueBufferRef write_buf = NULL;
        AudioQueueBufferRef done_buf = NULL;
        MMRESULT mr;
        int idx, size;
        WAVEHDR *hdr;
        /* Drain callbacks first — frees up buffers to re-enqueue. */
        while (!q->quit && q->done_n == 0 && (!q->running || q->fifo_n == 0 || !q->hwo))
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
        idx = buf_index(q, write_buf);
        if (idx < 0) {
            /* Not one of ours — drop it. */
            continue;
        }
        size = (int)write_buf->mAudioDataByteSize;
        if (size > (int)write_buf->mAudioDataBytesCapacity)
            size = (int)write_buf->mAudioDataBytesCapacity;
        size -= size % q->frame_bytes;
        if (size <= 0) {
            /* Nothing to play, loop it back to the user. */
            if (q->done_n < AQW_MAX_BUFS) {
                q->done[(q->done_r + q->done_n) % AQW_MAX_BUFS] = write_buf;
                q->done_n++;
            }
            continue;
        }
        apply_gain(q, (int16_t *)write_buf->mAudioData, size / q->frame_bytes);
        hdr = &q->hdrs[idx];
        hdr->lpData = (LPSTR)write_buf->mAudioData;
        hdr->dwBufferLength = (DWORD)size;
        hdr->dwBytesRecorded = 0;
        hdr->dwUser = (DWORD_PTR)write_buf;
        hdr->dwLoops = 0;
        /* Preserve WHDR_PREPARED across reuse — zeroing dwFlags here (as earlier revs did)
           strips the bit set by waveOutPrepareHeader, and the next waveOutWrite returns
           WAVERR_UNPREPARED (34) for every buffer. The result is total silence while the
           ring keeps draining — audio clock still advances via samples_played counting
           the bytes we think we handed to the device. */
        pthread_mutex_unlock(&q->mu);
        if (!q->prepared[idx]) {
            if (waveOutPrepareHeader(q->hwo, hdr, sizeof(*hdr)) == MMSYSERR_NOERROR)
                q->prepared[idx] = 1;
        }
        mr = waveOutWrite(q->hwo, hdr, sizeof(*hdr));
        pthread_mutex_lock(&q->mu);
        if (mr != MMSYSERR_NOERROR) {
            mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutWrite failed (%u) size=%d", (unsigned)mr, size);
            /* Device went away mid-playback. Loop the buffer back so the user keeps draining. */
            if (q->done_n < AQW_MAX_BUFS) {
                q->done[(q->done_r + q->done_n) % AQW_MAX_BUFS] = write_buf;
                q->done_n++;
                pthread_cond_broadcast(&q->cv);
            }
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
    mr = waveOutOpen(&q->hwo, WAVE_MAPPER, &wfx, (DWORD_PTR)wave_proc, (DWORD_PTR)q, CALLBACK_FUNCTION);
    if (mr != MMSYSERR_NOERROR) {
        mse_critf(MSE_LOG_WARN, "aq-winmm: waveOutOpen failed (%u)", (unsigned)mr);
        free(q);
        return -1;
    }
    mse_logf("aq-winmm: waveOutOpen OK rate=%d ch=%d", q->rate, q->ch);
    pthread_mutex_init(&q->mu, NULL);
    pthread_cond_init(&q->cv, NULL);
    if (pthread_create(&q->th, NULL, aqw_loop, q) != 0) {
        waveOutClose(q->hwo);
        pthread_cond_destroy(&q->cv);
        pthread_mutex_destroy(&q->mu);
        free(q);
        return -1;
    }
    *out = q;
    return 0;
}

OSStatus AudioQueueAllocateBuffer(AudioQueueRef q, UInt32 bytes, AudioQueueBufferRef *out)
{
    AudioQueueBufferRef buf;
    *out = NULL;
    pthread_mutex_lock(&q->mu);
    if (q->nbufs >= AQW_MAX_BUFS) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    buf = (AudioQueueBufferRef)calloc(1, sizeof(*buf) + bytes);
    if (!buf) {
        pthread_mutex_unlock(&q->mu);
        return -108;
    }
    buf->mAudioData = buf + 1;
    buf->mAudioDataBytesCapacity = bytes;
    q->bufs[q->nbufs++] = buf;
    pthread_mutex_unlock(&q->mu);
    *out = buf;
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
    if (q->hwo)
        waveOutRestart(q->hwo);
    return 0;
}

OSStatus AudioQueuePause(AudioQueueRef q)
{
    pthread_mutex_lock(&q->mu);
    q->running = 0;
    pthread_mutex_unlock(&q->mu);
    if (q->hwo)
        waveOutPause(q->hwo);
    return 0;
}

OSStatus AudioQueueFlush(AudioQueueRef q)
{
    int i;
    pthread_mutex_lock(&q->mu);
    for (i = 0; i < q->fifo_n; i++) {
        AudioQueueBufferRef buf = q->fifo[(q->fifo_r + i) % AQW_MAX_BUFS];
        if (buf)
            memset(buf->mAudioData, 0, buf->mAudioDataBytesCapacity);
    }
    pthread_mutex_unlock(&q->mu);
    /* waveOutReset returns every in-flight header via WOM_DONE, which the worker folds into done_fifo. */
    if (q->hwo)
        waveOutReset(q->hwo);
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
    if (q->hwo)
        waveOutReset(q->hwo);
    pthread_mutex_lock(&q->mu);
    q->quit = 1;
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    pthread_join(q->th, NULL);
    if (q->hwo) {
        for (i = 0; i < q->nbufs; i++) {
            if (q->prepared[i])
                waveOutUnprepareHeader(q->hwo, &q->hdrs[i], sizeof(q->hdrs[i]));
        }
        waveOutClose(q->hwo);
    }
    for (i = 0; i < q->nbufs; i++)
        free(q->bufs[i]);
    pthread_cond_destroy(&q->cv);
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
