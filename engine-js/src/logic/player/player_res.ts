// Cache /player JSON (pre–fake-144p) plus decoded SABR URLs captured from the
// player's own googlevideo requests. Download uses that; we do not decipher.

import { debug } from "../../debug";
import { JsEngineErrorImpl } from "../../protocol/command";
import { playingVideoId } from "./live";

const CACHE_MS = 3 * 60 * 1000;
const WAIT_MS = 20_000;

/** null = the entry is complete; else YouTube's non-OK playabilityStatus, which is never cached. */
type Verdict = { status: string; reason: string } | null;
type Waiter = (verdict: Verdict) => void;

type Entry = {
  rec: Record<string, unknown> | null;
  sabrUrls: string[];
  expires: number;
  waiters: Waiter[];
};

const cache: Record<string, Entry> = {};
/** googlevideo `id=o-…` → videoId. SABR URLs have no `v=`. */
const streamToVid: Record<string, string> = {};
let lastPlayerVid = "";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function now(): number {
  return Date.now();
}

/** The presenting video never expires: base.js won't refetch it for a preload of itself. */
function expired(id: string, cached: Entry, time: number, playing: string): boolean {
  return cached.expires < time && id !== playing;
}

function prune(): void {
  const t = now();
  const playing = playingVideoId();
  for (const id in cache) {
    const e = cache[id];
    if (e && expired(id, e, t, playing) && !e.waiters.length) delete cache[id];
  }
}

function complete(e: Entry): boolean {
  return !!(e.rec && e.sabrUrls.length);
}

function flush(e: Entry, verdict: Verdict): void {
  const w = e.waiters;
  e.waiters = [];
  for (let i = 0; i < w.length; i++) w[i]!(verdict);
}

function entry(id: string): Entry {
  let e = cache[id];
  if (!e) {
    e = { rec: null, sabrUrls: [], expires: now() + CACHE_MS, waiters: [] };
    cache[id] = e;
  }
  return e;
}

function simpleName(v: unknown): string {
  if (typeof v === "string") return v;
  const o = asRec(v);
  if (o && typeof o["simpleText"] === "string") return o["simpleText"] as string;
  return "";
}

function ytcfgStr(key: string): string {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  const get = cfg ? cfg["get"] : null;
  if (typeof get === "function") {
    const v = (get as (k: string) => unknown)(key);
    return typeof v === "string" ? v : "";
  }
  const data = cfg ? asRec(cfg["data_"]) : null;
  const v = data ? data[key] : null;
  return typeof v === "string" ? v : "";
}

// The video's own default caption, as the player resolves it: the default audio track's
// defaultCaptionTrackIndex into the raw captionTracks. -1 when the video sets none (captions start off).
function defaultCaptionIndex(list: Record<string, unknown> | null): number {
  const tracks = list ? list["audioTracks"] : null;
  if (!Array.isArray(tracks)) return -1;
  const at = list ? list["defaultAudioTrackIndex"] : null;
  const audio = asRec(tracks[typeof at === "number" ? at : 0]);
  const index = audio ? audio["defaultCaptionTrackIndex"] : null;
  return typeof index === "number" ? index : -1;
}

function flattenCaptions(rec: Record<string, unknown>): Record<string, unknown>[] {
  const caps = asRec(rec["captions"]);
  const list = caps ? asRec(caps["playerCaptionsTracklistRenderer"]) : null;
  const raw = list ? list["captionTracks"] : null;
  const out: Record<string, unknown>[] = [];
  if (!Array.isArray(raw)) return out;
  const defaultIndex = defaultCaptionIndex(list);
  for (let i = 0; i < raw.length; i++) {
    const t = asRec(raw[i]);
    if (!t) continue;
    const url = typeof t["baseUrl"] === "string" ? t["baseUrl"] : "";
    const lang = typeof t["languageCode"] === "string" ? t["languageCode"] : "";
    if (!url || !lang) continue;
    const kind = typeof t["kind"] === "string" ? t["kind"] : "";
    const name = simpleName(t["name"]) || lang;
    out.push({
      "languageCode": lang,
      "name": kind === "asr" ? name + " (auto)" : name,
      "baseUrl": url,
      "isAsr": kind === "asr",
      "isDefault": i === defaultIndex,
    });
  }
  return out;
}

