// Headless YouTube player: singleton Application over WEB homepage config from main.

import { JsEngineErrorImpl, callMain } from "../../protocol/command";
import {
  activeHtmlMedia,
  dropVideoFakeOverlay,
  installMseGlobals,
  installPlaybackQuality,
  isLiveDuration,
  mseCreateObjectURL,
  mseRevokeObjectURL,
  dumpDrain,
  onLoopAtEnd,
  resetDrainCounts,
} from "../../protocol/mse";
import { applyNavigatorLanguages, endLayoutProbes, installOrigEnv } from "./env";
import { installMediaCapabilities } from "./media_caps";
import { installXhr } from "../yt/xhr";
import { installFetch } from "../yt/fetch";
import { executeWithCookies } from "../yt/cookies";
import { HttpRequest } from "../../protocol/http";
import { utf8Encode } from "../../transport";
import { minterFromConfig } from "./pot";
import { installPlayerAdStrip } from "./skip_ads";
import { installSabrIntercept } from "../yt/sabr_http";
import { installAdaptiveFormatsHeader } from "../yt/sabr_af";
import { installWatchNextHarvest } from "./watch_next";
import { isAudioOnly, setAudioOnly, setYmQuality, ymQuality } from "./audio_only";
import { isYmLive } from "./live";
import { channelUrl } from "../yt/parse";
import { get, textOf } from "../yt/scan";
import {
  captionTrackRows,
  installCaptionMinter,
  onCaptionSeek,
  onCaptionsChanged,
  selectedCaptionVss,
} from "./captions";
import { playerStorageReady } from "./storage";
import { applyViewport, viewportHeight, viewportWidth } from "./viewport";

const ORIGIN = "https://www.youtube.com";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function sizePlayerEl(el: Element | null): void {
  if (!el) return;
  el.clientWidth = el.offsetWidth = viewportWidth();
  el.clientHeight = el.offsetHeight = viewportHeight();
}

function sizeNodeList(list: HTMLCollection | null): void {
  if (!list) return;
  for (let i = 0; i < list.length; i++) sizePlayerEl(list[i] ?? null);
}

function pad2(n: number): string {
  return (n < 10 ? "0" : "") + n;
}

/** `02:03` or `1:02:03`. */
function fmtProgressClock(ms: number): string {
  const t = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  if (h > 0) return h + ":" + pad2(m) + ":" + pad2(s);
  return pad2(m) + ":" + pad2(s);
}

function sizePlayerTree(): void {
  sizePlayerEl(document.getElementById("player"));
  sizePlayerEl(moviePlayer() as unknown as Element | null);
  sizePlayerEl(document.getElementById("video-player"));
  sizeNodeList(document.getElementsByTagName("video"));
  sizeNodeList(document.getElementsByClassName("html5-video-container"));
  sizeNodeList(document.getElementsByClassName("html5-video-player"));
}

/** IFrame API copied onto #movie_player by Application.create.
 *  Quoted names — Closure ADVANCED must not rename these; YouTube's object is uncompiled. */
type MoviePlayer = {
  "addEventListener"(name: string, fn: () => void): void;
  "setPlaybackQuality"(q: string): void;
  "setPlaybackQualityRange"?(min: string, max?: string): void;
  "getPlaybackQuality"(): string;
  "getUserPlaybackQualityPreference"(): string;
  "getAvailableQualityData"(): unknown;
  "getAvailableAudioTracks"?(): unknown;
  "getAudioTrack"?(): unknown;
  "setAudioTrack"?(track: unknown): unknown;
  "loadVideoById"(id: string | Record<string, unknown>): unknown;
  "getVideoData"(): Record<string, unknown>;
  "preloadVideoByPlayerVars"(
    vars: Record<string, unknown>,
    unused?: unknown,
    expireSec?: number,
  ): unknown;
  "playVideo"(): void;
  "pauseVideo"(): void;
  "stopVideo"(): void;
  "seekTo"(t: number, allowSeekAhead?: boolean): void;
  "setLoopVideo"(enable: boolean): void;
  "setPlayerSpeed"?(speed: number): void;
  "setPlaybackRate"(speed: number): void;
  "getPlayerResponse"(): unknown;
  "getProgressState"(): Record<string, unknown>;
  "getPlayerState"(): number;
};

let moviePlayerEl: MoviePlayer | null = null;

function moviePlayer(): MoviePlayer | null {
  if (!moviePlayerEl) {
    const el = document.getElementById("movie_player");
    if (el) moviePlayerEl = el as unknown as MoviePlayer;
  }
  return moviePlayerEl;
}

let pictureW = 0;
let pictureH = 0;

/** First adaptiveFormats width/height. */
function applyPictureFromResponse(): boolean {
  const player = moviePlayer();
  if (!player) return false;
  let pr: unknown;
  try {
    pr = player["getPlayerResponse"]();
  } catch (_e) {
    return false;
  }
  const rec = asRec(pr);
  const sd = rec ? asRec(rec["streamingData"]) : null;
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return false;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt) continue;
    const w = Number(fmt["width"]);
    const h = Number(fmt["height"]);
    if (isFinite(w) && w > 0 && isFinite(h) && h > 0) {
      const iw = Math.round(w);
      const ih = Math.round(h);
      if (pictureW === iw && pictureH === ih) return false;
      pictureW = iw;
      pictureH = ih;
      console.info("mse picture " + iw + "x" + ih);
      return true;
    }
  }
  return false;
}

function bindPictureSize(player: MoviePlayer): void {
  player["addEventListener"]("onVideoDataChange", function () {
    if (applyPictureFromResponse()) notifyListen("picture");
    notifyListen("details");
    notifyListen("progress");
    notifyListen("state");
  });
}

function bindPlaybackQualityChange(player: MoviePlayer): void {
  player["addEventListener"]("onPlaybackQualityChange", function () {
    notifyCatalog();
  });
}

function bindAudioTrackChange(player: MoviePlayer): void {
  player["addEventListener"]("onPlaybackAudioChange", notifyCatalog);
  player["addEventListener"]("onVideoDataChange", notifyCatalog);
}

function bindStateChange(player: MoviePlayer): void {
  player["addEventListener"]("onStateChange", function () {
    notifyListen("state");
  });
}

function bindVideoProgress(player: MoviePlayer): void {
  player["addEventListener"]("onVideoProgress", function () {
    notifyListen("progress");
  });
}

/** Listeners on #movie_player. Application.create copies the IFrame API
 *  onto that node (getPlaybackQuality, setPlaybackQualityRange, …). */
let boundHtmlMedia: ReturnType<typeof activeHtmlMedia> = null;
let lastYtPlayerState = -999;
let lastPlayabilityLog = "";

function onHtmlTimeupdate(): void {
  notifyListen("progress");
}
function onHtmlSeeked(): void {
  notifyListen("progress");
}
function onHtmlDurationchange(): void {
  notifyListen("progress");
}
function onHtmlPlay(): void {
  notifyListen("state");
}
function onHtmlPause(): void {
  notifyListen("state");
}
function onHtmlEnded(): void {
  notifyListen("state");
}

function unbindHtmlMediaEvents(): void {
  const v = boundHtmlMedia;
  if (!v) return;
  v["removeEventListener"]("timeupdate", onHtmlTimeupdate);
  v["removeEventListener"]("seeked", onHtmlSeeked);
  v["removeEventListener"]("durationchange", onHtmlDurationchange);
  v["removeEventListener"]("play", onHtmlPlay);
  v["removeEventListener"]("pause", onHtmlPause);
  v["removeEventListener"]("ended", onHtmlEnded);
  boundHtmlMedia = null;
}

