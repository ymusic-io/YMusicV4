//! Search input: a pushed layer with back and the search field on top; below it the saved history
//! (empty field) or, while typing, the matching history entries above the live suggestions.

use std::time::Duration;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Icon, IconName, Sizable, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, label, list_item};
use crate::action_icon::action_icon;
use crate::graph::{AppGraph, on_core};
use crate::settings_view::HOVER_ALPHA;

const SUGGEST_DEBOUNCE: Duration = Duration::from_millis(250);
const HISTORY_KEY: &str = "search.history";
const HISTORY_LIMIT: usize = 500;
/// History rows shown above the live suggestions while typing, so a long history never buries them.
const TYPED_HISTORY_MATCHES: usize = 5;
const SHOWN_SUGGESTIONS: usize = 12;

/// Which service the field searches; the history is shared.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum SearchMode {
    YouTube,
    Music,
}

pub enum SearchEvent {
    Submit(String),
    Back,
}

pub struct SearchView {
    input: Entity<InputState>,
    mode: SearchMode,
    suggestions: Vec<String>,
    /// Newest first.
    history: Vec<String>,
    generation: u64,
    focus: FocusList,
    /// Back and the field, then the hint under them while there is nothing to list.
    bar_focus: FocusList,
    _subscription: Subscription,
}

impl EventEmitter<SearchEvent> for SearchView {}

impl SearchView {
    pub fn new(query: &str, mode: SearchMode, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let hint = match mode {
            SearchMode::YouTube => t(Str::SearchYoutube),
            SearchMode::Music => t(Str::YtmusicSearchHint),
        };
        let input = cx.new(|cx| {
            let mut state = InputState::new(window, cx).placeholder(hint);
            state.set_value(query.to_owned(), window, cx);
            state
        });
        input.update(cx, |state, cx| state.focus(window, cx));
        let subscription = cx.subscribe_in(&input, window, |view, input, event: &InputEvent, _, cx| match event {
            InputEvent::Change => view.query_changed(input.read(cx).value().to_string(), cx),
            InputEvent::PressEnter { .. } => view.submit(input.read(cx).value().to_string(), cx),
            _ => {}
        });
        let history = match cx.global::<AppGraph>().settings.string(HISTORY_KEY, "") {
            Ok(raw) => raw.split('\n').filter(|entry| !entry.trim().is_empty()).map(str::to_owned).collect(),
            Err(error) => {
                cx.global::<AppGraph>().log.warn(&format!("search history could not be read: {error}"), None);
                Vec::new()
            }
        };
        let mut view = Self {
            input,
            mode,
            suggestions: Vec::new(),
            history,
            generation: 0,
            focus: FocusList::new(None),
            bar_focus: FocusList::new(None),
            _subscription: subscription,
        };
        view.query_changed(query.to_owned(), cx);
        view
    }

    fn submit(&mut self, query: String, cx: &mut Context<Self>) {
        let query = query.trim().replace('\n', " ");
        if query.is_empty() {
            return;
        }
        let mut history = vec![query.clone()];
        history.extend(self.history.iter().filter(|entry| **entry != query).cloned());
        self.save_history(history, cx);
        cx.emit(SearchEvent::Submit(query));
    }

    fn save_history(&mut self, mut history: Vec<String>, cx: &mut Context<Self>) {
        history.truncate(HISTORY_LIMIT);
        let raw = history.join("\n");
        self.history = history;
        cx.notify();
        let graph = cx.global::<AppGraph>();
        let (settings, log) = (graph.settings.clone(), graph.log.fork("search"));
        graph.tokio.spawn_blocking(move || {
            if let Err(error) = settings.put_raw(HISTORY_KEY, raw) {
                log.warn(&format!("search history could not be saved: {error}"), None);
            }
        });
    }

    fn remove_from_history(&mut self, entry: &str, cx: &mut Context<Self>) {
        let history = self.history.iter().filter(|kept| *kept != entry).cloned().collect();
        self.save_history(history, cx);
    }

