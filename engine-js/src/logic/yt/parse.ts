// InnerTube response → WireFeedPage. Field paths verified live 2026-07-12 (see docs/innertube/);
// both renderer generations are parsed: classic `*Renderer` (inline fields) and the migrated
// `lockupViewModel` (inline metadata viewModel — NOT frameworkUpdates, verified on /search and
// /next). Unknown renderer kinds are skipped, never a page failure — resilience over completeness,
// and this file ships remotely when YouTube moves things again.

import type {
  WireChip,
  WireFeedItem,
  WireFeedPage,
  WireFilterGroup,
  WireFilterOption,
  WirePageHeader,
  WirePageTab,
  WireSection,
  WireSectionHeader,
} from "./wire";
import { asString, get, isObj, textOf, thumbVariants, thumbsOf, walk } from "./scan";
import { hostVersionCode } from "../../protocol/host";

// The engine owns text placement (see wire.ts): each builder maps the semantic pieces a renderer
// yields onto the item's two generic subtitle lines. Assign the first two present strings, so a
// missing piece collapses the line above it rather than leaving a blank row.
function setLines(item: WireFeedItem, candidates: Array<string | undefined>): void {
  const present = candidates.filter((s): s is string => s !== undefined && s !== "");
  if (present[0] !== undefined) item["subtitleLine1"] = present[0];
  if (present[1] !== undefined) item["subtitleLine2"] = present[1];
}

/** Join the present parts with " · " (YouTube's metadata separator); "" if none. */
function joinDot(parts: Array<string | undefined>): string {
  return parts.filter((s): s is string => s !== undefined && s !== "").join(" · ");
}

// The one place a duration badge lands on an item: the verbatim text plus its parsed-ms twin —
// the app renders the text and never parses it (wire contract), so the parse lives here. A
// non-clock badge routed in ("LIVE") sets neither; an already-set badge is never overwritten.
export function setDurationBadge(item: WireFeedItem, text: string | undefined): void {
  if (text === undefined || item["duration"] !== undefined) return;
  item["duration"] = text;
  if (!/^\d+(:\d{1,2}){1,2}$/.test(text)) return;
  let seconds = 0;
  for (const part of text.split(":")) seconds = seconds * 60 + Number(part);
  item["durationMs"] = seconds * 1000;
}

function videoItem(r: Record<string, unknown>): WireFeedItem | null {
  const id = asString(r["videoId"]);
  const title = textOf(r["title"]) ?? textOf(get(r, "headline"));
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "video", "id": id, "title": title };
  const byline =
    textOf(r["ownerText"]) ?? textOf(r["shortBylineText"]) ?? textOf(r["longBylineText"]);
  const channelId =
    asString(get(r["ownerText"], "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId")) ??
    asString(
      get(r["shortBylineText"], "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId"),
    );
  if (channelId !== undefined) {
    item["channelId"] = channelId;
    item["channelUrl"] = channelUrl(channelId);
  }
  if (byline !== undefined) item["channelTitle"] = byline;
  const thumbs = thumbsOf(r["thumbnail"]);
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  // Shelf videos (gridVideoRenderer, live 2026-07-22) carry no lengthText — the duration is the
  // thumbnail's time-status overlay.
  setDurationBadge(item, textOf(r["lengthText"]) ?? timeOverlayOf(r["thumbnailOverlays"]));
  const views = textOf(r["viewCountText"]) ?? textOf(r["shortViewCountText"]);
  const published = textOf(r["publishedTimeText"]);
  if (isLiveBadged(r["badges"]) || isLiveOverlay(r["thumbnailOverlays"])) item["live"] = true;
  if (hasMusicOverlay(r["thumbnailOverlays"])) item["musicVideo"] = true;
  setWatchedPercent(item, resumeOverlayOf(r["thumbnailOverlays"]));
  const ownerBadge = ownerBadgeOf(r["ownerBadges"]);
  if (ownerBadge !== undefined) item["ownerBadge"] = ownerBadge;
  setLines(item, [byline, joinDot([views, published])]);
  return item;
}

/** The time-status overlay carrying the MUSIC icon marks a music video (verified live 2026-07-22). */
function hasMusicOverlay(overlays: unknown): boolean {
  if (!Array.isArray(overlays)) return false;
  for (const o of overlays) {
    if (asString(get(o, "thumbnailOverlayTimeStatusRenderer", "icon", "iconType")) === "MUSIC")
      return true;
  }
  return false;
}

function setWatchedPercent(item: WireFeedItem, percent: unknown): void {
  if (typeof percent !== "number") return;
  const rounded = Math.min(100, Math.round(percent));
  if (rounded > 0) item["watchedPercent"] = rounded;
}

function resumeOverlayOf(overlays: unknown): unknown {
  if (!Array.isArray(overlays)) return undefined;
  for (const o of overlays) {
    const percent = get(o, "thumbnailOverlayResumePlaybackRenderer", "percentDurationWatched");
    if (percent !== undefined) return percent;
  }
  return undefined;
}

// ---- App-internal urls -------------------------------------------------------------------------
// The engine composes every navigation target as an app link and the app round-trips it VERBATIM
// (yt/playlist, yt/channel). The query is engine-private state — e.g. a Mix's seed video rides
// along as `v=` — so all YouTube id knowledge stays on this side of the wire.

/** The app link opening a playlist detail. [seed] is the radio seed video when the source renderer
 *  declares one; yt/playlist reads it back out of the url. */
export function playlistUrl(id: string, seed?: string): string {
  return "/playlist?list=" + id + (seed !== undefined ? "&v=" + seed : "");
}

/** The app link opening a channel detail, optionally pinned to a server tab ([slug] names the
 *  tab in the path — "/channel/<id>/videos" — so yt/channel classifies it without decoding). */
export function channelUrl(id: string, params?: string, slug?: string): string {
  return (
    "/channel/" +
    id +
    (slug !== undefined ? "/" + slug : "") +
    (params !== undefined ? "?params=" + encodeURIComponent(params) : "")
  );
}

// ---- Channel tabs ------------------------------------------------------------------------------
// A tab is classified by its PUBLIC web url (endpoint.commandMetadata.webCommandMetadata.url,
// "/@handle/videos" — verified live 2026-07-23): the last path segment is a locale-independent
// slug, so no title matching and no params decoding. Only content tabs are kept; the slug also
// rides the app link ("/channel/<UC…>/<slug>?params=…") so yt/channel can read it back. A tab
// with no web url fails OPEN (kept, no slug).

const CONTENT_TAB_SLUGS: Record<string, true> = {
  "featured": true,
  "videos": true,
  "shorts": true,
  "streams": true,
  "playlists": true,
  "releases": true,
  "podcasts": true,
};

function tabWebSlug(endpoint: unknown): string | undefined {
  const url = asString(get(endpoint, "commandMetadata", "webCommandMetadata", "url"));
  if (url === undefined) return undefined;
  const q = url.indexOf("?");
  const path = q < 0 ? url : url.slice(0, q);
  const slug = path.slice(path.lastIndexOf("/") + 1);
  return slug === "" ? undefined : slug;
}

/** An auto-generated album playlist ("OLAK5uy_…" — YouTube's album uploads). */
function isAlbumPlaylist(id: string): boolean {
  return id.indexOf("OLAK5uy_") === 0;
}

/** A Mix/radio playlist — /browse refuses these; the watch-page /next API serves them. RDCLAK…
 *  ("album radio") lists ARE browsable and stay on the browse path (legacy canFetchNonMixPage). */
export function isMixPlaylist(id: string): boolean {
  return id.indexOf("RD") === 0 && id.indexOf("RDCLAK") !== 0;
}

/** The seed videoId embedded in a Mix id (RD<videoId> / RDAMVM<videoId>); undefined for RDEM…
 *  mixes, which start their radio from the playlistId alone. */
export function mixSeedVideoId(id: string): string | undefined {
  if (id.indexOf("RDAMVM") === 0 && id.length === 6 + 11) return id.slice(6);
  if (id.indexOf("RD") === 0 && id.length === 2 + 11) return id.slice(2);
  return undefined;
}

