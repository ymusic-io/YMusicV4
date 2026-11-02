#include "quickjs_pipe.h"
#include "quickjs_json.h"
#include "cutils.h"
#include <stdlib.h>
#include <string.h>

#define PB_CAP 65536 /* per-direction buffer bound (must match QuickJsRuntime.kt) */
#define PB_LOW 32768

/* Keep at most this many idle blocks (32 * 8 KB = 256 KB); beyond that, free(). */
#define POOL_MAX_FREE 32

typedef struct {
    void *free_list; /* singly linked through each idle block's first word */
    int free_count;
} QjsPool;

static uint8_t *pool_take(QjsPool *p) {
    if (p->free_list) {
        uint8_t *blk = (uint8_t *)p->free_list;
        p->free_list = *(void **)blk;
        p->free_count--;
        return blk;
    }
    return (uint8_t *)malloc(QJS_SEG_SIZE);
}

static void pool_put(QjsPool *p, uint8_t *blk) {
    if (p->free_count >= POOL_MAX_FREE) {
        free(blk);
        return;
    }
    *(void **)blk = p->free_list;
    p->free_list = blk;
    p->free_count++;
}

/* An inbound segment: header + payload carved from one pool block. Payload bytes are [start, end). */
typedef struct PbSeg {
    struct PbSeg *next;
    uint32_t start, end;
} PbSeg;

#define PB_SEG_CAP ((uint32_t)(QJS_SEG_SIZE - sizeof(PbSeg)))
#define PB_SEG_DATA(s) ((uint8_t *)((s) + 1))

enum {
    PB_JOB_NONE, PB_JOB_RECV, PB_JOB_INT, PB_JOB_LONG, PB_JOB_LINE, PB_JOB_JSON, PB_JOB_UTF8,
    PB_JOB_BYTES
};

typedef struct PipeBuf PipeBuf;

struct QjsPipeCtx {
    JSContext *ctx;
    QjsPool pool;
    QjsPipeDrainFn drain;
    void *user;
    struct {
        uint32_t id;
        PipeBuf *pb;
        JSValue ref; /* keeps a registered PipeBuf alive: registered => never dangles */
    } *ents;
    int n, cap;
};

struct PipeBuf {
    JSContext *ctx;
    QjsPipeCtx *pc;
    uint32_t id;
    PbSeg *head, *tail;
    uint32_t buffered;
    uint8_t write_ended, read_closed, host_blocked, strict;
    char *reset_reason; /* non-NULL => every read throws it */
    int job;
    JSValue dst; /* RECV destination view (re-resolved at completion: it may have been detached) */
    uint32_t dst_off, dst_len;
    QjsJStream *jstream;    /* JSON */
    DynBuf acc;             /* LINE span / UTF8 / BYTES body */
    JSValue resolve, reject; /* the parked promise capability */
};

static JSClassID pipebuf_class_id;

/* ---- registry (id -> PipeBuf; tiny linear array — an engine has a handful of live pipes) ---- */

static PipeBuf *reg_find(QjsPipeCtx *pc, uint32_t id) {
    for (int i = 0; i < pc->n; i++)
        if (pc->ents[i].id == id) return pc->ents[i].pb;
    return NULL;
}

static int reg_add(QjsPipeCtx *pc, uint32_t id, PipeBuf *pb, JSValueConst obj) {
    if (pc->n == pc->cap) {
        int ncap = pc->cap ? pc->cap * 2 : 8;
        void *ne = realloc(pc->ents, (size_t)ncap * sizeof(*pc->ents));
        if (!ne) return -1;
        pc->ents = ne;
        pc->cap = ncap;
    }
    pc->ents[pc->n].id = id;
    pc->ents[pc->n].pb = pb;
    pc->ents[pc->n].ref = JS_DupValue(pc->ctx, obj);
    pc->n++;
    return 0;
}

/* Dropping the ref may finalize the PipeBuf — callers must not touch it afterwards. */
static void reg_remove(QjsPipeCtx *pc, uint32_t id) {
    for (int i = 0; i < pc->n; i++) {
        if (pc->ents[i].id == id) {
            JSValue ref = pc->ents[i].ref;
            pc->ents[i] = pc->ents[pc->n - 1];
            pc->n--;
            JS_FreeValue(pc->ctx, ref);
            return;
        }
    }
}

/* ---- shared helpers ---- */

/* Resolve a Uint8Array argument to its byte pointer + byte length. Returns NULL and throws on a
 * non-view or detached argument. */
static uint8_t *view_bytes(JSContext *ctx, JSValueConst v, size_t *plen) {
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, v, &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) return NULL;
    size_t sz = 0;
    uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
    JS_FreeValue(ctx, ab); /* the caller's argv keeps the buffer alive */
    if (!ptr) {
        JS_ThrowTypeError(ctx, "detached buffer");
        return NULL;
    }
    (void)bpe; /* ta_len is already the view's BYTE length (JS_GetTypedArrayBuffer's pbyte_length) */
    *plen = ta_len;
    return ptr + ta_off;
}

/* A plain Error with the given message (reads throw/reject Error, not InternalError — the reset
 * reason and stream errors are protocol data, not engine bugs). JS_NULL on allocation failure. */
static JSValue pb_error(JSContext *ctx, const char *msg) {
    JSValue e = JS_NewError(ctx);
    if (JS_IsException(e)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        return JS_NULL;
    }
    JSValue m = JS_NewString(ctx, msg);
    if (JS_IsException(m)) JS_FreeValue(ctx, JS_GetException(ctx));
    else JS_DefinePropertyValueStr(ctx, e, "message", m, JS_PROP_WRITABLE | JS_PROP_CONFIGURABLE);
    return e;
}

static JSValue pb_throw(JSContext *ctx, const char *msg) {
    return JS_Throw(ctx, pb_error(ctx, msg));
}

