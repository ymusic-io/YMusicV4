// SABR /videoplayback debug log (`debug()` = DEBUG; proto parse lives in the thunk).
// PO (query pot= + StreamerContext 19.2).
// clientAbrState: 16 stickyRes (yt-player-quality px), 18/19 viewport, 23 bandwidth,
// 21 bitrate cap, 13/14 last manual quality, 38 mediaCapabilities, 40 tracks, 72 quality policy.
// bufferedRanges: 1 formatId.itag, 2 startMs, 3 durationMs.
// selected_format_ids=2, selected_audio_format_ids=16, selected_video_format_ids=17.

import { debug, debugRun } from "../../debug";
import { isAudioOnly } from "../player/audio_only";
import { noteSps, recordSabrForDump } from "../player/bg_dump";
import { dumpDrain } from "../../protocol/mse";

function asU8(body: unknown): Uint8Array | null {
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (ArrayBuffer.isView(body))
    return new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  return null;
}

function readVarint(buf: Uint8Array, i: number): { v: number; i: number } | null {
  let v = 0;
  let s = 0;
  while (i < buf.length) {
    const b = buf[i++]!;
    v += (b & 0x7f) * Math.pow(2, s);
    if ((b & 0x80) === 0) return { v: v, i: i };
    s += 7;
    if (s > 56) return null;
  }
  return null;
}

function protoBytes(buf: Uint8Array, want: number): Uint8Array | null {
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) return null;
      if (field === want) return buf.subarray(i, i + len);
      i += len;
    } else return null;
  }
  return null;
}

function protoVarint(buf: Uint8Array, want: number): number | null {
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      if (field === want) return n.v;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) return null;
      i = n.i;
      i += n.v;
      if (i > buf.length) return null;
    } else return null;
  }
  return null;
}

function protoString(buf: Uint8Array, want: number): string | null {
  const b = protoBytes(buf, want);
  if (!b) return null;
  let s = "";
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]!);
  return s;
}

function looksAscii(buf: Uint8Array): boolean {
  if (buf.length === 0) return false;
  for (let i = 0; i < buf.length; i++) {
    const c = buf[i]!;
    if (c < 32 || c > 126) return false;
  }
  return true;
}

function asciiOf(buf: Uint8Array): string {
  let s = "";
  for (let i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]!);
  return s;
}

function fmtFormatId(p: Uint8Array): string {
  const itag = protoVarint(p, 1);
  const lmt = protoVarint(p, 2);
  const xtag = protoString(p, 3);
  let s = "itag=" + (itag != null ? itag : "-");
  if (lmt != null) s += " lmt=" + lmt;
  if (xtag) s += " xtag=" + xtag;
  return s;
}

/** Compact protobuf field dump. Nested FormatId on 2/13, TimeRange on 15. */
function fmtProto(buf: Uint8Array): string {
  const parts: string[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) break;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      parts.push(field + "=" + n.v);
    } else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) break;
      const inner = buf.subarray(i, i + len);
      i += len;
      if (field === 13) parts.push("13{" + fmtFormatId(inner) + "}");
      else if (field === 15) parts.push("tr{" + fmtProto(inner) + "}");
      else if (looksAscii(inner) && inner.length < 80)
        parts.push(field + '="' + asciiOf(inner) + '"');
      else parts.push(field + ":len=" + len);
    } else if (wire === 1) {
      i += 8;
      parts.push(field + ":i64");
    } else if (wire === 5) {
      i += 4;
      parts.push(field + ":i32");
    } else break;
  }
  return parts.join(" ");
}

function fmtFormatInit(p: Uint8Array): string {
  const vid = protoString(p, 1);
  const fid = protoBytes(p, 2);
  let s = "FORMAT_INIT";
  if (vid) s += " vid=" + vid;
  if (fid) s += " " + fmtFormatId(fid);
  const rest = fmtProto(p);
  if (rest) s += " [" + rest + "]";
  return s;
}

