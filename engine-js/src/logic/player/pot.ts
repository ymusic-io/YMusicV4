// Headless content-PO minter: homepage ytcfg + ytAtN program (not WAA Create),
// set window.yt EVENT_ID, load interpreter, snapshot (must read yt.config_.EVENT_ID), GenerateIT, mint.

import { JsEngineErrorImpl } from "../../protocol/command";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA, getSession, SESSION_MAX_AGE_MS } from "../yt/session";
import { HttpRequest } from "../../protocol/http";
import {
  freezeYtAccessCounts,
  installYtcfg,
  navigatorLanguages,
  ytEventIdGets,
  ytWindowGets,
} from "./env";
import { isAudioOnly, ymQuality } from "./audio_only";
import { viewportHeight, viewportWidth } from "./viewport";
import {
  finishBotguardDump,
  readBotguardDumpSwitch,
  recordBotguardEntropy,
  recordBotguardInit,
  recordBotguardMint,
  recordBotguardSnapshot,
  recordBotguardSnapshotResponse,
} from "./bg_dump";

const REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
const API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
const GIT_URL = "https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT";

type VmEntry = (...args: unknown[]) => unknown;
type Minter = (id: Uint8Array) => unknown;

interface BotguardRun {
  snapshot: unknown;
  webPo: unknown[];
}

/** What a re-mint needs from the session's first BotGuard run. */
interface RemintContext {
  vmA: VmEntry;
  globalName: string;
  ytcfg: Record<string, unknown>;
  eacrToken: string;
  interpreterHash: string;
  interpreterUrl: string;
}

let activeMint: Minter | null = null;
let remintContext: RemintContext | null = null;
let remintGeneration = 0;
let remintedTokens = new Map<string, Uint8Array>();

function waaHeaders(): Record<string, string> {
  return {
    "content-type": "application/json+protobuf",
    "x-goog-api-key": API_KEY,
    "x-user-agent": "grpc-web-javascript/0.1",
  };
}

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function b64ToU8(s: string): Uint8Array {
  const bin = atob(s);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}

function unwrapTru(v: unknown): string {
  if (typeof v === "string") return v;
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    const w =
      o["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"] ??
      o["privateDoNotAccessOrElseSafeScriptWrappedValue"];
    if (typeof w === "string") return w;
  }
  return "";
}

function absUrl(src: string): string {
  if (src.indexOf("://") >= 0) return src;
  if (src.indexOf("//") === 0) return "https:" + src;
  if (src.charAt(0) === "/") return "https://www.youtube.com" + src;
  return src;
}

function overlayReplay(cfg: Record<string, unknown>, extras: Record<string, unknown> | null): void {
  if (!extras) return;
  const replay = extras["replayChallenge"];
  if (typeof replay !== "function") return;
  const rec = asRec((replay as () => unknown)());
  if (!rec || typeof rec["program"] !== "string") return;
  const bg = asRec(cfg["bgChallenge"]) || {};
  bg["program"] = rec["program"];
  if (typeof rec["interpreterJavascript"] === "string")
    bg["interpreterJavascript"] = rec["interpreterJavascript"];
  if (typeof rec["interpreterUrl"] === "string") bg["interpreterUrl"] = rec["interpreterUrl"];
  if (typeof rec["globalName"] === "string") bg["globalName"] = rec["globalName"];
  cfg["bgChallenge"] = bg;
  const ytcfgData = asRec(cfg["ytcfg"]) || {};
  if (typeof rec["eventId"] === "string") {
    cfg["eventId"] = rec["eventId"];
    ytcfgData["EVENT_ID"] = rec["eventId"];
  }
  if (typeof rec["visitorData"] === "string") ytcfgData["VISITOR_DATA"] = rec["visitorData"];
  if (typeof rec["clientVersion"] === "string")
    ytcfgData["INNERTUBE_CLIENT_VERSION"] = rec["clientVersion"];
  cfg["ytcfg"] = ytcfgData;
}

/** Host inputs the mill can see outside the entropy tape. Read before __bgEntropyBegin. */
function hostInfo(extras: Record<string, unknown> | null): Record<string, unknown> {
  const h: Record<string, unknown> = {
    "app": versions.app,
    "bundle": BUNDLE_NAME,
    "bundleVersion": BACKEND_VERSION,
    "production": PRODUCTION,
    "tzOffset": new Date().getTimezoneOffset(),
    "viewport": [viewportWidth(), viewportHeight()],
    "audioOnly": isAudioOnly(),
    "quality": ymQuality(),
    "languages": navigatorLanguages(),
  };
  if (typeof __timeZone === "function") h["tz"] = __timeZone();
  if (extras && typeof extras["platform"] === "string") h["platform"] = extras["platform"];
  if (extras && typeof extras["arch"] === "string") h["arch"] = extras["arch"];
  if (typeof __mseAvcMaxLevel === "function") h["avcMaxLevel"] = __mseAvcMaxLevel();
  if (typeof __mseAv1Ok === "function") h["av1Ok"] = __mseAv1Ok();
  if (typeof __mseVp9Ok === "function") h["vp9Ok"] = __mseVp9Ok();
  return h;
}

