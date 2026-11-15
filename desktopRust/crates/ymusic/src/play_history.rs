//! Records playback into the history store: watch time while an item plays, and the queue as a
//! "Play again" session when it starts, changes item or items, pauses, and every 30 s of playing.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::mpsc;
use std::time::{Duration, Instant};

use gpui_kit::*;
use serde_json::{Value, json};
use ym_common::log::Logger;
use ym_engine::yt::YtThumb;
use ym_history::{PlayAgainSession, PlayHistory, VideoMetadata};

use crate::graph::AppGraph;
use crate::playback::{PlayState, Playback, QueueItem, Source};

const PROGRESS_EVERY: Duration = Duration::from_secs(30);
const MIN_POSITION_DELTA_MS: i64 = 3_000;

/// The JVM app's `PlayAgainStore` item JSON, so either app resumes the other's queues; `thumbs`
/// and `code` are this app's own extras, which the JVM app ignores.
pub fn encode_items(items: &[QueueItem]) -> String {
    let rows: Vec<Value> = items
        .iter()
        .map(|item| {
            let thumbs: Vec<Value> =
                item.thumbs.iter().map(|thumb| json!({ "url": thumb.url, "w": thumb.w, "h": thumb.h })).collect();
            let thumbnail = item.thumb(480).map(|url| crate::local_art::to_jvm_art(&url));
            let mut row = json!({
                "title": item.title,
                "artist": item.artist,
                "thumbnailUrl": thumbnail,
                "durationMs": item.duration_ms,
                "thumbs": thumbs,
            });
            match &item.source {
                Source::YouTube(video_id) => {
                    row["type"] = json!("yt");
                    row["videoId"] = json!(video_id);
                    row["duration"] = json!(item.duration_label);
                    row["channelUrl"] = Value::Null;
                    row["live"] = json!(false);
                }
                Source::Local(path) => {
                    row["type"] = json!("local");
                    row["uri"] = json!(ym_common::file_uri::to_file_uri(&path.to_string_lossy()));
                    row["mediaId"] = Value::Null;
                    row["album"] = Value::Null;
                    row["code"] = json!(item.history_code);
                    row["videoId"] = json!(item.youtube_vid);
                    row["loudnessGainDb"] = json!(item.loudness_gain_db);
                }
            }
            row
        })
        .collect();
    Value::Array(rows).to_string()
}

/// Reads either app's item JSON. A JVM app thumbnail (`thumbnailUrl`, maybe `art://`) is kept as
/// written: [`crate::play_again`] resolves it off the UI thread.
pub fn decode_items(text: &str) -> Result<Vec<QueueItem>, String> {
    let rows: Vec<Value> = serde_json::from_str(text).map_err(|error| error.to_string())?;
    rows.iter()
        .map(|row| {
            let string = |key: &str| row.get(key).and_then(Value::as_str).map(str::to_owned);
            let source = match row.get("type").and_then(Value::as_str) {
                Some("yt") => Source::YouTube(string("videoId").ok_or("yt item without videoId")?),
                Some("local") => {
                    let uri = string("uri").ok_or("local item without uri")?;
                    Source::Local(PathBuf::from(ym_common::file_uri::file_uri_path(&uri).unwrap_or(uri)))
                }
                other => return Err(format!("unknown play-again item type {other:?}")),
            };
            let local = matches!(source, Source::Local(_));
            let mut thumbs: Vec<YtThumb> = row
                .get("thumbs")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|thumb| {
                    Some(YtThumb {
                        url: thumb.get("url")?.as_str()?.to_owned(),
                        w: thumb.get("w").and_then(Value::as_i64).map(|w| w as i32),
                        h: thumb.get("h").and_then(Value::as_i64).map(|h| h as i32),
                    })
                })
                .collect();
            if thumbs.is_empty()
                && let Some(url) = string("thumbnailUrl")
            {
                thumbs.push(YtThumb { url, w: None, h: None });
            }
            let gain = row.get("loudnessGainDb").or_else(|| row.get("gainDb")).and_then(Value::as_f64);
            Ok(QueueItem {
                title: string("title").unwrap_or_default(),
                artist: string("artist"),
                duration_ms: row.get("durationMs").and_then(Value::as_i64),
                duration_label: string("duration"),
                thumbs,
                history_code: if local { string("code") } else { None },
                youtube_vid: if local { string("videoId") } else { None },
                loudness_gain_db: if local { gain } else { None },
                source,
            })
        })
        .collect()
}