function fmtMediaHeader(p: Uint8Array): string {
  const hid = protoVarint(p, 1);
  const vid = protoString(p, 2);
  const itag = protoVarint(p, 3);
  const lmt = protoVarint(p, 4);
  const range = protoVarint(p, 6);
  const init = protoVarint(p, 8);
  const seq = protoVarint(p, 9);
  const t = protoVarint(p, 11);
  const dur = protoVarint(p, 12);
  const fid = protoBytes(p, 13);
  const clen = protoVarint(p, 14);
  const tr = protoBytes(p, 15);
  let s = "MEDIA_HEADER hid=" + (hid != null ? hid : "-");
  if (itag != null) s += " itag=" + itag;
  else if (fid) s += " " + fmtFormatId(fid);
  s += " init=" + (init === 1 ? "1" : "0");
  if (seq != null) s += " seq=" + seq;
  if (t != null) s += " startMs=" + t;
  if (dur != null) s += " durMs=" + dur;
  if (range != null) s += " range=" + range;
  if (clen != null) s += " clen=" + clen;
  if (lmt != null) s += " lmt=" + lmt;
  if (vid) s += " vid=" + vid;
  if (tr) s += " tr{" + fmtProto(tr) + "}";
  const rest = fmtProto(p);
  if (rest) s += " [" + rest + "]";
  return s;
}

function protoRepeatedVarint(buf: Uint8Array, want: number): number[] {
  const out: number[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) break;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      if (field === want) out.push(n.v);
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      i += n.v;
      if (i > buf.length) break;
    } else break;
  }
  return out;
}

function dash(n: number | null): string {
  return n != null ? String(n) : "-";
}

/** ClientAbrState fields the server uses to pick itag (sticky quality, viewport, bw, caps). */
function fmtClientAbrState(state: Uint8Array): string {
  const qg = protoBytes(state, 72);
  let qpol = "-";
  if (qg) {
    qpol =
      "def=" +
      dash(protoVarint(qg, 1)) +
      " sm=" +
      dash(protoVarint(qg, 2)) +
      " vis=" +
      dash(protoVarint(qg, 3)) +
      " gr=" +
      dash(protoVarint(qg, 4)) +
      " perf=" +
      dash(protoVarint(qg, 5)) +
      " spd=" +
      dash(protoVarint(qg, 6));
  }
  const mc = protoBytes(state, 38);
  let deny = "-";
  let vcap = "-";
  if (mc) {
    const attrs = protoBytes(mc, 4);
    if (attrs) {
      const ds = protoRepeatedVarint(attrs, 1);
      if (ds.length) deny = ds.join(",");
    }
    const vids = protoRepeated(mc, 1);
    const bits: string[] = [];
    for (let i = 0; i < vids.length && i < 4; i++) {
      const m = vids[i]!;
      bits.push(
        dash(protoVarint(m, 4)) +
          "x" +
          dash(protoVarint(m, 3)) +
          "@" +
          dash(protoVarint(m, 11)) +
          "b=" +
          dash(protoVarint(m, 12)),
      );
    }
    if (bits.length) vcap = bits.join(";");
  }
  return (
    "sabr clientAbrState sticky=" +
    dash(protoVarint(state, 16)) +
    " vw=" +
    dash(protoVarint(state, 18)) +
    " vh=" +
    dash(protoVarint(state, 19)) +
    " bw=" +
    dash(protoVarint(state, 23)) +
    " cap=" +
    dash(protoVarint(state, 21)) +
    " lastMs=" +
    dash(protoVarint(state, 13)) +
    " lastDir=" +
    dash(protoVarint(state, 14)) +
    " vis=" +
    dash(protoVarint(state, 34)) +
    " net=" +
    dash(protoVarint(state, 17)) +
    " tracks=" +
    dash(protoVarint(state, 40)) +
    " audQ=" +
    dash(protoVarint(state, 73)) +
    " qpol={" +
    qpol +
    "} deny=" +
    deny +
    " vcap=[" +
    vcap +
    "]"
  );
}

function protoRepeated(buf: Uint8Array, want: number): Uint8Array[] {
  const out: Uint8Array[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) break;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
    } else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint(buf, i);
      if (!n) break;
      i = n.i;
      const len = n.v;
      if (len < 0 || i + len > buf.length) break;
      if (field === want) out.push(buf.subarray(i, i + len));
      i += len;
    } else break;
  }
  return out;
}

function clockMs(): string {
  if (typeof __mseCurrentTime !== "function") return "-";
  try {
    return String(Math.round(__mseCurrentTime() * 1000));
  } catch (_e) {
    return "err";
  }
}

