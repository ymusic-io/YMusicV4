package io.ymusic.ui.subscriptions

import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.YtThumb
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.mapState
import kmpcommon.log.Logger
import kmphttp.HttpStatusException
import kmphttp.KmpHttpClient
import kmphttp.RequestBuilder
import kmphttp.toHttpUrlOrNull
import kmphttp.utf8String
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.use
import projectcommon.json.stringOrNull

data class LocalChannel(val id: String, val title: String, val thumb: String?)

/** What [LocalSubscriptions.latest] found: the newest uploads and how many channels could not be read. */
data class LocalFeed(val items: List<YtFeedItem>, val failedChannels: Int)

/**
 * Subscriptions without a Google account: the channel list lives in the app settings and the feed is
 * every channel's public RSS file, so nothing about the viewer leaves the device but the channel ids.
 */
class LocalSubscriptions(
    private val settings: SettingsStore,
    private val client: KmpHttpClient,
    private val logger: Logger?,
) {
    val channels: StateFlow<List<LocalChannel>> = settings.string(KEY, "[]").mapState { decode(it) }

    fun isSubscribed(channelId: String): Boolean = channels.value.any { it.id == channelId }

    suspend fun subscribe(channel: LocalChannel) {
        val held = channels.value
        if (held.any { it.id == channel.id }) return
        save(held + channel)
    }

    suspend fun unsubscribe(channelId: String) {
        save(channels.value.filterNot { it.id == channelId })
    }

    /** The channel list as text, ready to keep in a note or move to another phone. */
    fun export(): String = encode(channels.value)

    /** Adds the channels in [raw] (an [export] text) that are not subscribed yet; returns how many were added. */
    suspend fun importFrom(raw: String?): Int {
        require(!raw.isNullOrBlank()) { "nothing to import" }
        val incoming = decode(raw)
        require(incoming.isNotEmpty()) { "no channels found in the text" }
        val held = channels.value.map { it.id }.toSet()
        val added = incoming.filter { it.id !in held }.distinctBy { it.id }
        if (added.isNotEmpty()) save(channels.value + added)
        return added.size
    }

    suspend fun latest(): LocalFeed = coroutineScope {
        val results = channels.value.map { channel -> async { readChannel(channel) } }.awaitAll()
        val items = results.filterNotNull().flatten().sortedByDescending { it.second }.map { it.first }.take(MAX_ITEMS)
        LocalFeed(items, results.count { it == null })
    }

    private suspend fun readChannel(channel: LocalChannel): List<Pair<YtFeedItem, String>>? {
        try {
            val url = requireNotNull("$FEED_URL${channel.id}".toHttpUrlOrNull()) { "not a url: $FEED_URL" }
            val xml = client.execute(RequestBuilder().url(url).get().build()).use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.body.utf8String()
            }
            return parseFeed(xml, channel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger?.log(Logger.Level.WARN, "subscription feed failed for ${channel.id}", error)
            return null
        }
    }

    private suspend fun save(list: List<LocalChannel>) {
        settings.putString(KEY, encode(list))
    }

    private companion object {
        const val KEY = "local_subscriptions"
        const val FEED_URL = "https://www.youtube.com/feeds/videos.xml?channel_id="
        const val MAX_ITEMS = 200

        val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        val THUMBNAIL = Regex("<media:thumbnail[^>]*\\burl=\"([^\"]*)\"")

        fun encode(list: List<LocalChannel>): String = buildJsonArray {
            list.forEach { channel ->
                add(
                    buildJsonObject {
                        put("id", channel.id)
                        put("title", channel.title)
                        channel.thumb?.let { put("thumb", it) }
                    },
                )
            }
        }.toString()

        fun decode(raw: String): List<LocalChannel> {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            return array.filterIsInstance<JsonObject>().mapNotNull { entry ->
                val id = entry.stringOrNull("id") ?: return@mapNotNull null
                LocalChannel(id, entry.stringOrNull("title") ?: id, entry.stringOrNull("thumb"))
            }
        }

        fun tag(block: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.let { unescape(it) }

        fun unescape(text: String): String = text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        fun parseFeed(xml: String, channel: LocalChannel): List<Pair<YtFeedItem, String>> =
            ENTRY.findAll(xml).mapNotNull { match ->
                val block = match.groupValues[1]
                val videoId = tag(block, "yt:videoId") ?: return@mapNotNull null
                val title = tag(block, "title") ?: return@mapNotNull null
                val published = tag(block, "published").orEmpty()
                val thumb = THUMBNAIL.find(block)?.groupValues?.get(1)?.let { unescape(it) }
                val item = YtFeedItem(
                    kind = YtFeedItem.KIND_VIDEO,
                    id = videoId,
                    title = title,
                    subtitleLine1 = channel.title,
                    subtitleLine2 = published.take(DATE_LENGTH).ifEmpty { null },
                    channelId = channel.id,
                    channelTitle = channel.title,
                    channelUrl = "/channel/${channel.id}",
                    thumbs = listOfNotNull(thumb?.let { YtThumb(it) }),
                )
                item to published
            }.toList()

        const val DATE_LENGTH = 10
    }
}