async function waaJson(url: string, body: string): Promise<{ status: number; json: unknown[] }> {
  const r = await HttpRequest.post(url)
    .headers(waaHeaders())
    .setHeader("User-Agent", DESKTOP_UA)
    .setHeader("Origin", "https://www.youtube.com")
    .setHeader("Referer", "https://www.youtube.com/")
    .setHeader("Accept", "*/*")
    .body(body)
    .timeout(30000)
    .build()
    .execute();
  if (!r.ok) {
    r.close();
    throw JsEngineErrorImpl.httpStatus("generateIT", r.status, "E20149");
  }
  return { "status": r.status, "json": await r.jsonArray() };
}

/** Content-PO minter from main's `get_player_config` payload. HttpRequest only — never fetch. */
export async function minterFromConfig(
  cfg: Record<string, unknown>,
  out: Record<string, unknown>,
): Promise<(id: Uint8Array) => unknown> {
  try {
    return await buildMinter(cfg, out);
  } catch (e) {
    finishBotguardDump();
    throw e;
  }
}

async function buildMinter(
  cfg: Record<string, unknown>,
  out: Record<string, unknown>,
): Promise<(id: Uint8Array) => unknown> {
  await readBotguardDumpSwitch();
  const extras = asRec(__bgEntropyStart());
  const host = hostInfo(extras);
  overlayReplay(cfg, extras);
  const ytcfgData = asRec(cfg["ytcfg"]) || {};
  const eventId = typeof cfg["eventId"] === "string" ? (cfg["eventId"] as string) : "";
  if (eventId && ytcfgData["EVENT_ID"] === undefined) ytcfgData["EVENT_ID"] = eventId;
  out["visitorData"] =
    typeof ytcfgData["VISITOR_DATA"] === "string" ? ytcfgData["VISITOR_DATA"] : "";
  out["clientVersion"] =
    typeof ytcfgData["INNERTUBE_CLIENT_VERSION"] === "string"
      ? ytcfgData["INNERTUBE_CLIENT_VERSION"]
      : typeof ytcfgData["INNERTUBE_CONTEXT_CLIENT_VERSION"] === "string"
        ? ytcfgData["INNERTUBE_CONTEXT_CLIENT_VERSION"]
        : "";
  out["apiKey"] =
    typeof ytcfgData["INNERTUBE_API_KEY"] === "string" ? ytcfgData["INNERTUBE_API_KEY"] : "";
  if (!cfg["keepYtcfg"]) installYtcfg(ytcfgData);
  out["ytcfgKeys"] = Object.keys(ytcfgData).length;
  out["eventId"] = typeof ytcfgData["EVENT_ID"] === "string" ? ytcfgData["EVENT_ID"] : eventId;

  const bg = asRec(cfg["bgChallenge"]);
  if (!bg || typeof bg["program"] !== "string")
    throw JsEngineErrorImpl.parse("player config has no bgChallenge.program");
  const program = bg["program"];
  const globalName = String(bg["globalName"] ?? "trayride");
  out["programSource"] = "homepage";
  out["globalName"] = globalName;
  out["programLen"] = typeof program === "string" ? program.length : 0;

  out["step"] = "load";
  let vm = (globalThis as unknown as Record<string, unknown>)[globalName] as
    Record<string, unknown> | undefined;
  let interpJs = "";
  if (!vm || typeof vm["a"] !== "function") {
    const js = await fetchInterpreterJs(bg);
    interpJs = js;
    out["jsLen"] = js.length;
    /* After fetch, before eval — miller --preload interp is gone so this matches. */
    __bgEntropyBegin();
    (0, eval)(js);
    vm = (globalThis as unknown as Record<string, unknown>)[globalName] as
      Record<string, unknown> | undefined;
  } else {
    out["jsLen"] = 0;
    __bgEntropyBegin();
  }
  console.info("bg interp len=" + out["jsLen"]);
  const interpUrl = unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]);
  const initRec: Record<string, unknown> = {
    "botguardJsUrl": interpUrl,
    "eventId": out["eventId"],
    "visitorData": out["visitorData"],
    "clientVersion": out["clientVersion"],
    "globalName": globalName,
    "program": program,
    "host": host,
  };
  if (interpJs) initRec["interpJs"] = interpJs;
  recordBotguardInit(initRec);
  const vmA = vm && vm["a"];
  if (typeof vmA !== "function") throw new JsEngineErrorImpl("VM missing " + globalName, "E20074");

  let run: BotguardRun;
  try {
    run = await runProgram(vmA as VmEntry, program, out);
    if (typeof run.snapshot === "string") recordBotguardSnapshot(run.snapshot);
    out["ytGets"] = ytWindowGets();
    out["ytEventIdGets"] = ytEventIdGets();
    freezeYtAccessCounts();
    if (!cfg["keepYtcfg"] && ytWindowGets() === 0)
      throw new JsEngineErrorImpl(
        "window.yt was not accessed snap=" + out["snapshotLen"] + " webPo=" + out["webPo0"],
        "E20074",
      );
    requireWebPo(run, out);
  } finally {
    __bgEntropyEnd();
    recordBotguardEntropy();
  }

  out["step"] = "generateIT";
  const integrityToken = await generateIntegrityToken(run.snapshot, out);
  recordBotguardSnapshotResponse(integrityToken);

  out["step"] = "mint";
  activeMint = await minterFor(run.webPo, integrityToken);
  console.info("bg mint");
  remintGeneration = 0;
  remintedTokens = new Map();
  remintContext = {
    vmA: vmA as VmEntry,
    globalName: globalName,
    ytcfg: ytcfgData,
    eacrToken: typeof bg["eacrToken"] === "string" ? bg["eacrToken"] : "",
    interpreterHash: typeof bg["interpreterHash"] === "string" ? bg["interpreterHash"] : "",
    interpreterUrl: interpUrl,
  };
  /* Wrap: async return of a .then-bearing function is Promise-assimilated to undefined. */
  return function millContentPo(id: Uint8Array) {
    const token = (activeMint as Minter)(id);
    function done(t: unknown): unknown {
      recordBotguardMint(id, t);
      return t;
    }
    if (token instanceof Uint8Array || typeof token === "string") return done(token);
    if (
      token &&
      typeof token !== "function" &&
      typeof (token as Promise<unknown>).then === "function"
    ) {
      return (token as Promise<unknown>).then(done);
    }
    return done(token);
  };
}

