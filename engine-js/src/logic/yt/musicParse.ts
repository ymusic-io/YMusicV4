// music.youtube.com (WEB_REMIX) response → WireFeedPage. music.youtube.com serves its OWN renderer
// family (musicCarouselShelfRenderer / musicTwoRowItemRenderer / musicResponsiveListItemRenderer /
// musicShelfRenderer / musicCardShelfRenderer) that parse.ts's WEB walker doesn't know — so this is a
// sibling walker keyed on those names. Same discipline as parse.ts: a name-keyed walk (resilient to
// nesting changes), unknown renderers skipped not failed, quoted wire keys throughout, and the app
// stays dumb — every navigation target is an engine-composed app link round-tripped verbatim.

import type {
  WireChip,
  WireFeedItem,
  WireFeedPage,
  WireSection,
  WireSectionHeader,
  WireThumb,
} from "./wire";
import { asString, get, isObj, textOf, thumbVariants, walk } from "./scan";
import { isMixPlaylist, playlistUrl, searchChipToken, setDurationBadge } from "./parse";

/** An auto-generated album playlist ("OLAK5uy_…" — YouTube's album uploads). */
function isAlbumPlaylist(id: string): boolean {
  return id.indexOf("OLAK5uy_") === 0;
}

/** A home/explore carousel longer than this starts collapsed (a "Show more" toggle). */
const MUSIC_SHELF_COLLAPSE = 8;

// ---- App-internal urls -------------------------------------------------------------------------
// A music album/artist/playlist opens through a WEB_REMIX browse (ytmusic/browse), keyed by the
// card's own browseId — the id knowledge stays engine-side. Radios/plain playlists that the WEB
// yt/playlist path already serves keep their "/playlist?list=…" link so playback reuses that flow.

/** The app link opening a music detail surface (album/artist/playlist/category) via ytmusic/browse.
 *  [params] rides along for browses that need it (Charts, a mood/genre category). */
export function musicBrowseUrl(browseId: string, params?: string): string {
  return (
    "/ytmusic/browse?id=" +
    encodeURIComponent(browseId) +
    (params !== undefined ? "&params=" + encodeURIComponent(params) : "")
  );
}

/** The playlist behind a podcast show's browseId ("MPSP" + playlistId). */
export function showPlaylistId(browseId: string | undefined): string | undefined {
  return browseId !== undefined && browseId.indexOf("MPSP") === 0 ? browseId.slice(4) : undefined;
}

/** The video behind a podcast episode's browseId ("MPED" + videoId); that page lists nothing. */
function episodeVideoId(browseId: string | undefined): string | undefined {
  return browseId !== undefined && browseId.indexOf("MPED") === 0 ? browseId.slice(4) : undefined;
}

/** A music card's kind + detail link from its browse target. `video` items carry no url (they play);
 *  everything else opens a detail page. */
function browseTarget(
  browseId: string | undefined,
  pageType: string | undefined,
): { kind: string; url: string } | undefined {
  if (browseId === undefined || browseId === "") return undefined;
  const t = pageType ?? "";
  if (
    t.indexOf("ARTIST") >= 0 ||
    t.indexOf("USER_CHANNEL") >= 0 ||
    browseId.indexOf("UC") === 0 ||
    browseId.indexOf("MPLA") === 0
  ) {
    return { kind: "channel", url: musicBrowseUrl(browseId) };
  }
  // Albums (MPREb…), playlists (VL…), podcasts/audiobooks — all music browses.
  return { kind: "playlist", url: musicBrowseUrl(browseId) };
}

/** A music browse target that is an album (MPREb… / a pageType saying ALBUM) — the app marks it with
 *  the record glyph instead of the generic playlist one. */
function isAlbumBrowse(browseId: string | undefined, pageType: string | undefined): boolean {
  return (
    (pageType ?? "").indexOf("ALBUM") >= 0 ||
    (browseId !== undefined && browseId.indexOf("MPREb") === 0)
  );
}

