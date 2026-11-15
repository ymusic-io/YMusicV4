//! Discover's Account tab, as in the JVM app: the sign-in preface while signed out; signed in, the
//! account library feed under a header (account card, then My channel / history / Watch later /
//! Liked / Subscriptions as chips from 600 wide, rows below) that scrolls away with it.

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Icon, IconName, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::yt::YtAccountInfo;
use ym_i18n::{Str, t};

use crate::a11y::{DragRow, FocusList, announced_as, heading};
use crate::action_icon::action_icon;
use crate::feed::{FeedView, feed_width, thumb_for};
use crate::graph::{AppGraph, on_core};
use crate::settings_view::HOVER_ALPHA;
use crate::signin::AccountChanged;
use crate::spinner::Spinner;

const FEATURES: [Str; 7] = [
    Str::SigninFeatureHome,
    Str::SigninFeatureSubscriptions,
    Str::SigninFeaturePlaylists,
    Str::SigninFeatureHistory,
    Str::SigninFeatureSubscribe,
    Str::SigninFeatureLike,
    Str::SigninFeatureMore,
];
const FORM_MAX: f32 = 560.;
const CHIPS_MIN_WIDTH: f32 = 600.;

pub struct AccountView {
    feed: Entity<FeedView>,
    /// Discover's Account tab draws the account header; YT Music's Library tab does not.
    header: Option<Entity<AccountHeader>>,
    /// None until the first check answers.
    account: Option<YtAccountInfo>,
    sign_in_focus: FocusHandle,
    preface_focus: FocusList,
}

impl EventEmitter<AccountChanged> for AccountView {}

impl AccountView {
    pub fn new(feed: Entity<FeedView>, with_header: bool, cx: &mut Context<Self>) -> Self {
        let header = with_header.then(|| {
            let header = cx.new(|_| AccountHeader::new(feed.downgrade()));
            let view = cx.entity().downgrade();
            header.update(cx, |header, _| header.owner = Some(view));
            header
        });
        let mut account = Self {
            feed,
            header,
            account: None,
            sign_in_focus: cx.focus_handle().tab_stop(true),
            preface_focus: FocusList::new(None),
        };
        account.refresh(cx);
        account
    }

