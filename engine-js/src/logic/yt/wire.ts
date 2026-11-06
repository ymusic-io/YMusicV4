// The scrape-result wire shapes crossing to Kotlin (mirror: yt/YtFeed.kt in :dynamic-engine).
// Built with QUOTED keys/bracket writes only — Kotlin reads these names off the wire, so Closure
// must never rename them (and no dotted access may exist anywhere, or ADVANCED hard-errors on the
// mixed access).

// A feed item is a GENERIC display component, not a typed video/playlist/channel record: the app
// renders `title` + two subtitle lines + an optional CTA verbatim and decides NOTHING about what text
// they hold. The engine composes the lines — it alone knows whether line 1 should be the channel, the
// view count, the subscriber total, … — so the wire carries presentation slots, not semantic fields.
// `kind` still picks the thumbnail SHAPE (square vs circle) and which structural badge overlays; the
// duration/itemCount/live badges are fixed-position thumbnail overlays, not free text, so they stay
// typed.
/** One thumbnail variant; `w`/`h` in pixels when the source declares them. */
export interface WireThumb {
  url: string;
  w?: number;
  h?: number;
}

export interface WireFeedItem {
  /** "video" | "playlist" | "channel" — string, not a union, for forward-compat on the Kotlin side. */
  kind: string;
  /** videoId / playlistId / channelId ("UC…", "PL…", "RD…"). */
  id: string;
  title: string;
  /** Composed by the engine, rendered verbatim: e.g. channel name, or view count — the engine's call. */
  subtitleLine1?: string;
  subtitleLine2?: string;
  /** Channel-link target: tapping the byline/avatar navigates here. */
  channelId?: string;
  /** A video's owner channel / credited artists — the player's artist line (mini player, Now
   *  Playing, media session), independent of whatever the subtitle lines show. */
  channelTitle?: string;
  /** The app link opening [channelTitle]'s page — WEB "/channel/…" or a music artist browse. */
  channelUrl?: string;
  /** The app-internal link opening this item's detail surface ("/playlist?list=…" / "/channel/…").
   *  Composed by the engine and round-tripped back VERBATIM (yt/playlist, yt/channel) — the query
   *  may carry engine-private state the app must not interpret. Absent = the item has no page. */
  url?: string;
  /** Thumbnail variants in source order (ascending size); the app picks by display size. */
  thumbs?: WireThumb[];
  /** Thumbnail badge overlays (structural, fixed position — not subtitle text). */
  duration?: string;
  /** `duration` parsed to milliseconds when it is a clock badge ("3:14"); LIVE etc. carry none. */
  durationMs?: number;
  itemCount?: string;
  live?: boolean;
  /** The time-status overlay carried the MUSIC icon — this video is a music video. */
  musicVideo?: boolean;
  /** The signed-in user's watch progress on this video, 1–100 (%); absent = unwatched. */
  watchedPercent?: number;
  /** An auto-generated album playlist (OLAK5uy_…) — the app renders the album mark. */
  album?: boolean;
  /** The owner channel's badge: "artist" (Official Artist Channel) or "verified". */
  ownerBadge?: string;
  /** A tag's accent colour, 8-hex `AARRGGBB` (YouTube Music's per-mood/genre stripe colour). */
  accent?: string;
  /** Opaque yt/playlist/remove token: present when YouTube offers removing this video from the
   *  playlist being shown (the viewer's own). */
  removeToken?: string;
}

export interface WireChip {
  title: string;
  token?: string;
  selected?: boolean;
}

/**
 * One option in a search filter group. `params` is the FULL search params for re-running the search
 * with this option applied — the server composes combinations (and, on a selected removable option,
 * its removal), so the app never builds filter protobufs itself. Absent on the already-active
 * irremovable option (e.g. the default sort).
 */
export interface WireFilterOption {
  label: string;
  params?: string;
  selected?: boolean;
  disabled?: boolean;
}

/** A server-provided, titled group of search filters ("Type" / "Duration" / "Upload date" / …). */
export interface WireFilterGroup {
  title: string;
  options: WireFilterOption[];
}

/**
 * The page-level header of a detail surface (channel / playlist browse). Subtitle lines are
 * engine-composed metadata rows rendered verbatim ("@handle", "11.8M subscribers · 221 videos",
 * "Playlist · 31 videos · 1M views" — the engine's call, same contract as feed items).
 */
export interface WirePageHeader {
  title?: string;
  subtitleLine1?: string;
  subtitleLine2?: string;
  /** Channel avatar / playlist hero image, variants in source order. */
  thumbs?: WireThumb[];
  /** Channel banner variants. */
  banner?: WireThumb[];
  /** Playlist length from the header's "31 videos" part; absent when the header shows none. */
  itemCount?: number;
  /** A channel page's channel — the yt/channel/subscribe target. */
  channelId?: string;
  /** The viewer's subscription to [channelId]; absent when the header offers only a sign-in button. */
  subscribed?: boolean;
  /** The yt/playlist/save target — present only when the page offers a save toggle (not the
   *  viewer's own playlists, not Mixes). */
  savePlaylistId?: string;
  /** Whether [savePlaylistId] is in the viewer's library; absent when signed out. */
  saved?: boolean;
}

