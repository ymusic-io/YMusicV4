/*
 * Minimal JNI transport for QuickJS (jvm/android). The pipe multiplexer speaks a few operations per
 * direction instead of one framed byte blob:
 *   host -> JS : open(id,cmd) -> __jsOpen; write(id,bytes,off,len)->accepted straight into the
 *                C pipe buffer (qjs_pipe_write — no JS on the write path); closeWrite(id,reason)
 *                -> qjs_pipe_close_write + __jsClose; closeRead(id) -> __jsCloseRead;
 *                drain(id) -> __jsDrain
 *   JS -> host : JS globals  __hostOpen / __hostData / __hostClose / __hostCloseRead
 *                -> statics  QuickJsNative.onOpen/onWrite/onCloseWrite/onCloseRead; the inbound
 *                DRAIN fires from the C pipe layer through drain_hook -> onDrain
 * Each pipe direction is a bounded (64 KB), flow-controlled byte stream:
 *   host -> JS : the ByteArray is held with GetPrimitiveArrayCritical and memcpy'd once into the
 *                C-owned pipe segments (see quickjs_pipe.h);
 *   JS -> host : the Uint8Array's bytes are exposed to onWrite as a direct ByteBuffer (valid only
 *                during the call).
 * write()'s return is the accepted byte count (backpressure: wait for a drain); -1 = EPIPE.
 * closeWrite(reason=null) = END; non-null = RESET. ALL routing lives in Kotlin and JS.
 *
 * CRITICAL-REGION DISCIPLINE: while the host ByteArray is held critically, qjs_pipe_write may
 * settle a parked read's promise — that only SCHEDULES reaction jobs, and the microtask pump runs
 * after the critical release. No JS executes on the write path, so JNI re-entry is impossible by
 * construction; the in_critical guards below stay as cheap defense (a __host* call under the
 * region throws, a drain is queued and flushed after release).
 *
 * Strings that cross (commands, reasons, eval source/result, errors) use standard UTF-8 <-> UTF-16
 * (NewString/GetStringChars), never NewStringUTF, so astral characters survive. Single-thread
 * confinement: every JNI entry point runs on the one Kotlin engine thread.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "quickjs.h"
#include "quickjs_json.h"
#include "quickjs_pipe.h"
#include "quickjs_hostfn.h"
#include "quickjs_web.h"
#include "quickjs_mse.h"
#include "quickjs_dump.h"
#include "quickjs_rejections.h"

#define PENDING_DRAIN_MAX 16

typedef struct Engine {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *jvm;
    QjsPipeCtx *pipe;
    jclass bridgeClass;   /* global ref to io.ymusic.quickjs.QuickJsNative */
    jmethodID onOpen;     /* static int onOpen(long, int, String): 0 accepted / -1 refused */
    jmethodID onWrite;    /* static int  onWrite(long, int, ByteBuffer) */
    jmethodID onCloseWrite; /* static void onCloseWrite(long, int, String|null) */
    jmethodID onCloseRead;  /* static void onCloseRead(long, int) */
    jmethodID onDrain;    /* static void onDrain(long, int) */
    jmethodID onLog;      /* static void onLog(long, int, String) */
    JSValue jsOpen, jsClose, jsCloseRead, jsDrain; /* JS mux entry points (C -> JS) */
    int in_critical;      /* a host ByteArray is held critically: JNI re-entry is forbidden */
    uint32_t pending_drains[PENDING_DRAIN_MAX];
    int pending_count;
    QjsRejections rejections;
    int js_depth;         /* JS frames on the C stack; rejections are judged only at the outermost pump */
    void *mse;
} Engine;

void *qjs_ctx_mse(JSContext *ctx)
{
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    return e ? e->mse : NULL;
}

/* ---- UTF-8 (standard) <-> UTF-16 (astral-correct) ---- */

