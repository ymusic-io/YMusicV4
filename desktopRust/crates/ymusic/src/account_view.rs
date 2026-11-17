//! Discover's Account tab, as in the JVM app: the sign-in preface while signed out; signed in, the
//! account library feed under a header (account card, then My channel / history / Watch later /
//! Liked / Subscriptions as chips from 600 wide, rows below) that scrolls away with it.

use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Icon, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_engine::yt::YtAccountInfo;
use ym_i18n::{Str, t};
use ym_image_loader::async_image::async_image_of;

use crate::a11y::{DragRow, FocusList, announced_as, heading};
use crate::account::AccountController;
use crate::action_icon::material_action_icon;
use crate::assets::MaterialIcon;
use crate::feed::{FeedView, feed_width};
use crate::settings_view::HOVER_ALPHA;
use crate::spinner::Spinner;
use crate::youtube_thumbnails::thumb_request_sources;

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
    /// The library feed may fetch: signed in, with the profile ready.
    gate_open: bool,
    sign_in_focus: FocusHandle,
    preface_focus: FocusList,
    _accounts: Subscription,
}

impl AccountView {
    pub fn new(feed: Entity<FeedView>, with_header: bool, cx: &mut Context<Self>) -> Self {
        let header = with_header.then(|| {
            let header = cx.new(|_| AccountHeader::new(feed.downgrade()));
            let view = cx.entity().downgrade();
            header.update(cx, |header, _| header.owner = Some(view));
            header
        });
        let accounts = cx.observe(&AccountController::global(cx), |view, _, cx| view.sync(cx));
        let mut account = Self {
            feed,
            header,
            gate_open: false,
            sign_in_focus: cx.focus_handle().tab_stop(true),
            preface_focus: FocusList::new(None),
            _accounts: accounts,
        };
        account.sync(cx);
        account
    }

    /// Follows the account controller; the library reloads each time its gate opens.
    fn sync(&mut self, cx: &mut Context<Self>) {
        let controller = AccountController::global(cx);
        let (gate_open, account) = {
            let controller = controller.read(cx);
            (controller.signed_in && controller.profile_ready, controller.account.clone())
        };
        let top: Option<AnyView> = match (&self.header, account) {
            (Some(header), Some(account)) => {
                header.update(cx, |header, cx| {
                    header.account = account;
                    cx.notify();
                });
                Some(header.clone().into())
            }
            _ => None,
        };
        let reload = gate_open && !self.gate_open;
        self.gate_open = gate_open;
        self.feed.update(cx, |feed, cx| {
            feed.open_gate(gate_open);
            feed.set_top(top);
            if reload {
                feed.reload(cx);
            }
        });
        cx.notify();
    }

    fn sign_in(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        window.dispatch_action(Box::new(crate::shortcuts::OpenSignIn), cx);
    }

    fn sign_out(&mut self, cx: &mut Context<Self>) {
        AccountController::global(cx).update(cx, |controller, cx| controller.sign_out(cx));
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
                            .child(Icon::new(MaterialIcon::PlayArrow).size(px(34.)).text_color(white())),
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
        let controller = AccountController::global(cx);
        let (signed_in, profile_ready) = {
            let controller = controller.read(cx);
            (controller.signed_in, controller.profile_ready)
        };
        // The Account tab waits for the profile before choosing; YT Music's Library tab follows the gate alone.
        let waiting = !profile_ready && (self.header.is_some() || signed_in);
        if waiting {
            div().size_full().flex().items_center().justify_center().child(Spinner::new()).into_any_element()
        } else if !signed_in {
            self.render_preface(window, cx)
        } else {
            self.feed.clone().into_any_element()
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
    icon: MaterialIcon,
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
                icon: MaterialIcon::AccountCircleOutlined,
                label: t(Str::AccountMyChannel),
                link: format!("/channel/{channel}"),
                title: title.into(),
            });
        }
        for (icon, label, link) in [
            (MaterialIcon::HistoryOutlined, Str::AccountWatchHistory, "/feed/history"),
            (MaterialIcon::ScheduleOutlined, Str::AccountWatchLater, "/playlist?list=WL"),
            (MaterialIcon::ThumbUpOutlined, Str::AccountLikedVideos, "/playlist?list=LL"),
            (MaterialIcon::SubscriptionsOutlined, Str::AccountSubscriptions, "/feed/subscriptions"),
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
        let avatar = thumb_request_sources(&account.thumbs);
        let initial = account
            .name
            .as_deref()
            .and_then(|name| name.chars().next())
            .map(|letter| letter.to_uppercase().to_string());
        let fallback = div()
            .size_full()
            .rounded_full()
            .bg(theme.foreground)
            .text_color(theme.background)
            .flex()
            .items_center()
            .justify_center()
            .font_bold()
            .map(|disc| match initial {
                Some(letter) => disc.child(letter),
                None => disc.child(Icon::new(MaterialIcon::Person).size(px(24.))),
            });
        let owner = self.owner.clone();
        let more = material_action_icon("account-more", MaterialIcon::MoreVert, t(Str::CdMore))
            .dropdown_menu_with_anchor(Anchor::TopRight, move |menu, _, _| {
                let owner = owner.clone();
                menu.item(PopupMenuItem::new(t(Str::AccountSignOut)).on_click(move |_, _, cx| {
                    if let Some(owner) = &owner {
                        owner.update(cx, |view, cx| view.sign_out(cx)).ok();
                    }
                }))
            });
        let card = h_flex()
            .pl_4()
            .pr_2()
            .pt(px(15.))
            .pb(px(13.))
            .gap(px(13.))
            .items_center()
            .child(
                div().size(px(46.)).flex_shrink_0().rounded_full().overflow_hidden().child(match avatar {
                    // The disc only backs the fallback: under the photo its edge would show as a dark ring.
                    Some(request) => async_image_of(Some(request))
                        .size_full()
                        .rounded_full()
                        .object_fit(ObjectFit::Cover)
                        .placeholder(fallback)
                        .into_any_element(),
                    None => fallback.into_any_element(),
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
                .child(Icon::new(target.icon).size(px(24.)).text_color(theme.muted_foreground))
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
