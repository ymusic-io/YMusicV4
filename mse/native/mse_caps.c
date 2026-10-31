#include "mse_caps.h"

#include "mse_ffmpeg.h"

#include <libavcodec/avcodec.h>

#include <ctype.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include "mse_d3d11.h"
#elif defined(__APPLE__)
#include "vpipe_hw.h"
#include <unistd.h>
#elif !defined(__ANDROID__)
#include "mse_hwdev.h"
#include <unistd.h>
#endif

/* Android's MSE video is MediaCodec only: its rows come from Kotlin, never from a probe here. */
#if !defined(__ANDROID__)
#define MSE_CAPS_PROBE 1
#endif

#define MAX_ROWS 64
#define MAX_CODECS 4
#define SW_MAX_W 8192
#define SW_MAX_H 4352
#define SW_MAX_THREADS 16

enum { ST_EMPTY, ST_BUILDING, ST_READY };
enum { K_UNKNOWN, K_AUDIO, K_VIDEO };

typedef struct {
    int kind;
    int codec;
    int bits;
    int chroma;
} CapCodec;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static MseCapRow g_rows[MAX_ROWS];
static int g_n;
static int g_state;

static const char *codec_name(int c)
{
    switch (c) {
    case MSE_CAP_H264:
        return "h264";
    case MSE_CAP_HEVC:
        return "hevc";
    case MSE_CAP_VP8:
        return "vp8";
    case MSE_CAP_VP9:
        return "vp9";
    case MSE_CAP_AV1:
        return "av1";
    default:
        return "?";
    }
}

static const char *chroma_name(int c)
{
    return c >= MSE_CAP_444 ? "444" : c == MSE_CAP_422 ? "422" : "420";
}

static void publish(const MseCapRow *rows, int n, const char *source)
{
    int i;
    pthread_mutex_lock(&g_mu);
    memcpy(g_rows, rows, (size_t)n * sizeof(*rows));
    g_n = n;
    g_state = ST_READY;
    pthread_mutex_unlock(&g_mu);
    mse_critf(MSE_LOG_INFO, "mse caps %s: %d decoder rows", source, n);
    for (i = 0; i < n; i++) {
        const MseCapRow *r = &rows[i];
        if (r->max_fps > 0)
            mse_critf(MSE_LOG_INFO, "mse caps %s %s %db %s %dx%d@%.1f", r->hw ? "hw" : "sw",
                      codec_name(r->codec), r->max_bits, chroma_name(r->chroma), r->max_w, r->max_h,
                      r->max_fps);
        else
            mse_critf(MSE_LOG_INFO, "mse caps %s %s %db %s %dx%d", r->hw ? "hw" : "sw",
                      codec_name(r->codec), r->max_bits, chroma_name(r->chroma), r->max_w, r->max_h);
    }
}

void mse_caps_set_rows(const int *rows, int n)
{
    MseCapRow r[MAX_ROWS];
    int i, k = 0;
    for (i = 0; i < n; i++) {
        const int *v = rows + (size_t)i * MSE_CAP_ROW_INTS;
        if (v[0] < MSE_CAP_H264 || v[0] > MSE_CAP_AV1 || v[1] < 8 || v[2] < MSE_CAP_420 ||
            v[3] <= 0 || v[4] <= 0 || v[5] < 0) {
            mse_critf(MSE_LOG_WARN, "mse caps: row %d rejected codec=%d bits=%d chroma=%d %dx%d@%d", i,
                      v[0], v[1], v[2], v[3], v[4], v[5]);
            continue;
        }
        if (k == MAX_ROWS) {
            mse_critf(MSE_LOG_WARN, "mse caps: %d rows given, the table keeps %d", n, MAX_ROWS);
            break;
        }
        r[k].codec = v[0];
        r[k].max_bits = v[1];
        r[k].chroma = v[2] > MSE_CAP_444 ? MSE_CAP_444 : v[2];
        r[k].max_w = v[3];
        r[k].max_h = v[4];
        r[k].max_fps = v[5];
        r[k].hw = v[6] != 0;
        k++;
    }
    if (k == 0) {
        mse_critf(MSE_LOG_WARN, "mse caps: none of %d rows usable, table unchanged", n);
        return;
    }
    publish(r, k, "set");
}

#ifdef MSE_CAPS_PROBE
static int cpu_threads(void)
{
#ifdef _WIN32
    DWORD n = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
#else
    long n = sysconf(_SC_NPROCESSORS_ONLN);
#endif
    return n > 0 ? (int)n : 1;
}

