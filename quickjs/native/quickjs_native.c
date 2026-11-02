#include "quickjs_native.h"
#include "quickjs.h"
#include "quickjs_json.h"
#include "quickjs_pipe.h"
#include "quickjs_hostfn.h"
#include "quickjs_web.h"
#include "quickjs_mse.h"
#include "quickjs_dump.h"
#include "quickjs_rejections.h"
#include <stdlib.h>
#include <string.h>

struct QjsEngine {
    JSRuntime *rt;
    JSContext *ctx;
    QjsInboundCb cb;
    void *user;
    QjsPipeCtx *pipe;
    void *mse;
    JSValue jsOpen, jsClose, jsCloseRead, jsDrain; /* JS mux entry points (C -> JS) */
    QjsRejections rejections;
    int js_depth; /* JS frames on the C stack; rejections are judged only at the outermost pump */
};

void *qjs_ctx_mse(JSContext *ctx)
{
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    return e ? e->mse : NULL;
}

void qjs_set_mse_host(QjsEngine *e, void *host, const void *vt)
{
    if (!e)
        return;
    e->mse = host;
    qjs_mse_set_vt((const QjsMseVt *)vt);
}

/* Inbound flow control: the C pipe buffer drained below low-water; resume the host's parked
 * writer. Fired from JS-initiated read paths only (see quickjs_pipe.h). */
static int drain_hook(void *user, uint32_t id) {
    QjsEngine *e = (QjsEngine *)user;
    return e->cb ? e->cb(e->user, QJS_OP_DRAIN, id, NULL, 0) : 0;
}

static void report_rejection(void *user, const char *msg, size_t len) {
    QjsEngine *e = (QjsEngine *)user;
    if (e->cb) e->cb(e->user, QJS_OP_LOG, 4, (const uint8_t *)msg, len);
}

static void pump(QjsEngine *e) {
    JSContext *c;
    e->js_depth++;
    while (JS_ExecutePendingJob(e->rt, &c) > 0) { /* microtasks */ }
    e->js_depth--;
    if (e->js_depth == 0) qjs_rejections_flush(e->ctx, &e->rejections, report_rejection, e);
}

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) -> 0 accepted | -1 refused (no handler / host callback failed) */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    size_t len = 0;
    const char *cmd = JS_ToCStringLen(ctx, &len, argv[1]);
    int rc = -1;
    if (e->cb) rc = e->cb(e->user, QJS_OP_OPEN, id, (const uint8_t *)(cmd ? cmd : ""), cmd ? len : 0);
    if (cmd) JS_FreeCString(ctx, cmd);
    return JS_NewInt32(ctx, rc);
}

/* __hostData(id, u8, off, len) -> accepted byte count (or -1 EPIPE). The payload pointer is only
 * valid during this call; the Kotlin mux consumes it synchronously (parked reader / okio buffer). */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    int64_t off = 0, len = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    int rc = -1;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        /* ta_len is the view's BYTE length. off/len come from (remotely updatable) JS — an
         * unchecked range hands the host a pointer past the array: out-of-bounds read. */
        if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > (uint64_t)ta_len) {
            JS_FreeValue(ctx, ab);
            return JS_ThrowRangeError(ctx, "__hostData range out of bounds");
        }
        size_t sz = 0;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        if (ptr && e->cb && len > 0) rc = e->cb(e->user, QJS_OP_WRITE, id, ptr + ta_off + off, (size_t)len);
        else rc = 0;
    }
    JS_FreeValue(ctx, ab);
    return JS_NewInt32(ctx, rc);
}

/* __hostClose(id, reason)  — write-side half-close: reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    const char *reason = NULL; /* JS-owned; needs JS_FreeCString */
    const char *out = NULL;    /* what the callback sees: NULL = END, non-NULL = RESET */
    size_t rlen = 0;
    if (argc > 1 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])) {
        reason = JS_ToCStringLen(ctx, &rlen, argv[1]);
        if (reason) {
            out = reason;
        } else {
            /* A RESET whose reason won't coerce (Symbol / throwing toString) must NOT degrade to a
             * clean END — that reads as a valid EOF and silently truncates the stream. Drop the
             * pending exception and RESET with a fallback reason so the peer's read still throws. */
            JS_FreeValue(ctx, JS_GetException(ctx));
            out = "reason coercion failed";
            rlen = strlen(out);
        }
    }
    /* The trampoline returns 0 on success, -1 when the host callback threw. Unlike open/data the
     * return here isn't a protocol value, so a failure would vanish — raise it as a JS exception so
     * the transport JS's failure paths engage (mirrors the JNI check_java_exception path). */
    int rc = 0;
    if (e->cb) rc = e->cb(e->user, QJS_OP_CLOSE_WRITE, id, (const uint8_t *)out, rlen);
    if (reason) JS_FreeCString(ctx, reason);
    if (rc < 0) return JS_ThrowInternalError(ctx, "host callback failed (closeWrite)");
    return JS_UNDEFINED;
}