/** The pageType tag that classifies a music browse target (album/artist/playlist/…). */
function pageTypeOf(endpoint: unknown): string | undefined {
  return asString(
    get(
      endpoint,
      "browseEndpoint",
      "browseEndpointContextSupportedConfigs",
      "browseEndpointContextMusicConfig",
      "pageType",
    ),
  );
}

/** Variants of a `{musicThumbnailRenderer:{thumbnail:{thumbnails:[…]}}}` node. */
function musicThumbs(node: unknown): WireFeedItem["thumbs"] {
  return withLargerArt(
    thumbVariants(get(node, "musicThumbnailRenderer", "thumbnail", "thumbnails")),
  );
}

/** Feed row/card (480) and Now Playing (720). List-row art comes at 60/120 only; Google's image
 *  server serves any `=wN-hN` size of the same image, capped at the original. */
const MUSIC_ART_SIZES = [480, 720];

function digitsEnd(s: string, from: number): number {
  let i = from;
  while (i < s.length && s.charCodeAt(i) >= 48 && s.charCodeAt(i) <= 57) i++;
  return i;
}

function withLargerArt(thumbs: WireThumb[] | undefined): WireThumb[] | undefined {
  const last = thumbs?.[thumbs.length - 1];
  if (thumbs === undefined || last === undefined) return thumbs;
  const url = last["url"];
  const at = url.lastIndexOf("=w");
  if (at < 0 || url.indexOf("googleusercontent.com/") < 0) return thumbs;
  const wEnd = digitsEnd(url, at + 2);
  const hAt = url.indexOf("-h", wEnd);
  if (hAt < 0) return thumbs;
  const hEnd = digitsEnd(url, hAt + 2);
  const w = parseInt(url.substring(at + 2, wEnd), 10);
  const h = parseInt(url.substring(hAt + 2, hEnd), 10);
  if (!(w > 0) || !(h > 0)) return thumbs;
  const out = thumbs.slice();
  for (const size of MUSIC_ART_SIZES) {
    if (size <= w) continue;
    const height = Math.round((size * h) / w);
    const sized =
      url.substring(0, at) +
      "=w" +
      size +
      url.substring(wEnd, hAt) +
      "-h" +
      height +
      url.substring(hEnd);
    out.push({ "url": sized, "w": size, "h": height });
  }
  return out;
}

// ---- Leaf items --------------------------------------------------------------------------------

/** The " • " segment of a text's runs holding its first channel (artist) link, verbatim — so
 *  "Artist A & Artist B" stays one credit — and that first artist's browseId. */
function artistOf(text: unknown): { name: string; browseId: string } | undefined {
  const runs = get(text, "runs");
  if (!Array.isArray(runs)) return undefined;
  let segment = "";
  let linked: string | undefined;
  for (const run of runs) {
    const t = asString(get(run, "text")) ?? "";
    if (t.trim() === "•") {
      if (linked !== undefined) break;
      segment = "";
      continue;
    }
    segment += t;
    const browseId = asString(get(run, "navigationEndpoint", "browseEndpoint", "browseId"));
    if (linked === undefined && browseId !== undefined && browseId.indexOf("UC") === 0) {
      linked = browseId;
    }
  }
  return linked !== undefined && segment !== "" ? { name: segment, browseId: linked } : undefined;
}

function setArtist(item: WireFeedItem, text: unknown): boolean {
  const artist = artistOf(text);
  if (artist === undefined) return false;
  item["channelTitle"] = artist.name;
  item["channelId"] = artist.browseId;
  item["channelUrl"] = musicBrowseUrl(artist.browseId);
  return true;
}

/**
 * `musicTwoRowItemRenderer` — the card shape on home/explore/artist pages (albums, playlists,
 * artists, and some video/song cards). Its tap endpoint is a browse (album/artist/playlist), a
 * watchPlaylistEndpoint (a radio/playlist to play), or a watchEndpoint (a single song/video).
 */