static jstring utf8_to_jstring(JNIEnv *env, const char *s, size_t len) {
    jsize u16len = 0;
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)s[i];
        int adv = (c < 0x80) ? 1 : ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3
                 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        if (i + (size_t)adv > len) adv = 1;
        u16len += (adv == 4) ? 2 : 1;
        i += adv;
    }
    jchar *buf = (jchar *)malloc(sizeof(jchar) * (u16len > 0 ? u16len : 1));
    if (!buf) return NULL; /* OOM: a null jstring, same as NewString's own OOM return — callers tolerate it */
    jsize k = 0;
    i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)s[i];
        uint32_t cp;
        int adv;
        if (c < 0x80) { cp = c; adv = 1; }
        else if ((c & 0xE0) == 0xC0 && i + 2 <= len) { cp = ((uint32_t)(c & 0x1F) << 6) | (s[i+1] & 0x3F); adv = 2; }
        else if ((c & 0xF0) == 0xE0 && i + 3 <= len) { cp = ((uint32_t)(c & 0x0F) << 12) | ((uint32_t)(s[i+1] & 0x3F) << 6) | (s[i+2] & 0x3F); adv = 3; }
        else if ((c & 0xF8) == 0xF0 && i + 4 <= len) { cp = ((uint32_t)(c & 0x07) << 18) | ((uint32_t)(s[i+1] & 0x3F) << 12) | ((uint32_t)(s[i+2] & 0x3F) << 6) | (s[i+3] & 0x3F); adv = 4; }
        else { cp = 0xFFFD; adv = 1; }
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            buf[k++] = (jchar)(0xD800 + (cp >> 10));
            buf[k++] = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            buf[k++] = (jchar)cp;
        }
        i += adv;
    }
    jstring result = (*env)->NewString(env, buf, u16len);
    free(buf);
    return result;
}

