#include "file_av.h"
#ifdef __APPLE__
#include "mse_vt_present.h"
#endif

#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include "mse_d3d11.h"
/* Declared in mse_d3d11_jawt.c; shared by the host and file-av JNI. */
HWND mse_d3d11_jawt_hwnd(JNIEnv *env, jobject component);
#endif

typedef struct {
    JavaVM *jvm;
    jobject sink;
    jmethodID onFrame;
    jmethodID onProgress;
    jmethodID onEvent;
    jmethodID onCaption;
    jobject direct;
    const uint8_t *direct_ptr;
    int direct_n;
} JniSink;

static JNIEnv *sink_env(JniSink *s)
{
    JNIEnv *env = NULL;
    if (!s || !s->jvm)
        return NULL;
    (*s->jvm)->GetEnv(s->jvm, (void **)&env, JNI_VERSION_1_6);
    if (env)
        return env;
    (*s->jvm)->AttachCurrentThread(s->jvm, (void **)&env, NULL);
    return env;
}

static jobject direct_for(JNIEnv *env, JniSink *s, const uint8_t *bgra, int n)
{
    jobject local;
    if (s->direct && s->direct_ptr == bgra && s->direct_n >= n)
        return s->direct;
    if (s->direct) {
        (*env)->DeleteGlobalRef(env, s->direct);
        s->direct = NULL;
    }
    local = (*env)->NewDirectByteBuffer(env, (void *)bgra, (jlong)n);
    if (!local)
        return NULL;
    s->direct = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    s->direct_ptr = bgra;
    s->direct_n = n;
    return s->direct;
}

static void on_frame(void *user, int w, int h, const uint8_t *bgra)
{
    JniSink *s = user;
    JNIEnv *env;
    jobject buf;
    int n;
    if (!s || w <= 0 || h <= 0)
        return;
    env = sink_env(s);
    if (!env)
        return;
    if (!bgra) {
        (*env)->CallVoidMethod(env, s->sink, s->onFrame, w, h, NULL);
        return;
    }
    if ((uint64_t)w * (uint64_t)h > (uint64_t)(INT_MAX / 4))
        return;
    n = w * h * 4;
    buf = direct_for(env, s, bgra, n);
    if (!buf)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onFrame, w, h, buf);
}

static void on_prog(void *user, double pos, double dur, int w, int h)
{
    JniSink *s = user;
    JNIEnv *env = sink_env(s);
    if (!env)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onProgress, pos, dur, w, h);
}

static void on_event(void *user, int ended, const char *err)
{
    JniSink *s = user;
    JNIEnv *env;
    jstring js = NULL;
    if (!s)
        return;
    env = sink_env(s);
    if (!env)
        return;
    if (err && err[0])
        js = (*env)->NewStringUTF(env, err);
    (*env)->CallVoidMethod(env, s->sink, s->onEvent, ended ? JNI_TRUE : JNI_FALSE, js);
    if (js)
        (*env)->DeleteLocalRef(env, js);
}