function flattenTranslations(rec: Record<string, unknown>): Record<string, unknown>[] {
  const caps = asRec(rec["captions"]);
  const list = caps ? asRec(caps["playerCaptionsTracklistRenderer"]) : null;
  const raw = list ? list["translationLanguages"] : null;
  const out: Record<string, unknown>[] = [];
  if (!Array.isArray(raw)) return out;
  for (let i = 0; i < raw.length; i++) {
    const t = asRec(raw[i]);
    if (!t) continue;
    const lang = typeof t["languageCode"] === "string" ? t["languageCode"] : "";
    if (!lang) continue;
    out.push({ "languageCode": lang, "name": simpleName(t["languageName"]) || lang });
  }
  return out;
}

function ustreamerConfig(rec: Record<string, unknown>): string {
  const pc = asRec(rec["playerConfig"]);
  const mc = pc ? asRec(pc["mediaCommonConfig"]) : null;
  const ur = mc ? asRec(mc["mediaUstreamerRequestConfig"]) : null;
  const v = ur ? ur["videoPlaybackUstreamerConfig"] : null;
  return typeof v === "string" ? v : "";
}

const BACKUP: Record<string, string> = {
  "_lastModified": "lastModified",
  "_mimeType": "mimeType",
  "_xtags": "xtags",
  "_xtag": "xtag",
  "_initRange": "initRange",
  "_indexRange": "indexRange",
  "_contentLength": "contentLength",
};

function restoreFormat(fmt: Record<string, unknown>): Record<string, unknown> {
  let touched = false;
  for (const b in BACKUP) {
    if (fmt[b] !== undefined) {
      touched = true;
      break;
    }
  }
  if (!touched) return fmt;
  const out: Record<string, unknown> = {};
  for (const k in fmt) {
    if (k.charAt(0) === "_") continue;
    out[k] = fmt[k];
  }
  for (const b in BACKUP) {
    if (fmt[b] !== undefined) out[BACKUP[b]!] = fmt[b];
  }
  return out;
}

function streamingForHost(sd: Record<string, unknown>, sabrUrl: string): Record<string, unknown> {
  const streaming: Record<string, unknown> = {};
  for (const k in sd) streaming[k] = sd[k];
  const fmts = sd["adaptiveFormats"];
  if (Array.isArray(fmts)) {
    const restored: unknown[] = [];
    for (let i = 0; i < fmts.length; i++) {
      const f = asRec(fmts[i]);
      restored.push(f ? restoreFormat(f) : fmts[i]);
    }
    streaming["adaptiveFormats"] = restored;
  }
  if (sabrUrl) streaming["serverAbrStreamingUrl"] = sabrUrl;
  return streaming;
}

const MUSIC_DESCRIPTION_PREFIX = "Provided to YouTube by";
const CREDIT_SEPARATOR = " · ";

/**
 * The track's artist for file tags. Auto-generated music uploads describe themselves as "Provided to
 * YouTube by <label>", then `Title · Artist · Artist` — the only artist credit /player carries; else
 * the channel name minus " - Topic" and an "Official" suffix.
 */
function trackArtist(vd: Record<string, unknown>): string {
  const desc = typeof vd["shortDescription"] === "string" ? vd["shortDescription"] : "";
  if (desc.indexOf(MUSIC_DESCRIPTION_PREFIX) === 0) {
    const credit = desc.split("\n").find((line) => line.indexOf(CREDIT_SEPARATOR) >= 0);
    if (credit !== undefined) {
      const names = credit
        .split(CREDIT_SEPARATOR)
        .slice(1)
        .map((s) => s.trim())
        .filter((s) => s !== "");
      if (names.length > 0) return names.join(", ");
    }
  }
  const author = typeof vd["author"] === "string" ? vd["author"] : "";
  return author
    .replace(/ - Topic$/, "")
    .replace(/[^\w,]+official\b/gi, "")
    .trim();
}

