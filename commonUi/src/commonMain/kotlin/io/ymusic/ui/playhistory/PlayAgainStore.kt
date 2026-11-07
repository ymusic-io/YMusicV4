package io.ymusic.ui.playhistory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import io.ymusic.player.PlayableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import projectcommon.json.bool
import projectcommon.json.doubleOrNull
import projectcommon.json.longOrNull
import projectcommon.json.string
import projectcommon.json.stringOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * One saved queue in "Play again" (legacy Rewind playlist): where it was left — the item at
 * [queueIndex] of [queueSize], [positionMs] into it — keyed by the queue's [io.ymusic.player.QueueState.queueId].
 * [stripThumbs] is the art of the queue window starting at [stripStart] (around the current item);
 * [sourceToken] is the queue's [io.ymusic.player.QueueSource.resumeToken] (where its playlist continues).
 */
data class PlayAgainSession(
    val queueId: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val queueIndex: Int,
    val queueSize: Int,
    val positionMs: Long,
    val durationMs: Long,
    val stripStart: Int,
    val stripThumbs: List<String?>,
    val sourceToken: String? = null,
) {
    /** Resuming mid-item only pays off for long content; anything with under [RESUME_MIN_REMAINING] left
     *  (every ordinary song) restarts. */
    val resumesMidItem: Boolean
        get() = durationMs - positionMs >= RESUME_MIN_REMAINING.inWholeMilliseconds

    companion object {
        val RESUME_MIN_REMAINING = 5.minutes
        const val STRIP_BEFORE = 2
        const val STRIP_SIZE = 6

        /** First index of the [STRIP_SIZE] window shown for the item at [index] of [size]. */
        fun stripStart(index: Int, size: Int): Int = (index - STRIP_BEFORE).coerceIn(0, (size - STRIP_SIZE).coerceAtLeast(0))
    }
}

/** Whole queues for "Play again", newest first, capped at [maxSessions]. Shares the play-history database. */
class PlayAgainStore(
    private val driver: SqlDriver,
    private val maxSessions: Int = MAX_SESSIONS,
) {
    private val io = playHistoryDispatcher()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: Flow<Unit> = _changes.asSharedFlow()

    /** Insert or replace [session] with its queue [items]. */
    suspend fun save(session: PlayAgainSession, items: List<PlayableItem>) {
        require(items.size == session.queueSize)
        val itemsJson = encodeItems(items)
        withContext(io) {
            driver.execute(
                null,
                """
                INSERT OR REPLACE INTO play_again(
                    queue_id, title, subtitle, thumbnail_url, queue_index, queue_size, position_ms, duration_ms,
                    strip_start, strip_thumbs, source_token, items, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                13,
            ) {
                bindString(0, session.queueId)
                bindString(1, session.title)
                bindString(2, session.subtitle)
                bindString(3, session.thumbnailUrl)
                bindLong(4, session.queueIndex.toLong())
                bindLong(5, session.queueSize.toLong())
                bindLong(6, session.positionMs)
                bindLong(7, session.durationMs)
                bindLong(8, session.stripStart.toLong())
                bindString(9, encodeThumbs(session.stripThumbs))
                bindString(10, session.sourceToken)
                bindString(11, itemsJson)
                bindLong(12, now())
            }
            driver.execute(
                null,
                """
                DELETE FROM play_again WHERE queue_id IN (
                    SELECT queue_id FROM play_again ORDER BY updated_at DESC LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
                1,
            ) { bindLong(0, maxSessions.toLong()) }
        }
        _changes.tryEmit(Unit)
    }

    /** Update the play-head of an already saved queue whose items are unchanged; false when it isn't saved. */
    suspend fun saveProgress(session: PlayAgainSession): Boolean {
        val updated = withContext(io) {
            driver.execute(
                null,
                """
                UPDATE play_again SET
                    title = ?, subtitle = ?, thumbnail_url = ?, queue_index = ?, position_ms = ?, duration_ms = ?,
                    strip_start = ?, strip_thumbs = ?, source_token = ?, updated_at = ?
                WHERE queue_id = ?
                """.trimIndent(),
                11,
            ) {
                bindString(0, session.title)
                bindString(1, session.subtitle)
                bindString(2, session.thumbnailUrl)
                bindLong(3, session.queueIndex.toLong())
                bindLong(4, session.positionMs)
                bindLong(5, session.durationMs)
                bindLong(6, session.stripStart.toLong())
                bindString(7, encodeThumbs(session.stripThumbs))
                bindString(8, session.sourceToken)
                bindLong(9, now())
                bindString(10, session.queueId)
            }.value
        }
        if (updated == 0L) return false
        _changes.tryEmit(Unit)
        return true
    }

    suspend fun sessions(): List<PlayAgainSession> = withContext(io) {
        driver.executeQuery(
            null,
            "SELECT $SESSION_COLUMNS FROM play_again ORDER BY updated_at DESC",
            { cursor ->
                val out = mutableListOf<PlayAgainSession>()
                while (cursor.next().value) out += cursor.toSession()
                QueryResult.Value(out)
            },
            0,
        ).value
    }

    /** The saved session and its queue; null when [queueId] is not (or no longer) saved. */
    suspend fun load(queueId: String): Pair<PlayAgainSession, List<PlayableItem>>? = withContext(io) {
        driver.executeQuery(
            null,
            "SELECT $SESSION_COLUMNS, items FROM play_again WHERE queue_id = ?",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.toSession() to decodeItems(cursor.getString(11)!!) else null,
                )
            },
            1,
        ) { bindString(0, queueId) }.value
    }

    suspend fun delete(queueId: String) {
        withContext(io) {
            driver.execute(null, "DELETE FROM play_again WHERE queue_id = ?", 1) { bindString(0, queueId) }
        }
        _changes.tryEmit(Unit)
    }

    @OptIn(ExperimentalTime::class)
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val MAX_SESSIONS = 50
        private const val SESSION_COLUMNS =
            "queue_id, title, subtitle, thumbnail_url, queue_index, queue_size, position_ms, duration_ms, " +
                "strip_start, strip_thumbs, source_token"
    }
}

