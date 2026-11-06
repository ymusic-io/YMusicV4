#include "audio_fx.h"

#include <math.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* WSOLA window frames: 20 ms up to 192 kHz. */
#define AFX_WS_MAX 4096
#define AFX_FIR 32
#define AFX_FFT 2048
#define AFX_VIS_CAP 65536 /* power of two; > the deepest sink delay (~1.3s at 48k) */
#define AFX_BASS_HZ 150.f
#define AFX_LIMIT 32112.f /* -0.18 dBFS on the s16 scale */
#define AFX_LIMIT_RELEASE_S 0.08f
#define AFX_LIMIT_ABSORB_DB 6.f
#define AFX_MAKEUP_DB 3.f
#define AFX_GRID 48
#define PI 3.14159265358979323846f

typedef struct {
    float b0, b1, b2, a1, a2;
    float z1[2], z2[2];
} Biquad;

struct AudioFx {
    pthread_mutex_t mu;
    int rate;
    float volume;
    float bands[AFX_BANDS];
    float bass_db;
    float preamp_db;
    float width;
    float speed;
    float pitch;
    Biquad peak[AFX_BANDS];
    Biquad bass;
    float pre;
    float lim_gain;
    float lim_release;
    int color_on;
    float *inbuf;
    int in_n;
    int in_cap;
    double in_frac;
    float *mid;
    int mid_n;
    int mid_cap;
    double mid_frac;
    float *xf;
    int xf_n;
    int xf_cap;
    /* WSOLA state, as indices into the tempo stage's input buffer. */
    int ws_have;
    int ws_target;
    double ws_time;
    int ws_tail_n;
    float ws_tail[2][AFX_WS_MAX / 2];
    float ws_win[AFX_WS_MAX];
    int ws_win_n;
    int path;
    float fir_h[AFX_FIR];
    float fir_z[2][AFX_FIR];
    float fir_cut;
    float vis[AFX_VIS_CAP];
    int vis_w;
    int vis_n;
    float vis_hold[AFX_LEVELS];
};

static const float k_hz[AFX_BANDS] = {
    31.f, 62.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f,
};

static float clampf(float x, float lo, float hi)
{
    if (x < lo)
        return lo;
    if (x > hi)
        return hi;
    return x;
}

static int16_t sat16(float x)
{
    if (x > 32767.f)
        return 32767;
    if (x < -32768.f)
        return -32768;
    return (int16_t)x;
}

static void bq_clear(Biquad *b)
{
    b->z1[0] = b->z1[1] = b->z2[0] = b->z2[1] = 0;
}

/* Designers keep z1/z2: a retune mid-stream must not zero the filter state (that clicks). */
static void bq_peak(Biquad *b, float fs, float f0, float db, float q)
{
    float a, w0, c, s, alpha, b0, b1, b2, a0, a1, a2;
    if (fs < 1.f || f0 >= fs * 0.48f || fabsf(db) < 0.05f) {
        b->b0 = 1;
        b->b1 = b->b2 = b->a1 = b->a2 = 0;
        return;
    }
    a = powf(10.f, db / 40.f);
    w0 = 2.f * PI * f0 / fs;
    c = cosf(w0);
    s = sinf(w0);
    alpha = s / (2.f * q);
    b0 = 1.f + alpha * a;
    b1 = -2.f * c;
    b2 = 1.f - alpha * a;
    a0 = 1.f + alpha / a;
    a1 = -2.f * c;
    a2 = 1.f - alpha / a;
    b->b0 = b0 / a0;
    b->b1 = b1 / a0;
    b->b2 = b2 / a0;
    b->a1 = a1 / a0;
    b->a2 = a2 / a0;
}

static void bq_lowshelf(Biquad *b, float fs, float f0, float db)
{
    float a, w0, c, s, alpha, sq, b0, b1, b2, a0, a1, a2;
    if (fs < 1.f || fabsf(db) < 0.05f) {
        b->b0 = 1;
        b->b1 = b->b2 = b->a1 = b->a2 = 0;
        return;
    }
    a = powf(10.f, db / 40.f);
    w0 = 2.f * PI * f0 / fs;
    c = cosf(w0);
    s = sinf(w0);
    alpha = s / 2.f * sqrtf(2.f);
    sq = 2.f * sqrtf(a) * alpha;
    b0 = a * ((a + 1.f) - (a - 1.f) * c + sq);
    b1 = 2.f * a * ((a - 1.f) - (a + 1.f) * c);
    b2 = a * ((a + 1.f) - (a - 1.f) * c - sq);
    a0 = (a + 1.f) + (a - 1.f) * c + sq;
    a1 = -2.f * ((a - 1.f) + (a + 1.f) * c);
    a2 = (a + 1.f) + (a - 1.f) * c - sq;
    b->b0 = b0 / a0;
    b->b1 = b1 / a0;
    b->b2 = b2 / a0;
    b->a1 = a1 / a0;
    b->a2 = a2 / a0;
}

