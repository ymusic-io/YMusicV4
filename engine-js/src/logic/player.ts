// App-facing player commands. init warms the singleton WEB player; load stamps the video id and
// calls loadVideoById (returns immediately). Host observes:
//   listen/player/status  — event groups: progress | state | picture
//   listen/player/catalog — qualities, captions, audio tracks
// play/pause/seek/stop are playVideo/pauseVideo/seekTo/stopVideo.

import { onAccept } from "../transport";
import { commandHandler, JsEngineErrorImpl } from "../protocol/command";
import {
  initPlayer,
  requireFreshPlayerConfig,
  loadPlayer,
  mintPlayerPo,
  remintPlayerPo,
  preloadPlayer,
  pausePlayer,
  playPlayer,
  seekPlayer,
  setPlayerAudioTrack,
  setPlayerLoop,
  setPlayerQuality,
  setPlayerSpeed,
  setPlayerSpeedPitch,
  setPlayerViewport,
  statusPlayer,
  stopPlayer,
} from "./player/boot";
import { applyViewport } from "./player/viewport";
import { runCatalogSession, listenPlayerStatus } from "./player/push";
import { listCaptionTracks, runCaptionSession, setCaptionPreference } from "./player/captions";
import { downloadWaitMs, peekDownloadJson, waitDownloadJson } from "./player/player_res";

onAccept(
  "player/init",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    applyViewport(a["width"], a["height"]);
    requireFreshPlayerConfig(a["freshConfig"] === true);
    const result = await initPlayer();
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/load",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/load missing videoId", "E20072");
    applyViewport(a["width"], a["height"]);
    const quality = a["quality"] != null ? String(a["quality"]) : undefined;
    const audioOnly = a["audioOnly"] === true;
    const startMs = Number(a["startPositionMs"]);
    const speed = Number(a["speed"]);
    const pitch = Number(a["pitch"]);
    const result = await loadPlayer(id, {
      quality: quality,
      audioOnly: audioOnly,
      startSeconds: Number.isFinite(startMs) && startMs > 0 ? startMs / 1000 : 0,
      playWhenReady: a["playWhenReady"] !== false,
      speed: Number.isFinite(speed) ? speed : 1,
      pitch: Number.isFinite(pitch) ? pitch : 1,
      loop: a["loop"] === true,
    });
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/preload",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/preload missing videoId", "E20091");
    const result = await preloadPlayer(id);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/setViewport",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const result = setPlayerViewport(a["width"], a["height"]);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/mintPo",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw JsEngineErrorImpl.parse("player/mintPo missing videoId");
    const result = await mintPlayerPo(id);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/remintPo",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw JsEngineErrorImpl.parse("player/remintPo missing videoId");
    const result = await remintPlayerPo(id);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/setLoop",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    setPlayerLoop(a["enable"] === true);
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/setSpeed",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const speed = Number(a["speed"]);
    const tempo = Number(a["tempo"]);
    const s = Number.isFinite(speed) ? speed : 1;
    const t = Number.isFinite(tempo) ? tempo : s;
    setPlayerSpeed(s, t);
    const p = out.writeObjectAsJson({ "ok": true, "speed": s, "tempo": t });
    if (p) await p;
  }),
);

onAccept(
  "player/setSpeedPitch",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const speed = Number(a["speed"]);
    const pitch = Number(a["pitch"]);
    const s = Number.isFinite(speed) ? speed : 1;
    const p0 = Number.isFinite(pitch) ? pitch : 1;
    setPlayerSpeedPitch(s, p0);
    const p = out.writeObjectAsJson({ "ok": true, "speed": s, "pitch": p0 });
    if (p) await p;
  }),
);

onAccept(
  "player/play",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    playPlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/pause",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    pausePlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/seek",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const ms = Number(a["positionMs"]);
    if (Number.isFinite(ms) && ms >= 0) seekPlayer(ms / 1000);
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/stop",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    stopPlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/setQuality",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const quality = String(a["quality"] ?? "");
    const audioOnly = a["audioOnly"] === true;
    setPlayerQuality(quality, audioOnly);
    const p = out.writeObjectAsJson({ "ok": true, "quality": quality, "audioOnly": audioOnly });
    if (p) await p;
  }),
);

onAccept(
  "player/setAudioTrack",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["id"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/setAudioTrack missing id", "E20163");
    setPlayerAudioTrack(id);
    const p = out.writeObjectAsJson({ "ok": true, "id": id });
    if (p) await p;
  }),
);

onAccept(
  "player/status",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const p = out.writeObjectAsJson(statusPlayer());
    if (p) await p;
  }),
);

onAccept(
  "listen/player/status",
  commandHandler((_src, _out, pipe) => listenPlayerStatus(pipe)),
);

onAccept(
  "listen/player/catalog",
  commandHandler((_src, _out, pipe) => runCatalogSession(pipe)),
);

onAccept(
  "player/captions",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const p = out.writeObjectAsJson(listCaptionTracks());
    if (p) await p;
  }),
);

onAccept(
  "player/setCaption",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const vss = a["vssId"] == null || a["vssId"] === "" ? null : String(a["vssId"]);
    const visible = a["visible"] == null ? vss != null : a["visible"] === true;
    setCaptionPreference(visible, vss);
    const p = out.writeObjectAsJson({ "ok": true, "vssId": vss, "visible": visible });
    if (p) await p;
  }),
);

onAccept(
  "player/playerResponse",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/playerResponse missing videoId", "E20146");
    let rec = peekDownloadJson(id);
    if (!rec) {
      await preloadPlayer(id);
      rec = await waitDownloadJson(id, downloadWaitMs());
    }
    const p = out.writeObjectAsJson(rec);
    if (p) await p;
  }),
);

onAccept(
  "player/caption",
  commandHandler((_src, _out, pipe) => runCaptionSession(pipe)),
);
