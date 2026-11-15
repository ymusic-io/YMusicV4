//! The download queue. The store is the single source of truth; the service holds only the worker
//! slots and broadcasts each change. Up to `max_concurrent` tasks run at once; their player fetches
//! go one at a time through the [`PlayerGate`]. Pause is per task (the queue keeps flowing);
//! `pause_all` holds the whole queue. Nothing starts on launch: a crash leaves tasks paused.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use futures::future::BoxFuture;
use serde_json::{Map, Value};
use tokio::runtime::Handle;
use tokio::sync::{Mutex, OnceCell, broadcast};
use tokio::task::AbortHandle;
use ym_common::log::{Logger, now_millis};

use crate::error::DownloadError;
use crate::ffmpeg::Ffmpeg;
use crate::model::{DownloadChange, DownloadFormat, DownloadRequest, DownloadState, DownloadTask, StreamId, TaskError};
use crate::options::DownloadOptions;
use crate::pipeline::{Pipeline, PipelineResult};
use crate::storage::DownloadStorage;
use crate::store::DownloadStore;
use crate::streams::{PlayerGate, RunObserver, SpecResolver, StreamDownloader};

pub type FolderSource = Arc<dyn Fn() -> Option<PathBuf> + Send + Sync>;
pub type CompletedHook = Arc<dyn Fn(DownloadTask, PipelineResult) + Send + Sync>;

pub struct DownloadConfig {
    pub tokio: Handle,
    pub store: Arc<DownloadStore>,
    pub storage: Arc<DownloadStorage>,
    pub resolver: Arc<dyn SpecResolver>,
    pub sabr: ym_ytmedia::sabr::SabrHttp,
    /// Follows redirects: captions and cover art.
    pub client: reqwest::Client,
    pub ffmpeg: Option<Ffmpeg>,
    pub segment_index: Option<Arc<ym_ytmedia::segment_index::SegmentIndex>>,
    /// The player cache, read to reuse played segments, never written.
    pub player_cache: Option<Arc<ym_ytmedia::cache::MediaCache>>,
    pub max_concurrent: usize,
    /// The user's download folder, read at publish time.
    pub folder: FolderSource,
    /// Registers a finished file in the library.
    pub on_completed: Option<CompletedHook>,
    pub log: Logger,
}

#[derive(Default)]
struct Slots {
    queue_paused: bool,
    active: HashMap<String, AbortHandle>,
    /// Why a running task is being stopped: PAUSED or CANCELED.
    interrupts: HashMap<String, DownloadState>,
}

struct Inner {
    tokio: Handle,
    store: Arc<DownloadStore>,
    storage: Arc<DownloadStorage>,
    pipeline: Pipeline,
    resolver: Arc<dyn SpecResolver>,
    gate: Arc<PlayerGate>,
    ffmpeg_available: bool,
    max_concurrent: usize,
    folder: FolderSource,
    on_completed: Option<CompletedHook>,
    changes: broadcast::Sender<DownloadChange>,
    slots: Mutex<Slots>,
    initialized: OnceCell<()>,
    log: Logger,
}

#[derive(Clone)]
pub struct DownloadService {
    inner: Arc<Inner>,
}

impl DownloadService {
    pub fn new(config: DownloadConfig) -> Self {
        let log = config.log.fork("download");
        let gate = Arc::new(PlayerGate::new());
        let downloader = StreamDownloader {
            resolver: config.resolver.clone(),
            gate: gate.clone(),
            sabr: config.sabr,
            client: config.client,
            storage: config.storage.clone(),
            index: config.segment_index,
            player_cache: config.player_cache,
            log: log.fork("streams"),
        };
        let ffmpeg_available = config.ffmpeg.is_some();
        let pipeline =
            Pipeline { downloader, ffmpeg: config.ffmpeg, storage: config.storage.clone(), log: log.fork("pipeline") };
        Self {
            inner: Arc::new(Inner {
                tokio: config.tokio,
                store: config.store,
                storage: config.storage,
                pipeline,
                resolver: config.resolver,
                gate,
                ffmpeg_available,
                max_concurrent: config.max_concurrent.max(1),
                folder: config.folder,
                on_completed: config.on_completed,
                changes: broadcast::channel(256).0,
                slots: Mutex::new(Slots::default()),
                initialized: OnceCell::new(),
                log: log.fork("service"),
            }),
        }
    }