// A search chip's "token" (opaque to the app): the chip re-RUNS the search with the endpoint's
// query+params — there is no server continuation. Params first (URL-safe, never contains ":"),
// query verbatim after the separator.
const SEARCH_CHIP_PREFIX = "ysq1:";

export function searchChipToken(params: string | undefined, query: string): string {
  return SEARCH_CHIP_PREFIX + (params ?? "") + ":" + query;
}

/** Decode a [searchChipToken]; null for any other (server) continuation string. */
export function parseSearchChipToken(token: string): { query: string; params?: string } | null {
  if (token.indexOf(SEARCH_CHIP_PREFIX) !== 0) return null;
  const rest = token.slice(SEARCH_CHIP_PREFIX.length);
  const sep = rest.indexOf(":");
  if (sep < 0) return null;
  const out: { query: string; params?: string } = { query: rest.slice(sep + 1) };
  const params = rest.slice(0, sep);
  if (params !== "") out.params = params;
  return out;
}

// A browse-typed chip's "token" (opaque to the app): the chip re-BROWSES the page with fresh
// params (a channel sort, a playlist's Videos/Shorts filter; empty = none) — there is no server
// continuation. yt/channel and yt/playlist decode it and take the browseId from the round-tripped url.
const BROWSE_PARAMS_PREFIX = "ychp1:";

export function browseParamsToken(params: string): string {
  return BROWSE_PARAMS_PREFIX + params;
}

/** Decode a [browseParamsToken]; null for any other (server) continuation string. */
export function parseBrowseParamsToken(token: string): string | null {
  if (token.indexOf(BROWSE_PARAMS_PREFIX) !== 0) return null;
  return token.slice(BROWSE_PARAMS_PREFIX.length);
}

// An owned playlist's sort-menu option (opaque to the app): YouTube sorts by SAVING the playlist's
// order (edit_playlist ACTION_SET_PLAYLIST_VIDEO_ORDER), then the list is re-browsed — there is no
// server continuation. "<playlistId>:<order>:<params>"; yt/playlist decodes it.
const PLAYLIST_ORDER_PREFIX = "yplo1:";

export function playlistOrderToken(playlistId: string, order: number, params: string): string {
  return PLAYLIST_ORDER_PREFIX + playlistId + ":" + order + ":" + params;
}

/** The [playlistOrderToken] of the first ACTION_SET_PLAYLIST_VIDEO_ORDER edit under [node]. */
function playlistOrderTokenIn(node: unknown): string | undefined {
  let token: string | undefined;
  walk(node, (k, v) => {
    if (token !== undefined) return true;
    if (k !== "playlistEditEndpoint" || !isObj(v)) return;
    const order = get(v, "actions", 0, "playlistVideoOrder");
    const playlistId = asString(v["playlistId"]);
    if (
      get(v, "actions", 0, "action") === "ACTION_SET_PLAYLIST_VIDEO_ORDER" &&
      typeof order === "number" &&
      playlistId !== undefined
    ) {
      token = playlistOrderToken(playlistId, order, asString(v["params"]) ?? "");
    }
    return true;
  });
  return token;
}

/** Decode a [playlistOrderToken]; null for any other continuation string. */
export function parsePlaylistOrderToken(
  token: string,
): { playlistId: string; order: number; params: string } | null {
  if (token.indexOf(PLAYLIST_ORDER_PREFIX) !== 0) return null;
  const parts = token.slice(PLAYLIST_ORDER_PREFIX.length).split(":");
  const playlistId = parts[0];
  const order = Number(parts[1]);
  if (parts.length !== 3 || playlistId === undefined || playlistId === "" || !(order >= 0)) {
    return null;
  }
  return { playlistId: playlistId, order: order, params: parts[2] ?? "" };
}

// The engine-fabricated Mix continuation (opaque to the app): an infinite playlist panel pages by
// re-calling /next with (last videoId, playlistId, index) and dropping the overlap — there is no
// server token. Version-prefixed so a future format change can't be misread.
const MIX_TOKEN_PREFIX = "ymix1:";

export function mixToken(index: number, playlistId: string, videoId: string): string {
  return MIX_TOKEN_PREFIX + String(index) + ":" + playlistId + ":" + videoId;
}

/** Decode a [mixToken]; null for any other (server) continuation string. */
export function parseMixToken(
  token: string,
): { index: number; playlistId: string; videoId: string } | null {
  if (token.indexOf(MIX_TOKEN_PREFIX) !== 0) return null;
  const rest = token.slice(MIX_TOKEN_PREFIX.length);
  const a = rest.indexOf(":");
  const b = rest.indexOf(":", a + 1);
  if (a < 0 || b < 0) return null;
  const index = parseInt(rest.slice(0, a), 10);
  const playlistId = rest.slice(a + 1, b);
  const videoId = rest.slice(b + 1);
  if (!isFinite(index) || playlistId === "" || videoId === "") return null;
  return { index: index, playlistId: playlistId, videoId: videoId };
}

/**
 * A lockup byline's badge attachment (metadataRows parts' `text.attachmentRuns` image):
 * AUDIO_BADGE → "artist", CHECK_CIRCLE* → "verified". Scans all parts, artist wins over verified.
 */
function lockupOwnerBadge(rows: unknown): string | undefined {
  if (!Array.isArray(rows)) return undefined;
  let verified = false;
  for (const row of rows) {
    const parts = get(row, "metadataParts");
    if (!Array.isArray(parts)) continue;
    for (const p of parts) {
      const runs = get(p, "text", "attachmentRuns");
      if (!Array.isArray(runs)) continue;
      for (const r of runs) {
        const name =
          asString(
            get(
              r,
              "element",
              "type",
              "imageType",
              "image",
              "sources",
              0,
              "clientResource",
              "imageName",
            ),
          ) ?? "";
        if (name.indexOf("AUDIO_BADGE") >= 0) return "artist";
        if (name.indexOf("CHECK_CIRCLE") >= 0) verified = true;
      }
    }
  }
  return verified ? "verified" : undefined;
}

/** ownerBadges → "artist" (Official Artist Channel) / "verified" / undefined. */
function ownerBadgeOf(badges: unknown): string | undefined {
  if (!Array.isArray(badges)) return undefined;
  for (const b of badges) {
    const style = asString(get(b, "metadataBadgeRenderer", "style")) ?? "";
    if (style.indexOf("VERIFIED_ARTIST") >= 0) return "artist";
    if (style.indexOf("VERIFIED") >= 0) return "verified";
  }
  return undefined;
}

function isLiveBadged(badges: unknown): boolean {
  if (!Array.isArray(badges)) return false;
  for (const b of badges) {
    if (asString(get(b, "metadataBadgeRenderer", "style")) === "BADGE_STYLE_TYPE_LIVE_NOW")
      return true;
  }
  return false;
}

/** The duration text of a `thumbnailOverlayTimeStatusRenderer` (skipping the LIVE style). */
function timeOverlayOf(overlays: unknown): string | undefined {
  if (!Array.isArray(overlays)) return undefined;
  for (const o of overlays) {
    const r = get(o, "thumbnailOverlayTimeStatusRenderer");
    if (!isObj(r)) continue;
    if (asString(r["style"]) === "LIVE") continue;
    const text = textOf(r["text"]);
    if (text !== undefined) return text;
  }
  return undefined;
}

function isLiveOverlay(overlays: unknown): boolean {
  if (!Array.isArray(overlays)) return false;
  for (const o of overlays) {
    if (asString(get(o, "thumbnailOverlayTimeStatusRenderer", "style")) === "LIVE") return true;
  }
  return false;
}

function playlistItem(r: Record<string, unknown>): WireFeedItem | null {
  const id = asString(r["playlistId"]);
  const title = textOf(r["title"]);
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "playlist", "id": id, "title": title };
  if (isAlbumPlaylist(id)) item["album"] = true;
  // Radio renderers declare their seed video on the tap endpoint — ride it along in the url.
  item["url"] = playlistUrl(id, asString(get(r, "navigationEndpoint", "watchEndpoint", "videoId")));
  const byline = textOf(r["shortBylineText"]) ?? textOf(r["longBylineText"]);
  const thumbs =
    thumbsOf(get(r, "thumbnails", 0)) ??
    thumbsOf(r["thumbnail"]) ??
    thumbsOf(get(r, "thumbnailRenderer", "playlistVideoThumbnailRenderer", "thumbnail"));
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  const count = textOf(r["videoCountText"]) ?? asString(r["videoCount"]);
  if (count !== undefined) item["itemCount"] = count;
  setLines(item, [byline, count]);
  return item;
}

