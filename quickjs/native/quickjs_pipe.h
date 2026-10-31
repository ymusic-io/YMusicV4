/*
 * C-owned inbound pipe buffer ("PipeBuf"), shared by the JNI and cinterop shims.
 *
 * The host->JS direction of each pipe lives entirely in C: an 8 KB pooled segment chain (bounded
 * at 64 KB, low-water 32 KB), the pending read job, and the scanners (line splitter, streaming
 * JSON via quickjs_json.h, UTF-8 accumulate/decode). Host writes never enter JS
 * (qjs_pipe_write -> memcpy into segments -> advance the pending job); JS reads are thin natives
 * that return the result synchronously when it is already buffered, else park the job and return
 * a Promise that C settles on a later write / EOF / RESET.
 *
 * JS natives registered on globalThis:
 *   __pbNew(id) -> PipeBuf     handle held by the pipe closure; registers id -> buf for writes
 *   __pbRecv(pb, dst, off, len) -> count | -1 EOF | Promise
 *   __pbInt(pb) / __pbLong(pb) -> number | Promise            big-endian, fixed width
 *   __pbLine(pb, strict)       -> string | null | Promise     '\n'-terminated, '\r' stripped
 *   __pbJson(pb, expect)       -> value | Promise             stops at the value's end (tail stays)
 *   __pbUtf8(pb)               -> string | Promise            whole remaining body, decoded once
 *   __pbBytes(pb)              -> Uint8Array | Promise        whole remaining body, raw bytes
 *   __pbCloseRead(pb)          abandon the read side (free segments, unregister; next write -1)
 *   __utf8Decode(u8, off, len) -> string
 *   __utf8Into(str, from, to, dst, dstOff) -> bytes written (sink encode into a staged segment)
 *   __utf8Encode(str, from, to) -> Uint8Array   exact-size, zero-copy wrap of a C buffer
 *   __utf8Length(str, from, to) -> number       the byte count __utf8Encode would produce
 *
 * CRITICAL-REGION RULE (JVM): qjs_pipe_write runs while the host ByteArray is held with
 * GetPrimitiveArrayCritical. This path never calls back into the host — the accepted-count return
 * IS the flow control — and settling a parked promise only schedules reaction jobs. The drain
 * hook fires exclusively from JS-initiated read paths (never under a critical region).
 *
 * Lifetime: the registry holds a reference to each registered PipeBuf object, so a registered id
 * can never dangle; unregistration happens at close_write / closeRead (after which no host bytes
 * can arrive). The class finalizer is the GC backstop returning segments to the pool.
 */
#ifndef QUICKJS_PIPE_H
#define QUICKJS_PIPE_H

#include <stddef.h>
#include <stdint.h>
#include "quickjs.h"

/* Pool block size; PipeBuf segment payload is this minus a small header. */
#define QJS_SEG_SIZE 8192

/* Define the frozen `versions` global ({app: app_version}) the bundle branches on for backward
 * compatibility — the transport, host protocols, and app ship together in one app build, so the
 * app version alone identifies the whole host surface. [extra_json] (NULL or a flat JSON object)
 * adds the host's own entries (device facts) next to `app`; `app` always wins. Installed before
 * the engine script/bytecode runs; the global is non-writable, so no (remotely updated) bundle
 * can reassign it. */
void qjs_install_versions(JSContext *ctx, int app_version, const char *extra_json);

typedef struct QjsPipeCtx QjsPipeCtx;

/* Flow control toward the host: our buffer drained below low-water while its writer was parked.
 * Return 0, or <0 if the host callback failed (surfaces as a JS exception on the reading side). */
typedef int (*QjsPipeDrainFn)(void *user, uint32_t id);

/* Create the per-engine pipe context (segment pool + id registry; stored via JS_SetRuntimeOpaque)
 * and register the JS natives. Teardown order:
 *   qjs_pipe_ctx_release(pc, ctx);  // drop registry refs — BEFORE JS_FreeContext
 *   JS_FreeContext / JS_FreeRuntime;  // finalizers return live segments to the pool
 *   qjs_pipe_ctx_free(pc); */
QjsPipeCtx *qjs_register_pipe(JSContext *ctx, QjsPipeDrainFn drain, void *user);
void qjs_pipe_ctx_release(QjsPipeCtx *pc, JSContext *ctx);
void qjs_pipe_ctx_free(QjsPipeCtx *pc);

/* Host wrote bytes: copy into the pipe's segments (up to the cap) and advance the pending read
 * job. Returns the accepted count (< len => the host writer parks until DRAIN), or -1 when the
 * id is unknown / read-closed / the pipe failed. Safe under a JNI critical region. */
int qjs_pipe_write(QjsPipeCtx *pc, uint32_t id, const uint8_t *data, size_t len);

/* Host write-side half-close. reason NULL = END (EOF once drained), else RESET (discard buffered
 * data; the parked/next read throws the reason). Unregisters the id. */
void qjs_pipe_close_write(QjsPipeCtx *pc, uint32_t id, const char *reason, size_t reason_len);

#endif