/** A copy of videoDetails whose `author` is the track artist; the cached response stays as fetched. */
function videoDetailsForHost(v: unknown): unknown {
  const vd = asRec(v);
  if (!vd) return v;
  const artist = trackArtist(vd);
  return artist ? Object.assign({}, vd, { "author": artist }) : vd;
}

function buildDownloadJson(e: Entry): Record<string, unknown> {
  const rec = e.rec!;
  const sd = asRec(rec["streamingData"]) || {};
  const sabr = e.sabrUrls.length ? e.sabrUrls[e.sabrUrls.length - 1]! : "";
  const streaming = streamingForHost(sd, sabr);
  const playerConfig = asRec(rec["playerConfig"]);
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  const js = ytcfgStr("PLAYER_JS_URL");
  return {
    "clientName": "WEB",
    "clientNameId": 1,
    "clientVersion": cver,
    "playerJs": js,
    "videoDetails": videoDetailsForHost(rec["videoDetails"]),
    "microformat": rec["microformat"],
    "playabilityStatus": rec["playabilityStatus"],
    "streamingData": streaming,
    "audioConfig": playerConfig ? playerConfig["audioConfig"] : undefined,
    "captions": rec["captions"],
    "captionTracks": flattenCaptions(rec),
    "translationLanguages": flattenTranslations(rec),
    "videoPlaybackUstreamerConfig": ustreamerConfig(rec),
  };
}

export function peekDownloadJson(videoId: string): Record<string, unknown> | null {
  prune();
  const e = cache[videoId];
  if (!e || !complete(e) || expired(videoId, e, now(), playingVideoId())) return null;
  e.expires = now() + CACHE_MS;
  return buildDownloadJson(e);
}

export function waitDownloadJson(videoId: string, ms: number): Promise<Record<string, unknown>> {
  const hit = peekDownloadJson(videoId);
  if (hit) return Promise.resolve(hit);
  const e = entry(videoId);
  return new Promise(function (resolve, reject) {
    const waiter: Waiter = function (verdict) {
      clearTimeout(t);
      if (verdict) {
        reject(
          new JsEngineErrorImpl(
            "playability " + verdict.status + ": " + verdict.reason,
            verdict.status,
            verdict.reason ? { userMessage: verdict.reason } : undefined,
          ),
        );
        return;
      }
      try {
        resolve(buildDownloadJson(e));
      } catch (err) {
        reject(err);
      }
    };
    const t = setTimeout(function () {
      const i = e.waiters.indexOf(waiter);
      if (i >= 0) e.waiters.splice(i, 1);
      const missing = !e.rec ? "/player never arrived" : "no SABR URL after /player";
      reject(new JsEngineErrorImpl("player response not cached: " + missing, "E20087"));
    }, ms);
    e.waiters.push(waiter);
    if (complete(e)) flush(e, null);
  });
}

export function downloadWaitMs(): number {
  return WAIT_MS;
}

function queryParam(url: string, key: string): string {
  const m = new RegExp("[?&]" + key + "=([^&]*)").exec(url);
  if (!m) return "";
  try {
    return decodeURIComponent(m[1]!.replace(/\+/g, " "));
  } catch (_e) {
    return m[1]!;
  }
}

function streamIdOf(url: string): string {
  return queryParam(url, "id");
}