static JSValue pb_throw_ended(JSContext *ctx, uint32_t need) {
    char msg[48];
    snprintf(msg, sizeof(msg), "stream ended before %u bytes", (unsigned)need);
    return pb_throw(ctx, msg);
}

/* ---- segment chain ---- */

static void pb_release_head(PipeBuf *pb) {
    PbSeg *s = pb->head;
    pb->head = s->next;
    if (!pb->head) pb->tail = NULL;
    pool_put(&pb->pc->pool, (uint8_t *)s);
}

/* Consume n bytes from the head segment (n <= its remaining payload). */
static void pb_consume(PipeBuf *pb, uint32_t n) {
    PbSeg *s = pb->head;
    s->start += n;
    pb->buffered -= n;
    if (s->start == s->end) pb_release_head(pb);
}

static uint32_t pb_consume_copy(PipeBuf *pb, uint8_t *dst, uint32_t max) {
    uint32_t total = 0;
    while (total < max && pb->head) {
        PbSeg *s = pb->head;
        uint32_t n = s->end - s->start;
        if (n > max - total) n = max - total;
        memcpy(dst + total, PB_SEG_DATA(s) + s->start, n);
        pb_consume(pb, n);
        total += n;
    }
    return total;
}

static void pb_free_all(PipeBuf *pb) {
    while (pb->head) pb_release_head(pb);
    pb->buffered = 0;
}

/* Copy into segments up to the cap, packing the tail. Returns accepted, or -1 on block OOM. */
static int pb_fill(PipeBuf *pb, const uint8_t *data, size_t len) {
    size_t pos = 0;
    while (pos < len && pb->buffered < PB_CAP) {
        PbSeg *t = pb->tail;
        if (!t || t->end == PB_SEG_CAP) {
            uint8_t *blk = pool_take(&pb->pc->pool);
            if (!blk) return -1;
            t = (PbSeg *)blk;
            t->next = NULL;
            t->start = t->end = 0;
            if (pb->tail) pb->tail->next = t;
            else pb->head = t;
            pb->tail = t;
        }
        uint32_t n = PB_SEG_CAP - t->end;
        if (n > len - pos) n = (uint32_t)(len - pos);
        if (n > PB_CAP - pb->buffered) n = PB_CAP - pb->buffered;
        memcpy(PB_SEG_DATA(t) + t->end, data + pos, n);
        t->end += n;
        pb->buffered += n;
        pos += n;
    }
    return (int)pos;
}

static void pb_acc_reset(PipeBuf *pb) {
    pb->acc.size = 0;
    if (pb->acc.allocated_size > 65536) { /* don't retain a huge line/body buffer */
        dbuf_free(&pb->acc);
        dbuf_init(&pb->acc);
    }
}

static JSValue pb_acc_take_string(PipeBuf *pb) {
    JSValue s = JS_NewStringLen(pb->ctx, pb->acc.size ? (const char *)pb->acc.buf : "", pb->acc.size);
    pb_acc_reset(pb);
    return s;
}

static void pb_acc_buf_free(JSRuntime *rt, void *opaque, void *ptr) {
    (void)rt;
    (void)opaque;
    free(ptr);
}

/* Steal acc into an exact-size Uint8Array. DynBuf uses malloc/realloc, so the ArrayBuffer
 * free callback is free(), not js_free. */
