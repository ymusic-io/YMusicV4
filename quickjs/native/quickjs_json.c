#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs_json.h"
#include "cutils.h"

/* ============================================================================================
 * Streaming JSON parser + native stringify.
 *
 * The point: parse a JSON body arriving in CHUNKS without ever holding the whole body in memory.
 * A pull-parser can't do this here — frames are delivered into JS by the same single engine thread
 * that would block waiting for them, so pulling deadlocks. Hence a PUSH parser: a resumable state
 * machine fed one chunk at a time, suspending mid-token at chunk boundaries, producing the value at
 * end. Each chunk is consumed and discarded — only the parse stack, the current partial token, and
 * the output value tree are held, never the raw bytes.
 *
 * JS surface (globalThis):
 *   __jsonNew(expect?)               -> parser object (opaque; freed on GC or after end). expect
 *                                       0=any, 1=object, 2=array (root type checked on the 1st byte).
 *   __jsonPush(parser, u8, off, len) -> bytes consumed. Stops at the end of the top-level value, so
 *                                       a shorter-than-len return means the value is done and the
 *                                       remaining bytes are tail data (e.g. "{json}tail").
 *   __jsonDone(parser)               -> bool: the top-level value is complete.
 *   __jsonEnd(parser)                -> value (throws on incomplete/malformed input).
 *   __parseJson(uint8array)          -> value. One-shot for when you already have all the bytes.
 *   __stringifyJson(value)           -> Uint8Array of UTF-8 JSON bytes (native encode mirror).
 * ============================================================================================ */

#define JP_MAX_DEPTH 512

/* required top-level type (checked on the root value's first byte) */
#define EXPECT_ANY    0
#define EXPECT_OBJECT 1
#define EXPECT_ARRAY  2

/* Tokenizer / structure states — each is a resumable suspend point across chunk boundaries. */
enum {
    S_VALUE,             /* expecting a value */
    S_OBJ_KEY_OR_END,    /* after '{' or ',' in object: expect '"' or '}' */
    S_OBJ_COLON,         /* after a key: expect ':' */
    S_OBJ_COMMA_OR_END,  /* after an object value: expect ',' or '}' */
    S_ARR_VALUE_OR_END,  /* after '[' or ',' in array: expect value or ']' */
    S_ARR_COMMA_OR_END,  /* after an array value: expect ',' or ']' */
    S_STR,               /* in a string body */
    S_STR_ESC,           /* after '\' */
    S_STR_U,             /* collecting \uXXXX hex */
    S_STR_SURR_BS,       /* high surrogate pending, expecting '\' */
    S_STR_SURR_U,        /* high surrogate pending, consumed '\', expecting 'u' */
    S_NUM,               /* in a number */
    S_LIT,               /* matching true/false/null */
    S_DONE               /* top-level value complete */
};

typedef struct {
    JSValue container;   /* JS object/array being built */
    JSAtom pending_key;  /* object: key awaiting its value, else JS_ATOM_NULL */
    uint32_t index;      /* array: next element index */
    int is_array;
} JSFrame;

typedef struct {
    JSContext *ctx;
    JSRuntime *rt;       /* the frees use this: a finalizer can run after ctx is gone */
    int expect;

    JSFrame *stack;
    int sp, scap;

    JSValue result;
    int have_result;
    int failed;

    int tstate;
    DynBuf tok;          /* the current string/number/key bytes */
    int str_is_key;
    uint32_t uni_acc;    /* \u accumulator */
    int uni_count;       /* hex digits collected */
    uint32_t hi;         /* pending high surrogate, 0 = none */

    const char *lit;     /* "true"/"false"/"null" */
    int lit_len, lit_pos;
    JSValue lit_val;

    uint64_t pos;
} JStream;

#define JERR(s, msg) do { \
        JS_ThrowSyntaxError((s)->ctx, "%s at position %llu", msg, (unsigned long long)(s)->pos); \
        (s)->failed = 1; return -1; \
    } while (0)

