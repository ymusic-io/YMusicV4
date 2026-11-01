package io.ymusic.quickjs

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okio.Buffer
import okio.use
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Per-direction pipe buffer bound (bytes) — like a Linux pipe's fixed capacity. A writer whose
 *  write is only partially accepted parks and resumes on the peer's DRAIN notify, which fires once
 *  the buffer drains to [PIPE_LOW]. Must match quickjs_pipe.c (PB_CAP/PB_LOW). */
private const val PIPE_CAP = 64 * 1024
private const val PIPE_LOW = PIPE_CAP / 2

private const val EOF = -1L

/** A live [QuickJsRuntime.onAccept] registration; [remove] unregisters it. */
fun interface AcceptRegistration {
    fun remove()
}

/**
 * A QuickJS engine reached over an in-process pipe protocol, on every KMP target. JS execution is
 * confined to a single thread; the FFI boundary ([NativeEngine]) shuttles operations. On top of it
 * this multiplexes logical [Pipe]s: either side can [openPipe] a command to the other, or [onAccept]
 * commands the other opens.
 *
 * Each pipe direction is a **bounded, flow-controlled byte stream** whose buffer lives on the
 * *reader's* side: bytes JS writes to us land here, in an okio [Buffer] (segment pool, no per-chunk
 * allocation); bytes we write to JS land in its queue of pooled C segments. The JS→Kotlin direction
 * is **rendezvous-first**: when a reader is parked, `deliver()` copies the writer's bytes straight
 * into the reader's buffer (one copy) and only the remainder is buffered. The host→JS direction
 * always fills the C `PipeBuf` segments and satisfies the parked JS read from them (rendezvous was
 * deliberately dropped there). A partially accepted write parks the writer until the reader drains
 * to the low-water mark (DRAIN). All state lives on the engine thread — no locking.
 */
