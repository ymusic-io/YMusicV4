//! The YouTube extraction surface: intent-level calls to the bundle's `yt/*` and `ytmusic/*`
//! commands. The app states what it wants; app links, continuations and params are opaque tokens
//! the engine composed, passed back verbatim.

mod account;
mod model;

pub use model::*;
use serde_json::{Map, Value};
use ym_common::log::Logger;

use crate::engine::DynamicEngine;
use crate::error::EngineError;

/// Call args: absent optional fields are omitted, never null.
fn args<const N: usize>(fields: [(&str, Option<Value>); N]) -> Map<String, Value> {
    fields.into_iter().filter_map(|(key, value)| Some((key.to_owned(), value?))).collect()
}

fn text(value: Option<&str>) -> Option<Value> {
    value.map(Value::from)
}

/// This call's own log hop. Free-text user input (a search query) is never a prop: props reach
/// crash reports. Opaque tokens are truncated.
fn hop<const N: usize>(log: &Logger, tag: &str, props: [(&str, Option<&str>); N]) -> Logger {
    let props: Map<String, Value> = props
        .into_iter()
        .filter_map(|(key, value)| Some((key.to_owned(), Value::from(value?.chars().take(100).collect::<String>()))))
        .collect();
    log.fork_with(tag, props)
}

impl DynamicEngine {
    async fn feed(&self, command: &str, args: Map<String, Value>, log: Logger) -> Result<YtFeedPage, EngineError> {
        self.json_command(command, args, &log).await
    }

