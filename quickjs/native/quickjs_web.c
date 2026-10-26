#include "quickjs_web.h"
#include "cutils.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <unistd.h>
#include <sys/time.h>
#include <time.h>
#include <pthread.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#ifdef __APPLE__
#include <TargetConditionals.h>
#endif

/* Flat-string access (BEGIN/END patch at the end of vendored quickjs.c). */
JSValue JS_ToFlatString(JSContext *ctx, JSValueConst v);
int JS_GetFlatStringStorage(JSValueConst v, const void **pdata, uint32_t *plen);
JSValue JS_NewStringLatin1(JSContext *ctx, const char *buf, size_t len);

static const char B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int b64_digit(unsigned char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

static int is_b64_space(unsigned char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
}

static JSValue throw_invalid_char(JSContext *ctx)
{
    return JS_ThrowTypeError(ctx, "InvalidCharacterError");
}

static JSValue to_string_arg(JSContext *ctx, int argc, JSValueConst *argv)
{
    if (argc < 1)
        return JS_ThrowTypeError(ctx, "1 argument required");
    return JS_ToString(ctx, argv[0]);
}

/* Walk latin-1 bytes of a JS string without a UTF-8 round-trip. Wide strings
 * with a code unit > 255 fail (WHATWG btoa). */
static int latin1_bytes(JSContext *ctx, JSValueConst str,
                        const uint8_t **pbytes, uint32_t *plen, JSValue *keep)
{
    const void *data = NULL;
    uint32_t n = 0;
    int wide;
    JSValue flat;

    *keep = JS_UNDEFINED;
    flat = JS_ToFlatString(ctx, str);
    if (JS_IsException(flat))
        return -1;
    wide = JS_GetFlatStringStorage(flat, &data, &n);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        JS_ThrowTypeError(ctx, "not a string");
        return -1;
    }
    if (wide) {
        const uint16_t *w = (const uint16_t *)data;
        uint8_t *buf;
        uint32_t i;
        for (i = 0; i < n; i++) {
            if (w[i] > 255) {
                JS_FreeValue(ctx, flat);
                throw_invalid_char(ctx);
                return -1;
            }
        }
        buf = (uint8_t *)js_malloc(ctx, n ? n : 1);
        if (!buf) {
            JS_FreeValue(ctx, flat);
            return -1;
        }
        for (i = 0; i < n; i++)
            buf[i] = (uint8_t)w[i];
        JS_FreeValue(ctx, flat);
        *pbytes = buf;
        *plen = n;
        *keep = JS_NULL; /* marker: caller js_free(*pbytes) */
        return 0;
    }
    *pbytes = (const uint8_t *)data;
    *plen = n;
    *keep = flat; /* keep latin-1 storage alive */
    return 0;
}

static JSValue js_btoa(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    JSValue s, keep, out;
    const uint8_t *in;
    uint32_t n, i, j, out_len;
    uint8_t *dst;
    (void)this_val;

    s = to_string_arg(ctx, argc, argv);
    if (JS_IsException(s))
        return s;
    if (latin1_bytes(ctx, s, &in, &n, &keep)) {
        JS_FreeValue(ctx, s);
        return JS_EXCEPTION;
    }
    JS_FreeValue(ctx, s);
    out_len = ((n + 2) / 3) * 4;
    dst = (uint8_t *)js_malloc(ctx, out_len ? out_len : 1);
    if (!dst) {
        if (JS_IsNull(keep))
            js_free(ctx, (void *)in);
        else
            JS_FreeValue(ctx, keep);
        return JS_EXCEPTION;
    }
    j = 0;
    for (i = 0; i < n; i += 3) {
        unsigned a = in[i];
        unsigned b = i + 1 < n ? in[i + 1] : 0;
        unsigned c = i + 2 < n ? in[i + 2] : 0;
        dst[j++] = (uint8_t)B64[a >> 2];
        dst[j++] = (uint8_t)B64[((a & 3) << 4) | (b >> 4)];
        dst[j++] = (i + 1 < n) ? (uint8_t)B64[((b & 15) << 2) | (c >> 6)] : '=';
        dst[j++] = (i + 2 < n) ? (uint8_t)B64[c & 63] : '=';
    }
    if (JS_IsNull(keep))
        js_free(ctx, (void *)in);
    else
        JS_FreeValue(ctx, keep);
    out = JS_NewStringLatin1(ctx, (const char *)dst, out_len);
    js_free(ctx, dst);
    return out;
}