function twoRowItem(r: Record<string, unknown>): WireFeedItem | null {
  const title = textOf(r["title"]);
  if (title === undefined) return null;
  const nav = r["navigationEndpoint"];
  const thumbs = musicThumbs(r["thumbnailRenderer"]);
  const browseId = asString(get(nav, "browseEndpoint", "browseId"));

  const episodeId = episodeVideoId(browseId);
  const videoId = asString(get(nav, "watchEndpoint", "videoId")) ?? episodeId;
  if (videoId !== undefined) {
    const item: WireFeedItem = { "kind": "video", "id": videoId, "title": title };
    if (thumbs !== undefined) item["thumbs"] = thumbs;
    const sub = textOf(r["subtitle"]);
    if (sub !== undefined) item["subtitleLine1"] = sub;
    setArtist(item, r["subtitle"]);
    if (episodeId === undefined) item["musicVideo"] = true;
    return item;
  }

  // A radio/playlist to play (Mixes, "Start radio" cards): keep the /playlist?list=… link so the
  // existing yt/playlist flow serves it.
  const watchPlaylistId = asString(get(nav, "watchPlaylistEndpoint", "playlistId"));
  const playlistId =
    watchPlaylistId ??
    (browseId !== undefined && browseId.indexOf("VL") === 0 ? browseId.slice(2) : undefined);

  if (
    playlistId !== undefined &&
    (isMixPlaylist(playlistId) ||
      isAlbumPlaylist(playlistId) ||
      playlistId.indexOf("PL") === 0 ||
      playlistId.indexOf("RDCLAK") === 0 ||
      playlistId.indexOf("OLAK5uy") === 0)
  ) {
    const item: WireFeedItem = { "kind": "playlist", "id": playlistId, "title": title };
    if (isAlbumPlaylist(playlistId)) item["album"] = true;
    item["url"] = playlistUrl(playlistId, asString(get(nav, "watchPlaylistEndpoint", "videoId")));
    if (thumbs !== undefined) item["thumbs"] = thumbs;
    const sub = textOf(r["subtitle"]);
    if (sub !== undefined) item["subtitleLine1"] = sub;
    return item;
  }

  // Otherwise a music browse target — album / artist / user playlist.
  const pageType = pageTypeOf(nav);
  const target = browseTarget(browseId, pageType);
  if (target === undefined) return null;
  const item: WireFeedItem = { "kind": target.kind, "id": browseId ?? title, "title": title };
  item["url"] = target.url;
  if (target.kind === "channel" && browseId !== undefined) item["channelId"] = browseId;
  if (target.kind === "playlist" && isAlbumBrowse(browseId, pageType)) item["album"] = true;
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  const sub = textOf(r["subtitle"]);
  if (sub !== undefined) item["subtitleLine1"] = sub;
  return item;
}

/** The flexColumn texts of a `musicResponsiveListItemRenderer`, in order (title, artist, album, …). */
function flexTexts(r: Record<string, unknown>): string[] {
  const cols = r["flexColumns"];
  const out: string[] = [];
  if (!Array.isArray(cols)) return out;
  for (const c of cols) {
    const t = textOf(get(c, "musicResponsiveListItemFlexColumnRenderer", "text"));
    if (t !== undefined && t !== "") out.push(t);
  }
  return out;
}

/** The browse endpoint carried by a flexColumn run (an artist/album name that links out). */
function flexColumnBrowse(r: Record<string, unknown>, col: number): unknown {
  return get(
    r,
    "flexColumns",
    col,
    "musicResponsiveListItemFlexColumnRenderer",
    "text",
    "runs",
    0,
    "navigationEndpoint",
  );
}

/**
 * `musicResponsiveListItemRenderer` — the row shape (search song rows, album/playlist track lists,
 * and album/artist result rows). A row with a videoId is a playable song; one without is a browse
 * target (its navigationEndpoint, or its title run's, carries the browseId).
 */