/** Flatten __mseBuffered pairs to the same startMs+durationMs shape as SABR. */
function msePairs(audio: number): string {
  if (typeof __mseBuffered !== "function") return "";
  try {
    const r = __mseBuffered(audio);
    if (!r || r.length < 2) return "";
    const parts: string[] = [];
    for (let i = 0; i + 1 < r.length; i += 2) {
      const a = r[i]!;
      const b = r[i + 1]!;
      parts.push(Math.round(a * 1000) + "+" + Math.round((b - a) * 1000));
    }
    return parts.join(",");
  } catch (_e) {
    return "err";
  }
}

function durMs(): string {
  if (typeof __mseDuration !== "function") return "-";
  try {
    return String(Math.round(__mseDuration() * 1000));
  } catch (_e) {
    return "err";
  }
}

export function b64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** UMP varint — not protobuf. Prefix byte selects width (yt-media Ump.kt). */
function readUmpVarint(buf: Uint8Array, i: number): { v: number; i: number } | null {
  if (i >= buf.length) return null;
  const prefix = buf[i++]!;
  if (prefix < 0x80) return { v: prefix, i: i };
  if (prefix < 0xc0) {
    if (i >= buf.length) return null;
    return { v: (prefix & 0x3f) + 64 * buf[i]!, i: i + 1 };
  }
  if (prefix < 0xe0) {
    if (i + 1 >= buf.length) return null;
    return { v: (prefix & 0x1f) + 32 * (buf[i]! + 256 * buf[i + 1]!), i: i + 2 };
  }
  if (prefix < 0xf0) {
    if (i + 2 >= buf.length) return null;
    return {
      v: (prefix & 0x0f) + 16 * (buf[i]! + 256 * (buf[i + 1]! + 256 * buf[i + 2]!)),
      i: i + 3,
    };
  }
  if (i + 3 >= buf.length) return null;
  return {
    v: buf[i]! + 256 * (buf[i + 1]! + 256 * (buf[i + 2]! + 256 * buf[i + 3]!)),
    i: i + 4,
  };
}

const UMP_NAME: Record<number, string> = {
  20: "MEDIA_HEADER",
  21: "MEDIA_DATA",
  22: "MEDIA_END",
  31: "LIVE_METADATA",
  35: "NEXT_REQUEST_POLICY",
  42: "FORMAT_INIT",
  43: "SABR_REDIRECT",
  44: "SABR_ERROR",
  57: "SABR_CONTEXT_UPDATE",
  58: "STREAM_PROTECTION_STATUS",
};

function spsLabel(v: number): string {
  if (v === 1) return "ok";
  if (v === 2) return "pending";
  if (v === 3) return "po-invalid";
  return "?";
}

function catU8(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (!a.length) return b;
  if (!b.length) return a;
  const o = new Uint8Array(a.length + b.length);
  o.set(a, 0);
  o.set(b, a.length);
  return o;
}

function joinU8(parts: Uint8Array[], n: number): Uint8Array {
  if (parts.length === 1) return parts[0]!;
  const o = new Uint8Array(n);
  let off = 0;
  for (let i = 0; i < parts.length; i++) {
    o.set(parts[i]!, off);
    off += parts[i]!.length;
  }
  return o;
}

/**
 * Scan UMP on the player-read stream without buffering MEDIA_DATA.
 * Production: SPS, SABR_ERROR, FORMAT_INIT itag, MEDIA_HEADER itag.
 * MEDIA_DATA is never buffered. DEBUG keeps the full ump=[] dump.
 */
export interface SabrTee {
  push(chunk: Uint8Array): void;
  consume(parts: Uint8Array[]): void;
  maxHid(): number;
  finish(url: string, status: number): void;
}

