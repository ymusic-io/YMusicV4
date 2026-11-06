package io.ymusic.dynamicengine.yt

import io.ymusic.dynamicengine.CannedResponse
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.EngineError
import io.ymusic.dynamicengine.FakeHttpClient
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.dynamicengine.readAll
import kmpcommon.io.asAsyncSource
import kmphttp.loadForRequest
import kmphttp.parseCookie
import kmphttp.saveFromResponse
import kmphttp.toHttpUrlOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The yt scraping commands end to end over the PRODUCTION bundle: Kotlin args → engine JS →
 * (canned) InnerTube HTTP → parser → typed page back. Fixtures are trimmed captures of live
 * responses (YtFixtures.kt), so the parser is exercised against real current shapes.
 */
class YtCommandsTest {

    /** The engine serves the request body over a lazy pipe that dies with the exchange — capture
     *  it inside the handler, where it is still readable. */
    private var sentBody: String? = null

    private suspend fun manager(
        cacheStore: io.ymusic.dynamicengine.CacheStore? = null,
        settings: io.ymusic.settings.SettingsStore? = null,
        jar: kmphttp.CookieJar? = null,
        handler: suspend (kmphttp.Request) -> kmphttp.Response,
    ): Pair<DynamicEngineManager, FakeHttpClient> {
        sentBody = null
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                // The session bootstrap's homepage fetch — serve the ytcfg so scraping succeeds.
                CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                sentBody = request.body?.readAll()?.decodeToString()
                handler(request)
            }
        }
        if (jar != null) client.cookieJar = jar
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = cacheStore, settings = settings)
        m.start()
        return m to client
    }

    /** A fresh cache store for the surfaces that persist (the Recommended harvest): an in-memory
     *  SQLite index over a wiped temp blob dir, on a ticking clock so writes order deterministically. */
    private fun cacheStore(): io.ymusic.dynamicengine.DiskCacheStore {
        val dir = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ymusic-recommended-test"
        okio.FileSystem.SYSTEM.deleteRecursively(dir)
        var tick = 1_000L
        return io.ymusic.dynamicengine.DiskCacheStore(
            io.ymusic.dynamicengine.testCacheDriver(),
            dir,
            okio.FileSystem.SYSTEM,
            now = { tick++ },
        )
    }

    /** A host settings store whose app language is already seeded (the shell's job). */
    private suspend fun viSettings(): io.ymusic.settings.SettingsStore =
        io.ymusic.settings.InMemorySettingsStore().also { it.putString("app.uiLanguage", "vi") }

    private fun jsonResponse(body: String) = CannedResponse.ofText(
        body,
        headers = listOf("Content-Type" to "application/json"),
    )

    @Test
    fun cancelling_an_inflight_command_must_not_wedge_later_commands() = runTest {
        // Repro of the Android zombie state (2026-07-23): a command cancelled mid-flight (a pager
        // refresh / suggestion debounce cancelling its predecessor) must not leave the engine's
        // shared paths (session, cookie pipe) held — later commands must still complete.
        val firstRequestStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var slowServed = false
        val (m, _) = manager { request ->
            if (!slowServed) {
                slowServed = true
                firstRequestStarted.complete(Unit)
                gate.await() // hold the first search's HTTP exchange open until cancelled
            }
            jsonResponse(SEARCH_FIXTURE).build(request)
        }
        try {
            val stuck = backgroundScope.launch { m.search(query = "held") }
            firstRequestStarted.await()
            stuck.cancel() // cancel mid-HTTP, inside the engine command
            stuck.join()
            gate.complete(Unit)

            // The engine must serve the next command normally — pre-fix this hung forever.
            // Real-time timeout: the engine works on its own thread, outside the virtual clock.
            val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(30_000) { m.search(query = "son tung mtp") }
            }
            assertTrue(page.sections.isNotEmpty(), "$page")
        } finally {
            m.close()
        }
    }

    @Test
    fun search_parses_videos_channels_and_lockups() = runTest {
        val (m, client) = manager { jsonResponse(SEARCH_FIXTURE).build(it) }
        try {
            val page = m.search(query = "son tung mtp")

            val req = assertNotNull(client.lastRequest)
            assertEquals("POST", req.method)
            assertTrue(req.url.toString().contains("/youtubei/v1/search?prettyPrint=false"), req.url.toString())
            assertEquals("1", req.headers["X-Youtube-Client-Name"])
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""query":"son tung mtp""""), sent)
            assertTrue(sent.contains(""""clientName":"WEB""""), sent)

            assertEquals(2, page.sections.size, "flat results + the titled shelf: $page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            val items = section.items
            assertEquals(4, items.size, "$page")
            // Music-first ranking: the artist-badged video and the Mix bubble above the plain
            // verified video and the channel row, each group keeping the server's relative order
            // (server order was video · video · channel · mix).
            assertEquals(
                listOf("yuuWdm5tBD0", "RDEMeyKnYmm7ScRhnRO9nwOSNA", "FN7ALfpGxiI", "UClyA28-01x4z60eWQ2kiNbA"),
                items.map { it.id },
                "$page",
            )
            val video = items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, video.kind)
            assertEquals("yuuWdm5tBD0", video.id)
            assertTrue(video.title.contains("COME MY WAY"), video.title)
            assertEquals("Sơn Tùng M-TP Official", video.subtitleLine1)
            assertEquals("335,929 views · 49 minutes ago", video.subtitleLine2)
            assertEquals("UClyA28-01x4z60eWQ2kiNbA", video.channelId)
            assertEquals("4:13", video.duration)
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, video.ownerBadge, "$video")
            assertTrue(!video.musicVideo, "no MUSIC overlay on this one: $video")
            assertEquals(YtFeedItem.OWNER_BADGE_VERIFIED, items[2].ownerBadge, "${items[2]}")
            val thumb = video.thumbs.single()
            assertTrue(thumb.url.startsWith("https://i.ytimg.com/vi/yuuWdm5tBD0/"), thumb.url)
            assertEquals(720, thumb.w)
            assertEquals(404, thumb.h)

            val channel = items.first { it.kind == YtFeedItem.KIND_CHANNEL }
            assertTrue(channel.id.startsWith("UC"), channel.id)
            assertEquals("11.8M subscribers", channel.subtitleLine1)
            assertEquals("/channel/${channel.id}", channel.url, "openable items carry their app url")

            val mix = items.first { it.kind == YtFeedItem.KIND_PLAYLIST }
            assertTrue(mix.id.startsWith("RD"), mix.id)
            assertEquals("/playlist?list=${mix.id}", mix.url, "$mix")
            assertTrue(mix.title.startsWith("Mix"), mix.title)
            assertTrue(mix.thumbs.isNotEmpty(), "collection thumbnail must resolve: $mix")
            // The count overlay is what visually separates a playlist row from a video row.
            assertEquals("25 videos", mix.itemCount, "$mix")

            // Shelf videos (gridVideoRenderer) have no lengthText — the duration must come from
            // the time-status thumbnail overlay.
            val shelf = page.sections[1]
            assertEquals("Popular songs", shelf.header?.title, "$shelf")
            val shelfVideo = shelf.items.single()
            assertEquals("3:51", shelfVideo.duration, "$shelfVideo")
            assertEquals("Shelf Channel", shelfVideo.subtitleLine1)
            // The MUSIC-iconed time overlay marks a music video.
            assertTrue(shelfVideo.musicVideo, "$shelfVideo")

            assertNotNull(page.continuation, "search page must carry a continuation")

            assertEquals(listOf("Upload date", "Type", "Features"), page.filterGroups.map { it.title }, "$page")
            val uploadDate = page.filterGroups[0]
            assertEquals(listOf("Last hour", "Today"), uploadDate.options.map { it.label })
            assertEquals("EgIIAQ%3D%3D", uploadDate.options[0].params)
            val type = page.filterGroups[1]
            val selectedVideo = type.options.first { it.label == "Video" }
            assertTrue(selectedVideo.selected, "$selectedVideo")
            assertEquals("EgIQAQ%3D%3D", selectedVideo.params)
            // The url-sp fallback carrier (no searchEndpoint on this one).
            assertEquals("EgIQAg%253D%253D", type.options.first { it.label == "Channel" }.params)
            val purchased = page.filterGroups[2].options.single()
            assertTrue(purchased.disabled, "$purchased")
            assertEquals(null, purchased.params)

            // The header chip bar: a chip's endpoint is a fresh search (query+params), wrapped as
            // an engine-private token so the app's token-only chip pipeline round-trips it.
            assertEquals(listOf("All", "Shorts"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            assertEquals(null, page.chips[0].token, "the active chip has no endpoint")
            val chipToken = assertNotNull(page.chips[1].token)
            assertTrue(chipToken.startsWith("ysq1:"), chipToken)

            m.search(continuation = chipToken)
            val sent3 = assertNotNull(sentBody)
            assertTrue(sent3.contains(""""query":"son tung mtp""""), sent3)
            assertTrue(sent3.contains(""""params":"EgIQAQ%3D%3D""""), sent3)
            assertTrue(!sent3.contains(""""continuation""""), sent3)
        } finally {
            m.close()
        }
    }

    @Test
    fun artist_search_parses_the_universal_watch_card() = runTest {
        val (m, _) = manager { jsonResponse(ARTIST_SEARCH_FIXTURE).build(it) }
        try {
            val page = m.search(query = "bruno mars")

            // Card sections lead the page: artist+Mix, then Albums (near the top, collapsed behind
            // the show-more toggle), then top songs — then the primary results.
            assertEquals(4, page.sections.size, "$page")

            val lead = page.sections[0].items
            assertEquals(2, lead.size, "$lead")
            val artist = lead[0]
            assertEquals(YtFeedItem.KIND_CHANNEL, artist.kind)
            assertEquals("UCoUM-UJ7rirJYP8CQ0EIaHA", artist.id)
            assertEquals("Bruno Mars", artist.title)
            assertEquals("@brunomars", artist.subtitleLine1)
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, artist.ownerBadge, "$artist")
            assertTrue(artist.thumbs.isNotEmpty(), "$artist")
            assertEquals("/channel/UCoUM-UJ7rirJYP8CQ0EIaHA", artist.url)
            val radio = lead[1]
            assertEquals(YtFeedItem.KIND_PLAYLIST, radio.kind)
            assertEquals("RDEMgEsSBB7GgTqDUsqz-X50Iw", radio.id)
            // The hero's seed video rides along as engine-private url state.
            assertEquals("/playlist?list=RDEMgEsSBB7GgTqDUsqz-X50Iw&v=PMivT7MJ41M", radio.url)
            assertEquals("Mix - Bruno Mars", radio.title)
            assertTrue(radio.thumbs.single().url.contains("PMivT7MJ41M"), "seed video thumb: $radio")

            val albums = page.sections[1]
            assertEquals("Albums", albums.header?.title, "$albums")
            assertTrue(albums.collapsible, "album row collapses behind the show-more toggle: $albums")
            // Only the card deep-linking a playlist counts; the plain query refinement is skipped.
            val album = albums.items.single()
            assertEquals(YtFeedItem.KIND_PLAYLIST, album.kind)
            assertTrue(album.id.startsWith("OLAK5uy_"), album.id)
            assertEquals("24K Magic", album.title)
            assertTrue(album.album, "OLAK5uy_ playlists carry the album mark: $album")

            val songs = page.sections[2].items
            assertEquals(listOf("kPa7bsKwL-c", "ekr2nIex040"), songs.map { it.id })
            val song = songs[0]
            assertEquals(YtFeedItem.KIND_VIDEO, song.kind)
            assertEquals("Die With A Smile", song.title)
            assertEquals("4:13", song.duration)
            // watchCardCompactVideoRenderer has no thumbnail — the canonical i.ytimg.com one is used.
            assertTrue(song.thumbs.single().url.contains("/vi/kPa7bsKwL-c/"), "$song")

            val primary = page.sections[3].items
            assertEquals("lY5V4hSLWY8", primary.single().id)
            assertNotNull(page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_detail_parses_page_header_and_tabs_from_the_app_url() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_FIXTURE).build(it) }
        try {
            val page = m.extractChannelDetail("/channel/UCchannelchannelchannel0")

            // The engine decoded the app url into the browse call itself.
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"UCchannelchannelchannel0""""), sent)

            val header = assertNotNull(page.pageHeader, "$page")
            assertEquals("Chan", header.title)
            assertEquals("@chan", header.subtitleLine1)
            assertEquals("1M subscribers · 10 videos", header.subtitleLine2)
            assertEquals("https://a/av.jpg", header.thumbs.single().url)
            assertEquals("https://a/banner.jpg", header.banner.single().url)

            // Content tabRenderer tabs only — the expandableTabRenderer search tab and the Posts
            // tab are dropped; the tab's public web url slug, not the localized title, decides.
            // Each carries its own app url (the round-trip currency) with the slug in the path.
            assertEquals(listOf("Home", "Videos"), page.pageTabs.map { it.title }, "$page")
            assertTrue(page.pageTabs[0].selected, "$page")
            assertTrue(page.pageTabs[0].home, "featured-params tab is the channel front: $page")
            assertTrue(!page.pageTabs[1].home, "$page")
            assertEquals("/channel/UCchannelchannelchannel0/featured?params=EghmZWF0dXJlZA", page.pageTabs[0].url)
            assertEquals("/channel/UCchannelchannelchannel0/videos?params=EgZ2aWRlb3M", page.pageTabs[1].url)

            assertEquals("Channel video", page.sections.single().items.single().title)

            // A tab's url round-trips into a browse with that tab's params.
            m.extractChannelDetail(page.pageTabs[1].url!!)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""browseId":"UCchannelchannelchannel0""""), sent2)
            assertTrue(sent2.contains(""""params":"EgZ2aWRlb3M""""), sent2)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_shorts_tab_parses_shorts_tiles_and_pages_with_a_wrapped_token() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_SHORTS_FIXTURE).build(it) }
        try {
            // The /shorts path slug flips the engine into Shorts parsing (skipped everywhere
            // else); the params ride verbatim (server hands them out URL-ENCODED, the url layer
            // encodes on top).
            val url = "/channel/UCchannelchannelchannel0/shorts?params=EgZzaG9ydHPyBgUKA5oBAA%253D%253D"
            val page = m.extractChannelDetail(url)

            // The browse gets the server's own (still URL-encoded) params back verbatim.
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""params":"EgZzaG9ydHPyBgUKA5oBAA%3D%3D""""), sent)

            val items = page.sections.single().items
            assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), items.map { it.id }, "$page")
            val short = items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, short.kind)
            assertEquals("First short", short.title)
            assertEquals("1M views", short.subtitleLine1)
            assertTrue(short.thumbs.single().url.contains("/vi/AAAAAAAAAAA/"), "$short")

            // The sort bar (chipViewModel generation) parses into chips; on the Shorts tab the
            // tokens are wrapped like the continuation so sorting stays in Shorts mode.
            assertEquals(listOf("Latest", "Popular"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            assertEquals("ychshorts1:sort-latest", page.chips[0].token)
            assertEquals("ychshorts1:sort-popular", page.chips[1].token)

            // The continuation is wrapped with engine-private state so paging stays in Shorts mode…
            val cont = assertNotNull(page.continuation)
            assertTrue(cont.startsWith("ychshorts1:"), cont)
            assertTrue(cont.endsWith("shorts-cont"), cont)

            // …and unwraps back to the raw server token on the next call.
            m.extractChannelDetail(url, continuation = cont)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""continuation":"shorts-cont""""), sent2)
        } finally {
            m.close()
        }
    }

    @Test
    fun channel_playlists_tab_surfaces_the_sort_menu_as_chips() = runTest {
        val (m, _) = manager { jsonResponse(CHANNEL_PLAYLISTS_FIXTURE).build(it) }
        try {
            val url = "/channel/UCchannelchannelchannel0/playlists?params=EglwbGF5bGlzdHPyBgQKAkIA"
            val page = m.extractChannelDetail(url)

            assertEquals("Channel playlist", page.sections.single().items.single().title, "$page")

            // The browse-typed sort menu (sortFilterSubMenuRenderer) becomes chips whose tokens
            // wrap the sort's browse params (there is no server continuation for a sort).
            assertEquals(listOf("Date added (newest)", "Last video added"), page.chips.map { it.title }, "$page")
            assertTrue(page.chips[0].selected, "$page")
            val sortToken = assertNotNull(page.chips[1].token)
            assertTrue(sortToken.startsWith("ychp1:"), sortToken)

            // Clicking a sort chip re-browses the channel with the wrapped params — no continuation.
            m.extractChannelDetail(url, continuation = sortToken)
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"UCchannelchannelchannel0""""), sent)
            assertTrue(sent.contains(""""params":"EglwbGF5bGlzdHMYBCABMAHyBgQKAkIA""""), sent)
            assertTrue(!sent.contains(""""continuation""""), sent)
        } finally {
            m.close()
        }
    }

    @Test
    fun home_feed_parses_rich_grid_chips_and_nested_shelves() = runTest {
        val (m, client) = manager(settings = viSettings()) { jsonResponse(BROWSE_FIXTURE).build(it) }
        try {
            m.applyYtLocale(gl = "VN") // pin the region; the language is the settings pull
            val page = m.extractHomeFeed()

            val req = assertNotNull(client.lastRequest)
            assertTrue(req.url.toString().startsWith("https://www.youtube.com/youtubei/v1/browse"), "${req.url}")
            assertEquals("CgtzZXNzaW9uVGVzdA%3D%3D", req.headers["X-Goog-Visitor-Id"])
            val sent = assertNotNull(sentBody)
            // The front-door browseId is the ENGINE's knowledge — the app never sent one; the
            // locale and the visitor identity are the engine's too (its ladder / its session).
            assertTrue(sent.contains(""""browseId":"FEwhat_to_watch""""), sent)
            assertTrue(sent.contains(""""hl":"vi""""), sent)
            assertTrue(sent.contains(""""gl":"VN""""), sent)
            assertTrue(sent.contains(""""visitorData":"CgtzZXNzaW9uVGVzdA%3D%3D""""), sent)

            assertEquals(1, page.sections.size, "$page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_GRID, section.layout, "rich-grid feed → grid layout")
            assertEquals(2, section.items.size, "one direct rich item + one inside the nested shelf: $page")
            assertEquals("browse-cont-token", page.continuation)
            assertEquals(2, page.chips.size)
            assertEquals("All", page.chips[0].title)
            assertTrue(page.chips[0].selected)
            assertEquals("chip-music-token", page.chips[1].token)
        } finally {
            m.close()
        }
    }

    @Test
    fun recommended_is_empty_until_something_has_been_played() = runTest {
        val (m, client) = manager(cacheStore()) { jsonResponse("{}").build(it) }
        try {
            val page = m.getRecommendedFeed()
            assertEquals(emptyList(), page.sections, "a cold start is an empty feed, not an error")
            // The whole surface is offline: nothing but the session bootstrap may have been fetched.
            assertTrue(
                client.lastRequest?.url?.toString()?.contains("youtubei") != true,
                "the recommended feed must not call YouTube: ${client.lastRequest?.url}",
            )
        } finally {
            m.close()
        }
    }

    @Test
    fun recommended_is_built_from_the_watch_next_payloads_the_webview_reported() = runTest {
        val (m, _) = manager(cacheStore()) { jsonResponse("{}").build(it) }
        try {
            m.handleWebViewPlayNext("yuuWdm5tBD0", WATCH_NEXT_ENDSCREEN_FIXTURE)
            val page = m.getRecommendedFeed()

            val section = page.sections.single()
            assertNull(section.header, "one plain run of items — the tab's title is header enough")
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            assertNull(page.continuation, "what's on disk IS the feed")

            // Three related videos; the played video itself is not among its own recommendations.
            assertEquals(3, section.items.size, "$page")
            assertTrue(section.items.none { it.id == "yuuWdm5tBD0" }, "$page")
            val first = section.items.first { it.id == "N30CFk9fHwg" }
            assertEquals(YtFeedItem.KIND_VIDEO, first.kind)
            assertEquals("Trạm Acoustic", first.subtitleLine1)
            assertEquals("52:30", first.duration)
            assertEquals("UCnTSVstwC_7cG6XEPrwvwGw", first.channelId)
        } finally {
            m.close()
        }
    }

    @Test
    fun recommended_unions_plays_deduped_and_reshuffles_each_read() = runTest {
        val (m, _) = manager(cacheStore()) { jsonResponse("{}").build(it) }
        try {
            // Two plays reporting the SAME related list: the union must not double up.
            m.handleWebViewPlayNext("yuuWdm5tBD0", WATCH_NEXT_ENDSCREEN_FIXTURE)
            m.handleWebViewPlayNext("N30CFk9fHwg", WATCH_NEXT_ENDSCREEN_FIXTURE)

            val ids = m.getRecommendedFeed().sections.single().items.map { it.id }
            // The second play's own id drops out of ITS payload but stays in the first play's.
            assertEquals(ids.toSet().size, ids.size, "deduped across plays: $ids")
            assertEquals(setOf("N30CFk9fHwg", "aYIhumdzhAo", "hmcGrZ42NNg", "yuuWdm5tBD0"), ids.toSet(), "$ids")

            // Reshuffled per read — over enough reads the first slot must not be constant.
            val firsts = (1..12).map { m.getRecommendedFeed().sections.single().items.first().id }
            assertTrue(firsts.toSet().size > 1, "the feed reshuffles on every refresh: $firsts")
        } finally {
            m.close()
        }
    }

    @Test
    fun trending_music_maps_every_chart_to_a_titled_section() = runTest {
        val (m, client) = manager(settings = viSettings()) { jsonResponse(CHARTS_FIXTURE).build(it) }
        try {
            m.applyYtLocale(gl = "vn")
            val page = m.extractTrendingMusicFeed()

            // Charts live on their own host + client; the region rides the engine's own locale
            // ladder, uppercased.
            val req = assertNotNull(client.lastRequest)
            assertTrue(req.url.toString().startsWith("https://charts.youtube.com/youtubei/v1/browse"), "${req.url}")
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""clientName":"WEB_MUSIC_ANALYTICS""""), sent)
            assertTrue(sent.contains(""""browseId":"FEmusic_analytics_charts_home""""), sent)
            assertTrue(sent.contains(""""gl":"VN""""), sent)
            assertTrue(sent.contains(""""hl":"vi""""), sent)

            // Legacy's order and period picks: trending videos, then songs (weekly wins), music
            // videos (daily wins), songs on Shorts (daily wins).
            assertEquals(
                listOf("trending_music_videos", "top_songs_weekly", "top_music_videos_daily", "top_songs_on_shorts_daily"),
                page.sections.map { it.header?.titleKey },
                "$page",
            )
            assertEquals("Daily Top Music Videos", page.sections[2].header?.title, "the English fallback title")
            assertTrue(page.sections.all { it.layout == YtSection.LAYOUT_LIST }, "charts are ranked lists")
            assertTrue(page.sections.all { it.collapsible }, "a 30–100 entry chart collapses to a teaser")
            assertNull(page.continuation, "charts publish no continuation")

            val trending = page.sections[0].items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, trending.kind)
            assertEquals("PXxbEfhtDiM", trending.id)
            assertEquals("Lil Baby", trending.subtitleLine1, "the credited artists, not the channel")
            assertEquals("#1", trending.subtitleLine2, "a trending entry publishes no view count")
            assertEquals("2:50", trending.duration, "videoDuration seconds → a badge the app renders verbatim")
            assertEquals("UCM1gEqLPsgej7ZKzu9w8v7A", trending.channelId)
            assertEquals("https://i.ytimg.com/vi/PXxbEfhtDiM/mqdefault.jpg", trending.thumbs.single().url)

            // A song chart keys on an entity id ("G:…") — the item must carry the VIDEO id.
            val song = page.sections[1].items[0]
            assertEquals("fcnDmrtj6Sk", song.id)
            assertEquals("Shakira, Burna Boy", song.subtitleLine1)
            assertEquals("#1 · 5.9M", song.subtitleLine2)
            assertNull(song.duration, "song charts carry no duration")
        } finally {
            m.close()
        }
    }

    @Test
    fun trending_music_fails_typed_when_the_analytics_section_is_gone() = runTest {
        val (m, _) = manager { jsonResponse("""{"contents":{}}""").build(it) }
        try {
            assertFailsWith<EngineError.Parse> { m.extractTrendingMusicFeed() }
        } finally {
            m.close()
        }
    }

    @Test
    fun home_feed_groups_a_titled_shelf_into_its_own_section() = runTest {
        val (m, _) = manager { jsonResponse(HOME_SHELF_FIXTURE).build(it) }
        try {
            val page = m.extractHomeFeed()

            // The fixture also carries a "Shorts" richShelf (shortsLockupViewModel items) — it must
            // not surface as a section.
            assertEquals(3, page.sections.size, "loose grid + two titled shelves, Shorts dropped: $page")
            assertTrue(page.sections.none { it.header?.title == "Shorts" }, "$page")

            val grid = page.sections[0]
            assertNull(grid.header, "the loose grid has no header")
            assertEquals(YtSection.LAYOUT_GRID, grid.layout)
            assertEquals(listOf("AAAAAAAAAAA"), grid.items.map { it.id })

            val shelf = page.sections[1]
            val header = assertNotNull(shelf.header, "the titled shelf carries a header")
            assertEquals("For you", header.title)
            assertEquals("shelf-cta-token", header.ctaToken)
            // The "View all" browse target becomes an app deeplink (VL prefix stripped: the app's
            // playlist ids are bare, its browse path re-adds VL).
            assertEquals("/playlist?list=PLBcAa442MLAqvWaER_5uj7AVi6FjNe-Zg", header.ctaDeeplink)
            assertEquals(YtSection.LAYOUT_GRID, shelf.layout)
            assertEquals(listOf("BBBBBBBBBBB"), shelf.items.map { it.id })

            // A channel-targeting shelf deep-links the channel WITH its tab params preserved.
            val channelShelf = assertNotNull(page.sections[2].header, "$page")
            assertEquals(
                "/channel/UCchannelchannelchannel0?params=EgZ2aWRlb3M",
                channelShelf.ctaDeeplink,
            )

            assertEquals("page-cont", page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun watch_next_merges_the_end_screen_deduped_and_music_first() = runTest {
        // The end screen (under playerOverlays, outside the page walk's scanned roots) is the
        // better related list and carries the Mix tile the secondary column lacks — so it leads,
        // its repeat of a column item collapses, and music sorts ahead of plain uploads because
        // this list becomes the autoplay queue.
        val (m, _) = manager { jsonResponse(NEXT_WITH_OVERLAYS_FIXTURE).build(it) }
        try {
            val page = m.watchNext(videoId = "wudOtmP09xU")

            val items = page.sections.flatMap { it.items }
            assertEquals(
                listOf(
                    "_KFaLBdT0k4",
                    "RDEM3QJrf1jib0giOa1iEysOiw",
                    "ixYtHJOLY-Y",
                    "PLncXuldPDHBME0DeqYkNSoZaplLWOlguq",
                    "PLAINVIDEO1",
                ),
                items.map { it.id },
                "music videos + the Mix lead in source order, then the plain playlist and upload: $page",
            )
            assertEquals(5, items.size, "the column's repeat of _KFaLBdT0k4 collapsed: $page")
            assertTrue(items[0].musicVideo, "${items[0]}")
            assertEquals(
                "劉德華 今天…is the Day 巡迴演唱會 - 4K60FPS ",
                items[0].title,
                "the end screen's copy won the dedupe, not the column's",
            )

            // The Mix tile: only the end screen offers it, and an RDEM mix has no seed in its id —
            // the seed rides the app link so yt/playlist can materialize the radio.
            val mix = items[1]
            assertEquals(YtFeedItem.KIND_PLAYLIST, mix.kind)
            assertEquals("/playlist?list=RDEM3QJrf1jib0giOa1iEysOiw&v=Cy5KEKvQzFM", mix.url)
            assertEquals("Andy Lau, 海來阿木, Richie Jen, and more", mix.subtitleLine1)
            assertEquals("50+ videos", mix.itemCount)

            // A regular playlist tile ranks below the music run but still parses whole.
            val playlist = items[3]
            assertEquals("52 videos", playlist.itemCount)
            assertEquals("Trạm Acoustic", playlist.subtitleLine1)
            assertEquals("_KFaLBdT0k4", page.autoplayVideoId)
        } finally {
            m.close()
        }
    }

    @Test
    fun watch_next_parses_lockups_and_autoplay() = runTest {
        val (m, _) = manager { jsonResponse(NEXT_FIXTURE).build(it) }
        try {
            val page = m.watchNext(videoId = "yuuWdm5tBD0")

            assertEquals(1, page.sections.size, "$page")
            val section = page.sections[0]
            assertEquals(YtSection.LAYOUT_LIST, section.layout)
            assertEquals(2, section.items.size, "$page")
            val first = section.items[0]
            assertEquals(YtFeedItem.KIND_VIDEO, first.kind)
            assertEquals("SlQR9iu09bQ", first.id)
            assertTrue(first.title.contains("COME MY WAY"), first.title)
            assertEquals("Sơn Tùng M-TP Official and Tyga", first.subtitleLine1)
            assertEquals("3:55", first.duration)
            assertEquals("38M views · 1 month ago", first.subtitleLine2)
            // Lockup signals: MUSIC badge icon on the thumb, byline attachment badges.
            assertTrue(first.musicVideo, "$first")
            assertEquals(YtFeedItem.OWNER_BADGE_ARTIST, first.ownerBadge)
            val second = section.items[1]
            assertTrue(!second.musicVideo, "$second")
            assertEquals(YtFeedItem.OWNER_BADGE_VERIFIED, second.ownerBadge)
            assertEquals("kV1Nxa9tLuY", page.autoplayVideoId)
            assertNotNull(page.continuation)
        } finally {
            m.close()
        }
    }

    @Test
    fun mix_playlist_detail_serves_the_watch_panel_and_pages_by_window() = runTest {
        val (m, _) = manager {
            val window = sentBody?.contains("BBBBBBBBBBB") == true
            jsonResponse(if (window) MIX_NEXT_WINDOW_FIXTURE else MIX_NEXT_FIXTURE).build(it)
        }
        try {
            // The app hands back the engine-composed url; the engine alone knows a Mix isn't
            // browsable and must go through /next with the start-radio params. Seedless (RDEM…):
            // playlistId alone must be a valid call.
            val url = "/playlist?list=RDEMtestmix"
            val page = m.extractPlaylistDetail(url)

            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""playlistId":"RDEMtestmix""""), sent)
            assertTrue(sent.contains(""""params":"wAEB""""), sent)

            assertEquals("Mix - Test Radio", page.pageHeader?.title, "$page")
            assertEquals("YouTube", page.pageHeader?.subtitleLine1)
            // Only the panel belongs on the detail page — the response's watch-page related
            // videos are dropped by the engine, not by the app.
            assertEquals(listOf("Mix song one", "Mix song two"), page.sections.single().items.map { it.title })
            assertEquals("3:33", page.sections.single().items[0].duration)
            assertEquals("Artist One", page.sections.single().items[0].subtitleLine1)

            // The infinite panel yields the engine's own opaque continuation; feeding it back
            // slides the window (videoId + playlistIndex) and returns ONLY the fresh tail.
            val token = assertNotNull(page.continuation, "$page")
            val page2 = m.extractPlaylistDetail(url, continuation = token)
            val sent2 = assertNotNull(sentBody)
            assertTrue(sent2.contains(""""videoId":"BBBBBBBBBBB""""), sent2)
            assertTrue(sent2.contains(""""playlistIndex":1"""), sent2)
            assertEquals(listOf("Mix song three"), page2.sections.single().items.map { it.title }, "$page2")
            assertNotNull(page2.continuation, "the mix keeps paging: $page2")
        } finally {
            m.close()
        }
    }

    @Test
    fun playlist_detail_decodes_the_app_url_engine_side() = runTest {
        val (m, _) = manager { jsonResponse(MIX_NEXT_FIXTURE).build(it) }
        try {
            // A seeded Mix url: the engine reads its own `v=` round-trip state back out.
            m.extractPlaylistDetail("/playlist?list=RDEMtestmix&v=kXYiU_JCYtU")
            val mixSent = assertNotNull(sentBody)
            assertTrue(mixSent.contains(""""videoId":"kXYiU_JCYtU""""), mixSent)
            // A seed embedded in the Mix id itself (RD<videoId>) is derived engine-side.
            m.extractPlaylistDetail("/playlist?list=RDkXYiU_JCYtU")
            val derived = assertNotNull(sentBody)
            assertTrue(derived.contains(""""videoId":"kXYiU_JCYtU""""), derived)
            // A regular playlist browses as VL<id> — the prefix is the engine's business.
            m.extractPlaylistDetail("/playlist?list=PLBcAa442MLAqvWaER")
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""browseId":"VLPLBcAa442MLAqvWaER""""), sent)
        } finally {
            m.close()
        }
    }

    @Test
    fun suggest_parses_the_web_searchbox_jsonp() = runTest {
        val (m, client) = manager {
            CannedResponse.ofText(
                """window.google.ac.h(["son tung",[["sơn tùng m-tp",0,[512,433]],""" +
                    """["son tung mtp making my way",0,[512,433]]],{"k":1,"q":"x"}])""",
            ).build(it)
        }
        try {
            val suggestions = m.searchSuggestions("son tung")
            assertEquals(listOf("sơn tùng m-tp", "son tung mtp making my way"), suggestions)
            val url = assertNotNull(client.lastRequest).url.toString()
            assertTrue(url.contains("client=youtube&gs_ri=youtube"), url)
            assertTrue(url.contains("q=son%20tung"), url)
        } finally {
            m.close()
        }
    }

    @Test
    fun http_failure_crosses_as_typed_network_error() = runTest {
        val (m, _) = manager { throw okio.IOException("connect refused") }
        try {
            assertFailsWith<EngineError.Network> { m.search(query = "anything") }
        } finally {
            m.close()
        }
    }

    @Test
    fun upstream_client_error_carries_its_site_code() = runTest {
        val (m, _) = manager { CannedResponse.ofText("""{"error":{"code":400}}""", status = 400).build(it) }
        try {
            assertEquals("E20140", assertFailsWith<EngineError.Technical> { m.extractHomeFeed() }.code)
        } finally {
            m.close()
        }
    }

    @Test
    fun missing_required_arg_is_a_parse_error() = runTest {
        val (m, _) = manager { jsonResponse(SEARCH_FIXTURE).build(it) }
        try {
            assertFailsWith<EngineError.Parse> { m.search() }
        } finally {
            m.close()
        }
    }

    @Test
    fun the_app_language_is_pulled_from_host_settings_as_the_hl_default() = runTest {
        // The shell seeds "app.uiLanguage" BEFORE the engine starts (single source of truth); the
        // engine pulls it itself — no apply call, no push — as its hl until an explicit pick.
        val settings = io.ymusic.settings.InMemorySettingsStore()
        settings.putString("app.uiLanguage", "vi")
        val (m, _) = manager(settings = settings) { jsonResponse("{}").build(it) }
        try {
            m.search(query = "anything")
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""hl":"vi""""), sent)
        } finally {
            m.close()
        }
    }

    @Test
    fun the_working_region_is_youtubes_own_answer_for_this_connection() = runTest {
        val (m, _) = manager { jsonResponse(ACCOUNT_MENU_FIXTURE).build(it) }
        try {
            val locales = m.ytLocaleOptions()

            // No pick was ever applied: the working region is straight off the bootstrap
            // homepage's ytcfg — the engine asserts no gl to get it, and there is no separate
            // "automatic" state; this concrete value IS what the picker shows as current.
            assertEquals("VN", locales.gl)
        } finally {
            m.close()
        }
    }

    @Test
    fun locale_options_are_youtubes_own_menus_labelled_in_the_effective_language() = runTest {
        val (m, client) = manager(settings = viSettings()) { jsonResponse(ACCOUNT_MENU_FIXTURE).build(it) }
        try {
            m.applyYtLocale(gl = "VN")
            val locales = m.ytLocaleOptions()

            assertEquals(listOf("af" to "Afrikaans", "vi" to "Tiếng Việt"), locales.languages.map { it.code to it.label })
            assertEquals(listOf("US" to "Hoa Kỳ", "VN" to "Việt Nam"), locales.regions.map { it.code to it.label })
            // The working region is the session's parse (the fixture's echo); the language came
            // from the settings pull and labelled the menus.
            assertEquals("VN", locales.gl)
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""hl":"vi""""), sent)

            // One cached copy, tagged with the effective pair it was fetched under: the same ask
            // is free; a new pick changes the pair, so the menus (and the respun session) refetch.
            val requests = client.requestCount
            m.ytLocaleOptions()
            assertEquals(requests, client.requestCount, "the repeat ask must ride the cached menus")
            m.applyYtLocale(gl = "US")
            m.ytLocaleOptions()
            // Only the session re-scraped: the canned homepage echoes the SAME region back
            // (YouTube outranks the pick), so the effective pair — and the menus — are unchanged.
            assertEquals(requests + 1, client.requestCount, "a pick must re-scrape the session")
        } finally {
            m.close()
        }
    }

    @Test
    fun applying_a_region_pick_rides_pref_into_every_cookie_and_respins_the_session() = runTest {
        val (m, client) = manager(settings = viSettings()) { jsonResponse("{}").build(it) }
        try {
            m.search(query = "seed") // bootstrap: the homepage scrape under the automatic identity
            val before = client.requestCount

            // The override lands in PREF and the cached session dies with the old identity: the
            // next command re-scrapes (homepage + its own POST), both carrying the new cookie.
            m.applyYtLocale(gl = "JP")
            m.search(query = "seed")
            assertEquals(before + 2, client.requestCount, "a changed apply must drop the cached session")
            val cookie = assertNotNull(client.lastRequest).headers["Cookie"].orEmpty()
            assertTrue(cookie.contains("PREF="), cookie)
            assertTrue(cookie.contains("hl=vi"), cookie)
            assertTrue(cookie.contains("gl=JP"), cookie)

            // Re-applying the same pick is free: no cookie write, no session drop.
            m.applyYtLocale(gl = "JP")
            val settled = client.requestCount
            m.search(query = "seed")
            assertEquals(settled + 1, client.requestCount, "an unchanged apply must keep the session")

            // Reverting to automatic strips the gl key (the region answers un-asserted again);
            // the language half stays — it is the app language, not part of the region pick.
            m.applyYtLocale(gl = "")
            m.search(query = "seed")
            val reverted = assertNotNull(client.lastRequest).headers["Cookie"].orEmpty()
            assertTrue(reverted.contains("hl=vi") && !reverted.contains("gl=JP"), reverted)
        } finally {
            m.close()
        }
    }

    @Test
    fun a_pref_language_clobbered_behind_the_engines_back_is_repaired_but_the_region_is_youtubes() = runTest {
        val (m, client) = manager(settings = viSettings()) { jsonResponse("{}").build(it) }
        try {
            m.applyYtLocale(gl = "JP")

            // The store is shared with the WebView: one of its pages rewrites PREF underneath us.
            val url = checkNotNull("https://www.youtube.com/".toHttpUrlOrNull())
            client.cookieJar.saveFromResponse(
                url,
                listOf(checkNotNull(parseCookie(url, "PREF=hl=en&gl=US; Domain=.youtube.com; Path=/"))),
            )

            // The language is APP-OWNED truth (cached in engine memory): the very next cooked
            // request repairs the hl slot, in its own Cookie header AND back into the store. The
            // gl slot is YouTube's to control — the rewrite is left standing (the next session
            // refresh parses whatever region the server settles on).
            m.search(query = "anything")
            val cookie = assertNotNull(client.lastRequest).headers["Cookie"].orEmpty()
            assertTrue(cookie.contains("hl=vi"), cookie)
            assertTrue(cookie.contains("gl=US"), cookie)
            val stored = client.cookieJar.loadForRequest(url).first { it.name == "PREF" }.value
            assertTrue(stored.contains("hl=vi") && stored.contains("gl=US"), stored)
        } finally {
            m.close()
        }
    }

    @Test
    fun a_fresh_engine_reuses_the_pick_from_the_shared_cookie_store() = runTest {
        val settings = viSettings()
        val (m, client) = manager(settings = settings) { jsonResponse("{}").build(it) }
        try {
            m.applyYtLocale(gl = "JP")
        } finally {
            m.close()
        }

        // A fresh engine (app restart or a remote bundle swap) with NO apply call: PREF itself —
        // the persistent, WebView-shared platform store — is the pick's record, and the language
        // rides settings, so the first cooked request already carries both.
        val (m2, client2) = manager(settings = settings, jar = client.cookieJar) { jsonResponse("{}").build(it) }
        try {
            m2.search(query = "anything")
            val cookie = assertNotNull(client2.lastRequest).headers["Cookie"].orEmpty()
            assertTrue(cookie.contains("hl=vi"), cookie)
            assertTrue(cookie.contains("gl=JP"), cookie)
        } finally {
            m2.close()
        }
    }

    @Test
    fun locale_options_fall_back_to_the_cached_menus_when_the_refetch_fails() = runTest {
        var offline = false
        val settings = viSettings()
        val (m, _) = manager(settings = settings) {
            if (offline) throw okio.IOException("connect refused")
            jsonResponse(ACCOUNT_MENU_FIXTURE).build(it)
        }
        try {
            m.applyYtLocale(gl = "VN")
            m.ytLocaleOptions()
            offline = true

            // A language change moves the effective pair, forcing a menus re-fetch (new labels);
            // its failure serves the held copy — year-stable lists in yesterday's language beat
            // an error dialog on the settings screen. The working region is untouched: the
            // session's parse (the fixture's echo) survives, YouTube's answer as always.
            settings.putString("app.uiLanguage", "en")
            m.applyYtLocale(gl = "VN")
            val locales = m.ytLocaleOptions()
            assertEquals(listOf("af", "vi"), locales.languages.map { it.code })
            assertEquals(listOf("US", "VN"), locales.regions.map { it.code })
            assertEquals("VN", locales.gl)
        } finally {
            m.close()
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun concurrent_commands_join_one_session_scrape() = runTest {
        // The homepage scrape is heavy (BotGuard bait when bursty): hold the first scrape open and
        // fire a second command — it must JOIN the in-flight scrape, never start its own.
        val homepageHits = kotlin.concurrent.atomics.AtomicInt(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                homepageHits.addAndFetch(1)
                gate.await()
                CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                jsonResponse("{}").build(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            val first = backgroundScope.launch { runCatching { m.search(query = "a") } }
            val second = backgroundScope.launch { runCatching { m.search(query = "b") } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (homepageHits.load() == 0) kotlinx.coroutines.delay(10)
                }
            }
            gate.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(30_000) {
                    first.join()
                    second.join()
                }
            }
            assertEquals(1, homepageHits.load(), "concurrent commands must share one scrape")
        } finally {
            m.close()
        }
    }

    @Test
    fun a_failed_session_refresh_serves_the_stale_session() = runTest {
        var offline = false
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                if (offline) throw okio.IOException("connect refused")
                CannedResponse.ofText(HOMEPAGE_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                sentBody = request.body?.readAll()?.decodeToString()
                jsonResponse("{}").build(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        m.start()
        try {
            m.search(query = "seed") // bootstrap succeeds
            offline = true

            // A region pick forces a refresh; its failure must NOT break anything — the stale
            // session (carrying the stamped pick) keeps serving every surface.
            m.applyYtLocale(gl = "JP")
            m.search(query = "seed")
            val sent = assertNotNull(sentBody)
            assertTrue(sent.contains(""""gl":"JP""""), sent)
        } finally {
            m.close()
        }
    }

    @Test
    fun locale_options_menus_survive_an_engine_restart_via_the_cache_store() = runTest {
        val store = cacheStore()
        val settings = viSettings()
        val (m, client) = manager(cacheStore = store, settings = settings) { jsonResponse(ACCOUNT_MENU_FIXTURE).build(it) }
        try {
            m.applyYtLocale(gl = "VN")
            m.ytLocaleOptions()
        } finally {
            m.close()
        }

        assertTrue(client.requestCount >= 2, "the first engine must scrape live: session + account_menu")

        // A fresh engine on the same store re-seeds EVERYTHING from the host Cache: the applied
        // pick, the session, and the menus — zero requests, no apply call.
        val (m2, client2) = manager(cacheStore = store, settings = settings, jar = client.cookieJar) { jsonResponse(ACCOUNT_MENU_FIXTURE).build(it) }
        try {
            val bootstrapOnly = client2.requestCount
            val locales = m2.ytLocaleOptions()
            assertEquals(
                bootstrapOnly,
                client2.requestCount,
                "the fresh engine must serve the menus from the persisted cache",
            )
            assertEquals(listOf("US" to "Hoa Kỳ", "VN" to "Việt Nam"), locales.regions.map { it.code to it.label })
            assertEquals("VN", locales.gl)
        } finally {
            m2.close()
        }
    }

    /** A client whose homepage GET serves [homepage] and whose InnerTube POSTs serve
     *  [BROWSE_FIXTURE], counting each side. */
    private class CountingClient(homepage: String) {
        var homepageHits = 0
        var browseHits = 0
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                homepageHits++
                CannedResponse.ofText(homepage, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                browseHits++
                CannedResponse.ofText(
                    BROWSE_FIXTURE,
                    headers = listOf("Content-Type" to "application/json"),
                ).build(request)
            }
        }
    }

    @Test
    fun the_first_home_load_rides_the_bootstrap_homepage_not_browse() = runTest {
        val counting = CountingClient(HOMEPAGE_WITH_FEED_FIXTURE)
        val m = DynamicEngineManager(counting.client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = cacheStore())
        m.start()
        try {
            // The session bootstrap's page IS the first answer: one fetch, zero /browse.
            val first = m.extractHomeFeed()
            assertEquals(1, counting.homepageHits)
            assertEquals(0, counting.browseHits, "the first home load must not browse")
            assertEquals(3, first.sections.size, "$first")
            assertEquals("page-cont", first.continuation)

            // The kept page is single-use — every later load browses normally.
            val second = m.extractHomeFeed()
            assertEquals(1, counting.homepageHits)
            assertEquals(1, counting.browseHits)
            assertEquals("browse-cont-token", second.continuation)

            m.extractHomeFeed(continuation = "page-cont")
            assertEquals(2, counting.browseHits)
        } finally {
            m.close()
        }
    }

    @Test
    fun a_restart_re_scrapes_the_homepage_for_the_first_home_load() = runTest {
        val store = cacheStore()
        val first = CountingClient(HOMEPAGE_WITH_FEED_FIXTURE)
        val m = DynamicEngineManager(first.client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = store)
        m.start()
        try {
            m.extractHomeFeed() // consumes the kept bootstrap page
        } finally {
            m.close()
        }
        assertEquals(1, first.homepageHits)
        assertEquals(0, first.browseHits)

        // The next run's session is still fresh off the store, but its kept page was consumed:
        // the first home load forces ONE re-scrape and serves from that — still no /browse.
        val second = CountingClient(HOMEPAGE_WITH_FEED_FIXTURE)
        second.client.cookieJar = first.client.cookieJar
        val m2 = DynamicEngineManager(second.client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = store)
        m2.start()
        try {
            val page = m2.extractHomeFeed()
            assertEquals(1, second.homepageHits, "one forced re-scrape replaces the /browse call")
            assertEquals(0, second.browseHits)
            assertEquals("page-cont", page.continuation)
        } finally {
            m2.close()
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun a_home_load_settles_an_in_flight_scrape_and_serves_its_fresher_page() = runTest {
        // An entry is already kept (its payload pages with "browse-cont-token") but a scrape is
        // MID-FLIGHT when home loads: the consume must wait for it and serve the page it fetched
        // ("page-cont"), not the stale entry — and never start a scrape of its own.
        val store = cacheStore()
        store.put("yt-session", "homepage", emptyMap(), okio.Buffer().writeUtf8(BROWSE_FIXTURE).asAsyncSource())

        val homepageHits = kotlin.concurrent.atomics.AtomicInt(0)
        var browseHits = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                homepageHits.addAndFetch(1)
                gate.await()
                CannedResponse.ofText(HOMEPAGE_WITH_FEED_FIXTURE, headers = listOf("Content-Type" to "text/html")).build(request)
            } else {
                if (url.contains("/browse")) browseHits++
                jsonResponse(SEARCH_FIXTURE).build(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = store)
        m.start()
        try {
            val search = backgroundScope.launch { runCatching { m.search(query = "seed") } } // kicks the scrape
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (homepageHits.load() == 0) kotlinx.coroutines.delay(10)
                }
            }
            val home = backgroundScope.async { m.extractHomeFeed() }
            // While the scrape is held open, home must be PARKED on it — answering now could only
            // mean it served the stale entry instead of settling the scrape.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.delay(300)
            }
            assertTrue(!home.isCompleted, "home must wait for the running scrape, not serve the stale entry")
            gate.complete(Unit)
            val page = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(30_000) { home.await() }
            }
            search.join()
            assertEquals("page-cont", page.continuation, "the just-scraped page must win over the stale entry")
            assertEquals(1, homepageHits.load(), "the home load must settle the running scrape, not start one")
            assertEquals(0, browseHits)
        } finally {
            m.close()
        }
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun a_home_load_fails_fast_when_the_settled_scrape_crashes_with_no_session() = runTest {
        // Cold start, offline: home parks on the scrape another command started; when it crashes
        // with no session anywhere, home must rethrow THAT error — never spend a second doomed
        // scrape reaching the same failure.
        val homepageHits = kotlin.concurrent.atomics.AtomicInt(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = FakeHttpClient { request ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei")) {
                homepageHits.addAndFetch(1)
                gate.await()
                throw okio.IOException("connect refused")
            } else {
                jsonResponse(SEARCH_FIXTURE).build(request)
            }
        }
        val m = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = cacheStore())
        m.start()
        try {
            val search = backgroundScope.launch { runCatching { m.search(query = "seed") } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (homepageHits.load() == 0) kotlinx.coroutines.delay(10)
                }
            }
            val home = backgroundScope.async { runCatching { m.extractHomeFeed() } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.delay(300) // let home reach and park on the held scrape
            }
            gate.complete(Unit)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(30_000) { home.await() }
            }
            search.join()
            assertTrue(result.isFailure, "$result")
            assertTrue(result.exceptionOrNull() is EngineError.Network, "${result.exceptionOrNull()}")
            assertEquals(1, homepageHits.load(), "the crash must rethrow — no second scrape gets spent")
        } finally {
            m.close()
        }
    }

    @Test
    fun a_homepage_without_initial_data_falls_back_to_browse_directly() = runTest {
        val counting = CountingClient(HOMEPAGE_FIXTURE) // ytcfg only, no ytInitialData
        val m = DynamicEngineManager(counting.client, appVersion = 1, jsSource = embeddedEngineJsSource(), cacheStore = cacheStore())
        m.start()
        try {
            val page = m.extractHomeFeed()
            // The page was there, just payload-less: /browse answers, no second scrape is spent.
            assertEquals(1, counting.homepageHits, "a payload-less page must not trigger a re-scrape")
            assertEquals(1, counting.browseHits)
            assertEquals("browse-cont-token", page.continuation)
        } finally {
            m.close()
        }
    }
}