static JSValue js_atob(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    JSValue s, flat, out;
    const void *data = NULL;
    uint32_t n = 0, i, nvalid = 0, pad = 0, di = 0;
    int wide;
    uint8_t *dst;
    uint32_t acc = 0, nbits = 0;
    (void)this_val;

    s = to_string_arg(ctx, argc, argv);
    if (JS_IsException(s))
        return s;
    flat = JS_ToFlatString(ctx, s);
    JS_FreeValue(ctx, s);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &n);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    /* Count digits; allow url-safe alphabet and '.'/'=' padding. */
    for (i = 0; i < n; i++) {
        unsigned c = wide ? ((const uint16_t *)data)[i] : ((const uint8_t *)data)[i];
        int d;
        if (c > 255) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        if (is_b64_space((unsigned char)c))
            continue;
        if (c == '=' || c == '.') {
            pad++;
            continue;
        }
        if (pad) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        d = b64_digit((unsigned char)c);
        if (d < 0) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        nvalid++;
    }
    dst = (uint8_t *)js_malloc(ctx, nvalid ? (nvalid * 3) / 4 + 1 : 1);
    if (!dst) {
        JS_FreeValue(ctx, flat);
        return JS_EXCEPTION;
    }
    for (i = 0; i < n; i++) {
        unsigned c = wide ? ((const uint16_t *)data)[i] : ((const uint8_t *)data)[i];
        int d;
        if (is_b64_space((unsigned char)c) || c == '=' || c == '.')
            continue;
        d = b64_digit((unsigned char)c);
        acc = (acc << 6) | (unsigned)d;
        nbits += 6;
        if (nbits >= 8) {
            nbits -= 8;
            dst[di++] = (uint8_t)((acc >> nbits) & 255);
        }
    }
    JS_FreeValue(ctx, flat);
    out = JS_NewStringLatin1(ctx, (const char *)dst, di);
    js_free(ctx, dst);
    return out;
}

/* ---- UTF-8 (same WTF-8 lone-surrogate rule as __utf8Encode) ---- */

static uint8_t *utf8_encode_units(const void *data, int wide, uint32_t i, uint32_t end,
                                  uint8_t *q)
{
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) {
            uint8_t c = sp[i];
            if (c < 0x80) {
                *q++ = c;
            } else {
                *q++ = (uint8_t)(0xC0 | (c >> 6));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
            }
        }
        return q;
    }
    {
        const uint16_t *sp = (const uint16_t *)data;
        while (i < end) {
            uint32_t c = sp[i];
            if (c < 0x80) {
                *q++ = (uint8_t)c;
                i++;
            } else if (c < 0x800) {
                *q++ = (uint8_t)(0xC0 | (c >> 6));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
                i++;
            } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end &&
                       sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
                uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (sp[i + 1] - 0xDC00);
                *q++ = (uint8_t)(0xF0 | (cp >> 18));
                *q++ = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
                *q++ = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
                *q++ = (uint8_t)(0x80 | (cp & 0x3F));
                i += 2;
            } else {
                *q++ = (uint8_t)(0xE0 | (c >> 12));
                *q++ = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
                i++;
            }
        }
        return q;
    }
}

static size_t utf8_measure_units(const void *data, int wide, uint32_t i, uint32_t end)
{
    size_t n = 0;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) {
            size_t add = (sp[i] < 0x80) ? 1 : 2;
            if (n > SIZE_MAX - add)
                return SIZE_MAX;
            n += add;
        }
        return n;
    }
    {
        const uint16_t *sp = (const uint16_t *)data;
        while (i < end) {
            uint32_t c = sp[i];
            size_t add;
            if (c < 0x80) {
                add = 1;
                i++;
            } else if (c < 0x800) {
                add = 2;
                i++;
            } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end &&
                       sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
                add = 4;
                i += 2;
            } else {
                add = 3;
                i++;
            }
            if (n > SIZE_MAX - add)
                return SIZE_MAX;
            n += add;
        }
        return n;
    }
}

static void u8_buf_free(JSRuntime *rt, void *opaque, void *ptr)
{
    (void)opaque;
    js_free_rt(rt, ptr);
}

static JSValue utf8_to_uint8array(JSContext *ctx, JSValueConst str)
{
    JSValue flat, ab, u8, args[3];
    const void *data = NULL;
    uint32_t slen = 0;
    int wide;
    size_t n;
    uint8_t *buf = NULL;

    flat = JS_ToFlatString(ctx, str);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    n = utf8_measure_units(data, wide, 0, slen);
    if (n == SIZE_MAX) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowRangeError(ctx, "string too long");
    }
    if (n > 0) {
        buf = (uint8_t *)js_malloc(ctx, n);
        if (!buf) {
            JS_FreeValue(ctx, flat);
            return JS_EXCEPTION;
        }
        utf8_encode_units(data, wide, 0, slen, buf);
    }
    JS_FreeValue(ctx, flat);
    ab = JS_NewArrayBuffer(ctx, buf, n, buf ? u8_buf_free : NULL, NULL, 0);
    if (JS_IsException(ab)) {
        js_free(ctx, buf);
        return ab;
    }
    args[0] = ab;
    args[1] = JS_UNDEFINED;
    args[2] = JS_UNDEFINED;
    u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

