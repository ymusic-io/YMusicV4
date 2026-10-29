// MSE — base.js talks to MediaSource / SourceBuffer / HTMLMediaElement as if this were Chrome.
// Append/play/seek hit C trampolines (`__mse*`). Quoted names so Closure ADVANCED leaves the
// DOM-shaped API intact for YouTube's player.

import { debug } from "../debug";
import { onEvent } from "../transport";
import { isAudioOnly } from "../logic/player/audio_only";
import { isYmLive } from "../logic/player/live";
import { viewportHeight, viewportWidth } from "../logic/player/viewport";

type Listener = (ev?: Record<string, unknown>) => void;

function mseStateLog(el: HTMLMediaElement, ev: string): void {
  console.info(
    "mse " +
      ev +
      " ct=" +
      Number(el["currentTime"]).toFixed(3) +
      " rs=" +
      el["readyState"] +
      " paused=" +
      (el["paused"] ? "1" : "0") +
      " ended=" +
      (el["ended"] ? "1" : "0") +
      " buf=" +
      fmtRanges(el["buffered"]) +
      " dur=" +
      Number(el["duration"]),
  );
}

function fmtRanges(
  b: { length: number; start(i: number): number; end(i: number): number } | null,
): string {
  if (!b || !b["length"]) return "-";
  const parts: string[] = [];
  for (let i = 0; i < b["length"]; i++) {
    parts.push(b["start"](i).toFixed(3) + ".." + b["end"](i).toFixed(3));
  }
  return parts.join(",");
}

type AppendDigest = { t: number; n: number; bytes: number; started: boolean };
const appendDigest: [AppendDigest, AppendDigest] = [
  { t: 0, n: 0, bytes: 0, started: false },
  { t: 0, n: 0, bytes: 0, started: false },
];

/** src= / load() destroy MseAv (v_rest=0). Put the decoder back to sleep. */
function resetPipeline(): void {
  __mseReset();
  appendDigest[0] = { t: 0, n: 0, bytes: 0, started: false };
  appendDigest[1] = { t: 0, n: 0, bytes: 0, started: false };
  if (isAudioOnly()) __mseRestVideo(1);
}

function audioFlag(mime: string): number {
  return String(mime).toLowerCase().indexOf("audio/") === 0 ? 1 : 0;
}

/** First codecs="..." token, lowercased. */
export function mimeCodec(mime: string): string {
  const m = String(mime).toLowerCase();
  const key = "codecs=";
  const i = m.indexOf(key);
  if (i < 0) return "";
  let s = m.slice(i + key.length);
  if (s.charAt(0) === '"') {
    const end = s.indexOf('"', 1);
    return end >= 0 ? s.slice(1, end) : s.slice(1);
  }
  const sp = s.search(/[\s;]/);
  return sp >= 0 ? s.slice(0, sp) : s;
}

/**
 * Chrome MSE rejects illegal vp09/av01 profiles. YouTube mdp() probes
 * vp09.02.51…09.16.09.00 vs …09.99.99.00; both true made we=0 and
 * rewrote VP9 mime to codecs="vp9", stripping .fake.
 */
function chromeRejectsCodec(mime: string): boolean {
  const c = mimeCodec(mime);
  if (!c) return false;
  if (c.indexOf("av99") === 0) return true;
  if (c.indexOf("vp09.") !== 0) return false;
  const p = c.split(".");
  const prof = p[1] || "";
  if (prof !== "00" && prof !== "01" && prof !== "02") return true;
  for (let i = 5; i <= 7 && i < p.length; i++) {
    if (p[i] === "99") return true;
  }
  return false;
}

/** The video codecs the host's decoder table knows; any other codec keeps the container check. */
const TABLE_VIDEO = /^(avc1|avc3|hev1|hvc1|vp8|vp08|vp9|vp09|av01)(\.|$)/;

let tableLive = false;

/** isTypeSupported / canPlayType consult the decoder table from now on. */
export function useDecodeTable(): void {
  tableLive = true;
}

/** mse_caps bits for a MediaCapabilities part (1 supported, 2 smooth, 4 powerEfficient); -1 when the
 *  host has no decoder table (older app, Android before its rows), -2 while it still probes. */
export function decodeCaps(
  mime: string,
  width: number,
  height: number,
  framerate: number,
  bitrate: number,
): number {
  if (typeof __mseDecodingInfo !== "function") return -1;
  return __mseDecodingInfo(mime, width, height, framerate, bitrate);
}

/** The decoder table has nothing that takes this video codec at any size. */
export function decodeTableRejects(mime: string): boolean {
  if (!tableLive || !TABLE_VIDEO.test(mimeCodec(mime))) return false;
  const bits = decodeCaps(mime, 0, 0, 0, 0);
  return bits >= 0 && (bits & 1) === 0;
}

let sbGen = 0;
let lastMs: MediaSource | null = null;

export function isLiveDuration(): boolean {
  if (isYmLive()) return true;
  const el = activeMedia;
  if (!el) return false;
  if (Number(el["duration"]) === Infinity) return true;
  const ms = el._ms;
  return !!(ms && Number(ms["duration"]) === Infinity);
}

function asU8(data: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (data instanceof Uint8Array) return data;
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  }
  return new Uint8Array(data);
}

const drainCalls = { audio: 0, video: 0, throw: 0, abort: 0, remove: 0, lisErr: 0 };

export function resetDrainCounts(): void {
  drainCalls.audio = 0;
  drainCalls.video = 0;
  drainCalls.throw = 0;
  drainCalls.abort = 0;
  drainCalls.remove = 0;
  drainCalls.lisErr = 0;
}

function logAppend(
  audio: number,
  bytes: number,
  mime: string,
  tsOff: number,
  winStart: number,
  winEnd: number,
): void {
  const d = appendDigest[audio]!;
  d.n++;
  d.bytes += bytes;
  let left = "-";
  let ranges: ReturnType<typeof nativeRanges> = null;
  try {
    const ct = __mseCurrentTime();
    ranges = nativeRanges(audio);
    left = leftoverMs(ranges, ct);
  } catch (_e) {
    left = "-";
  }
  debug(function () {
    return (
      "mse append a=" +
      audio +
      " bytes=" +
      bytes +
      " mime=" +
      mime +
      " tsOff=" +
      tsOff +
      " win=" +
      winStart +
      ".." +
      winEnd +
      " leftover=" +
      left +
      " br=[" +
      fmtBr(ranges) +
      "]"
    );
  });
  const leftN = left === "-" ? -1 : +left;
  const hole = left === "-" || !(leftN >= 1000);
  const now = Date.now();
  if (!d.started || hole || now - d.t >= 1000) {
    console.info("mse append a=" + audio + " n=" + d.n + " bytes=" + d.bytes + " leftover=" + left);
    d.t = now;
    d.n = 0;
    d.bytes = 0;
    d.started = true;
  }
}