static float bq_gain_db(const Biquad *b, float w)
{
    float c1 = cosf(w), s1 = sinf(w), c2 = cosf(2.f * w), s2 = sinf(2.f * w);
    float nr = b->b0 + b->b1 * c1 + b->b2 * c2;
    float ni = -(b->b1 * s1 + b->b2 * s2);
    float dr = 1.f + b->a1 * c1 + b->a2 * c2;
    float di = -(b->a1 * s1 + b->a2 * s2);
    return 10.f * log10f((nr * nr + ni * ni) / (dr * dr + di * di) + 1e-20f);
}

static float bq_tick(Biquad *b, int ch, float x)
{
    float y = b->b0 * x + b->z1[ch];
    b->z1[ch] = b->b1 * x - b->a1 * y + b->z2[ch];
    b->z2[ch] = b->b2 * x - b->a2 * y;
    return y;
}

/* ITU-R BS.1770 K-weighting at 48 kHz (pre-filter shelf + RLB high-pass): a loudness-perception weight. */
static const Biquad k_shelf = {1.53512486f, -2.69169619f, 1.19839281f, -1.69065929f, 0.73248077f, {0, 0}, {0, 0}};
static const Biquad k_rlb = {1.f, -2.f, 1.f, -1.99004745f, 0.99007225f, {0, 0}, {0, 0}};

/*
 * Over a log-spaced 20 Hz .. min(20 kHz, 0.45 fs) grid (pink-noise-like music: equal energy per octave),
 * the chain's K-weighted power-average gain (how much louder it sounds) and its largest boost, in dB.
 */
static void chain_gain_db(const AudioFx *fx, float fs, float *loudness, float *max_boost)
{
    int i, k;
    float top = fminf(20000.f, 0.45f * fs);
    double num = 0, den = 0;
    float worst = 0.f;
    for (k = 0; k < AFX_GRID; k++) {
        float f = 20.f * powf(top / 20.f, (float)k / (float)(AFX_GRID - 1));
        float w = 2.f * PI * f / fs;
        float wk = 2.f * PI * f / 48000.f;
        float g = bq_gain_db(&fx->bass, w);
        double weight;
        for (i = 0; i < AFX_BANDS; i++)
            g += bq_gain_db(&fx->peak[i], w);
        if (g > worst)
            worst = g;
        weight = pow(10.0, (bq_gain_db(&k_shelf, wk) + bq_gain_db(&k_rlb, wk)) / 10.0);
        num += weight * pow(10.0, g / 10.0);
        den += weight;
    }
    *loudness = (float)(10.0 * log10(num / den));
    *max_boost = worst;
}

/* Call with fx->mu held (or before the instance is shared). */
static void rebuild(AudioFx *fx)
{
    int i;
    float loudness, max_boost, pre_db;
    float fs = (float)(fx->rate > 0 ? fx->rate : 48000);
    fx->color_on = 0;
    for (i = 0; i < AFX_BANDS; i++) {
        bq_peak(&fx->peak[i], fs, k_hz[i], fx->bands[i], 1.4f);
        if (fabsf(fx->bands[i]) >= 0.05f)
            fx->color_on = 1;
    }
    bq_lowshelf(&fx->bass, fs, AFX_BASS_HZ, fx->bass_db);
    if (fabsf(fx->bass_db) >= 0.05f)
        fx->color_on = 1;
    /* Loudness-matched pre-gain, so a setting reshapes the sound without changing how loud it is; but the
     * limiter never absorbs more than AFX_LIMIT_ABSORB_DB of a big boost, and cuts get only AFX_MAKEUP_DB back. */
    chain_gain_db(fx, fs, &loudness, &max_boost);
    pre_db = fminf(-loudness, AFX_LIMIT_ABSORB_DB - max_boost);
    fx->pre = powf(10.f, (clampf(pre_db, -12.f, AFX_MAKEUP_DB) + fx->preamp_db) / 20.f);
    if (fabsf(fx->preamp_db) >= 0.05f)
        fx->color_on = 1;
    fx->lim_release = 1.f - expf(-1.f / (AFX_LIMIT_RELEASE_S * fs));
    if (fabsf(fx->width - 1.f) >= 0.02f)
        fx->color_on = 1;
    if (fabsf(fx->volume - 1.f) >= 0.001f)
        fx->color_on = 1;
}

