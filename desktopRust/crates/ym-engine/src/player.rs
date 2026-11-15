//! The player bundle's commands (`player/*`) and push streams (`listen/player/*`), served by a
//! `DynamicEngine` booted with `player.js` and a native MSE host.

use bytes::BytesMut;
use serde_json::{Map, Value, json};
use ym_common::log::Logger;
use ym_quickjs::PipeError;

use crate::engine::DynamicEngine;
use crate::error::EngineError;
use crate::head::{HeadError, read_json_value};

/// A caption track the current video offers.
#[derive(Debug, Clone, PartialEq, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptionTrack {
    pub vss_id: String,
    #[serde(default)]
    pub language_code: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub display_name: String,
}

#[derive(serde::Deserialize, Default)]
struct CaptionTracks {
    #[serde(default)]
    tracks: Vec<CaptionTrack>,
}

/// One `player/load`. `quality: None` with `audio_only` plays audio only (the bundle's `"zero"`).
#[derive(Debug, Clone)]
pub struct LoadRequest {
    pub video_id: String,
    pub quality: Option<String>,
    pub audio_only: bool,
    pub viewport: Option<(i32, i32)>,
    pub start_position_ms: i64,
    pub play_when_ready: bool,
    pub speed: f32,
    pub pitch: f32,
    pub looping: bool,
}

impl LoadRequest {
    pub fn audio(video_id: &str) -> Self {
        Self {
            video_id: video_id.to_owned(),
            quality: None,
            audio_only: true,
            viewport: None,
            start_position_ms: 0,
            play_when_ready: true,
            speed: 1.0,
            pitch: 1.0,
            looping: false,
        }
    }
}

fn object(value: Value) -> Map<String, Value> {
    match value {
        Value::Object(map) => map,
        _ => Map::new(),
    }
}

/// Speeds cross as decimal strings, as the Kotlin host sends them.
fn decimal(value: f32) -> Value {
    Value::String(format!("{value:?}"))
}

impl DynamicEngine {
    pub async fn player_init(
        &self,
        viewport: Option<(i32, i32)>,
        fresh_config: bool,
        log: &Logger,
    ) -> Result<Value, EngineError> {
        let mut args = Map::new();
        if let Some((width, height)) = viewport {
            args.insert("width".into(), json!(width));
            args.insert("height".into(), json!(height));
        }
        if fresh_config {
            args.insert("freshConfig".into(), json!(true));
        }
        self.json_command("player/init", args, &log.fork("init")).await
    }

    pub async fn player_load(&self, request: &LoadRequest, log: &Logger) -> Result<Value, EngineError> {
        let mut args = object(json!({
            "videoId": request.video_id,
            "audioOnly": request.audio_only,
            "playWhenReady": request.play_when_ready,
            "speed": decimal(request.speed),
            "pitch": decimal(request.pitch),
            "loop": request.looping,
        }));
        if let Some(quality) = &request.quality {
            args.insert("quality".into(), json!(quality));
        }
        if let Some((width, height)) = request.viewport {
            args.insert("width".into(), json!(width));
            args.insert("height".into(), json!(height));
        }
        if request.start_position_ms > 0 {
            args.insert("startPositionMs".into(), json!(request.start_position_ms));
        }
        let log = log.fork_with("load", object(json!({ "videoId": request.video_id })));
        self.json_command("player/load", args, &log).await
    }

    async fn player_ack(&self, command: &str, args: Value, log: &Logger) -> Result<(), EngineError> {
        let _: Value = self.json_command(command, object(args), log).await?;
        Ok(())
    }

    pub async fn player_play(&self, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/play", json!({}), log).await
    }

    pub async fn player_pause(&self, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/pause", json!({}), log).await
    }

    pub async fn player_stop(&self, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/stop", json!({}), log).await
    }

    pub async fn player_seek(&self, position_ms: i64, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/seek", json!({ "positionMs": position_ms }), log).await
    }

    /// The display hole in CSS pixels; YouTube's ABR uses it as the viewport.
    pub async fn player_set_viewport(&self, width: i32, height: i32, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/setViewport", json!({ "width": width, "height": height }), log).await
    }

    pub async fn player_set_quality(&self, quality: &str, audio_only: bool, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/setQuality", json!({ "quality": quality, "audioOnly": audio_only }), log).await
    }

    pub async fn player_set_speed_pitch(&self, speed: f32, pitch: f32, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/setSpeedPitch", json!({ "speed": decimal(speed), "pitch": decimal(pitch) }), log).await
    }

    pub async fn player_set_loop(&self, enable: bool, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/setLoop", json!({ "enable": enable }), log).await
    }

