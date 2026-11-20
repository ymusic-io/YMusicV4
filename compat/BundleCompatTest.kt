package io.ymusic.dynamicengine.compat

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.StaticJsSource
import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.dynamicengine.testEngineDispatcher
import io.ymusic.dynamicengine.yt.extractChannelDetail
import io.ymusic.dynamicengine.yt.extractHomeFeed
import io.ymusic.dynamicengine.yt.extractPlaylistDetail
import io.ymusic.dynamicengine.yt.extractTrendingMusicFeed
import io.ymusic.dynamicengine.yt.search
import io.ymusic.dynamicengine.yt.searchSuggestions
import io.ymusic.dynamicengine.yt.watchNext
import kmphttp.Request
import kmphttp.Response
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Today's production engine.js / player.js on the host of an already-shipped tag: compat/run.sh
 * copies this file into that tag's checkout. It may only call API that exists at every tag from
 * compat/floor.txt on. Assertions stay loose — decodes, non-empty, typed errors — content
 * correctness is HEAD's own tests' job.
 */
class BundleCompatTest {

    private val bundleDir = File("build/compat")

    private fun bundle(name: String): String {
        val file = File(bundleDir, name)
        check(file.isFile) { "missing ${file.absolutePath} — run compat/run.sh, it stages today's bundles" }
        return file.readText()
    }

    private val engineJs = bundle("engine.js")
    private val playerJs = bundle("player.js")

    /** Every versionCode this tag shipped with (Android, desktop, iOS); JS gates on versions.app. */
    private val appVersions: List<Int> =
        bundle("app-versions").split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }.distinct()

    private fun jsonResponse(body: String) = CannedResponse.ofText(body, headers = listOf("Content-Type" to "application/json"))

    private suspend fun forEachHost(
        handler: suspend (Request) -> Response,
        block: suspend (DynamicEngineManager) -> Unit,
    ) {
        for (appVersion in appVersions) {
            val client = FakeHttpClient { request ->
                val url = request.url.toString()
                if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                    CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
                } else {
                    handler(request)
                }
            }
            val manager = DynamicEngineManager(client, appVersion = appVersion, jsSource = StaticJsSource(engineJs))
            manager.start()
            try {
                block(manager)
            } catch (error: Throwable) {
                throw AssertionError("host versionCode $appVersion: ${error.message}", error)
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun search_decodes() = runTest {
        forEachHost({ jsonResponse(SEARCH_FIXTURE).build(it) }) { manager ->
            val page = manager.search(query = "son tung mtp")
            assertTrue(page.sections.isNotEmpty() && page.sections[0].items.isNotEmpty(), "$page")
        }
    }

    @Test
    fun watch_next_decodes() = runTest {
        forEachHost({ jsonResponse(NEXT_FIXTURE).build(it) }) { manager ->
            val page = manager.watchNext(videoId = "yuuWdm5tBD0")
            assertTrue(page.sections.isNotEmpty() && page.sections[0].items.isNotEmpty(), "$page")
            assertNotNull(page.autoplayVideoId, "$page")
        }
    }

    @Test
    fun home_feed_decodes() = runTest {
        forEachHost({ jsonResponse(BROWSE_FIXTURE).build(it) }) { manager ->
            val page = manager.extractHomeFeed()
            assertTrue(page.sections.isNotEmpty() && page.sections[0].items.isNotEmpty(), "$page")
        }
    }

    @Test
    fun channel_detail_decodes() = runTest {
        forEachHost({ jsonResponse(CHANNEL_FIXTURE).build(it) }) { manager ->
            val page = manager.extractChannelDetail("/channel/UCchannelchannelchannel0")
            assertNotNull(page.pageHeader, "$page")
            assertTrue(page.pageTabs.isNotEmpty(), "$page")
        }
    }

    @Test
    fun playlist_detail_decodes() = runTest {
        forEachHost({ jsonResponse(MIX_NEXT_FIXTURE).build(it) }) { manager ->
            val page = manager.extractPlaylistDetail("/playlist?list=RDEMtestmix")
            assertNotNull(page.pageHeader, "$page")
            assertTrue(page.sections.isNotEmpty() && page.sections[0].items.isNotEmpty(), "$page")
        }
    }

    @Test
    fun trending_music_decodes() = runTest {
        forEachHost({ jsonResponse(MUSIC_CHANNEL_FIXTURE).build(it) }) { manager ->
            val page = manager.extractTrendingMusicFeed()
            assertTrue(page.sections.isNotEmpty(), "$page")
        }
    }

    @Test
    fun search_suggestions_decode() = runTest {
        val jsonp = """window.google.ac.h(["son tung",[["sơn tùng m-tp",0,[512,433]]],{"k":1,"q":"x"}])"""
        forEachHost({ CannedResponse.ofText(jsonp).build(it) }) { manager ->
            assertTrue(manager.searchSuggestions("son tung").isNotEmpty())
        }
    }

    @Test
    fun network_failure_is_a_typed_error() = runTest {
        forEachHost({ throw okio.IOException("connect refused") }) { manager ->
            assertFailsWith<EngineError.Network> { manager.search(query = "anything") }
        }
    }

    @Test
    fun upstream_failure_is_a_typed_error() = runTest {
        forEachHost({ CannedResponse.ofText("""{"error":{"code":400}}""", status = 400).build(it) }) { manager ->
            assertFailsWith<EngineError> { manager.extractHomeFeed() }
        }
    }

    /** Boot + health check only: every player command past that needs `player/init`, which scrapes
     *  the live homepage and base.js — out of reach of canned HTTP. */
    @Test
    fun player_boots() = runTest {
        for (appVersion in appVersions) {
            var backendVersion: Int? = null
            val player = try {
                withContext(testEngineDispatcher) {
                    DynamicEnginePlayer.create(
                        FakeHttpClient(CannedResponse.ofText("")),
                        appVersion = appVersion,
                        dispatcher = testEngineDispatcher,
                        engineJs = playerJs,
                        onBackendVersion = { backendVersion = it },
                    )
                }
            } catch (error: Throwable) {
                throw AssertionError("host versionCode $appVersion: ${error.message}", error)
            }
            player.close()
            assertNotNull(backendVersion, "host versionCode $appVersion: no health-checked revision")
        }
    }
}