function channelItem(r: Record<string, unknown>): WireFeedItem | null {
  const id = asString(r["channelId"]);
  const title = textOf(r["title"]);
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "channel", "id": id, "title": title, "channelId": id };
  item["url"] = channelUrl(id);
  // The two count fields swapped meaning mid-2024; whichever mentions subscribers wins.
  const a = textOf(r["subscriberCountText"]);
  const b = textOf(r["videoCountText"]);
  const subscribers = [a, b].filter((s) => s !== undefined && /subscriber/i.test(s))[0] ?? a ?? b;
  const thumbs = thumbsOf(r["thumbnail"]);
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  setLines(item, [subscribers]);
  return item;
}

// lockupViewModel: contentId + contentType discriminate; display data sits inline under
// metadata.lockupMetadataViewModel (title.content, metadataRows) and contentImage.*ViewModel.
function lockupItem(lv: Record<string, unknown>): WireFeedItem | null {
  const id = asString(lv["contentId"]);
  const type = asString(lv["contentType"]) ?? "";
  const title = textOf(get(lv, "metadata", "lockupMetadataViewModel", "title"));
  if (id === undefined || title === undefined) return null;
  const kind =
    type.indexOf("VIDEO") >= 0
      ? "video"
      : type.indexOf("CHANNEL") >= 0
        ? "channel"
        : type.indexOf("PLAYLIST") >= 0 ||
            type.indexOf("ALBUM") >= 0 ||
            type.indexOf("PODCAST") >= 0 ||
            type.indexOf("MIX") >= 0
          ? "playlist"
          : null;
  if (kind === null) return null;
  const item: WireFeedItem = { "kind": kind, "id": id, "title": title };
  if (kind === "playlist" && isAlbumPlaylist(id)) item["album"] = true;
  if (kind === "playlist") {
    item["url"] = playlistUrl(
      id,
      asString(
        get(
          lv,
          "rendererContext",
          "commandContext",
          "onTap",
          "innertubeCommand",
          "watchEndpoint",
          "videoId",
        ),
      ),
    );
  } else if (kind === "channel") {
    item["url"] = channelUrl(id);
  }

  const thumbs =
    thumbVariants(get(lv, "contentImage", "thumbnailViewModel", "image", "sources")) ??
    thumbVariants(
      get(
        lv,
        "contentImage",
        "collectionThumbnailViewModel",
        "primaryThumbnail",
        "thumbnailViewModel",
        "image",
        "sources",
      ),
    );
  if (thumbs !== undefined) item["thumbs"] = thumbs;

  // metadataRows: row0 = byline; row1 parts = views / published (video lockups).
  const rows = get(
    lv,
    "metadata",
    "lockupMetadataViewModel",
    "metadata",
    "contentMetadataViewModel",
    "metadataRows",
  );
  let byline: string | undefined;
  let line2: string | undefined;
  let channelTitle: string | undefined;
  let channelId: string | undefined;
  if (Array.isArray(rows)) {
    // A row's DISPLAY parts, joined " · " ("Private" + "Playlist" on the library's own playlists).
    // A part linking anywhere but a channel is a navigation link ("View full playlist"), not
    // metadata — skipped, so it never poses as a count or subtitle. The channel link is the byline.
    const rowText = (row: unknown): string | undefined => {
      const parts = get(row, "metadataParts");
      if (!Array.isArray(parts)) return undefined;
      const texts: string[] = [];
      for (const p of parts) {
        const t = textOf(get(p, "text"));
        const runs = get(p, "text", "commandRuns");
        if (Array.isArray(runs)) {
          const browseId = asString(
            get(runs, 0, "onTap", "innertubeCommand", "browseEndpoint", "browseId"),
          );
          if (browseId === undefined || browseId.indexOf("UC") !== 0) continue;
          if (channelTitle === undefined && t !== undefined && t !== "") {
            channelTitle = t;
            channelId = browseId;
          }
        }
        if (t !== undefined && t !== "") texts.push(t);
      }
      return texts.length > 0 ? texts.join(" · ") : undefined;
    };
    byline = rowText(rows[0]);
    if (kind === "playlist") {
      const p0 = rowText(rows[1]);
      // Own playlists put "Updated today" here; their count is only on the thumbnail badge.
      if (p0 !== undefined && itemCountOf(p0) !== undefined) item["itemCount"] = p0;
      line2 = p0;
    } else {
      line2 = rowText(rows[1]);
    }
    if (kind === "video") {
      const badge = lockupOwnerBadge(rows);
      if (badge !== undefined) item["ownerBadge"] = badge;
      if (channelId !== undefined) {
        item["channelId"] = channelId;
        item["channelUrl"] = channelUrl(channelId);
      }
      // A collab byline links a channel-picker dialog, not a channel — row 0 of a two-row video
      // lockup is the byline regardless.
      const owner = channelTitle ?? (rows.length >= 2 && line2 !== undefined ? byline : undefined);
      if (owner !== undefined) item["channelTitle"] = owner;
    }
  }

  // Thumbnail badges: a time-shaped badge is the duration; a LIVE style marks live; on a playlist
  // any other badge is the count overlay ("16 videos", "Mix", "50+") — without it a playlist row
  // is visually a video row. A MUSIC badge icon marks a music video (home lockups, live 2026-07-22).
  // The progress bar beside the badges is the signed-in user's watch progress.
  walk(get(lv, "contentImage"), (key, value) => {
    if (key === "thumbnailOverlayProgressBarViewModel" && isObj(value)) {
      if (kind === "video") setWatchedPercent(item, value["startPercent"]);
      return true;
    }
    if (key !== "thumbnailBadgeViewModel" || !isObj(value)) return;
    const text = asString(value["text"]);
    if (text !== undefined && /^\d+(:\d+)+$/.test(text)) {
      setDurationBadge(item, text);
    } else if (text !== undefined && kind === "playlist" && item["itemCount"] === undefined) {
      item["itemCount"] = text;
    }
    if ((asString(value["badgeStyle"]) ?? "").indexOf("LIVE") >= 0) item["live"] = true;
    if (
      kind === "video" &&
      asString(get(value, "icon", "sources", 0, "clientResource", "imageName")) === "MUSIC"
    ) {
      item["musicVideo"] = true;
    }
    return true;
  });
  setLines(item, [byline, line2]);
  return item;
}

function chipItem(c: Record<string, unknown>): WireChip | null {
  const title = textOf(c["text"]);
  if (title === undefined) return null;
  const chip: WireChip = { "title": title };
  const token =
    asString(get(c, "navigationEndpoint", "continuationCommand", "token")) ??
    asString(get(c, "navigationEndpoint", "browseEndpoint", "params"));
  if (token !== undefined) chip["token"] = token;
  if (c["isSelected"] === true) chip["selected"] = true;
  return chip;
}

/**
 * `pageHeaderRenderer` (the migrated channel/playlist page header, verified live 2026-07-21) →
 * WirePageHeader. Title/metadata come from `pageHeaderViewModel`; the avatar (channel), hero image
 * (playlist) and banner are optional. Legacy `c4TabbedHeaderRenderer` / `playlistHeaderRenderer`
 * shapes are handled as fallbacks — this file ships remotely, resilience over completeness.
 */
