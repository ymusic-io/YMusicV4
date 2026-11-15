// Per-session remill file over cache/put. Each record is prefix + JSON + newline, fire-and-forget.
// keepCount(4) trims old session keys after close. Payloads are not kept in JS.

import { callHost } from "../../protocol/command";
import { keepCount, scopedCache } from "../../protocol/cache";
import { RemoteConfig } from "../../protocol/remote_config";
import { open } from "../../transport";
import { DESKTOP_UA } from "../yt/session";
import { onShutdown } from "../../lifecycle";

const SCOPE = "yt.bg.session";
const KEEP = 4;
let dump: Pipe | null = null;
/** Off until the minter reads the switch; when off, E20088 still goes, without the dump. */
let dumpEnabled = false;
let writeChain: Promise<void> = Promise.resolve();
/** Shutdown acks on this and the runtime is freed right after: work not on it dies mid-flight. */
let finishing: Promise<void> = Promise.resolve();
let sessionKey = "";
let loggedSps = 0;
let withheldRounds = 0;
let dumpFinished = false;
let lastSabrUrl = "";
let lastSabrBody: Uint8Array | null = null;

function utcStamp(): string {
  const iso = new Date().toISOString();
  return (
    iso.slice(0, 4) +
    iso.slice(5, 7) +
    iso.slice(8, 10) +
    "-" +
    iso.slice(11, 13) +
    iso.slice(14, 16) +
    iso.slice(17, 19) +
    "-" +
    iso.slice(20, 23)
  );
}

function wait(p: unknown): Promise<void> {
  if (p && typeof (p as Promise<void>).then === "function") return p as Promise<void>;
  return Promise.resolve();
}

function warn(e: unknown, what: string): void {
  console.warn("bg dump " + what + " skipped: " + (e instanceof Error ? e.message : String(e)));
}

/** A failed write leaves a torn record: RESET so the host drops the staged value. */
function abandon(pipe: Pipe, e: unknown, what: string): void {
  warn(e, what);
  if (dump === pipe) dump = null;
  try {
    pipe.sink.close("bg dump " + what + " failed");
    pipe.source.close();
  } catch (_e) {}
}

function ensureDump(): Pipe | null {
  if (!dumpEnabled) return null;
  if (dump) return dump;
  sessionKey = utcStamp();
  try {
    dump = open("cache/put");
  } catch (e) {
    warn(e, "open");
    return null;
  }
  const pipe = dump;
  writeChain = Promise.resolve(
    wait(
      pipe.sink.writeObjectAsJson({
        "scope": SCOPE,
        "key": sessionKey,
        "meta": {},
        // Open until the first SABR verdict, however long before the first play.
        "idleTimeoutMs": 0,
      }),
    ),
  ).then(
    function () {},
    function (e) {
      abandon(pipe, e, "head");
    },
  );
  return dump;
}

function writeJsonLine(prefix: string, value: unknown): void {
  const pipe = ensureDump();
  if (!pipe) return;
  writeChain = writeChain
    .then(function () {
      return wait(pipe.sink.writeUtf8(prefix, " "));
    })
    .then(function () {
      return wait(pipe.sink.writeObjectAsJson(value));
    })
    .then(function () {
      return wait(pipe.sink.putByte(10));
    })
    .then(
      function () {},
      function (e) {
        abandon(pipe, e, "write");
      },
    );
}

function finishDump(report?: () => Promise<void>): void {
  const pipe = dump;
  dump = null;
  if (pipe) {
    writeChain = writeChain
      .then(function () {
        return wait(pipe.sink.close());
      })
      .then(function () {
        return pipe.source.readJsonObject();
      })
      .then(function () {
        pipe.source.close();
        return scopedCache(SCOPE, keepCount(KEEP));
      })
      .then(
        function () {},
        function (e) {
          try {
            pipe.source.close();
          } catch (_e) {}
          warn(e, "close");
        },
      );
  }
  const closed = writeChain;
  finishing = finishing.then(function () {
    return closed;
  });
  if (report) finishing = finishing.then(report);
}

// Player engine teardown: save what the session recorded instead of losing it to the hard close.
onShutdown(function () {
  finishDump();
  return finishing;
});

