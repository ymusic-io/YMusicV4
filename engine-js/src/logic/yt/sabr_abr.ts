// SABR VideoPlaybackAbrRequest FormatId lists (SabrProto.kt):
//   2  selected_format_ids       — initialized (already have init)
//   16 selected_audio_format_ids — playable audio ladder (client-filtered)
//   17 selected_video_format_ids — playable video ladder (client-filtered)
// Audio-only VOD: CAS 40=1; drop 144p from initialized (2) and playable video
// (17) and those buffered_ranges (3). A 144p range in (3) means cover is done
// — skip fake UMP. Live AO: no 40=1, no fake UMP, keep 144p on the ladder so
// auto-tiny loads real tiny and rest-video parks the decoder.
// Playable audio (16) is narrowed to its best itag so the server cannot step
// down the audio ladder.

const INITIALIZED_FORMAT_IDS = 2;
const BUFFERED_RANGES = 3;
const PLAYABLE_AUDIO_FORMAT_IDS = 16;
const PLAYABLE_VIDEO_FORMAT_IDS = 17;
const STREAMER_CONTEXT = 19;
const STREAMER_CONTEXT_PO_TOKEN = 2;

import { debug } from "../../debug";
import { isAudioOnly } from "../player/audio_only";
import { ITAG_144P, is144pItag, pickFake144 } from "../player/itag_144";
import { remintedPoToken } from "../player/pot";
import type { Fake144Row } from "../player/itag_144";
import { isYmLive, playingVideoId } from "../player/live";
import { videoIdForSabrUrl } from "../player/player_res";

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

function writeVarint(v: number): number[] {
  const out: number[] = [];
  v = Math.floor(v);
  if (!(v > 0)) return [0];
  /* Do not use `&` — JS bitwise ops are 32-bit. lastModified is ~1.7e15. */
  while (v >= 128) {
    const low = v % 128;
    out.push(low + 128);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
}

type Field = { field: number; wire: number; value: number | Uint8Array };

function readFields(buf: Uint8Array): Field[] | null {
  const out: Field[] = [];
  let i = 0;
  while (i < buf.length) {
    const k = readVarint(buf, i);
    if (!k) return null;
    i = k.i;
    const field = k.v >>> 3;
    const wire = k.v & 7;
    if (wire === 0) {
      const x = readVarint(buf, i);
      if (!x) return null;
      i = x.i;
      out.push({ field: field, wire: 0, value: x.v });
    } else if (wire === 2) {
      const x = readVarint(buf, i);
      if (!x) return null;
      i = x.i;
      if (x.v < 0 || i + x.v > buf.length) return null;
      out.push({ field: field, wire: 2, value: buf.subarray(i, i + x.v) });
      i += x.v;
    } else if (wire === 1) {
      if (i + 8 > buf.length) return null;
      out.push({ field: field, wire: 1, value: buf.subarray(i, i + 8) });
      i += 8;
    } else if (wire === 5) {
      if (i + 4 > buf.length) return null;
      out.push({ field: field, wire: 5, value: buf.subarray(i, i + 4) });
      i += 4;
    } else return null;
  }
  return out;
}

function writeFields(fs: Field[]): Uint8Array {
  const bytes: number[] = [];
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    const key = writeVarint((f.field << 3) | f.wire);
    for (let j = 0; j < key.length; j++) bytes.push(key[j]!);
    if (f.wire === 0) {
      const v = writeVarint(f.value as number);
      for (let j = 0; j < v.length; j++) bytes.push(v[j]!);
    } else {
      const u = f.value as Uint8Array;
      if (f.wire === 2) {
        const ln = writeVarint(u.length);
        for (let j = 0; j < ln.length; j++) bytes.push(ln[j]!);
      }
      for (let j = 0; j < u.length; j++) bytes.push(u[j]!);
    }
  }
  return new Uint8Array(bytes);
}

function parseFormatId(msg: Uint8Array): { itag: number; lmt: number } | null {
  const fs = readFields(msg);
  if (!fs) return null;
  let itag: number | null = null;
  let lmt = 0;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 1 && f.wire === 0) itag = f.value as number;
    if (f.field === 2 && f.wire === 0) lmt = f.value as number;
  }
  if (itag == null) return null;
  return { itag: itag, lmt: lmt };
}

function formatItag(msg: Uint8Array): number | null {
  const id = parseFormatId(msg);
  return id ? id.itag : null;
}

