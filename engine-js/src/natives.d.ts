// The typed contract of the C FFI boundary — every native the engine registers on the global
// before the bundle evaluates (quickjs_native.c / quickjs_jni.c / quickjs_pipe.c / quickjs_json.c),
// and the transport globals the bundle must define for C to capture. transport.js (plain JS)
// implements against this; TS code that feature-detects a native (`typeof __x === "function"`,
// per-app-version — see `versions`) gets it typed. Ambient, like types.d.ts.
//
// NONE of these names may be minified/renamed — they ARE the C boundary (esbuild preserves them
// automatically: calls are undeclared identifiers, definitions are globalThis property writes).

/** An opaque C handle (a boxed pointer); only ever passed back to the natives that produced it. */
declare interface NativeHandle {
  readonly __native: unique symbol;
}

// ---- host transport (quickjs_native.c / quickjs_jni.c): JS -> Kotlin ----

/** Open pipe [id] toward the host. 0 accepted; -1 refused (nothing serves [command]); -2 called
 *  under a critical region (a transport bug). A refusal leaves no state on either side. */
declare function __hostOpen(id: number, command: string): number;
/** Deliver bytes[off, off+len) to the host. Returns the accepted count (< len = backpressure,
 *  park until DRAIN; the buffer must stay unmodified), or -1 = host read end closed (EPIPE). */
declare function __hostData(id: number, bytes: Uint8Array, off: number, len: number): number;
/** Half-close our write side: END (reason null) or RESET (reason string). */
declare function __hostClose(id: number, reason: string | null): void;
/** Abandon our read side: the host's next send fails EPIPE. */
declare function __hostCloseRead(id: number): void;

// ---- C pipe buffer (quickjs_pipe.c): the host->JS direction lives entirely in C ----

/** The C-owned inbound buffer for pipe [id]; reads below return the value when already buffered,
 *  else a Promise C settles on delivery. */
declare function __pbNew(id: number): NativeHandle;
declare function __pbRecv(
  pb: NativeHandle,
  buf: Uint8Array,
  off: number,
  len: number,
): number | Promise<number>;
declare function __pbInt(pb: NativeHandle): number | Promise<number>;
declare function __pbLong(pb: NativeHandle): number | Promise<number>;
declare function __pbLine(
  pb: NativeHandle,
  strict: boolean,
): string | null | Promise<string | null>;
/** Streaming JSON parse off the buffer; [rootType] 1 = object, 2 = array. */
declare function __pbJson(
  pb: NativeHandle,
  rootType: 1 | 2,
): Record<string, unknown> | unknown[] | Promise<Record<string, unknown> | unknown[]>;
declare function __pbUtf8(pb: NativeHandle): string | Promise<string>;
declare function __pbBytes(pb: NativeHandle): Uint8Array | Promise<Uint8Array>;
/** Release the buffer and fail future reads — the read-side half of pipe teardown. */
declare function __pbCloseRead(pb: NativeHandle): void;

// ---- native UTF-8 (quickjs_pipe.c) — astral-correct, no per-codepoint JS loops ----

declare function __utf8Decode(bytes: Uint8Array, off: number, len: number): string;
/** Encode str[from, to) into buf at off (caller guarantees fit); returns bytes written. */
declare function __utf8Into(
  str: string,
  from: number,
  to: number,
  buf: Uint8Array,
  off: number,
): number;
declare function __utf8Encode(str: string, from: number, to: number): Uint8Array;
/** The exact byte count __utf8Encode(str, from, to) would produce, without encoding. */
declare function __utf8Length(str: string, from: number, to: number): number;
/** Encode src[srcOff, srcOff+srcLen) as base64url (no pad) into dst[dstOff..]. Returns dst bytes written. */
declare function __b64urlInto(
  src: Uint8Array,
  srcOff: number,
  srcLen: number,
  dst: Uint8Array,
  dstOff: number,
): number;

// ---- console/diagnostics bridge (quickjs_native.c / quickjs_jni.c) ----

/** Log [msg] to the host (0 debug, 1 info, 2 warn, 3 error) — console.ts is the only caller. */
declare function __consoleLog(level: number, msg: string): void;

/** Wrap [impl] as a C function named [name] so Function.prototype.toString is V8 native. */
declare function __nativeFn(name: string, impl?: Function, length?: number): Function;

/** Host IANA time zone (TZ / Android persist.sys.timezone / /etc/localtime). */
declare function __timeZone(): string;

