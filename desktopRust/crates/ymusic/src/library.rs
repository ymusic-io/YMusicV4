//! The local Library, laid out like the JVM app: pinned text tabs (Folders, Playlists, Songs,
//! Artists, Albums, Genres), then per tab a 40-tall toolbar (folders: Add folder, rescan,
//! breadcrumb; playlists: New playlist; songs: Shuffle all; all: the sort control, remembered under
//! the JVM's `library.sort.<list>` keys) over a grid of folder/playlist/group cards (at most 176
//! wide) and song cells (at least 360 wide); a list below 480.

use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Disableable, Icon, IconName, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_store::{Bucket, MediaItem, MediaStore, Playlist, PlaylistItemDetail};

use crate::a11y::{DragRow, FocusList, ListStops, announced, heading, label, list_item};
use crate::action_icon::action_icon;
use crate::file_rename::RenameFileDialog;
use crate::graph::{AppGraph, on_core};
use crate::library_playlist::{self, AddToLocalPlaylist, ArrangePlaylist, Changed, NameMode, PlaylistNameDialog};
use crate::mini_player::clock;
use crate::playback::QueueItem;
use crate::playlist_transfer::PlaylistTransfer;
use crate::spinner::LoadingLine;
use crate::tab_row::tab_row;

/// Writes that land within this window of each other cost one reload (a scan writes row by row).
const STORE_CHANGE_COALESCE: std::time::Duration = std::time::Duration::from_millis(300);

/// Runs `reload` on the view after media store writes (a finished download, a scan, a dialog), for
/// as long as the view lives.
pub fn on_library_change<V: 'static>(cx: &mut Context<V>, reload: impl Fn(&mut V, &mut Context<V>) + 'static) {
    let (sender, mut receiver) = futures::channel::mpsc::unbounded::<()>();
    let mut changes = cx.global::<AppGraph>().media.changes();
    let forward = on_core(cx, async move {
        while changes.changed().await.is_ok() {
            tokio::time::sleep(STORE_CHANGE_COALESCE).await;
            changes.borrow_and_update();
            if sender.unbounded_send(()).is_err() {
                return;
            }
        }
    });
    cx.background_executor().spawn(forward).detach();
    cx.spawn(async move |this, cx| {
        while futures::StreamExt::next(&mut receiver).await.is_some() {
            if this.update(cx, |view, cx| reload(view, cx)).is_err() {
                return;
            }
        }
    })
    .detach();
}

/// Play these local files in order from `index`.
pub struct PlayLocal {
    pub items: Vec<QueueItem>,
    pub index: usize,
}

/// Put a local file next in the queue, or at its end.
pub struct QueueLocal {
    pub item: QueueItem,
    pub next: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum GroupKind {
    Artist,
    Album,
}

/// What a song's menu asks of the view listing it.
pub enum SongAction {
    GoTo(GroupKind, String),
    ShowInFolder(i64),
    RemoveFromPlaylist(MediaItem),
    RemoveFromHistory(MediaItem),
    /// A dialog changed the library (a file deleted, a playlist added to).
    Changed,
}

/// A view whose song cells carry the song menu.
pub trait SongMenuHost: EventEmitter<QueueLocal> + Sized + 'static {
    fn song_action(&mut self, action: SongAction, window: &mut Window, cx: &mut Context<Self>);
}

/// The context-dependent entries of a song menu.
#[derive(Clone, Copy, Default)]
pub struct SongMenu {
    pub remove_from_playlist: bool,
    pub remove_from_history: bool,
    pub show_in_folder: bool,
}

/// One remembered sort: an index into its list's options and the direction, stored as `index,0|1`.
#[derive(Clone, Copy, PartialEq, Eq)]
struct SortState {
    index: usize,
    descending: bool,
}

impl SortState {
    fn decode(raw: Option<&str>, default_index: usize) -> Self {
        let raw = raw.unwrap_or_default();
        let mut parts = raw.split(',');
        match parts.next().and_then(|index| index.parse().ok()) {
            Some(index) => Self { index, descending: parts.next() == Some("1") },
            None => Self { index: default_index, descending: false },
        }
    }

    fn encode(self) -> String {
        format!("{},{}", self.index, u8::from(self.descending))
    }
}

/// A sortable list: its settings id, option labels and the manual-order option, if any.
#[derive(Clone, Copy)]
struct SortContext {
    id: &'static str,
    labels: &'static [Str],
    manual: Option<usize>,
}

const SONG_SORT: &[Str] = &[Str::SortName, Str::SortDateAdded, Str::SortType, Str::SortTrackNumber];
/// The prefix of [`SONG_SORT`]: the Folders tab sorts its folders and songs with one index.
const FOLDER_SORT: &[Str] = &[Str::SortName, Str::SortDateAdded];
const GROUP_SORT: &[Str] = &[Str::SortName, Str::SortDateCreated];
const PLAYLIST_SORT: &[Str] = &[Str::SortName, Str::SortDateCreated];
/// Manual is last so the indices the JVM app stored keep their meaning.
const PLAYLIST_SONG_SORT: &[Str] = &[Str::SortLastAdded, Str::SortName, Str::SortManual];
const PLAYLIST_MANUAL_SORT: usize = 2;
const SORT_IDS: [&str; 10] = [
    "folders",
    "songs",
    "artists",
    "artists.songs",
    "albums",
    "albums.songs",
    "genres",
    "genres.songs",
    "playlists",
    "playlist.songs",
];

const GRID_MIN_WIDTH: f32 = 480.;
const CARD_MAX: f32 = 176.;
const SONG_MIN: f32 = 360.;
const GRID_PADDING: f32 = 10.;
const THUMB: f32 = 56.;
const TOOL_ADD_FOLDER: usize = 0;
const TOOL_SHUFFLE: usize = 1;
const TOOL_SORT: usize = 2;
const TOOL_SORT_DIRECTION: usize = 3;
const TOOL_NEW_PLAYLIST: usize = 4;
const TOOL_PLAYLIST_MENU: usize = 5;
const TOOL_EMPTY_RESTORE: usize = 6;
const TOOL_RESCAN: usize = 7;
const TOOL_SCAN_EMPTY: usize = 8;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Tab {
    Folders,
    Playlists,
    Songs,
    Artists,
    Albums,
    Genres,
}

impl Tab {
    const ALL: [Tab; 6] = [Tab::Folders, Tab::Playlists, Tab::Songs, Tab::Artists, Tab::Albums, Tab::Genres];

    fn label(self) -> &'static str {
        t(match self {
            Tab::Folders => Str::LibraryTabFolders,
            Tab::Playlists => Str::LibraryTabPlaylists,
            Tab::Songs => Str::LibraryTabSongs,
            Tab::Artists => Str::LibraryTabArtists,
            Tab::Albums => Str::LibraryTabAlbums,
            Tab::Genres => Str::LibraryTabGenres,
        })
    }

    fn sort_id(self) -> &'static str {
        match self {
            Tab::Folders => "folders",
            Tab::Playlists => "playlists",
            Tab::Songs => "songs",
            Tab::Artists => "artists",
            Tab::Albums => "albums",
            Tab::Genres => "genres",
        }
    }

    fn group_songs_sort_id(self) -> &'static str {
        match self {
            Tab::Artists => "artists.songs",
            Tab::Albums => "albums.songs",
            _ => "genres.songs",
        }
    }

    fn unknown_group(self) -> &'static str {
        t(match self {
            Tab::Artists => Str::UnknownArtist,
            Tab::Albums => Str::UnknownAlbum,
            _ => Str::UnknownGenre,
        })
    }
}

/// The Playlists tab's opened list: a computed one from play history, or one of the user's.
#[derive(Clone, PartialEq)]
enum OpenPlaylist {
    Recent,
    Top,
    User(Playlist),
}

impl OpenPlaylist {
    fn title(&self) -> String {
        match self {
            OpenPlaylist::Recent => t(Str::PlaylistRecentPlay).to_owned(),
            OpenPlaylist::Top => t(Str::PlaylistTopTracks).to_owned(),
            OpenPlaylist::User(playlist) => playlist.name.clone(),
        }
    }
}

/// Everything the non-folder tabs render from.
#[derive(Default)]
struct Loaded {
    items: Vec<MediaItem>,
    recent: Vec<MediaItem>,
    top: Vec<MediaItem>,
    playlists: Vec<(Playlist, Vec<PlaylistItemDetail>)>,
    folders_added: bool,
    ready: bool,
}

fn by_codes(items: &[MediaItem], codes: &[String]) -> Vec<MediaItem> {
    let by_code: HashMap<String, &MediaItem> = items.iter().map(|item| (item.playlist_code(), item)).collect();
    codes.iter().filter_map(|code| by_code.get(code).map(|item| (*item).clone())).collect()
}

/// Sorts songs by [`SONG_SORT`]'s option `index`.
fn sort_songs(items: &mut [MediaItem], state: SortState) {
    match state.index {
        1 => items.sort_by_key(|item| item.created_at),
        2 => items.sort_by_key(|item| (item.video_codec.is_some(), item.title.to_lowercase())),
        3 => items.sort_by_key(|item| (item.track_number.is_none(), item.track_number, item.title.to_lowercase())),
        _ => items.sort_by_key(|item| item.title.to_lowercase()),
    }
    if state.descending {
        items.reverse();
    }
}