static JSValue pb_acc_take_bytes(PipeBuf *pb) {
    JSContext *ctx = pb->ctx;
    size_t n = pb->acc.size;
    uint8_t *buf = pb->acc.buf;
    pb->acc.buf = NULL;
    pb->acc.size = 0;
    pb->acc.allocated_size = 0;
    dbuf_init(&pb->acc);
    if (n == 0) {
        free(buf);
        buf = NULL;
    } else if (buf) {
        uint8_t *tight = (uint8_t *)realloc(buf, n);
        if (tight) buf = tight;
    }
    JSValue ab = JS_NewArrayBuffer(ctx, buf, n, buf ? pb_acc_buf_free : NULL, NULL, 0);
    if (JS_IsException(ab)) {
        free(buf);
        return ab;
    }
    /* the constructor reads argv[1] (offset) and argv[2] (length) even when argc is smaller */
    JSValue args[3] = { ab, JS_UNDEFINED, JS_UNDEFINED };
    JSValue u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

/* ---- scanners (consume as they feed, so an over-cap line/body never deadlocks the writer) ---- */

/* 1 = line complete (*out owned), 0 = all buffered consumed into acc, -1 = exception. */
static int pb_line_scan(PipeBuf *pb, JSValue *out) {
    JSContext *ctx = pb->ctx;
    while (pb->head) {
        PbSeg *s = pb->head;
        uint8_t *p = PB_SEG_DATA(s) + s->start;
        uint32_t avail = s->end - s->start;
        uint8_t *hit = (uint8_t *)memchr(p, '\n', avail);
        if (!hit) {
            if (dbuf_put(&pb->acc, p, avail)) {
                JS_ThrowOutOfMemory(ctx);
                return -1;
            }
            pb_consume(pb, avail);
            continue;
        }
        uint32_t j = (uint32_t)(hit - p);
        JSValue str;
        if (pb->acc.size == 0) {
            /* fast path: the whole line lives in this segment — decode in place */
            uint32_t e = (j > 0 && p[j - 1] == '\r') ? j - 1 : j;
            str = JS_NewStringLen(ctx, (const char *)p, e);
        } else {
            if (dbuf_put(&pb->acc, p, j)) {
                JS_ThrowOutOfMemory(ctx);
                return -1;
            }
            size_t e = pb->acc.size;
            if (e > 0 && pb->acc.buf[e - 1] == '\r') e--;
            str = JS_NewStringLen(ctx, (const char *)pb->acc.buf, e);
        }
        pb_consume(pb, j + 1);
        pb_acc_reset(pb);
        if (JS_IsException(str)) return -1;
        *out = str;
        return 1;
    }
    return 0;
}

/* 1 = value complete (its tail stays queued), 0 = need more, -1 = exception. */
static int pb_json_feed(PipeBuf *pb) {
    while (pb->head && !qjs_jstream_done(pb->jstream)) {
        PbSeg *s = pb->head;
        uint32_t avail = s->end - s->start;
        int consumed = qjs_jstream_push(pb->jstream, PB_SEG_DATA(s) + s->start, avail);
        if (consumed < 0) return -1;
        if (consumed == 0 && !qjs_jstream_done(pb->jstream)) {
            JS_ThrowInternalError(pb->ctx, "json parser made no progress"); /* guard: a 0 spins the loop */
            return -1;
        }
        pb_consume(pb, (uint32_t)consumed);
    }
    return qjs_jstream_done(pb->jstream) ? 1 : 0;
}

/* 0 = all buffered consumed into acc, -1 = exception. */
static int pb_utf8_feed(PipeBuf *pb) {
    while (pb->head) {
        PbSeg *s = pb->head;
        uint32_t avail = s->end - s->start;
        if (dbuf_put(&pb->acc, PB_SEG_DATA(s) + s->start, avail)) {
            JS_ThrowOutOfMemory(pb->ctx);
            return -1;
        }
        pb_consume(pb, avail);
    }
    return 0;
}

/* ---- the pending job ---- */

static JSValue pb_read_be(PipeBuf *pb, uint32_t n) {
    uint8_t b[8];
    pb_consume_copy(pb, b, n);
    if (n == 4) {
        int32_t v = (int32_t)(((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) | ((uint32_t)b[2] << 8) | b[3]);
        return JS_NewInt32(pb->ctx, v);
    }
    int64_t v = 0;
    for (uint32_t i = 0; i < 8; i++) v = (v << 8) | b[i];
    return JS_NewInt64(pb->ctx, v); /* a Number: loses precision above 2^53, like the JS reader did */
}

/* Try to finish the current job from buffered bytes (+ EOF state).
 * 1 = done (*out owned), 0 = still pending, -1 = failed (JS exception pending). */
static int pb_try(PipeBuf *pb, JSValue *out) {
    JSContext *ctx = pb->ctx;
    int eof = pb->write_ended;
    switch (pb->job) {
    case PB_JOB_RECV:
        if (pb->buffered > 0) {
            size_t dlen = 0;
            uint8_t *d = view_bytes(ctx, pb->dst, &dlen);
            if (!d) return -1;
            if ((uint64_t)pb->dst_off + pb->dst_len > dlen) {
                JS_ThrowRangeError(ctx, "receive destination out of bounds");
                return -1;
            }
            *out = JS_NewInt32(ctx, (int32_t)pb_consume_copy(pb, d + pb->dst_off, pb->dst_len));
            return 1;
        }
        if (eof) { *out = JS_NewInt32(ctx, -1); return 1; }
        return 0;
    case PB_JOB_INT:
    case PB_JOB_LONG: {
        uint32_t need = pb->job == PB_JOB_INT ? 4 : 8;
        if (pb->buffered >= need) {
            *out = pb_read_be(pb, need);
            return JS_IsException(*out) ? -1 : 1;
        }
        if (eof) { pb_throw_ended(ctx, need); return -1; }
        return 0;
    }
    case PB_JOB_LINE: {
        int r = pb_line_scan(pb, out);
        if (r != 0 || !eof) return r;
        if (pb->strict) {
            pb_acc_reset(pb);
            pb_throw(ctx, "readUtf8Line(strict): stream exhausted before a newline");
            return -1;
        }
        if (pb->acc.size > 0) { /* final unterminated line, raw */
            *out = pb_acc_take_string(pb);
            return JS_IsException(*out) ? -1 : 1;
        }
        *out = JS_NULL;
        return 1;
    }
    case PB_JOB_JSON: {
        int r = pb_json_feed(pb);
        if (r == 0 && !eof) return 0;
        if (r >= 0) {
            *out = qjs_jstream_end(pb->jstream); /* at EOF-incomplete this throws */
            r = JS_IsException(*out) ? -1 : 1;
        }
        qjs_jstream_free(pb->jstream);
        pb->jstream = NULL;
        return r;
    }
    case PB_JOB_UTF8:
        if (pb_utf8_feed(pb)) return -1;
        if (!eof) return 0;
        *out = pb_acc_take_string(pb);
        return JS_IsException(*out) ? -1 : 1;
    case PB_JOB_BYTES:
        if (pb_utf8_feed(pb)) return -1;
        if (!eof) return 0;
        *out = pb_acc_take_bytes(pb);
        return JS_IsException(*out) ? -1 : 1;
    }
    return 0;
}

/* Drop per-job state (a job that never parked, or one being settled). */
static void pb_job_reset(PipeBuf *pb) {
    pb->job = PB_JOB_NONE;
    if (!JS_IsUndefined(pb->dst)) {
        JS_FreeValue(pb->ctx, pb->dst);
        pb->dst = JS_UNDEFINED;
    }
    if (pb->jstream) {
        qjs_jstream_free(pb->jstream);
        pb->jstream = NULL;
    }
}

/* Settle the parked promise. `result` is consumed. Returns -1 when the capability call itself
 * threw (a transport-JS bug — the caller poisons the pipe so the host writer fails loudly). */
static int pb_settle(PipeBuf *pb, int ok, JSValue result) {
    JSContext *ctx = pb->ctx;
    JSValue fn = ok ? pb->resolve : pb->reject;
    JSValue other = ok ? pb->reject : pb->resolve;
    pb->resolve = pb->reject = JS_UNDEFINED;
    pb_job_reset(pb);
    /* resolving a promise only schedules reaction jobs — legal under a JNI critical region */
    JSValue r = JS_Call(ctx, fn, JS_UNDEFINED, 1, &result);
    JS_FreeValue(ctx, result);
    JS_FreeValue(ctx, fn);
    JS_FreeValue(ctx, other);
    if (JS_IsException(r)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        return -1;
    }
    JS_FreeValue(ctx, r);
    return 0;
}

/* Advance the pending job after new bytes / EOF / RESET. 0 = ok, -1 = settle failure (poison).
 * Acts only on an ARMED job: between a read setting pb->job and pb_read_finish storing the
 * capability, the DRAIN hook runs host code that could re-enter here — a half-armed job must be
 * left alone (pb_read_finish re-runs this once armed, so nothing is missed). */
static int pb_progress(PipeBuf *pb) {
    if (pb->job == PB_JOB_NONE || JS_IsUndefined(pb->resolve)) return 0;
    if (pb->reset_reason) return pb_settle(pb, 0, pb_error(pb->ctx, pb->reset_reason));
    JSValue out = JS_UNDEFINED;
    int r = pb_try(pb, &out);
    if (r == 0) return 0;
    if (r == 1) return pb_settle(pb, 1, out);
    return pb_settle(pb, 0, JS_GetException(pb->ctx));
}

/* The pipe is unusable mid-stream (segment OOM, settle failure): poison the read side so this and
 * every later read throws, fail the parked reader, and report EPIPE to the host writer. */
static int pb_fail(PipeBuf *pb) {
    JSContext *ctx = pb->ctx;
    JSValue exc = JS_UNDEFINED;
    const char *m = NULL;
    if (JS_HasException(ctx)) {
        exc = JS_GetException(ctx);
        m = JS_ToCString(ctx, exc);
    }
    static const char prefix[] = "pipe buffering failed: ";
    const char *msg = m ? m : "internal error";
    size_t ml = strlen(msg);
    char *r = (char *)malloc(sizeof(prefix) + ml);
    if (r) {
        memcpy(r, prefix, sizeof(prefix) - 1);
        memcpy(r + sizeof(prefix) - 1, msg, ml + 1);
    }
    if (m) JS_FreeCString(ctx, m);
    JS_FreeValue(ctx, exc);
    free(pb->reset_reason);
    pb->reset_reason = r;
    pb->write_ended = 1; /* even if the reason string failed to allocate, reads terminate */
    pb_free_all(pb);
    if (pb->job != PB_JOB_NONE && !JS_IsUndefined(pb->resolve))
        pb_settle(pb, 0, pb_error(ctx, r ? r : "pipe buffering failed"));
    return -1;
}

/* ---- host entry points ---- */

int qjs_pipe_write(QjsPipeCtx *pc, uint32_t id, const uint8_t *data, size_t len) {
    PipeBuf *pb = reg_find(pc, id);
    if (!pb || pb->reset_reason) return -1;
    size_t pos = 0;
    for (;;) {
        int f = pb_fill(pb, data + pos, len - pos);
        if (f < 0) {
            JS_ThrowOutOfMemory(pb->ctx);
            return pb_fail(pb);
        }
        pos += (size_t)f;
        if (pos >= len) {
            if (pb_progress(pb) < 0) return pb_fail(pb);
            break;
        }
        /* cap full, input remains: only a consuming job can make room */
        if (pb->job == PB_JOB_NONE) break;
        uint32_t before = pb->buffered;
        if (pb_progress(pb) < 0) return pb_fail(pb);
        if (pb->buffered >= before) break;
    }
    if (pos < len) pb->host_blocked = 1; /* the writer parks; reads below LOW owe it a DRAIN */
    return (int)pos;
}

void qjs_pipe_close_write(QjsPipeCtx *pc, uint32_t id, const char *reason, size_t reason_len) {
    PipeBuf *pb = reg_find(pc, id);
    if (!pb) return;
    pb->write_ended = 1;
    if (reason) { /* RESET: discard buffered data; the parked/next read throws the reason */
        pb_free_all(pb);
        char *r = (char *)malloc(reason_len + 1);
        if (r) {
            memcpy(r, reason, reason_len);
            r[reason_len] = 0;
        }
        free(pb->reset_reason);
        pb->reset_reason = r ? r : strdup("pipe reset"); /* NULL only under double OOM: reads then EOF */
        pb_acc_reset(pb);
        pb_progress(pb); /* rejects an armed job with the reason */
    } else { /* END: EOF once drained; terminal-complete the parked job */
        pb_progress(pb);
    }
    reg_remove(pc, id); /* may finalize pb — last touch */
}

/* ---- JS natives ---- */

static PipeBuf *pb_get(JSContext *ctx, JSValueConst v) {
    return (PipeBuf *)JS_GetOpaque2(ctx, v, pipebuf_class_id);
}

static JSValue pb_guard(JSContext *ctx, PipeBuf *pb) {
    if (pb->read_closed) return pb_throw(ctx, "receive() after closeRead()");
    if (pb->job != PB_JOB_NONE) return pb_throw(ctx, "concurrent receive() on the same pipe");
    if (pb->reset_reason) return pb_throw(ctx, pb->reset_reason);
    return JS_UNDEFINED;
}

/* Reads below the low-water mark owe the parked host writer a DRAIN. Read paths only — never
 * reached from qjs_pipe_write (the host writer is mid-write there, not parked), which keeps the
 * critical-region rule by construction. */
static JSValue pb_drain_check(PipeBuf *pb) {
    QjsPipeCtx *pc = pb->pc;
    if (pb->host_blocked && pb->buffered <= PB_LOW) {
        pb->host_blocked = 0;
        if (pc->drain && pc->drain(pc->user, pb->id) < 0)
            return JS_ThrowInternalError(pb->ctx, "host callback failed (drain)");
    }
    return JS_UNDEFINED;
}

/* Shared tail of every read native: finish synchronously when possible, else park and return the
 * Promise that qjs_pipe_write / close_write settles. Callers set the job params first. */
static JSValue pb_read_finish(JSContext *ctx, PipeBuf *pb) {
    JSValue out = JS_UNDEFINED;
    int r = pb_try(pb, &out);
    // Error teardown drops any bytes pb_try scanned into `acc` (a partial line/body): the pipe is
    // dead on these paths (OOM / a poisoned drain hook), and leaving them would make a later
    // different-typed read — which reads segments only — skip them. The park path (r==0) keeps
    // `acc` on purpose (it survives across writes).
    if (JS_IsException(pb_drain_check(pb))) {
        if (r == 1) JS_FreeValue(ctx, out);
        pb_acc_reset(pb);
        pb_job_reset(pb);
        return JS_EXCEPTION;
    }
    if (r < 0) {
        pb_acc_reset(pb);
        pb_job_reset(pb);
        return JS_EXCEPTION;
    }
    if (r == 1) {
        pb_job_reset(pb);
        return out;
    }
    JSValue funcs[2];
    JSValue promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        pb_acc_reset(pb);
        pb_job_reset(pb);
        return promise;
    }
    pb->resolve = funcs[0];
    pb->reject = funcs[1];
    /* the drain hook above ran host code while the job was half-armed — catch anything it did */
    if (pb_progress(pb) < 0) pb_fail(pb);
    return promise;
}

/* __pbNew(id) -> PipeBuf handle, registered for host writes. */
static JSValue js_pb_new(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsPipeCtx *pc = (QjsPipeCtx *)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    if (reg_find(pc, id)) return JS_ThrowInternalError(ctx, "pipe id already registered");
    PipeBuf *pb = (PipeBuf *)calloc(1, sizeof(PipeBuf));
    if (!pb) return JS_ThrowOutOfMemory(ctx);
    pb->ctx = ctx;
    pb->pc = pc;
    pb->id = id;
    pb->dst = pb->resolve = pb->reject = JS_UNDEFINED;
    dbuf_init(&pb->acc);
    JSValue obj = JS_NewObjectClass(ctx, pipebuf_class_id);
    if (JS_IsException(obj)) {
        free(pb);
        return obj;
    }
    JS_SetOpaque(obj, pb);
    if (reg_add(pc, id, pb, obj)) {
        JS_FreeValue(ctx, obj); /* finalizer frees pb */
        return JS_ThrowOutOfMemory(ctx);
    }
    return obj;
}

/* __pbRecv(pb, dst, off, len) -> count | -1 EOF | Promise. */
static JSValue js_pb_recv(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    // Coerce every argument BEFORE the guard and BEFORE arming any job: coercion of an object arg
    // runs user JS (valueOf) that could reenter and arm a job on this same pb; guarding afterward
    // then rejects it as a concurrent read instead of silently overwriting (and leaking) it.
    int64_t off = 0, len = 0;
    if (JS_ToInt64(ctx, &off, argv[2]) || JS_ToInt64(ctx, &len, argv[3])) return JS_EXCEPTION;
    size_t dlen = 0;
    if (!view_bytes(ctx, argv[1], &dlen)) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > dlen)
        return JS_ThrowRangeError(ctx, "receive destination out of bounds");
    if (len == 0) return JS_NewInt32(ctx, 0);
    pb->job = PB_JOB_RECV;
    pb->dst = JS_DupValue(ctx, argv[1]);
    pb->dst_off = (uint32_t)off;
    pb->dst_len = (uint32_t)len;
    return pb_read_finish(ctx, pb);
}

