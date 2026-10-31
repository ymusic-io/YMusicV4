#include "quickjs_mse.h"

#include <limits.h>
#include <stdio.h>
#include <string.h>
#ifdef __APPLE__
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/log.h>
#include <sys/sysctl.h>
#endif
#endif
#ifdef __ANDROID__
#include <android/log.h>
#include <dlfcn.h>
#include <media/NdkMediaCodec.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#endif

static const QjsMseVt *g_vt;

void qjs_mse_set_vt(const QjsMseVt *vt)
{
    g_vt = vt;
}

extern void *qjs_ctx_mse(JSContext *ctx);

static void *host_of(JSContext *ctx)
{
    return qjs_ctx_mse(ctx);
}

static JSValue throw_no_mse(JSContext *ctx)
{
    return JS_ThrowTypeError(ctx, "native MSE host not attached");
}

static JSValue js_ready(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
    return JS_NewBool(ctx, g_vt && host_of(ctx));
}

static JSValue js_current_time(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewFloat64(ctx, g_vt->current_time(h));
}

static JSValue js_set_current_time(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double t = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToFloat64(ctx, &t, argv[0]))
        return JS_EXCEPTION;
    g_vt->set_current_time(h, t);
    return JS_UNDEFINED;
}

static JSValue js_set_speed(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double speed = 1, tempo = 1;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (!g_vt->set_speed)
        return JS_UNDEFINED;
    if (argc < 1 || JS_ToFloat64(ctx, &speed, argv[0]))
        return JS_EXCEPTION;
    tempo = speed;
    if (argc >= 2 && JS_ToFloat64(ctx, &tempo, argv[1]))
        return JS_EXCEPTION;
    g_vt->set_speed(h, speed, tempo);
    return JS_UNDEFINED;
}

static JSValue js_playback_rate(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (!g_vt->playback_rate)
        return JS_NewFloat64(ctx, 1);
    return JS_NewFloat64(ctx, g_vt->playback_rate(h));
}

static JSValue js_set_pitch(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double pitch = 1;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToFloat64(ctx, &pitch, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->set_pitch)
        g_vt->set_pitch(h, pitch);
    return JS_UNDEFINED;
}

static JSValue js_duration(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewFloat64(ctx, g_vt->duration(h));
}

static JSValue js_paused(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewBool(ctx, g_vt->paused(h));
}

static JSValue js_ready_state(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewInt32(ctx, g_vt->ready_state(h));
}

static JSValue js_play(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->play(h);
    return JS_UNDEFINED;
}

static JSValue js_pause(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->pause(h);
    return JS_UNDEFINED;
}

static JSValue js_append(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    double ts = 0, ws = 0, we = 1e300;
    size_t size = 0, bpe = 0;
    size_t offset = 0;
    JSValue buf;
    uint8_t *p;
    size_t len = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 2)
        return JS_ThrowTypeError(ctx, "__mseAppend(audio, bytes, ts, winStart, winEnd)");
    if (JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    if (argc > 2 && JS_ToFloat64(ctx, &ts, argv[2]))
        return JS_EXCEPTION;
    if (argc > 3 && JS_ToFloat64(ctx, &ws, argv[3]))
        return JS_EXCEPTION;
    if (argc > 4 && JS_ToFloat64(ctx, &we, argv[4]))
        return JS_EXCEPTION;
    buf = JS_GetTypedArrayBuffer(ctx, argv[1], &offset, &size, &bpe);
    if (JS_IsException(buf))
        return buf;
    p = JS_GetArrayBuffer(ctx, &len, buf);
    JS_FreeValue(ctx, buf);
    if (!p)
        return JS_ThrowTypeError(ctx, "__mseAppend: not a Uint8Array");
    if (offset + size > len)
        return JS_ThrowRangeError(ctx, "__mseAppend: OOB");
    if (size > (size_t)INT_MAX)
        return JS_ThrowRangeError(ctx, "__mseAppend: too large");
    if (!g_vt->append(h, audio, p + offset, (int)size, ts, ws, we))
        return JS_ThrowInternalError(ctx, "__mseAppend failed");
    return JS_UNDEFINED;
}

static JSValue js_prepare_append(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0, n = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 2)
        return JS_ThrowTypeError(ctx, "__msePrepareAppend(audio, nbytes)");
    if (JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    if (JS_ToInt32(ctx, &n, argv[1]))
        return JS_EXCEPTION;
    if (!g_vt->prepare_append)
        return JS_NewInt32(ctx, 1);
    return JS_NewInt32(ctx, g_vt->prepare_append(h, audio, n));
}