function pageHeaderOf(header: unknown): WirePageHeader | null {
  const out: WirePageHeader = {};
  const phr = get(header, "pageHeaderRenderer");
  if (isObj(phr)) {
    const vm = get(phr, "content", "pageHeaderViewModel");
    const title =
      textOf(get(vm, "title", "dynamicTextViewModel", "text")) ?? asString(phr["pageTitle"]);
    if (title !== undefined) out["title"] = title;
    const rows = get(vm, "metadata", "contentMetadataViewModel", "metadataRows");
    if (Array.isArray(rows)) {
      const lines: string[] = [];
      for (const row of rows) {
        const parts = get(row, "metadataParts");
        if (!Array.isArray(parts)) continue;
        const texts: string[] = [];
        for (const p of parts) {
          const t = textOf(get(p, "text"));
          if (t !== undefined && t !== "") texts.push(t);
          // "Playlist · 31 videos · 1,007,162 views": the count leads the views.
          const count = t === undefined ? undefined : itemCountOf(t);
          if (count !== undefined && out["itemCount"] === undefined) out["itemCount"] = count;
        }
        if (texts.length > 0) lines.push(texts.join(" · "));
      }
      if (lines[0] !== undefined) out["subtitleLine1"] = lines[0];
      if (lines[1] !== undefined) out["subtitleLine2"] = lines[1];
    }
    const thumbs =
      thumbVariants(
        get(
          vm,
          "image",
          "decoratedAvatarViewModel",
          "avatar",
          "avatarViewModel",
          "image",
          "sources",
        ),
      ) ?? thumbVariants(get(vm, "heroImage", "contentPreviewImageViewModel", "image", "sources"));
    if (thumbs !== undefined) out["thumbs"] = thumbs;
    const banner = thumbVariants(get(vm, "banner", "imageBannerViewModel", "image", "sources"));
    if (banner !== undefined) out["banner"] = banner;
    return out;
  }
  const legacy = get(header, "c4TabbedHeaderRenderer") ?? get(header, "playlistHeaderRenderer");
  if (isObj(legacy)) {
    const title = textOf(legacy["title"]);
    if (title !== undefined) out["title"] = title;
    const countText = textOf(legacy["videosCountText"]) ?? textOf(legacy["numVideosText"]);
    const count = countText === undefined ? undefined : itemCountOf(countText);
    if (count !== undefined) out["itemCount"] = count;
    const sub = countText ?? textOf(legacy["subscriberCountText"]);
    if (sub !== undefined) out["subtitleLine1"] = sub;
    const thumbs = thumbsOf(legacy["avatar"]);
    if (thumbs !== undefined) out["thumbs"] = thumbs;
    const banner = thumbsOf(legacy["banner"]);
    if (banner !== undefined) out["banner"] = banner;
    return out;
  }
  return null;
}

/** A YouTube playlist holds at most this many videos — a bigger number is a view count. */
const MAX_PLAYLIST_ITEMS = 5000;

/**
 * "31 videos" / "1,234 video" / "1.234 vidéos" → the number; undefined for anything abbreviated
 * ("2.9M views": no space after the digits) or past [MAX_PLAYLIST_ITEMS] (an exact view count).
 */
function itemCountOf(text: string): number | undefined {
  const m = /^(\d{1,3}(?:[.,\u00a0\u202f ]\d{3})*|\d+)\s+\S/.exec(text);
  if (m === null || m[1] === undefined) return undefined;
  const n = parseInt(m[1].replace(/\D/g, ""), 10);
  return n > 0 && n <= MAX_PLAYLIST_ITEMS ? n : undefined;
}

/** The `sp` query param out of a results-page url — the fallback carrier for filter params. */
function spFromUrl(url: string | undefined): string | undefined {
  if (url === undefined) return undefined;
  const m = /[?&]sp=([^&]+)/.exec(url);
  return m === null ? undefined : m[1];
}

/**
 * `searchFilterGroupRenderer` → a titled group of filter options (search subMenu only). Each
 * option's endpoint carries the FULL `params` for the search with it applied; a selected removable
 * option's endpoint removes it — either way the app just re-searches with the option's params.
 */
function filterGroup(g: Record<string, unknown>): WireFilterGroup | null {
  const title = textOf(g["title"]);
  const filters = g["filters"];
  if (title === undefined || !Array.isArray(filters)) return null;
  const options: WireFilterOption[] = [];
  for (const f of filters) {
    const r = get(f, "searchFilterRenderer");
    if (!isObj(r)) continue;
    const label = textOf(r["label"]);
    if (label === undefined) continue;
    const option: WireFilterOption = { "label": label };
    const params =
      asString(get(r, "navigationEndpoint", "searchEndpoint", "params")) ??
      spFromUrl(
        asString(get(r, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url")),
      );
    if (params !== undefined) option["params"] = params;
    const status = asString(r["status"]) ?? "";
    if (status.indexOf("SELECTED") >= 0) option["selected"] = true;
    if (status.indexOf("DISABLED") >= 0) option["disabled"] = true;
    options.push(option);
  }
  return options.length > 0 ? { "title": title, "options": options } : null;
}

/** i.ytimg.com's canonical thumb for a videoId — watch-card entries carry no thumbnail of their own. */
function videoThumb(id: string): Array<{ "url": string; "w"?: number; "h"?: number }> {
  return [{ "url": "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", "w": 320, "h": 180 }];
}

/** A universal-watch-card top song (watchCardCompactVideoRenderer, verified live 2026-07-22). */
function watchCardVideoItem(r: unknown): WireFeedItem | null {
  if (!isObj(r)) return null;
  const id = asString(get(r, "navigationEndpoint", "watchEndpoint", "videoId"));
  const title = textOf(r["title"]);
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "video", "id": id, "title": title };
  item["thumbs"] = videoThumb(id);
  setDurationBadge(item, textOf(r["lengthText"]));
  const byline = textOf(r["byline"]);
  if (byline !== undefined) item["channelTitle"] = byline;
  setLines(item, [byline, textOf(r["subtitle"])]);
  return item;
}

/** An album/single card (searchRefinementCardRenderer) — only ones deep-linking to a playlist;
 *  plain query-refinement cards have no watchPlaylistEndpoint and are skipped. */
function refinementCardItem(r: unknown): WireFeedItem | null {
  if (!isObj(r)) return null;
  const id = asString(get(r, "searchEndpoint", "watchPlaylistEndpoint", "playlistId"));
  const title = textOf(r["query"]);
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "playlist", "id": id, "title": title };
  if (isAlbumPlaylist(id)) item["album"] = true;
  item["url"] = playlistUrl(id);
  const thumbs = thumbsOf(r["thumbnail"]);
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  return item;
}

/**
 * An artist search's universal watch card (secondaryContents.…universalWatchCardRenderer, verified
 * live 2026-07-22 for "bruno mars"): the artist header + their radio Mix lead, then each card
 * section — the top-songs list and the Albums card row — as its own titled section.
 */