function parseBufferedRange(
  msg: Uint8Array,
): { itag: number; lmt: number; startMs: number; durationMs: number } | null {
  const fs = readFields(msg);
  if (!fs) return null;
  let id: { itag: number; lmt: number } | null = null;
  let startMs: number | null = null;
  let durationMs: number | null = null;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 1 && f.wire === 2 && f.value instanceof Uint8Array) id = parseFormatId(f.value);
    if (f.field === 2 && f.wire === 0) startMs = f.value as number;
    if (f.field === 3 && f.wire === 0) durationMs = f.value as number;
  }
  if (!id || startMs == null || durationMs == null) return null;
  return { itag: id.itag, lmt: id.lmt, startMs: startMs, durationMs: durationMs };
}

function casPlayerTimeMs(cas: Uint8Array): number {
  const fs = readFields(cas);
  if (!fs) return 0;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 28 && f.wire === 0) return f.value as number;
  }
  return 0;
}

function drop144(itag: number, audioOnly: boolean): boolean {
  return is144pItag(itag) && audioOnly && !isYmLive();
}

// Outlives audio-only in YouTube's player as a 0..duration range; sent, googlevideo sends no video.
let injectedFake: { videoId: string; itag: number; lmt: number } | null = null;

function isInjectedFake(videoId: string, itag: number, lmt: number): boolean {
  const fake = injectedFake;
  return !!fake && fake.videoId === videoId && fake.itag === itag && fake.lmt === lmt;
}

function decToNum(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const d = s.charCodeAt(i) - 48;
    if (d < 0 || d > 9) continue;
    n = n * 10 + d;
  }
  return n;
}

function asciiU8(s: string): Uint8Array {
  const u = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i) & 0xff;
  return u;
}

function writeUmpVarint(v: number): number[] {
  v = Math.floor(v);
  if (v < 0) v = 0;
  if (v < 128) return [v];
  if (v < 16384) return [(v & 0x3f) | 0x80, (v >>> 6) & 0xff];
  if (v < 2097152) return [(v & 0x1f) | 0xc0, (v >>> 5) & 0xff, (v >>> 13) & 0xff];
  if (v < 268435456)
    return [(v & 0x0f) | 0xe0, (v >>> 4) & 0xff, (v >>> 12) & 0xff, (v >>> 20) & 0xff];
  return [0xf0, v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff];
}

function umpPart(id: number, payload: Uint8Array): Uint8Array {
  const hid = writeUmpVarint(id);
  const sz = writeUmpVarint(payload.length);
  const out = new Uint8Array(hid.length + sz.length + payload.length);
  out.set(hid, 0);
  out.set(sz, hid.length);
  out.set(payload, hid.length + sz.length);
  return out;
}

function encodeFormatId(itag: number, lmtDec: string): Uint8Array {
  return writeFields([
    { field: 1, wire: 0, value: itag },
    { field: 2, wire: 0, value: decToNum(lmtDec) },
  ]);
}

/** Init MEDIA_DATA is initRange+indexRange (dump: 0–3191 = 3192). YMF0 at head. */
function initBytes(row: Fake144Row): Uint8Array {
  const end = row.indexEnd >= row.initEnd ? row.indexEnd : row.initEnd;
  const n = end - row.initStart + 1;
  const u = new Uint8Array(n > 4 ? n : 4);
  u[0] = 0x59;
  u[1] = 0x4d;
  u[2] = 0x46;
  u[3] = 0x30;
  return u;
}

function writeU64LE(u: Uint8Array, off: number, v: number): void {
  let n = Math.floor(v);
  if (n < 0) n = 0;
  for (let i = 0; i < 8; i++) {
    u[off + i] = n % 256;
    n = Math.floor(n / 256);
  }
}

function encodeRange(start: number, end: number): Uint8Array {
  return writeFields([
    { field: 1, wire: 0, value: start },
    { field: 2, wire: 0, value: end },
  ]);
}

/** YMF1 + startMs (u64le) + durationMs (u64le). appendBuffer parses this. */
function fakeMediaBytes(startMs: number, durationMs: number): Uint8Array {
  const u = new Uint8Array(20);
  u[0] = 0x59;
  u[1] = 0x4d;
  u[2] = 0x46;
  u[3] = 0x31;
  writeU64LE(u, 4, startMs);
  writeU64LE(u, 12, durationMs);
  return u;
}

function ticks(ms: number, timescale: number): number {
  if (!(ms > 0) || !(timescale > 0)) return 0;
  return Math.round((ms * timescale) / 1000);
}

