// Host opens listen/player/status or listen/player/catalog to subscribe.
// Status is event-driven: each message is only the group that changed.
//   progress — onVideoProgress: position, buffer, duration, seek window
//   state    — onStateChange: newState, playWhenReady, live, error extras
//   picture  — onVideoDataChange: videoWidth, videoHeight
//   details  — onVideoDataChange: videoId, channelTitle, channelUrl
//   gain     — base.js wrote the element's volume: value, its loudness gain for the presenting format
//   watchNext — a music video's related items: videoId, contents (JSON text; see watch_next.ts)
//   poRejected — true: googlevideo rejected the session's PO token (StreamProtectionStatus 3), or kept
//                withholding media while attestation stayed pending (2)

import { JsEngineErrorImpl } from "../../protocol/command";
import {
  catalogPlayer,
  catalogReady,
  detailsPlayer,
  gainPlayer,
  ListenKind,
  picturePlayer,
  playerStatePlayer,
  progressClockPlayer,
  setCatalogHook,
  setListenHook,
} from "./boot";
import { setPoRejectedHook } from "./bg_dump";
import { setWatchNextHook } from "./watch_next";

let progressPipe: Pipe | null = null;
let catalogPipe: Pipe | null = null;

async function emit(sink: PipeSink, rec: Record<string, unknown>): Promise<void> {
  const p = sink.writeObjectAsJson(rec);
  if (p) await p;
  const f = sink.flush();
  if (f) await f;
}

async function waitHostClose(pipe: Pipe): Promise<void> {
  try {
    const r = pipe.source.readJsonObject();
    if (r && typeof (r as Promise<unknown>).then === "function") await r;
  } catch (_e) {
    /* host closed */
  }
}

function snapshot(kind: ListenKind): Record<string, unknown> {
  if (kind === "progress") return progressClockPlayer();
  if (kind === "state") return playerStatePlayer();
  if (kind === "details") return detailsPlayer();
  if (kind === "gain") return gainPlayer();
  return picturePlayer();
}

function sameProgress(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["progress"] === b["progress"] &&
    a["bufferedMs"] === b["bufferedMs"] &&
    a["duration"] === b["duration"] &&
    a["live"] === b["live"] &&
    a["seekable"] === b["seekable"] &&
    a["seekableStartMs"] === b["seekableStartMs"] &&
    a["seekableEndMs"] === b["seekableEndMs"] &&
    a["progressLabel"] === b["progressLabel"] &&
    a["videoId"] === b["videoId"]
  );
}

function sameState(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["newState"] === b["newState"] &&
    a["playWhenReady"] === b["playWhenReady"] &&
    a["live"] === b["live"] &&
    a["code"] === b["code"] &&
    a["message"] === b["message"]
  );
}

function samePicture(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return a["videoWidth"] === b["videoWidth"] && a["videoHeight"] === b["videoHeight"];
}

function sameDetails(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  return (
    a["videoId"] === b["videoId"] &&
    a["channelTitle"] === b["channelTitle"] &&
    a["channelUrl"] === b["channelUrl"]
  );
}

function sameCatalog(a: Record<string, unknown>, b: Record<string, unknown>): boolean {
  if (
    a["quality"] !== b["quality"] ||
    a["preferQuality"] !== b["preferQuality"] ||
    a["audioOnly"] !== b["audioOnly"] ||
    a["caption"] !== b["caption"]
  )
    return false;
  const qa = a["qualities"];
  const qb = b["qualities"];
  if (!Array.isArray(qa) || !Array.isArray(qb) || qa.length !== qb.length) return false;
  for (let i = 0; i < qa.length; i++) {
    const x = qa[i] as Record<string, unknown>;
    const y = qb[i] as Record<string, unknown>;
    if (x["quality"] !== y["quality"] || x["label"] !== y["label"]) return false;
  }
  if (a["audioTrack"] !== b["audioTrack"]) return false;
  const aa = a["audioTracks"];
  const ab = b["audioTracks"];
  if (!Array.isArray(aa) || !Array.isArray(ab) || aa.length !== ab.length) return false;
  for (let i = 0; i < aa.length; i++) {
    const x = aa[i] as Record<string, unknown>;
    const y = ab[i] as Record<string, unknown>;
    if (x["id"] !== y["id"] || x["displayName"] !== y["displayName"]) return false;
  }
  const ca = a["captionTracks"];
  const cb = b["captionTracks"];
  if (!Array.isArray(ca) || !Array.isArray(cb) || ca.length !== cb.length) return false;
  for (let i = 0; i < ca.length; i++) {
    const x = ca[i] as Record<string, unknown>;
    const y = cb[i] as Record<string, unknown>;
    if (x["vssId"] !== y["vssId"] || x["displayName"] !== y["displayName"]) return false;
  }
  return true;
}

