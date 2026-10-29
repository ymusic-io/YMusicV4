package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.player.PlayableItem
import kmphttp.Request
import kmphttp.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playlist / channel detail screens, headless, over the real engine bundle + trimmed LIVE browse
 * captures (2026-07-21: pageHeaderViewModel headers, tabRenderer tabs, lockup playlist items).
 */
@OptIn(ExperimentalTestApi::class)
class DetailScreensTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)).readText()

    private fun withEngine(
        handler: suspend (request: Request, body: String?) -> Response,
        block: (engines: DynamicEngineManager, player: StubPlayer, scope: CoroutineScope) -> Unit,
    ) {
        Locale.setDefault(Locale.ENGLISH)
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                textResponse(request, HOMEPAGE_FIXTURE, "text/html")
            } else {
                handler(request, body)
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(engines, StubPlayer(), scope)
        } finally {
            scope.cancel()
            runBlocking { engines.close() }
        }
    }

    @Test
    fun playlist_detail_header_play_in_context_and_shuffle() {
        val bodies = CopyOnWriteArrayList<String>()
        val fixture = resource("yt-playlist-live.json")
        withEngine({ request, body ->
            body?.let(bodies::add)
            textResponse(request, fixture, "application/json")
        }) { engines, player, scope ->
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = player, playerSurface = {})
                val coordinator = PlaybackCoordinator(graph, scope)
                val item = YtFeedItem(
                    kind = YtFeedItem.KIND_PLAYLIST,
                    id = "PLL6O4RnjOZCVbxKNDznzuBYtI9cQfFmkY",
                    title = "fallback title",
                )
                val session = PlaylistSession(graph, scope, "/playlist?list=PLL6O4RnjOZCVbxKNDznzuBYtI9cQfFmkY", item, parentLog = null)
                setContent {
                    YMusicTheme {
                        PlaylistDetailScreen(coordinator, session, onBack = {}, onSearch = {})
                    }
                }

                // Bar title + the response header replace the fallback item metadata.
                onNodeWithText("Playlist").assertExists()
                waitUntilAtLeastOneExists(hasText("Những ca khúc hay nhất của Sơn Tùng M-TP"), 20_000)
                onNodeWithText("Playlist · 31 videos · 1,007,162 views").assertExists()
                assertTrue(bodies.first().contains("VLPLL6O4RnjOZCVbxKNDznzuBYtI9cQfFmkY"), bodies.first())

                // Tapping a video plays the playlist as the queue from that video.
                onNodeWithText("Nắng Ấm Xa Dần", substring = true).performClick()
                val queue = checkNotNull(player.lastQueue)
                assertEquals(4, queue.size, "$queue")
                assertEquals(1, player.lastPlayIndex)
                assertEquals(false, player.lastShuffle)
                assertTrue(queue.all { it is PlayableItem.YtMedia }, "$queue")

                // The FAB shuffles the whole playlist, starting anywhere in it.
                onNodeWithText("Shuffle").performClick()
                assertEquals(true, player.lastShuffle)
                assertTrue(player.lastPlayIndex in 0..3, "${player.lastPlayIndex}")
            }
        }
    }

    @Test
    fun mix_detail_loads_via_watch_next_and_hides_related() {
        val bodies = CopyOnWriteArrayList<String>()
        val fixture = resource("yt-mix-next-live.json")
        val window2 = resource("yt-mix-next-window2-live.json")
        withEngine({ request, body ->
            if (request.url.toString().contains("youtubei")) body?.let(bodies::add)
            // The load-more window call re-seeds around the first window's LAST video ("Hello").
            val isWindow2 = body != null && body.contains("YQHsXMglC9A")
            textResponse(request, if (isWindow2) window2 else fixture, "application/json")
        }) { engines, player, scope ->
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = player, playerSurface = {})
                val coordinator = PlaybackCoordinator(graph, scope)
                val item = YtFeedItem(
                    kind = YtFeedItem.KIND_PLAYLIST,
                    id = "RDrYEDA3JcQqw",
                    title = "Mix - Adele",
                )
                // The engine-composed url; the RD-embedded seed video is the ENGINE's to derive.
                val session = PlaylistSession(graph, scope, "/playlist?list=RDrYEDA3JcQqw", item, parentLog = null)
                setContent {
                    YMusicTheme {
                        PlaylistDetailScreen(coordinator, session, onBack = {}, onSearch = {})
                    }
                }

                // The panel header + its videos render; the /next request carried the mix triple.
                waitUntilAtLeastOneExists(hasText("Mix - Adele - Rolling in the Deep (Official Music Video)"), 20_000)
                onNodeWithText("Adele - Set Fire to the Rain", substring = true).assertExists()
                val next = bodies.first { it.contains("RDrYEDA3JcQqw") }
                assertTrue(next.contains(""""videoId":"rYEDA3JcQqw""""), next)
                assertTrue(next.contains(""""params":"wAEB""""), next)

                // The watch page's related videos must NOT leak into the playlist detail.
                onNodeWithText("Greatest H", substring = true).assertDoesNotExist()

                // Tapping a panel video queues the whole mix from that video.
                onNodeWithText("Adele - Someone Like You", substring = true).performClick()
                val queue = checkNotNull(player.lastQueue)
                assertEquals(5, queue.size, "$queue")
                assertEquals(2, player.lastPlayIndex)

                // The infinite mix pages: Show more slides the window and appends ONLY the fresh
                // items (the overlap video is not duplicated). The token is the engine's own.
                onNodeWithText("Show more").performClick()
                waitUntilAtLeastOneExists(hasText("Rihanna - Diamonds", substring = true), 20_000)
                onNodeWithText("OneRepublic - Counting Stars", substring = true).assertExists()
                onAllNodesWithText("Adele - Hello", substring = true).assertCountEquals(1)
                val window2Body = bodies.first { it.contains("YQHsXMglC9A") }
                assertTrue(window2Body.contains(""""playlistIndex":4"""), window2Body)
            }
        }
    }

    @Test
    fun channel_detail_header_tabs_and_playlist_open() {
        val bodies = CopyOnWriteArrayList<String>()
        val home = resource("yt-channel-live.json")
        val playlists = resource("yt-channel-playlists-live.json")
        withEngine({ request, body ->
            body?.let(bodies::add)
            // Only tab switches carry params; the initial channel browse has none.
            val fixture = if (body != null && body.contains(""""params":""")) playlists else home
            textResponse(request, fixture, "application/json")
        }) { engines, player, scope ->
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = player, playerSurface = {})
                val coordinator = PlaybackCoordinator(graph, scope)
                val opened = CopyOnWriteArrayList<YtFeedItem>()
                val item = YtFeedItem(
                    kind = YtFeedItem.KIND_CHANNEL,
                    id = "UClyA28-01x4z60eWQ2kiNbA",
                    title = "Sơn Tùng M-TP Official",
                )
                val session = ChannelSession(graph, scope, item, initialUrl = "/channel/UClyA28-01x4z60eWQ2kiNbA", parentLog = null)
                setContent {
                    YMusicTheme {
                        ChannelDetailScreen(
                            coordinator, session,
                            onBack = {}, onSearch = {}, onOpen = { opened.add(it) },
                        )
                    }
                }

                // Header + server tabs (the expandable search tab is dropped).
                waitUntilAtLeastOneExists(hasText("11.8M subscribers · 221 videos"), 20_000)
                onNodeWithText("Subscribe").assertExists()
                waitUntilAtLeastOneExists(hasText("Home"), 5_000)
                onNodeWithText("Releases").assertExists()
                onNodeWithText("Search").assertDoesNotExist()

                // Switching to Playlists re-browses with the tab params and lists playlists.
                onNodeWithText("Playlists").performClick()
                waitUntilAtLeastOneExists(hasText("7 Minute Stage", substring = true), 20_000)
                assertTrue(bodies.any { it.contains(""""params":""") }, "$bodies")

                // Tapping a playlist opens it through onOpen.
                onNodeWithText("7 Minute Stage", substring = true).performClick()
                assertEquals(1, opened.size, "$opened")
                assertEquals(YtFeedItem.KIND_PLAYLIST, opened.first().kind)
            }
        }
    }
}