    /// Where ffmpeg exists; without it only M4A is offered.
    pub fn ffmpeg_available(&self) -> bool {
        self.inner.ffmpeg_available
    }

    pub fn subscribe(&self) -> broadcast::Receiver<DownloadChange> {
        self.inner.changes.subscribe()
    }

    pub async fn snapshot(&self) -> Result<Vec<DownloadTask>, DownloadError> {
        self.inner.init().await;
        self.inner.store.all().map_err(store_error)
    }

    /// What a video can be downloaded as: the options dialog's input.
    pub async fn probe(&self, video_id: &str) -> Result<DownloadOptions, DownloadError> {
        let inner = &self.inner;
        let response = inner.gate.run(inner.resolver.player_response(video_id)).await?;
        DownloadOptions::parse(&response, inner.ffmpeg_available)
    }

    pub fn enqueue(&self, request: DownloadRequest) -> String {
        let now = now_millis();
        let id = format!("{now}-{}-{}", request.video_id, rand::random_range(0..1_000_000));
        let task = DownloadTask {
            id: id.clone(),
            video_id: request.video_id,
            title: request.title,
            artist: request.artist,
            thumbnail_url: request.thumbnail_url,
            format: request.format,
            requested_height: request.requested_height,
            state: DownloadState::Queued,
            downloaded_bytes: 0,
            total_bytes: 0,
            file_uri: None,
            error: None,
            created_at: now,
            updated_at: now,
            file_name: request.file_name,
            audio_track_id: request.audio_track_id,
            captions: request.captions,
            audio_language: request.audio_language,
            caption_language: request.caption_language,
            audio_quality: request.audio_quality,
            audio_stream: None,
            video_stream: None,
            loudness_gain_db: None,
        };
        self.command(None, move |inner| async move {
            inner.init().await;
            inner.store.insert(&task).map_err(store_error)?;
            inner.emit(DownloadChange::Added(Box::new(task)));
            inner.fill_slots().await;
            Ok(())
        });
        id
    }

    /// Frees the task's slot; the next queued task takes it.
    pub fn pause(&self, id: &str) {
        let id = id.to_owned();
        self.command(Some(id.clone()), move |inner| async move {
            let mut slots = inner.slots.lock().await;
            if let Some(running) = slots.active.get(&id).cloned() {
                slots.interrupts.insert(id, DownloadState::Paused);
                running.abort();
            } else if inner.store.get(&id).map_err(store_error)?.is_some_and(|task| task.state == DownloadState::Queued)
            {
                inner.set_state(&id, DownloadState::Paused, None)?;
            }
            Ok(())
        });
    }

    /// Back to the queue from paused or failed; also un-holds a held queue.
    pub fn resume(&self, id: &str) {
        let id = id.to_owned();
        self.command(Some(id.clone()), move |inner| async move {
            let Some(task) = inner.store.get(&id).map_err(store_error)? else { return Ok(()) };
            if matches!(task.state, DownloadState::Paused | DownloadState::Failed) {
                inner.slots.lock().await.queue_paused = false;
                inner.set_state(&id, DownloadState::Queued, None)?;
                inner.fill_slots().await;
            }
            Ok(())
        });
    }

    pub fn retry(&self, id: &str) {
        self.resume(id);
    }

    pub fn cancel(&self, id: &str) {
        let id = id.to_owned();
        self.command(Some(id.clone()), move |inner| async move {
            let mut slots = inner.slots.lock().await;
            if let Some(running) = slots.active.get(&id).cloned() {
                slots.interrupts.insert(id, DownloadState::Canceled);
                running.abort();
            } else {
                inner.cleanup_scratch(&id);
                inner.set_state(&id, DownloadState::Canceled, None)?;
            }
            Ok(())
        });
    }

