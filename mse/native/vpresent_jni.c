#include "vpresent.h"
#include "mse_ffmpeg.h"

#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include "mse_d3d11.h"
#include <jawt.h>
#include <jawt_md.h>
#endif

typedef struct {
    JavaVM *jvm;
    jobject sink;
    jmethodID on_frame;
    jmethodID on_mode;
    /* Serialises onPresentMode so the last call carries the current mode. */
    pthread_mutex_t mode_mu;
    VPresenter *p; /* mode_mu */
    /* Direct buffers over the presenter's two alternating pictures; present_mu. */
    jobject direct[2];
    const uint8_t *direct_ptr[2];
    int direct_n[2];
    int direct_last;
} JniSink;

static JNIEnv *sink_env(JniSink *s)
{
    JNIEnv *env = NULL;
    if ((*s->jvm)->GetEnv(s->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK && env)
        return env;
    if ((*s->jvm)->AttachCurrentThread(s->jvm, (void **)&env, NULL) != JNI_OK)
        return NULL;
    return env;
}

static void sink_threw(JNIEnv *env, const char *what)
{
    if (!(*env)->ExceptionCheck(env))
        return;
    (*env)->ExceptionDescribe(env);
    mse_critf(MSE_LOG_WARN, "vpresent sink %s threw", what);
}

static jobject direct_for(JNIEnv *env, JniSink *s, const uint8_t *bgra, int n)
{
    jobject local;
    int i;
    for (i = 0; i < 2; i++) {
        if (s->direct[i] && s->direct_ptr[i] == bgra && s->direct_n[i] >= n) {
            s->direct_last = i;
            return s->direct[i];
        }
    }
    /* The one not shown last: the buffers alternate. */
    i = s->direct_last ^ 1;
    if (s->direct[i]) {
        (*env)->DeleteGlobalRef(env, s->direct[i]);
        s->direct[i] = NULL;
    }
    local = (*env)->NewDirectByteBuffer(env, (void *)bgra, (jlong)n);
    if (!local)
        return NULL;
    s->direct[i] = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!s->direct[i])
        return NULL;
    s->direct_ptr[i] = bgra;
    s->direct_n[i] = n;
    s->direct_last = i;
    return s->direct[i];
}

static void on_frame(void *user, int w, int h, const uint8_t *bgra)
{
    JniSink *s = user;
    JNIEnv *env;
    jobject buf;
    if (!bgra || w <= 0 || h <= 0 || (int64_t)w * h > INT_MAX / 4)
        return;
    env = sink_env(s);
    if (!env)
        return;
    buf = direct_for(env, s, bgra, w * h * 4);
    if (!buf) {
        sink_threw(env, "direct buffer");
        return;
    }
    (*env)->CallVoidMethod(env, s->sink, s->on_frame, (jint)w, (jint)h, buf);
    sink_threw(env, "onFrame");
}

static void on_mode(void *user, int mode)
{
    JniSink *s = user;
    JNIEnv *env = sink_env(s);
    if (!env)
        return;
    pthread_mutex_lock(&s->mode_mu);
    if (s->p)
        mode = vpresent_mode(s->p);
    (*env)->CallVoidMethod(env, s->sink, s->on_mode, (jint)mode);
    sink_threw(env, "onPresentMode");
    pthread_mutex_unlock(&s->mode_mu);
}

static void sink_free(JNIEnv *env, JniSink *s)
{
    int i;
    if (env) {
        for (i = 0; i < 2; i++) {
            if (s->direct[i])
                (*env)->DeleteGlobalRef(env, s->direct[i]);
        }
        if (s->sink)
            (*env)->DeleteGlobalRef(env, s->sink);
    }
    pthread_mutex_destroy(&s->mode_mu);
    free(s);
}

static void sink_release(void *user)
{
    JniSink *s = user;
    sink_free(sink_env(s), s);
}

#ifdef _WIN32
/* Resolved at runtime (Kotlin loads jawt): no link-time dependency on jawt.lib. */
static jboolean (WINAPI *g_jawt_get_awt)(JNIEnv *, JAWT *);