function bindHtmlMediaEvents(): void {
  const v = activeHtmlMedia();
  if (boundHtmlMedia === v) return;
  unbindHtmlMediaEvents();
  if (!v) return;
  boundHtmlMedia = v;
  v["addEventListener"]("timeupdate", onHtmlTimeupdate);
  v["addEventListener"]("seeked", onHtmlSeeked);
  v["addEventListener"]("durationchange", onHtmlDurationchange);
  v["addEventListener"]("play", onHtmlPlay);
  v["addEventListener"]("pause", onHtmlPause);
  v["addEventListener"]("ended", onHtmlEnded);
}

let iframeListenBound = false;

function callQualityRange(host: unknown, min: string, max: string): boolean {
  const rec = asRec(host);
  if (!rec) return false;
  const range = rec["setPlaybackQualityRange"];
  if (typeof range === "function") {
    (range as (a: string, b: string) => void).call(host, min, max);
    return true;
  }
  const st = asRec(rec["state"]);
  const z = st ? asRec(st["Z"]) : null;
  const zfn = z ? z["setPlaybackQualityRange"] : undefined;
  if (typeof zfn === "function") {
    (zfn as (a: string, b: string) => void)(min, max);
    return true;
  }
  const gi = rec["getInternalApi"];
  if (typeof gi === "function") {
    const api = asRec(gi.call(host));
    const fn = api ? api["setPlaybackQualityRange"] : undefined;
    if (typeof fn === "function") {
      (fn as (a: string, b: string) => void)(min, max);
      return true;
    }
  }
  return false;
}

/** IFrame setPlaybackQuality is ez (no-op). Range lives on the API wrapper
 *  (oV.Sp / #movie_player), including state.Z and getInternalApi(). */
function pinPlaybackQuality(
  player: MoviePlayer,
  app: YtApp | null,
  min: string,
  max: string,
): void {
  if (callQualityRange(player, min, max)) return;
  if (app && callQualityRange(app, min, max)) return;
  if (app) {
    const rec = app as unknown as Record<string, unknown>;
    const keys = Object.keys(rec);
    for (let i = 0; i < keys.length; i++) {
      if (callQualityRange(rec[keys[i]!], min, max)) return;
    }
  }
  throw new JsEngineErrorImpl("setQuality: no setPlaybackQualityRange", "E20085");
}

function bindMoviePlayerApi(): void {
  const player = moviePlayer();
  if (!player || iframeListenBound) return;
  iframeListenBound = true;
  bindPictureSize(player);
  bindPlaybackQualityChange(player);
  bindAudioTrackChange(player);
  bindStateChange(player);
  bindVideoProgress(player);
  bindHtmlMediaEvents();
}

function absUrl(src: string): string {
  src = src.replace(/\/player_\w+\.vflset/g, "/player_es6.vflset");
  if (src.indexOf("://") >= 0) return src;
  if (src.indexOf("//") === 0) return "https:" + src;
  if (src.charAt(0) === "/") return ORIGIN + src;
  return ORIGIN + "/" + src;
}

function u8ToB64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function asU8(v: unknown): Uint8Array {
  if (v instanceof Uint8Array) return v;
  if (typeof v === "string") return new TextEncoder().encode(v);
  if (v && typeof v === "object" && (v as ArrayBufferView).buffer)
    return new Uint8Array(
      (v as ArrayBufferView).buffer,
      (v as ArrayBufferView).byteOffset,
      (v as ArrayBufferView).byteLength,
    );
  return new Uint8Array(0);
}

function setLocation(href: string, pathname: string, search: string): void {
  location.href = href;
  location.protocol = "https:";
  location.host = "www.youtube.com";
  location.hostname = "www.youtube.com";
  location.port = "";
  location.pathname = pathname;
  location.search = search;
  location.hash = "";
  location.origin = ORIGIN;
  location.toString = asNative("toString", function (this: Location) {
    return String(this.href || href);
  });
  location.valueOf = asNative("valueOf", function (this: Location) {
    return String(this.href || href);
  });
  origin = ORIGIN;
  document.URL = href;
  document.documentURI = href;
  document.domain = "www.youtube.com";
  document.location = location;
}

function asNative<T extends Function>(name: string, impl: T): T {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl) as unknown as T;
  return impl;
}

/** e2e C URL is a constructor with createObjectURL. Engine URL is `{createObjectURL}` only. */
function installNativeUrl(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  function urlCtor(this: Record<string, unknown>, url: unknown, base?: unknown) {
    const o: Record<string, unknown> = this && this !== globalThis ? this : {};
    let href = String(url == null ? "" : url);
    let b = "";
    if (base != null) {
      b = typeof base === "string" ? base : String((base as Record<string, unknown>)["href"] || "");
    }
    if (href.indexOf("://") < 0) {
      if (href.indexOf("//") === 0) href = "https:" + href;
      else if (href.charAt(0) === "/") href = "https://www.youtube.com" + href;
      else href = (b || "https://www.youtube.com/") + href;
    }
    let protocol = "https:";
    let hostname = "www.youtube.com";
    let pathname = "/";
    let search = "";
    let hash = "";
    const proto = href.indexOf("://");
    if (proto >= 0) {
      protocol = href.slice(0, proto + 1);
      const rest = href.slice(proto + 3);
      const slash = rest.indexOf("/");
      const q = rest.indexOf("?");
      const h = rest.indexOf("#");
      let hostEnd = rest.length;
      if (slash >= 0 && slash < hostEnd) hostEnd = slash;
      if (q >= 0 && q < hostEnd) hostEnd = q;
      if (h >= 0 && h < hostEnd) hostEnd = h;
      hostname = rest.slice(0, hostEnd);
      pathname = slash >= 0 ? rest.slice(slash) : "/";
    } else {
      pathname = href.charAt(0) === "/" ? href : "/" + href;
    }
    const qi = pathname.indexOf("?");
    const hi = pathname.indexOf("#");
    if (hi >= 0) {
      hash = pathname.slice(hi);
      pathname = pathname.slice(0, hi);
    }
    if (qi >= 0) {
      search = pathname.slice(qi);
      pathname = pathname.slice(0, qi);
    }
    o["href"] = href;
    o["protocol"] = protocol;
    o["host"] = hostname;
    o["hostname"] = hostname;
    o["port"] = "";
    o["pathname"] = pathname || "/";
    o["search"] = search;
    o["hash"] = hash;
    o["origin"] = protocol + "//" + hostname;
    o["username"] = "";
    o["password"] = "";
    o["toString"] = function () {
      return String(o["href"]);
    };
    o["toJSON"] = function () {
      return String(o["href"]);
    };
    return o;
  }
  const ctor = asNative("URL", urlCtor) as unknown as Function & Record<string, unknown>;
  ctor.prototype = urlCtor.prototype || {};
  ctor["createObjectURL"] = asNative("createObjectURL", function (obj: unknown) {
    return mseCreateObjectURL(obj as { __id?: number });
  });
  ctor["revokeObjectURL"] = asNative("revokeObjectURL", function (url: unknown) {
    mseRevokeObjectURL(String(url == null ? "" : url));
  });
  g["URL"] = ctor;
}

