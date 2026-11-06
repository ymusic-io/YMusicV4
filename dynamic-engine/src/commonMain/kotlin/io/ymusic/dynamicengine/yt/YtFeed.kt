package io.ymusic.dynamicengine.yt

/**
 * A feed entry scraped by the engine bundle — the Kotlin mirror of the `WireFeedItem` shape
 * (engine-js `logic/yt/wire.ts`). A **generic display component**, not a typed record: the UI renders
 * [title] + [subtitleLine1]/[subtitleLine2] verbatim and decides nothing about what text they hold — the engine composes those lines (it alone knows whether line 1 should be the
 * channel, the view count, the subscriber total…). [kind] is an open string (see [KIND_VIDEO]…) that
 * picks the thumbnail shape + which structural badge overlays; a newer remote bundle can introduce
 * kinds an older app ignores gracefully.
 *
 * Read off the pipe by [readFeedPage] (YtFeedReader.kt), not a reflective deserializer — hence no
 * `@Serializable` on this or the other result shapes.
 */
/** One thumbnail variant; [w]/[h] in pixels when the source declares them. Lists arrive in source
 *  order (ascending size) — the app picks the variant nearest its display size. */
data class YtThumb(
    val url: String,
    val w: Int? = null,
    val h: Int? = null,
)

data class YtFeedItem(
    val kind: String,
    /** videoId / playlistId / channelId, by [kind]. */
    val id: String,
    val title: String,
    /** Engine-composed subtitle lines, rendered verbatim (no app-side field selection). */
    val subtitleLine1: String? = null,
    val subtitleLine2: String? = null,
    /** Channel-link target: tapping the byline/avatar navigates here. */
    val channelId: String? = null,
    /** A video's owner channel / credited artists — the player's artist line (mini player, Now
     *  Playing, media session), independent of the subtitle lines. */
    val channelTitle: String? = null,
    /** The engine-composed app link opening [channelTitle]'s page. */
    val channelUrl: String? = null,
    /** The app-internal link opening this item's detail surface ("/playlist?…" / "/channel/…").
     *  Engine-composed and round-tripped back VERBATIM ([extractPlaylistDetail] /
     *  [extractChannelDetail]) — the query may carry engine-private state the app must not
     *  interpret. Null = the item has no page. */
    val url: String? = null,
    val thumbs: List<YtThumb> = emptyList(),
    /** Thumbnail badge overlays (structural, fixed position — not subtitle text). */
    val duration: String? = null,
    /** [duration] parsed to ms by the ENGINE when it is a clock badge ("3:14") — the app renders
     *  [duration] verbatim and never parses it; LIVE etc. carry none. */
    val durationMs: Long? = null,
    val itemCount: String? = null,
    val live: Boolean = false,
    /** The thumbnail's time-status overlay carried the MUSIC icon — a music video. */
    val musicVideo: Boolean = false,
    /** The signed-in user's watch progress on this video, 1–100 (%); null = unwatched. */
    val watchedPercent: Int? = null,
    /** An auto-generated album playlist — rendered with the album mark. */
    val album: Boolean = false,
    /** The owner channel's badge (see [OWNER_BADGE_ARTIST]/[OWNER_BADGE_VERIFIED]); null = none. */
    val ownerBadge: String? = null,
    /** A [KIND_TAG]'s accent colour, 8-hex `AARRGGBB` (YouTube Music's per-mood/genre colour); null = none. */
    val accent: String? = null,
    /** [removeFromPlaylist]'s token: set when YouTube offers removing this video from the playlist
     *  being shown (the viewer's own); null elsewhere. */
    val removeToken: String? = null,
) {
    companion object {
        const val KIND_VIDEO = "video"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_CHANNEL = "channel"

        /** A navigation tag/button (a mood/genre or an Explore entry) — no artwork; rendered as a
         *  pill in a [YtSection.LAYOUT_TAGS] section, opening its browse via [url]. */
        const val KIND_TAG = "tag"
        const val OWNER_BADGE_ARTIST = "artist"
        const val OWNER_BADGE_VERIFIED = "verified"
    }
}