    /// Removes the task and its scratch; a finished file stays on disk.
    pub fn remove(&self, id: &str) {
        let id = id.to_owned();
        self.command(Some(id.clone()), move |inner| async move {
            {
                let mut slots = inner.slots.lock().await;
                if let Some(running) = slots.active.get(&id).cloned() {
                    slots.interrupts.insert(id.clone(), DownloadState::Canceled);
                    running.abort();
                } else {
                    inner.cleanup_scratch(&id);
                }
                inner.store.delete(&id).map_err(store_error)?;
            }
            inner.emit(DownloadChange::Removed { id });
            Ok(())
        });
    }

    /// Re-points every finished task whose file was `from_uri` (renamed or moved) at `to_uri`.
    pub async fn relocate(&self, from_uri: &str, to_uri: &str) -> Result<(), DownloadError> {
        let inner = &self.inner;
        inner.init().await;
        let tasks = inner.store.all().map_err(store_error)?;
        for task in tasks {
            if task.state != DownloadState::Completed || task.file_uri.as_deref() != Some(from_uri) {
                continue;
            }
            let bytes = if task.total_bytes > 0 { task.total_bytes } else { task.downloaded_bytes };
            inner
                .store
                .update_completed(&task.id, to_uri, bytes, task.loudness_gain_db, now_millis())
                .map_err(store_error)?;
            inner.emit(DownloadChange::Completed { id: task.id, file_uri: to_uri.to_owned(), bytes });
        }
        Ok(())
    }

    /// Holds the whole queue: stops every running task and every queued one.
    pub fn pause_all(&self) {
        self.command(None, |inner| async move {
            {
                let mut slots = inner.slots.lock().await;
                slots.queue_paused = true;
                let running: Vec<(String, AbortHandle)> =
                    slots.active.iter().map(|(id, handle)| (id.clone(), handle.clone())).collect();
                for (id, handle) in running {
                    slots.interrupts.insert(id, DownloadState::Paused);
                    handle.abort();
                }
            }
            for task in inner.store.all().map_err(store_error)? {
                if task.state == DownloadState::Queued {
                    inner.set_state(&task.id, DownloadState::Paused, None)?;
                }
            }
            Ok(())
        });
    }

    pub fn resume_all(&self) {
        self.command(None, |inner| async move {
            inner.slots.lock().await.queue_paused = false;
            for task in inner.store.all().map_err(store_error)? {
                if task.state == DownloadState::Paused {
                    inner.set_state(&task.id, DownloadState::Queued, None)?;
                }
            }
            inner.fill_slots().await;
            Ok(())
        });
    }

    /// Runs a queue command; a failure (typically a full disk on a DB write) is logged and, for a
    /// task command, shown on that task.
    fn command<F, Fut>(&self, id: Option<String>, block: F)
    where
        F: FnOnce(Arc<Inner>) -> Fut + Send + 'static,
        Fut: Future<Output = Result<(), DownloadError>> + Send + 'static,
    {
        let inner = self.inner.clone();
        self.inner.tokio.spawn(async move {
            if let Err(error) = block(inner.clone()).await {
                inner.command_failed(id, &error);
            }
        });
    }
}

impl Inner {
    /// Once per launch, lazily: what was running or queued is held as paused, and scratch folders
    /// no resumable task owns are deleted (app data the OS never trims).
    async fn init(self: &Arc<Self>) {
        self.initialized
            .get_or_init(|| async {
                if let Err(error) = self.store.pause_active_and_queued(now_millis()) {
                    self.log.warn("startup recovery could not pause running tasks", Some(&error));
                }
                self.prune_orphan_scratch();
            })
            .await;
    }

    fn prune_orphan_scratch(&self) {
        let resumable: Vec<String> = match self.store.all() {
            Ok(tasks) => tasks.into_iter().filter(|task| !task.state.is_terminal()).map(|task| task.id).collect(),
            Err(error) => {
                self.log.warn("scratch pruning skipped: the queue could not be read", Some(&error));
                return;
            }
        };
        match self.storage.scratch_task_ids() {
            Ok(owners) => {
                owners.iter().filter(|owner| !resumable.contains(owner)).for_each(|id| self.cleanup_scratch(id))
            }
            Err(error) => self.log.warn("scratch listing failed (orphans stay until next run)", Some(&error)),
        }
    }