static JSValue js_textencoder_ctor(JSContext *ctx, JSValueConst new_target,
                                   int argc, JSValueConst *argv)
{
    JSValue proto, obj;
    (void)argc;
    (void)argv;
    if (JS_IsUndefined(new_target))
        return JS_ThrowTypeError(ctx, "TextEncoder constructor");
    proto = JS_GetPropertyStr(ctx, new_target, "prototype");
    if (JS_IsException(proto))
        return proto;
    obj = JS_NewObjectProto(ctx, proto);
    JS_FreeValue(ctx, proto);
    return obj;
}

static JSValue js_textencoder_encode(JSContext *ctx, JSValueConst this_val,
                                     int argc, JSValueConst *argv)
{
    JSValue s, u8;
    (void)this_val;
    if (argc < 1 || JS_IsUndefined(argv[0])) {
        JSValue e = JS_NewStringLatin1(ctx, "", 0);
        if (JS_IsException(e))
            return e;
        u8 = utf8_to_uint8array(ctx, e);
        JS_FreeValue(ctx, e);
        return u8;
    }
    s = JS_ToString(ctx, argv[0]);
    if (JS_IsException(s))
        return s;
    u8 = utf8_to_uint8array(ctx, s);
    JS_FreeValue(ctx, s);
    return u8;
}

static JSValue js_textencoder_encode_into(JSContext *ctx, JSValueConst this_val,
                                          int argc, JSValueConst *argv)
{
    JSValue s, flat, rec;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide;
    size_t dlen = 0, ta_off = 0, ta_len = 0, bpe = 0;
    uint8_t *dst;
    JSValue ab;
    uint32_t written, read;
    (void)this_val;
    if (argc < 2)
        return JS_ThrowTypeError(ctx, "TextEncoder.encodeInto requires 2 arguments");
    s = JS_ToString(ctx, argv[0]);
    if (JS_IsException(s))
        return s;
    ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) {
        JS_FreeValue(ctx, s);
        return ab;
    }
    dst = JS_GetArrayBuffer(ctx, &dlen, ab);
    JS_FreeValue(ctx, ab);
    if (!dst) {
        JS_FreeValue(ctx, s);
        return JS_ThrowTypeError(ctx, "detached buffer");
    }
    dst += ta_off;
    dlen = ta_len;
    (void)bpe;
    flat = JS_ToFlatString(ctx, s);
    JS_FreeValue(ctx, s);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    /* Fill dest; stop before a code point that would overflow. */
    read = 0;
    written = 0;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        while (read < slen) {
            uint8_t c = sp[read];
            size_t need = c < 0x80 ? 1 : 2;
            if (written + need > dlen)
                break;
            utf8_encode_units(data, 0, read, read + 1, dst + written);
            written += (uint32_t)need;
            read++;
        }
    } else {
        const uint16_t *sp = (const uint16_t *)data;
        while (read < slen) {
            uint32_t c = sp[read];
            uint32_t adv = 1;
            size_t need;
            if (c < 0x80)
                need = 1;
            else if (c < 0x800)
                need = 2;
            else if (c >= 0xD800 && c < 0xDC00 && read + 1 < slen &&
                     sp[read + 1] >= 0xDC00 && sp[read + 1] < 0xE000) {
                need = 4;
                adv = 2;
            } else {
                need = 3;
            }
            if (written + need > dlen)
                break;
            utf8_encode_units(data, 1, read, read + adv, dst + written);
            written += (uint32_t)need;
            read += adv;
        }
    }
    JS_FreeValue(ctx, flat);
    rec = JS_NewObject(ctx);
    if (JS_IsException(rec))
        return rec;
    JS_SetPropertyStr(ctx, rec, "read", JS_NewUint32(ctx, read));
    JS_SetPropertyStr(ctx, rec, "written", JS_NewUint32(ctx, written));
    return rec;
}

static JSValue js_textencoder_get_encoding(JSContext *ctx, JSValueConst this_val)
{
    (void)this_val;
    return JS_NewString(ctx, "utf-8");
}

static uint8_t *buffer_source(JSContext *ctx, JSValueConst v, size_t *plen)
{
    size_t ta_off = 0, ta_len = 0, bpe = 0, sz = 0;
    JSValue ab;
    uint8_t *ptr;

    if (JS_IsUndefined(v) || JS_IsNull(v)) {
        *plen = 0;
        return (uint8_t *)"";
    }
    ab = JS_GetTypedArrayBuffer(ctx, v, &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        JS_FreeValue(ctx, ab);
        if (!ptr) {
            JS_ThrowTypeError(ctx, "detached buffer");
            return NULL;
        }
        (void)bpe;
        *plen = ta_len;
        return ptr + ta_off;
    }
    JS_FreeValue(ctx, JS_GetException(ctx));
    ptr = JS_GetArrayBuffer(ctx, plen, v);
    if (ptr)
        return ptr;
    JS_ThrowTypeError(ctx, "TextDecoder.decode: BufferSource");
    return NULL;
}

