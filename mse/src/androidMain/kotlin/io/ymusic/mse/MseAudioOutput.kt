package io.ymusic.mse

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Plays one MSE host's PCM ring through Media3's [DefaultAudioSink]: a mixer AudioTrack that equalizer
 * apps can attach to, playout timestamps for the host's A/V clock, and a new track after audioserver
 * dies. DefaultAudioSink allows a single looper, so every sink call runs on [thread].
 */
@OptIn(UnstableApi::class)
internal class MseAudioOutput(private val host: Long) {
    private val thread = HandlerThread("mse-audio", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val state = IntArray(STATE_SIZE)
    private val step = Runnable { step() }
    private lateinit var sink: DefaultAudioSink

    @Volatile
    private var released = false
    private var gen = -1
    private var rate = 0
    private var channels = 0
    private var configured = false
    private var configuredAtMs = 0L
    private var configureWarned = ""
    private var running = false
    private var failing = false

    /** Frames in [buffer] the sink has not taken yet; 0 = [buffer] is free. */
    private var heldFrames = 0
    private var heldSilence = false

    /** Frames handed to the sink since its last flush, silence included: the timestamp base. */
    private var sinkFrames = 0L

    /** Sink frame where this generation starts: 0 after a flush, later on a track kept running. */
    private var baseFrames = 0L

    /** Start padding (see [padToStart]): [padFrames] of silence written after sink frame [padAt]. */
    private var padAt = 0L
    private var padFrames = 0L
    private var starvedSinceMs = 0L
    private var underrunLoggedAtMs = 0L

    /** Highest position reported in this generation: AudioTrack timestamps can step backwards. */
    private var reportedFrames = -1L

    /** Sink frame where this generation's audio ended (see [padToEnd]); -1 while it is still coming. */
    private var endAt = -1L

    init {
        handler.post {
            @Suppress("DEPRECATION") // Builder(Context) only adds encoded-passthrough detection; this is PCM.
            sink = DefaultAudioSink.Builder()
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(MIN_TRACK_BUFFER_US)
                        .setMaxPcmBufferDurationUs(MAX_TRACK_BUFFER_US)
                        .build(),
                )
                .build()
            sink.setListener(SinkListener())
            sink.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            MseHostNative.dacAttach(host, true)
            step()
        }
    }

    /** Blocks until the sink is gone and the host no longer feeds it; call before destroying the host. */
    fun release() {
        released = true
        // Wakes a paused dacPoll wait now instead of after PAUSED_WAIT_MS.
        MseHostNative.dacAttach(host, false)
        val done = CountDownLatch(1)
        handler.post {
            handler.removeCallbacks(step)
            sink.reset()
            sink.release()
            done.countDown()
        }
        done.await()
        thread.quitSafely()
    }