function formatInitUmp(videoId: string, row: Fake144Row, durationMs: number): Uint8Array {
  const fid = encodeFormatId(row.itag, row.lmt);
  const ts = row.timescale > 0 ? row.timescale : 1000;
  const fs: Field[] = [
    { field: 1, wire: 2, value: asciiU8(videoId) },
    { field: 2, wire: 2, value: fid },
  ];
  if (durationMs > 0) fs.push({ field: 3, wire: 0, value: durationMs });
  fs.push({ field: 4, wire: 0, value: row.bitrate > 0 ? Math.round(row.bitrate / 1000) : 0 });
  fs.push({ field: 5, wire: 2, value: asciiU8(row.mime) });
  fs.push({ field: 6, wire: 2, value: encodeRange(row.initStart, row.initEnd) });
  fs.push({ field: 7, wire: 2, value: encodeRange(row.indexStart, row.indexEnd) });
  fs.push({ field: 9, wire: 0, value: ticks(durationMs, ts) });
  fs.push({ field: 10, wire: 0, value: ts });
  return umpPart(42, writeFields(fs));
}

function mediaHeaderUmp(opts: {
  hid: number;
  videoId: string;
  itag: number;
  lmt: string;
  init: boolean;
  seq: number;
  startMs: number;
  durationMs: number;
  startRange: number;
  clen: number;
  timescale: number;
}): Uint8Array {
  // YouTube's MEDIA_HEADER reader (pT) is sequential: a higher field number
  // before a lower one is "already passed" — field 13 before 8/9 dropped init+seq.
  const fs: Field[] = [
    { field: 1, wire: 0, value: opts.hid },
    { field: 2, wire: 2, value: asciiU8(opts.videoId) },
    { field: 3, wire: 0, value: opts.itag },
    { field: 4, wire: 0, value: decToNum(opts.lmt) },
    { field: 6, wire: 0, value: opts.startRange },
    { field: 8, wire: 0, value: opts.init ? 1 : 0 },
  ];
  if (!opts.init) {
    const ts = opts.timescale > 0 ? opts.timescale : 1000;
    fs.push({ field: 9, wire: 0, value: opts.seq });
    fs.push({ field: 11, wire: 0, value: opts.startMs });
    fs.push({ field: 12, wire: 0, value: opts.durationMs });
  }
  fs.push({ field: 14, wire: 0, value: opts.clen });
  if (!opts.init) {
    const ts = opts.timescale > 0 ? opts.timescale : 1000;
    fs.push({
      field: 15,
      wire: 2,
      value: writeFields([
        { field: 1, wire: 0, value: ticks(opts.startMs, ts) },
        { field: 2, wire: 0, value: ticks(opts.durationMs, ts) },
        { field: 3, wire: 0, value: ts },
      ]),
    });
  }
  return umpPart(20, writeFields(fs));
}

function mediaDataUmp(hid: number, data: Uint8Array): Uint8Array {
  const p = new Uint8Array(1 + data.length);
  p[0] = hid;
  p.set(data, 1);
  return umpPart(21, p);
}

function mediaEndUmp(hid: number): Uint8Array {
  return umpPart(22, new Uint8Array([hid]));
}

export type SabrRequestState = {
  audioOnly: boolean;
  videoId: string;
  row: Fake144Row;
  durationMs: number;
  playerTimeMs: number;
  buffered144End: number;
};

function buildFakeUmp(state: SabrRequestState, maxHid: number): Uint8Array[] | null {
  if (!state.audioOnly) return null;
  const row = state.row;
  const dur = state.durationMs;
  if (!(dur > 0)) return null;
  const media = fakeMediaBytes(0, dur);
  const base = maxHid < 0 ? 0 : maxHid;
  const initHid = (base + 1) & 255;
  const mediaHid = (base + 2) & 255;
  const mediaStart = row.indexEnd >= row.initEnd ? row.indexEnd + 1 : row.initEnd + 1;
  const parts: Uint8Array[] = [
    formatInitUmp(state.videoId, row, state.durationMs),
    mediaHeaderUmp({
      hid: initHid,
      videoId: state.videoId,
      itag: row.itag,
      lmt: row.lmt,
      init: true,
      seq: 0,
      startMs: 0,
      durationMs: 0,
      startRange: row.initStart,
      clen: initBytes(row).length,
      timescale: row.timescale,
    }),
    mediaDataUmp(initHid, initBytes(row)),
    mediaEndUmp(initHid),
    mediaHeaderUmp({
      hid: mediaHid,
      videoId: state.videoId,
      itag: row.itag,
      lmt: row.lmt,
      init: false,
      seq: 1,
      startMs: 0,
      durationMs: dur,
      startRange: mediaStart,
      clen: media.length,
      timescale: row.timescale,
    }),
    mediaDataUmp(mediaHid, media),
    mediaEndUmp(mediaHid),
  ];
  injectedFake = { videoId: state.videoId, itag: row.itag, lmt: decToNum(row.lmt) };
  debug(function () {
    return (
      "mse sabr fake-ump itag=" +
      row.itag +
      " lmt=" +
      row.lmt +
      " hid=" +
      initHid +
      "," +
      mediaHid +
      " startMs=0 seq=1 durMs=" +
      dur +
      " playerTimeMs=" +
      state.playerTimeMs +
      " buf144End=" +
      state.buffered144End
    );
  });
  return parts;
}

