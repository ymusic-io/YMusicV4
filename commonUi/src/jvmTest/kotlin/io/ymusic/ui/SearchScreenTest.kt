package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The search screen end to end, headless (no window, no input automation): Compose UI test drives
 * the semantics tree, the REAL engine bundle runs in QuickJS, and a canned HTTP client serves a
 * trimmed LIVE /search capture (yt-search-live.json, 2026-07-21 — filter groups sit in the header's
 * searchFilterOptionsDialogRenderer, the old sectionList subMenu is an empty husk).
 */
@OptIn(ExperimentalTestApi::class)
class SearchScreenTest {

    @Test
    fun search_suggestions_results_filter_dialog_and_history() {
        Locale.setDefault(Locale.ENGLISH)
        val searchBodies = CopyOnWriteArrayList<String>()
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp","son tung mtp karaoke"]]""", "application/json")
                url.contains("youtubei/v1/search") -> {
                    body?.let(searchBodies::add)
                    textResponse(request, searchJson, "application/json")
                }
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                val coordinator = PlaybackCoordinator(graph, scope)
                setContent { YMusicTheme { SearchScreen(graph, coordinator, onBack = {}) } }

                // Input mode, nothing saved yet.
                onNodeWithText("Type to search").assertExists()

                // Typing surfaces suggestions; tapping one submits it.
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp karaoke"), 20_000)
                onNodeWithText("son tung mtp karaoke").performClick()

                // Results mode: the fixture's items render, the bar shows the query + filter icon.
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)
                onNodeWithText("son tung mtp karaoke").assertExists()
                // The playlist row carries its count/Mix badge — the visual video/playlist separator.
                onNodeWithText("Mix", useUnmergedTree = true).assertExists()

                // The filter dialog lists the server's groups; picking one re-searches with its params.
                onNodeWithActionLabel("Filter").performClick()
                waitUntilAtLeastOneExists(hasText("TYPE"), 10_000)
                onNodeWithText("Under 3 minutes").assertExists()
                onNodeWithText("Relevance").assertExists()
                onNodeWithText("Shorts").performClick()
                waitUntil("filtered search request sent", 20_000) {
                    searchBodies.any { it.contains("EgIQCQ") }
                }
                onNodeWithText("TYPE").assertDoesNotExist()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)

                // Search icon returns to input mode with the query prefilled; clearing shows history.
                onNodeWithActionLabel("Search").performClick()
                onNodeWithActionLabel("Clear").performClick()
                waitUntilAtLeastOneExists(hasText("Clear history"), 10_000)
                onNodeWithText("son tung mtp karaoke").assertExists()

                assertTrue(searchBodies.first().contains("son tung mtp karaoke"), searchBodies.first())
            }
        } finally {
            scope.cancel()
            runBlocking { engines.close() }
        }
    }
}
