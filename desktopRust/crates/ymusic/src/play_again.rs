//! Play again: the queues played lately, newest first, each resumable where it was left.

use std::sync::Arc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::Button;
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Icon, IconName, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_history::{PlayAgainSession, PlayHistory};
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, alert, announced, list_item};
use crate::action_icon::action_icon;
use crate::graph::{AppGraph, on_core};
use crate::local_art::{self, from_jvm_art};
use crate::mini_player::clock;
use crate::play_history::decode_items;
use crate::playback::{QueueItem, Source};

/// Resume this saved queue.
pub struct ResumeQueue {
    pub queue_id: String,
    pub items: Vec<QueueItem>,
    pub index: usize,
    pub start_ms: i64,
}

pub struct PlayAgainView {
    history: Arc<PlayHistory>,
    sessions: Vec<PlayAgainSession>,
    error: Option<String>,
    scroll: ScrollHandle,
    focus: FocusList,
    /// The error's or the empty state's message, then Retry.
    message_focus: FocusList,
}

impl EventEmitter<ResumeQueue> for PlayAgainView {}

impl PlayAgainView {
    pub fn new(history: Arc<PlayHistory>, cx: &mut Context<Self>) -> Self {
        let scroll = ScrollHandle::new();
        let mut view = Self {
            history,
            sessions: Vec::new(),
            error: None,
            scroll: scroll.clone(),
            focus: FocusList::new(Some(scroll)),
            message_focus: FocusList::new(None),
        };
        view.refresh(cx);
        view
    }

    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        let load = self.load_sessions(cx);
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(Ok(sessions))) => {
                        view.sessions = sessions;
                        view.error = None;
                    }
                    Some(Ok(Err(error))) => view.failed(&error.to_string(), cx),
                    Some(Err(error)) => view.failed(&error.to_string(), cx),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn failed(&mut self, message: &str, cx: &mut Context<Self>) {
        cx.global::<AppGraph>().log.warn(&format!("R90017 play again could not be read: {message}"), None);
        self.error = Some(tf(Str::ErrorGeneric, &[&"R90017"]));
    }

    /// Debug: resumes the newest session once the list has loaded.
    pub fn resume_newest(&mut self, cx: &mut Context<Self>) {
        let load = self.load_sessions(cx);
        cx.spawn(async move |this, cx| {
            if let Some(Ok(Ok(sessions))) = load.await
                && let Some(newest) = sessions.into_iter().next()
            {
                this.update(cx, |view, cx| view.resume(newest, cx)).ok();
            }
        })
        .detach();
    }

    /// The sessions with the JVM app's `art://` thumbnails resolved.
    fn load_sessions(
        &self,
        cx: &mut Context<Self>,
    ) -> impl std::future::Future<
        Output = Option<Result<rusqlite::Result<Vec<PlayAgainSession>>, tokio::task::JoinError>>,
    > + use<> {
        let (history, media) = (self.history.clone(), cx.global::<AppGraph>().media.clone());
        on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                let mut sessions = history.sessions()?;
                for session in &mut sessions {
                    session.thumbnail_url =
                        session.thumbnail_url.take().and_then(|url| from_jvm_art(&url, &media, local_art::PLAYER_PX));
                    for thumb in &mut session.strip_thumbs {
                        *thumb = thumb.take().and_then(|url| from_jvm_art(&url, &media, local_art::ROW_PX));
                    }
                }
                Ok(sessions)
            })
            .await
        })
    }

    fn resume(&mut self, session: PlayAgainSession, cx: &mut Context<Self>) {
        let (history, media) = (self.history.clone(), cx.global::<AppGraph>().media.clone());
        let queue_id = session.queue_id.clone();
        let load = on_core(cx, async move {
            tokio::task::spawn_blocking(move || {
                history.load_session(&queue_id).map(|loaded| {
                    loaded.map(|(saved, json)| (saved, decode_items(&json).map(|items| adopt_items(items, &media))))
                })
            })
            .await
        });
        cx.spawn(async move |this, cx| {
            let loaded = load.await;
            this.update(cx, |view, cx| match loaded {
                Some(Ok(Ok(Some((saved, decoded))))) => match decoded {
                    Ok(items) if !items.is_empty() => {
                        let start_ms = if saved.resumes_mid_item() { saved.position_ms } else { 0 };
                        let index = saved.queue_index.min(items.len() - 1);
                        cx.emit(ResumeQueue { queue_id: saved.queue_id, items, index, start_ms });
                    }
                    Ok(_) => view.refresh(cx),
                    Err(message) => view.failed(&message, cx),
                },
                Some(Ok(Ok(None))) => view.refresh(cx),
                Some(Ok(Err(error))) => view.failed(&error.to_string(), cx),
                Some(Err(error)) => view.failed(&error.to_string(), cx),
                None => {}
            })
            .ok();
        })
        .detach();
    }
}