/* __pbInt(pb) / __pbLong(pb) -> number | Promise (big-endian fixed width). */
static JSValue js_pb_int(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    pb->job = PB_JOB_INT;
    return pb_read_finish(ctx, pb);
}

static JSValue js_pb_long(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    pb->job = PB_JOB_LONG;
    return pb_read_finish(ctx, pb);
}

/* __pbLine(pb, strict) -> string | null | Promise. */
static JSValue js_pb_line(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    uint8_t strict = (uint8_t)JS_ToBool(ctx, argv[1]); // ToBoolean never runs user JS; still coerce first
    pb->job = PB_JOB_LINE;
    pb->strict = strict;
    return pb_read_finish(ctx, pb);
}

/* __pbJson(pb, expect) -> value | Promise (stops at the value's end; the tail stays queued). */
static JSValue js_pb_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    int32_t expect = QJS_JSON_EXPECT_ANY;
    if (JS_ToInt32(ctx, &expect, argv[1])) return JS_EXCEPTION; // coerce before guard/arm (see js_pb_recv)
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    pb->jstream = qjs_jstream_new(ctx, expect);
    if (!pb->jstream) return JS_EXCEPTION;
    pb->job = PB_JOB_JSON;
    return pb_read_finish(ctx, pb);
}

/* __pbUtf8(pb) -> string | Promise (the whole remaining body, decoded once at EOF). */
static JSValue js_pb_utf8(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    pb->job = PB_JOB_UTF8;
    return pb_read_finish(ctx, pb);
}