AudioFx *audio_fx_create(void)
{
    AudioFx *fx = (AudioFx *)calloc(1, sizeof(AudioFx));
    if (!fx)
        return NULL;
    fx->rate = 48000;
    fx->volume = 1.f;
    fx->width = 1.f;
    fx->speed = 1.f;
    fx->pitch = 1.f;
    fx->lim_gain = 1.f;
    if (pthread_mutex_init(&fx->mu, NULL) != 0) {
        free(fx);
        return NULL;
    }
    rebuild(fx);
    return fx;
}

static void stretch_reset_unlocked(AudioFx *fx)
{
    fx->in_n = 0;
    fx->in_frac = 0;
    fx->mid_n = 0;
    fx->mid_frac = 0;
    fx->xf_n = 0;
    fx->ws_have = 0;
    fx->ws_tail_n = 0;
    fx->path = 0;
    fx->fir_cut = 0;
    memset(fx->fir_z, 0, sizeof(fx->fir_z));
}

void audio_fx_destroy(AudioFx *fx)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    free(fx->inbuf);
    fx->inbuf = NULL;
    free(fx->mid);
    fx->mid = NULL;
    free(fx->xf);
    fx->xf = NULL;
    pthread_mutex_unlock(&fx->mu);
    pthread_mutex_destroy(&fx->mu);
    free(fx);
}

