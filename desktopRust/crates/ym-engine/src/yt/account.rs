//! Account, sign-in state, per-video details and comments, and playlist edits: calls that act as
//! (or for) the signed-in viewer.

use serde_json::{Map, Value, json};
use ym_common::log::Logger;

use super::model::*;
use super::{args, hop, text};
use crate::engine::DynamicEngine;
use crate::error::EngineError;

impl DynamicEngine {
    async fn ack(&self, command: &str, args: Map<String, Value>, log: &Logger) -> Result<(), EngineError> {
        let _: Value = self.json_command(command, args, log).await?;
        Ok(())
    }

    /// The cookie-only sign-in gate; never calls YouTube's API mid-login.
    pub async fn login_status(&self, log: &Logger) -> Result<bool, EngineError> {
        let result: SignedIn = self.json_command("yt/login/status", Map::new(), &hop(log, "login_status", [])).await?;
        Ok(result.signed_in)
    }

    /// Where a browser signs in.
    pub async fn login_url(&self, log: &Logger) -> Result<String, EngineError> {
        let result: UrlResult = self.json_command("yt/login/url", Map::new(), &hop(log, "login_url", [])).await?;
        Ok(result.url)
    }

    pub async fn switch_account_url(&self, log: &Logger) -> Result<String, EngineError> {
        let result: UrlResult =
            self.json_command("yt/login/switch-url", Map::new(), &hop(log, "switch_account_url", [])).await?;
        Ok(result.url)
    }

    pub async fn verify_target(&self, log: &Logger) -> Result<YtVerifyTarget, EngineError> {
        self.json_command("yt/verify/target", Map::new(), &hop(log, "verify_target", [])).await
    }

    /// The account card; `refresh` re-reads it from YouTube.
    pub async fn account_info(&self, refresh: bool, log: &Logger) -> Result<YtAccountInfo, EngineError> {
        let refresh = refresh.then_some(Value::Bool(true));
        self.json_command("yt/account", args([("refresh", refresh)]), &hop(log, "account_info", [])).await
    }

    pub async fn logout(&self, log: &Logger) -> Result<(), EngineError> {
        self.ack("yt/logout", Map::new(), &hop(log, "logout", [])).await
    }

    pub async fn refresh_session(&self, log: &Logger) -> Result<(), EngineError> {
        self.ack("yt/session/refresh", Map::new(), &hop(log, "refresh_session", [])).await
    }

