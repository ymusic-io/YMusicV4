// The Trending Music feed (legacy TrendingMusicFeedFetcher's job, in the remotely-updatable bundle).
// Charts do NOT come from the app's usual InnerTube path: a different host (charts.youtube.com), a
// different client (WEB_MUSIC_ANALYTICS) and — the reason this file exists — a response with no
// renderers in it at all. `musicAnalyticsSectionRenderer.content` carries raw chart DATA
// (trackViews / videoViews arrays of plain fields), so none of parse.ts's renderer walking applies
// and the whole page is composed here.
//
// The charts have no section titles of their own, so the engine synthesizes them: each header
// carries an English `title` plus a stable `titleKey` the app resolves to a localized string
// (falling back to `title` for a key it doesn't know — an older app meeting a newer bundle).

import { debug } from "../../debug";
import { Cache } from "../../protocol/cache";
import { JsEngineErrorImpl, errorCode } from "../../protocol/command";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { HttpRequest } from "../../protocol/http";
import { channelUrl, setDurationBadge } from "./parse";
import { executeWithCookies } from "./cookies";
import type { AuthContext } from "./cookies";
import { effectiveLocale, getSession } from "./session";
import type { YtSession } from "./session";
import { asString, get, isObj, walk } from "./scan";
import type { WireFeedItem, WireFeedPage, WireSection, WireSectionHeader } from "./wire";

const CHARTS_ORIGIN = "https://charts.youtube.com";

/**
 * Charts only: charts.youtube.com answers 429 to some Chrome versions and not to others — no clean
 * cutoff (2026-10-14, 8 tries each: 151/153/155 → 429, 154/156/160 → 200). Swap when it 429s again.
 */
const CHARTS_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/160.0.0.0 Safari/537.36";

const CHARTS_RETRY_DELAY_MS = 1500;

function delay(ms: number): Promise<void> {
  return new Promise(function (resolve) {
    setTimeout(function () {
      resolve();
    }, ms);
  });
}

const CHARTS_URL = CHARTS_ORIGIN + "/youtubei/v1/browse?alt=json";
const CHARTS_CLIENT_NAME = "31";
const CHARTS_CLIENT_VERSION = "2.0";
/** The query flags the site sends with every chart browse (comma URL-encoded, as the site does). */
const CHARTS_QUERY_FLAGS =
  "flags=MusicCharts__enable_apac_and_shorts_charts_expansion%2CMusicCharts__enable_recaptcha";

/** The signed-in slot of the held session; signed out (or no session yet) leaves every field unset. */
async function chartsAuth(): Promise<AuthContext> {
  let session: YtSession | undefined;
  try {
    session = await getSession(Infinity);
  } catch (e) {
    debug(function () {
      return "yt charts: no session, sending without the account slot: " + errText(e);
    });
  }
  return {
    sessionIndex: session?.sessionIndex,
    delegatedSessionId: session?.delegatedSessionId,
    userSessionId: session?.userSessionId,
  };
}

function chartsPost(
  body: Record<string, unknown>,
  auth: AuthContext,
  log: JsLogger,
): Promise<HttpResponse> {
  const builder = HttpRequest.post(CHARTS_URL)
    .json(body)
    .setHeader("User-Agent", CHARTS_UA)
    .setHeader("Origin", CHARTS_ORIGIN)
    .setHeader("X-Origin", CHARTS_ORIGIN)
    .setHeader("Referer", CHARTS_ORIGIN + "/")
    .setHeader("X-YouTube-Client-Name", CHARTS_CLIENT_NAME)
    .setHeader("X-YouTube-Client-Version", CHARTS_CLIENT_VERSION)
    .logger(log);
  return executeWithCookies(CHARTS_URL, builder, auth);
}

/**
 * POST the chart-home browse for the engine-resolved region (session.ts effectiveLocale).
 * Verified live 2026-07-25:
 * CHART_HOME serves every region tried (including the ones legacy's hardcoded table routed to the
 * location-insights perspective instead).
 * The site now sends a reCAPTCHA Enterprise token (`musicAnalyticsChartsBrowseParams.recaptchaToken`,
 * seen 2026-11-19) the bundle can't mint; a tokenless call is refused (403) intermittently, hence
 * the one retry.
 */