pub struct LibraryView {
    store: Arc<MediaStore>,
    history: Arc<ym_history::PlayHistory>,
    tab: Tab,
    loaded: Loaded,
    /// The artist, album or genre opened from its tab.
    group: Option<String>,
    open_playlist: Option<OpenPlaylist>,
    /// The open folder chain; empty is the root.
    path: Vec<Bucket>,
    folders: Vec<Bucket>,
    /// Whether a folder has been added, `None` until the store answers: without one the Library is
    /// only its Scan folder prompt, no tabs.
    folders_added: Option<bool>,
    items: Vec<MediaItem>,
    download_folder: Option<PathBuf>,
    sorts: HashMap<&'static str, SortState>,
    scanning: bool,
    /// A failed read, shown as a snackbar at the next render.
    notice: Option<String>,
    /// The window the Library draws in, where a scan's report shows even once the Library is left.
    window: Option<AnyWindowHandle>,
    scroll: ScrollHandle,
    card_focus: FocusList,
    tab_focus: FocusList,
    tool_focus: FocusList,
    crumb_focus: FocusList,
    tab_drag: DragRow,
    crumb_drag: DragRow,
    /// The open folder the crumbs were last scrolled to the end for.
    crumbs_shown_for: Option<i64>,
    song_focus: FocusList,
    /// Debug-route actions, one run after each finished reload.
    dev_actions: Vec<String>,
    dev_ready: bool,
    /// The first Library folder, offered as the download location once it was added.
    location_offer: Option<Bucket>,
    transfer: PlaylistTransfer,
}

impl EventEmitter<PlayLocal> for LibraryView {}
impl EventEmitter<QueueLocal> for LibraryView {}

impl LibraryView {
    pub fn new(store: Arc<MediaStore>, cx: &mut Context<Self>) -> Self {
        let graph = cx.global::<AppGraph>();
        let history = graph.history.clone();
        let settings = graph.settings.clone();
        let sorts = SORT_IDS
            .into_iter()
            .map(|id| {
                let raw = settings.get_raw(&format!("library.sort.{id}")).ok().flatten();
                let default = if id == "playlist.songs" { PLAYLIST_MANUAL_SORT } else { 0 };
                (id, SortState::decode(raw.as_deref(), default))
            })
            .collect();
        let scroll = ScrollHandle::new();
        let mut view = Self {
            store,
            history,
            tab: Tab::Folders,
            loaded: Loaded::default(),
            group: None,
            open_playlist: None,
            path: Vec::new(),
            folders: Vec::new(),
            folders_added: None,
            items: Vec::new(),
            download_folder: None,
            sorts,
            scanning: false,
            notice: None,
            window: None,
            scroll: scroll.clone(),
            card_focus: FocusList::new(Some(scroll.clone())),
            tab_focus: FocusList::new(None),
            tool_focus: FocusList::new(None),
            crumb_focus: FocusList::new(None),
            tab_drag: DragRow::default(),
            crumb_drag: DragRow::default(),
            crumbs_shown_for: None,
            song_focus: FocusList::new(Some(scroll)),
            dev_actions: Vec::new(),
            dev_ready: false,
            location_offer: None,
            transfer: PlaylistTransfer::default(),
        };
        view.refresh(cx);
        on_library_change(cx, |view, cx| {
            view.refresh(cx);
            if view.tab != Tab::Folders {
                view.refresh_all(cx);
            }
        });
        view
    }

    /// Reloads the open folder, and the other tabs' data when one of them shows.
    fn reload(&mut self, cx: &mut Context<Self>) {
        crate::offline::OfflineMarks::refresh(cx);
        self.refresh(cx);
        if self.tab != Tab::Folders {
            self.refresh_all(cx);
        }
    }

    /// A dialog's callback that reloads this view.
    fn changed(&self, cx: &mut Context<Self>) -> Changed {
        let view = cx.entity().downgrade();
        Rc::new(move |_, cx| {
            view.update(cx, |view, cx| view.reload(cx)).ok();
        })
    }