/** A feed filter chip (home "All / Music / Gaming…"); [token] re-requests the filtered feed. */
data class YtChip(
    val title: String,
    val token: String? = null,
    val selected: Boolean = false,
)

/**
 * One option in a search filter group. [params] is the full search params for re-running the search
 * with this option applied — the server composes combinations (and, on a [selected] removable
 * option, its removal). Null on the already-active irremovable option (e.g. the default sort).
 */
data class YtFilterOption(
    val label: String,
    val params: String? = null,
    val selected: Boolean = false,
    val disabled: Boolean = false,
)

/** A server-provided, titled group of search filters ("Type" / "Duration" / "Upload date" / …). */
data class YtFilterGroup(
    val title: String,
    val options: List<YtFilterOption>,
)

/**
 * The page-level header of a detail surface (channel / playlist browse). Subtitle lines are
 * engine-composed metadata rows rendered verbatim ("@handle", "11.8M subscribers · 221 videos",
 * "Playlist · 31 videos · 1M views") — same contract as feed items.
 */
data class YtPageHeader(
    val title: String? = null,
    val subtitleLine1: String? = null,
    val subtitleLine2: String? = null,
    /** Channel avatar / playlist hero image, variants in source order. */
    val thumbs: List<YtThumb> = emptyList(),
    /** Channel banner variants. */
    val banner: List<YtThumb> = emptyList(),
    /** A playlist's length when its header states one; null otherwise (Mixes, music pages). */
    val itemCount: Int? = null,
    /** A channel page's channel — the [subscribeChannel] target. */
    val channelId: String? = null,
    /** The viewer's subscription to [channelId]; null when signed out (YouTube offers only sign-in). */
    val subscribed: Boolean? = null,
    /** The [savePlaylist] target; null when the page offers no save (own playlists, Mixes). */
    val savePlaylistId: String? = null,
    /** Whether [savePlaylistId] is in the viewer's library; null when signed out. */
    val saved: Boolean? = null,
)

/** One browse tab of a channel page (Home / Videos / …). [url] is the app link fetching the channel
 *  pinned to that tab (round-tripped verbatim through [extractChannelDetail]); [params] is the raw
 *  server token it embeds — informational, the app never composes browses from it. */
data class YtPageTab(
    val title: String,
    val params: String? = null,
    val url: String? = null,
    /** The channel's front (Home) tab — the channel header renders only on it. */
    val home: Boolean = false,
    val selected: Boolean = false,
)

/**
 * The single-line header above a [YtSection] (design: `[circle icon] · title · [count] · [CTA]`).
 * Every part is optional — a bare grid with no header omits it entirely.
 */
data class YtSectionHeader(
    val title: String? = null,
    /** Set only when the engine SYNTHESIZED [title] (the charts feed publishes none): a stable key
     *  the app resolves to its own localized string, with [title] as the fallback for a key this
     *  build doesn't know — an older app meeting a newer bundle. */
    val titleKey: String? = null,
    val iconThumbs: List<YtThumb> = emptyList(),
    val count: String? = null,
    val ctaText: String? = null,
    val ctaToken: String? = null,
    /** App-internal navigation for the CTA — "/playlist?list=<id>" or "/channel/<id>[?params=<tab>]".
     *  Engine-composed; the app parses only this one scheme (see commonUi parseDeeplink). */
    val ctaDeeplink: String? = null,
    /** [CTA_PLAY_ALL] = the CTA plays the section's videos in order instead of navigating. */
    val ctaAction: String? = null,
) {
    companion object {
        const val CTA_PLAY_ALL = "playAll"
    }
}

/**
 * A run of items under one header. [layout] is the engine's INTENT (see the LAYOUT_ constants) derived
 * from the source renderer; the app re-resolves list/grid (and any unknown value) responsively by
 * screen width. The app renders each item with the (`item.kind` × [layout]) leaf
 * component — videoType{List,Grid} / channelType{List,Grid} / playlistType{List,Grid}.
 */