    fn cleanup_scratch(&self, id: &str) {
        if let Err(error) = self.storage.delete_scratch(id) {
            self.log.warn(&format!("scratch cleanup failed for {id} (files stay until next run)"), Some(&error));
        }
    }

    fn emit(&self, change: DownloadChange) {
        // No subscriber is fine: the store holds the state.
        let _ = self.changes.send(change);
    }

    fn set_state(&self, id: &str, state: DownloadState, error: Option<TaskError>) -> Result<(), DownloadError> {
        self.store.update_state(id, state, error.as_ref(), now_millis()).map_err(store_error)?;
        self.emit(DownloadChange::StateChanged { id: id.to_owned(), state, error });
        Ok(())
    }

    fn task_log(&self, task: &DownloadTask) -> Logger {
        let mut props = Map::new();
        props.insert("id".to_owned(), Value::from(task.id.clone()));
        props.insert("videoId".to_owned(), Value::from(task.video_id.clone()));
        self.log.fork_with("task", props)
    }

    fn command_failed(&self, id: Option<String>, error: &DownloadError) {
        if error.is_environmental() {
            self.log.warn("download command failed", Some(error));
        } else {
            self.log.capture_error("download command failed", error);
        }
        if let Some(id) = id {
            self.emit(DownloadChange::StateChanged {
                id,
                state: DownloadState::Failed,
                error: Some(task_error(error)),
            });
        }
    }

    /// Starts queued tasks until the slots are full, unless the queue is held. Each pick is claimed
    /// as PREPARING under the lock so the next `next_queued` skips it.
    // Boxed: a finished run refills the slots, which start runs.
    fn fill_slots(self: &Arc<Self>) -> BoxFuture<'static, ()> {
        let inner = self.clone();
        Box::pin(async move {
            let mut slots = inner.slots.lock().await;
            if slots.queue_paused {
                return;
            }
            while slots.active.len() < inner.max_concurrent {
                let next = match inner.store.next_queued() {
                    Ok(Some(task)) => task,
                    Ok(None) => break,
                    Err(error) => {
                        inner.command_failed(None, &store_error(error));
                        break;
                    }
                };
                if slots.active.contains_key(&next.id) {
                    break;
                }
                if let Err(error) = inner.set_state(&next.id, DownloadState::Preparing, None) {
                    inner.command_failed(Some(next.id.clone()), &error);
                    break;
                }
                let run = inner.tokio.spawn(inner.clone().execute(next.clone()));
                slots.active.insert(next.id.clone(), run.abort_handle());
                inner.tokio.spawn(inner.clone().supervise(next, run));
            }
        })
    }

    /// Holds the slot until the run has fully unwound, and writes its end state in the same locked
    /// step that frees it: a task is resumable only once nothing of its run can touch its files.
    async fn supervise(self: Arc<Self>, task: DownloadTask, run: tokio::task::JoinHandle<Option<TaskError>>) {
        let (failure, interrupted) = match run.await {
            Ok(failure) => (failure, false),
            Err(error) if error.is_cancelled() => (None, true),
            Err(panic) => {
                let error = DownloadError::technical("R70035", format!("download run panicked: {panic}"));
                self.task_log(&task).capture_error("download run panicked", &error);
                (Some(task_error(&error)), false)
            }
        };
        {
            let mut slots = self.slots.lock().await;
            let target = slots.interrupts.remove(&task.id);
            let end = match self.store.get(&task.id) {
                Ok(Some(row)) if row.state == DownloadState::Completed => Ok(()),
                Ok(None) => Ok(()),
                Ok(Some(_)) if interrupted => {
                    let state = target.unwrap_or(DownloadState::Paused);
                    if state == DownloadState::Canceled {
                        self.cleanup_scratch(&task.id);
                    }
                    self.set_state(&task.id, state, None)
                }
                Ok(Some(_)) => match failure {
                    Some(error) => self.set_state(&task.id, DownloadState::Failed, Some(error)),
                    None => Ok(()),
                },
                Err(error) => Err(store_error(error)),
            };
            if let Err(error) = end {
                // The row could not take the end state: listeners still see it failed.
                self.command_failed(Some(task.id.clone()), &error);
            }
            slots.active.remove(&task.id);
        }
        self.fill_slots().await;
    }

    /// One run; returns the user-facing error on failure, already logged.
    async fn execute(self: Arc<Self>, task: DownloadTask) -> Option<TaskError> {
        let log = self.task_log(&task);
        let result = match (self.folder)() {
            Some(folder) => {
                let mut observer = Observer { inner: self.clone(), id: task.id.clone(), original: task.format };
                self.pipeline.run(&task, &folder, &mut observer).await
            }
            None => Err(DownloadError::FolderUnavailable(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                "no download folder chosen",
            ))),
        };
        match result {
            // No await from here on: the file is published, so a pause or cancel can't undo it.
            Ok(published) => {
                let now = now_millis();
                let recorded = self.store.update_completed(
                    &task.id,
                    &published.uri,
                    published.bytes,
                    published.loudness_gain_db,
                    now,
                );
                if let Err(error) = recorded {
                    let error = store_error(error);
                    log.capture_error("a published download could not be recorded", &error);
                    return Some(task_error(&error));
                }
                self.emit(DownloadChange::Completed {
                    id: task.id.clone(),
                    file_uri: published.uri.clone(),
                    bytes: published.bytes,
                });
                if let Some(hook) = &self.on_completed {
                    match self.store.get(&task.id) {
                        Ok(Some(row)) => hook(row, published),
                        Ok(None) => {}
                        Err(error) => log.warn("the completed task could not be read for the library", Some(&error)),
                    }
                }
                None
            }
            Err(error) => {
                if error.is_environmental() {
                    log.warn("download failed", Some(&error));
                } else {
                    log.capture_error("download failed", &error);
                }
                Some(task_error(&error))
            }
        }
    }
}

