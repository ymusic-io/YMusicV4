//! Video details and comments, the watch page minus the player: title, factoids, channel, rating,
//! share and description, then the comments with their sort, composer, replies, ratings and the
//! viewer's own edit / delete.

use std::collections::HashMap;

use futures::FutureExt;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{InputEvent, Textarea, TextareaState};
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Icon, IconName, Selectable, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::EngineError;
use ym_engine::yt::{YtComment, YtCommentPage, YtThumb, YtVideoDetails};
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, alert, announced};
use crate::action_icon::{HIT, action_icon};
use crate::elevated_dialog::{ConfirmDialog, button_bar, chrome, press, scroll_body, text_button, title_bar};
use crate::feed::{FeedSource, Navigate, error_text, thumb_for};
use crate::graph::{AppGraph, on_core};
use crate::settings_view::HOVER_ALPHA;
use crate::spinner::Spinner;

const LIKE: &str = "LIKE";
const DISLIKE: &str = "DISLIKE";
const INDIFFERENT: &str = "INDIFFERENT";
/// The dialog's width: room for the description and the comment threads.
const DIALOG_WIDTH: f32 = 720.;
/// A comment box grows to this many rows, then scrolls.
const COMMENT_MAX_ROWS: usize = 6;
/// Rows of description shown until it is expanded.
const DESCRIPTION_LINES: usize = 4;
/// Replies and their composer sit under the parent's text, past its avatar.
const REPLY_INDENT: f32 = 64.;
const PILL_ICON: f32 = 18.;
const PILL_HEIGHT: f32 = 34.;

#[derive(Default)]
struct CommentList {
    comments: Vec<YtComment>,
    continuation: Option<String>,
    loading: bool,
    error: Option<EngineError>,
}

/// Which list a page loads into: the top-level comments, or one thread's replies.
#[derive(Clone, PartialEq)]
enum ListKey {
    Top,
    Replies(String),
}

/// What an input box posts.
#[derive(Clone, Copy, PartialEq)]
enum Draft {
    Comment,
    Reply,
    Edit,
}

pub struct VideoDetailsView {
    video_id: String,
    details: Option<YtVideoDetails>,
    details_error: Option<EngineError>,
    /// The first comments page: count, sort chips and the composer.
    header: Option<YtCommentPage>,
    comments: CommentList,
    replies: HashMap<String, CommentList>,
    like_status: String,
    /// The viewer's comment ratings made here, over each comment's served state.
    comment_rating: HashMap<String, String>,
    /// New text of the viewer's comments edited here.
    edited_text: HashMap<String, String>,
    editing: Option<String>,
    replying_to: Option<String>,
    posting: Option<Draft>,
    post_error: Option<(Draft, EngineError)>,
    description_open: bool,
    composer: Entity<TextareaState>,
    reply_input: Entity<TextareaState>,
    edit_input: Entity<TextareaState>,
    /// Every control, a stop the arrows move between without leaving the dialog.
    focus: FocusList,
    /// The next stop's index while rendering: stops are numbered in render order.
    next_stop: std::cell::Cell<usize>,
    focused_first: bool,
    scroll: ScrollHandle,
    _subscriptions: Vec<Subscription>,
}

impl EventEmitter<Navigate> for VideoDetailsView {}

