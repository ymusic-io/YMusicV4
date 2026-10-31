/*
 * ASan/UBSan harness for the C pipe layer (dev-only; not part of any shipped build).
 *
 * Drives the REAL engine — quickjs_native.c's qjs_* API over the real engine bundle — through the
 * pipe scenarios whose memory management is now C's problem: fragmented delivery into every read
 * type, cap/backpressure/drain, RESET into a parked read, closeRead vs a writing host, a refused
 * open, GC with parked jobs, and teardown with live parked pipes.
 *
 * Build & run (from quickjs/native; build the TEST bundle first — it carries the stress
 * fixtures: ./gradlew :engine-js:bundleTestJs):
 *   JAVA_HOME=$(/usr/libexec/java_home) cmake -S . -B build-asan
 *   cmake --build build-asan --target pipe_stress
 *   ./build-asan/pipe_stress ../../engine-js/build/dist/engine-test.js
 * Exits 0 with "ALL OK" or 1 on the first failed check / any sanitizer report.
 * (Apple clang's ASan has no leak detection; this catches UAF/overflow/double-free + UB.)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "quickjs_native.c" /* the harness IS the host — needs the engine struct for JS_RunGC */

#define CHECK(cond, ...) do { \
        if (!(cond)) { \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__); \
            fprintf(stderr, "\n"); \
            exit(1); \
        } \
    } while (0)

/* ---- a tiny in-C host: routes by command, records everything ---- */

#define MAXP 16
typedef struct {
    struct {
        uint32_t id;
        int used, slow;      /* slow: accept at most 1000 bytes per write */
        size_t got;
        uint32_t sum;
        int close_read, ended, drains;
        char reason[256];
    } p[MAXP];
} Host;

static int host_slot(Host *h, uint32_t id) {
    for (int i = 0; i < MAXP; i++)
        if (h->p[i].used && h->p[i].id == id) return i;
    return -1;
}

/* Host-opened pipes (qjs_open from main) have no OPEN callback; give their events a slot too. */
static int host_slot_or_new(Host *h, uint32_t id) {
    int i = host_slot(h, id);
    if (i >= 0) return i;
    for (i = 0; i < MAXP; i++) {
        if (!h->p[i].used) {
            h->p[i].used = 1;
            h->p[i].id = id;
            return i;
        }
    }
    return -1;
}

static int host_cb(void *user, int op, uint32_t id, const uint8_t *data, size_t len) {
    Host *h = (Host *)user;
    switch (op) {
    case QJS_OP_OPEN: {
        const char *cmd = (const char *)data;
        if (strcmp(cmd, "devnull") != 0 && strcmp(cmd, "slow") != 0) return -1;
        for (int i = 0; i < MAXP; i++) {
            if (!h->p[i].used) {
                h->p[i].used = 1;
                h->p[i].id = id;
                h->p[i].slow = strcmp(cmd, "slow") == 0;
                return 0;
            }
        }
        return -1;
    }
    case QJS_OP_WRITE: {
        int i = host_slot(h, id);
        if (i < 0) return -1;
        size_t n = h->p[i].slow && len > 1000 ? 1000 : len;
        for (size_t k = 0; k < n; k++) h->p[i].sum = (h->p[i].sum + data[k]) & 0xffff;
        h->p[i].got += n;
        return (int)n;
    }
    case QJS_OP_CLOSE_WRITE: {
        int i = host_slot_or_new(h, id);
        if (i >= 0) {
            h->p[i].ended = 1;
            if (data) snprintf(h->p[i].reason, sizeof(h->p[i].reason), "%.*s", (int)len, (const char *)data);
        }
        return 0;
    }
    case QJS_OP_CLOSE_READ: {
        int i = host_slot_or_new(h, id);
        if (i >= 0) h->p[i].close_read = 1;
        return 0;
    }
    case QJS_OP_DRAIN: {
        int i = host_slot_or_new(h, id);
        if (i >= 0) h->p[i].drains++;
        return 0;
    }
    case QJS_OP_LOG:
        fprintf(stderr, "[js log %u] %.*s\n", id, (int)len, (const char *)data);
        return 0;
    }
    return -1;
}

static char *eval(QjsEngine *e, const char *js) {
    int isErr = 0;
    char *r = qjs_eval_sync(e, js, "<stress>", &isErr);
    CHECK(!isErr, "eval failed: %s (in: %s)", r ? r : "?", js);
    return r; /* caller frees */
}