function captureReject(sps: number, key: string): Promise<void> {
  return callHost("log/capture", async function (pipe) {
    const head: Record<string, unknown> = { "code": "E20088", "message": "PO rejected sps=" + sps };
    if (key) {
      head["scope"] = SCOPE;
      head["key"] = key;
    }
    let p = pipe.sink.writeObjectAsJson(head);
    if (p) await p;
    p = pipe.sink.close();
    if (p) await p;
    await pipe.source.readUtf8();
  }).then(
    function () {},
    function (e) {
      console.warn("bg po-reject report skipped: " + (e instanceof Error ? e.message : String(e)));
    },
  );
}

function timeOriginMs(): number {
  const g = globalThis as unknown as Record<string, unknown>;
  const p = g["performance"] as Record<string, unknown> | undefined;
  const n = p && p["timeOrigin"];
  return typeof n === "number" ? n : 0;
}

/** Remote `enable_bg_dump`: false = off, a number N = only player.js revisions >= N, else on. */
export async function readBotguardDumpSwitch(): Promise<void> {
  const value = await RemoteConfig.get<boolean | number>("enable_bg_dump", true);
  dumpEnabled = typeof value === "number" ? BACKEND_VERSION >= value : value !== false;
}

export function recordBotguardEntropy(): void {
  if (!dumpEnabled || typeof __bgEntropyDump !== "function") return;
  const ent = __bgEntropyDump();
  if (!(ent instanceof Uint8Array) || !ent.length) return;
  const pipe = ensureDump();
  if (!pipe) return;
  writeChain = writeChain
    .then(function () {
      return wait(pipe.sink.writeUtf8("entropy "));
    })
    .then(function () {
      return wait(pipe.sink.writeBytes(ent));
    })
    .then(function () {
      return wait(pipe.sink.putByte(10));
    })
    .then(
      function () {},
      function (e) {
        abandon(pipe, e, "entropy");
      },
    );
}

/** The mint failed: no verdict will come, so close the session's dump now. */
export function finishBotguardDump(): void {
  dumpFinished = true;
  finishDump();
}

export function recordBotguardInit(p: Record<string, unknown>): void {
  finishDump();
  loggedSps = 0;
  withheldRounds = 0;
  dumpFinished = false;
  writeChain = Promise.resolve();
  lastSabrUrl = "";
  lastSabrBody = null;
  p["ua"] = DESKTOP_UA;
  p["timeOrigin"] = timeOriginMs();
  writeJsonLine("init", p);
}

export function recordBotguardSnapshot(s: string): void {
  writeJsonLine("snapshot", s);
}

export function recordBotguardSnapshotResponse(it: string): void {
  writeJsonLine("snapshotResponse", it);
}

export function recordBotguardMint(id: Uint8Array, token: unknown): void {
  const pipe = ensureDump();
  if (!pipe) return;
  const idStr = new TextDecoder().decode(id);
  writeChain = writeChain
    .then(function () {
      return wait(pipe.sink.writeUtf8('mint {"id":'));
    })
    .then(function () {
      return wait(pipe.sink.writeObjectAsJson(idStr));
    })
    .then(function () {
      if (typeof token === "string") {
        return wait(pipe.sink.writeUtf8(',"pot":')).then(function () {
          return wait(pipe.sink.writeObjectAsJson(token));
        });
      }
      if (token instanceof Uint8Array && token.length) {
        return wait(pipe.sink.writeUtf8(',"pot":"'))
          .then(function () {
            return wait(pipe.sink.writeBytesAsBase64url(token));
          })
          .then(function () {
            return wait(pipe.sink.writeUtf8('"'));
          });
      }
    })
    .then(function () {
      return wait(pipe.sink.writeUtf8("}"));
    })
    .then(function () {
      return wait(pipe.sink.putByte(10));
    })
    .then(
      function () {},
      function (e) {
        abandon(pipe, e, "mint");
      },
    );
}

export function recordSabrForDump(url: string, body: Uint8Array | null): void {
  lastSabrUrl = url;
  lastSabrBody = body && body.length ? body : null;
}

let poRejectedHook: (() => void) | null = null;

/** Same limit as the download's SabrSession.maxConsecutiveEmptyRounds. */
const MAX_WITHHELD_ROUNDS = 3;

/** Called on StreamProtectionStatus 3 (googlevideo rejected this session's PO token), or when
 *  googlevideo keeps withholding media while attestation stays pending (2). */
export function setPoRejectedHook(hook: (() => void) | null): void {
  poRejectedHook = hook;
}

let remintHook: (() => Promise<void>) | null = null;
let remints = 0;
let remintRun: Promise<boolean> | null = null;
let roundsSinceRemint = 0;
let tokenAccepted = false;
const MAX_REMINTS = 3;
/** Withheld rounds a re-minted token gets before the next re-roll: its verdict can lag a round. */
const ROUNDS_BEFORE_NEXT_REMINT = 2;