function universalCardSections(card: Record<string, unknown>): WireSection[] {
  const sections: WireSection[] = [];
  const header = get(card, "header", "watchCardRichHeaderRenderer");
  const artistTitle = textOf(get(header, "title"));
  const channelId = asString(get(header, "titleNavigationEndpoint", "browseEndpoint", "browseId"));
  const lead: WireFeedItem[] = [];
  if (artistTitle !== undefined && channelId !== undefined) {
    const artist: WireFeedItem = {
      "kind": "channel",
      "id": channelId,
      "title": artistTitle,
      "channelId": channelId,
      "url": channelUrl(channelId),
    };
    const thumbs = thumbsOf(get(header, "avatar"));
    if (thumbs !== undefined) artist["thumbs"] = thumbs;
    const subtitle = textOf(get(header, "subtitle"));
    if (subtitle !== undefined) artist["subtitleLine1"] = subtitle;
    const style = asString(get(header, "titleBadge", "metadataBadgeRenderer", "style")) ?? "";
    if (style.indexOf("VERIFIED_ARTIST") >= 0) artist["ownerBadge"] = "artist";
    else if (style.indexOf("VERIFIED") >= 0) artist["ownerBadge"] = "verified";
    lead.push(artist);
  }
  // The hero call-to-action starts the artist radio — surface it as the Mix playlist it plays
  // (titled like YouTube's own home-feed Mixes; the RD… id gets the app's Mix treatment).
  const hero = get(
    card,
    "callToAction",
    "watchCardHeroVideoRenderer",
    "navigationEndpoint",
    "watchEndpoint",
  );
  const mixId = asString(get(hero, "playlistId"));
  const seed = asString(get(hero, "videoId"));
  if (mixId !== undefined && mixId.indexOf("RD") === 0 && artistTitle !== undefined) {
    const mix: WireFeedItem = { "kind": "playlist", "id": mixId, "title": "Mix - " + artistTitle };
    mix["url"] = playlistUrl(mixId, seed);
    if (seed !== undefined) mix["thumbs"] = videoThumb(seed);
    lead.push(mix);
  }
  if (lead.length > 0) sections.push({ "layout": "list", "items": lead });

  const cardSections = card["sections"];
  if (!Array.isArray(cardSections)) return sections;
  // Card rows (Albums / Singles) surface ABOVE the song lists — near the artist, collapsed to one
  // row behind the app's show-more toggle — regardless of the card's own row order.
  const songSections: WireSection[] = [];
  const cardRowSections: WireSection[] = [];
  for (const s of cardSections) {
    const seq = get(s, "watchCardSectionSequenceRenderer");
    if (!isObj(seq)) continue;
    const listTitle = textOf(get(seq, "listTitles", 0));
    const lists = seq["lists"];
    if (!Array.isArray(lists)) continue;
    for (const l of lists) {
      // The top-songs list (untitled server-side — the songs read directly under the artist).
      const vertical = get(l, "verticalWatchCardListRenderer", "items");
      if (Array.isArray(vertical)) {
        const items: WireFeedItem[] = [];
        for (const v of vertical) {
          const it = watchCardVideoItem(get(v, "watchCardCompactVideoRenderer"));
          if (it !== null) items.push(it);
        }
        if (items.length > 0) {
          const section: WireSection = { "layout": "list", "items": items };
          if (listTitle !== undefined) section["header"] = { "title": listTitle };
          songSections.push(section);
        }
      }
      // The Albums / Singles card row.
      const horizontal = get(l, "horizontalCardListRenderer");
      if (isObj(horizontal)) {
        const title =
          listTitle ??
          textOf(get(horizontal, "header", "titleAndButtonListHeaderRenderer", "title"));
        const cards = horizontal["cards"];
        const items: WireFeedItem[] = [];
        if (Array.isArray(cards)) {
          for (const c of cards) {
            const it = refinementCardItem(get(c, "searchRefinementCardRenderer"));
            if (it !== null) items.push(it);
          }
        }
        if (items.length > 0) {
          // Collapses to a single card row (two rows as a narrow list) — near the artist, the
          // albums are a teaser, not the content; the app default would keep more.
          const section: WireSection = {
            "layout": "grid",
            "items": items,
            "collapsible": true,
            "collapsedRows": [2, 1],
          };
          if (title !== undefined) section["header"] = { "title": title };
          cardRowSections.push(section);
        }
      }
    }
  }
  for (const s of cardRowSections) sections.push(s);
  for (const s of songSections) sections.push(s);
  return sections;
}

/**
 * Search-results ranking (yt/search only): within a loose results run, music content — Mix/radio
 * playlists, music videos (MUSIC time-status overlay), and videos from artist channels — surfaces
 * above everything else, each group keeping the server's relative order. Titled shelves and the
 * artist card are left untouched.
 */
export function rankSearchItems(items: WireFeedItem[]): WireFeedItem[] {
  const music: WireFeedItem[] = [];
  const rest: WireFeedItem[] = [];
  for (const it of items) {
    const mix = it["kind"] === "playlist" && isMixPlaylist(it["id"]);
    const hot = mix || it["musicVideo"] === true || it["ownerBadge"] === "artist";
    (hot ? music : rest).push(it);
  }
  return music.concat(rest);
}

/** Drop repeats by (kind, id), keeping first occurrence — the order given is the order kept. */
export function dedupeItems(items: WireFeedItem[]): WireFeedItem[] {
  const seen: Record<string, true> = {};
  const out: WireFeedItem[] = [];
  for (const it of items) {
    const key = it["kind"] + ":" + it["id"];
    if (seen[key] === true) continue;
    seen[key] = true;
    out.push(it);
  }
  return out;
}

/** A Shorts tile (shortsLockupViewModel) — parsed ONLY on the channel Shorts tab (see
 *  collectPage's `shorts` option); everywhere else Shorts are skipped as not-feed-content. */
function shortsItem(lv: Record<string, unknown>): WireFeedItem | null {
  const id = asString(get(lv, "onTap", "innertubeCommand", "reelWatchEndpoint", "videoId"));
  const title =
    asString(get(lv, "overlayMetadata", "primaryText", "content")) ??
    asString(lv["accessibilityText"]);
  if (id === undefined || title === undefined) return null;
  const item: WireFeedItem = { "kind": "video", "id": id, "title": title };
  // Live 2026-07-23: the tile image is thumbnailViewModel.thumbnailViewModel.image.sources (yes,
  // doubled); older shapes and the reel endpoint's own thumbnail are the fallbacks.
  const thumbs =
    thumbVariants(get(lv, "thumbnailViewModel", "thumbnailViewModel", "image", "sources")) ??
    thumbVariants(get(lv, "thumbnail", "sources")) ??
    thumbVariants(
      get(lv, "onTap", "innertubeCommand", "reelWatchEndpoint", "thumbnail", "thumbnails"),
    );
  if (thumbs !== undefined) item["thumbs"] = thumbs;
  const views = asString(get(lv, "overlayMetadata", "secondaryText", "content"));
  if (views !== undefined) item["subtitleLine1"] = views;
  return item;
}

/** Parse a single leaf renderer into a feed item, or null if `key`/`value` is not a known leaf. */
function leafItem(key: string, value: unknown, shorts?: boolean): WireFeedItem | null {
  if (!isObj(value)) return null;
  if (shorts === true && key === "shortsLockupViewModel") return shortsItem(value);
  switch (key) {
    case "videoRenderer":
    case "compactVideoRenderer":
    case "gridVideoRenderer":
    case "playlistVideoRenderer":
    case "playlistPanelVideoRenderer":
    // The embedded player's end screen (verified live 2026-07-25): same inline fields as
    // videoRenderer — it reaches us through the WebView's watch-next payload, never a browse.
    case "endScreenVideoRenderer":
      return withRemoveToken(videoItem(value), value);
    case "playlistRenderer":
    case "gridPlaylistRenderer":
    case "compactPlaylistRenderer":
    case "radioRenderer":
    case "compactRadioRenderer":
    case "endScreenPlaylistRenderer":
      return playlistItem(value);
    case "channelRenderer":
    case "gridChannelRenderer":
      return channelItem(value);
    case "lockupViewModel":
      return withRemoveToken(lockupItem(value), value);
  }
  return null;
}

/** Engine-private prefix of a yt/playlist/remove token: `<prefix><playlistId>:<setVideoId>`. */
export const REMOVE_TOKEN_PREFIX = "yplrm1:";

/**
 * Tags a video with a removeToken when its menu carries the owner's "Remove from playlist" — a
 * playlistEditEndpoint with ACTION_REMOVE_VIDEO by setVideoId (the membership, not the video:
 * Watch-later toggles remove BY_VIDEO_ID and never match).
 */
function withRemoveToken(
  item: WireFeedItem | null,
  node: Record<string, unknown>,
): WireFeedItem | null {
  if (item === null || item["kind"] !== "video") return item;
  walk(node, (k, v) => {
    if (item["removeToken"] !== undefined) return true;
    if (k !== "playlistEditEndpoint" || !isObj(v)) return;
    const playlistId = asString(v["playlistId"]);
    const actions = v["actions"];
    if (playlistId === undefined || !Array.isArray(actions)) return true;
    for (const a of actions) {
      const setVideoId = asString(get(a, "setVideoId"));
      if (get(a, "action") === "ACTION_REMOVE_VIDEO" && setVideoId !== undefined) {
        item["removeToken"] = REMOVE_TOKEN_PREFIX + playlistId + ":" + setVideoId;
      }
    }
    return true;
  });
  return item;
}

// Not feed content: ads, Shorts (both the legacy reel shelf and the current lockup/reel item
// shapes), and the watch page's own metadata column. These sit INSIDE the scanned subtrees, which
// is what makes them worth naming — response chrome (responseContext / topbar / microformat /
// sidebar / playerOverlays) needs no entry here because collectPage never walks it: it scans an
// allow-list of roots (see scanRoots), not the whole response.
const SKIP_KEYS: Record<string, true> = {
  "adSlotRenderer": true,
  "promotedSparklesWebRenderer": true,
  "reelShelfRenderer": true,
  "reelItemRenderer": true,
  "shortsLockupViewModel": true,
  "engagementPanels": true,
  "videoPrimaryInfoRenderer": true,
  "compositeVideoPrimaryInfoRenderer": true,
  "videoSecondaryInfoRenderer": true,
};