static void on_caption(void *user, int id, double start, double end, const char *text)
{
    JniSink *s = user;
    JNIEnv *env;
    jstring js;
    if (!s || !s->onCaption || !text)
        return;
    env = sink_env(s);
    if (!env)
        return;
    js = (*env)->NewStringUTF(env, text);
    if (!js)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onCaption, (jint)id, start, end, js);
    (*env)->DeleteLocalRef(env, js);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void drop_sink(JNIEnv *env, JniSink *s)
{
    if (!s)
        return;
    if (s->direct)
        (*env)->DeleteGlobalRef(env, s->direct);
    if (s->sink)
        (*env)->DeleteGlobalRef(env, s->sink);
    free(s);
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_FileAvNative_create(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)file_av_create();
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_destroy(JNIEnv *env, jclass cls, jlong handle)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    JniSink *s;
    (void)cls;
    if (!p)
        return;
    s = (JniSink *)file_av_user(p);
    file_av_set_sinks(p, NULL, NULL, NULL, NULL, NULL, NULL);
    file_av_destroy(p);
    drop_sink(env, s);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_FileAvNative_open(JNIEnv *env, jclass cls, jlong handle, jstring path)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    const char *c;
    int r;
    (void)cls;
    if (!p || !path)
        return -1;
    c = (*env)->GetStringUTFChars(env, path, NULL);
    if (!c)
        return -1;
    r = file_av_open(p, c);
    (*env)->ReleaseStringUTFChars(env, path, c);
    return r;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_close(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_close((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_play(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_play((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_pause(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    file_av_pause((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_seek(JNIEnv *env, jclass cls, jlong handle, jdouble seconds)
{
    (void)env;
    (void)cls;
    file_av_seek((FileAv *)(intptr_t)handle, seconds);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_FileAvNative_step(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_step((FileAv *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setSpeed(JNIEnv *env, jclass cls, jlong handle, jdouble speed)
{
    (void)env;
    (void)cls;
    file_av_set_speed((FileAv *)(intptr_t)handle, speed);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setVolume(JNIEnv *env, jclass cls, jlong handle, jdouble vol)
{
    (void)env;
    (void)cls;
    file_av_set_volume((FileAv *)(intptr_t)handle, vol);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_FileAvNative_pcmLevels(JNIEnv *env, jclass cls, jlong handle, jfloatArray out)
{
    float tmp[64];
    jsize n;
    int got;
    (void)cls;
    if (!out)
        return 0;
    n = (*env)->GetArrayLength(env, out);
    if (n > 64)
        n = 64;
    got = file_av_pcm_levels((FileAv *)(intptr_t)handle, tmp, (int)n);
    if (got > 0)
        (*env)->SetFloatArrayRegion(env, out, 0, got, tmp);
    return got;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setPitch(JNIEnv *env, jclass cls, jlong handle, jdouble pitch)
{
    (void)env;
    (void)cls;
    file_av_set_pitch((FileAv *)(intptr_t)handle, pitch);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setEq(JNIEnv *env, jclass cls, jlong handle, jfloatArray bands)
{
    float tmp[10];
    jsize n;
    (void)cls;
    if (!bands)
        return;
    n = (*env)->GetArrayLength(env, bands);
    if (n > 10)
        n = 10;
    memset(tmp, 0, sizeof(tmp));
    (*env)->GetFloatArrayRegion(env, bands, 0, n, tmp);
    file_av_set_eq((FileAv *)(intptr_t)handle, tmp);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setBass(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    file_av_set_bass((FileAv *)(intptr_t)handle, db);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setVirtualizer(JNIEnv *env, jclass cls, jlong handle, jfloat width)
{
    (void)env;
    (void)cls;
    file_av_set_virtualizer((FileAv *)(intptr_t)handle, width);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setPreamp(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    file_av_set_preamp((FileAv *)(intptr_t)handle, db);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setAudioIndex(JNIEnv *env, jclass cls, jlong handle, jint index)
{
    (void)env;
    (void)cls;
    file_av_set_audio_index((FileAv *)(intptr_t)handle, index);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setSubtitleIndex(JNIEnv *env, jclass cls, jlong handle, jint index)
{
    (void)env;
    (void)cls;
    file_av_set_subtitle_index((FileAv *)(intptr_t)handle, index);
}

typedef struct {
    char **lines;
    int n, cap;
} DumpAcc;

static void dump_cue(void *user, int id, double start, double end, const char *text)
{
    DumpAcc *a = user;
    char line[1400];
    char *copy;
    (void)id;
    if (!a || !text)
        return;
    snprintf(line, sizeof(line), "%.3f|%.3f|%s", start, end, text);
    if (a->n >= a->cap) {
        int cap = a->cap ? a->cap * 2 : 32;
        char **nlines = realloc(a->lines, (size_t)cap * sizeof(*nlines));
        if (!nlines)
            return;
        a->lines = nlines;
        a->cap = cap;
    }
    copy = strdup(line);
    if (!copy)
        return;
    a->lines[a->n++] = copy;
}

JNIEXPORT jobjectArray JNICALL
Java_io_ymusic_mse_FileAvNative_dumpCaptions(JNIEnv *env, jclass cls, jlong handle, jint index)
{
    DumpAcc acc = {0};
    jobjectArray arr;
    jclass strCls;
    int i;
    (void)cls;
    file_av_dump_captions((FileAv *)(intptr_t)handle, index, dump_cue, &acc);
    strCls = (*env)->FindClass(env, "java/lang/String");
    arr = (*env)->NewObjectArray(env, acc.n, strCls, NULL);
    for (i = 0; i < acc.n; i++) {
        jstring js = (*env)->NewStringUTF(env, acc.lines[i] ? acc.lines[i] : "");
        (*env)->SetObjectArrayElement(env, arr, i, js);
        (*env)->DeleteLocalRef(env, js);
        free(acc.lines[i]);
    }
    free(acc.lines);
    return arr;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setVideoEnabled(JNIEnv *env, jclass cls, jlong handle, jboolean on)
{
    (void)env;
    (void)cls;
    file_av_set_video_enabled((FileAv *)(intptr_t)handle, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setBlitMax(JNIEnv *env, jclass cls, jlong handle, jint maxHeight)
{
    (void)env;
    (void)cls;
    file_av_set_blit_max((FileAv *)(intptr_t)handle, maxHeight);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setBlitSize(JNIEnv *env, jclass cls, jlong handle, jint w, jint h)
{
    (void)env;
    (void)cls;
    file_av_set_blit_size((FileAv *)(intptr_t)handle, w, h);
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_FileAvNative_setVideoComponent(JNIEnv *env, jclass cls, jlong handle, jobject component)
{
    (void)cls;
#ifdef __APPLE__
    void *layer = NULL;
    if (component)
        layer = mse_vt_attach_component(env, component);
    file_av_set_video_layer((FileAv *)(intptr_t)handle, layer);
    return layer ? JNI_TRUE : JNI_FALSE;
#elif defined(_WIN32)
    FileAv *p = (FileAv *)(intptr_t)handle;
    HWND hwnd = NULL;
    int ok = 0;
    if (!p)
        return JNI_FALSE;
    if (component) {
        hwnd = mse_d3d11_jawt_hwnd(env, component);
        if (hwnd)
            ok = (mse_d3d11_present_attach(hwnd) == 0);
    } else {
        mse_d3d11_present_detach();
    }
    file_av_set_video_hwnd(p, ok ? (void *)hwnd : NULL);
    return ok ? JNI_TRUE : JNI_FALSE;
#else
    (void)env;
    (void)handle;
    (void)component;
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_orderWindowBelow(JNIEnv *env, jclass cls, jobject below, jobject above)
{
    (void)cls;
#ifdef __APPLE__
    mse_vt_order_window_below(env, below, above);
#else
    (void)env;
    (void)below;
    (void)above;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_prepareFullscreen(JNIEnv *env, jclass cls, jobject primary)
{
    (void)cls;
#ifdef __APPLE__
    mse_vt_prepare_fullscreen(env, primary);
#else
    (void)env;
    (void)primary;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_toggleFullScreen(JNIEnv *env, jclass cls, jobject primary)
{
    (void)cls;
#ifdef __APPLE__
    mse_vt_toggle_fullscreen(env, primary);
#else
    (void)env;
    (void)primary;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setOverlayHidden(JNIEnv *env, jclass cls, jboolean hid)
{
    (void)env;
    (void)cls;
#ifdef __APPLE__
    mse_vt_set_overlay_hidden(hid ? 1 : 0);
#else
    (void)hid;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_layoutVideo(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h)
{
    (void)env;
    (void)cls;
#ifdef __APPLE__
    mse_vt_layout_screen(x, y, w, h);
#else
    (void)x;
    (void)y;
    (void)w;
    (void)h;
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_io_ymusic_mse_FileAvNative_snapshotOverlay(JNIEnv *env, jclass cls, jintArray wh)
{
    int w = 0, h = 0, need;
    jbyteArray arr;
    jbyte *dst;
    jint m[2];
    (void)cls;
#ifdef __APPLE__
    need = mse_vt_copy_last(NULL, 0, &w, &h);
    if (need <= 0)
        return NULL;
    arr = (*env)->NewByteArray(env, need);
    if (!arr)
        return NULL;
    dst = (*env)->GetByteArrayElements(env, arr, NULL);
    if (!dst) {
        (*env)->DeleteLocalRef(env, arr);
        return NULL;
    }
    if (mse_vt_copy_last((uint8_t *)dst, need, &w, &h) <= 0) {
        (*env)->ReleaseByteArrayElements(env, arr, dst, JNI_ABORT);
        (*env)->DeleteLocalRef(env, arr);
        return NULL;
    }
    (*env)->ReleaseByteArrayElements(env, arr, dst, 0);
    if (wh && (*env)->GetArrayLength(env, wh) >= 2) {
        m[0] = w;
        m[1] = h;
        (*env)->SetIntArrayRegion(env, wh, 0, 2, m);
    }
    return arr;
#else
    (void)env;
    (void)wh;
    return NULL;
#endif
}

/* memcpy BGRA into TYPE_INT_RGB. IntBuffer.get of 4K is several ms. */
JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_copyBgra(JNIEnv *env, jclass cls, jobject buf, jintArray rgb, jint n)
{
    void *src;
    jint *dst;
    (void)cls;
    if (!buf || !rgb || n <= 0)
        return;
    src = (*env)->GetDirectBufferAddress(env, buf);
    if (!src)
        return;
    dst = (*env)->GetPrimitiveArrayCritical(env, rgb, NULL);
    if (!dst)
        return;
    memcpy(dst, src, (size_t)n * 4u);
    (*env)->ReleasePrimitiveArrayCritical(env, rgb, dst, 0);
}

JNIEXPORT jdouble JNICALL
Java_io_ymusic_mse_FileAvNative_duration(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_duration((FileAv *)(intptr_t)handle);
}

JNIEXPORT jdouble JNICALL
Java_io_ymusic_mse_FileAvNative_position(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_position((FileAv *)(intptr_t)handle);
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_FileAvNative_ended(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return file_av_ended((FileAv *)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_FileAvNative_videoSize(JNIEnv *env, jclass cls, jlong handle, jintArray outWh)
{
    int w = 0, h = 0, ok;
    jint wh[2];
    (void)cls;
    ok = file_av_video_size((FileAv *)(intptr_t)handle, &w, &h);
    wh[0] = w;
    wh[1] = h;
    (*env)->SetIntArrayRegion(env, outWh, 0, 2, wh);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_ymusic_mse_FileAvNative_lastError(JNIEnv *env, jclass cls, jlong handle)
{
    const char *e = file_av_last_error((FileAv *)(intptr_t)handle);
    (void)cls;
    return (*env)->NewStringUTF(env, e ? e : "");
}

JNIEXPORT jobjectArray JNICALL
Java_io_ymusic_mse_FileAvNative_streams(JNIEnv *env, jclass cls, jlong handle)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    int n, i;
    jobjectArray arr;
    jclass strCls;
    (void)cls;
    n = file_av_stream_count(p);
    strCls = (*env)->FindClass(env, "java/lang/String");
    arr = (*env)->NewObjectArray(env, n, strCls, NULL);
    for (i = 0; i < n; i++) {
        int kind = 0, index = 0, w = 0, h = 0;
        char lang[16], codec[32], line[96];
        jstring js;
        file_av_stream_at(p, i, &kind, &index, &w, &h, lang, (int)sizeof(lang), codec, (int)sizeof(codec));
        snprintf(line, sizeof(line), "%d|%d|%d|%d|%s|%s", kind, index, w, h, lang, codec);
        js = (*env)->NewStringUTF(env, line);
        (*env)->SetObjectArrayElement(env, arr, i, js);
        (*env)->DeleteLocalRef(env, js);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_FileAvNative_setSink(JNIEnv *env, jclass cls, jlong handle, jobject sink)
{
    FileAv *p = (FileAv *)(intptr_t)handle;
    JniSink *s;
    jclass sc;
    (void)cls;
    if (!p)
        return;
    s = (JniSink *)file_av_user(p);
    file_av_set_sinks(p, NULL, NULL, NULL, NULL, NULL, NULL);
    drop_sink(env, s);
    if (!sink)
        return;
    s = calloc(1, sizeof(*s));
    if (!s)
        return;
    (*env)->GetJavaVM(env, &s->jvm);
    s->sink = (*env)->NewGlobalRef(env, sink);
    sc = (*env)->GetObjectClass(env, sink);
    s->onFrame = (*env)->GetMethodID(env, sc, "onFrame", "(IILjava/nio/ByteBuffer;)V");
    s->onProgress = (*env)->GetMethodID(env, sc, "onProgress", "(DDII)V");
    s->onEvent = (*env)->GetMethodID(env, sc, "onEvent", "(ZLjava/lang/String;)V");
    s->onCaption = (*env)->GetMethodID(env, sc, "onCaption", "(IDDLjava/lang/String;)V");
    if (!s->onCaption && (*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if (!s->onCaption) {
        jclass iface = (*env)->FindClass(env, "io/ymusic/mse/FileAvSink");
        if (iface) {
            s->onCaption = (*env)->GetMethodID(env, iface, "onCaption", "(IDDLjava/lang/String;)V");
            (*env)->DeleteLocalRef(env, iface);
        }
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }
    file_av_set_sinks(p, on_frame, NULL, on_prog, on_event, on_caption, s);
}