    fn refresh(&mut self, cx: &mut Context<Self>) {
        self.download_folder = crate::downloads::download_folder(cx.global::<AppGraph>().settings.as_ref());
        let store = self.store.clone();
        let folder = self.path.last().map(|bucket| bucket.id);
        let load = on_core(cx, async move {
            tokio::task::spawn_blocking(move || match folder {
                None => Ok((store.root_buckets()?, Vec::new())),
                Some(id) => Ok::<_, ym_store::StoreError>((store.child_buckets(id)?, store.items_in_bucket(id)?)),
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(Ok((folders, items)))) => {
                        if folder.is_none() {
                            view.folders_added = Some(!folders.is_empty());
                        }
                        view.folders = folders;
                        view.items = items;
                    }
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>().log.warn("R90008 library read failed", Some(&error));
                        view.notice = Some(tf(Str::ErrorGeneric, &[&"R90008"]));
                    }
                    _ => {}
                }
                if view.tab == Tab::Folders {
                    view.run_dev_action(cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub fn select_tab_index(&mut self, index: usize, cx: &mut Context<Self>) {
        self.select_tab(Tab::ALL[index.min(Tab::ALL.len() - 1)], cx);
    }

    fn select_tab(&mut self, tab: Tab, cx: &mut Context<Self>) {
        self.tab = tab;
        self.group = None;
        self.open_playlist = None;
        if tab == Tab::Folders {
            self.refresh(cx);
        } else {
            self.refresh_all(cx);
        }
        cx.notify();
    }

    /// Every file, the play-history lists and the user's playlists with their resolved members.
    fn refresh_all(&mut self, cx: &mut Context<Self>) {
        let (store, history) = (self.store.clone(), self.history.clone());
        let load = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let mut items = store.all_items().map_err(|error| error.to_string())?;
                items.sort_by_key(|item| item.title.to_lowercase());
                let recent = history.local_recent(ym_history::RETAIN).map_err(|error| error.to_string())?;
                let top = history.local_top(ym_history::RETAIN).map_err(|error| error.to_string())?;
                let folders_added = !store.root_buckets().map_err(|error| error.to_string())?.is_empty();
                let mut playlists = Vec::new();
                for playlist in store.playlists().map_err(|error| error.to_string())? {
                    let details = store.playlist_item_details(playlist.id).map_err(|error| error.to_string())?;
                    playlists.push((playlist, details));
                }
                Ok::<_, String>(Loaded {
                    recent: by_codes(&items, &recent),
                    top: by_codes(&items, &top),
                    items,
                    playlists,
                    folders_added,
                    ready: true,
                })
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(Ok(loaded))) => {
                        if let Some(OpenPlaylist::User(open)) = &view.open_playlist {
                            view.open_playlist = loaded
                                .playlists
                                .iter()
                                .find(|(playlist, _)| playlist.id == open.id)
                                .map(|(playlist, _)| OpenPlaylist::User(playlist.clone()));
                        }
                        view.folders_added = Some(loaded.folders_added);
                        view.loaded = loaded;
                    }
                    Some(Ok(Err(message))) => {
                        cx.global::<AppGraph>().log.warn(&format!("R90008 library read failed: {message}"), None);
                        view.notice = Some(tf(Str::ErrorGeneric, &[&"R90008"]));
                    }
                    _ => {}
                }
                view.run_dev_action(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn sort_state(&self, id: &'static str) -> SortState {
        self.sorts.get(id).copied().unwrap_or(SortState { index: 0, descending: false })
    }

    fn set_sort(&mut self, id: &'static str, state: SortState, cx: &mut Context<Self>) {
        self.sorts.insert(id, state);
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("settings"));
        graph.tokio.spawn_blocking(move || {
            if let Err(error) = settings.put_raw(&format!("library.sort.{id}"), state.encode()) {
                log.warn(&format!("library.sort.{id} could not be saved: {error}"), None);
            }
        });
        cx.notify();
    }

    /// The list the toolbar's sort control orders, if it has one.
    fn sort_context(&self) -> Option<SortContext> {
        let context = |id, labels| Some(SortContext { id, labels, manual: None });
        match (self.tab, &self.group, &self.open_playlist) {
            (Tab::Folders, _, _) => context("folders", FOLDER_SORT),
            (Tab::Songs, _, _) => context("songs", SONG_SORT),
            (Tab::Playlists, _, None) => context("playlists", PLAYLIST_SORT),
            (Tab::Playlists, _, Some(OpenPlaylist::User(_))) => Some(SortContext {
                id: "playlist.songs",
                labels: PLAYLIST_SONG_SORT,
                manual: Some(PLAYLIST_MANUAL_SORT),
            }),
            (Tab::Playlists, _, Some(_)) => None,
            (tab, None, _) => context(tab.sort_id(), GROUP_SORT),
            (tab, Some(_), _) => context(tab.group_songs_sort_id(), SONG_SORT),
        }
    }

    /// Picking the current option flips the direction, another selects it ascending; the manual
    /// option selects it and opens the arranger.
    fn pick_sort(&mut self, context: SortContext, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let current = self.sort_state(context.id);
        let state = if context.manual == Some(index) || index != current.index {
            SortState { index, descending: false }
        } else {
            SortState { index, descending: !current.descending }
        };
        self.set_sort(context.id, state, cx);
        if context.manual == Some(index) {
            self.arrange(window, cx);
        }
    }

    fn open_user_playlist(&self) -> Option<&(Playlist, Vec<PlaylistItemDetail>)> {
        let Some(OpenPlaylist::User(open)) = &self.open_playlist else { return None };
        self.loaded.playlists.iter().find(|(playlist, _)| playlist.id == open.id)
    }

    fn arrange(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some((playlist, details)) = self.open_user_playlist() else { return };
        let (id, details) = (playlist.id, details.clone());
        let changed = self.changed(cx);
        ArrangePlaylist::open(id, details, Some(changed), window, cx);
    }

    fn create_playlist(&mut self, name: String, window: &mut Window, cx: &mut Context<Self>) {
        let names = self.loaded.playlists.iter().map(|(playlist, _)| playlist.name.clone()).collect();
        let changed = self.changed(cx);
        PlaylistNameDialog::open(NameMode::Create { codes: Vec::new(), name }, names, Some(changed), window, cx);
    }

    fn rename_playlist(&mut self, playlist: Playlist, window: &mut Window, cx: &mut Context<Self>) {
        let names = self.loaded.playlists.iter().map(|(playlist, _)| playlist.name.clone()).collect();
        let changed = self.changed(cx);
        PlaylistNameDialog::open(NameMode::Rename(playlist), names, Some(changed), window, cx);
    }

    fn delete_playlist(&mut self, playlist: Playlist, window: &mut Window, cx: &mut Context<Self>) {
        let changed = self.changed(cx);
        library_playlist::confirm_delete_playlist(playlist, Some(changed), window, cx);
    }

    /// `target` skips the save panel (debug routes).
    fn backup_playlists(&mut self, target: Option<PathBuf>, window: &mut Window, cx: &mut Context<Self>) {
        self.transfer.backup(target, window, cx);
        cx.notify();
    }

    /// `source` skips the open panel (debug routes).
    fn restore_playlists(&mut self, source: Option<PathBuf>, window: &mut Window, cx: &mut Context<Self>) {
        let changed = self.changed(cx);
        self.transfer.open_restore(source, self.loaded.playlists.len(), changed, window, cx);
    }

    /// Each group's name and members, by name; an untagged file falls under the tab's unknown name.
    fn grouped(&self) -> Vec<(String, Vec<&MediaItem>)> {
        let mut groups: std::collections::BTreeMap<String, Vec<&MediaItem>> = std::collections::BTreeMap::new();
        for item in &self.loaded.items {
            groups.entry(self.group_of(item)).or_default().push(item);
        }
        let mut groups: Vec<_> = groups.into_iter().collect();
        groups.sort_by_key(|(name, _)| name.to_lowercase());
        groups
    }

    fn group_of(&self, item: &MediaItem) -> String {
        let name = match self.tab {
            Tab::Artists => item.artist.as_ref(),
            Tab::Albums => item.album.as_ref(),
            _ => item.genre.as_ref(),
        };
        name.filter(|name| !name.trim().is_empty()).cloned().unwrap_or_else(|| self.tab.unknown_group().to_owned())
    }

    /// The group cards of the open tab, sorted: name, song count and the first member with artwork.
    fn groups(&self) -> Vec<(String, usize, Option<MediaItem>)> {
        let mut groups = self.grouped();
        let state = self.sort_state(self.tab.sort_id());
        if state.index == 1 {
            groups.sort_by_key(|(_, members)| members.iter().map(|item| item.created_at).min().unwrap_or(0));
        }
        if state.descending {
            groups.reverse();
        }
        groups
            .into_iter()
            .map(|(name, members)| {
                let art = members
                    .iter()
                    .find(|item| crate::local_art::art_url(item, 0).is_some())
                    .map(|item| (*item).clone());
                (name, members.len(), art)
            })
            .collect()
    }

    fn sorted_folders(&self) -> Vec<Bucket> {
        let mut folders = self.folders.clone();
        let state = self.sort_state("folders");
        if state.index == 1 {
            folders.sort_by_key(|bucket| bucket.created_at);
        } else {
            folders.sort_by_key(|bucket| bucket.display_name().to_lowercase());
        }
        if state.descending {
            folders.reverse();
        }
        folders
    }

    /// The user playlists, sorted, with their song count and the art of the newest member with some.
    fn playlist_cards(&self) -> Vec<(Playlist, usize, Option<MediaItem>)> {
        let mut playlists: Vec<_> = self.loaded.playlists.iter().collect();
        let state = self.sort_state("playlists");
        if state.index == 1 {
            playlists.sort_by_key(|(playlist, _)| playlist.created_at);
        } else {
            playlists.sort_by_key(|(playlist, _)| playlist.name.to_lowercase());
        }
        if state.descending {
            playlists.reverse();
        }
        playlists
            .into_iter()
            .map(|(playlist, details)| {
                let mut newest: Vec<&PlaylistItemDetail> = details.iter().collect();
                newest.sort_by_key(|detail| std::cmp::Reverse(detail.added_at));
                let art = newest
                    .into_iter()
                    .find(|detail| crate::local_art::art_url(&detail.item, 0).is_some())
                    .map(|detail| detail.item.clone());
                (playlist.clone(), details.len(), art)
            })
            .collect()
    }

    /// The files the open tab lists, in the order they play.
    fn shown_items(&self) -> Vec<MediaItem> {
        let (mut items, sort_id) = match self.tab {
            Tab::Folders => (self.items.clone(), "folders"),
            Tab::Songs => (self.loaded.items.clone(), "songs"),
            Tab::Playlists => {
                return match &self.open_playlist {
                    None => Vec::new(),
                    Some(OpenPlaylist::Recent) => self.loaded.recent.clone(),
                    Some(OpenPlaylist::Top) => self.loaded.top.clone(),
                    Some(OpenPlaylist::User(_)) => self.playlist_songs(),
                };
            }
            Tab::Artists | Tab::Albums | Tab::Genres => match &self.group {
                Some(group) => (
                    self.loaded.items.iter().filter(|item| &self.group_of(item) == group).cloned().collect(),
                    self.tab.group_songs_sort_id(),
                ),
                None => return Vec::new(),
            },
        };
        sort_songs(&mut items, self.sort_state(sort_id));
        items
    }

    fn playlist_songs(&self) -> Vec<MediaItem> {
        let Some((_, details)) = self.open_user_playlist() else { return Vec::new() };
        let mut details = details.clone();
        let state = self.sort_state("playlist.songs");
        match state.index {
            0 => details.sort_by_key(|detail| detail.added_at),
            1 => details.sort_by_key(|detail| detail.item.title.to_lowercase()),
            _ => return details.into_iter().map(|detail| detail.item).collect(),
        }
        if state.descending {
            details.reverse();
        }
        details.into_iter().map(|detail| detail.item).collect()
    }

    fn shuffle_all(&mut self, cx: &mut Context<Self>) {
        let mut items: Vec<QueueItem> = self.shown_items().iter().map(QueueItem::from_local).collect();
        if items.is_empty() {
            return;
        }
        rand::seq::SliceRandom::shuffle(items.as_mut_slice(), &mut rand::rng());
        cx.emit(PlayLocal { items, index: 0 });
    }

    /// Re-scans every root folder.
    fn rescan(&mut self, cx: &mut Context<Self>) {
        self.rescan_where(cx, |_| true);
    }

    fn rescan_where(&mut self, cx: &mut Context<Self>, keep: impl Fn(&Bucket) -> bool + Send + 'static) {
        let store = self.store.clone();
        let roots = on_core(cx, async move { tokio::task::spawn_blocking(move || store.root_buckets()).await });
        cx.spawn(async move |this, cx| {
            let result = roots.await;
            this.update(cx, |view, cx| match result {
                Some(Ok(Ok(roots))) => {
                    let paths =
                        roots.iter().filter(|bucket| keep(bucket)).map(|bucket| PathBuf::from(&bucket.uri)).collect();
                    view.scan_paths(paths, false, cx);
                }
                Some(Ok(Err(error))) => {
                    cx.global::<AppGraph>().log.warn("R90008 library read failed", Some(&error));
                    view.notice = Some(tf(Str::ErrorGeneric, &[&"R90008"]));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    fn open_folder(&mut self, bucket: Bucket, cx: &mut Context<Self>) {
        if bucket.excluded {
            return;
        }
        self.path.push(bucket);
        self.refresh(cx);
    }

    /// The Folders tab at the deepest Library folder holding `file`; the root when none does.
    pub fn reveal_file(&mut self, file: PathBuf, cx: &mut Context<Self>) {
        let store = self.store.clone();
        let load = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let mut chain = Vec::new();
                let mut level = store.root_buckets()?;
                while let Some(bucket) = level.into_iter().find(|bucket| file.starts_with(&bucket.uri)) {
                    level = store.child_buckets(bucket.id)?;
                    chain.push(bucket);
                }
                Ok::<_, ym_store::StoreError>(chain)
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                view.select_tab(Tab::Folders, cx);
                match result {
                    Some(Ok(Ok(chain))) => view.path = chain,
                    Some(Ok(Err(error))) => cx
                        .global::<AppGraph>()
                        .log
                        .warn("library folders could not be read to reveal a file", Some(&error)),
                    Some(Err(error)) => cx
                        .global::<AppGraph>()
                        .log
                        .warn("library folders could not be read to reveal a file", Some(&error)),
                    None => {}
                }
                view.refresh(cx);
            })
            .ok();
        })
        .detach();
    }

    fn open_crumb(&mut self, depth: usize, cx: &mut Context<Self>) {
        self.path.truncate(depth);
        self.refresh(cx);
    }

    /// Steps up one level of the open tab: the open folder's parent, out of an opened playlist or
    /// group; nothing at the tab's top level.
    pub fn back(&mut self, cx: &mut Context<Self>) {
        if self.tab == Tab::Folders && !self.path.is_empty() {
            self.open_crumb(self.path.len() - 1, cx);
        } else {
            self.open_playlist = None;
            self.group = None;
        }
        cx.notify();
    }

    /// Remove (a root), Exclude / Include (a subfolder): the store cascades, then the list reloads.
    /// Including clears the flag first, then rescans the root, since scans skip excluded folders.
    fn folder_change(&mut self, bucket: Bucket, change: FolderChange, window: &mut Window, cx: &mut Context<Self>) {
        let id = bucket.id;
        let request = library_playlist::store_call(cx, move |store| match change {
            FolderChange::Remove => store.remove_bucket(id),
            FolderChange::Exclude => store.set_bucket_excluded(id, true),
            FolderChange::Include => store.set_bucket_excluded(id, false),
        });
        let root = self.path.first().map(|root| root.id);
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| match result {
                Ok(()) => {
                    if change == FolderChange::Include
                        && let Some(root) = root
                    {
                        view.rescan_where(cx, move |bucket| bucket.id == root);
                    }
                    view.reload(cx);
                }
                Err(error) => {
                    library_playlist::report("R94004", "changing a library folder failed", &error, window, cx)
                }
            })
            .ok();
        })
        .detach();
    }

    /// Makes `bucket` the download folder after a test write there, or clears it when it is one.
    fn toggle_download_folder(&mut self, bucket: Bucket, window: &mut Window, cx: &mut Context<Self>) {
        let folder = PathBuf::from(&bucket.uri);
        let clear = self.download_folder.as_deref() == Some(folder.as_path());
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("library"));
        let request = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let value = if clear {
                    String::new()
                } else {
                    crate::downloads::probe_download_folder(&folder)?;
                    crate::downloads::folder_uri(&folder)
                };
                if let Err(error) = settings.put_raw(crate::downloads::DOWNLOAD_LOCATION_KEY, value) {
                    log.warn(&format!("download.location could not be saved: {error}"), None);
                }
                Ok(())
            })
            .await
        });
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                if let Some(Ok(Err(problem))) = result {
                    window.push_notification(t(problem), cx);
                }
                view.refresh(cx);
            })
            .ok();
        })
        .detach();
    }

    fn scan(&mut self, cx: &mut Context<Self>) {
        if self.scanning {
            return;
        }
        let pick = crate::desktop::file_dialog(rfd::AsyncFileDialog::new().set_title(t(Str::FolderPickerTitle)).pick_folder(), cx);
        cx.spawn(async move |this, cx| {
            let Some(folder) = pick.await else { return };
            this.update(cx, |view, cx| view.scan_path(folder.path().to_path_buf(), false, cx)).ok();
        })
        .detach();
    }

    /// Scans `root`, then opens it; `play_first` starts its first file (debug routes).
    pub fn scan_path(&mut self, root: PathBuf, play_first: bool, cx: &mut Context<Self>) {
        self.scan_paths(vec![root], play_first, cx);
    }

    fn scan_paths(&mut self, roots: Vec<PathBuf>, play_first: bool, cx: &mut Context<Self>) {
        if self.scanning || roots.is_empty() {
            return;
        }
        let store = self.store.clone();
        cx.spawn(async move |this, cx| {
            let started = this.update(cx, |view, cx| {
                view.scanning = true;
                cx.notify();
            });
            if started.is_err() {
                return;
            }
            let scan = cx.update(|cx| {
                let log = cx.global::<AppGraph>().log.fork("download-location");
                on_core(cx, async move {
                    tokio::task::spawn_blocking(move || {
                        let first_folder = store.root_buckets()?.is_empty();
                        let cancel = AtomicBool::new(false);
                        let mut total = ym_store::ScanReport::default();
                        for root in &roots {
                            let report = ym_store::scan_folder(&store, root, &cancel)?;
                            total.added += report.added;
                            total.updated += report.updated;
                            total.unchanged += report.unchanged;
                            total.removed += report.removed;
                            total.errors.extend(report.errors);
                        }
                        let offer = if first_folder { crate::downloads::location_offer(&store, &log)? } else { None };
                        Ok::<_, ym_store::StoreError>((total, offer))
                    })
                    .await
                })
            });
            let result = scan.await;
            this.update(cx, |view, cx| {
                view.scanning = false;
                let log = cx.global::<AppGraph>().log.fork("library");
                if let Some(Ok(Ok((_, Some(offer))))) = &result
                    && crate::downloads::download_folder(cx.global::<AppGraph>().settings.as_ref()).is_none()
                {
                    view.location_offer = Some(offer.clone());
                }
                let result = result.map(|joined| joined.map(|scanned| scanned.map(|(report, _)| report)));
                let report = match result {
                    Some(Ok(Ok(report))) => {
                        for error in &report.errors {
                            log.warn(&format!("scan skipped {}: {}", error.path.display(), error.message), None);
                        }
                        let (scanned, added, updated, removed) =
                            (report.scanned(), report.added, report.updated, report.removed);
                        if report.errors.is_empty() {
                            tf(Str::LibraryScanSummary, &[&scanned, &added, &updated, &removed])
                        } else {
                            tf(
                                Str::LibraryScanSummaryFailed,
                                &[&scanned, &added, &updated, &removed, &report.errors.len()],
                            )
                        }
                    }
                    Some(Ok(Err(error))) => {
                        log.warn("R90009 library scan failed", Some(&error));
                        tf(Str::LibraryScanFailed, &[&"R90009"])
                    }
                    _ => tf(Str::LibraryScanFailed, &[&"R90009"]),
                };
                match view.window {
                    Some(window) => cx.defer(move |cx| {
                        // The window is gone only when the app closes.
                        let _ = cx.update_window(window, |_, window, cx| window.push_notification(report, cx));
                    }),
                    None => view.notice = Some(report),
                }
                if play_first {
                    view.open_root_and_play(cx);
                } else {
                    view.reload(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn open_root_and_play(&mut self, cx: &mut Context<Self>) {
        let store = self.store.clone();
        let load = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let root = store.root_buckets()?.into_iter().next();
                let items = match &root {
                    Some(bucket) => store.items_in_bucket(bucket.id)?,
                    None => Vec::new(),
                };
                Ok::<_, ym_store::StoreError>((root, items))
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            if let Some(Ok(Ok((Some(root), items)))) = load.await {
                this.update(cx, |view, cx| {
                    view.path = vec![root];
                    view.items = items;
                    view.play(0, cx);
                    view.refresh(cx);
                })
                .ok();
            }
        })
        .detach();
    }

    fn play(&mut self, index: usize, cx: &mut Context<Self>) {
        let items = self.shown_items().iter().map(QueueItem::from_local).collect();
        cx.emit(PlayLocal { items, index });
    }

    /// Opens the artist's or album's songs in its tab ("Go to artist / album").
    pub fn reveal_group(&mut self, kind: GroupKind, name: String, cx: &mut Context<Self>) {
        self.tab = if kind == GroupKind::Artist { Tab::Artists } else { Tab::Albums };
        self.open_playlist = None;
        self.group = Some(if name.trim().is_empty() { self.tab.unknown_group().to_owned() } else { name });
        self.scroll.set_offset(point(px(0.), px(0.)));
        self.refresh_all(cx);
        cx.notify();
    }

    /// Opens the folder holding a file ("Show in folder").
    pub fn reveal_folder(&mut self, bucket_id: i64, cx: &mut Context<Self>) {
        let request = library_playlist::store_call(cx, move |store| store.bucket_chain(bucket_id));
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| match result {
                Ok(chain) => {
                    view.tab = Tab::Folders;
                    view.group = None;
                    view.open_playlist = None;
                    view.path = chain;
                    view.scroll.set_offset(point(px(0.), px(0.)));
                    view.refresh(cx);
                    cx.notify();
                }
                Err(error) => cx.global::<AppGraph>().log.warn(&format!("R90008 library read failed: {error}"), None),
            })
            .ok();
        })
        .detach();
    }

    /// Debug routes: actions run in order, each after the reload the previous one caused.
    pub fn queue_dev_actions(&mut self, actions: &str, cx: &mut Context<Self>) {
        self.dev_actions = actions.split(';').map(str::to_owned).collect();
        self.reload(cx);
    }

    fn run_dev_action(&mut self, cx: &mut Context<Self>) {
        if !self.dev_actions.is_empty() {
            self.dev_ready = true;
            cx.notify();
        }
    }

    fn dev_action(&mut self, action: &str, window: &mut Window, cx: &mut Context<Self>) {
        let (name, argument) = action.split_once(':').unwrap_or((action, ""));
        let index: usize = argument.parse().unwrap_or(0);
        let log = cx.global::<AppGraph>().log.fork("dev-library");
        log.info(&format!("dev action {action}"));
        match name {
            "tab" => self.select_tab_index(index, cx),
            "playlist" => {
                self.tab = Tab::Playlists;
                self.open_playlist = match index {
                    0 => Some(OpenPlaylist::Recent),
                    1 => Some(OpenPlaylist::Top),
                    _ => self
                        .playlist_cards()
                        .get(index - 2)
                        .map(|(playlist, _, _)| OpenPlaylist::User(playlist.clone())),
                };
                self.refresh_all(cx);
            }
            "create" => self.create_playlist(argument.to_owned(), window, cx),
            "addto" => {
                if let Some(item) = self.loaded.items.get(index) {
                    let changed = self.changed(cx);
                    AddToLocalPlaylist::open(vec![item.playlist_code()], Some(changed), window, cx);
                }
            }
            "rename" | "delete" => {
                if let Some(OpenPlaylist::User(playlist)) = self.open_playlist.clone() {
                    if name == "rename" {
                        self.rename_playlist(playlist, window, cx);
                    } else {
                        self.delete_playlist(playlist, window, cx);
                    }
                }
            }
            "arrange" => self.arrange(window, cx),
            "backup" => self.backup_playlists(Some(PathBuf::from(argument)), window, cx),
            "restore" => self.restore_playlists(Some(PathBuf::from(argument)), window, cx),
            "transfermenu" => window.focus(&self.tool_focus.handle(TOOL_PLAYLIST_MENU, cx), cx),
            "rescan" => self.rescan(cx),
            "sort" => {
                if let Some(context) = self.sort_context() {
                    self.pick_sort(context, index, window, cx);
                }
            }
            "song" => {
                let (verb, title) = argument.split_once(':').unwrap_or((argument, ""));
                let Some(item) = self.loaded.items.iter().find(|item| item.title == title).cloned() else {
                    log.warn(&format!("no song titled {title}"), None);
                    return;
                };
                let action = match verb {
                    "artist" => SongAction::GoTo(GroupKind::Artist, item.artist.clone().unwrap_or_default()),
                    "album" => SongAction::GoTo(GroupKind::Album, item.album.clone().unwrap_or_default()),
                    "folder" => SongAction::ShowInFolder(item.bucket_id.unwrap_or_default()),
                    "unlist" => SongAction::RemoveFromPlaylist(item),
                    "rename" => {
                        let changed = self.changed(cx);
                        RenameFileDialog::open(PathBuf::from(&item.uri), Some(changed), window, cx);
                        return;
                    }
                    "deletefile" => {
                        let changed = self.changed(cx);
                        library_playlist::confirm_delete_from_disk(item, Some(changed), window, cx);
                        return;
                    }
                    _ => return,
                };
                self.song_action(action, window, cx);
            }
            "folder" => {
                let (verb, position) = argument.split_once(':').unwrap_or((argument, "0"));
                let Some(bucket) = self.sorted_folders().get(position.parse().unwrap_or(0)).cloned() else { return };
                match verb {
                    "open" => self.open_folder(bucket, cx),
                    "remove" => self.folder_change(bucket, FolderChange::Remove, window, cx),
                    "exclude" => self.folder_change(bucket, FolderChange::Exclude, window, cx),
                    "include" => self.folder_change(bucket, FolderChange::Include, window, cx),
                    "download" => self.toggle_download_folder(bucket, window, cx),
                    _ => {}
                }
            }
            _ => log.warn(&format!("unknown dev action {action}"), None),
        }
        cx.notify();
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum FolderChange {
    Remove,
    Exclude,
    Include,
}

impl SongMenuHost for LibraryView {
    fn song_action(&mut self, action: SongAction, window: &mut Window, cx: &mut Context<Self>) {
        match action {
            SongAction::GoTo(kind, name) => self.reveal_group(kind, name, cx),
            SongAction::ShowInFolder(bucket_id) => self.reveal_folder(bucket_id, cx),
            SongAction::RemoveFromPlaylist(item) => {
                let Some(OpenPlaylist::User(playlist)) = &self.open_playlist else { return };
                let (id, code) = (playlist.id, item.playlist_code());
                let request = library_playlist::store_call(cx, move |store| store.remove_from_playlist(id, &code));
                cx.spawn_in(window, async move |this, cx| {
                    let result = request.await;
                    this.update_in(cx, |view, window, cx| match result {
                        Ok(()) => view.reload(cx),
                        Err(error) => {
                            library_playlist::report("R94001", "removing from a playlist failed", &error, window, cx)
                        }
                    })
                    .ok();
                })
                .detach();
            }
            SongAction::RemoveFromHistory(item) => {
                let (history, code) = (self.history.clone(), item.playlist_code());
                let request =
                    on_core(cx, async move { tokio::task::spawn_blocking(move || history.delete_local(&code)).await });
                cx.spawn_in(window, async move |this, cx| {
                    let result = request.await;
                    this.update_in(cx, |view, window, cx| {
                        if let Some(Ok(Err(error))) = result {
                            library_playlist::report(
                                "R94005",
                                "removing a play-history row failed",
                                &error.to_string(),
                                window,
                                cx,
                            );
                        }
                        view.reload(cx);
                    })
                    .ok();
                })
                .detach();
            }
            SongAction::Changed => self.reload(cx),
        }
    }
}

pub fn reveal_label() -> &'static str {
    t(if cfg!(target_os = "macos") {
        Str::MenuRevealFinder
    } else if cfg!(target_os = "windows") {
        Str::MenuRevealExplorer
    } else {
        Str::MenuRevealFileManager
    })
}

/// A mouse wheel's turn scrolls the horizontal `row` while it can move (the JVM app's
/// `horizontalWheelScroll`); at either end the page under it scrolls instead.
fn wheel_scrolls_row(row: ScrollHandle) -> impl Fn(&ScrollEvent, &mut Window, &mut App) + 'static {
    move |event, window, cx| {
        if event.delta.precise() {
            return;
        }
        let delta = event.delta.pixel_delta(window.line_height());
        if !delta.x.is_zero() || delta.y.is_zero() {
            return;
        }
        let offset = row.offset();
        let x = (offset.x + delta.y).clamp(-row.max_offset().x, px(0.));
        if x != offset.x {
            row.set_offset(point(x, offset.y));
            window.refresh();
            cx.stop_propagation();
        }
    }
}

/// The artwork filling its frame with `corner_radius` corners, the icon while it loads or when there
/// is none.
pub fn art_or_icon(
    icon: Lucide,
    art: Option<SharedString>,
    icon_size: Pixels,
    corner_radius: f32,
    cx: &App,
) -> AnyElement {
    let color = cx.theme().muted_foreground;
    let placeholder = move || Icon::new(icon).size(icon_size).text_color(color).into_any_element();
    match art {
        Some(url) => img(url)
            .size_full()
            .rounded(px(corner_radius))
            .object_fit(ObjectFit::Cover)
            .with_loading(placeholder)
            .with_fallback(placeholder)
            .into_any_element(),
        None => placeholder(),
    }
}

/// Song cells per row at a content `width`; 1 below the grid width.
pub fn song_columns(width: f32) -> usize {
    if width < GRID_MIN_WIDTH { 1 } else { ((width - 2. * GRID_PADDING) / SONG_MIN).floor().max(1.) as usize }
}

/// Splits `cells` into rows of `columns` equal cells, padding a short last row.
pub fn grid_rows(cells: Vec<AnyElement>, columns: usize) -> Vec<AnyElement> {
    let mut rows = Vec::new();
    let mut cells = cells.into_iter().peekable();
    while cells.peek().is_some() {
        let mut row = h_flex().w_full().items_start();
        for _ in 0..columns {
            row = row.child(match cells.next() {
                Some(cell) => div().flex_1().min_w_0().child(cell),
                None => div().flex_1(),
            });
        }
        rows.push(row.into_any_element());
    }
    rows
}

/// A local song cell (thumbnail, title, artist · album, a More menu) that plays through `play`.
pub fn song_cell<V: SongMenuHost>(
    focus: &FocusList,
    index: usize,
    item: &MediaItem,
    play: fn(&mut V, usize, &mut Context<V>),
    menu: SongMenu,
    cx: &mut Context<V>,
) -> AnyElement {
    let theme = cx.theme().clone();
    let detail = [item.artist.clone(), item.album.clone()].into_iter().flatten().collect::<Vec<_>>().join(" · ");
    let label = [Some(item.title.clone()), item.artist.clone(), item.duration_ms.map(clock)]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(", ");
    let view = cx.entity().downgrade();
    let song = item.clone();
    let more = action_icon(("song-more", index), IconName::EllipsisVertical, t(Str::CdMore));
    let more = focus.trailing_button(index, 0, more, cx).dropdown_menu_with_anchor(
        Anchor::TopRight,
        move |menu_list, _, _| {
            let local = QueueItem::from_local(&song);
            let path = PathBuf::from(&song.uri);
            let act = {
                let view = view.clone();
                move |action: SongAction| {
                    let view = view.clone();
                    let action = std::cell::Cell::new(Some(action));
                    move |_: &ClickEvent, window: &mut Window, cx: &mut App| {
                        if let Some(action) = action.take() {
                            view.update(cx, |view, cx| view.song_action(action, window, cx)).ok();
                        }
                    }
                }
            };
            let (play_view, next, last) = (view.clone(), view.clone(), view.clone());
            let (next_item, last_item) = (local.clone(), local);
            let changed: Changed = {
                let view = view.clone();
                Rc::new(move |window, cx| {
                    view.update(cx, |view, cx| view.song_action(SongAction::Changed, window, cx)).ok();
                })
            };
            let (playlist_changed, delete_changed, rename_changed) = (changed.clone(), changed.clone(), changed);
            let renamed = path.clone();
            let (code, doomed) = (song.playlist_code(), song.clone());
            let mut menu_list = menu_list
                .item(PopupMenuItem::new(t(Str::MenuPlay)).on_click(move |_, _, cx| {
                    play_view.update(cx, |view, cx| play(view, index, cx)).ok();
                }))
                .item(PopupMenuItem::new(t(Str::QueueNext)).on_click(move |_, _, cx| {
                    next.update(cx, |_, cx| cx.emit(QueueLocal { item: next_item.clone(), next: true })).ok();
                }))
                .item(PopupMenuItem::new(t(Str::QueueLast)).on_click(move |_, _, cx| {
                    last.update(cx, |_, cx| cx.emit(QueueLocal { item: last_item.clone(), next: false })).ok();
                }))
                .item(PopupMenuItem::new(t(Str::CdAddToPlaylist)).on_click(move |_, window, cx| {
                    AddToLocalPlaylist::open(vec![code.clone()], Some(playlist_changed.clone()), window, cx);
                }));
            if menu.remove_from_playlist {
                menu_list = menu_list.item(
                    PopupMenuItem::new(t(Str::MenuRemoveFromPlaylist))
                        .on_click(act(SongAction::RemoveFromPlaylist(song.clone()))),
                );
            }
            if menu.remove_from_history {
                menu_list = menu_list.item(
                    PopupMenuItem::new(t(Str::MenuRemove)).on_click(act(SongAction::RemoveFromHistory(song.clone()))),
                );
            }
            menu_list = menu_list
                .item(
                    PopupMenuItem::new(t(Str::MenuGoToArtist))
                        .on_click(act(SongAction::GoTo(GroupKind::Artist, song.artist.clone().unwrap_or_default()))),
                )
                .item(
                    PopupMenuItem::new(t(Str::MenuGoToAlbum))
                        .on_click(act(SongAction::GoTo(GroupKind::Album, song.album.clone().unwrap_or_default()))),
                );
            if menu.show_in_folder
                && let Some(bucket_id) = song.bucket_id
            {
                menu_list = menu_list.item(
                    PopupMenuItem::new(t(Str::MenuShowInFolder)).on_click(act(SongAction::ShowInFolder(bucket_id))),
                );
            }
            menu_list
                .separator()
                .item(PopupMenuItem::new(reveal_label()).on_click(move |_, _, cx| cx.reveal_path(&path)))
                .item(PopupMenuItem::new(t(Str::MenuRename)).on_click(move |_, window, cx| {
                    RenameFileDialog::open(renamed.clone(), Some(rename_changed.clone()), window, cx);
                }))
                .item(PopupMenuItem::new(t(Str::MenuRemoveFromDisk)).on_click(move |_, window, cx| {
                    library_playlist::confirm_delete_from_disk(
                        doomed.clone(),
                        Some(delete_changed.clone()),
                        window,
                        cx,
                    );
                }))
        },
    );
    focus
        .item(index, list_item(h_flex().id(("local-item", index)), index).aria_label(label), cx)
        .pl_4()
        .pr_1()
        .py_2()
        .gap_4()
        .items_start()
        .cursor_pointer()
        .rounded(px(10.))
        .hover(|style| style.bg(theme.primary.opacity(crate::settings_view::HOVER_ALPHA)))
        .child(LibraryView::thumb(
            if item.video_codec.is_some() { Lucide::Film } else { Lucide::Music },
            crate::local_art::art_url(item, crate::local_art::ROW_PX),
            item.duration_ms,
            cx,
        ))
        .child(
            v_flex().flex_1().min_w_0().child(div().text_sm().line_clamp(2).child(item.title.clone())).when(
                !detail.is_empty(),
                |column| {
                    column.child(div().pt_1().text_xs().text_color(theme.muted_foreground).truncate().child(detail))
                },
            ),
        )
        .child(div().pt(px(2.)).child(more))
        .on_click(cx.listener(move |view, _, _, cx| play(view, index, cx)))
        .into_any_element()
}

fn more_button(id: impl Into<ElementId>) -> Button {
    action_icon(id, IconName::EllipsisVertical, t(Str::CdMore))
}

impl LibraryView {
    /// A 56 thumbnail: the art, else the file kind's icon, with the duration badged at the bottom-end.
    pub fn thumb(icon: Lucide, art: Option<SharedString>, duration_ms: Option<i64>, cx: &App) -> Div {
        let theme = cx.theme();
        div()
            .relative()
            .size(px(THUMB))
            .flex_shrink_0()
            .rounded(px(10.))
            .overflow_hidden()
            .bg(theme.secondary)
            .flex()
            .items_center()
            .justify_center()
            .child(art_or_icon(icon, art, px(20.), 10., cx))
            .when_some(duration_ms, |thumb, duration| {
                thumb.child(
                    div()
                        .absolute()
                        .bottom(px(4.))
                        .right(px(4.))
                        .px(px(4.))
                        .py(px(1.))
                        .rounded(px(3.))
                        .bg(black().opacity(0.7))
                        .text_color(white())
                        .text_size(px(10.))
                        .child(clock(duration)),
                )
            })
    }

    fn song_menu(&self) -> SongMenu {
        let open = self.open_playlist.as_ref();
        SongMenu {
            remove_from_playlist: matches!(open, Some(OpenPlaylist::User(_))),
            remove_from_history: matches!(open, Some(OpenPlaylist::Top)),
            show_in_folder: self.tab != Tab::Folders,
        }
    }

    /// A grid card: square art, the title (2 lines) and the subtitle when there is one; `grid` false
    /// draws a list row. `kind` is spoken after the title, not drawn. `marked` adds the
    /// download-location badge: on the art in a grid, in place of the subtitle in a list.
    #[allow(clippy::too_many_arguments)]
    fn card(
        id: ElementId,
        position: usize,
        icon: Lucide,
        art: Option<SharedString>,
        title: String,
        subtitle: Option<String>,
        kind: Option<&str>,
        grid: bool,
        dimmed: bool,
        marked: bool,
        cx: &App,
    ) -> Stateful<Div> {
        let theme = cx.theme();
        let label = [Some(title.as_str()), subtitle.as_deref(), kind, marked.then(|| t(Str::MenuDownloadLocation))]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(", ");
        let subtitle =
            subtitle.map(|subtitle| div().text_xs().text_color(theme.muted_foreground).truncate().child(subtitle));
        let base = list_item(div().id(id), position)
            .aria_label(label)
            .cursor_pointer()
            .rounded(px(10.))
            .hover(|style| style.bg(theme.primary.opacity(crate::settings_view::HOVER_ALPHA)))
            .when(dimmed, |card| card.opacity(0.4));
        if grid {
            base.flex()
                .flex_col()
                .p(px(6.))
                .child(
                    div()
                        .relative()
                        .w_full()
                        .aspect_ratio(1.)
                        .rounded(px(10.))
                        .overflow_hidden()
                        .bg(theme.secondary)
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(art_or_icon(icon, art, px(40.), 10., cx))
                        .when(marked, |tile| {
                            tile.child(
                                div()
                                    .absolute()
                                    .top(px(6.))
                                    .left(px(6.))
                                    .child(Icon::new(Lucide::Download).size(px(18.)).text_color(theme.primary)),
                            )
                        }),
                )
                .child(div().pt(px(7.)).text_sm().line_clamp(2).child(title))
                .when_some(subtitle, |card, subtitle| card.child(div().pt(px(3.)).child(subtitle)))
        } else {
            let second_line = if marked {
                Some(
                    h_flex()
                        .gap_1()
                        .items_center()
                        .min_w_0()
                        .child(Icon::new(Lucide::Download).size(px(14.)).text_color(theme.primary))
                        .child(
                            div()
                                .text_xs()
                                .text_color(theme.muted_foreground)
                                .truncate()
                                .child(t(Str::MenuDownloadLocation)),
                        ),
                )
            } else {
                subtitle
            };
            base.flex().px_4().py_2().gap_4().items_start().child(Self::thumb(icon, art, None, cx)).child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(div().text_sm().line_clamp(2).child(title))
                    .when_some(second_line, |text, line| text.child(div().pt_1().child(line))),
            )
        }
    }

    /// The sort chip (current option, a menu of options) and the direction toggle; on a list's
    /// manual order the toggle is the Arrange songs button.
    fn sort_control(&self, context: SortContext, cx: &mut Context<Self>) -> AnyElement {
        let state = self.sort_state(context.id);
        let index = state.index.min(context.labels.len() - 1);
        let current = t(context.labels[index]);
        let manual = context.manual == Some(index);
        let view = cx.entity().downgrade();
        let chip = Button::new("sort")
            .outline()
            .small()
            .rounded_full()
            .icon(Lucide::ArrowUpDown)
            .label(current)
            .accessibility_label(format!("{}, {current}", t(Str::CdSort)))
            .track_focus(&self.tool_focus.handle(TOOL_SORT, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                for (option, label) in context.labels.iter().enumerate() {
                    let view = view.clone();
                    menu = menu.item(
                        PopupMenuItem::new(t(*label)).checked(context.manual.is_none() && option == index).on_click(
                            move |_, window, cx| {
                                view.update(cx, |view, cx| view.pick_sort(context, option, window, cx)).ok();
                            },
                        ),
                    );
                }
                menu
            });
        let toggle = if manual {
            action_icon("sort-direction", Lucide::SquarePen, t(Str::ArrangeSongs))
                .on_click(cx.listener(|view, _, window, cx| view.arrange(window, cx)))
        } else {
            let direction = if state.descending { Str::CdSortDescending } else { Str::CdSortAscending };
            action_icon(
                "sort-direction",
                if state.descending { IconName::ArrowDown } else { IconName::ArrowUp },
                t(direction),
            )
            .on_click(cx.listener(move |view, _, _, cx| {
                let state = view.sort_state(context.id);
                view.set_sort(context.id, SortState { index: state.index, descending: !state.descending }, cx);
            }))
        };
        h_flex()
            .gap_1()
            .items_center()
            .child(self.tool_focus.wrap(TOOL_SORT, chip, cx))
            .child(self.tool_focus.button(TOOL_SORT_DIRECTION, toggle, cx))
            .into_any_element()
    }

    fn rescan_button(&self, cx: &mut Context<Self>) -> AnyElement {
        self.tool_focus
            .button(
                TOOL_RESCAN,
                action_icon("rescan", Lucide::RefreshCw, t(Str::LibraryRescan))
                    .disabled(self.scanning)
                    .on_click(cx.listener(|view, _, _, cx| view.rescan(cx))),
                cx,
            )
            .into_any_element()
    }

    fn crumb_link(id: impl Into<ElementId>, label: String, cx: &App) -> Stateful<Div> {
        let theme = cx.theme();
        div()
            .id(id)
            .role(Role::Link)
            .aria_label(label.clone())
            .px_1()
            .rounded(px(6.))
            .cursor_pointer()
            .hover(|style| style.bg(theme.primary.opacity(crate::settings_view::HOVER_ALPHA)))
            .text_sm()
            .truncate()
            .child(label)
    }

    /// `Tab › name` with the shuffle chip, over an opened group or playlist.
    fn drill_header(&self, bar: Div, title: String, back: fn(&mut Self), cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let has_songs = !self.shown_items().is_empty();
        let link = Self::crumb_link("drill-back", self.tab.label().to_owned(), cx).on_click(cx.listener(
            move |view, _, _, cx| {
                back(view);
                cx.notify();
            },
        ));
        bar.child(self.crumb_focus.item(0, link, cx))
            .child(Icon::new(IconName::ChevronRight).size(px(16.)).text_color(theme.muted_foreground))
            .child(div().text_sm().font_semibold().truncate().child(title))
            .when(has_songs, |bar| bar.child(self.shuffle_chip(cx)))
    }

    /// The 40-tall bar over the tab's content.
    fn toolbar(&self, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let bar = h_flex().h(px(40.)).pl_4().pr_2().gap_2().items_center();
        let bar = match (self.tab, &self.group, &self.open_playlist) {
            (Tab::Folders, _, _) if self.path.is_empty() => bar
                .child(
                    self.tool_focus.button(
                        TOOL_ADD_FOLDER,
                        Button::new("add-folder")
                            .outline()
                            .small()
                            .rounded_full()
                            .label(if self.scanning { t(Str::LibraryScanning) } else { t(Str::LibraryAddFolder) })
                            .on_click(cx.listener(|view, _, _, cx| view.scan(cx))),
                        cx,
                    ),
                )
                .child(self.rescan_button(cx)),
            (Tab::Folders, _, _) => {
                let mut crumbs = h_flex()
                    .id("crumbs")
                    .track_scroll(self.crumb_drag.scroll())
                    .overflow_x_scroll()
                    .restrict_scroll_to_axis()
                    .on_scroll(wheel_scrolls_row(self.crumb_drag.scroll().clone()))
                    .gap_1()
                    .items_center();
                let row = Some(self.crumb_drag.scroll().clone());
                crumbs = crumbs.child(
                    self.crumb_focus
                        .item_in(
                            0,
                            Self::crumb_link("crumb-root", t(Str::LibraryCrumbRoot).to_owned(), cx)
                                .on_click(cx.listener(|view, _, _, cx| view.open_crumb(0, cx))),
                            row.clone(),
                            cx,
                        )
                        .flex_shrink_0(),
                );
                let last = self.path.len() - 1;
                for (depth, bucket) in self.path.iter().enumerate() {
                    let name = bucket.display_name().to_owned();
                    crumbs = crumbs
                        .child(Icon::new(IconName::ChevronRight).size(px(16.)).text_color(theme.muted_foreground));
                    crumbs = crumbs.child(if depth == last {
                        div().flex_shrink_0().px_1().text_sm().font_semibold().child(name).into_any_element()
                    } else {
                        let link = Self::crumb_link(("crumb", depth), name, cx)
                            .on_click(cx.listener(move |view, _, _, cx| view.open_crumb(depth + 1, cx)));
                        self.crumb_focus.item_in(depth + 1, link, row.clone(), cx).flex_shrink_0().into_any_element()
                    });
                }
                bar.child(self.crumb_drag.wrap(crumbs).min_w_0()).child(self.rescan_button(cx))
            }
            (Tab::Playlists, _, None) => bar.child(
                self.tool_focus.button(
                    TOOL_NEW_PLAYLIST,
                    Button::new("new-playlist")
                        .outline()
                        .small()
                        .rounded_full()
                        .label(t(Str::PlaylistNew))
                        .on_click(cx.listener(|view, _, window, cx| view.create_playlist(String::new(), window, cx))),
                    cx,
                ),
            ),
            (Tab::Playlists, _, Some(open)) => {
                self.drill_header(bar, open.title(), |view| view.open_playlist = None, cx)
            }
            (_, Some(group), _) => self.drill_header(bar, group.clone(), |view| view.group = None, cx),
            (Tab::Songs, _, _) => bar.child(self.shuffle_chip(cx)),
            _ => bar,
        };
        let sort = self.sort_context().map(|context| self.sort_control(context, cx));
        let transfer_menu =
            (self.tab == Tab::Playlists && self.open_playlist.is_none()).then(|| self.transfer_menu(cx));
        bar.child(div().flex_1()).children(sort).children(transfer_menu).into_any_element()
    }

    /// The Playlists toolbar's ⋮: Back up (when there is a playlist to write) and Restore.
    fn transfer_menu(&self, cx: &mut Context<Self>) -> Div {
        let view = cx.entity().downgrade();
        let can_backup = !self.loaded.playlists.is_empty();
        let more = more_button("playlist-transfer-more")
            .track_focus(&self.tool_focus.handle(TOOL_PLAYLIST_MENU, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |menu, _, _| {
                let (backup_view, restore_view) = (view.clone(), view.clone());
                menu.when(can_backup, |menu| {
                    menu.item(PopupMenuItem::new(t(Str::PlaylistMenuBackup)).on_click(move |_, window, cx| {
                        backup_view.update(cx, |view, cx| view.backup_playlists(None, window, cx)).ok();
                    }))
                })
                .item(PopupMenuItem::new(t(Str::PlaylistMenuRestore)).on_click(
                    move |_, window, cx| {
                        restore_view.update(cx, |view, cx| view.restore_playlists(None, window, cx)).ok();
                    },
                ))
            });
        self.tool_focus.wrap(TOOL_PLAYLIST_MENU, more, cx)
    }

    /// Under the system playlists while the user has none: the way to bring some in.
    fn no_playlists_hint(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let restore = Button::new("playlists-empty-restore")
            .outline()
            .label(t(Str::PlaylistsEmptyRestore))
            .disabled(self.transfer.busy())
            .on_click(cx.listener(|view, _, window, cx| view.restore_playlists(None, window, cx)));
        v_flex()
            .w_full()
            .px_4()
            .py_8()
            .gap_2()
            .items_center()
            .child(
                heading("playlists-empty-title", 2, t(Str::PlaylistsEmptyTitle))
                    .text_size(px(16.))
                    .font_medium()
                    .text_color(theme.foreground),
            )
            .child(
                label("playlists-empty-desc", t(Str::PlaylistsEmptyDesc))
                    .text_size(px(13.))
                    .text_center()
                    .text_color(theme.muted_foreground),
            )
            .child(div().pt_2().child(self.tool_focus.button(TOOL_EMPTY_RESTORE, restore, cx)))
    }

    fn shuffle_chip(&self, cx: &mut Context<Self>) -> Div {
        let chip = Button::new("shuffle-all")
            .outline()
            .small()
            .rounded_full()
            .icon(Lucide::Shuffle)
            .label(t(Str::ShuffleAll))
            .on_click(cx.listener(|view, _, _, cx| view.shuffle_all(cx)));
        self.tool_focus.button(TOOL_SHUFFLE, chip, cx)
    }

    fn folder_cards(&self, grid: bool, window: &Window, cx: &mut Context<Self>) -> (Vec<AnyElement>, ListStops<'_>) {
        let folders = self.sorted_folders();
        let stops = self.card_focus.list_stops(0..folders.len(), window, cx);
        let mut cards = Vec::new();
        for (index, bucket) in folders.into_iter().enumerate() {
            let path = PathBuf::from(&bucket.uri);
            let marked = !bucket.excluded && self.download_folder.as_deref() == Some(path.as_path());
            let view = cx.entity().downgrade();
            let menu_bucket = bucket.clone();
            let more = self.card_focus.trailing_button(index, 0, more_button(("folder-more", index)), cx);
            let more = more.dropdown_menu_with_anchor(Anchor::TopRight, move |menu, _, _| {
                let bucket = menu_bucket.clone();
                let path = path.clone();
                let change = |label: Str, change: FolderChange| {
                    let (view, bucket) = (view.clone(), bucket.clone());
                    PopupMenuItem::new(t(label)).on_click(move |_, window, cx| {
                        view.update(cx, |view, cx| view.folder_change(bucket.clone(), change, window, cx)).ok();
                    })
                };
                let mut menu = menu.item(if bucket.parent_id.is_none() {
                    change(Str::MenuRemoveFolder, FolderChange::Remove)
                } else if bucket.excluded {
                    change(Str::MenuIncludeFolder, FolderChange::Include)
                } else {
                    change(Str::MenuExcludeFolder, FolderChange::Exclude)
                });
                if !bucket.excluded {
                    let (view, bucket) = (view.clone(), bucket.clone());
                    menu = menu.item(PopupMenuItem::new(t(Str::MenuDownloadLocation)).checked(marked).on_click(
                        move |_, window, cx| {
                            view.update(cx, |view, cx| view.toggle_download_folder(bucket.clone(), window, cx)).ok();
                        },
                    ));
                }
                menu.item(PopupMenuItem::new(reveal_label()).on_click(move |_, _, cx| cx.reveal_path(&path)))
            });
            let card = Self::card(
                ("folder", index).into(),
                index,
                Lucide::Folder,
                None,
                bucket.display_name().to_owned(),
                (!grid).then(|| t(Str::LibraryFolder).to_owned()),
                grid.then(|| t(Str::LibraryFolder)),
                grid,
                bucket.excluded,
                marked,
                cx,
            )
            .relative()
            .child(div().absolute().top(px(8.)).right(px(if grid { 8. } else { 4. })).child(more))
            .on_click(cx.listener(move |view, _, _, cx| view.open_folder(bucket.clone(), cx)));
            cards.push(self.card_focus.item(index, card, cx).into_any_element());
        }
        (cards, stops)
    }

    fn playlist_entry_cards(
        &self,
        grid: bool,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> (Vec<AnyElement>, ListStops<'_>) {
        let art_px = if grid { crate::local_art::CARD_PX } else { crate::local_art::ROW_PX };
        let mut cards = Vec::new();
        let system = [
            (OpenPlaylist::Recent, Lucide::RotateCcwClock, Str::PlaylistRecentPlayDesc),
            (OpenPlaylist::Top, Lucide::Flame, Str::PlaylistTopTracksDesc),
        ];
        let system_count = system.len();
        let playlists = self.playlist_cards();
        let stops = self.card_focus.list_stops(0..system_count + playlists.len(), window, cx);
        for (index, (open, icon, description)) in system.into_iter().enumerate() {
            let card = Self::card(
                ("system-playlist", index).into(),
                index,
                icon,
                None,
                open.title(),
                Some(t(description).to_owned()),
                None,
                grid,
                false,
                false,
                cx,
            )
            .on_click(cx.listener(move |view, _, _, cx| {
                view.open_playlist = Some(open.clone());
                cx.notify();
            }));
            cards.push(self.card_focus.item(index, card, cx).into_any_element());
        }
        for (position, (playlist, count, art_item)) in playlists.into_iter().enumerate() {
            let index = position + system_count;
            let art = art_item.and_then(|item| crate::local_art::art_url(&item, art_px));
            let subtitle = ym_i18n::plural(ym_i18n::Plural::NSongs, count as i64, &[&count]);
            let view = cx.entity().downgrade();
            let menu_playlist = playlist.clone();
            let more = self.card_focus.trailing_button(index, 0, more_button(("playlist-more", position)), cx);
            let more = more.dropdown_menu_with_anchor(Anchor::TopRight, move |menu, _, _| {
                let (rename_view, delete_view) = (view.clone(), view.clone());
                let (renamed, deleted) = (menu_playlist.clone(), menu_playlist.clone());
                menu.item(PopupMenuItem::new(t(Str::MenuRename)).on_click(move |_, window, cx| {
                    rename_view.update(cx, |view, cx| view.rename_playlist(renamed.clone(), window, cx)).ok();
                }))
                .item(PopupMenuItem::new(t(Str::MenuDeletePlaylist)).on_click(
                    move |_, window, cx| {
                        delete_view.update(cx, |view, cx| view.delete_playlist(deleted.clone(), window, cx)).ok();
                    },
                ))
            });
            let card = Self::card(
                ("user-playlist", position).into(),
                index,
                Lucide::ListMusic,
                art,
                playlist.name.clone(),
                Some(subtitle),
                None,
                grid,
                false,
                false,
                cx,
            )
            .relative()
            .child(div().absolute().top(px(8.)).right(px(if grid { 8. } else { 4. })).child(more))
            .on_click(cx.listener(move |view, _, _, cx| {
                view.open_playlist = Some(OpenPlaylist::User(playlist.clone()));
                cx.notify();
            }));
            cards.push(self.card_focus.item(index, card, cx).into_any_element());
        }
        (cards, stops)
    }

    fn group_cards(&self, grid: bool, window: &Window, cx: &mut Context<Self>) -> (Vec<AnyElement>, ListStops<'_>) {
        let icon = match self.tab {
            Tab::Artists => Lucide::User,
            Tab::Albums => Lucide::Disc,
            _ => Lucide::Tag,
        };
        let art_px = if grid { crate::local_art::CARD_PX } else { crate::local_art::ROW_PX };
        let groups = self.groups();
        let stops = self.card_focus.list_stops(0..groups.len(), window, cx);
        let mut cards = Vec::new();
        for (index, (name, count, art_item)) in groups.into_iter().enumerate() {
            let art = art_item.and_then(|item| crate::local_art::art_url(&item, art_px));
            let subtitle = ym_i18n::plural(ym_i18n::Plural::NSongs, count as i64, &[&count]);
            let open = name.clone();
            let card = Self::card(
                ("group", index).into(),
                index,
                icon,
                art,
                name,
                Some(subtitle),
                None,
                grid,
                false,
                false,
                cx,
            )
            .on_click(cx.listener(move |view, _, _, cx| {
                view.group = Some(open.clone());
                cx.notify();
            }));
            cards.push(self.card_focus.item(index, card, cx).into_any_element());
        }
        (cards, stops)
    }
}

impl Render for LibraryView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        self.window = Some(window.window_handle());
        if let Some(notice) = self.notice.take() {
            window.push_notification(notice, cx);
        }
        if let Some(offer) = self.location_offer.take() {
            window.defer(cx, move |window, cx| crate::downloads::offer_download_location(offer, window, cx));
        }
        if self.dev_ready && !self.dev_actions.is_empty() {
            self.dev_ready = false;
            let action = self.dev_actions.remove(0);
            let view = cx.entity();
            window.defer(cx, move |window, cx| view.update(cx, |view, cx| view.dev_action(&action, window, cx)));
        }
        let open_folder = self.path.last().map(|bucket| bucket.id);
        if open_folder.is_some() && self.crumbs_shown_for != open_folder {
            // The current crumb is the row's last child: each folder adds a chevron and its crumb.
            self.crumb_drag.scroll().scroll_to_item(2 * self.path.len());
        }
        self.crumbs_shown_for = open_folder;
        let theme = cx.theme().clone();
        let width = crate::feed::feed_width(window);
        let grid = width >= GRID_MIN_WIDTH;
        let card_columns = ((width - 2. * GRID_PADDING) / CARD_MAX).ceil().max(2.) as usize;
        let song_columns = song_columns(width);

        let selected = Tab::ALL.iter().position(|tab| *tab == self.tab).unwrap_or(0);
        let view = cx.entity().downgrade();
        let tabs =
            div().w_full().px_3().bg(crate::shell::chrome_tone(cx)).border_b_1().border_color(theme.border).child(
                tab_row(
                    "library-tabs",
                    Tab::ALL.iter().map(|tab| SharedString::from(tab.label())).collect(),
                    selected,
                    &self.tab_focus,
                    &self.tab_drag,
                    move |index, _, cx| {
                        view.update(cx, |view, cx| view.select_tab(Tab::ALL[index], cx)).ok();
                    },
                    cx,
                ),
            );

        let Some(folders_added) = self.folders_added else {
            return div().size_full().into_any_element();
        };
        if !folders_added && !self.scanning {
            return v_flex()
                .size_full()
                .child(
                    v_flex()
                        .flex_1()
                        .items_center()
                        .justify_center()
                        .gap(px(14.))
                        .child(announced("library-empty", t(Str::LibraryEmpty)).text_color(theme.muted_foreground))
                        .child(
                            self.tool_focus.button(
                                TOOL_SCAN_EMPTY,
                                Button::new("scan-empty")
                                    .primary()
                                    .rounded_full()
                                    .label(t(Str::LibraryScanFolder))
                                    .on_click(cx.listener(|view, _, _, cx| view.scan(cx))),
                                cx,
                            ),
                        ),
                )
                .into_any_element();
        }

        let (cards, card_stops) = match self.tab {
            Tab::Folders => self.folder_cards(grid, window, cx),
            Tab::Playlists if self.open_playlist.is_none() => self.playlist_entry_cards(grid, window, cx),
            Tab::Artists | Tab::Albums | Tab::Genres if self.group.is_none() => self.group_cards(grid, window, cx),
            _ => (Vec::new(), self.card_focus.list_stops(0..0, window, cx)),
        };
        let shown = self.shown_items();
        let song_stops = self.song_focus.list_stops(0..shown.len(), window, cx);
        let song_list_label: SharedString = match (&self.open_playlist, &self.group, self.path.last()) {
            (Some(open), _, _) => open.title().into(),
            (None, Some(group), _) => group.clone().into(),
            (None, None, Some(folder)) => folder.display_name().to_owned().into(),
            (None, None, None) => self.tab.label().into(),
        };
        let menu = self.song_menu();
        let songs: Vec<AnyElement> = shown
            .iter()
            .enumerate()
            .map(|(index, item)| song_cell(&self.song_focus, index, item, Self::play, menu, cx))
            .collect();

        let mut body = v_flex().w_full().pt_1().pb_4().px(px(if grid { GRID_PADDING } else { 0. }));
        body = body.child(div().mx(px(if grid { -GRID_PADDING } else { 0. })).child(self.toolbar(cx)));
        let empty = cards.is_empty() && songs.is_empty();
        let (card_count, song_count) = (cards.len(), songs.len());
        let (card_block, song_block) = if grid {
            (v_flex().children(grid_rows(cards, card_columns)), v_flex().children(grid_rows(songs, song_columns)))
        } else {
            (v_flex().children(cards), v_flex().children(songs))
        };
        if card_count > 0 {
            body = body.child(card_stops.wrap(card_block.id("library-cards"), Some(self.tab.label().into()), cx));
        }
        if song_count > 0 {
            body = body.child(song_stops.wrap(song_block.id("library-songs"), Some(song_list_label), cx));
        }
        if self.tab == Tab::Playlists
            && self.open_playlist.is_none()
            && self.loaded.ready
            && self.loaded.playlists.is_empty()
        {
            body = body.child(self.no_playlists_hint(cx));
        }
        if empty {
            let message = t(match self.tab {
                Tab::Folders => Str::LibraryEmptyFolder,
                Tab::Songs | Tab::Playlists => Str::LibraryNoSongs,
                Tab::Artists => Str::LibraryNoArtists,
                Tab::Albums => Str::LibraryNoAlbums,
                Tab::Genres => Str::LibraryNoGenres,
            });
            body = body.child(
                div()
                    .py_16()
                    .flex()
                    .justify_center()
                    .child(announced("library-tab-empty", message).text_color(theme.muted_foreground)),
            );
        }
        v_flex()
            .size_full()
            .child(tabs)
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .relative()
                    .child(
                        div()
                            .id("library-scroll")
                            .size_full()
                            .overflow_y_scroll()
                            .track_scroll(&self.scroll)
                            .child(body),
                    )
                    .children(crate::shell::bar_shadow(cx))
                    .when(self.scanning, |content| {
                        content.child(
                            div()
                                .absolute()
                                .top_0()
                                .left_0()
                                .right_0()
                                .child(LoadingLine::new("library-scanning", t(Str::LibraryScanning))),
                        )
                    }),
            )
            .into_any_element()
    }
}
