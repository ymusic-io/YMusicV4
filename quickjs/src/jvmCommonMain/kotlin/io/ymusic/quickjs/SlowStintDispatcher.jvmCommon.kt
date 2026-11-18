package io.ymusic.quickjs

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val stintDumper: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "engine-stint-dump").apply { isDaemon = true }
    }

/**
 * One task's stint job on [stintDumper]: at the budget it dumps the engine thread's stack, then
 * re-arms every [HEARTBEAT_MS] while the task runs. A job that runs, or is still pending, [PAUSE_MS]+
 * past its due time means no thread of the process ran meanwhile: that lateness is a process pause.
 */
private class StintWatch(private val task: String, private val engineThread: Thread, stintMs: Long) : Runnable {
    private var due = TimeSource.Monotonic.markNow() + stintMs.milliseconds
    private var running = true
    private var pausedMs = 0L
    private var job: ScheduledFuture<*>? = null

    var stack: String? = null
        private set

    fun start(stintMs: Long) = synchronized(this) {
        job = stintDumper.schedule(this, stintMs, TimeUnit.MILLISECONDS)
    }

    override fun run() = synchronized(this) {
        if (!running) return
        addLateness()
        if (stack == null) stack = formatEngineStack(task, engineThread)
        due = TimeSource.Monotonic.markNow() + HEARTBEAT_MS.milliseconds
        job = stintDumper.schedule(this, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    /** Stops the job; returns the milliseconds of the stint the process was paused. */
    fun finish(): Long = synchronized(this) {
        running = false
        job?.cancel(false)
        addLateness()
        pausedMs
    }

    private fun addLateness() {
        val late = due.elapsedNow().inWholeMilliseconds
        if (late >= PAUSE_MS) pausedMs += late
    }

    private companion object {
        const val HEARTBEAT_MS = 100L
        const val PAUSE_MS = 250L
    }
}

/** Delay passes through to [delegate]: without it every timer on this dispatcher hops via DefaultExecutor. */
@OptIn(InternalCoroutinesApi::class)
internal actual fun slowStintDispatcher(
    delegate: CloseableCoroutineDispatcher,
    stintMs: Long,
    onSlowStint: ((millis: Long, pausedMillis: Long, detail: String) -> Unit)?,
    waitMs: Long,
    onSlowWait: ((millis: Long) -> Unit)?,
    liveStacks: Boolean,
): CloseableCoroutineDispatcher = object : ExecutorCoroutineDispatcher(), Delay by delegate as Delay {
    // newEngineDispatcher() is executor-backed on JVM/Android; expose its executor unchanged.
    override val executor: Executor get() = (delegate as ExecutorCoroutineDispatcher).executor

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val enqueued = TimeSource.Monotonic.markNow()
        val task = context[CoroutineName]?.name ?: "unnamed"
        delegate.dispatch(context) {
            val watch = if (liveStacks && onSlowStint != null && stintMs > 0L) {
                StintWatch(task, Thread.currentThread(), stintMs).also { it.start(stintMs) }
            } else {
                null
            }
            runWatched(
                enqueued,
                stintMs,
                onSlowStint?.let { cb ->
                    { ms: Long, paused: Long ->
                        val detail = when {
                            watch == null -> "task=$task"
                            else -> watch.stack ?: "task=$task (stint ended before a live dump)"
                        }
                        cb(ms, paused, detail)
                    }
                },
                waitMs,
                onSlowWait,
                { watch?.finish() ?: 0L },
            ) { block.run() }
        }
    }

    override fun close() = delegate.close()
}

private fun formatEngineStack(task: String, thread: Thread): String = buildString {
    append("task=").append(task).append(" thread=").append(thread.name)
    for (el in thread.stackTrace) {
        append("\n  at ").append(el)
    }
}