async function chartsBrowse(
  locale: { hl: string; gl: string },
  log?: JsLogger,
): Promise<Record<string, unknown>> {
  const client: Record<string, unknown> = {
    "clientName": "WEB_MUSIC_ANALYTICS",
    "clientVersion": CHARTS_CLIENT_VERSION,
    "hl": locale.hl,
    "gl": locale.gl,
    "experimentIds": [],
    "experimentsToken": "",
    "theme": "MUSIC",
  };
  const body: Record<string, unknown> = {
    "context": {
      "client": client,
      "capabilities": {},
      "request": { "internalExperimentFlags": [] },
    },
    "browseId": "FEmusic_analytics_charts_home",
    "query": CHARTS_QUERY_FLAGS + "&perspective=CHART_HOME&chart_params_country_code=" + locale.gl,
  };
  const logger = log ?? jsLogger("yt/trending");
  const auth = await chartsAuth();
  let res = await chartsPost(body, auth, logger);
  if (res.status === 403 || res.status === 429) {
    res.close();
    await delay(CHARTS_RETRY_DELAY_MS);
    res = await chartsPost(body, auth, logger);
  }
  if (!res.ok) {
    res.close();
    throw JsEngineErrorImpl.httpStatus("yt charts", res.status, "E20143");
  }
  return res.jsonObject();
}

// ---- Chart entries -----------------------------------------------------------------------------

/** The videoId of a chart entry: track charts key on an entity id ("G:…") and carry the video
 *  separately; video charts put the videoId in `id` itself. */
function entryVideoId(entry: Record<string, unknown>): string | undefined {
  const encrypted = asString(entry["encryptedVideoId"]);
  if (encrypted !== undefined && encrypted.length === 11) return encrypted;
  const id = asString(entry["id"]);
  return id !== undefined && id.length === 11 ? id : undefined;
}

/** `viewCount` arrives as a decimal string on both chart shapes; 0 when absent or unparsable. */
function entryViews(v: unknown): number {
  if (typeof v === "number") return v;
  const s = asString(v);
  if (s === undefined) return 0;
  const n = Number(s);
  return isFinite(n) && n > 0 ? n : 0;
}

/** A count in compact form ("5.9M"), so no invented unit word crosses the wire untranslated. */
function compactCount(n: number): string {
  const units = [
    { "at": 1e9, "suffix": "B" },
    { "at": 1e6, "suffix": "M" },
    { "at": 1e3, "suffix": "K" },
  ];
  for (const u of units) {
    if (n >= u["at"]) {
      const scaled = n / u["at"];
      const text = scaled < 100 ? scaled.toFixed(1) : String(Math.round(scaled));
      // 5.0M reads as noise — drop a trailing zero decimal.
      return (text.indexOf(".0") === text.length - 2 ? text.slice(0, -2) : text) + u["suffix"];
    }
  }
  return String(Math.round(n));
}

/** `videoDuration` is whole seconds — the app renders the badge verbatim, so format it here. */
function durationText(v: unknown): string | undefined {
  const total = typeof v === "number" ? Math.round(v) : 0;
  if (total <= 0) return undefined;
  const s = total % 60;
  const m = Math.floor(total / 60) % 60;
  const h = Math.floor(total / 3600);
  const ss = (s < 10 ? "0" : "") + s;
  if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + ss;
  return m + ":" + ss;
}

/** The credited artists, joined — chart entries carry an artist list, not a byline string. */
function artistNames(v: unknown): string | undefined {
  if (!Array.isArray(v)) return undefined;
  const names: string[] = [];
  for (const a of v) {
    const name = asString(get(a, "name"));
    if (name !== undefined && name !== "") names.push(name);
  }
  return names.length > 0 ? names.join(", ") : undefined;
}

/**
 * One chart entry → a feed item. Track entries carry square cover art; the app renders video items
 * in a 16:9 slot, so every entry gets the canonical video thumbnail instead (legacy did the same).
 */