/** One BotGuard run of [program] on the loaded interpreter, through its async snapshot. */
async function runProgram(
  vmA: VmEntry,
  program: string,
  out: Record<string, unknown>,
): Promise<BotguardRun> {
  out["step"] = "init";
  const fns: Record<string, unknown> = {};
  let setupDone: () => void = function () {};
  const setupWait = new Promise<void>(function (resolve) {
    setupDone = resolve;
  });
  const setup = function (
    asyncSnap: unknown,
    shutdown: unknown,
    passEvent: unknown,
    checkCamera: unknown,
  ) {
    fns["asyncSnap"] = asyncSnap;
    fns["shutdown"] = shutdown;
    fns["passEvent"] = passEvent;
    fns["checkCamera"] = checkCamera;
    setupDone();
  };
  const nop = function () {};
  const webPo: unknown[] = [];
  let ret = vmA(program, setup, true, undefined, nop, [[], []], undefined, false, [
    nop,
    nop,
    nop,
    nop,
    nop,
  ]);
  if (ret && typeof (ret as Promise<unknown>).then === "function")
    ret = await (ret as Promise<unknown>);
  if (typeof fns["asyncSnap"] !== "function") {
    await Promise.race([
      setupWait,
      new Promise<void>(function (resolve) {
        setTimeout(function () {
          resolve();
        }, 15000);
      }),
    ]);
  }
  const asyncSnap = fns["asyncSnap"];
  if (typeof asyncSnap !== "function")
    throw new JsEngineErrorImpl("asyncSnapshotFunction missing", "E20074");
  await new Promise(function (resolve) {
    setTimeout(resolve, 50);
  });

  out["step"] = "snapshot";
  console.info("bg snap start");
  const snapshot = await new Promise<unknown>(function (resolve, reject) {
    try {
      (asyncSnap as (cb: (r: unknown) => void, args: unknown[]) => void)(
        function (resp: unknown) {
          resolve(resp);
        },
        [undefined, undefined, webPo, undefined],
      );
    } catch (e) {
      reject(e);
    }
  });
  out["snapshotLen"] = typeof snapshot === "string" ? snapshot.length : 0;
  out["snapshotPrefix"] = typeof snapshot === "string" ? snapshot.slice(0, 48) : "";
  console.info("bg snap len=" + out["snapshotLen"]);
  out["webPo0"] = typeof webPo[0];
  out["webPoLen"] = webPo.length;
  return { snapshot: snapshot, webPo: webPo };
}