/* __hostCloseRead(id) — abandon the read end: the host's next write gets EPIPE */
static JSValue js_host_close_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    int rc = 0;
    if (e->cb) rc = e->cb(e->user, QJS_OP_CLOSE_READ, id, NULL, 0);
    if (rc < 0) return JS_ThrowInternalError(ctx, "host callback failed (closeRead)");
    return JS_UNDEFINED;
}

/* __consoleLog(level, msg) — the bundle's console + diagnostics, straight to the host callback
 * (no pipe: logging must work from the first instruction of the boot eval and when the pipe
 * layer itself is sick). Coercions run before the callback; nothing is held across them. */
static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    int32_t level = 0;
    JS_ToInt32(ctx, &level, argv[0]);
    if (level < 0 || level > 3) level = 3;
    size_t len = 0;
    const char *msg = JS_ToCStringLen(ctx, &len, argv[1]);
    if (!msg) return JS_EXCEPTION;
    if (e->cb) e->cb(e->user, QJS_OP_LOG, (uint32_t)level, (const uint8_t *)msg, len);
    JS_FreeCString(ctx, msg);
    return JS_UNDEFINED;
}

/* Rejections nobody handles are the classic silent async failure — surfaced on the log channel
 * once the job queue drains (see quickjs_rejections.h): a breadcrumb, not a crash record. */
static void promise_rejection_tracker(JSContext *ctx, JSValueConst promise, JSValueConst reason,
                                      int is_handled, void *opaque) {
    QjsEngine *e = (QjsEngine *)opaque;
    if (!e) return;
    qjs_rejections_track(ctx, &e->rejections, promise, reason, is_handled);
}

/* Extract the pending JS exception into *err (malloc'd; falls back to [fallback]). */
static void set_err(JSContext *ctx, char **err, const char *fallback) {
    JSValue exc = JS_GetException(ctx);
    const char *m = JS_ToCString(ctx, exc);
    if (err) *err = strdup(m ? m : fallback);
    if (m) JS_FreeCString(ctx, m);
    JS_FreeValue(ctx, exc);
}

static QjsEngine *engine_new(int app_version, const char *versions_json, QjsInboundCb cb, void *user) {
    QjsEngine *e = (QjsEngine *)calloc(1, sizeof(QjsEngine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->cb = cb;
    e->user = user;
    e->jsOpen = e->jsClose = e->jsCloseRead = e->jsDrain = JS_UNDEFINED;
    e->mse = NULL;
    JS_SetContextOpaque(e->ctx, e);

    JSValue g = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, g, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, g, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, g, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_SetPropertyStr(e->ctx, g, "__hostCloseRead", JS_NewCFunction(e->ctx, js_host_close_read, "__hostCloseRead", 1));
    JS_SetPropertyStr(e->ctx, g, "__consoleLog", JS_NewCFunction(e->ctx, js_console_log, "__consoleLog", 2));
    JS_FreeValue(e->ctx, g);
    qjs_register_hostfn(e->ctx);
    qjs_register_web(e->ctx);
    qjs_register_json(e->ctx);
    e->pipe = qjs_register_pipe(e->ctx, drain_hook, e);
    qjs_mse_install(e->ctx);
    qjs_install_versions(e->ctx, app_version, versions_json);
    JS_SetHostPromiseRejectionTracker(e->rt, promise_rejection_tracker, e);
    return e;
}

static void engine_capture(QjsEngine *e) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    e->jsOpen = JS_GetPropertyStr(e->ctx, g, "__jsOpen");
    e->jsClose = JS_GetPropertyStr(e->ctx, g, "__jsClose");
    e->jsCloseRead = JS_GetPropertyStr(e->ctx, g, "__jsCloseRead");
    e->jsDrain = JS_GetPropertyStr(e->ctx, g, "__jsDrain");
    JS_FreeValue(e->ctx, g);
}

QjsEngine *qjs_create(int app_version, const char *versions_json, QjsInboundCb cb, void *user) {
    return engine_new(app_version, versions_json, cb, user);
}

int qjs_eval_engine(QjsEngine *e, const char *script, char **err) {
    e->js_depth++;
    JSValue r = JS_Eval(e->ctx, script, strlen(script), "<engine>", JS_EVAL_TYPE_GLOBAL);
    e->js_depth--;
    if (JS_IsException(r)) {
        set_err(e->ctx, err, "engine script failed");
        return -1;
    }
    JS_FreeValue(e->ctx, r);
    pump(e); /* top-level JS may open host pipes; let its microtasks settle before capture */
    engine_capture(e);
    return 0;
}

int qjs_eval_engine_bc(QjsEngine *e, const uint8_t *bytecode, size_t len, char **err) {
    JSValue fn = JS_ReadObject(e->ctx, bytecode, len, JS_READ_OBJ_BYTECODE);
    if (JS_IsException(fn)) {
        set_err(e->ctx, err, "bad bytecode");
        return -1;
    }
    e->js_depth++;
    JSValue r = JS_EvalFunction(e->ctx, fn); /* consumes fn */
    e->js_depth--;
    if (JS_IsException(r)) {
        set_err(e->ctx, err, "engine bytecode failed");
        return -1;
    }
    JS_FreeValue(e->ctx, r);
    pump(e);
    engine_capture(e);
    return 0;
}

uint8_t *qjs_compile(const char *script, const char *filename, size_t *out_len, char **err) {
    JSRuntime *rt = JS_NewRuntime();
    JSContext *ctx = JS_NewContext(rt);
    JS_SetStripInfo(rt, JS_STRIP_SOURCE); /* consumed at parse time — must precede JS_Eval */
    uint8_t *out = NULL;
    *out_len = 0;
    JSValue fn = JS_Eval(ctx, script, strlen(script), filename ? filename : "<engine>",
                         JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_COMPILE_ONLY);
    if (JS_IsException(fn)) {
        set_err(ctx, err, "compile failed");
    } else {
        size_t sz = 0;
        uint8_t *buf = JS_WriteObject(ctx, &sz, fn, JS_WRITE_OBJ_BYTECODE);
        if (!buf) {
            set_err(ctx, err, "bytecode write failed");
        } else {
            /* copy out of the JS heap: the caller frees with qjs_free (plain free) */
            out = (uint8_t *)malloc(sz > 0 ? sz : 1);
            if (out) {
                memcpy(out, buf, sz);
                *out_len = sz;
            } else if (err) {
                *err = strdup("out of memory (bytecode)");
            }
            js_free(ctx, buf);
        }
    }
    JS_FreeValue(ctx, fn);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return out;
}

char *qjs_eval_sync(QjsEngine *e, const char *script, const char *filename, int *isError) {
    e->js_depth++;
    JSValue val = JS_Eval(e->ctx, script, strlen(script), filename ? filename : "<eval>", JS_EVAL_TYPE_GLOBAL);
    e->js_depth--;
    pump(e);
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *m = JS_ToCString(e->ctx, exc);
        char *out = strdup(m ? m : "error");
        if (m) JS_FreeCString(e->ctx, m);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, val);
        if (isError) *isError = 1;
        return out;
    }
    char *out = NULL;
    if (!JS_IsUndefined(val) && !JS_IsNull(val)) {
        const char *res = JS_ToCString(e->ctx, val);
        if (res) { out = strdup(res); JS_FreeCString(e->ctx, res); }
    }
    JS_FreeValue(e->ctx, val);
    if (isError) *isError = 0;
    return out;
}

