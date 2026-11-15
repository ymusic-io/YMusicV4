//! Signing in without a browser, laid out like the JVM's CookieSignInScreen: a full-page layer with
//! its own bar. First the QR to scan with the phone app (its cookies arrive over the device link),
//! with the matching code; "Or paste cookies from a browser" switches to pasting a copied request.

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{Textarea, TextareaState};
use gpui_kit::component::{ActiveTheme, Disableable, IconName, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};
use ym_signin::DeviceLinkOffer;

use crate::a11y::{FocusList, alert, announced, announced_as, heading, label};
use crate::action_icon::action_icon;
use crate::cookie_guide::CookieGuide;
use crate::cookie_lock::{ProtectEvent, ProtectStep};
use crate::elevated_dialog::ConfirmDialog;
use crate::graph::{AppGraph, on_core};
use crate::signin::SignInError;
use crate::spinner::{BusyButton, Spinner};

const FORM_MAX: f32 = 560.;
const QR_BOX: f32 = 220.;
const QR_PADDING: f32 = 8.;
const STOP_BACK: usize = 0;
const STOP_RETRY: usize = 1;
const STOP_PASTE_LINK: usize = 2;
const STOP_SUBMIT: usize = 3;

pub enum SignInEvent {
    /// Leave the screen without signing in.
    Back,
    Done,
}

enum Link {
    Starting,
    Waiting {
        modules: Vec<bool>,
        width: usize,
        code: String,
    },
    /// The offer could not be made or expired; Retry starts a new one.
    Ended,
    /// A guest launch: nothing of a sign-in could be kept.
    Unavailable,
}

pub struct SignInView {
    link: Link,
    /// Shown under the code: why the QR ended, or why a paste was refused.
    error: Option<String>,
    paste_mode: bool,
    guide: Entity<CookieGuide>,
    paste: Entity<TextareaState>,
    submitting: bool,
    /// Signed in; the password step is up, or about to be built.
    protecting: bool,
    protect: Option<(Entity<ProtectStep>, Subscription)>,
    /// The bar's and the page's buttons.
    focus: FocusList,
    /// The title takes focus once, as the page opens, so the screen reader says where it is; it is
    /// no Tab stop.
    title_focus: FocusHandle,
    title_focused: bool,
    _link: Option<Task<()>>,
}

impl EventEmitter<SignInEvent> for SignInView {}