static char *jstring_to_utf8(JNIEnv *env, jstring s, size_t *outLen) {
    jsize n = (*env)->GetStringLength(env, s);
    const jchar *u = (*env)->GetStringChars(env, s, NULL);
    /* n*4+1 (max UTF-8 expansion) must not overflow size_t — it can on 32-bit ABIs (armv7/x86)
     * for a pathological ~1 GB string. Guard before the malloc; callers treat NULL as OOM. */
    if (n < 0 || (size_t)n > (SIZE_MAX - 1) / 4) {
        (*env)->ReleaseStringChars(env, s, u);
        if (outLen) *outLen = 0;
        return NULL;
    }
    char *buf = (char *)malloc((size_t)n * 4 + 1);
    if (!buf) { /* OOM: release the pinned chars and report empty; callers guard the null (see create/evalSync) */
        (*env)->ReleaseStringChars(env, s, u);
        if (outLen) *outLen = 0;
        return NULL;
    }
    size_t k = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
            jchar lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) { cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00); i++; }
        }
        if (cp < 0x80) buf[k++] = (char)cp;
        else if (cp < 0x800) { buf[k++] = (char)(0xC0 | (cp >> 6)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { buf[k++] = (char)(0xE0 | (cp >> 12)); buf[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
        else { buf[k++] = (char)(0xF0 | (cp >> 18)); buf[k++] = (char)(0x80 | ((cp >> 12) & 0x3F)); buf[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
    }
    buf[k] = 0;
    (*env)->ReleaseStringChars(env, s, u);
    if (outLen) *outLen = k;
    return buf;
}

static void throw_qjs(JNIEnv *env, jstring msg) {
    jclass ex = (*env)->FindClass(env, "io/ymusic/quickjs/QuickJsException");
    jmethodID ctor = (*env)->GetMethodID(env, ex, "<init>", "(Ljava/lang/String;)V");
    jobject o = (*env)->NewObject(env, ex, ctor, msg);
    (*env)->Throw(env, (jthrowable)o);
}

static void report_rejection(void *user, const char *msg, size_t len);

static void pump_jobs(Engine *e) {
    JSContext *c;
    JS_UpdateStackTop(e->rt);
    e->js_depth++;
    while (JS_ExecutePendingJob(e->rt, &c) > 0) {}
    e->js_depth--;
    /* report_rejection calls into the JVM: never at a nested pump, never under the critical region */
    if (e->js_depth == 0 && !e->in_critical) qjs_rejections_flush(e->ctx, &e->rejections, report_rejection, e);
}

static JNIEnv *engine_env(Engine *e) {
    JNIEnv *env;
    (*e->jvm)->GetEnv(e->jvm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

/* Drains queued while a critical region was held (see file comment) — flush once JNI is legal. */
static void flush_pending(Engine *e, JNIEnv *env) {
    for (int i = 0; i < e->pending_count; i++)
        (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onDrain,
                                     (jlong)(intptr_t)e, (jint)e->pending_drains[i]);
    e->pending_count = 0;
}

/* A Kotlin callback that threw leaves a pending Java exception; continuing to run JS (or making
 * further JNI calls) with one pending is undefined behavior. Convert it into a JS exception so the
 * the transport JS's normal failure paths (RESET the pipe, reject the caller) take over.
 * Do not map this to open() rc=-1: that looks like "setTimeout refused" and hides the real throw. */
static int check_java_exception(Engine *e, JNIEnv *env, JSContext *ctx) {
    jthrowable t;
    jclass objCls, classCls, thCls;
    jmethodID getName, getMessage;
    jstring jcn, jmsg;
    char *cn = NULL, *msg = NULL;
    char line[512];
    (void)e;
    if (!(*env)->ExceptionCheck(env)) return 0;
    t = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    line[0] = 0;
    if (t) {
        objCls = (*env)->GetObjectClass(env, t);
        classCls = (*env)->FindClass(env, "java/lang/Class");
        thCls = (*env)->FindClass(env, "java/lang/Throwable");
        getName = classCls ? (*env)->GetMethodID(env, classCls, "getName", "()Ljava/lang/String;") : NULL;
        getMessage = thCls ? (*env)->GetMethodID(env, thCls, "getMessage", "()Ljava/lang/String;") : NULL;
        jcn = (getName && objCls) ? (jstring)(*env)->CallObjectMethod(env, objCls, getName) : NULL;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            jcn = NULL;
        }
        jmsg = getMessage ? (jstring)(*env)->CallObjectMethod(env, t, getMessage) : NULL;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            jmsg = NULL;
        }
        if (jcn) cn = jstring_to_utf8(env, jcn, NULL);
        if (jmsg) msg = jstring_to_utf8(env, jmsg, NULL);
        if (cn && msg && msg[0])
            snprintf(line, sizeof(line), "%s: %s", cn, msg);
        else if (cn && cn[0])
            snprintf(line, sizeof(line), "%s", cn);
        free(cn);
        free(msg);
        if (jcn) (*env)->DeleteLocalRef(env, jcn);
        if (jmsg) (*env)->DeleteLocalRef(env, jmsg);
        if (objCls) (*env)->DeleteLocalRef(env, objCls);
        if (classCls) (*env)->DeleteLocalRef(env, classCls);
        if (thCls) (*env)->DeleteLocalRef(env, thCls);
        (*env)->DeleteLocalRef(env, t);
    }
    JS_ThrowInternalError(ctx, "%s", line[0] ? line : "host callback threw");
    return 1;
}

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) -> 0 accepted | -1 refused (no handler / host callback failed) |
 * -2 called under a critical region (transport JS bug). C-error-code style: the JS side checks the
 * code and cleans up its registry — no exception crosses the boundary for the normal refusal. */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_NewInt32(ctx, -2);
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    size_t len = 0;
    const char *cmd;
    jstring jcmd;
    jint rc;
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    JS_ToUint32(ctx, &id, argv[0]);
    cmd = JS_ToCStringLen(ctx, &len, argv[1]);
    jcmd = utf8_to_jstring(env, cmd ? cmd : "", cmd ? len : 0);
    if (cmd) JS_FreeCString(ctx, cmd);
    rc = (*env)->CallStaticIntMethod(env, e->bridgeClass, e->onOpen, (jlong)(intptr_t)e, (jint)id, jcmd);
    (*env)->DeleteLocalRef(env, jcmd);
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    return JS_NewInt32(ctx, rc);
}

/* __hostData(id, u8, off, len) -> accepted byte count (or -1 EPIPE). The JS bytes cross as a direct
 * ByteBuffer over the typed array's memory — valid only during the call; the Kotlin mux consumes it
 * synchronously (parked reader's ByteArray and/or its okio buffer). */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostData during data delivery");
    JNIEnv *env = engine_env(e);
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    uint32_t id = 0;
    int64_t off = 0, len = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    jint rc = -1;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        /* ta_len is the view's BYTE length. off/len come from (remotely updatable) JS — an
         * unchecked range hands the host a DirectByteBuffer past the array: out-of-bounds read. */
        if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > (uint64_t)ta_len) {
            JS_FreeValue(ctx, ab);
            return JS_ThrowRangeError(ctx, "__hostData range out of bounds");
        }
        size_t sz = 0;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        if (ptr && len > 0) {
            jobject bb = (*env)->NewDirectByteBuffer(env, ptr + ta_off + off, (jlong)len);
            rc = (*env)->CallStaticIntMethod(env, e->bridgeClass, e->onWrite, (jlong)(intptr_t)e, (jint)id, bb);
            (*env)->DeleteLocalRef(env, bb);
            if (check_java_exception(e, env, ctx)) { JS_FreeValue(ctx, ab); return JS_EXCEPTION; }
        } else {
            rc = 0;
        }
    }
    JS_FreeValue(ctx, ab);
    return JS_NewInt32(ctx, rc);
}

