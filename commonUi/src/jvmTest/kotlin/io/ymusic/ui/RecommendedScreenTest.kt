package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.CacheStore
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.desktopEngineCacheStore
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.player.WatchNextSeen
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test

/**
 * Discover's Recommended tab end to end, with no network at all: the tab is empty until something
 * plays, the YouTube player's watch-next report reaches the engine through the app, and the feed is
 * then served back out of the engine's disk cache.
 */
@OptIn(ExperimentalTestApi::class)
class RecommendedScreenTest {

    /** The engine's real disk cache (the desktop shell's own factory) on a wiped temp dir — the
     *  Recommended feed has no other storage. */
    private fun cacheStore(): CacheStore {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ymusic-recommended-ui-test")
        dir.deleteRecursively()
        return desktopEngineCacheStore(dir)
    }

    /** The page's report: YouTube's related-items array, exactly as the MSE player reports it. */
    private val watchNextContents = """
        [{"endScreenVideoRenderer":{"videoId":"N30CFk9fHwg","title":{"simpleText":"A related song"},
        "shortBylineText":{"runs":[{"text":"Trạm Acoustic"}]},"lengthText":{"simpleText":"52:30"},
        "shortViewCountText":{"simpleText":"108K views"},
        "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/N30CFk9fHwg/hqdefault.jpg","width":168,"height":94}]}}}]
    """.trimIndent().replace("\n", "")

    @Test
    fun recommended_starts_empty_then_fills_from_what_played() {
        Locale.setDefault(Locale.ENGLISH)
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                textResponse(request, HOMEPAGE_FIXTURE, "text/html")
            } else {
                textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, cacheStore = cacheStore(), jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val player = StubPlayer()
                val graph = AppGraph(engines = engines, player = player, playerSurface = {})
                setContent { App(graph) }

                onNodeWithActionLabel("Recommended").performClick()

                // Cold start: an invitation to go play something, not an error.
                waitUntilAtLeastOneExists(hasText("Play a few songs", substring = true), 20_000)

                // The WebView page reports what it saw while playing a music video.
                runBlocking { player.watchNextReports.emit(WatchNextSeen("yuuWdm5tBD0", watchNextContents)) }

                // Leaving the tab and coming back rebuilds the feed from the harvest.
                onNodeWithActionLabel("Home").performClick()
                onNodeWithActionLabel("Recommended").performClick()
                waitUntilAtLeastOneExists(hasText("A related song", substring = true), 20_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