static int bind_jawt(void)
{
    HMODULE h;
    if (g_jawt_get_awt)
        return 1;
    h = GetModuleHandleW(L"jawt.dll");
    if (!h)
        h = LoadLibraryW(L"jawt.dll");
    if (!h) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt.dll not loaded");
        return 0;
    }
    g_jawt_get_awt = (jboolean (WINAPI *)(JNIEnv *, JAWT *))(void (*)(void))GetProcAddress(
        h, "_JAWT_GetAWT@8");
    if (!g_jawt_get_awt)
        g_jawt_get_awt = (jboolean (WINAPI *)(JNIEnv *, JAWT *))(void (*)(void))GetProcAddress(
            h, "JAWT_GetAWT");
    if (!g_jawt_get_awt) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetProcAddress failed");
        return 0;
    }
    return 1;
}

HWND mse_d3d11_jawt_hwnd(JNIEnv *env, jobject component)
{
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    JAWT_Win32DrawingSurfaceInfo *win;
    HWND hwnd = NULL;
    jclass canvas;

    if (!env || !component)
        return NULL;
    if (!bind_jawt())
        return NULL;
    canvas = (*env)->FindClass(env, "java/awt/Canvas");
    if (!canvas || !(*env)->IsInstanceOf(env, component, canvas)) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        mse_critf(MSE_LOG_WARN, "d3d11 jawt not a Canvas");
        return NULL;
    }
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_7;
    if (g_jawt_get_awt(env, &awt) == JNI_FALSE) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetAWT failed");
        return NULL;
    }
    ds = awt.GetDrawingSurface(env, component);
    if (!ds) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetDrawingSurface failed");
        return NULL;
    }
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(ds);
        mse_critf(MSE_LOG_WARN, "d3d11 jawt Lock failed");
        return NULL;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (dsi && dsi->platformInfo) {
        win = (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
        hwnd = win->hwnd;
    }
    if (dsi)
        ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    if (!hwnd)
        mse_critf(MSE_LOG_WARN, "d3d11 jawt drawing surface has no HWND");
    return hwnd;
}
#endif