function responsiveItem(r: Record<string, unknown>): WireFeedItem | null {
  const texts = flexTexts(r);
  const title = texts[0];
  if (title === undefined) return null;
  const thumbs = musicThumbs(r["thumbnail"]);

  const videoId =
    asString(get(r, "playlistItemData", "videoId")) ??
    asString(
      get(
        r,
        "overlay",
        "musicItemThumbnailOverlayRenderer",
        "content",
        "musicPlayButtonRenderer",
        "playNavigationEndpoint",
        "watchEndpoint",
        "videoId",
      ),
    ) ??
    asString(get(flexColumnBrowse(r, 0), "watchEndpoint", "videoId"));
  if (videoId !== undefined) {
    const item: WireFeedItem = { "kind": "video", "id": videoId, "title": title };
    if (thumbs !== undefined) item["thumbs"] = thumbs;
    if (texts[1] !== undefined) item["subtitleLine1"] = texts[1];
    if (texts[2] !== undefined) item["subtitleLine2"] = texts[2];
    const cols = r["flexColumns"];
    if (Array.isArray(cols)) {
      for (let col = 1; col < cols.length; col++) {
        if (setArtist(item, get(cols[col], "musicResponsiveListItemFlexColumnRenderer", "text")))
          break;
      }
    }
    item["musicVideo"] = true;
    return item;
  }

  // No videoId → an album/artist/playlist row. Its browse target is on the row endpoint or its
  // title run.
  const nav = r["navigationEndpoint"] ?? flexColumnBrowse(r, 0);
  const browseId = asString(get(nav, "browseEndpoint", "browseId"));
  const pageType = pageTypeOf(nav);
  const target = browseTarget(browseId, pageType);
  if (target === undefined) return null;
  const item: WireFeedItem = { "kind": target.kind, "id": browseId ?? title, "title": title };
  item["url"] = target.url;
  if (target.kind === "channel" && browseId !== undefined) item["channelId"] = browseId;
  if (target.kind === "playlist" && isAlbumBrowse(browseId, pageType)) item["album"] = true;
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  if (texts[1] !== undefined) item["subtitleLine1"] = texts[1];
  if (texts[2] !== undefined) item["subtitleLine2"] = texts[2];
  return item;
}

/** `musicMultiRowListItemRenderer` — a podcast episode row on a show page. */
function episodeItem(r: Record<string, unknown>): WireFeedItem | null {
  const title = textOf(r["title"]);
  const videoId = asString(get(r, "onTap", "watchEndpoint", "videoId"));
  if (title === undefined || videoId === undefined) return null;
  const item: WireFeedItem = { "kind": "video", "id": videoId, "title": title };
  const thumbs = musicThumbs(r["thumbnail"]);
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  const sub = textOf(r["subtitle"]);
  if (sub !== undefined) item["subtitleLine1"] = sub;
  const progress = get(r, "playbackProgress", "musicPlaybackProgressRenderer");
  setDurationBadge(item, asString(get(progress, "durationText", "runs", 1, "text")));
  return item;
}

// The top-level feed entry points that are their OWN tabs (or the section itself) in the app — the
// app never renders them as nav buttons, so Explore's redundant top entry grid (New releases /
// Charts / Moods & genres, all now reachable as tabs / the tiles below) collapses to nothing.
const TAB_BROWSE_IDS: Record<string, true> = {
  "FEmusic_home": true,
  "FEmusic_explore": true,
  "FEmusic_new_releases": true,
  "FEmusic_charts": true,
  "FEmusic_moods_and_genres": true,
};

/**
 * A `musicNavigationButtonRenderer` — an Explore entry button (New releases / Charts / Moods &
 * genres) or a mood/genre category tile. A browse target with no artwork; opens via ytmusic/browse
 * (kind "tag" → rendered as a pill, a tap navigates). The top-level entry buttons ([TAB_BROWSE_IDS])
 * are dropped — they duplicate the app's own tabs and the mood/genre tiles.
 */