static JSValue js_textdecoder_ctor(JSContext *ctx, JSValueConst new_target,
                                   int argc, JSValueConst *argv)
{
    JSValue proto, obj;
    if (JS_IsUndefined(new_target))
        return JS_ThrowTypeError(ctx, "TextDecoder constructor");
    if (argc > 0 && !JS_IsUndefined(argv[0]) && !JS_IsNull(argv[0])) {
        const char *label = JS_ToCString(ctx, argv[0]);
        int ok;
        if (!label)
            return JS_EXCEPTION;
        ok = !label[0] || !strcmp(label, "utf-8") || !strcmp(label, "utf8") ||
             !strcmp(label, "unicode-1-1-utf-8");
        JS_FreeCString(ctx, label);
        if (!ok)
            return JS_ThrowRangeError(ctx, "TextDecoder: unsupported encoding");
    }
    proto = JS_GetPropertyStr(ctx, new_target, "prototype");
    if (JS_IsException(proto))
        return proto;
    obj = JS_NewObjectProto(ctx, proto);
    JS_FreeValue(ctx, proto);
    return obj;
}

static JSValue js_textdecoder_decode(JSContext *ctx, JSValueConst this_val,
                                     int argc, JSValueConst *argv)
{
    size_t n = 0;
    uint8_t *p;
    (void)this_val;
    if (argc < 1)
        return JS_NewStringLatin1(ctx, "", 0);
    p = buffer_source(ctx, argv[0], &n);
    if (!p)
        return JS_EXCEPTION;
    return JS_NewStringLen(ctx, (const char *)p, n);
}

static JSValue js_textdecoder_get_encoding(JSContext *ctx, JSValueConst this_val)
{
    (void)this_val;
    return JS_NewString(ctx, "utf-8");
}

static int install_ctor(JSContext *ctx, JSValueConst global, const char *name,
                        JSCFunction *ctor, int length,
                        const JSCFunctionListEntry *proto, int proto_len)
{
    JSValue p, cls;
    p = JS_NewObject(ctx);
    JS_SetPropertyFunctionList(ctx, p, proto, proto_len);
    cls = JS_NewCFunction2(ctx, ctor, name, length, JS_CFUNC_constructor, 0);
    JS_SetConstructor(ctx, cls, p);
    JS_FreeValue(ctx, p);
    JS_DefinePropertyValueStr(ctx, global, name, cls, JS_PROP_C_W_E);
    return 0;
}

static const JSCFunctionListEntry textencoder_proto[] = {
    JS_CGETSET_DEF("encoding", js_textencoder_get_encoding, NULL),
    JS_CFUNC_DEF("encode", 1, js_textencoder_encode),
    JS_CFUNC_DEF("encodeInto", 2, js_textencoder_encode_into),
};

static const JSCFunctionListEntry textdecoder_proto[] = {
    JS_CGETSET_DEF("encoding", js_textdecoder_get_encoding, NULL),
    JS_CFUNC_DEF("decode", 1, js_textdecoder_decode),
};

static double g_perf_origin;
static double g_mono_origin;
static double g_perf_last;

#define ENT_CAP 65536
#define ENT_CTX 8
#define ERR_CTORS 9
static const char *const k_err_ctors[ERR_CTORS] = {
    "Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError",
    "URIError", "EvalError", "AggregateError", "InternalError",
};

typedef struct {
    JSContext *ctx;
    int rec;
    JSValue date_orig;
    JSValue rand_orig;
    JSValue tostr_orig;
    JSValue error_orig[ERR_CTORS];
    char **line;
    int n;
    int cap;
} Ent;
static Ent g_ent[ENT_CTX];
/* Runtimes on different threads claim and release rows concurrently (a player tearing down while the
 * next one starts); a row's fields are touched only by its own context's thread. */
static pthread_mutex_t g_ent_lock = PTHREAD_MUTEX_INITIALIZER;

/* Lookup only: the hooks run on every context, only __bgEntropyStart claims a row. */
static Ent *ent_of(JSContext *ctx)
{
    int i;
    if (!ctx)
        return NULL;
    for (i = 0; i < ENT_CTX; i++)
        if (g_ent[i].ctx == ctx)
            return &g_ent[i];
    return NULL;
}