function installSabrObserver(): void {
  function IntersectionObserver(this: Record<string, unknown>, cb: unknown) {
    this["_cb"] = cb;
  }
  IntersectionObserver.prototype.observe = asNative(
    "observe",
    function (this: Record<string, unknown>, el: unknown) {
      const self = this;
      const rec = el && typeof el === "object" ? (el as Record<string, unknown>) : null;
      const getRect =
        rec && typeof rec["getBoundingClientRect"] === "function"
          ? (rec["getBoundingClientRect"] as () => Record<string, number>)
          : null;
      const vw = viewportWidth();
      const vh = viewportHeight();
      const rect = getRect
        ? getRect.call(rec)
        : { x: 0, y: 0, width: vw, height: vh, top: 0, left: 0, bottom: vh, right: vw };
      const w = Number(rect["width"]) || vw;
      const h = Number(rect["height"]) || vh;
      Promise.resolve().then(function () {
        const cb = self["_cb"];
        if (typeof cb !== "function") return;
        try {
          (cb as Function)(
            [
              {
                target: el,
                isIntersecting: true,
                isVisible: true,
                intersectionRatio: 1,
                time: 0,
                boundingClientRect: rect,
                intersectionRect: {
                  x: 0,
                  y: 0,
                  width: w,
                  height: h,
                  top: 0,
                  left: 0,
                  bottom: h,
                  right: w,
                },
                rootBounds: {
                  x: 0,
                  y: 0,
                  width: 1280,
                  height: 720,
                  top: 0,
                  left: 0,
                  bottom: 720,
                  right: 1280,
                },
              },
            ],
            self,
          );
        } catch (_e) {}
      });
    },
  );
  IntersectionObserver.prototype.unobserve = asNative("unobserve", function () {});
  IntersectionObserver.prototype.disconnect = asNative("disconnect", function () {});
  (globalThis as unknown as Record<string, unknown>)["IntersectionObserver"] = IntersectionObserver;
}

function installPlayerTree(): void {
  if (document.getElementById("player")) return;
  const player = document.createElement("div");
  player.id = "player";
  sizePlayerEl(player);
  document.body.appendChild(player);
}

function fireWindowResize(): void {
  try {
    const fn = (globalThis as unknown as Record<string, unknown>)["dispatchEvent"];
    if (typeof fn === "function") (fn as (ev: unknown) => unknown)({ type: "resize" });
  } catch (_e) {}
}

export function setPlayerViewport(width: unknown, height: unknown): Record<string, unknown> {
  applyViewport(width, height);
  sizePlayerTree();
  fireWindowResize();
  return { "ok": true, "width": viewportWidth(), "height": viewportHeight() };
}

function owlSlot(): string {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  const flags =
    cfg && typeof cfg["get"] === "function"
      ? asRec((cfg["get"] as (k: string) => unknown)("EXPERIMENT_FLAGS"))
      : null;
  if (flags && flags["bg_st_hr"]) return "havuokmhhs-0";
  const perf = asRec(g["performance"]);
  const originMs =
    perf && typeof perf["timeOrigin"] === "number" ? (perf["timeOrigin"] as number) : 0;
  return "havuokmhhs-" + Math.floor(originMs);
}

function enableSharedOwlFlag(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  if (!cfg || typeof cfg["get"] !== "function" || typeof cfg["set"] !== "function") return;
  const get = cfg["get"] as (k: string) => unknown;
  const set = cfg["set"] as (d: unknown) => void;
  const flags = asRec(get("EXPERIMENT_FLAGS")) || {};
  flags["html5_use_shared_owl_instance"] = true;
  flags["html5_webpo_idle_priority_job"] = false;
  /* Bl(): server picks SABR itags; field 16/17 stay empty. Client filter instead. */
  flags["html5_enable_server_format_filter"] = false;
  flags["html5_enable_server_format_filter_for_drm"] = false;
  // Player fetch gzips /youtubei/v1/player when this is on; jsonObject() then
  // sees gzip magic (0x1f) instead of `{`.
  flags["wug_networking_gzip_request"] = false;
  set({ "EXPERIMENT_FLAGS": flags });
}

function installSharedOwl(mintFn: (id: Uint8Array) => unknown): void {
  const g = globalThis as unknown as Record<string, unknown>;
  enableSharedOwlFlag();
  const nop = function () {};
  const wpcHandle: Record<string, unknown> = {
    "f": function () {
      return Promise.resolve();
    },
    "c": function (n: number) {
      return !(n > 150);
    },
    "m": function (req: unknown) {
      const rec = asRec(req);
      const c = rec ? rec["c"] : undefined;
      const b =
        c instanceof Uint8Array
          ? c
          : typeof c === "string"
            ? new TextEncoder().encode(c)
            : new Uint8Array(0);
      return mintFn(b);
    },
    "mws": function (req: unknown) {
      const raw = asU8((wpcHandle["m"] as (r: unknown) => unknown)(req));
      return u8ToB64url(raw);
    },
  };
  const bevasrs: Record<string, unknown> = {
    "s": nop,
    "e": nop,
    "c": function () {
      return Promise.resolve();
    },
    "p": Promise.resolve(),
    "l": function () {
      return {};
    },
    "wpc": function () {
      return Promise.resolve(wpcHandle);
    },
  };
  const slot = owlSlot();
  let box = asRec(g[slot]);
  if (!box) box = {};
  box["bevasrs"] = bevasrs;
  g[slot] = box;
}

type YtApp = {
  "loadVideoById"?: (id: string, start?: number) => unknown;
  "loadVideoByPlayerVars": (vars: Record<string, unknown>) => unknown;
  "preloadVideoById"?: (id: string) => unknown;
  "preloadVideoByPlayerVars"?: (
    vars: Record<string, unknown>,
    unused?: unknown,
    expireSec?: number,
  ) => unknown;
  "playVideo": () => void;
  "pauseVideo": () => void;
  "seekTo": (t: number, allowSeekAhead?: boolean) => void;
  "setPlaybackQualityRange"?: (q: string, q2?: string) => void;
  "setPlaybackQuality"?: (q: string) => void;
  "getPlaybackQuality"?: () => string;
  "getUserPlaybackQualityPreference"?: () => string;
  "getAvailableQualityData"?: () => unknown;
  "getAvailableQualityLevels"?: () => unknown;
  "getPlayerResponse"?: () => unknown;
};

function parseQueryMap(s: string): Record<string, string> {
  const out: Record<string, string> = {};
  if (!s) return out;
  const parts = s.split("&");
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    if (!p) continue;
    const eq = p.indexOf("=");
    if (eq < 0) out[p] = "";
    else out[p.slice(0, eq)] = p.slice(eq + 1);
  }
  return out;
}

function joinQueryMap(map: Record<string, string>): string {
  const keys = Object.keys(map);
  const parts: string[] = [];
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    parts.push(k + "=" + map[k]);
  }
  return parts.join("&");
}

function patchFlags(s: string, patches: Record<string, string>): string {
  const map = parseQueryMap(s);
  const keys = Object.keys(patches);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    map[k] = patches[k]!;
  }
  return joinQueryMap(map);
}

let freshPlayerConfig = false;

/** A user reload: this session's config comes from a new homepage scrape, not the held session. */
export function requireFreshPlayerConfig(fresh: boolean): void {
  freshPlayerConfig = fresh;
}

/** The base.js response head for diagnostics. Content-Length/-Encoding are as the host relayed
 *  them: a transport that transparently gunzips (OkHttp) drops both. */
function baseJsHead(res: HttpResponse): string {
  return (
    "status=" +
    res.status +
    " contentLength=" +
    (res.headers.get("content-length") ?? "-") +
    " encoding=" +
    (res.headers.get("content-encoding") ?? "-") +
    " cache=" +
    (res.cache ?? "network")
  );
}

