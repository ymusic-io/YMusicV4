//! Library search: type-to-filter over the local files, no suggestions and no submit. A "Search
//! online" hand-off row leads, then the matching Songs, Artists and Albums; an artist or album
//! opens its songs in place.

use std::sync::Arc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, IconName, StyledExt, h_flex, v_flex};
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_store::{MediaItem, MediaStore};

use crate::a11y::{FocusList, heading, list_item};
use crate::action_icon::action_icon;
use crate::graph::{AppGraph, on_core};
use crate::library::{
    GroupKind, LibraryView, PlayLocal, QueueLocal, SongAction, SongMenu, SongMenuHost, grid_rows, song_cell,
    song_columns,
};
use crate::playback::QueueItem;
use crate::settings_view::HOVER_ALPHA;

pub enum LibrarySearchEvent {
    Back,
    SearchOnline(String),
    /// "Go to artist / album" on a match: the Library opens that group.
    GoTo(GroupKind, String),
    /// "Show in folder" on a match: the Library opens that folder.
    ShowInFolder(i64),
}

pub struct LibrarySearchView {
    store: Arc<MediaStore>,
    input: Entity<InputState>,
    all: Vec<MediaItem>,
    /// The open artist or album: its name and songs.
    drill: Option<(String, Vec<MediaItem>)>,
    /// The songs the cells play from: the drill's, else the matches.
    songs: Vec<MediaItem>,
    scroll: ScrollHandle,
    focus: FocusList,
    song_focus: FocusList,
    bar_focus: FocusList,
    _subscription: Subscription,
}

impl EventEmitter<LibrarySearchEvent> for LibrarySearchView {}
impl EventEmitter<PlayLocal> for LibrarySearchView {}
impl EventEmitter<QueueLocal> for LibrarySearchView {}

impl LibrarySearchView {
    pub fn new(store: Arc<MediaStore>, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let input = cx.new(|cx| InputState::new(window, cx).placeholder(t(Str::LibrarySearchFieldHint)));
        input.update(cx, |state, cx| state.focus(window, cx));
        let subscription = cx.subscribe(&input, |view, _, event: &InputEvent, cx| {
            if matches!(event, InputEvent::Change) {
                view.drill = None;
                cx.notify();
            }
        });
        let scroll = ScrollHandle::new();
        let mut view = Self {
            store,
            input,
            all: Vec::new(),
            drill: None,
            songs: Vec::new(),
            scroll: scroll.clone(),
            focus: FocusList::new(Some(scroll.clone())),
            song_focus: FocusList::new(Some(scroll)),
            bar_focus: FocusList::new(None),
            _subscription: subscription,
        };
        view.load(cx);
        crate::library::on_library_change(cx, Self::load);
        view
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        let store = self.store.clone();
        let load = on_core(cx, async move { tokio::task::spawn_blocking(move || store.all_items()).await });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(Ok(items))) => view.all = items,
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>().log.warn("library search could not read the library", Some(&error))
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("library search could not read the library", Some(&error))
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Closes an open artist or album first; true when it did.
    pub fn back(&mut self, cx: &mut Context<Self>) -> bool {
        if self.drill.take().is_some() {
            cx.notify();
            return true;
        }
        false
    }

    fn play(&mut self, index: usize, cx: &mut Context<Self>) {
        let items = self.songs.iter().map(QueueItem::from_local).collect();
        cx.emit(PlayLocal { items, index });
    }

    /// Groups whose name contains the query, by name; an untagged file falls under `unknown`.
    fn group_matches(
        &self,
        query: &str,
        unknown: &str,
        key: fn(&MediaItem) -> Option<&String>,
    ) -> Vec<(String, Vec<MediaItem>)> {
        let mut groups: std::collections::BTreeMap<String, Vec<MediaItem>> = std::collections::BTreeMap::new();
        for item in &self.all {
            let name = key(item).filter(|name| !name.trim().is_empty()).cloned().unwrap_or_else(|| unknown.to_owned());
            groups.entry(name).or_default().push(item.clone());
        }
        let mut matches: Vec<_> = groups.into_iter().filter(|(name, _)| name.to_lowercase().contains(query)).collect();
        matches.sort_by_key(|(name, _)| name.to_lowercase());
        matches
    }

    fn section_header(title: &str, count: usize, cx: &App) -> Div {
        h_flex()
            .gap_2()
            .px_4()
            .pt_4()
            .pb_2()
            .items_baseline()
            .child(heading(SharedString::from(format!("section-{title}")), 2, title.to_owned()).font_semibold())
            .child(div().text_sm().text_color(cx.theme().muted_foreground).child(count.to_string()))
    }

