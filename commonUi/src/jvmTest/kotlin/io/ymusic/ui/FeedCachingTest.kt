package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Home feed must survive navigation: opening the search layer and backing out shows the
 * already-loaded feed again with NO second /browse request (the Discover session cache).
 */
@OptIn(ExperimentalTestApi::class)
class FeedCachingTest {

    @Test
    fun home_is_not_reloaded_after_visiting_search() {
        Locale.setDefault(Locale.ENGLISH)
        val homeJson = checkNotNull(javaClass.classLoader.getResource("yt-home-live.json")).readText()
        val browseCalls = AtomicInteger()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/browse") -> {
                    browseCalls.incrementAndGet()
                    textResponse(request, homeJson, "application/json")
                }
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
                // The startup Trending tab is a /browse too.
                val loadedCalls = browseCalls.get()

                // Into the search layer and straight back out.
                onNodeWithActionLabel("Search").performClick()
                waitUntilAtLeastOneExists(hasText("Search YouTube", substring = true), 10_000)
                onNodeWithActionLabel("Back").performClick()

                // The feed is right back — same loaded page, no new /browse.
                waitUntilAtLeastOneExists(hasText("Mock Text 010", substring = true), 10_000)
                assertEquals(loadedCalls, browseCalls.get(), "returning home must not refetch the feed")
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
