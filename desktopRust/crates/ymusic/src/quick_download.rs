//! The Quick download preset: picks saved from the options dialog (or Settings), applied to any
//! video without asking. Languages, not tracks: each download resolves them against its own player
//! response. Shared with the JVM app through `download.quickPreset`.

use std::rc::Rc;

use gpui_kit::component::button::Button;
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Sizable, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::*;
use serde_json::{Map, Value, json};
use ym_download::{AudioQuality, DownloadFormat, DownloadRequest, names};
use ym_engine::yt::YtLocaleOption;
use ym_i18n::{Str, t, tf};

use crate::a11y::FocusList;
use crate::download_dialog::{DownloadTarget, format_name, quality_name};
use crate::downloads::{offline_ids, usable_download_folder};
use crate::elevated_dialog::{DEFAULT_WIDTH, ROW_HORIZONTAL, button_bar, chrome, press, text_button, title_bar};
use crate::graph::{AppGraph, on_core};

pub const QUICK_PRESET_KEY: &str = "download.quickPreset";
const PRESET_HEIGHTS: [i32; 6] = [2160, 1440, 1080, 720, 480, 360];

#[derive(Clone, Debug, PartialEq)]
pub struct QuickPreset {
    pub format: DownloadFormat,
    pub height: Option<i32>,
    pub audio_language: Option<String>,
    /// None = captions off.
    pub caption_language: Option<String>,
    pub audio_quality: AudioQuality,
}

impl QuickPreset {
    /// Opus when ffmpeg can write it, else M4A; the default audio track; captions in the app language.
    pub fn default(ffmpeg_available: bool) -> Self {
        Self {
            format: if ffmpeg_available { DownloadFormat::AudioWebm } else { DownloadFormat::M4a },
            height: None,
            audio_language: None,
            caption_language: ym_i18n::current_locale().split('-').next().map(str::to_owned),
            audio_quality: AudioQuality::Best,
        }
    }

    /// The default when never saved or when the saved format is unknown to this build.
    pub fn decode(raw: &str, ffmpeg_available: bool) -> Self {
        let Ok(Value::Object(json)) = serde_json::from_str::<Value>(raw) else {
            return Self::default(ffmpeg_available);
        };
        let text = |key: &str| json.get(key).and_then(Value::as_str).map(str::to_owned);
        let Some(format) = text("format").as_deref().and_then(DownloadFormat::from_name) else {
            return Self::default(ffmpeg_available);
        };
        Self {
            format,
            height: json.get("height").and_then(Value::as_i64).map(|height| height as i32),
            audio_language: text("audioLanguage"),
            caption_language: text("captionLanguage"),
            audio_quality: text("audioQuality")
                .as_deref()
                .and_then(AudioQuality::from_name)
                .unwrap_or(AudioQuality::Best),
        }
    }

    pub fn encode(&self) -> String {
        let mut json = Map::new();
        json.insert("format".into(), json!(self.format.name()));
        json.insert("audioQuality".into(), json!(self.audio_quality.name()));
        if let Some(height) = self.height {
            json.insert("height".into(), json!(height));
        }
        if let Some(language) = &self.audio_language {
            json.insert("audioLanguage".into(), json!(language));
        }
        if let Some(language) = &self.caption_language {
            json.insert("captionLanguage".into(), json!(language));
        }
        Value::Object(json).to_string()
    }

    /// The download of `target` with these picks; `prefix` starts the file name ("1. ").
    pub fn request(&self, target: &DownloadTarget, prefix: &str) -> DownloadRequest {
        let file_name = names::from_title(&format!("{prefix}{}", target.title));
        DownloadRequest {
            video_id: target.video_id.clone(),
            title: target.title.clone(),
            artist: None,
            thumbnail_url: target.thumbnail_url.clone(),
            format: self.format,
            requested_height: if self.format.is_video() { self.height } else { None },
            file_name: (!file_name.is_empty()).then_some(file_name),
            audio_track_id: None,
            captions: self.caption_language.is_none().then(Vec::new),
            audio_language: self.audio_language.clone(),
            caption_language: self.caption_language.clone(),
            audio_quality: self.audio_quality,
        }
    }

    /// "MP3", "MP4 · 1080p": the menu entry's detail.
    pub fn short_label(&self) -> String {
        match self.height.filter(|_| self.format.is_video()) {
            Some(height) => format!("{} · {}", format_name(self.format), height_label(height)),
            None => format_name(self.format).to_owned(),
        }
    }