static JSValue js_remove(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    double start = 0, end = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 3)
        return JS_ThrowTypeError(ctx, "__mseRemove(audio, start, end)");
    if (JS_ToInt32(ctx, &audio, argv[0]) || JS_ToFloat64(ctx, &start, argv[1]) || JS_ToFloat64(ctx, &end, argv[2]))
        return JS_EXCEPTION;
    g_vt->remove(h, audio, start, end);
    return JS_UNDEFINED;
}

static JSValue js_abort(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    g_vt->abort(h, audio);
    return JS_UNDEFINED;
}

static JSValue js_change_type(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->change_type)
        g_vt->change_type(h, audio);
    return JS_UNDEFINED;
}

static JSValue js_rest_video(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int on = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &on, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->rest_video)
        g_vt->rest_video(h, on);
    return JS_UNDEFINED;
}

static JSValue js_set_duration(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double t = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToFloat64(ctx, &t, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->set_duration)
        g_vt->set_duration(h, t);
    return JS_UNDEFINED;
}

static JSValue js_ended(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (!g_vt->ended)
        return JS_NewInt32(ctx, 0);
    return JS_NewInt32(ctx, g_vt->ended(h));
}

static JSValue js_end_of_stream(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (g_vt->end_of_stream)
        g_vt->end_of_stream(h);
    return JS_UNDEFINED;
}

static JSValue js_buffered(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0, n = 0, i;
    double pairs[64];
    JSValue arr;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    n = g_vt->buffered(h, audio, pairs, 32);
    arr = JS_NewArray(ctx);
    for (i = 0; i < n; i++) {
        JS_SetPropertyUint32(ctx, arr, (unsigned)(i * 2), JS_NewFloat64(ctx, pairs[i * 2]));
        JS_SetPropertyUint32(ctx, arr, (unsigned)(i * 2 + 1), JS_NewFloat64(ctx, pairs[i * 2 + 1]));
    }
    return arr;
}

static JSValue js_reset(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->reset(h);
    return JS_UNDEFINED;
}

/* AV1 HW: A17 Pro / A18 / M3 / M4. Simulator reports "arm64", not iPhoneN,M.
   iPhone16,1 = 15 Pro. iPhone15,* = A16 / 14 Pro — no AV1.
   iPad16,* = mini A17 Pro, Pro M4, Air M4. iPad15,3–15,6 = Air M3 (has AV1);
   iPad15,7–15,8 = A16 iPad (no AV1). Everyone else gets H.264. */
#if TARGET_OS_IPHONE
static int ios_av1_hw(void)
{
    static int probed;
    static int ok;
    char machine[64];
    size_t n = sizeof(machine);
    int major = 0, minor = 0;
    if (probed)
        return ok;
    probed = 1;
    if (sysctlbyname("hw.machine", machine, &n, NULL, 0) != 0)
        machine[0] = 0;
    if (sscanf(machine, "iPhone%d", &major) == 1 && major >= 16)
        ok = 1;
    else if (sscanf(machine, "iPad%d,%d", &major, &minor) == 2) {
        if (major >= 16)
            ok = 1;
        else if (major == 15 && minor >= 3 && minor <= 6)
            ok = 1;
    }
    fprintf(stderr, "mse ios machine=%s av1=%d\n", machine, ok);
    os_log_with_type(os_log_create("com.ymusic.mse", "v"), OS_LOG_TYPE_INFO,
                     "mse ios machine=%{public}s av1=%d", machine, ok);
    return ok;
}
#endif

#ifdef __ANDROID__
typedef media_status_t (*mse_mc_get_name_fn)(AMediaCodec *, char **);
typedef void (*mse_mc_rel_name_fn)(AMediaCodec *, char *);

