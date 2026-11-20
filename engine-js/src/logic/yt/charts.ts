// The Top charts page, from YouTube Music's Charts page: its chart playlists, each as one list, then
// its Top artists. charts.youtube.com is not used: since 2026-11-20 it refuses every call without a
// reCAPTCHA Enterprise token, which only a real browser can mint.

import { debug } from "../../debug";
import { Cache } from "../../protocol/cache";
import { JsEngineErrorImpl, errorCode } from "../../protocol/command";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { collectPage } from "./parse";
import { innertubeJson } from "./innertube";
import { collectMusicPage } from "./musicParse";
import { musicJson } from "./ytmusic";
import { effectiveLocale } from "./session";
import type { WireFeedItem, WireFeedPage, WireSection } from "./wire";

/** The Video charts shelf holds Trending 20, Daily Top and Top 100; each one is a browse request. */
const CHART_PLAYLISTS_MAX = 3;

/**
 * The region's charts from YouTube Music's Charts page (music.youtube.com/charts, formData picks the
 * country), which holds two shelves: Video charts (chart playlists) and Top artists. Each chart
 * playlist becomes its own collapsible list, titled with YouTube's localized name and numbered by
 * position (a playlist carries no ranks or views); Top artists follows them, as YouTube shows it.
 */
async function ytMusicChartsPage(gl: string, log: JsLogger): Promise<WireFeedPage> {
  const charts = collectMusicPage(
    await musicJson(
      "browse",
      { "browseId": "FEmusic_charts", "formData": { "selectedValues": [gl] } },
      log,
    ),
  );
  const playlists: WireFeedItem[] = [];
  let artists: WireSection | undefined;
  for (const section of charts["sections"]) {
    const items = section["items"];
    if (items.length > 0 && items.every((item) => item["kind"] === "channel")) {
      if (artists === undefined) artists = section;
      continue;
    }
    for (const item of items) if (item["kind"] === "playlist") playlists.push(item);
  }
  if (playlists.length === 0)
    throw JsEngineErrorImpl.parse("yt/trending: no chart playlists on YouTube Music's charts");
  const loaded = await Promise.all(
    playlists.slice(0, CHART_PLAYLISTS_MAX).map(async function (playlist) {
      try {
        const root = await innertubeJson("browse", { "browseId": "VL" + playlist["id"] }, log);
        return { playlist: playlist, page: collectPage(root) };
      } catch (e) {
        console.warn("yt charts: chart playlist " + playlist["id"] + " failed:", e);
        return { playlist: playlist, error: e };
      }
    }),
  );
  const sections: WireSection[] = [];
  let firstError: unknown;
  for (const result of loaded) {
    if (result.page === undefined) {
      if (firstError === undefined) firstError = result.error;
      continue;
    }
    const items: WireFeedItem[] = [];
    for (const section of result.page["sections"]) {
      for (const item of section["items"]) {
        if (item["kind"] !== "video") continue;
        item["subtitleLine2"] = "#" + (items.length + 1);
        items.push(item);
      }
    }
    if (items.length === 0) continue;
    sections.push({
      "header": { "title": result.playlist["title"] },
      "layout": "list",
      "items": items,
      "collapsible": true,
      "playMode": "queue",
    });
  }
  if (sections.length === 0) {
    if (firstError !== undefined) throw firstError;
    throw JsEngineErrorImpl.parse("yt/trending: YouTube Music's chart playlists were empty");
  }
  if (artists !== undefined) {
    artists["collapsible"] = true;
    sections.push(artists);
  }
  return { "sections": sections };
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

const CHARTS_CACHE_SCOPE = "yt-charts";
/** The charts move daily at most: a region's page serves a day before the next fetch. */
const CHARTS_TTL_MS = 24 * 60 * 60 * 1000;

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
    page = await ytMusicChartsPage(locale.gl, log ?? jsLogger("yt/trending"));
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