enum Write {
    Watch {
        item: QueueItem,
        watch_ms: i64,
    },
    Session {
        session: PlayAgainSession,
        items: Option<String>,
    },
    /// Answered once every earlier write is applied.
    Drained(mpsc::Sender<()>),
}

/// One thread applies every write in order.
/// A guest's YouTube plays are not kept.
fn start_writer(history: Arc<PlayHistory>, guest: bool, log: Logger) -> mpsc::Sender<Write> {
    let (sender, receiver) = mpsc::channel::<Write>();
    std::thread::Builder::new()
        .name("play-history".into())
        .spawn(move || {
            for write in receiver {
                let result = match write {
                    Write::Drained(done) => {
                        let _ = done.send(());
                        continue;
                    }
                    Write::Watch { item, watch_ms } => record_watch(&history, &item, watch_ms, guest),
                    Write::Session { session, items: Some(items) } => {
                        history.save_session(&session, &items).map_err(|error| error.to_string())
                    }
                    Write::Session { session, items: None } => match history.save_progress(&session) {
                        Ok(true) => Ok(()),
                        Ok(false) => Err(format!("progress for queue {} that is not saved", session.queue_id)),
                        Err(error) => Err(error.to_string()),
                    },
                };
                // A missed write loses only this play-head or slice; the next trigger writes again.
                if let Err(message) = result {
                    log.warn(&format!("play history write failed: {message}"), None);
                }
            }
        })
        .expect("spawn the play-history writer");
    sender
}

fn record_watch(history: &PlayHistory, item: &QueueItem, watch_ms: i64, guest: bool) -> Result<(), String> {
    record_watch_rows(history, item, watch_ms, guest).map_err(|error| error.to_string())
}

fn record_watch_rows(
    history: &PlayHistory,
    item: &QueueItem,
    watch_ms: i64,
    guest: bool,
) -> Result<(), ym_history::StoreError> {
    match &item.source {
        Source::Local(_) => {
            if let Some(code) = &item.history_code {
                history.track_local(code, watch_ms)?;
            }
            if let Some(video_id) = item.youtube_vid.as_ref().filter(|_| !guest) {
                history.track_youtube(video_id, watch_ms)?;
            }
            Ok(())
        }
        Source::YouTube(_) if guest => Ok(()),
        Source::YouTube(video_id) => {
            history.save_youtube_metadata(&VideoMetadata {
                video_id: video_id.clone(),
                title: if item.title.is_empty() { video_id.clone() } else { item.title.clone() },
                artist: item.artist.clone(),
                thumbnail_url: item.thumb(i32::MAX).map(|url| url.to_string()),
                duration: item.duration_label.clone(),
                duration_ms: item.duration_ms,
            })?;
            history.track_youtube(video_id, watch_ms)
        }
    }
}

struct Snapshot {
    queue_id: String,
    revision: u64,
    index: usize,
    session: PlayAgainSession,
    playing: bool,
}

pub struct HistoryRecorder {
    writes: mpsc::Sender<Write>,
    watching: Option<(String, QueueItem, Instant)>,
    current: Option<Snapshot>,
    saved: Option<(String, u64)>,
    last_written: Option<PlayAgainSession>,
    last_write_at: Instant,
    playback: Entity<Playback>,
    /// A guest records no Play again sessions.
    guest: bool,
    _observe: Subscription,
    _quit: Subscription,
}

impl HistoryRecorder {
    pub fn new(playback: Entity<Playback>, cx: &mut Context<Self>) -> Self {
        let graph = cx.global::<AppGraph>();
        let guest = graph.guest;
        let writes = start_writer(graph.history.clone(), guest, graph.log.fork("play-history"));
        let observe = cx.observe(&playback, |recorder, playback, cx| recorder.observe(&playback, cx));
        let quit = cx.on_app_quit(|recorder, cx| {
            recorder.flush(cx);
            async {}
        });
        Self {
            writes,
            watching: None,
            current: None,
            saved: None,
            last_written: None,
            last_write_at: Instant::now(),
            playback,
            guest,
            _observe: observe,
            _quit: quit,
        }
    }

    /// On quit: the slice being watched and the play-head, written before the process ends.
    fn flush(&mut self, cx: &mut Context<Self>) {
        self.track_watch(None, false);
        let playback = self.playback.read(cx);
        if let Some(item) = playback.current().cloned() {
            let snapshot = snapshot(playback, &item, false);
            self.last_written = None;
            self.write(&snapshot, playback);
        }
        let (done, drained) = mpsc::channel();
        if self.writes.send(Write::Drained(done)).is_ok() {
            // Brief, at quit only: SQLite writes of a few rows.
            let _ = drained.recv_timeout(Duration::from_secs(1));
        }
    }

