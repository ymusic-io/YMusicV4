// The Top charts page: YouTube's own music front page, youtube.com/music (the YouTube Music
// channel's Home tab). Its shelves (trending songs, top charts, new releases, live performances…)
// follow the region like any other page. charts.youtube.com is not used: since 2026-11-20 it
// refuses every call without a reCAPTCHA Enterprise token, which only a real browser can mint.

import { debug } from "../../debug";
import { Cache } from "../../protocol/cache";
import { JsEngineErrorImpl, errorCode } from "../../protocol/command";
import { jsLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { collectPage } from "./parse";
import { innertubeJson } from "./innertube";
import { effectiveLocale } from "./session";
import type { WireFeedPage } from "./wire";

/** youtube.com/music. */
const MUSIC_CHANNEL_ID = "UC-9-kyTW8ZkZNDHQJ6FgpwQ";

/** The channel's Home shelves, in YouTube's order; its header and tabs are not part of the page. */
async function musicChannelPage(log: JsLogger): Promise<WireFeedPage> {
  const page = collectPage(await innertubeJson("browse", { "browseId": MUSIC_CHANNEL_ID }, log));
  const sections = page["sections"].filter((section) => section["items"].length > 0);
  if (sections.length === 0)
    throw JsEngineErrorImpl.parse("yt/trending: youtube.com/music listed no shelves");
  return { "sections": sections };
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

const CHARTS_CACHE_SCOPE = "yt-charts";
/** The page changes daily at most: a region's copy serves a day before the next fetch. */
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
    page = await musicChannelPage(log ?? jsLogger("yt/trending"));
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
