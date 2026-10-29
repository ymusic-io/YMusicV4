// navigator.mediaCapabilities from the host's decoder table (`:mse` mse_caps.c). Installed after the
// BotGuard snapshot: the snapshot sees the same Chrome profile on every host, base.js the device.

import {
  decodeCaps,
  decodeTableRejects,
  mimeCodec,
  mseTypeSupported,
  useDecodeTable,
} from "../../protocol/mse";

const SUPPORTED = 1;
const SMOOTH = 2;
const EFFICIENT = 4;
const NO_TABLE = -1;
const PROBING = -2;
const PROBE_WAIT_MS = 5000;
const RETRY_MS = 50;

type Part = {
  contentType: string;
  width: number;
  height: number;
  framerate: number;
  bitrate: number;
  video: boolean;
};

function asRec(v: unknown): Record<string, unknown> | null {
  return v != null && typeof v === "object" ? (v as Record<string, unknown>) : null;
}

function positive(v: unknown): number {
  const n = Number(v);
  return isFinite(n) && n > 0 ? n : 0;
}

function partOf(rec: Record<string, unknown>, video: boolean): Part {
  const ct = rec["contentType"];
  return {
    contentType: ct == null ? "" : String(ct),
    width: Math.round(positive(rec["width"])),
    height: Math.round(positive(rec["height"])),
    framerate: positive(rec["framerate"]),
    bitrate: positive(rec["bitrate"]),
    video: video,
  };
}

function globalProto(name: string): Record<string, unknown> | null {
  const ctor = (globalThis as unknown as Record<string, unknown>)[name];
  if (typeof ctor !== "function") return null;
  return asRec((ctor as unknown as Record<string, unknown>)["prototype"]);
}

function asNative<T extends Function>(name: string, impl: T, length: number): T {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl, length) as unknown as T;
  return impl;
}

const logged: Record<string, boolean> = {};

function logVerdict(p: Part, bits: number, why: string): void {
  const line =
    "mse caps " +
    (mimeCodec(p.contentType) || p.contentType) +
    (p.video ? " " + p.width + "x" + p.height + "@" + p.framerate : "") +
    " -> supported=" +
    (bits & SUPPORTED ? 1 : 0) +
    " smooth=" +
    (bits & SMOOTH ? 1 : 0) +
    " powerEfficient=" +
    (bits & EFFICIENT ? 1 : 0) +
    why;
  if (logged[line]) return;
  logged[line] = true;
  console.info(line);
}

/** Set by the first answer still probing: past it, nobody waits for the table any more. */
let probeDeadline = 0;

/** Bits for one part, or PROBING while the table is built and the wait is not over. */
function partBits(p: Part): number {
  if (!mseTypeSupported(p.contentType)) {
    logVerdict(p, 0, "");
    return 0;
  }
  const bits = p.video
    ? decodeCaps(p.contentType, p.width, p.height, p.framerate, p.bitrate)
    : decodeCaps(p.contentType, 0, 0, 0, p.bitrate);
  if (bits >= 0) {
    logVerdict(p, bits, "");
    return bits;
  }
  if (bits === PROBING) {
    if (!probeDeadline) probeDeadline = Date.now() + PROBE_WAIT_MS;
    if (Date.now() < probeDeadline) return PROBING;
  }
  const fallback = SUPPORTED | SMOOTH;
  logVerdict(p, fallback, bits === NO_TABLE ? " (no decoder table)" : " (decoder table not ready)");
  return fallback;
}

function decodingInfo(config: unknown): Promise<Record<string, unknown>> {
  const cfg = asRec(config);
  if (!cfg)
    return Promise.reject(
      new TypeError(
        "Failed to execute 'decodingInfo' on 'MediaCapabilities': 1 argument required, but only 0 present.",
      ),
    );
  const v = asRec(cfg["video"]);
  const a = asRec(cfg["audio"]);
  if (!v && !a)
    return Promise.reject(
      new TypeError(
        "Failed to execute 'decodingInfo' on 'MediaCapabilities': The configuration dictionary has neither 'video' nor 'audio' specified.",
      ),
    );
  const parts: Part[] = [];
  if (v) parts.push(partOf(v, true));
  if (a) parts.push(partOf(a, false));
  return new Promise(function (resolve) {
    function settle(): void {
      let bits = SUPPORTED | SMOOTH | EFFICIENT;
      for (let i = 0; i < parts.length; i++) {
        const b = partBits(parts[i]!);
        if (b === PROBING) {
          setTimeout(settle, RETRY_MS);
          return;
        }
        bits &= b;
      }
      resolve({
        "supported": (bits & SUPPORTED) !== 0,
        "smooth": (bits & SMOOTH) !== 0,
        "powerEfficient": (bits & EFFICIENT) !== 0,
        "keySystemAccess": null,
        "configuration": config,
      });
    }
    settle();
  });
}

function encodingInfo(config: unknown): Promise<Record<string, unknown>> {
  if (!asRec(config))
    return Promise.reject(
      new TypeError(
        "Failed to execute 'encodingInfo' on 'MediaCapabilities': 1 argument required, but only 0 present.",
      ),
    );
  return Promise.resolve({
    "supported": false,
    "smooth": false,
    "powerEfficient": false,
    "configuration": config,
  });
}

let installed = false;

/** navigator.mediaCapabilities, and the decoder table behind isTypeSupported / canPlayType, when
 *  the host has a table; otherwise everything stays as on a host without one. */
export function installMediaCapabilities(): void {
  if (installed) return;
  if (decodeCaps('video/mp4; codecs="avc1.42e01e"', 0, 0, 0, 0) === NO_TABLE) {
    console.info("mse caps: no decoder table on this host, no navigator.mediaCapabilities");
    return;
  }
  installed = true;
  useDecodeTable();
  const nav = asRec((globalThis as unknown as Record<string, unknown>)["navigator"]);
  const proto = globalProto("MediaCapabilities");
  if (!nav || !proto) {
    console.warn("mse caps: no navigator or MediaCapabilities to install on");
    return;
  }
  proto["decodingInfo"] = asNative("decodingInfo", decodingInfo, 1);
  proto["encodingInfo"] = asNative("encodingInfo", encodingInfo, 1);
  nav["mediaCapabilities"] = Object.create(proto);
  const mproto = globalProto("HTMLMediaElement");
  const envCanPlay = mproto ? mproto["canPlayType"] : undefined;
  if (mproto && typeof envCanPlay === "function") {
    mproto["canPlayType"] = asNative(
      "canPlayType",
      function (this: unknown, type: unknown): unknown {
        if (decodeTableRejects(type == null ? "" : String(type))) return "";
        return (envCanPlay as (t: unknown) => unknown).call(this, type);
      },
      1,
    );
  }
}