struct Observer {
    inner: Arc<Inner>,
    id: String,
    original: DownloadFormat,
}

impl Observer {
    fn state(&self, state: DownloadState) {
        if let Err(error) = self.inner.set_state(&self.id, state, None) {
            self.inner.log.warn("a download state could not be saved", Some(&error));
        }
    }
}

impl RunObserver for Observer {
    fn preparing(&mut self) {
        self.state(DownloadState::Preparing);
    }

    fn downloading(&mut self) {
        self.state(DownloadState::Downloading);
    }

    fn processing(&mut self) {
        self.state(DownloadState::Processing);
    }

    fn progress(&mut self, done: i64, total: i64) {
        match self.inner.store.update_progress(&self.id, done, total, now_millis()) {
            Ok(()) => self.inner.emit(DownloadChange::ProgressChanged { id: self.id.clone(), done, total }),
            Err(error) => self.inner.log.warn("download progress could not be saved", Some(&error)),
        }
    }

    fn streams(
        &mut self,
        format: DownloadFormat,
        audio: StreamId,
        video: Option<StreamId>,
    ) -> Result<(), DownloadError> {
        self.inner.store.update_streams(&self.id, format, audio, video, now_millis()).map_err(store_error)?;
        if format != self.original {
            self.inner.emit(DownloadChange::FormatChanged { id: self.id.clone(), format });
        }
        Ok(())
    }
}

fn store_error(error: rusqlite::Error) -> DownloadError {
    if let rusqlite::Error::SqliteFailure(failure, _) = &error
        && failure.code == rusqlite::ErrorCode::DiskFull
    {
        return DownloadError::DiskFull(std::io::Error::new(std::io::ErrorKind::StorageFull, error.to_string()));
    }
    DownloadError::technical("R70036", format!("download store: {error}"))
}

fn task_error(error: &DownloadError) -> TaskError {
    let user_message = match error {
        DownloadError::Engine(engine) => engine.details.as_ref().and_then(|details| details.user_message.clone()),
        _ => None,
    };
    TaskError { code: error.code(), user_message }
}
