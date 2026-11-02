package io.ymusic.player

import kotlinx.coroutines.flow.SharedFlow

/**
 * YouTube playback engine. Same capabilities the facade binds ([VideoQualitySelectable], captions,
 * audio tracks) plus download helpers and the watch-next harvest. [io.ymusic.player.mse.MsePlayer]
 * is the playback implementation. Downloads cook SABR specs through [requestPlayerResponse]
 * (cached `/player` + decoded googlevideo URL) and [requestPoToken].
 */
interface YtEngine :
    MediaEngine<PlayableItem.YtMedia>,
    VideoQualitySelectable,
    CaptionSelectable,
    CaptionRenderable,
    AudioTrackSelectable {
    val watchNextSeen: SharedFlow<WatchNextSeen>
    suspend fun requestPlayerResponse(videoId: String): String
    suspend fun requestPoToken(videoId: String): String
}