/* __hostClose(id, reason)  — write-side half-close: reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostClose during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    jstring jreason = NULL;
    if (argc > 1 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])) {
        size_t rlen = 0;
        const char *reason = JS_ToCStringLen(ctx, &rlen, argv[1]);
        if (reason) {
            jreason = utf8_to_jstring(env, reason, rlen);
            JS_FreeCString(ctx, reason);
        } else {
            /* A RESET whose reason won't coerce must stay a RESET, not degrade to END (a non-null
             * jreason is RESET). Drop the pending exception and use a fallback reason — matches the
             * cinterop shim. */
            JS_FreeValue(ctx, JS_GetException(ctx));
            jreason = utf8_to_jstring(env, "reason coercion failed", 22);
        }
    }
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onCloseWrite, (jlong)(intptr_t)e, (jint)id, jreason);
    if (jreason) (*env)->DeleteLocalRef(env, jreason);
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    return JS_UNDEFINED;
}

/* __hostCloseRead(id) — abandon the read end: the host's next write gets EPIPE */
static JSValue js_host_close_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostCloseRead during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onCloseRead, (jlong)(intptr_t)e, (jint)id);
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    return JS_UNDEFINED;
}

/* __consoleLog(level, msg) — the bundle's console + diagnostics, straight to the Kotlin log hook
 * (no pipe: logging must work from the first instruction of the boot eval and when the pipe
 * layer itself is sick). Coercions run before the callback; nothing is held across them. */
static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__consoleLog during data delivery");
    JNIEnv *env = engine_env(e);
    int32_t level = 0;
    JS_ToInt32(ctx, &level, argv[0]);
    if (level < 0 || level > 3) level = 3;
    size_t len = 0;
    const char *msg = JS_ToCStringLen(ctx, &len, argv[1]);
    if (!msg) return JS_EXCEPTION;
    jstring jmsg = utf8_to_jstring(env, msg, len);
    JS_FreeCString(ctx, msg);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onLog, (jlong)(intptr_t)e, (jint)level, jmsg);
    (*env)->DeleteLocalRef(env, jmsg);
    if (check_java_exception(e, env, ctx)) return JS_EXCEPTION;
    return JS_UNDEFINED;
}

