// Per-session remill file over cache/put. Each record is prefix + JSON + newline, fire-and-forget.
// keepCount(4) trims old session keys after close. Payloads are not kept in JS.

import { callHost } from "../../protocol/command";
import { keepCount, scopedCache } from "../../protocol/cache";
import { open } from "../../transport";
import { DESKTOP_UA } from "../yt/session";
import { onShutdown } from "../../lifecycle";

const SCOPE = "yt.bg.session";
const KEEP = 4;
let dump: Pipe | null = null;
let writeChain: Promise<void> = Promise.resolve();
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

function finishDump(after?: () => void): void {
  const pipe = dump;
  dump = null;
  if (!pipe) {
    if (after) after();
    return;
  }
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
      function () {
        if (after) after();
      },
      function (e) {
        try {
          pipe.source.close();
        } catch (_e) {}
        warn(e, "close");
        if (after) after();
      },
    );
}

// Player engine teardown: save what the session recorded instead of losing it to the hard close.
onShutdown(function () {
  return new Promise<void>(function (resolve) {
    finishDump(resolve);
  });
});

function captureReject(sps: number): void {
  const key = sessionKey;
  callHost("log/capture", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({
      "code": "E20088",
      "message": "PO rejected sps=" + sps,
      "scope": SCOPE,
      "key": key,
    });
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

export function recordBotguardEntropy(): void {
  if (typeof __bgEntropyDump !== "function") return;
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

export function noteSps(sps: number, media: number): void {
  if (media > 0 || sps === 1) withheldRounds = 0;
  else if (sps === 2) withheldRounds++;
  if (!(sps > 0)) return;
  if (loggedSps !== sps) {
    loggedSps = sps;
    console.info("bg sps=" + sps);
  }
  if (sps === 3 || withheldRounds > MAX_WITHHELD_ROUNDS) {
    if (sps === 2) console.warn("bg sps=2 withheld media for " + withheldRounds + " rounds");
    withheldRounds = 0;
    if (poRejectedHook) poRejectedHook();
  }
  if (dumpFinished) return;
  if (typeof __bgEntropyStop === "function") __bgEntropyStop();
  // 2 = attestation pending, not a verdict: googlevideo follows up with 1 (ok) or 3 (rejected).
  if (sps === 2) return;
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
  if (sps === 1) finishDump();
  else
    finishDump(function () {
      captureReject(sps);
    });
}