static int add_sw(MseCapRow *r, int cap, int n, int codec, int bits, int chroma, double px_per_s)
{
    if (n >= cap)
        return n;
    r[n].codec = codec;
    r[n].max_bits = bits;
    r[n].chroma = chroma;
    r[n].max_w = SW_MAX_W;
    r[n].max_h = SW_MAX_H;
    r[n].max_fps = px_per_s / ((double)SW_MAX_W * SW_MAX_H);
    r[n].hw = 0;
    return n + 1;
}

/* What the bundled software decoders sustain, in pixels per second per hardware thread: low-end
   numbers on purpose, since these rows only ever answer "smooth". Above 8 bits costs half again. */
static int sw_rows(MseCapRow *r, int cap)
{
    int n = 0;
    int t = cpu_threads();
    double k;
    if (t > SW_MAX_THREADS)
        t = SW_MAX_THREADS;
    k = (double)t;
    if (avcodec_find_decoder_by_name("h264")) {
        n = add_sw(r, cap, n, MSE_CAP_H264, 8, MSE_CAP_444, 40e6 * k);
        n = add_sw(r, cap, n, MSE_CAP_H264, 10, MSE_CAP_444, 40e6 * k / 1.5);
    }
    if (avcodec_find_decoder_by_name("hevc")) {
        n = add_sw(r, cap, n, MSE_CAP_HEVC, 8, MSE_CAP_444, 24e6 * k);
        n = add_sw(r, cap, n, MSE_CAP_HEVC, 12, MSE_CAP_444, 24e6 * k / 1.5);
    }
    /* libvpx-vp9 is built without high bit depth: profiles 2 and 3 decode on native vp9. */
    if (avcodec_find_decoder_by_name("libvpx-vp9") || avcodec_find_decoder_by_name("vp9"))
        n = add_sw(r, cap, n, MSE_CAP_VP9, 8, MSE_CAP_444, 24e6 * k);
    if (avcodec_find_decoder_by_name("vp9"))
        n = add_sw(r, cap, n, MSE_CAP_VP9, 12, MSE_CAP_444, 24e6 * k / 1.5);
    /* Native av1 only carries a hwaccel. */
    if (avcodec_find_decoder_by_name("libdav1d")) {
        n = add_sw(r, cap, n, MSE_CAP_AV1, 8, MSE_CAP_444, 20e6 * k);
        n = add_sw(r, cap, n, MSE_CAP_AV1, 10, MSE_CAP_444, 20e6 * k / 1.5);
    }
    if (avcodec_find_decoder_by_name("libvpx") || avcodec_find_decoder_by_name("vp8"))
        n = add_sw(r, cap, n, MSE_CAP_VP8, 8, MSE_CAP_420, 40e6 * k);
    return n;
}

static int probe_hw(MseCapRow *r, int cap)
{
#if defined(_WIN32)
    return mse_d3d11_caps(r, cap);
#elif defined(__APPLE__)
    return vpipe_hw_vt_caps(r, cap);
#else
    return mse_hwdev_caps(r, cap);
#endif
}

static void *build_main(void *arg)
{
    MseCapRow rows[MAX_ROWS];
    int n;
    (void)arg;
    n = probe_hw(rows, MAX_ROWS);
    n += sw_rows(rows + n, MAX_ROWS - n);
    publish(rows, n, "probe");
    return NULL;
}

static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void start_once(void)
{
    pthread_t t;
    pthread_attr_t at;
    int err;
    pthread_mutex_lock(&g_mu);
    if (g_state != ST_EMPTY) {
        pthread_mutex_unlock(&g_mu);
        return;
    }
    g_state = ST_BUILDING;
    pthread_mutex_unlock(&g_mu);
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    err = pthread_create(&t, &at, build_main, NULL);
    pthread_attr_destroy(&at);
    if (err != 0) {
        mse_critf(MSE_LOG_WARN, "mse caps: no probe thread (%d), probing on the caller", err);
        build_main(NULL);
    }
}
#endif

void mse_caps_start(void)
{
#ifdef MSE_CAPS_PROBE
    pthread_once(&g_once, start_once);
#endif
}

static int hw_live(int codec)
{
#if defined(_WIN32)
    (void)codec;
    return !mse_d3d11_lost();
#elif defined(MSE_CAPS_PROBE) && !defined(__APPLE__)
    switch (codec) {
    case MSE_CAP_H264:
        return mse_hwdev_usable(AV_CODEC_ID_H264);
    case MSE_CAP_HEVC:
        return mse_hwdev_usable(AV_CODEC_ID_HEVC);
    case MSE_CAP_VP8:
        return mse_hwdev_usable(AV_CODEC_ID_VP8);
    case MSE_CAP_VP9:
        return mse_hwdev_usable(AV_CODEC_ID_VP9);
    case MSE_CAP_AV1:
        return mse_hwdev_usable(AV_CODEC_ID_AV1);
    default:
        return 0;
    }
#else
    (void)codec;
    return 1;
#endif
}