function requireWebPo(run: BotguardRun, out: Record<string, unknown>): void {
  if (typeof run.webPo[0] !== "function")
    throw new JsEngineErrorImpl(
      "webPo minter missing snap=" + out["snapshotLen"] + " ytGets=" + ytEventIdGets(),
      "E20074",
    );
}

async function generateIntegrityToken(
  snapshot: unknown,
  out: Record<string, unknown>,
): Promise<string> {
  const git = await waaJson(GIT_URL, JSON.stringify([REQUEST_KEY, snapshot]));
  out["gitStatus"] = git["status"];
  const gitArr = git["json"];
  const integrityToken = gitArr && gitArr[0];
  out["integrityTokenLen"] = typeof integrityToken === "string" ? integrityToken.length : 0;
  out["integrityTokenBytes"] =
    typeof integrityToken === "string" ? b64ToU8(integrityToken).length : 0;
  console.info("bg generateIT status=" + git["status"] + " it=" + out["integrityTokenLen"]);
  if (typeof integrityToken !== "string" || !integrityToken) {
    throw new JsEngineErrorImpl("Could not get integrity token", "E20074");
  }
  return integrityToken;
}

async function minterFor(webPo: unknown[], integrityToken: string): Promise<Minter> {
  let mintCallback = (webPo[0] as (t: Uint8Array) => unknown)(b64ToU8(integrityToken));
  /* Await a Promise, not a function that happens to have .then (thenable assimilation). */
  if (
    mintCallback &&
    typeof mintCallback !== "function" &&
    typeof (mintCallback as Promise<unknown>).then === "function"
  ) {
    mintCallback = await (mintCallback as Promise<unknown>);
  }
  if (typeof mintCallback !== "function") throw new JsEngineErrorImpl("APF:Failed", "E20074");
  return mintCallback as Minter;
}

async function fetchInterpreterJs(bg: Record<string, unknown>): Promise<string> {
  let js = unwrapTru(bg["interpreterJavascript"]);
  let interpUrl = unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]);
  if (!js && interpUrl) {
    interpUrl = absUrl(interpUrl);
    const ir = await executeWithCookies(
      interpUrl,
      HttpRequest.get(interpUrl).setHeader("User-Agent", DESKTOP_UA).timeout(30000),
    );
    if (!ir.ok) {
      ir.close();
      throw JsEngineErrorImpl.httpStatus("interpreter", ir.status, "E20148");
    }
    js = await ir.text();
  }
  if (!js) throw new JsEngineErrorImpl("no interpreter javascript", "E20074");
  if (js.indexOf("//#") === 0) js = js.slice(js.indexOf("\n") + 1);
  return js;
}

/** A new challenge bound to the page's EVENT_ID, as base.js refreshes its shared one; null when
 *  att/get gives none. */
async function attChallenge(ctx: RemintContext): Promise<Record<string, unknown> | null> {
  const ytcfg = ctx.ytcfg;
  const text = function (key: string): string {
    const v = ytcfg[key];
    return typeof v === "string" ? v : "";
  };
  const clientVersion =
    text("INNERTUBE_CLIENT_VERSION") || text("INNERTUBE_CONTEXT_CLIENT_VERSION");
  const client: Record<string, unknown> = { "clientName": "WEB", "clientVersion": clientVersion };
  if (text("HL")) client["hl"] = text("HL");
  if (text("GL")) client["gl"] = text("GL");
  const body: Record<string, unknown> = {
    "context": { "client": client },
    "engagementType": "ENGAGEMENT_TYPE_UNBOUND",
    "eacrToken": ctx.eacrToken,
  };
  if (ctx.interpreterHash) body["interpreterHash"] = ctx.interpreterHash;
  const key = text("INNERTUBE_API_KEY");
  const url =
    "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false" + (key ? "&key=" + key : "");
  const session = await getSession(SESSION_MAX_AGE_MS);
  const res = await executeWithCookies(
    url,
    HttpRequest.post(url)
      .setHeader("User-Agent", DESKTOP_UA)
      .setHeader("Content-Type", "application/json")
      .setHeader("X-Youtube-Client-Name", "1")
      .setHeader("X-Youtube-Client-Version", clientVersion)
      .setHeader("X-Goog-Visitor-Id", text("VISITOR_DATA"))
      .setHeader("Origin", "https://www.youtube.com")
      .setHeader("Referer", "https://www.youtube.com/")
      .body(JSON.stringify(body))
      .timeout(15000),
    {
      sessionIndex: session.sessionIndex,
      delegatedSessionId: session.delegatedSessionId,
      userSessionId: session.userSessionId,
    },
  );
  if (!res.ok) {
    res.close();
    console.warn("bg remint att/get status=" + res.status);
    return null;
  }
  const bg = asRec((await res.jsonObject())["bgChallenge"]);
  return bg && typeof bg["program"] === "string" ? bg : null;
}