function nativeRanges(audio: number): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} | null {
  const r = __mseBuffered(audio);
  if (!r || r.length < 2) return null;
  return {
    "length": r.length / 2,
    "start": function (i: number) {
      return r[i * 2]!;
    },
    "end": function (i: number) {
      return r[i * 2 + 1]!;
    },
  };
}

function fmtBr(
  br: { length: number; start(i: number): number; end(i: number): number } | null,
): string {
  if (!br) return "";
  const n = br["length"];
  const parts: string[] = [];
  for (let i = 0; i < n; i++) {
    const a = br["start"](i);
    const b = br["end"](i);
    parts.push(Math.round(a * 1000) + "+" + Math.round((b - a) * 1000));
  }
  return parts.join(",");
}

/** YouTube `DI(buffered, t)`: leftover ms in the range that contains t, else 0. */
function leftoverMs(
  br: { length: number; start(i: number): number; end(i: number): number } | null,
  t: number,
): string {
  if (!br) return "-";
  const n = br["length"];
  for (let i = 0; i < n; i++) {
    const a = br["start"](i);
    const b = br["end"](i);
    if (t >= a - 0.05 && t <= b + 0.05) return String(Math.round((b - t) * 1000));
  }
  return "0";
}

function lisN(sb: { _listeners: Record<string, Listener[]> }, type: string): number {
  const list = sb._listeners[type];
  return list ? list.length : 0;
}

function fire(
  target: { _listeners: Record<string, Listener[]>; mimeType?: string },
  type: string,
): void {
  const list = target._listeners[type];
  if (!list) return;
  if (isAudioOnly() && type === "updateend") {
    const mime = String((target as { ["mimeType"]?: string })["mimeType"] || "");
    debug(function () {
      return "mse evt updateend " + (audioFlag(mime) ? "a" : "v") + " lis=" + list.length;
    });
  }
  const ev: Record<string, unknown> = {
    "type": type,
    "target": target,
    "currentTarget": target,
    "bubbles": false,
    "cancelable": false,
    "defaultPrevented": false,
  };
  for (const fn of list.slice()) {
    try {
      fn.call(target, ev);
    } catch (e) {
      drainCalls.lisErr++;
      const err = e instanceof Error ? e : new Error(String(e));
      const st = err.stack ? " " + err.stack.replace(/\s+/g, " ").slice(0, 240) : "";
      console.warn("mse listener " + type + " err=" + err.name + " " + err.message + st);
    }
  }
}

/* Chrome: append/remove while updating is InvalidStateError (code 11). TypeError
   is not a DOMException; YouTube's TZP then returns 4 → reattachOnAppend and
   never drains the SABR append queue. */
function domException(name: string, msg: string, code: number): Error {
  const Ctor = (globalThis as unknown as { DOMException?: new (m: string, n: string) => Error })
    .DOMException;
  if (typeof Ctor === "function") {
    try {
      return new Ctor(msg, name);
    } catch (_e) {}
  }
  const err = new Error(msg);
  err.name = name;
  (err as unknown as { code: number }).code = code;
  return err;
}

function invalidState(msg: string): Error {
  return domException("InvalidStateError", msg, 11);
}

function quotaExceeded(): Error {
  return domException(
    "QuotaExceededError",
    "The SourceBuffer is full, and cannot free space to append additional buffers.",
    22,
  );
}

function notSupported(msg: string): Error {
  return domException("NotSupportedError", msg, 9);
}

function sbRemoved(sb: SourceBuffer): boolean {
  const p = sb._ms;
  return !p || p["sourceBuffers"].indexOf(sb) < 0;
}

function readU64LE(u: Uint8Array, off: number): number {
  let n = 0;
  let p = 1;
  for (let i = 0; i < 8; i++) {
    n += (u[off + i] || 0) * p;
    p *= 256;
  }
  return n;
}

function cBufferedPairs(audio: number): Array<[number, number]> {
  const r = __mseBuffered(audio);
  const ranges: Array<[number, number]> = [];
  if (r) {
    for (let i = 0; i + 1 < r.length; i += 2) {
      let a = r[i]!;
      const b = r[i + 1]!;
      /* miller DM5 Y=10ms; first opus packet is ~14ms. */
      if (a > 0 && a < 0.05) a = 0;
      ranges.push([a, b]);
    }
  }
  return ranges;
}

function mergeRanges(a: Array<[number, number]>, extra: [number, number]): Array<[number, number]> {
  const all = a.slice();
  all.push(extra);
  all.sort(function (x, y) {
    return x[0] - y[0];
  });
  const out: Array<[number, number]> = [];
  for (let i = 0; i < all.length; i++) {
    const r = all[i]!;
    const last = out.length ? out[out.length - 1] : null;
    if (!last || r[0] > last[1] + 0.001) out.push([r[0], r[1]]);
    else if (r[1] > last[1]) last[1] = r[1];
  }
  return out;
}

function clearFakeOverlay(sb: SourceBuffer): void {
  sb._fakeFollowAudio = false;
  sb._fakeStart = 0;
  sb._fakeEnd = 0;
}

function sbHasFakeOverlay(sb: SourceBuffer): boolean {
  return sb._fakeFollowAudio || sb._fakeEnd > sb._fakeStart;
}

/** AO-off: drop fake-144p TimeRanges so leftover is real video. SABR then fetches. */
export function dropVideoFakeOverlay(): void {
  const el = activeMedia;
  const ms = el && el._ms;
  if (!ms) return;
  const bufs = ms["sourceBuffers"];
  for (let i = 0; i < bufs.length; i++) {
    const sb = bufs[i]!;
    if (audioFlag(sb["mimeType"])) continue;
    if (!sbHasFakeOverlay(sb)) continue;
    console.info(
      "mse drop fake-cover follow=" +
        (sb._fakeFollowAudio ? "1" : "0") +
        " span=" +
        sb._fakeStart +
        ".." +
        sb._fakeEnd,
    );
    clearFakeOverlay(sb);
    if (sb["updating"]) continue;
    Promise.resolve().then(function () {
      if (sbRemoved(sb) || sb["updating"]) return;
      fire(sb, "update");
      fire(sb, "updateend");
      const media = activeMedia;
      if (media && media._ms === sb._ms) syncReadyState(media);
    });
  }
}