    private fun step() {
        if (released) return
        val free = heldFrames == 0
        val n = MseHostNative.dacPoll(host, buffer, if (free) buffer.capacity() else 0, PAUSED_WAIT_MS, state)
        if (n == POLL_RELEASED) return
        if (n < 0) {
            if (!failing) MseLogBridge.fire(LOG_WARN, "mse audio sink: poll failed ($n), retrying")
            failing = true
            handler.postDelayed(step, RETRY_MS)
            return
        }
        if (!configured && rate > 0 && SystemClock.uptimeMillis() - configuredAtMs >= CONFIGURE_RETRY_MS) {
            configured = configure()
        }
        if (state[GEN] != gen || state[RATE] != rate || state[CHANNELS] != channels) {
            // A flush releases the AudioTrack, and a new one takes ~150 ms to start. After the end of a
            // generation (the next song, a replay) what the track still holds is that end, so it plays
            // out and the track runs on; audio queued mid-stream (a seek) is stale and must go. A
            // generation that wrote nothing leaves whatever an earlier one kept.
            val sameFormat = configured && state[RATE] == rate && state[CHANNELS] == channels
            if (sameFormat && (endAt >= 0 || sinkFrames == baseFrames || !sink.hasPendingData())) {
                // One continuous stream: timestamps keep growing, positions count from baseFrames.
                baseFrames = sinkFrames
            } else {
                sink.flush()
                sinkFrames = 0
                baseFrames = 0
            }
            gen = state[GEN]
            heldFrames = 0
            heldSilence = false
            padAt = baseFrames
            padFrames = 0
            starvedSinceMs = 0
            reportedFrames = -1
            endAt = -1
            if (state[RATE] != rate || state[CHANNELS] != channels || !configured) {
                rate = state[RATE]
                channels = state[CHANNELS]
                configured = configure()
            }
        }
        if (n > 0 && configured) {
            // More audio after the end (a live stream grew) plays on after the end pad.
            endAt = -1
            buffer.clear()
            buffer.limit(n)
            heldFrames = n / (channels * BYTES_PER_SAMPLE)
            starvedSinceMs = 0
        }
        val run = state[RUN] != 0
        if (run != running) {
            running = run
            if (run) sink.play() else sink.pause()
        }
        if (n == 0 && heldFrames == 0 && running && padFrames == 0L && reportedFrames <= 0 && sinkFrames > 0 &&
            baseFrames == 0L
        ) {
            val now = SystemClock.uptimeMillis()
            if (starvedSinceMs == 0L) starvedSinceMs = now else if (now - starvedSinceMs >= START_STARVED_MS) padToStart()
        }
        var full = false
        try {
            if (heldFrames > 0) {
                if (sink.handleBuffer(buffer, sinkFrames * MICROS_PER_SECOND / rate, 1)) {
                    sinkFrames += heldFrames
                    heldSilence = false
                    heldFrames = 0
                } else {
                    full = true
                }
            }
            if (state[DRAIN] != 0 && heldFrames == 0 && sinkFrames > 0 && endAt < 0) padToEnd()
            failing = false
        } catch (error: AudioSink.InitializationException) {
            recover(error)
            return
        } catch (error: AudioSink.WriteException) {
            recover(error)
            return
        }
        report()
        when {
            n > 0 && !full -> handler.post(step)
            full || running -> handler.postDelayed(step, playingWaitMs())
            else -> handler.postDelayed(step, IDLE_MS)
        }
    }

    /** A playing track lives off its buffer between steps: a step every third of it keeps it fed. */
    private fun playingWaitMs(): Long {
        val bufferUs = sink.audioTrackBufferSizeUs
        if (bufferUs == C.TIME_UNSET) return IDLE_MS
        return (bufferUs / 3_000).coerceIn(IDLE_MS, PLAYING_WAIT_MAX_MS)
    }

    private fun report() {
        val raw = playedFrames()
        if (raw >= 0) {
            val played = if (endAt >= 0) minOf(raw, endAt) else raw
            val media = (if (played <= padAt || padFrames == 0L) played else maxOf(padAt, played - padFrames)) - baseFrames
            // Negative while a kept track still plays the previous generation's end.
            if (media >= 0) reportedFrames = maxOf(reportedFrames, media)
        }
        val bufferUs = sink.audioTrackBufferSizeUs.takeIf { it != C.TIME_UNSET } ?: 0L
        // A start pad still held is counted in padFrames but not yet in sinkFrames; the end pad is not audio.
        val padWritten = if (endAt < 0 && heldSilence) 0L else padFrames
        val written = (if (endAt >= 0) endAt else sinkFrames) - baseFrames - padWritten
        val pending = if (endAt >= 0) raw < endAt else sink.hasPendingData() || heldFrames > 0
        MseHostNative.dacReport(host, gen, reportedFrames, written, pending, bufferUs)
    }