    /// "M4A · Audio only", "MP4 · 1080p": the format field and the Settings row.
    pub fn label(&self) -> String {
        if self.format.is_video() {
            format!("{} · {}", format_name(self.format), height_label(self.height.unwrap_or(1080)))
        } else {
            format!("{} · {}", format_name(self.format), t(Str::DownloadAudioOnly))
        }
    }
}

fn height_label(height: i32) -> String {
    match height {
        4320.. => "8K".to_owned(),
        2160.. => "4K".to_owned(),
        _ => format!("{height}p"),
    }
}

/// The saved preset. Blocking (reads `app.db`), like the other settings reads on the UI thread.
pub fn saved_preset(cx: &App) -> QuickPreset {
    let graph = cx.global::<AppGraph>();
    let raw = graph.settings.get_raw(QUICK_PRESET_KEY).ok().flatten().unwrap_or_default();
    QuickPreset::decode(&raw, graph.downloads.ffmpeg_available())
}

pub fn save_preset(preset: &QuickPreset, cx: &App) -> tokio::task::JoinHandle<()> {
    let graph = cx.global::<AppGraph>();
    let (settings, log, raw) = (graph.settings.clone(), graph.log.fork("quick-download"), preset.encode());
    graph.tokio.spawn_blocking(move || {
        if let Err(error) = settings.put_raw(QUICK_PRESET_KEY, raw) {
            log.warn(&format!("the Quick download preset could not be saved: {error}"), None);
        }
    })
}

/// YouTube's own language menu, labelled in the app language; empty when it fails (Default / Off
/// stay offered).
pub fn load_languages(cx: &App) -> impl Future<Output = Vec<YtLocaleOption>> + use<> {
    let graph = cx.global::<AppGraph>();
    let (engine, log) = (graph.engine(), graph.log.fork("quick-preset"));
    let load = on_core(cx, async move {
        match engine.locale_options(&log).await {
            Ok(locales) => locales.languages,
            Err(error) => {
                log.warn("language list failed; offering Default / Off only", Some(&error));
                Vec::new()
            }
        }
    });
    async move { load.await.unwrap_or_default() }
}

fn language_name(code: Option<&str>, languages: &[YtLocaleOption]) -> Option<String> {
    let code = code?;
    Some(
        languages
            .iter()
            .find(|language| language.code == code)
            .map_or(code.to_owned(), |language| language.label.clone()),
    )
}

pub type PresetChange = Rc<dyn Fn(QuickPreset, &mut Window, &mut App)>;

/// One labelled dropdown row.
pub fn option_line(label: &'static str, control: impl IntoElement, cx: &App) -> Div {
    h_flex()
        .gap_4()
        .items_center()
        .child(
            div().w(px(112.)).flex_shrink_0().text_sm().font_semibold().text_color(cx.theme().foreground).child(label),
        )
        .child(div().flex_1().min_w_0().child(control))
}

/// A dropdown field that is `focus`'s `index`th stop; wrap the menu with [`FocusList::wrap`].
pub fn dropdown(id: &'static str, label: &str, value: String, focus: &FocusList, index: usize, cx: &App) -> Button {
    Button::new(id)
        .small()
        .outline()
        .w_full()
        .label(value.clone())
        .accessibility_label(format!("{label}, {value}"))
        .track_focus(&focus.handle(index, cx))
}

