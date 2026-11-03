// YouTube cookie policy — the legacy YTHttpClientCookieStoreAndHeadersManager role, in the
// remotely-updatable bundle. The host HTTP client carries NO cookie jar: every request's `Cookie`
// header is cooked HERE from the app-provided store (Cookies.get/set — the platform cookie store
// the WebView player shares), and every response's `Set-Cookie` is written back. So cookie behavior
// (which cookies ride, consent/SOCS synthesis, future login handling) ships without an app release,
// and scraping + playback present one identity.

import { Cookies } from "../../protocol/cookie";
import { Settings } from "../../protocol/settings";
import { authorizationHeader, originOf } from "./sapisid";

const YT_ORIGIN = "https://www.youtube.com";
const GOOGLE_ORIGIN = "https://www.google.com";
// A SOCS value YouTube used to hand pre-consent pages — treated as "consent not given" (legacy).
const REJECTED_SOCS = "CAISEwgDEgk1NDg4NzI2MjUaAmVuIAEaBgiAz9ylBg";
// A known-good "consented" SOCS token (legacy remote-config default). Bundle-updatable.
const CONSENTED_SOCS =
  "CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjQwODExLjA4X3AwGgJlbiACGgYIgIr1tQY";
const COOKIE_ATTRS = "; Expires=Sun, 10-Jan-2038 07:59:59 GMT; Path=/";

/**
 * The signed-in session identifiers a youtubei call carries alongside the SAPISIDHASH — the
 * account's ytcfg SESSION_INDEX (`X-Goog-AuthUser`, NOT a hardcoded "0") and DELEGATED_SESSION_ID
 * (`X-Goog-PageId`, brand/delegated accounts). Both from session.ts, both omitted when unknown.
 * Passing this (even with undefined fields) marks the request AUTHENTICATED — the auth headers ride
 * only when the jar actually holds SAPISID, so a logged-out request is unaffected (legacy YTHttpClient
 * deletes these headers when signed out).
 */
export interface AuthContext {
  sessionIndex: string | undefined;
  delegatedSessionId: string | undefined;
  userSessionId: string | undefined;
}

/**
 * Execute `builder`'s request with the cooked `Cookie` header for `url`, then store the response's
 * `Set-Cookie`s. The one path all YouTube requests go through. Pass `auth` (the youtubei callers) to
 * add the `Authorization: SAPISIDHASH …` + `X-Goog-AuthUser`/`X-Goog-PageId` headers when the jar is
 * signed in; logged out (no SAPISID) it's a no-op. The cookie map is read ONCE and shared, so auth
 * adds no extra round-trip.
 */
/** InnerTube paths base.js hits via XHR/fetch — they need SAPISIDHASH when signed in. */
export function isYoutubeiUrl(url: string): boolean {
  return url.indexOf("/youtubei/") >= 0;
}

/** youtube.com / youtu.be / googlevideo.com — browser Cookie header rides regardless of Authorization. */
export function isYoutubeHost(url: string): boolean {
  return (
    url.indexOf("youtube.com") !== -1 ||
    url.indexOf("youtu.be") !== -1 ||
    url.indexOf("googlevideo.com") !== -1 ||
    url.indexOf("youtubekids.com") !== -1
  );
}

/**
 * Cook Cookie (and, when [auth] is passed, SAPISIDHASH) onto [builder]. Shared by
 * [executeWithCookies] and the player XHR/fetch shims.
 */
export async function attachRequestIdentity(
  url: string,
  builder: HttpRequestBuilder,
  auth?: AuthContext,
): Promise<void> {
  const map = await requestCookieMap(url);
  const cookie = cookieHeader(map);
  if (cookie.length > 0) builder.setHeader("Cookie", cookie);
  if (auth !== undefined) applyAuthHeaders(builder, map, originOf(url), auth);
}