/* All of s as a number in base; def when empty or not a number. */
static int num_field(const char *s, int base, int def)
{
    char *end;
    long v;
    if (!s || !*s)
        return def;
    v = strtol(s, &end, base);
    return *end ? def : (int)v;
}

static int hex_digit(char ch)
{
    if (ch >= '0' && ch <= '9')
        return ch - '0';
    if (ch >= 'a' && ch <= 'f')
        return ch - 'a' + 10;
    return -1;
}

/* profile_idc of avc1.PPCCLL; 0 when absent. */
static int avc_profile(const char *f)
{
    int hi, lo;
    if (strlen(f) != 6)
        return 0;
    hi = hex_digit(f[0]);
    lo = hex_digit(f[1]);
    return hi < 0 || lo < 0 ? 0 : hi * 16 + lo;
}

static void video(CapCodec *c, int codec, int bits, int chroma)
{
    c->kind = K_VIDEO;
    c->codec = codec;
    c->bits = bits;
    c->chroma = chroma;
}

/* One codecs= entry, lowercase: avc1/avc3, hev1/hvc1, vp8/vp08, vp9/vp09, av01, or a decoded audio codec. */
static void parse_codec(const char *tok, size_t len, CapCodec *c)
{
    char buf[64];
    char *f[10];
    int nf = 0, prof;
    char *p;
    memset(c, 0, sizeof(*c));
    if (len >= sizeof(buf))
        return;
    memcpy(buf, tok, len);
    buf[len] = 0;
    for (p = buf; nf < 10;) {
        f[nf++] = p;
        p = strchr(p, '.');
        if (!p)
            break;
        *p++ = 0;
    }
    if (!strcmp(f[0], "avc1") || !strcmp(f[0], "avc3")) {
        video(c, MSE_CAP_H264, 8, MSE_CAP_420);
        prof = nf > 1 ? avc_profile(f[1]) : 0;
        if (prof == 110)
            c->bits = 10;
        else if (prof == 122)
            video(c, MSE_CAP_H264, 10, MSE_CAP_422);
        else if (prof == 244 || prof == 44)
            video(c, MSE_CAP_H264, 10, MSE_CAP_444);
    } else if (!strcmp(f[0], "hev1") || !strcmp(f[0], "hvc1")) {
        const char *idc = nf > 1 ? f[1] : "";
        if (*idc >= 'a' && *idc <= 'c')
            idc++;
        prof = num_field(idc, 10, 1);
        if (prof == 2)
            video(c, MSE_CAP_HEVC, 10, MSE_CAP_420);
        else if (prof == 1 || prof == 3)
            video(c, MSE_CAP_HEVC, 8, MSE_CAP_420);
        else
            video(c, MSE_CAP_HEVC, 12, MSE_CAP_444);
    } else if (!strcmp(f[0], "vp09")) {
        int depth, cs;
        prof = nf > 1 ? num_field(f[1], 10, 0) : 0;
        depth = nf > 3 ? num_field(f[3], 10, 8) : prof >= 2 ? 10 : 8;
        cs = nf > 4 ? num_field(f[4], 10, 1) : (prof == 1 || prof == 3) ? 3 : 1;
        video(c, MSE_CAP_VP9, depth, cs >= 3 ? MSE_CAP_444 : cs == 2 ? MSE_CAP_422 : MSE_CAP_420);
    } else if (!strcmp(f[0], "vp9")) {
        prof = nf > 1 ? num_field(f[1], 10, 0) : 0;
        video(c, MSE_CAP_VP9, prof >= 2 ? 10 : 8, (prof == 1 || prof == 3) ? MSE_CAP_444 : MSE_CAP_420);
    } else if (!strcmp(f[0], "vp8") || !strcmp(f[0], "vp08")) {
        video(c, MSE_CAP_VP8, 8, MSE_CAP_420);
    } else if (!strcmp(f[0], "av01")) {
        int depth, chroma;
        prof = nf > 1 ? num_field(f[1], 10, 0) : 0;
        depth = nf > 3 ? num_field(f[3], 10, 8) : 8;
        chroma = prof == 1 ? MSE_CAP_444 : prof == 2 ? MSE_CAP_422 : MSE_CAP_420;
        /* mono_chrome, then subsampling_x subsampling_y chroma_sample_position. */
        if (nf > 5 && strlen(f[5]) == 3)
            chroma = f[5][0] == '0' ? MSE_CAP_444 : f[5][1] == '0' ? MSE_CAP_422 : MSE_CAP_420;
        video(c, MSE_CAP_AV1, depth, chroma);
    } else if (!strcmp(f[0], "opus") || !strcmp(f[0], "flac") || !strcmp(f[0], "vorbis") ||
               !strcmp(f[0], "mp3")) {
        c->kind = K_AUDIO;
    } else if (!strcmp(f[0], "mp4a") && nf > 1 &&
               (!strcmp(f[1], "40") || !strcmp(f[1], "67") || !strcmp(f[1], "69") ||
                !strcmp(f[1], "6b"))) {
        c->kind = K_AUDIO;
    }
}