/// Format / quality / audio language / captions pickers for `preset`; captions need ffmpeg to embed.
/// `focus` gives each picker a stop from `first` on.
pub fn preset_fields(
    preset: &QuickPreset,
    languages: &[YtLocaleOption],
    ffmpeg_available: bool,
    on_change: PresetChange,
    focus: &FocusList,
    first: usize,
    cx: &App,
) -> Div {
    let mut column = v_flex().gap_2();
    let audio_formats: &[DownloadFormat] = if ffmpeg_available {
        &[DownloadFormat::AudioWebm, DownloadFormat::M4a, DownloadFormat::Mp3]
    } else {
        &[DownloadFormat::M4a]
    };
    let video_formats: &[DownloadFormat] =
        if ffmpeg_available { &[DownloadFormat::VideoMp4, DownloadFormat::VideoWebm] } else { &[] };

    let mut index = first;
    let (current, change) = (preset.clone(), on_change.clone());
    let format_menu = dropdown("preset-format", t(Str::DownloadFormat), preset.label(), focus, index, cx)
        .dropdown_menu(move |mut menu, _, _| {
            menu = menu.scrollable(true).max_h(px(360.)).label(t(Str::DownloadAudioOnly));
            for format in audio_formats {
                let (picked, change) = (QuickPreset { format: *format, ..current.clone() }, change.clone());
                menu = menu.item(
                    PopupMenuItem::new(format_name(*format))
                        .checked(current.format == *format)
                        .on_click(move |_, window, cx| change(picked.clone(), window, cx)),
                );
            }
            for container in video_formats {
                menu = menu.label(format!("{} · {}", t(Str::DownloadVideo), format_name(*container)));
                for height in PRESET_HEIGHTS {
                    let picked = QuickPreset { format: *container, height: Some(height), ..current.clone() };
                    let change = change.clone();
                    menu = menu.item(
                        PopupMenuItem::new(height_label(height))
                            .checked(current.format == *container && current.height == Some(height))
                            .on_click(move |_, window, cx| change(picked.clone(), window, cx)),
                    );
                }
            }
            menu
        });
    column = column.child(option_line(t(Str::DownloadFormat), focus.wrap(index, format_menu, cx), cx));
    index += 1;

    if !preset.format.is_video() {
        let (current, change) = (preset.clone(), on_change.clone());
        let shown = quality_name(preset.audio_quality).to_owned();
        let quality = dropdown("preset-quality", t(Str::DownloadQuality), shown, focus, index, cx).dropdown_menu(
            move |mut menu, _, _| {
                for quality in AudioQuality::ALL.into_iter().rev() {
                    let (picked, change) = (QuickPreset { audio_quality: quality, ..current.clone() }, change.clone());
                    menu = menu.item(
                        PopupMenuItem::new(quality_name(quality))
                            .checked(current.audio_quality == quality)
                            .on_click(move |_, window, cx| change(picked.clone(), window, cx)),
                    );
                }
                menu
            },
        );
        column = column.child(option_line(t(Str::DownloadQuality), focus.wrap(index, quality, cx), cx));
    }
    index += 1;

    let language_menu = |id: &'static str,
                         label: &'static str,
                         none_label: &'static str,
                         value: Option<&str>,
                         index: usize,
                         apply: fn(&QuickPreset, Option<String>) -> QuickPreset| {
        let (current, change, options) = (preset.clone(), on_change.clone(), languages.to_vec());
        let shown = language_name(value, languages).unwrap_or_else(|| none_label.to_owned());
        let selected = value.map(str::to_owned);
        dropdown(id, label, shown, focus, index, cx).dropdown_menu(move |mut menu, _, _| {
            menu = menu.scrollable(true).max_h(px(360.));
            let (picked, on_pick) = (apply(&current, None), change.clone());
            menu = menu.item(
                PopupMenuItem::new(none_label)
                    .checked(selected.is_none())
                    .on_click(move |_, window, cx| on_pick(picked.clone(), window, cx)),
            );
            for language in &options {
                let (picked, on_pick) = (apply(&current, Some(language.code.clone())), change.clone());
                menu = menu.item(
                    PopupMenuItem::new(language.label.clone())
                        .checked(selected.as_deref() == Some(language.code.as_str()))
                        .on_click(move |_, window, cx| on_pick(picked.clone(), window, cx)),
                );
            }
            menu
        })
    };
    let audio = language_menu(
        "preset-audio",
        t(Str::DownloadAudioLanguage),
        t(Str::DownloadDefaultTrack),
        preset.audio_language.as_deref(),
        index,
        |preset, language| QuickPreset { audio_language: language, ..preset.clone() },
    );
    column = column.child(option_line(t(Str::DownloadAudioLanguage), focus.wrap(index, audio, cx), cx));
    index += 1;
    if ffmpeg_available {
        let captions = language_menu(
            "preset-captions",
            t(Str::DownloadCaptions),
            t(Str::DownloadCaptionsOff),
            preset.caption_language.as_deref(),
            index,
            |preset, language| QuickPreset { caption_language: language, ..preset.clone() },
        );
        column = column.child(option_line(t(Str::DownloadCaptions), focus.wrap(index, captions, cx), cx));
    }
    column
}

/// How many focus stops [`preset_fields`] takes.
pub const PRESET_FIELD_STOPS: usize = 4;