static int is_ws(uint8_t c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

static void jstream_free(JStream *s) {
    if (s == NULL) return;
    JSRuntime *rt = s->rt;
    for (int i = 0; i < s->sp; i++) {
        JS_FreeValueRT(rt, s->stack[i].container);
        if (s->stack[i].pending_key != JS_ATOM_NULL) JS_FreeAtomRT(rt, s->stack[i].pending_key);
    }
    free(s->stack);
    if (s->have_result) JS_FreeValueRT(rt, s->result);
    dbuf_free(&s->tok);
    free(s);
}

static JStream *jstream_alloc(JSContext *ctx, int expect) {
    JStream *s = (JStream *)calloc(1, sizeof(JStream));
    if (s == NULL) { JS_ThrowOutOfMemory(ctx); return NULL; }
    s->ctx = ctx;
    s->rt = JS_GetRuntime(ctx);
    s->expect = expect;
    s->result = JS_UNDEFINED;
    s->lit_val = JS_UNDEFINED;
    dbuf_init(&s->tok);
    s->tstate = S_VALUE;
    return s;
}

static int tok_put_byte(JStream *s, uint8_t c) {
    if (dbuf_putc(&s->tok, c)) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return 0;
}
static int tok_put_cp(JStream *s, uint32_t cp) {
    uint8_t b[8];
    int n = unicode_to_utf8(b, cp);
    if (dbuf_put(&s->tok, b, n)) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return 0;
}

/* Attach a completed value to its destination (parent frame or the root result), set next state. */
static int st_finish_value(JStream *s, JSValue v) {
    JSContext *ctx = s->ctx;
    if (s->sp == 0) {
        s->result = v;
        s->have_result = 1;
        s->tstate = S_DONE;
        return 0;
    }
    JSFrame *f = &s->stack[s->sp - 1];
    if (f->is_array) {
        if (JS_DefinePropertyValueUint32(ctx, f->container, f->index++, v, JS_PROP_C_W_E) < 0) { s->failed = 1; return -1; }
        s->tstate = S_ARR_COMMA_OR_END;
    } else {
        int r = JS_DefinePropertyValue(ctx, f->container, f->pending_key, v, JS_PROP_C_W_E);
        JS_FreeAtom(ctx, f->pending_key);
        f->pending_key = JS_ATOM_NULL;
        if (r < 0) { s->failed = 1; return -1; }
        s->tstate = S_OBJ_COMMA_OR_END;
    }
    return 0;
}

static int st_open_container(JStream *s, int is_array) {
    if (s->sp >= JP_MAX_DEPTH) JERR(s, "nesting too deep");
    if (s->sp >= s->scap) {
        int ncap = s->scap ? s->scap * 2 : 16;
        JSFrame *ns = (JSFrame *)realloc(s->stack, sizeof(JSFrame) * ncap);
        if (ns == NULL) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
        s->stack = ns;
        s->scap = ncap;
    }
    /* Do not publish the frame through sp until its JSValue is initialized. JS_NewObject/Array may
     * trigger cycle GC; jstream_mark walks [0, sp), so incrementing first makes it mark an
     * uninitialized container and corrupt the GC refcounts. */
    JSFrame *f = &s->stack[s->sp];
    f->is_array = is_array;
    f->pending_key = JS_ATOM_NULL;
    f->index = 0;
    f->container = is_array ? JS_NewArray(s->ctx) : JS_NewObject(s->ctx);
    if (JS_IsException(f->container)) { s->failed = 1; return -1; }
    s->sp++;
    s->tstate = is_array ? S_ARR_VALUE_OR_END : S_OBJ_KEY_OR_END;
    return 0;
}

static int st_close_container(JStream *s) {
    JSFrame f = s->stack[--s->sp];
    if (f.pending_key != JS_ATOM_NULL) JS_FreeAtom(s->ctx, f.pending_key);
    return st_finish_value(s, f.container);
}

/* Validate and parse tok as a JSON number. -1 syntax, -2 oom, 0 ok (*out set). Integers up to 18
 * digits become exact int64; everything else goes through strtod (same as JSON.parse). */
static int parse_number_tok(JSContext *ctx, const uint8_t *b, size_t n, JSValue *out) {
    size_t i = 0;
    int is_float = 0;
    if (i < n && b[i] == '-') i++;
    if (i >= n || b[i] < '0' || b[i] > '9') return -1;
    if (b[i] == '0') i++;
    else while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    if (i < n && b[i] == '.') {
        is_float = 1; i++;
        if (i >= n || b[i] < '0' || b[i] > '9') return -1;
        while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    }
    if (i < n && (b[i] == 'e' || b[i] == 'E')) {
        is_float = 1; i++;
        if (i < n && (b[i] == '+' || b[i] == '-')) i++;
        if (i >= n || b[i] < '0' || b[i] > '9') return -1;
        while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    }
    if (i != n) return -1;
    if (!is_float && n <= 18) {
        int neg = b[0] == '-';
        int64_t v = 0;
        for (size_t k = neg; k < n; k++) v = v * 10 + (b[k] - '0');
        *out = JS_NewInt64(ctx, neg ? -v : v);
        return 0;
    }
    char sbuf[64], *cb = sbuf;
    if (n + 1 > sizeof(sbuf)) { cb = (char *)malloc(n + 1); if (cb == NULL) return -2; }
    memcpy(cb, b, n);
    cb[n] = '\0';
    double dv = strtod(cb, NULL);
    if (cb != sbuf) free(cb);
    *out = JS_NewFloat64(ctx, dv);
    return 0;
}

static int st_finish_number(JStream *s) {
    JSValue nv;
    int r = parse_number_tok(s->ctx, s->tok.buf, s->tok.size, &nv);
    if (r == -1) JERR(s, "invalid number");
    if (r == -2) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return st_finish_value(s, nv);
}

static int st_key_done(JStream *s) {
    JSFrame *top = &s->stack[s->sp - 1];
    top->pending_key = JS_NewAtomLen(s->ctx, (const char *)s->tok.buf, s->tok.size);
    if (top->pending_key == JS_ATOM_NULL) { s->failed = 1; return -1; }
    s->tstate = S_OBJ_COLON;
    return 0;
}

/* Emit the current \u code unit (uni_acc), honoring a pending high surrogate (WTF-8 for a lone one,
 * which JS_NewStringLen decodes back). */
static int st_emit_unit(JStream *s) {
    if (s->hi != 0) {
        if (s->uni_acc >= 0xDC00 && s->uni_acc <= 0xDFFF) {
            uint32_t cp = 0x10000 + ((s->hi - 0xD800) << 10) + (s->uni_acc - 0xDC00);
            s->hi = 0;
            if (tok_put_cp(s, cp)) return -1;
            s->tstate = S_STR;
            return 0;
        }
        if (tok_put_cp(s, s->hi)) return -1; /* lone high surrogate */
        s->hi = 0;
    }
    if (s->uni_acc >= 0xD800 && s->uni_acc <= 0xDBFF) {
        s->hi = s->uni_acc;
        s->tstate = S_STR_SURR_BS;
    } else {
        if (tok_put_cp(s, s->uni_acc)) return -1;
        s->tstate = S_STR;
    }
    return 0;
}

static int st_string_done(JStream *s) {
    if (s->str_is_key) return st_key_done(s);
    JSValue v = JS_NewStringLen(s->ctx, (const char *)s->tok.buf, s->tok.size);
    if (JS_IsException(v)) { s->failed = 1; return -1; }
    return st_finish_value(s, v);
}

static void st_begin_string(JStream *s, int is_key) {
    s->str_is_key = is_key;
    s->hi = 0;
    s->tok.size = 0;
    s->tstate = S_STR;
}

/* Feed one chunk. Parses at most ONE top-level value: stops as soon as the value completes (the
 * parser reaches S_DONE) and returns how many bytes of [data,+len) were consumed — the caller keeps
 * the rest (e.g. "{json}tail"). While the value is incomplete, consumes the whole chunk. Returns
 * the consumed byte count, or -1 with a JS exception pending. */
static int jstream_push(JStream *s, const uint8_t *data, size_t len) {
    if (s->failed) { JS_ThrowSyntaxError(s->ctx, "parser is in a failed state"); return -1; }
    const uint8_t *p = data, *end = data + len;
    while (p < end && s->tstate != S_DONE) {
        uint8_t c = *p;
        switch (s->tstate) {
        case S_VALUE:
            if (is_ws(c)) { p++; s->pos++; break; }
            // enforce the required root type on its first byte (sp==0 is only true for the root)
            if (s->expect != EXPECT_ANY && s->sp == 0) {
                if (s->expect == EXPECT_OBJECT && c != '{') JERR(s, "expected a JSON object");
                if (s->expect == EXPECT_ARRAY && c != '[') JERR(s, "expected a JSON array");
            }
            if (c == '{') { p++; s->pos++; if (st_open_container(s, 0)) return -1; break; }
            if (c == '[') { p++; s->pos++; if (st_open_container(s, 1)) return -1; break; }
            if (c == '"') { p++; s->pos++; st_begin_string(s, 0); break; }
            if (c == '-' || (c >= '0' && c <= '9')) { s->tok.size = 0; s->tstate = S_NUM; break; }
            if (c == 't') { s->lit = "true";  s->lit_len = 4; s->lit_pos = 0; s->lit_val = JS_TRUE;  s->tstate = S_LIT; break; }
            if (c == 'f') { s->lit = "false"; s->lit_len = 5; s->lit_pos = 0; s->lit_val = JS_FALSE; s->tstate = S_LIT; break; }
            if (c == 'n') { s->lit = "null";  s->lit_len = 4; s->lit_pos = 0; s->lit_val = JS_NULL;  s->tstate = S_LIT; break; }
            JERR(s, "unexpected character");

        case S_OBJ_KEY_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == '"') { p++; s->pos++; st_begin_string(s, 1); break; }
            if (c == '}') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected string key or '}'");

        case S_OBJ_COLON:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ':') { p++; s->pos++; s->tstate = S_VALUE; break; }
            JERR(s, "expected ':'");

        case S_OBJ_COMMA_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ',') { p++; s->pos++; s->tstate = S_OBJ_KEY_OR_END; break; }
            if (c == '}') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected ',' or '}'");

        case S_ARR_VALUE_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ']') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            s->tstate = S_VALUE; /* reprocess c as the element value */
            break;

        case S_ARR_COMMA_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ',') { p++; s->pos++; s->tstate = S_ARR_VALUE_OR_END; break; }
            if (c == ']') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected ',' or ']'");

        case S_STR:
            if (c == '"') { p++; s->pos++; if (st_string_done(s)) return -1; break; }
            if (c == '\\') { p++; s->pos++; s->tstate = S_STR_ESC; break; }
            if (c < 0x20) JERR(s, "unescaped control character in string");
            if (tok_put_byte(s, c)) return -1;
            p++; s->pos++;
            break;

        case S_STR_ESC: {
            uint32_t cp;
            switch (c) {
            case '"': case '\\': case '/': cp = c; break;
            case 'b': cp = 8; break;
            case 'f': cp = 12; break;
            case 'n': cp = 10; break;
            case 'r': cp = 13; break;
            case 't': cp = 9; break;
            case 'u': p++; s->pos++; s->uni_acc = 0; s->uni_count = 0; s->tstate = S_STR_U; goto next;
            default: JERR(s, "invalid escape");
            }
            if (tok_put_cp(s, cp)) return -1;
            p++; s->pos++; s->tstate = S_STR;
            next: break;
        }

        case S_STR_U: {
            int hv;
            if (c >= '0' && c <= '9') hv = c - '0';
            else if (c >= 'a' && c <= 'f') hv = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') hv = c - 'A' + 10;
            else JERR(s, "invalid \\u escape");
            s->uni_acc = (s->uni_acc << 4) | (uint32_t)hv;
            p++; s->pos++;
            if (++s->uni_count == 4) { if (st_emit_unit(s)) return -1; }
            break;
        }

        case S_STR_SURR_BS:
            if (c == '\\') { p++; s->pos++; s->tstate = S_STR_SURR_U; break; }
            if (tok_put_cp(s, s->hi)) return -1; /* no low follows: lone high surrogate */
            s->hi = 0;
            s->tstate = S_STR; /* reprocess c */
            break;

        case S_STR_SURR_U:
            if (c == 'u') { p++; s->pos++; s->uni_acc = 0; s->uni_count = 0; s->tstate = S_STR_U; break; }
            if (tok_put_cp(s, s->hi)) return -1;
            s->hi = 0;
            s->tstate = S_STR_ESC; /* the '\' was consumed; reprocess c as the escape char */
            break;

        case S_NUM:
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                if (tok_put_byte(s, c)) return -1;
                p++; s->pos++;
                break;
            }
            if (st_finish_number(s)) return -1; /* terminator c reprocessed in the new state */
            break;

        case S_LIT:
            if (c != (uint8_t)s->lit[s->lit_pos]) JERR(s, "invalid literal");
            p++; s->pos++;
            if (++s->lit_pos == s->lit_len) { if (st_finish_value(s, s->lit_val)) return -1; }
            break;

        default: /* S_DONE is excluded by the loop guard; anything else is a bug */
            JERR(s, "internal parser state error");
        }
    }
    return (int)(p - data);
}