const CARD_MIN: f32 = 340.;
const GAP: f32 = 16.;
const TILE: f32 = 30.;
const TILE_GAP: f32 = 4.;
/// Room the Resume pill takes beside the strip.
const RESUME_PILL: f32 = 112.;
const MORE_LABEL: f32 = 32.;

impl PlayAgainView {
    fn play_from_beginning(&mut self, session: PlayAgainSession, cx: &mut Context<Self>) {
        self.resume(PlayAgainSession { queue_index: 0, position_ms: 0, ..session }, cx);
    }

    fn remove(&mut self, queue_id: String, cx: &mut Context<Self>) {
        let history = self.history.clone();
        let delete =
            on_core(cx, async move { tokio::task::spawn_blocking(move || history.delete_session(&queue_id)).await });
        cx.spawn(async move |this, cx| {
            let result = delete.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(Err(error))) => view.failed(&error.to_string(), cx),
                    Some(Err(error)) => view.failed(&error.to_string(), cx),
                    _ => {}
                }
                view.refresh(cx);
            })
            .ok();
        })
        .detach();
    }

    /// The queue strip: as many whole 30 tiles as fit `width`, the current one ringed, then "+N".
    fn strip(session: &PlayAgainSession, width: f32, cx: &App) -> Div {
        let theme = cx.theme();
        let fits = ((width - MORE_LABEL + TILE_GAP) / (TILE + TILE_GAP)).floor().max(0.) as usize;
        let shown = session.strip_thumbs.len().min(fits);
        let current = session.queue_index.checked_sub(session.strip_start);
        let rest = session.queue_size.saturating_sub(session.strip_start + shown);
        let mut row = h_flex().flex_1().min_w_0().gap(px(TILE_GAP)).items_center().overflow_hidden();
        for (offset, thumb) in session.strip_thumbs.iter().take(shown).enumerate() {
            row = row.child(
                div()
                    .size(px(TILE))
                    .flex_shrink_0()
                    .rounded(px(6.))
                    .overflow_hidden()
                    .bg(theme.muted)
                    .when(current == Some(offset), |tile| tile.border_1().border_color(theme.primary))
                    .when_some(thumb.clone(), |tile, url| {
                        tile.child(
                            img(SharedString::from(url)).size_full().rounded(px(6.)).object_fit(ObjectFit::Cover),
                        )
                    }),
            );
        }
        row.when(rest > 0, |row| {
            row.child(div().w(px(MORE_LABEL)).text_xs().text_color(theme.muted_foreground).child(format!("+{rest}")))
        })
    }

    fn card(&self, position: usize, session: &PlayAgainSession, card_width: f32, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let resume_label =
            session.resumes_mid_item().then(|| tf(Str::PlayAgainResumeAt, &[&clock(session.position_ms)]));
        let track = tf(Str::PlayAgainTrackOf, &[&(session.queue_index + 1), &session.queue_size]);
        let detail = [session.subtitle.clone(), Some(track), resume_label].into_iter().flatten().collect::<Vec<_>>();
        let detail = detail.join(" · ");
        let view = cx.entity().downgrade();
        let (from_start, removed) = (session.clone(), session.queue_id.clone());
        let more = action_icon(("play-again-more", position), IconName::EllipsisVertical, t(Str::CdMore));
        let more = self.focus.trailing_button(position, 0, more, cx).dropdown_menu_with_anchor(
            Anchor::TopRight,
            move |menu, _, _| {
                let (restart, remove) = (view.clone(), view.clone());
                let (from_start, removed) = (from_start.clone(), removed.clone());
                menu.item(PopupMenuItem::new(t(Str::MenuPlayFromBeginning)).on_click(move |_, _, cx| {
                    restart.update(cx, |view, cx| view.play_from_beginning(from_start.clone(), cx)).ok();
                }))
                .item(PopupMenuItem::new(t(Str::MenuRemove)).on_click(move |_, _, cx| {
                    remove.update(cx, |view, cx| view.remove(removed.clone(), cx)).ok();
                }))
            },
        );
        let target = session.clone();
        let strip_width = card_width - 16. - 4. - 8. - 8. - RESUME_PILL;
        let base = list_item(v_flex().id(("play-again", position)), position)
            .aria_label(format!("{}, {detail}", session.title));
        self.focus
            .item(position, base, cx)
            .rounded(px(6.))
            .bg(theme.secondary)
            .border_1()
            .border_color(theme.border)
            .pl_4()
            .pr_1()
            .pt_2()
            .pb_4()
            .gap_2()
            .child(
                h_flex()
                    .pt_2()
                    .gap_4()
                    .items_center()
                    .child(
                        div()
                            .size(px(56.))
                            .flex_shrink_0()
                            .rounded(px(10.))
                            .overflow_hidden()
                            .bg(theme.muted)
                            .when_some(session.thumbnail_url.clone(), |frame, url| {
                                frame.child(
                                    img(SharedString::from(url))
                                        .size_full()
                                        .rounded(px(10.))
                                        .object_fit(ObjectFit::Cover),
                                )
                            }),
                    )
                    .child(
                        v_flex()
                            .flex_1()
                            .min_w_0()
                            .child(div().text_sm().font_medium().truncate().child(session.title.clone()))
                            .child(
                                div().pt_1().text_xs().text_color(theme.muted_foreground).line_clamp(2).child(detail),
                            ),
                    )
                    .child(more),
            )
            .child(
                h_flex().pr_2().gap_2().items_center().child(Self::strip(session, strip_width, cx)).child(
                    Button::new(("resume", position))
                        .outline()
                        .rounded_full()
                        .icon(Lucide::Play)
                        .label(t(Str::PlayAgainResume))
                        .on_click(cx.listener(move |view, _, _, cx| view.resume(target.clone(), cx))),
                ),
            )
            .into_any_element()
    }
}