static Ent *ent_claim(JSContext *ctx)
{
    int i;
    Ent *e = ent_of(ctx);
    if (e || !ctx)
        return e;
    pthread_mutex_lock(&g_ent_lock);
    for (i = 0; i < ENT_CTX; i++) {
        if (!g_ent[i].ctx) {
            e = &g_ent[i];
            e->ctx = ctx;
            break;
        }
    }
    pthread_mutex_unlock(&g_ent_lock);
    if (e) {
        int k;
        e->date_orig = JS_UNDEFINED;
        e->rand_orig = JS_UNDEFINED;
        e->tostr_orig = JS_UNDEFINED;
        for (k = 0; k < ERR_CTORS; k++)
            e->error_orig[k] = JS_UNDEFINED;
    }
    return e;
}

static void ent_line(Ent *e, char tag, const char *fmt, double v)
{
    char buf[48];
    int n;
    char *s;
    if (!e || !e->rec || e->n >= ENT_CAP)
        return;
    n = snprintf(buf, sizeof(buf), fmt, tag, v);
    if (n < 0)
        return;
    if (e->n >= e->cap) {
        int cap = e->cap ? e->cap * 2 : 1024;
        char **nl = (char **)realloc(e->line, sizeof(char *) * (size_t)cap);
        if (!nl)
            return;
        e->line = nl;
        e->cap = cap;
    }
    s = (char *)malloc((size_t)n + 1);
    if (!s)
        return;
    memcpy(s, buf, (size_t)n + 1);
    e->line[e->n++] = s;
}

static void ent_raw(Ent *e, const char *s)
{
    size_t n;
    char *p;
    if (!e || !e->rec || !s || e->n >= ENT_CAP)
        return;
    if (e->n >= e->cap) {
        int cap = e->cap ? e->cap * 2 : 1024;
        char **nl = (char **)realloc(e->line, sizeof(char *) * (size_t)cap);
        if (!nl)
            return;
        e->line = nl;
        e->cap = cap;
    }
    n = strlen(s);
    p = (char *)malloc(n + 1);
    if (!p)
        return;
    memcpy(p, s, n + 1);
    e->line[e->n++] = p;
}

static void ent_json(Ent *e, JSContext *ctx, char tag, JSValueConst v)
{
    JSValue js;
    const char *s;
    char *line;
    size_t n;
    if (!e || !e->rec)
        return;
    js = JS_JSONStringify(ctx, v, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(js))
        return;
    s = JS_ToCString(ctx, js);
    JS_FreeValue(ctx, js);
    if (!s)
        return;
    n = strlen(s);
    line = (char *)malloc(n + 3);
    if (line) {
        line[0] = tag;
        line[1] = ' ';
        memcpy(line + 2, s, n + 1);
        ent_raw(e, line);
        free(line);
    }
    JS_FreeCString(ctx, s);
}

static JSValue js_fn_tostr_rec(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue v;
    if (!e || JS_IsUndefined(e->tostr_orig))
        return JS_EXCEPTION;
    v = JS_Call(ctx, e->tostr_orig, this_val, argc, argv);
    if (e->rec && !JS_IsException(v))
        ent_json(e, ctx, 't', v);
    return v;
}

static JSValue js_err_prop_get(JSContext *ctx, JSValueConst this_val, int magic)
{
    Ent *e = ent_of(ctx);
    JSValue v;
    if (!(e && e->rec))
        return JS_GetPropertyStr(ctx, this_val, magic ? "\x01bgS" : "\x01bgN");
    v = JS_GetPropertyStr(ctx, this_val, magic ? "\x01bgS" : "\x01bgN");
    if (!JS_IsException(v))
        ent_json(e, ctx, magic ? 'S' : 'N', v);
    return v;
}

static void err_install_gets(JSContext *ctx, JSValueConst err)
{
    JSValue name, stack, gn, gs;
    JSAtom an, as;
    JSCFunctionType ft;
    name = JS_GetPropertyStr(ctx, err, "name");
    stack = JS_GetPropertyStr(ctx, err, "stack");
    JS_DefinePropertyValueStr(ctx, err, "\x01bgN", name, 0);
    JS_DefinePropertyValueStr(ctx, err, "\x01bgS", stack, 0);
    an = JS_NewAtom(ctx, "name");
    as = JS_NewAtom(ctx, "stack");
    ft.getter_magic = js_err_prop_get;
    gn = JS_NewCFunction2(ctx, ft.generic, "get name", 0, JS_CFUNC_getter_magic, 0);
    gs = JS_NewCFunction2(ctx, ft.generic, "get stack", 0, JS_CFUNC_getter_magic, 1);
    JS_DefinePropertyGetSet(ctx, err, an, gn, JS_UNDEFINED,
                            JS_PROP_CONFIGURABLE | JS_PROP_ENUMERABLE);
    JS_DefinePropertyGetSet(ctx, err, as, gs, JS_UNDEFINED,
                            JS_PROP_CONFIGURABLE | JS_PROP_ENUMERABLE);
    JS_FreeAtom(ctx, an);
    JS_FreeAtom(ctx, as);
}