/* Finish parsing at EOF; returns the value (transfers ownership) or JS_EXCEPTION. */
static JSValue jstream_end(JStream *s) {
    if (s->failed) return JS_ThrowSyntaxError(s->ctx, "parser is in a failed state");
    if (s->tstate == S_NUM) {
        if (st_finish_number(s)) return JS_EXCEPTION; /* no terminator: EOF ends the number */
    }
    if (s->tstate != S_DONE || s->sp != 0 || !s->have_result)
        return JS_ThrowSyntaxError(s->ctx, "unexpected end of JSON input");
    JSValue r = s->result;
    s->result = JS_UNDEFINED;
    s->have_result = 0;
    return r;
}

/* ---- C-level API (quickjs_json.h) — the pipe layer drives the parser without a JSValue wrapper ---- */

QjsJStream *qjs_jstream_new(JSContext *ctx, int expect) {
    return (QjsJStream *)jstream_alloc(ctx, expect);
}

int qjs_jstream_push(QjsJStream *s, const uint8_t *data, size_t len) {
    return jstream_push((JStream *)s, data, len);
}

int qjs_jstream_done(QjsJStream *s) {
    return ((JStream *)s)->tstate == S_DONE;
}

JSValue qjs_jstream_end(QjsJStream *s) {
    return jstream_end((JStream *)s);
}