export function notePlayerResponse(rec: Record<string, unknown>): void {
  const vd = asRec(rec["videoDetails"]);
  const vid = vd && typeof vd["videoId"] === "string" ? (vd["videoId"] as string) : "";
  if (!vid) return;
  const st = asRec(rec["playabilityStatus"]);
  const ok = !st || st["status"] === "OK" || st["status"] == null;
  if (!ok) {
    const waiting = cache[vid];
    if (waiting && waiting.waiters.length) {
      const status = String(st["status"]).toUpperCase() || "ERROR";
      const reason = st["reason"] != null ? String(st["reason"]) : "";
      console.info("mse player-res /player vid=" + vid + " playability=" + status);
      flush(waiting, { status: status, reason: reason });
    }
    return;
  }
  lastPlayerVid = vid;
  const sd = asRec(rec["streamingData"]);
  const listed =
    sd && typeof sd["serverAbrStreamingUrl"] === "string" ? sd["serverAbrStreamingUrl"] : "";
  const sid = listed ? streamIdOf(listed as string) : "";
  if (sid) streamToVid[sid] = vid;
  const e = entry(vid);
  e.rec = rec;
  e.expires = now() + CACHE_MS;
  console.info(
    "mse player-res /player vid=" +
      vid +
      " stream=" +
      (sid || "-") +
      " sabr=" +
      e.sabrUrls.length +
      " wait=" +
      e.waiters.length,
  );
  if (complete(e)) flush(e, null);
  if (playerResponseListener) playerResponseListener(vid);
}

let playerResponseListener: ((videoId: string) => void) | null = null;

/** Told the videoId of every playable /player response, after it is cached. */
export function setPlayerResponseListener(fn: ((videoId: string) => void) | null): void {
  playerResponseListener = fn;
}

/** The video's `microformat` category ("Music", …) from its cached /player; "" if unknown. */
export function playerCategory(videoId: string): string {
  const e = cache[videoId];
  const micro =
    e && e.rec ? asRec(asRec(e.rec["microformat"])?.["playerMicroformatRenderer"]) : null;
  const category = micro ? micro["category"] : undefined;
  return typeof category === "string" ? category : "";
}

/** SABR `id=o-…` mapped from a prior /player. Empty if this stream is unknown. */
export function videoIdForSabrUrl(url: string): string {
  const sid = streamIdOf(url);
  return (sid && streamToVid[sid]) || "";
}

const CATALOG_KEYS = [
  "itag",
  "lastModified",
  "mimeType",
  "width",
  "height",
  "contentLength",
  "approxDurationMs",
  "isDrc",
  "xtags",
  "indexRange",
  "audioTrack",
];

/** Original /player adaptiveFormats metadata (no url / cipher) for the SABR stream of [url]. */
export function sabrAdaptiveFormats(url: string): Record<string, unknown>[] {
  const vid = videoIdForSabrUrl(url) || lastPlayerVid;
  const e = vid ? cache[vid] : undefined;
  const sd = e && e.rec ? asRec(e.rec["streamingData"]) : null;
  const fmts = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(fmts)) return [];
  const out: Record<string, unknown>[] = [];
  for (let i = 0; i < fmts.length; i++) {
    const f = asRec(fmts[i]);
    if (!f) continue;
    const r = restoreFormat(f);
    const row: Record<string, unknown> = {};
    for (let k = 0; k < CATALOG_KEYS.length; k++) {
      const key = CATALOG_KEYS[k]!;
      if (r[key] != null) row[key] = r[key];
    }
    out.push(row);
  }
  return out;
}

export function noteSabrPlaybackUrl(url: string): void {
  if (url.indexOf("/videoplayback") < 0) return;
  if (url.indexOf("sabr=") < 0) return;
  const sid = streamIdOf(url);
  const vid = (sid && streamToVid[sid]) || lastPlayerVid;
  if (!vid) return;
  if (sid && !streamToVid[sid]) streamToVid[sid] = vid;
  const e = entry(vid);
  if (e.sabrUrls.length && e.sabrUrls[e.sabrUrls.length - 1] === url) return;
  e.sabrUrls.push(url);
  e.expires = now() + CACHE_MS;
  debug(function () {
    return "mse player-res sabr vid=" + vid + " stream=" + (sid || "-") + " n=" + e.sabrUrls.length;
  });
  if (complete(e)) flush(e, null);
}
