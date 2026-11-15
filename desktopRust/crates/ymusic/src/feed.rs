//! A feed surface: one engine source (a Discover tab, search results, a playlist / channel / music
//! page), paged by continuation, rendered as titled sections of display items.

use std::sync::Arc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{
    ActiveTheme, Disableable, Icon, IconName, Selectable, Sizable, StyledExt, WindowExt, h_flex, v_flex,
};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_common::log::Logger;
use ym_engine::yt::{YtFeedItem, YtFeedPage, YtFilterGroup, YtPageHeader, YtSectionHeader, YtThumb};
use ym_engine::{DynamicEngine, EngineError, ErrorKind};
use ym_i18n::{Str, t, tf};

use crate::a11y::{DragRow, FocusList, alert, announced, announced_as, heading, list_item};
use crate::action_icon::{HIT, action_icon, sized_action_icon};
use crate::download_dialog::DownloadTarget;
use crate::graph::{AppGraph, on_core};
use crate::local_playlist::LocalPlaylistFromDownloads;
use crate::offline::{OfflineMarks, offline_mark};
use crate::playlist_download::PlaylistDownload;
use crate::quick_download::saved_preset;
use crate::settings_view::{HOVER_ALPHA, StackTones};
use crate::spinner::{BusyButton, LoadingLine, Spinner};
use crate::tab_row::tab_row;

#[derive(Debug, Clone, PartialEq)]
pub enum FeedSource {
    Home,
    Trending,
    Recommended,
    MusicHome,
    MusicExplore,
    MusicNewReleases,
    MusicCharts,
    /// The signed-in account's YT Music library.
    MusicLibrary,
    /// The most-watched YouTube videos from play history.
    TopTracks,
    /// The signed-in account's library.
    Account,
    Playlist(String),
    Channel(String),
    MusicBrowse(String),
    /// A query and the filter params picked for it.
    Search(String, Option<String>),
    MusicSearch(String, Option<String>),
}

impl FeedSource {
    /// The JVM app's per-surface empty text, for a page that brings none of its own.
    fn empty_message(&self) -> &'static str {
        t(match self {
            Self::Home => Str::DiscoverNoAnonymousHome,
            Self::Trending => Str::DiscoverNoTrending,
            Self::Recommended => Str::DiscoverNoRecommendations,
            Self::TopTracks => Str::DiscoverNoTopTracks,
            Self::Account => Str::AccountLibraryEmpty,
            Self::MusicHome | Self::MusicNewReleases | Self::MusicCharts => Str::YtmusicHomeEmpty,
            Self::MusicExplore => Str::YtmusicExploreEmpty,
            Self::MusicLibrary => Str::YtmusicLibraryEmpty,
            Self::Search(..) | Self::MusicSearch(..) => Str::SearchNoResults,
            Self::Playlist(_) | Self::Channel(_) | Self::MusicBrowse(_) => Str::FeedEmpty,
        })
    }

    /// Routes an engine-composed app link by its path prefix only; the whole link goes back to the
    /// engine verbatim. `None` = a scheme this build can't open (a newer bundle's), left inert.
    pub fn from_link(link: &str) -> Option<Self> {
        if link.starts_with("/ytmusic/browse") {
            Some(Self::MusicBrowse(link.to_owned()))
        } else if link.starts_with("/playlist?") {
            Some(Self::Playlist(link.to_owned()))
        } else if link.len() > "/channel/".len() && link.starts_with("/channel/")
            || link.len() > "/feed/".len() && link.starts_with("/feed/")
        {
            Some(Self::Channel(link.to_owned()))
        } else {
            None
        }
    }

    pub(crate) async fn load(
        self,
        engine: Arc<DynamicEngine>,
        history: Arc<ym_history::PlayHistory>,
        continuation: Option<String>,
        log: Logger,
    ) -> Result<YtFeedPage, EngineError> {
        self.fetch(engine, history, continuation, false, log).await
    }

    /// A user's reload: Home and YT Music Home bypass the engine's held session, as in the JVM app.
    pub(crate) async fn reload(
        self,
        engine: Arc<DynamicEngine>,
        history: Arc<ym_history::PlayHistory>,
        log: Logger,
    ) -> Result<YtFeedPage, EngineError> {
        self.fetch(engine, history, None, true, log).await
    }

    async fn fetch(
        self,
        engine: Arc<DynamicEngine>,
        history: Arc<ym_history::PlayHistory>,
        continuation: Option<String>,
        refresh: bool,
        log: Logger,
    ) -> Result<YtFeedPage, EngineError> {
        let continuation = continuation.as_deref();
        match &self {
            Self::TopTracks => top_tracks(history).await,
            Self::Account => engine.account_library(continuation, &log).await,
            Self::Home => engine.home_feed(continuation, refresh, &log).await,
            Self::Trending => engine.trending_music_feed(&log).await,
            Self::Recommended => engine.recommended_feed(&log).await,
            Self::MusicHome => engine.music_home(continuation, refresh, &log).await,
            Self::MusicExplore => engine.music_explore(continuation, &log).await,
            Self::MusicNewReleases => engine.music_new_releases(continuation, &log).await,
            Self::MusicCharts => engine.music_charts(continuation, &log).await,
            Self::MusicLibrary => engine.music_library(continuation, &log).await,
            Self::Playlist(url) => engine.playlist_detail(url, continuation, &log).await,
            Self::Channel(url) => engine.channel_detail(url, continuation, &log).await,
            Self::MusicBrowse(url) => engine.music_browse(url, continuation, &log).await,
            Self::Search(query, params) => {
                let (query, params) = first_page(continuation, query, params);
                engine.search(query, params, continuation, &log).await
            }
            Self::MusicSearch(query, params) => {
                let (query, params) = first_page(continuation, query, params);
                engine.music_search(query, params, continuation, &log).await
            }
        }
    }
}

/// A continuation carries the query and filter on; only the first page sends them.
fn first_page<'a>(
    continuation: Option<&str>,
    query: &'a str,
    params: &'a Option<String>,
) -> (Option<&'a str>, Option<&'a str>) {
    if continuation.is_some() { (None, None) } else { (Some(query), params.as_deref()) }
}

/// Play history's most-watched videos as one section that plays in order.
async fn top_tracks(history: Arc<ym_history::PlayHistory>) -> Result<YtFeedPage, EngineError> {
    let videos = tokio::task::spawn_blocking(move || history.youtube_top(ym_history::RETAIN))
        .await
        .map_err(|error| EngineError::site("R90020", format!("top tracks: {error}")))?
        .map_err(|error| EngineError::site("R90020", format!("top tracks: {error}")))?;
    let items = videos
        .into_iter()
        .map(|video| YtFeedItem {
            kind: "video".into(),
            thumbs: vec![ym_engine::yt::YtThumb {
                url: video
                    .thumbnail_url
                    .unwrap_or_else(|| format!("https://i.ytimg.com/vi/{}/mqdefault.jpg", video.video_id)),
                w: Some(320),
                h: Some(180),
            }],
            id: video.video_id,
            title: video.title,
            subtitle_line1: video.artist.clone(),
            channel_title: video.artist,
            duration: video.duration,
            duration_ms: video.duration_ms,
            ..YtFeedItem::default()
        })
        .collect::<Vec<_>>();
    let sections = if items.is_empty() {
        Vec::new()
    } else {
        vec![ym_engine::yt::YtSection {
            layout: "grid".into(),
            items,
            play_mode: Some("queue".into()),
            ..Default::default()
        }]
    };
    Ok(YtFeedPage { sections, ..YtFeedPage::default() })
}

/// A request to play a video; `queue` is set when its section plays in order.
pub struct Play {
    pub item: YtFeedItem,
    pub queue: Option<Vec<YtFeedItem>>,
    pub shuffle: bool,
}

/// Something to do with a video besides opening it.
pub struct ItemAction {
    pub item: YtFeedItem,
    pub action: FeedAction,
}

#[derive(Clone, Copy)]
pub enum FeedAction {
    QueueNext,
    QueueLast,
    Radio,
    Download,
}

/// A request to open a new surface on the current section's stack.
pub struct Navigate {
    pub source: FeedSource,
    pub title: Option<SharedString>,
}

/// The empty Home's Search videos: open the search field.
pub struct RequestSearch;

/// A menu option that re-serves the page in place, by its position.
#[derive(Clone, Copy, PartialEq)]
enum Refilter {
    Chip(usize),
    Sort(usize),
}

enum Phase {
    Loading,
    Ready,
    Failed(EngineError),
}

