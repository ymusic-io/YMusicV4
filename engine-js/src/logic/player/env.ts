// Helpers around the original env.raw.js / idl_stubs.raw.js (eval'd as source, not Closure).
// Page shape lives in env.raw.js. This file only loads it and overlays live homepage ytcfg.

import origEnvJs from "./orig/env.raw.js";
import origIdlStubsJs from "./orig/idl_stubs.raw.js";
import chromeShapesJs from "./orig/chrome_shapes.raw.js";
import { HttpRequest } from "../../protocol/http";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";

function native(nameOrFn: string | Function, impl?: Function): Function {
  const name = typeof nameOrFn === "string" ? nameOrFn : nameOrFn.name || "";
  const fn = typeof nameOrFn === "string" ? (impl as Function) : nameOrFn;
  if (typeof __nativeFn === "function") return __nativeFn(name, fn);
  return fn;
}

const IMAGE_ACCEPT = "image/jxl,image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8";

function requestImage(url: string, done: () => void): void {
  const location = (globalThis as unknown as Record<string, unknown>)["location"] as
    Record<string, unknown> | undefined;
  const request = HttpRequest.get(url)
    .setHeader("User-Agent", DESKTOP_UA)
    .setHeader("Accept", IMAGE_ACCEPT)
    .timeout(30000);
  if (location && location["href"] != null) request.setHeader("Referer", String(location["href"]));
  executeWithCookies(url, request).then(
    function (res) {
      res.close();
      done();
    },
    function (error: unknown) {
      console.warn("image " + url + " failed:", error);
      done();
    },
  );
}

let origEnvInstalled = false;
/** The handle env.raw.js evaluates to: wire-shaped (quoted keys), never a global BotGuard can see. */
let origEnvHandle: Record<string, unknown> | null = null;
/** What chrome_shapes.raw.js evaluates to; applyChromeShapes runs it once. */
let reshapeChromePrototypes: (() => void) | null = null;

/** Player orig env+idl (DOM that Application.create needs). */
export function installOrigEnv(): void {
  if (origEnvInstalled) return;
  origEnvInstalled = true;
  origEnvHandle = (0, eval)(origEnvJs) as Record<string, unknown> | null;
  const setImageRequest = origEnvHandle ? origEnvHandle["setImageRequest"] : undefined;
  if (typeof setImageRequest === "function") (setImageRequest as Function)(requestImage);
  (0, eval)(origIdlStubsJs);
  const reshape = (0, eval)(chromeShapesJs) as unknown;
  if (typeof reshape === "function") reshapeChromePrototypes = reshape as () => void;
  const installChromeBackedApis = origEnvHandle
    ? origEnvHandle["installChromeBackedApis"]
    : undefined;
  if (typeof installChromeBackedApis === "function") (installChromeBackedApis as Function)();
}

/** Must run after every env install that shapes interface prototypes and before BotGuard's
 *  snapshot: BotGuard fingerprints their own-member lists, and a mismatch costs sps=2. */
export function applyChromeShapes(): void {
  const reshape = reshapeChromePrototypes;
  reshapeChromePrototypes = null;
  if (reshape) reshape();
}

/** Layout getters answer 0 from now on: BotGuard only measures before its snapshot, and the player
 *  UI's hundreds of reads made real layout cost seconds per load in QuickJS. */
export function endLayoutProbes(): void {
  const end = origEnvHandle ? origEnvHandle["endLayoutProbes"] : undefined;
  if (typeof end === "function") (end as () => void)();
}

/** navigator.language(s) as Chrome derives them from the Accept-Language the homepage was fetched
 *  with; kept at the env default when the config predates `acceptLanguage`. */
export function applyNavigatorLanguages(acceptLanguage: unknown): void {
  if (typeof acceptLanguage !== "string") return;
  const tags = acceptLanguage
    .split(",")
    .map((part) => (part.split(";")[0] ?? "").trim())
    .filter((tag) => tag.length > 0);
  const first = tags[0];
  if (first === undefined) return;
  const nav = (globalThis as unknown as Record<string, unknown>)["navigator"] as
    Record<string, unknown> | undefined;
  if (nav == null) return;
  nav["language"] = first;
  nav["languages"] = tags;
}

/** navigator.languages as the BotGuard VM reads them, for the replay record. */
export function navigatorLanguages(): unknown {
  const nav = (globalThis as unknown as Record<string, unknown>)["navigator"] as
    Record<string, unknown> | undefined;
  return nav == null ? undefined : nav["languages"];
}

let eventIdGets = 0;
let ytGets = 0;
let countYtAccess = true;

export function ytEventIdGets(): number {
  return eventIdGets;
}

export function ytWindowGets(): number {
  return ytGets;
}

/** Stop counting after the PO snapshot — base.js also reads yt.config_.EVENT_ID. */
export function freezeYtAccessCounts(): void {
  countYtAccess = false;
}

export function installYtcfg(data: Record<string, unknown>): void {
  const g = globalThis as unknown as Record<string, unknown>;
  const data_: Record<string, unknown> = {};
  const cfg: Record<string, unknown> = {
    "data_": data_,
    "set": native("set", function (d: unknown) {
      if (typeof d !== "object" || d === null) return;
      const rec = d as Record<string, unknown>;
      for (const k of Object.keys(rec)) data_[k] = rec[k];
    }),
    "get": native("get", function (k: string, fb?: unknown) {
      return Object.prototype.hasOwnProperty.call(data_, k) ? data_[k] : fb;
    }),
  };
  g["ytcfg"] = cfg;
  const ytObj: Record<string, unknown> = { "config_": data_ };
  ytGets = 0;
  countYtAccess = true;
  g["yt"] = ytObj;
  (cfg["set"] as (d: unknown) => void)(data);
  eventIdGets = 0;
  let held = data_["EVENT_ID"];
  Object.defineProperty(data_, "EVENT_ID", {
    configurable: true,
    enumerable: true,
    get: function () {
      if (countYtAccess) {
        eventIdGets++;
        ytGets++;
      }
      return held;
    },
    set: function (v: unknown) {
      held = v;
    },
  });
}