/* Rejections nobody handles are the classic silent async failure — surfaced on the log channel
 * (level 4) once the job queue drains (see quickjs_rejections.h): a breadcrumb, not a crash
 * record. Tracking makes no JNI call, so it is safe under the critical region. */
static void promise_rejection_tracker(JSContext *ctx, JSValueConst promise, JSValueConst reason,
                                      int is_handled, void *opaque) {
    Engine *e = (Engine *)opaque;
    if (!e) return;
    qjs_rejections_track(ctx, &e->rejections, promise, reason, is_handled);
}

static void report_rejection(void *user, const char *msg, size_t len) {
    Engine *e = (Engine *)user;
    JNIEnv *env = engine_env(e);
    jstring jm = utf8_to_jstring(env, msg, len);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onLog, (jlong)(intptr_t)e, (jint)4, jm);
    (*env)->DeleteLocalRef(env, jm);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

/* Inbound flow control from the C pipe layer: its buffer drained below low-water; resume the
 * host's parked writer. Fires from JS-initiated read paths only — never under the critical
 * region by construction; queued as defense if it ever were. */
static int drain_hook(void *user, uint32_t id) {
    Engine *e = (Engine *)user;
    if (e->in_critical) {
        if (e->pending_count < PENDING_DRAIN_MAX) {
            e->pending_drains[e->pending_count++] = id;
            return 0;
        }
        return -1;
    }
    JNIEnv *env = engine_env(e);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onDrain, (jlong)(intptr_t)e, (jint)id);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }
    return 0;
}

/* ---- JNI entry points (all on the single engine thread) ---- */