function navButtonItem(r: Record<string, unknown>): WireFeedItem | null {
  const title = textOf(r["buttonText"]);
  const be = isObj(get(r, "clickCommand", "browseEndpoint"))
    ? get(r, "clickCommand", "browseEndpoint")
    : get(r, "navigationEndpoint", "browseEndpoint");
  const browseId = asString(get(be, "browseId"));
  if (title === undefined || browseId === undefined) return null;
  if (TAB_BROWSE_IDS[browseId] === true) return null;
  const item: WireFeedItem = { "kind": "tag", "id": browseId, "title": title };
  item["url"] = musicBrowseUrl(browseId, asString(get(be, "params")));
  // The tile's accent colour (YouTube's per-mood/genre stripe), a packed ARGB int → 8-hex AARRGGBB.
  const color = get(r, "solid", "leftStripeColor");
  if (typeof color === "number") item["accent"] = (color >>> 0).toString(16).padStart(8, "0");
  return item;
}

/** Whether every item is a navigation tag — such a run renders as a wrap of pills, not cards. */
function allTags(items: WireFeedItem[]): boolean {
  if (items.length === 0) return false;
  for (const it of items) if (it["kind"] !== "tag") return false;
  return true;
}

/** Parse one music leaf renderer into a feed item, or null if `key` is not a known leaf. */
function musicLeaf(key: string, value: unknown): WireFeedItem | null {
  if (!isObj(value)) return null;
  if (key === "musicTwoRowItemRenderer") return twoRowItem(value);
  if (key === "musicResponsiveListItemRenderer") return responsiveItem(value);
  if (key === "musicMultiRowListItemRenderer") return episodeItem(value);
  if (key === "musicNavigationButtonRenderer") return navButtonItem(value);
  return null;
}

/** Collect every music leaf item nested under `node`, in document order. */
function collectMusicItems(node: unknown): WireFeedItem[] {
  const items: WireFeedItem[] = [];
  walk(node, (key, value) => {
    const it = musicLeaf(key, value);
    if (it !== null) {
      items.push(it);
      return true;
    }
    return;
  });
  return items;
}

// ---- Sections ----------------------------------------------------------------------------------

/** A carousel/shelf title + its optional "more" CTA → a section header. A CTA that opens the player
 *  rather than a page ("Play all") plays the shelf's own songs in order — its params encode exactly them. */
function shelfHeader(
  titleNode: unknown,
  moreEndpoint: unknown,
  moreText?: unknown,
): WireSectionHeader | undefined {
  const title = textOf(titleNode);
  if (title === undefined) return undefined;
  const header: WireSectionHeader = { "title": title };
  const browseId = asString(get(moreEndpoint, "browseEndpoint", "browseId"));
  if (browseId !== undefined) {
    header["ctaDeeplink"] = musicBrowseUrl(browseId);
  } else if (
    get(moreEndpoint, "watchEndpoint") !== undefined ||
    get(moreEndpoint, "watchPlaylistEndpoint") !== undefined
  ) {
    header["ctaAction"] = "playAll";
    const label = textOf(moreText);
    if (label !== undefined) header["ctaText"] = label;
  }
  return header;
}

/** The page continuation token (new continuationItemRenderer or legacy nextContinuationData). */
function continuationOf(root: Record<string, unknown>): string | undefined {
  let token: string | undefined;
  walk(root, (key, value) => {
    if (token !== undefined) return true;
    if (key === "continuationItemRenderer") {
      token = asString(get(value, "continuationEndpoint", "continuationCommand", "token"));
      return true;
    }
    if (key === "nextContinuationData") {
      token = asString(get(value, "continuation"));
      return true;
    }
    return;
  });
  return token;
}

// A music browse filter chip's "token" (opaque to the app): the mood/genre chips on Home/Explore
// re-BROWSE the same feed with fresh params (the engine owns the browseId), so — like search chips —
// they aren't a server continuation. The token carries just the params; ytmusic/home|explore supply
// their own browseId when they decode it. Version-prefixed so a format change can't be misread.
const MUSIC_CHIP_PREFIX = "ymc1:";

export function musicChipToken(params: string): string {
  return MUSIC_CHIP_PREFIX + params;
}

