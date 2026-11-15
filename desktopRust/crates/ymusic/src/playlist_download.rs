//! Download a whole YouTube playlist: every loaded video listed with a box (a newly loaded page
//! arrives checked), one set of picks for all of them (the Quick download preset's fields), the
//! folder, an index prefix for the file names and an optional Library playlist the songs join.

use std::collections::HashSet;
use std::path::PathBuf;
use std::rc::Rc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::actions::Confirm;
use gpui_kit::component::button::Button;
use gpui_kit::component::checkbox::Checkbox;
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::switch::Switch;
use gpui_kit::component::{ActiveTheme, Disableable, Icon, IconName, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::yt::{YtFeedItem, YtFeedPage, YtLocaleOption};
use ym_i18n::{Plural, Str, plural, t, tf};
use ym_store::Playlist;

use crate::a11y::FocusList;
use crate::action_icon::action_icon;
use crate::download_dialog::DownloadTarget;
use crate::downloads::{download_folder, offline_ids, pick_download_folder, usable_download_folder};
use crate::elevated_dialog::{
    ConfirmDialog, DEFAULT_WIDTH, ROW_HORIZONTAL, bar_spinner, button_bar, chrome, press, scroll_body, text_button,
    title_bar,
};
use crate::feed::{FeedSource, error_text, thumb_for};
use crate::graph::{AppGraph, on_core};
use crate::quick_download::{
    PRESET_FIELD_STOPS, QuickPreset, dropdown, load_languages, option_line, preset_fields, saved_preset,
};
use crate::settings_view::HOVER_ALPHA;
use crate::spinner::Spinner;

type CheckAction = fn(&mut PlaylistDownload);
type OnCreated = Rc<dyn Fn(Playlist, &mut App)>;

const STOP_LOAD_MORE: usize = 0;
const STOP_MENU: usize = 1;
const STOP_PRESET: usize = 2;
const STOP_FOLDER: usize = STOP_PRESET + PRESET_FIELD_STOPS;
const STOP_PREFIX: usize = STOP_FOLDER + 1;
const STOP_TARGET: usize = STOP_PREFIX + 1;
const STOP_DOWNLOAD: usize = STOP_TARGET + 1;
const STOP_CANCEL: usize = STOP_DOWNLOAD + 1;
const STOP_ROWS: usize = STOP_CANCEL + 1;
/// The form's width: room for the options beside the thumbnails.
const DIALOG_WIDTH: f32 = 760.;

pub struct PlaylistDownload {
    source: FeedSource,
    title: String,
    videos: Vec<YtFeedItem>,
    continuation: Option<String>,
    loading_more: bool,
    /// Every video id listed so far: a newly loaded page arrives checked, a seen one keeps its box.
    seen: HashSet<String>,
    checked: HashSet<String>,
    index_prefix: bool,
    preset: QuickPreset,
    languages: Vec<YtLocaleOption>,
    offline: HashSet<String>,
    playlists: Vec<Playlist>,
    target: Option<Playlist>,
    folder: Option<PathBuf>,
    scroll: ScrollHandle,
    focus: FocusList,
}

impl PlaylistDownload {
    /// Opens the page for `title`, starting from the videos `page` already holds.
    pub fn open(source: FeedSource, title: String, page: &YtFeedPage, window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| Self::new(source, title, page, cx));
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DIALOG_WIDTH, window).on_ok(|_, _, _| false).child(view.clone())
        });
    }

    fn new(source: FeedSource, title: String, page: &YtFeedPage, cx: &mut Context<Self>) -> Self {
        let scroll = ScrollHandle::new();
        let graph = cx.global::<AppGraph>();
        let folder = download_folder(graph.settings.as_ref());
        let mut view = Self {
            source,
            title,
            videos: Vec::new(),
            continuation: page.continuation.clone(),
            loading_more: false,
            seen: HashSet::new(),
            checked: HashSet::new(),
            index_prefix: false,
            preset: saved_preset(cx),
            languages: Vec::new(),
            offline: HashSet::new(),
            playlists: Vec::new(),
            target: None,
            folder,
            scroll: scroll.clone(),
            focus: FocusList::modal(Some(scroll), cx),
        };
        view.add_videos(page);
        let languages = load_languages(cx);
        let offline = offline_ids(cx);
        let media = cx.global::<AppGraph>().media.clone();
        let playlists = on_core(cx, async move { tokio::task::spawn_blocking(move || media.playlists()).await });
        cx.spawn(async move |this, cx| {
            let (languages, offline, playlists) = futures::join!(languages, offline, playlists);
            this.update(cx, |view, cx| {
                view.languages = languages;
                view.offline = offline;
                match playlists {
                    Some(Ok(Ok(playlists))) => view.playlists = playlists,
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>().log.warn("Library playlists could not be read", Some(&error))
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("Library playlists could not be read", Some(&error))
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        view
    }

    fn add_videos(&mut self, page: &YtFeedPage) {
        for video in page.sections.iter().flat_map(|section| section.items.iter()).filter(|item| item.kind == "video") {
            if self.seen.insert(video.id.clone()) {
                if !video.live {
                    self.checked.insert(video.id.clone());
                }
                self.videos.push(video.clone());
            }
        }
    }

    fn load_more(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(continuation) = self.continuation.clone() else { return };
        if self.loading_more {
            return;
        }
        self.loading_more = true;
        let graph = cx.global::<AppGraph>();
        let (engine, history, log) = (graph.engine(), graph.history.clone(), graph.log.fork("playlist-download"));
        let load = on_core(cx, self.source.clone().load(engine, history, Some(continuation), log));
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = load.await;
            this.update_in(cx, |view, window, cx| {
                view.loading_more = false;
                match result {
                    Some(Ok(page)) => {
                        view.continuation = page.continuation.clone();
                        view.add_videos(&page);
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

    fn toggle(&mut self, id: &str, cx: &mut Context<Self>) {
        if !self.checked.remove(id) {
            self.checked.insert(id.to_owned());
        }
        cx.notify();
    }

    fn toggle_prefix(&mut self, cx: &mut Context<Self>) {
        self.index_prefix = !self.index_prefix;
        cx.notify();
    }

    fn pickable(&self) -> impl Iterator<Item = &YtFeedItem> {
        self.videos.iter().filter(|video| !video.live)
    }

    fn picked(&self) -> Vec<YtFeedItem> {
        self.pickable().filter(|video| self.checked.contains(&video.id)).cloned().collect()
    }

    fn choose_folder(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let pick = pick_download_folder(window, cx);
        cx.spawn(async move |this, cx| {
            let Some(folder) = pick.await else { return };
            this.update(cx, |view, cx| {
                view.folder = Some(folder);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn download(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let picked = self.picked();
        if picked.is_empty() {
            return;
        }
        let downloaded = picked.iter().filter(|video| self.offline.contains(&video.id)).count();
        if downloaded == 0 {
            self.start(picked, window, cx);
            return;
        }
        let view = cx.entity().downgrade();
        let message = plural(Plural::PlaylistRedownloadMessage, downloaded as i64, &[&downloaded]);
        let offline = self.offline.clone();
        let (all_view, all_picked) = (view.clone(), picked.clone());
        ConfirmDialog::new(t(Str::RedownloadTitle), message, t(Str::PlaylistSkipDownloaded))
            .alternative(t(Str::PlaylistDownloadAll), move |window, cx| {
                let picked = all_picked.clone();
                all_view.update(cx, |view, cx| view.start(picked, window, cx)).ok();
            })
            .open(window, cx, move |window, cx| {
                let fresh: Vec<YtFeedItem> =
                    picked.iter().filter(|video| !offline.contains(&video.id)).cloned().collect();
                if !fresh.is_empty() {
                    view.update(cx, |view, cx| view.start(fresh, window, cx)).ok();
                }
            });
    }

    /// Queues `picked` with the preset (numbered by playlist position when asked), adds them to
    /// the target Library playlist, then closes. Without a usable folder the picker comes first.
    fn start(&mut self, picked: Vec<YtFeedItem>, window: &mut Window, cx: &mut Context<Self>) {
        let folder = usable_download_folder(window, cx);
        cx.spawn_in(window, async move |this, cx| {
            if folder.await.is_none() {
                return;
            }
            this.update_in(cx, |view, window, cx| {
                let positions: std::collections::HashMap<&str, usize> =
                    view.videos.iter().enumerate().map(|(index, video)| (video.id.as_str(), index + 1)).collect();
                let graph = cx.global::<AppGraph>();
                for video in &picked {
                    let prefix = match positions.get(video.id.as_str()).filter(|_| view.index_prefix) {
                        Some(position) => format!("{position}. "),
                        None => String::new(),
                    };
                    graph.downloads.enqueue(view.preset.request(&DownloadTarget::from_feed(video), &prefix));
                }
                if let Some(target) = &view.target {
                    let (media, log, id) = (graph.media.clone(), graph.log.fork("playlist-download"), target.id);
                    let codes: Vec<String> = picked.iter().map(|video| video.id.clone()).collect();
                    graph.tokio.spawn_blocking(move || {
                        if let Err(error) = media.add_codes_to_playlist(id, &codes) {
                            log.warn("R92000 downloads not added to the Library playlist", Some(&error));
                        }
                    });
                }
                window.close_dialog(cx);
                window.push_notification(plural(Plural::PlaylistAdded, picked.len() as i64, &[&picked.len()]), cx);
            })
            .ok();
        })
        .detach();
    }

    fn create_target(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let view = cx.entity().downgrade();
        let names = self.playlists.iter().map(|playlist| playlist.name.clone()).collect();
        NewPlaylistName::open(self.title.clone(), names, window, cx, move |playlist, cx| {
            view.update(cx, |view, cx| {
                view.playlists.push(playlist.clone());
                view.target = Some(playlist);
                cx.notify();
            })
            .ok();
        });
    }

    fn header(&self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let load_more: AnyElement = if self.loading_more {
            Spinner::new().small().into_any_element()
        } else if self.continuation.is_some() {
            self.focus
                .button(
                    STOP_LOAD_MORE,
                    Button::new("load-more")
                        .small()
                        .rounded_full()
                        .outline()
                        .label(t(Str::PlaylistLoadMore))
                        .on_click(cx.listener(|view, _, window, cx| view.load_more(window, cx))),
                    cx,
                )
                .into_any_element()
        } else {
            div().into_any_element()
        };
        let _ = window;
        let view = cx.entity().downgrade();
        let menu = action_icon("check-menu", IconName::EllipsisVertical, t(Str::CdMore))
            .track_focus(&self.focus.handle(STOP_MENU, cx))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                let entries: [(Str, CheckAction); 4] = [
                    (Str::PlaylistCheckAll, |view| {
                        view.checked = view.pickable().map(|video| video.id.clone()).collect();
                    }),
                    (Str::PlaylistCheckNotDownloaded, |view| {
                        let fresh: Vec<String> = view
                            .pickable()
                            .filter(|video| !view.offline.contains(&video.id))
                            .map(|video| video.id.clone())
                            .collect();
                        view.checked.extend(fresh);
                    }),
                    (Str::PlaylistUncheckAll, |view| view.checked.clear()),
                    (Str::PlaylistUncheckDownloaded, |view| {
                        let offline = view.offline.clone();
                        view.checked.retain(|id| !offline.contains(id));
                    }),
                ];
                for (label, apply) in entries {
                    let view = view.clone();
                    menu = menu.item(PopupMenuItem::new(t(label)).on_click(move |_, _, cx| {
                        view.update(cx, |view, cx| {
                            apply(view);
                            cx.notify();
                        })
                        .ok();
                    }));
                }
                menu
            });
        v_flex().gap_1().child(div().text_lg().font_semibold().line_clamp(2).child(self.title.clone())).child(
            h_flex()
                .gap_2()
                .items_center()
                .child(
                    div().px_3().py_1().rounded_full().border_1().border_color(theme.border).text_xs().child(plural(
                        Plural::PlaylistVideosLoaded,
                        self.videos.len() as i64,
                        &[&self.videos.len()],
                    )),
                )
                .child(load_more)
                .child(div().flex_1())
                .child(self.focus.wrap(STOP_MENU, menu, cx)),
        )
    }

    fn options(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let view = cx.entity().downgrade();
        let on_change = Rc::new(move |preset: QuickPreset, _: &mut Window, cx: &mut App| {
            view.update(cx, |view, cx| {
                view.preset = preset;
                cx.notify();
            })
            .ok();
        });
        let ffmpeg = cx.global::<AppGraph>().downloads.ffmpeg_available();
        let fields = preset_fields(&self.preset, &self.languages, ffmpeg, on_change, &self.focus, STOP_PRESET, cx);
        let folder_label = self
            .folder
            .as_ref()
            .map(|folder| folder.display().to_string())
            .unwrap_or_else(|| t(Str::DownloadLocationNotSet).to_owned());
        let folder = Button::new("save-to")
            .small()
            .outline()
            .w_full()
            .icon(Icon::new(Lucide::Folder))
            .label(folder_label.clone())
            .accessibility_label(format!("{}, {folder_label}", t(Str::DownloadSaveTo)))
            .on_click(cx.listener(|view, _, window, cx| view.choose_folder(window, cx)));
        let target_label =
            self.target.as_ref().map_or(t(Str::PlaylistSelectLibrary).to_owned(), |playlist| playlist.name.clone());
        let view = cx.entity().downgrade();
        let (playlists, target_id) = (self.playlists.clone(), self.target.as_ref().map(|playlist| playlist.id));
        let target = dropdown("target-playlist", t(Str::PlaylistAddTo), target_label, &self.focus, STOP_TARGET, cx)
            .dropdown_menu(move |mut menu, _, _| {
                menu = menu.scrollable(true).max_h(px(320.));
                let none_view = view.clone();
                menu = menu.item(PopupMenuItem::new(t(Str::PlaylistTargetNone)).checked(target_id.is_none()).on_click(
                    move |_, _, cx| {
                        none_view
                            .update(cx, |view, cx| {
                                view.target = None;
                                cx.notify();
                            })
                            .ok();
                    },
                ));
                let create_view = view.clone();
                menu = menu.item(PopupMenuItem::new(t(Str::PlaylistTargetCreate)).on_click(move |_, window, cx| {
                    create_view.update(cx, |view, cx| view.create_target(window, cx)).ok();
                }));
                for playlist in &playlists {
                    let (pick_view, picked) = (view.clone(), playlist.clone());
                    menu = menu.item(
                        PopupMenuItem::new(playlist.name.clone()).checked(target_id == Some(playlist.id)).on_click(
                            move |_, _, cx| {
                                let picked = picked.clone();
                                pick_view
                                    .update(cx, |view, cx| {
                                        view.target = Some(picked);
                                        cx.notify();
                                    })
                                    .ok();
                            },
                        ),
                    );
                }
                menu
            });
        v_flex()
            .gap_2()
            .p_4()
            .rounded(px(12.))
            .border_1()
            .border_color(theme.border)
            .child(
                div()
                    .text_xs()
                    .font_semibold()
                    .text_color(theme.muted_foreground)
                    .child(t(Str::PlaylistOptions).to_uppercase()),
            )
            .child(fields)
            .child(option_line(t(Str::DownloadSaveTo), self.focus.button(STOP_FOLDER, folder, cx), cx))
            .child(
                self.focus.item(
                    STOP_PREFIX,
                    h_flex()
                        .id("index-prefix-row")
                        .role(Role::Switch)
                        .aria_label(t(Str::PlaylistIndexPrefix))
                        .aria_toggled(if self.index_prefix { Toggled::True } else { Toggled::False })
                        .gap_4()
                        .py_1()
                        .rounded(px(6.))
                        .items_center()
                        .cursor_pointer()
                        .on_click(cx.listener(|view, _, _, cx| view.toggle_prefix(cx)))
                        .on_action(cx.listener(|view, _: &Confirm, _, cx| view.toggle_prefix(cx)))
                        .child(
                            div()
                                .w(px(112.))
                                .flex_shrink_0()
                                .text_sm()
                                .font_semibold()
                                .child(t(Str::PlaylistIndexPrefix)),
                        )
                        .child(
                            div()
                                .flex_1()
                                .text_xs()
                                .text_color(theme.muted_foreground)
                                .child(t(Str::PlaylistIndexPrefixHint)),
                        )
                        .child(
                            Switch::new("index-prefix")
                                .checked(self.index_prefix)
                                .tab_stop(false)
                                .accessibility_label(t(Str::PlaylistIndexPrefix)),
                        ),
                    cx,
                ),
            )
            .child(option_line(t(Str::PlaylistAddTo), self.focus.wrap(STOP_TARGET, target, cx), cx))
    }

    fn row(&self, index: usize, video: &YtFeedItem, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let checked = self.checked.contains(&video.id) && !video.live;
        let downloaded = self.offline.contains(&video.id);
        let title = if self.index_prefix { format!("{}. {}", index + 1, video.title) } else { video.title.clone() };
        let label = [
            Some(title.clone()),
            video.live.then(|| t(Str::A11yKindLive).to_owned()),
            video.duration.clone(),
            video.subtitle_line1.clone(),
            downloaded.then(|| t(Str::A11yDownloaded).to_owned()),
        ]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(", ");
        let (id, enter_id) = (video.id.clone(), video.id.clone());
        let live = video.live;
        let element = h_flex()
            .id(("playlist-video", index))
            .role(Role::CheckBox)
            .aria_label(label)
            .aria_toggled(if checked { Toggled::True } else { Toggled::False })
            .gap_3()
            .px_2()
            .py_1()
            .rounded(px(8.))
            .items_center()
            .when(!live, |row| row.cursor_pointer().hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA))))
            .on_click(cx.listener(move |view, _, _, cx| {
                if !live {
                    view.toggle(&id, cx);
                }
            }))
            .on_action(cx.listener(move |view, _: &Confirm, _, cx| {
                if !live {
                    view.toggle(&enter_id, cx);
                }
            }))
            .child(Checkbox::new(("video-check", index)).checked(checked).tab_stop(false).disabled(live))
            .child(
                div()
                    .relative()
                    .flex_shrink_0()
                    .w(px(96.))
                    .h(px(54.))
                    .rounded(px(6.))
                    .overflow_hidden()
                    .bg(theme.secondary)
                    .when_some(thumb_for(&video.thumbs, 192), |frame, url| {
                        frame.child(img(url).size_full().rounded(px(6.)).object_fit(ObjectFit::Cover))
                    })
                    .when_some(video.duration.clone(), |frame, duration| {
                        frame.child(
                            div()
                                .absolute()
                                .bottom(px(2.))
                                .right(px(2.))
                                .px(px(4.))
                                .rounded(px(3.))
                                .bg(black().opacity(0.75))
                                .text_xs()
                                .text_color(white())
                                .child(duration),
                        )
                    })
                    .when(downloaded, |frame| {
                        frame.child(
                            div()
                                .absolute()
                                .top_0()
                                .left_0()
                                .p(px(2.))
                                .rounded_br(px(6.))
                                .bg(theme.primary)
                                .child(Icon::new(Lucide::Check).size(px(12.)).text_color(theme.primary_foreground)),
                        )
                    }),
            )
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(
                        div()
                            .text_sm()
                            .line_clamp(2)
                            .text_color(if live { theme.muted_foreground } else { theme.foreground })
                            .child(title),
                    )
                    .when_some(video.subtitle_line1.clone(), |column, line| {
                        column.child(div().text_xs().text_color(theme.muted_foreground).truncate().child(line))
                    }),
            );
        self.focus.item(STOP_ROWS + index, element, cx)
    }
}

impl Render for PlaylistDownload {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let count = self.picked().len();
        let mut rows = v_flex().gap_1();
        for (index, video) in self.videos.clone().iter().enumerate() {
            rows = rows.child(self.row(index, video, cx));
        }
        let rows = self.focus.container(rows, STOP_ROWS..STOP_ROWS + self.videos.len(), cx);
        let body = v_flex()
            .w_full()
            .px(px(ROW_HORIZONTAL))
            .py_4()
            .gap_3()
            .child(self.header(window, cx))
            .child(self.options(cx))
            .child(rows);
        let buttons = h_flex()
            .child(press(
                &self.focus,
                STOP_CANCEL,
                text_button("playlist-download-cancel", t(Str::DialogCancel), false, true, cx),
                true,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(press(
                &self.focus,
                STOP_DOWNLOAD,
                text_button(
                    "playlist-download",
                    plural(Plural::PlaylistDownloadAction, count as i64, &[&count]),
                    true,
                    count > 0,
                    cx,
                ),
                count > 0,
                cx.listener(|view, _, window, cx| view.download(window, cx)),
                cx,
            ));
        v_flex()
            .w_full()
            .child(title_bar(t(Str::PlaylistDownloadTitle), cx))
            .child(scroll_body("playlist-download-scroll", &self.scroll, body, window, cx))
            .child(button_bar(buttons, cx))
    }
}

/// Names a new Library playlist (the playlist's title to start), refusing a name already taken.
pub struct NewPlaylistName {
    name: Entity<InputState>,
    taken: Vec<String>,
    on_created: OnCreated,
    busy: bool,
    focus: FocusList,
    _subscription: Subscription,
}

impl NewPlaylistName {
    pub fn open(
        initial: String,
        taken: Vec<String>,
        window: &mut Window,
        cx: &mut App,
        on_created: impl Fn(Playlist, &mut App) + 'static,
    ) {
        let view = cx.new(|cx| {
            let name = cx.new(|cx| {
                InputState::new(window, cx).placeholder(t(Str::PlaylistNameHint)).default_value(initial.clone())
            });
            name.update(cx, |input, cx| input.focus(window, cx));
            let subscription = cx.subscribe_in(&name, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { .. }) {
                    view.submit(window, cx);
                }
                cx.notify();
            });
            Self {
                name,
                taken,
                on_created: Rc::new(on_created),
                busy: false,
                focus: FocusList::modal(None, cx),
                _subscription: subscription,
            }
        });
        window.open_dialog(cx, move |dialog, window, _| {
            let submit = view.downgrade();
            chrome(dialog, DEFAULT_WIDTH, window).child(view.clone()).on_ok(move |_, window, cx| {
                submit.update(cx, |view, cx| view.submit(window, cx)).ok();
                false
            })
        });
    }

    fn trimmed(&self, cx: &App) -> String {
        self.name.read(cx).value().trim().to_owned()
    }

    fn duplicate(&self, cx: &App) -> bool {
        let name = self.trimmed(cx);
        !name.is_empty() && self.taken.iter().any(|taken| taken.to_lowercase() == name.to_lowercase())
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let name = self.trimmed(cx);
        if self.busy || name.is_empty() || self.duplicate(cx) {
            return;
        }
        self.busy = true;
        let media = cx.global::<AppGraph>().media.clone();
        let create =
            on_core(cx, async move { tokio::task::spawn_blocking(move || media.create_playlist(&name)).await });
        cx.spawn_in(window, async move |this, cx| {
            let result = create.await;
            this.update_in(cx, |view, window, cx| {
                view.busy = false;
                match result {
                    Some(Ok(Ok(playlist))) => {
                        window.close_dialog(cx);
                        (view.on_created)(playlist, cx);
                    }
                    Some(Ok(Err(error))) => {
                        cx.global::<AppGraph>().log.warn("R92001 Library playlist not created", Some(&error));
                        window.push_notification(tf(Str::ErrorGeneric, &[&"R92001"]), cx);
                    }
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("R92001 Library playlist not created", Some(&error));
                        window.push_notification(tf(Str::ErrorGeneric, &[&"R92001"]), cx);
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for NewPlaylistName {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let duplicate = self.duplicate(cx);
        let can_submit = !self.busy && !self.trimmed(cx).is_empty() && !duplicate;
        let field = Input::new(&self.name)
            .aria_label(t(Str::PlaylistNameHint))
            .when(duplicate, |input| input.border_color(theme.danger));
        let create = if self.busy {
            bar_spinner()
        } else {
            press(
                &self.focus,
                2,
                text_button("new-playlist-create", t(Str::PlaylistCreateConfirm), true, can_submit, cx),
                can_submit,
                cx.listener(|view, _, window, cx| view.submit(window, cx)),
                cx,
            )
        };
        let buttons = h_flex()
            .child(press(
                &self.focus,
                1,
                text_button("new-playlist-cancel", t(Str::DialogCancel), false, !self.busy, cx),
                !self.busy,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(create);
        v_flex()
            .w_full()
            .child(title_bar(t(Str::PlaylistCreateTitle), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .py_2()
                    .gap_1()
                    .child(self.focus.field(0, &self.name.focus_handle(cx), field, cx))
                    .when(duplicate, |column| {
                        column.child(
                            div()
                                .id("new-playlist-taken")
                                .px_4()
                                .text_size(px(12.))
                                .text_color(theme.danger)
                                .role(Role::Alert)
                                .aria_label(t(Str::PlaylistNameExists))
                                .child(t(Str::PlaylistNameExists)),
                        )
                    }),
            )
            .child(button_bar(buttons, cx))
    }
}