/** CAS 38.4.1 itagDenylist — merge 144p itags, keep existing denies. */
function mergeItagDenylist(attrs: Uint8Array): Uint8Array {
  const fs = attrs.length ? readFields(attrs) : [];
  const out: Field[] = [];
  const have: Record<number, boolean> = {};
  if (fs) {
    for (let i = 0; i < fs.length; i++) {
      const f = fs[i]!;
      out.push(f);
      if (f.field === 1 && f.wire === 0) have[f.value as number] = true;
    }
  }
  for (let i = 0; i < ITAG_144P.length; i++) {
    const t = ITAG_144P[i]!;
    if (!have[t]) out.push({ field: 1, wire: 0, value: t });
  }
  return writeFields(out);
}

function deny144MediaCaps(mc: Uint8Array): Uint8Array {
  const fs = readFields(mc);
  if (!fs) return mc;
  const out: Field[] = [];
  let saw4 = false;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 4 && f.wire === 2 && f.value instanceof Uint8Array) {
      out.push({ field: 4, wire: 2, value: mergeItagDenylist(f.value) });
      saw4 = true;
      continue;
    }
    out.push(f);
  }
  if (!saw4) out.push({ field: 4, wire: 2, value: mergeItagDenylist(new Uint8Array(0)) });
  return writeFields(out);
}

function rewriteCas40(cas: Uint8Array): Uint8Array {
  const fs = readFields(cas);
  if (!fs) return cas;
  const out: Field[] = [];
  let saw40 = false;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 38 && f.wire === 2 && f.value instanceof Uint8Array) {
      out.push({ field: 38, wire: 2, value: deny144MediaCaps(f.value) });
      debug(function () {
        return "sabr deny144 mediaCapabilities itags=" + ITAG_144P.join(",");
      });
      continue;
    }
    if (f.field === 40) {
      saw40 = true;
      out.push({ field: 40, wire: 0, value: 1 });
      continue;
    }
    out.push(f);
  }
  if (!saw40) out.push({ field: 40, wire: 0, value: 1 });
  return writeFields(out);
}

let lastPlayable = "";

/** Best first. 774/141 are Premium-only (256 kbps); 600/599 are the ultralow rungs. */
const AUDIO_ITAG_RANK = [774, 141, 251, 140, 250, 249, 139, 600, 599];

function bestPlayableAudioItag(fs: Field[]): number {
  let best = -1;
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field !== PLAYABLE_AUDIO_FORMAT_IDS || f.wire !== 2 || !(f.value instanceof Uint8Array))
      continue;
    const t = formatItag(f.value);
    const rank = t == null ? -1 : AUDIO_ITAG_RANK.indexOf(t);
    if (rank >= 0 && (best < 0 || rank < best)) best = rank;
  }
  return best >= 0 ? AUDIO_ITAG_RANK[best]! : -1;
}

/** The streamer context with its PO token swapped for the re-minted one; null when nothing was
 *  re-minted. base.js keeps sending the token it minted before the re-mint. */
function withRemintedPoToken(context: Uint8Array, videoId: string): Uint8Array | null {
  const token = remintedPoToken(videoId);
  const fields = token ? readFields(context) : null;
  if (!token || !fields) return null;
  const out: Field[] = [];
  for (let i = 0; i < fields.length; i++) {
    const f = fields[i]!;
    out.push(
      f.field === STREAMER_CONTEXT_PO_TOKEN && f.wire === 2
        ? { field: f.field, wire: 2, value: token }
        : f,
    );
  }
  return writeFields(out);
}