/** Decode a [musicChipToken] to its params; null for any other (server) continuation string. */
export function parseMusicChipToken(token: string): string | null {
  return token.indexOf(MUSIC_CHIP_PREFIX) === 0 ? token.slice(MUSIC_CHIP_PREFIX.length) : null;
}

// A library filter chip (Playlists / Songs / Albums / …) re-BROWSES a SIBLING feed — a different
// browseId, no params — so its token carries the browseId; ytmusic/library decodes it back.
const MUSIC_LIBRARY_CHIP_PREFIX = "ymlc1:";

export function musicLibraryChipToken(browseId: string): string {
  return MUSIC_LIBRARY_CHIP_PREFIX + browseId;
}

/** Decode a [musicLibraryChipToken] to its browseId; null for any other continuation string. */
export function parseMusicLibraryChipToken(token: string): string | null {
  return token.indexOf(MUSIC_LIBRARY_CHIP_PREFIX) === 0
    ? token.slice(MUSIC_LIBRARY_CHIP_PREFIX.length)
    : null;
}

/** The first `browseEndpoint` under `node` (chips nest theirs inside a commandExecutorCommand). */
function firstBrowseEndpoint(node: unknown): unknown {
  let found: unknown;
  walk(node, (key, value) => {
    if (found !== undefined) return true;
    if (key === "browseEndpoint" && isObj(value)) {
      found = value;
      return true;
    }
    return;
  });
  return found;
}

/**
 * A `chipCloudChipRenderer` filter chip. YouTube Music has NO filter dialog — filters are chips, each
 * an independent request, not a continuation:
 * - **search** chips carry a `searchEndpoint` (query + params) → wrapped as the WEB [searchChipToken];
 * - **home/explore** mood/genre chips carry a `browseEndpoint` (browseId + params) → [musicChipToken];
 * - **library** chips carry a params-LESS `browseEndpoint` to a sibling feed → [musicLibraryChipToken];
 *   a SELECTED one gets its deselect target instead, so tapping it again clears the filter.
 * Either way the app's token-only chip row re-runs the feed filtered (ytmusic/search|home|explore|
 * library decode the token). Chips with no endpoint (the icon-only "Clear filters") are dropped.
 */
function filterChip(c: Record<string, unknown>): WireChip | null {
  const title = textOf(c["text"]);
  if (title === undefined) return null;
  const chip: WireChip = { "title": title };
  const query = asString(get(c, "navigationEndpoint", "searchEndpoint", "query"));
  if (query !== undefined) {
    chip["token"] = searchChipToken(
      asString(get(c, "navigationEndpoint", "searchEndpoint", "params")),
      query,
    );
  } else {
    const be = firstBrowseEndpoint(c["navigationEndpoint"]);
    const params = asString(get(be, "params"));
    if (params !== undefined) {
      chip["token"] = musicChipToken(params);
    } else {
      const target = c["isSelected"] === true ? firstBrowseEndpoint(c["onDeselectedCommand"]) : be;
      const browseId = asString(get(target, "browseId"));
      if (browseId === undefined) return null;
      chip["token"] = musicLibraryChipToken(browseId);
    }
  }
  if (c["isSelected"] === true) chip["selected"] = true;
  return chip;
}

/**
 * A `musicSortFilterButtonRenderer` (the library's "Recent activity ⌄" control) → the page's sort
 * options. Each menu option's selectedCommand carries a `reloadContinuationData` continuation — a
 * REAL server continuation re-serving the page in that order, so an option's token rides the normal
 * continuation path. The button's own title is the currently applied order.
 */
function sortOptions(s: Record<string, unknown>): WireChip[] {
  const current = textOf(s["title"]);
  const options: WireChip[] = [];
  walk(s["menu"], (key, value) => {
    if (key !== "musicMultiSelectMenuItemRenderer" || !isObj(value)) return;
    const title = textOf(value["title"]);
    if (title === undefined) return true;
    let token: string | undefined;
    walk(value["selectedCommand"], (k, v) => {
      if (token !== undefined) return true;
      if (k === "reloadContinuationData") {
        token = asString(get(v, "continuation"));
        return true;
      }
      return;
    });
    if (token === undefined) return true;
    const option: WireChip = { "title": title, "token": token };
    if (title === current) option["selected"] = true;
    options.push(option);
    return true;
  });
  return options;
}