static Engine *engine_new(JNIEnv *env, jint appVersion) {
    Engine *e = (Engine *)calloc(1, sizeof(Engine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->jsOpen = e->jsClose = e->jsCloseRead = e->jsDrain = JS_UNDEFINED;
    JS_SetContextOpaque(e->ctx, e);
    (*env)->GetJavaVM(env, &e->jvm);

    jclass local = (*env)->FindClass(env, "io/ymusic/quickjs/QuickJsNative");
    e->bridgeClass = (jclass)(*env)->NewGlobalRef(env, local);
    e->onOpen = (*env)->GetStaticMethodID(env, e->bridgeClass, "onOpen", "(JILjava/lang/String;)I");
    e->onWrite = (*env)->GetStaticMethodID(env, e->bridgeClass, "onWrite", "(JILjava/nio/ByteBuffer;)I");
    e->onCloseWrite = (*env)->GetStaticMethodID(env, e->bridgeClass, "onCloseWrite", "(JILjava/lang/String;)V");
    e->onCloseRead = (*env)->GetStaticMethodID(env, e->bridgeClass, "onCloseRead", "(JI)V");
    e->onDrain = (*env)->GetStaticMethodID(env, e->bridgeClass, "onDrain", "(JI)V");
    e->onLog = (*env)->GetStaticMethodID(env, e->bridgeClass, "onLog", "(JILjava/lang/String;)V");

    JSValue global = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, global, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, global, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, global, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_SetPropertyStr(e->ctx, global, "__hostCloseRead", JS_NewCFunction(e->ctx, js_host_close_read, "__hostCloseRead", 1));
    JS_SetPropertyStr(e->ctx, global, "__consoleLog", JS_NewCFunction(e->ctx, js_console_log, "__consoleLog", 2));
    JS_FreeValue(e->ctx, global);
    qjs_register_hostfn(e->ctx);
    qjs_register_web(e->ctx);
    qjs_register_json(e->ctx);
    qjs_mse_install(e->ctx);
    e->pipe = qjs_register_pipe(e->ctx, drain_hook, e);
    qjs_install_versions(e->ctx, appVersion);
    JS_SetHostPromiseRejectionTracker(e->rt, promise_rejection_tracker, e);
    return e;
}

/* Throw the pending JS exception as a QuickJsException (falls back to [fallback]). */
static void throw_js_exception(JNIEnv *env, JSContext *ctx, const char *fallback) {
    JSValue exc = JS_GetException(ctx);
    size_t mlen = 0;
    const char *msg = JS_ToCStringLen(ctx, &mlen, exc);
    throw_qjs(env, utf8_to_jstring(env, msg ? msg : fallback, msg ? mlen : strlen(fallback)));
    if (msg) JS_FreeCString(ctx, msg);
    JS_FreeValue(ctx, exc);
}

static void engine_capture(Engine *e) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    e->jsOpen = JS_GetPropertyStr(e->ctx, g, "__jsOpen");
    e->jsClose = JS_GetPropertyStr(e->ctx, g, "__jsClose");
    e->jsCloseRead = JS_GetPropertyStr(e->ctx, g, "__jsCloseRead");
    e->jsDrain = JS_GetPropertyStr(e->ctx, g, "__jsDrain");
    JS_FreeValue(e->ctx, g);
}

JNIEXPORT jlong JNICALL
Java_io_ymusic_quickjs_QuickJsNative_create(JNIEnv *env, jobject thiz, jint appVersion) {
    return (jlong)(intptr_t)engine_new(env, appVersion);
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_evalEngine(JNIEnv *env, jobject thiz, jlong handle,
                                             jstring jscript) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t blen = 0;
    char *script = jstring_to_utf8(env, jscript, &blen);
    if (!script) { /* OOM converting the script source — don't feed JS_Eval a null */
        throw_qjs(env, utf8_to_jstring(env, "out of memory (script)", 22));
        return;
    }
    JS_UpdateStackTop(e->rt);
    e->js_depth++;
    JSValue bret = JS_Eval(e->ctx, script, blen, "<engine>", JS_EVAL_TYPE_GLOBAL);
    e->js_depth--;
    free(script);
    if (JS_IsException(bret)) {
        throw_js_exception(env, e->ctx, "engine script failed");
        return;
    }
    JS_FreeValue(e->ctx, bret);
    pump_jobs(e); /* top-level JS may open host pipes; let its microtasks settle before capture */
    engine_capture(e);
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_evalEngineBytecode(JNIEnv *env, jobject thiz, jlong handle,
                                                     jbyteArray jbc) {
    Engine *e = (Engine *)(intptr_t)handle;
    jsize bclen = (*env)->GetArrayLength(env, jbc);
    jbyte *bc = (*env)->GetByteArrayElements(env, jbc, NULL);
    JSValue fn = bc ? JS_ReadObject(e->ctx, (const uint8_t *)bc, (size_t)bclen, JS_READ_OBJ_BYTECODE)
                    : JS_ThrowInternalError(e->ctx, "out of memory (bytecode)");
    if (bc) (*env)->ReleaseByteArrayElements(env, jbc, bc, JNI_ABORT);
    if (JS_IsException(fn)) {
        throw_js_exception(env, e->ctx, "bad bytecode");
        return;
    }
    e->js_depth++;
    JSValue r = JS_EvalFunction(e->ctx, fn); /* consumes fn */
    e->js_depth--;
    if (JS_IsException(r)) {
        throw_js_exception(env, e->ctx, "engine bytecode failed");
        return;
    }
    JS_FreeValue(e->ctx, r);
    pump_jobs(e);
    engine_capture(e);
}

JNIEXPORT jbyteArray JNICALL
Java_io_ymusic_quickjs_QuickJsNative_compile(JNIEnv *env, jobject thiz, jstring jscript,
                                          jstring jfilename) {
    size_t slen = 0;
    char *script = jstring_to_utf8(env, jscript, &slen);
    char *filename = jstring_to_utf8(env, jfilename, NULL);
    if (!script || !filename) {
        free(script);
        free(filename);
        throw_qjs(env, utf8_to_jstring(env, "out of memory", 13));
        return NULL;
    }
    JSRuntime *rt = JS_NewRuntime();
    JSContext *ctx = JS_NewContext(rt);
    JS_SetStripInfo(rt, JS_STRIP_SOURCE); /* consumed at parse time — must precede JS_Eval */
    jbyteArray result = NULL;
    JSValue fn = JS_Eval(ctx, script, slen, filename, JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    free(script);
    free(filename);
    if (JS_IsException(fn)) {
        throw_js_exception(env, ctx, "compile failed");
    } else {
        size_t sz = 0;
        uint8_t *buf = JS_WriteObject(ctx, &sz, fn, JS_WRITE_OBJ_BYTECODE);
        if (!buf) {
            throw_js_exception(env, ctx, "bytecode write failed");
        } else {
            result = (*env)->NewByteArray(env, (jsize)sz);
            if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)sz, (const jbyte *)buf);
            js_free(ctx, buf);
        }
    }
    JS_FreeValue(ctx, fn);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return result;
}

JNIEXPORT jstring JNICALL
Java_io_ymusic_quickjs_QuickJsNative_evalSync(JNIEnv *env, jobject thiz, jlong handle,
                                           jstring jscript, jstring jfilename) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t slen = 0;
    char *script = jstring_to_utf8(env, jscript, &slen);
    char *filename = jstring_to_utf8(env, jfilename, NULL);
    if (!script || !filename) { /* OOM: don't hand JS_Eval a null source/name */
        free(script);
        free(filename);
        throw_qjs(env, utf8_to_jstring(env, "out of memory", 13));
        return NULL;
    }
    JS_UpdateStackTop(e->rt);
    e->js_depth++;
    JSValue val = JS_Eval(e->ctx, script, slen, filename, JS_EVAL_TYPE_GLOBAL);
    e->js_depth--;
    free(script);
    free(filename);
    pump_jobs(e);
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        size_t mlen = 0;
        const char *msg = JS_ToCStringLen(e->ctx, &mlen, exc);
        jstring jmsg = utf8_to_jstring(env, msg ? msg : "error", msg ? mlen : 5);
        if (msg) JS_FreeCString(e->ctx, msg);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, val);
        throw_qjs(env, jmsg);
        return NULL;
    }
    jstring result = NULL;
    if (!JS_IsUndefined(val) && !JS_IsNull(val)) {
        size_t rlen = 0;
        const char *res = JS_ToCStringLen(e->ctx, &rlen, val);
        if (res) { result = utf8_to_jstring(env, res, rlen); JS_FreeCString(e->ctx, res); }
    }
    JS_FreeValue(e->ctx, val);
    return result;
}