void qjs_jstream_free(QjsJStream *s) {
    jstream_free((JStream *)s);
}

void qjs_jstream_mark(JSRuntime *rt, QjsJStream *js, JS_MarkFunc *mark_func) {
    JStream *s = (JStream *)js;
    if (s == NULL) return;
    for (int i = 0; i < s->sp; i++) JS_MarkValue(rt, s->stack[i].container, mark_func);
    if (s->have_result) JS_MarkValue(rt, s->result, mark_func);
}

/* ---- JS class wrapping a JStream (opaque; finalizer frees it) ---- */

static JSClassID jstream_class_id;

static void jstream_class_finalizer(JSRuntime *rt, JSValue val) {
    JStream *s = JS_GetOpaque(val, jstream_class_id);
    if (s) jstream_free(s);
}

/* The parser holds refcounted JSValues (half-built containers, the result) that aren't reachable
 * through the normal object graph — the GC must be told, or the cycle collector / shutdown sweep
 * mis-accounts them (a debug build asserts on a non-empty gc_obj_list at JS_FreeRuntime). */
static void jstream_class_gc_mark(JSRuntime *rt, JSValueConst val, JS_MarkFunc *mark_func) {
    qjs_jstream_mark(rt, (QjsJStream *)JS_GetOpaque(val, jstream_class_id), mark_func);
}

static JSClassDef jstream_class_def = {
    "JsonStream", .finalizer = jstream_class_finalizer, .gc_mark = jstream_class_gc_mark
};

/* __jsonNew(expect?) -> parser object. */
static JSValue js_json_new(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int32_t expect = EXPECT_ANY;
    if (argc >= 1 && JS_ToInt32(ctx, &expect, argv[0])) return JS_EXCEPTION;
    JStream *s = jstream_alloc(ctx, expect);
    if (s == NULL) return JS_EXCEPTION;
    JSValue obj = JS_NewObjectClass(ctx, jstream_class_id);
    if (JS_IsException(obj)) { jstream_free(s); return obj; }
    JS_SetOpaque(obj, s);
    return obj;
}

/* __jsonPush(parser, u8, off, len) -> bytes consumed. */
static JSValue js_json_push(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 2) return JS_ThrowTypeError(ctx, "__jsonPush(parser, u8, off, len)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    /* Convert off/len FIRST: a valueOf here can detach or resize the source buffer, so base/ta_len
     * must be captured AFTER (reflecting the post-conversion state) — otherwise a cached base
     * dangles into freed memory. */
    int64_t off = 0, len = -1; /* len < 0: default to the whole buffer from off */
    int have_len = argc >= 4;
    if (argc >= 3 && JS_ToInt64(ctx, &off, argv[2])) return JS_EXCEPTION;
    if (have_len && JS_ToInt64(ctx, &len, argv[3])) return JS_EXCEPTION;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) return ab;
    size_t sz = 0;
    uint8_t *base = JS_GetArrayBuffer(ctx, &sz, ab);
    JSValue ret;
    if (base == NULL) {
        ret = JS_ThrowTypeError(ctx, "__jsonPush: argument is not a byte buffer");
    } else {
        if (!have_len) len = (int64_t)ta_len;
        // Unsigned, no `off + len` sum — that overflows on 32-bit and bypasses the check.
        if (off < 0 || len < 0 || (uint64_t)off > ta_len || (uint64_t)len > ta_len - (uint64_t)off) {
            ret = JS_ThrowRangeError(ctx, "__jsonPush: offset/length out of range");
        } else {
            int consumed = len > 0 ? jstream_push(s, base + ta_off + off, (size_t)len) : 0;
            ret = consumed < 0 ? JS_EXCEPTION : JS_NewInt32(ctx, consumed);
        }
    }
    JS_FreeValue(ctx, ab);
    return ret;
}

/* __jsonDone(parser) -> bool. */
static JSValue js_json_done(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__jsonDone(parser)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    return JS_NewBool(ctx, s->tstate == S_DONE);
}

/* __jsonEnd(parser) -> value. */
static JSValue js_json_end(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__jsonEnd(parser)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    return jstream_end(s);
}

/* __parseJson(uint8array) -> value. One-shot QuickJS parse for when you already have all the bytes. */
static JSValue js_parse_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__parseJson requires a Uint8Array");
    size_t off = 0, len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[0], &off, &len, &bpe);
    if (JS_IsException(ab)) return ab;
    size_t sz = 0;
    uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
    JSValue result;
    if (ptr == NULL) {
        result = JS_ThrowTypeError(ctx, "__parseJson: argument is not a byte buffer");
    } else {
        /* JS_ParseJSON's tokenizer needs a trailing '\0' sentinel; the typed array isn't
         * NUL-terminated, so copy into a len+1 buffer to avoid reading past the end. */
        char *tmp = (char *)malloc(len + 1);
        if (tmp == NULL) {
            result = JS_ThrowOutOfMemory(ctx);
        } else {
            memcpy(tmp, ptr + off, len);
            tmp[len] = '\0';
            result = JS_ParseJSON(ctx, tmp, len, "<json>");
            free(tmp);
        }
    }
    JS_FreeValue(ctx, ab);
    return result;
}

/* __stringifyJson(value) -> Uint8Array of UTF-8 JSON bytes. Native stringify + native UTF-16->UTF-8
 * (JS_ToCStringLen) — the encode mirror; never runs through interpreted per-codepoint JS. */
static JSValue js_stringify_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__stringifyJson requires a value");
    JSValue str = JS_JSONStringify(ctx, argv[0], JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(str)) return str;
    if (JS_IsUndefined(str)) {
        JS_FreeValue(ctx, str);
        return JS_ThrowTypeError(ctx, "__stringifyJson: value has no JSON representation");
    }
    size_t len = 0;
    const char *utf8 = JS_ToCStringLen(ctx, &len, str);
    JS_FreeValue(ctx, str);
    if (utf8 == NULL) return JS_EXCEPTION;
    JSValue ab = JS_NewArrayBufferCopy(ctx, (const uint8_t *)utf8, len);
    JS_FreeCString(ctx, utf8);
    if (JS_IsException(ab)) return ab;
    /* new Uint8Array(ab) — the constructor reads argv[1]/argv[2] unconditionally (interpreter
     * undefined-padding), so pad them explicitly. View, no copy. */
    JSValueConst args[3] = { ab, JS_UNDEFINED, JS_UNDEFINED };
    JSValue u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