function rewriteAbr(
  u: Uint8Array,
  videoId: string,
): { body: Uint8Array; state: SabrRequestState | null } {
  const fs = readFields(u);
  if (!fs) return { body: u, state: null };
  const audioOnly = isAudioOnly();
  const selItags: number[] = [];
  const playAud: number[] = [];
  const playVid: number[] = [];
  const out: Field[] = [];
  let stripped = false;
  let has144Buffered = false;
  let buffered144End = 0;
  let playerTimeMs = 0;
  const bestAudio = bestPlayableAudioItag(fs);
  for (let i = 0; i < fs.length; i++) {
    const f = fs[i]!;
    if (f.field === 1 && f.wire === 2 && f.value instanceof Uint8Array) {
      playerTimeMs = casPlayerTimeMs(f.value);
      if (audioOnly && !isYmLive()) out.push({ field: 1, wire: 2, value: rewriteCas40(f.value) });
      else out.push(f);
      continue;
    }
    if (f.field === INITIALIZED_FORMAT_IDS && f.wire === 2 && f.value instanceof Uint8Array) {
      const id = parseFormatId(f.value);
      if (id) selItags.push(id.itag);
      if (id && (drop144(id.itag, audioOnly) || isInjectedFake(videoId, id.itag, id.lmt))) {
        stripped = true;
        continue;
      }
    }
    if (f.field === PLAYABLE_AUDIO_FORMAT_IDS && f.wire === 2 && f.value instanceof Uint8Array) {
      const t = formatItag(f.value);
      if (t != null) playAud.push(t);
      if (bestAudio >= 0 && t !== bestAudio) {
        stripped = true;
        continue;
      }
    }
    if (f.field === PLAYABLE_VIDEO_FORMAT_IDS && f.wire === 2 && f.value instanceof Uint8Array) {
      const id = parseFormatId(f.value);
      if (id) playVid.push(id.itag);
      if (id && drop144(id.itag, audioOnly)) {
        stripped = true;
        continue;
      }
    }
    if (f.field === BUFFERED_RANGES && f.wire === 2 && f.value instanceof Uint8Array) {
      const r = parseBufferedRange(f.value);
      if (r && (drop144(r.itag, audioOnly) || isInjectedFake(videoId, r.itag, r.lmt))) {
        has144Buffered = true;
        const end = r.startMs + r.durationMs;
        if (end > buffered144End) buffered144End = end;
        stripped = true;
        continue;
      }
    }
    if (f.field === STREAMER_CONTEXT && f.wire === 2 && f.value instanceof Uint8Array) {
      const context = withRemintedPoToken(f.value, videoId);
      if (context) {
        out.push({ field: STREAMER_CONTEXT, wire: 2, value: context });
        stripped = true;
        continue;
      }
    }
    out.push(f);
  }
  const playable =
    "sabr playable audio=[" +
    playAud.join(",") +
    "] pinned=" +
    bestAudio +
    " video=[" +
    playVid.join(",") +
    "]";
  if (playable !== lastPlayable) {
    lastPlayable = playable;
    console.info(playable);
  }
  let state: SabrRequestState | null = null;
  const fake = videoId ? pickFake144(videoId, selItags, playVid) : null;
  if (has144Buffered) {
    debug(function () {
      return "sabr skip fake-ump 144p already buffered";
    });
  } else if (audioOnly && fake && !fake.live && fake.durationMs > 0) {
    state = {
      audioOnly: audioOnly,
      videoId: videoId,
      row: fake.row,
      durationMs: fake.durationMs,
      playerTimeMs: playerTimeMs,
      buffered144End: buffered144End,
    };
  }
  const body = !stripped && !audioOnly ? u : writeFields(out);
  return { body: body, state: state };
}

function isPlayingSabr(url: string): boolean {
  const play = playingVideoId();
  const sabr = videoIdForSabrUrl(url);
  return !!(play && sabr && play === sabr);
}

export function transformRequestBody(
  url: string,
  body: unknown,
): { body: unknown; state: SabrRequestState | null } {
  if (url.indexOf("/videoplayback") < 0) return { body: body, state: null };
  const u = asU8(body);
  if (!u || u.length === 0) return { body: body, state: null };
  const rewritten = rewriteAbr(u, videoIdForSabrUrl(url));
  if (rewritten.state && !isPlayingSabr(url)) rewritten.state = null;
  return rewritten;
}

export function fakeUmpAfter(state: SabrRequestState | null, maxHid: number): Uint8Array[] | null {
  if (!state || !state.audioOnly || isYmLive()) return null;
  const fake = buildFakeUmp(state, maxHid);
  if (!fake || !fake.length) return null;
  return fake;
}