    /** Sink frames played since the last flush, pads included; -1 when the sink has no position yet. */
    private fun playedFrames(): Long {
        val positionUs = sink.getCurrentPositionUs(false)
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || rate <= 0) return -1
        return positionUs * rate / MICROS_PER_SECOND
    }

    /**
     * The end of this generation's audio. A running streaming AudioTrack can hold back its last
     * partial period until more data comes; playToEndOfStream would push it out by stopping the track,
     * and only a flush (a new AudioTrack) restarts it. A short silence pad pushes it out instead, and
     * the track keeps running for the next generation. [report] counts audio up to [endAt] only.
     */
    private fun padToEnd() {
        val frameBytes = channels * BYTES_PER_SAMPLE
        val frames = (rate * END_PAD_MS / 1000).coerceAtMost((buffer.capacity() / frameBytes).toLong()).toInt()
        endAt = sinkFrames
        if (frames <= 0) return
        buffer.clear()
        buffer.put(SILENCE, 0, frames * frameBytes)
        buffer.flip()
        heldFrames = frames
        heldSilence = true
    }

    /**
     * A streaming AudioTrack starts only once its buffer is full, and outputs may enlarge the buffer
     * past what we ask for (500 ms on a Galaxy S24). Right after a seek the stream can hold less than
     * that while the host's clock waits for the track, so neither moves: top the track up with
     * silence once. [report] takes the silence back out of the position.
     */
    private fun padToStart() {
        val bufferUs = sink.audioTrackBufferSizeUs
        if (bufferUs == C.TIME_UNSET) return
        val frameBytes = channels * BYTES_PER_SAMPLE
        val missing = bufferUs * rate / MICROS_PER_SECOND - sinkFrames
        val frames = (missing.coerceAtLeast(0) + rate / START_PAD_MARGIN_DIVISOR)
            .coerceAtMost((buffer.capacity() / frameBytes).toLong()).toInt()
        buffer.clear()
        buffer.put(SILENCE, 0, frames * frameBytes)
        buffer.flip()
        heldFrames = frames
        heldSilence = true
        padAt = sinkFrames
        // Counted now: the sink may take the pad in pieces, and report() runs between them.
        padFrames = frames.toLong()
        MseLogBridge.fire(LOG_INFO, "mse audio sink: track not started, padded ${frames * 1000L / rate}ms of silence")
    }

    private fun configure(): Boolean {
        configuredAtMs = SystemClock.uptimeMillis()
        if (rate <= 0 || channels <= 0) return false
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .build()
        return try {
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            true
        } catch (error: AudioSink.ConfigurationException) {
            val what = "${rate}Hz/${channels}ch"
            if (configureWarned != what) MseLogBridge.fire(LOG_WARN, "mse audio sink: cannot play $what, retrying: $error")
            configureWarned = what
            false
        }
    }

    /**
     * A flush drops the broken track; the next handleBuffer opens a new one. The held chunk is
     * resubmitted at the old timestamp, so the clock carries on past the audio the track lost.
     */
    private fun recover(error: Exception) {
        if (!failing) MseLogBridge.fire(LOG_WARN, "mse audio sink failed, reopening: $error")
        failing = true
        sink.flush()
        handler.postDelayed(step, RETRY_MS)
    }

    private inner class SinkListener : AudioSink.Listener {
        override fun onPositionDiscontinuity() = Unit

        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            val now = SystemClock.uptimeMillis()
            if (now - underrunLoggedAtMs < UNDERRUN_LOG_INTERVAL_MS) return
            underrunLoggedAtMs = now
            // sinceFeed at or past the buffer: we stopped feeding; well under it: the device starved itself.
            MseLogBridge.fire(LOG_WARN, "mse audio sink underrun buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms")
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            MseLogBridge.fire(LOG_INFO, "mse audio session $audioSessionId")
            MseHostNative.dacSession(host, audioSessionId)
        }
    }

    private companion object {
        const val GEN = 0
        const val RATE = 1
        const val CHANNELS = 2
        const val RUN = 3
        const val DRAIN = 4
        const val STATE_SIZE = 5

        /** dacPoll: the host is released; anything else negative is a JNI buffer error. */
        const val POLL_RELEASED = -1

        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_BYTES = 4096 * 2 * BYTES_PER_SAMPLE
        val SILENCE = ByteArray(CHUNK_BYTES)
        const val MICROS_PER_SECOND = 1_000_000L
        const val IDLE_MS = 10L

        /** Under the host clock's 50 ms run past the last report (DAC_EXTRAPOLATE_S): each step reports. */
        const val PLAYING_WAIT_MAX_MS = 40L
        const val RETRY_MS = 100L
        const val PAUSED_WAIT_MS = 200
        const val START_STARVED_MS = 100L
        const val END_PAD_MS = 50L
        const val CONFIGURE_RETRY_MS = 1000L
        const val UNDERRUN_LOG_INTERVAL_MS = 10_000L

        /** Pad margin: 1/50 s. */
        const val START_PAD_MARGIN_DIVISOR = 50

        /** The host's ring is the real buffer; outputs may still enlarge the track (see [padToStart]). */
        const val MIN_TRACK_BUFFER_US = 100_000
        const val MAX_TRACK_BUFFER_US = 200_000

        /** MSE_LOG_INFO / MSE_LOG_WARN. */
        const val LOG_INFO = 1
        const val LOG_WARN = 2
    }
}