/* ============================================================================================
 * Resumable native JSON ENCODER — the stringify mirror of the streaming parser above.
 *
 * The point: serialize a value into FIXED-SIZE output chunks without ever materializing the whole
 * JSON text (which JS_JSONStringify does), at C speed (the interpreted token pump paid ~1µs per
 * token in call overhead alone). A depth-first walk kept as explicit state:
 *
 *   __jsonEncNew(value)             -> encoder object (opaque; freed on GC — abandonment-safe)
 *   __jsonEncFill(enc, dst, off)    -> bytes written into dst[off..]; call repeatedly, flushing
 *                                      between calls; 0 once done (or when off is at the end)
 *   __jsonEncDone(enc)              -> bool: the value is fully emitted
 *
 * Matches JSON.stringify: toJSON() honored (no key argument), own enumerable string keys in spec
 * order, NaN/Infinity -> null, undefined/function/symbol omitted in objects / null in arrays,
 * exact escape set, and a cycle throws TypeError. Divergences (deliberate, matching the old JS
 * pump): a root undefined/function/symbol emits "null" (stringify returns undefined), and lone
 * surrogates go out as WTF-8 rather than \uXXXX.
 *
 * Memory held at any instant = the frame stack (bounded by nesting depth) + ONE in-flight token
 * (a string/key token is escaped whole into a reusable DynBuf, then drained across fills). Because
 * the walk resumes across fills, toJSON()/getters run *during* serialization — a value mutated
 * under backpressure serializes as visited (also true of the JS pump this replaces).
 * ============================================================================================ */

typedef struct {
    JSValue container;    /* dup'd array or object being walked */
    JSPropertyEnum *tab;  /* object: own enumerable string keys (spec order); NULL for arrays */
    uint32_t tab_len;
    uint32_t idx;         /* next key / element index */
    uint32_t arr_len;     /* array: length snapshot (LengthOfArrayLike, read once) */
    int emitted;          /* at least one member written (comma logic) */
    int is_array;
} EncFrame;

typedef struct {
    JSContext *ctx;
    JSRuntime *rt;        /* the frees use this: a finalizer can run after ctx is gone */
    EncFrame *stack;
    int sp, scap;
    JSValue pending;      /* the next VALUE to emit */
    int has_pending;
    int root_raw;         /* pending is the raw root: apply toJSON on first produce */
    JSAtom atom_tojson;   /* interned once — JS_GetPropertyStr would re-hash "toJSON" per value */
    JSAtom atom_length;   /* likewise, "length" per array */
    DynBuf tok;           /* current token bytes, drained across fills */
    size_t tok_pos;
    int done;
    int failed;
    int busy;             /* inside js_json_enc_fill: reject reentrant fills (a held EncFrame* would dangle) */
} JEnc;

/* From the BEGIN/END patch at the end of the vendored quickjs.c: direct flat-string storage access,
 * so string tokens escape+encode straight out of JSString memory (Latin-1 / UTF-16) with no
 * per-token UTF-8 cstring materialization (that one malloc + extra pass was most of the remaining
 * gap to the internal JSON.stringify). */
JSValue JS_ToFlatString(JSContext *ctx, JSValueConst v);
int JS_GetFlatStringStorage(JSValueConst v, const void **pdata, uint32_t *plen);

static void jenc_free(JEnc *e) {
    if (e == NULL) return;
    JSRuntime *rt = e->rt;
    for (int i = 0; i < e->sp; i++) {
        EncFrame *f = &e->stack[i];
        JS_FreeValueRT(rt, f->container);
        if (f->tab) {
            for (uint32_t k = 0; k < f->tab_len; k++) JS_FreeAtomRT(rt, f->tab[k].atom);
            js_free_rt(rt, f->tab);
        }
    }
    free(e->stack);
    if (e->has_pending) JS_FreeValueRT(rt, e->pending);
    JS_FreeAtomRT(rt, e->atom_tojson);
    JS_FreeAtomRT(rt, e->atom_length);
    dbuf_free(&e->tok);
    free(e);
}

static int jenc_fail(JEnc *e) { e->failed = 1; return -1; } /* exception already pending */

static int jenc_put(JEnc *e, const void *p, size_t n) {
    if (dbuf_put(&e->tok, (const uint8_t *)p, n)) { JS_ThrowOutOfMemory(e->ctx); return jenc_fail(e); }
    return 0;
}

static int jenc_putc(JEnc *e, char c) {
    if (dbuf_putc(&e->tok, (uint8_t)c)) { JS_ThrowOutOfMemory(e->ctx); return jenc_fail(e); }
    return 0;
}

/* HEX + the escape for one char < 0x80 needing one (writes 2 or 6 bytes at q, returns new q).
 * JSON.stringify's exact escape set. */
static const char JENC_HEX[] = "0123456789abcdef";

static inline uint8_t *jenc_esc_char(uint8_t *q, uint8_t c) {
    *q++ = '\\';
    switch (c) {
    case '"': *q++ = '"'; break;
    case '\\': *q++ = '\\'; break;
    case '\n': *q++ = 'n'; break;
    case '\r': *q++ = 'r'; break;
    case '\t': *q++ = 't'; break;
    case '\b': *q++ = 'b'; break;
    case '\f': *q++ = 'f'; break;
    default:
        *q++ = 'u'; *q++ = '0'; *q++ = '0';
        *q++ = JENC_HEX[c >> 4]; *q++ = JENC_HEX[c & 15];
    }
    return q;
}

/* JSON string literal straight from a narrow JSString's storage (Latin-1 bytes) — escape + UTF-8
 * encode in one pass, written raw into the reserved DynBuf tail (worst case 6 bytes per char),
 * chunked so a huge string never over-reserves. */