/**
 * An artist card's Mix/radio playlist id, from its action buttons
 * (`buttonRenderer.command.watchPlaylistEndpoint.playlistId`). Prefer the "Mix" radio (RDEM…) over
 * the "Shuffle" list (RDAO…); any RD… otherwise. undefined when the card offers no radio.
 */
function cardMixPlaylistId(buttons: unknown): string | undefined {
  if (!Array.isArray(buttons)) return undefined;
  let fallback: string | undefined;
  for (const b of buttons) {
    const pid = asString(
      get(b, "buttonRenderer", "command", "watchPlaylistEndpoint", "playlistId"),
    );
    if (pid === undefined) continue;
    if (pid.indexOf("RDEM") === 0) return pid;
    if (fallback === undefined && pid.indexOf("RD") === 0) fallback = pid;
  }
  return fallback;
}

/**
 * Collect a music page as sections. `musicCarouselShelfRenderer` (home/explore/artist shelves)
 * becomes a titled grid (cards); `musicShelfRenderer` (search groups, track lists) a titled list; a
 * `musicCardShelfRenderer` (search "top result") a one-item lead; the loose rows (search's
 * `itemSectionRenderer` results, an album's `musicPlaylistShelfRenderer` tracks) collect into a
 * default LIST section. Search filter chips (`chipCloudChipRenderer`) come back as page chips.
 * Everything descends from the browse/search content root.
 */