    /// The Discover home feed; `refresh` re-scrapes the homepage for a user reload.
    pub async fn home_feed(
        &self,
        continuation: Option<&str>,
        refresh: bool,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "home_feed", [("continuation", continuation)]);
        let refresh = refresh.then_some(Value::Bool(true));
        self.feed("yt/home", args([("continuation", text(continuation)), ("refresh", refresh)]), log).await
    }

    /// The signed-in Library feed; call only behind the sign-in gate.
    pub async fn account_library(&self, continuation: Option<&str>, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "account_library", [("continuation", continuation)]);
        self.feed("yt/library", args([("continuation", text(continuation))]), log).await
    }

    /// Built from harvested watch-next payloads; empty until something has played.
    pub async fn recommended_feed(&self, log: &Logger) -> Result<YtFeedPage, EngineError> {
        self.feed("yt/recommended", Map::new(), hop(log, "recommended_feed", [])).await
    }

    /// The music charts for the engine-resolved region; section titles carry a `title_key`.
    pub async fn trending_music_feed(&self, log: &Logger) -> Result<YtFeedPage, EngineError> {
        self.feed("yt/trending", Map::new(), hop(log, "trending_music_feed", [])).await
    }

    pub async fn playlist_detail(
        &self,
        url: &str,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "playlist_detail", [("url", Some(url)), ("continuation", continuation)]);
        self.feed("yt/playlist", args([("url", text(Some(url))), ("continuation", text(continuation))]), log).await
    }

    pub async fn channel_detail(
        &self,
        url: &str,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "channel_detail", [("url", Some(url)), ("continuation", continuation)]);
        self.feed("yt/channel", args([("url", text(Some(url))), ("continuation", text(continuation))]), log).await
    }

    pub async fn search(
        &self,
        query: Option<&str>,
        params: Option<&str>,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "search", [("params", params), ("continuation", continuation)]);
        let fields = [("query", text(query)), ("params", text(params)), ("continuation", text(continuation))];
        self.feed("yt/search", args(fields), log).await
    }

    pub async fn search_suggestions(&self, query: &str, log: &Logger) -> Result<Vec<String>, EngineError> {
        let log = hop(log, "search_suggestions", []);
        let result: Suggestions = self.json_command("yt/suggest", args([("query", text(Some(query)))]), &log).await?;
        Ok(result.suggestions)
    }

    /// Related items and `autoplay_video_id` for a video, or a continuation of them.
    pub async fn watch_next(
        &self,
        video_id: Option<&str>,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "watch_next", [("videoId", video_id), ("continuation", continuation)]);
        self.feed("yt/next", args([("videoId", text(video_id)), ("continuation", text(continuation))]), log).await
    }

    /// The play queue's first page for a video: its radio, else its related videos.
    pub async fn start_radio_queue(&self, video_id: &str, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "start_radio_queue", [("videoId", Some(video_id))]);
        self.feed("yt/queue", args([("videoId", text(Some(video_id)))]), log).await
    }

    /// The queue continuing a playlist page's list after `video_id`, the item at `index`.
    pub async fn playlist_queue(
        &self,
        url: &str,
        video_id: &str,
        index: i64,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "playlist_queue", [("url", Some(url)), ("videoId", Some(video_id))]);
        let fields = [("url", text(Some(url))), ("videoId", text(Some(video_id))), ("index", Some(Value::from(index)))];
        self.feed("yt/queue", args(fields), log).await
    }

    pub async fn queue_continuation(&self, continuation: &str, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "queue_continuation", [("continuation", Some(continuation))]);
        self.feed("yt/queue", args([("continuation", text(Some(continuation)))]), log).await
    }

    /// Notify a region pick (`gl`, blank = automatic) or a language change (`None`).
    pub async fn apply_locale(&self, gl: Option<&str>, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "apply_locale", [("gl", gl)]);
        let _: Value = self.json_command("yt/locale/apply", args([("gl", text(gl))]), &log).await?;
        Ok(())
    }

    pub async fn locale_options(&self, log: &Logger) -> Result<YtLocales, EngineError> {
        self.json_command("yt/locales", Map::new(), &hop(log, "locale_options", [])).await
    }

    /// What a shared YouTube link opens; `None` when the engine doesn't recognize it.
    pub async fn resolve_link(&self, url: &str, log: &Logger) -> Result<Option<YtLinkTarget>, EngineError> {
        // The url may carry a search query (user input): never a prop.
        let log = hop(log, "resolve_link", []);
        let target: WireLinkTarget =
            self.json_command("yt/resolve-link", args([("url", text(Some(url)))]), &log).await?;
        Ok(target.into_target())
    }

    pub async fn music_home(
        &self,
        continuation: Option<&str>,
        refresh: bool,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_home", [("continuation", continuation)]);
        let refresh = refresh.then_some(Value::Bool(true));
        self.feed("ytmusic/home", args([("continuation", text(continuation)), ("refresh", refresh)]), log).await
    }

    pub async fn music_explore(&self, continuation: Option<&str>, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_explore", [("continuation", continuation)]);
        self.feed("ytmusic/explore", args([("continuation", text(continuation))]), log).await
    }

    pub async fn music_new_releases(
        &self,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_new_releases", [("continuation", continuation)]);
        self.feed("ytmusic/new-releases", args([("continuation", text(continuation))]), log).await
    }

    pub async fn music_charts(&self, continuation: Option<&str>, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_charts", [("continuation", continuation)]);
        self.feed("ytmusic/charts", args([("continuation", text(continuation))]), log).await
    }

    pub async fn music_library(&self, continuation: Option<&str>, log: &Logger) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_library", [("continuation", continuation)]);
        self.feed("ytmusic/library", args([("continuation", text(continuation))]), log).await
    }

    pub async fn music_browse(
        &self,
        url: &str,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_browse", [("url", Some(url)), ("continuation", continuation)]);
        self.feed("ytmusic/browse", args([("url", text(Some(url))), ("continuation", text(continuation))]), log).await
    }

    pub async fn music_search(
        &self,
        query: Option<&str>,
        params: Option<&str>,
        continuation: Option<&str>,
        log: &Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let log = hop(log, "music_search", [("params", params), ("continuation", continuation)]);
        let fields = [("query", text(query)), ("params", text(params)), ("continuation", text(continuation))];
        self.feed("ytmusic/search", args(fields), log).await
    }

    pub async fn music_search_suggestions(&self, query: &str, log: &Logger) -> Result<Vec<String>, EngineError> {
        let log = hop(log, "music_search_suggestions", []);
        let result: Suggestions =
            self.json_command("ytmusic/suggest", args([("query", text(Some(query)))]), &log).await?;
        Ok(result.suggestions)
    }
}
