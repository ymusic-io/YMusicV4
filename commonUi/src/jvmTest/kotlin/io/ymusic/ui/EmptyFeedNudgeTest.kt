package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.extractHomeFeed
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anonymous Home feed IS empty (docs/innertube/auth.md: only a feedNudgeRenderer comes back) —
 * the empty state must show YouTube's own localized explainer instead of the app's generic message.
 * Over the real engine bundle + a trimmed LIVE anonymous FEwhat_to_watch capture (2026-07-31).
 */
@OptIn(ExperimentalTestApi::class)
class EmptyFeedNudgeTest {

    private fun nudgeClient(): CannedHttp {
        val fixture = checkNotNull(javaClass.classLoader.getResource("yt-home-anon-nudge-live.json")).readText()
        return CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/browse") -> textResponse(request, fixture, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
    }

    @Test
    fun the_nudge_reaches_the_wire_as_the_pages_empty_explainer() {
        Locale.setDefault(Locale.ENGLISH)
        val engines = DynamicEngineManager(nudgeClient(), appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking {
            try {
                engines.start()
                val page = engines.extractHomeFeed()
                assertTrue(page.sections.all { it.items.isEmpty() }, "${page.sections}")
                assertEquals("Try searching to get started", page.emptyTitle)
                assertEquals(
                    "Start watching videos to help us build a feed of videos you'll love.",
                    page.emptyMessage,
                )
            } finally {
                engines.close()
            }
        }
    }

    @Test
    fun the_empty_home_shows_youtubes_words_not_the_generic_message() {
        Locale.setDefault(Locale.ENGLISH)
        val engines = DynamicEngineManager(nudgeClient(), appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                setContent { App(AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})) }
                onNodeWithActionLabel("Home").performClick()
                waitUntilAtLeastOneExists(hasText("Try searching to get started"), 20_000)
                onNodeWithText("Start watching videos to help us build a feed of videos you'll love.")
                    .assertExists()
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