/** One browse tab of a channel page (Home / Videos / Shorts / …). `url` is the app link fetching
 *  the channel filtered to that tab (round-tripped verbatim through yt/channel); `params` is the
 *  raw server token it embeds — informational, the app never composes browses from it. */
export interface WirePageTab {
  title: string;
  params?: string;
  url?: string;
  /** The channel's front (Home/featured) tab — the app shows the channel header only here. */
  home?: boolean;
  selected?: boolean;
}

// The single-line header above a section (design: [circle icon] · title · [count] · [CTA]); every
// part optional, so a bare grid with no header omits it entirely.
export interface WireSectionHeader {
  title?: string;
  /** Set only when the engine SYNTHESIZED the title (charts publish none): a stable key the app
   *  resolves to its own localized string. `title` stays as the fallback for an unknown key. */
  titleKey?: string;
  /** Leading circle icon/avatar (e.g. a channel shelf's avatar), variants in source order. */
  iconThumbs?: WireThumb[];
  /** Trailing count next to the title ("500+"). */
  count?: string;
  /** Trailing action label ("View all"); absent = no CTA. */
  ctaText?: string;
  /** browse/continuation token the CTA follows. */
  ctaToken?: string;
  /** App-internal navigation for the CTA — "/playlist?list=<id>" or "/channel/<id>[?params=<tab>]".
   *  The engine composes it (it knows the endpoint shapes); the app only parses this one scheme. */
  ctaDeeplink?: string;
  /** "playAll" = the CTA plays this section's videos as the queue, in order (no navigation). */
  ctaAction?: string;
}

// A run of items under one header. `layout` is the engine's INTENT ("list" | "grid" | "tags" |
// "carousel" = one horizontal row at every width; open string), derived from the source renderer
// (vertical feed → list, shelf/rich-grid → grid); the app may still re-resolve it responsively by screen width. The app renders each item with
// the (item.kind × layout) leaf component: videoType{List,Grid} / channelType{List,Grid} /
// playlistType{List,Grid}.
export interface WireSection {
  header?: WireSectionHeader;
  layout: string;
  items: WireFeedItem[];
  /** The section offers a show-more/less toggle (design: collapsible "Recommended"). */
  collapsible?: boolean;
  /** Rows a collapsed section keeps, indexed by the column count the app resolves responsively:
   *  `[4, 3, 2]` = 4 rows single-column, 3 rows at two columns, 2 from three columns up (the last
   *  entry extends). Absent = the app's default policy — send only to override one section. */
  collapsedRows?: number[];
  /** Per-section load-more token (a shelf "View all" / its own continuation). */
  continuation?: string;
  /** "queue" = tapping a video queues this whole section from it; absent = watch-next radio. */
  playMode?: string;
}

export interface WireFeedPage {
  sections: WireSection[];
  /** Page-level continuation (infinite scroll of the whole feed). */
  continuation?: string;
  chips?: WireChip[];
  /** The page's sort menu (music library's "Recent activity / Recently saved / …"): each option's
   *  token is a server reload continuation re-serving the page in that order; the selected option
   *  is the current one. Continuation pages omit it — the app carries the row over like chips. */
  sort?: WireChip[];
  /** Layout intent for the chip row: true = wrap to as many lines as needed (the music library,
   *  whose sort trigger shares the row), absent/false = the default horizontal scroll. Set by the
   *  serving command, not the parser, so it rides EVERY response of that surface. */
  chipsWrap?: boolean;
  /** The source's OWN empty-state explainer (anonymous Home's feedNudgeRenderer: "Try searching to
   *  get started" + subtitle), localized by YouTube. Rendered by the app's empty state in place of
   *  its generic message when the page has no items; absent = the app's default. */
  emptyTitle?: string;
  emptyMessage?: string;
  /** Search only: the filter submenu groups (fresh pages; continuations omit them). */
  filterGroups?: WireFilterGroup[];
  /** Detail surfaces only (channel / playlist browse). */
  pageHeader?: WirePageHeader;
  pageTabs?: WirePageTab[];
  autoplayVideoId?: string;
  /** A part of the page failed but the rest is served: the code the app shows as a transient message. */
  partialErrorCode?: string;
}

export interface WireSuggest {
  suggestions: string[];
}

/**
 * The signed-in user's account card (yt/account) — parsed from account_menu's
 * activeAccountHeaderRenderer. `signedIn` is the cookie-gate truth (the network header is only
 * fetched once the essential cookies are present); the rest is display metadata, absent fields
 * simply not rendered.
 */
export interface WireAccount {
  signedIn: boolean;
  name?: string;
  email?: string;
  /** "@handle" for the user's channel. */
  handle?: string;
  /** The user's channel ("UC…") — the My-channel link target. */
  channelId?: string;
  /** Avatar variants in source order. */
  thumbs?: WireThumb[];
}

