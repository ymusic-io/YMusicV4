package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.yt.extractAccountLibrary
import io.ymusic.dynamicengine.yt.extractChannelDetail
import kmphttp.CookieBuilder
import kmphttp.saveFromResponse
import kmphttp.toHttpUrl
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
 * The signed-in Account tab's library screen, headless, over the real engine bundle + a trimmed
 * LIVE `/feed/you` capture (2026-07-30: richShelfRenderer shelves, playlist lockups with
 * "Private · Playlist" metadata rows and count thumbnail badges).
 */
@OptIn(ExperimentalTestApi::class)
class AccountLibraryScreenTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResource(name)).readText()

    /** account_menu with the active account header — what yt/account parses the card from. */
    private val accountMenuFixture = """
{"Mock Text 007":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
  "header":{"activeAccountHeaderRenderer":{
    "accountName":{"simpleText":"Mock Account"},
    "email":{"simpleText":"mock@test.dev"},
    "channelHandle":{"simpleText":"@mocktest"},
    "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/photo=s88","width":88,"height":88}]}}},
  "sections":[{"multiPageMenuSectionRenderer":{"items":[
    {"compactLinkRenderer":{"title":{"runs":[{"text":"Your channel"}]},
      "navigationEndpoint":{"browseEndpoint":{"browseId":"UCtestchannel1234567890"}}}}
  ]}}]}}}}]}
"""

    /** One domain-wide auth cookie satisfies the engine's cookie-only gate for www AND m jars. */
    private fun signIn(client: CannedHttp) = runBlocking {
        val cookie = CookieBuilder()
            .name("SAPISID")
            .value("test-sapisid")
            .domain("youtube.com")
            .path("/")
            .build()
        client.cookieJar.saveFromResponse("https://www.youtube.com/".toHttpUrl(), listOf(cookie))
    }

    private fun libraryClient(bodies: CopyOnWriteArrayList<String>): CannedHttp {
        val fixture = resource("yt-library-live.json")
        return CannedHttp { request, body ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("account_menu") -> textResponse(request, accountMenuFixture, "application/json")
                else -> {
                    body?.let(bodies::add)
                    textResponse(request, fixture, "application/json")
                }
            }
        }
    }

    @Test
    fun library_page_parses_shelves_privacy_and_counts() {
        Locale.setDefault(Locale.ENGLISH)
        val bodies = CopyOnWriteArrayList<String>()
        val client = libraryClient(bodies)
        signIn(client)
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking {
            try {
                engines.start()
                val page = engines.extractAccountLibrary()
                // The engine leads with the user's playlists (YouTube serves History first).
                assertEquals(
                    listOf("Playlists", "History", "Watch later", "Liked videos"),
                    page.sections.map { it.header?.title },
                )
                assertTrue(bodies.any { it.contains("FElibrary") }, "$bodies")

                // The Playlists / History shelves' View-all app links round-trip via yt/channel.
                assertEquals("/feed/playlists", page.sections[0].header?.ctaDeeplink)
                assertEquals("/feed/history", page.sections[1].header?.ctaDeeplink)

                val playlists = page.sections[0].items
                assertEquals(4, playlists.size)
                assertTrue(playlists.all { it.subtitleLine1 == "Private · Playlist" }, "$playlists")
                // The count comes from the thumbnail badge, NOT the "View full playlist" link row.
                assertTrue(playlists.all { it.itemCount?.contains("video") == true }, "$playlists")
                assertTrue(playlists.all { it.url?.startsWith("/playlist?list=PL") == true }, "$playlists")

                // The Watch later / Liked shelves carry their count and an app link to the full list.
                assertEquals("12 videos", page.sections[2].header?.count)
                assertEquals("/playlist?list=WL", page.sections[2].header?.ctaDeeplink)
                assertEquals("683 videos", page.sections[3].header?.count)
                assertEquals("/playlist?list=LL", page.sections[3].header?.ctaDeeplink)

                // Library shelves collapse to 5 list rows / 3 grid rows behind Show more.
                assertTrue(
                    page.sections.all { it.collapsible && it.collapsedRows == listOf(5, 3) },
                    "${page.sections}",
                )

                // The View-all links round-trip: yt/channel decodes them to their FE browseIds.
                engines.extractChannelDetail("/feed/history")
                assertTrue(bodies.any { it.contains("FEhistory") }, "$bodies")
                engines.extractChannelDetail("/feed/playlists")
                assertTrue(bodies.any { it.contains("FEplaylist_aggregation") }, "$bodies")
            } finally {
                engines.close()
            }
        }
    }

    @Test
    fun revoked_cookies_fall_back_to_the_signin_preface() {
        // The jar passes the cookie gate but the session is revoked server-side: the response
        // wipes LOGIN_INFO (`Set-Cookie: LOGIN_INFO=;…` — the ONE trustworthy remote-logout
        // signal; a merely missing account header must NOT log out, it could be a parser miss).
        // The engine drops the dead auth set and answers signedIn=false; the tab shows the
        // sign-in preface, never a phantom empty profile (seen live 2026-07-31).
        Locale.setDefault(Locale.ENGLISH)
        val wipe = "LOGIN_INFO=; Domain=.youtube.com; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure"
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            when {
                request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei") ->
                    textResponse(request, HOMEPAGE_FIXTURE, "text/html")
                url.contains("account_menu") ->
                    textResponse(request, "{}", "application/json", listOf("Set-Cookie" to wipe))
                else -> textResponse(request, "{}", "application/json")
            }
        }
        signIn(client)
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        try {
            runComposeUiTest {
                setContent { App(AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})) }
                onNodeWithActionLabel("Library").performClick()
                waitUntilAtLeastOneExists(hasText("Sign in with YouTube"), 20_000)
                onAllNodesWithText("Watch history").assertCountEquals(0)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun account_tab_renders_header_nav_rows_and_library_feed() {
        Locale.setDefault(Locale.ENGLISH)
        val bodies = CopyOnWriteArrayList<String>()
        val client = libraryClient(bodies)
        signIn(client)
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runComposeUiTest {
                setContent { App(AppGraph(engines = engines, player = StubPlayer(), playerSurface = {})) }
                onNodeWithActionLabel("Library").performClick()

                // The account card (from account_menu) over the cookie gate.
                waitUntilAtLeastOneExists(hasText("Mock Account"), 20_000)
                onNodeWithText("mock@test.dev").assertExists()

                // The fixed nav rows ("Watch later"/"Liked videos" recur as feed section headers).
                onNodeWithText("My channel").assertExists()
                onNodeWithText("Watch history").assertExists()
                assertTrue(onAllNodesWithText("Watch later").fetchSemanticsNodes().isNotEmpty())
                assertTrue(onAllNodesWithText("Liked videos").fetchSemanticsNodes().isNotEmpty())

                // The feed below leads with the playlists (privacy line) and their View-all CTA.
                waitUntilAtLeastOneExists(hasText("Playlists"), 20_000)
                assertTrue(onAllNodesWithText("View all").fetchSemanticsNodes().isNotEmpty())
                onAllNodes(hasScrollAction()).onFirst()
                    .performScrollToNode(hasText("Private · Playlist", substring = true))
                waitUntilAtLeastOneExists(hasText("Private · Playlist", substring = true), 20_000)

                // The Watch history nav row opens the GENERIC feed page — the FEhistory browse
                // fires and no channel chrome (Subscribe) appears.
                onNodeWithText("Watch history").performClick()
                waitUntilAtLeastOneExists(hasText("Watch history"), 20_000)
                waitUntil(timeoutMillis = 20_000) { bodies.any { it.contains("FEhistory") } }
                onAllNodesWithText("Subscribe").assertCountEquals(0)
            }
        } finally {
            scope.cancel()
            runBlocking { engines.close() }
        }
    }
}