/** Collect the leaf items nested anywhere under `node` (a shelf's content), in document order. */
export function collectItems(node: unknown, shorts?: boolean): WireFeedItem[] {
  const items: WireFeedItem[] = [];
  walk(node, (key, value) => {
    const it = leafItem(key, value, shorts);
    if (it !== null) {
      items.push(it);
      return true;
    }
    if (SKIP_KEYS[key] === true) return true;
    return;
  });
  return items;
}

/** The app-internal deeplink for a browse target (see WireSectionHeader.ctaDeeplink): playlists
 *  (VL…) and channels (UC…, optional tab params) map to the app's detail surfaces; anything else
 *  is not linkable. Verified live 2026-07-23: channel-page shelves' "View all" endpoints carry
 *  browseId "VLPL…"; channel-tab targets are browseId "UC…" + params. */
function deeplinkFor(browseId: string | undefined, params: string | undefined): string | undefined {
  if (browseId === undefined) return undefined;
  if (browseId.indexOf("VL") === 0) return playlistUrl(browseId.slice(2));
  if (browseId.indexOf("UC") === 0) return channelUrl(browseId, params);
  // The library shelves' own-account feeds (yt/channel serves these app links back as browses).
  if (browseId === "FEhistory") return "/feed/history";
  if (browseId === "FEplaylist_aggregation") return "/feed/playlists";
  // Older hosts render the subscriptions page's channel row and its single "Latest" run wrongly.
  if (browseId === "FEsubscriptions")
    return hostVersionCode() >= 3 ? "/feed/subscriptions" : undefined;
  return undefined;
}

// A titled shelf: `shelfRenderer` (vertical/expanded) or `richShelfRenderer` (rich grid). Its title
// becomes a section header; a "View all" endpoint becomes the header CTA — with an app deeplink
// when it targets a playlist/channel page.
function shelfHeader(value: Record<string, unknown>): WireSectionHeader | undefined {
  const title =
    textOf(value["title"]) ?? textOf(get(value, "headerRenderer", "shelfHeaderRenderer"));
  if (title === undefined) return undefined;
  const header: WireSectionHeader = { "title": title };
  // The library shelves' subtitle is a count ("12 videos") — the design's trailing count slot.
  const subtitle = textOf(value["subtitle"]);
  if (subtitle !== undefined) header["count"] = subtitle;
  const token =
    asString(get(value, "endpoint", "continuationCommand", "token")) ??
    asString(get(value, "endpoint", "browseEndpoint", "params"));
  if (token !== undefined) header["ctaToken"] = token;
  const nav = isObj(get(value, "endpoint", "browseEndpoint"))
    ? get(value, "endpoint", "browseEndpoint")
    : get(value, "title", "runs", 0, "navigationEndpoint", "browseEndpoint");
  const deeplink = deeplinkFor(asString(get(nav, "browseId")), asString(get(nav, "params")));
  if (deeplink !== undefined) header["ctaDeeplink"] = deeplink;
  return header;
}

/** A continuationItemRenderer's token: an owned playlist wraps it in a commandExecutorCommand
 *  beside a "Reload for updates" popup (verified live 2026-10-22). */
function continuationEndpointToken(endpoint: unknown): string | undefined {
  const direct = asString(get(endpoint, "continuationCommand", "token"));
  if (direct !== undefined) return direct;
  const commands = get(endpoint, "commandExecutorCommand", "commands");
  if (!Array.isArray(commands)) return undefined;
  for (const command of commands) {
    const token = asString(get(command, "continuationCommand", "token"));
    if (token !== undefined) return token;
  }
  return undefined;
}

/**
 * Collect a feed page as a list of sections. Serves /browse, /search and /next alike (fresh page or
 * continuation append). A **titled shelf** becomes its own section (header + grid layout); everything
 * else — the loose grid/list items YouTube emits directly — collects into a default section whose
 * layout is inferred from its container (`richGridRenderer`/`gridRenderer` → grid, otherwise list).
 * Chips and the page continuation are page-level. Chrome/ads/Shorts subtrees are skipped by key.
 */
