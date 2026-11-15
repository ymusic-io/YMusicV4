//! Feedback: the anonymous ids a report needs, GitHub / email with them filled in, and a message
//! sent through Sentry with the app log (JVM `FeedbackScreen.kt` / `captureFeedbackReportAction`).

use std::rc::Rc;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{InputEvent, Textarea, TextareaState};
use gpui_kit::component::{ActiveTheme, Icon, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t, tf};

use crate::a11y::{FocusList, KeyboardFocusable};
use crate::elevated_dialog::{
    ConfirmDialog, DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, text_button, title_bar,
};
use crate::graph::{AppGraph, on_core};
use crate::settings_view::HOVER_ALPHA;
use crate::telemetry::{AppLogs, CRASH_REPORTS_KEY, crash_reports_allowed, feedback_available};

const FEEDBACK_EMAIL: &str = "feedback@ymusic.io";
const FEEDBACK_ISSUE_URL: &str = "https://github.com/ymusic-io/YMusicV4/issues/new";
pub const TELEGRAM_URL: &str = "https://t.me/ymusic_io";
const DEVICE_ID_KEY: &str = "telemetry.deviceId";

/// A config-service link override, else the built-in one.
pub fn link(graph: &AppGraph, key: &str, fallback: &str) -> String {
    graph.remote.current().feature(key).and_then(|value| value.as_str()).unwrap_or(fallback).to_owned()
}

struct Info {
    device_id: String,
    app: String,
    engine: String,
    language: String,
}

impl Info {
    /// English keys whatever the UI language, so every report reads the same in triage.
    fn lines(&self) -> String {
        format!(
            "Device ID: {}\nApp: {}\nEngine: {}\nLanguage: {}",
            self.device_id, self.app, self.engine, self.language
        )
    }
}

/// Every byte but RFC 3986 unreserved; `+` stays encoded, which form decoders read as a space.
fn percent_encode(value: &str) -> String {
    value
        .bytes()
        .map(|byte| match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (byte as char).to_string(),
            other => format!("%{other:02X}"),
        })
        .collect()
}

pub struct FeedbackView {
    copied: bool,
    /// A message went out this visit.
    sent: bool,
}

impl FeedbackView {
    pub fn new() -> Self {
        Self { copied: false, sent: false }
    }

    /// The message dialog; with anonymous reports off, the consent dialog first (the report is the
    /// app log, which only leaves with reports on).
    pub fn write_message(
        &mut self,
        prefill: Option<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Option<Entity<MessageDialog>> {
        let view = cx.entity().downgrade();
        let on_sent: Rc<dyn Fn(&mut App)> = Rc::new(move |cx| {
            view.update(cx, |view, cx| {
                view.sent = true;
                cx.notify();
            })
            .ok();
        });
        if crash_reports_allowed(cx.global::<AppGraph>().settings.as_ref()) {
            return Some(MessageDialog::open(prefill, on_sent, window, cx));
        }
        ConfirmDialog::new(
            t(Str::FeedbackReportsOffTitle),
            t(Str::FeedbackReportsOffMessage),
            t(Str::FeedbackContinue),
        )
        .note(t(Str::FeedbackReportsOffNote))
        .open(window, cx, move |window, cx| {
            crate::settings_view::save_setting(CRASH_REPORTS_KEY, "true".to_owned(), cx);
            let (on_sent, prefill) = (on_sent.clone(), prefill.clone());
            window.defer(cx, move |window, cx| {
                MessageDialog::open(prefill, on_sent, window, cx);
            });
        });
        None
    }

    /// Debug route `feedback:message:<text>` / `feedback:send:<text>`: the dialog prefilled, and
    /// with `send` sent a few seconds later (reports on).
    pub fn dev_message(&mut self, text: String, send: bool, window: &mut Window, cx: &mut Context<Self>) {
        let Some(dialog) = self.write_message(Some(text), window, cx) else { return };
        if send {
            cx.spawn_in(window, async move |_, cx| {
                cx.background_executor().timer(std::time::Duration::from_secs(4)).await;
                dialog.update_in(cx, |dialog, window, cx| dialog.send(window, cx)).ok();
            })
            .detach();
        }
    }

    fn info(cx: &App) -> Info {
        let graph = cx.global::<AppGraph>();
        Info {
            device_id: graph.settings.get_raw(DEVICE_ID_KEY).ok().flatten().unwrap_or_default(),
            app: format!("rust {} ({})", crate::graph::APP_VERSION_NAME, crate::graph::APP_VERSION),
            engine: format!("{} / {}", graph.engines.backend_version(), graph.player_source.embedded().1),
            language: ym_i18n::current_locale().to_owned(),
        }
    }
}

/// "Send via Sentry": the message, with the anonymous info and app log attached; Send stays off
/// while it is blank. The send runs on after the dialog closes.
pub struct MessageDialog {
    message: Entity<TextareaState>,
    on_sent: Rc<dyn Fn(&mut App)>,
    focused: bool,
    focus: FocusList,
    _subscription: Subscription,
}

impl MessageDialog {
    fn open(
        prefill: Option<String>,
        on_sent: Rc<dyn Fn(&mut App)>,
        window: &mut Window,
        cx: &mut App,
    ) -> Entity<MessageDialog> {
        let view = cx.new(|cx| {
            let message = cx.new(|cx| {
                TextareaState::new(window, cx)
                    .placeholder(t(Str::FeedbackSentryLabel))
                    .auto_grow(4, 10)
                    .default_value(prefill.unwrap_or_default())
            });
            let subscription = cx.subscribe_in(&message, window, |_: &mut Self, _, _: &InputEvent, _, cx| cx.notify());
            Self { message, on_sent, focused: false, focus: FocusList::modal(None, cx), _subscription: subscription }
        });
        let dialog_view = view.clone();
        window.open_dialog(cx, move |dialog, window, _| {
            let submit = dialog_view.clone();
            chrome(dialog, DEFAULT_WIDTH, window).overlay_closable(false).child(dialog_view.clone()).on_ok(
                move |_, window, cx| {
                    submit.update(cx, |view, cx| view.send(window, cx));
                    false
                },
            )
        });
        view
    }