    /// One history or suggestion row: icon, text, remove (history only) and insert-into-field.
    fn query_row(&self, index: usize, text: &str, history: bool, cx: &mut Context<Self>) -> Stateful<Div> {
        let theme = cx.theme().clone();
        let (submitted, inserted, removed) = (text.to_owned(), text.to_owned(), text.to_owned());
        let icon = if history { Icon::new(Lucide::Clock) } else { Icon::new(IconName::Search) };
        self.focus
            .item(index, list_item(h_flex().id(("query", index)), index).aria_label(text.to_owned()), cx)
            .gap_4()
            .pl_4()
            .pr_2()
            .py_1()
            .cursor_pointer()
            .hover(|style| style.bg(theme.primary.opacity(HOVER_ALPHA)))
            .child(icon.text_color(theme.muted_foreground))
            .child(div().flex_1().min_w_0().truncate().text_sm().child(text.to_owned()))
            .child(
                h_flex()
                    .when(history, |actions| {
                        actions.child(
                            self.focus
                                .trailing_button(
                                    index,
                                    0,
                                    action_icon(
                                        ("remove", index),
                                        Icon::new(IconName::Close).text_color(theme.muted_foreground),
                                        t(Str::CdRemoveFromHistory),
                                    ),
                                    cx,
                                )
                                .on_click(cx.listener(move |view, _, _, cx| view.remove_from_history(&removed, cx))),
                        )
                    })
                    .child(
                        self.focus
                            .trailing_button(
                                index,
                                1,
                                action_icon(
                                    ("insert", index),
                                    Icon::new(Lucide::ArrowUpLeft).text_color(theme.muted_foreground),
                                    t(Str::CdInsertQuery),
                                ),
                                cx,
                            )
                            .on_click(cx.listener(move |view, _, window, cx| {
                                view.input.update(cx, |input, cx| input.set_value(inserted.clone(), window, cx));
                            })),
                    ),
            )
            .on_click(cx.listener(move |view, _, _, cx| view.submit(submitted.clone(), cx)))
    }

    fn query_changed(&mut self, query: String, cx: &mut Context<Self>) {
        self.generation += 1;
        let generation = self.generation;
        cx.notify();
        if query.trim().is_empty() {
            self.suggestions.clear();
            return;
        }
        let graph = cx.global::<AppGraph>();
        let (engine, log, mode) = (graph.engine(), graph.log.fork("search"), self.mode);
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            executor.timer(SUGGEST_DEBOUNCE).await;
            if this.read_with(cx, |view, _| view.generation != generation).unwrap_or(true) {
                return;
            }
            let fetch = cx.update(|cx| {
                on_core(cx, async move {
                    match mode {
                        SearchMode::YouTube => engine.search_suggestions(&query, &log).await,
                        SearchMode::Music => engine.music_search_suggestions(&query, &log).await,
                    }
                })
            });
            let result = fetch.await;
            this.update(cx, |view, cx| {
                if view.generation == generation
                    && let Some(Ok(suggestions)) = result
                {
                    view.suggestions = suggestions;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }
}

impl Render for SearchView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let bar = h_flex()
            .gap_2()
            .px_2()
            .pt(px(2.))
            .pb(px(3.))
            .border_b_1()
            .border_color(theme.border)
            .child(
                self.bar_focus.button(
                    0,
                    action_icon("search-back", IconName::ArrowLeft, t(Str::CdBack))
                        .on_click(cx.listener(|_, _, _, cx| cx.emit(SearchEvent::Back))),
                    cx,
                ),
            )
            .child(
                self.bar_focus
                    .field(1, &self.input.focus_handle(cx), Input::new(&self.input).cleanable(true), cx)
                    .flex_1(),
            );
        let typed = self.input.read(cx).value().trim().to_owned();
        if typed.is_empty() && self.history.is_empty() {
            let hint = div()
                .flex_1()
                .flex()
                .items_center()
                .justify_center()
                .child(label("search-hint", t(Str::SearchTypeToSearch)).text_color(theme.muted_foreground));
            return v_flex().size_full().child(bar).child(hint);
        }
        let (history, suggestions): (Vec<String>, Vec<String>) = if typed.is_empty() {
            (self.history.clone(), Vec::new())
        } else {
            let needle = typed.to_lowercase();
            let matches: Vec<String> = self
                .history
                .iter()
                .filter(|entry| entry.to_lowercase().contains(&needle))
                .take(TYPED_HISTORY_MATCHES)
                .cloned()
                .collect();
            let fresh = self
                .suggestions
                .iter()
                .filter(|suggestion| !matches.iter().any(|entry| entry.to_lowercase() == suggestion.to_lowercase()))
                .take(SHOWN_SUGGESTIONS)
                .cloned()
                .collect();
            (matches, fresh)
        };
        let stops = self.focus.list_stops(0..history.len() + suggestions.len(), window, cx);
        let mut rows = v_flex().id("search-queries");
        let mut count = 0;
        for entry in &history {
            rows = rows.child(self.query_row(count, entry, true, cx));
            count += 1;
        }
        for suggestion in &suggestions {
            rows = rows.child(self.query_row(count, suggestion, false, cx));
            count += 1;
        }
        let mut rows = v_flex().child(stops.wrap(rows, None, cx));
        if typed.is_empty() {
            let clear = Button::new("clear-history")
                .ghost()
                .small()
                .icon(Icon::new(Lucide::Trash))
                .label(t(Str::SearchClearHistory))
                .on_click(cx.listener(|view, _, _, cx| view.save_history(Vec::new(), cx)));
            rows = rows.child(h_flex().justify_center().py_2().child(self.focus.button(count, clear, cx)));
            count += 1;
        }
        let rows = self.focus.container(rows, 0..count, cx);
        v_flex().size_full().child(bar).child(div().id("suggestions").flex_1().overflow_y_scroll().child(rows))
    }
}
