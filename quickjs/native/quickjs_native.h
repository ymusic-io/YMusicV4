/*
 * Plain-C QuickJS transport for the Kotlin/Native (cinterop) path — the non-JNI counterpart of
 * quickjs_jni.c. The pipe multiplexer speaks a few operations per direction rather than one framed
 * byte blob, so no per-frame header is allocated and commands/reasons cross as native strings.
 *
 * Each pipe direction is a bounded (64 KB), flow-controlled byte stream: write() copies its bytes
 * once into the C-owned pipe buffer (pooled 8 KB segments, see quickjs_pipe.h — no JS runs on the
 * write path) and returns the accepted byte count. accepted < len is backpressure: the writer
 * waits for a DRAIN notify. -1 means the reader closed its read end (EPIPE).
 * close_write(reason=NULL) is a clean END; a non-NULL reason is a RESET (the peer's read throws
 * instead of seeing EOF).
 *
 * Strings are UTF-8 (Kotlin/Native's toKString/cstr are UTF-8, astral-correct).
 */
#ifndef QUICKJS_NATIVE_H
#define QUICKJS_NATIVE_H

#include <stddef.h>
#include <stdint.h>

typedef struct QjsEngine QjsEngine;

/* op codes for the inbound (JS->host) callback */
#define QJS_OP_OPEN        0
#define QJS_OP_WRITE       1 /* data/len = payload (valid only during the call); return = accepted, -1 EPIPE */
#define QJS_OP_CLOSE_WRITE 2 /* data = RESET reason (NUL-terminated UTF-8) or NULL for END */
#define QJS_OP_CLOSE_READ  3 /* abandon read end: host's next write gets EPIPE */
#define QJS_OP_DRAIN       4 /* flow control: the reader drained; resume the parked writer */
#define QJS_OP_LOG         5 /* console/diagnostics: id = level (0 debug, 1 info, 2 warn,
                              * 3 error, 4 unhandled promise rejection), data/len = UTF-8
                              * message (valid only during the call); return value ignored */


/* Invoked when JS reaches the host. OPEN: data = command (NUL-terminated UTF-8), len = its
 * length — the return value is 0 (accepted) or -1 (refused: no handler / callback failed), so an
 * open of an unserved command fails AT the open, C-error-code style. WRITE: data = payload bytes,
 * len = length — consume synchronously (the pointer dies with the call); the return value is the
 * accepted byte count (or -1 EPIPE). CLOSE_WRITE: data = reason or NULL, len = reason length.
 * CLOSE_READ / DRAIN: data = NULL, len = 0. Return values of the other ops are ignored. */
typedef int (*QjsInboundCb)(void *user, int op, uint32_t id, const uint8_t *data, size_t len);

/* Create a bare engine: install the pipe/JSON natives and the frozen `versions` global
 * ({app: app_version} plus the flat JSON object [versions_json], or NULL) — no script. The host
 * can serve commands before any JS exists; the engine script loads via qjs_eval_engine. */
QjsEngine *qjs_create(int app_version, const char *versions_json, QjsInboundCb cb, void *user);

/* Evaluate the engine script (the :engine-js bundle) and capture the transport globals
 * (__jsOpen/__jsClose/__jsCloseRead/__jsDrain) right after — call exactly once, before any
 * qjs_open. Returns 0, or -1 with *err set to a malloc'd message (free via qjs_free); the
 * engine stays valid on failure (tear down via qjs_close). */
int qjs_eval_engine(QjsEngine *e, const char *script, char **err);

/* Compile [script] to serialized bytecode with per-function source text stripped
 * (Function.prototype.toString yields a placeholder; line-number debug info is kept so stack
 * traces stay useful). Returns a malloc'd buffer with *out_len set (free via qjs_free), or NULL
 * with *err set to a malloc'd message. */
uint8_t *qjs_compile(const char *script, const char *filename, size_t *out_len, char **err);

/* qjs_eval_engine from qjs_compile output — the version can't be baked into the precompiled
 * blob, so it's installed at qjs_create, before the bytecode program runs. */
int qjs_eval_engine_bc(QjsEngine *e, const uint8_t *bytecode, size_t len, char **err);

/* Evaluate [script]; returns a malloc'd UTF-8 string (the result's string form, or the error
 * message) or NULL. *isError is set to 1 when the returned string is an error message. */
char *qjs_eval_sync(QjsEngine *e, const char *script, const char *filename, int *isError);

/* Host -> JS. open() returns 0 (accepted) or -1 (refused — no JS handler for the command);
 * write() returns the accepted byte count (see header comment); reason semantics as in
 * QJS_OP_CLOSE_WRITE; drain() resumes the JS side's parked partial send. */
int qjs_open(QjsEngine *e, uint32_t id, const char *command);
int qjs_write(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len);
void qjs_close_write(QjsEngine *e, uint32_t id, const char *reason);
void qjs_close_read(QjsEngine *e, uint32_t id);
void qjs_drain(QjsEngine *e, uint32_t id);

void qjs_close(QjsEngine *e);
void qjs_free(void *p);

/* Bind a host pointer + `:mse` vtable so JS `__mse*` trampolines hit mse_host_*.
 * Either may be NULL (detach). */
void qjs_set_mse_host(QjsEngine *e, void *host, const void *vt);

#endif