static JSValue js_error_ctor_rec(JSContext *ctx, JSValueConst this_val,
                                 int argc, JSValueConst *argv, int magic)
{
    Ent *e = ent_of(ctx);
    JSValue err;
    if (!e || magic < 0 || magic >= ERR_CTORS || JS_IsUndefined(e->error_orig[magic]))
        return JS_EXCEPTION;
    err = JS_CallConstructor2(ctx, e->error_orig[magic],
                              JS_IsUndefined(this_val) ? e->error_orig[magic] : this_val,
                              argc, argv);
    /* GETSET on every Error for the process lifetime. rec is mill-only;
     * playback Errors must stay native data properties. */
    if (!JS_IsException(err) && e->rec)
        err_install_gets(ctx, err);
    return err;
}

static double timespec_ms(const struct timespec *ts)
{
    return (double)ts->tv_sec * 1000.0 + (double)ts->tv_nsec / 1000000.0;
}

static double realtime_ms(void)
{
    struct timespec ts;
    struct timeval tv;
    if (clock_gettime(CLOCK_REALTIME, &ts) == 0)
        return timespec_ms(&ts);
    gettimeofday(&tv, NULL);
    return (double)tv.tv_sec * 1000.0 + (double)tv.tv_usec / 1000.0;
}

static double mono_ms(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0)
        return timespec_ms(&ts);
    return realtime_ms();
}

static int64_t date_now_ms(void)
{
    struct timespec ts;
    struct timeval tv;
    if (clock_gettime(CLOCK_REALTIME, &ts) == 0)
        return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + (tv.tv_usec / 1000);
}

/* Monotonic ns as ms. Do not quantize — BotGuard times 1000 Math.random against this. */
static JSValue js_perf_now(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    double n;
    Ent *e;
    (void)this_val;
    (void)argc;
    (void)argv;
    n = mono_ms() - g_mono_origin;
    if (n < g_perf_last)
        n = g_perf_last;
    g_perf_last = n;
    e = ent_of(ctx);
    if (e && e->rec)
        ent_line(e, 'p', "%c %.17g", n);
    return JS_NewFloat64(ctx, n);
}

/* Integer UNIX ms. Record after the read, only while rec. */
static JSValue js_date_now_rec(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    int64_t ms = date_now_ms();
    Ent *e = ent_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (e && e->rec)
        ent_line(e, 'd', "%c %.0f", (double)ms);
    return JS_NewInt64(ctx, ms);
}

static void ent_date_ctor(JSContext *ctx, double ms)
{
    Ent *e = ent_of(ctx);
    if (e && e->rec)
        ent_line(e, 'w', "%c %.0f", ms);
}

static const char *host_platform(void)
{
#if defined(__ANDROID__)
    return "android";
#elif defined(__APPLE__) && TARGET_OS_IPHONE
    return "ios";
#elif defined(__APPLE__)
    return "macos";
#elif defined(__linux__)
    return "linux";
#elif defined(_WIN32)
    return "windows";
#else
    return "unknown";
#endif
}

static const char *host_arch(void)
{
#if defined(__aarch64__)
    return "arm64";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "arm";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

static JSValue bg_host_info(JSContext *ctx)
{
    JSValue o = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, o, "platform", JS_NewString(ctx, host_platform()));
    JS_SetPropertyStr(ctx, o, "arch", JS_NewString(ctx, host_arch()));
    return o;
}

static JSValue js_math_random_rec(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue v;
    (void)argc;
    (void)argv;
    if (!e || JS_IsUndefined(e->rand_orig))
        return JS_EXCEPTION;
    v = JS_Call(ctx, e->rand_orig, this_val, 0, NULL);
    if (e->rec && !JS_IsException(v)) {
        double d;
        if (!JS_ToFloat64(ctx, &d, v))
            ent_line(e, 'r', "%c %.17g", d);
    }
    return v;
}

static void ent_clear(Ent *e)
{
    int i;
    if (!e)
        return;
    for (i = 0; i < e->n; i++)
        free(e->line[i]);
    free(e->line);
    e->line = NULL;
    e->n = 0;
    e->cap = 0;
}

/* Install C Date.now / Math.random so BotGuard binds these, not later wrappers.
 * rec stays 0 until begin. Do not restore: snapshot may close over the wrappers.
 * Returns {platform, arch} for the remill dump. */
