//! The password around the saved sign-in, laid out like the JVM's CookieLockScreens: the launch
//! gate for a password-sealed jar (unlock, continue as a guest, or forget the sign-in) and the
//! step after a sign-in that offers to seal the jar under a password.

use std::sync::Arc;

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{Input, InputContentType, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Disableable, StyledExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use tokio::runtime::Handle;
use ym_common::log::Logger;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, alert, heading, label};
use crate::cookie_session::{CookieSession, CookieStoreError, LockedJar, SealedJar};
use crate::elevated_dialog::ConfirmDialog;
use crate::spinner::Spinner;

const FORM_MAX: f32 = 560.;
/// A form's stops after its buttons (0 to 2): its password fields.
const STOP_PASSWORD: usize = 3;
const STOP_CONFIRM: usize = 4;

type OnSession = Box<dyn FnOnce(CookieSession, &mut Window, &mut App)>;

fn password_input(window: &mut Window, cx: &mut App) -> Entity<InputState> {
    cx.new(|cx| InputState::new(window, cx).masked(true))
}

/// The field is `focus`'s `index`th stop, its error the next.
fn password_field(
    id: &'static str,
    state: &Entity<InputState>,
    name: &'static str,
    error: Option<&str>,
    (focus, index): (&FocusList, usize),
    cx: &App,
) -> AnyElement {
    let theme = cx.theme();
    let input = Input::new(state).id(id).aria_label(name).content_type(InputContentType::Password);
    v_flex()
        .w_full()
        .gap_1()
        .child(div().text_xs().text_color(theme.muted_foreground).child(name))
        .child(focus.field(index, &state.focus_handle(cx), input, cx))
        .when_some(error.map(str::to_owned), |column, error| {
            column.child(alert(format!("{id}-error"), error).text_sm().text_color(theme.danger))
        })
        .into_any_element()
}

/// The primary button, or a spinner in its place while the (deliberately slow) key derivation runs.
fn busy_or(busy: bool, button: Div) -> AnyElement {
    if busy {
        h_flex().w_full().h_8().justify_center().items_center().child(Spinner::new()).into_any_element()
    } else {
        button.w_full().into_any_element()
    }
}

fn lock_form(title: &'static str, body: &'static str, content: Vec<AnyElement>, cx: &App) -> Div {
    let theme = cx.theme();
    v_flex().size_full().bg(theme.background).child(
        div().id("lock-scroll").flex_1().min_h_0().overflow_y_scroll().child(
            h_flex().w_full().min_h_full().justify_center().items_center().child(
                v_flex()
                    .w_full()
                    .max_w(px(FORM_MAX))
                    .px_4()
                    .py_4()
                    .gap_4()
                    .items_center()
                    .child(heading("lock-title", 1, title).text_center().font_semibold())
                    .child(label("lock-body", body).text_center().text_sm().text_color(theme.muted_foreground))
                    .children(content),
            ),
        ),
    )
}

/// Launch gate for a password-sealed jar.
pub struct UnlockView {
    locked: Arc<LockedJar>,
    tokio: Handle,
    log: Logger,
    password: Entity<InputState>,
    error: Option<String>,
    busy: bool,
    focus: FocusList,
    on_session: Option<OnSession>,
    _subscription: Subscription,
}

enum UnlockAction {
    Unlock(String),
    Guest,
    SignOut,
}