export function collectPage(
  root: Record<string, unknown>,
  opts?: { shorts?: boolean },
): WireFeedPage {
  const shortsMode = opts !== undefined && opts.shorts === true;
  const sections: WireSection[] = [];
  const chips: WireChip[] = [];
  const sort: WireChip[] = [];
  const sheetSort: WireChip[] = [];
  const filterGroups: WireFilterGroup[] = [];
  const pageTabs: WirePageTab[] = [];
  let panelHeader: WirePageHeader | undefined;
  let mixContinuation: string | undefined;
  let continuation: string | undefined;
  let emptyTitle: string | undefined;
  let emptyMessage: string | undefined;

  let loose: WireFeedItem[] = [];
  let looseLayout = "list";
  const flushLoose = (): void => {
    if (loose.length > 0) {
      sections.push({ "layout": looseLayout, "items": loose });
      loose = [];
    }
  };

  const scan = (node: unknown): void =>
    walk(node, (key, value) => {
      // The playlist panel / watch card were pre-extracted — don't re-collect their items.
      if (panelWrapper !== undefined && value === panelWrapper) return true;
      if (cardWrapper !== undefined && value === cardWrapper) return true;
      // A grid container switches the loose section to grid layout (its items render as grid cells).
      if (key === "richGridRenderer" || key === "gridRenderer") {
        looseLayout = "grid";
        return;
      }
      // A titled shelf is its own section; an untitled one falls through so its items go loose.
      // A titled shelf whose content yields NO items (a Shorts shelf, an all-ads shelf) is dropped
      // whole — pushing it would render a bare header in the app.
      if (key === "shelfRenderer" || key === "richShelfRenderer") {
        if (isObj(value)) {
          const header = shelfHeader(value);
          if (header !== undefined) {
            // shelfRenderer nests under `content`; richShelfRenderer (the library's shelves,
            // live 2026-07-30) holds its rich items directly under `contents`.
            const items = collectItems(value["content"] ?? value["contents"], shortsMode);
            if (items.length === 0) return true;
            flushLoose();
            const section: WireSection = {
              "header": header,
              "layout": "grid",
              "items": items,
            };
            sections.push(section);
            return true;
          }
        }
        return;
      }
      const it = leafItem(key, value, shortsMode);
      if (it !== null) {
        loose.push(it);
        return true;
      }
      if (key === "chipCloudChipRenderer") {
        if (isObj(value)) {
          const chip = chipItem(value);
          if (chip) chips.push(chip);
        }
        return true;
      }
      // The channel Playlists tab's sort menu (verified live 2026-07-23): a browse-typed control —
      // each entry re-browses the channel with fresh params — surfaced as chips with the params
      // wrapped as an engine-private token (see browseParamsToken; yt/channel decodes it).
      if (key === "sortFilterSubMenuRenderer") {
        if (isObj(value) && Array.isArray(value["subMenuItems"])) {
          for (const entry of value["subMenuItems"]) {
            const title = asString(get(entry, "title"));
            if (title === undefined) continue;
            const chip: WireChip = { "title": title };
            if (get(entry, "selected") === true) chip["selected"] = true;
            // An owned playlist's Sort menu (verified live 2026-10-20): each entry saves the order.
            const orderToken = playlistOrderTokenIn(entry);
            if (orderToken !== undefined) {
              chip["token"] = orderToken;
              sort.push(chip);
              continue;
            }
            const params = asString(get(entry, "navigationEndpoint", "browseEndpoint", "params"));
            if (params !== undefined) chip["token"] = browseParamsToken(params);
            chips.push(chip);
          }
        }
        return true;
      }
      // The migrated chip generation (channel Videos/Shorts sort bar: Latest/Popular/Oldest,
      // verified live 2026-07-23): richGridRenderer.header.chipBarViewModel.chips[].chipViewModel,
      // token under tapCommand.innertubeCommand.continuationCommand. A playlist's All/Videos/Shorts
      // bar (verified live 2026-10-20) is browse-typed instead: browseEndpoint.params, none on All.
      if (key === "chipViewModel") {
        // An owned playlist's sort chip (verified live 2026-10-20) opens a sheet of order options —
        // the ones YouTube's web app sends; the legacy Sort menu is the fallback.
        const sheet = get(value, "tapCommand", "innertubeCommand", "showSheetCommand");
        if (sheet !== undefined) {
          walk(sheet, (k, v) => {
            if (k !== "listItemViewModel" || !isObj(v)) return;
            const title = asString(get(v, "title", "content"));
            const token = playlistOrderTokenIn(v);
            if (title !== undefined && token !== undefined) {
              const chip: WireChip = { "title": title, "token": token };
              if (v["isSelected"] === true) chip["selected"] = true;
              sheetSort.push(chip);
            }
            return true;
          });
          return true;
        }
        if (isObj(value)) {
          const title = asString(value["text"]);
          if (title !== undefined) {
            const chip: WireChip = { "title": title };
            const command = get(value, "tapCommand", "innertubeCommand");
            const browse = get(command, "browseEndpoint");
            const token =
              asString(get(command, "continuationCommand", "token")) ??
              (isObj(browse) ? browseParamsToken(asString(browse["params"]) ?? "") : undefined);
            if (token !== undefined) chip["token"] = token;
            if (value["selected"] === true) chip["selected"] = true;
            chips.push(chip);
          }
        }
        return true;
      }
      if (key === "searchFilterGroupRenderer") {
        if (isObj(value)) {
          const grp = filterGroup(value);
          if (grp !== null) filterGroups.push(grp);
        }
        return true;
      }
      // The feed's own empty-state explainer (anonymous Home is ONLY this — docs/innertube/auth.md):
      // YouTube's localized "Try searching to get started" nudge, surfaced page-level so the app's
      // empty state shows YouTube's words instead of a generic string. Not an item; the page stays
      // empty.
      if (key === "feedNudgeRenderer") {
        if (isObj(value)) {
          emptyTitle = textOf(value["title"]);
          emptyMessage = textOf(value["subtitle"]);
        }
        return true;
      }
      // A titled channel tab (Home / Videos / …). Record it and keep DESCENDING — the selected
      // tab's content holds the page's items. Untitled tabs (home/search wrappers) and the
      // expandableTabRenderer channel-search tab are skipped by not matching here.
      if (key === "tabRenderer") {
        if (isObj(value)) {
          const title = asString(value["title"]);
          if (title !== undefined) {
            const nav = get(value, "endpoint", "browseEndpoint");
            const params = asString(get(nav, "params"));
            const slug = tabWebSlug(value["endpoint"]);
            // Non-content tabs (Posts / About / …) are dropped from the page; keep descending so
            // a selected tab's content still collects.
            if (slug !== undefined && CONTENT_TAB_SLUGS[slug] !== true) return;
            const tab: WirePageTab = { "title": title };
            if (params === undefined || slug === "featured") tab["home"] = true;
            if (params !== undefined) tab["params"] = params;
            // The tab's own endpoint carries the channel browseId — its app link comes from it,
            // with the tab slug in the path.
            const browseId = asString(get(nav, "browseId"));
            const url =
              browseId !== undefined && browseId.indexOf("UC") === 0
                ? channelUrl(browseId, params, slug)
                : deeplinkFor(browseId, params);
            if (url !== undefined) tab["url"] = url;
            if (value["selected"] === true) tab["selected"] = true;
            pageTabs.push(tab);
          }
        }
        return;
      }
      if (key === "continuationItemRenderer") {
        if (continuation === undefined) {
          continuation = continuationEndpointToken(get(value, "continuationEndpoint"));
        }
        return true;
      }
      // The view-model form playlist browse pages switched to (verified live 2026-10-08).
      if (key === "continuationItemViewModel") {
        if (continuation === undefined) {
          continuation = asString(
            get(value, "continuationCommand", "innertubeCommand", "continuationCommand", "token"),
          );
        }
        return true;
      }
      if (SKIP_KEYS[key] === true) return true;
      return;
    });

  // The watch-page playlist panel (twoColumnWatchNextResults.playlist.playlist — verified live
  // 2026-07-22): the ONLY way to read a Mix/radio (RD…), which /browse refuses. Its videos become
  // the page's first section and its title/owner the page header; the wrapper is then claimed so
  // the generic walk doesn't re-collect the panel items into the related-videos run.
  const panelWrapper = get(root, "contents", "twoColumnWatchNextResults", "playlist");
  const panel = get(panelWrapper, "playlist");
  if (isObj(panel) && Array.isArray(panel["contents"])) {
    const header: WirePageHeader = {};
    const title = asString(panel["title"]) ?? textOf(panel["titleText"]);
    if (title !== undefined) header["title"] = title;
    const ownerName = textOf(panel["ownerName"]) ?? textOf(panel["shortBylineText"]);
    if (ownerName !== undefined) header["subtitleLine1"] = ownerName;
    const total = panel["totalVideos"];
    if (panel["isInfinite"] !== true && typeof total === "number" && total > 0)
      header["itemCount"] = total;
    panelHeader = header;
    const panelItems = collectItems(panel["contents"]);
    sections.push({ "layout": "list", "items": panelItems });
    // An infinite panel (a Mix) pages by SLIDING ITS WINDOW around a video, not by a server
    // continuation token — so the engine fabricates its own opaque token from the window's last
    // video (see mixToken in commands.ts). It wins over any related-videos continuation below.
    const pid = asString(panel["playlistId"]);
    const lastPanelItem = panelItems[panelItems.length - 1];
    if (panel["isInfinite"] === true && lastPanelItem !== undefined && pid !== undefined) {
      mixContinuation = mixToken(panelItems.length - 1, pid, lastPanelItem["id"]);
    }
  }

  // An artist search's universal watch card (the search page's secondary column): parsed into
  // leading sections — artist · radio Mix · top songs · albums — then the wrapper is claimed so
  // the generic walk doesn't half-read its unknown renderers.
  const cardWrapper = get(root, "contents", "twoColumnSearchResultsRenderer", "secondaryContents");
  walk(cardWrapper, (key, value) => {
    if (key !== "universalWatchCardRenderer" || !isObj(value)) return;
    for (const s of universalCardSections(value)) sections.push(s);
    return true;
  });

  const scanRoots: unknown[] = [
    root["contents"],
    root["onResponseReceivedActions"],
    root["onResponseReceivedCommands"],
    root["onResponseReceivedEndpoints"],
  ];
  for (const r of scanRoots) scan(r);
  flushLoose();

  // The search filter dialog moved into the page header (searchHeaderRenderer → filter button →
  // popup → searchFilterOptionsDialogRenderer; the old sectionListRenderer.subMenu is an empty
  // husk now). Walk the header for the filter groups and the chip bar ONLY — other chrome
  // (buttons, sort menus) must not leak into chips/items. A search chip's endpoint is a fresh
  // searchEndpoint (query+params), not a continuation — it rides the chip token as engine-private
  // wrapped state (see SEARCH_CHIP_PREFIX) so the app's token-only chip pipeline still works.
  walk(root["header"], (key, value) => {
    if (key === "searchFilterGroupRenderer") {
      if (isObj(value)) {
        const grp = filterGroup(value);
        if (grp !== null) filterGroups.push(grp);
      }
      return true;
    }
    if (key === "chipCloudChipRenderer") {
      if (isObj(value)) {
        const chip = chipItem(value);
        if (chip !== null) {
          const se = get(value, "navigationEndpoint", "searchEndpoint");
          const q = asString(get(se, "query"));
          if (q !== undefined) {
            chip["token"] = searchChipToken(asString(get(se, "params")), q);
          }
          chips.push(chip);
        }
      }
      return true;
    }
    return;
  });

  const page: WireFeedPage = { "sections": sections };
  const effectiveContinuation = mixContinuation ?? continuation;
  if (effectiveContinuation !== undefined) page["continuation"] = effectiveContinuation;
  if (emptyTitle !== undefined) page["emptyTitle"] = emptyTitle;
  if (emptyMessage !== undefined) page["emptyMessage"] = emptyMessage;
  if (chips.length > 0) page["chips"] = chips;
  const sortMenu = sheetSort.length > 0 ? sheetSort : sort;
  if (sortMenu.length > 0) page["sort"] = sortMenu;
  if (filterGroups.length > 0) page["filterGroups"] = filterGroups;
  if (pageTabs.length > 0) page["pageTabs"] = pageTabs;
  const pageHeader = pageHeaderOf(root["header"]) ?? panelHeader;
  if (pageHeader != null) page["pageHeader"] = pageHeader;
  const channelId = asString(get(root["metadata"], "channelMetadataRenderer", "externalId"));
  if (channelId !== undefined) {
    // Topic channels carry a carousel header pageHeaderOf doesn't read; the subscribe state still applies.
    const header: WirePageHeader = pageHeader ?? {};
    header["channelId"] = channelId;
    const subscribed = subscribedOf(root);
    if (subscribed !== undefined) header["subscribed"] = subscribed;
    page["pageHeader"] = header;
  }
  const save = saveToggleOf(root);
  if (save !== undefined && pageHeader != null) {
    const id =
      playlistIdOf(save) ?? playlistIdOf(get(root["microformat"], "microformatDataRenderer"));
    if (id !== undefined) {
      pageHeader["savePlaylistId"] = id;
      // Signed out the toggle opens a sign-in dialog instead of saving.
      let signedOut = false;
      walk(save, (k) => {
        if (k === "signInEndpoint") signedOut = true;
        return signedOut;
      });
      if (!signedOut) pageHeader["saved"] = save["isToggled"] === true;
    }
  }
  return page;
}

