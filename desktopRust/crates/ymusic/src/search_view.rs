//! The search layer: one per section stack, in input or results mode. Input is back and the field on
//! top; below it the saved history (empty field) or, while typing, the matching history entries above
//! the live suggestions. Results are the submitted query's feed, drawn by the Shell under its bar.

use std::time::Duration;

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::{ActiveTheme, Icon, Sizable, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_i18n::{Str, t};

use crate::a11y::{FocusList, label, list_item};
use crate::action_icon::{action_icon, auto_mirrored};
use crate::assets::MaterialIcon;
use crate::feed::FeedView;
use crate::graph::{AppGraph, on_core};
use crate::lazy_rows::LazyRows;
use crate::settings_view::HOVER_ALPHA;

const SUGGEST_DEBOUNCE: Duration = Duration::from_millis(250);
const HISTORY_KEY: &str = "search.history";
const HISTORY_LIMIT: usize = 500;
/// History rows shown above the live suggestions while typing, so a long history never buries them.
const TYPED_HISTORY_MATCHES: usize = 5;
const QUERY_ROW_ESTIMATE: f32 = 48.;

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
    submitted: Option<String>,
    editing: bool,
    results: Option<Entity<FeedView>>,
    /// The field takes focus on the next frame it shows.
    focus_input: bool,
    suggestions: Vec<String>,
    /// Newest first.
    history: Vec<String>,
    generation: u64,
    scroll: ScrollHandle,
    rows: LazyRows,
    focus: FocusList,
    /// Back and the field, then the hint under them while there is nothing to list.
    bar_focus: FocusList,
    _subscription: Subscription,
}

impl EventEmitter<SearchEvent> for SearchView {}

impl SearchView {
    pub fn new(mode: SearchMode, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let hint = match mode {
            SearchMode::YouTube => t(Str::SearchYoutube),
            SearchMode::Music => t(Str::YtmusicSearchHint),
        };
        let input = cx.new(|cx| InputState::new(window, cx).placeholder(hint));
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
        let scroll = ScrollHandle::new();
        Self {
            input,
            mode,
            submitted: None,
            editing: true,
            results: None,
            focus_input: true,
            suggestions: Vec::new(),
            history,
            generation: 0,
            rows: LazyRows::new(scroll.clone(), QUERY_ROW_ESTIMATE),
            focus: FocusList::new(Some(scroll.clone())),
            scroll,
            bar_focus: FocusList::new(None),
            _subscription: subscription,
        }
    }

    pub fn mode(&self) -> SearchMode {
        self.mode
    }

    pub fn editing(&self) -> bool {
        self.editing
    }

    pub fn submitted(&self) -> Option<&str> {
        self.submitted.as_deref()
    }

    pub fn results(&self) -> Option<&Entity<FeedView>> {
        self.results.as_ref()
    }

    /// The submitted query's results replace the input; the field keeps the query.
    pub fn show_results(
        &mut self,
        query: String,
        results: Entity<FeedView>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.editing = false;
        self.focus_input = false;
        self.input.update(cx, |input, cx| input.set_value(query.clone(), window, cx));
        self.submitted = Some(query);
        self.results = Some(results);
        self.query_changed(String::new(), cx);
    }

    /// Back to the input, which still holds the submitted query.
    pub fn edit(&mut self, cx: &mut Context<Self>) {
        self.editing = true;
        self.focus_input = true;
        let query = self.input.read(cx).value().to_string();
        self.query_changed(query, cx);
    }

    /// The input's back: to the results when there are some, else out of search.
    pub fn back(&mut self, cx: &mut Context<Self>) {
        if self.submitted.is_some() {
            self.editing = false;
            self.query_changed(String::new(), cx);
        } else {
            cx.emit(SearchEvent::Back);
        }
    }

    /// The layer came back to the top: the field takes focus again if it shows.
    pub fn raised(&mut self, cx: &mut Context<Self>) {
        self.focus_input = true;
        cx.notify();
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
        let icon = Icon::new(if history { MaterialIcon::ScheduleOutlined } else { MaterialIcon::Search }).size(px(24.));
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
                                        Icon::new(MaterialIcon::Close).text_color(theme.muted_foreground),
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
                                    Icon::new(MaterialIcon::NorthWest).text_color(theme.muted_foreground),
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
        if !self.editing || query.trim().is_empty() {
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
                if view.generation != generation {
                    return;
                }
                // Best-effort: a failed suggest leaves no list, as the JVM SearchScreen does.
                view.suggestions = match result {
                    Some(Ok(suggestions)) => suggestions,
                    Some(Err(error)) => {
                        cx.global::<AppGraph>().log.fork("search").warn("suggest failed", Some(&error));
                        Vec::new()
                    }
                    None => Vec::new(),
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

impl Render for SearchView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if std::mem::take(&mut self.focus_input) && self.editing {
            self.input.update(cx, |state, cx| state.focus(window, cx));
        }
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
                    action_icon("search-back", auto_mirrored(MaterialIcon::ArrowBack), t(Str::CdBack))
                        .on_click(cx.listener(|view, _, _, cx| view.back(cx))),
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
                .cloned()
                .collect();
            (matches, fresh)
        };
        let mut count = history.len() + suggestions.len();
        let stops = self.focus.list_stops(0..count, window, cx);
        let rows = self.rows.render(count, 0., stops.kept(), window, |index| match history.get(index) {
            Some(entry) => self.query_row(index, entry, true, cx).into_any_element(),
            None => self.query_row(index, &suggestions[index - history.len()], false, cx).into_any_element(),
        });
        let mut rows = v_flex().child(stops.wrap(rows.id("search-queries"), None, cx));
        if typed.is_empty() {
            let clear = Button::new("clear-history")
                .ghost()
                .small()
                .icon(Icon::new(MaterialIcon::DeleteOutlined))
                .label(t(Str::SearchClearHistory))
                .on_click(cx.listener(|view, _, _, cx| view.save_history(Vec::new(), cx)));
            rows = rows.child(h_flex().justify_center().py_2().child(self.focus.button(count, clear, cx)));
            count += 1;
        }
        let rows = self.focus.container(rows, 0..count, cx);
        v_flex()
            .size_full()
            .child(bar)
            .child(div().id("suggestions").flex_1().overflow_y_scroll().track_scroll(&self.scroll).child(rows))
    }
}
