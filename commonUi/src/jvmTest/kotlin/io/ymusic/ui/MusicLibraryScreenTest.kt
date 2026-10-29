package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.YtFeedItem
import io.ymusic.dynamicengine.yt.extractMusicLibrary
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
 * The YT Music Library tab (`ytmusic/library`), headless, over the real engine bundle + a trimmed
 * LIVE FEmusic_library_landing capture (2026-07-31: the mixed landing grid — playlists and artists —
 * under the type filter chips and the "Recent activity" sort menu).
 */
@OptIn(ExperimentalTestApi::class)
class MusicLibraryScreenTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)).readText()

    /** Serves the landing fixture for every music browse except the Home tab's FEmusic_home. */
    private fun libraryClient(bodies: CopyOnWriteArrayList<String>): CannedHttp {
        val fixture = resource("yt-music-library-live.json")
        return CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("music.youtube.com") && url.contains("youtubei/v1/browse") -> {
                    body?.let(bodies::add)
                    if (body?.contains("FEmusic_home") == true) {
                        textResponse(request, "{}", "application/json")
                    } else {
                        textResponse(request, fixture, "application/json")
                    }
                }
                else -> textResponse(request, "{}", "application/json")
            }
        }
    }

    @Test
    fun library_page_parses_chips_sort_and_mixed_grid() {
        Locale.setDefault(Locale.ENGLISH)
        val bodies = CopyOnWriteArrayList<String>()
        val engines = DynamicEngineManager(libraryClient(bodies), appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking {
            try {
                engines.start()
                val page = engines.extractMusicLibrary()
                assertTrue(bodies.any { it.contains("FEmusic_library_landing") }, "$bodies")

                // The type filter chips, none applied on the landing, each carrying a token.
                assertEquals(
                    listOf("Playlists", "Songs", "Albums", "Artists", "Mock Text 098", "Podcasts"),
                    page.chips.map { it.title },
                )
                assertTrue(page.chips.none { it.selected }, "${page.chips}")
                assertTrue(page.chips.all { it.token != null }, "${page.chips}")
                // The library wraps its chip row (engine layout intent, stamped per-response).
                assertTrue(page.chipsWrap)

                // The sort menu: current order marked, every option a server reload token.
                assertEquals(
                    listOf("Recent activity", "Mock Text 100", "Recently played"),
                    page.sort.map { it.title },
                )
                assertEquals(listOf(true, false, false), page.sort.map { it.selected })
                assertTrue(page.sort.all { it.token != null }, "${page.sort}")

                // The mixed grid: user playlists (WEB playlist links), the Liked Music auto
                // playlist (a music browse), and artists (channel cards → music browse).
                val items = page.sections.flatMap { it.items }
                assertEquals(5, items.size, "$items")
                val playlist = items.first { it.title == "Mock Text 102" }
                assertEquals("Playlist • Mock User X • 8 tracks", playlist.subtitleLine1)
                assertTrue(playlist.url?.startsWith("/playlist?list=PL") == true, "$playlist")
                assertEquals("/ytmusic/browse?id=VLLM", items.first { it.title == "Liked Music" }.url)
                val artist = items.first { it.title.startsWith("Mock Text 104") }
                assertEquals(YtFeedItem.KIND_CHANNEL, artist.kind)
                assertTrue(artist.url?.startsWith("/ytmusic/browse?id=UC") == true, "$artist")
                assertTrue(page.continuation != null)

                // A chip token round-trips to its sibling feed's browseId…
                engines.extractMusicLibrary(page.chips[0].token)
                assertTrue(bodies.any { it.contains("FEmusic_liked_playlists") }, "$bodies")
                // …and a sort token rides the normal server-continuation path.
                val sortToken = checkNotNull(page.sort[1].token)
                engines.extractMusicLibrary(sortToken)
                assertTrue(bodies.any { it.contains(sortToken) }, "$bodies")
            } finally {
                engines.close()
            }
        }
    }

    @Test
    fun library_tab_renders_grid_chips_and_sort_menu() {
        Locale.setDefault(Locale.ENGLISH)
        val bodies = CopyOnWriteArrayList<String>()
        val engines = DynamicEngineManager(libraryClient(bodies), appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                val coordinator = PlaybackCoordinator(graph, scope)
                setContent {
                    YMusicTheme {
                        YtMusicScreen(
                            graph,
                            coordinator,
                            sessions = mutableMapOf(),
                            localeEpoch = 0,
                            scope = scope,
                            signedIn = true,
                        )
                    }
                }
                onNodeWithText("Library").performClick()

                // The landing grid: playlist cards with their privacy/owner line and artist cards.
                waitUntilAtLeastOneExists(hasText("Mock Text 102", substring = true), 20_000)
                onNodeWithText("Playlist • Mock User X • 8 tracks", substring = true).assertExists()
                waitUntilAtLeastOneExists(hasText("Mock Text 104", substring = true), 20_000)

                // The filter chips + the sort trigger naming the current order.
                onNodeWithText("Songs").assertExists()
                onNodeWithText("Recent activity").assertExists()

                // A chip tap re-browses the sibling feed.
                onNodeWithText("Playlists").performClick()
                waitUntil(timeoutMillis = 20_000) { bodies.any { it.contains("FEmusic_liked_playlists") } }

                // The sort menu opens with the current option marked; picking another re-serves
                // the page through its continuation.
                onNodeWithText("Recent activity").performClick()
                waitUntilAtLeastOneExists(hasText("Recently played"), 20_000)
                onNodeWithText("Recently played").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    bodies.any { it.contains("\"continuation\"") && !it.contains("browseId") }
                }
            }
        } finally {
            scope.cancel()
            runBlocking { engines.close() }
        }
    }
}