/* Host -> JS: call one of the JS mux entry points; return its int result (or exc_rc on a JS
 * exception). Frees argv, then runs the microtask pump. */
static int call_js(Engine *e, JSValue fn, int argc, JSValue *argv, int exc_rc) {
    JSValue g, r;
    JS_UpdateStackTop(e->rt);
    g = JS_GetGlobalObject(e->ctx);
    e->js_depth++;
    r = JS_Call(e->ctx, fn, g, argc, argv);
    e->js_depth--;
    int rc = exc_rc;
    if (!JS_IsException(r)) JS_ToInt32(e->ctx, &rc, r);
    else JS_FreeValue(e->ctx, JS_GetException(e->ctx)); /* transport JS bug; don't leave it pending */
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump_jobs(e);
    return rc;
}

JNIEXPORT jint JNICALL
Java_io_ymusic_quickjs_QuickJsNative_open(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jcommand) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t len = 0;
    char *cmd = jstring_to_utf8(env, jcommand, &len);
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), JS_NewStringLen(e->ctx, cmd, len) };
    free(cmd);
    return (jint)call_js(e, e->jsOpen, 2, argv, -1);
}

/* write(id, bytes, off, len) -> accepted. The ByteArray is held with GetPrimitiveArrayCritical and
 * memcpy'd once into the C pipe buffer — see the file comment for the discipline. */