/** Native MSE session attached to this engine (sync C, not pipes). */
declare function __mseReady(): boolean;
declare function __mseCurrentTime(): number;
declare function __mseSetCurrentTime(seconds: number): void;
/** speed = playbackRate (currentTime). tempo = PCM stretch. Pair them. */
declare function __mseSetSpeed(speed: number, tempo: number): void;
declare function __msePlaybackRate(): number;
declare function __mseSetPitch(pitch: number): void;
declare function __mseDuration(): number;
declare function __mseSetDuration(seconds: number): void;
/** 1 when leftover is drained and (endOfStream or last ≈ duration). Chrome IsEnded. */
declare function __mseEnded(): number;
declare function __mseEndOfStream(): void;
declare function __msePaused(): boolean;
declare function __mseReadyState(): number;
declare function __msePlay(): void;
declare function __msePause(): void;
/** 1 = room (after coded-frame eviction). 0 = still full; throw QuotaExceededError. */
declare function __msePrepareAppend(audio: number, nbytes: number): number;
declare function __mseAppend(
  audio: number,
  bytes: Uint8Array,
  tsOff: number,
  winStart: number,
  winEnd: number,
): void;
declare function __mseRemove(audio: number, start: number, end: number): void;
declare function __mseAbort(audio: number): void;
declare function __mseChangeType(audio: number): void;
declare function __mseRestVideo(on: number): void;
declare function __mseBuffered(audio: number): number[];
declare function __mseReset(): void;
/** 0 if this host cannot decode AV1. */
declare function __mseAv1Ok(): number;
/** 0 if this host cannot decode VP9. */
declare function __mseVp9Ok(): number;
/** Max AVC level_idc (0x1f = 3.1). 255 = no cap. */
declare function __mseAvcMaxLevel(): number;
/** The host's decoder table for one MediaCapabilities part; width/height/framerate 0 = any size.
 *  Bits 1 supported, 2 smooth, 4 powerEfficient; -1 no table (Android before its MediaCodec rows,
 *  no MSE host), -2 still probing — ask again. Newer hosts only: guard with typeof. */
declare function __mseDecodingInfo(
  contentType: string,
  width: number,
  height: number,
  framerate: number,
  bitrate: number,
): number;
/** [totalVideoFrames, droppedVideoFrames] since the last load; [0, 0] without a host. Newer hosts only. */
declare function __mseVideoQuality(): number[];

declare function atob(data: string): string;
declare function btoa(data: string): string;

/** Install C Date.now / Math.random wrappers (rec off). BotGuard must bind these.
 * Returns `{platform, arch}` (undefined on older hosts); the overlay adds `{replayChallenge, …}`. */
declare function __bgEntropyStart(): unknown;
/** Record d/p/r (w = new Date() on newer hosts) plus t/N/S JSON (toString, Error.name, Error.stack). */
declare function __bgEntropyBegin(): void;
declare function __bgEntropyEnd(): void;
declare function __bgEntropyStop(): void;
/** JSON string bytes of the tape (quoted, escaped, lines joined by \\n), or null. */
declare function __bgEntropyDump(): Uint8Array | null;

// ---- native streaming JSON encoder (quickjs_json.c) ----

/** Start encoding [value] (JSON.stringify semantics — a cycle throws). */
declare function __jsonEncNew(value: unknown): NativeHandle;
/** Fill buf from off with the next chunk; returns bytes written. Runs user JS (getters). */
declare function __jsonEncFill(enc: NativeHandle, buf: Uint8Array, off: number): number;
declare function __jsonEncDone(enc: NativeHandle): boolean;

// ---- native one-shot / streaming JSON parser (quickjs_json.c) ----

/** One-shot JS_ParseJSON over raw UTF-8 bytes. */
declare function __parseJson(bytes: Uint8Array): unknown;
/** One-shot JSON.stringify to UTF-8 bytes (throws on undefined/no representation). */
declare function __stringifyJson(value: unknown): Uint8Array;
/** Resumable streaming parser: feed chunks with __jsonPush, finish with __jsonEnd.
 *  [rootType] 1 = require object, 2 = require array. */
declare function __jsonNew(rootType?: number): NativeHandle;
declare function __jsonPush(p: NativeHandle, bytes: Uint8Array, off: number, len: number): number;
declare function __jsonEnd(p: NativeHandle): unknown;

// ---- transport globals the bundle DEFINES (transport.js) and C captures right after eval ----

/** The host opened pipe [id]: 0 accepted, -1 refused (no ymix.onAccept handler). */
declare var __jsOpen: (id: number, command: string) => number;
/** The host closed its write end (END or RESET — the C buffer already knows which). */
declare var __jsClose: (id: number) => void;
/** The host abandoned its read end — our sends get EPIPE. */
declare var __jsCloseRead: (id: number) => void;
/** Host flow-control: its buffer drained; continue the parked partial send. */
declare var __jsDrain: (id: number) => void;