/** YMF0 init, or [hid+]YMF1 startMs/durationMs. Union with C leftover; do not replace. */
function takeFakeMedia(sb: SourceBuffer, u8: Uint8Array): void {
  if (isAudioOnly() && activeMedia && sb._ms === activeMedia._ms) __mseRestVideo(1);
  let p = u8;
  if (p.length >= 5 && p[1] === 0x59 && p[2] === 0x4d && p[3] === 0x46) p = p.subarray(1);
  if (p.length < 4 || p[0] !== 0x59 || p[1] !== 0x4d || p[2] !== 0x46) return;
  if (p[3] === 0x30) {
    console.info("mse append fake-init bytes=" + p.length);
    return;
  }
  if (p[3] === 0x31 && p.length >= 20) {
    const start = readU64LE(p, 4) / 1000;
    const dur = readU64LE(p, 12) / 1000;
    if (dur > 0) {
      sb._fakeFollowAudio = false;
      const end = start + dur;
      if (sb._fakeEnd > sb._fakeStart) {
        if (start < sb._fakeStart) sb._fakeStart = start;
        if (end > sb._fakeEnd) sb._fakeEnd = end;
      } else {
        sb._fakeStart = start;
        sb._fakeEnd = end;
      }
      debug(function () {
        return (
          "mse append fake-media " +
          start +
          ".." +
          end +
          " span=" +
          sb._fakeStart +
          ".." +
          sb._fakeEnd
        );
      });
    } else {
      sb._fakeFollowAudio = true;
      sb._fakeStart = 0;
      sb._fakeEnd = 0;
      console.info("mse append fake-media live follow-audio");
    }
  }
}

function playheadBuffered(el: HTMLMediaElement): boolean {
  const t = Number(el["currentTime"]);
  const br = el["buffered"];
  const n = br ? br["length"] : 0;
  for (let i = 0; i < n; i++) {
    if (br["start"](i) <= t + 0.35 && br["end"](i) >= t - 0.05) return true;
  }
  return false;
}

/* Chrome: playhead not in buffered → readyState drops, one waiting event.
   We used to leave HAVE_FUTURE_DATA and never fire waiting, so the player
   treated an empty live switch as still playable. */
function syncReadyState(el: HTMLMediaElement | null): void {
  if (!el) return;
  if (playheadBuffered(el)) {
    el._stalled = false;
    if (el["readyState"] < 3) {
      el["readyState"] = 3;
      fire(el, "canplay");
      if (!el["paused"]) fire(el, "playing");
    }
  } else if (!el["paused"] && el["readyState"] >= 2) {
    el["readyState"] = 1;
    if (!el._stalled) {
      el._stalled = true;
      mseStateLog(el, "waiting");
      fire(el, "waiting");
    }
  }
}

/* Chrome SourceBuffer appendBuffer / remove: updating is set synchronously, updatestart is a queued
   task, and the buffer append / range removal runs in a later task that ends with update +
   updateend. abort() or a detach cancels what has not run yet (the token moves on). */
function runUpdate(sb: SourceBuffer, work: () => void): void {
  const token = ++sb._updateToken;
  sb["updating"] = true;
  sb._startFired = false;
  setTimeout(function () {
    if (sb._updateToken !== token) return;
    sb._startFired = true;
    fire(sb, "updatestart");
    setTimeout(function () {
      if (sb._updateToken !== token) return;
      let failed = false;
      try {
        work();
      } catch (e) {
        failed = true;
        drainCalls.throw++;
        const err = e instanceof Error ? e : new Error(String(e));
        console.warn("mse update failed " + err.name + " " + err.message);
      }
      sb._updateToken++;
      sb["updating"] = false;
      fire(sb, failed ? "error" : "update");
      fire(sb, "updateend");
      const el = activeMedia;
      if (el && el._ms === sb._ms) syncReadyState(el);
    }, 0);
  }, 0);
}

function anyUpdating(ms: MediaSource): boolean {
  const bufs = ms["sourceBuffers"];
  for (let i = 0; i < bufs.length; i++) if (bufs[i]!["updating"]) return true;
  return false;
}

function isYmf(u8: Uint8Array): boolean {
  if (u8.length >= 5 && u8[1] === 0x59 && u8[2] === 0x4d && u8[3] === 0x46) return true;
  return u8.length >= 4 && u8[0] === 0x59 && u8[1] === 0x4d && u8[2] === 0x46;
}

let nextId = 1;

class SourceBuffer {
  ["mimeType"]: string;
  ["id"]: number;
  ["updating"]: boolean;
  ["timestampOffset"]: number;
  ["appendWindowStart"]: number;
  ["appendWindowEnd"]: number;
  _ms: MediaSource | null;
  _listeners: Record<string, Listener[]>;
  /* One TimeRanges object for the life of the SB. base.js HS(!0) caches
     this by reference; start/end must read current leftover. */
  _liveBuffered: { length: number; start(i: number): number; end(i: number): number } | null;
  _fakeStart: number;
  _fakeEnd: number;
  _fakeFollowAudio: boolean;
  _updateToken: number;
  _startFired: boolean;
  constructor(mime: string, ms: MediaSource | null) {
    this["mimeType"] = mime;
    this["id"] = nextId++;
    this["updating"] = false;
    this["timestampOffset"] = 0;
    this["appendWindowStart"] = 0;
    this["appendWindowEnd"] = Infinity;
    this._ms = ms;
    this._listeners = {};
    this._liveBuffered = null;
    this._fakeStart = 0;
    this._fakeEnd = 0;
    this._fakeFollowAudio = false;
    this._updateToken = 0;
    this._startFired = false;
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }

  get ["buffered"](): { length: number; start(i: number): number; end(i: number): number } {
    const self = this;
    if (!self._liveBuffered) {
      self._liveBuffered = liveTimeRanges(function () {
        return self._bufferedPairs();
      });
    }
    return self._liveBuffered;
  }

  _bufferedPairs(): Array<[number, number]> {
    const audio = audioFlag(this["mimeType"]);
    if (this._fakeFollowAudio) return cBufferedPairs(1);
    const real = cBufferedPairs(audio);
    if (this._fakeEnd > this._fakeStart) return mergeRanges(real, [this._fakeStart, this._fakeEnd]);
    return real;
  }

  ["changeType"](mime: string): void {
    /* Chrome SourceBuffer::ChangeType_Locked (WICG / MSE codec switching):
       empty type → TypeError; removed or updating → InvalidStateError;
       unsupported type → NotSupportedError; ended parent → open+sourceopen;
       reset parser state; pending initialization segment = true. */
    const next = String(mime || "");
    if (!next) throw new TypeError("The type provided is empty");
    const parent = this._ms;
    if (sbRemoved(this) || !parent) throw invalidState("SourceBuffer.changeType: removed");
    if (this["updating"]) throw invalidState("SourceBuffer.changeType: updating");
    if (!MediaSource["isTypeSupported"](next))
      throw notSupported("Changing to the type provided ('" + next + "') is not supported.");
    if (parent["readyState"] === "ended") {
      parent["readyState"] = "open";
      fire(parent, "sourceopen");
      fire(parent, "webkitsourceopen");
    }
    const prev = String(this["mimeType"] || "");
    const audio = audioFlag(next);
    console.info("mse changeType a=" + audio + " from=" + prev + " to=" + next);
    this["mimeType"] = next;
    __mseChangeType(audio);
  }

