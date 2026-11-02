package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole app shell (App()) navigation, headless: search overlay → results → tapping a playlist
 * must show its detail page IMMEDIATELY (regression: the search overlay rendered above the pushed
 * detail, which only appeared after backing out of search).
 */
@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {

    @Test
    fun search_result_playlist_tap_opens_detail_immediately() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val mixJson = checkNotNull(javaClass.classLoader.getResource("yt-mix-next-live.json")).readText()
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                // The tapped result is the Mix lockup (RDEM… id) → its detail loads via /next.
                url.contains("youtubei/v1/next") -> textResponse(request, mixJson, "application/json")
                // Discover's home browse — an empty page is fine, the test never looks at it.
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                onNodeWithActionLabel("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()

                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)
                onNodeWithText("Mix - Sơn Tùng M-TP", substring = true).performClick()

                // The detail page is on screen right away — its bar title and the /next panel header.
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Adele - Rolling in the Deep (Official Music Video)"), 20_000)

                // Back pops the detail onto the still-loaded SEARCH RESULTS (not Home): the
                // results bar title and the tapped result are right there, no reload.
                onNodeWithActionLabel("Back").performClick()
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 10_000)

                // Back again leaves search for the Discover shell.
                onNodeWithActionLabel("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Trending music"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun search_icon_on_a_detail_page_brings_the_existing_results_to_front() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val mixJson = checkNotNull(javaClass.classLoader.getResource("yt-mix-next-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                url.contains("youtubei/v1/next") -> textResponse(request, mixJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                setContent { App(graph) }

                // Search → results → a result's detail page.
                onNodeWithActionLabel("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)
                onNodeWithText("Mix - Sơn Tùng M-TP", substring = true).performClick()
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)

                // The detail bar's search icon raises the EXISTING results layer (SINGLE_TOP):
                // the submitted query and its loaded results are right there — no blank input.
                onNodeWithActionLabel("Search").performClick()
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 10_000)

                // The stack reordered (not grew): back lands on the detail page, then Home.
                onNodeWithActionLabel("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)
                onNodeWithActionLabel("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Trending music"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun each_section_keeps_its_own_stack_across_drawer_switches() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("suggestqueries") ->
                    textResponse(request, """["son tung",["son tung mtp"]]""", "application/json")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})
                // Hoisted so the test can switch sections the way a drawer tap does — over the
                // results layer the drawer only opens by touch edge-swipe, which jvm can't drive.
                val section = androidx.compose.runtime.mutableStateOf(AppSection.Discover)
                setContent { App(graph, section) }

                // Discover: search → submitted results.
                onNodeWithActionLabel("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("son tung")
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 20_000)
                onNodeWithText("son tung mtp").performClick()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)

                // → Library: a lateral switch, NOT a push. Library shows its own surface (no
                // YouTube results), and no YouTube search action — Library's search is a
                // different, local-files flow.
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("Library"), 10_000)
                onNodeWithText("Mix - Sơn Tùng M-TP", substring = true).assertDoesNotExist()
                onNodeWithActionLabel("Search").assertDoesNotExist()

                // → Discover: the section restores its RETAINED stack — the search results are
                // right there, query and all, not a bare Discover feed.
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 10_000)
                waitUntilAtLeastOneExists(hasText("son tung mtp"), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun a_refresh_request_refetches_the_visible_feed() {
        Locale.setDefault(Locale.ENGLISH)
        val browseCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/browse") -> {
                    browseCalls.incrementAndGet()
                    textResponse(request, "{}", "application/json")
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

                // Home loads once, then the desktop F5 path (AppGraph.requestRefresh) refetches it.
                waitUntil(timeoutMillis = 20_000) { browseCalls.get() == 1 }
                graph.requestRefresh()
                waitUntil(timeoutMillis = 10_000) { browseCalls.get() == 2 }
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun library_search_filters_locally_and_hands_off_online() {
        Locale.setDefault(Locale.ENGLISH)
        val searchJson = checkNotNull(javaClass.classLoader.getResource("yt-search-live.json")).readText()
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/search") -> textResponse(request, searchJson, "application/json")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        runBlocking {
            val root = store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music"))
            store.upsertItem(
                root.id,
                MediaItemDraft(
                    uri = Uri.parse("file:///Music/tinh-ta.mp3"),
                    title = "Tinh Ta Bien Bac Dong Xanh",
                    mimeType = "audio/mpeg",
                    fileSize = 1L,
                    lastModified = 1L,
                    artist = "Bao Anh",
                ),
            )
            store.upsertItem(
                root.id,
                MediaItemDraft(
                    uri = Uri.parse("file:///Music/other.mp3"),
                    title = "Something Else",
                    mimeType = "audio/mpeg",
                    fileSize = 2L,
                    lastModified = 1L,
                    artist = "Anh Tu",
                ),
            )
        }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {}, mediaStore = store)
                val section = androidx.compose.runtime.mutableStateOf(AppSection.Library)
                setContent { App(graph, section) }

                // Library's bar has its OWN search (local files, not YouTube).
                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)
                onNodeWithActionLabel("Search").performClick()
                onNode(hasSetTextAction()).performTextInput("anh")

                // Filter-as-you-type: the matching song, its artist section, and the online CTA.
                waitUntilAtLeastOneExists(hasText("Tinh Ta Bien Bac Dong Xanh", substring = true), 10_000)
                onNodeWithText("Search “anh” online").assertExists()
                onNodeWithText("Something Else").assertDoesNotExist()

                // An artist match drills into its songs in place (the song row also carries
                // "Bao Anh" as its subtitle — the artist ROW is the one with the song count).
                onNode(hasText("Bao Anh") and hasText("1 song")).performClick()
                waitUntilAtLeastOneExists(hasText("Tinh Ta Bien Bac Dong Xanh", substring = true), 10_000)

                // The online CTA jumps to Discover and runs the query as a YouTube search.
                // Two Backs while drilled in (input bar + drill header); either exits the drill.
                onAllNodesWithActionLabel("Back").onFirst().performClick()
                onNodeWithText("Search “anh” online").performClick()
                waitUntilAtLeastOneExists(hasText("Mix - Sơn Tùng M-TP", substring = true), 20_000)
                assertEquals(AppSection.Discover, section.value)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun library_keeps_its_tab_and_folder_across_section_switches() {
        Locale.setDefault(Locale.ENGLISH)
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                else -> textResponse(request, "{}", "application/json")
            }
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        runBlocking {
            val root = store.upsertBucket(BucketDraft(Uri.parse("file:///RootFolder"), "RootFolder"))
            store.upsertBucket(BucketDraft(Uri.parse("file:///RootFolder/SubFolder"), "SubFolder", parentId = root.id))
            val song = store.upsertItem(
                root.id,
                MediaItemDraft(
                    uri = Uri.parse("file:///RootFolder/song-a.mp3"),
                    title = "Local Song A",
                    mimeType = "audio/mpeg",
                    fileSize = 1L,
                    lastModified = 1L,
                    artist = "ArtistX",
                ),
            )
            val playlist = store.createPlaylist("MyList")
            store.addToPlaylist(playlist.id, listOf(song))
        }
        try {
            runComposeUiTest {
                val graph = AppGraph(engines = engines, player = StubPlayer(), playerSurface = {}, mediaStore = store)
                val section = androidx.compose.runtime.mutableStateOf(AppSection.Library)
                setContent { App(graph, section) }

                // Browse into RootFolder — its child folder shows.
                waitUntilAtLeastOneExists(hasText("RootFolder"), 20_000)
                onNodeWithText("RootFolder").performClick()
                waitUntilAtLeastOneExists(hasText("SubFolder"), 10_000)

                // Away to Discover and back: still INSIDE RootFolder, not reset to the root.
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Trending music"), 20_000)
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("SubFolder"), 10_000)

                // Pick the Songs tab, leave, come back: still on Songs.
                onNodeWithText("Songs").performClick()
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Trending music"), 10_000)
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)

                // And Folders kept its position through the tab switch too.
                onNodeWithText("Folders").performClick()
                waitUntilAtLeastOneExists(hasText("SubFolder"), 10_000)

                // Open a playlist; away and back — still inside it (its songs, not the list).
                onNodeWithText("Playlists").performClick()
                waitUntilAtLeastOneExists(hasText("MyList"), 10_000)
                onNodeWithText("MyList").performClick()
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Trending music"), 10_000)
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)

                // Same for a grouped tab: open an artist, away and back — still inside.
                onNodeWithText("Artists").performClick()
                waitUntilAtLeastOneExists(hasText("ArtistX"), 10_000)
                onNodeWithText("ArtistX").performClick()
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)
                section.value = AppSection.Discover
                waitUntilAtLeastOneExists(hasText("Trending music"), 10_000)
                section.value = AppSection.Library
                waitUntilAtLeastOneExists(hasText("Local Song A", substring = true), 10_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