static int jenc_escape_latin1(JEnc *e, const uint8_t *s, uint32_t len) {
    DynBuf *d = &e->tok;
    if (jenc_putc(e, '"')) return -1;
    uint32_t i = 0;
    while (i < len) {
        uint32_t todo = len - i;
        if (todo > 1024) todo = 1024;
        if (dbuf_claim(d, (size_t)todo * 6)) { JS_ThrowOutOfMemory(e->ctx); return jenc_fail(e); }
        uint8_t *q = d->buf + d->size;
        for (uint32_t end = i + todo; i < end; i++) {
            uint8_t c = s[i];
            if (c >= 0x20 && c < 0x80 && c != '"' && c != '\\') *q++ = c;
            else if (c >= 0x80) { *q++ = 0xC0 | (c >> 6); *q++ = 0x80 | (c & 0x3F); }
            else q = jenc_esc_char(q, c);
        }
        d->size = q - d->buf;
    }
    return jenc_putc(e, '"');
}

/* The same for a wide JSString (UTF-16 code units). Astral pairs -> 4-byte UTF-8; a lone surrogate
 * goes out as WTF-8 (3 bytes), consistent with utf8Encode/JS_ToCStringLen across the stack. */
static int jenc_escape_utf16(JEnc *e, const uint16_t *s, uint32_t len) {
    DynBuf *d = &e->tok;
    if (jenc_putc(e, '"')) return -1;
    uint32_t i = 0;
    while (i < len) {
        uint32_t todo = len - i;
        if (todo > 1024) todo = 1024;
        if (dbuf_claim(d, (size_t)todo * 6)) { JS_ThrowOutOfMemory(e->ctx); return jenc_fail(e); }
        uint8_t *q = d->buf + d->size;
        uint32_t end = i + todo;
        while (i < end) {
            uint32_t c = s[i++];
            if (c < 0x80) {
                if (c >= 0x20 && c != '"' && c != '\\') *q++ = c;
                else q = jenc_esc_char(q, (uint8_t)c);
            } else if (c < 0x800) {
                *q++ = 0xC0 | (c >> 6); *q++ = 0x80 | (c & 0x3F);
            } else if (c >= 0xD800 && c < 0xDC00 && i < len && s[i] >= 0xDC00 && s[i] < 0xE000) {
                uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (s[i++] - 0xDC00);
                *q++ = 0xF0 | (cp >> 18); *q++ = 0x80 | ((cp >> 12) & 0x3F);
                *q++ = 0x80 | ((cp >> 6) & 0x3F); *q++ = 0x80 | (cp & 0x3F);
            } else {
                *q++ = 0xE0 | (c >> 12); *q++ = 0x80 | ((c >> 6) & 0x3F); *q++ = 0x80 | (c & 0x3F);
            }
        }
        d->size = q - d->buf;
    }
    return jenc_putc(e, '"');
}

/* Emit the JSON string literal for a FLAT string value, dispatching on its storage width. */
static int jenc_escape_flat(JEnc *e, JSValueConst flat) {
    const void *data = NULL;
    uint32_t len = 0;
    int wide = JS_GetFlatStringStorage(flat, &data, &len);
    if (wide < 0) { JS_ThrowTypeError(e->ctx, "not a flat string"); return jenc_fail(e); }
    return wide ? jenc_escape_utf16(e, (const uint16_t *)data, len)
                : jenc_escape_latin1(e, (const uint8_t *)data, len);
}

/* JSON.stringify's toJSON hook (no key argument — matches the JS pump this replaces). Takes
 * ownership of v; returns a new value or an exception. */
static JSValue jenc_resolve(JEnc *e, JSValue v) {
    JSContext *ctx = e->ctx;
    if (!JS_IsObject(v) && !JS_IsBigInt(ctx, v)) return v;
    JSValue fn = JS_GetProperty(ctx, v, e->atom_tojson);
    if (JS_IsException(fn)) { JS_FreeValue(ctx, v); return JS_EXCEPTION; }
    if (JS_IsFunction(ctx, fn)) {
        JSValue r = JS_Call(ctx, fn, v, 0, NULL);
        JS_FreeValue(ctx, fn);
        JS_FreeValue(ctx, v);
        return r;
    }
    JS_FreeValue(ctx, fn);
    return v;
}

static int jenc_not_serializable(JSContext *ctx, JSValueConst v) {
    return JS_IsUndefined(v) || JS_IsFunction(ctx, v) || JS_IsSymbol(v);
}

