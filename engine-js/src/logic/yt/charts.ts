// The Top charts page: the region's chart playlists from YouTube Music's Charts page, each as one
// list. charts.youtube.com is not used: since 2026-11-20 it refuses every call without a reCAPTCHA
// Enterprise token, which only a real browser can mint.

import { debug } from "../../debug";
import { Cache } from "../../protocol/cache";
import { JsEngineErrorImpl, errorCode } from "../../protocol/command";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { collectPage } from "./parse";
import { innertubeJson } from "./innertube";
import { musicJson } from "./ytmusic";
import { effectiveLocale } from "./session";
import { asString, get, walk } from "./scan";
import type { WireFeedItem, WireFeedPage, WireSection } from "./wire";

/** At most this many chart playlists load: each one is a browse request. */
const YTMUSIC_CHART_LISTS_MAX = 4;

/**
 * The region's charts from YouTube Music's own Charts page (music.youtube.com/charts, formData picks
 * the country). Each chart playlist becomes a collapsible list titled with YouTube's localized name;
 * a playlist carries no ranks or views, so the rank is the list position.
 */
async function ytMusicChartsPage(gl: string, log: JsLogger): Promise<WireFeedPage> {
  const root = await musicJson(
    "browse",
    { "browseId": "FEmusic_charts", "formData": { "selectedValues": [gl] } },
    log,
  );
  const lists: Array<{ id: string; title: string }> = [];
  walk(root, (key, value) => {
    if (key !== "musicTwoRowItemRenderer") return;
    const id = asString(get(value, "navigationEndpoint", "browseEndpoint", "browseId"));
    const title = asString(get(value, "title", "runs", 0, "text"));
    if (id !== undefined && id.indexOf("VL") === 0 && title !== undefined) {
      lists.push({ id: id, title: title });
    }
    return true;
  });
  if (lists.length === 0)
    throw JsEngineErrorImpl.parse("yt/trending: no chart playlists on YouTube Music's charts");
  const loaded = await Promise.all(
    lists.slice(0, YTMUSIC_CHART_LISTS_MAX).map(async function (list) {
      try {
        return {
          list: list,
          page: collectPage(await innertubeJson("browse", { "browseId": list.id }, log)),
        };
      } catch (e) {
        console.warn("yt charts: YouTube Music chart list " + list.id + " failed:", e);
        return { list: list, error: e };
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
      "header": { "title": result.list.title },
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
  return { "sections": sections };
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

const CHARTS_CACHE_SCOPE = "yt-charts";
/** Charts move daily at most: a region's page serves 10h before the next YouTube Music fetch. */
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