impl VideoDetailsView {
    /// Opens the dialog; the returned view emits [`Navigate`] when a channel is opened from it.
    pub fn open(video_id: String, window: &mut Window, cx: &mut App) -> Entity<Self> {
        let view = cx.new(|cx| Self::new(video_id, window, cx));
        let panel = view.clone();
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DIALOG_WIDTH, window).overlay_closable(false).on_ok(|_, _, _| false).child(panel.clone())
        });
        view
    }

    fn new(video_id: String, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let input = |placeholder: &'static str, window: &mut Window, cx: &mut Context<Self>| {
            cx.new(|cx| TextareaState::new(window, cx).placeholder(placeholder).auto_grow(1, COMMENT_MAX_ROWS))
        };
        let composer = input(t(Str::VideoDetailsCommentHint), window, cx);
        let reply_input = input(t(Str::VideoDetailsCommentHint), window, cx);
        let edit_input = input(t(Str::VideoDetailsCommentHint), window, cx);
        let mut subscriptions = Vec::new();
        for (state, draft) in [(&composer, Draft::Comment), (&reply_input, Draft::Reply), (&edit_input, Draft::Edit)] {
            subscriptions.push(cx.subscribe_in(state, window, move |view, _, event: &InputEvent, window, cx| {
                if matches!(event, InputEvent::PressEnter { secondary: true, .. }) {
                    view.send(draft, window, cx);
                }
            }));
        }
        let scroll = ScrollHandle::new();
        let mut view = Self {
            video_id,
            details: None,
            details_error: None,
            header: None,
            comments: CommentList::default(),
            replies: HashMap::new(),
            like_status: INDIFFERENT.to_owned(),
            comment_rating: HashMap::new(),
            edited_text: HashMap::new(),
            editing: None,
            replying_to: None,
            posting: None,
            post_error: None,
            description_open: false,
            composer,
            reply_input,
            edit_input,
            focus: FocusList::modal(Some(scroll.clone()), cx),
            next_stop: std::cell::Cell::new(0),
            focused_first: false,
            scroll,
            _subscriptions: subscriptions,
        };
        view.load(cx);
        view
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        self.details_error = None;
        let graph = cx.global::<AppGraph>();
        let (engine, log, video_id) = (graph.engine(), graph.log.fork("video-details"), self.video_id.clone());
        let request = on_core(cx, async move { engine.video_details(&video_id, &log).await });
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(details)) => {
                        view.like_status = if details.like_status.is_empty() {
                            INDIFFERENT.to_owned()
                        } else {
                            details.like_status.clone()
                        };
                        if let Some(token) = details.comments_continuation.clone() {
                            view.reload_comments(token, cx);
                        }
                        view.details = Some(details);
                    }
                    Some(Err(error)) => view.details_error = Some(error),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn list_mut(&mut self, key: &ListKey) -> Option<&mut CommentList> {
        match key {
            ListKey::Top => Some(&mut self.comments),
            ListKey::Replies(id) => self.replies.get_mut(id),
        }
    }

    /// Replaces the top-level comments from `token`: the first page or a sort chip.
    fn reload_comments(&mut self, token: String, cx: &mut Context<Self>) {
        self.comments = CommentList { continuation: Some(token), ..CommentList::default() };
        self.load_more(ListKey::Top, false, cx);
    }

    fn load_more(&mut self, key: ListKey, keep_header: bool, cx: &mut Context<Self>) {
        let Some(list) = self.list_mut(&key) else { return };
        let Some(token) = list.continuation.clone() else { return };
        if list.loading {
            return;
        }
        list.loading = true;
        list.error = None;
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("comments"));
        let request = on_core(cx, async move { engine.comments(&token, &log).await });
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = request.await;
            this.update(cx, |view, cx| {
                let header = matches!(result, Some(Ok(_))) && !keep_header && key == ListKey::Top;
                if let Some(Ok(page)) = &result
                    && header
                {
                    view.header = Some(YtCommentPage { comments: Vec::new(), ..page.clone() });
                }
                if let Some(list) = view.list_mut(&key) {
                    list.loading = false;
                    match result {
                        Some(Ok(page)) => {
                            list.comments.extend(page.comments);
                            list.continuation = page.continuation;
                        }
                        Some(Err(error)) => list.error = Some(error),
                        None => {}
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn toggle_replies(&mut self, comment: &YtComment, cx: &mut Context<Self>) {
        if self.replies.remove(&comment.id).is_some() {
            cx.notify();
            return;
        }
        let list = CommentList { continuation: comment.replies_continuation.clone(), ..CommentList::default() };
        self.replies.insert(comment.id.clone(), list);
        self.load_more(ListKey::Replies(comment.id.clone()), true, cx);
    }

    fn signed_out_notice(window: &mut Window, cx: &mut App) {
        window.push_notification(t(Str::VideoDetailsSignInToRate), cx);
    }

    /// Toggles LIKE / DISLIKE at once; a failure restores the previous rating.
    fn toggle_rating(&mut self, status: &str, window: &mut Window, cx: &mut Context<Self>) {
        let target = if self.like_status == status { INDIFFERENT } else { status };
        let Some(token) = self.details.as_ref().and_then(|details| details.rate_tokens.get(target).cloned()) else {
            Self::signed_out_notice(window, cx);
            return;
        };
        let previous = std::mem::replace(&mut self.like_status, target.to_owned());
        let graph = cx.global::<AppGraph>();
        let (engine, log, video_id, target) =
            (graph.engine(), graph.log.fork("rate-video"), self.video_id.clone(), target.to_owned());
        let request = on_core(cx, {
            let target = target.clone();
            async move { engine.rate_video(&video_id, &target, &token, &log).await }
        });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            if let Some(Err(error)) = request.await {
                this.update_in(cx, |view, window, cx| {
                    if view.like_status == target {
                        view.like_status = previous;
                    }
                    window.push_notification(error_text(&error), cx);
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    fn rating_of(&self, comment: &YtComment) -> String {
        self.comment_rating.get(&comment.id).cloned().unwrap_or_else(|| {
            if comment.liked {
                LIKE.to_owned()
            } else if comment.disliked {
                DISLIKE.to_owned()
            } else {
                INDIFFERENT.to_owned()
            }
        })
    }

    fn rate_comment(&mut self, comment: &YtComment, target: &'static str, window: &mut Window, cx: &mut Context<Self>) {
        let current = self.rating_of(comment);
        let undo = current == target;
        let token = match (undo, target) {
            (true, LIKE) => comment.unlike_token.clone(),
            (true, _) => comment.undislike_token.clone(),
            (false, LIKE) => comment.like_token.clone(),
            (false, _) => comment.dislike_token.clone(),
        };
        let Some(token) = token else { return };
        let id = comment.id.clone();
        self.comment_rating.insert(id.clone(), if undo { INDIFFERENT.to_owned() } else { target.to_owned() });
        self.action(token, window, cx, move |view, failed, _| {
            if failed {
                view.comment_rating.insert(id, current);
            }
        });
    }

    /// Runs a comment token action, then `done(view, failed)`; a failure is shown as a notification.
    fn action(
        &mut self,
        token: String,
        window: &mut Window,
        cx: &mut Context<Self>,
        done: impl FnOnce(&mut Self, bool, &mut Window) + 'static,
    ) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("comment-action"));
        let request = on_core(cx, async move { engine.comment_action(&token, &log).await });
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                let failed = match result {
                    Some(Err(error)) => {
                        window.push_notification(error_text(&error), cx);
                        true
                    }
                    _ => false,
                };
                done(view, failed, window);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn confirm_delete(&mut self, comment: YtComment, window: &mut Window, cx: &mut Context<Self>) {
        let Some(token) = comment.delete_token.clone() else { return };
        let view = cx.entity().downgrade();
        ConfirmDialog::new(
            t(Str::VideoDetailsDeleteTitle),
            t(Str::VideoDetailsDeleteMessage),
            t(Str::VideoDetailsDelete),
        )
        .open(window, cx, move |window, cx| {
            let (token, id) = (token.clone(), comment.id.clone());
            view.update(cx, |view, cx| {
                view.action(token, window, cx, move |view, failed, window| {
                    if failed {
                        return;
                    }
                    view.comments.comments.retain(|comment| comment.id != id);
                    for list in view.replies.values_mut() {
                        list.comments.retain(|comment| comment.id != id);
                    }
                    view.replies.remove(&id);
                    let _ = window;
                });
            })
            .ok();
        });
    }

    fn start_edit(&mut self, comment: &YtComment, window: &mut Window, cx: &mut Context<Self>) {
        self.editing = Some(comment.id.clone());
        self.post_error = None;
        let text = self.text_of(comment);
        self.edit_input.update(cx, |input, cx| {
            input.set_value(text, window, cx);
            input.focus(window, cx);
        });
        cx.notify();
    }

    fn open_reply(&mut self, comment: &YtComment, window: &mut Window, cx: &mut Context<Self>) {
        self.replying_to = if self.replying_to.as_ref() == Some(&comment.id) { None } else { Some(comment.id.clone()) };
        self.post_error = None;
        if self.replying_to.is_some() {
            let placeholder =
                comment.reply_placeholder.clone().unwrap_or_else(|| t(Str::VideoDetailsCommentHint).to_owned());
            self.reply_input.update(cx, |input, cx| {
                input.set_value(String::new(), window, cx);
                input.set_placeholder(placeholder, window, cx);
                input.focus(window, cx);
            });
        }
        cx.notify();
    }

    fn text_of(&self, comment: &YtComment) -> String {
        self.edited_text.get(&comment.id).cloned().unwrap_or_else(|| comment.text.clone())
    }

    fn find_comment(&self, id: &str) -> Option<YtComment> {
        self.comments
            .comments
            .iter()
            .chain(self.replies.values().flat_map(|list| list.comments.iter()))
            .find(|comment| comment.id == id)
            .cloned()
    }

    /// Posts the box's trimmed text: a new comment, a reply to the open thread, or an edit.
    fn send(&mut self, draft: Draft, window: &mut Window, cx: &mut Context<Self>) {
        if self.posting.is_some() {
            return;
        }
        let input = match draft {
            Draft::Comment => &self.composer,
            Draft::Reply => &self.reply_input,
            Draft::Edit => &self.edit_input,
        };
        let text = input.read(cx).value().trim().to_owned();
        if text.is_empty() {
            return;
        }
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("comment-post"));
        let posted_text = text.clone();
        enum Posted {
            Comment(YtComment),
            Reply(String, YtComment),
            Edit(String),
        }
        let request = match draft {
            Draft::Comment => {
                let Some(token) = self.header.as_ref().and_then(|header| header.create_token.clone()) else { return };
                on_core(cx, async move { engine.create_comment(&token, &text, &log).await.map(Posted::Comment) })
                    .boxed_local()
            }
            Draft::Reply => {
                let Some(parent) = self.replying_to.as_ref().and_then(|id| self.find_comment(id)) else { return };
                let Some(token) = parent.reply_token.clone() else { return };
                on_core(cx, async move {
                    engine.reply_to_comment(&token, &text, &log).await.map(|reply| Posted::Reply(parent.id, reply))
                })
                .boxed_local()
            }
            Draft::Edit => {
                let Some(comment) = self.editing.as_ref().and_then(|id| self.find_comment(id)) else { return };
                let Some(token) = comment.edit_token.clone() else { return };
                on_core(
                    cx,
                    async move { engine.edit_comment(&token, &text, &log).await.map(|_| Posted::Edit(comment.id)) },
                )
                .boxed_local()
            }
        };
        self.posting = Some(draft);
        self.post_error = None;
        cx.notify();
        cx.spawn_in(window, async move |this, cx| {
            let result = request.await;
            this.update_in(cx, |view, window, cx| {
                view.posting = None;
                match result {
                    Some(Ok(posted)) => {
                        let notice = match posted {
                            Posted::Comment(comment) => {
                                view.comments.comments.insert(0, comment);
                                view.composer.update(cx, |input, cx| input.set_value(String::new(), window, cx));
                                Str::VideoDetailsPosted
                            }
                            Posted::Reply(parent, reply) => {
                                let continuation =
                                    view.find_comment(&parent).and_then(|parent| parent.replies_continuation);
                                view.replies
                                    .entry(parent)
                                    .or_insert_with(|| CommentList { continuation, ..CommentList::default() })
                                    .comments
                                    .insert(0, reply);
                                view.replying_to = None;
                                Str::VideoDetailsReplyPosted
                            }
                            Posted::Edit(id) => {
                                view.edited_text.insert(id, posted_text);
                                view.editing = None;
                                Str::VideoDetailsEdited
                            }
                        };
                        window.push_notification(t(notice), cx);
                    }
                    Some(Err(error)) => view.post_error = Some((draft, error)),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn open_channel(&mut self, url: &str, title: &str, window: &mut Window, cx: &mut Context<Self>) {
        let Some(source) = FeedSource::from_link(url).or_else(|| Some(FeedSource::Channel(url.to_owned()))) else {
            return;
        };
        window.close_dialog(cx);
        cx.emit(Navigate { source, title: Some(SharedString::from(title.to_owned())) });
    }

    fn next_stop(&self) -> usize {
        let index = self.next_stop.get();
        self.next_stop.set(index + 1);
        index
    }

    fn stop(&self, button: Button, cx: &App) -> Div {
        self.focus.button(self.next_stop(), button, cx)
    }

    fn stop_item(&self, element: Stateful<Div>, cx: &App) -> Stateful<Div> {
        self.focus.item(self.next_stop(), element, cx)
    }

    fn avatar(thumbs: &[YtThumb], size: f32, cx: &App) -> Div {
        let frame = div().size(px(size)).flex_shrink_0().rounded_full().overflow_hidden().bg(cx.theme().secondary);
        match thumb_for(thumbs, (size * 2.) as i32) {
            Some(url) => frame.child(img(url).size_full().rounded_full().object_fit(ObjectFit::Cover)),
            None => frame
                .flex()
                .items_center()
                .justify_center()
                .child(Icon::new(Lucide::User).size(px(size / 2.)).text_color(cx.theme().muted_foreground)),
        }
    }

    fn pill(cx: &App) -> Div {
        h_flex().items_center().rounded_full().bg(cx.theme().secondary)
    }

    /// A segment of a [`Self::pill`]: `icon`, and whatever the caller adds after it.
    fn pill_button(id: &'static str, icon: Lucide) -> Button {
        Button::new(id).ghost().rounded_full().h(px(PILL_HEIGHT)).px_4().child(Icon::new(icon).size(px(PILL_ICON)))
    }

    fn render_header(&self, details: &YtVideoDetails, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let mut column = v_flex().gap_4().px_4().py_2();
        if let Some(title) = &details.title {
            column = column.child(div().text_lg().font_bold().child(title.clone()));
        }
        if !details.factoids.is_empty() {
            let mut row = h_flex().w_full().gap_2().items_start();
            for factoid in &details.factoids {
                let weight = factoid.value.chars().count().max(factoid.label.chars().count()).max(4) as f32;
                row = row.child(
                    v_flex()
                        .flex_grow(weight)
                        .flex_basis(px(0.))
                        .min_w_0()
                        .items_center()
                        .child(div().font_bold().text_center().line_clamp(2).child(factoid.value.clone()))
                        .when(!factoid.label.is_empty(), |cell| {
                            cell.child(
                                div()
                                    .text_xs()
                                    .text_center()
                                    .truncate()
                                    .text_color(theme.muted_foreground)
                                    .child(factoid.label.clone()),
                            )
                        }),
                );
            }
            column = column.child(row);
        }
        if let Some(name) = &details.channel_title {
            let (url, title) = (details.channel_url.clone(), name.clone());
            let row = h_flex().id("details-channel").role(Role::Button).aria_label(name.clone());
            column = column.child(
                self.stop_item(row, cx)
                    .gap_4()
                    .items_center()
                    .rounded(px(8.))
                    .when(url.is_some(), |row| {
                        row.cursor_pointer().hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                    })
                    .child(Self::avatar(&details.channel_thumbs, 40., cx))
                    .child(
                        v_flex()
                            .min_w_0()
                            .child(div().text_sm().font_semibold().truncate().child(name.clone()))
                            .when_some(details.subscribers.clone(), |column, subscribers| {
                                column.child(
                                    div().text_xs().text_color(theme.muted_foreground).truncate().child(subscribers),
                                )
                            }),
                    )
                    .on_click(cx.listener(move |view, _, window, cx| {
                        if let Some(url) = &url {
                            view.open_channel(url, &title, window, cx);
                        }
                    })),
            );
        }
        let liked = self.like_status == LIKE;
        let disliked = self.like_status == DISLIKE;
        let like_count = if liked {
            details.like_count_liked.clone().or(details.like_count.clone())
        } else {
            details.like_count.clone()
        };
        let like_label = [Some(t(Str::VideoDetailsLike).to_owned()), like_count.clone()]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(", ");
        let rating = Self::pill(cx)
            .child(
                self.stop(
                    Self::pill_button("details-like", Lucide::ThumbsUp)
                        .when_some(like_count, |button, count| button.child(count))
                        .selected(liked)
                        .accessibility_label(like_label)
                        .on_click(cx.listener(|view, _, window, cx| view.toggle_rating(LIKE, window, cx))),
                    cx,
                ),
            )
            .child(div().w(px(1.)).h(px(20.)).bg(theme.border))
            .child(
                self.stop(
                    Self::pill_button("details-dislike", Lucide::ThumbsDown)
                        .selected(disliked)
                        .accessibility_label(t(Str::VideoDetailsDislike))
                        .on_click(cx.listener(|view, _, window, cx| view.toggle_rating(DISLIKE, window, cx))),
                    cx,
                ),
            );
        let link = format!("https://youtu.be/{}", self.video_id);
        let share = Self::pill_button("details-share", Lucide::Share2)
            .accessibility_label(t(Str::CdShare))
            .tooltip(t(Str::CdShare))
            .on_click(move |_, window, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(link.clone()));
                window.push_notification(t(Str::LinkCopied), cx);
            });
        column = column.child(h_flex().gap_2().child(rating).child(Self::pill(cx).child(self.stop(share, cx))));
        if let Some(description) = details.description.clone().filter(|text| !text.trim().is_empty()) {
            let open = self.description_open;
            // The clamp counts wrapped rows only, so hard breaks are cut here too.
            let lines: Vec<&str> = description.lines().collect();
            let long = lines.len() > DESCRIPTION_LINES || description.chars().count() > DESCRIPTION_LINES * 90;
            let shown = if open { description.clone() } else { lines[..lines.len().min(DESCRIPTION_LINES)].join("\n") };
            column = column.child(
                v_flex()
                    .p_4()
                    .gap_2()
                    .rounded(px(8.))
                    .bg(theme.secondary)
                    .child(div().text_sm().when(!open, |text| text.line_clamp(DESCRIPTION_LINES)).child(shown))
                    .when(long, |column| {
                        column.child(
                            self.stop(
                                Button::new("details-description")
                                    .ghost()
                                    .xsmall()
                                    .label(t(if open { Str::ShowLess } else { Str::ShowMore }))
                                    .on_click(cx.listener(|view, _, _, cx| {
                                        view.description_open = !view.description_open;
                                        cx.notify();
                                    })),
                                cx,
                            ),
                        )
                    }),
            );
        }
        column.child(div().h(px(1.)).w_full().bg(theme.border))
    }

    fn render_field(&self, draft: Draft, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let input = match draft {
            Draft::Comment => &self.composer,
            Draft::Reply => &self.reply_input,
            Draft::Edit => &self.edit_input,
        };
        let busy = self.posting == Some(draft);
        let error = self.post_error.as_ref().filter(|(failed, _)| *failed == draft).map(|(_, error)| error_text(error));
        let id = match draft {
            Draft::Comment => "post-comment",
            Draft::Reply => "post-reply",
            Draft::Edit => "post-edit",
        };
        v_flex()
            .gap_1()
            .child(
                h_flex()
                    .gap_2()
                    .items_end()
                    .child(div().flex_1().child(Textarea::new(input)))
                    .when(draft == Draft::Edit && !busy, |row| {
                        row.child(self.stop(
                            action_icon("cancel-edit", IconName::Close, t(Str::DialogCancel)).on_click(cx.listener(
                                |view, _, _, cx| {
                                    view.editing = None;
                                    cx.notify();
                                },
                            )),
                            cx,
                        ))
                    })
                    .child(if busy {
                        div()
                            .size(px(HIT))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(Spinner::new().small())
                            .into_any_element()
                    } else {
                        self.stop(
                            action_icon(id, Lucide::Send, t(Str::VideoDetailsPost))
                                .on_click(cx.listener(move |view, _, window, cx| view.send(draft, window, cx))),
                            cx,
                        )
                        .into_any_element()
                    }),
            )
            .when_some(error, |column, error| column.child(div().text_xs().text_color(theme.danger).child(error)))
            .into_any_element()
    }

    fn render_comment(&self, comment: &YtComment, indent: bool, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let id = SharedString::from(format!("comment-{}", comment.id));
        let byline =
            [comment.author.clone(), comment.published.clone()].into_iter().flatten().collect::<Vec<_>>().join(" · ");
        let author = comment.author.clone().zip(comment.author_channel_url.clone());
        let mut body = v_flex().flex_1().min_w_0().gap_1();
        if let Some(pinned) = &comment.pinned {
            body = body.child(
                h_flex()
                    .gap_1()
                    .items_center()
                    .text_xs()
                    .text_color(theme.muted_foreground)
                    .child(Icon::new(Lucide::Pin).size(px(12.)))
                    .child(pinned.clone()),
            );
        }
        let open_author = author.clone();
        let byline_element =
            div().id(SharedString::from(format!("{id}-author"))).role(Role::Link).aria_label(byline.clone());
        let byline_element = if open_author.is_some() { self.stop_item(byline_element, cx) } else { byline_element };
        body = body.child(
            byline_element
                .text_xs()
                .truncate()
                .text_color(theme.muted_foreground)
                .when(comment.by_creator, |text| text.font_bold())
                .when(open_author.is_some(), |text| text.cursor_pointer())
                .child(byline)
                .on_click(cx.listener(move |view, _, window, cx| {
                    if let Some((name, url)) = &open_author {
                        view.open_channel(url, name, window, cx);
                    }
                })),
        );
        if self.editing.as_ref() == Some(&comment.id) {
            body = body.child(self.render_field(Draft::Edit, cx));
        } else {
            body = body
                .child(div().text_sm().child(self.text_of(comment)))
                .child(self.render_actions(comment, !indent, cx));
        }
        let own = comment.edit_token.is_some() || comment.delete_token.is_some();
        let menu_comment = comment.clone();
        let view = cx.entity().downgrade();
        h_flex()
            .id(id.clone())
            .gap_4()
            .items_start()
            .pl(px(if indent { REPLY_INDENT } else { 16. }))
            .pr_4()
            .py_2()
            .child(Self::avatar(&comment.author_thumbs, if indent { 24. } else { 32. }, cx))
            .child(body)
            .when(own, |row| {
                let index = self.next_stop();
                let more_id = SharedString::from(format!("{id}-more"));
                row.child(
                    self.focus.wrap(
                        index,
                        action_icon(more_id, IconName::EllipsisVertical, t(Str::CdMore))
                            .track_focus(&self.focus.handle(index, cx))
                            .dropdown_menu_with_anchor(Anchor::TopRight, move |mut menu, _, _| {
                                if menu_comment.edit_token.is_some() {
                                    let (view, comment) = (view.clone(), menu_comment.clone());
                                    menu = menu.item(PopupMenuItem::new(t(Str::VideoDetailsEdit)).on_click(
                                        move |_, window, cx| {
                                            view.update(cx, |view, cx| view.start_edit(&comment, window, cx)).ok();
                                        },
                                    ));
                                }
                                if menu_comment.delete_token.is_some() {
                                    let (view, comment) = (view.clone(), menu_comment.clone());
                                    menu = menu.item(PopupMenuItem::new(t(Str::VideoDetailsDelete)).on_click(
                                        move |_, window, cx| {
                                            view.update(cx, |view, cx| {
                                                view.confirm_delete(comment.clone(), window, cx)
                                            })
                                            .ok();
                                        },
                                    ));
                                }
                                menu
                            }),
                        cx,
                    ),
                )
            })
            .into_any_element()
    }

    /// Like (count) · dislike, toggles when signed in, else just the count · creator heart · Reply.
    fn render_actions(&self, comment: &YtComment, can_reply: bool, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let rating = self.rating_of(comment);
        let (liked, disliked) = (rating == LIKE, rating == DISLIKE);
        let can_like = comment.like_token.is_some() && comment.unlike_token.is_some();
        let can_dislike = comment.dislike_token.is_some() && comment.undislike_token.is_some();
        let count = if liked { comment.likes_liked.clone().or(comment.likes.clone()) } else { comment.likes.clone() };
        let replyable = can_reply && comment.reply_token.is_some();
        let id = comment.id.clone();
        let mut row = h_flex().gap_1().items_center().text_xs().text_color(theme.muted_foreground);
        if can_like {
            let target = comment.clone();
            let label = [Some(t(Str::VideoDetailsLike).to_owned()), count.clone()]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(", ");
            row = row.child(
                self.stop(
                    Button::new(SharedString::from(format!("like-{id}")))
                        .ghost()
                        .xsmall()
                        .icon(Icon::new(Lucide::ThumbsUp))
                        .when_some(count.clone(), |button, count| button.label(count))
                        .selected(liked)
                        .accessibility_label(label)
                        .on_click(cx.listener(move |view, _, window, cx| view.rate_comment(&target, LIKE, window, cx))),
                    cx,
                ),
            );
        } else if let Some(count) = count {
            row = row
                .child(h_flex().gap_1().items_center().child(Icon::new(Lucide::ThumbsUp).size(px(14.))).child(count));
        }
        if can_dislike {
            let target = comment.clone();
            row = row.child(
                self.stop(
                    Button::new(SharedString::from(format!("dislike-{id}")))
                        .ghost()
                        .xsmall()
                        .icon(Icon::new(Lucide::ThumbsDown))
                        .selected(disliked)
                        .accessibility_label(t(Str::VideoDetailsDislike))
                        .on_click(
                            cx.listener(move |view, _, window, cx| view.rate_comment(&target, DISLIKE, window, cx)),
                        ),
                    cx,
                ),
            );
        }
        if comment.hearted {
            row = row.child(Icon::new(Lucide::Heart).size(px(14.)).text_color(theme.primary));
        }
        if replyable {
            let target = comment.clone();
            row = row.child(
                self.stop(
                    Button::new(SharedString::from(format!("reply-{id}")))
                        .ghost()
                        .xsmall()
                        .label(t(Str::VideoDetailsReply))
                        .on_click(cx.listener(move |view, _, window, cx| view.open_reply(&target, window, cx))),
                    cx,
                ),
            );
        }
        row.into_any_element()
    }

    /// Under a list: a spinner while a page loads, the error with Retry, or `more` when there is a next page.
    fn render_footer(&self, key: ListKey, more: Option<&'static str>, cx: &mut Context<Self>) -> Option<AnyElement> {
        let list = match &key {
            ListKey::Top => &self.comments,
            ListKey::Replies(id) => self.replies.get(id)?,
        };
        let start = if matches!(key, ListKey::Top) { 16. } else { REPLY_INDENT };
        let id = match &key {
            ListKey::Top => SharedString::from("comments-more"),
            ListKey::Replies(parent) => SharedString::from(format!("replies-more-{parent}")),
        };
        if list.loading {
            return Some(h_flex().pl(px(start)).py_3().justify_center().child(Spinner::new()).into_any_element());
        }
        if let Some(error) = &list.error {
            let retry = key.clone();
            return Some(
                h_flex()
                    .pl(px(start))
                    .gap_2()
                    .items_center()
                    .child(announced(format!("{id}-error"), error_text(error)).text_xs())
                    .child(
                        self.stop(
                            Button::new(id)
                                .ghost()
                                .xsmall()
                                .label(t(Str::Retry))
                                .on_click(cx.listener(move |view, _, _, cx| view.load_more(retry.clone(), true, cx))),
                            cx,
                        ),
                    )
                    .into_any_element(),
            );
        }
        let label = more?;
        list.continuation.as_ref()?;
        Some(
            div()
                .pl(px(start))
                .child(
                    self.stop(
                        Button::new(id)
                            .ghost()
                            .xsmall()
                            .label(label)
                            .on_click(cx.listener(move |view, _, _, cx| view.load_more(key.clone(), true, cx))),
                        cx,
                    ),
                )
                .into_any_element(),
        )
    }

    fn render_comments(&self, details: &YtVideoDetails, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let header = self.header.clone();
        let count = header.as_ref().and_then(|header| header.count_text.clone()).unwrap_or_else(|| {
            [Some(t(Str::VideoDetailsComments).to_owned()), details.comments_count.clone()]
                .into_iter()
                .flatten()
                .collect::<Vec<_>>()
                .join(" ")
        });
        let mut top = v_flex().gap_2().px_4().py_2().child(div().font_bold().child(count));
        if details.comments_continuation.is_none() {
            top = top.child(div().text_sm().text_color(theme.muted_foreground).child(t(Str::VideoDetailsCommentsOff)));
        }
        if let Some(header) = &header {
            if header.sort.len() > 1 {
                let mut chips = h_flex().gap_2();
                for (index, chip) in header.sort.iter().enumerate() {
                    let token = chip.token.clone();
                    let selected = chip.selected;
                    let button =
                        crate::action_icon::chip(Button::new(("comment-sort", index))).label(chip.title.clone());
                    let chip = if selected { button.primary() } else { button.outline() }.selected(selected).on_click(
                        cx.listener(move |view, _, _, cx| {
                            if let (false, Some(token)) = (selected, token.clone()) {
                                view.reload_comments(token, cx);
                            }
                        }),
                    );
                    chips = chips.child(self.stop(chip, cx));
                }
                top = top.child(chips);
            }
            if header.create_token.is_some() {
                top = top.child(self.render_field(Draft::Comment, cx));
            } else {
                top = top.child(
                    div().text_sm().text_color(theme.muted_foreground).child(t(Str::VideoDetailsSignInToComment)),
                );
            }
        }
        let mut column = v_flex().child(top);
        for comment in &self.comments.comments {
            column = column.child(self.render_comment(comment, false, cx));
            if self.replying_to.as_ref() == Some(&comment.id) {
                column = column.child(div().pl(px(REPLY_INDENT)).pr_4().child(self.render_field(Draft::Reply, cx)));
            }
            if comment.replies_continuation.is_some() {
                let open = self.replies.contains_key(&comment.id);
                let label = if open {
                    t(Str::VideoDetailsHideReplies).to_owned()
                } else {
                    tf(Str::VideoDetailsReplies, &[&comment.reply_count.clone().unwrap_or_default()])
                };
                let target = comment.clone();
                column = column.child(
                    div().pl(px(REPLY_INDENT)).child(
                        self.stop(
                            Button::new(SharedString::from(format!("replies-{}", comment.id)))
                                .ghost()
                                .xsmall()
                                .label(label)
                                .on_click(cx.listener(move |view, _, _, cx| view.toggle_replies(&target, cx))),
                            cx,
                        ),
                    ),
                );
            }
            if let Some(list) = self.replies.get(&comment.id) {
                for reply in &list.comments {
                    column = column.child(self.render_comment(reply, true, cx));
                }
                if let Some(footer) =
                    self.render_footer(ListKey::Replies(comment.id.clone()), Some(t(Str::VideoDetailsMoreReplies)), cx)
                {
                    column = column.child(footer);
                }
            }
        }
        if let Some(footer) = self.render_footer(ListKey::Top, Some(t(Str::ShowMore)), cx) {
            column = column.child(footer);
        } else if self.header.is_some() && self.comments.comments.is_empty() {
            column = column
                .child(div().p_4().text_sm().text_color(theme.muted_foreground).child(t(Str::VideoDetailsNoComments)));
        }
        column
    }
}

impl Render for VideoDetailsView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.next_stop.set(0);
        if self.details.is_some() && !self.focused_first {
            self.focused_first = true;
            let first = self.focus.handle(0, cx);
            window.defer(cx, move |window, cx| window.focus(&first, cx));
        }
        let body = match (self.details.clone(), &self.details_error) {
            (Some(details), _) => v_flex()
                .child(self.render_header(&details, cx))
                .child(self.render_comments(&details, cx))
                .into_any_element(),
            (None, Some(error)) => v_flex()
                .min_h(px(240.))
                .items_center()
                .justify_center()
                .gap_3()
                .child(alert("details-error", error_text(error)).text_center())
                .child(
                    self.stop(
                        Button::new("details-retry")
                            .label(t(Str::Retry))
                            .on_click(cx.listener(|view, _, _, cx| view.load(cx))),
                        cx,
                    ),
                )
                .into_any_element(),
            (None, None) => h_flex()
                .min_h(px(240.))
                .justify_center()
                .items_center()
                .child(Spinner::new().large())
                .into_any_element(),
        };
        let close = press(
            &self.focus,
            self.next_stop(),
            text_button("details-close", t(Str::CdClose), true, true, cx),
            true,
            |_, window, cx| window.close_dialog(cx),
            cx,
        );
        let stops = self.next_stop.get();
        self.focus
            .container(v_flex().id("video-details").w_full(), 0..stops, cx)
            .child(title_bar(t(Str::VideoDetails), cx))
            .child(scroll_body("video-details-body", &self.scroll, div().w_full().pb_4().child(body), window, cx))
            .child(button_bar(close, cx))
    }
}