  ["appendBuffer"](data: ArrayBuffer | ArrayBufferView): void {
    if (sbRemoved(this)) throw invalidState("SourceBuffer.appendBuffer: removed");
    const audio = audioFlag(this["mimeType"]);
    if (this["updating"]) {
      drainCalls.throw++;
      console.warn("mse append throw updating a=" + audio);
      throw invalidState("SourceBuffer.appendBuffer: updating");
    }
    const u8 = asU8(data);
    /* Chrome prepareAppend: coded-frame eviction, then QuotaExceededError while
       updating is still false so YouTube can remove() immediately. */
    if (
      !isYmf(u8) &&
      typeof __msePrepareAppend === "function" &&
      !__msePrepareAppend(audio, u8.length)
    ) {
      drainCalls.throw++;
      console.warn("mse quota a=" + audio + " n=" + u8.length);
      throw quotaExceeded();
    }
    const self = this;
    const tsOff = self["timestampOffset"];
    const winStart = self["appendWindowStart"];
    const winEnd = self["appendWindowEnd"] === Infinity ? 1e300 : self["appendWindowEnd"];
    runUpdate(self, function () {
      if (isYmf(u8)) {
        takeFakeMedia(self, u8);
        return;
      }
      if (!audio) clearFakeOverlay(self);
      __mseAppend(audio, u8, tsOff, winStart, winEnd);
      /* Chrome appendBuffer does not move currentTime. The native clock
         holds while the playhead is outside the buffer, until the player seeks. */
      if (audio) drainCalls.audio++;
      else drainCalls.video++;
      logAppend(audio, u8.length, self["mimeType"], tsOff, winStart, winEnd);
    });
  }

  ["remove"](start: number, end: number): void {
    if (sbRemoved(this)) throw invalidState("SourceBuffer.remove: removed");
    if (this["updating"]) throw invalidState("SourceBuffer.remove: updating");
    const audio = audioFlag(this["mimeType"]);
    runUpdate(this, function () {
      __mseRemove(audio, start, end);
      drainCalls.remove++;
      console.info("mse remove a=" + audio + " " + start + ".." + end);
    });
  }

  ["abort"](): void {
    if (sbRemoved(this)) throw invalidState("SourceBuffer.abort: removed");
    const audio = audioFlag(this["mimeType"]);
    const wasUpdating = this["updating"];
    drainCalls.abort++;
    console.info("mse abort a=" + audio + " upd=" + (wasUpdating ? "1" : "0"));
    __mseAbort(audio);
    this._updateToken++;
    this["updating"] = false;
    /* MSE: abort/updateend only if an update was in progress. Firing updateend while
     * already in updateend (updating=false) re-enters YouTube's listener until the stack blows. */
    if (wasUpdating) {
      /* Chrome queued updatestart before the abort: it still comes first. */
      if (!this._startFired) fire(this, "updatestart");
      fire(this, "abort");
      fire(this, "updateend");
    }
    const el = activeMedia;
    if (el && el._ms === this._ms) maybeFireEnded(el);
  }
}

function liveTimeRanges(read: () => Array<[number, number]>): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} {
  return {
    get ["length"]() {
      return read().length;
    },
    "start": function (i: number) {
      return read()[i]![0];
    },
    "end": function (i: number) {
      return read()[i]![1];
    },
  };
}

function makeTimeRanges(ranges: Array<[number, number]>): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} {
  const snap = (ranges || []).slice();
  return liveTimeRanges(function () {
    return snap;
  });
}

function sbRanges(sb: SourceBuffer): Array<[number, number]> {
  const br = sb["buffered"];
  const out: Array<[number, number]> = [];
  for (let i = 0; i < br["length"]; i++) out.push([br["start"](i), br["end"](i)]);
  return out;
}

function intersectRanges(
  a: Array<[number, number]>,
  b: Array<[number, number]>,
): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (let i = 0; i < a.length; i++) {
    for (let j = 0; j < b.length; j++) {
      const lo = Math.max(a[i]![0], b[j]![0]);
      const hi = Math.min(a[i]![1], b[j]![1]);
      if (hi > lo) out.push([lo, hi]);
    }
  }
  return out;
}

class MediaSource {
  ["readyState"]: string;
  ["sourceBuffers"]: SourceBuffer[];
  _duration: number;
  _listeners: Record<string, Listener[]>;
  __id: number;
  _openFired: boolean;

  constructor() {
    this["readyState"] = "closed";
    this._duration = NaN;
    this["sourceBuffers"] = [];
    this._listeners = {};
    this.__id = nextId++;
    this._openFired = false;
    lastMs = this;
    console.info("mse MediaSource create");
  }

  get ["duration"](): number {
    return this._duration;
  }
  set ["duration"](v: number) {
    if (this["readyState"] === "open" && anyUpdating(this))
      throw invalidState("MediaSource.duration: a SourceBuffer is updating");
    this._duration = v;
    /* Infinity = live. A leftover finite head must not keep clamping
       currentTime (log23: clock stuck at 17253 after sourceopen). */
    if (v === Infinity || !isFinite(v) || v <= 0) __mseSetDuration(0);
    else __mseSetDuration(v);
  }

  static ["isTypeSupported"](mime: string): boolean {
    const m = String(mime).toLowerCase();
    if (chromeRejectsCodec(m)) {
      console.info("mse isTypeSupported reject codec " + mimeCodec(m));
      return false;
    }
    if (m.indexOf("av01") >= 0 || m.indexOf("av1.") >= 0) {
      if (typeof __mseAv1Ok === "function" && !__mseAv1Ok()) {
        console.info("mse isTypeSupported reject av01");
        return false;
      }
    }
    if (m.indexOf("vp9") >= 0 || m.indexOf("vp09") >= 0) {
      if (typeof __mseVp9Ok === "function" && !__mseVp9Ok()) {
        console.info("mse isTypeSupported reject vp9");
        return false;
      }
    }
    if (typeof __mseAvcMaxLevel === "function") {
      const avc = /avc1\.([0-9a-f]{6})/i.exec(m);
      const hex = avc ? avc[1] : "";
      if (hex && hex.length >= 6) {
        const lvl = parseInt(hex.slice(4, 6), 16);
        if (lvl > __mseAvcMaxLevel()) {
          console.info("mse isTypeSupported reject avc level " + lvl.toString(16));
          return false;
        }
      }
    }
    if (decodeTableRejects(m)) {
      console.info("mse isTypeSupported reject " + mimeCodec(m) + ": no decoder");
      return false;
    }
    const ok =
      m.indexOf("audio/webm") === 0 ||
      m.indexOf("video/webm") === 0 ||
      m.indexOf("audio/mp4") === 0 ||
      m.indexOf("video/mp4") === 0 ||
      m === "audio/mpeg";
    if (m.indexOf(".fake") >= 0)
      console.info("mse isTypeSupported fake " + (ok ? "ok" : "reject") + " " + mime);
    return ok;
  }