JNIEXPORT jint JNICALL
Java_io_ymusic_quickjs_QuickJsNative_write(JNIEnv *env, jobject thiz, jlong handle, jint id,
                                        jbyteArray bytes, jint off, jint len) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (len <= 0) return 0;
    /* off/len come from the Kotlin mux; bound them against the array before pinning so a caller bug
     * can't hand qjs_pipe_write a pointer past the buffer (the native shim validates the same way for
     * JS-supplied ranges). Do it before GetPrimitiveArrayCritical — GetArrayLength is illegal under it. */
    jsize alen = (*env)->GetArrayLength(env, bytes);
    if (off < 0 || (jlong)off + (jlong)len > (jlong)alen)
        return -1;
    jbyte *base = (*env)->GetPrimitiveArrayCritical(env, bytes, NULL);
    if (!base) return -1;
    e->in_critical = 1;
    int rc = qjs_pipe_write(e->pipe, (uint32_t)id, (const uint8_t *)base + off, (size_t)len);
    e->in_critical = 0;
    (*env)->ReleasePrimitiveArrayCritical(env, bytes, base, JNI_ABORT);
    flush_pending(e, env); /* notifies queued while critical — JNI is legal again */
    pump_jobs(e);
    return (jint)rc;
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_closeWrite(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jreason) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (jreason != NULL) {
        size_t rlen = 0;
        char *reason = jstring_to_utf8(env, jreason, &rlen);
        qjs_pipe_close_write(e->pipe, (uint32_t)id, reason ? reason : "", rlen); /* RESET even if OOM'd the text */
        free(reason);
    } else {
        qjs_pipe_close_write(e->pipe, (uint32_t)id, NULL, 0);
    }
    JSValue argv[1] = { JS_NewUint32(e->ctx, (uint32_t)id) };
    call_js(e, e->jsClose, 1, argv, 0); /* JS-side bookkeeping (reap) */
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_closeRead(JNIEnv *env, jobject thiz, jlong handle, jint id) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue argv[1] = { JS_NewUint32(e->ctx, (uint32_t)id) };
    call_js(e, e->jsCloseRead, 1, argv, 0);
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_drain(JNIEnv *env, jobject thiz, jlong handle, jint id) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue argv[1] = { JS_NewUint32(e->ctx, (uint32_t)id) };
    call_js(e, e->jsDrain, 1, argv, 0);
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_setMseHost(JNIEnv *env, jobject thiz, jlong handle, jlong mseHost, jlong vtable)
{
    Engine *e = (Engine *)(intptr_t)handle;
    (void)env;
    (void)thiz;
    if (!e)
        return;
    qjs_mse_set_vt(vtable ? (const QjsMseVt *)(intptr_t)vtable : NULL);
    e->mse = mseHost ? (void *)(intptr_t)mseHost : NULL;
}

JNIEXPORT void JNICALL
Java_io_ymusic_quickjs_QuickJsNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsClose);
    JS_FreeValue(e->ctx, e->jsCloseRead);
    JS_FreeValue(e->ctx, e->jsDrain);
    e->mse = NULL;
    qjs_rejections_free(e->ctx, &e->rejections);
    (*env)->DeleteGlobalRef(env, e->bridgeClass);
    qjs_pipe_ctx_release(e->pipe, e->ctx);
    qjs_web_release(e->ctx);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt); /* PipeBuf finalizers return segments to the pool -> free the ctx after */
    qjs_pipe_ctx_free(e->pipe);
    free(e);
}