    fn trimmed(&self, cx: &App) -> String {
        self.message.read(cx).value().trim().to_owned()
    }

    fn send(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let message = self.trimmed(cx);
        if message.is_empty() {
            return;
        }
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("feedback"));
        let logs = cx.try_global::<AppLogs>().and_then(|logs| logs.0.clone());
        let failure_log = log.clone();
        let send = on_core(cx, async move {
            if let Err(error) =
                crate::telemetry::send_feedback(logs, settings, message.clone(), Some(message), log).await
            {
                failure_log.capture_error("feedback report capture failed", &error);
            }
        });
        cx.background_executor().spawn(send).detach();
        (self.on_sent)(cx);
        window.close_dialog(cx);
    }
}

impl Render for MessageDialog {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.focused {
            self.focused = true;
            let message = self.message.clone();
            window.defer(cx, move |window, cx| {
                message.update(cx, |input, cx| {
                    input.focus(window, cx);
                    let end = input.value().len();
                    input.set_selected_range(end..end, cx);
                })
            });
        }
        let theme = cx.theme().clone();
        let blank = self.trimmed(cx).is_empty();
        let buttons = h_flex()
            .child(press(
                &self.focus,
                0,
                text_button("feedback-message-cancel", t(Str::DialogCancel), false, true, cx),
                true,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(press(
                &self.focus,
                1,
                text_button("feedback-message-send", t(Str::FeedbackSentrySend), true, !blank, cx),
                !blank,
                cx.listener(|view, _, window, cx| view.send(window, cx)),
                cx,
            ));
        v_flex()
            .w_full()
            .child(title_bar(t(Str::FeedbackSentryTitle), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(ROW_HORIZONTAL))
                    .py_2()
                    .gap_2()
                    .child(
                        div().text_size(px(12.)).text_color(theme.muted_foreground).child(t(Str::FeedbackSentryLabel)),
                    )
                    .child(Textarea::new(&self.message).aria_label(t(Str::FeedbackSentryLabel)))
                    .child(
                        div()
                            .text_size(px(13.))
                            .line_height(px(18.))
                            .text_color(theme.muted_foreground)
                            .child(t(Str::FeedbackSentryNote)),
                    ),
            )
            .child(button_bar(buttons, cx))
    }
}

impl Default for FeedbackView {
    fn default() -> Self {
        Self::new()
    }
}

impl Render for FeedbackView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let info = Self::info(cx);
        let graph = cx.global::<AppGraph>();
        let email = link(graph, "feedbackEmail", FEEDBACK_EMAIL);
        let issue = link(graph, "feedbackIssueUrl", FEEDBACK_ISSUE_URL);
        let prefill = format!("\n\n---\n{}", info.lines());
        let github_url = format!(
            "{issue}{}body={}",
            if issue.contains('?') { '&' } else { '?' },
            percent_encode(&format!("\n\n---\n```\n{}\n```", info.lines()))
        );
        let mailto = format!(
            "mailto:{email}?subject={}&body={}",
            percent_encode(t(Str::FeedbackEmailSubject)),
            percent_encode(&prefill)
        );
        let viewport = window.viewport_size();
        let width = crate::feed::feed_width(window);
        let margin = if width >= 840. { 24. } else { 16. };
        let center_vertically = width >= 840. && f32::from(viewport.height) >= 600.;

