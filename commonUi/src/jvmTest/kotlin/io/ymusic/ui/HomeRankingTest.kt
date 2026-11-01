package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home feed music-first ranking end to end over the REAL live home capture (2026-07-22, lockup
 * items): the raw feed leads with a travel vlog (verified channel); the rendered feed must lead
 * with the music videos. Regression for the lockup-tagging gap — classic-renderer-only badge
 * parsing left home lockups untagged and the ranking a no-op.
 */
@OptIn(ExperimentalTestApi::class)
class HomeRankingTest {

    @Test
    fun home_renders_music_videos_above_the_travel_vlog() {
        Locale.setDefault(Locale.ENGLISH)
        val homeJson = checkNotNull(javaClass.classLoader.getResource("yt-home-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/browse") -> textResponse(request, homeJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }
                onNodeWithActionLabel("Home").performClick()

                waitUntilAtLeastOneExists(hasText("Mock Text 010", substring = true), 20_000)
                val mix = onNodeWithText("Mock Text 036", substring = true).fetchSemanticsNode()
                val musicVid = onNodeWithText("Mock Text 010", substring = true).fetchSemanticsNode()
                val vlog = onNodeWithText("Mock Text 003", substring = true).fetchSemanticsNode()
                val artistTrack = onNodeWithText("Mock Text 015", substring = true).fetchSemanticsNode()
                // Grid cells in the same row share y — compare reading order (row, then column).
                fun order(n: androidx.compose.ui.semantics.SemanticsNode) =
                    n.positionInRoot.y * 100_000 + n.positionInRoot.x
                assertTrue(order(mix) < order(musicVid), "the Mix outranks even music videos")
                assertTrue(order(musicVid) < order(vlog), "music video must render above the travel vlog")
                assertTrue(order(artistTrack) < order(vlog), "artist-channel music must render above the travel vlog")
                // The 3 music videos are announced as such.
                assertTrue(onAllNodes(hasText("Music video,", substring = true)).fetchSemanticsNodes().size >= 3)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