    fn observe(&mut self, playback: &Entity<Playback>, cx: &mut Context<Self>) {
        let playback = playback.read(cx);
        let playing = playback.state == PlayState::Playing;
        let current = playback.current().cloned();
        self.track_watch(current.as_ref(), playing);
        let next = current.map(|item| snapshot(playback, &item, playing));
        let previous = self.current.take();
        match (&previous, &next) {
            (_, Some(next)) if previous.as_ref().is_none_or(|previous| previous.queue_id != next.queue_id) => {
                if let Some(previous) = &previous {
                    self.write(previous, playback);
                }
                self.write(&at_item_start(next), playback);
            }
            (Some(previous), Some(next)) => {
                if next.index != previous.index {
                    self.write(&at_item_start(next), playback);
                } else if next.revision != previous.revision
                    || (previous.playing && !next.playing)
                    || (next.playing && self.last_write_at.elapsed() >= PROGRESS_EVERY)
                {
                    self.write(next, playback);
                }
            }
            _ => {}
        }
        self.current = next;
    }

    /// Credits time only while the same item keeps playing.
    fn track_watch(&mut self, current: Option<&QueueItem>, playing: bool) {
        let key = current.map(QueueItem::key);
        let same = self.watching.as_ref().is_some_and(|(watched, _, _)| Some(watched) == key.as_ref());
        if same && playing {
            return;
        }
        if let Some((_, item, since)) = self.watching.take() {
            let watch_ms = since.elapsed().as_millis() as i64;
            if watch_ms > 0 {
                let _ = self.writes.send(Write::Watch { item, watch_ms });
            }
        }
        if let (true, Some(item), Some(key)) = (playing, current, key) {
            self.watching = Some((key, item.clone(), Instant::now()));
        }
    }

    fn write(&mut self, snapshot: &Snapshot, playback: &Playback) {
        if self.guest {
            return;
        }
        let session = &snapshot.session;
        let saved = self.saved.as_ref() == Some(&(snapshot.queue_id.clone(), snapshot.revision));
        if saved
            && let Some(last) = &self.last_written
            && (PlayAgainSession { position_ms: session.position_ms, ..last.clone() }) == *session
            && (last.position_ms - session.position_ms).abs() < MIN_POSITION_DELTA_MS
        {
            return;
        }
        // Only the queue it describes may be encoded: a snapshot of a replaced queue sends progress.
        let items = (!saved && playback.queue_id == snapshot.queue_id).then(|| encode_items(&playback.queue));
        if !saved && items.is_none() {
            return;
        }
        let _ = self.writes.send(Write::Session { session: session.clone(), items });
        self.saved = Some((snapshot.queue_id.clone(), snapshot.revision));
        self.last_written = Some(session.clone());
        self.last_write_at = Instant::now();
    }
}

fn snapshot(playback: &Playback, item: &QueueItem, playing: bool) -> Snapshot {
    let size = playback.queue.len();
    let strip_start = PlayAgainSession::strip_start(playback.index, size);
    let duration = if playback.duration_ms > 0 { playback.duration_ms } else { item.duration_ms.unwrap_or(0) };
    Snapshot {
        queue_id: playback.queue_id.clone(),
        revision: playback.queue_revision,
        index: playback.index,
        session: PlayAgainSession {
            queue_id: playback.queue_id.clone(),
            title: item.title.clone(),
            subtitle: item.artist.clone(),
            thumbnail_url: item.thumb(480).map(|url| crate::local_art::to_jvm_art(&url)),
            queue_index: playback.index,
            queue_size: size,
            position_ms: playback.position_ms.max(0),
            duration_ms: duration,
            strip_start,
            strip_thumbs: playback.queue[strip_start..(strip_start + PlayAgainSession::STRIP_SIZE).min(size)]
                .iter()
                .map(|item| item.thumb(120).map(|url| crate::local_art::to_jvm_art(&url)))
                .collect(),
            source_token: None,
        },
        playing,
    }
}

/// A track change: the play-head may still be the previous item's.
fn at_item_start(snapshot: &Snapshot) -> Snapshot {
    let mut session = snapshot.session.clone();
    session.position_ms = 0;
    Snapshot {
        queue_id: snapshot.queue_id.clone(),
        revision: snapshot.revision,
        index: snapshot.index,
        session,
        playing: snapshot.playing,
    }
}
