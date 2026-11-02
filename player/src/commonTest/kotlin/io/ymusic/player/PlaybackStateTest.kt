package io.ymusic.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStateTest {
    @Test
    fun ready_to_load_matches_legacy_contract() {
        assertFalse(PlaybackState.Uninitialized(isInitializing = false).readyToLoad)
        assertFalse(PlaybackState.Uninitialized(isInitializing = true).readyToLoad)
        assertTrue(PlaybackState.Idle.readyToLoad)
        assertTrue(PlaybackState.Prepared(true, buffering = true, ended = false).readyToLoad)
        assertTrue(PlaybackState.Error(PlayerError("E1", canRetry = true)).readyToLoad)
        assertTrue(PlaybackState.Error(PlayerError("E1", canRetry = false)).readyToLoad)
        assertFalse(PlaybackState.Error(PlayerError("E1", canRetry = true, needReset = true)).readyToLoad)
    }
}