/* Emit one value (takes ownership): a scalar's token, or a container's opener + a new frame. */
static int jenc_emit_value(JEnc *e, JSValue v) {
    JSContext *ctx = e->ctx;
    if (JS_IsNull(v) || jenc_not_serializable(ctx, v)) {
        JS_FreeValue(ctx, v);
        return jenc_put(e, "null", 4);
    }
    if (JS_IsBool(v)) {
        int b = JS_ToBool(ctx, v);
        JS_FreeValue(ctx, v);
        return b ? jenc_put(e, "true", 4) : jenc_put(e, "false", 5);
    }
    if (JS_IsNumber(v)) {
        if (JS_VALUE_GET_TAG(v) == JS_TAG_INT) { /* the common case: no JSString + cstring churn */
            char nbuf[16];
            int n = snprintf(nbuf, sizeof(nbuf), "%d", JS_VALUE_GET_INT(v)); /* == ECMA ToString for int32 */
            return jenc_put(e, nbuf, (size_t)n);
        }
        double d = 0;
        JS_ToFloat64(ctx, &d, v);
        if (!isfinite(d)) { JS_FreeValue(ctx, v); return jenc_put(e, "null", 4); }
        size_t len = 0;
        const char *cs = JS_ToCStringLen(ctx, &len, v); /* exact ECMA ToString formatting */
        JS_FreeValue(ctx, v);
        if (cs == NULL) return jenc_fail(e);
        int r = jenc_put(e, cs, len);
        JS_FreeCString(ctx, cs);
        return r;
    }
    if (JS_IsString(v)) {
        JSValue flat = JS_ToFlatString(ctx, v); /* linearizes a rope; usually a cheap dup */
        JS_FreeValue(ctx, v);
        if (JS_IsException(flat)) return jenc_fail(e);
        int r = jenc_escape_flat(e, flat);
        JS_FreeValue(ctx, flat);
        return r;
    }
    if (JS_IsBigInt(ctx, v)) {
        JS_FreeValue(ctx, v);
        JS_ThrowTypeError(ctx, "Do not know how to serialize a BigInt");
        return jenc_fail(e);
    }
    int isarr = JS_IsArray(ctx, v);
    if (isarr < 0) { JS_FreeValue(ctx, v); return jenc_fail(e); }
    /* cycle check: is v already on the walk stack? (the explicit stack makes this cheap) */
    for (int i = 0; i < e->sp; i++) {
        if (JS_VALUE_GET_PTR(e->stack[i].container) == JS_VALUE_GET_PTR(v)) {
            JS_FreeValue(ctx, v);
            JS_ThrowTypeError(ctx, "Converting circular structure to JSON");
            return jenc_fail(e);
        }
    }
    uint32_t arr_len = 0;
    JSPropertyEnum *tab = NULL;
    uint32_t tab_len = 0;
    if (isarr) {
        JSValue lv = JS_GetProperty(ctx, v, e->atom_length);
        if (JS_IsException(lv) || JS_ToUint32(ctx, &arr_len, lv)) {
            JS_FreeValue(ctx, lv);
            JS_FreeValue(ctx, v);
            return jenc_fail(e);
        }
        JS_FreeValue(ctx, lv);
    } else {
        if (JS_GetOwnPropertyNames(ctx, &tab, &tab_len, v, JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY)) {
            JS_FreeValue(ctx, v);
            return jenc_fail(e);
        }
    }
    if (e->sp == e->scap) {
        int ncap = e->scap ? e->scap * 2 : 16;
        EncFrame *ns = (EncFrame *)realloc(e->stack, sizeof(EncFrame) * ncap);
        if (ns == NULL) {
            if (tab) JS_FreePropertyEnum(ctx, tab, tab_len);
            JS_FreeValue(ctx, v);
            JS_ThrowOutOfMemory(ctx);
            return jenc_fail(e);
        }
        e->stack = ns;
        e->scap = ncap;
    }
    EncFrame *f = &e->stack[e->sp++];
    f->container = v;
    f->tab = tab;
    f->tab_len = tab_len;
    f->idx = 0;
    f->arr_len = arr_len;
    f->emitted = 0;
    f->is_array = isarr;
    return jenc_putc(e, isarr ? '[' : '{');
}

/* Produce the next token into e->tok (possibly empty — the fill loop just produces again; every
 * call advances the walk state, so progress is guaranteed). Sets done; -1 on exception. */
static int jenc_produce(JEnc *e) {
    JSContext *ctx = e->ctx;
    e->tok.size = 0;
    e->tok_pos = 0;
    if (e->tok.allocated_size > 65536) { dbuf_free(&e->tok); dbuf_init(&e->tok); } /* don't retain a huge token buffer */

    if (e->has_pending) {
        JSValue v = e->pending;
        e->pending = JS_UNDEFINED;
        e->has_pending = 0;
        if (e->root_raw) {
            e->root_raw = 0;
            v = jenc_resolve(e, v);
            if (JS_IsException(v)) return jenc_fail(e);
        }
        return jenc_emit_value(e, v);
    }
    if (e->sp == 0) { e->done = 1; return 0; }
    EncFrame *f = &e->stack[e->sp - 1];
    if (f->is_array) {
        if (f->idx >= f->arr_len) {
            JS_FreeValue(ctx, f->container);
            e->sp--;
            return jenc_putc(e, ']');
        }
        JSValue el = JS_GetPropertyUint32(ctx, f->container, f->idx);
        f->idx++;
        if (JS_IsException(el)) return jenc_fail(e);
        el = jenc_resolve(e, el);
        if (JS_IsException(el)) return jenc_fail(e);
        if (jenc_not_serializable(ctx, el)) { JS_FreeValue(ctx, el); el = JS_NULL; }
        e->pending = el;
        e->has_pending = 1;
        if (f->emitted) { f->emitted = 1; return jenc_putc(e, ','); }
        f->emitted = 1;
        return 0;
    }
    /* object: advance to the next includable member; token = [,]"key": */
    for (;;) {
        if (f->idx >= f->tab_len) {
            JS_FreePropertyEnum(ctx, f->tab, f->tab_len);
            JS_FreeValue(ctx, f->container);
            e->sp--;
            return jenc_putc(e, '}');
        }
        JSAtom key = f->tab[f->idx].atom;
        f->idx++;
        JSValue mv = JS_GetProperty(ctx, f->container, key);
        if (JS_IsException(mv)) return jenc_fail(e);
        mv = jenc_resolve(e, mv);
        if (JS_IsException(mv)) return jenc_fail(e);
        if (jenc_not_serializable(ctx, mv)) { JS_FreeValue(ctx, mv); continue; } /* omit the key */
        if (f->emitted && jenc_putc(e, ',')) { JS_FreeValue(ctx, mv); return -1; }
        f->emitted = 1;
        JSValue kv = JS_AtomToValue(ctx, key); /* string atoms are always flat strings */
        if (JS_IsException(kv)) { JS_FreeValue(ctx, mv); return jenc_fail(e); }
        int r = jenc_escape_flat(e, kv);
        JS_FreeValue(ctx, kv);
        if (r || jenc_putc(e, ':')) { JS_FreeValue(ctx, mv); return -1; }
        e->pending = mv;
        e->has_pending = 1;
        return 0;
    }
}

/* ---- JS class wrapping a JEnc (opaque; finalizer frees it — abandonment-safe) ---- */

static JSClassID jenc_class_id;

static void jenc_class_finalizer(JSRuntime *rt, JSValue val) {
    JEnc *e = JS_GetOpaque(val, jenc_class_id);
    if (e) jenc_free(e);
}

/* Same rule as the parser: held JSValues invisible to the object graph MUST be marked. */
static void jenc_class_gc_mark(JSRuntime *rt, JSValueConst val, JS_MarkFunc *mark_func) {
    JEnc *e = JS_GetOpaque(val, jenc_class_id);
    if (e == NULL) return;
    for (int i = 0; i < e->sp; i++) JS_MarkValue(rt, e->stack[i].container, mark_func);
    if (e->has_pending) JS_MarkValue(rt, e->pending, mark_func);
}