pub struct FeedView {
    source: FeedSource,
    title: Option<SharedString>,
    page: YtFeedPage,
    phase: Phase,
    loading_more: bool,
    more_failed: Option<EngineError>,
    /// The chip or sort option whose page is loading; the current page stays up until it lands.
    refiltering: Option<Refilter>,
    /// An F5 reload is in flight; the page stays up under the loading line until it lands.
    refreshing: bool,
    generation: u64,
    scroll: ScrollHandle,
    /// Collapsible sections the user expanded, by index.
    expanded: std::collections::HashSet<usize>,
    /// A header action (save, subscribe) is running.
    header_busy: bool,
    /// Every item's focus stop, by its index across all sections.
    focus: FocusList,
    /// Section header and Show more buttons, three per section.
    controls: FocusList,
    /// The filter chips, then the page's Show more.
    chips: FocusList,
    tabs: FocusList,
    actions: FocusList,
    /// The failed page's message and Retry.
    failure: FocusList,
    /// Drawn above the page, scrolling with it, in place of the page's own header (the Account
    /// tab's card, which already shows who the page belongs to).
    top: Option<AnyView>,
    chips_drag: DragRow,
    tabs_drag: DragRow,
    carousel_drags: std::cell::RefCell<std::collections::HashMap<usize, DragRow>>,
    offline: Entity<OfflineMarks>,
    /// Channel items offer Subscribe / Unsubscribe only when signed in.
    signed_in: bool,
    _offline: Subscription,
}

/// Which page header a detail page shows.
#[derive(Clone, Copy, PartialEq)]
enum HeaderKind {
    /// Title, meta, and the actions row ending in the shuffle circle.
    Playlist,
    /// Banner, avatar, name and Subscribe.
    Channel,
    /// A YT Music category page: Play all and Shuffle all pills.
    Category,
    Generic,
}

impl EventEmitter<Navigate> for FeedView {}
impl EventEmitter<RequestSearch> for FeedView {}
impl EventEmitter<Play> for FeedView {}
impl EventEmitter<ItemAction> for FeedView {}

impl FeedView {
    pub fn new(source: FeedSource, title: Option<SharedString>, cx: &mut Context<Self>) -> Self {
        let scroll = ScrollHandle::new();
        OfflineMarks::refresh(cx);
        let offline = OfflineMarks::shared(cx);
        let observe_offline = cx.observe(&offline, |_, _, cx| cx.notify());
        let mut view = Self {
            source,
            title,
            page: YtFeedPage::default(),
            phase: Phase::Loading,
            loading_more: false,
            more_failed: None,
            refiltering: None,
            refreshing: false,
            generation: 0,
            scroll: scroll.clone(),
            expanded: std::collections::HashSet::new(),
            header_busy: false,
            focus: FocusList::new(Some(scroll.clone())),
            controls: FocusList::new(Some(scroll.clone())),
            chips: FocusList::new(Some(scroll.clone())),
            tabs: FocusList::new(None),
            actions: FocusList::new(Some(scroll)),
            failure: FocusList::new(None),
            top: None,
            chips_drag: DragRow::default(),
            tabs_drag: DragRow::default(),
            carousel_drags: Default::default(),
            offline,
            signed_in: false,
            _offline: observe_offline,
        };
        view.reload(cx);
        view
    }

    pub fn title(&self) -> Option<SharedString> {
        self.title.clone().or_else(|| self.page.page_header.as_ref()?.title.clone().map(Into::into))
    }

    /// Playlists title their bar "Playlist", not with the playlist's name.
    pub fn is_playlist_page(&self) -> bool {
        self.header_kind() == HeaderKind::Playlist
    }

    fn header_kind(&self) -> HeaderKind {
        let header = self.page.page_header.as_ref();
        let channel_like = header.is_some_and(|header| {
            header.channel_id.is_some() || header.subscribed.is_some() || !header.banner.is_empty()
        });
        let playlist_like =
            header.is_some_and(|header| header.save_playlist_id.is_some() || header.item_count.is_some());
        match &self.source {
            FeedSource::Playlist(_) => HeaderKind::Playlist,
            FeedSource::Channel(_) if header.is_some() => HeaderKind::Channel,
            FeedSource::MusicBrowse(_) if channel_like => HeaderKind::Channel,
            FeedSource::MusicBrowse(_) if playlist_like => HeaderKind::Playlist,
            FeedSource::MusicBrowse(_) => HeaderKind::Category,
            _ => HeaderKind::Generic,
        }
    }

    fn videos(&self) -> Vec<YtFeedItem> {
        self.page
            .sections
            .iter()
            .flat_map(|section| section.items.iter())
            .filter(|item| item.kind == "video")
            .cloned()
            .collect()
    }

    fn play_all(&mut self, shuffle: bool, cx: &mut Context<Self>) {
        let videos = self.videos();
        if videos.is_empty() {
            return;
        }
        let start = if shuffle { rand::Rng::random_range(&mut rand::rng(), 0..videos.len()) } else { 0 };
        cx.emit(Play { item: videos[start].clone(), queue: Some(videos), shuffle });
    }

    fn share_page(&self) -> Option<String> {
        match &self.source {
            FeedSource::Playlist(url) | FeedSource::Channel(url) if url.starts_with('/') => {
                Some(format!("https://www.youtube.com{url}"))
            }
            _ => {
                let header = self.page.page_header.as_ref()?;
                header
                    .save_playlist_id
                    .as_ref()
                    .map(|id| format!("https://www.youtube.com/playlist?list={id}"))
                    .or_else(|| header.channel_id.as_ref().map(|id| format!("https://www.youtube.com/channel/{id}")))
            }
        }
    }

