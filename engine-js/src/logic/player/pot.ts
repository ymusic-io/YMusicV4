// Headless content-PO minter: homepage ytcfg + ytAtN program (not WAA Create),
// set window.yt EVENT_ID, load interpreter, snapshot (must read yt.config_.EVENT_ID), GenerateIT, mint.

import { JsEngineErrorImpl } from "../../protocol/command";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
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
  let botguardResponse: unknown;
  const webPo: unknown[] = [];
  try {
    let ret = (vmA as (...args: unknown[]) => unknown)(
      program,
      setup,
      true,
      undefined,
      nop,
      [[], []],
      undefined,
      false,
      [nop, nop, nop, nop, nop],
    );
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
    botguardResponse = await new Promise(function (resolve, reject) {
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
    out["snapshotLen"] = typeof botguardResponse === "string" ? botguardResponse.length : 0;
    out["snapshotPrefix"] =
      typeof botguardResponse === "string" ? (botguardResponse as string).slice(0, 48) : "";
    console.info("bg snap len=" + out["snapshotLen"]);
    if (typeof botguardResponse === "string") recordBotguardSnapshot(botguardResponse);
    out["webPo0"] = typeof webPo[0];
    out["webPoLen"] = webPo.length;
    out["ytGets"] = ytWindowGets();
    out["ytEventIdGets"] = ytEventIdGets();
    freezeYtAccessCounts();
    if (!cfg["keepYtcfg"] && ytWindowGets() === 0)
      throw new JsEngineErrorImpl(
        "window.yt was not accessed snap=" + out["snapshotLen"] + " webPo=" + out["webPo0"],
        "E20074",
      );
    if (typeof webPo[0] !== "function")
      throw new JsEngineErrorImpl(
        "webPo minter missing snap=" + out["snapshotLen"] + " ytGets=" + ytEventIdGets(),
        "E20074",
      );
  } finally {
    __bgEntropyEnd();
    recordBotguardEntropy();
  }

  out["step"] = "generateIT";
  const git = await waaJson(GIT_URL, JSON.stringify([REQUEST_KEY, botguardResponse]));
  out["gitStatus"] = git["status"];
  const gitArr = git["json"];
  const integrityToken = gitArr && gitArr[0];
  out["integrityTokenLen"] = typeof integrityToken === "string" ? integrityToken.length : 0;
  const itBytes = typeof integrityToken === "string" ? b64ToU8(integrityToken).length : 0;
  out["integrityTokenBytes"] = itBytes;
  console.info("bg generateIT status=" + git["status"] + " it=" + out["integrityTokenLen"]);
  if (typeof integrityToken !== "string" || !integrityToken) {
    throw new JsEngineErrorImpl("Could not get integrity token", "E20074");
  }
  recordBotguardSnapshotResponse(integrityToken);

  out["step"] = "mint";
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
  console.info("bg mint");
  const mint = mintCallback as (id: Uint8Array) => unknown;
  /* Wrap: async return of a .then-bearing function is Promise-assimilated to undefined. */
  return function millContentPo(id: Uint8Array) {
    const token = mint(id);
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