  ["addSourceBuffer"](mime: string): SourceBuffer {
    if (this["readyState"] !== "open") {
      console.warn("mse addSourceBuffer throw closed mime=" + mime);
      throw invalidState("MediaSource.addSourceBuffer: not open");
    }
    console.info("mse addSourceBuffer " + mime);
    sbGen++;
    const sb = new SourceBuffer(mime, this);
    this["sourceBuffers"].push(sb);
    return sb;
  }

  ["removeSourceBuffer"](sb: SourceBuffer): void {
    const i = this["sourceBuffers"].indexOf(sb);
    if (i < 0) throw invalidState("MediaSource.removeSourceBuffer: not in sourceBuffers");
    if (sb["updating"]) sb["abort"]();
    this["sourceBuffers"].splice(i, 1);
    sb._ms = null;
  }

  ["endOfStream"](): void {
    if (this["readyState"] !== "open") throw invalidState("MediaSource.endOfStream: not open");
    if (anyUpdating(this))
      throw invalidState("MediaSource.endOfStream: a SourceBuffer is updating");
    this["readyState"] = "ended";
    console.info("mse endOfStream");
    if (typeof __mseEndOfStream === "function") __mseEndOfStream();
    const el = activeMedia;
    if (el && el._ms === this) maybeFireEnded(el);
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
    /* src= assigned sourceopen before this listener (reload / audio-only). */
    if (
      type === "sourceopen" &&
      this["readyState"] === "open" &&
      this._openFired &&
      this["sourceBuffers"].length === 0
    ) {
      const self = this;
      Promise.resolve().then(function () {
        if (self["sourceBuffers"].length) return;
        fn.call(self, { "type": "sourceopen", "target": self });
      });
    }
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }
}

function detachMediaSource(ms: MediaSource): void {
  const bufs = ms["sourceBuffers"].slice();
  for (let i = 0; i < bufs.length; i++) {
    bufs[i]!._updateToken++;
    bufs[i]!["updating"] = false;
    bufs[i]!._ms = null;
  }
  ms["sourceBuffers"].length = 0;
  ms._duration = NaN;
  if (ms["readyState"] !== "closed") {
    ms["readyState"] = "closed";
    ms._openFired = false;
    fire(ms, "sourceclose");
    fire(ms, "webkitsourceclose");
  }
}

function notifySourceOpen(ms: MediaSource): void {
  if (ms["readyState"] === "closed") ms["readyState"] = "open";
  Promise.resolve().then(function () {
    if (ms["readyState"] !== "open" || ms._openFired) {
      console.info(
        "mse sourceopen skip rs=" +
          ms["readyState"] +
          " fired=" +
          (ms._openFired ? "1" : "0") +
          " n=" +
          ms["sourceBuffers"].length,
      );
      return;
    }
    ms._openFired = true;
    fire(ms, "sourceopen");
    fire(ms, "webkitsourceopen");
    console.info("mse sourceopen");
  });
}

const blobUrls: Record<string, MediaSource> = {};

let activeMedia: HTMLMediaElement | null = null;
let loopAtEnd: (() => void) | null = null;

/** What a looping element runs on reaching the end, once per pass. */
export function onLoopAtEnd(fn: (() => void) | null): void {
  loopAtEnd = fn;
}
let mediaTick: ReturnType<typeof setTimeout> | null = null;

function stopMediaTick(): void {
  if (mediaTick != null) {
    clearTimeout(mediaTick);
    mediaTick = null;
  }
}

function pipelineEnded(): boolean {
  if (typeof __mseEnded !== "function") return false;
  try {
    return !!__mseEnded();
  } catch (_e) {
    return false;
  }
}

function maybeFireEnded(el: HTMLMediaElement): boolean {
  if (el["paused"] || el["ended"] || el["seeking"]) return false;
  if (!pipelineEnded()) return false;
  // A looping element never ends. base.js restarts a loop with its own seekTo(0)
  // ("videoplayer_loop"); an element-side seek it did not ask for, it seeks back.
  if (el["loop"]) {
    if (!el._looped && loopAtEnd) {
      el._looped = true;
      console.info("mse loop clock=" + __mseCurrentTime() + " dur=" + Number(el["duration"]));
      loopAtEnd();
    }
    return false;
  }
  el["ended"] = true;
  el["paused"] = true;
  stopMediaTick();
  __msePause();
  console.info("mse ended clock=" + __mseCurrentTime() + " dur=" + Number(el["duration"]));
  fire(el, "timeupdate");
  fire(el, "ended");
  fire(el, "pause");
  return true;
}

onEvent("mse_ended", function () {
  const el = activeMedia;
  if (el) maybeFireEnded(el);
});

function startMediaTick(el: HTMLMediaElement): void {
  stopMediaTick();
  function pulse(): void {
    if (el["paused"] || el["ended"]) return;
    if (maybeFireEnded(el)) return;
    fire(el, "timeupdate");
    syncReadyState(el);
    mediaTick = setTimeout(pulse, 250);
  }
  mediaTick = setTimeout(pulse, 250);
}

function emptyTrackList(): Record<string, unknown> {
  const nop = function () {};
  const list: Record<string, unknown> = {
    "length": 0,
    "addEventListener": nop,
    "removeEventListener": nop,
    "addTextTrack": function () {
      return {};
    },
  };
  return list;
}

function initHtmlMedia(self: HTMLMediaElement): void {
  self["paused"] = true;
  self["ended"] = false;
  self["loop"] = false;
  self["seeking"] = false;
  self._stalled = false;
  self._looped = false;
  self["muted"] = false;
  self["volume"] = 1;
  self._rate = 1;
  self._tempo = 1;
  self._pitch = 1;
  self["defaultPlaybackRate"] = 1;
  self["readyState"] = 0;
  self["networkState"] = 0;
  self._src = "";
  self._ms = null;
  self._listeners = {};
  (self as unknown as Record<string, unknown>)["textTracks"] = emptyTrackList();
  (self as unknown as Record<string, unknown>)["audioTracks"] = emptyTrackList();
  (self as unknown as Record<string, unknown>)["videoTracks"] = emptyTrackList();
}

class HTMLMediaElement {
  ["paused"]!: boolean;
  ["ended"]!: boolean;
  ["loop"]!: boolean;
  ["seeking"]!: boolean;
  _stalled!: boolean;
  _looped!: boolean;
  ["muted"]!: boolean;
  ["volume"]!: number;
  _rate!: number;
  _tempo!: number;
  _pitch!: number;
  ["defaultPlaybackRate"]!: number;
  ["readyState"]!: number;
  ["networkState"]!: number;
  _src!: string;
  _ms!: MediaSource | null;
  _listeners!: Record<string, Listener[]>;

  constructor() {
    initHtmlMedia(this);
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }

  get ["videoWidth"](): number {
    return viewportWidth();
  }
  get ["videoHeight"](): number {
    return viewportHeight();
  }