    /// Saves the playlist to (or removes it from) the account library.
    fn toggle_saved(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(header) = &self.page.page_header else { return };
        let (Some(playlist_id), Some(saved)) = (header.save_playlist_id.clone(), header.saved) else { return };
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("save-playlist"));
        let request = on_core(cx, async move { engine.save_playlist(&playlist_id, !saved, &log).await });
        self.run_header_action(request, window, cx, move |header| {
            header.saved = Some(!saved);
            Some(t(if saved { Str::PlaylistRemoved } else { Str::PlaylistSaved }))
        });
    }

    fn toggle_subscribed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(header) = &self.page.page_header else { return };
        let (Some(channel_id), Some(subscribed)) = (header.channel_id.clone(), header.subscribed) else { return };
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("subscribe"));
        let request = on_core(cx, async move { engine.subscribe_channel(&channel_id, !subscribed, &log).await });
        self.run_header_action(request, window, cx, move |header| {
            header.subscribed = Some(!subscribed);
            None
        });
    }

    /// Runs a header request: busy until it lands, then applies `done` (showing the message it
    /// returns), or shows the error.
    fn run_header_action(
        &mut self,
        request: impl Future<Output = Option<Result<(), EngineError>>> + 'static,
        window: &mut Window,
        cx: &mut Context<Self>,
        done: impl FnOnce(&mut YtPageHeader) -> Option<&'static str> + 'static,
    ) {
        if self.header_busy {
            return;
        }
        self.header_busy = true;
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.header_busy = false;
                match result {
                    Some(Ok(())) => {
                        if let Some(message) = view.page.page_header.as_mut().and_then(done) {
                            window.push_notification(message, cx);
                        }
                    }
                    Some(Err(error)) => window.push_notification(error_text(&error), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The playlist download page (or, `local`, the local playlist from downloads); false while
    /// the page has no videos yet.
    pub fn open_playlist_dialog(&mut self, local: bool, window: &mut Window, cx: &mut Context<Self>) -> bool {
        if self.videos().is_empty() {
            return false;
        }
        let title = self.page.page_header.as_ref().and_then(|header| header.title.clone());
        let title = title.or_else(|| self.title.as_ref().map(|title| title.to_string())).unwrap_or_default();
        if local {
            LocalPlaylistFromDownloads::open(self.source.clone(), title, &self.page, window, cx);
        } else {
            PlaylistDownload::open(self.source.clone(), title, &self.page, window, cx);
        }
        true
    }

    /// The server's search filters; empty until a page is up, and on surfaces without them.
    pub fn filter_groups(&self) -> &[YtFilterGroup] {
        match self.phase {
            Phase::Ready => &self.page.filter_groups,
            _ => &[],
        }
    }

    /// Re-runs the search with a filter option's params.
    pub fn apply_filter(&mut self, params: String, cx: &mut Context<Self>) {
        match &mut self.source {
            FeedSource::Search(_, current) | FeedSource::MusicSearch(_, current) => *current = Some(params),
            _ => return,
        }
        self.reload(cx);
    }

    pub fn set_top(&mut self, top: Option<AnyView>) {
        self.top = top;
    }

    pub fn reload(&mut self, cx: &mut Context<Self>) {
        self.phase = Phase::Loading;
        self.fetch(None, false, cx);
    }

    /// F5 / Ctrl+R (the JVM app's `pager.reload()`): the page again from the top, without a chip
    /// filter, the current list staying up under the loading line until the new one lands; a
    /// failure keeps it and says why.
    pub fn refresh(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !matches!(self.phase, Phase::Ready) {
            return self.reload(cx);
        }
        self.generation += 1;
        self.refreshing = true;
        cx.notify();
        let generation = self.generation;
        let load = self.load_reloaded(cx);
        cx.spawn_in(window, async move |this, cx| {
            let result = load.await;
            this.update_in(cx, |view, window, cx| {
                if view.generation != generation {
                    return;
                }
                view.refreshing = false;
                view.refiltering = None;
                view.loading_more = false;
                match result {
                    Some(Ok(page)) => {
                        view.replace(page, false);
                        view.scroll.set_offset(point(px(0.), px(0.)));
                    }
                    Some(Err(error)) => window.push_notification(error_text(&error), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn load_more(&mut self, cx: &mut Context<Self>) {
        let Some(continuation) = self.page.continuation.clone() else { return };
        if self.loading_more || self.more_failed.is_some() || self.refreshing {
            return;
        }
        self.loading_more = true;
        self.fetch(Some(continuation), true, cx);
    }

    /// A chip or sort option re-serves the page in place: the current page stays up, the option
    /// spins, and the new page replaces it when it lands; a failure leaves the page and says why.
    fn refilter(&mut self, target: Refilter, token: String, window: &mut Window, cx: &mut Context<Self>) {
        if self.refiltering == Some(target) {
            return;
        }
        self.refiltering = Some(target);
        self.refreshing = false;
        self.generation += 1;
        let generation = self.generation;
        let load = self.load(Some(token), cx);
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = load.await;
            this.update_in(cx, |view, window, cx| {
                if view.generation != generation {
                    return;
                }
                view.refiltering = None;
                view.loading_more = false;
                match result {
                    Some(Ok(page)) => {
                        let (carried_chips, carried_sort) = (page.chips.is_empty(), page.sort.is_empty());
                        view.replace(page, true);
                        let marked = match target {
                            Refilter::Chip(index) if carried_chips => Some((&mut view.page.chips, index)),
                            Refilter::Sort(index) if carried_sort => Some((&mut view.page.sort, index)),
                            _ => None,
                        };
                        if let Some((options, index)) = marked {
                            for (position, option) in options.iter_mut().enumerate() {
                                option.selected = position == index;
                            }
                        }
                    }
                    Some(Err(error)) => window.push_notification(error_text(&error), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn load(
        &self,
        continuation: Option<String>,
        cx: &mut Context<Self>,
    ) -> impl Future<Output = Option<Result<YtFeedPage, EngineError>>> + 'static {
        let graph = cx.global::<AppGraph>();
        let (engine, history, log) = (graph.engine(), graph.history.clone(), graph.log.fork("feed"));
        on_core(cx, self.source.clone().load(engine, history, continuation, log))
    }

    fn load_reloaded(
        &self,
        cx: &mut Context<Self>,
    ) -> impl Future<Output = Option<Result<YtFeedPage, EngineError>>> + 'static {
        let graph = cx.global::<AppGraph>();
        let (engine, history, log) = (graph.engine(), graph.history.clone(), graph.log.fork("feed"));
        on_core(cx, self.source.clone().reload(engine, history, log))
    }

    fn fetch(&mut self, continuation: Option<String>, append: bool, cx: &mut Context<Self>) {
        self.generation += 1;
        self.refreshing = false;
        if !append {
            self.refiltering = None;
        }
        let generation = self.generation;
        let load = self.load(continuation, cx);
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                if view.generation != generation {
                    return;
                }
                view.loading_more = false;
                match result {
                    Some(Ok(page)) if append => view.append(page),
                    Some(Ok(page)) => {
                        view.replace(page, false);
                        view.check_signed_in(cx);
                    }
                    Some(Err(error)) if append => view.more_failed = Some(error),
                    Some(Err(error)) => view.phase = Phase::Failed(error),
                    None => {}
                }
                cx.notify();
            })
        })
        .detach();
    }

    /// `refiltered`: a chip or sort re-serve, which often omits the menus; they carry over. Any
    /// other load is a new surface (a channel tab) and must not inherit them.
    fn replace(&mut self, mut page: YtFeedPage, refiltered: bool) {
        if refiltered {
            if page.chips.is_empty() {
                page.chips = std::mem::take(&mut self.page.chips);
            }
            if page.sort.is_empty() {
                page.sort = std::mem::take(&mut self.page.sort);
            }
            if page.filter_groups.is_empty() {
                page.filter_groups = std::mem::take(&mut self.page.filter_groups);
            }
            page.chips_wrap |= self.page.chips_wrap;
        }
        if page.page_header.is_none() {
            page.page_header = self.page.page_header.take();
            page.page_tabs = std::mem::take(&mut self.page.page_tabs);
        }
        self.page = page;
        self.phase = Phase::Ready;
        self.more_failed = None;
    }

    /// Reads the cookie-only sign-in gate when the page has channel items to offer Subscribe on.
    fn check_signed_in(&mut self, cx: &mut Context<Self>) {
        let has_channels =
            self.page.sections.iter().any(|section| section.items.iter().any(|item| item.kind == "channel"));
        if !has_channels {
            return;
        }
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("feed-signed-in"));
        let status = on_core(cx, async move {
            let status = engine.login_status(&log).await;
            if let Err(error) = &status {
                log.warn("sign-in status could not be read", Some(error));
            }
            status
        });
        cx.spawn(async move |this, cx| {
            if let Some(Ok(signed_in)) = status.await {
                this.update(cx, |view, cx| {
                    view.signed_in = signed_in;
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    /// Subscribe / Unsubscribe from a channel item's menu, reported by notification.
    fn subscribe_channel(&mut self, channel_id: String, subscribe: bool, window: &mut Window, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("channel-subscribe"));
        let request = on_core(cx, async move {
            let result = engine.subscribe_channel(&channel_id, subscribe, &log).await;
            if let Err(error) = &result {
                log.warn("subscribe from a channel item failed", Some(error));
            }
            result
        });
        cx.spawn_in(window, async move |_, cx| {
            let message = match request.await {
                Some(Ok(())) => t(if subscribe { Str::Subscribed } else { Str::Unsubscribed }).to_owned(),
                Some(Err(error)) => error_text(&error),
                None => return,
            };
            cx.update(|window, cx| window.push_notification(message, cx)).ok();
        })
        .detach();
    }

    fn append(&mut self, page: YtFeedPage) {
        self.page.sections.extend(page.sections);
        self.page.continuation = page.continuation;
    }

    fn open_item(&mut self, item: &YtFeedItem, queue: Option<Vec<YtFeedItem>>, cx: &mut Context<Self>) {
        let title = Some(SharedString::from(item.title.clone()));
        let source = match item.kind.as_str() {
            "video" => {
                cx.emit(Play { item: item.clone(), queue, shuffle: false });
                return;
            }
            "playlist" => item
                .url
                .as_deref()
                .map(|url| FeedSource::from_link(url).unwrap_or(FeedSource::Playlist(url.to_owned()))),
            "channel" => {
                item.url.as_deref().map(|url| FeedSource::from_link(url).unwrap_or(FeedSource::Channel(url.to_owned())))
            }
            _ => item.url.as_deref().and_then(FeedSource::from_link),
        };
        if let Some(source) = source {
            cx.emit(Navigate { source, title });
        }
    }

    pub fn open_link(&mut self, link: &str, title: Option<SharedString>, cx: &mut Context<Self>) {
        if let Some(source) = FeedSource::from_link(link) {
            cx.emit(Navigate { source, title });
        }
    }

    fn open_tab(&mut self, url: String, cx: &mut Context<Self>) {
        if let Some(source) = FeedSource::from_link(&url) {
            self.source = source;
            self.phase = Phase::Loading;
            self.fetch(None, false, cx);
        }
    }
}

/// What the user sees for a failure: a localized category, the server's own message, or the
/// generic message with the code to report.
pub fn error_text(error: &EngineError) -> String {
    match error.kind {
        ErrorKind::Network => t(Str::ErrorNetwork).to_owned(),
        _ => match error.details.as_ref().and_then(|details| details.user_message.clone()) {
            Some(message) => format!("{message} ({})", error.code),
            None => tf(Str::ErrorGeneric, &[&error.code]),
        },
    }
}

fn section_title(header: &YtSectionHeader) -> Option<String> {
    let localized = header.title_key.as_deref().and_then(|key| {
        Some(match key {
            "trending_music_videos" => Str::ChartTrendingMusicVideos,
            "top_songs" => Str::ChartTopSongs,
            "top_songs_daily" => Str::ChartTopSongsDaily,
            "top_songs_weekly" => Str::ChartTopSongsWeekly,
            "top_music_videos" => Str::ChartTopMusicVideos,
            "top_music_videos_daily" => Str::ChartTopMusicVideosDaily,
            "top_music_videos_weekly" => Str::ChartTopMusicVideosWeekly,
            "top_songs_on_shorts" => Str::ChartTopSongsOnShorts,
            "top_songs_on_shorts_daily" => Str::ChartTopSongsOnShortsDaily,
            "top_songs_on_shorts_weekly" => Str::ChartTopSongsOnShortsWeekly,
            "subscribed_channels" => Str::SectionSubscribedChannels,
            _ => return None,
        })
    });
    localized.map(|key| t(key).to_owned()).or_else(|| header.title.clone())
}

/// The smallest variant at least `width` px wide, else the largest.
pub fn thumb_for(thumbs: &[YtThumb], width: i32) -> Option<SharedString> {
    thumbs
        .iter()
        .find(|thumb| thumb.w.is_some_and(|w| w >= width))
        .or_else(|| thumbs.last())
        .map(|thumb| absolute_url(&thumb.url).into())
}

fn absolute_url(url: &str) -> String {
    if url.starts_with("//") { format!("https:{url}") } else { url.to_owned() }
}

fn centered(content: impl IntoElement) -> Div {
    div().size_full().flex().items_center().justify_center().child(content)
}

impl FeedView {
    fn render_header(&self, header: &YtPageHeader, cx: &mut Context<Self>) -> AnyElement {
        match self.header_kind() {
            HeaderKind::Playlist => self.render_playlist_header(header, cx),
            HeaderKind::Channel => self.render_channel_header(header, cx),
            HeaderKind::Category => self.render_category_bar(cx),
            HeaderKind::Generic => self.render_generic_header(header, cx),
        }
    }

    /// Title and meta, then save · share ... the shuffle circle, over an inset divider.
    fn render_playlist_header(&self, header: &YtPageHeader, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let meta = [header.subtitle_line1.clone(), header.subtitle_line2.clone()]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(" · ");
        let share = self.share_page();
        let has_videos = self.page.sections.iter().any(|section| section.items.iter().any(|item| item.kind == "video"));
        let mut actions = h_flex().px_4().py_2().gap_4().items_center();
        if let Some(saved) = header.saved.filter(|_| header.save_playlist_id.is_some()) {
            let label = t(if saved { Str::CdRemovePlaylistFromLibrary } else { Str::CdSavePlaylist });
            actions = actions.child(if self.header_busy {
                Spinner::new().into_any_element()
            } else {
                let save = action_icon(
                    "playlist-save",
                    Icon::new(if saved { Lucide::ListCheck } else { Lucide::ListPlus }),
                    label,
                )
                .selected(saved)
                .on_click(cx.listener(|view, _, window, cx| view.toggle_saved(window, cx)));
                self.actions.button(ACTION_SAVE, save, cx).into_any_element()
            });
        }
        let share = action_icon("playlist-share", Icon::new(Lucide::Share2), t(Str::CdShare))
            .disabled(share.is_none())
            .on_click(move |_, window, cx| {
                if let Some(link) = share.clone() {
                    cx.write_to_clipboard(ClipboardItem::new_string(link));
                    window.push_notification(t(Str::LinkCopied), cx);
                }
            });
        actions = actions.child(self.actions.button(ACTION_SHARE, share, cx));
        let download = action_icon("playlist-download", Icon::new(Lucide::Download), t(Str::CdDownload))
            .disabled(!has_videos)
            .on_click(cx.listener(|view, _, window, cx| {
                view.open_playlist_dialog(false, window, cx);
            }));
        actions = actions.child(self.actions.button(ACTION_DOWNLOAD, download, cx));
        let local = action_icon("playlist-local", Icon::new(Lucide::LibraryBig), t(Str::LocalPlaylistCreate))
            .disabled(!has_videos)
            .on_click(cx.listener(|view, _, window, cx| {
                view.open_playlist_dialog(true, window, cx);
            }));
        actions = actions.child(self.actions.button(ACTION_LOCAL, local, cx));
        actions = actions.child(div().flex_1()).child(
            self.actions
                .item(
                    ACTION_SHUFFLE,
                    div().id("playlist-shuffle").role(Role::Button).aria_label(t(Str::ShuffleAll)),
                    cx,
                )
                .size(px(46.))
                .flex_shrink_0()
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .bg(theme.primary)
                .text_color(theme.primary_foreground)
                .when(has_videos, |circle| circle.cursor_pointer())
                .when(!has_videos, |circle| circle.opacity(0.5))
                .child(Icon::new(Lucide::Shuffle))
                .on_click(cx.listener(|view, _, _, cx| view.play_all(true, cx))),
        );
        v_flex()
            .child(
                v_flex()
                    .px_4()
                    .pt_2()
                    .gap_1()
                    .when_some(header.title.clone(), |column, title| {
                        column.child(div().text_xl().font_semibold().child(title))
                    })
                    .when(!meta.is_empty(), |column| {
                        column.child(div().text_sm().text_color(theme.muted_foreground).child(meta))
                    }),
            )
            .child(actions)
            .child(div().mx_4().mb_2().h(px(1.)).bg(cx.global::<StackTones>().near))
            .into_any_element()
    }

    /// The banner (when there is one), then avatar, name, meta and Subscribe.
    fn render_channel_header(&self, header: &YtPageHeader, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let avatar = thumb_for(&header.thumbs, 128);
        let banner = thumb_for(&header.banner, 1280);
        let meta = [header.subtitle_line1.clone(), header.subtitle_line2.clone()]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(" · ");
        let subscribe = header.subscribed.filter(|_| header.channel_id.is_some()).map(|subscribed| {
            let label = t(if subscribed { Str::Subscribed } else { Str::Subscribe });
            let pill = Button::new("subscribe").rounded_full().label(label).busy(self.header_busy);
            let pill = if subscribed { pill.outline() } else { pill.primary() };
            let pill = pill.on_click(cx.listener(|view, _, window, cx| view.toggle_subscribed(window, cx)));
            self.actions.button(ACTION_SUBSCRIBE, pill, cx)
        });
        v_flex()
            .when_some(banner, |column, url| {
                column.child(
                    div()
                        .mx_4()
                        .mt_2()
                        .h(px(96.))
                        .rounded(px(12.))
                        .overflow_hidden()
                        .bg(theme.secondary)
                        .child(img(url).size_full().rounded(px(12.)).object_fit(ObjectFit::Cover)),
                )
            })
            .child(
                h_flex()
                    .px_4()
                    .py_2()
                    .gap_4()
                    .items_center()
                    .when_some(avatar, |row, url| {
                        row.child(img(url).size(px(64.)).rounded_full().object_fit(ObjectFit::Cover).flex_shrink_0())
                    })
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .when_some(header.title.clone(), |column, title| {
                                column.child(div().font_semibold().truncate().child(title))
                            })
                            .when(!meta.is_empty(), |column| {
                                column.child(
                                    div()
                                        .pt(px(2.))
                                        .text_sm()
                                        .text_color(theme.muted_foreground)
                                        .truncate()
                                        .child(meta),
                                )
                            }),
                    )
                    .children(subscribe),
            )
            .into_any_element()
    }

    /// Play all (filled) and Shuffle all (outlined) pills.
    fn render_category_bar(&self, cx: &mut Context<Self>) -> AnyElement {
        h_flex()
            .px_4()
            .py_2()
            .gap_2()
            .child(
                self.actions.button(
                    ACTION_PLAY_ALL,
                    Button::new("play-all")
                        .primary()
                        .rounded_full()
                        .icon(Lucide::Play)
                        .label(t(Str::PlayAll))
                        .on_click(cx.listener(|view, _, _, cx| view.play_all(false, cx))),
                    cx,
                ),
            )
            .child(
                self.actions.button(
                    ACTION_SHUFFLE_ALL,
                    Button::new("shuffle-all")
                        .outline()
                        .rounded_full()
                        .icon(Lucide::Shuffle)
                        .label(t(Str::ShuffleAll))
                        .on_click(cx.listener(|view, _, _, cx| view.play_all(true, cx))),
                    cx,
                ),
            )
            .into_any_element()
    }

    fn render_generic_header(&self, header: &YtPageHeader, cx: &App) -> AnyElement {
        let theme = cx.theme();
        let avatar = thumb_for(&header.thumbs, 160);
        h_flex()
            .px_4()
            .pt_2()
            .gap_4()
            .pb_2()
            .when_some(avatar, |row, url| {
                row.child(img(url).size(px(88.)).rounded_full().object_fit(ObjectFit::Cover).flex_shrink_0())
            })
            .child(
                v_flex()
                    .gap_1()
                    .min_w_0()
                    .when_some(header.title.clone(), |column, title| {
                        column.child(div().text_xl().font_semibold().child(title))
                    })
                    .when_some(header.subtitle_line1.clone(), |column, line| {
                        column.child(div().text_sm().text_color(theme.muted_foreground).child(line))
                    })
                    .when_some(header.subtitle_line2.clone(), |column, line| {
                        column.child(div().text_sm().text_color(theme.muted_foreground).child(line))
                    }),
            )
            .into_any_element()
    }
}

/// Grid and list geometry for a feed this wide (measured as if the rail were expanded).
#[derive(Clone, Copy)]
struct Bands {
    columns: usize,
    margin: f32,
    gutter: f32,
    /// List thumbnails: square below 320 wide, 16:9 below 470.
    square: bool,
}

const CARD_MAX: f32 = 324.;
/// The sort menu, after any chip.
const CHIPS_SORT: usize = 63;
/// The page header's buttons, the empty state's and the failed Show more's text and buttons, as
/// focus stops.
const ACTION_SAVE: usize = 0;
const ACTION_SHARE: usize = 1;
const ACTION_DOWNLOAD: usize = 2;
const ACTION_LOCAL: usize = 3;
const ACTION_SHUFFLE: usize = 4;
const ACTION_SUBSCRIBE: usize = 5;
const ACTION_PLAY_ALL: usize = 6;
const ACTION_SHUFFLE_ALL: usize = 7;
const ACTION_RELOAD: usize = 8;
const ACTION_SEARCH: usize = 9;
const ACTION_RETRY_MORE: usize = 10;
/// The page's Show more, after any chip.
const CHIPS_SHOW_MORE: usize = 64;
const ROW_GAP: f32 = 16.;

impl Bands {
    fn for_width(width: f32) -> Self {
        let (margin, gutter) = if width >= 840. { (24., 16.) } else { (16., 8.) };
        let columns = if width < 470. {
            1
        } else {
            (((width - 2. * margin + gutter) / (CARD_MAX + gutter)).ceil() as usize).max(2)
        };
        Self { columns, margin, gutter, square: width < 320. }
    }

    fn inset(&self) -> f32 {
        self.gutter / 2.
    }
}

/// The feed's width as the JVM app measures it: the window, less the expanded rail in rail mode.
pub fn feed_width(window: &Window) -> f32 {
    let viewport = window.viewport_size();
    let (width, height) = (f32::from(viewport.width), f32::from(viewport.height));
    if width >= 750. && height >= 480. { width - 280. } else { width }
}

/// What a screen reader says for a card or row.
fn item_label(item: &YtFeedItem, downloaded: bool) -> String {
    let kind = match item.kind.as_str() {
        "playlist" if item.album => t(Str::A11yKindAlbum),
        "playlist" => t(Str::A11yKindPlaylist),
        "channel" => t(Str::A11yKindChannel),
        _ if item.live => t(Str::A11yKindLive),
        _ if item.music_video => t(Str::A11yKindMusicVideo),
        _ => t(Str::A11yKindVideo),
    };
    [
        Some(item.title.clone()),
        item.subtitle_line1.clone(),
        item.subtitle_line2.clone(),
        item.duration.clone(),
        Some(kind.to_owned()),
        downloaded.then(|| t(Str::A11yDownloaded).to_owned()),
    ]
    .into_iter()
    .flatten()
    .filter(|part| !part.is_empty())
    .collect::<Vec<_>>()
    .join(", ")
}

fn subtitle(item: &YtFeedItem) -> Option<String> {
    let parts: Vec<String> = [item.subtitle_line1.clone(), item.subtitle_line2.clone()].into_iter().flatten().collect();
    (!parts.is_empty()).then(|| parts.join(" · "))
}

fn badge(item: &YtFeedItem) -> Option<String> {
    if item.live {
        return Some(t(Str::BadgeLive).to_owned());
    }
    match item.kind.as_str() {
        "playlist" => item.item_count.clone(),
        _ => item.duration.clone(),
    }
}

/// The two card edges peeking up-right behind a `width` × `height` playlist list thumbnail, each
/// shifted by its offset and shrunk on every side (JVM `drawPlaylistStack`).
fn playlist_stack(width: f32, height: f32, tones: StackTones) -> [Div; 2] {
    let layer = |offset: f32, shrink: f32, color: Hsla| {
        div()
            .absolute()
            .left(px(offset + shrink))
            .top(px(shrink - offset))
            .w(px(width - 2. * shrink))
            .h(px(height - 2. * shrink))
            .rounded(px(6.))
            .bg(color)
    };
    [layer(8., 2., tones.far), layer(4., 1., tones.near)]
}

/// The next card's edge over a playlist grid thumbnail, 4 tall in the card's top inset (JVM
/// `FeedGridCard`); its lower half hides under the art.
fn playlist_tab(color: Hsla) -> Div {
    // Compose scales the tab's 6dp top corners down to its 4dp height.
    div().absolute().top(px(-4.)).left(px(6.)).right(px(6.)).h(px(8.)).rounded_t(px(4.)).bg(color)
}

impl FeedView {
    fn downloaded(&self, item: &YtFeedItem, cx: &App) -> bool {
        item.kind == "video" && self.offline.read(cx).contains(&item.id)
    }

    fn more_menu(&self, item: &YtFeedItem, key: (&'static str, usize), cx: &mut Context<Self>) -> AnyElement {
        let view = cx.entity().downgrade();
        let target = item.clone();
        let top_tracks = matches!(self.source, FeedSource::TopTracks);
        let downloaded = self.downloaded(item, cx);
        let channel_id = (item.kind == "channel" && self.signed_in)
            .then(|| item.channel_id.clone().unwrap_or_else(|| item.id.clone()))
            .filter(|id| !id.is_empty());
        let more = action_icon(
            key,
            Icon::new(IconName::EllipsisVertical).text_color(cx.theme().muted_foreground),
            t(Str::CdMore),
        );
        self.focus
            .trailing_button(key.1, 0, more, cx)
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, cx| {
                let video = target.kind == "video";
                let mut actions: Vec<(Str, MenuAction)> = Vec::new();
                if video {
                    actions.push((Str::MenuPlaySingle, MenuAction::PlaySingle));
                    actions.push((Str::MenuStartRadio, MenuAction::Feed(FeedAction::Radio)));
                    actions.push((Str::QueueNext, MenuAction::Feed(FeedAction::QueueNext)));
                    actions.push((Str::QueueLast, MenuAction::Feed(FeedAction::QueueLast)));
                    if !target.live {
                        actions.push((Str::DownloadTitle, MenuAction::Feed(FeedAction::Download)));
                    }
                    actions.push((Str::CdAddToPlaylist, MenuAction::AddToPlaylist));
                }
                if target.remove_token.is_some() {
                    actions.push((Str::MenuRemoveFromPlaylist, MenuAction::RemoveFromPlaylist));
                }
                if channel_id.is_some() {
                    actions.push((Str::Subscribe, MenuAction::Subscribe(true)));
                    actions.push((Str::Unsubscribe, MenuAction::Subscribe(false)));
                }
                if share_link(&target).is_some() {
                    actions.push((Str::CdShare, MenuAction::Share));
                }
                if video && top_tracks {
                    actions.push((Str::MenuRemove, MenuAction::RemoveFromTop));
                }
                for (label, action) in actions {
                    let (view, item) = (view.clone(), target.clone());
                    menu = menu.item(PopupMenuItem::new(t(label)).on_click(move |_, window, cx| {
                        let item = item.clone();
                        view.update(cx, |view, cx| view.menu_action(item, action, window, cx)).ok();
                    }));
                    if matches!(action, MenuAction::Feed(FeedAction::Download)) {
                        let item = target.clone();
                        let label = tf(Str::QuickDownloadMenu, &[&saved_preset(cx).short_label()]);
                        menu = menu.item(PopupMenuItem::new(label).on_click(move |_, window, cx| {
                            crate::quick_download::launch(DownloadTarget::from_feed(&item), window, cx);
                        }));
                    }
                }
                if video && downloaded {
                    let video_id = target.id.clone();
                    menu = menu.separator().label(t(Str::SectionLibrary)).item(
                        PopupMenuItem::new(t(Str::CdAddToPlaylist)).on_click(move |_, window, cx| {
                            crate::library_playlist::AddToLocalPlaylist::open(vec![video_id.clone()], None, window, cx);
                        }),
                    );
                }
                menu
            })
            .into_any_element()
    }

    /// Takes the item off the viewer's own playlist, then off this page.
    fn remove_from_playlist(&mut self, item: YtFeedItem, window: &mut Window, cx: &mut Context<Self>) {
        let Some(token) = item.remove_token.clone() else { return };
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("yt-playlist"));
        let request = on_core(cx, async move { engine.remove_from_playlist(&token, &log).await });
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                match result {
                    Some(Ok(())) => {
                        for section in &mut view.page.sections {
                            section.items.retain(|kept| kept.remove_token != item.remove_token);
                        }
                        window.push_notification(t(Str::YtPlaylistRemovedVideo), cx);
                    }
                    Some(Err(error)) => window.push_notification(error_text(&error), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn menu_action(&mut self, item: YtFeedItem, action: MenuAction, window: &mut Window, cx: &mut Context<Self>) {
        match action {
            MenuAction::PlaySingle => cx.emit(Play { item: item.clone(), queue: Some(vec![item]), shuffle: false }),
            MenuAction::Feed(action) => cx.emit(ItemAction { item, action }),
            MenuAction::Share => {
                if let Some(link) = share_link(&item) {
                    cx.write_to_clipboard(ClipboardItem::new_string(link));
                    window.push_notification(t(Str::LinkCopied), cx);
                }
            }
            MenuAction::AddToPlaylist => crate::yt_playlist::AddToPlaylist::open(item.id, window, cx),
            MenuAction::RemoveFromPlaylist => self.remove_from_playlist(item, window, cx),
            MenuAction::Subscribe(subscribe) => {
                let channel_id = item.channel_id.clone().unwrap_or(item.id);
                self.subscribe_channel(channel_id, subscribe, window, cx);
            }
            MenuAction::RemoveFromTop => {
                let history = cx.global::<AppGraph>().history.clone();
                let id = item.id.clone();
                let removed =
                    on_core(cx, async move { tokio::task::spawn_blocking(move || history.delete_youtube(&id)).await });
                cx.spawn(async move |this, cx| {
                    if let Some(Ok(Err(error))) = removed.await {
                        this.update(cx, |_, cx| {
                            cx.global::<AppGraph>().log.warn("top track removal failed", Some(&error))
                        })
                        .ok();
                    }
                    this.update(cx, |view, cx| view.reload(cx)).ok();
                })
                .detach();
            }
        }
    }

    /// The `position`th item of its section's list, `id.1` its index on the page.
    fn clickable(
        &self,
        id: (&'static str, usize),
        position: usize,
        item: &YtFeedItem,
        queue: Option<std::rc::Rc<Vec<YtFeedItem>>>,
        row: Option<ScrollHandle>,
        cx: &mut Context<Self>,
    ) -> Stateful<Div> {
        let clicked = item.clone();
        let theme = cx.theme();
        let element = list_item(div().id(id), position)
            .aria_label(item_label(item, self.downloaded(item, cx)))
            .cursor_pointer()
            .rounded(px(12.))
            .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
            .on_click(cx.listener(move |view, _, _, cx| {
                let queue =
                    queue.as_ref().map(|items| items.iter().filter(|item| item.kind == "video").cloned().collect());
                view.open_item(&clicked, queue, cx)
            }));
        self.focus.item_in(id.1, element, row, cx)
    }

    /// A grid card: 16:9 thumbnail on top, title and subtitle below with the ⋮ menu beside them;
    /// a channel is a centered avatar instead.
    fn render_card(
        &self,
        item: &YtFeedItem,
        index: usize,
        position: usize,
        bands: Bands,
        queue: Option<std::rc::Rc<Vec<YtFeedItem>>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = cx.theme().clone();
        let card = self
            .clickable(("feed-card", index), position, item, queue, None, cx)
            .flex_1()
            .min_w_0()
            .p(px(bands.inset()));
        if item.kind == "channel" {
            return card
                .relative()
                .child(
                    v_flex()
                        .items_center()
                        .gap_1()
                        .child(
                            div().size(px(76.)).rounded_full().overflow_hidden().bg(theme.secondary).when_some(
                                thumb_for(&item.thumbs, 152),
                                |frame, url| {
                                    frame.child(img(url).size_full().rounded_full().object_fit(ObjectFit::Cover))
                                },
                            ),
                        )
                        .child(div().pt_1().text_sm().font_medium().truncate().child(item.title.clone()))
                        .when_some(subtitle(item), |column, line| {
                            column.child(div().text_xs().text_color(theme.muted_foreground).truncate().child(line))
                        }),
                )
                .child(div().absolute().top_0().right_0().child(self.more_menu(item, ("card-more", index), cx)))
                .into_any_element();
        }
        let stack_tab = (item.kind == "playlist").then(|| cx.global::<StackTones>().tab);
        let thumbnail = div()
            .relative()
            .w_full()
            .aspect_ratio(16. / 9.)
            .rounded(px(5.))
            .overflow_hidden()
            .bg(theme.secondary)
            .when_some(thumb_for(&item.thumbs, 640), |frame, url| {
                frame.child(img(url).size_full().rounded(px(5.)).object_fit(ObjectFit::Cover))
            })
            .when(self.downloaded(item, cx), |frame| frame.child(offline_mark(theme.primary, 5.)))
            .when_some(badge(item), |frame, text| {
                frame.child(
                    div()
                        .absolute()
                        .bottom_1()
                        .right_1()
                        .px(px(5.))
                        .py(px(2.))
                        .rounded(px(3.))
                        .bg(black().opacity(0.75))
                        .text_xs()
                        .font_semibold()
                        .text_color(white())
                        .child(text),
                )
            });
        card.child(
            div()
                .relative()
                .w_full()
                .when_some(stack_tab, |art, color| art.child(playlist_tab(color)))
                .child(thumbnail),
        )
        .child(
            h_flex()
                .pt_2()
                .gap_2()
                .items_start()
                .child(
                    v_flex()
                        .flex_1()
                        .min_w_0()
                        .gap_1()
                        .child(div().text_sm().font_medium().line_clamp(2).child(item.title.clone()))
                        .when_some(subtitle(item), |column, line| {
                            column.child(div().text_xs().text_color(theme.muted_foreground).truncate().child(line))
                        }),
                )
                .child(self.more_menu(item, ("card-more", index), cx)),
        )
        .into_any_element()
    }

    /// A list row: thumbnail (square, 16:9, or a channel circle), then title and subtitle, ⋮ at the end.
    fn render_row(
        &self,
        item: &YtFeedItem,
        index: usize,
        position: usize,
        bands: Bands,
        queue: Option<std::rc::Rc<Vec<YtFeedItem>>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = cx.theme().clone();
        let (width, height, round) = match item.kind.as_str() {
            "channel" => (74., 74., true),
            _ if bands.square => (92., 92., false),
            _ => (160., 90., false),
        };
        let stack = (item.kind == "playlist").then(|| playlist_stack(width, height, *cx.global::<StackTones>()));
        let thumbnail = div()
            .relative()
            .size_full()
            .overflow_hidden()
            .bg(theme.secondary)
            .when(round, |frame| frame.rounded_full())
            .when(!round, |frame| frame.rounded(px(6.)))
            .when_some(thumb_for(&item.thumbs, (width * 2.) as i32), |frame, url| {
                let picture = img(url).size_full().object_fit(ObjectFit::Cover);
                frame.child(if round { picture.rounded_full() } else { picture.rounded(px(6.)) })
            })
            .when(self.downloaded(item, cx), |frame| frame.child(offline_mark(theme.primary, 6.)))
            .when_some(badge(item).filter(|_| !round), |frame, text| {
                frame.child(
                    div()
                        .absolute()
                        .bottom_1()
                        .right_1()
                        .px(px(5.))
                        .py(px(2.))
                        .rounded(px(3.))
                        .bg(black().opacity(0.75))
                        .text_xs()
                        .text_color(white())
                        .child(text),
                )
            });
        self.clickable(("feed-row", index), position, item, queue, None, cx)
            .child(
                h_flex()
                    .pl_4()
                    .pr_1()
                    .py(px(10.))
                    .gap_4()
                    .items_start()
                    .child(
                        div()
                            .relative()
                            .flex_shrink_0()
                            .w(px(width))
                            .h(px(height))
                            .children(stack.into_iter().flatten())
                            .child(thumbnail),
                    )
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .gap_1()
                            .child(
                                div()
                                    .text_sm()
                                    .font_medium()
                                    .line_clamp(if bands.square { 2 } else { 3 })
                                    .child(item.title.clone()),
                            )
                            .when_some(subtitle(item), |column, line| {
                                column
                                    .child(div().text_xs().text_color(theme.muted_foreground).line_clamp(2).child(line))
                            }),
                    )
                    .child(self.more_menu(item, ("row-more", index), cx)),
            )
            .into_any_element()
    }

    /// Accent tick, optional avatar, title, count, then "View all" or the collapse chevron.
    fn render_section_header(
        &self,
        header: Option<&YtSectionHeader>,
        section_index: usize,
        collapsible: bool,
        expanded: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = cx.theme().clone();
        let title = header.and_then(section_title);
        let avatar = header.and_then(|header| thumb_for(&header.icon_thumbs, 52));
        let count = header.and_then(|header| header.count.clone());
        let cta = header.and_then(|header| header.cta_deeplink.clone().zip(header.cta_text.clone()));
        h_flex()
            .px_4()
            .pt_4()
            .pb_2()
            .gap_2()
            .items_center()
            .child(div().w(px(4.)).h(px(16.)).rounded_sm().bg(theme.primary))
            .when_some(avatar, |row, url| row.child(img(url).size(px(26.)).rounded_full().object_fit(ObjectFit::Cover)))
            .child(
                match title.clone() {
                    Some(title) => heading(("section-title", section_index), 2, title),
                    None => div().id(("section-title", section_index)),
                }
                .flex_1()
                .min_w_0()
                .truncate()
                .text_size(px(16.5))
                .font_bold(),
            )
            .when_some(count, |row, count| row.child(div().text_sm().text_color(theme.muted_foreground).child(count)))
            .when_some(cta.filter(|_| !collapsible), |row, (link, label)| {
                let title = title.clone().map(SharedString::from);
                let button = Button::new(("cta", section_index))
                    .ghost()
                    .small()
                    .label(label)
                    .icon(Icon::new(IconName::ChevronRight))
                    .on_click(cx.listener(move |view, _, _, cx| view.open_link(&link, title.clone(), cx)));
                row.child(self.controls.button(section_index * 3, button, cx))
            })
            .when(collapsible, |row| {
                let chevron = Icon::new(if expanded { IconName::ChevronUp } else { IconName::ChevronDown })
                    .text_color(theme.muted_foreground);
                let label = t(if expanded { Str::CdCollapse } else { Str::CdExpand });
                let button = sized_action_icon(("collapse", section_index), chevron, label, 20., HIT)
                    .on_click(cx.listener(move |view, _, _, cx| view.toggle_section(section_index, cx)));
                row.child(self.controls.button(section_index * 3 + 1, button, cx))
            })
            .into_any_element()
    }

    fn toggle_section(&mut self, section_index: usize, cx: &mut Context<Self>) {
        if !self.expanded.remove(&section_index) {
            self.expanded.insert(section_index);
        }
        cx.notify();
    }

    fn render_section(
        &self,
        section: &ym_engine::yt::YtSection,
        section_index: usize,
        first_item: usize,
        bands: Bands,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = cx.theme().clone();
        let expanded = self.expanded.contains(&section_index);
        let title = section.header.as_ref().and_then(section_title).map(SharedString::from);
        let mut block = v_flex().w_full();
        if section_index > 0 {
            block = block.child(div().px_4().pt_4().child(div().h(px(1.)).w_full().bg(theme.border)));
        }
        if section.header.is_some() || section.collapsible {
            block = block.child(self.render_section_header(
                section.header.as_ref(),
                section_index,
                section.collapsible,
                expanded,
                cx,
            ));
        }
        let queue = (section.play_mode.as_deref() == Some("queue")).then(|| std::rc::Rc::new(section.items.clone()));
        match section.layout.as_str() {
            "tags" => {
                let shown =
                    if section.collapsible && !expanded { 10.min(section.items.len()) } else { section.items.len() };
                let stops = self.focus.list_stops(first_item..first_item + shown, window, cx);
                let mut pills = h_flex().id(("section-items", section_index)).flex_wrap().gap_2().px_4().py_1();
                for (offset, item) in section.items.iter().take(shown).enumerate() {
                    let link = item.url.clone();
                    let title = SharedString::from(item.title.clone());
                    let tag = Button::new(("tag", first_item + offset))
                        .small()
                        .outline()
                        .rounded_full()
                        .label(item.title.clone())
                        .on_click(cx.listener(move |view, _, _, cx| {
                            if let Some(link) = &link {
                                view.open_link(link, Some(title.clone()), cx);
                            }
                        }));
                    pills = pills.child(self.focus.button(first_item + offset, tag, cx));
                }
                if section.collapsible && section.items.len() > 10 {
                    let label = if expanded {
                        t(Str::ShowLess).to_owned()
                    } else {
                        tf(Str::ShowMoreN, &[&(section.items.len() - 10)])
                    };
                    let more = Button::new(("tags-more", section_index))
                        .small()
                        .outline()
                        .rounded_full()
                        .label(label)
                        .on_click(cx.listener(move |view, _, _, cx| view.toggle_section(section_index, cx)));
                    pills = pills.child(self.controls.button(section_index * 3 + 2, more, cx));
                }
                return block.child(stops.wrap(pills, title, cx)).into_any_element();
            }
            "carousel" => {
                let drag = self.carousel_drags.borrow_mut().entry(section_index).or_default().clone();
                let row_scroll = drag.scroll().clone();
                let stops = self.focus.list_stops(first_item..first_item + section.items.len(), window, cx);
                let mut row = h_flex()
                    .id(("carousel-scroll", section_index))
                    .track_scroll(&row_scroll)
                    .overflow_x_scroll()
                    .restrict_scroll_to_axis()
                    .gap_1()
                    .px_3();
                for (offset, item) in section.items.iter().enumerate() {
                    row = row.child(
                        self.clickable(
                            ("carousel", first_item + offset),
                            offset,
                            item,
                            None,
                            Some(row_scroll.clone()),
                            cx,
                        )
                        .w(px(80.))
                        .flex_shrink_0()
                        .p_1()
                        .child(
                            v_flex()
                                .items_center()
                                .gap_1()
                                .child(
                                    div().size(px(64.)).rounded_full().overflow_hidden().bg(theme.secondary).when_some(
                                        thumb_for(&item.thumbs, 128),
                                        |frame, url| {
                                            frame
                                                .child(img(url).size_full().rounded_full().object_fit(ObjectFit::Cover))
                                        },
                                    ),
                                )
                                .child(div().w_full().text_xs().text_center().truncate().child(item.title.clone())),
                        ),
                    );
                }
                return block.child(drag.wrap(stops.wrap(row, title, cx))).into_any_element();
            }
            _ => {}
        }
        // A collapsed section keeps columns x rows, rows from the engine's ladder or 4/3/2.
        let ladder: Vec<usize> = if section.collapsed_rows.is_empty() {
            vec![4, 3, 2]
        } else {
            section.collapsed_rows.iter().map(|rows| (*rows).max(1) as usize).collect()
        };
        let rows = ladder.get(bands.columns - 1).or(ladder.last()).copied().unwrap_or(2);
        let limit = bands.columns * rows;
        let shown = if section.collapsible && !expanded { limit.min(section.items.len()) } else { section.items.len() };
        let items = &section.items[..shown];
        let stops = self.focus.list_stops(first_item..first_item + shown, window, cx);
        let mut list = v_flex().id(("section-items", section_index)).w_full();
        if bands.columns == 1 {
            for (offset, item) in items.iter().enumerate() {
                list = list.child(self.render_row(item, first_item + offset, offset, bands, queue.clone(), cx));
            }
        } else {
            let inset = bands.inset();
            for (chunk_index, chunk) in items.chunks(bands.columns).enumerate() {
                let mut row = h_flex()
                    .w_full()
                    .items_start()
                    .px(px(bands.margin - inset))
                    .pb(px(ROW_GAP - 2. * inset))
                    .gap(px(bands.gutter - 2. * inset));
                for (offset, item) in chunk.iter().enumerate() {
                    let position = chunk_index * bands.columns + offset;
                    row = row.child(self.render_card(item, first_item + position, position, bands, queue.clone(), cx));
                }
                for _ in chunk.len()..bands.columns {
                    row = row.child(div().flex_1().min_w_0());
                }
                list = list.child(row);
            }
        }
        block = block.child(stops.wrap(list, title, cx));
        if section.collapsible && section.items.len() > limit {
            let label = if expanded {
                t(Str::ShowLess).to_owned()
            } else {
                tf(Str::ShowMoreN, &[&(section.items.len() - limit)])
            };
            let more = Button::new(("section-more", section_index))
                .outline()
                .rounded_full()
                .w_full()
                .label(label)
                .icon(Icon::new(if expanded { IconName::ChevronUp } else { IconName::ChevronDown }))
                .on_click(cx.listener(move |view, _, _, cx| view.toggle_section(section_index, cx)));
            block = block.child(
                div().px(px(bands.margin)).child(self.controls.button(section_index * 3 + 2, more, cx).w_full()),
            );
        }
        block.into_any_element()
    }
}

impl FeedView {
    /// The JVM app's `EmptyState`: centred in the rest of the page, a picture, the source's own
    /// title, its message or this surface's, then Reload (Home leads with Search videos instead).
    fn render_empty(&self, cx: &mut Context<Self>) -> AnyElement {
        let (muted, ink) = (cx.theme().muted_foreground, cx.theme().foreground);
        let message = self.page.empty_message.clone().unwrap_or_else(|| self.source.empty_message().to_owned());
        // Material's filled / outlined button: a 40 px pill.
        let pill = |button: Button| button.h(px(40.)).px_6().rounded_full();
        let reload = pill(Button::new("empty-reload"))
            .label(t(Str::Reload))
            .on_click(cx.listener(|view, _, _, cx| view.reload(cx)));
        let actions = if self.source == FeedSource::Home {
            let search = pill(Button::new("empty-search"))
                .primary()
                .label(t(Str::SearchVideos))
                .on_click(cx.listener(|_, _, _, cx| cx.emit(RequestSearch)));
            h_flex()
                .gap_2()
                .child(self.actions.button(ACTION_RELOAD, reload.outline().icon(Lucide::RefreshCw), cx))
                .child(self.actions.button(ACTION_SEARCH, search, cx))
        } else {
            h_flex().child(self.actions.button(ACTION_RELOAD, reload.primary(), cx))
        };
        div()
            .flex_1()
            .flex()
            .items_center()
            .justify_center()
            .p_6()
            .child(
                v_flex()
                    .items_center()
                    .gap_2()
                    .child(div().pb_1().child(Icon::new(Lucide::Inbox).size(px(52.)).text_color(muted)))
                    .when_some(self.page.empty_title.clone(), |empty, title| {
                        empty.child(
                            heading("empty-title", 2, title).text_base().font_semibold().text_color(ink).text_center(),
                        )
                    })
                    .child(
                        match &self.page.empty_title {
                            Some(title) => {
                                announced_as("empty-message", message.clone(), format!("{title}. {message}"))
                            }
                            None => announced("empty-message", message),
                        }
                        .text_center()
                        .text_color(muted),
                    )
                    .child(actions.pt_2()),
            )
            .into_any_element()
    }

    /// The server sort menu: the current order on the trigger, the options in a dropdown; picking
    /// one re-serves the feed through its token, keeping the page up like a chip.
    fn render_sort_menu(&self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let options = self.page.sort.clone();
        let current = options.iter().find(|option| option.selected).or(options.first())?.title.clone();
        let busy = matches!(self.refiltering, Some(Refilter::Sort(_)));
        let view = cx.entity().downgrade();
        let trigger = Button::new("feed-sort")
            .outline()
            .small()
            .rounded_full()
            .when(!busy, |button| button.icon(Lucide::ArrowUpDown))
            .label(current.clone())
            .busy(busy)
            .accessibility_label(format!("{}, {current}", t(Str::CdSort)))
            .track_focus(&self.chips.handle(CHIPS_SORT, cx))
            .dropdown_menu_with_anchor(Anchor::TopLeft, move |mut menu, _, _| {
                for (index, option) in options.iter().enumerate() {
                    let (view, token, selected) = (view.clone(), option.token.clone(), option.selected);
                    menu = menu.item(PopupMenuItem::new(option.title.clone()).checked(selected).on_click(
                        move |_, window, cx| {
                            if let Some(token) = token.clone().filter(|_| !selected) {
                                view.update(cx, |view, cx| view.refilter(Refilter::Sort(index), token, window, cx))
                                    .ok();
                            }
                        },
                    ));
                }
                menu
            });
        Some(self.chips.wrap(CHIPS_SORT, trigger, cx).into_any_element())
    }
}

#[derive(Clone, Copy)]
enum MenuAction {
    PlaySingle,
    Feed(FeedAction),
    Share,
    RemoveFromTop,
    AddToPlaylist,
    RemoveFromPlaylist,
    Subscribe(bool),
}

fn share_link(item: &YtFeedItem) -> Option<String> {
    match item.kind.as_str() {
        "video" => Some(format!("https://youtu.be/{}", item.id)),
        "playlist" if !item.id.is_empty() => Some(format!("https://www.youtube.com/playlist?list={}", item.id)),
        "channel" => {
            item.channel_id.as_ref().or(Some(&item.id)).map(|id| format!("https://www.youtube.com/channel/{id}"))
        }
        _ => None,
    }
}

impl Render for FeedView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        match &self.phase {
            Phase::Loading => return centered(Spinner::new().large()).into_any_element(),
            Phase::Failed(error) => {
                let message = error_text(error);
                return centered(
                    v_flex()
                        .gap_3()
                        .items_center()
                        .max_w(px(420.))
                        .p_6()
                        .child(alert("feed-error", message).text_center())
                        .child(
                            self.failure.button(
                                1,
                                Button::new("retry")
                                    .label(t(Str::Retry))
                                    .on_click(cx.listener(|view, _, _, cx| view.reload(cx))),
                                cx,
                            ),
                        ),
                )
                .into_any_element();
            }
            Phase::Ready => {}
        }
        let bands = Bands::for_width(feed_width(window));
        let theme = cx.theme().clone();
        let mut column = v_flex().w_full().pb_4().children(self.top.clone());
        let tabs = self.page.page_tabs.clone();
        let selected_tab = tabs.iter().position(|tab| tab.selected).unwrap_or(0);
        let on_home_tab = tabs.get(selected_tab).is_none_or(|tab| tab.home);
        let category = self.header_kind() == HeaderKind::Category;
        if on_home_tab
            && self.top.is_none()
            && let Some(header) = self.page.page_header.clone()
        {
            column = column.child(self.render_header(&header, cx));
        } else if category {
            column = column.child(self.render_category_bar(cx));
        }
        let tab_row = (!tabs.is_empty()).then(|| {
            let urls: Vec<Option<String>> = tabs.iter().map(|tab| tab.url.clone()).collect();
            let entity = cx.entity().downgrade();
            div().px_3().border_b_1().border_color(theme.border).child(tab_row(
                "page-tabs",
                tabs.iter().map(|tab| SharedString::from(tab.title.clone())).collect(),
                selected_tab,
                &self.tabs,
                &self.tabs_drag,
                move |index, _, cx| {
                    if let Some(Some(url)) = urls.get(index).cloned() {
                        entity.update(cx, |view, cx| view.open_tab(url, cx)).ok();
                    }
                },
                cx,
            ))
        });
        if !self.page.chips.is_empty() || !self.page.sort.is_empty() {
            let wrap = self.page.chips_wrap;
            let mut chips =
                h_flex().id("chips").gap_2().px_4().py_2().when(wrap, |row| row.flex_wrap()).when(!wrap, |row| {
                    row.track_scroll(self.chips_drag.scroll()).overflow_x_scroll().restrict_scroll_to_axis()
                });
            for (index, chip) in self.page.chips.clone().into_iter().enumerate() {
                let token = chip.token.clone();
                let loading = self.refiltering == Some(Refilter::Chip(index));
                let mut button = crate::action_icon::chip(Button::new(("chip", index))).label(chip.title.clone());
                button = if chip.selected { button.primary() } else { button.outline() };
                let button = button
                    .selected(chip.selected)
                    .when(loading, |button| button.child(Spinner::new().small()))
                    .on_click(cx.listener(move |view, _, window, cx| {
                        if let Some(token) = token.clone() {
                            view.refilter(Refilter::Chip(index), token, window, cx);
                        }
                    }));
                let row = (!wrap).then(|| self.chips_drag.scroll().clone());
                chips = chips.child(self.chips.button_in(index, button, row, cx));
            }
            if let Some(sort) = self.render_sort_menu(cx) {
                chips = chips.child(sort);
            }
            column = column.child(if wrap { div().child(chips) } else { self.chips_drag.wrap(chips) });
        } else if self.page.page_header.is_none() && !category {
            column = column.child(div().h(px(6.)));
        }

        let item_total: usize = self.page.sections.iter().map(|section| section.items.len()).sum();
        if item_total == 0 {
            column = column.min_h_full().child(self.render_empty(cx));
        }
        let mut first_item = 0;
        for (section_index, section) in self.page.sections.clone().iter().enumerate() {
            column = column.child(self.render_section(section, section_index, first_item, bands, window, cx));
            first_item += section.items.len();
        }
        if self.loading_more {
            column = column.child(h_flex().justify_center().p_3().child(Spinner::new()));
        } else if let Some(error) = &self.more_failed {
            column = column.child(
                v_flex().items_center().gap_2().py_4().child(alert("more-error", error_text(error)).text_sm()).child(
                    self.actions.button(
                        ACTION_RETRY_MORE,
                        Button::new("retry-more").small().label(t(Str::Retry)).on_click(cx.listener(
                            |view, _, _, cx| {
                                view.more_failed = None;
                                view.load_more(cx);
                            },
                        )),
                        cx,
                    ),
                ),
            );
        } else if self.page.continuation.is_some() {
            column = column.child(
                div().px(px(bands.margin)).pt_2().child(
                    self.chips
                        .button(
                            CHIPS_SHOW_MORE,
                            Button::new("show-more")
                                .outline()
                                .rounded_full()
                                .w_full()
                                .label(t(Str::ShowMore))
                                .on_click(cx.listener(|view, _, _, cx| view.load_more(cx))),
                            cx,
                        )
                        .w_full(),
                ),
            );
        }
        v_flex()
            .size_full()
            .relative()
            .children(tab_row)
            .child(
                div().id("feed-scroll").flex_1().min_h_0().overflow_y_scroll().track_scroll(&self.scroll).child(column),
            )
            .when(self.refreshing, |feed| {
                feed.child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .right_0()
                        .child(LoadingLine::new("feed-refreshing", t(Str::A11yRefreshing))),
                )
            })
            .into_any_element()
    }
}