export async function executeWithCookies(
  url: string,
  builder: HttpRequestBuilder,
  auth?: AuthContext,
): Promise<HttpResponse> {
  await attachRequestIdentity(url, builder, auth);
  const res = await builder.build().execute();
  await storeSetCookies(res.url.length > 0 ? res.url : url, res);
  return res;
}

/** The `Cookie` header value for `url` — the stored cookies, with consent ensured and the app
 *  language re-asserted into PREF for YouTube. */
export async function cookieHeaderFor(url: string): Promise<string> {
  return cookieHeader(await requestCookieMap(url));
}

/** The stored cookie map for `url`, with YouTube's consent ensured and the app language re-asserted. */
async function requestCookieMap(url: string): Promise<Record<string, string | undefined>> {
  const map = await Cookies.get(url);
  // An empty-valued cookie is a TOMBSTONE (a half-applied wipe — seen live 2026-07-31: session
  // "SAPISID=" next to live __Secure twins). Treat it as absent: never emitted on a request,
  // never fed to auth — `SAPISID=` in the Cookie header poisons the whole exchange.
  for (const name in map) {
    const v = map[name];
    if (v === undefined || v.length === 0) delete map[name];
  }
  if (isYoutubeHost(url) && url.indexOf("googlevideo.com") === -1) {
    await ensureConsent(map);
    await syncPref(map);
  }
  return map;
}

function cookieHeader(map: Record<string, string | undefined>): string {
  const parts: string[] = [];
  for (const name in map) parts.push(name + "=" + map[name]);
  return parts.join("; ");
}

/** Add the authenticated-request headers when the jar holds SAPISID (else a no-op). The AuthUser /
 *  PageId values come from the scraped session — never hardcoded. */
function applyAuthHeaders(
  builder: HttpRequestBuilder,
  map: Record<string, string | undefined>,
  origin: string,
  auth: AuthContext,
): void {
  const authorization = authorizationHeader(map, origin, auth.userSessionId);
  if (authorization === undefined) return; // not signed in — no auth headers at all
  builder.setHeader("Authorization", authorization);
  builder.setHeader("X-Youtube-Bootstrap-Logged-In", "true");
  if (auth.sessionIndex !== undefined) builder.setHeader("X-Goog-AuthUser", auth.sessionIndex);
  if (auth.delegatedSessionId !== undefined)
    builder.setHeader("X-Goog-PageId", auth.delegatedSessionId);
}

// YouTube serves consent walls (no feed, stripped pages) without a granted CONSENT/SOCS pair.
// Missing or ungranted -> synthesize one for .youtube.com AND .google.com (legacy behavior) and
// carry it on the current request too.
async function ensureConsent(map: Record<string, string | undefined>): Promise<void> {
  const consent = map["CONSENT"];
  const socs = map["SOCS"];
  const granted =
    consent !== undefined &&
    consent.indexOf("YES+") === 0 &&
    socs !== undefined &&
    socs !== REJECTED_SOCS;
  if (granted) return;

  const ytConsent = "YES+" + (100 + Math.floor(Math.random() * 500));
  const googleConsent = "YES+" + (100 + Math.floor(Math.random() * 500));
  await Cookies.set(YT_ORIGIN, [
    "CONSENT=" + ytConsent + "; Domain=.youtube.com" + COOKIE_ATTRS,
    "SOCS=" + CONSENTED_SOCS + "; Domain=.youtube.com" + COOKIE_ATTRS,
  ]);
  await Cookies.set(GOOGLE_ORIGIN, [
    "CONSENT=" + googleConsent + "; Domain=.google.com" + COOKIE_ATTRS,
    "SOCS=" + CONSENTED_SOCS + "; Domain=.google.com" + COOKIE_ATTRS,
  ]);
  map["CONSENT"] = ytConsent;
  map["SOCS"] = CONSENTED_SOCS;
}

const EXPIRED = "; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";