async function fetchPlayerConfig(): Promise<Record<string, unknown>> {
  return callMain("get_player_config", async (pipe) => {
    const w = pipe.sink.writeObjectAsJson(freshPlayerConfig ? { "maxAge": 0 } : {});
    if (w) await w;
    const c = pipe.sink.close();
    if (c) await c;
    return await pipe.source.readJsonObject();
  });
}

/** The whole player/init (or a PO-token mint's BotGuard) gets this long; the host's own 30 s cap
 *  is only a backstop for an engine that stopped answering. */
const INIT_DEADLINE_MS = 20000;
/** A loop-on seek this close to the end restarts the loop; the buffer ends a few ms short of the duration. */
const LOOP_TAIL_S = 0.5;

/** An init step in flight: what an expired deadline names and abandons. */
interface InitStep {
  name: string;
  code: string;
  abort: (e: Error) => void;
}

/** In start order; shared by player/init and the PO-token mint, which run the same steps. */
const runningSteps: InitStep[] = [];

/** [task] as a named step: settles with it, or rejects when a deadline expires while it runs. */
function initStep<T>(task: Promise<T>, name: string, code: string): Promise<T> {
  return new Promise<T>(function (resolve, reject) {
    const step: InitStep = {
      name: name,
      code: code,
      abort: function (e: Error) {
        finish();
        reject(e);
      },
    };
    function finish(): void {
      const i = runningSteps.indexOf(step);
      if (i >= 0) runningSteps.splice(i, 1);
    }
    runningSteps.push(step);
    task.then(
      function (value) {
        finish();
        resolve(value);
      },
      function (e: unknown) {
        finish();
        reject(e);
      },
    );
  });
}

/** Rejects when [task] has not settled within [INIT_DEADLINE_MS], with the code of the earliest
 *  step still running; those steps are abandoned with the same error, so the config / minter
 *  they belong to starts fresh on the next attempt instead of awaiting a hung one. */