    /// Hands the engine the watch-next payload the player saw for a music video, verbatim; it
    /// feeds the Recommended feed. Fire and forget: a failure must not disturb playback.
    pub async fn harvest_watch_next(&self, video_id: &str, contents: &str, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "harvest_watch_next", [("videoId", Some(video_id))]);
        self.ack("yt/webview-next", args([("videoId", text(Some(video_id))), ("contents", text(Some(contents)))]), &log)
            .await
    }

    pub async fn subscribe_channel(&self, channel_id: &str, subscribed: bool, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "subscribe_channel", [("channelId", Some(channel_id))]);
        let fields = [("channelId", text(Some(channel_id))), ("subscribed", Some(Value::Bool(subscribed)))];
        self.ack("yt/channel/subscribe", args(fields), &log).await
    }

    pub async fn save_playlist(&self, playlist_id: &str, saved: bool, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "save_playlist", [("playlistId", Some(playlist_id))]);
        let fields = [("playlistId", text(Some(playlist_id))), ("saved", Some(Value::Bool(saved)))];
        self.ack("yt/playlist/save", args(fields), &log).await
    }

    pub async fn video_details(&self, video_id: &str, log: &Logger) -> Result<YtVideoDetails, EngineError> {
        let log = hop(log, "video_details", [("videoId", Some(video_id))]);
        self.json_command("yt/video-details", args([("videoId", text(Some(video_id)))]), &log).await
    }

    /// `status` is the target `LIKE` / `DISLIKE` / `INDIFFERENT`, `token` its rate token.
    pub async fn rate_video(&self, video_id: &str, status: &str, token: &str, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "rate_video", [("videoId", Some(video_id)), ("status", Some(status))]);
        let fields = [("videoId", text(Some(video_id))), ("status", text(Some(status))), ("token", text(Some(token)))];
        self.ack("yt/video/rate", args(fields), &log).await
    }

    pub async fn comments(&self, continuation: &str, log: &Logger) -> Result<YtCommentPage, EngineError> {
        let log = hop(log, "comments", [("continuation", Some(continuation))]);
        self.json_command("yt/comments", args([("continuation", text(Some(continuation)))]), &log).await
    }

    pub async fn create_comment(&self, create_token: &str, body: &str, log: &Logger) -> Result<YtComment, EngineError> {
        let fields = [("token", text(Some(create_token))), ("text", text(Some(body)))];
        self.json_command("yt/comment/create", args(fields), &hop(log, "create_comment", [])).await
    }

    pub async fn reply_to_comment(
        &self,
        reply_token: &str,
        body: &str,
        log: &Logger,
    ) -> Result<YtComment, EngineError> {
        let fields = [("token", text(Some(reply_token))), ("text", text(Some(body)))];
        self.json_command("yt/comment/reply", args(fields), &hop(log, "reply_to_comment", [])).await
    }

    pub async fn edit_comment(&self, edit_token: &str, body: &str, log: &Logger) -> Result<(), EngineError> {
        let fields = [("token", text(Some(edit_token))), ("text", text(Some(body)))];
        self.ack("yt/comment/edit", args(fields), &hop(log, "edit_comment", [])).await
    }

    /// Like / unlike / dislike / delete: the comment's token for the action.
    pub async fn comment_action(&self, token: &str, log: &Logger) -> Result<(), EngineError> {
        self.ack("yt/comment/action", args([("token", text(Some(token)))]), &hop(log, "comment_action", [])).await
    }

    pub async fn playlist_targets(&self, video_id: &str, log: &Logger) -> Result<Vec<YtPlaylistTarget>, EngineError> {
        let log = hop(log, "playlist_targets", [("videoId", Some(video_id))]);
        let result: PlaylistTargets =
            self.json_command("yt/playlist/targets", args([("videoId", text(Some(video_id)))]), &log).await?;
        Ok(result.playlists)
    }

    pub async fn add_to_playlist(&self, playlist_id: &str, video_id: &str, log: &Logger) -> Result<(), EngineError> {
        let log = hop(log, "add_to_playlist", [("playlistId", Some(playlist_id)), ("videoId", Some(video_id))]);
        let fields = [("playlistId", text(Some(playlist_id))), ("videoId", text(Some(video_id)))];
        self.ack("yt/playlist/add", args(fields), &log).await
    }

    /// `remove_token` is the item's `remove_token` on the viewer's own playlist page.
    pub async fn remove_from_playlist(&self, remove_token: &str, log: &Logger) -> Result<(), EngineError> {
        self.ack(
            "yt/playlist/remove",
            args([("token", text(Some(remove_token)))]),
            &hop(log, "remove_from_playlist", []),
        )
        .await
    }

    /// Returns the new playlist's id.
    pub async fn create_playlist(
        &self,
        title: &str,
        privacy: &str,
        video_ids: &[String],
        log: &Logger,
    ) -> Result<String, EngineError> {
        let count = video_ids.len().to_string();
        let log = hop(log, "create_playlist", [("privacy", Some(privacy)), ("videos", Some(count.as_str()))]);
        let fields =
            [("title", text(Some(title))), ("privacy", text(Some(privacy))), ("videoIds", Some(json!(video_ids)))];
        let result: CreatedPlaylist = self.json_command("yt/playlist/create", args(fields), &log).await?;
        Ok(result.playlist_id)
    }
}