// The locale in PREF: hl is the APP LANGUAGE — pulled from host settings, cached in engine memory
// (never queried per request; yt/locale/apply is the change notification that re-pulls) and
// re-asserted on every cooked request, because it is app-owned truth the WebView's pages must not
// drift. gl is the user's REGION PICK, written/removed ONLY by yt/locale/apply — PREF itself (the
// persistent, WebView-shared store) is the pick's record, and the slot is deliberately never
// re-asserted: the region is YouTube's to control, and each session refresh parses its answer
// back as the working value (session.ts).
let appLanguage: string | null | undefined; // null = no settings host; undefined = not yet pulled

const APP_LANGUAGE_KEY = "app.uiLanguage";

/**
 * The app language — the ONE hl (seeded by the shell at first launch, changed only by the language
 * picker). Pulled from host settings once and cached; [invalidateAppLanguage] (from apply, the
 * change notification) forces a re-pull. An empty read (raced an unseeded store) is retried on the
 * next ask; a host without a settings store answers null once and is not asked again.
 */
export async function pulledAppLanguage(): Promise<string | undefined> {
  if (appLanguage === undefined) {
    try {
      const v = await Settings.get(APP_LANGUAGE_KEY, "");
      if (v.length > 0) appLanguage = v;
    } catch (e) {
      appLanguage = null;
    }
  }
  return appLanguage === undefined || appLanguage === null ? undefined : appLanguage;
}

/** Drop the cached app language so the next ask re-pulls — apply calls this on every notify. */
export function invalidateAppLanguage(): void {
  appLanguage = undefined;
}

/**
 * The region-pick write (the legacy YTPrefsUpdateTask role): [gl] lands in the `PREF` cookie —
 * blank = "automatic", the slot is REMOVED so the homepage answers with YouTube's own IP-derived
 * region again — the app language re-asserts into the hl slot, and `s_gl` (the server's sticky
 * region echo) is deleted, as legacy did on every region change. PREF itself (the persistent,
 * WebView-shared store) is the pick's record; the caller follows up with a session refresh, whose
 * parsed GL overwrites the working value — YouTube outranks the pick. Returns whether anything
 * changed — an already-matching PREF is left untouched (no cookie write, no session respin).
 * An undefined [gl] (a language change) leaves the gl slot and `s_gl` alone.
 */
export async function applyLocaleCookie(gl: string | undefined): Promise<boolean> {
  const map = await Cookies.get(YT_ORIGIN);
  const hl = await pulledAppLanguage();
  const pref = prefWithout(
    map["PREF"],
    gl === undefined ? ["hl", "al", "app"] : ["hl", "gl", "al", "app"],
  );
  if (hl !== undefined) pref.push("hl=" + hl);
  if (gl !== undefined && gl.length > 0) pref.push("gl=" + gl);
  const next = pref.join("&");
  if (next === (map["PREF"] ?? "")) return false;
  const values: string[] = [
    pref.length > 0
      ? "PREF=" + next + "; Domain=.youtube.com" + COOKIE_ATTRS
      : "PREF=; Domain=.youtube.com" + EXPIRED,
  ];
  if (gl !== undefined) values.push("s_gl=; Domain=.youtube.com" + EXPIRED);
  await Cookies.set(YT_ORIGIN, values);
  return true;
}

/** Repair `map`'s PREF on every cooked request — the WebView's pages can rewrite it underneath us:
 *  re-assert the app language into the hl slot, and drop `app` (`app=m`, persisted by the mobile
 *  sign-in's `persist_app=1`, redirects www.youtube.com to m.youtube.com — a mobile session whose
 *  BotGuard program the desktop player can't mint with). The gl slot is deliberately left alone —
 *  the region is YouTube's to control (its rewrites are legitimate; the next session refresh parses
 *  them back). */