  ["canPlayType"](t: string): string {
    t = String(t || "").toLowerCase();
    if (chromeRejectsCodec(t)) return "";
    if (
      (t.indexOf("av01") >= 0 || t.indexOf("av1.") >= 0) &&
      typeof __mseAv1Ok === "function" &&
      !__mseAv1Ok()
    )
      return "";
    if (
      (t.indexOf("vp9") >= 0 || t.indexOf("vp09") >= 0) &&
      typeof __mseVp9Ok === "function" &&
      !__mseVp9Ok()
    )
      return "";
    if (decodeTableRejects(t)) return "";
    return t.indexOf("webm") >= 0 || t.indexOf("mp4") >= 0 ? "probably" : "";
  }

  ["load"](): void {
    /* HTML media element load algorithm: stop, forget the resource, then
       resource-select src again. MSE detach closes the MediaSource. */
    const url = this._src;
    this["paused"] = true;
    this["ended"] = false;
    this["seeking"] = false;
    stopMediaTick();
    __msePause();
    if (this._ms) detachMediaSource(this._ms);
    resetPipeline();
    this._ms = null;
    this._src = "";
    this["readyState"] = 0;
    if (url) assignMediaSrc(this, url);
  }

  ["play"](): Promise<void> {
    const self = this;
    /* Chrome: play() after ended seeks to the start, then plays. */
    if (this["ended"]) this["currentTime"] = 0;
    this["paused"] = false;
    this["ended"] = false;
    __msePlay();
    startMediaTick(self);
    return Promise.resolve().then(function () {
      mseStateLog(self, "play");
      fire(self, "play");
      if (playheadBuffered(self)) {
        self["readyState"] = 3;
        self._stalled = false;
        mseStateLog(self, "playing");
        fire(self, "playing");
      } else {
        self["readyState"] = 1;
        self._stalled = true;
        mseStateLog(self, "waiting");
        fire(self, "waiting");
      }
    });
  }

  ["pause"](): void {
    this["paused"] = true;
    const self = this;
    stopMediaTick();
    __msePause();
    mseStateLog(self, "pause");
    Promise.resolve().then(function () {
      fire(self, "pause");
    });
  }

  get ["src"](): string {
    return this._src;
  }
  set ["src"](url: string) {
    const prev = this._ms;
    const ms = blobUrls[url] || null;
    this._src = url;
    console.info("mse video.src " + url + (ms ? " mse" : " not-mse"));
    if (prev !== ms) {
      /* Chrome: assigning a new media resource detaches the old MediaSource
         (readyState closed, sourceBuffers removed) and the pipeline demuxer
         is a new empty ChunkDemuxer. */
      if (prev) detachMediaSource(prev);
      resetPipeline();
      this._ms = ms;
      if (ms && ms["readyState"] === "closed") notifySourceOpen(ms);
    } else {
      this._ms = ms;
    }
    this["readyState"] = 1;
    const self = this;
    Promise.resolve().then(function () {
      fire(self, "loadstart");
      fire(self, "loadedmetadata");
      fire(self, "durationchange");
    });
  }

  get ["currentSrc"](): string {
    return this._src;
  }

  get ["duration"](): number {
    const ms = this._ms;
    if (ms && typeof ms["duration"] === "number") {
      if (ms["duration"] === Infinity) return Infinity;
      if (isFinite(ms["duration"]) && ms["duration"] > 0) return ms["duration"];
    }
    const d = __mseDuration();
    return d > 0 ? d : NaN;
  }
  set ["duration"](v: number) {
    if (this._ms) this._ms["duration"] = v;
    else if (v === Infinity || !isFinite(v) || v <= 0) __mseSetDuration(0);
    else __mseSetDuration(v);
  }

  get ["buffered"](): { length: number; start(i: number): number; end(i: number): number } {
    const ms = this._ms;
    if (!ms) return makeTimeRanges([]);
    const bufs = ms["sourceBuffers"];
    if (!bufs.length) return makeTimeRanges([]);
    let acc = sbRanges(bufs[0]!);
    for (let i = 1; i < bufs.length; i++) acc = intersectRanges(acc, sbRanges(bufs[i]!));
    return makeTimeRanges(acc);
  }

  get ["seekable"](): { length: number; start(i: number): number; end(i: number): number } {
    /* MSE: finite duration → [0, duration]; +Infinity / NaN → buffered. */
    const d = this["duration"];
    if (isFinite(d) && d > 0) return makeTimeRanges([[0, d]]);
    return this["buffered"];
  }

  get ["played"](): { length: number; start(i: number): number; end(i: number): number } {
    const t = Number(this["currentTime"]);
    if (isFinite(t) && t > 0) return makeTimeRanges([[0, t]]);
    return makeTimeRanges([]);
  }

  ["fastSeek"](t: number): void {
    this["currentTime"] = t;
  }

  get ["playbackRate"](): number {
    if (typeof __msePlaybackRate === "function") return __msePlaybackRate();
    return this._rate;
  }
  set ["playbackRate"](v: number) {
    let s = Number(v);
    if (!Number.isFinite(s) || s === 0) return;
    if (s < 0.25) s = 0.25;
    if (s > 4) s = 4;
    const prev = this._rate;
    const native = typeof __msePlaybackRate === "function" ? Number(__msePlaybackRate()) : prev;
    if (prev === s && native === s) return;
    this._rate = s;
    this._tempo = s;
    if (typeof __mseSetSpeed === "function") __mseSetSpeed(s, s);
    console.info("mse ratechange " + prev + "->" + s);
    fire(this, "ratechange");
  }

  ["setPlaybackRate"](v: number): void {
    this["playbackRate"] = v;
  }

  get ["pitch"](): number {
    return this._pitch > 0 ? this._pitch : 1;
  }
  set ["pitch"](v: number) {
    let p = Number(v);
    if (!Number.isFinite(p) || p === 0) return;
    if (p < 0.5) p = 0.5;
    if (p > 2) p = 2;
    const prev = this._pitch;
    if (prev === p) return;
    this._pitch = p;
    if (typeof __mseSetPitch === "function") __mseSetPitch(p);
    console.info("mse pitch " + prev + "->" + p);
  }