void audio_fx_reset(AudioFx *fx)
{
    int i;
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    stretch_reset_unlocked(fx);
    for (i = 0; i < AFX_BANDS; i++)
        bq_clear(&fx->peak[i]);
    bq_clear(&fx->bass);
    fx->lim_gain = 1.f;
    memset(fx->vis, 0, sizeof(fx->vis));
    memset(fx->vis_hold, 0, sizeof(fx->vis_hold));
    fx->vis_w = 0;
    fx->vis_n = 0;
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_rate(AudioFx *fx, int rate)
{
    int i;
    if (!fx || rate <= 0)
        return;
    pthread_mutex_lock(&fx->mu);
    if (fx->rate == rate) {
        pthread_mutex_unlock(&fx->mu);
        return;
    }
    fx->rate = rate;
    rebuild(fx);
    for (i = 0; i < AFX_BANDS; i++)
        bq_clear(&fx->peak[i]);
    bq_clear(&fx->bass);
    stretch_reset_unlocked(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_volume(AudioFx *fx, float vol)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->volume = clampf(vol, 0.f, 1.f);
    rebuild(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_eq(AudioFx *fx, const float bands_db[AFX_BANDS])
{
    int i;
    if (!fx || !bands_db)
        return;
    pthread_mutex_lock(&fx->mu);
    for (i = 0; i < AFX_BANDS; i++)
        fx->bands[i] = clampf(bands_db[i], -12.f, 12.f);
    rebuild(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_bass(AudioFx *fx, float db)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->bass_db = clampf(db, -12.f, 12.f);
    rebuild(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_virtualizer(AudioFx *fx, float width)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->width = clampf(width, 0.f, 2.f);
    rebuild(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_preamp(AudioFx *fx, float db)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->preamp_db = clampf(db, -12.f, 12.f);
    rebuild(fx);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_speed(AudioFx *fx, float speed)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->speed = clampf(speed, 0.25f, 4.f);
    pthread_mutex_unlock(&fx->mu);
}

void audio_fx_set_pitch(AudioFx *fx, float pitch)
{
    if (!fx)
        return;
    pthread_mutex_lock(&fx->mu);
    fx->pitch = clampf(pitch, 0.5f, 2.f);
    pthread_mutex_unlock(&fx->mu);
}

static void feed_levels(AudioFx *fx, const int16_t *s16, int frames, int nch)
{
    int i;
    pthread_mutex_lock(&fx->mu);
    for (i = 0; i < frames; i++) {
        float l = (float)s16[i * nch];
        float r = nch > 1 ? (float)s16[i * nch + 1] : l;
        fx->vis[fx->vis_w] = 0.5f * (l + r) / 32768.f;
        fx->vis_w = (fx->vis_w + 1) & (AFX_VIS_CAP - 1);
        if (fx->vis_n < AFX_VIS_CAP)
            fx->vis_n++;
    }
    pthread_mutex_unlock(&fx->mu);
}

static void fft_radix2(float *re, float *im, int n)
{
    int i, j, k, m;
    j = 0;
    for (i = 0; i < n; i++) {
        if (i < j) {
            float tr = re[i], ti = im[i];
            re[i] = re[j];
            im[i] = im[j];
            re[j] = tr;
            im[j] = ti;
        }
        m = n >> 1;
        while (m >= 1 && j >= m) {
            j -= m;
            m >>= 1;
        }
        j += m;
    }
    for (m = 2; m <= n; m <<= 1) {
        float wm_re = cosf(-2.f * PI / (float)m);
        float wm_im = sinf(-2.f * PI / (float)m);
        for (k = 0; k < n; k += m) {
            float w_re = 1.f, w_im = 0.f;
            int half = m >> 1;
            for (j = 0; j < half; j++) {
                int u = k + j;
                int t = u + half;
                float tr = w_re * re[t] - w_im * im[t];
                float ti = w_re * im[t] + w_im * re[t];
                float nw_re;
                re[t] = re[u] - tr;
                im[t] = im[u] - ti;
                re[u] += tr;
                im[u] += ti;
                nw_re = w_re * wm_re - w_im * wm_im;
                w_im = w_re * wm_im + w_im * wm_re;
                w_re = nw_re;
            }
        }
    }
}

int audio_fx_levels(AudioFx *fx, float *out, int n, int delay_frames)
{
    float re[AFX_FFT];
    float im[AFX_FFT];
    int i, k, got, end, avail, take, nyq, prev;
    if (!fx || !out || n <= 0)
        return 0;
    got = n < AFX_LEVELS ? n : AFX_LEVELS;
    if (delay_frames < 0)
        delay_frames = 0;
    if (delay_frames > AFX_VIS_CAP - AFX_FFT)
        delay_frames = AFX_VIS_CAP - AFX_FFT;
    memset(re, 0, sizeof(re));
    memset(im, 0, sizeof(im));
    pthread_mutex_lock(&fx->mu);
    end = fx->vis_w - delay_frames;
    avail = fx->vis_n - delay_frames;
    take = avail < AFX_FFT ? avail : AFX_FFT;
    for (i = 0; i < take; i++)
        re[AFX_FFT - take + i] = fx->vis[(end - take + i) & (AFX_VIS_CAP - 1)];
    pthread_mutex_unlock(&fx->mu);
    for (i = 0; i < AFX_FFT; i++)
        re[i] *= 0.5f * (1.f - cosf(2.f * PI * (float)i / (float)(AFX_FFT - 1)));
    fft_radix2(re, im, AFX_FFT);
    nyq = AFX_FFT / 2;
    pthread_mutex_lock(&fx->mu);
    prev = 1;
    for (i = 0; i < got; i++) {
        float t = (float)(i + 1) / (float)got;
        int next = (int)(powf((float)nyq, t) + 0.5f);
        float peak = 0.f;
        float mag;
        if (next <= prev)
            next = prev + 1;
        if (next > nyq)
            next = nyq;
        for (k = prev; k < next; k++) {
            float m = hypotf(re[k], im[k]);
            if (m > peak)
                peak = m;
        }
        prev = next;
        /* Hann sine peak ≈ N/4; *3.2 so loud music reaches 1 easily. */
        mag = peak * (4.f / (float)AFX_FFT) * 3.2f;
        if (mag > 1.f)
            mag = 1.f;
        {
            float a = mag > fx->vis_hold[i] ? 0.72f : 0.14f;
            fx->vis_hold[i] += (mag - fx->vis_hold[i]) * a;
        }
        out[i] = fx->vis_hold[i];
    }
    pthread_mutex_unlock(&fx->mu);
    return got;
}

void audio_fx_color(AudioFx *fx, int16_t *s16, int frames, int ch)
{
    int i, c, nch;
    if (!fx || !s16 || frames <= 0)
        return;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    pthread_mutex_lock(&fx->mu);
    if (fx->color_on) {
        for (i = 0; i < frames; i++) {
            float l, r, mid, side, y, peak, target;
            l = (float)s16[i * nch] * fx->pre;
            r = nch > 1 ? (float)s16[i * nch + 1] * fx->pre : l;
            for (c = 0; c < AFX_BANDS; c++) {
                l = bq_tick(&fx->peak[c], 0, l);
                if (nch > 1)
                    r = bq_tick(&fx->peak[c], 1, r);
            }
            l = bq_tick(&fx->bass, 0, l);
            if (nch > 1)
                r = bq_tick(&fx->bass, 1, r);
            if (nch > 1 && fabsf(fx->width - 1.f) >= 0.02f) {
                mid = 0.5f * (l + r);
                side = 0.5f * (l - r) * fx->width;
                l = mid + side;
                r = mid - side;
            }
            /* Peak limiter: instant attack (never over AFX_LIMIT), smooth release. */
            peak = fmaxf(fabsf(l), nch > 1 ? fabsf(r) : 0.f);
            target = peak > AFX_LIMIT ? AFX_LIMIT / peak : 1.f;
            if (target < fx->lim_gain)
                fx->lim_gain = target;
            else
                fx->lim_gain += (target - fx->lim_gain) * fx->lim_release;
            y = fx->lim_gain * fx->volume;
            s16[i * nch] = sat16(l * y);
            if (nch > 1)
                s16[i * nch + 1] = sat16(r * y);
        }
    }
    pthread_mutex_unlock(&fx->mu);
    feed_levels(fx, s16, frames, nch);
}

static int ensure_buf(float **buf, int *cap, int need, int ch)
{
    float *nbuf;
    if (need <= *cap)
        return 0;
    if (need < 4096)
        need = 4096;
    nbuf = (float *)realloc(*buf, (size_t)need * (size_t)ch * sizeof(float));
    if (!nbuf)
        return -1;
    *buf = nbuf;
    *cap = need;
    return 0;
}

static float lerp_ch(const float *buf, int n, int nch, int c, double src)
{
    int i;
    float f;
    if (n <= 0)
        return 0;
    if (src <= 0.0)
        return buf[c];
    i = (int)src;
    f = (float)(src - (double)i);
    if (i >= n - 1)
        return buf[(n - 1) * nch + c];
    return buf[i * nch + c] * (1.f - f) + buf[(i + 1) * nch + c] * f;
}

/* Catmull-Rom, SoundTouch cubic interpolator. */
static float hermite_ch(const float *buf, int n, int nch, int c, double src)
{
    int i;
    float f, y0, y1, y2, y3, a0, a1, a2;
    if (n <= 3)
        return lerp_ch(buf, n, nch, c, src);
    i = (int)src;
    f = (float)(src - (double)i);
    if (i < 1 || i + 2 >= n)
        return lerp_ch(buf, n, nch, c, src);
    y0 = buf[(i - 1) * nch + c];
    y1 = buf[i * nch + c];
    y2 = buf[(i + 1) * nch + c];
    y3 = buf[(i + 2) * nch + c];
    a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
    a1 = y0 - 2.5f * y1 + 2.f * y2 - 0.5f * y3;
    a2 = -0.5f * y0 + 0.5f * y2;
    return ((a0 * f + a1) * f + a2) * f + y1;
}

static void fir_design(AudioFx *fx, float cutoff)
{
    int i, m;
    float sum = 0.f;
    if (cutoff > 0.49f)
        cutoff = 0.49f;
    if (cutoff < 0.04f)
        cutoff = 0.04f;
    if (fabsf(fx->fir_cut - cutoff) < 0.002f)
        return;
    fx->fir_cut = cutoff;
    m = AFX_FIR / 2;
    for (i = 0; i < AFX_FIR; i++) {
        float x = (float)(i - m);
        float w = 0.54f - 0.46f * cosf(2.f * PI * (float)i / (float)(AFX_FIR - 1));
        float s = (x == 0.f) ? (2.f * cutoff) : (sinf(2.f * PI * cutoff * x) / (PI * x));
        fx->fir_h[i] = s * w;
        sum += fx->fir_h[i];
    }
    if (sum > 1e-12f) {
        for (i = 0; i < AFX_FIR; i++)
            fx->fir_h[i] /= sum;
    }
    memset(fx->fir_z, 0, sizeof(fx->fir_z));
}

static float fir_tick(AudioFx *fx, int c, float x)
{
    int i;
    float y = 0.f;
    memmove(&fx->fir_z[c][1], &fx->fir_z[c][0], (AFX_FIR - 1) * sizeof(float));
    fx->fir_z[c][0] = x;
    for (i = 0; i < AFX_FIR; i++)
        y += fx->fir_h[i] * fx->fir_z[c][i];
    return y;
}

static void consume(float *buf, int *n, int drop, int nch)
{
    if (drop <= 0)
        return;
    if (drop > *n)
        drop = *n;
    *n -= drop;
    if (*n > 0)
        memmove(buf, buf + drop * nch, (size_t)*n * (size_t)nch * sizeof(float));
}

/* 20 ms Hann windows (periodic, so the halves of neighbours sum to 1) and 30 ms of candidates. */
static void ws_params(AudioFx *fx, int *win, int *cand)
{
    int rate = fx->rate < 8000 ? 48000 : fx->rate;
    int w = (rate / 50) & ~1;
    if (w > AFX_WS_MAX)
        w = AFX_WS_MAX;
    *win = w;
    *cand = rate * 3 / 100;
    if (fx->ws_win_n != w) {
        int i;
        for (i = 0; i < w; i++)
            fx->ws_win[i] = 0.5f - 0.5f * cosf(2.f * PI * (float)i / (float)w);
        fx->ws_win_n = w;
    }
}

/* SoundTouch RateTransposer: cubic + FIR cutoff 0.5/max(rate,1). */
static void rate_xfer(AudioFx *fx, float *src, int *src_n, double *frac, float **dst, int *dst_n,
                      int *dst_cap, float rate, int nch, int use_aa)
{
    int drop;
    if (rate < 0.25f)
        rate = 0.25f;
    if (use_aa)
        fir_design(fx, 0.45f / (rate > 1.f ? rate : 1.f));
    while ((int)*frac + 2 < *src_n) {
        int c;
        if (ensure_buf(dst, dst_cap, *dst_n + 8, nch) != 0)
            return;
        for (c = 0; c < nch; c++) {
            float y = hermite_ch(src, *src_n, nch, c, *frac);
            if (use_aa)
                y = fir_tick(fx, c, y);
            (*dst)[*dst_n * nch + c] = y;
        }
        (*dst_n)++;
        *frac += (double)rate;
    }
    drop = (int)*frac;
    if (drop > 0) {
        consume(src, src_n, drop, nch);
        *frac -= (double)drop;
        if (*frac < 0)
            *frac = 0;
    }
}

static int rate_to_s16(AudioFx *fx, float *src, int *src_n, double *frac, float rate, int16_t *out,
                       int out_cap, int nch, int use_aa)
{
    int o = 0, drop;
    if (rate < 0.25f)
        rate = 0.25f;
    if (use_aa)
        fir_design(fx, 0.45f / (rate > 1.f ? rate : 1.f));
    while ((int)*frac + 2 < *src_n && o < out_cap) {
        int c;
        for (c = 0; c < nch; c++) {
            float y = hermite_ch(src, *src_n, nch, c, *frac);
            if (use_aa)
                y = fir_tick(fx, c, y);
            out[o * nch + c] = sat16(y);
        }
        o++;
        *frac += (double)rate;
    }
    drop = (int)*frac;
    if (drop > 0) {
        consume(src, src_n, drop, nch);
        *frac -= (double)drop;
        if (*frac < 0)
            *frac = 0;
    }
    return o;
}

/* The window at src[off] against the target window, as a dot product over the candidate's energy
   (the target's energy is the same for every candidate). Every `step`-th frame. */
static float ws_similarity(const float *src, int off, int tgt, int win, int nch, int step)
{
    float dot = 0.f, energy = 0.f;
    int t, c;
    for (t = 0; t < win; t += step) {
        for (c = 0; c < nch; c++) {
            float x = src[(off + t) * nch + c];
            dot += x * src[(tgt + t) * nch + c];
            energy += x * x;
        }
    }
    return energy < 1e-9f ? 0.f : dot / sqrtf(energy);
}

/* Coarse pass over every 5th candidate at half resolution, then every candidate around the best. */
static int ws_best(const float *src, int search, int cand, int tgt, int win, int nch)
{
    int i, lo, hi, best = search;
    float beste = -1e30f;
    for (i = 0; i < cand; i += 5) {
        float e = ws_similarity(src, search + i, tgt, win, nch, 2);
        if (e > beste) {
            beste = e;
            best = search + i;
        }
    }
    lo = best - 4 < search ? search : best - 4;
    hi = best + 4 > search + cand - 1 ? search + cand - 1 : best + 4;
    beste = -1e30f;
    for (i = lo; i <= hi; i++) {
        float e = ws_similarity(src, i, tgt, win, nch, 1);
        if (e > beste) {
            beste = e;
            best = i;
        }
    }
    return best;
}

/* Chromium's AudioRendererAlgorithm WSOLA: each hop overlap-adds the next window onto the held half of
   the last, every output frame a crossfade. The next window is the last one's natural continuation
   while that lies in the search region around where the input should be, else the candidate most
   like that continuation. Input before both the continuation and the next search is dropped. */
static void wsola(AudioFx *fx, float *src, int *src_n, float tempo, float *dst, int *dst_n,
                  int dst_cap, int nch)
{
    int win, cand, hop, center, t, c;
    ws_params(fx, &win, &cand);
    hop = win / 2;
    center = cand / 2 + hop - 1;
    if (!fx->ws_have) {
        fx->ws_time = center;
        fx->ws_target = -1;
        fx->ws_tail_n = 0;
        fx->ws_have = 1;
    }
    for (;;) {
        int search = (int)(fx->ws_time + 0.5) - center;
        int opt, next, drop;
        if (*dst_n + hop > dst_cap || search + cand + win - 1 > *src_n)
            break;
        if (fx->ws_target < 0)
            opt = 0;
        else if (fx->ws_target >= search && fx->ws_target + win <= search + cand + win - 1)
            opt = fx->ws_target;
        else
            opt = ws_best(src, search, cand, fx->ws_target, win, nch);
        /* Hann halves keep the amplitude of correlated audio; uncorrelated audio (fricatives, noise)
           needs power-complementary halves (their square roots), or it dips 3 dB at every overlap:
           a flutter at the hop rate. The overlap's correlation blends the two. */
        float corr = 0.f;
        if (fx->ws_tail_n) {
            float dot = 0.f, e_tail = 0.f, e_new = 0.f;
            for (t = 0; t < hop; t++) {
                for (c = 0; c < nch; c++) {
                    float a = fx->ws_tail[c][t], b = src[(opt + t) * nch + c];
                    dot += a * b;
                    e_tail += a * a;
                    e_new += b * b;
                }
            }
            if (e_tail > 1e-9f && e_new > 1e-9f)
                corr = clampf(dot / sqrtf(e_tail * e_new), 0.f, 1.f);
        }
        for (t = 0; t < hop; t++) {
            float hann_rise = fx->ws_win[t], hann_fall = fx->ws_win[hop + t];
            float rise = corr * hann_rise + (1.f - corr) * sqrtf(hann_rise);
            float fall = corr * hann_fall + (1.f - corr) * sqrtf(hann_fall);
            for (c = 0; c < nch; c++) {
                float x = src[(opt + t) * nch + c];
                dst[(*dst_n + t) * nch + c] = fx->ws_tail_n ? fx->ws_tail[c][t] * fall + x * rise : x;
            }
        }
        *dst_n += hop;
        for (t = 0; t < hop; t++) {
            for (c = 0; c < nch; c++)
                fx->ws_tail[c][t] = src[(opt + hop + t) * nch + c];
        }
        fx->ws_tail_n = hop;
        fx->ws_target = opt + hop;
        fx->ws_time += (double)hop * (double)tempo;
        next = (int)(fx->ws_time + 0.5) - center;
        drop = next < fx->ws_target ? next : fx->ws_target;
        if (drop > 0) {
            consume(src, src_n, drop, nch);
            fx->ws_target -= drop;
            fx->ws_time -= drop;
        }
    }
}

/* Leaves the tempo stage's input at the last window's natural continuation and forgets the WSOLA
   state: the held half-window is that input itself, so playing on from there repeats and drops
   nothing. */
static void ws_settle(AudioFx *fx, int nch)
{
    float *src = fx->path == 4 ? fx->mid : fx->inbuf;
    int *src_n = fx->path == 4 ? &fx->mid_n : &fx->in_n;
    if (fx->ws_have && fx->ws_target > 0)
        consume(src, src_n, fx->ws_target < *src_n ? fx->ws_target : *src_n, nch);
    fx->ws_have = 0;
    fx->ws_tail_n = 0;
}

static int prepend(float **buf, int *n, int *cap, float *src, int *src_n, int nch)
{
    if (*src_n <= 0)
        return 0;
    if (ensure_buf(buf, cap, *n + *src_n, nch) != 0)
        return -1;
    memmove(*buf + (size_t)*src_n * (size_t)nch, *buf, (size_t)*n * (size_t)nch * sizeof(float));
    memcpy(*buf, src, (size_t)*src_n * (size_t)nch * sizeof(float));
    *n += *src_n;
    *src_n = 0;
    return 0;
}

/* Back to 1x: what the stretcher holds plays out first (in play order, through inbuf), so no
   audio is lost or replayed later and the A/V clock stays exact. */
static void stretch_to_plain(AudioFx *fx, int nch)
{
    int rc = 0;
    ws_settle(fx, nch);
    if (fx->path == 4) {
        rc |= prepend(&fx->inbuf, &fx->in_n, &fx->in_cap, fx->mid, &fx->mid_n, nch);
        rc |= prepend(&fx->inbuf, &fx->in_n, &fx->in_cap, fx->xf, &fx->xf_n, nch);
    } else if (fx->path == 2 || fx->path == 3) {
        rc |= prepend(&fx->inbuf, &fx->in_n, &fx->in_cap, fx->mid, &fx->mid_n, nch);
    }
    if (rc != 0) {
        stretch_reset_unlocked(fx);
        return;
    }
    fx->in_frac = 0;
    fx->mid_frac = 0;
    fx->fir_cut = 0;
    memset(fx->fir_z, 0, sizeof(fx->fir_z));
    fx->path = 0;
}

static int fifo_to_s16(float *src, int *src_n, int16_t *out, int out_cap, int nch)
{
    int n = *src_n < out_cap ? *src_n : out_cap;
    int i, c;
    for (i = 0; i < n; i++) {
        for (c = 0; c < nch; c++)
            out[i * nch + c] = sat16(src[i * nch + c]);
    }
    consume(src, src_n, n, nch);
    return n;
}

int audio_fx_stretch(AudioFx *fx, const int16_t *in, int in_frames, int16_t *out, int out_cap, int ch)
{
    float pitch, speed, tempo;
    int nch, i, c, produced = 0, do_rate, do_tempo, plain;
    if (!fx || !out || out_cap <= 0)
        return 0;
    nch = ch < 1 ? 1 : (ch > 2 ? 2 : ch);
    pthread_mutex_lock(&fx->mu);
    speed = fx->speed;
    pitch = fx->pitch;
    if (pitch < 0.5f)
        pitch = 0.5f;
    if (pitch > 2.f)
        pitch = 2.f;
    /* SoundTouch: _rate = pitch, _tempo = speed/pitch. */
    tempo = speed / pitch;
    do_rate = fabsf(pitch - 1.f) >= 0.005f;
    do_tempo = fabsf(tempo - 1.f) >= 0.005f;
    plain = !do_rate && !do_tempo;
    if (plain && fx->path != 0)
        stretch_to_plain(fx, nch);
    if (plain && fx->in_n == 0) {
        int n = in_frames < out_cap ? in_frames : out_cap;
        if (in && n > 0)
            memcpy(out, in, (size_t)n * (size_t)nch * sizeof(int16_t));
        pthread_mutex_unlock(&fx->mu);
        return n;
    }
    if (in && in_frames > 0) {
        if (ensure_buf(&fx->inbuf, &fx->in_cap, fx->in_n + in_frames + 8, nch) != 0) {
            pthread_mutex_unlock(&fx->mu);
            return 0;
        }
        for (i = 0; i < in_frames; i++) {
            int dst = fx->in_n + i;
            for (c = 0; c < nch; c++)
                fx->inbuf[dst * nch + c] = (float)in[i * nch + c];
        }
        fx->in_n += in_frames;
    }
    if (plain) {
        produced = fifo_to_s16(fx->inbuf, &fx->in_n, out, out_cap, nch);
        pthread_mutex_unlock(&fx->mu);
        return produced;
    }
    {
        int path = !do_tempo ? 1 : !do_rate ? 2 : pitch > 1.f ? 3 : 4;
        if (path != fx->path) {
            ws_settle(fx, nch);
            fx->fir_cut = 0;
            memset(fx->fir_z, 0, sizeof(fx->fir_z));
            fx->path = path;
        }
    }
    {
        int win, cand;
        ws_params(fx, &win, &cand);
        if (!do_tempo) {
            produced = rate_to_s16(fx, fx->inbuf, &fx->in_n, &fx->in_frac, pitch, out, out_cap, nch,
                                   1);
        } else if (!do_rate) {
            if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + win * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            wsola(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch);
            produced = fifo_to_s16(fx->mid, &fx->mid_n, out, out_cap, nch);
        } else if (pitch > 1.f) {
            if (ensure_buf(&fx->mid, &fx->mid_cap, fx->mid_n + win * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            wsola(fx, fx->inbuf, &fx->in_n, tempo, fx->mid, &fx->mid_n, fx->mid_cap, nch);
            produced = rate_to_s16(fx, fx->mid, &fx->mid_n, &fx->mid_frac, pitch, out, out_cap, nch,
                                   1);
        } else {
            rate_xfer(fx, fx->inbuf, &fx->in_n, &fx->in_frac, &fx->mid, &fx->mid_n, &fx->mid_cap,
                      pitch, nch, 1);
            if (ensure_buf(&fx->xf, &fx->xf_cap, fx->xf_n + win * 2, nch) != 0) {
                pthread_mutex_unlock(&fx->mu);
                return 0;
            }
            wsola(fx, fx->mid, &fx->mid_n, tempo, fx->xf, &fx->xf_n, fx->xf_cap, nch);
            produced = fifo_to_s16(fx->xf, &fx->xf_n, out, out_cap, nch);
        }
    }
    pthread_mutex_unlock(&fx->mu);
    return produced;
}