    /// The `position`th row of its section's list, `index` its focus stop.
    fn group_row(
        &self,
        index: usize,
        position: usize,
        icon: Lucide,
        name: &str,
        members: &[MediaItem],
        cx: &mut Context<Self>,
    ) -> Stateful<Div> {
        let theme = cx.theme().clone();
        let count = ym_i18n::plural(ym_i18n::Plural::NSongs, members.len() as i64, &[&members.len()]);
        let art = members.iter().find_map(|item| crate::local_art::art_url(item, crate::local_art::ROW_PX));
        let drill = (name.to_owned(), members.to_vec());
        self.focus
            .item(
                index,
                list_item(h_flex().id(("library-group", index)), position).aria_label(format!("{name}, {count}")),
                cx,
            )
            .px_4()
            .py_2()
            .gap_4()
            .items_start()
            .cursor_pointer()
            .rounded(px(10.))
            .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
            .child(LibraryView::thumb(icon, art, None, cx))
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(div().text_sm().line_clamp(2).child(name.to_owned()))
                    .child(div().pt_1().text_xs().text_color(theme.muted_foreground).child(count)),
            )
            .on_click(cx.listener(move |view, _, _, cx| {
                view.drill = Some(drill.clone());
                cx.notify();
            }))
    }

    fn song_block(&self, title: &str, columns: usize, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let menu = SongMenu { show_in_folder: true, ..SongMenu::default() };
        let stops = self.song_focus.list_stops(0..self.songs.len(), window, cx);
        let cells: Vec<AnyElement> = self
            .songs
            .iter()
            .enumerate()
            .map(|(index, item)| song_cell(&self.song_focus, index, item, Self::play, menu, cx))
            .collect();
        let rows = if columns > 1 { grid_rows(cells, columns) } else { cells };
        let list = v_flex().id("library-search-songs").children(rows);
        stops.wrap(list, Some(title.to_owned().into()), cx).into_any_element()
    }
}

impl SongMenuHost for LibrarySearchView {
    fn song_action(&mut self, action: SongAction, _: &mut Window, cx: &mut Context<Self>) {
        match action {
            SongAction::GoTo(kind, name) => cx.emit(LibrarySearchEvent::GoTo(kind, name)),
            SongAction::ShowInFolder(bucket_id) => cx.emit(LibrarySearchEvent::ShowInFolder(bucket_id)),
            SongAction::Changed => {
                self.drill = None;
                self.load(cx);
            }
            SongAction::RemoveFromPlaylist(_) | SongAction::RemoveFromHistory(_) => {}
        }
    }
}

impl Render for LibrarySearchView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        let theme = cx.theme().clone();
        let bar = h_flex()
            .gap_2()
            .px_2()
            .pt(px(2.))
            .pb(px(3.))
            .border_b_1()
            .border_color(theme.border)
            .child(self.bar_focus.button(
                0,
                action_icon("library-search-back", IconName::ArrowLeft, t(Str::CdBack)).on_click(cx.listener(
                    |view, _, _, cx| {
                        if !view.back(cx) {
                            cx.emit(LibrarySearchEvent::Back);
                        }
                    },
                )),
                cx,
            ))
            .child(
                self.bar_focus
                    .field(1, &self.input.focus_handle(cx), Input::new(&self.input).cleanable(true), cx)
                    .flex_1(),
            );
        let centered = |text: String| {
            div().flex_1().flex().items_center().justify_center().p_6().text_color(theme.muted_foreground).child(text)
        };
        let columns = song_columns(crate::feed::feed_width(window));
        let query = self.input.read(cx).value().trim().to_owned();

        let body = if let Some((name, members)) = self.drill.clone() {
            self.songs = members;
            let header = Self::section_header(&name, self.songs.len(), cx);
            v_flex().child(header).child(self.song_block(&name, columns, window, cx)).into_any_element()
        } else if query.is_empty() {
            self.songs.clear();
            return v_flex().size_full().child(bar).child(centered(t(Str::LibrarySearchHint).to_owned()));
        } else {
            let needle = query.to_lowercase();
            self.songs = self.all.iter().filter(|item| item.title.to_lowercase().contains(&needle)).cloned().collect();
            let artists = self.group_matches(&needle, t(Str::UnknownArtist), |item| item.artist.as_ref());
            let albums = self.group_matches(&needle, t(Str::UnknownAlbum), |item| item.album.as_ref());
            let online = query.clone();
            let online_row = self
                .focus
                .item(
                    0,
                    div().id("search-online").role(Role::Button).aria_label(tf(Str::LibrarySearchOnline, &[&query])),
                    cx,
                )
                .px_4()
                .py_3()
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .text_sm()
                .font_semibold()
                .text_color(theme.primary)
                .child(tf(Str::LibrarySearchOnline, &[&query]))
                .on_click(cx.listener(move |_, _, _, cx| cx.emit(LibrarySearchEvent::SearchOnline(online.clone()))));
            let mut column = v_flex().child(online_row);
            let mut focus_count = 1;
            if !self.songs.is_empty() {
                column = column
                    .child(Self::section_header(t(Str::LibraryTabSongs), self.songs.len(), cx))
                    .child(self.song_block(t(Str::LibraryTabSongs), columns, window, cx));
            }
            for (section, (title, icon, groups)) in
                [(t(Str::LibraryTabArtists), Lucide::User, &artists), (t(Str::LibraryTabAlbums), Lucide::Disc, &albums)]
                    .into_iter()
                    .enumerate()
            {
                if groups.is_empty() {
                    continue;
                }
                let stops = self.focus.list_stops(focus_count..focus_count + groups.len(), window, cx);
                let mut list = v_flex().id(("library-search-groups", section));
                for (position, (name, members)) in groups.iter().enumerate() {
                    list = list.child(self.group_row(focus_count, position, icon, name, members, cx));
                    focus_count += 1;
                }
                column = column.child(Self::section_header(title, groups.len(), cx)).child(stops.wrap(
                    list,
                    Some(title.into()),
                    cx,
                ));
            }
            if self.songs.is_empty() && artists.is_empty() && albums.is_empty() {
                column =
                    column.child(div().p_4().text_color(theme.muted_foreground).child(t(Str::LibrarySearchNoMatches)));
            }
            column.into_any_element()
        };
        v_flex().size_full().child(bar).child(
            div()
                .id("library-search-results")
                .flex_1()
                .overflow_y_scroll()
                .track_scroll(&self.scroll)
                .pb_4()
                .child(body),
        )
    }
}
