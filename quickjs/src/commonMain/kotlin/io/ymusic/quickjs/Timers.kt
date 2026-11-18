package io.ymusic.quickjs

import kmpcommon.io.BufferedAsyncSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okio.Buffer
import okio.EOFException
import kotlin.time.TimeSource

/**
 * `setTimeout` / `setInterval` (+ their `clear*`), **scheduled on the host**. QuickJS core ships no
 * timers — in every JS runtime they belong to the host (libuv in Node, the task scheduler in a
 * browser), not the engine. We provide them over the pipe protocol (no new native code), backed by a
 * coroutine `delay` on the engine scope: `delay` **suspends**, so the single engine thread stays free
 * while a timer waits, and the callback fires as a normal host→JS edge (like any pipe event) followed
 * by the microtask pump.
 *
 * Wire: JS opens a `"setTimeout"` / `"setInterval"` pipe, sends the delay (ms, big-endian 8-byte long), then
 * receives one byte per fire; closing the pipe (`clearTimeout`/`clearInterval`) stops it. Reserves
 * those two command names.
 */
internal fun QuickJsRuntime.installTimers() {
    onAccept("setTimeout") { pipe -> runTimer(pipe, repeat = false) }
    onAccept("setInterval") { pipe -> runTimer(pipe, repeat = true) }
    // The JS globals (setTimeout/setInterval/clear*) live in the engine script (transport.ts).
}

private const val TICK = 1

/** HTML5's minimum for `setInterval`; also the floor that keeps a 0 ms interval — a NaN/garbage
 *  delay coerces to 0 upstream — from hot-looping the single engine thread with back-to-back ticks. */
private const val MIN_INTERVAL_MS = 4L

private const val BACKGROUND_INTERVAL_MS = 1_000L

private val gridOrigin = TimeSource.Monotonic.markNow()

/** In the background an interval under a second waits for the next line of a shared 1 s grid, so
 *  every runtime's intervals fire together in one wake-up a second. */
private fun intervalWait(ms: Long): Long {
    if (!QuickJsRuntime.timersInBackground || ms >= BACKGROUND_INTERVAL_MS) return ms
    return BACKGROUND_INTERVAL_MS - gridOrigin.elapsedNow().inWholeMilliseconds % BACKGROUND_INTERVAL_MS
}

/**
 * Read the delay, then fire after it — racing each wait against the pipe's close so a cleared timer
 * stops promptly and its `delay` is cancelled (no coroutine left sleeping). `setInterval` loops;
 * `setTimeout` fires once.
 */
private suspend fun runTimer(pipe: Pipe, repeat: Boolean) {
    val source = pipe.source.asBufferedSource()
    val ms = readMillis(source).let { if (repeat) it.coerceAtLeast(MIN_INTERVAL_MS) else it }
    coroutineScope {
        val cleared = async { source.awaitEof() } // completes when JS closes the pipe (clear*)
        try {
            do {
                val stopped = select {
                    cleared.onAwait { true }   // cleared before the next tick → stop
                    onTimeout(if (repeat) intervalWait(ms) else ms) { false } // elapsed → fire
                }
                if (stopped) break
                pipe.sink.send(Buffer().writeByte(TICK), 1L)
            } while (repeat)
        } finally {
            cleared.cancel()
        }
    }
    pipe.sink.close()
    pipe.source.close()
}

/** First frame is the delay as a big-endian 8-byte long (okio `readLong`); the fixed width
 *  self-frames it, so a split write can't be misread as a shorter value. A clear before the frame
 *  fully arrives reads as 0 (fire immediately / no interval). */
private suspend fun readMillis(source: BufferedAsyncSource): Long =
    try {
        source.readLong().coerceAtLeast(0L)
    } catch (e: EOFException) {
        0L
    }

/** Suspend until the peer closes the pipe (JS sends nothing after the delay). */
private suspend fun BufferedAsyncSource.awaitEof() {
    while (!exhausted()) buffer.clear() // discard anything unexpected; wait for EOF
}