/* __pbBytes(pb) -> Uint8Array | Promise (the whole remaining body, one C accumulator). */
static JSValue js_pb_bytes(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (JS_IsException(pb_guard(ctx, pb))) return JS_EXCEPTION;
    pb->job = PB_JOB_BYTES;
    return pb_read_finish(ctx, pb);
}

/* __pbCloseRead(pb) — abandon the read side; the host's next write returns -1. */
static JSValue js_pb_close_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    PipeBuf *pb = pb_get(ctx, argv[0]);
    if (!pb) return JS_EXCEPTION;
    if (pb->read_closed) return JS_UNDEFINED;
    pb->read_closed = 1;
    pb_free_all(pb);
    // The resolve guard matches pb_fail: only a fully-parked job (capability stored) can be settled.
    // In reachable states a job here is always parked; the guard hardens against a half-armed reentry.
    if (pb->job != PB_JOB_NONE && !JS_IsUndefined(pb->resolve)) {
        pb_acc_reset(pb);
        pb_settle(pb, 0, pb_error(ctx, "receive() after closeRead()")); /* in-flight read is a misuse */
    }
    reg_remove(pb->pc, pb->id); /* argv[0] keeps the object alive */
    return JS_UNDEFINED;
}

/* __utf8Decode(u8, off, len) -> string. Native UTF-8 -> JS string (astral-correct). */
static JSValue js_utf8_decode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t off = 0, len = 0;
    JS_ToInt64(ctx, &off, argv[1]);
    JS_ToInt64(ctx, &len, argv[2]);
    size_t src_len = 0;
    uint8_t *src = view_bytes(ctx, argv[0], &src_len);
    if (!src) return JS_EXCEPTION;
    if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > src_len)
        return JS_ThrowRangeError(ctx, "__utf8Decode range out of bounds");
    return JS_NewStringLen(ctx, (const char *)(src + off), (size_t)len);
}