static void eval_expect(QjsEngine *e, const char *js, const char *want) {
    char *r = eval(e, js);
    CHECK(r != NULL && strcmp(r, want) == 0, "eval %s -> %s, want %s", js, r ? r : "(null)", want);
    free(r);
}

static char *read_file(const char *path) {
    FILE *f = fopen(path, "rb");
    CHECK(f != NULL, "cannot open %s", path);
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = (char *)malloc((size_t)n + 1);
    CHECK(buf && fread(buf, 1, (size_t)n, f) == (size_t)n, "read %s", path);
    buf[n] = 0;
    fclose(f);
    return buf;
}

/* S1: every read type over worst-case fragmentation (one byte per write). */
static void scenario_fragmented_reads(QjsEngine *e) {
    CHECK(qjs_open(e, 1, "t1") == 0, "open t1"); /* handler = the bundle's stress fixture */
    static const uint8_t payload[] = {
        'h', 'e', 'l', 'l', 'o', 0xF0, 0x9F, 0x8E, 0xB5, '\r', '\n',            /* hello🎵\r\n */
        '{', '"', 'a', '"', ':', '[', '1', ',', '2', ']', ',',
        '"', 's', '"', ':', '"', 0xCE, 0xB2, '"', '}',                          /* {"a":[1,2],"s":"β"} */
        0xFF, 0xFF, 0xFF, 0xF9,                                                 /* int32 -7 */
        0x00, 0x00, 0x01, 0x1F, 0x71, 0xFB, 0x04, 0xCB,                         /* int64 1234567890123 */
        't', 'a', 'i', 'l', 0xE2, 0x9C, 0x93                                    /* tail✓ */
    };
    for (size_t i = 0; i < sizeof(payload); i++)
        CHECK(qjs_write(e, 1, payload + i, 1) == 1, "byte %zu accepted", i);
    qjs_close_write(e, 1, NULL);
    eval_expect(e, "globalThis.r1", "hello\xF0\x9F\x8E\xB5|{\"a\":[1,2],\"s\":\"\xCE\xB2\"}|-7|1234567890123|tail\xE2\x9C\x93|-1");
}

/* S2: fill the 64 KB cap against a gated reader, then drain and finish. */
static void scenario_backpressure(QjsEngine *e, Host *h) {
    CHECK(qjs_open(e, 3, "t2") == 0, "open t2"); /* handler = the bundle's stress fixture */
    enum { TOTAL = 300000, CHUNK = 8192 };
    uint8_t chunk[CHUNK];
    size_t sent = 0;
    uint32_t sum = 0;
    int saw_partial = 0;
    while (sent < TOTAL) {
        size_t want = TOTAL - sent < CHUNK ? TOTAL - sent : CHUNK;
        for (size_t i = 0; i < want; i++) chunk[i] = (uint8_t)((sent + i) * 31);
        int acc = qjs_write(e, 3, chunk, want);
        CHECK(acc >= 0, "write accepted (got %d)", acc);
        for (int i = 0; i < acc; i++) sum = (sum + chunk[i]) & 0xffff;
        sent += (size_t)acc;
        if ((size_t)acc < want) {
            saw_partial = 1;
            /* buffer full: open the gate; the reader drains and the DRAIN hook fires */
            free(eval(e, "if (globalThis.go2) { var f = go2; go2 = null; f(); } void 0"));
        }
    }
    CHECK(saw_partial, "the cap was actually exercised");
    int slot = -1;
    for (int i = 0; i < MAXP; i++) if (h->p[i].used && h->p[i].id == 3) slot = i;
    (void)slot;
    qjs_close_write(e, 3, NULL);
    char want[64];
    snprintf(want, sizeof(want), "%d:%u", TOTAL, sum);
    eval_expect(e, "globalThis.r2", want);
}

/* S3: RESET lands in a parked strict line read as a thrown reason. */
static void scenario_reset(QjsEngine *e) {
    CHECK(qjs_open(e, 5, "t3") == 0, "open t3"); /* handler = the bundle's stress fixture */
    CHECK(qjs_write(e, 5, (const uint8_t *)"no newline yet", 14) == 14, "partial line accepted");
    qjs_close_write(e, 5, "boom\xF0\x9F\x92\xA5");
    eval_expect(e, "globalThis.r3", "err:boom\xF0\x9F\x92\xA5");
}

/* S4: JS closeRead -> host write returns EPIPE; a read after closeRead throws. */
static void scenario_close_read(QjsEngine *e, Host *h) {
    CHECK(qjs_open(e, 7, "t4") == 0, "open t4"); /* handler = the bundle's stress fixture */
    uint8_t b = 42;
    CHECK(qjs_write(e, 7, &b, 1) == -1, "write after closeRead is EPIPE");
    int i = host_slot(h, 7);
    CHECK(i >= 0 && h->p[i].close_read, "host saw the closeRead");
    eval_expect(e, "globalThis.r4", "threw:receive() after closeRead()");
}