function sameListen(
  kind: ListenKind,
  a: Record<string, unknown>,
  b: Record<string, unknown>,
): boolean {
  if (kind === "progress") return sameProgress(a, b);
  if (kind === "state") return sameState(a, b);
  if (kind === "details") return sameDetails(a, b);
  if (kind === "gain") return a["value"] === b["value"];
  return samePicture(a, b);
}

export async function listenPlayerStatus(pipe: Pipe): Promise<void> {
  if (progressPipe) throw new JsEngineErrorImpl("listen/player/status busy", "E20089");
  progressPipe = pipe;
  let queued: Partial<Record<ListenKind, Record<string, unknown>>> = {};
  const queue: Record<string, unknown>[] = [];
  let writing = false;

  // The sink allows one write in flight; a second throws and that record is lost for good.
  async function drain(): Promise<void> {
    writing = true;
    try {
      while (queue.length) await emit(pipe.sink, queue.shift()!);
    } catch (e) {
      queue.length = 0;
      queued = {};
      throw e;
    } finally {
      writing = false;
    }
  }

  // Every state change keeps its own record so the host sees each one (Ended must not be
  // overtaken by the next load); other groups merge into the waiting record, latest wins.
  function enqueue(rec: Record<string, unknown>): void {
    const tail = queue.length ? queue[queue.length - 1]! : null;
    if (tail && !(rec["state"] !== undefined && tail["state"] !== undefined)) {
      const keys = Object.keys(rec);
      for (let i = 0; i < keys.length; i++) tail[keys[i]!] = rec[keys[i]!];
    } else {
      queue.push(rec);
    }
    if (writing) return;
    drain().catch(function (e: unknown) {
      console.warn("player status push failed: " + (e instanceof Error ? e.message : String(e)));
    });
  }

  function push(kinds: ListenKind[]): void {
    const rec: Record<string, unknown> = {};
    let changed = false;
    for (let i = 0; i < kinds.length; i++) {
      const kind = kinds[i]!;
      const body = snapshot(kind);
      const prev = queued[kind];
      if (prev && sameListen(kind, prev, body)) continue;
      queued[kind] = body;
      rec[kind] = body;
      changed = true;
    }
    if (changed) enqueue(rec);
  }

  try {
    setListenHook(function (kind: ListenKind) {
      // The player response can land after the first onVideoDataChange; a state change re-checks.
      push(kind === "state" ? ["state", "details"] : [kind]);
    });
    push(["state", "progress", "picture", "details", "gain"]);
    setWatchNextHook(function (watchNext: Record<string, unknown>) {
      enqueue({ "watchNext": watchNext });
    });
    setPoRejectedHook(function () {
      enqueue({ "poRejected": true });
    });
    await waitHostClose(pipe);
  } finally {
    setPoRejectedHook(null);
    setWatchNextHook(null);
    setListenHook(null);
    progressPipe = null;
  }
}

export async function runCatalogSession(pipe: Pipe): Promise<void> {
  if (catalogPipe) throw new JsEngineErrorImpl("listen/player/catalog busy", "E20090");
  catalogPipe = pipe;
  let sent: Record<string, unknown> | null = null;
  let dirty = false;
  let writing = false;
  async function drain(): Promise<void> {
    writing = true;
    try {
      while (dirty) {
        dirty = false;
        if (!catalogReady()) return;
        const rec = catalogPlayer();
        if (sent && sameCatalog(sent, rec)) continue;
        await emit(pipe.sink, rec);
        sent = rec;
      }
    } finally {
      writing = false;
    }
  }
  const push = function (): void {
    dirty = true;
    if (writing) return;
    drain().catch(function (e: unknown) {
      console.warn("player catalog push failed: " + (e instanceof Error ? e.message : String(e)));
    });
  };
  setCatalogHook(push);
  try {
    push();
    await waitHostClose(pipe);
  } finally {
    setCatalogHook(null);
    catalogPipe = null;
  }
}