/* From the BEGIN/END patch at the end of the vendored quickjs.c: direct flat-string storage access
 * (narrow = Latin-1, wide = UTF-16; ropes linearize once and cache). */
JSValue JS_ToFlatString(JSContext *ctx, JSValueConst v);
int JS_GetFlatStringStorage(JSValueConst v, const void **pdata, uint32_t *plen);

/* Encode UTF-16 units [i, end) of flat storage into q, which the caller has sized for the worst
 * case (3 bytes/unit; a pair's 4 bytes span 2 units). A lone surrogate goes out as WTF-8,
 * consistent with the rest of the stack. Returns one past the last byte written. */
static uint8_t *utf8_encode_units(const void *data, int wide, uint32_t i, uint32_t end, uint8_t *q) {
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) {
            uint8_t c = sp[i];
            if (c < 0x80) {
                *q++ = c;
            } else {
                *q++ = 0xC0 | (c >> 6);
                *q++ = 0x80 | (c & 0x3F);
            }
        }
        return q;
    }
    const uint16_t *sp = (const uint16_t *)data;
    while (i < end) {
        uint32_t c = sp[i];
        if (c < 0x80) {
            *q++ = c;
            i++;
        } else if (c < 0x800) {
            *q++ = 0xC0 | (c >> 6);
            *q++ = 0x80 | (c & 0x3F);
            i++;
        } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end && sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
            uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (sp[i + 1] - 0xDC00);
            *q++ = 0xF0 | (cp >> 18);
            *q++ = 0x80 | ((cp >> 12) & 0x3F);
            *q++ = 0x80 | ((cp >> 6) & 0x3F);
            *q++ = 0x80 | (cp & 0x3F);
            i += 2;
        } else {
            *q++ = 0xE0 | (c >> 12);
            *q++ = 0x80 | ((c >> 6) & 0x3F);
            *q++ = 0x80 | (c & 0x3F);
            i++;
        }
    }
    return q;
}

static size_t utf8_measure_units(const void *data, int wide, uint32_t i, uint32_t end) {
    size_t n = 0;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) n += (sp[i] < 0x80) ? 1 : 2;
        return n;
    }
    const uint16_t *sp = (const uint16_t *)data;
    while (i < end) {
        uint32_t c = sp[i];
        if (c < 0x80) { n += 1; i++; }
        else if (c < 0x800) { n += 2; i++; }
        else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end && sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) { n += 4; i += 2; }
        else { n += 3; i++; }
    }
    return n;
}

/* __utf8Into(str, from, to, dst, dstOff) -> bytes written. One-shot encode of str[from, to)
 * (UTF-16 unit indices) straight from flat string storage into dst[dstOff..]. The caller
 * guarantees fit via the (to - from) * 3 worst case; a range that could overflow throws. */
static JSValue js_utf8_into(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t from = 0, to = 0, dst_off = 0;
    JS_ToInt64(ctx, &from, argv[1]);
    JS_ToInt64(ctx, &to, argv[2]);
    JS_ToInt64(ctx, &dst_off, argv[4]);
    size_t dlen = 0;
    uint8_t *dst = view_bytes(ctx, argv[3], &dlen);
    if (!dst) return JS_EXCEPTION;
    if (from < 0 || to < from || dst_off < 0 || (uint64_t)dst_off > dlen)
        return JS_ThrowRangeError(ctx, "__utf8Into range out of bounds");
    JSValue flat = JS_ToFlatString(ctx, argv[0]);
    if (JS_IsException(flat)) return flat;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "__utf8Into: not a string");
    }
    uint32_t i = (from > (int64_t)slen) ? slen : (uint32_t)from;
    uint32_t end = (to > (int64_t)slen) ? slen : (uint32_t)to;
    if (i > end) i = end;
    if ((uint64_t)(end - i) * 3 > dlen - (size_t)dst_off) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowRangeError(ctx, "__utf8Into: dst too small");
    }
    uint8_t *q = utf8_encode_units(data, wide, i, end, dst + dst_off);
    JS_FreeValue(ctx, flat);
    return JS_NewInt32(ctx, (int32_t)(q - (dst + dst_off)));
}

static void utf8_encode_buf_free(JSRuntime *rt, void *opaque, void *ptr) {
    js_free_rt(rt, ptr);
}

/* __utf8Encode(str, from, to) -> Uint8Array. One-shot encode of str[from, to) into an exact-size
 * C buffer wrapped zero-copy (freed with the array). For strings too big to stage: the sink
 * flushes, then streams the returned array through send() under the normal backpressure. */
