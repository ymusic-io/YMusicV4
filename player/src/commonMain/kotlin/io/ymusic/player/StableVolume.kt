package io.ymusic.player

/** The share of YouTube's loudness attenuation playback applies: 0.5 turns its 0.70 gain into 0.85. */
internal const val STABLE_VOLUME_STRENGTH = 0.5f

/** [gain], YouTube's attenuate-only linear loudness gain, with [STABLE_VOLUME_STRENGTH] of its cut. */
internal fun softenedLoudnessGain(gain: Float): Float = 1f - (1f - gain.coerceIn(0f, 1f)) * STABLE_VOLUME_STRENGTH