/// Asks before downloading a video the Library already holds; a confirmed re-download is saved as
/// a new file (`name_1.ext`), never over the old one.
pub fn confirm_redownload(
    title: &str,
    window: &mut Window,
    cx: &mut App,
    on_confirm: impl Fn(&mut Window, &mut App) + 'static,
) {
    let message = tf(Str::RedownloadMessage, &[&title]);
    crate::elevated_dialog::confirm(
        t(Str::RedownloadTitle),
        message,
        t(Str::RedownloadConfirm),
        window,
        cx,
        on_confirm,
    );
}

/// Downloads `target` with the saved preset, no options dialog: straight into the download folder
/// (the picker first when there is none; a cancel drops it), after asking if it is already saved.
pub fn launch(target: DownloadTarget, window: &mut Window, cx: &mut App) {
    let offline = offline_ids(cx);
    window
        .spawn(cx, async move |cx| {
            if !offline.await.contains(&target.video_id) {
                cx.update(|window, cx| enqueue(target, window, cx)).ok();
                return;
            }
            cx.update(|window, cx| {
                let title = target.title.clone();
                confirm_redownload(&title, window, cx, move |window, cx| enqueue(target.clone(), window, cx));
            })
            .ok();
        })
        .detach();
}

fn enqueue(target: DownloadTarget, window: &mut Window, cx: &mut App) {
    let folder = usable_download_folder(window, cx);
    window
        .spawn(cx, async move |cx| {
            if folder.await.is_none() {
                return;
            }
            cx.update(|window, cx| {
                let preset = saved_preset(cx);
                cx.global::<AppGraph>().downloads.enqueue(preset.request(&target, ""));
                window.push_notification(t(Str::DownloadAdded), cx);
            })
            .ok();
        })
        .detach();
}

/// Settings → Quick download: edits a draft of the saved preset; Save keeps it.
pub struct QuickPresetEditor {
    draft: QuickPreset,
    languages: Vec<YtLocaleOption>,
    focus: FocusList,
}

const STOP_CANCEL: usize = PRESET_FIELD_STOPS;
const STOP_SAVE: usize = STOP_CANCEL + 1;

impl QuickPresetEditor {
    pub fn open(window: &mut Window, cx: &mut App) {
        let view = cx.new(|cx| {
            let languages = load_languages(cx);
            cx.spawn(async move |this, cx| {
                let languages = languages.await;
                this.update(cx, |view: &mut Self, cx| {
                    view.languages = languages;
                    cx.notify();
                })
                .ok();
            })
            .detach();
            Self { draft: saved_preset(cx), languages: Vec::new(), focus: FocusList::modal(None, cx) }
        });
        window.open_dialog(cx, move |dialog, window, _| {
            chrome(dialog, DEFAULT_WIDTH, window).on_ok(|_, _, _| false).child(view.clone())
        });
    }

    fn save(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let write = save_preset(&self.draft, cx);
        window.close_dialog(cx);
        cx.spawn(async move |_, cx| {
            write.await.ok();
            cx.update(|cx| cx.refresh_windows());
        })
        .detach();
    }
}

impl Render for QuickPresetEditor {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let view = cx.entity().downgrade();
        let on_change: PresetChange = Rc::new(move |preset, _, cx| {
            view.update(cx, |view, cx| {
                view.draft = preset;
                cx.notify();
            })
            .ok();
        });
        let ffmpeg = cx.global::<AppGraph>().downloads.ffmpeg_available();
        let buttons = h_flex()
            .child(press(
                &self.focus,
                STOP_CANCEL,
                text_button("quick-cancel", t(Str::DialogCancel), false, true, cx),
                true,
                |_, window, cx| window.close_dialog(cx),
                cx,
            ))
            .child(press(
                &self.focus,
                STOP_SAVE,
                text_button("quick-save", t(Str::DownloadSave), true, true, cx),
                true,
                cx.listener(|view, _, window, cx| view.save(window, cx)),
                cx,
            ));
        let fields = preset_fields(&self.draft, &self.languages, ffmpeg, on_change, &self.focus, 0, cx);
        v_flex()
            .w_full()
            .child(title_bar(t(Str::SettingsQuickDownload), cx))
            .child(v_flex().w_full().px(px(ROW_HORIZONTAL)).py_4().gap_2().child(fields))
            .child(button_bar(buttons, cx))
    }
}