/** The loaded interpreter's entry, loading the one [bg] names when the server rotated it. */
async function interpreterFor(bg: Record<string, unknown>, ctx: RemintContext): Promise<VmEntry> {
  const hash = typeof bg["interpreterHash"] === "string" ? bg["interpreterHash"] : "";
  const url = unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]);
  const inline = unwrapTru(bg["interpreterJavascript"]);
  const rotated = hash
    ? hash !== ctx.interpreterHash
    : (url !== "" && url !== ctx.interpreterUrl) || inline !== "";
  if (rotated) {
    (0, eval)(await fetchInterpreterJs(bg));
    const name = String(bg["globalName"] ?? ctx.globalName);
    const vm = asRec((globalThis as unknown as Record<string, unknown>)[name]);
    const entry = vm ? vm["a"] : undefined;
    if (typeof entry !== "function") throw new JsEngineErrorImpl("VM missing " + name, "E20074");
    ctx.vmA = entry as VmEntry;
    ctx.globalName = name;
    ctx.interpreterHash = hash;
    ctx.interpreterUrl = url;
  }
  return ctx.vmA;
}

/**
 * A new BotGuard challenge, run, integrity token and minter for this player session; every later
 * content token comes from it. The challenge is att/get's, bound to the page by its eacrToken;
 * without one (an engine.js that drops it) it comes from [rescrape], a new homepage.
 */
export async function remint(rescrape: () => Promise<Record<string, unknown>>): Promise<void> {
  const ctx = remintContext;
  if (!ctx) throw new JsEngineErrorImpl("re-mint before the session's first mint", "E20165");
  const t0 = Date.now();
  let source = "att";
  let bg = ctx.eacrToken ? await attChallenge(ctx) : null;
  if (!bg) {
    source = "homepage";
    const cfg = await rescrape();
    const data = asRec(cfg["ytcfg"]) || {};
    const eventId =
      typeof data["EVENT_ID"] === "string"
        ? (data["EVENT_ID"] as string)
        : String(cfg["eventId"] ?? "");
    const ytcfg = asRec((globalThis as unknown as Record<string, unknown>)["ytcfg"]);
    if (ytcfg && typeof ytcfg["set"] === "function")
      (ytcfg["set"] as (d: unknown) => void)({
        "EVENT_ID": eventId,
        "VISITOR_DATA": data["VISITOR_DATA"],
      });
    data["EVENT_ID"] = eventId;
    ctx.ytcfg = data;
    bg = asRec(cfg["bgChallenge"]);
    if (!bg || typeof bg["program"] !== "string")
      throw JsEngineErrorImpl.parse("player config has no bgChallenge.program");
    if (typeof bg["eacrToken"] === "string") ctx.eacrToken = bg["eacrToken"];
  }
  const vmA = await interpreterFor(bg, ctx);
  const out: Record<string, unknown> = {};
  const run = await runProgram(vmA, String(bg["program"]), out);
  requireWebPo(run, out);
  const integrityToken = await generateIntegrityToken(run.snapshot, out);
  activeMint = await minterFor(run.webPo, integrityToken);
  remintGeneration++;
  remintedTokens = new Map();
  console.info(
    "bg remint source=" +
      source +
      " generation=" +
      remintGeneration +
      " it=" +
      integrityToken.length +
      " ms=" +
      (Date.now() - t0),
  );
}

/** After a re-mint, the content token for [videoId] from the new minter: base.js may still send the
 *  one it minted before. Null before any re-mint. */
export function remintedPoToken(videoId: string): Uint8Array | null {
  if (remintGeneration === 0 || !activeMint || !videoId) return null;
  const held = remintedTokens.get(videoId);
  if (held) return held;
  const minted = activeMint(new TextEncoder().encode(videoId));
  const token =
    minted instanceof Uint8Array
      ? minted
      : typeof minted === "string" && minted
        ? b64ToU8(minted.replace(/-/g, "+").replace(/_/g, "/"))
        : null;
  if (!token) return null;
  remintedTokens.set(videoId, token);
  return token;
}
