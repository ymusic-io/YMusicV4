#include "mse_host.h"
#include "mse_caps.h"
#include "mse_ffmpeg.h"

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    JavaVM *jvm;
    jobject sink;
    jmethodID onPcm;
    jmethodID onProgress;
} JniSink;

static JavaVM *g_jvm;
static jobject g_js_bridge;
static jmethodID g_js_fire;
static jobject g_log_bridge;
static jmethodID g_log_fire;

static void jni_js_event(const char *name)
{
    JNIEnv *env = NULL;
    jstring js;
    if (!g_jvm || !g_js_bridge || !g_js_fire || !name)
        return;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) != JNI_OK)
            return;
    }
    if (!env)
        return;
    js = (*env)->NewStringUTF(env, name);
    if (!js)
        return;
    (*env)->CallVoidMethod(env, g_js_bridge, g_js_fire, js);
    (*env)->DeleteLocalRef(env, js);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void register_js_event(JNIEnv *env)
{
    jclass cls;
    jfieldID inst;
    jobject obj;
    if (g_js_bridge)
        return;
    cls = (*env)->FindClass(env, "io/ymusic/mse/MseJsEventBridge");
    if (!cls)
        return;
    inst = (*env)->GetStaticFieldID(env, cls, "INSTANCE", "Lio/ymusic/mse/MseJsEventBridge;");
    if (!inst) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    obj = (*env)->GetStaticObjectField(env, cls, inst);
    if (!obj) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    g_js_bridge = (*env)->NewGlobalRef(env, obj);
    g_js_fire = (*env)->GetMethodID(env, cls, "fire", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, obj);
    if (!g_js_fire) {
        (*env)->DeleteGlobalRef(env, g_js_bridge);
        g_js_bridge = NULL;
        return;
    }
    mse_host_set_js_event(jni_js_event);
}