static JSValue js_utf8_encode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t from = 0, to = 0;
    JS_ToInt64(ctx, &from, argv[1]);
    JS_ToInt64(ctx, &to, argv[2]);
    if (from < 0 || to < from)
        return JS_ThrowRangeError(ctx, "__utf8Encode range out of bounds");
    JSValue flat = JS_ToFlatString(ctx, argv[0]);
    if (JS_IsException(flat)) return flat;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "__utf8Encode: not a string");
    }
    uint32_t i = (from > (int64_t)slen) ? slen : (uint32_t)from;
    uint32_t end = (to > (int64_t)slen) ? slen : (uint32_t)to;
    if (i > end) i = end;
    size_t n = utf8_measure_units(data, wide, i, end);
    uint8_t *buf = NULL;
    if (n > 0) {
        buf = (uint8_t *)js_malloc(ctx, n);
        if (!buf) {
            JS_FreeValue(ctx, flat);
            return JS_EXCEPTION;
        }
        utf8_encode_units(data, wide, i, end, buf);
    }
    JS_FreeValue(ctx, flat);
    JSValue ab = JS_NewArrayBuffer(ctx, buf, n, buf ? utf8_encode_buf_free : NULL, NULL, 0);
    if (JS_IsException(ab)) {
        js_free(ctx, buf);
        return ab;
    }
    /* the constructor reads argv[1] (offset) and argv[2] (length) even when argc is smaller */
    JSValue args[3] = { ab, JS_UNDEFINED, JS_UNDEFINED };
    JSValue u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

/* __utf8Length(str, from, to) -> number. The exact byte count __utf8Encode(str, from, to) would
 * produce, measured without encoding — the same utf8_measure_units the encoder sizes with, so the
 * two can never disagree (a JS mirror would drift on lone surrogates). */
static JSValue js_utf8_length(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t from = 0, to = 0;
    JS_ToInt64(ctx, &from, argv[1]);
    JS_ToInt64(ctx, &to, argv[2]);
    if (from < 0 || to < from)
        return JS_ThrowRangeError(ctx, "__utf8Length range out of bounds");
    JSValue flat = JS_ToFlatString(ctx, argv[0]);
    if (JS_IsException(flat)) return flat;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "__utf8Length: not a string");
    }
    uint32_t i = (from > (int64_t)slen) ? slen : (uint32_t)from;
    uint32_t end = (to > (int64_t)slen) ? slen : (uint32_t)to;
    if (i > end) i = end;
    size_t n = utf8_measure_units(data, wide, i, end);
    JS_FreeValue(ctx, flat);
    return JS_NewInt64(ctx, (int64_t)n);
}