/* The codecs= entries of a MIME type; a codec-less audio/mpeg is mp3. */
static int parse_content_type(const char *ct, CapCodec *out, int cap)
{
    char buf[256];
    const char *p, *end;
    size_t n = 0;
    int k = 0;
    if (!ct)
        return 0;
    while (ct[n] && n + 1 < sizeof(buf)) {
        buf[n] = (char)tolower((unsigned char)ct[n]);
        n++;
    }
    buf[n] = 0;
    p = strstr(buf, "codecs=");
    if (!p) {
        if (strncmp(buf, "audio/mpeg", 10) != 0)
            return 0;
        out[0].kind = K_AUDIO;
        return 1;
    }
    p += 7;
    if (*p == '"') {
        p++;
        end = strchr(p, '"');
        if (!end)
            end = p + strlen(p);
    } else {
        end = p + strcspn(p, "; \t");
    }
    while (p < end && k < cap) {
        const char *q = p;
        size_t len;
        while (q < end && *q != ',')
            q++;
        while (p < q && (*p == ' ' || *p == '\t'))
            p++;
        len = (size_t)(q - p);
        while (len > 0 && (p[len - 1] == ' ' || p[len - 1] == '\t'))
            len--;
        if (len > 0)
            parse_codec(p, len, &out[k++]);
        p = q < end ? q + 1 : q;
    }
    return k;
}

static int fits_size(const MseCapRow *r, int w, int h)
{
    int lo, hi, rlo, rhi;
    if (w <= 0 || h <= 0)
        return 1;
    lo = w < h ? w : h;
    hi = w < h ? h : w;
    rlo = r->max_w < r->max_h ? r->max_w : r->max_h;
    rhi = r->max_w < r->max_h ? r->max_h : r->max_w;
    return hi <= rhi && lo <= rlo;
}

static int fits_rate(const MseCapRow *r, int w, int h, double fps)
{
    if (r->max_fps <= 0 || fps <= 0 || w <= 0 || h <= 0)
        return 1;
    return (double)w * h * fps <= (double)r->max_w * r->max_h * r->max_fps * 1.0001;
}

/* Caller holds g_mu. */
static int eval_video(const CapCodec *c, int w, int h, double fps)
{
    int i, bits = 0;
    for (i = 0; i < g_n; i++) {
        const MseCapRow *r = &g_rows[i];
        if (r->codec != c->codec || c->bits > r->max_bits || c->chroma > r->chroma ||
            !fits_size(r, w, h))
            continue;
        if (r->hw && !hw_live(r->codec))
            continue;
        bits |= MSE_CAPS_SUPPORTED;
        if (!fits_rate(r, w, h, fps))
            continue;
        bits |= MSE_CAPS_SMOOTH;
        if (r->hw)
            bits |= MSE_CAPS_EFFICIENT;
    }
    return bits;
}

int mse_caps_decoding_info(const char *content_type, int w, int h, double fps, double bitrate)
{
    CapCodec cs[MAX_CODECS];
    int i, n, bits = MSE_CAPS_SUPPORTED | MSE_CAPS_SMOOTH | MSE_CAPS_EFFICIENT;
    (void)bitrate;
    n = parse_content_type(content_type, cs, MAX_CODECS);
    pthread_mutex_lock(&g_mu);
    if (g_state != ST_READY) {
        pthread_mutex_unlock(&g_mu);
#ifdef MSE_CAPS_PROBE
        mse_caps_start();
        return MSE_CAPS_PENDING;
#else
        return MSE_CAPS_NONE;
#endif
    }
    if (n == 0)
        bits = 0;
    for (i = 0; i < n; i++) {
        if (cs[i].kind == K_VIDEO)
            bits &= eval_video(&cs[i], w, h, fps);
        else if (cs[i].kind != K_AUDIO)
            bits = 0;
    }
    pthread_mutex_unlock(&g_mu);
    return bits;
}