async function syncPref(map: Record<string, string | undefined>): Promise<boolean> {
  const hl = await pulledAppLanguage();
  const pref = prefWithout(map["PREF"], hl === undefined ? ["app"] : ["hl", "al", "app"]);
  if (hl !== undefined) pref.push("hl=" + hl);
  const next = pref.join("&");
  if (next === (map["PREF"] ?? "")) return false;
  await Cookies.set(YT_ORIGIN, [
    pref.length > 0
      ? "PREF=" + next + "; Domain=.youtube.com" + COOKIE_ATTRS
      : "PREF=; Domain=.youtube.com" + EXPIRED,
  ]);
  if (pref.length > 0) map["PREF"] = next;
  else delete map["PREF"];
  return true;
}

/** The `&`-separated PREF entries minus `drop`'s keys — unknown keys (f2, f6, …) ride unchanged. */
function prefWithout(pref: string | undefined, drop: string[]): string[] {
  if (pref === undefined || pref.length === 0) return [];
  const out: string[] = [];
  for (const entry of pref.split("&")) {
    const eq = entry.indexOf("=");
    const key = eq < 0 ? entry : entry.slice(0, eq);
    if (entry.length > 0 && drop.indexOf(key) === -1) out.push(entry);
  }
  return out;
}

// ---- SameSite -----------------------------------------------------------------------------------
// Chromium 51-66 (the system WebView of un-updated devices, e.g. Android 9 head units) silently drops
// every cookie marked `SameSite=None`, which YouTube's identity and sign-in cookies all are. The
// attribute is stripped from every cookie before it reaches the store.
function withoutSameSite(setCookie: string): string {
  return setCookie.replace(/;\s*samesite\s*=\s*[^;]*/gi, "");
}

// ---- Remote logout ------------------------------------------------------------------------------
// A response EXPLICITLY wiping LOGIN_INFO (`Set-Cookie: LOGIN_INFO=;…`) is YouTube revoking the
// session server-side — the ONE trustworthy logout signal. (A missing account header is NOT: that
// can be a markup change / parser miss and must never sign the user out.) login.ts registers the
// action (drop the remaining auth cookies + the cached account card); the detection lives here
// because storeSetCookies is the one path every response's cookies cross.
let onLoginInfoWiped: (() => Promise<void>) | undefined;

export function setLoginInfoWipedHandler(handler: () => Promise<void>): void {
  onLoginInfoWiped = handler;
}

/** `Set-Cookie: LOGIN_INFO=<empty>…` — the server clearing the sign-in cookie. */
function isLoginInfoWipe(header: string): boolean {
  if (header.indexOf("LOGIN_INFO=") !== 0) return false;
  const rest = header.slice("LOGIN_INFO=".length);
  const semi = rest.indexOf(";");
  return (semi < 0 ? rest : rest.slice(0, semi)).trim() === "";
}

/** Write a response's `Set-Cookie` headers back to the store. Best-effort: a store failure must
 *  not fail the request that carried the response — breadcrumb and move on. */
export async function storeSetCookies(url: string, res: HttpResponse): Promise<void> {
  const values: string[] = [];
  for (const entry of res.headers.entries) {
    if (String(entry[0]).toLowerCase() === "set-cookie") values.push(entry[1]);
  }
  await applySetCookieValues(url, values);
}

async function applySetCookieValues(url: string, values: string[]): Promise<void> {
  if (values.length === 0) return;
  try {
    await Cookies.set(url, values.map(withoutSameSite));
  } catch (e) {
    console.warn("cookie store write failed:", e);
  }
  let wiped = false;
  for (const v of values) {
    if (isLoginInfoWipe(v)) {
      wiped = true;
      break;
    }
  }
  if (wiped && onLoginInfoWiped !== undefined) {
    console.info("remote logout: server wiped LOGIN_INFO (" + url + ")");
    try {
      await onLoginInfoWiped();
    } catch (e) {
      console.warn("remote logout cleanup failed:", e);
    }
  }
}