impl UnlockView {
    pub fn new(
        locked: Arc<LockedJar>,
        tokio: Handle,
        log: Logger,
        on_session: impl FnOnce(CookieSession, &mut Window, &mut App) + 'static,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let password = password_input(window, cx);
        password.update(cx, |input, cx| input.focus(window, cx));
        let subscription = cx.subscribe_in(&password, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
            match event {
                InputEvent::PressEnter { .. } => view.unlock(window, cx),
                InputEvent::Change => view.error = None,
                _ => {}
            }
            cx.notify();
        });
        Self {
            locked,
            tokio,
            log: log.fork("cookies"),
            password,
            error: None,
            busy: false,
            focus: FocusList::new(None),
            on_session: Some(Box::new(on_session)),
            _subscription: subscription,
        }
    }

    fn unlock(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let password = self.password.read(cx).value().to_string();
        if password.is_empty() {
            return;
        }
        self.run(UnlockAction::Unlock(password), window, cx);
    }

    fn run(&mut self, action: UnlockAction, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy || self.on_session.is_none() {
            return;
        }
        self.busy = true;
        self.error = None;
        let locked = self.locked.clone();
        let opened = self.tokio.spawn_blocking(move || match action {
            UnlockAction::Unlock(password) => locked.unlock(&password),
            UnlockAction::Guest => locked.as_guest().map(Some),
            UnlockAction::SignOut => locked.sign_out().map(Some),
        });
        cx.spawn_in(window, async move |this, cx| {
            let result = opened.await.unwrap_or_else(|error| Err(CookieStoreError::Io(std::io::Error::other(error))));
            this.update_in(cx, |view, window, cx| {
                match result {
                    Ok(Some(session)) => {
                        if let Some(on_session) = view.on_session.take() {
                            on_session(session, window, cx);
                        }
                        return;
                    }
                    Ok(None) => view.error = Some(t(Str::CookieUnlockWrong).to_owned()),
                    Err(error) => {
                        view.log.capture_error("R98002 cookie store unlock failed", &error);
                        view.error = Some(tf(Str::ErrorGeneric, &[&"R98002"]));
                    }
                }
                view.busy = false;
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn confirm_sign_out(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let view = cx.entity().downgrade();
        ConfirmDialog::new(
            t(Str::CookieUnlockSignOutTitle),
            t(Str::CookieUnlockSignOutBody),
            t(Str::CookieUnlockSignOut),
        )
        .destructive()
        .open(window, cx, move |window, cx| {
            view.update(cx, |view, cx| view.run(UnlockAction::SignOut, window, cx)).ok();
        });
    }

    fn escape_choice(
        &self,
        index: usize,
        name: &'static str,
        hint: &'static str,
        danger: bool,
        on_click: impl Fn(&mut Self, &mut Window, &mut Context<Self>) + 'static,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = cx.theme().clone();
        let button = Button::new(("unlock-choice", index))
            .ghost()
            .label(name)
            .disabled(self.busy)
            .when(danger, |button| button.text_color(theme.danger))
            .on_click(cx.listener(move |view, _, window, cx| on_click(view, window, cx)));
        v_flex()
            .flex_1()
            .min_w_0()
            .items_center()
            .gap_1()
            .child(self.focus.button(index, button, cx))
            .child(
                label(("unlock-choice-hint", index), hint).text_xs().text_center().text_color(theme.muted_foreground),
            )
            .into_any_element()
    }
}

impl Render for UnlockView {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let blank = self.password.read(cx).value().is_empty();
        let submit = Button::new("unlock-submit")
            .primary()
            .w_full()
            .label(t(Str::CookieUnlockSubmit))
            .disabled(blank)
            .on_click(cx.listener(|view, _, window, cx| view.unlock(window, cx)));
        let divider = || div().flex_1().h(px(1.)).bg(theme.border);
        let content = vec![
            password_field(
                "unlock-password",
                &self.password,
                t(Str::CookiePassword),
                self.error.as_deref(),
                (&self.focus, STOP_PASSWORD),
                cx,
            ),
            busy_or(self.busy, self.focus.button(0, submit, cx)),
            h_flex()
                .w_full()
                .gap_4()
                .items_center()
                .child(divider())
                .child(
                    label("unlock-cant", t(Str::CookieUnlockCantUnlock)).text_xs().text_color(theme.muted_foreground),
                )
                .child(divider())
                .into_any_element(),
            h_flex()
                .w_full()
                .gap_4()
                .items_start()
                .child(self.escape_choice(
                    1,
                    t(Str::CookieUnlockGuest),
                    t(Str::CookieUnlockGuestHint),
                    false,
                    |view, window, cx| view.run(UnlockAction::Guest, window, cx),
                    cx,
                ))
                .child(self.escape_choice(
                    2,
                    t(Str::CookieUnlockSignOut),
                    t(Str::CookieUnlockSignOutHint),
                    true,
                    |view, window, cx| view.confirm_sign_out(window, cx),
                    cx,
                ))
                .into_any_element(),
        ];
        div().size_full().relative().child(crate::a11y::frame_marker()).child(lock_form(
            t(Str::CookieUnlockTitle),
            t(Str::CookieUnlockBody),
            content,
            cx,
        ))
    }
}

pub enum ProtectEvent {
    /// Sealed under the password, or skipped.
    Done,
}

/// After sign-in: seal the jar under a password, or keep the default key ("Not now").
pub struct ProtectStep {
    sealed: Arc<SealedJar>,
    tokio: Handle,
    log: Logger,
    password: Entity<InputState>,
    confirm: Entity<InputState>,
    error: Option<String>,
    busy: bool,
    focus: FocusList,
    _subscriptions: Vec<Subscription>,
}

impl EventEmitter<ProtectEvent> for ProtectStep {}

impl ProtectStep {
    pub fn new(
        sealed: Arc<SealedJar>,
        tokio: Handle,
        log: Logger,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let password = password_input(window, cx);
        let confirm = password_input(window, cx);
        password.update(cx, |input, cx| input.focus(window, cx));
        let on_password = cx.subscribe_in(&password, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
            match event {
                InputEvent::PressEnter { .. } => view.confirm.update(cx, |input, cx| input.focus(window, cx)),
                InputEvent::Change => view.error = None,
                _ => {}
            }
            cx.notify();
        });
        let on_confirm = cx.subscribe_in(&confirm, window, |view: &mut Self, _, event: &InputEvent, window, cx| {
            match event {
                InputEvent::PressEnter { .. } => view.submit(window, cx),
                InputEvent::Change => view.error = None,
                _ => {}
            }
            cx.notify();
        });
        Self {
            sealed,
            tokio,
            log: log.fork("cookies"),
            password,
            confirm,
            error: None,
            busy: false,
            focus: FocusList::new(None),
            _subscriptions: vec![on_password, on_confirm],
        }
    }

    fn submit(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let password = self.password.read(cx).value().to_string();
        let confirm = self.confirm.read(cx).value().to_string();
        if self.busy || password.is_empty() || confirm.is_empty() {
            return;
        }
        if password != confirm {
            self.error = Some(t(Str::CookieProtectMismatch).to_owned());
            cx.notify();
            return;
        }
        self.busy = true;
        let sealed = self.sealed.clone();
        let protected = self.tokio.spawn_blocking(move || sealed.protect(&password));
        cx.spawn_in(window, async move |this, cx| {
            let result =
                protected.await.unwrap_or_else(|error| Err(CookieStoreError::Io(std::io::Error::other(error))));
            this.update(cx, |view, cx| {
                match result {
                    Ok(()) => {
                        cx.emit(ProtectEvent::Done);
                        return;
                    }
                    Err(error) => {
                        view.log.capture_error("R98001 protect cookie store failed", &error);
                        view.error = Some(tf(Str::ErrorGeneric, &[&"R98001"]));
                    }
                }
                view.busy = false;
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

impl Render for ProtectStep {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let filled = !self.password.read(cx).value().is_empty() && !self.confirm.read(cx).value().is_empty();
        let submit = Button::new("protect-submit")
            .primary()
            .w_full()
            .label(t(Str::CookieProtectSubmit))
            .disabled(!filled)
            .on_click(cx.listener(|view, _, window, cx| view.submit(window, cx)));
        let skip = Button::new("protect-skip")
            .outline()
            .w_full()
            .label(t(Str::CookieProtectSkip))
            .disabled(self.busy)
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ProtectEvent::Done)));
        let content = vec![
            password_field(
                "protect-password",
                &self.password,
                t(Str::CookiePassword),
                None,
                (&self.focus, STOP_PASSWORD),
                cx,
            ),
            password_field(
                "protect-confirm",
                &self.confirm,
                t(Str::CookieProtectConfirm),
                self.error.as_deref(),
                (&self.focus, STOP_CONFIRM),
                cx,
            ),
            busy_or(self.busy, self.focus.button(0, submit, cx)),
            self.focus.button(1, skip, cx).w_full().into_any_element(),
        ];
        lock_form(t(Str::CookieProtectTitle), t(Str::CookieProtectBody), content, cx)
    }
}