static int android_hw_decoder(const char *mime)
{
    static mse_mc_get_name_fn get_name;
    static mse_mc_rel_name_fn rel_name;
    static int once;
    AMediaCodec *c;
    char *name = NULL;
    int hw = 0;
    if (!once) {
        once = 1;
        get_name = (mse_mc_get_name_fn)dlsym(RTLD_DEFAULT, "AMediaCodec_getName");
        rel_name = (mse_mc_rel_name_fn)dlsym(RTLD_DEFAULT, "AMediaCodec_releaseName");
    }
    c = AMediaCodec_createDecoderByType(mime);
    if (!c) {
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "canPlay %s hw=0 create=fail", mime);
        return 0;
    }
    if (get_name && get_name(c, &name) == AMEDIA_OK && name) {
        int google = strstr(name, "google") || strstr(name, "c2.android");
        int sw = strstr(name, ".sw") || strstr(name, "sw-based");
        hw = !google && !sw;
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "canPlay %s hw=%d name=%s", mime, hw, name);
        if (rel_name)
            rel_name(c, name);
    } else {
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "canPlay %s hw=0 name=missing", mime);
    }
    AMediaCodec_delete(c);
    return hw;
}

static int android_mpc_high(void)
{
    char v[PROP_VALUE_MAX];
    int n;
    if (__system_property_get("ro.odm.build.media_performance_class", v) <= 0)
        v[0] = 0;
    n = atoi(v);
    __android_log_print(ANDROID_LOG_INFO, "mse-v", "mpc=%d high=%d", n, n >= 30);
    return n >= 30;
}

static int android_av1_ok(void)
{
    static int probed, ok;
    if (!probed) {
        probed = 1;
        ok = android_hw_decoder("video/av01") || android_mpc_high();
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "av1Playable=%d", ok);
    }
    return ok;
}

static int android_vp9_ok(void)
{
    static int probed, ok;
    if (!probed) {
        probed = 1;
        ok = android_hw_decoder("video/x-vnd.on2.vp9") || android_mpc_high();
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "vp9Playable=%d", ok);
    }
    return ok;
}

/* High@L4.0 is 720p60 (itag 298). i.MX8 cannot keep that realtime. */
static int android_avc_max_level(void)
{
    static int probed, lvl;
    if (!probed) {
        probed = 1;
        lvl = android_mpc_high() ? 255 : 0x1f;
        __android_log_print(ANDROID_LOG_INFO, "mse-v", "avcMaxLevel=%d", lvl);
    }
    return lvl;
}
#endif

static JSValue js_av1_ok(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
#if TARGET_OS_IPHONE
    return JS_NewInt32(ctx, ios_av1_hw());
#elif defined(__ANDROID__)
    return JS_NewInt32(ctx, android_av1_ok());
#else
    return JS_NewInt32(ctx, 1);
#endif
}

static JSValue js_avc_max_level(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
#ifdef __ANDROID__
    return JS_NewInt32(ctx, android_avc_max_level());
#else
    return JS_NewInt32(ctx, 255);
#endif
}

static JSValue js_vp9_ok(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
#if TARGET_OS_IPHONE
    return JS_NewInt32(ctx, 0);
#elif defined(__ANDROID__)
    return JS_NewInt32(ctx, android_vp9_ok());
#else
    return JS_NewInt32(ctx, 1);
#endif
}

static JSValue js_decoding_info(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    const char *ct;
    int32_t w = 0, h = 0;
    double fps = 0, bitrate = 0;
    int r;
    (void)this_val;
    if (!g_vt || !g_vt->decoding_info)
        return JS_NewInt32(ctx, -1);
    if (argc < 1)
        return JS_ThrowTypeError(ctx, "__mseDecodingInfo(contentType, width, height, framerate, bitrate)");
    if ((argc > 1 && JS_ToInt32(ctx, &w, argv[1])) || (argc > 2 && JS_ToInt32(ctx, &h, argv[2])) ||
        (argc > 3 && JS_ToFloat64(ctx, &fps, argv[3])) || (argc > 4 && JS_ToFloat64(ctx, &bitrate, argv[4])))
        return JS_EXCEPTION;
    if (!(fps > 0))
        fps = 0;
    if (!(bitrate > 0))
        bitrate = 0;
    ct = JS_ToCString(ctx, argv[0]);
    if (!ct)
        return JS_EXCEPTION;
    r = g_vt->decoding_info(ct, w, h, fps, bitrate);
    JS_FreeCString(ctx, ct);
    return JS_NewInt32(ctx, r);
}

static JSValue js_video_quality(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int total = 0, dropped = 0;
    JSValue arr;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (g_vt && h && g_vt->video_quality)
        g_vt->video_quality(h, &total, &dropped);
    arr = JS_NewArray(ctx);
    if (JS_IsException(arr))
        return arr;
    JS_SetPropertyUint32(ctx, arr, 0, JS_NewInt32(ctx, total));
    JS_SetPropertyUint32(ctx, arr, 1, JS_NewInt32(ctx, dropped));
    return arr;
}