static const char B64URL[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/* __b64urlInto(src, srcOff, srcLen, dst, dstOff) -> dst bytes written. Encodes 3-byte
 * groups into 4 chars while they fit; a 1–2 byte tail (when srcLen < 3) emits 2 or 3
 * chars with no padding. */
static JSValue js_b64url_into(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    int64_t src_off = 0, src_len = 0, dst_off = 0;
    size_t slen = 0, dlen = 0, n, room, wrote = 0;
    uint8_t *src, *dst;
    const uint8_t *p;
    uint8_t *q;
    (void)this_val;
    (void)argc;
    JS_ToInt64(ctx, &src_off, argv[1]);
    JS_ToInt64(ctx, &src_len, argv[2]);
    JS_ToInt64(ctx, &dst_off, argv[4]);
    src = view_bytes(ctx, argv[0], &slen);
    if (!src)
        return JS_EXCEPTION;
    dst = view_bytes(ctx, argv[3], &dlen);
    if (!dst)
        return JS_EXCEPTION;
    if (src_off < 0 || src_len < 0 || dst_off < 0 ||
        (uint64_t)src_off + (uint64_t)src_len > slen || (uint64_t)dst_off > dlen)
        return JS_ThrowRangeError(ctx, "__b64urlInto range out of bounds");
    p = src + (size_t)src_off;
    q = dst + (size_t)dst_off;
    n = (size_t)src_len;
    room = dlen - (size_t)dst_off;
    while (n >= 3 && room >= 4) {
        unsigned v = ((unsigned)p[0] << 16) | ((unsigned)p[1] << 8) | p[2];
        q[0] = (uint8_t)B64URL[(v >> 18) & 63];
        q[1] = (uint8_t)B64URL[(v >> 12) & 63];
        q[2] = (uint8_t)B64URL[(v >> 6) & 63];
        q[3] = (uint8_t)B64URL[v & 63];
        p += 3;
        n -= 3;
        q += 4;
        room -= 4;
        wrote += 4;
    }
    if (n > 0 && n < 3 && room >= (n == 1 ? 2u : 3u)) {
        unsigned v = ((unsigned)p[0] << 16) | (n == 2 ? ((unsigned)p[1] << 8) : 0);
        q[0] = (uint8_t)B64URL[(v >> 18) & 63];
        q[1] = (uint8_t)B64URL[(v >> 12) & 63];
        if (n == 2)
            q[2] = (uint8_t)B64URL[(v >> 6) & 63];
        wrote += n == 1 ? 2 : 3;
    }
    return JS_NewInt32(ctx, (int32_t)wrote);
}

/* ---- class + registration ---- */

static void pipebuf_finalizer(JSRuntime *rt, JSValue val) {
    PipeBuf *pb = (PipeBuf *)JS_GetOpaque(val, pipebuf_class_id);
    if (!pb) return;
    while (pb->head) { /* pc (and its pool) outlives the runtime — see teardown order */
        PbSeg *s = pb->head;
        pb->head = s->next;
        pool_put(&pb->pc->pool, (uint8_t *)s);
    }
    JS_FreeValueRT(rt, pb->dst);
    JS_FreeValueRT(rt, pb->resolve);
    JS_FreeValueRT(rt, pb->reject);
    if (pb->jstream) qjs_jstream_free(pb->jstream);
    dbuf_free(&pb->acc);
    free(pb->reset_reason);
    free(pb);
}

/* A parked job holds refcounted JSValues (dst, the capability functions, half-built JSON
 * containers) that aren't reachable through the normal object graph — the GC must be told. */
static void pipebuf_gc_mark(JSRuntime *rt, JSValueConst val, JS_MarkFunc *mark_func) {
    PipeBuf *pb = (PipeBuf *)JS_GetOpaque(val, pipebuf_class_id);
    if (!pb) return;
    JS_MarkValue(rt, pb->dst, mark_func);
    JS_MarkValue(rt, pb->resolve, mark_func);
    JS_MarkValue(rt, pb->reject, mark_func);
    qjs_jstream_mark(rt, pb->jstream, mark_func);
}

static JSClassDef pipebuf_class_def = {
    "PipeBuf", .finalizer = pipebuf_finalizer, .gc_mark = pipebuf_gc_mark
};

QjsPipeCtx *qjs_register_pipe(JSContext *ctx, QjsPipeDrainFn drain, void *user) {
    QjsPipeCtx *pc = (QjsPipeCtx *)calloc(1, sizeof(QjsPipeCtx));
    pc->ctx = ctx;
    pc->drain = drain;
    pc->user = user;
    JS_SetRuntimeOpaque(JS_GetRuntime(ctx), pc);
    JS_NewClassID(&pipebuf_class_id);
    JS_NewClass(JS_GetRuntime(ctx), pipebuf_class_id, &pipebuf_class_def);
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__pbNew", JS_NewCFunction(ctx, js_pb_new, "__pbNew", 1));
    JS_SetPropertyStr(ctx, g, "__pbRecv", JS_NewCFunction(ctx, js_pb_recv, "__pbRecv", 4));
    JS_SetPropertyStr(ctx, g, "__pbInt", JS_NewCFunction(ctx, js_pb_int, "__pbInt", 1));
    JS_SetPropertyStr(ctx, g, "__pbLong", JS_NewCFunction(ctx, js_pb_long, "__pbLong", 1));
    JS_SetPropertyStr(ctx, g, "__pbLine", JS_NewCFunction(ctx, js_pb_line, "__pbLine", 2));
    JS_SetPropertyStr(ctx, g, "__pbJson", JS_NewCFunction(ctx, js_pb_json, "__pbJson", 2));
    JS_SetPropertyStr(ctx, g, "__pbUtf8", JS_NewCFunction(ctx, js_pb_utf8, "__pbUtf8", 1));
    JS_SetPropertyStr(ctx, g, "__pbBytes", JS_NewCFunction(ctx, js_pb_bytes, "__pbBytes", 1));
    JS_SetPropertyStr(ctx, g, "__pbCloseRead", JS_NewCFunction(ctx, js_pb_close_read, "__pbCloseRead", 1));
    JS_SetPropertyStr(ctx, g, "__utf8Decode", JS_NewCFunction(ctx, js_utf8_decode, "__utf8Decode", 3));
    JS_SetPropertyStr(ctx, g, "__utf8Into", JS_NewCFunction(ctx, js_utf8_into, "__utf8Into", 5));
    JS_SetPropertyStr(ctx, g, "__utf8Encode", JS_NewCFunction(ctx, js_utf8_encode, "__utf8Encode", 3));
    JS_SetPropertyStr(ctx, g, "__utf8Length", JS_NewCFunction(ctx, js_utf8_length, "__utf8Length", 3));
    JS_SetPropertyStr(ctx, g, "__b64urlInto", JS_NewCFunction(ctx, js_b64url_into, "__b64urlInto", 5));
    JS_FreeValue(ctx, g);
    return pc;
}

void qjs_pipe_ctx_release(QjsPipeCtx *pc, JSContext *ctx) {
    if (!pc) return;
    for (int i = 0; i < pc->n; i++) JS_FreeValue(ctx, pc->ents[i].ref);
    pc->n = 0;
}

void qjs_pipe_ctx_free(QjsPipeCtx *pc) {
    if (!pc) return;
    void *blk = pc->pool.free_list;
    while (blk) {
        void *next = *(void **)blk;
        free(blk);
        blk = next;
    }
    free(pc->ents);
    free(pc);
}

void qjs_install_versions(JSContext *ctx, int app_version, const char *extra_json) {
    JSValue v = JS_NewObject(ctx);
    /* First and non-configurable: an `app` key in extra_json fails to redefine it. */
    JS_DefinePropertyValueStr(ctx, v, "app", JS_NewInt32(ctx, app_version), JS_PROP_ENUMERABLE);
    if (extra_json && extra_json[0]) {
        JSValue extra = JS_ParseJSON(ctx, extra_json, strlen(extra_json), "<versions>");
        if (JS_IsObject(extra)) {
            JSPropertyEnum *props = NULL;
            uint32_t n = 0, i;
            if (JS_GetOwnPropertyNames(ctx, &props, &n, extra, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) == 0) {
                for (i = 0; i < n; i++) {
                    JSValue val = JS_GetProperty(ctx, extra, props[i].atom);
                    if (JS_IsObject(val))
                        JS_FreeValue(ctx, val); /* flat only: a nested object would stay mutable */
                    else
                        JS_DefinePropertyValue(ctx, v, props[i].atom, val, JS_PROP_ENUMERABLE);
                }
                JS_FreePropertyEnum(ctx, props, n);
            }
        } else if (JS_IsException(extra)) {
            /* The host builds this JSON; a bad one still boots the engine, with `app` only. */
            JS_FreeValue(ctx, JS_GetException(ctx));
        }
        JS_FreeValue(ctx, extra);
    }
    JS_PreventExtensions(ctx, v); /* non-writable props + non-extensible = Object.isFrozen */
    JSValue g = JS_GetGlobalObject(ctx);
    JS_DefinePropertyValueStr(ctx, g, "versions", v, JS_PROP_ENUMERABLE);
    JS_FreeValue(ctx, g);
}