    /// Cues the next video without interrupting the current one.
    pub async fn player_preload(&self, video_id: &str, log: &Logger) -> Result<Value, EngineError> {
        let log = log.fork_with("preload", object(json!({ "videoId": video_id })));
        self.json_command("player/preload", object(json!({ "videoId": video_id })), &log).await
    }

    /// One of the catalog's audio tracks (a dub or the original language), by id.
    pub async fn player_set_audio_track(&self, id: &str, log: &Logger) -> Result<(), EngineError> {
        self.player_ack("player/setAudioTrack", json!({ "id": id }), log).await
    }

    pub async fn player_caption_tracks(&self, log: &Logger) -> Result<Vec<CaptionTrack>, EngineError> {
        let mut tracks: CaptionTracks = self.json_command("player/captions", Map::new(), log).await?;
        for track in &mut tracks.tracks {
            if track.display_name.is_empty() {
                track.display_name = track.vss_id.clone();
            }
        }
        Ok(tracks.tracks)
    }

    /// Captions on or off with the preferred track; `vss_id` may also be a language code. The
    /// bundle keeps the preference across videos and reports its pick on the catalog stream.
    pub async fn player_set_caption(
        &self,
        vss_id: Option<&str>,
        visible: bool,
        log: &Logger,
    ) -> Result<(), EngineError> {
        self.player_ack("player/setCaption", json!({ "vssId": vss_id, "visible": visible }), log).await
    }

    pub async fn player_status(&self, log: &Logger) -> Result<Value, EngineError> {
        self.json_command("player/status", Map::new(), log).await
    }

    /// The `/player` JSON plus the decoded SABR URL the player captured, for downloads.
    pub async fn player_response(&self, video_id: &str, log: &Logger) -> Result<Value, EngineError> {
        self.json_command("player/playerResponse", object(json!({ "videoId": video_id })), log).await
    }

    pub async fn mint_content_po_token(&self, video_id: &str, log: &Logger) -> Result<String, EngineError> {
        let result: Value = self.json_command("player/mintPo", object(json!({ "videoId": video_id })), log).await?;
        result
            .get("contentPoToken")
            .and_then(Value::as_str)
            .filter(|token| token.len() >= 20)
            .map(str::to_owned)
            .ok_or_else(|| EngineError::site("R20021", "player/mintPo answered without a contentPoToken"))
    }

    /// A download's one re-mint of the session's content PO token, after googlevideo withheld or
    /// rejected it. A bundle without `player/remintPo` refuses the open: [`ContentPoRemint::Unavailable`].
    pub async fn remint_content_po_token(&self, video_id: &str, log: &Logger) -> Result<ContentPoRemint, EngineError> {
        let result: Value =
            match self.json_command("player/remintPo", object(json!({ "videoId": video_id })), log).await {
                Ok(result) => result,
                Err(error) if error.code == "R20018" && error.message.contains("no JS handler") => {
                    return Ok(ContentPoRemint::Unavailable);
                }
                Err(error) => return Err(error),
            };
        match result.get("status").and_then(Value::as_str) {
            Some("reminted") => result
                .get("contentPoToken")
                .and_then(Value::as_str)
                .filter(|token| token.len() >= 20)
                .map(|token| ContentPoRemint::Minted(token.to_owned()))
                .ok_or_else(|| EngineError::site("R20024", "player/remintPo re-minted without a contentPoToken")),
            Some("accepted") => Ok(ContentPoRemint::PlayerAccepted),
            _ => Ok(ContentPoRemint::Unavailable),
        }
    }

    /// Subscribes to a push stream (`listen/player/status` event groups, `listen/player/catalog`,
    /// `player/caption` paint-on lines `{"lines":[{"id","text"}]}`)
    /// and hands each object to `on_value` until the bundle ends it; dropping the future unsubscribes.
    pub async fn listen_player(&self, command: &str, mut on_value: impl FnMut(Value)) -> Result<(), EngineError> {
        let pipe = self
            .runtime()
            .open_pipe(command)
            .await
            .map_err(|error| EngineError::site("R20018", format!("{command}: {error}")))?;
        let mut buffer = BytesMut::new();
        let result = loop {
            match read_json_value(&pipe, &mut buffer).await {
                Ok(Some(value)) => on_value(value),
                Ok(None) => break Ok(()),
                Err(HeadError::Pipe(PipeError::Reset(reason))) => break Err(EngineError::decode_reset(&reason)),
                Err(error) => break Err(EngineError::site("R20017", format!("{command}: {error}"))),
            }
        };
        let _ = pipe.close_write(None).await;
        let _ = pipe.close_read().await;
        result
    }
}

/// What [`DynamicEngine::remint_content_po_token`] got.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ContentPoRemint {
    Minted(String),
    /// googlevideo accepts the player's current token, so a refused download is not about the token.
    PlayerAccepted,
    /// No re-mint: the session's are spent, or the bundle predates them.
    Unavailable,
}