/** Re-mints the session's content token when googlevideo withholds media (sps=2, no media);
 *  installed per minter, so the counts start over with each player session. */
export function setRemintHook(hook: (() => Promise<void>) | null): void {
  remintHook = hook;
  remints = 0;
  remintRun = null;
  roundsSinceRemint = 0;
  tokenAccepted = false;
}

/** One re-mint out of the session's [MAX_REMINTS]; null when none is left. Resolves false when
 *  it failed. */
function runRemint(reason: string): Promise<boolean> | null {
  const hook = remintHook;
  if (!hook || remints >= MAX_REMINTS) return null;
  remints++;
  console.info("bg remint try=" + remints + " " + reason);
  const run = hook()
    .then(
      function () {
        tokenAccepted = false;
        roundsSinceRemint = 0;
        withheldRounds = 0;
        return true;
      },
      function (e: unknown) {
        roundsSinceRemint = ROUNDS_BEFORE_NEXT_REMINT;
        console.warn("bg remint failed: " + (e instanceof Error ? e.message : String(e)));
        return false;
      },
    )
    .then(function (ok) {
      remintRun = null;
      return ok;
    });
  remintRun = run;
  return run;
}

function startRemint(): void {
  if (remintRun) return;
  if (remints > 0 && roundsSinceRemint < ROUNDS_BEFORE_NEXT_REMINT) return;
  runRemint("after " + withheldRounds + " withheld rounds");
}

/**
 * A download's request for a new content token. "accepted": the player's current token plays
 * (googlevideo answered it sps=1), so the VM is left alone and the download's own requests are at
 * fault. "spent": no re-mint left, or it failed. A re-mint already running for playback is shared.
 */
export async function remintForDownload(): Promise<string> {
  if (tokenAccepted) return "accepted";
  const run = remintRun || runRemint("for a download");
  if (!run) return "spent";
  return (await run) ? "reminted" : "spent";
}

export function noteSps(sps: number, media: number): void {
  if (sps === 1) tokenAccepted = true;
  if (media > 0 || sps === 1) withheldRounds = 0;
  else if (sps === 2) {
    withheldRounds++;
    roundsSinceRemint++;
    startRemint();
  }
  if (!(sps > 0)) return;
  if (loggedSps !== sps) {
    loggedSps = sps;
    console.info("bg sps=" + sps);
  }
  const remintLeft = remintRun !== null || (remintHook !== null && remints < MAX_REMINTS);
  if (sps === 3 || (withheldRounds > MAX_WITHHELD_ROUNDS && !remintLeft)) {
    if (sps === 2) console.warn("bg sps=2 withheld media for " + withheldRounds + " rounds");
    withheldRounds = 0;
    if (poRejectedHook) poRejectedHook();
  }
  if (dumpFinished) return;
  if (typeof __bgEntropyStop === "function") __bgEntropyStop();
  // 2 with media is attestation pending, not a verdict yet. 2 without media is this mint failing:
  // the dump closes on it, before a re-mint's token is judged in its place.
  if (sps === 2 && media > 0) return;
  dumpFinished = true;
  if (sps !== 1) {
    const url = lastSabrUrl;
    const body = lastSabrBody;
    const pipe = ensureDump();
    if (pipe) {
      writeChain = writeChain
        .then(function () {
          return wait(pipe.sink.writeUtf8('sabr {"url":'));
        })
        .then(function () {
          return wait(pipe.sink.writeObjectAsJson(url));
        })
        .then(function () {
          return wait(pipe.sink.writeUtf8(',"body":"'));
        })
        .then(function () {
          if (body) return wait(pipe.sink.writeBytesAsBase64url(body));
        })
        .then(function () {
          return wait(pipe.sink.writeUtf8('"}'));
        })
        .then(function () {
          return wait(pipe.sink.putByte(10));
        })
        .then(
          function () {},
          function (e) {
            abandon(pipe, e, "sabr");
          },
        );
    }
  }
  lastSabrUrl = "";
  lastSabrBody = null;
  if (sps === 1) {
    finishDump();
  } else {
    // The report waits on the put, and a later mint or entropy read opens the next session's dump.
    const key = dumpEnabled ? sessionKey : "";
    finishDump(function () {
      return captureReject(sps, key);
    });
  }
}