function chartItem(entry: unknown): WireFeedItem | null {
  if (!isObj(entry)) return null;
  const id = entryVideoId(entry);
  const title = asString(entry["name"]) ?? asString(entry["title"]);
  if (id === undefined || title === undefined || title === "") return null;
  const item: WireFeedItem = { "kind": "video", "id": id, "title": title };
  item["thumbs"] = [
    { "url": "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", "w": 320, "h": 180 },
  ];
  setDurationBadge(item, durationText(entry["videoDuration"]));
  const channelId = asString(entry["externalChannelId"]);
  if (channelId !== undefined) {
    item["channelId"] = channelId;
    item["channelUrl"] = channelUrl(channelId);
  }
  const byline = artistNames(entry["artists"]) ?? asString(entry["channelName"]);
  if (byline !== undefined) {
    item["subtitleLine1"] = byline;
    item["channelTitle"] = byline;
  }
  const stats: string[] = [];
  const position = get(entry, "chartEntryMetadata", "currentPosition");
  if (typeof position === "number") stats.push("#" + position);
  const views = entryViews(entry["viewCount"]);
  if (views > 0) stats.push(compactCount(views));
  if (stats.length > 0) item["subtitleLine2"] = stats.join(" · ");
  return item;
}

// ---- Chart sections ----------------------------------------------------------------------------

/** The chart lists, in the order legacy showed them; `titleKey` is what the app localizes. */
interface ChartSpec {
  key: string;
  title: string;
}

function periodSpec(period: string | undefined, base: string, label: string): ChartSpec {
  if (period === "CHART_PERIOD_TYPE_DAILY")
    return { key: base + "_daily", title: "Daily " + label };
  if (period === "CHART_PERIOD_TYPE_WEEKLY")
    return { key: base + "_weekly", title: "Weekly " + label };
  return { key: base, title: label };
}

function chartSection(
  spec: ChartSpec,
  chart: Record<string, unknown> | undefined,
): WireSection | null {
  if (chart === undefined) return null;
  const entries = chart["videoViews"] ?? chart["trackViews"];
  if (!Array.isArray(entries)) return null;
  const items: WireFeedItem[] = [];
  for (const e of entries) {
    const item = chartItem(e);
    if (item !== null) items.push(item);
  }
  if (items.length === 0) return null;
  const header: WireSectionHeader = { "title": spec.title, "titleKey": spec.key };
  // A chart runs 30–100 entries; collapsed, the four of them read as an index of the region's
  // charts and each expands on demand instead of burying the next header a hundred rows down.
  return {
    "header": header,
    "layout": "list",
    "items": items,
    "collapsible": true,
    "playMode": "queue",
  };
}

/** The first `musicAnalyticsSectionRenderer.content` in the response — the only node charts use. */
function analyticsContent(root: Record<string, unknown>): Record<string, unknown> | undefined {
  let found: Record<string, unknown> | undefined;
  walk(root, (key, value) => {
    if (found !== undefined) return true;
    if (key === "musicAnalyticsSectionRenderer") {
      const content = get(value, "content");
      if (isObj(content)) found = content;
      return true;
    }
    return;
  });
  return found;
}

/**
 * The trending music page. The response holds every chart the region publishes in two arrays
 * (`trackTypes` = songs, `videos` = music videos), each tagged with a `listType` and a chart period;
 * the shown set — and which period wins when a chart ships both — mirrors legacy's picks.
 */