    /// Re-checks the account; a change of who is signed in reloads the library.
    pub fn refresh(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (engine, log) = (graph.engine(), graph.log.fork("account-tab"));
        let load = on_core(cx, async move { engine.account_info(false, &log).await });
        cx.spawn(async move |this, cx| {
            let result = load.await;
            this.update(cx, |view, cx| {
                let account = match result {
                    Some(Ok(account)) => account,
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.warn("account check failed", Some(&error));
                        YtAccountInfo::default()
                    }
                    None => return,
                };
                let changed = view.account.as_ref().map(|old| (old.signed_in, old.channel_id.clone()))
                    != Some((account.signed_in, account.channel_id.clone()));
                if account.signed_in {
                    let top: Option<AnyView> = view.header.clone().map(|header| {
                        header.update(cx, |header, cx| {
                            header.account = account.clone();
                            cx.notify();
                        });
                        header.into()
                    });
                    view.feed.update(cx, |feed, cx| {
                        feed.set_top(top);
                        if changed {
                            feed.reload(cx);
                        }
                    });
                }
                view.account = Some(account);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn sign_in(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        window.dispatch_action(Box::new(crate::shortcuts::OpenSignIn), cx);
    }

    fn sign_out(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        let (jar, engines, log) = (graph.cookies.clone(), graph.engines.clone(), graph.log.fork("signout"));
        let done = on_core(cx, async move { crate::signin::sign_out(jar, engines, &log).await });
        cx.spawn(async move |this, cx| {
            let result = done.await;
            this.update(cx, |view, cx| {
                if let Some(Err(error)) = result {
                    cx.global::<AppGraph>().log.warn("sign-out failed", Some(&error));
                }
                view.refresh(cx);
                cx.emit(AccountChanged);
            })
            .ok();
        })
        .detach();
    }

    /// Signed out: what signing in brings, and the button.
    fn render_preface(&self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let width = feed_width(window);
        let margin = if width >= 840. { 24. } else { 16. };
        let center = width >= 840. && f32::from(window.viewport_size().height) >= 600.;
        // What signing in enables is shown, not read: the title and subtitle say it.
        let mut features = v_flex();
        for feature in FEATURES {
            features = features.child(
                h_flex()
                    .py_2()
                    .gap(px(14.))
                    .items_center()
                    .child(div().size(px(10.)).flex_shrink_0().rounded_full().bg(theme.primary))
                    .child(t(feature)),
            );
        }
        let column = v_flex()
            .w_full()
            .max_w(px(FORM_MAX))
            .px(px(margin))
            .py_4()
            .child(
                h_flex()
                    .gap_4()
                    .items_center()
                    .child(
                        div()
                            .size(px(56.))
                            .flex_shrink_0()
                            .rounded(px(14.))
                            .bg(rgb(0xff0000))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(Icon::new(Lucide::Play).size(px(30.)).text_color(white())),
                    )
                    .child(
                        v_flex()
                            .child(heading("signin-title", 2, t(Str::SigninPrefaceTitle)).text_xl().font_bold())
                            .child(
                                announced_as(
                                    "signin-subtitle",
                                    t(Str::SigninPrefaceSubtitle),
                                    format!("{}. {}", t(Str::SigninPrefaceTitle), t(Str::SigninPrefaceSubtitle)),
                                )
                                .text_sm()
                                .text_color(theme.muted_foreground),
                            ),
                    ),
            )
            .child(div().my(px(20.)).h(px(1.)).bg(theme.border))
            .child(div().child(t(Str::SigninEnables)).font_bold().text_color(theme.muted_foreground))
            .child(div().h(px(12.)))
            .child(features)
            .child(div().h(px(28.)))
            .child(
                self.preface_focus
                    .item_for(
                        0,
                        &self.sign_in_focus,
                        div().id("signin-button").role(Role::Button).aria_label(t(Str::SigninButton)),
                        cx,
                    )
                    .w_full()
                    .py(px(14.))
                    .rounded(px(8.))
                    .bg(theme.primary)
                    .text_color(theme.primary_foreground)
                    .font_bold()
                    .flex()
                    .justify_center()
                    .cursor_pointer()
                    .child(t(Str::SigninButton).to_uppercase())
                    .on_click(cx.listener(|view, _, window, cx| view.sign_in(window, cx))),
            );
        div()
            .id("signin-preface")
            .size_full()
            .overflow_y_scroll()
            .child(
                h_flex()
                    .w_full()
                    .min_h_full()
                    .justify_center()
                    .when(center, |row| row.items_center())
                    .when(!center, |row| row.items_start())
                    .child(column),
            )
            .into_any_element()
    }
}

impl Render for AccountView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        match &self.account {
            None => div().size_full().flex().items_center().justify_center().child(Spinner::new()).into_any_element(),
            Some(account) if !account.signed_in => self.render_preface(window, cx),
            Some(_) => self.feed.clone().into_any_element(),
        }
    }
}

/// The signed-in header drawn as the library feed's top content.
pub struct AccountHeader {
    account: YtAccountInfo,
    feed: WeakEntity<FeedView>,
    owner: Option<WeakEntity<AccountView>>,
    focus: FocusList,
    drag: DragRow,
}

struct NavTarget {
    icon: Lucide,
    label: &'static str,
    link: String,
    title: SharedString,
}

impl AccountHeader {
    fn new(feed: WeakEntity<FeedView>) -> Self {
        Self {
            account: YtAccountInfo::default(),
            feed,
            owner: None,
            focus: FocusList::new(None),
            drag: DragRow::default(),
        }
    }

    fn targets(&self) -> Vec<NavTarget> {
        let mut targets = Vec::new();
        if let Some(channel) = &self.account.channel_id {
            let title = self.account.name.clone().unwrap_or_else(|| t(Str::AccountMyChannel).to_owned());
            targets.push(NavTarget {
                icon: Lucide::CircleUser,
                label: t(Str::AccountMyChannel),
                link: format!("/channel/{channel}"),
                title: title.into(),
            });
        }
        for (icon, label, link) in [
            (Lucide::RotateCcwClock, Str::AccountWatchHistory, "/feed/history"),
            (Lucide::Clock, Str::AccountWatchLater, "/playlist?list=WL"),
            (Lucide::ThumbsUp, Str::AccountLikedVideos, "/playlist?list=LL"),
            (Lucide::SquarePlay, Str::AccountSubscriptions, "/feed/subscriptions"),
        ] {
            targets.push(NavTarget { icon, label: t(label), link: link.to_owned(), title: t(label).into() });
        }
        targets
    }
}

impl Render for AccountHeader {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let account = self.account.clone();
        let avatar = thumb_for(&account.thumbs, 92);
        let initial = account
            .name
            .as_deref()
            .and_then(|name| name.chars().next())
            .map(|letter| letter.to_uppercase().to_string());
        let owner = self.owner.clone();
        let more = action_icon("account-more", IconName::EllipsisVertical, t(Str::CdMore)).dropdown_menu_with_anchor(
            Anchor::TopRight,
            move |menu, _, _| {
                let owner = owner.clone();
                menu.item(PopupMenuItem::new(t(Str::AccountSignOut)).on_click(move |_, _, cx| {
                    if let Some(owner) = &owner {
                        owner.update(cx, |view, cx| view.sign_out(cx)).ok();
                    }
                }))
            },
        );
        let card = h_flex()
            .pl_4()
            .pr_2()
            .pt(px(15.))
            .pb(px(13.))
            .gap(px(13.))
            .items_center()
            .child(
                div()
                    .size(px(46.))
                    .flex_shrink_0()
                    .rounded_full()
                    .overflow_hidden()
                    .bg(theme.foreground)
                    .text_color(theme.background)
                    .flex()
                    .items_center()
                    .justify_center()
                    .font_bold()
                    .map(|frame| match (avatar, initial) {
                        (Some(url), _) => frame.child(img(url).size_full().rounded_full().object_fit(ObjectFit::Cover)),
                        (None, Some(letter)) => frame.child(letter),
                        (None, None) => frame.child(Icon::new(Lucide::User)),
                    }),
            )
            .child(
                v_flex()
                    .flex_1()
                    .min_w_0()
                    .child(div().text_lg().font_bold().truncate().child(account.name.clone().unwrap_or_default()))
                    .when_some(account.email.clone().or(account.handle.clone()), |column, line| {
                        column.child(div().text_sm().text_color(theme.muted_foreground).truncate().child(line))
                    }),
            )
            .child(more);
        let divider = || div().mx_4().h(px(1.)).bg(theme.border);
        let targets = self.targets();
        let count = targets.len();
        let chips = feed_width(window) >= CHIPS_MIN_WIDTH;
        let mut nav = if chips {
            h_flex()
                .id("account-nav")
                .px_4()
                .py(px(10.))
                .gap_2()
                .track_scroll(self.drag.scroll())
                .overflow_x_scroll()
                .restrict_scroll_to_axis()
        } else {
            v_flex().id("account-nav").py_1()
        };
        for (index, target) in targets.into_iter().enumerate() {
            let feed = self.feed.clone();
            let (link, title) = (target.link.clone(), target.title.clone());
            let entry = div().id(("account-nav", index)).role(Role::Link).aria_label(target.label);
            let entry = self
                .focus
                .item_in(index, entry, chips.then(|| self.drag.scroll().clone()), cx)
                .flex()
                .items_center()
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .map(|entry| {
                    if chips {
                        entry
                            .flex_shrink_0()
                            .gap(px(7.))
                            .pl_2()
                            .pr_3()
                            .py_1()
                            .rounded_full()
                            .border_1()
                            .border_color(theme.border)
                    } else {
                        entry.gap(px(18.)).px(px(18.)).py(px(15.))
                    }
                })
                .child(Icon::new(target.icon).text_color(theme.muted_foreground))
                .child(div().text_sm().font_medium().child(target.label))
                .on_click(move |_, _, cx| {
                    feed.update(cx, |feed, cx| feed.open_link(&link, Some(title.clone()), cx)).ok();
                });
            nav = nav.child(entry);
        }
        let nav = self.focus.container(nav, 0..count, cx);
        v_flex()
            .child(card)
            .child(divider())
            .child(if chips { self.drag.wrap(nav).into_any_element() } else { nav.into_any_element() })
            .child(divider())
    }
}