export function collectMusicPage(root: Record<string, unknown>): WireFeedPage {
  const sections: WireSection[] = [];
  const chips: WireChip[] = [];
  let sort: WireChip[] = [];
  let loose: WireFeedItem[] = [];
  const flushLoose = (layout: string): void => {
    if (loose.length > 0) {
      sections.push({ "layout": layout, "items": loose });
      loose = [];
    }
  };

  walk(root, (key, value) => {
    if (!isObj(value)) return;

    if (key === "chipCloudChipRenderer") {
      const chip = filterChip(value);
      if (chip !== null) chips.push(chip);
      return true;
    }

    if (key === "musicSortFilterButtonRenderer") {
      const options = sortOptions(value);
      if (options.length > 0) sort = options;
      return true;
    }

    if (key === "musicCarouselShelfRenderer" || key === "musicImmersiveCarouselShelfRenderer") {
      const headerRenderer = get(value, "header", "musicCarouselShelfBasicHeaderRenderer");
      const moreButton = get(headerRenderer, "moreContentButton", "buttonRenderer");
      const header = shelfHeader(
        get(headerRenderer, "title"),
        get(moreButton, "navigationEndpoint"),
        get(moreButton, "text"),
      );
      const items = collectMusicItems(value["contents"]);
      if (items.length > 0) {
        flushLoose("list");
        // A shelf of nav buttons (Explore's "Moods & genres") is a wrap of pills, not cards.
        const tags = allTags(items);
        const section: WireSection = { "layout": tags ? "tags" : "grid", "items": items };
        if (header !== undefined) section["header"] = header;
        // A long shelf starts collapsed behind a "Show more" toggle so it doesn't bury the rest of
        // the feed — home's "Quick picks" card strip and Explore's 37-tile "Moods & genres" pill wrap
        // alike; short shelves stay fully shown.
        if (items.length > MUSIC_SHELF_COLLAPSE) section["collapsible"] = true;
        sections.push(section);
      }
      return true;
    }

    // A grid of nav buttons (Explore's top New releases / Charts / Moods & genres entries) → a pill
    // wrap. Other grids (album/new-release card grids) fall through to collect as loose cards.
    if (key === "gridRenderer") {
      const items = collectMusicItems(value["items"]);
      if (items.length > 0 && allTags(items)) {
        flushLoose("list");
        const section: WireSection = { "layout": "tags", "items": items };
        const header = shelfHeader(get(value, "header", "gridHeaderRenderer", "title"), undefined);
        if (header !== undefined) section["header"] = header;
        sections.push(section);
        return true;
      }
      return;
    }

    if (key === "musicShelfRenderer") {
      const header = shelfHeader(value["title"], get(value, "bottomEndpoint"));
      const items = collectMusicItems(value["contents"]);
      if (items.length > 0) {
        flushLoose("list");
        const section: WireSection = { "layout": "list", "items": items };
        if (header !== undefined) section["header"] = header;
        const cont = asString(
          get(value, "continuations", 0, "nextContinuationData", "continuation"),
        );
        if (cont !== undefined) section["continuation"] = cont;
        sections.push(section);
      }
      return true;
    }

    if (key === "musicCardShelfRenderer") {
      // The search "top result" card. Its tap target is a song (videoId) or a browse (album/artist).
      // For an ARTIST, emit TWO items: the artist channel AND their Mix/radio (a playable playlist
      // from the card's action buttons) — the same pairing WEB search's artist card produces.
      const title = textOf(value["title"]);
      if (title !== undefined) {
        const tap = value["title"];
        const videoId =
          asString(get(tap, "runs", 0, "navigationEndpoint", "watchEndpoint", "videoId")) ??
          asString(get(value, "onTap", "watchEndpoint", "videoId"));
        const thumbs = musicThumbs(value["thumbnail"]);
        const sub = textOf(value["subtitle"]);
        const items: WireFeedItem[] = [];
        if (videoId !== undefined) {
          const item: WireFeedItem = {
            "kind": "video",
            "id": videoId,
            "title": title,
            "musicVideo": true,
          };
          if (thumbs !== undefined) item["thumbs"] = thumbs;
          if (sub !== undefined) item["subtitleLine1"] = sub;
          setArtist(item, value["subtitle"]);
          items.push(item);
        } else {
          const nav = get(tap, "runs", 0, "navigationEndpoint") ?? value["onTap"];
          const browseId = asString(get(nav, "browseEndpoint", "browseId"));
          const pageType = pageTypeOf(nav);
          const target = browseTarget(browseId, pageType);
          if (target !== undefined) {
            const item: WireFeedItem = {
              "kind": target.kind,
              "id": browseId ?? title,
              "title": title,
              "url": target.url,
            };
            if (target.kind === "channel" && browseId !== undefined) item["channelId"] = browseId;
            if (target.kind === "playlist" && isAlbumBrowse(browseId, pageType))
              item["album"] = true;
            if (thumbs !== undefined) item["thumbs"] = thumbs;
            if (sub !== undefined) item["subtitleLine1"] = sub;
            items.push(item);
            // The artist's radio, from the card's "Mix"/"Shuffle" buttons — a playable RD… playlist.
            const mixId =
              target.kind === "channel" ? cardMixPlaylistId(value["buttons"]) : undefined;
            if (mixId !== undefined) {
              const mix: WireFeedItem = {
                "kind": "playlist",
                "id": mixId,
                "title": "Mix - " + title,
              };
              mix["url"] = playlistUrl(mixId);
              if (thumbs !== undefined) mix["thumbs"] = thumbs;
              items.push(mix);
            }
          }
        }
        if (items.length > 0) {
          flushLoose("list");
          sections.push({ "layout": "list", "items": items });
        }
      }
      return true;
    }

    // A leaf that sits loose (album track lists, grid pages): collect it into the default section.
    const it = musicLeaf(key, value);
    if (it !== null) {
      loose.push(it);
      return true;
    }
    return;
  });
  flushLoose("list");

  const page: WireFeedPage = { "sections": sections };
  const cont = continuationOf(root);
  if (cont !== undefined) page["continuation"] = cont;
  if (chips.length > 0) page["chips"] = chips;
  if (sort.length > 0) page["sort"] = sort;
  return page;
}