/**
 * A playlist page's save toggle: the header's `toggleButtonViewModel` whose default icon is
 * PLAYLIST_ADD, else the sidebar's legacy `toggleButtonRenderer` (skipped when `isDisabled`).
 * Pages the viewer can't save (their own playlists, Mixes) carry neither.
 */
function saveToggleOf(root: Record<string, unknown>): Record<string, unknown> | undefined {
  let found: Record<string, unknown> | undefined;
  walk(root["header"], (k, v) => {
    if (found !== undefined) return true;
    if (k !== "toggleButtonViewModel" || !isObj(v)) return;
    const icon = get(v, "defaultButtonViewModel", "buttonViewModel", "iconName");
    if (icon === "PLAYLIST_ADD") found = v;
    return true;
  });
  if (found !== undefined) return found;
  walk(root["sidebar"], (k, v) => {
    if (found !== undefined) return true;
    if (k !== "toggleButtonRenderer" || !isObj(v)) return;
    if (get(v, "defaultIcon", "iconType") === "PLAYLIST_ADD" && v["isDisabled"] !== true) found = v;
    return true;
  });
  return found;
}

/** The first playlist id under [node]: a `playlistId`, a `VL…` browseId, or a `list=` url param. */
function playlistIdOf(node: unknown): string | undefined {
  let id: string | undefined;
  walk(node, (k, v) => {
    if (id !== undefined) return true;
    const s = asString(v);
    if (s === undefined) return;
    if (k === "playlistId") id = s;
    else if (k === "browseId" && s.indexOf("VL") === 0) id = s.slice(2);
    else {
      const m = /[?&]list=([\w-]+)/.exec(s);
      if (m !== null && m[1] !== undefined) id = m[1];
    }
    return;
  });
  return id;
}

/**
 * The viewer's subscription from the header's subscribe button: `subscribeButtonRenderer.subscribed`
 * or the view model's `subscribeState`, overridden by the frameworkUpdates `subscriptionStateEntity`
 * its entity key names (the live store). Undefined when signed out — the header then holds a plain
 * sign-in button with neither.
 */
function subscribedOf(root: Record<string, unknown>): boolean | undefined {
  let subscribed: boolean | undefined;
  let key: string | undefined;
  walk(root["header"], (k, v) => {
    if (!isObj(v)) return;
    if (k === "subscribeButtonRenderer") {
      if (typeof v["subscribed"] === "boolean") subscribed = v["subscribed"];
      key = asString(v["subscribedEntityKey"]);
      return true;
    }
    if (k === "subscribeButtonViewModel") {
      const state = get(v, "subscribeButtonContent", "subscribeState", "subscribed");
      if (typeof state === "boolean") subscribed = state;
      key = asString(v["stateEntityStoreKey"]);
      return true;
    }
    return;
  });
  const mutations = get(root["frameworkUpdates"], "entityBatchUpdate", "mutations");
  if (!Array.isArray(mutations)) return subscribed;
  const entities: Record<string, unknown>[] = [];
  for (const m of mutations) {
    const entity = get(m, "payload", "subscriptionStateEntity");
    if (isObj(entity) && typeof entity["subscribed"] === "boolean") entities.push(entity);
  }
  // A key that matches nothing falls back to a lone entity — the page's own channel.
  const match =
    entities.filter((e) => key !== undefined && e["key"] === key)[0] ??
    (entities.length === 1 ? entities[0] : undefined);
  return match === undefined ? subscribed : match["subscribed"] === true;
}

/** The viewer's subscribed channels off /guide (guideSubscriptionsSectionRenderer, verified live
 *  2026-10-14): leading entries inline, the rest under a guideCollapsibleEntryRenderer's
 *  expandableItems. Only UC… browse targets are channels — header/"Show more" rows are skipped. */
export function guideChannelsSection(root: Record<string, unknown>): WireSection | undefined {
  const items: WireFeedItem[] = [];
  const entry = (node: unknown): void => {
    const r = get(node, "guideEntryRenderer");
    if (!isObj(r)) return;
    const id = asString(get(r, "navigationEndpoint", "browseEndpoint", "browseId"));
    const title = textOf(r["formattedTitle"]);
    if (id === undefined || id.indexOf("UC") !== 0 || title === undefined) return;
    const item: WireFeedItem = { "kind": "channel", "id": id, "title": title, "channelId": id };
    item["url"] = channelUrl(id);
    const thumbs = thumbsOf(r["thumbnail"]);
    if (thumbs !== undefined) item["thumbs"] = thumbs;
    items.push(item);
  };
  const guideItems = get(root, "items");
  if (!Array.isArray(guideItems)) return undefined;
  for (const section of guideItems) {
    const entries = get(section, "guideSubscriptionsSectionRenderer", "items");
    if (!Array.isArray(entries)) continue;
    for (const node of entries) {
      entry(node);
      const more = get(node, "guideCollapsibleEntryRenderer", "expandableItems");
      if (Array.isArray(more)) for (const m of more) entry(m);
    }
  }
  if (items.length === 0) return undefined;
  return {
    "header": { "title": "Channels", "titleKey": "subscribed_channels" },
    "layout": "carousel",
    "items": items,
  };
}

/** The first titled shelf with no items of its own: the subscriptions feed's "Latest", a bare
 *  header over the loose grid that follows it (verified live 2026-10-14). */
export function headerOnlyShelfTitle(root: unknown): string | undefined {
  let title: string | undefined;
  walk(root, (key, value) => {
    if (title !== undefined) return true;
    if (key !== "shelfRenderer" || !isObj(value)) return;
    if (value["content"] !== undefined || value["contents"] !== undefined) return;
    title = textOf(value["title"]);
    return true;
  });
  return title;
}

/** Folds every headerless run into ONE trailing section under [header]; titled sections keep
 *  their order ahead of it — the infinite run must be last, where the page continuation feeds. */
export function foldLooseRuns(
  sections: WireSection[],
  header: WireSectionHeader | undefined,
): WireSection[] {
  const out: WireSection[] = [];
  const items: WireFeedItem[] = [];
  let layout = "grid";
  for (const section of sections) {
    if (section["header"] !== undefined) {
      out.push(section);
      continue;
    }
    layout = section["layout"];
    for (const item of section["items"]) items.push(item);
  }
  if (items.length === 0) return out;
  const run: WireSection = { "layout": layout, "items": items };
  if (header !== undefined) run["header"] = header;
  out.push(run);
  return out;
}
