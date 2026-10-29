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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine-composed CTA deeplinks: the parser contract, and the "View all" click end to end. */
@OptIn(ExperimentalTestApi::class)
class DeeplinkTest {

    @Test
    fun parser_routes_on_the_path_and_keeps_the_url_opaque() {
        assertEquals(Deeplink.Playlist("/playlist?list=PLabc123"), parseDeeplink("/playlist?list=PLabc123"))
        // The query round-trips VERBATIM — engine-private state (a Mix seed, encoded params)
        // survives untouched; the app never interprets it.
        assertEquals(
            Deeplink.Playlist("/playlist?list=RDabcdefghijk&v=abcdefghijk"),
            parseDeeplink("/playlist?list=RDabcdefghijk&v=abcdefghijk"),
        )
        assertEquals(Deeplink.Channel("/channel/UCxyz"), parseDeeplink("/channel/UCxyz"))
        assertEquals(
            Deeplink.Channel("/channel/UCxyz?params=EgZ2aWRlb3M%3D"),
            parseDeeplink("/channel/UCxyz?params=EgZ2aWRlb3M%3D"),
        )
        assertNull(parseDeeplink("/playlist"))
        assertNull(parseDeeplink("/channel/"))
        assertNull(parseDeeplink("/watch?v=abc"), "a newer bundle's scheme is inert, not a crash")
        assertNull(parseDeeplink("garbage"))
    }

    @Test
    fun view_all_cta_on_a_home_shelf_opens_the_playlist_detail() {
        Locale.setDefault(Locale.ENGLISH)
        // A home feed whose shelf "View all" targets a playlist (the live channel-shelf shape).
        val homeJson = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
            {"sectionListRenderer":{"contents":[{"shelfRenderer":{"title":{"runs":[{"text":"Sky Decade"}]},
            "endpoint":{"browseEndpoint":{"browseId":"VLPLBcAa442MLArUZuKY29nwsXj39mt3FL5W"}},
            "content":{"expandedShelfContentsRenderer":{"items":[{"videoRenderer":{"videoId":"AAAAAAAAAAA",
            "title":{"runs":[{"text":"Shelf video"}]},"ownerText":{"runs":[{"text":"Chan"}]},
            "lengthText":{"simpleText":"3:00"}}}]}}}}]}}}}]}}}
        """.trimIndent().replace("\n", "")
        val playlistJson = checkNotNull(javaClass.classLoader.getResource("yt-playlist-live.json")).readText()
        val bodies = CopyOnWriteArrayList<String>()
        val client = CannedHttp { request, body ->
            val url = request.url.toString()
            body?.let(bodies::add)
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("youtubei/v1/browse") && body?.contains("VLPL") == true ->
                    textResponse(request, playlistJson, "application/json")
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

                waitUntilAtLeastOneExists(hasText("Sky Decade"), 20_000)
                onNodeWithActionLabel("View all").performClick()

                // Playlist detail opens, seeded with the shelf title, then loads via VL browse.
                waitUntilAtLeastOneExists(hasText("Playlist"), 10_000)
                waitUntilAtLeastOneExists(hasText("Những ca khúc hay nhất", substring = true), 20_000)
                assertTrue(
                    bodies.any { it.contains(""""browseId":"VLPLBcAa442MLArUZuKY29nwsXj39mt3FL5W"""") },
                    "$bodies",
                )
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