function withinInitDeadline<T>(task: Promise<T>, what: string): Promise<T> {
  return new Promise<T>(function (resolve, reject) {
    const timer = setTimeout(function () {
      const steps = runningSteps.slice();
      const first = steps[0];
      const names = steps
        .map(function (s) {
          return s.name;
        })
        .join(", ");
      const message =
        what + " not done within " + INIT_DEADLINE_MS + "ms; still running: " + (names || "none");
      console.warn(message);
      const err = new JsEngineErrorImpl(message, first ? first.code : "E20159");
      for (const s of steps) s.abort(err);
      reject(err);
    }, INIT_DEADLINE_MS);
    task.then(
      function (value) {
        clearTimeout(timer);
        resolve(value);
      },
      function (e: unknown) {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

interface BaseJsDownload {
  text: string;
  head: string;
}

async function downloadBaseJs(jsUrl: string): Promise<BaseJsDownload> {
  let head = "status=none";
  try {
    const res = await executeWithCookies(
      jsUrl,
      HttpRequest.get(jsUrl)
        .setHeader("Referer", ORIGIN + "/")
        .setHeader("Origin", ORIGIN),
    );
    head = baseJsHead(res);
    if (!res.ok) {
      res.close();
      throw new Error("GET " + jsUrl + " status=" + res.status);
    }
    return { text: await res.text(), head: head };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    console.warn("base.js fetch failed " + head + " err=" + msg + " jsUrl=" + jsUrl);
    throw new JsEngineErrorImpl("base.js fetch: " + msg + " (" + head + ")", "E20075", {
      cause: e instanceof Error ? e : undefined,
    });
  }
}

/** Everything `player/load` applies before the first frame, so the host needs one round-trip. */
export interface PlayerLoadOptions {
  quality: string | undefined;
  audioOnly: boolean;
  startSeconds: number;
  playWhenReady: boolean;
  speed: number;
  pitch: number;
  loop: boolean;
}

class Player {
  private player: YtApp | null = null;
  private speed = 1;
  private tempo = 1;
  private pitch = 1;
  private mintFn: ((id: Uint8Array) => unknown) | null = null;
  private configP: Promise<Record<string, unknown>> | null = null;
  private minterP: Promise<Record<string, unknown>> | null = null;
  private initP: Promise<Record<string, unknown>> | null = null;
  private createFn: ((r: unknown, a: unknown, b: unknown) => unknown) | null = null;
  private createCtx: Record<string, unknown> = {};

  init(): Promise<Record<string, unknown>> {
    if (this.initP == null) {
      this.initP = withinInitDeadline(this.doInit(), "player/init").then(
        (v) => v,
        (e: unknown) => {
          this.initP = null;
          throw e;
        },
      );
    }
    return this.initP;
  }

  private ensureConfig(): Promise<Record<string, unknown>> {
    if (this.configP == null) {
      this.configP = this.doConfig().then(
        (v) => v,
        (e: unknown) => {
          this.configP = null;
          throw e;
        },
      );
    }
    return this.configP;
  }

  private ensureMinter(): Promise<Record<string, unknown>> {
    if (this.minterP == null) {
      this.minterP = this.doMinter().then(
        (v) => v,
        (e: unknown) => {
          this.minterP = null;
          throw e;
        },
      );
    }
    return this.minterP;
  }

  private async doConfig(): Promise<Record<string, unknown>> {
    await initStep(playerStorageReady(), "player storage", "E20158");
    installOrigEnv();
    installNativeUrl();
    setLocation(ORIGIN + "/", "/", "");
    const cfg = await initStep(fetchPlayerConfig(), "session scrape", "E20155");
    applyNavigatorLanguages(cfg["acceptLanguage"]);
    return cfg;
  }

  private async doMinter(): Promise<Record<string, unknown>> {
    const cfg = await this.ensureConfig();
    const out: Record<string, unknown> = { "step": "minter" };
    this.mintFn = await initStep(minterFromConfig(cfg, out), "botguard", "E20156");
    endLayoutProbes();
    const self = this;
    installCaptionMinter(function (id: string) {
      return self.mintToken(id);
    });
    installMseGlobals();
    installMediaCapabilities();
    installPlaybackQuality();
    installSabrObserver();
    installXhr();
    installFetch();
    installSharedOwl(this.mintFn);
    return out;
  }

  /** Session scrape, then BotGuard with base.js downloading alongside; base.js is evaluated only
   *  after the BotGuard snapshot. */
  private async doInit(): Promise<Record<string, unknown>> {
    const cfg = await this.ensureConfig();
    const jsUrlRaw = typeof cfg["jsUrl"] === "string" ? (cfg["jsUrl"] as string) : "";
    if (!jsUrlRaw) throw new JsEngineErrorImpl("player config has no jsUrl", "E20151");
    const jsUrl = absUrl(jsUrlRaw);
    cfg["jsUrl"] = jsUrl;
    const download = initStep(downloadBaseJs(jsUrl), "base.js download", "E20157");
    download.catch(function () {});
    const out = await this.ensureMinter();

    installPlayerTree();
    installPlayerAdStrip();
    installSabrIntercept();
    installAdaptiveFormatsHeader();
    installWatchNextHarvest();

    out["jsUrl"] = jsUrl;
    out["step"] = "basejs";
    const downloaded = await download;
    const baseJs = downloaded.text;
    const head = downloaded.head;
    out["baseJsSrc"] = "cdn";
    out["baseJsLen"] = baseJs.length;
    console.info("base.js source=cdn " + head + " len=" + baseJs.length + " jsUrl=" + jsUrl);
    try {
      (0, eval)(baseJs);
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e));
      const body =
        head +
        " len=" +
        baseJs.length +
        " bytes=" +
        utf8Encode(baseJs).length +
        " tail=" +
        JSON.stringify(baseJs.slice(-80));
      console.warn("base.js eval failed " + body + " err=" + err.message + " jsUrl=" + jsUrl);
      throw new JsEngineErrorImpl(
        "base.js eval: " +
          err.message +
          " (" +
          body +
          ")" +
          (typeof err.stack === "string" ? "\n" + err.stack : ""),
        "E20152",
        { cause: err },
      );
    }

    const g = globalThis as unknown as Record<string, unknown>;
    const ytcfg = asRec(g["ytcfg"]);
    const yt = asRec(g["yt"]);
    const playerNs = yt ? asRec(yt["player"]) : null;
    const Application = playerNs ? asRec(playerNs["Application"]) : null;
    const create = Application ? Application["create"] : undefined;
    if (typeof create !== "function")
      throw new JsEngineErrorImpl("yt.player.Application.create missing", "E20076");

    const get = ytcfg ? (ytcfg["get"] as (k: string) => unknown) : null;
    const ctxs = get ? asRec(get("WEB_PLAYER_CONTEXT_CONFIGS")) : null;
    const ctx = ctxs ? asRec(ctxs["WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH"]) : null;
    if (!ctx)
      throw new JsEngineErrorImpl("missing WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH", "E20153");
    enableSharedOwlFlag();
    if (typeof ctx["serializedExperimentFlags"] === "string") {
      ctx["serializedExperimentFlags"] = patchFlags(ctx["serializedExperimentFlags"] as string, {
        "html5_use_shared_owl_instance": "true",
        "html5_webpo_idle_priority_job": "false",
        "wug_networking_gzip_request": "false",
        "html5_enable_server_format_filter": "false",
        "html5_enable_server_format_filter_for_drm": "false",
      });
    }
    this.createFn = create as (r: unknown, a: unknown, b: unknown) => unknown;
    this.createCtx = ctx;
    out["step"] = "ok";
    out["ok"] = true;
    return out;
  }

  private ensureCreated(out: Record<string, unknown>): YtApp {
    if (this.player) return this.player;
    onLoopAtEnd(function () {
      const player = moviePlayer();
      if (player) player["seekTo"](0, true);
    });
    const create = this.createFn;
    if (typeof create !== "function")
      throw new JsEngineErrorImpl("yt.player.Application.create missing", "E20076");
    /* No video_id here: create would POST /player + SABR, then load
       would do it again. Watch starts only in loadVideoByPlayerVars. */
    const playerVars: Record<string, unknown> = {
      "skip_ads": 1,
      /* Without this, getAvailablePlaybackRates() is [1] and setPlaybackRate snaps to 1×. */
      "enableSpeedOptions": true,
    };
    const param2 = {
      "args": playerVars,
      "attrs": {
        "id": "video-player",
        "width": String(viewportWidth()),
        "height": String(viewportHeight()),
      },
      "loaded": true,
    };
    /* Arg 1 is the parent. Application.create appends a new div whose id is
       ctx.rootElementId ("movie_player") and copies the IFrame API onto it. */
    const root = document.getElementById("player");
    if (!root) throw new JsEngineErrorImpl("missing #player", "E20076");
    sizePlayerEl(root);
    out["step"] = "create";
    try {
      this.player = create(root, param2, this.createCtx) as YtApp;
      (globalThis as unknown as Record<string, unknown>)["__ymPlayer"] = this.player;
      sizePlayerTree();
      fireWindowResize();
      bindMoviePlayerApi();
    } catch (e) {
      throw new JsEngineErrorImpl(
        "Application.create: " + (e instanceof Error ? e.message : String(e)),
        "E20076",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    return this.player;
  }

  private async mintToken(videoId: string): Promise<string> {
    const fn = this.mintFn;
    if (!fn) throw new JsEngineErrorImpl("player not initialized", "E20074");
    let minted: unknown = fn(new TextEncoder().encode(videoId));
    if (minted && typeof (minted as Promise<unknown>).then === "function")
      minted = await (minted as Promise<unknown>);
    if (!minted) throw new JsEngineErrorImpl("YNJ:Undefined", "E20074");
    return typeof minted === "string" ? minted : u8ToB64url(asU8(minted));
  }

  /** Quality token + audio-only flag, applied together. Audio-only pins Auto and rests C. */
  setQuality(quality: string, audioOnly: boolean): void {
    /* tiny (144p) is reserved for the fake-144p SABR inject; it is not a playable rung. */
    let q = String(quality || "") || ymQuality() || "auto";
    if (q === "tiny") q = "auto";
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("setQuality: no movie_player", "E20085");
    setYmQuality(q);
    setAudioOnly(!!audioOnly);
    if (audioOnly) {
      /* Live: real tiny + rest decoder. VOD: auto-auto, fake 144p via SABR. */
      if (isYmLive()) pinPlaybackQuality(player, this.player, "auto", "tiny");
      else pinPlaybackQuality(player, this.player, "auto", "auto");
      __mseRestVideo(1);
    } else {
      __mseRestVideo(0);
      if (!isYmLive()) dropVideoFakeOverlay();
      bindMoviePlayerApi();
      pinPlaybackQuality(player, this.player, q, q);
    }
    resetDrainCounts();
    bindHtmlMediaEvents();
    console.info(
      "mse setQuality q=" +
        q +
        " audioOnly=" +
        (audioOnly ? "1" : "0") +
        " clock=" +
        (activeHtmlMedia() ? __mseCurrentTime() : 0),
    );
    dumpDrain("setQuality-" + q + (audioOnly ? "-ao" : ""));
    notifyCatalog();
  }

  private currentVideoId(): string {
    const player = moviePlayer();
    if (!player) return "";
    try {
      const d = player["getVideoData"]();
      const id = d["video_id"];
      return typeof id === "string" ? id : "";
    } catch (_e) {
      return "";
    }
  }

  private loadById(videoId: string, startSeconds: number): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player/load: no movie_player", "E20077");
    if (startSeconds > 0) {
      player["loadVideoById"]({ "videoId": videoId, "startSeconds": startSeconds });
    } else {
      player["loadVideoById"](videoId);
    }
  }

  async load(videoId: string, options: PlayerLoadOptions): Promise<Record<string, unknown>> {
    const initOut = await this.init();
    const ao = options.audioOnly;
    const q = options.quality && options.quality.length ? options.quality : undefined;
    const out: Record<string, unknown> = {
      "videoId": videoId,
      "quality": q || "auto",
      "audioOnly": ao,
      "jsUrl": initOut["jsUrl"],
      "baseJsLen": initOut["baseJsLen"],
      "ytGets": initOut["ytGets"],
      "step": "loadVideoById",
    };
    this.ensureCreated(out);
    /* Chrome /player uses watch URL as originalUrl / currentUrl / graftUrl. */
    setLocation(ORIGIN + "/watch?v=" + videoId, "/watch", "?v=" + videoId);
    this.setQuality(q || "auto", ao);
    this.setSpeedPitch(options.speed, options.pitch);
    try {
      this.loadById(videoId, options.startSeconds > 0 ? options.startSeconds : 0);
      // After the load: base.js clears loop for every new video.
      this.setLoop(options.loop);
      /* Same JS turn as loadVideoById: no frame plays before the pause lands. */
      if (!options.playWhenReady) this.pause();
    } catch (e) {
      throw new JsEngineErrorImpl(
        "loadVideoById: " + (e instanceof Error ? e.message : String(e)),
        "E20077",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    bindHtmlMediaEvents();
    this.reapplySpeedPitch();
    /* Fire-and-forget: /player, MSE, picture, catalog arrive on
       onVideoDataChange / status / catalog. load() does not wait. */
    out["step"] = "ok";
    out["ok"] = true;
    return out;
  }

  async preload(videoId: string): Promise<Record<string, unknown>> {
    const initOut = await this.init();
    const out: Record<string, unknown> = {
      "videoId": videoId,
      "jsUrl": initOut["jsUrl"],
      "step": "preload",
    };
    this.ensureCreated(out);
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player/preload: no movie_player", "E20092");
    try {
      player["preloadVideoByPlayerVars"]({ "video_id": videoId }, undefined, 60);
    } catch (e) {
      if (e instanceof JsEngineErrorImpl) throw e;
      throw new JsEngineErrorImpl(
        "preload: " + (e instanceof Error ? e.message : String(e)),
        "E20092",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    console.info("mse preload " + videoId);
    out["ok"] = true;
    return out;
  }

  async mintPo(videoId: string): Promise<Record<string, unknown>> {
    const minterOut = await withinInitDeadline(this.ensureMinter(), "po token minter");
    const token = await this.mintToken(videoId);
    return {
      "videoId": videoId,
      "contentPoToken": token,
      "contentPoTokenLen": token.length,
      "ytGets": minterOut["ytGets"],
      "eventIdGets": minterOut["ytEventIdGets"],
      "eventId": minterOut["eventId"],
      "programSource": minterOut["programSource"],
      "step": "ok",
      "ok": true,
    };
  }

  play(): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player not created", "E20082");
    player["playVideo"]();
  }

  setLoop(enable: boolean): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player not created", "E20082");
    player["setLoopVideo"](!!enable);
  }

  setSpeed(speed: number, _tempo?: number): void {
    let s = Number(speed);
    if (!Number.isFinite(s)) s = 1;
    if (s < 0.25) s = 0.25;
    if (s > 3) s = 3;
    this.tempo = s;
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player not created", "E20082");
    if (this.speed !== s) {
      this.speed = s;
      player["setPlaybackRate"](s);
    }
    /* IFrame API ZlP-snaps to getAvailablePlaybackRates(); always write the element. */
    const el = activeHtmlMedia();
    if (el) el["playbackRate"] = s;
  }

  setSpeedPitch(speed: number, pitch: number): void {
    this.setSpeed(speed);
    this.applyHtmlPitch(pitch);
  }

  /** After loadVideoById YouTube may reset the element; push stored rate/pitch. */
  private reapplySpeedPitch(): void {
    const player = moviePlayer();
    if (player) player["setPlaybackRate"](this.speed);
    const el = activeHtmlMedia();
    if (el) {
      el["playbackRate"] = this.speed;
      el["pitch"] = this.pitch;
    }
  }

  private applyHtmlPitch(pitch: number): void {
    let p = Number(pitch);
    if (!Number.isFinite(p)) p = 1;
    if (p < 0.5) p = 0.5;
    if (p > 2) p = 2;
    if (this.pitch === p) return;
    this.pitch = p;
    const el = activeHtmlMedia();
    if (el) el["pitch"] = p;
  }

  pause(): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player not created", "E20083");
    player["pauseVideo"]();
  }

  seek(t: number): void {
    const player = moviePlayer();
    if (!player) return;
    const el = activeHtmlMedia();
    const ct = el ? __mseCurrentTime() : -1;
    console.info("mse seek t=" + t + " ct=" + ct);
    // base.js pauses a seek onto the tail instead of ending it, so a looping element never wraps.
    if (el && el["loop"] && !isLiveDuration()) {
      const d = Number(el["duration"]);
      if (isFinite(d) && d > 0 && t >= d - LOOP_TAIL_S) t = 0;
    }
    player["seekTo"](t, true);
    onCaptionSeek();
  }

  /** YouTube IFrame getProgressState — DVR allowSeeking / seekableStart / seekableEnd. */
  private progressState(): Record<string, unknown> | null {
    const player = moviePlayer();
    if (!player) return null;
    try {
      const s = player["getProgressState"]();
      return s && typeof s === "object" ? s : null;
    } catch (_e) {
      return null;
    }
  }

  stop(): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("player not created", "E20084");
    player["stopVideo"]();
    bindHtmlMediaEvents();
    // The host drops its video size on stop; a next video of the same size must report it again.
    pictureW = 0;
    pictureH = 0;
    notifyListen("picture");
  }

  /** Clock group: position, buffer, duration, seek window. Same payload as webview `_progress`. */
  progressClock(): Record<string, unknown> {
    const v = activeHtmlMedia();
    const ps = this.progressState();
    const live = isLiveDuration();
    /* YouTube's playhead is getProgressState().current — the IFrame UI and SABR
       playerTimeMs. HTML currentTime resets to 0 on a quality MediaSource swap. */
    let current = NaN;
    let loaded = NaN;
    let dur = NaN;
    let skStart = NaN;
    let skEnd = NaN;
    let allowSeeking: boolean | null = null;
    if (ps) {
      current = Number(ps["current"]);
      loaded = Number(ps["loaded"]);
      dur = Number(ps["duration"]);
      skStart = Number(ps["seekableStart"]);
      skEnd = Number(ps["seekableEnd"]);
      allowSeeking = !!ps["allowSeeking"];
    }
    if (!isFinite(current) && v) current = Number(v["currentTime"]);
    if (!(isFinite(dur) && dur > 0) && v) {
      const d = Number(v["duration"]);
      if (isFinite(d) && d > 0) dur = d;
    }
    if (!(isFinite(loaded) && loaded > 0) && v) {
      const b = v["buffered"] as { length?: number; end?: (i: number) => number } | null;
      const n = b && typeof b["length"] === "number" ? Number(b["length"]) : 0;
      if (n > 0 && typeof b!["end"] === "function") {
        const end = Number(b!["end"]!(n - 1));
        if (isFinite(end) && end > 0) loaded = end;
      }
    }
    if (!(isFinite(skStart) && isFinite(skEnd) && skEnd > skStart) && v) {
      const sk = v["seekable"] as {
        length?: number;
        start?: (i: number) => number;
        end?: (i: number) => number;
      } | null;
      const skN = sk && typeof sk["length"] === "number" ? Number(sk["length"]) : 0;
      if (skN > 0 && typeof sk!["start"] === "function" && typeof sk!["end"] === "function") {
        skStart = Number(sk!["start"]!(0));
        skEnd = Number(sk!["end"]!(skN - 1));
      }
    }
    /* Live duration is often 0 / Infinity; the bar is the DVR window ending at seekableEnd. */
    if (!(isFinite(dur) && dur > 0) && isFinite(skEnd) && skEnd > 0) dur = skEnd;
    const seekable = allowSeeking != null ? allowSeeking : !live;
    if (!live && seekable && !(isFinite(skEnd) && skEnd > (isFinite(skStart) ? skStart : 0) + 1)) {
      if (isFinite(current) && current > 0) {
        skStart = 0;
        skEnd = current;
      }
    }
    const posMs = isFinite(current) ? Math.max(0, Math.round(current * 1000)) : 0;
    const durationMs =
      isFinite(dur) && dur > 0
        ? Math.round(dur * 1000)
        : isFinite(skEnd) && skEnd > 0
          ? Math.round(skEnd * 1000)
          : 0;
    const skStartMs = Math.round((isFinite(skStart) ? skStart : 0) * 1000);
    const skEndMs = Math.round((isFinite(skEnd) ? skEnd : 0) * 1000);
    const window = live && skEndMs > skStartMs;
    const barStart = window ? skStartMs : 0;
    const barEnd = window ? skEndMs : durationMs;
    const barSpan = Math.max(0, barEnd - barStart);
    const progress =
      barSpan > 0
        ? Math.min(barSpan, Math.max(0, posMs - barStart))
        : Math.max(0, posMs - barStart);
    const barDur = barSpan > 0 ? barSpan : durationMs;
    const behind = Math.max(0, barDur - progress);
    const atHead = live && behind <= 2000;
    let progressLabel: string | null = null;
    if (live) progressLabel = atHead ? null : "-" + fmtProgressClock(behind);
    else if (barDur > 0 || progress > 0) progressLabel = fmtProgressClock(progress);
    return {
      "videoId": this.currentVideoId(),
      "progress": progress,
      "progressLabel": progressLabel,
      "duration": barDur,
      "durationLabel": live ? "LIVE" : barDur > 0 ? fmtProgressClock(barDur) : null,
      "highlightDuration": atHead,
      "bufferedMs": isFinite(loaded) && loaded > 0 ? Math.round(loaded * 1000) : 0,
      "seekable": seekable,
      "live": live,
      "seekableStartMs": skStartMs,
      "seekableEndMs": skEndMs,
      /* Same values as progress/duration — existing status() readers. */
      "positionMs": progress,
      "durationMs": barDur,
    };
  }

  /**
   * Discrete playback state — webview `_state_changed`.
   * newState: idle | buffering | ready | ended | error
   */
  listenState(): Record<string, unknown> {
    const player = moviePlayer();
    const live = isLiveDuration();
    const out: Record<string, unknown> = {
      "newState": "idle",
      "playWhenReady": false,
      "live": live,
    };
    if (!player) return out;
    const media = activeHtmlMedia();
    if (media && media["ended"]) {
      out["newState"] = "ended";
      out["playWhenReady"] = false;
      if (lastYtPlayerState !== 0) {
        lastYtPlayerState = 0;
        console.info("mse ytState ended ct=" + Number(media["currentTime"]).toFixed(3));
      }
      return out;
    }
    const st = player["getPlayerState"]();
    out["playWhenReady"] = st === 1 || st === 3;
    if (st < 0) {
      const pr = asRec(player["getPlayerResponse"]());
      const playability = pr ? asRec(pr["playabilityStatus"]) : null;
      const status =
        playability && playability["status"] != null ? String(playability["status"]) : "";
      if (status && status !== "OK") {
        const code = status.toUpperCase() || "ERROR";
        out["newState"] = "error";
        out["code"] = code;
        out["message"] =
          playability && playability["reason"] != null ? String(playability["reason"]) : "";
        const userMessage = playabilityUserMessage(playability);
        if (userMessage) out["userMessage"] = userMessage;
        const playabilityLog =
          "mse playability " +
          code +
          " userMessage=" +
          JSON.stringify(userMessage) +
          " status=" +
          JSON.stringify(playability).slice(0, 800);
        if (playabilityLog !== lastPlayabilityLog) {
          lastPlayabilityLog = playabilityLog;
          console.info(playabilityLog);
        }
        out["canRetry"] = true;
        out["needReset"] =
          code.indexOf("LOGIN") >= 0 ||
          code.indexOf("SIGN_IN") >= 0 ||
          code.indexOf("AUTH") >= 0 ||
          code.indexOf("CAPTCHA") >= 0 ||
          code.indexOf("UNPLAYABLE") >= 0;
        return out;
      }
      out["newState"] = "idle";
      return out;
    }
    const mapped: Record<number, string> = {
      0: "ended",
      1: "ready",
      2: "ready",
      3: "buffering",
      5: "idle",
    };
    out["newState"] = mapped[st] || "idle";
    if (st !== lastYtPlayerState) {
      lastYtPlayerState = st;
      console.info(
        "mse ytState " +
          st +
          "=" +
          String(out["newState"]) +
          (media
            ? " ct=" +
              Number(media["currentTime"]).toFixed(3) +
              " rs=" +
              media["readyState"] +
              " paused=" +
              (media["paused"] ? "1" : "0")
            : ""),
      );
    }
    return out;
  }

  picture(): Record<string, unknown> {
    return {
      "videoWidth": pictureW,
      "videoHeight": pictureH,
    };
  }

  /** The presenting video's owner channel, from its player response (absent until it lands). */
  details(): Record<string, unknown> {
    const videoId = this.currentVideoId();
    const out: Record<string, unknown> = { "videoId": videoId };
    const player = moviePlayer();
    if (!player || videoId === "") return out;
    const pr = asRec(player["getPlayerResponse"]());
    const vd = pr ? asRec(pr["videoDetails"]) : null;
    if (!vd || vd["videoId"] !== videoId) return out;
    const author = vd["author"];
    if (typeof author === "string" && author !== "") out["channelTitle"] = author;
    const channelId = vd["channelId"];
    if (typeof channelId === "string" && channelId.indexOf("UC") === 0) {
      out["channelUrl"] = channelUrl(channelId);
    }
    return out;
  }

  progress(): Record<string, unknown> {
    const clock = this.progressClock();
    const st = this.listenState();
    const pic = this.picture();
    const out: Record<string, unknown> = {};
    for (const k in clock) out[k] = clock[k];
    out["live"] = st["live"];
    out["paused"] = !st["playWhenReady"];
    out["ended"] = st["newState"] === "ended";
    out["readyState"] = st["newState"] === "ready" ? 4 : st["newState"] === "buffering" ? 2 : 0;
    out["videoWidth"] = pic["videoWidth"];
    out["videoHeight"] = pic["videoHeight"];
    out["audioOnly"] = isAudioOnly();
    return out;
  }

  catalog(): Record<string, unknown> {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("catalog: no movie_player", "E20085");
    let pref = String(player["getUserPlaybackQualityPreference"]());
    if (pref === "default" || pref === "unknown" || pref === "") pref = "auto";
    /* Auto has not settled on a rung yet — YouTube reports unknown/tiny mid-flight. */
    let actual = String(player["getPlaybackQuality"]());
    if (actual === "unknown" || actual === "tiny") actual = "auto";
    return {
      "quality": actual,
      "preferQuality": pref,
      "audioOnly": isAudioOnly(),
      "qualities": this.qualityOptions(),
      "caption": selectedCaptionVss(),
      "captionTracks": captionTrackRows(),
      "audioTrack": selectedAudioTrackId(player),
      "audioTracks": audioTrackRows(player),
    };
  }

  setAudioTrack(id: string): void {
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("setAudioTrack: no movie_player", "E20164");
    const tracks = availableAudioTracks(player);
    for (let i = 0; i < tracks.length; i++) {
      const track = tracks[i]!;
      if (track["id"] === id && player["setAudioTrack"]) {
        player["setAudioTrack"](track);
        notifyCatalog();
        return;
      }
    }
    throw new JsEngineErrorImpl("setAudioTrack: no audio track " + id, "E20162");
  }

  status(): Record<string, unknown> {
    const p = this.progress();
    const c = this.catalog();
    const out: Record<string, unknown> = {};
    for (const k in p) out[k] = p[k];
    for (const k in c) out[k] = c[k];
    return out;
  }

  private qualityOptions(): Record<string, unknown>[] {
    const labels: Record<string, string> = {
      "tiny": "144p",
      "small": "240p",
      "medium": "360p",
      "large": "480p",
      "hd720": "720p",
      "hd1080": "1080p",
      "hd1440": "1440p",
      "hd2160": "4K",
      "highres": "8K",
    };
    const rows: Record<string, unknown>[] = [{ "quality": "auto", "label": "Auto" }];
    const seen: Record<string, boolean> = { "auto": true };
    function add(q: string, label?: string): void {
      if (!q || q === "auto" || q === "default" || q === "tiny") return;
      const lab = label && label.length ? label : labels[q] || q;
      for (let i = 0; i < rows.length; i++) {
        if (String(rows[i]!["label"]) === lab) return;
      }
      seen[q] = true;
      rows.push({ "quality": q, "label": lab });
    }
    bindMoviePlayerApi();
    const player = moviePlayer();
    if (!player) throw new JsEngineErrorImpl("catalog: no movie_player", "E20085");
    const data = player["getAvailableQualityData"]();
    const list = Array.isArray(data) ? data : [];
    for (let i = 0; i < list.length; i++) {
      const o = asRec(list[i]);
      if (!o) continue;
      // A paygated upsell ("1080p Premium" on a non-Premium account) is listed but unplayable.
      if (o["isPlayable"] === false) continue;
      const q = o["quality"] != null ? String(o["quality"]) : "";
      const lab = o["qualityLabel"] != null ? String(o["qualityLabel"]) : "";
      add(q, lab);
    }
    return rows;
  }
}

function availableAudioTracks(player: MoviePlayer): Record<string, unknown>[] {
  const list = player["getAvailableAudioTracks"] ? player["getAvailableAudioTracks"]() : null;
  const out: Record<string, unknown>[] = [];
  if (!Array.isArray(list)) return out;
  for (let i = 0; i < list.length; i++) {
    const track = asRec(list[i]);
    if (track && typeof track["id"] === "string") out.push(track);
  }
  return out;
}

/** base.js minifies the field holding a track's {id, name, isDefault}, so it is found by shape. */
function audioTrackInfo(track: Record<string, unknown>): Record<string, unknown> | null {
  for (const key in track) {
    const info = asRec(track[key]);
    if (info && typeof info["name"] === "string" && typeof info["isDefault"] === "boolean")
      return info;
  }
  return null;
}

/** Empty for a single-track video: there is nothing to pick. */
function audioTrackRows(player: MoviePlayer): Record<string, unknown>[] {
  const tracks = availableAudioTracks(player);
  const rows: Record<string, unknown>[] = [];
  if (tracks.length < 2) return rows;
  for (let i = 0; i < tracks.length; i++) {
    const info = audioTrackInfo(tracks[i]!);
    if (!info) return [];
    rows.push({
      "id": tracks[i]!["id"],
      "displayName": info["name"],
      "isDefault": info["isDefault"],
    });
  }
  return rows;
}

function selectedAudioTrackId(player: MoviePlayer): string | null {
  const track = asRec(player["getAudioTrack"] ? player["getAudioTrack"]() : null);
  const id = track ? track["id"] : null;
  return typeof id === "string" ? id : null;
}

const ytPlayer = new Player();

export function initPlayer(): Promise<Record<string, unknown>> {
  return ytPlayer.init();
}

export function loadPlayer(
  videoId: string,
  options: PlayerLoadOptions,
): Promise<Record<string, unknown>> {
  return ytPlayer.load(videoId, options);
}

export function preloadPlayer(videoId: string): Promise<Record<string, unknown>> {
  return ytPlayer.preload(videoId);
}

export function setPlayerQuality(quality: string, audioOnly: boolean): void {
  ytPlayer.setQuality(quality, audioOnly);
}

export function mintPlayerPo(videoId: string): Promise<Record<string, unknown>> {
  return ytPlayer.mintPo(videoId);
}

export function setPlayerAudioTrack(id: string): void {
  ytPlayer.setAudioTrack(id);
}

export function playPlayer(): void {
  ytPlayer.play();
}

export function setPlayerLoop(enable: boolean): void {
  ytPlayer.setLoop(enable);
}

export function setPlayerSpeed(speed: number, tempo?: number): void {
  ytPlayer.setSpeed(speed, tempo);
}

export function setPlayerSpeedPitch(speed: number, pitch: number): void {
  ytPlayer.setSpeedPitch(speed, pitch);
}

export function pausePlayer(): void {
  ytPlayer.pause();
}

export function seekPlayer(t: number): void {
  ytPlayer.seek(t);
}

export function stopPlayer(): void {
  ytPlayer.stop();
}

export function statusPlayer(): Record<string, unknown> {
  return ytPlayer.status();
}

export function progressPlayer(): Record<string, unknown> {
  return ytPlayer.progress();
}

export function progressClockPlayer(): Record<string, unknown> {
  return ytPlayer.progressClock();
}

export function playerStatePlayer(): Record<string, unknown> {
  return ytPlayer.listenState();
}

export function picturePlayer(): Record<string, unknown> {
  return ytPlayer.picture();
}

export function detailsPlayer(): Record<string, unknown> {
  return ytPlayer.details();
}

export function catalogPlayer(): Record<string, unknown> {
  return ytPlayer.catalog();
}

/** The movie player exists only after the first player/load; until then there is no catalog. */
export function catalogReady(): boolean {
  return moviePlayer() != null;
}

export type ListenKind = "progress" | "state" | "picture" | "details";

let catalogHook: (() => void) | null = null;
let listenHook: ((kind: ListenKind) => void) | null = null;

export function setCatalogHook(fn: (() => void) | null): void {
  catalogHook = fn;
}

export function setListenHook(fn: ((kind: ListenKind) => void) | null): void {
  listenHook = fn;
  if (fn) bindHtmlMediaEvents();
  else unbindHtmlMediaEvents();
}

function notifyCatalog(): void {
  if (catalogHook) catalogHook();
}

onCaptionsChanged(notifyCatalog);

function notifyListen(kind: ListenKind): void {
  if (listenHook) listenHook(kind);
}

/** YouTube's own words for a playability verdict, as its player shows them: the headline over its
 *  detail, e.g. "Video unavailable" / "This video is private". The error screen comes in two layouts
 *  (the legacy message renderer's reason/subreason, the interstitial view model's title/description);
 *  without one, the bare reason and the status's messages. Already in the viewer's language. */
function playabilityUserMessage(playability: Record<string, unknown> | null): string {
  const screen = get(playability, "errorScreen", "playerErrorMessageRenderer");
  const interstitial = get(
    playability,
    "errorScreen",
    "playerInterstitialRenderer",
    "content",
    "interstitialViewModel",
  );
  const bareReason = get(playability, "reason");
  const headline =
    nonBlank(textOf(get(screen, "reason"))) ??
    nonBlank(textOf(get(interstitial, "title"))) ??
    nonBlank(typeof bareReason === "string" ? bareReason : undefined);
  const messages = get(playability, "messages");
  const detail =
    nonBlank(textOf(get(screen, "subreason"))) ??
    nonBlank(textOf(get(interstitial, "description"))) ??
    nonBlank(
      Array.isArray(messages)
        ? messages.filter((m): m is string => typeof m === "string").join("\n")
        : undefined,
    );
  const lines: string[] = [];
  if (headline) lines.push(headline);
  if (detail && detail !== headline) lines.push(detail);
  return lines.join("\n");
}

function nonBlank(text: string | undefined): string | undefined {
  return text && text.trim() !== "" ? text : undefined;
}