static void jni_log(int level, const char *msg)
{
    JNIEnv *env = NULL;
    jstring js;
    if (!g_jvm || !g_log_bridge || !g_log_fire || !msg)
        return;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, (void **)&env, NULL) != JNI_OK)
            return;
    }
    if (!env)
        return;
    js = (*env)->NewStringUTF(env, msg);
    if (!js)
        return;
    (*env)->CallVoidMethod(env, g_log_bridge, g_log_fire, (jint)level, js);
    (*env)->DeleteLocalRef(env, js);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void register_log(JNIEnv *env)
{
    jclass cls;
    jfieldID inst;
    jobject obj;
    if (g_log_bridge)
        return;
    cls = (*env)->FindClass(env, "io/ymusic/mse/MseLogBridge");
    if (!cls)
        return;
    inst = (*env)->GetStaticFieldID(env, cls, "INSTANCE", "Lio/ymusic/mse/MseLogBridge;");
    if (!inst) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    obj = (*env)->GetStaticObjectField(env, cls, inst);
    if (!obj) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    g_log_bridge = (*env)->NewGlobalRef(env, obj);
    g_log_fire = (*env)->GetMethodID(env, cls, "fire", "(ILjava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, obj);
    if (!g_log_fire) {
        (*env)->DeleteGlobalRef(env, g_log_bridge);
        g_log_bridge = NULL;
        return;
    }
    mse_set_log_callback(jni_log);
}

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

static void on_pcm(void *user, int rate, int ch, const int16_t *s16, int nbytes)
{
    JniSink *s = (JniSink *)user;
    JNIEnv *env;
    jbyteArray arr;
    if (!s || !s16 || nbytes <= 0)
        return;
    env = sink_env(s);
    if (!env)
        return;
    arr = (*env)->NewByteArray(env, nbytes);
    if (!arr)
        return;
    (*env)->SetByteArrayRegion(env, arr, 0, nbytes, (const jbyte *)s16);
    (*env)->CallVoidMethod(env, s->sink, s->onPcm, rate, ch, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void on_prog(void *user, double pos, double dur, int w, int h)
{
    JniSink *s = (JniSink *)user;
    JNIEnv *env = sink_env(s);
    if (!env)
        return;
    (*env)->CallVoidMethod(env, s->sink, s->onProgress, pos, dur, w, h);
}

#ifdef __ANDROID__
/* Android: create/destroy wrap the host with its Kotlin MseAudioOutput, so the raw calls get their own names. */
JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_MseHostNative_createHost(JNIEnv *env, jclass cls)
{
    (void)cls;
    (*env)->GetJavaVM(env, &g_jvm);
    register_js_event(env);
    register_log(env);
    return (jlong)(intptr_t)mse_host_create();
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_destroyHost(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    mse_host_destroy((MseHost *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_MseHostNative_audioSessionId(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return mse_host_audio_session((MseHost *)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL
Java_io_ymusic_mse_MseHostNative_videoFailure(JNIEnv *env, jclass cls, jlong handle)
{
    char reason[128];
    (void)cls;
    if (!mse_host_video_failure((MseHost *)(intptr_t)handle, reason, (int)sizeof(reason)))
        return NULL;
    return (*env)->NewStringUTF(env, reason);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_dacAttach(JNIEnv *env, jclass cls, jlong handle, jboolean attached)
{
    (void)env;
    (void)cls;
    mse_host_dac_attach((MseHost *)(intptr_t)handle, attached ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_MseHostNative_dacPoll(JNIEnv *env, jclass cls, jlong handle, jobject dst, jint cap,
                                         jint waitMs, jintArray state)
{
    int st[MSE_DAC_STATE_N];
    uint8_t *p = NULL;
    int n;
    (void)cls;
    memset(st, 0, sizeof(st));
    if (dst && cap > 0) {
        p = (uint8_t *)(*env)->GetDirectBufferAddress(env, dst);
        /* -1 is "host released": the sink thread stops for good on it. */
        if (!p || (*env)->GetDirectBufferCapacity(env, dst) < cap)
            return -2;
    }
    n = mse_host_dac_poll((MseHost *)(intptr_t)handle, p, p ? cap : 0, waitMs, st);
    if (state && (*env)->GetArrayLength(env, state) >= MSE_DAC_STATE_N)
        (*env)->SetIntArrayRegion(env, state, 0, MSE_DAC_STATE_N, (const jint *)st);
    return n;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_dacReport(JNIEnv *env, jclass cls, jlong handle, jint gen, jlong position,
                                           jlong written, jboolean pending, jlong bufferUs)
{
    (void)env;
    (void)cls;
    mse_host_dac_report((MseHost *)(intptr_t)handle, gen, position, written, pending ? 1 : 0, bufferUs);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_dacSession(JNIEnv *env, jclass cls, jlong handle, jint session)
{
    (void)env;
    (void)cls;
    mse_host_dac_session((MseHost *)(intptr_t)handle, session);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setDecoderCaps(JNIEnv *env, jclass cls, jintArray rows)
{
    jsize n;
    jint *v;
    (void)cls;
    n = rows ? (*env)->GetArrayLength(env, rows) : 0;
    if (n == 0 || n % MSE_CAP_ROW_INTS != 0) {
        jclass iae = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (iae)
            (*env)->ThrowNew(env, iae, "setDecoderCaps: rows must be n x 7 ints, n > 0");
        return;
    }
    v = (*env)->GetIntArrayElements(env, rows, NULL);
    if (!v)
        return;
    mse_caps_set_rows((const int *)v, n / MSE_CAP_ROW_INTS);
    (*env)->ReleaseIntArrayElements(env, rows, v, JNI_ABORT);
}
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    (void)reserved;
    g_jvm = vm;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    register_js_event(env);
    register_log(env);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_MseHostNative_vtable(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)mse_host_vtable();
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_MseHostNative_create(JNIEnv *env, jclass cls)
{
    (void)cls;
    (*env)->GetJavaVM(env, &g_jvm);
    register_js_event(env);
    register_log(env);
    return (jlong)(intptr_t)mse_host_create();
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_destroy(JNIEnv *env, jclass cls, jlong handle)
{
    MseHost *h = (MseHost *)(intptr_t)handle;
    (void)cls;
    if (!h)
        return;
    mse_host_destroy(h);
    (void)env;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setSink(JNIEnv *env, jclass cls, jlong handle, jobject sink)
{
    MseHost *h = (MseHost *)(intptr_t)handle;
    JniSink *s;
    jclass sc;
    (void)cls;
    if (!h)
        return;
    s = (JniSink *)calloc(1, sizeof(JniSink));
    if (!s)
        return;
    (*env)->GetJavaVM(env, &s->jvm);
    s->sink = (*env)->NewGlobalRef(env, sink);
    sc = (*env)->GetObjectClass(env, sink);
    s->onPcm = (*env)->GetMethodID(env, sc, "onPcm", "(II[B)V");
    s->onProgress = (*env)->GetMethodID(env, sc, "onProgress", "(DDII)V");
    mse_host_set_sinks(h, on_pcm, on_prog, s);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_MseHostNative_frames(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return mse_host_frames((MseHost *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_MseHostNative_samplesPlayed(JNIEnv *env, jclass cls, jlong handle)
{
    (void)env;
    (void)cls;
    return (jlong)mse_host_samples_played((MseHost *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_MseHostNative_copyFrameBytes(JNIEnv *env, jclass cls, jlong handle, jint afterGen, jintArray meta, jbyteArray dest)
{
    int w = 0, ht = 0, gen = 0, need, n;
    jbyte *dst;
    jint m[3];
    (void)cls;
    need = mse_host_copy_frame((MseHost *)(intptr_t)handle, NULL, 0, &w, &ht, &gen);
    if (need <= 0 || gen == afterGen)
        return 0;
    if (!dest || (*env)->GetArrayLength(env, dest) < need)
        return need;
    dst = (*env)->GetByteArrayElements(env, dest, NULL);
    if (!dst)
        return 0;
    n = mse_host_copy_frame((MseHost *)(intptr_t)handle, (uint8_t *)dst, need, &w, &ht, &gen);
    (*env)->ReleaseByteArrayElements(env, dest, dst, 0);
    if (n <= 0 || n > need)
        return 0;
    if (meta && (*env)->GetArrayLength(env, meta) >= 3) {
        m[0] = w;
        m[1] = ht;
        m[2] = gen;
        (*env)->SetIntArrayRegion(env, meta, 0, 3, m);
    }
    return n;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setVolume(JNIEnv *env, jclass cls, jlong handle, jfloat vol)
{
    (void)env;
    (void)cls;
    mse_host_set_volume((MseHost *)(intptr_t)handle, vol);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setSpeed(JNIEnv *env, jclass cls, jlong handle, jfloat speed, jfloat tempo)
{
    (void)env;
    (void)cls;
    mse_host_set_speed((MseHost *)(intptr_t)handle, speed, tempo);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setPitch(JNIEnv *env, jclass cls, jlong handle, jfloat pitch)
{
    (void)env;
    (void)cls;
    mse_host_set_pitch((MseHost *)(intptr_t)handle, pitch);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setEq(JNIEnv *env, jclass cls, jlong handle, jfloatArray bands)
{
    float tmp[10];
    jsize n;
    (void)cls;
    memset(tmp, 0, sizeof(tmp));
    if (bands) {
        n = (*env)->GetArrayLength(env, bands);
        if (n > 10)
            n = 10;
        (*env)->GetFloatArrayRegion(env, bands, 0, n, tmp);
    }
    mse_host_set_eq((MseHost *)(intptr_t)handle, tmp);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setBass(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    mse_host_set_bass((MseHost *)(intptr_t)handle, db);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setVirtualizer(JNIEnv *env, jclass cls, jlong handle, jfloat width)
{
    (void)env;
    (void)cls;
    mse_host_set_virtualizer((MseHost *)(intptr_t)handle, width);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setPreamp(JNIEnv *env, jclass cls, jlong handle, jfloat db)
{
    (void)env;
    (void)cls;
    mse_host_set_preamp((MseHost *)(intptr_t)handle, db);
}

JNIEXPORT jint JNICALL
Java_io_ymusic_mse_MseHostNative_pcmLevels(JNIEnv *env, jclass cls, jlong handle, jfloatArray out)
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
    got = mse_host_pcm_levels((MseHost *)(intptr_t)handle, tmp, (int)n);
    if (got > 0)
        (*env)->SetFloatArrayRegion(env, out, 0, got, tmp);
    return got;
}

JNIEXPORT jboolean JNICALL
Java_io_ymusic_mse_MseHostNative_videoSize(JNIEnv *env, jclass cls, jlong handle, jintArray outWh)
{
    int w = 0, ht = 0;
    jint wh[2];
    (void)cls;
    if (!outWh || (*env)->GetArrayLength(env, outWh) < 2)
        return JNI_FALSE;
    if (!mse_host_video_size((MseHost *)(intptr_t)handle, &w, &ht))
        return JNI_FALSE;
    wh[0] = w;
    wh[1] = ht;
    (*env)->SetIntArrayRegion(env, outWh, 0, 2, wh);
    return JNI_TRUE;
}

#ifndef __ANDROID__
JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_bindPresenter(JNIEnv *env, jclass cls, jlong handle, jlong presenter)
{
    (void)env;
    (void)cls;
    mse_host_bind_presenter((MseHost *)(intptr_t)handle, (struct VPresenter *)(intptr_t)presenter);
}
#endif

#ifdef __ANDROID__
#include <android/native_window.h>
#include <android/native_window_jni.h>

JNIEXPORT void JNICALL
Java_io_ymusic_mse_MseHostNative_setVideoSurface(JNIEnv *env, jclass cls, jlong handle, jobject surface)
{
    MseHost *h = (MseHost *)(intptr_t)handle;
    ANativeWindow *win = NULL;
    (void)cls;
    if (surface)
        win = ANativeWindow_fromSurface(env, surface);
    mse_host_set_video_surface(h, win);
}
#endif