/* S5: outbound — JS writes through the staging sink to a slow host, paced by drains. */
static void scenario_outbound(QjsEngine *e, Host *h) {
    CHECK(qjs_open(e, 11, "t5") == 0, "open t5"); /* trigger: the fixture opens 'slow' and streams */
    uint32_t id = 0;
    for (int i = 0; i < MAXP; i++)
        if (h->p[i].used && h->p[i].slow) id = h->p[i].id;
    CHECK(id != 0, "slow pipe opened");
    for (int guard = 0; guard < 100; guard++) { /* pump drains until the JS side finishes */
        char *r = eval(e, "globalThis.r5 === 'sent' ? 'sent' : 'pending'");
        int done = strcmp(r, "sent") == 0;
        free(r);
        if (done) break;
        qjs_drain(e, id);
    }
    eval_expect(e, "globalThis.r5", "sent");
    int i = host_slot(h, id);
    CHECK(i >= 0 && h->p[i].got == 50004 && h->p[i].ended, "host got the full body (got=%zu)", h->p[i].got);
}

/* S6: a refused open leaves nothing behind on either side. */
static void scenario_refused_open(QjsEngine *e) {
    CHECK(qjs_open(e, 13, "t6") == 0, "open t6"); /* trigger: the fixture tries open('nope') */
    eval_expect(e, "globalThis.r6", "open(\"nope\") refused by host");
}

/* S7: GC with a parked job (gc_mark must keep dst/capability/parser alive), then complete it. */
static void scenario_gc_parked(QjsEngine *e) {
    CHECK(qjs_open(e, 9, "t7") == 0, "open t7"); /* handler = the bundle's stress fixture */
    CHECK(qjs_write(e, 9, (const uint8_t *)"{\"deep\":[[[", 11) == 11, "half a json accepted");
    JS_RunGC(e->rt);
    CHECK(qjs_write(e, 9, (const uint8_t *)"1]]]}", 5) == 5, "rest accepted");
    qjs_close_write(e, 9, NULL);
    eval_expect(e, "globalThis.r7", "{\"deep\":[[[1]]]}");
}

/* S8: teardown with parked reads (incl. a JSON job holding half-built containers), a never-drained
 * buffer, and a live registry (finalizer + gc_mark paths). */
static void scenario_teardown(const char *script, Host *h) {
    char *err = NULL;
    QjsEngine *e = qjs_create(1, NULL, host_cb, h);
    CHECK(e != NULL, "second engine");
    CHECK(qjs_eval_engine(e, script, &err) == 0, "second engine eval: %s", err ? err : "?");
    CHECK(qjs_open(e, 1, "park") == 0, "open park"); /* handlers = the bundle's stress fixtures */
    CHECK(qjs_open(e, 3, "full") == 0, "open full");
    CHECK(qjs_open(e, 5, "jsonpark") == 0, "open jsonpark");
    CHECK(qjs_write(e, 5, (const uint8_t *)"{\"a\":[1,{\"b\":", 13) == 13, "half a json parked");
    uint8_t junk[10000];
    memset(junk, 7, sizeof(junk));
    CHECK(qjs_write(e, 3, junk, sizeof(junk)) == (int)sizeof(junk), "junk buffered");
    JS_RunGC(e->rt); /* gc_mark over the parked jobs' held values */
    qjs_close(e); /* parked jobs + buffered segments + registered ids all reclaimed here */
}

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "../../engine-js/build/dist/engine-test.js";
    char *script = read_file(path);
    Host host;
    memset(&host, 0, sizeof(host));
    char *err = NULL;
    QjsEngine *e = qjs_create(1, NULL, host_cb, &host);
    CHECK(e != NULL, "create");
    CHECK(qjs_eval_engine(e, script, &err) == 0, "eval engine: %s", err ? err : "?");

    scenario_fragmented_reads(e);
    scenario_backpressure(e, &host);
    scenario_reset(e);
    scenario_close_read(e, &host);
    scenario_outbound(e, &host);
    scenario_refused_open(e);
    scenario_gc_parked(e);
    JS_RunGC(e->rt);
    qjs_close(e);

    Host host2;
    memset(&host2, 0, sizeof(host2));
    scenario_teardown(script, &host2);

    free(script);
    printf("ALL OK\n");
    return 0;
}