static JSValue js_bg_entropy_start(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_claim(ctx);
    JSValue g, date, math;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e)
        return JS_UNDEFINED;
    e->rec = 0;
    ent_clear(e);
    g = JS_GetGlobalObject(ctx);
    date = JS_GetPropertyStr(ctx, g, "Date");
    math = JS_GetPropertyStr(ctx, g, "Math");
    if (JS_IsUndefined(e->date_orig)) {
        e->date_orig = JS_GetPropertyStr(ctx, date, "now");
        JS_SetPropertyStr(ctx, date, "now", JS_NewCFunction(ctx, js_date_now_rec, "now", 0));
    }
    if (JS_IsUndefined(e->rand_orig)) {
        e->rand_orig = JS_GetPropertyStr(ctx, math, "random");
        JS_SetPropertyStr(ctx, math, "random", JS_NewCFunction(ctx, js_math_random_rec, "random", 0));
    }
    if (JS_IsUndefined(e->tostr_orig)) {
        JSValue fn = JS_GetPropertyStr(ctx, g, "Function");
        JSValue proto = JS_GetPropertyStr(ctx, fn, "prototype");
        e->tostr_orig = JS_GetPropertyStr(ctx, proto, "toString");
        JS_SetPropertyStr(ctx, proto, "toString",
                          JS_NewCFunction(ctx, js_fn_tostr_rec, "toString", 0));
        JS_FreeValue(ctx, proto);
        JS_FreeValue(ctx, fn);
    }
    {
        int k;
        for (k = 0; k < ERR_CTORS; k++) {
            JSValue orig, wrap, proto;
            if (!JS_IsUndefined(e->error_orig[k]))
                continue;
            orig = JS_GetPropertyStr(ctx, g, k_err_ctors[k]);
            if (!JS_IsFunction(ctx, orig)) {
                JS_FreeValue(ctx, orig);
                continue;
            }
            e->error_orig[k] = orig;
            wrap = JS_NewCFunctionMagic(ctx, js_error_ctor_rec, k_err_ctors[k], 1,
                                        JS_CFUNC_constructor_or_func_magic, k);
            proto = JS_GetPropertyStr(ctx, orig, "prototype");
            JS_SetConstructor(ctx, wrap, proto);
            JS_FreeValue(ctx, proto);
            JS_SetPropertyStr(ctx, g, k_err_ctors[k], wrap);
        }
    }
    JS_SetDateCtorHook(ent_date_ctor);
    JS_FreeValue(ctx, date);
    JS_FreeValue(ctx, math);
    JS_FreeValue(ctx, g);
    return bg_host_info(ctx);
}

static JSValue js_bg_entropy_begin(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e)
        return JS_UNDEFINED;
    ent_clear(e);
    e->rec = 1;
    return JS_UNDEFINED;
}

static JSValue js_bg_entropy_end(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (e)
        e->rec = 0;
    return JS_UNDEFINED;
}

static JSValue js_bg_entropy_stop(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (e) {
        e->rec = 0;
        ent_clear(e);
    }
    return JS_UNDEFINED;
}

static size_t json_escape_len(const char *s)
{
    size_t n = 0;
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t')
            n += 2;
        else if (c < 0x20)
            n += 6;
        else
            n++;
    }
    return n;
}

static char *json_escape_put(char *d, const char *s)
{
    static const char hex[] = "0123456789abcdef";
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') {
            *d++ = '\\';
            *d++ = (char)c;
        } else if (c == '\n') {
            *d++ = '\\';
            *d++ = 'n';
        } else if (c == '\r') {
            *d++ = '\\';
            *d++ = 'r';
        } else if (c == '\t') {
            *d++ = '\\';
            *d++ = 't';
        } else if (c < 0x20) {
            memcpy(d, "\\u00", 4);
            d += 4;
            *d++ = hex[c >> 4];
            *d++ = hex[c & 15];
        } else {
            *d++ = (char)c;
        }
    }
    return d;
}

/* JSON string bytes of the tape (quoted, escaped, lines joined by \n). No JS string. */
static JSValue js_bg_entropy_dump(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    size_t need = 2;
    char *out, *d;
    int i;
    JSValue ab, u8, args[3];
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e || e->n <= 0)
        return JS_NULL;
    for (i = 0; i < e->n; i++) {
        need += json_escape_len(e->line[i]);
        if (i + 1 < e->n)
            need += 2;
    }
    out = (char *)js_malloc(ctx, need);
    if (!out)
        return JS_EXCEPTION;
    d = out;
    *d++ = '"';
    for (i = 0; i < e->n; i++) {
        d = json_escape_put(d, e->line[i]);
        if (i + 1 < e->n) {
            *d++ = '\\';
            *d++ = 'n';
        }
    }
    *d++ = '"';
    ent_clear(e);
    ab = JS_NewArrayBuffer(ctx, (uint8_t *)out, (size_t)(d - out), u8_buf_free, NULL, 0);
    if (JS_IsException(ab)) {
        js_free(ctx, out);
        return ab;
    }
    args[0] = ab;
    args[1] = JS_UNDEFINED;
    args[2] = JS_UNDEFINED;
    u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

