package io.ymusic.ytmedia.sabr

import kotlinx.serialization.json.JsonObject
import projectcommon.json.boolOrNull
import projectcommon.json.doubleOrNull
import projectcommon.json.long
import projectcommon.json.longOrNull
import projectcommon.json.objectOrNull
import projectcommon.json.stringOrNull

/** One player-response `streamingData.adaptiveFormats` entry. */
fun JsonObject.toAdaptiveFormat(): AdaptiveFormat {
    val index = objectOrNull("indexRange")
    val audioTrack = objectOrNull("audioTrack")
    return AdaptiveFormat(
        itag = long("itag").toInt(),
        lmt = long("lastModified"),
        xtag = stringOrNull("xtags"),
        mimeType = stringOrNull("mimeType") ?: "",
        clen = longOrNull("contentLength") ?: 0L,
        indexRangeStart = index?.longOrNull("start") ?: 0L,
        indexRangeEnd = index?.longOrNull("end") ?: 0L,
        width = longOrNull("width")?.toInt(),
        height = longOrNull("height")?.toInt(),
        approxDurationMs = longOrNull("approxDurationMs")?.toInt() ?: 0,
        audioTrackIsDefault = audioTrack?.boolOrNull("audioIsDefault"),
        audioTrackId = audioTrack?.stringOrNull("id"),
        isDrc = boolOrNull("isDrc") ?: false,
        loudnessLkfs = doubleOrNull("trackAbsoluteLoudnessLkfs"),
        isVb = boolOrNull("isVb") ?: false,
    )
}