function composeTrendingPage(response: Record<string, unknown>): WireFeedPage {
  const content = analyticsContent(response);
  if (content === undefined)
    throw JsEngineErrorImpl.parse("yt/trending: no musicAnalyticsSectionRenderer content");

  let trendingVideos: Record<string, unknown> | undefined; // the region's trending music videos
  let topViewsRegion: Record<string, unknown> | undefined; // location-insights' equivalent chart
  let topSongs: Record<string, unknown> | undefined;
  let topMusicVideos: Record<string, unknown> | undefined;
  let topSongsOnShorts: Record<string, unknown> | undefined;

  for (const group of ["trackTypes", "videos"]) {
    const charts = content[group];
    if (!Array.isArray(charts)) continue;
    for (const chart of charts) {
      if (!isObj(chart)) continue;
      const listType = asString(chart["listType"]);
      const period = asString(chart["chartPeriodType"]);
      if (listType === "TRENDING_CHART") {
        trendingVideos = chart;
      } else if (listType === "TOP_VIEWS") {
        topViewsRegion = chart;
      } else if (listType === "TOP_VIEWS_CHART") {
        // The same listType serves both charts; only the music-video one counts views.
        if (chart["videoViews"] !== undefined) {
          if (topMusicVideos === undefined || period === "CHART_PERIOD_TYPE_DAILY")
            topMusicVideos = chart;
        } else if (topSongs === undefined || period === "CHART_PERIOD_TYPE_WEEKLY") {
          topSongs = chart;
        }
      } else if (listType === "TOP_SHORTS_BY_USAGE") {
        if (topSongsOnShorts === undefined || period === "CHART_PERIOD_TYPE_DAILY")
          topSongsOnShorts = chart;
      }
    }
  }

  const sections: WireSection[] = [];
  const push = (section: WireSection | null): void => {
    if (section !== null) sections.push(section);
  };
  push(
    chartSection(
      { key: "trending_music_videos", title: "Trending Music Videos" },
      trendingVideos ?? topViewsRegion,
    ),
  );
  push(
    chartSection(
      periodSpec(asString(get(topSongs, "chartPeriodType")), "top_songs", "Top Songs"),
      topSongs,
    ),
  );
  push(
    chartSection(
      periodSpec(
        asString(get(topMusicVideos, "chartPeriodType")),
        "top_music_videos",
        "Top Music Videos",
      ),
      topMusicVideos,
    ),
  );
  push(
    chartSection(
      periodSpec(
        asString(get(topSongsOnShorts, "chartPeriodType")),
        "top_songs_on_shorts",
        "Top Songs on Shorts",
      ),
      topSongsOnShorts,
    ),
  );
  return { "sections": sections };
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

const CHARTS_CACHE_SCOPE = "yt-charts";
/** Charts move daily at most: a region's page serves 10h before the next charts.youtube.com hit. */
const CHARTS_TTL_MS = 10 * 60 * 60 * 1000;

interface CachedCharts {
  page: WireFeedPage;
  fresh: boolean;
}

/** The region's last cached page, however old; null on a miss or without a cache store. */
async function readCachedPage(key: string): Promise<CachedCharts | null> {
  try {
    return await Cache.get(CHARTS_CACHE_SCOPE, key, async (source, meta) => {
      const page = (await source.readJsonObject()) as unknown as WireFeedPage;
      return { page: page, fresh: Date.now() - meta.updatedAt <= CHARTS_TTL_MS };
    });
  } catch (e) {
    // No cache store wired (capability inert) or a store error — the live fetch still works.
    debug(function () {
      return "yt charts cache read skipped: " + errText(e);
    });
    return null;
  }
}

/** A failed refresh serves the region's stale page, marked with the failure's code, when there is
 *  one; only a cold cache throws. */
export async function trendingMusicPage(log?: JsLogger): Promise<WireFeedPage> {
  const locale = await effectiveLocale();
  const key = locale.hl + "_" + locale.gl;
  const cached = await readCachedPage(key);
  if (cached !== null && cached.fresh) return cached.page;
  let page: WireFeedPage;
  try {
    page = composeTrendingPage(await chartsBrowse(locale, log));
  } catch (e) {
    if (cached === null) throw e;
    console.warn("yt charts refresh failed, serving the stale page:", e);
    const stale = cached.page;
    stale["partialErrorCode"] = errorCode(e);
    return stale;
  }
  try {
    await Cache.setJson(CHARTS_CACHE_SCOPE, key, page);
  } catch (e) {
    debug(function () {
      return "yt charts cache write skipped: " + errText(e);
    });
  }
  return page;
}
