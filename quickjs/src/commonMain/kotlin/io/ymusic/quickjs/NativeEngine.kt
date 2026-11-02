package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher

/**
 * A chunk of JS-owned bytes crossing JS -> host, valid ONLY during the [EngineCallbacks.onWrite]
 * call that delivers it (jvm: a direct ByteBuffer over the typed array's memory; native: a raw
 * pointer). The mux must consume it synchronously — copy into the parked reader's buffer and/or
 * its inbound okio buffer — never retain it.
 */
internal expect class NativeChunk {
    val size: Int

    /** Copy `[srcOffset, srcOffset+count)` of the chunk into [dst] at [dstOffset]. */
    fun copyInto(dst: ByteArray, dstOffset: Int, srcOffset: Int, count: Int)
}

/**
 * Inbound callbacks: invoked (on the engine thread) when JS reaches the host. The multiplexer
 * ([QuickJsRuntime]) implements these; the transport never routes, it only shuttles operations.
 */
internal interface EngineCallbacks {
    /** JS opened a pipe to us. Return 0 to accept, or -1 to refuse (no handler for [command]) —
     *  a refusal fails the JS side's open() call itself, C-error-code style. */
    fun onOpen(id: Int, command: String): Int

    /** JS wrote bytes to us. Consume [chunk] synchronously (parked reader first, then the inbound
     *  buffer up to its cap) and return the accepted byte count — less than `chunk.size` means the
     *  JS writer parks until we [NativeEngine.drain] — or -1 if our read end is closed. */
    fun onWrite(id: Int, chunk: NativeChunk): Int

    /** JS closed its write end: [reason] `null` = END (receive returns -1 once drained); non-null =
     *  RESET (pending data is discarded and receive throws [PipeResetException]). */
    fun onCloseWrite(id: Int, reason: String?)

    /** JS abandoned its read end: our next send throws [BrokenPipeException]. */
    fun onCloseRead(id: Int)

    /** Flow control: JS drained its buffer to the low-water mark — wake our parked writer. */
    fun onDrain(id: Int)

    /** Console/diagnostics line from the engine (0 debug, 1 info, 2 warn, 3 error, 4 unhandled
     *  promise rejection). Must be cheap and must not throw. */
    fun onLog(level: Int, message: String)
}

/**
 * The low-level engine boundary over QuickJS. Backed by JNI on jvm/android and by cinterop on
 * Kotlin/Native; both speak the same op-per-direction contract, so [QuickJsRuntime]'s mux is fully
 * shared. Commands and RESET reasons cross as native strings; payloads cross zero-copy (a one-shot
 * view of the caller's bytes, valid only during the call).
 */
internal expect class NativeEngine {
    /** Evaluate [script]; returns the completion value's string form (or null). Throws on JS error. */
    fun evalSync(script: String, filename: String): String?

    /** Evaluate the engine script (the :engine-js bundle) and capture the transport globals
     *  (`__jsOpen`/`__jsClose`/`__jsCloseRead`/`__jsDrain`) right after — exactly once, before any
     *  [open]. Throws [QuickJsException] on a JS error (the engine stays valid; [destroy] it). */
    fun evalEngine(script: String)

    /** [evalEngine] from [compile] output — the version can't be baked into the precompiled blob,
     *  so it's installed at [create], before the bytecode program runs. */
    fun evalEngineBytecode(bytecode: ByteArray)

    /** Host -> JS. Returns 0 when JS accepted the pipe, or -1 when it refused (no handler). */
    fun open(id: Int, command: String): Int

    /** Deliver `buffer[offset, offset+len)` to the JS reader; returns the accepted byte count
     *  (< len = backpressure, wait for [EngineCallbacks.onDrain]), or -1 if it closed its read end. */
    fun write(id: Int, buffer: ByteArray, offset: Int, len: Int): Int

    /** Write-side half-close: [reason] `null` = END, non-null = RESET. */
    fun closeWrite(id: Int, reason: String?)

    /** Abandon our read end: the JS writer's next send gets EPIPE. */
    fun closeRead(id: Int)

    /** Flow control: we drained our buffer to the low-water mark — wake JS's parked writer. */
    fun drain(id: Int)

    /** Bind a native MSE host pointer + vtable so JS `__mse*` getters are synchronous C. */
    fun setMseHost(mseHandle: Long, vtable: Long)

    /** Tear down the engine and release native resources. */
    fun destroy()

    companion object {
        /** A bare engine — natives + the frozen `versions` global (`{app: appVersion}` plus the flat
         *  JSON object [versionsJson]), no script. [callbacks] route from the first instruction of
         *  the later [evalEngine], so host-served commands registered before it are visible to the
         *  bundle's top-level code. */
        fun create(appVersion: Int, versionsJson: String?, callbacks: EngineCallbacks): NativeEngine

        /** Compile [script] to serialized bytecode with per-function source text stripped
         *  (`Function.prototype.toString` yields a placeholder; line-number debug info is kept). */
        fun compile(script: String, filename: String): ByteArray
    }
}

internal expect fun newEngineDispatcher(): CloseableCoroutineDispatcher