  get ["currentTime"](): number {
    /* Chrome WMPI::CurrentTime clamps to duration once the pipeline has ended. */
    if (this["ended"]) {
      const d = Number(this["duration"]);
      if (isFinite(d) && d > 0) return d;
    }
    return __mseCurrentTime();
  }
  set ["currentTime"](t: number) {
    this._looped = false;
    const raw = Number(t);
    t = raw;
    /* Chrome stores the assigned time. Do not rewrite a large finite value to 0
       when buffered is empty — that is what made the next live segment miss X_. */
    if (!isFinite(t)) return;
    /* Clamp to a finite duration. Do not snap to leftover — DVR -10s is often
       just outside buffered; SABR fills from the new playhead. */
    if (t < 0) t = 0;
    const d = this["duration"];
    if (isFinite(d) && t > d) t = d;
    const prev = __mseCurrentTime();
    this["seeking"] = true;
    this["ended"] = false;
    const self = this;
    fire(self, "seeking");
    console.info("mse currentTime set raw=" + raw + " t=" + t + " prev=" + prev);
    __mseSetCurrentTime(t);
    const t0 = Date.now();
    function finishSeek(): void {
      self["seeking"] = false;
      fire(self, "seeked");
    }
    function waitSeeked(): void {
      const br = self["buffered"];
      let ok = false;
      const n = br ? br["length"] : 0;
      for (let i = 0; i < n; i++) {
        if (br["start"](i) <= t + 0.35 && br["end"](i) >= t - 0.05) ok = true;
      }
      if (ok) {
        finishSeek();
        return;
      }
      /* Chrome: empty seekable aborts the seek (no seeked). Empty buffered
         with a non-empty seekable waits for data — do not fake seeked. */
      const sk = self["seekable"];
      const skN = sk ? sk["length"] : 0;
      if (skN === 0) {
        self["seeking"] = false;
        return;
      }
      if (Date.now() - t0 > 8000) {
        finishSeek();
        return;
      }
      setTimeout(waitSeeked, 50);
    }
    Promise.resolve().then(waitSeeked);
  }
}

function srcAccessor(): PropertyDescriptor | undefined {
  return Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "src");
}

/** YouTube video is a DOM stub, not `new HTMLMediaElement()`. removeAttribute('src')
 *  deletes the copied accessor; later `video.src = blob` becomes a data property. */
function bindMediaSrc(el: HTMLMediaElement): void {
  const own = Object.getOwnPropertyDescriptor(el, "src");
  if (own && typeof own.set === "function" && own.configurable === false) return;
  const proto = srcAccessor();
  if (!proto || typeof proto.get !== "function" || typeof proto.set !== "function") return;
  const get = proto.get;
  const set = proto.set;
  Object.defineProperty(el, "src", {
    get: get,
    set: set,
    enumerable: true,
    configurable: false,
  });
}

function assignMediaSrc(el: HTMLMediaElement, url: string): void {
  bindMediaSrc(el);
  el["src"] = url;
}

function attachHtmlMedia(el: object): void {
  const rec0 = el as Record<string, unknown>;
  if (rec0["_mseAttached"]) {
    setActiveMedia(el as HTMLMediaElement);
    return;
  }
  rec0["_mseAttached"] = 1;
  initHtmlMedia(el as HTMLMediaElement);
  setActiveMedia(el as HTMLMediaElement);
  const proto = HTMLMediaElement.prototype as unknown as Record<string, unknown>;
  const methods = [
    "play",
    "pause",
    "load",
    "canPlayType",
    "addEventListener",
    "removeEventListener",
    "fastSeek",
    "setPlaybackRate",
    "getVideoPlaybackQuality",
  ];
  for (let i = 0; i < methods.length; i++) {
    const n = methods[i]!;
    const fn = proto[n];
    if (typeof fn === "function") (el as Record<string, unknown>)[n] = fn;
  }
  const props = [
    "src",
    "currentSrc",
    "currentTime",
    "duration",
    "buffered",
    "seekable",
    "played",
    "videoWidth",
    "videoHeight",
    "playbackRate",
    "defaultPlaybackRate",
    "paused",
    "ended",
    "seeking",
    "muted",
    "volume",
    "readyState",
    "loop",
    "pitch",
    "webkitDecodedFrameCount",
    "webkitDroppedFrameCount",
  ];
  for (let i = 0; i < props.length; i++) {
    const n = props[i]!;
    const d = Object.getOwnPropertyDescriptor(proto, n);
    if (d) Object.defineProperty(el, n, d);
  }
  bindMediaSrc(el as HTMLMediaElement);
  const rec = el as Record<string, unknown>;
  const protoSet = rec["setAttribute"];
  rec["setAttribute"] = function (n: string, v: unknown) {
    if (String(n).toLowerCase() === "src") {
      assignMediaSrc(el as HTMLMediaElement, String(v == null ? "" : v));
      return;
    }
    if (typeof protoSet === "function")
      (protoSet as (n: string, v: unknown) => void).call(el, n, v);
    else rec[n] = v;
  };
  const protoRemove = rec["removeAttribute"];
  rec["removeAttribute"] = function (n: string) {
    if (String(n).toLowerCase() === "src") {
      assignMediaSrc(el as HTMLMediaElement, "");
      return;
    }
    if (typeof protoRemove === "function") (protoRemove as (n: string) => void).call(el, n);
    else delete rec[n];
  };
}

export function mseCreateObjectURL(obj: MediaSource | { __id?: number } | null): string {
  const rec = obj as MediaSource;
  if (rec && rec.__id == null) rec.__id = nextId++;
  const url = "blob:mse/" + String(rec && rec.__id);
  if (rec) blobUrls[url] = rec;
  console.info("mse createObjectURL " + url);
  const el = activeMedia;
  /* First load: YouTube may skip video.src. Do not steal a playing
     element — that would detach leftover. Unattached MS stays closed. */
  if (el && rec && rec !== el._ms && !(el._ms && el._ms["sourceBuffers"].length > 0)) {
    assignMediaSrc(el, url);
  }
  return url;
}

export function mseSourceBufferCount(): number {
  const el = activeMedia;
  return el && el._ms ? el._ms["sourceBuffers"].length : 0;
}

export function mseMediaSourceId(): number {
  const el = activeMedia;
  return el && el._ms ? el._ms.__id : -1;
}

export function mseSourceBufferGen(): number {
  return sbGen;
}

/** After loadVideoById: attach the newest MediaSource if YouTube left the old one playing. */
export function mseAttachLatestIfStale(prevId: number): boolean {
  const el = activeMedia;
  if (!el || !lastMs) return false;
  if (lastMs.__id === prevId) return false;
  if (el._ms === lastMs) return false;
  const url = "blob:mse/" + String(lastMs.__id);
  blobUrls[url] = lastMs;
  console.info("mse attach latest " + url + " prev=" + prevId);
  assignMediaSrc(el, url);
  return true;
}

export function mseRevokeObjectURL(url: string): void {
  delete blobUrls[String(url)];
}

const URLImpl = {
  "createObjectURL": mseCreateObjectURL,
  "revokeObjectURL": mseRevokeObjectURL,
};

/** Keep MediaSource + blob URLs on the global. IDL may replace URL; put createObjectURL back. */
export function installMseGlobals(): void {
  const glob = globalThis as unknown as Record<string, unknown>;
  glob["MediaSource"] = MediaSource;
  glob["SourceBuffer"] = SourceBuffer;
  glob["HTMLMediaElement"] = HTMLMediaElement;
  glob["HTMLVideoElement"] = HTMLMediaElement;
  glob["HTMLAudioElement"] = HTMLMediaElement;
  (HTMLMediaElement as unknown as Record<string, unknown>)["attach"] = attachHtmlMedia;
  (HTMLMediaElement as unknown as Record<string, unknown>)["main"] = null;
  const Url = glob["URL"];
  if (typeof Url === "function") {
    const rec = Url as unknown as Record<string, unknown>;
    rec["createObjectURL"] = mseCreateObjectURL;
    rec["revokeObjectURL"] = mseRevokeObjectURL;
  } else {
    glob["URL"] = URLImpl;
  }
}