void qjs_web_release(JSContext *ctx)
{
    Ent *e = ent_of(ctx);
    int k;
    if (!e)
        return;
    JS_FreeValue(ctx, e->date_orig);
    JS_FreeValue(ctx, e->rand_orig);
    JS_FreeValue(ctx, e->tostr_orig);
    for (k = 0; k < ERR_CTORS; k++)
        JS_FreeValue(ctx, e->error_orig[k]);
    ent_clear(e);
    pthread_mutex_lock(&g_ent_lock);
    memset(e, 0, sizeof(*e));
    pthread_mutex_unlock(&g_ent_lock);
}

static void install_performance(JSContext *ctx, JSValueConst global)
{
    JSValue perf;
    g_mono_origin = mono_ms() - 1847.3;
    g_perf_origin = realtime_ms() - 1847.3;
    g_perf_last = 0;
    perf = JS_NewObject(ctx);
    JS_DefinePropertyValueStr(ctx, perf, "timeOrigin",
                              JS_NewFloat64(ctx, g_perf_origin), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, perf, "now",
                              JS_NewCFunction(ctx, js_perf_now, "now", 0), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, global, "performance", JS_DupValue(ctx, perf), JS_PROP_C_W_E);
    JS_FreeValue(ctx, perf);
}

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

static int tz_looks_iana(const char *s)
{
    return s && s[0] && (strchr(s, '/') || !strcmp(s, "UTC") || !strcmp(s, "GMT"));
}

static const char *zoneinfo_tail(const char *path)
{
    const char *p = path;
    const char *hit = NULL;
    while ((p = strstr(p, "zoneinfo/"))) {
        hit = p + 9;
        p = hit;
    }
    return hit && hit[0] ? hit : NULL;
}

/** IANA id of the host zone (TZ, Android persist.sys.timezone, /etc/localtime). */
static JSValue js_time_zone(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    const char *tz;
    char path[PATH_MAX];
    ssize_t n;
    const char *tail;
    (void)this_val;
    (void)argc;
    (void)argv;
    tz = getenv("TZ");
    if (tz && tz[0] == ':')
        tz++;
    if (tz_looks_iana(tz))
        return JS_NewString(ctx, tz);
#ifdef __ANDROID__
    {
        char prop[PROP_VALUE_MAX];
        if (__system_property_get("persist.sys.timezone", prop) > 0 && tz_looks_iana(prop))
            return JS_NewString(ctx, prop);
    }
#endif
#ifndef _WIN32
    // Windows has no /etc/localtime symlink; fall through to the UTC default (TZ env was already
    // consulted above). Native Windows timezone resolution (GetDynamicTimeZoneInformation) is a
    // separate step and not wired here yet.
    n = readlink("/etc/localtime", path, sizeof(path) - 1);
    if (n > 0) {
        path[n] = 0;
        tail = zoneinfo_tail(path);
        if (tz_looks_iana(tail))
            return JS_NewString(ctx, tail);
    }
#endif
    return JS_NewString(ctx, "UTC");
}

void qjs_register_web(JSContext *ctx)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "btoa", JS_NewCFunction(ctx, js_btoa, "btoa", 0));
    JS_SetPropertyStr(ctx, g, "atob", JS_NewCFunction(ctx, js_atob, "atob", 0));
    JS_SetPropertyStr(ctx, g, "__timeZone", JS_NewCFunction(ctx, js_time_zone, "__timeZone", 0));
    install_ctor(ctx, g, "TextEncoder", js_textencoder_ctor, 0,
                 textencoder_proto, countof(textencoder_proto));
    install_ctor(ctx, g, "TextDecoder", js_textdecoder_ctor, 0,
                 textdecoder_proto, countof(textdecoder_proto));
    install_performance(ctx, g);
    JS_SetPropertyStr(ctx, g, "__bgEntropyStart",
                      JS_NewCFunction(ctx, js_bg_entropy_start, "__bgEntropyStart", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyBegin",
                      JS_NewCFunction(ctx, js_bg_entropy_begin, "__bgEntropyBegin", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyEnd",
                      JS_NewCFunction(ctx, js_bg_entropy_end, "__bgEntropyEnd", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyStop",
                      JS_NewCFunction(ctx, js_bg_entropy_stop, "__bgEntropyStop", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyDump",
                      JS_NewCFunction(ctx, js_bg_entropy_dump, "__bgEntropyDump", 0));
    JS_FreeValue(ctx, g);
}
