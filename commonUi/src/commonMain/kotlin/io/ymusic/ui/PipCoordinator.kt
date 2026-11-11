package io.ymusic.ui

import io.ymusic.player.Player
import io.ymusic.player.VideoQualityState
import io.ymusic.player.isPlaying
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Picture-in-picture eligibility and the AudioOnly demotion used when the app leaves
 * the screen without PiP, or when the user closes the PiP window (pause + AudioOnly;
 * a notification resume stays audio until foreground restores the user's quality).
 */
class PipCoordinator(
    private val player: Player,
    private val settings: SettingsStore,
) {
    val nowPlayingOpen = MutableStateFlow(false)
    val videoFullscreen = MutableStateFlow(false)
    val inPip = MutableStateFlow(false)

    /** True while AudioOnly is forced for background — not the user's choice, so never persisted. */
    val demotedToAudioOnly = MutableStateFlow(false)

    /** No view shows the picture, so the player rests its video decoder (desktop: the player bar covers it). True until
     *  the UI composes and whenever none is composed. */
    val pictureHidden = MutableStateFlow(true)

    fun enablePip(): Boolean = settings.flow(AppSettings.enablePip).value

    fun playingVideo(): Boolean {
        if (player.config.value.audioOnly) return false
        val q = player.videoQuality.value
        if (q.preferredQuality == VideoQualityState.AUDIO_ONLY) return false
        if (q.actualQuality == VideoQualityState.AUDIO_ONLY) return false
        return isPlaying(player.status.value, player.queue.value)
    }

    /** Now Playing or fullscreen, video (not AudioOnly), PiP setting on, currently playing. */
    fun autoEnterEligible(): Boolean {
        if (!enablePip()) return false
        if (!nowPlayingOpen.value && !videoFullscreen.value) return false
        return playingVideo()
    }

    fun onLeaveWithoutPip() {
        if (inPip.value) return
        if (playingVideo()) demoteToAudioOnly()
    }

    /** Desktop: the window is minimized or hidden, playing or not. [onEnterForeground] restores the quality. */
    fun onWindowHidden() {
        demoteToAudioOnly()
    }

    fun onPipStarted() {
        inPip.value = true
    }

    fun onPipStopped(appInForeground: Boolean) {
        inPip.value = false
        if (appInForeground) {
            onEnterForeground()
            return
        }
        // X-close: pause so audio does not keep going in the background. AudioOnly so a
        // notification / lock-screen resume stays audio until the app is foregrounded.
        demoteToAudioOnly()
        player.pause()
    }

    private fun demoteToAudioOnly() {
        if (player.config.value.audioOnly) return
        if (player.videoQuality.value.preferredQuality == VideoQualityState.AUDIO_ONLY) return
        demotedToAudioOnly.value = true
        player.setAudioOnly(true)
    }

    fun onEnterForeground() {
        if (!demotedToAudioOnly.value) return
        player.setAudioOnly(false)
        demotedToAudioOnly.value = false
    }
}