impl SignInView {
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let paste = cx.new(|cx| TextareaState::new(window, cx).placeholder(t(Str::SigninPasteEmpty)));
        let guest = cx.global::<AppGraph>().guest;
        let mut view = Self {
            link: if guest { Link::Unavailable } else { Link::Starting },
            error: None,
            paste_mode: false,
            guide: cx.new(CookieGuide::new),
            paste,
            submitting: false,
            protecting: false,
            protect: None,
            focus: FocusList::new(None),
            title_focus: cx.focus_handle(),
            title_focused: false,
            _link: None,
        };
        if !guest {
            view.start_link(cx);
        }
        view
    }

    /// Debug: straight to pasting cookies.
    pub fn dev_paste(&mut self) {
        self.paste_mode = true;
    }

    /// Debug: straight to the password step, as after a sign-in.
    pub fn dev_protect(&mut self) {
        self.protecting = true;
    }

    /// Offers the password step where the saved jar can take one; done otherwise.
    fn signed_in(&mut self, cx: &mut Context<Self>) {
        let graph = cx.global::<AppGraph>();
        if graph.can_protect && graph.sealed_jar.as_ref().is_some_and(|sealed| !sealed.is_password_protected()) {
            self.protecting = true;
            cx.notify();
        } else {
            cx.emit(SignInEvent::Done);
        }
    }

    fn protect_step(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Option<Entity<ProtectStep>> {
        if !self.protecting {
            return None;
        }
        if self.protect.is_none() {
            let graph = cx.global::<AppGraph>();
            let sealed = graph.sealed_jar.clone()?;
            let (tokio, log) = (graph.tokio.clone(), graph.log.clone());
            let step = cx.new(|cx| ProtectStep::new(sealed, tokio, log, window, cx));
            let done = cx.subscribe(&step, |_, _, _: &ProtectEvent, cx| cx.emit(SignInEvent::Done));
            self.protect = Some((step, done));
        }
        self.protect.as_ref().map(|(step, _)| step.clone())
    }

    /// Back from pasting returns to the QR; back from the QR leaves; back from the password step
    /// keeps the sign-in unprotected.
    pub fn back(&mut self, cx: &mut Context<Self>) {
        if self.protecting {
            cx.emit(SignInEvent::Done);
        } else if self.paste_mode {
            self.paste_mode = false;
            self.error = None;
            cx.notify();
        } else {
            cx.emit(SignInEvent::Back);
        }
    }

    fn start_link(&mut self, cx: &mut Context<Self>) {
        self.link = Link::Starting;
        self.error = None;
        let remote = cx.global::<AppGraph>().remote.clone();
        let start = on_core(cx, async move { crate::signin::start_device_link(&remote).await });
        self._link = Some(cx.spawn(async move |this, cx| {
            let result = start.await;
            this.update(cx, |view, cx| {
                match result {
                    Some(Ok(offer)) => view.wait_for_phone(offer, cx),
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.info(&format!("device link unavailable: {error}"));
                        view.error = Some(sign_in_error_text(&error, cx));
                        view.link = Link::Ended;
                    }
                    None => {}
                }
                cx.notify();
            })
            .ok();
        }));
    }

    fn wait_for_phone(&mut self, offer: DeviceLinkOffer, cx: &mut Context<Self>) {
        match qrcode::QrCode::new(offer.qr_payload().as_bytes()) {
            Ok(qr) => {
                let width = qr.width();
                let modules = qr.to_colors().into_iter().map(|color| color == qrcode::Color::Dark).collect();
                self.link = Link::Waiting { modules, width, code: offer.confirmation_code() };
            }
            Err(error) => {
                cx.global::<AppGraph>()
                    .log
                    .warn(&format!("R90023 the device-link QR could not be drawn: {error}"), None);
                self.error = Some(tf(Str::ErrorGeneric, &[&"R90023"]));
                self.link = Link::Ended;
                return;
            }
        }
        let graph = cx.global::<AppGraph>();
        let (remote, jar, engines, log) =
            (graph.remote.clone(), graph.cookies.clone(), graph.engines.clone(), graph.log.fork("devicelink"));
        let linked =
            on_core(cx, async move { crate::signin::await_device_link(remote, offer, jar, engines, &log).await });
        self._link = Some(cx.spawn(async move |this, cx| {
            let result = linked.await;
            this.update(cx, |view, cx| match result {
                Some(Ok(_)) => view.signed_in(cx),
                Some(Err(error)) => {
                    view.error = Some(sign_in_error_text(&error, cx));
                    view.link = Link::Ended;
                    cx.notify();
                }
                None => {}
            })
            .ok();
        }));
    }

    fn submit_paste(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let text = self.paste.read(cx).value().to_string();
        let cookies = ym_signin::parse_cookie_paste(&text);
        if cookies.is_empty() {
            if text.trim_start().starts_with("curl") {
                ConfirmDialog::new(
                    t(Str::SigninPasteNoCookieTitle),
                    t(Str::SigninPasteNoCookieBody),
                    t(Str::SigninPasteNoCookieOk),
                )
                .without_cancel()
                .open(window, cx, |_, _| {});
            } else {
                self.error = Some(t(Str::SigninPasteEmpty).to_owned());
                cx.notify();
            }
            return;
        }
        self.submitting = true;
        self.error = None;
        let graph = cx.global::<AppGraph>();
        let (jar, engines, log) = (graph.cookies.clone(), graph.engines.clone(), graph.log.fork("signin"));
        let done = on_core(cx, async move { crate::signin::sign_in_with_cookies(cookies, jar, engines, &log).await });
        cx.spawn(async move |this, cx| {
            let result = done.await;
            this.update(cx, |view, cx| {
                view.submitting = false;
                match result {
                    Some(Ok(_)) => view.signed_in(cx),
                    Some(Err(error)) => view.error = Some(sign_in_error_text(&error, cx)),
                    None => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn render_qr(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let theme = cx.theme().clone();
        let qr: AnyElement = match &self.link {
            Link::Waiting { modules, width, .. } => {
                let cell = ((QR_BOX - 2. * QR_PADDING) / *width as f32).floor();
                let mut grid = v_flex()
                    .id("device-link-qr")
                    .role(Role::Image)
                    .aria_label(t(Str::CdDeviceLinkQr))
                    .p(px(QR_PADDING))
                    .bg(white());
                for y in 0..*width {
                    let mut line = h_flex();
                    for x in 0..*width {
                        let dark = modules[y * width + x];
                        line = line.child(div().size(px(cell)).when(dark, |module| module.bg(black())));
                    }
                    grid = grid.child(line);
                }
                grid.into_any_element()
            }
            Link::Starting => Spinner::new().into_any_element(),
            Link::Ended | Link::Unavailable => div().into_any_element(),
        };
        let code = match &self.link {
            Link::Waiting { code, .. } => Some(code.clone()),
            _ => None,
        };
        let status: AnyElement = match (&self.error, &self.link) {
            (Some(error), _) => {
                alert("signin-error", error.clone()).text_sm().text_color(theme.danger).text_center().into_any_element()
            }
            (None, Link::Ended) => div().into_any_element(),
            (None, _) => h_flex()
                .gap_2()
                .items_center()
                .child(Spinner::new().small())
                .child(label("signin-waiting", t(Str::SigninQrWaiting)).text_sm().text_color(theme.muted_foreground))
                .into_any_element(),
        };
        let ended = matches!(self.link, Link::Ended);
        vec![
            label("signin-qr-title", t(Str::SigninQrTitle)).text_center().font_semibold().into_any_element(),
            announced_as(
                "signin-qr-hint",
                t(Str::SigninQrHint),
                format!("{}. {}", t(Str::SigninQrTitle), t(Str::SigninQrHint)),
            )
            .text_center()
            .text_sm()
            .text_color(theme.muted_foreground)
            .into_any_element(),
            div().w_full().h(px(QR_BOX)).flex().items_center().justify_center().child(qr).into_any_element(),
            v_flex()
                .w_full()
                .items_center()
                .gap_2()
                .child(match code {
                    Some(code) => div()
                        .id("signin-code")
                        .role(Role::Label)
                        .aria_value(format!("{code}. {}", t(Str::SigninQrSasHint)))
                        .aria_live(Live::Polite)
                        .child(code)
                        .text_size(px(28.))
                        .font_bold()
                        .font_family("Menlo")
                        .text_color(theme.foreground),
                    None => div()
                        .id("signin-code-placeholder")
                        .text_size(px(28.))
                        .font_bold()
                        .font_family("Menlo")
                        .text_color(transparent_black())
                        .child("000 000"),
                })
                .child(
                    label("signin-code-hint", t(Str::SigninQrSasHint))
                        .text_sm()
                        .text_center()
                        .text_color(theme.muted_foreground),
                )
                .into_any_element(),
            v_flex()
                .w_full()
                .min_h(px(20.))
                .items_center()
                .gap_1()
                .child(status)
                .when(ended, |column| {
                    column.child(
                        self.focus.button(
                            STOP_RETRY,
                            Button::new("signin-retry")
                                .ghost()
                                .icon(Lucide::RefreshCw)
                                .label(t(Str::Retry))
                                .on_click(cx.listener(|view, _, _, cx| view.start_link(cx))),
                            cx,
                        ),
                    )
                })
                .into_any_element(),
            self.focus
                .item(
                    STOP_PASTE_LINK,
                    div().id("signin-paste-link").role(Role::Link).aria_label(t(Str::SigninOrPaste)),
                    cx,
                )
                .py_2()
                .px_1()
                .rounded(px(4.))
                .text_sm()
                .text_color(theme.link)
                .cursor_pointer()
                .child(t(Str::SigninOrPaste))
                .on_click(cx.listener(|view, _, _, cx| {
                    view.paste_mode = true;
                    view.error = None;
                    cx.notify();
                }))
                .into_any_element(),
        ]
    }

    fn render_paste(&self, cx: &mut Context<Self>) -> Vec<AnyElement> {
        let theme = cx.theme().clone();
        let blank = self.paste.read(cx).value().trim().is_empty();
        vec![
            announced("signin-paste-title", t(Str::SigninOrPaste)).text_center().font_semibold().into_any_element(),
            self.guide.clone().into_any_element(),
            v_flex()
                .w_full()
                .gap_1()
                .child(div().text_xs().text_color(theme.muted_foreground).child(t(Str::SigninPasteLabel)))
                .child(Textarea::new(&self.paste).aria_label(t(Str::SigninPasteLabel)).h(px(120.)))
                .when_some(self.error.clone(), |column, error| {
                    column.child(alert("signin-error", error).text_sm().text_color(theme.danger))
                })
                .into_any_element(),
            self.focus
                .button(
                    STOP_SUBMIT,
                    Button::new("signin-use-cookies")
                        .primary()
                        .w_full()
                        .label(t(Str::SigninPasteSubmit))
                        .busy(self.submitting)
                        .disabled(blank || self.submitting)
                        .on_click(cx.listener(|view, _, window, cx| view.submit_paste(window, cx))),
                    cx,
                )
                .w_full()
                .into_any_element(),
        ]
    }
}

fn sign_in_error_text(error: &SignInError, cx: &App) -> String {
    match error {
        SignInError::NotSignedIn => t(Str::SigninCookiesRejected).to_owned(),
        SignInError::Engine(engine) => crate::feed::error_text(engine),
        other => {
            cx.global::<AppGraph>().log.warn("sign-in failed", Some(other));
            tf(Str::ErrorGeneric, &[&"R90019"])
        }
    }
}

impl Render for SignInView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if let Some(step) = self.protect_step(window, cx) {
            return step.into_any_element();
        }
        if !self.title_focused {
            self.title_focused = true;
            let title = self.title_focus.clone();
            window.defer(cx, move |window, cx| window.focus(&title, cx));
        }
        let theme = cx.theme().clone();
        let bar = h_flex()
            .px_2()
            .py_1()
            .gap_2()
            .items_center()
            .child(
                self.focus.button(
                    STOP_BACK,
                    action_icon("signin-back", IconName::ArrowLeft, t(Str::CdBack))
                        .on_click(cx.listener(|view, _, _, cx| view.back(cx))),
                    cx,
                ),
            )
            .child(
                heading("signin-heading", 1, t(Str::SigninScreenTitle)).track_focus(&self.title_focus).font_semibold(),
            );
        let content = match (&self.link, self.paste_mode) {
            (Link::Unavailable, _) => vec![
                announced("signin-unavailable", t(Str::SigninUnavailable))
                    .text_center()
                    .text_color(theme.muted_foreground)
                    .into_any_element(),
            ],
            (_, true) => self.render_paste(cx),
            (_, false) => self.render_qr(cx),
        };
        v_flex()
            .size_full()
            .bg(theme.background)
            .child(bar)
            .child(div().id("signin-scroll").flex_1().min_h_0().overflow_y_scroll().child(
                h_flex().w_full().min_h_full().justify_center().items_center().child(
                    v_flex().w_full().max_w(px(FORM_MAX)).px_4().py_4().gap_4().items_center().children(content),
                ),
            ))
            .into_any_element()
    }
}