        let info_row = |label: &'static str, value: String| {
            h_flex()
                .gap_4()
                .pr_3()
                .child(div().w(px(96.)).flex_shrink_0().text_sm().text_color(theme.muted_foreground).child(label))
                .child(div().flex_1().min_w_0().text_sm().child(value))
        };
        let contact = |id: &'static str, icon: Lucide, title: String, hint: &'static str, url: String| {
            h_flex()
                .id(id)
                .role(Role::Link)
                .aria_label(title.clone())
                .keyboard_focusable(theme.ring)
                .gap_4()
                .px_4()
                .py_3()
                .items_center()
                .rounded(px(12.))
                .border_1()
                .border_color(theme.border)
                .cursor_pointer()
                .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                .child(
                    div()
                        .size(px(40.))
                        .flex_shrink_0()
                        .rounded_full()
                        .bg(theme.secondary)
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(Icon::new(icon).size(px(22.))),
                )
                .child(
                    v_flex()
                        .flex_1()
                        .min_w_0()
                        .child(div().text_sm().font_medium().child(title))
                        .child(div().pt(px(2.)).text_xs().text_color(theme.muted_foreground).child(hint)),
                )
                .child(Icon::new(Lucide::ExternalLink).size(px(18.)).text_color(theme.muted_foreground))
                .on_click(move |_, _, cx| cx.open_url(&url))
        };
        let lines = info.lines();
        let info_card = v_flex()
            .gap_2()
            .pl_4()
            .pr_1()
            .pt_1()
            .pb_4()
            .rounded(px(12.))
            .bg(theme.secondary)
            .child(
                h_flex()
                    .items_center()
                    .child(div().flex_1().text_sm().font_semibold().child(t(Str::FeedbackInfoTitle)))
                    .child(
                        Button::new("copy")
                            .small()
                            .ghost()
                            .label(t(if self.copied { Str::FeedbackCopied } else { Str::FeedbackCopy }))
                            .on_click(cx.listener(move |view, _, _, cx| {
                                cx.write_to_clipboard(ClipboardItem::new_string(lines.clone()));
                                view.copied = true;
                                cx.notify();
                            })),
                    ),
            )
            .child(info_row(t(Str::FeedbackDeviceId), info.device_id.clone()))
            .child(info_row(t(Str::FeedbackApp), info.app.clone()))
            .child(info_row(t(Str::FeedbackEngine), info.engine.clone()))
            .child(info_row(t(Str::FeedbackLanguage), info.language.clone()))
            .child(div().pr_3().text_xs().text_color(theme.muted_foreground).child(t(Str::FeedbackInfoNote)));
        let column = v_flex()
            .w_full()
            .max_w(px(560.))
            .px(px(margin))
            .py_4()
            .gap_4()
            .child(div().text_sm().child(t(Str::FeedbackIntro)))
            .child(info_card)
            .child(contact(
                "github",
                Lucide::MessageCircle,
                t(Str::FeedbackGithubTitle).to_owned(),
                t(Str::FeedbackGithubHint),
                github_url,
            ))
            .child(contact(
                "email",
                Lucide::Send,
                tf(Str::FeedbackEmailTitle, &[&email]),
                t(Str::FeedbackEmailHint),
                mailto,
            ))
            .when(feedback_available(), |column| {
                column.child(h_flex().justify_center().child(if self.sent {
                    div()
                        .id("feedback-sent")
                        .role(Role::Status)
                        .aria_label(t(Str::FeedbackSentrySent))
                        .p_2()
                        .text_sm()
                        .text_color(theme.muted_foreground)
                        .child(t(Str::FeedbackSentrySent))
                        .into_any_element()
                } else {
                    div()
                        .id("feedback-sentry")
                        .role(Role::Link)
                        .aria_label(t(Str::FeedbackSentryLink))
                        .keyboard_focusable(theme.ring)
                        .p_2()
                        .rounded(px(4.))
                        .cursor_pointer()
                        .text_sm()
                        .font_semibold()
                        .text_color(theme.primary)
                        .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
                        .child(t(Str::FeedbackSentryLink))
                        .on_click(cx.listener(|view, _, window, cx| {
                            view.write_message(None, window, cx);
                        }))
                        .into_any_element()
                }))
            })
            .child(div().text_xs().text_color(theme.muted_foreground).child(t(Str::FeedbackPrefilledNote)));
        div().id("feedback-scroll").size_full().overflow_y_scroll().child(
            h_flex()
                .w_full()
                .min_h_full()
                .justify_center()
                .when(center_vertically, |row| row.items_center())
                .when(!center_vertically, |row| row.items_start())
                .child(column),
        )
    }
}
