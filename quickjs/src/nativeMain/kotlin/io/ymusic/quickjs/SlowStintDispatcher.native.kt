package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** Delay passes through to [delegate]: without it every timer on this dispatcher hops via DefaultExecutor. */
@OptIn(InternalCoroutinesApi::class)
internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long, pausedMillis: Long, detail: String) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
    liveStacks: Boolean,
): CloseableCoroutineDispatcher = object : CloseableCoroutineDispatcher(), Delay by delegate as Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        val fire = onSlowStint?.let { cb -> { ms: Long, paused: Long -> cb(ms, paused, "") } }
        delegate.dispatch(
            context,
            Runnable { runWatched(enqueued, stintMs, fire, waitMs, onSlowWait, { 0L }) { block.run() } },
        )
    }

    override fun close() = delegate.close()
}