private fun SqlCursor.toSession() = PlayAgainSession(
    queueId = getString(0)!!,
    title = getString(1)!!,
    subtitle = getString(2),
    thumbnailUrl = getString(3),
    queueIndex = getLong(4)!!.toInt(),
    queueSize = getLong(5)!!.toInt(),
    positionMs = getLong(6)!!,
    durationMs = getLong(7)!!,
    stripStart = getLong(8)!!.toInt(),
    stripThumbs = decodeThumbs(getString(9)!!),
    sourceToken = getString(10),
)

private fun encodeThumbs(thumbs: List<String?>): String = buildJsonArray { thumbs.forEach { add(JsonPrimitive(it)) } }.toString()

/** A row saved before the strip existed holds an empty string: no tiles. */
private fun decodeThumbs(json: String): List<String?> =
    if (json.isEmpty()) emptyList() else Json.parseToJsonElement(json).jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }

private const val TYPE_LOCAL = "local"
private const val TYPE_YT = "yt"

private fun encodeItems(items: List<PlayableItem>): String = buildJsonArray {
    items.forEach { item ->
        add(
            buildJsonObject {
                when (item) {
                    is PlayableItem.LocalMedia -> {
                        put("type", TYPE_LOCAL)
                        put("uri", item.uri)
                        put("mediaId", item.mediaId)
                        put("videoId", item.videoId)
                        put("title", item.title)
                        put("artist", item.artist)
                        put("album", item.album)
                        put("thumbnailUrl", item.thumbnailUrl)
                        put("durationMs", item.durationMs)
                        put("loudnessGainDb", item.loudnessGainDb)
                    }
                    is PlayableItem.YtMedia -> {
                        put("type", TYPE_YT)
                        put("videoId", item.videoId)
                        put("title", item.title)
                        put("artist", item.artist)
                        put("channelUrl", item.channelUrl)
                        put("thumbnailUrl", item.thumbnailUrl)
                        put("duration", item.duration)
                        put("durationMs", item.durationMs)
                        put("live", item.live)
                    }
                }
            },
        )
    }
}.toString()

private fun decodeItems(json: String): List<PlayableItem> =
    Json.parseToJsonElement(json).jsonArray.map { element ->
        val obj = element as JsonObject
        when (val type = obj.string("type")) {
            TYPE_LOCAL -> PlayableItem.LocalMedia(
                uri = obj.string("uri"),
                mediaId = obj.longOrNull("mediaId"),
                title = obj.stringOrNull("title"),
                artist = obj.stringOrNull("artist"),
                album = obj.stringOrNull("album"),
                thumbnailUrl = obj.stringOrNull("thumbnailUrl"),
                durationMs = obj.longOrNull("durationMs"),
                loudnessGainDb = obj.doubleOrNull("loudnessGainDb"),
                videoId = obj.stringOrNull("videoId"),
            )
            TYPE_YT -> PlayableItem.YtMedia(
                videoId = obj.string("videoId"),
                title = obj.stringOrNull("title"),
                artist = obj.stringOrNull("artist"),
                thumbnailUrl = obj.stringOrNull("thumbnailUrl"),
                duration = obj.stringOrNull("duration"),
                durationMs = obj.longOrNull("durationMs"),
                live = obj.bool("live"),
                channelUrl = obj.stringOrNull("channelUrl"),
            )
            else -> error("Unknown play-again item type $type")
        }
    }