JNIEXPORT jlong JNICALL
Java_io_ymusic_mse_VideoPresenterNative_create(JNIEnv *env, jclass cls, jobject canvas, jobject sink)
{
    JniSink *s;
    VpSink vs;
    VPresenter *p = NULL;
    jclass sc;
    (void)cls;
    if (!sink)
        return 0;
    s = calloc(1, sizeof(*s));
    if (!s)
        return 0;
    (*env)->GetJavaVM(env, &s->jvm);
    pthread_mutex_init(&s->mode_mu, NULL);
    sc = (*env)->GetObjectClass(env, sink);
    s->on_frame = (*env)->GetMethodID(env, sc, "onFrame", "(IILjava/nio/ByteBuffer;)V");
    if (s->on_frame)
        s->on_mode = (*env)->GetMethodID(env, sc, "onPresentMode", "(I)V");
    (*env)->DeleteLocalRef(env, sc);
    /* A missing method leaves NoSuchMethodError pending for the caller. */
    if (!s->on_frame || !s->on_mode) {
        sink_free(NULL, s);
        return 0;
    }
    s->sink = (*env)->NewGlobalRef(env, sink);
    memset(&vs, 0, sizeof(vs));
    vs.on_frame = on_frame;
    vs.on_mode = on_mode;
    vs.release = sink_release;
    vs.user = s;
#if defined(_WIN32)
    if (canvas) {
        HWND hwnd = mse_d3d11_jawt_hwnd(env, canvas);
        if (hwnd)
            p = vpresent_create_d3d11(hwnd, &vs);
    }
#elif defined(__APPLE__)
    if (canvas)
        p = vpresent_create_vt_overlay(env, canvas, &vs);
#else
    (void)canvas;
#endif
    if (!p)
        p = vpresent_create_cpu(&vs);
    if (!p) {
        mse_critf(MSE_LOG_WARN, "vpresent create failed");
        sink_free(env, s);
        return 0;
    }
    pthread_mutex_lock(&s->mode_mu);
    s->p = p;
    pthread_mutex_unlock(&s->mode_mu);
    mse_critf(MSE_LOG_INFO, "vpresent surface %p canvas=%d mode=%d", (void *)p, canvas ? 1 : 0,
              vpresent_mode(p));
    on_mode(s, vpresent_mode(p));
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_VideoPresenterNative_destroy(JNIEnv *env, jclass cls, jlong handle)
{
    VPresenter *p = (VPresenter *)(intptr_t)handle;
    (void)env;
    (void)cls;
    if (!p)
        return;
    vpresent_detach(p);
    vpresent_release(p);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_VideoPresenterNative_setSize(JNIEnv *env, jclass cls, jlong handle, jint w, jint h)
{
    (void)env;
    (void)cls;
    vpresent_set_size((VPresenter *)(intptr_t)handle, w, h);
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_VideoPresenterNative_layout(JNIEnv *env, jclass cls, jlong handle, jint x, jint y,
                                               jint w, jint h)
{
    (void)env;
    (void)cls;
#ifdef __APPLE__
    vpresent_vt_layout((VPresenter *)(intptr_t)handle, x, y, w, h);
#else
    (void)handle;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
#endif
}

JNIEXPORT void JNICALL
Java_io_ymusic_mse_VideoPresenterNative_setOverlayHidden(JNIEnv *env, jclass cls, jlong handle,
                                                         jboolean hidden)
{
    (void)env;
    (void)cls;
#ifdef __APPLE__
    vpresent_vt_set_hidden((VPresenter *)(intptr_t)handle, hidden ? 1 : 0);
#else
    (void)handle;
    (void)hidden;
#endif
}

/* Pixel count of the picture on screen; copied into rgb (TYPE_INT_RGB raster) only when it fits. */
JNIEXPORT jint JNICALL
Java_io_ymusic_mse_VideoPresenterNative_snapshot(JNIEnv *env, jclass cls, jlong handle,
                                                 jintArray rgb, jintArray wh)
{
    VPresenter *p = (VPresenter *)(intptr_t)handle;
    uint32_t *px;
    jint m[2];
    int w = 0, h = 0, n, got, cap;
    (void)cls;
    if (!p || !wh || (*env)->GetArrayLength(env, wh) < 2)
        return 0;
    n = vpresent_snapshot(p, NULL, 0, &w, &h);
    cap = rgb ? (int)(*env)->GetArrayLength(env, rgb) : 0;
    /* A scratch copy, not a critical region: the snapshot waits on present_mu, whose holder
       may be calling into Java. */
    if (n > 0 && cap >= n) {
        px = malloc((size_t)n * 4);
        if (!px)
            return 0;
        got = vpresent_snapshot(p, px, n, &w, &h);
        if (got > 0 && got <= n)
            (*env)->SetIntArrayRegion(env, rgb, 0, got, (const jint *)px);
        free(px);
        n = got;
    }
    if (n <= 0)
        return 0;
    m[0] = w;
    m[1] = h;
    (*env)->SetIntArrayRegion(env, wh, 0, 2, m);
    return n;
}

/* memcpy BGRA into TYPE_INT_RGB. IntBuffer.get of 4K is several ms. */
JNIEXPORT void JNICALL
Java_io_ymusic_mse_VideoPresenterNative_copyBgra(JNIEnv *env, jclass cls, jobject buf, jintArray rgb,
                                                 jint n)
{
    void *src;
    jint *dst;
    (void)cls;
    if (!buf || !rgb || n <= 0)
        return;
    src = (*env)->GetDirectBufferAddress(env, buf);
    if (!src || (*env)->GetDirectBufferCapacity(env, buf) < (jlong)n * 4 ||
        (*env)->GetArrayLength(env, rgb) < n)
        return;
    dst = (*env)->GetPrimitiveArrayCritical(env, rgb, NULL);
    if (!dst)
        return;
    memcpy(dst, src, (size_t)n * 4u);
    (*env)->ReleasePrimitiveArrayCritical(env, rgb, dst, 0);
}
