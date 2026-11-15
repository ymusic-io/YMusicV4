//! The bundle's wire shapes (engine-js `logic/yt/wire.ts`), read tolerantly: unknown fields are
//! skipped and absent or null fields default, so a newer bundle never breaks an older app.

use serde::{Deserialize, Deserializer};

fn null_default<'de, D: Deserializer<'de>, T: Default + Deserialize<'de>>(deserializer: D) -> Result<T, D::Error> {
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

fn present_ints<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<i32>, D::Error> {
    let values: Option<Vec<Option<i32>>> = Option::deserialize(deserializer)?;
    Ok(values.unwrap_or_default().into_iter().flatten().collect())
}

macro_rules! wire {
    ($(#[$meta:meta])* $vis:vis struct $name:ident { $($(#[$field_meta:meta])* $field_vis:vis $field:ident : $ty:ty),* $(,)? }) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Default, PartialEq, Deserialize)]
        #[serde(rename_all = "camelCase", default)]
        $vis struct $name {
            $($(#[$field_meta])* #[serde(deserialize_with = "null_default")] $field_vis $field: $ty),*
        }
    };
}

wire! {
    /// One thumbnail variant; lists arrive in ascending size.
    pub struct YtThumb {
        pub url: String,
        pub w: Option<i32>,
        pub h: Option<i32>,
    }
}

wire! {
    /// A generic display item: the UI renders `title` and the subtitle lines verbatim.
    pub struct YtFeedItem {
        /// Open set: `video`, `playlist`, `channel`, `tag`, or a newer kind to ignore.
        pub kind: String,
        pub id: String,
        pub title: String,
        pub subtitle_line1: Option<String>,
        pub subtitle_line2: Option<String>,
        pub channel_id: Option<String>,
        pub channel_title: Option<String>,
        pub channel_url: Option<String>,
        /// The engine-composed app link to this item's page, passed back verbatim.
        pub url: Option<String>,
        pub thumbs: Vec<YtThumb>,
        pub duration: Option<String>,
        pub duration_ms: Option<i64>,
        pub item_count: Option<String>,
        pub live: bool,
        pub music_video: bool,
        pub watched_percent: Option<i32>,
        pub album: bool,
        pub owner_badge: Option<String>,
        /// `AARRGGBB` accent of a tag.
        pub accent: Option<String>,
        pub remove_token: Option<String>,
    }
}

wire! {
    pub struct YtChip {
        pub title: String,
        pub token: Option<String>,
        pub selected: bool,
    }
}

wire! {
    pub struct YtFilterOption {
        pub label: String,
        pub params: Option<String>,
        pub selected: bool,
        pub disabled: bool,
    }
}

wire! {
    pub struct YtFilterGroup {
        pub title: String,
        pub options: Vec<YtFilterOption>,
    }
}

wire! {
    pub struct YtPageHeader {
        pub title: Option<String>,
        pub subtitle_line1: Option<String>,
        pub subtitle_line2: Option<String>,
        pub thumbs: Vec<YtThumb>,
        pub banner: Vec<YtThumb>,
        pub item_count: Option<i32>,
        pub channel_id: Option<String>,
        pub subscribed: Option<bool>,
        pub save_playlist_id: Option<String>,
        pub saved: Option<bool>,
    }
}

wire! {
    pub struct YtPageTab {
        pub title: String,
        pub params: Option<String>,
        pub url: Option<String>,
        pub home: bool,
        pub selected: bool,
    }
}

wire! {
    pub struct YtSectionHeader {
        pub title: Option<String>,
        /// Set only on an engine-synthesized title: a key the app localizes, `title` the fallback.
        pub title_key: Option<String>,
        pub icon_thumbs: Vec<YtThumb>,
        pub count: Option<String>,
        pub cta_text: Option<String>,
        pub cta_token: Option<String>,
        pub cta_deeplink: Option<String>,
        /// `playAll`: the CTA plays the section instead of navigating.
        pub cta_action: Option<String>,
    }
}

/// A run of items under one header.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct YtSection {
    #[serde(deserialize_with = "null_default")]
    pub header: Option<YtSectionHeader>,
    /// Intent: `list`, `grid`, `tags`, `carousel`, or newer; the app re-resolves by width.
    #[serde(deserialize_with = "null_default")]
    pub layout: String,
    #[serde(deserialize_with = "null_default")]
    pub items: Vec<YtFeedItem>,
    #[serde(deserialize_with = "null_default")]
    pub collapsible: bool,
    /// Rows a collapsed section keeps per resolved column count; the last entry extends.
    #[serde(deserialize_with = "present_ints")]
    pub collapsed_rows: Vec<i32>,
    #[serde(deserialize_with = "null_default")]
    pub continuation: Option<String>,
    /// `queue`: a video tap queues the section; absent = watch-next radio.
    #[serde(deserialize_with = "null_default")]
    pub play_mode: Option<String>,
}

impl Default for YtSection {
    fn default() -> Self {
        Self {
            header: None,
            layout: "list".into(),
            items: Vec::new(),
            collapsible: false,
            collapsed_rows: Vec::new(),
            continuation: None,
            play_mode: None,
        }
    }
}

wire! {
    /// One page of any feed surface; `continuation` fetches the next.
    pub struct YtFeedPage {
        pub sections: Vec<YtSection>,
        pub continuation: Option<String>,
        pub chips: Vec<YtChip>,
        pub sort: Vec<YtChip>,
        pub chips_wrap: bool,
        pub empty_title: Option<String>,
        pub empty_message: Option<String>,
        pub filter_groups: Vec<YtFilterGroup>,
        pub page_header: Option<YtPageHeader>,
        pub page_tabs: Vec<YtPageTab>,
        pub autoplay_video_id: Option<String>,
        /// Part of the page failed: the code to show as a transient message.
        pub partial_error_code: Option<String>,
    }
}

wire! {
    pub(crate) struct Suggestions {
        pub suggestions: Vec<String>,
    }
}

wire! {
    pub struct YtLocaleOption {
        pub code: String,
        pub label: String,
    }
}

wire! {
    /// The working region and the menus YouTube offers, labelled in the effective language.
    pub struct YtLocales {
        pub gl: String,
        pub languages: Vec<YtLocaleOption>,
        pub regions: Vec<YtLocaleOption>,
    }
}

wire! {
    pub(crate) struct WireLinkTarget {
        pub kind: String,
        pub item: Option<YtFeedItem>,
        pub url: Option<String>,
        pub start_ms: Option<i64>,
        pub query: Option<String>,
    }
}

/// What a shared YouTube link opens.
#[derive(Debug, Clone, PartialEq)]
pub enum YtLinkTarget {
    Video { item: Box<YtFeedItem>, playlist_url: Option<String>, start_ms: Option<i64> },
    Playlist(String),
    Channel(String),
    MusicBrowse(String),
    Search(String),
}

impl WireLinkTarget {
    pub(crate) fn into_target(self) -> Option<YtLinkTarget> {
        match self.kind.as_str() {
            "video" => self.item.map(|item| YtLinkTarget::Video {
                item: Box::new(item),
                playlist_url: self.url,
                start_ms: self.start_ms,
            }),
            "playlist" => self.url.map(YtLinkTarget::Playlist),
            "channel" => self.url.map(YtLinkTarget::Channel),
            "music" => self.url.map(YtLinkTarget::MusicBrowse),
            "search" => self.query.map(YtLinkTarget::Search),
            _ => None,
        }
    }
}

wire! {
    /// The signed-in account card; `signed_in` is the cookie-gate truth, the rest display metadata.
    pub struct YtAccountInfo {
        pub signed_in: bool,
        pub name: Option<String>,
        pub email: Option<String>,
        pub handle: Option<String>,
        pub channel_id: Option<String>,
        pub thumbs: Vec<YtThumb>,
    }
}

wire! {
    pub(crate) struct SignedIn {
        pub signed_in: bool,
    }
}

wire! {
    pub(crate) struct UrlResult {
        pub url: String,
    }
}

wire! {
    /// Where a bot challenge is cleared: a browser on `url` presenting `user_agent`.
    pub struct YtVerifyTarget {
        pub url: String,
        pub user_agent: String,
    }
}

wire! {
    pub struct YtFactoid {
        pub value: String,
        pub label: String,
    }
}

wire! {
    pub struct YtVideoDetails {
        pub title: Option<String>,
        pub channel_title: Option<String>,
        pub channel_url: Option<String>,
        pub channel_thumbs: Vec<YtThumb>,
        pub subscribers: Option<String>,
        pub factoids: Vec<YtFactoid>,
        pub like_count: Option<String>,
        pub like_count_liked: Option<String>,
        /// `LIKE`, `DISLIKE` or `INDIFFERENT` (empty reads as indifferent).
        pub like_status: String,
        /// The rating token per target status.
        pub rate_tokens: std::collections::BTreeMap<String, String>,
        pub description: Option<String>,
        pub comments_count: Option<String>,
        pub comments_continuation: Option<String>,
    }
}

wire! {
    pub struct YtComment {
        pub id: String,
        pub text: String,
        pub author: Option<String>,
        pub author_thumbs: Vec<YtThumb>,
        pub author_channel_url: Option<String>,
        pub published: Option<String>,
        pub likes: Option<String>,
        pub likes_liked: Option<String>,
        pub liked: bool,
        pub disliked: bool,
        pub like_token: Option<String>,
        pub unlike_token: Option<String>,
        pub dislike_token: Option<String>,
        pub undislike_token: Option<String>,
        pub delete_token: Option<String>,
        pub edit_token: Option<String>,
        pub reply_token: Option<String>,
        pub reply_placeholder: Option<String>,
        pub reply_count: Option<String>,
        pub replies_continuation: Option<String>,
        pub pinned: Option<String>,
        pub hearted: bool,
        pub by_creator: bool,
        pub verified: bool,
    }
}

wire! {
    pub struct YtCommentPage {
        pub count_text: Option<String>,
        pub sort: Vec<YtChip>,
        pub composer_placeholder: Option<String>,
        pub create_token: Option<String>,
        pub comments: Vec<YtComment>,
        pub continuation: Option<String>,
    }
}

wire! {
    /// One of the viewer's playlists a video can be saved to.
    pub struct YtPlaylistTarget {
        pub playlist_id: String,
        pub title: String,
        /// `PUBLIC`, `UNLISTED` or `PRIVATE`.
        pub privacy: Option<String>,
        pub contains_video: bool,
    }
}

wire! {
    pub(crate) struct PlaylistTargets {
        pub playlists: Vec<YtPlaylistTarget>,
    }
}

wire! {
    pub(crate) struct CreatedPlaylist {
        pub playlist_id: String,
    }
}