/** The `yt/login/status` result — the cookie-only sign-in gate (no network). */
export interface WireLoginStatus {
  signedIn: boolean;
}

/** The `yt/login/url` result — the sign-in entry URL (engine-owned, never hardcoded app-side). */
export interface WireLoginUrl {
  url: string;
}

/** The `yt/verify/target` result — where a `captcha` challenge is cleared, and the UA to present. */
export interface WireVerifyTarget {
  url: string;
  userAgent: string;
}

/** One pickable content language / region: the InnerTube code and YouTube's own label for it. */
export interface WireLocaleOption {
  code: string;
  label: string;
}

/**
 * The Settings region picker's whole world in one reply: [gl] is the WORKING region (the session's
 * parsed answer — YouTube's own), and the lists YouTube offers with their labels in the effective
 * language. No language here — the app language IS the content language, picked app-side.
 */
export interface WireLocales {
  gl: string;
  languages?: WireLocaleOption[];
  regions?: WireLocaleOption[];
}

/** One headline stat under a video's title, rendered as value over label ("19M" / "Likes"). */
export interface WireFactoid {
  value: string;
  label: string;
}

/** The watch page minus the player (yt/video-details): metadata, description, comments entry. */
export interface WireVideoDetails {
  title?: string;
  channelTitle?: string;
  channelUrl?: string;
  channelThumbs?: WireThumb[];
  /** "4.55M subscribers". */
  subscribers?: string;
  factoids?: WireFactoid[];
  /** The like button's count ("19M"), and its count once the viewer's own like is in. */
  likeCount?: string;
  likeCountLiked?: string;
  /** The viewer's rating: "LIKE" | "DISLIKE" | "INDIFFERENT". */
  likeStatus?: string;
  /** Engine-private yt/video/rate tokens keyed by the new status (LIKE / DISLIKE / INDIFFERENT);
   *  absent when the viewer cannot rate (signed out — the button only prompts sign-in). */
  rateTokens?: Record<string, string>;
  /** Plain text; links and timestamps stay inline as their visible text. */
  description?: string;
  /** "2.4M" — the comment total, absent when comments are off. */
  commentsCount?: string;
  /** The first comments page's token for yt/comments; absent = comments are off. */
  commentsContinuation?: string;
}

export interface WireComment {
  id: string;
  text: string;
  author?: string;
  authorThumbs?: WireThumb[];
  authorChannelUrl?: string;
  /** "1 year ago" (YouTube appends "(edited)" itself). */
  published?: string;
  likes?: string;
  /** [likes] once the viewer's own like is in. */
  likesLiked?: string;
  /** The viewer liked / disliked it. */
  liked?: boolean;
  disliked?: boolean;
  /** Engine-private yt/comment/action tokens; absent when the viewer cannot rate (signed out). */
  likeToken?: string;
  unlikeToken?: string;
  dislikeToken?: string;
  undislikeToken?: string;
  /** Engine-private tokens present only on the viewer's own comments: yt/comment/action deletes,
   *  yt/comment/edit replaces the text. */
  deleteToken?: string;
  editToken?: string;
  /** Engine-private yt/comment/reply token; absent when the viewer cannot reply. */
  replyToken?: string;
  replyPlaceholder?: string;
  /** "962" — absent on a reply and on a thread with none. */
  replyCount?: string;
  /** yt/comments token for this thread's replies. */
  repliesContinuation?: string;
  /** "Pinned by @RickAstleyYT". */
  pinned?: string;
  /** The video's creator hearted it. */
  hearted?: boolean;
  /** Written by the video's own channel. */
  byCreator?: boolean;
  verified?: boolean;
}

/** A page of comments or of one thread's replies (yt/comments). The header fields ride only the
 *  first page of top-level comments. */
export interface WireCommentPage {
  /** "2,458,438 Comments". */
  countText?: string;
  /** Top / Newest — each token reloads the list in that order. */
  sort?: WireChip[];
  composerPlaceholder?: string;
  /** Engine-private token for yt/comment/create; absent = this viewer cannot comment. */
  createToken?: string;
  comments: WireComment[];
  continuation?: string;
}

/** One of the viewer's own playlists in the add-to-playlist picker (yt/playlist/targets). */
export interface WirePlaylistTarget {
  playlistId: string;
  title: string;
  /** PUBLIC | UNLISTED | PRIVATE. */
  privacy?: string;
  /** The playlist already holds the video. */
  containsVideo?: boolean;
}

/** What an outside link (a youtube.com / youtu.be / music.youtube.com url, or the same path under
 *  the app's own scheme/domains) opens (yt/resolve-link). `kind`: "video" (play `item`; `url` is its
 *  playlist's app link when the link named one, `startMs` its start time), "playlist" / "channel" /
 *  "music" (the detail page behind the app link `url`), "search" (`query`), "none" (not openable). */
export interface WireLinkTarget {
  kind: string;
  item?: WireFeedItem;
  url?: string;
  startMs?: number;
  query?: string;
}
