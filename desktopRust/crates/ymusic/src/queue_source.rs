//! Where a queue's further items come from (the JVM `QueueSource` / `QueueMore` and commonUi
//! `NextQueueSource`): a video's radio, or a playlist page's list past what the page had loaded,
//! growing through the engine's `yt/queue` (/next) pages.

use std::collections::HashSet;
use std::future::Future;
use std::sync::Arc;

use serde_json::Value;
use ym_common::log::Logger;
use ym_engine::yt::{YtFeedItem, YtFeedPage};
use ym_engine::{DynamicEngine, EngineError, ErrorKind};
use ym_i18n::{Str, t, tf};

use crate::playback::QueueItem;

/// A queue page failed without an engine error of its own (the JVM E60063).
pub const QUEUE_PAGE_UNCODED: &str = "R91200";

const STEP_RADIO: &str = "radio";
const STEP_PLAYLIST: &str = "playlist";
const STEP_CONTINUE: &str = "next";

#[derive(Debug, Clone, PartialEq)]
enum Step {
    Radio {
        video_id: String,
    },
    /// `url` is a playlist page's engine link, round-tripped verbatim.
    Playlist {
        url: String,
        video_id: String,
        index: i64,
    },
    Continue {
        token: String,
    },
}

/// The queue source's side of the queue: `total_count` becomes the loaded size once it ends;
/// `resume_token` is the source's latest [`NextQueueSource::resume_token`].
#[derive(Debug, Clone, PartialEq)]
pub struct QueueMore {
    pub total_count: Option<i32>,
    pub has_more: bool,
    pub loading: bool,
    /// The last page's failure, as the code its text is rendered from.
    pub error: Option<String>,
    pub resume_token: Option<String>,
}

/// A page's new items; `items` may be empty (a page of duplicates) while `has_more` is still true.
pub struct QueueSourcePage {
    pub items: Vec<QueueItem>,
    pub has_more: bool,
}

/// Videos already queued are skipped; it ends when a page brings no continuation (a radio YouTube
/// can't start ends after its related videos).
pub struct NextQueueSource {
    step: Option<Step>,
    total_count: Option<i32>,
    seen: HashSet<String>,
    log: Logger,
}

impl NextQueueSource {
    fn new(step: Step, total_count: Option<i32>, queued: impl IntoIterator<Item = String>, log: Logger) -> Self {
        Self { step: Some(step), total_count, seen: queued.into_iter().collect(), log }
    }

    /// `video_id`'s radio, after the video itself (already queued).
    pub fn radio(video_id: &str, log: Logger) -> Self {
        Self::new(Step::Radio { video_id: video_id.to_owned() }, None, [video_id.to_owned()], log)
    }

    /// A playlist page's list past `loaded` (the page's videos, in order); None when nothing was loaded.
    pub fn playlist(url: String, loaded: &[YtFeedItem], total_count: Option<i32>, log: Logger) -> Option<Self> {
        let last = loaded.last()?;
        let step = Step::Playlist { url, video_id: last.id.clone(), index: loaded.len() as i64 - 1 };
        Some(Self::new(step, total_count, loaded.iter().map(|item| item.id.clone()), log))
    }

    /// The whole list's length, loaded or not, when the source knows it.
    pub fn total_count(&self) -> Option<i32> {
        self.total_count
    }

    /// Where the list continues, in the JVM app's JSON so either app resumes the other's saved
    /// queue; None once the list ended.
    pub fn resume_token(&self) -> Option<String> {
        let quoted = |text: &str| Value::from(text).to_string();
        let total = self.total_count.map_or_else(|| "null".to_owned(), |total| total.to_string());
        Some(match self.step.as_ref()? {
            Step::Radio { video_id } => {
                format!(r#"{{"step":"{STEP_RADIO}","videoId":{},"total":{total}}}"#, quoted(video_id))
            }
            Step::Playlist { url, video_id, index } => format!(
                r#"{{"step":"{STEP_PLAYLIST}","url":{},"videoId":{},"index":{index},"total":{total}}}"#,
                quoted(url),
                quoted(video_id)
            ),
            Step::Continue { token } => {
                format!(r#"{{"step":"{STEP_CONTINUE}","continuation":{},"total":{total}}}"#, quoted(token))
            }
        })
    }

    /// The next page's request; None once the list ended.
    pub fn fetch(
        &self,
        engine: Arc<DynamicEngine>,
    ) -> Option<impl Future<Output = Result<YtFeedPage, EngineError>> + Send + 'static> {
        let (step, log) = (self.step.clone()?, self.log.clone());
        Some(async move {
            let result = match &step {
                Step::Radio { video_id } => engine.start_radio_queue(video_id, &log).await,
                Step::Playlist { url, video_id, index } => engine.playlist_queue(url, video_id, *index, &log).await,
                Step::Continue { token } => engine.queue_continuation(token, &log).await,
            };
            if let Err(error) = &result {
                log.warn("queue page failed", Some(error));
            }
            result
        })
    }

    /// Takes in a page [`Self::fetch`] brought: the list continues at its continuation.
    pub fn accept(&mut self, page: YtFeedPage) -> QueueSourcePage {
        self.step = page.continuation.map(|token| Step::Continue { token });
        if let Some(count) = page.page_header.as_ref().and_then(|header| header.item_count) {
            self.total_count = Some(count);
        }
        let items = page
            .sections
            .iter()
            .flat_map(|section| section.items.iter())
            .filter(|item| item.kind == "video" && self.seen.insert(item.id.clone()))
            .map(QueueItem::from_feed)
            .collect();
        QueueSourcePage { items, has_more: self.step.is_some() }
    }
}

/// The code a failed page's footer shows: the network category, else the engine's code.
pub fn error_code(error: &EngineError) -> String {
    if matches!(error.kind, ErrorKind::Network) { "network".to_owned() } else { error.code.clone() }
}

/// The text for a failed page's code (the JVM `errorMessage`).
pub fn error_text(code: &str) -> String {
    match code {
        "network" => t(Str::ErrorNetwork).to_owned(),
        "captcha" => t(Str::ErrorCaptcha).to_owned(),
        "supervised_account" => t(Str::ErrorSupervisedAccount).to_owned(),
        _ => tf(Str::ErrorGeneric, &[&code]),
    }
}