impl Render for PlayAgainView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::shell::track_scroll(&self.scroll, cx);
        let theme = cx.theme().clone();
        if let Some(error) = self.error.clone() {
            return v_flex()
                .size_full()
                .items_center()
                .justify_center()
                .gap_3()
                .child(alert("play-again-error", error).text_color(theme.danger))
                .child(self.message_focus.button(
                    1,
                    Button::new("reload").label(t(Str::Retry)).on_click(cx.listener(|view, _, _, cx| view.refresh(cx))),
                    cx,
                ))
                .into_any_element();
        }
        if self.sessions.is_empty() {
            return v_flex()
                .size_full()
                .items_center()
                .justify_center()
                .gap_2()
                .p_6()
                .child(Icon::new(Lucide::RotateCcwClock).size(px(56.)).text_color(theme.muted_foreground))
                .child(
                    div().max_w(px(420.)).child(
                        announced("play-again-empty", t(Str::PlayAgainEmpty))
                            .text_center()
                            .text_color(theme.muted_foreground),
                    ),
                )
                .into_any_element();
        }
        let width = crate::feed::feed_width(window);
        let columns = ((width - 2. * GAP + GAP) / (CARD_MIN + GAP)).floor().max(1.) as usize;
        let card_width = (width - 2. * GAP - GAP * (columns - 1) as f32) / columns as f32;
        let sessions = self.sessions.clone();
        let stops = self.focus.list_stops(0..sessions.len(), window, cx);
        let mut grid = v_flex().id("play-again-sessions").w_full().p_4().gap_4();
        for (row_index, row) in sessions.chunks(columns).enumerate() {
            let mut cells = h_flex().w_full().gap_4().items_start();
            for (offset, session) in row.iter().enumerate() {
                cells = cells.child(div().flex_1().min_w_0().child(self.card(
                    row_index * columns + offset,
                    session,
                    card_width,
                    cx,
                )));
            }
            for _ in row.len()..columns {
                cells = cells.child(div().flex_1());
            }
            grid = grid.child(cells);
        }
        let grid = stops.wrap(grid, Some(t(Str::SectionPlayAgain).into()), cx);
        div()
            .id("play-again-scroll")
            .size_full()
            .overflow_y_scroll()
            .track_scroll(&self.scroll)
            .child(grid)
            .into_any_element()
    }
}

/// Blocking. A local item the Library knows plays as its Library item (its history code and art);
/// a JVM app `art://` thumbnail of any other item is resolved.
fn adopt_items(items: Vec<QueueItem>, media: &ym_store::MediaStore) -> Vec<QueueItem> {
    items
        .into_iter()
        .map(|mut item| {
            if let Source::Local(path) = &item.source
                && let Ok(Some((known, false))) = media.find_item_by_uri(&path.to_string_lossy())
            {
                return QueueItem::from_local(&known);
            }
            for thumb in &mut item.thumbs {
                let px = thumb.w.map_or(local_art::PLAYER_PX, |width| width.max(0) as u32);
                if let Some(url) = from_jvm_art(&thumb.url, media, px) {
                    thumb.url = url;
                }
            }
            item
        })
        .collect()
}