@OptIn(ExperimentalAtomicApi::class)
class QuickJsRuntime private constructor(
    private val engine: NativeEngine,
    private val engineDispatcher: CoroutineDispatcher,
    private val logHandler: ((level: Int, message: String) -> Unit)?,
) {

    private val scope = CoroutineScope(engineDispatcher + SupervisorJob())
    private val closed = AtomicBoolean(false)
    private val pipes = HashMap<Int, PipeImpl>()
    private val idleWaiters = ArrayList<CancellableContinuation<Unit>>() // engine-thread confined

    // onAccept is called from any thread (the app registers handlers off the engine thread), while
    // onOpen reads on the engine thread — the one piece of state not engine-confined. Guard both
    // ends with a lock.
    private val accepts = HashMap<String, suspend (Pipe) -> Unit>()
    private val prefixAccepts = HashMap<String, suspend (Pipe) -> Unit>()
    private val acceptsLock = SynchronizedObject()
    private var nextId = 1 // Kotlin opens odd ids; JS opens even.

    /**
     * Evaluate [script] synchronously (to load engine code / register JS `ymix.onAccept` handlers).
     * Returns the completion value's string form, or null; throws [QuickJsException] on a JS error.
     */
    suspend fun load(script: String, filename: String = "<load>"): String? {
        if (closed.load()) throw QuickJsException("runtime closed")
        return withContext(engineDispatcher) {
            // Re-check ON the engine thread: a close() racing the hop CASes `closed` then enqueues
            // its teardown (engine.destroy()) — if that landed first, a stale pre-hop read must not
            // call into the freed engine. The single-thread dispatcher serializes the two blocks.
            if (closed.load()) throw QuickJsException("runtime closed")
            engine.evalSync(script, filename)
        }
    }

    /**
     * Suspend until no pipe is open in either direction — everything in flight is a pipe
     * (host-opened commands, JS-opened commands, timers), so empty = quiesced. Returns immediately
     * when already idle; multiple waiters are allowed. Completes (rather than throws) when the
     * runtime is closed mid-wait — no pipes is factually true then. Callers bound the wait
     * themselves (`withTimeoutOrNull(...) { awaitIdle() }`).
     */
    suspend fun awaitIdle() {
        if (closed.load()) return
        withContext(engineDispatcher) {
            if (closed.load() || pipes.isEmpty()) return@withContext
            suspendCancellableCoroutine { cont ->
                // No off-thread removal on cancellation: a cancelled waiter stays in the list and
                // the eventual resume is ignored (the list is engine-confined; mutating it from
                // the canceller's thread would race).
                idleWaiters.add(cont)
            }
        }
    }

    /** On the engine thread, after any pipe leaves the map. */
    private fun notifyIfIdle() {
        if (pipes.isNotEmpty() || idleWaiters.isEmpty()) return
        val waiters = idleWaiters.toList()
        idleWaiters.clear()
        for (w in waiters) w.resume(Unit)
    }

    /** Bind native MSE to this engine. Must run on the engine thread, before bundle eval. */
    fun attachMseHost(mseHandle: Long, vtable: Long) {
        engine.setMseHost(mseHandle, vtable)
    }

    /**
     * Any thread. Opens `"event"` to JS with [eventName] as the UTF-8 body (EOF-delimited).
     * JS `ymix.onEvent` runs on the engine thread. No-ops if closed or JS has no handler.
     */
    fun fireEvent(eventName: String) {
        if (closed.load()) return
        scope.launch {
            if (closed.load()) return@launch
            try {
                val pipe = openPipe("event")
                val body = Buffer().writeUtf8(eventName)
                pipe.sink.send(body, body.size)
                pipe.sink.close()
                pipe.source.close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: QuickJsException) {
                // JS has no onAccept("event") (old bundle)
            } catch (_: PipeResetException) {
                // JS RESET
            } catch (_: BrokenPipeException) {
                // JS closed early
            }
        }
    }

    /** Handle pipes JS opens with the exact [command] name, replacing any previous handler. Any
     *  arguments are read inside [handler] from the pipe's bytes — the command is a routing name
     *  only, never carries args. Returns a registration whose [AcceptRegistration.remove]
     *  unregisters it — a no-op once a newer registration has replaced this one.
     *
     *  [handler] runs ON the engine thread — treat it like a UI main thread: any I/O or
     *  potentially-blocking platform call hops off with `withContext(Dispatchers.IO)`, because one
     *  blocking call here wedges all JS, every pipe and every timer silently. */
    fun onAccept(command: String, handler: suspend (Pipe) -> Unit): AcceptRegistration {
        synchronized(acceptsLock) { accepts[command] = handler }
        return AcceptRegistration {
            synchronized(acceptsLock) {
                if (accepts[command] === handler) accepts.remove(command)
            }
        }
    }

    /** Handle pipes JS opens whose command **starts with** [prefix]. Exact [onAccept] names win.
     *  Longest prefix wins when several match. [Pipe.command] is the full name JS opened. */
    fun onAcceptPrefix(prefix: String, handler: suspend (Pipe) -> Unit): AcceptRegistration {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
        synchronized(acceptsLock) { prefixAccepts[prefix] = handler }
        return AcceptRegistration {
            synchronized(acceptsLock) {
                if (prefixAccepts[prefix] === handler) prefixAccepts.remove(prefix)
            }
        }
    }

    /** Open a pipe to JS addressed to the [command] name (send any arguments as bytes afterwards).
     *  Fails AT THE OPEN — like ECONNREFUSED — when no JS handler serves [command]. */
    suspend fun openPipe(command: String): Pipe {
        if (closed.load()) throw QuickJsException("runtime closed")
        var opened: PipeImpl? = null
        try {
            return withContext(engineDispatcher) {
                // Re-check on the engine thread (see [load]): without this a openPipe racing close()
                // runs engine.open() on the destroyed engine and re-populates the cleared pipes map.
                if (closed.load()) throw QuickJsException("runtime closed")
                val id = nextId
                nextId += 2
                val impl = PipeImpl(id, command)
                // Registered before open() so anything crossing during the call finds the pipe;
                // dropped again on refusal/throw so a failed open never dangles in the registry.
                pipes[id] = impl
                val rc = try {
                    engine.open(id, command)
                } catch (e: Throwable) {
                    pipes.remove(id)
                    notifyIfIdle()
                    throw e
                }
                if (rc < 0) {
                    pipes.remove(id)
                    notifyIfIdle()
                    throw QuickJsException("open(\"$command\") refused: no JS handler")
                }
                opened = impl
                impl.pipe
            }
        } catch (t: Throwable) {
            // Prompt cancellation thrown at the withContext boundary AFTER the open succeeded: the pipe
            // exists on both sides but the caller never receives its handle, so nobody can close it —
            // tear it down here or it leaks (a live JS handler + a routing entry). closeWrite/closeRead
            // self-hop NonCancellable (surviving the cancellation) and reap it from the pipes map.
            opened?.let { impl ->
                runCatching { impl.closeWrite(null) }.exceptionOrNull()?.let(t::addSuppressed)
                runCatching { impl.closeRead() }.exceptionOrNull()?.let(t::addSuppressed)
            }
            throw t
        }
    }

    /**
     * BOOT PATH, engine-thread-only: open [command] with no arguments (END immediately) and return
     * the peer's whole response, which must have completed **synchronously** — the native layer
     * pumps microtasks inside every JS entry, so a handler that only computes and writes (no
     * timers, no host round-trips) has written and ENDed by the time `open` returns. A handler
     * that parked instead throws [QuickJsException]: coroutines queued on the engine dispatcher
     * cannot run while the caller occupies its thread, so waiting would deadlock, and a boot
     * handler that needs them is a bundle defect. Throws [PipeResetException] on a RESET (the
     * peer's own failure classification).
     */
    fun openReadNow(command: String): String {
        if (closed.load()) throw QuickJsException("runtime closed")
        val id = nextId
        nextId += 2
        val impl = PipeImpl(id, command)
        pipes[id] = impl
        val rc = try {
            engine.open(id, command)
        } catch (e: Throwable) {
            pipes.remove(id)
            notifyIfIdle()
            throw e
        }
        if (rc < 0) {
            pipes.remove(id)
            notifyIfIdle()
            throw QuickJsException("open(\"$command\") refused: no JS handler")
        }
        val body = try {
            impl.closeWriteNow(null)
            impl.takeCompletedNow()
                ?: throw QuickJsException("\"$command\" did not complete synchronously")
        } catch (t: Throwable) {
            runCatching { impl.closeReadDirect() }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        }
        impl.closeReadDirect()
        return body
    }

    /** JS opened a pipe to us — route to the registered handler (on the engine thread).
     *  Returns 0 (accepted) or -1 (refused: no handler), which fails JS's open() call itself. */
    private fun handlerFor(command: String): (suspend (Pipe) -> Unit)? = synchronized(acceptsLock) {
        accepts[command]?.let { return@synchronized it }
        var bestLen = -1
        var best: (suspend (Pipe) -> Unit)? = null
        for ((prefix, handler) in prefixAccepts) {
            if (command.startsWith(prefix) && prefix.length > bestLen) {
                bestLen = prefix.length
                best = handler
            }
        }
        best
    }

    internal fun onOpen(id: Int, command: String): Int {
        val handler = handlerFor(command) ?: return -1
        // Refuse a colliding id rather than overwrite a live pipe: a buggy/hostile bundle reusing an
        // id (or opening an odd Kotlin-range id) would otherwise orphan the old PipeImpl, whose
        // parked continuation close()'s failPending — walking only the pipes map — could never fail.
        if (pipes.containsKey(id)) return -1
        val impl = PipeImpl(id, command)
        pipes[id] = impl
        scope.launch {
            try {
                handler(impl.pipe)
            } catch (e: CancellationException) {
                // Real cancellation propagates; a CancellationException on a still-active job (e.g.
                // withTimeout inside the handler) is a failure and aborts the pipe like any other.
                if (!isActive) throw e
                impl.abort(e)
            } catch (e: Throwable) {
                impl.abort(e)
            }
        }
        return 0
    }

    internal fun onWrite(id: Int, chunk: NativeChunk): Int = pipes[id]?.deliver(chunk) ?: -1

    internal fun onCloseWrite(id: Int, reason: String?) { pipes[id]?.onPeerCloseWrite(reason) }

    internal fun onCloseRead(id: Int) { pipes[id]?.onPeerCloseRead() }

    internal fun onDrain(id: Int) { pipes[id]?.onDrain() }

    /** Console/diagnostics from the engine (levels per [LOG_ERROR]…). A throwing handler must not
     *  unwind into C — swallowed here, deliberately (there is no channel left to report on). */
    internal fun onLog(level: Int, message: String) {
        val handler = logHandler ?: return
        runCatching { handler(level, message) }
    }

    private inner class PipeImpl(val id: Int, val command: String) : PipeSource, PipeSink {
        /** The public pair of ends handed to callers — both views over this one state machine. */
        val pipe = Pipe(this, this, command)

        // ---- inbound: JS writes, we buffer (okio segment pool) & read ----
        private val inbound = Buffer()
        private val cursor = Buffer.UnsafeCursor()
        private var writeEnded = false      // peer closed its write end -> EOF once drained
        private var resetReason: String? = null // peer RESET -> receive throws
        private var readClosed = false      // we closed our read end
        private var peerBlocked = false     // last deliver() accepted less than offered; we owe a DRAIN
        private var receiving = false
        private var reader: ParkedReader? = null

        // ---- outbound: we write, JS buffers ----
        private var sending = false
        private var drainCredit = false     // DRAIN arrived before we parked (lost-wakeup guard)
        private var peerReadClosed = false  // peer closed its read end -> our send throws EPIPE
        private var weEnded = false
        private var writer: CancellableContinuation<Unit>? = null

        // ---- inbound plumbing (all on the engine thread) ----

        /** JS wrote [chunk] (valid only during this call). Rendezvous-first: copy straight into a
         *  parked reader's sink (up to its byteCount — sized at delivery time, so a full chunk can
         *  rendezvous), then the okio buffer up to [PIPE_CAP]. Returns accepted, or -1 (EPIPE). */
        fun deliver(chunk: NativeChunk): Int {
            // Reject after our read close (EPIPE) or after the peer's own END: no bytes may land past a
            // clean END. Both are serialized on the engine thread in the normal flow; the writeEnded
            // guard also blocks a hostile __hostData whose off/len valueOf reentered __hostClose(END).
            if (readClosed || writeEnded) return -1
            var pos = 0
            val r = reader
            if (r != null) {
                reader = null
                val n = minOf(chunk.size.toLong(), r.byteCount).toInt()
                copyChunk(r.sink, chunk, 0, n)
                pos = n
                // If cancellation (from any thread) wins against this resume, the bytes are
                // stranded in the abandoned sink while the peer counts them delivered — safe only
                // because the cancelled receive() closes the read side on its way out, so the
                // desynced stream can never be read past.
                r.cont.resume(n.toLong())
            }
            val room = (PIPE_CAP.toLong() - inbound.size).toInt()
            val toBuffer = minOf(chunk.size - pos, room)
            if (toBuffer > 0) {
                copyChunk(inbound, chunk, pos, toBuffer)
                pos += toBuffer
            }
            if (pos < chunk.size) peerBlocked = true
            return pos
        }

        /** Copy `[srcOffset, srcOffset+count)` of [chunk] straight into [dst]'s okio segment arrays. */
        private fun copyChunk(dst: Buffer, chunk: NativeChunk, srcOffset: Int, count: Int) {
            val c = dst.readAndWriteUnsafe(cursor)
            try {
                val oldSize = dst.size
                c.resizeBuffer(oldSize + count)
                var copied = 0
                while (copied < count) {
                    val n = minOf(count - copied, c.end - c.start)
                    chunk.copyInto(c.data!!, c.start, srcOffset + copied, n)
                    copied += n
                    if (copied < count) {
                        check(c.next() != -1)
                    }
                }
            } finally {
                c.close()
            }
        }

        fun onPeerCloseWrite(reason: String?) {
            writeEnded = true
            val r = reader
            if (reason != null) {
                resetReason = reason
                inbound.clear() // RESET: the stream is suspect — discard, fail fast
                if (r != null) { reader = null; r.cont.resumeWithException(PipeResetException(reason)) }
            } else if (r != null) { // a parked reader implies an empty buffer -> EOF now
                reader = null
                r.cont.resume(EOF)
            }
            maybeReap()
        }

        fun onPeerCloseRead() {
            peerReadClosed = true
            val w = writer
            if (w != null) { writer = null; w.resumeWithException(BrokenPipeException()) }
        }

        fun onDrain() {
            val w = writer
            if (w != null) { writer = null; w.resume(Unit) } else drainCredit = true
        }

        /** Fire-and-forget teardown of a failed/aborted served pipe: RESET the peer with the cause's
         *  message. This is the transport's DUMB backstop — the richer `{code, …}` error contract
         *  (classify + birth-site log) lives one layer up in `dynamicengine.commandHandler`. Each
         *  close is wrapped so a teardown failure can't escape the launched coroutine into the
         *  SupervisorJob's uncaught handler. */
        suspend fun abort(cause: Throwable) {
            runCatching { closeRead() }
            runCatching { closeWrite(cause.message ?: "handler failed") }
        }

        override suspend fun receive(sink: Buffer, byteCount: Long): Long {
            // Checked before the dispatcher hop: after runtime close() the engine thread is gone
            // (dispatching there is at best a fallback thread, at worst an error).
            if (closed.load()) throw QuickJsException("runtime closed")
            var entered = false // passed validation and began the read — pre-read misuse must not close
            var cleaned = false // the in-body catch already closed the read side (skip the outer hop)
            try {
                return withContext(engineDispatcher) {
                    // A REAPED pipe (left the pipes map on maybeReap) is not poisoned by failPending,
                    // so its own-side flags can't guard a race with teardown — the engine-thread
                    // `closed` recheck does (a receive draining buffered data still calls engine.drain).
                    if (closed.load()) throw QuickJsException("runtime closed")
                    require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
                    if (byteCount == 0L) return@withContext 0L
                    check(!readClosed) { "receive() after the read side was closed (closeRead() or an aborted receive)" }
                    check(!receiving) { "concurrent receive() on the same pipe" }
                    receiving = true
                    entered = true
                    try {
                        resetReason?.let { throw PipeResetException(it) }
                        if (inbound.size > 0L) {
                            val n = inbound.read(sink, byteCount) // whole-segment moves, no copy
                            if (peerBlocked && inbound.size <= PIPE_LOW) {
                                peerBlocked = false
                                engine.drain(id)
                            }
                            return@withContext n
                        }
                        if (writeEnded) return@withContext -1L
                        // Park with the sink itself; deliver() copies straight into it (sized at
                        // delivery time) and resumes us with the count — or EOF / a reset / a failure.
                        // No invokeOnCancellation: [reader] is engine-confined, and the catch below
                        // clears it on the engine thread; a cancellation cleanup would run on the
                        // canceller's thread and race deliver().
                        suspendCancellableCoroutine { cont ->
                            reader = ParkedReader(sink, byteCount, cont)
                        }
                    } catch (t: Throwable) {
                        // In-body abort (parked cancel, resetReason). A PipeResetException is the peer's
                        // terminal signal, not a stranded read: leave the read side open so every later
                        // receive() re-throws it (the KDoc contract). Any other abort closes the read
                        // side HERE, synchronously on the engine thread, so a racing deliver() can't
                        // slip in before it closes. Fire-and-forget: cleanup must not mask the cause.
                        if (t !is PipeResetException) {
                            runCatching { closeReadNow(t) }.exceptionOrNull()?.let(t::addSuppressed)
                            cleaned = true
                        }
                        throw t
                    } finally {
                        receiving = false
                    }
                }
            } catch (t: Throwable) {
                // Prompt cancellation thrown at the withContext boundary AFTER the body completed: the
                // bytes were already moved out of inbound / a DRAIN sent, but withContext discards the
                // return value, so honor the same abort-closes-the-read-side contract. `entered` gates
                // out pre-read misuse; `cleaned` skips the in-body path that already closed it; a
                // re-hop is needed because closeReadNow is engine-confined (NonCancellable to survive
                // the very cancellation that brought us here). On any completion the reader is already
                // null (sync paths never parked; a parked read that completed was resumed by deliver),
                // so this closeReadNow can't race a live deliver.
                if (entered && !cleaned && t !is PipeResetException) {
                    withContext(engineDispatcher + NonCancellable) {
                        runCatching { closeReadNow(t) }.exceptionOrNull()?.let(t::addSuppressed)
                    }
                }
                throw t
            }
        }

        // ---- outbound plumbing ----

        override suspend fun send(source: Buffer, byteCount: Long) {
            if (closed.load()) throw QuickJsException("runtime closed")
            withContext(engineDispatcher) {
                if (closed.load()) throw QuickJsException("runtime closed") // reaped-pipe race, see receive()
                require(byteCount in 0..source.size) {
                    "byteCount $byteCount out of range (buffer size ${source.size})"
                }
                if (byteCount == 0L) return@withContext
                check(!sending) { "concurrent send() on the same pipe" }
                if (weEnded) throw SendClosedException("send() after close()") // writing past our own half-close
                sending = true
                drainCredit = false
                try {
                    var remaining = byteCount
                    while (remaining > 0L) {
                        // Snapshot the head segment's range (cursor detached again before any suspend).
                        var data: ByteArray? = null
                        var start = 0
                        var n = 0
                        source.readUnsafe(cursor).use { c ->
                            c.seek(0L)
                            data = c.data
                            start = c.start
                            n = minOf(remaining, (c.end - c.start).toLong()).toInt()
                        }
                        sendRange(data!!, start, n) // the segment array crosses the FFI pinned, zero-copy
                        source.skip(n.toLong()) // consume only once the peer fully accepted it
                        remaining -= n
                    }
                } catch (t: Throwable) {
                    // A send that aborts (cancellation included) strands the stream at an unknowable
                    // position: the peer would wait forever for (or misparse) the rest, and a retry
                    // would resend the unskipped tail it already consumed. RESET so the pipe lands
                    // in ONE predictable state: done writing, the peer's read fails fast.
                    // Deliberate fire-and-forget: cleanup must not mask the abort cause.
                    if (!weEnded && !peerReadClosed) {
                        weEnded = true
                        runCatching {
                            engine.closeWrite(id, "send aborted mid-stream: ${t.message ?: "cancelled"}")
                        }.exceptionOrNull()?.let(t::addSuppressed)
                        maybeReap()
                    }
                    throw t
                } finally {
                    sending = false
                }
            }
        }

        private suspend fun sendRange(buffer: ByteArray, offset: Int, len: Int) {
            var pos = offset
            val end = offset + len
            while (pos < end) {
                if (peerReadClosed) throw BrokenPipeException()
                val written = engine.write(id, buffer, pos, end - pos)
                if (written < 0) { peerReadClosed = true; throw BrokenPipeException() }
                pos += written
                if (pos < end) {
                    // Partial: the peer's buffer is full. engine.write's trailing microtask
                    // pump may already have drained it and fired DRAIN (credit) — else park.
                    if (drainCredit) { drainCredit = false; continue }
                    // Same lost-wakeup window for closeRead: the pump can run the peer's
                    // closeRead() during THIS engine.write call (onPeerCloseRead finds no parked
                    // writer, only sets the flag). Parking after that would hang forever — no
                    // DRAIN or close event is ever coming from a peer that stopped reading.
                    if (peerReadClosed) throw BrokenPipeException()
                    // [writer] is engine-confined and set/cleared in this one spot: onDrain /
                    // onPeerCloseRead null it on a normal wake, this finally covers an abort. No
                    // invokeOnCancellation — it would run on the canceller's thread and race onDrain.
                    try {
                        suspendCancellableCoroutine { cont -> writer = cont }
                    } finally {
                        writer = null
                    }
                }
            }
        }

        // NonCancellable: these are cleanup calls invoked from `finally`, so they must run even
        // when the caller's job is already cancelled. The closed latch skips them after teardown.
        override suspend fun close(reason: String?) = closeWrite(reason) // PipeSink

        override suspend fun close() = closeRead() // PipeSource

        suspend fun closeWrite(reason: String?) {
            // weEnded/readClosed are engine-confined, but as monotonic flags an off-thread read
            // can only be stale-false (take the hop and recheck) — never wrongly true. Skips the
            // dispatcher hop on repeated closes (cleanup paths close idempotently by design).
            if (closed.load() || weEnded) return
            withContext(engineDispatcher + NonCancellable) {
                // A reaped pipe's weEnded may already be true (reaped via our END); but if reaped via
                // peer-END + our-still-open-write, the `closed` recheck is what stops closeWrite from
                // reaching a destroyed engine when it lands behind teardown.
                if (closed.load()) return@withContext
                closeWriteNow(reason)
            }
        }

        /** The engine-thread body of [closeWrite]; also the boot path's direct (non-suspend) close. */
        fun closeWriteNow(reason: String?) {
            if (weEnded) return
            weEnded = true
            val w = writer
            // A send parked at the cap has undelivered bytes: an END-close that must abort
            // it delivers a RESET instead — a clean END would truncate the peer's stream. A
            // RESET-close passes its own reason through. Either way the parked send is
            // force-failed (never left hung) with SendClosedException — self-caused (our
            // close), NOT the peer-caused BrokenPipeException; weEnded is set so the send's
            // catch won't re-RESET.
            val effectiveReason = reason ?: if (w != null) "closed while a send was in progress" else null
            engine.closeWrite(id, effectiveReason)
            if (w != null) { writer = null; w.resumeWithException(SendClosedException("send aborted by close()")) }
            maybeReap()
        }

        /** Boot path (engine thread): the peer's whole response — null while its stream is still
         *  open, the bytes once it ENDed; throws [PipeResetException] on a RESET. No drain
         *  bookkeeping: a boot response is far below [PIPE_CAP], so the peer never parked. */
        fun takeCompletedNow(): String? {
            resetReason?.let { throw PipeResetException(it) }
            if (!writeEnded) return null
            return inbound.readUtf8()
        }

        /** Boot path (engine thread): close the read end, reaping the pipe. */
        fun closeReadDirect() {
            closeReadNow(null)
        }

        suspend fun closeRead() {
            if (closed.load() || readClosed) return
            withContext(engineDispatcher + NonCancellable) {
                // The reaped-pipe UAF: a reaped pipe (readClosed still false) is not poisoned by
                // failPending, so only this engine-thread recheck stops closeReadNow → engine.closeRead
                // on the destroyed engine when the everyday `finally { source.close() }` races close().
                if (closed.load()) return@withContext
                closeReadNow(null)
            }
        }

        /** [cause] is the aborted receive's failure when the abort path (not an explicit
         *  [closeRead]) closes the read side. */
        private fun closeReadNow(cause: Throwable?) {
            if (readClosed) return
            readClosed = true
            inbound.clear()
            engine.closeRead(id) // tell the peer writer: EPIPE
            val r = reader // an in-flight receive is now a misuse — fail it, don't quietly EOF
            if (r != null) {
                reader = null
                r.cont.resumeWithException(cause ?: IllegalStateException("receive() after closeRead()"))
            }
            maybeReap()
        }

        // No more data either way — drop routing. (The runtime close() reaps everything regardless.)
        private fun maybeReap() {
            if (weEnded && (writeEnded || readClosed)) {
                pipes.remove(id)
                notifyIfIdle()
            }
        }

        /** Runtime teardown: poison the pipe on BOTH sides and fail anything parked — a
         *  continuation left suspended when the engine thread dies would never resume (a
         *  permanently leaked, hung coroutine). The own-side flags ([weEnded], [readClosed])
         *  matter as much as the peer-side ones: an op that still lands on the engine queue
         *  behind [close]'s `engine.destroy()` (e.g. `finally { pipe.close() }` racing runtime
         *  close) must bail on the flags instead of calling into the freed engine. */
        fun failPending(cause: Throwable) {
            writeEnded = true
            peerReadClosed = true
            weEnded = true
            readClosed = true
            if (resetReason == null) resetReason = cause.message ?: "runtime closed"
            inbound.clear()
            val r = reader
            if (r != null) { reader = null; r.cont.resumeWithException(cause) }
            val w = writer
            if (w != null) { writer = null; w.resumeWithException(cause) }
        }
    }

    /** A parked receive: deliver() copies straight into [sink] (up to [byteCount]) and resumes. */
    private class ParkedReader(
        val sink: Buffer,
        val byteCount: Long,
        val cont: CancellableContinuation<Long>,
    )

    /** Cancel handlers, fail anything still parked in [Pipe.send]/[Pipe.receive] (a caller outside
     *  [scope] would otherwise stay suspended forever once the engine thread is gone), free the
     *  engine (on its thread), and release the engine thread. Idempotent: only the first call
     *  tears down; later calls return immediately (the engine must never be destroyed twice, and
     *  the closed dispatcher must never be dispatched to again). */
    suspend fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        scope.cancel()
        // Cleanup must complete even when the caller is already cancelled — the standard
        // `finally { rt.close() }` on a cancelled job — so the teardown hop is NonCancellable
        // (a plain withContext would skip the whole block, silently leaking the engine).
        withContext(engineDispatcher + NonCancellable) {
            val cause = QuickJsException("runtime closed")
            val open = pipes.values.toList()
            pipes.clear()
            notifyIfIdle() // no pipes is factually true; idle waiters complete, not throw
            for (p in open) p.failPending(cause)
            yield() // let the failed continuations unwind while the engine is still alive
            engine.destroy()
        }
    }

    /**
     * BOOT-FAILURE twin of [close]: non-suspend, engine-thread-only, and only for a runtime that
     * never escaped its create call. No yield before destroy — nothing can be parked, because the
     * boot held the engine thread the whole time (handlers were queued, never started). The
     * caller-owned dispatcher is never touched (a native worker's close() from its own thread
     * would deadlock waiting for itself to terminate).
     */
    fun closeNow() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        scope.cancel()
        val cause = QuickJsException("runtime closed")
        val open = pipes.values.toList()
        pipes.clear()
        notifyIfIdle()
        for (p in open) p.failPending(cause)
        engine.destroy()
    }

    companion object {
        /** [onLog] levels: the bundle's console methods, plus 4 = an unhandled promise rejection
         *  (the classic silent async failure, surfaced by the C-side tracker). */
        const val LOG_DEBUG = 0
        const val LOG_INFO = 1
        const val LOG_WARN = 2
        const val LOG_ERROR = 3
        const val LOG_REJECTION = 4

        /**
         * [script] is the engine JS (the :engine-js bundle). It must define the transport globals
         * (`__jsOpen`/`__jsClose`/`__jsCloseRead`/`__jsDrain`, `ymix`) — the native engine
         * captures them right after the one eval, so the script is only accepted HERE, never
         * loaded onto a live runtime. This module ships no JS.
         *
         * [configure] runs on the fresh runtime BEFORE the eval — register [onAccept] handlers
         * there, and the bundle's top-level code can already open every host command it needs
         * (the eval is the engine's boot; a handler registered after [create] returns would miss
         * boot-time opens). Before the eval, C installs the frozen, non-writable `versions`
         * global (`{app: appVersion}`) the bundle branches on for backward compatibility — the
         * host ships as one app build, so the app version identifies its whole surface.
         */
        /**
         * The ONE way to boot a runtime: synchronously, on a CALLER-OWNED [dispatcher]. The
         * contract is the caller's to honor — [dispatcher] must be single-threaded (QuickJS is not
         * thread-safe), the call must run ON it (everything here executes on the current thread),
         * and the caller closes it after [close] (engines may share one JS thread across their
         * lifetimes; [close] never touches it).
         */
        fun create(
            script: String,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            onLog: ((level: Int, message: String) -> Unit)? = null,
            configure: (QuickJsRuntime) -> Unit = {},
        ): QuickJsRuntime = bootOn(dispatcher, appVersion, onLog, configure) { it.evalEngine(script) }

        /** The [ByteArray] twin of the synchronous [create]. */
        fun create(
            bytecode: ByteArray,
            appVersion: Int,
            dispatcher: CoroutineDispatcher,
            onLog: ((level: Int, message: String) -> Unit)? = null,
            configure: (QuickJsRuntime) -> Unit = {},
        ): QuickJsRuntime = bootOn(dispatcher, appVersion, onLog, configure) { it.evalEngineBytecode(bytecode) }

        /**
         * Host entries every runtime created from now on gets in its frozen `versions` global next
         * to `app` (device facts: os, model, screen…). Values are String, Boolean or numbers; set
         * once at startup, before the first engine.
         */
        @Volatile
        var hostVersions: Map<String, Any> = emptyMap()

        /** A fresh single-thread engine dispatcher for the synchronous [create]; the caller owns
         *  it — close it once every runtime confined to it is closed.
         *
         *  [onSlowStint] (with [slowStintMs] > 0) arms a watchdog over **stints** — one continuous
         *  resume-to-suspend stretch ON the engine thread, the unit that actually blocks the
         *  engine (a command's wall time includes suspensions — HTTP awaits, store hops — during
         *  which the thread serves everyone else, so command duration proves nothing).
         *  [onSlowWait] (with [slowWaitMs] > 0) watches the victim's side instead: a task that sat
         *  in the queue for [slowWaitMs]+ before running, which also catches cumulative congestion
         *  no single stint shows. Both callbacks run on the engine thread itself: keep them cheap.
         *  [onSlowStint] receives the stint length and a live engine-thread stack dump (JVM/Android)
         *  captured at the budget, while the task is still running, and how much of the stint the
         *  whole process was frozen; [liveStacks] = false drops both for two clock reads per task
         *  (see [slowStintDispatcher]). A throwing callback propagates
         *  out of the task. Nothing armed = the raw dispatcher, zero overhead. */
        fun newDispatcher(
            slowStintMs: Long = 0L,
            onSlowStint: ((millis: Long, pausedMillis: Long, detail: String) -> Unit)? = null,
            slowWaitMs: Long = 0L,
            onSlowWait: ((millis: Long) -> Unit)? = null,
            liveStacks: Boolean = true,
        ): CloseableCoroutineDispatcher {
            val raw = newEngineDispatcher()
            val stint = onSlowStint.takeIf { slowStintMs > 0L }
            val wait = onSlowWait.takeIf { slowWaitMs > 0L }
            if (stint == null && wait == null) return raw
            return slowStintDispatcher(raw, slowStintMs, stint, slowWaitMs, wait, liveStacks)
        }

        /** Compile [script] to QuickJS bytecode for [create]. Per-function source text is stripped
         *  (`Function.prototype.toString` yields a `[native code]` placeholder instead of embedding
         *  the whole script in the bytecode); line-number debug info is kept for stack traces. */
        suspend fun compile(script: String, filename: String = "<engine>"): ByteArray =
            withContext(Dispatchers.Default) { NativeEngine.compile(script, filename) }

        /** The whole boot, synchronously on the current thread — which MUST be [dispatcher]'s.
         *  A boot failure tears the runtime down via [closeNow] and rethrows; a caller-owned
         *  dispatcher is never closed here. */
        private fun bootOn(
            dispatcher: CoroutineDispatcher,
            appVersion: Int,
            onLog: ((level: Int, message: String) -> Unit)?,
            configure: (QuickJsRuntime) -> Unit,
            eval: (NativeEngine) -> Unit,
        ): QuickJsRuntime {
            var rtRef: QuickJsRuntime? = null
            val callbacks = object : EngineCallbacks {
                override fun onOpen(id: Int, command: String): Int = rtRef?.onOpen(id, command) ?: -1
                override fun onWrite(id: Int, chunk: NativeChunk): Int = rtRef?.onWrite(id, chunk) ?: -1
                override fun onCloseWrite(id: Int, reason: String?) { rtRef?.onCloseWrite(id, reason) }
                override fun onCloseRead(id: Int) { rtRef?.onCloseRead(id) }
                override fun onDrain(id: Int) { rtRef?.onDrain(id) }
                override fun onLog(level: Int, message: String) { rtRef?.onLog(level, message) }
            }
            val engine = NativeEngine.create(appVersion, versionsJson(hostVersions), callbacks)
            val rt = try {
                QuickJsRuntime(engine, dispatcher, onLog)
            } catch (t: Throwable) {
                runCatching { engine.destroy() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
            rtRef = rt
            try {
                rt.installTimers() // host-scheduled setTimeout/setInterval (reserves those commands)
                configure(rt) // register the host-served commands the bundle's boot may open
                eval(engine)
            } catch (t: Throwable) {
                // Full close, not the bare teardown: pipes may have opened during the failed boot
                // (their handlers never ran — the boot held the thread — so closeNow's no-yield
                // teardown is exactly right).
                runCatching { rt.closeNow() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
            return rt
        }
    }
}

/** [entries] as a flat JSON object for C's `versions` install; null when empty. */
internal fun versionsJson(entries: Map<String, Any>): String? {
    if (entries.isEmpty()) return null
    val out = StringBuilder("{")
    for ((key, value) in entries) {
        val literal = when (value) {
            is String -> jsonString(value)
            is Boolean, is Int, is Long -> value.toString()
            is Double, is Float -> value.toString().takeIf { value.toDouble().isFinite() }
                ?: throw IllegalArgumentException("versions entry $key: $value is not a finite number")
            else -> throw IllegalArgumentException("versions entry $key: ${value::class} is not String/Boolean/number")
        }
        if (out.length > 1) out.append(',')
        out.append(jsonString(key)).append(':').append(literal)
    }
    return out.append('}').toString()
}

private fun jsonString(text: String): String {
    val out = StringBuilder(text.length + 2).append('"')
    for (ch in text) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch < ' ' -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> out.append(ch)
        }
    }
    return out.append('"').toString()
}
