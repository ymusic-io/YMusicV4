// Outside links → app targets (yt/resolve-link). The app hands over any url it was opened with —
// youtube.com in all its hosts, youtu.be, music.youtube.com, or the same paths under its own scheme
// and domains — and gets back an app link it already knows how to open. Every YouTube url shape
// (and the handle → channel resolution) lives here, so a new shape never needs an app update.

import { onAccept } from "../../transport";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { hostLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { innertubeJson, innertubeJsonOrNotFound } from "./innertube";
import { musicBrowseUrl, showPlaylistId } from "./musicParse";
import { channelUrl, playlistUrl } from "./parse";
import { asString, get } from "./scan";
import { videoDetailsOf } from "./watchParse";
import type { WireFeedItem, WireLinkTarget } from "./wire";

interface ParsedLink {
  host: string;
  /** Path segments, decoded, empty ones dropped. */
  segments: string[];
  query: Record<string, string>;
}

function decodeComponent(s: string): string {
  try {
    return decodeURIComponent(s.split("+").join(" "));
  } catch (e) {
    return s;
  }
}

/** [url] split without the URL API (absent in QuickJS). A schemeless or "ymusic://app/…" url has
 *  host "" / "app" — only a youtu.be or music.youtube.com host changes the routing. */
function parseLink(url: string): ParsedLink {
  let rest = url.trim();
  const hash = rest.indexOf("#");
  if (hash >= 0) rest = rest.slice(0, hash);
  let host = "";
  const scheme = rest.indexOf("://");
  if (scheme >= 0) {
    rest = rest.slice(scheme + 3);
    const slash = rest.search(/[/?]/);
    host = (slash < 0 ? rest : rest.slice(0, slash)).toLowerCase();
    rest = slash < 0 ? "" : rest.slice(slash);
    const at = host.lastIndexOf("@");
    if (at >= 0) host = host.slice(at + 1);
    const port = host.indexOf(":");
    if (port >= 0) host = host.slice(0, port);
  }
  const q = rest.indexOf("?");
  const path = q < 0 ? rest : rest.slice(0, q);
  const query: Record<string, string> = {};
  if (q >= 0) {
    for (const pair of rest.slice(q + 1).split("&")) {
      const eq = pair.indexOf("=");
      if (eq <= 0) continue;
      const key = decodeComponent(pair.slice(0, eq));
      if (query[key] === undefined) query[key] = decodeComponent(pair.slice(eq + 1));
    }
  }
  const segments: string[] = [];
  for (const s of path.split("/")) if (s !== "") segments.push(decodeComponent(s));
  return { host: host, segments: segments, query: query };
}

const VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/** A `t=` / `start=` value — "90", "90s", "1h2m3s" — in ms; undefined when it is none of those. */
function startMsOf(t: string | undefined): number | undefined {
  if (t === undefined) return undefined;
  const m = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/.exec(t);
  if (m === null || t === "") return undefined;
  const seconds = Number(m[1] ?? 0) * 3600 + Number(m[2] ?? 0) * 60 + Number(m[3] ?? 0);
  return seconds > 0 ? seconds * 1000 : undefined;
}

/** The video a link plays, or undefined: /watch?v=, youtu.be/<id>, /shorts|live|embed|v/<id>. */
function videoIdOf(link: ParsedLink): string | undefined {
  const first = link.segments[0];
  let id: string | undefined;
  if (link.host === "youtu.be") id = first;
  else if (first === "watch") id = link.query["v"];
  else if (first === "shorts" || first === "live" || first === "embed" || first === "v") {
    id = link.segments[1];
  }
  return id !== undefined && VIDEO_ID.test(id) ? id : undefined;
}

async function videoTarget(
  videoId: string,
  link: ParsedLink,
  log: JsLogger,
): Promise<WireLinkTarget> {
  const details = videoDetailsOf(await innertubeJson("next", { "videoId": videoId }, log));
  const item: WireFeedItem = {
    "kind": "video",
    "id": videoId,
    "title": details["title"] ?? "",
    "thumbs": [
      { "url": "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", "w": 480, "h": 360 },
    ],
  };
  const channelTitle = details["channelTitle"];
  if (channelTitle !== undefined) {
    item["channelTitle"] = channelTitle;
    item["subtitleLine1"] = channelTitle;
  }
  const owner = details["channelUrl"];
  if (owner !== undefined) item["channelUrl"] = owner;
  const target: WireLinkTarget = { "kind": "video", "item": item };
  const list = link.query["list"];
  if (list !== undefined && list !== "") target["url"] = playlistUrl(list, videoId);
  const startMs = startMsOf(link.query["t"] ?? link.query["start"]);
  if (startMs !== undefined) target["startMs"] = startMs;
  return target;
}

/** A /@handle, /c/<name> or /user/<name> page's channel id, via YouTube's own url resolver;
 *  undefined when YouTube knows no such page. */
async function resolveChannelId(path: string, log: JsLogger): Promise<string | undefined> {
  const root = await innertubeJsonOrNotFound(
    "navigation/resolve_url",
    { "url": "https://www.youtube.com" + path },
    log,
  );
  if (root === null) return undefined;
  const id = asString(get(root, "endpoint", "browseEndpoint", "browseId"));
  return id !== undefined && id.indexOf("UC") === 0 ? id : undefined;
}

async function resolveLink(url: string, log: JsLogger): Promise<WireLinkTarget> {
  const link = parseLink(url);
  const none: WireLinkTarget = { "kind": "none" };
  const videoId = videoIdOf(link);
  if (videoId !== undefined) return videoTarget(videoId, link, log);
  const first = link.segments[0];
  if (first === undefined) return none;
  const list = link.query["list"];
  if ((first === "playlist" || first === "watch") && list !== undefined && list !== "") {
    return { "kind": "playlist", "url": playlistUrl(showPlaylistId(list) ?? list) };
  }
  if (first === "results" || first === "search") {
    const query = (link.query["search_query"] ?? link.query["q"] ?? "").trim();
    return query !== "" ? { "kind": "search", "query": query } : none;
  }
  if (link.host === "music.youtube.com" && first === "browse" && link.segments[1] !== undefined) {
    return { "kind": "music", "url": musicBrowseUrl(link.segments[1]) };
  }
  const second = link.segments[1];
  if (first === "channel" && second !== undefined && second.indexOf("UC") === 0) {
    return { "kind": "channel", "url": channelUrl(second) };
  }
  let named: string | undefined;
  if (first.charAt(0) === "@" && first.length > 1) named = "/" + first;
  else if ((first === "c" || first === "user") && second !== undefined)
    named = "/" + first + "/" + second;
  if (named !== undefined) {
    const id = await resolveChannelId(encodeURI(named), log);
    return id !== undefined ? { "kind": "channel", "url": channelUrl(id) } : none;
  }
  return none;
}

// {"url"} -> WireLinkTarget.
onAccept(
  "yt/resolve-link",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const url = asString(args["url"]);
    if (url === undefined) throw JsEngineErrorImpl.parse("yt/resolve-link needs url");
    const p = out.writeObjectAsJson(await resolveLink(url, hostLogger(args, "yt/resolve-link")));
    if (p) await p;
  }),
);