/** MediaSource.isTypeSupported as base.js sees it. */
export function mseTypeSupported(mime: string): boolean {
  return MediaSource["isTypeSupported"](mime);
}

function asNative<T extends Function>(name: string, impl: T, length: number): T {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl, length) as unknown as T;
  return impl;
}

/** [totalVideoFrames, droppedVideoFrames] since the last load. */
function frameCounts(): [number, number] {
  const q = __mseVideoQuality();
  return [Number(q[0]) || 0, Number(q[1]) || 0];
}

let qualityInstalled = false;

/** getVideoPlaybackQuality, and Chrome's webkitDecodedFrameCount / webkitDroppedFrameCount that
 *  base.js falls back to (its video is a DOM stub, not instanceof HTMLVideoElement), from the host's
 *  frame counts. Elements attached afterwards get them. */
export function installPlaybackQuality(): void {
  if (qualityInstalled || typeof __mseVideoQuality !== "function") return;
  qualityInstalled = true;
  const proto = HTMLMediaElement.prototype as unknown as Record<string, unknown>;
  proto["getVideoPlaybackQuality"] = asNative(
    "getVideoPlaybackQuality",
    function () {
      const q = frameCounts();
      const g = globalThis as unknown as Record<string, unknown>;
      const ctor = g["VideoPlaybackQuality"] as Record<string, unknown> | undefined;
      const qp = ctor ? ctor["prototype"] : null;
      const perf = g["performance"] as Record<string, unknown> | undefined;
      const now = perf ? perf["now"] : undefined;
      const o: Record<string, unknown> =
        qp != null && typeof qp === "object" ? Object.create(qp) : {};
      o["creationTime"] = typeof now === "function" ? (now as () => number).call(perf) : Date.now();
      o["totalVideoFrames"] = q[0];
      o["droppedVideoFrames"] = q[1];
      o["corruptedVideoFrames"] = 0;
      return o;
    },
    0,
  );
  Object.defineProperty(proto, "webkitDecodedFrameCount", {
    get: asNative(
      "get webkitDecodedFrameCount",
      function () {
        return frameCounts()[0];
      },
      0,
    ),
    enumerable: true,
    configurable: true,
  });
  Object.defineProperty(proto, "webkitDroppedFrameCount", {
    get: asNative(
      "get webkitDroppedFrameCount",
      function () {
        return frameCounts()[1];
      },
      0,
    ),
    enumerable: true,
    configurable: true,
  });
}

export function activeHtmlMedia(): HTMLMediaElement | null {
  return activeMedia;
}

let activeMediaListener: (() => void) | null = null;

export function onActiveHtmlMedia(fn: (() => void) | null): void {
  activeMediaListener = fn;
}

function setActiveMedia(el: HTMLMediaElement): void {
  if (activeMedia === el) return;
  activeMedia = el;
  if (activeMediaListener) activeMediaListener();
}

/** SoundTouch pair: playbackRate (currentTime) + tempo (PCM stretch). */
export function setHtmlPlaybackRate(speed: number, tempo: number): void {
  const el = activeMedia;
  const prev = el ? el._rate : speed;
  if (el) {
    el._tempo = tempo;
    el._rate = speed;
  }
  if (typeof __mseSetSpeed === "function") __mseSetSpeed(speed, tempo);
  if (el && prev !== speed) fire(el, "ratechange");
}

/** MSE-only drain line. Do not walk base.js internals. */
export function dumpDrain(why: string): void {
  const quality = why.indexOf("setQuality") >= 0;
  if (!quality && !isAudioOnly()) return;
  if (quality) {
    try {
      console.info("mse drain " + why + " " + dumpMseDrain());
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e));
      console.info("mse drain " + why + " err=" + err.message);
    }
    return;
  }
  debug(function () {
    try {
      return "mse drain " + why + " " + dumpMseDrain();
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e));
      return "mse drain " + why + " err=" + err.message;
    }
  });
}

/** Protocol drain snapshot: audio/video append, updating, je, listeners. */
export function dumpMseDrain(): string {
  const el = activeMedia;
  const ct = el ? __mseCurrentTime() : 0;
  let mediaBuf = "";
  let mediaJe = "-";
  let paused = "-";
  let rs = "-";
  let seeking = "-";
  if (el) {
    try {
      const b = el["buffered"];
      mediaBuf = fmtBr(b);
      mediaJe = leftoverMs(b, ct);
    } catch (_e) {
      mediaBuf = "err";
    }
    paused = el["paused"] ? "1" : "0";
    rs = String(el["readyState"]);
    seeking = el["seeking"] ? "1" : "0";
  }
  const parts: string[] = [
    "ct=" + ct.toFixed(3),
    "paused=" + paused,
    "rs=" + rs,
    "seek=" + seeking,
    "media.buf=[" + mediaBuf + "]",
    "media.je=" + mediaJe,
  ];
  const ms = el && el._ms;
  const bufs = ms ? ms["sourceBuffers"] : [];
  for (let i = 0; i < bufs.length; i++) {
    const sb = bufs[i]!;
    const audio = audioFlag(sb["mimeType"]);
    let buf = "";
    let je = "-";
    try {
      const b = sb["buffered"];
      buf = fmtBr(b);
      je = leftoverMs(b, ct);
    } catch (_e) {
      buf = "err";
    }
    parts.push(
      (audio ? "a" : "v") +
        "{upd=" +
        (sb["updating"] ? "1" : "0") +
        " mime=" +
        sb["mimeType"] +
        " buf=[" +
        buf +
        "] je=" +
        je +
        " ue=" +
        lisN(sb, "updateend") +
        "}",
    );
  }
  parts.push(
    "calls a=" +
      drainCalls.audio +
      "/v=" +
      drainCalls.video +
      " throw=" +
      drainCalls.throw +
      " abort=" +
      drainCalls.abort +
      " rm=" +
      drainCalls.remove +
      " lisErr=" +
      drainCalls.lisErr,
  );
  return parts.join(" ");
}

/** Drop the current MediaSource attachment and native decoder so the next video can start clean. */
export async function resetMse(): Promise<void> {
  const v = activeMedia;
  if (v) {
    v["paused"] = true;
    if (v._ms) detachMediaSource(v._ms);
    v._src = "";
    v._ms = null;
    v["readyState"] = 0;
    stopMediaTick();
    v["seeking"] = false;
    v["ended"] = false;
  }
  resetPipeline();
}

installMseGlobals();