static JSClassDef jenc_class_def = {
    "JsonEncoder", .finalizer = jenc_class_finalizer, .gc_mark = jenc_class_gc_mark
};

/* __jsonEncNew(value) -> encoder object. */
static JSValue js_json_enc_new(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JEnc *e = (JEnc *)calloc(1, sizeof(JEnc));
    if (e == NULL) return JS_ThrowOutOfMemory(ctx);
    e->ctx = ctx;
    e->rt = JS_GetRuntime(ctx);
    e->pending = JS_DupValue(ctx, argc >= 1 ? argv[0] : JS_UNDEFINED);
    e->has_pending = 1;
    e->root_raw = 1;
    e->atom_tojson = JS_NewAtom(ctx, "toJSON");
    e->atom_length = JS_NewAtom(ctx, "length");
    dbuf_init(&e->tok);
    JSValue obj = JS_NewObjectClass(ctx, jenc_class_id);
    if (JS_IsException(obj)) { jenc_free(e); return obj; }
    JS_SetOpaque(obj, e);
    return obj;
}

/* __jsonEncFill(enc, dst, off) -> bytes written into dst[off..]. */
static JSValue js_json_enc_fill(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 3) return JS_ThrowTypeError(ctx, "__jsonEncFill(enc, dst, off)");
    JEnc *e = (JEnc *)JS_GetOpaque2(ctx, argv[0], jenc_class_id);
    if (e == NULL) return JS_EXCEPTION;
    if (e->failed) return JS_ThrowInternalError(ctx, "__jsonEncFill: encoder already failed");
    // jenc_produce below runs user JS (getters/toJSON/Proxy traps) while a raw EncFrame* into
    // e->stack is held across the call; a reentrant fill on THIS encoder would realloc/pop that
    // stack and dangle the pointer. Reject reentry outright — encoding is strictly one-at-a-time.
    if (e->busy) return JS_ThrowInternalError(ctx, "__jsonEncFill: reentrant fill");
    e->busy = 1; // arm before coercing off: an object off's valueOf must not reenter unguarded
    int64_t off = 0;
    if (JS_ToInt64(ctx, &off, argv[2])) { e->busy = 0; return JS_EXCEPTION; }
    if (off < 0) { e->busy = 0; return JS_ThrowRangeError(ctx, "__jsonEncFill: bad destination range"); }
    size_t w = 0;
    for (;;) {
        /* Re-resolve the destination EVERY iteration: jenc_produce runs user JS (getters, toJSON,
         * Proxy traps) that can detach or resize this ArrayBuffer, dangling any cached base/cap.
         * Mirrors the pipe layer's PB_JOB_RECV re-resolve. `w` is validated against the fresh cap
         * so a mid-fill shrink can't drive an out-of-bounds write. */
        size_t ta_off = 0, ta_len = 0, bpe = 0;
        JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
        if (JS_IsException(ab)) { e->busy = 0; return ab; }
        size_t sz = 0;
        uint8_t *base = JS_GetArrayBuffer(ctx, &sz, ab);
        JS_FreeValue(ctx, ab);
        if (base == NULL || (uint64_t)off > ta_len || (uint64_t)off + w > ta_len) {
            e->busy = 0;
            return JS_ThrowRangeError(ctx, "__jsonEncFill: destination detached or shrank mid-encode");
        }
        size_t cap = ta_len - (size_t)off;
        uint8_t *dst = base + ta_off + off;
        size_t avail = e->tok.size - e->tok_pos;
        if (avail > 0) {
            size_t n = avail < cap - w ? avail : cap - w;
            memcpy(dst + w, e->tok.buf + e->tok_pos, n);
            w += n;
            e->tok_pos += n;
            if (w == cap) break;
        }
        if (e->done) break;
        if (jenc_produce(e)) { e->busy = 0; return JS_EXCEPTION; }
    }
    e->busy = 0;
    return JS_NewInt64(ctx, (int64_t)w);
}

/* __jsonEncDone(enc) -> bool (all output produced AND drained). */
static JSValue js_json_enc_done(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__jsonEncDone(enc)");
    JEnc *e = (JEnc *)JS_GetOpaque2(ctx, argv[0], jenc_class_id);
    if (e == NULL) return JS_EXCEPTION;
    return JS_NewBool(ctx, e->done && e->tok_pos >= e->tok.size);
}

void qjs_register_json(JSContext *ctx) {
    JS_NewClassID(&jstream_class_id);
    JS_NewClass(JS_GetRuntime(ctx), jstream_class_id, &jstream_class_def);
    JS_NewClassID(&jenc_class_id);
    JS_NewClass(JS_GetRuntime(ctx), jenc_class_id, &jenc_class_def);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__parseJson", JS_NewCFunction(ctx, js_parse_json, "__parseJson", 1));
    JS_SetPropertyStr(ctx, global, "__stringifyJson", JS_NewCFunction(ctx, js_stringify_json, "__stringifyJson", 1));
    JS_SetPropertyStr(ctx, global, "__jsonNew", JS_NewCFunction(ctx, js_json_new, "__jsonNew", 1));
    JS_SetPropertyStr(ctx, global, "__jsonPush", JS_NewCFunction(ctx, js_json_push, "__jsonPush", 4));
    JS_SetPropertyStr(ctx, global, "__jsonDone", JS_NewCFunction(ctx, js_json_done, "__jsonDone", 1));
    JS_SetPropertyStr(ctx, global, "__jsonEnd", JS_NewCFunction(ctx, js_json_end, "__jsonEnd", 1));
    JS_SetPropertyStr(ctx, global, "__jsonEncNew", JS_NewCFunction(ctx, js_json_enc_new, "__jsonEncNew", 1));
    JS_SetPropertyStr(ctx, global, "__jsonEncFill", JS_NewCFunction(ctx, js_json_enc_fill, "__jsonEncFill", 3));
    JS_SetPropertyStr(ctx, global, "__jsonEncDone", JS_NewCFunction(ctx, js_json_enc_done, "__jsonEncDone", 1));
    JS_FreeValue(ctx, global);
}