export function createSabrTee(): SabrTee {
  let leftover = new Uint8Array(0);
  let inPayload = false;
  let partId = 0;
  let partRemain = 0;
  let partSize = 0;
  let needPayload = false;
  let pay: Uint8Array[] = [];
  let payN = 0;
  let maxHid = -1;
  let sps = -1;
  let media = 0;
  let bodyN = 0;
  let err = "";
  let mhSeq: number | null = null;
  const parts: string[] = [];

  function endPart(): void {
    const payload = needPayload && payN ? joinU8(pay, payN) : null;
    pay = [];
    payN = 0;
    if (partId === 58 && payload) {
      const v = protoVarint(payload, 1);
      sps = v != null ? v : -1;
    }
    if (partId === 44) {
      const msg = payload ? protoString(payload, 1) : "";
      err = msg || "err";
      console.warn("sabr error " + (msg || ""));
    }
    if (partId === 42 && payload) {
      const fid = protoBytes(payload, 2);
      const itag = fid ? protoVarint(fid, 1) : protoVarint(payload, 3);
      console.info("sabr FORMAT_INIT itag=" + (itag != null ? itag : "-"));
    }
    if (partId === 20 && payload) {
      const hid = protoVarint(payload, 1);
      if (hid != null && hid > maxHid) maxHid = hid;
      const itag = protoVarint(payload, 3);
      const init = protoVarint(payload, 8) === 1 ? 1 : 0;
      const seq = protoVarint(payload, 9);
      debug(function () {
        return (
          "sabr MEDIA_HEADER hid=" +
          (hid != null ? hid : "-") +
          " itag=" +
          (itag != null ? itag : "-") +
          " init=" +
          init +
          (seq != null ? " seq=" + seq : "")
        );
      });
    }
    debugRun(function () {
      const name = UMP_NAME[partId] || String(partId);
      if (partId === 58) {
        parts.push("SPS=" + sps + "(" + spsLabel(sps) + ")");
      } else if (partId === 44) {
        parts.push("SABR_ERROR" + (err ? ":" + err : ""));
      } else if (partId === 43) {
        parts.push("REDIRECT");
      } else if (partId === 42 && payload) {
        const fi = fmtFormatInit(payload);
        const fid = protoBytes(payload, 2);
        const itag = fid ? protoVarint(fid, 1) : protoVarint(payload, 3);
        parts.push("FORMAT_INIT:itag=" + (itag != null ? itag : "-"));
        console.log("sabr " + fi);
      } else if (partId === 20 && payload) {
        const mh = fmtMediaHeader(payload);
        const itag = protoVarint(payload, 3);
        const init = protoVarint(payload, 8) === 1 ? 1 : 0;
        const seq = protoVarint(payload, 9);
        if (init !== 1 && seq != null && seq > 0 && (mhSeq == null || seq > mhSeq)) mhSeq = seq;
        const t = protoVarint(payload, 11);
        parts.push(
          "MH:itag=" +
            (itag != null ? itag : "-") +
            ":init=" +
            init +
            (seq != null ? ":seq=" + seq : "") +
            (t != null ? ":t=" + t : ""),
        );
        console.log("sabr " + mh);
      } else {
        parts.push(name + ":" + partSize);
      }
    });
  }

  function push(chunk: Uint8Array): void {
    if (!chunk.length) return;
    bodyN += chunk.length;
    const buf = leftover.length ? catU8(leftover, chunk) : chunk;
    leftover = new Uint8Array(0);
    let i = 0;
    while (i < buf.length) {
      if (!inPayload) {
        const id = readUmpVarint(buf, i);
        if (!id) {
          leftover = buf.slice(i);
          return;
        }
        const sz = readUmpVarint(buf, id.i);
        if (!sz) {
          leftover = buf.slice(i);
          return;
        }
        inPayload = true;
        partId = id.v;
        partSize = sz.v;
        partRemain = sz.v;
        i = sz.i;
        needPayload =
          partId === 58 ||
          partId === 44 ||
          partId === 42 ||
          partId === 20 ||
          (!PRODUCTION && partId !== 21);
        pay = [];
        payN = 0;
      }
      const take = Math.min(partRemain, buf.length - i);
      if (needPayload && take > 0) {
        pay.push(buf.subarray(i, i + take));
        payN += take;
      }
      if (partId === 21) media += take;
      i += take;
      partRemain -= take;
      if (partRemain === 0) {
        inPayload = false;
        endPart();
      }
    }
  }

  function finish(_url: string, status: number): void {
    debug(function () {
      if (inPayload || leftover.length) parts.push((UMP_NAME[partId] || String(partId)) + ":trunc");
      let line =
        "sabr HTTP " +
        status +
        " body=" +
        bodyN +
        " ump=[" +
        parts.join(",") +
        "]" +
        " media=" +
        media +
        " sps=" +
        sps;
      if (mhSeq != null) line += " mhSeq=" + mhSeq;
      if (sps === 2 || sps === 3) line += " PO_REJECTED";
      if (err) line += " SABR_ERROR";
      return line;
    });
    noteSps(sps, media);
    if (isAudioOnly()) {
      dumpDrain(media > 0 ? "sabr-media" : "sabr-res");
      if (media > 0) {
        setTimeout(function () {
          dumpDrain("sabr-media+0");
        }, 0);
      }
    }
  }

  return {
    push: push,
    consume: function (parts) {
      for (let i = 0; i < parts.length; i++) push(parts[i]!);
    },
    maxHid: function () {
      return maxHid;
    },
    finish: finish,
  };
}