void qjs_mse_install(JSContext *ctx)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__mseReady", JS_NewCFunction(ctx, js_ready, "__mseReady", 0));
    JS_SetPropertyStr(ctx, g, "__mseCurrentTime", JS_NewCFunction(ctx, js_current_time, "__mseCurrentTime", 0));
    JS_SetPropertyStr(ctx, g, "__mseSetCurrentTime", JS_NewCFunction(ctx, js_set_current_time, "__mseSetCurrentTime", 1));
    JS_SetPropertyStr(ctx, g, "__mseSetSpeed", JS_NewCFunction(ctx, js_set_speed, "__mseSetSpeed", 2));
    JS_SetPropertyStr(ctx, g, "__msePlaybackRate", JS_NewCFunction(ctx, js_playback_rate, "__msePlaybackRate", 0));
    JS_SetPropertyStr(ctx, g, "__mseSetPitch", JS_NewCFunction(ctx, js_set_pitch, "__mseSetPitch", 1));
    JS_SetPropertyStr(ctx, g, "__mseDuration", JS_NewCFunction(ctx, js_duration, "__mseDuration", 0));
    JS_SetPropertyStr(ctx, g, "__mseSetDuration", JS_NewCFunction(ctx, js_set_duration, "__mseSetDuration", 1));
    JS_SetPropertyStr(ctx, g, "__mseEnded", JS_NewCFunction(ctx, js_ended, "__mseEnded", 0));
    JS_SetPropertyStr(ctx, g, "__mseEndOfStream", JS_NewCFunction(ctx, js_end_of_stream, "__mseEndOfStream", 0));
    JS_SetPropertyStr(ctx, g, "__msePaused", JS_NewCFunction(ctx, js_paused, "__msePaused", 0));
    JS_SetPropertyStr(ctx, g, "__mseReadyState", JS_NewCFunction(ctx, js_ready_state, "__mseReadyState", 0));
    JS_SetPropertyStr(ctx, g, "__msePlay", JS_NewCFunction(ctx, js_play, "__msePlay", 0));
    JS_SetPropertyStr(ctx, g, "__msePause", JS_NewCFunction(ctx, js_pause, "__msePause", 0));
    JS_SetPropertyStr(ctx, g, "__mseAppend", JS_NewCFunction(ctx, js_append, "__mseAppend", 5));
    JS_SetPropertyStr(ctx, g, "__msePrepareAppend", JS_NewCFunction(ctx, js_prepare_append, "__msePrepareAppend", 2));
    JS_SetPropertyStr(ctx, g, "__mseRemove", JS_NewCFunction(ctx, js_remove, "__mseRemove", 3));
    JS_SetPropertyStr(ctx, g, "__mseAbort", JS_NewCFunction(ctx, js_abort, "__mseAbort", 1));
    JS_SetPropertyStr(ctx, g, "__mseChangeType", JS_NewCFunction(ctx, js_change_type, "__mseChangeType", 1));
    JS_SetPropertyStr(ctx, g, "__mseRestVideo", JS_NewCFunction(ctx, js_rest_video, "__mseRestVideo", 1));
    JS_SetPropertyStr(ctx, g, "__mseBuffered", JS_NewCFunction(ctx, js_buffered, "__mseBuffered", 1));
    JS_SetPropertyStr(ctx, g, "__mseReset", JS_NewCFunction(ctx, js_reset, "__mseReset", 0));
    JS_SetPropertyStr(ctx, g, "__mseAv1Ok", JS_NewCFunction(ctx, js_av1_ok, "__mseAv1Ok", 0));
    JS_SetPropertyStr(ctx, g, "__mseVp9Ok", JS_NewCFunction(ctx, js_vp9_ok, "__mseVp9Ok", 0));
    JS_SetPropertyStr(ctx, g, "__mseAvcMaxLevel", JS_NewCFunction(ctx, js_avc_max_level, "__mseAvcMaxLevel", 0));
    JS_SetPropertyStr(ctx, g, "__mseDecodingInfo", JS_NewCFunction(ctx, js_decoding_info, "__mseDecodingInfo", 5));
    JS_SetPropertyStr(ctx, g, "__mseVideoQuality", JS_NewCFunction(ctx, js_video_quality, "__mseVideoQuality", 0));
    JS_FreeValue(ctx, g);
}