/* ---- outbound: host -> JS (call the JS mux entry points) ---- */

/* Call [fn]; return its result as an int (or the given default on exception). Frees argv. Runs the
 * microtask pump afterward. */
static int call_js(QjsEngine *e, JSValue fn, int argc, JSValue *argv, int exc_rc) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    e->js_depth++;
    JSValue r = JS_Call(e->ctx, fn, g, argc, argv);
    e->js_depth--;
    int rc = exc_rc;
    if (!JS_IsException(r)) JS_ToInt32(e->ctx, &rc, r);
    else JS_FreeValue(e->ctx, JS_GetException(e->ctx)); /* transport JS bug; don't leave it pending */
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump(e);
    return rc;
}

int qjs_open(QjsEngine *e, uint32_t id, const char *command) {
    JSValue argv[2] = { JS_NewUint32(e->ctx, id), JS_NewString(e->ctx, command ? command : "") };
    return call_js(e, e->jsOpen, 2, argv, -1);
}

int qjs_write(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len) {
    if (len == 0) return 0;
    int rc = qjs_pipe_write(e->pipe, id, data, len);
    pump(e); /* a settled read's reactions run now, after the caller's memory was consumed */
    return rc;
}

void qjs_close_write(QjsEngine *e, uint32_t id, const char *reason) {
    qjs_pipe_close_write(e->pipe, id, reason, reason ? strlen(reason) : 0);
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsClose, 1, argv, 0); /* JS-side bookkeeping (reap) */
}

void qjs_close_read(QjsEngine *e, uint32_t id) {
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsCloseRead, 1, argv, 0);
}

void qjs_drain(QjsEngine *e, uint32_t id) {
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsDrain, 1, argv, 0);
}

void qjs_close(QjsEngine *e) {
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsClose);
    JS_FreeValue(e->ctx, e->jsCloseRead);
    JS_FreeValue(e->ctx, e->jsDrain);
    e->mse = NULL;
    qjs_rejections_free(e->ctx, &e->rejections);
    qjs_pipe_ctx_release(e->pipe, e->ctx);
    qjs_web_release(e->ctx);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt); /* PipeBuf finalizers return segments to the pool -> free the ctx after */
    qjs_pipe_ctx_free(e->pipe);
    free(e);
}

void qjs_free(void *p) {
    free(p);
}