data class YtSection(
    val header: YtSectionHeader? = null,
    val layout: String = LAYOUT_LIST,
    val items: List<YtFeedItem>,
    val collapsible: Boolean = false,
    /** Engine override for how many rows a collapsed section keeps, indexed by the resolved
     *  column count (`[4, 3, 2]` = 4 rows single-column, 3 at two columns, 2 beyond; the last
     *  entry extends). Empty = the app's default policy. */
    val collapsedRows: List<Int> = emptyList(),
    val continuation: String? = null,
    /** How a video tap plays: [PLAY_QUEUE] queues the section; null = watch-next radio. */
    val playMode: String? = null,
) {
    companion object {
        const val PLAY_QUEUE = "queue"
        const val LAYOUT_LIST = "list"
        const val LAYOUT_GRID = "grid"

        /** A wrap of navigation pills ([YtFeedItem.KIND_TAG]) — e.g. Explore's Moods & genres. */
        const val LAYOUT_TAGS = "tags"

        /** One horizontally scrolling row at every width — e.g. the subscribed-channel avatars. */
        const val LAYOUT_CAROUSEL = "carousel"
    }
}

/** One page of any feed surface (browse / search / watch-next); [continuation] fetches the next. */
data class YtFeedPage(
    val sections: List<YtSection>,
    val continuation: String? = null,
    val chips: List<YtChip> = emptyList(),
    /** The page's sort menu (music library's "Recent activity / Recently saved / …"): each option's
     *  [YtChip.token] is a server continuation re-serving the page in that order; the selected one
     *  is the current order. Continuation pages omit it — the UI carries it over like [chips]. */
    val sort: List<YtChip> = emptyList(),
    /** Layout intent for the chip row: true = wrap to as many lines as needed (the music library),
     *  false = the default horizontal scroll. Set by the serving engine command on every response
     *  of its surface. */
    val chipsWrap: Boolean = false,
    /** The source's OWN empty-state explainer (anonymous Home's "Try searching to get started" +
     *  subtitle), localized by YouTube. Shown by the app's empty state in place of its generic
     *  message when the page has no items; null = the app's default. */
    val emptyTitle: String? = null,
    val emptyMessage: String? = null,
    /** Search only: the filter submenu groups (fresh pages; continuations omit them). */
    val filterGroups: List<YtFilterGroup> = emptyList(),
    /** Detail surfaces only (channel / playlist browse). */
    val pageHeader: YtPageHeader? = null,
    val pageTabs: List<YtPageTab> = emptyList(),
    /** Watch-next only: the video autoplay would play next. */
    val autoplayVideoId: String? = null,
    /** A part of the page failed but the rest is served: the error code to show as a transient message. */
    val partialErrorCode: String? = null,
)

/**
 * The signed-in user's account card (engine `yt/account`, from account_menu's
 * activeAccountHeaderRenderer). [signedIn] is the cookie-gate truth; the rest is display metadata
 * (absent fields simply not shown). [channelId] is the user's own channel ("UC…"), the My-channel
 * link target.
 */
data class YtAccountInfo(
    val signedIn: Boolean,
    val name: String? = null,
    val email: String? = null,
    val handle: String? = null,
    val channelId: String? = null,
    val thumbs: List<YtThumb> = emptyList(),
)

/** Where the user clears a `captcha` bot challenge: a browser on [url] presenting [userAgent] (the
 *  scrape's own) over the shared cookie jar. */
data class YtVerifyTarget(val url: String, val userAgent: String)

/** One pickable content language / region: the InnerTube code and YouTube's own label for it. */
data class YtLocaleOption(val code: String, val label: String)

/**
 * The Settings region picker's whole world: [gl] is the WORKING region (the session's parsed
 * answer — YouTube's own, whether IP-derived or following a pick) and [regions] the menu YouTube
 * offers, labelled in the effective language. The locale itself lives in the ENGINE — the app only
 * displays this and reports picks. (No language here: the app language IS the content language,
 * picked in the app's own language row.)
 */
data class YtLocales(
    val gl: String = "",
    val languages: List<YtLocaleOption> = emptyList(),
    val regions: List<YtLocaleOption> = emptyList(),
)