function itagsOf(msgs: Uint8Array[]): string {
  const out: number[] = [];
  for (let i = 0; i < msgs.length; i++) {
    const t = protoVarint(msgs[i]!, 1);
    if (t != null) out.push(t);
  }
  return out.length ? out.join(",") : "-";
}

export function logSabrRequest(url: string, body?: unknown, status?: number): void {
  if (url.indexOf("/videoplayback") < 0) return;
  const u = asU8(body);
  recordSabrForDump(url, u);
  debug(function () {
    const q = (url.match(/[?&]pot=([^&]*)/) || [])[1] || "";
    let potB = 0;
    let potB64 = "";
    let playerTimeMs = -1;
    let sinceSeekMs = -1;
    let bit40 = -1;
    let bit46 = -1;
    let br = "";
    let sel = "-";
    let aud = "-";
    let vid = "-";
    let clientAbrLog = "";
    if (u && u.length > 0) {
      const sc = protoBytes(u, 19);
      if (sc) {
        const pot = protoBytes(sc, 2);
        if (pot) {
          potB = pot.length;
          potB64 = b64url(pot);
        }
      }
      const clientAbr = protoBytes(u, 1);
      if (clientAbr) {
        const t = protoVarint(clientAbr, 28);
        if (t != null) playerTimeMs = t;
        const s = protoVarint(clientAbr, 29);
        if (s != null) sinceSeekMs = s;
        const f40 = protoVarint(clientAbr, 40);
        if (f40 != null) bit40 = f40;
        const f46 = protoVarint(clientAbr, 46);
        if (f46 != null) bit46 = f46;
        clientAbrLog = fmtClientAbrState(clientAbr);
      }
      const ranges = protoRepeated(u, 3);
      const rangeParts: string[] = [];
      for (let i = 0; i < ranges.length && i < 8; i++) {
        const fid = protoBytes(ranges[i]!, 1);
        const itag = fid ? protoVarint(fid, 1) : null;
        const st = protoVarint(ranges[i]!, 2);
        const d = protoVarint(ranges[i]!, 3);
        rangeParts.push(
          (itag != null ? itag + ":" : "") + (st != null ? st : "?") + "+" + (d != null ? d : "?"),
        );
      }
      br = rangeParts.join(",");
      sel = itagsOf(protoRepeated(u, 2));
      aud = itagsOf(protoRepeated(u, 16));
      vid = itagsOf(protoRepeated(u, 17));
    }
    const sabr = url.indexOf("sabr=") >= 0 ? "1" : "0";
    const st = status != null ? " HTTP " + status : "";
    if (clientAbrLog) console.log(clientAbrLog);
    return (
      "sabr" +
      st +
      " sabr=" +
      sabr +
      " audioOnly=" +
      (isAudioOnly() ? "1" : "0") +
      " 40=" +
      bit40 +
      " 46=" +
      bit46 +
      " playerTimeMs=" +
      playerTimeMs +
      " sinceSeekMs=" +
      sinceSeekMs +
      " clock=" +
      clockMs() +
      " dur=" +
      durMs() +
      " sel=" +
      sel +
      " aud=" +
      aud +
      " vid=" +
      vid +
      " br=[" +
      br +
      "]" +
      " mse=[a:" +
      msePairs(1) +
      " v:" +
      msePairs(0) +
      "]" +
      " potQ=" +
      q.length +
      " potBody=" +
      potB +
      " pot=" +
      potB64
    );
  });
  if (isAudioOnly()) dumpDrain("sabr-req");
}
