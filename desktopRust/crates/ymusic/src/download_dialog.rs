//! The download options dialog (the JVM app's `DownloadDialogDesktop`, and its `DownloadSheet` in a
//! window too small for two panes): what a video can be saved as (audio formats and tiers, video
//! heights and containers, audio track, captions), its file name, where it goes, and its size.

use std::cell::Cell;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Mutex;

use gpui_kit::assets::IconName as Lucide;
use gpui_kit::base::actions::Confirm;
use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::checkbox::Checkbox;
use gpui_kit::component::dialog::Dialog;
use gpui_kit::component::input::{Input, InputEvent, InputState};
use gpui_kit::component::menu::{DropdownMenu, PopupMenu, PopupMenuItem};
use gpui_kit::component::{ActiveTheme, Colorize, Disableable, Icon, IconName, StyledExt, WindowExt, h_flex, v_flex};
use gpui_kit::prelude::FluentBuilder;
use gpui_kit::*;
use ym_download::options::{CaptionOption, DownloadOptions, VideoOption};
use ym_download::{AudioQuality, DownloadError, DownloadFormat, DownloadRequest, DownloadService, names};
use ym_i18n::{Str, t, tf};

use crate::a11y::FocusList;
use crate::action_icon::action_icon;
use crate::downloads::{download_folder, offline_ids, pick_download_folder, usable_download_folder};
use crate::downloads_view::{Playability, playability};
use crate::elevated_dialog::{self, body_max_height, button_bar, chrome, scroll_shadows, text_button, title_bar};
use crate::graph::{AppGraph, on_core};
use crate::quick_download::{QuickPreset, confirm_redownload, save_preset, saved_preset};

/// The video to download, as the card showed it.
#[derive(Clone)]
pub struct DownloadTarget {
    pub video_id: String,
    pub title: String,
    pub artist: Option<String>,
    pub thumbnail_url: Option<String>,
}

impl DownloadTarget {
    pub fn from_feed(item: &ym_engine::yt::YtFeedItem) -> Self {
        Self {
            video_id: item.id.clone(),
            title: item.title.clone(),
            artist: item.channel_title.clone().or_else(|| item.subtitle_line1.clone()),
            thumbnail_url: item.thumbs.last().map(|thumb| {
                if thumb.url.starts_with("//") { format!("https:{}", thumb.url) } else { thumb.url.clone() }
            }),
        }
    }
}

/// The last picks, so the next dialog opens where this one left off (for the app session).
struct LastChoice {
    audio_only: bool,
    audio_format: DownloadFormat,
    audio_quality: AudioQuality,
    container: DownloadFormat,
    height: i32,
}

static LAST_CHOICE: Mutex<LastChoice> = Mutex::new(LastChoice {
    audio_only: true,
    audio_format: DownloadFormat::M4a,
    audio_quality: AudioQuality::Best,
    container: DownloadFormat::VideoMp4,
    height: 1080,
});

/// The two-pane dialog needs this much window; anything smaller gets the sheet.
const DIALOG_MIN_WINDOW_WIDTH: f32 = 750.;
const DIALOG_MIN_WINDOW_HEIGHT: f32 = 600.;
const DIALOG_MAX_WIDTH: f32 = 880.;
const SHEET_MAX_WIDTH: f32 = 640.;
const SHEET_HANDLE_HEIGHT: f32 = 48.;
/// The dialog layer keeps this much of the window clear on each side.
const SHEET_MARGIN: f32 = 16.;
const LEFT_PANE_WIDTH: f32 = 272.;
const BODY_MIN_HEIGHT: f32 = 360.;
const QUALITY_COLUMNS: usize = 3;
/// More qualities than this collapse behind a "+N more" tile.
const QUALITY_COLLAPSE_OVER: usize = 6;

// Focus stops, in reading order.
const STOP_LOCATION: usize = 0;
const STOP_FORMAT: usize = 1;
const STOP_QUALITY: usize = 2;
const STOP_AUDIO: usize = 3;
const STOP_CAPTIONS: usize = 4;
const STOP_CONTAINER: usize = 5;
const STOP_RETRY: usize = 8;
const STOP_CANCEL: usize = 9;
const STOP_DOWNLOAD: usize = 10;
const STOP_RENAME: usize = 11;
const STOP_KIND: usize = 12;
const STOP_TILES: usize = 20;
const STOP_AUDIO_CARDS: usize = 60;

fn is_narrow(window: &Window) -> bool {
    let viewport = window.viewport_size();
    f32::from(viewport.width) < DIALOG_MIN_WINDOW_WIDTH || f32::from(viewport.height) < DIALOG_MIN_WINDOW_HEIGHT
}

enum FailAction {
    Retry,
    SignIn,
    None,
}

enum Phase {
    Loading,
    Ready(Box<DownloadOptions>),
    Live,
    Failed(String, FailAction),
}

pub struct DownloadPicker {
    target: DownloadTarget,
    service: DownloadService,
    phase: Phase,
    audio_only: bool,
    audio_format: DownloadFormat,
    quality: AudioQuality,
    height: Option<i32>,
    container: DownloadFormat,
    /// The one caption track to include ([`CaptionOption::key`]); none = no captions.
    caption: Option<String>,
    /// The Quick download caption language the picks start from; none starts with captions off.
    preferred_caption: Option<String>,
    folder: Option<PathBuf>,
    already_downloaded: bool,
    /// Also keep these picks as the Quick download preset; unticked on every open, by design.
    save_as_quick: bool,
    /// The `audioTrack.id` to download; none = the default track (or a video without dubs).
    audio_track: Option<String>,
    file_name: Entity<InputState>,
    /// The last edit dropped a character no file name may hold.
    stripped: bool,
    /// The next change is the stripped value written back, not an edit.
    stripping: bool,
    all_qualities: bool,
    /// The sheet column's natural height, measured as it paints; the sheet is sized and placed by it.
    sheet_content: Rc<Cell<f32>>,
    focus: FocusList,
    scroll: ScrollHandle,
    _name_changes: Subscription,
}

impl DownloadPicker {
    /// Opens the dialog for `target`, after the folder picker when there is no usable download folder
    /// (a cancel opens nothing).
    pub fn open(target: DownloadTarget, window: &mut Window, cx: &mut App) {
        let folder = usable_download_folder(window, cx);
        window
            .spawn(cx, async move |cx| {
                if folder.await.is_some() {
                    cx.update(|window, cx| Self::show(target, window, cx)).ok();
                }
            })
            .detach();
    }

    fn show(target: DownloadTarget, window: &mut Window, cx: &mut App) {
        let picker = cx.new(|cx| DownloadPicker::new(target, window, cx));
        window.open_dialog(cx, move |dialog, window, cx| {
            let dialog = if is_narrow(window) {
                let height = picker.read(cx).sheet_height(window);
                sheet_chrome(dialog, height, window)
            } else {
                chrome(dialog, DIALOG_MAX_WIDTH, window)
            };
            // Enter belongs to the focused control; on the dialog itself it neither closes nor downloads.
            dialog.on_ok(|_, _, _| false).child(picker.clone())
        });
    }

    fn new(target: DownloadTarget, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let stem = Some(names::from_title(&target.title)).filter(|stem| !stem.is_empty());
        let stem = stem.unwrap_or_else(|| target.video_id.clone());
        let file_name = cx.new(|cx| InputState::new(window, cx).default_value(stem));
        let name_changes = cx.subscribe_in(&file_name, window, |picker: &mut Self, input, event, window, cx| {
            if !matches!(event, InputEvent::Change) {
                return;
            }
            let raw = input.read(cx).value().to_string();
            if names::has_forbidden(&raw) {
                let clean = names::strip_forbidden(&raw);
                input.update(cx, |input, cx| input.set_value(clean, window, cx));
                picker.stripped = true;
                picker.stripping = true;
            } else if !std::mem::take(&mut picker.stripping) {
                picker.stripped = false;
            }
            cx.notify();
        });
        let graph = cx.global::<AppGraph>();
        let service = graph.downloads.clone();
        let folder = download_folder(graph.settings.as_ref());
        let offline = offline_ids(cx);
        let offline_id = target.video_id.clone();
        cx.spawn(async move |this, cx| {
            let downloaded = offline.await.contains(&offline_id);
            this.update(cx, |picker, _| picker.already_downloaded = downloaded).ok();
        })
        .detach();
        let preferred_caption = saved_preset(cx).caption_language;
        let last = LAST_CHOICE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let scroll = ScrollHandle::new();
        let mut picker = Self {
            target,
            service,
            phase: Phase::Loading,
            audio_only: last.audio_only,
            audio_format: last.audio_format,
            quality: last.audio_quality,
            height: None,
            container: last.container,
            caption: None,
            preferred_caption,
            folder,
            already_downloaded: false,
            save_as_quick: false,
            audio_track: None,
            file_name,
            stripped: false,
            stripping: false,
            all_qualities: false,
            sheet_content: Rc::default(),
            focus: FocusList::modal(Some(scroll.clone()), cx),
            scroll,
            _name_changes: name_changes,
        };
        drop(last);
        picker.load(cx);
        picker
    }

    fn load(&mut self, cx: &mut Context<Self>) {
        self.phase = Phase::Loading;
        let service = self.service.clone();
        let video_id = self.target.video_id.clone();
        let probe = on_core(cx, async move { service.probe(&video_id).await });
        cx.spawn(async move |this, cx| {
            let result = probe.await;
            this.update(cx, |picker, cx| {
                picker.phase = match result {
                    Some(Ok(options)) => {
                        picker.pick_defaults(&options);
                        Phase::Ready(Box::new(options))
                    }
                    Some(Err(DownloadError::Live(_))) => Phase::Live,
                    Some(Err(error)) => {
                        let (message, action) = picker.failure(&error, cx);
                        Phase::Failed(message, action)
                    }
                    None => return,
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn failure(&self, error: &DownloadError, cx: &App) -> (String, FailAction) {
        let graph = cx.global::<AppGraph>();
        let code = error.code();
        match playability(&code) {
            Some(Playability::SignInRequired) => (
                t(Str::PlaybackSignInRequired).to_owned(),
                if graph.guest { FailAction::Retry } else { FailAction::SignIn },
            ),
            // YouTube's verdict, not a fault: a re-probe gets the same answer.
            Some(Playability::Unavailable) => (t(Str::PlaybackUnavailable).to_owned(), FailAction::None),
            None => {
                let message = match error {
                    DownloadError::StillProcessing(_) => t(Str::ErrorLiveStillProcessing).to_owned(),
                    DownloadError::Network(_) => t(Str::ErrorNetwork).to_owned(),
                    DownloadError::Engine(engine) if engine.kind == ym_engine::ErrorKind::Network => {
                        t(Str::ErrorNetwork).to_owned()
                    }
                    _ if code == "ERROR" => t(Str::PlaybackRetryLater).to_owned(),
                    _ => {
                        let mut props = serde_json::Map::new();
                        props.insert("videoId".into(), self.target.video_id.clone().into());
                        graph.log.fork_with("download-options", props).warn("probe failed", Some(error));
                        tf(Str::DownloadFailed, &[&code])
                    }
                };
                (message, FailAction::Retry)
            }
        }
    }

    fn pick_defaults(&mut self, options: &DownloadOptions) {
        if !options.audio.iter().any(|option| option.format == self.audio_format)
            && let Some(first) = options.audio.first()
        {
            self.audio_format = first.format;
        }
        let preferred = LAST_CHOICE.lock().map_or(1080, |last| last.height);
        self.height = options
            .video
            .iter()
            .find(|option| option.height <= preferred)
            .or(options.video.last())
            .map(|option| option.height);
        self.audio_track = options
            .audio_tracks
            .iter()
            .find(|track| track.is_default)
            .or(options.audio_tracks.first())
            .map(|track| track.id.clone());
        self.caption = self.default_caption(&options.captions);
    }

    /// What Quick download would take: the video's own track in the preferred language (exact or
    /// base language, uploaded before auto-generated), else its default track; never a translation.
    fn default_caption(&self, tracks: &[CaptionOption]) -> Option<String> {
        let language = self.preferred_caption.as_deref()?;
        let base = |code: &str| code.split('-').next().unwrap_or(code).to_ascii_lowercase();
        let own: Vec<&CaptionOption> = tracks.iter().filter(|track| !track.is_translated).collect();
        let wanted: Vec<&&CaptionOption> = own
            .iter()
            .filter(|track| {
                track.language_code.eq_ignore_ascii_case(language) || base(&track.language_code) == base(language)
            })
            .collect();
        wanted
            .iter()
            .find(|track| !track.is_asr)
            .or(wanted.first())
            .map(|track| **track)
            .or_else(|| own.iter().find(|track| track.is_default).copied())
            .map(|track| track.key())
    }

    fn options(&self) -> Option<&DownloadOptions> {
        match &self.phase {
            Phase::Ready(options) => Some(options),
            _ => None,
        }
    }

    fn has_video(&self) -> bool {
        self.options().is_some_and(|options| !options.video.is_empty())
    }

    fn containers(&self) -> Vec<DownloadFormat> {
        if self.options().is_some_and(|options| options.video.iter().any(|option| option.webm_bytes.is_some())) {
            vec![DownloadFormat::VideoMp4, DownloadFormat::VideoWebm]
        } else {
            vec![DownloadFormat::VideoMp4]
        }
    }

    fn format(&self) -> DownloadFormat {
        if self.audio_only || !self.has_video() {
            self.audio_format
        } else if self.containers().contains(&self.container) {
            self.container
        } else {
            DownloadFormat::VideoMp4
        }
    }

    fn captions_supported(&self) -> bool {
        self.service.ffmpeg_available()
    }

    fn selected_audio_quality(&self) -> Option<(AudioQuality, i64)> {
        let option = self.options()?.audio.iter().find(|option| option.format == self.audio_format)?;
        option.pick(self.quality).map(|picked| (picked.quality, picked.bytes))
    }

    fn shows_audio_quality(&self) -> bool {
        !self.format().is_video()
            && self
                .options()
                .is_some_and(|options| options.audio.iter().any(|option| option.format == self.audio_format))
    }

    fn selected_video(&self) -> Option<&VideoOption> {
        self.options()?.video.iter().find(|option| Some(option.height) == self.height)
    }

    fn selected_bytes(&self) -> Option<i64> {
        let format = self.format();
        if format.is_video() {
            let video = self.selected_video()?;
            if format == DownloadFormat::VideoWebm { video.webm_bytes } else { Some(video.mp4_bytes) }
        } else {
            self.options()?.audio_bytes(format, self.quality)
        }
    }

    fn finalized_name(&self, cx: &App) -> String {
        names::finalize(&self.file_name.read(cx).value())
    }

    fn ready(&self, cx: &App) -> bool {
        self.options().is_some() && !self.finalized_name(cx).is_empty()
    }

    fn choose_folder(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let pick = pick_download_folder(window, cx);
        cx.spawn(async move |this, cx| {
            let Some(folder) = pick.await else { return };
            this.update(cx, |picker, cx| {
                picker.folder = Some(folder);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn sign_in(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        window.close_dialog(cx);
        window.dispatch_action(Box::new(crate::shortcuts::OpenSignIn), cx);
    }

    fn download(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.ready(cx) {
            return;
        }
        if self.already_downloaded {
            let picker = cx.entity().downgrade();
            confirm_redownload(&self.target.title, window, cx, move |window, cx| {
                picker.update(cx, |picker, cx| picker.start(window, cx)).ok();
            });
        } else {
            self.start(window, cx);
        }
    }

    /// These picks as languages, for other videos.
    fn quick_preset(&self, options: &DownloadOptions) -> QuickPreset {
        QuickPreset {
            format: self.format(),
            height: self.height,
            audio_language: self
                .audio_track
                .as_deref()
                .filter(|_| !options.audio_tracks.is_empty())
                .map(|id| ym_download::selection::audio_track_language(id).to_owned()),
            caption_language: self
                .caption
                .as_ref()
                .filter(|_| self.captions_supported())
                .and_then(|key| options.captions.iter().find(|caption| caption.key() == *key))
                .map(|caption| caption.language_code.clone()),
            audio_quality: self.selected_audio_quality().map_or(self.quality, |(quality, _)| quality),
        }
    }

    fn start(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(options) = self.options() else { return };
        if self.save_as_quick {
            save_preset(&self.quick_preset(options), cx);
        }
        if let Ok(mut last) = LAST_CHOICE.lock() {
            last.audio_only = self.audio_only;
            last.audio_format = self.audio_format;
            last.audio_quality = self.quality;
            last.container = self.container;
            if let Some(height) = self.height {
                last.height = height;
            }
        }
        let format = self.format();
        let caption = self
            .caption
            .clone()
            .filter(|key| self.captions_supported() && options.captions.iter().any(|caption| caption.key() == *key));
        let target = self.target.clone();
        self.service.enqueue(DownloadRequest {
            video_id: target.video_id,
            title: target.title,
            artist: target.artist,
            thumbnail_url: target.thumbnail_url,
            format,
            requested_height: if format.is_video() { self.height } else { None },
            file_name: Some(self.finalized_name(cx)).filter(|name| !name.is_empty()),
            audio_track_id: self.audio_track.clone().filter(|_| !options.audio_tracks.is_empty()),
            captions: Some(caption.into_iter().collect()),
            audio_language: None,
            caption_language: None,
            audio_quality: self.selected_audio_quality().map_or(self.quality, |(quality, _)| quality),
        });
        window.close_dialog(cx);
        window.push_notification(t(Str::DownloadAdded), cx);
    }

    fn rename(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let stem = self.file_name.read(cx).value().to_string();
        let extension = format!(".{}", self.format().extension());
        let picker = cx.entity().downgrade();
        RenameDraft::open(stem, extension, window, cx, move |stem, window, cx| {
            picker
                .update(cx, |picker, cx| {
                    picker.file_name.update(cx, |input, cx| input.set_value(stem, window, cx));
                    cx.notify();
                })
                .ok();
        });
    }
}

/// The sheet's chrome: as wide as the window allows up to 640, `height` tall on the window bottom.
fn sheet_chrome(dialog: Dialog, height: f32, window: &Window) -> Dialog {
    let viewport = window.viewport_size();
    dialog
        .p_0()
        .gap_0()
        .border_0()
        .rounded_t(px(28.))
        .rounded_b(px(0.))
        .overflow_hidden()
        .close_button(false)
        .w(px(f32::from(viewport.width).min(SHEET_MAX_WIDTH)))
        .margin_top(px((f32::from(viewport.height) - SHEET_MARGIN - height).max(SHEET_MARGIN)))
}

pub fn format_name(format: DownloadFormat) -> &'static str {
    match format {
        DownloadFormat::Mp3 => "MP3",
        DownloadFormat::M4a => "M4A",
        DownloadFormat::AudioWebm => "Opus",
        DownloadFormat::VideoMp4 => "MP4",
        DownloadFormat::VideoWebm => "WebM",
    }
}

fn format_detail(format: DownloadFormat) -> &'static str {
    t(match format {
        DownloadFormat::Mp3 => Str::DownloadMp3Detail,
        DownloadFormat::AudioWebm => Str::DownloadWebmDetail,
        _ => Str::DownloadM4aDetail,
    })
}

pub fn quality_name(quality: AudioQuality) -> &'static str {
    t(match quality {
        AudioQuality::Save => Str::DownloadQualitySave,
        AudioQuality::Medium => Str::DownloadQualityMedium,
        AudioQuality::Best => Str::DownloadQualityBest,
    })
}

/// MP3 names its bitrate; a stream's bitrate is whatever YouTube offers, so it names the tier.
fn tier_label(quality: AudioQuality, kbps: Option<u32>, format: DownloadFormat) -> String {
    match kbps.filter(|_| format == DownloadFormat::Mp3) {
        Some(kbps) => tf(Str::DownloadKbps, &[&kbps]),
        None => quality_name(quality).to_owned(),
    }
}

/// "4K", "1080p60".
fn video_label(option: &VideoOption) -> String {
    let name = match option.height {
        4320.. => "8K".to_owned(),
        2160.. => "4K".to_owned(),
        height => format!("{height}p"),
    };
    if option.fps >= 48 { format!("{name}{}", option.fps) } else { name }
}

/// "4.1 MB", "92 MB", "1.2 GB".
fn download_size(bytes: i64) -> String {
    let megabytes = bytes.max(0) as f64 / 1e6;
    if megabytes >= 1000. {
        tf(Str::DownloadSizeGb, &[&format!("{:.1}", megabytes / 1000.)])
    } else if megabytes >= 10. {
        tf(Str::DownloadSizeMb, &[&(megabytes as i64)])
    } else {
        tf(Str::DownloadSizeMb, &[&format!("{megabytes:.1}")])
    }
}

fn caption_label(caption: &CaptionOption) -> String {
    if caption.is_translated { tf(Str::DownloadCaptionTranslated, &[&caption.name]) } else { caption.name.clone() }
}

/// The JVM palette's SurfaceDim: a half step of the muted ground over the surface.
fn surface_dim(cx: &App) -> Hsla {
    cx.theme().muted.opacity(0.5)
}

/// The JVM palette's InkSoft, between the foreground and the secondary ink.
fn ink_soft(cx: &App) -> Hsla {
    cx.theme().foreground.opacity(0.6)
}

struct Choice {
    label: String,
    selected: bool,
    pick: Rc<dyn Fn(&mut DownloadPicker)>,
}

fn choice(label: String, selected: bool, pick: impl Fn(&mut DownloadPicker) + 'static) -> Choice {
    Choice { label, selected, pick: Rc::new(pick) }
}

fn choice_items(mut menu: PopupMenu, choices: &[Choice], picker: &WeakEntity<DownloadPicker>) -> PopupMenu {
    for choice in choices {
        let (pick, picker) = (choice.pick.clone(), picker.clone());
        menu =
            menu.item(PopupMenuItem::new(choice.label.clone()).checked(choice.selected).on_click(move |_, _, cx| {
                picker
                    .update(cx, |picker, cx| {
                        pick(picker);
                        cx.notify();
                    })
                    .ok();
            }));
    }
    menu
}

impl DownloadPicker {
    fn audio_quality_label(&self) -> String {
        let Some(options) = self.options() else { return String::new() };
        let Some(option) = options.audio.iter().find(|option| option.format == self.audio_format) else {
            return String::new();
        };
        option.pick(self.quality).map_or_else(String::new, |picked| {
            format!("{} · {}", tier_label(picked.quality, picked.kbps, self.audio_format), download_size(picked.bytes))
        })
    }

    fn audio_quality_choices(&self) -> Vec<Choice> {
        let Some(option) = self
            .options()
            .and_then(|options| options.audio.iter().find(|option| option.format == self.audio_format).cloned())
        else {
            return Vec::new();
        };
        let selected = option.pick(self.quality).map(|picked| picked.quality);
        option
            .qualities
            .iter()
            .map(|tier| {
                let quality = tier.quality;
                choice(
                    format!("{} · {}", tier_label(quality, tier.kbps, self.audio_format), download_size(tier.bytes)),
                    Some(quality) == selected,
                    move |picker| picker.quality = quality,
                )
            })
            .collect()
    }

    /// The chosen audio track's name; "Default" when the video has a single track.
    fn audio_track_label(&self) -> String {
        self.options()
            .and_then(|options| options.audio_tracks.iter().find(|track| Some(&track.id) == self.audio_track.as_ref()))
            .map_or_else(|| t(Str::DownloadDefaultTrack).to_owned(), |track| track.name.clone())
    }

    fn audio_track_choices(&self) -> Vec<Choice> {
        let tracks = self.options().map(|options| options.audio_tracks.clone()).unwrap_or_default();
        if tracks.is_empty() {
            return vec![choice(t(Str::DownloadDefaultTrack).to_owned(), true, |_| {})];
        }
        tracks
            .into_iter()
            .map(|track| {
                let selected = Some(&track.id) == self.audio_track.as_ref();
                choice(track.name, selected, move |picker| picker.audio_track = Some(track.id.clone()))
            })
            .collect()
    }

    /// "English", "English · as lyrics" (MP3, Opus), or "Off".
    fn caption_summary(&self) -> String {
        let name = self.options().and_then(|options| {
            options.captions.iter().find(|caption| Some(caption.key()) == self.caption).map(caption_label)
        });
        match name {
            Some(name) if self.format().captions_as_lyrics() => tf(Str::DownloadCaptionsAsLyrics, &[&name]),
            Some(name) => name,
            None => t(Str::DownloadCaptionsOff).to_owned(),
        }
    }

    fn caption_choices(&self) -> Vec<Choice> {
        let mut choices = vec![choice(t(Str::DownloadCaptionsOff).to_owned(), self.caption.is_none(), |picker| {
            picker.caption = None
        })];
        for caption in self.options().map(|options| options.captions.clone()).unwrap_or_default() {
            let key = caption.key();
            let selected = Some(&key) == self.caption.as_ref();
            choices.push(choice(caption_label(&caption), selected, move |picker| picker.caption = Some(key.clone())));
        }
        choices
    }

    fn container_choices(&self) -> Vec<Choice> {
        let format = self.format();
        self.containers()
            .into_iter()
            .map(|container| {
                choice(format_name(container).to_owned(), format == container, move |picker| {
                    picker.container = container
                })
            })
            .collect()
    }

    fn location_name(&self) -> String {
        self.folder
            .as_ref()
            .and_then(|folder| folder.file_name())
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| t(Str::DownloadLocationNotSet).to_owned())
    }

    /// "MP4 · 1080p60 · English · English · ≈ 92 MB".
    fn summary(&self) -> String {
        let Some(options) = self.options() else { return String::new() };
        let format = self.format();
        let mut parts = vec![format_name(format).to_owned()];
        if format.is_video()
            && let Some(video) = self.selected_video()
        {
            parts.push(video_label(video));
        }
        if let Some(track) = options.audio_tracks.iter().find(|track| Some(&track.id) == self.audio_track.as_ref()) {
            parts.push(track.name.clone());
        }
        if self.caption.is_some() && self.captions_supported() {
            parts.push(self.caption_summary());
        }
        if let Some(bytes) = self.selected_bytes() {
            parts.push(format!("≈ {}", download_size(bytes)));
        }
        parts.join(" · ")
    }

    /// An outlined value with a caret that opens `choices` (the JVM `DropdownField`).
    fn dropdown(
        &self,
        id: &'static str,
        label: &'static str,
        value: String,
        stop: usize,
        menu: impl Fn(PopupMenu) -> PopupMenu + 'static,
        cx: &App,
    ) -> Div {
        let theme = cx.theme();
        let field = Button::new(id)
            .outline()
            .w_full()
            .h(px(36.))
            .px(px(8.))
            .rounded(px(9.))
            .border_color(theme.input)
            .dropdown_caret(true)
            .accessibility_label(format!("{label}, {value}"))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_left()
                    .text_size(px(14.))
                    .text_color(theme.foreground)
                    .truncate()
                    .child(value),
            )
            .track_focus(&self.focus.handle(stop, cx))
            .dropdown_menu(move |popup, _, _| menu(popup.scrollable(true).max_h(px(360.))));
        self.focus.wrap(stop, field, cx).w_full()
    }

    fn choice_dropdown(
        &self,
        id: &'static str,
        label: &'static str,
        value: String,
        choices: Vec<Choice>,
        stop: usize,
        cx: &mut Context<Self>,
    ) -> Div {
        let picker = cx.entity().downgrade();
        self.dropdown(id, label, value, stop, move |menu| choice_items(menu, &choices, &picker), cx)
    }

    fn format_dropdown(&self, cx: &mut Context<Self>) -> Div {
        let Some(options) = self.options().cloned() else { return div() };
        let format = self.format();
        let current = if format.is_video() {
            self.selected_video().map(|video| format!("{} · {}", format_name(format), video_label(video)))
        } else {
            Some(format_name(format).to_owned())
        };
        let picker = cx.entity().downgrade();
        self.dropdown(
            "download-format",
            t(Str::DownloadFormat),
            current.unwrap_or_default(),
            STOP_FORMAT,
            move |mut menu| {
                if !options.video.is_empty() {
                    menu = menu.label(t(Str::DownloadVideo).to_uppercase());
                    for video in &options.video {
                        let bytes = if format == DownloadFormat::VideoWebm {
                            video.webm_bytes.unwrap_or(0)
                        } else {
                            video.mp4_bytes
                        };
                        let (height, picker) = (video.height, picker.clone());
                        menu = menu.item(
                            PopupMenuItem::new(format!("{} — {}", video_label(video), download_size(bytes))).on_click(
                                move |_, _, cx| {
                                    picker
                                        .update(cx, |picker, cx| {
                                            picker.audio_only = false;
                                            picker.height = Some(height);
                                            cx.notify();
                                        })
                                        .ok();
                                },
                            ),
                        );
                    }
                }
                menu = menu.label(t(Str::DownloadAudioOnly).to_uppercase());
                for audio in &options.audio {
                    let (audio_format, picker) = (audio.format, picker.clone());
                    menu = menu.item(
                        PopupMenuItem::new(format!("{} · {}", format_name(audio_format), format_detail(audio_format)))
                            .on_click(move |_, _, cx| {
                                picker
                                    .update(cx, |picker, cx| {
                                        picker.audio_only = true;
                                        picker.audio_format = audio_format;
                                        cx.notify();
                                    })
                                    .ok();
                            }),
                    );
                }
                menu
            },
            cx,
        )
    }

    fn file_name_field(&self, cx: &App) -> Div {
        let theme = cx.theme();
        let blank = self.finalized_name(cx).is_empty();
        let error = blank || self.stripped;
        let hint = if blank {
            t(Str::DownloadNameEmpty).to_owned()
        } else {
            tf(Str::DownloadForbiddenHint, &[&names::FORBIDDEN_DISPLAY])
        };
        let tone = if error { theme.danger } else { theme.muted_foreground };
        v_flex()
            .w_full()
            .gap(px(4.))
            .child(div().text_size(px(12.)).text_color(tone).child(t(Str::DownloadFileName)))
            .child(
                Input::new(&self.file_name)
                    .aria_label(t(Str::DownloadFileName))
                    .text_size(px(13.))
                    .when(error, |input| input.border_color(theme.danger))
                    .suffix(
                        div()
                            .text_size(px(13.))
                            .text_color(theme.muted_foreground)
                            .child(format!(".{}", self.format().extension())),
                    ),
            )
            .child(div().px(px(16.)).text_size(px(12.)).text_color(tone).child(hint))
    }

    fn left_pane(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let duration = self.options().map(|options| options.duration_ms).filter(|ms| *ms > 0);
        let thumbnail = div()
            .relative()
            .w_full()
            .aspect_ratio(16. / 9.)
            .rounded(px(10.))
            .overflow_hidden()
            .bg(theme.secondary)
            .flex()
            .items_center()
            .justify_center()
            .child(Icon::new(Lucide::Image).size(px(26.)).text_color(theme.muted_foreground))
            .when_some(self.target.thumbnail_url.clone(), |frame, url| {
                frame.child(img(url).absolute().inset_0().size_full().rounded(px(10.)).object_fit(ObjectFit::Cover))
            })
            .when_some(duration, |frame, ms| {
                frame.child(
                    div()
                        .absolute()
                        .bottom(px(8.))
                        .right(px(8.))
                        .px(px(5.))
                        .py(px(2.))
                        .rounded(px(3.))
                        .bg(rgb(0x333333))
                        .text_size(px(11.))
                        .font_semibold()
                        .text_color(white())
                        .child(crate::mini_player::clock(ms)),
                )
            });
        let location = Button::new("download-location")
            .outline()
            .w_full()
            .h(px(40.))
            .px(px(24.))
            .rounded(px(8.))
            .border_color(theme.input)
            .accessibility_label(format!("{}, {}", t(Str::DownloadSaveTo), self.location_name()))
            .child(
                h_flex()
                    .flex_1()
                    .min_w_0()
                    .gap(px(8.))
                    .child(Icon::new(Lucide::Folder).size(px(16.)).text_color(ink_soft(cx)))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_left()
                            .text_size(px(13.))
                            .text_color(theme.foreground)
                            .truncate()
                            .child(self.location_name()),
                    ),
            )
            .on_click(cx.listener(|picker, _, window, cx| picker.choose_folder(window, cx)));
        v_flex()
            .w(px(LEFT_PANE_WIDTH))
            .flex_shrink_0()
            .bg(surface_dim(cx))
            .p(px(24.))
            .gap(px(16.))
            .child(thumbnail)
            .child(
                div()
                    .text_size(px(14.))
                    .line_height(px(18.))
                    .font_semibold()
                    .text_color(theme.foreground)
                    .line_clamp(3)
                    .child(self.target.title.clone()),
            )
            .child(self.file_name_field(cx))
            .child(
                v_flex()
                    .gap(px(4.))
                    .child(
                        div()
                            .text_size(px(12.))
                            .font_semibold()
                            .text_color(theme.muted_foreground)
                            .child(t(Str::DownloadSaveTo)),
                    )
                    .child(self.focus.button(STOP_LOCATION, location, cx).w_full()),
            )
    }

    fn track_row(
        &self,
        icon: Lucide,
        title: &'static str,
        align_top: bool,
        content: impl IntoElement,
        cx: &App,
    ) -> Div {
        let theme = cx.theme();
        v_flex()
            .w_full()
            .child(
                h_flex()
                    .w_full()
                    .py(px(16.))
                    .gap(px(16.))
                    .map(|row| if align_top { row.items_start() } else { row.items_center() })
                    .child(
                        div()
                            .size(px(34.))
                            .flex_shrink_0()
                            .rounded(px(9.))
                            .bg(surface_dim(cx))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(Icon::new(icon).size(px(18.)).text_color(ink_soft(cx))),
                    )
                    .child(
                        div()
                            .w(px(96.))
                            .flex_shrink_0()
                            .when(align_top, |label| label.pt(px(8.)))
                            .text_size(px(14.))
                            .font_semibold()
                            .text_color(theme.foreground)
                            .child(title),
                    )
                    .child(div().flex_1().min_w_0().child(content)),
            )
            .child(div().h(px(1.)).w_full().bg(theme.border.opacity(0.7)))
    }

    fn note(text: &'static str, cx: &App) -> Div {
        div().text_size(px(12.)).text_color(cx.theme().muted_foreground).child(text)
    }

    fn container_toggle(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let format = self.format();
        let mut row = h_flex().rounded(px(9.)).bg(surface_dim(cx)).p(px(2.)).gap(px(2.));
        for (index, container) in self.containers().into_iter().enumerate() {
            let selected = format == container;
            let segment = div()
                .id(("download-container", index))
                .role(Role::RadioButton)
                .aria_label(format_name(container))
                .aria_toggled(if selected { Toggled::True } else { Toggled::False })
                .w(px(96.))
                .h(px(30.))
                .rounded(px(7.))
                .cursor_pointer()
                .when(selected, |segment| segment.bg(theme.background))
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(13.))
                .font_semibold()
                .text_color(if selected { theme.foreground } else { theme.muted_foreground })
                .child(format_name(container))
                .on_click(cx.listener(move |picker, _, _, cx| {
                    picker.container = container;
                    cx.notify();
                }))
                .on_action(cx.listener(move |picker, _: &Confirm, _, cx| {
                    picker.container = container;
                    cx.notify();
                }));
            row = row.child(self.focus.item(STOP_CONTAINER + index, segment, cx));
        }
        row
    }

    fn track_rows(&self, cx: &mut Context<Self>) -> Div {
        let Some(options) = self.options() else { return div() };
        let has_captions = !options.captions.is_empty();
        let format = self.format();
        let mut rows = v_flex().w_full();
        rows = rows.child(self.track_row(Lucide::File, t(Str::DownloadFormat), false, self.format_dropdown(cx), cx));
        if self.shows_audio_quality() {
            let field = self.choice_dropdown(
                "download-quality",
                t(Str::DownloadQuality),
                self.audio_quality_label(),
                self.audio_quality_choices(),
                STOP_QUALITY,
                cx,
            );
            rows = rows.child(self.track_row(Lucide::Gauge, t(Str::DownloadQuality), false, field, cx));
        }
        let audio = self.choice_dropdown(
            "download-audio",
            t(Str::DownloadAudioLanguage),
            self.audio_track_label(),
            self.audio_track_choices(),
            STOP_AUDIO,
            cx,
        );
        rows = rows.child(self.track_row(Lucide::Volume2, t(Str::DownloadAudioLanguage), false, audio, cx));
        if has_captions && self.captions_supported() {
            let lyrics = format.captions_as_lyrics();
            let field = v_flex()
                .gap(px(4.))
                .child(self.choice_dropdown(
                    "download-captions",
                    t(Str::DownloadSubtitles),
                    self.caption_summary(),
                    self.caption_choices(),
                    STOP_CAPTIONS,
                    cx,
                ))
                .when(lyrics, |column| column.child(Self::note(t(Str::DownloadLyricsNote), cx)));
            rows = rows.child(self.track_row(Lucide::Captions, t(Str::DownloadSubtitles), lyrics, field, cx));
        }
        if format.is_video() {
            let field = v_flex()
                .gap(px(4.))
                .child(self.container_toggle(cx))
                .when(format == DownloadFormat::VideoMp4, |column| {
                    column.child(Self::note(t(Str::DownloadMp4Note), cx))
                });
            rows = rows.child(self.track_row(Lucide::Rows2, t(Str::DownloadContainer), false, field, cx));
        }
        rows
    }

    fn save_as_quick(&self, cx: &mut Context<Self>) -> impl IntoElement {
        Checkbox::new("save-as-quick").checked(self.save_as_quick).label(t(Str::DownloadSaveQuick)).on_click(
            cx.listener(|picker, checked: &bool, _, cx| {
                picker.save_as_quick = *checked;
                cx.notify();
            }),
        )
    }

    fn live_message(cx: &App) -> Div {
        div()
            .w_full()
            .py(px(24.))
            .text_center()
            .text_size(px(14.))
            .text_color(cx.theme().muted_foreground)
            .child(t(Str::DownloadLiveUnsupported))
    }

    fn failed(&self, message: &str, action: &FailAction, cx: &mut Context<Self>) -> Div {
        let button = match action {
            FailAction::Retry => Some(
                text_button("download-retry", t(Str::Retry), true, true, cx)
                    .on_click(cx.listener(|picker, _, _, cx| picker.load(cx))),
            ),
            FailAction::SignIn => Some(
                text_button("download-sign-in", t(Str::SigninButton), true, true, cx)
                    .on_click(cx.listener(|picker, _, window, cx| picker.sign_in(window, cx))),
            ),
            FailAction::None => None,
        };
        v_flex()
            .w_full()
            .py(px(16.))
            .gap(px(8.))
            .items_center()
            .child(
                div()
                    .text_center()
                    .text_size(px(14.))
                    .text_color(cx.theme().muted_foreground)
                    .child(message.to_owned()),
            )
            .when_some(button, |column, button| column.child(self.focus.button(STOP_RETRY, button, cx)))
    }

    fn render_dialog(&mut self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let right = match &self.phase {
            Phase::Live => Self::live_message(cx),
            Phase::Failed(message, action) => self.failed(&message.clone(), action, cx),
            Phase::Loading => {
                div().py(px(24.)).text_size(px(14.)).text_color(theme.muted_foreground).child(t(Str::DownloadLoading))
            }
            Phase::Ready(_) => {
                v_flex().w_full().child(self.track_rows(cx)).child(div().pt(px(8.)).child(self.save_as_quick(cx)))
            }
        };
        let ready = self.ready(cx);
        let buttons = h_flex()
            .w_full()
            .items_center()
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .pl(px(8.))
                    .text_size(px(13.))
                    .text_color(ink_soft(cx))
                    .line_clamp(2)
                    .child(self.summary()),
            )
            .child(
                self.focus.button(
                    STOP_CANCEL,
                    text_button("download-cancel", t(Str::DialogCancel), false, true, cx)
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    cx,
                ),
            )
            .child(
                self.focus.button(
                    STOP_DOWNLOAD,
                    text_button("download-start", t(Str::DownloadAction), true, ready, cx)
                        .disabled(!ready)
                        .on_click(cx.listener(|picker, _, window, cx| picker.download(window, cx))),
                    cx,
                ),
            );
        let body = h_flex()
            .w_full()
            .items_stretch()
            .min_h(px(BODY_MIN_HEIGHT))
            .child(self.left_pane(cx))
            .child(div().w(px(1.)).flex_shrink_0().bg(theme.border))
            .child(v_flex().flex_1().min_w_0().px(px(24.)).py(px(8.)).child(right));
        v_flex()
            .w_full()
            .child(title_bar(t(Str::DownloadTitle), cx))
            .child(
                div()
                    .relative()
                    .w_full()
                    .child(
                        div()
                            .id("download-body")
                            .w_full()
                            .max_h(body_max_height(window))
                            .overflow_y_scroll()
                            .track_scroll(&self.scroll)
                            .on_scroll(cx.listener(|_, _, _, cx| cx.notify()))
                            .child(body),
                    )
                    .child(div().absolute().inset_0().child(scroll_shadows(&self.scroll))),
            )
            .child(button_bar(buttons, cx))
            .into_any_element()
    }
}

// The narrow-window sheet.
impl DownloadPicker {
    /// The content's height plus the handle, at most what the window holds.
    fn sheet_height(&self, window: &Window) -> f32 {
        let available = f32::from(window.viewport_size().height) - 2. * SHEET_MARGIN;
        (self.sheet_content.get() + SHEET_HANDLE_HEIGHT).min(available).max(SHEET_HANDLE_HEIGHT + 1.)
    }

    fn sheet_header(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let thumbnail = div()
            .size(px(64.))
            .flex_shrink_0()
            .rounded(px(8.))
            .overflow_hidden()
            .bg(theme.secondary)
            .flex()
            .items_center()
            .justify_center()
            .relative()
            .child(Icon::new(Lucide::Music).size(px(24.)).text_color(theme.muted_foreground))
            .when_some(self.target.thumbnail_url.clone(), |frame, url| {
                frame.child(img(url).absolute().inset_0().size_full().rounded(px(8.)).object_fit(ObjectFit::Cover))
            });
        let rename = action_icon(
            "download-rename",
            Icon::new(Lucide::SquarePen).text_color(ink_soft(cx)),
            t(Str::DownloadEditName),
        )
        .on_click(cx.listener(|picker, _, window, cx| picker.rename(window, cx)));
        h_flex()
            .w_full()
            .gap(px(16.))
            .items_start()
            .child(thumbnail)
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(px(15.))
                    .line_height(px(20.))
                    .font_semibold()
                    .text_color(theme.foreground)
                    .child(self.file_name.read(cx).value().to_string())
                    .child(
                        div()
                            .font_medium()
                            .text_color(theme.muted_foreground.opacity(0.85))
                            .child(format!(".{}", self.format().extension())),
                    )
                    .flex()
                    .flex_wrap(),
            )
            .child(self.focus.button(STOP_RENAME, rename, cx))
    }

    fn section_label(text: &'static str, cx: &App) -> Div {
        div().text_size(px(12.)).font_semibold().text_color(cx.theme().muted_foreground).child(text.to_uppercase())
    }

    fn kind_segments(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let mut row = h_flex().w_full().rounded(px(12.)).bg(surface_dim(cx)).p(px(4.)).gap(px(4.));
        for (index, (icon, label, audio_only)) in
            [(Lucide::Headphones, t(Str::DownloadAudioOnly), true), (Lucide::Video, t(Str::DownloadVideo), false)]
                .into_iter()
                .enumerate()
        {
            let selected = self.audio_only == audio_only;
            let color = if selected { theme.foreground } else { theme.muted_foreground };
            let segment = div()
                .id(("download-kind", index))
                .role(Role::Tab)
                .aria_label(label)
                .aria_selected(selected)
                .flex_1()
                .h(px(40.))
                .rounded(px(9.))
                .cursor_pointer()
                .when(selected, |segment| segment.bg(theme.background))
                .flex()
                .items_center()
                .justify_center()
                .gap(px(8.))
                .child(Icon::new(icon).size(px(16.)).text_color(color))
                .child(div().text_size(px(14.)).font_semibold().text_color(color).child(label))
                .on_click(cx.listener(move |picker, _, _, cx| {
                    picker.audio_only = audio_only;
                    cx.notify();
                }))
                .on_action(cx.listener(move |picker, _: &Confirm, _, cx| {
                    picker.audio_only = audio_only;
                    cx.notify();
                }));
            row = row.child(self.focus.item(STOP_KIND + index, segment, cx).flex_1());
        }
        row
    }

    fn tile(
        &self,
        stop: usize,
        label: String,
        detail: Option<String>,
        selected: Option<bool>,
        on_pick: impl Fn(&mut Self) + 'static,
        cx: &mut Context<Self>,
    ) -> Stateful<Div> {
        let theme = cx.theme().clone();
        let on_pick = Rc::new(on_pick);
        let (click, enter) = (on_pick.clone(), on_pick);
        let spoken = detail.as_ref().map_or(label.clone(), |detail| format!("{label}, {detail}"));
        let tile = div()
            .id(("download-tile", stop))
            .aria_label(spoken)
            .flex_1()
            .h(px(60.))
            .rounded(px(12.))
            .cursor_pointer()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .map(|tile| match selected {
                Some(selected) => tile
                    .role(Role::RadioButton)
                    .aria_toggled(if selected { Toggled::True } else { Toggled::False })
                    .bg(if selected { theme.primary.opacity(0.12) } else { theme.background })
                    .when(selected, |tile| tile.border_2().border_color(theme.primary))
                    .when(!selected, |tile| tile.border_1().border_color(theme.border)),
                None => tile.role(Role::Button).bg(surface_dim(cx)),
            })
            .child(
                div()
                    .text_size(px(if selected.is_some() { 15. } else { 14. }))
                    .font_bold()
                    .text_color(theme.foreground)
                    .child(label),
            )
            .when_some(detail, |tile, detail| {
                tile.child(div().text_size(px(12.)).text_color(theme.muted_foreground).child(detail))
            })
            .on_click(cx.listener(move |picker, _, _, cx| {
                click(picker);
                cx.notify();
            }))
            .on_action(cx.listener(move |picker, _: &Confirm, _, cx| {
                enter(picker);
                cx.notify();
            }));
        self.focus.item(stop, tile, cx)
    }

    fn quality_grid(&self, cx: &mut Context<Self>) -> Div {
        let video = self.options().map(|options| options.video.clone()).unwrap_or_default();
        let webm = self.format() == DownloadFormat::VideoWebm;
        let collapsible = video.len() > QUALITY_COLLAPSE_OVER;
        let shown = if collapsible && !self.all_qualities { QUALITY_COLLAPSE_OVER - 1 } else { video.len() };
        let mut tiles: Vec<AnyElement> = Vec::new();
        for (index, option) in video.iter().take(shown).enumerate() {
            let bytes = if webm { option.webm_bytes.unwrap_or(0) } else { option.mp4_bytes };
            let height = option.height;
            tiles.push(
                self.tile(
                    STOP_TILES + index,
                    video_label(option),
                    Some(download_size(bytes)),
                    Some(Some(height) == self.height),
                    move |picker| picker.height = Some(height),
                    cx,
                )
                .into_any_element(),
            );
        }
        if collapsible {
            let more = if self.all_qualities {
                self.tile(
                    STOP_TILES + shown,
                    t(Str::DownloadLess).to_owned(),
                    None,
                    None,
                    |picker| picker.all_qualities = false,
                    cx,
                )
            } else {
                let lowest = video.last().map(video_label).unwrap_or_default();
                self.tile(
                    STOP_TILES + shown,
                    tf(Str::DownloadMoreQualities, &[&(video.len() - shown)]),
                    Some(tf(Str::DownloadDownTo, &[&lowest])),
                    None,
                    |picker| picker.all_qualities = true,
                    cx,
                )
            };
            tiles.push(more.into_any_element());
        }
        let mut grid = v_flex().w_full().gap(px(8.)).child(Self::section_label(t(Str::DownloadQuality), cx));
        let mut tiles = tiles.into_iter().peekable();
        while tiles.peek().is_some() {
            let mut row = h_flex().w_full().gap(px(8.));
            let mut count = 0;
            for tile in tiles.by_ref().take(QUALITY_COLUMNS) {
                row = row.child(tile);
                count += 1;
            }
            for _ in count..QUALITY_COLUMNS {
                row = row.child(div().flex_1());
            }
            grid = grid.child(row);
        }
        grid
    }

    fn audio_cards(&self, cx: &mut Context<Self>) -> Div {
        let theme = cx.theme().clone();
        let audio = self.options().map(|options| options.audio.clone()).unwrap_or_default();
        let mut cards = v_flex().w_full().gap(px(8.)).child(Self::section_label(t(Str::DownloadFormat), cx));
        for (index, option) in audio.iter().enumerate() {
            let format = option.format;
            let selected = self.audio_format == format;
            let card = div()
                .id(("download-audio-card", index))
                .role(Role::RadioButton)
                .aria_label(format!("{}, {}", format_name(format), format_detail(format)))
                .aria_toggled(if selected { Toggled::True } else { Toggled::False })
                .w_full()
                .flex()
                .items_center()
                .gap(px(16.))
                .px(px(16.))
                .py(px(8.))
                .rounded(px(12.))
                .cursor_pointer()
                .bg(if selected { theme.primary.opacity(0.12) } else { theme.background })
                .map(|card| {
                    if selected {
                        card.border_2().border_color(theme.primary)
                    } else {
                        card.border_1().border_color(theme.border)
                    }
                })
                .child(
                    div()
                        .size(px(20.))
                        .flex_shrink_0()
                        .rounded_full()
                        .border_2()
                        .border_color(if selected { theme.primary } else { theme.muted_foreground })
                        .flex()
                        .items_center()
                        .justify_center()
                        .when(selected, |radio| radio.child(div().size(px(10.)).rounded_full().bg(theme.primary))),
                )
                .child(
                    v_flex()
                        .flex_1()
                        .min_w_0()
                        .child(
                            div()
                                .text_size(px(15.))
                                .font_bold()
                                .text_color(theme.foreground)
                                .child(format_name(format)),
                        )
                        .child(
                            div().text_size(px(12.)).text_color(theme.muted_foreground).child(format_detail(format)),
                        ),
                )
                .on_click(cx.listener(move |picker, _, _, cx| {
                    picker.audio_format = format;
                    cx.notify();
                }))
                .on_action(cx.listener(move |picker, _: &Confirm, _, cx| {
                    picker.audio_format = format;
                    cx.notify();
                }));
            cards = cards.child(self.focus.item(STOP_AUDIO_CARDS + index, card, cx));
        }
        cards
    }

    /// The sheet's `OptionRow`: icon, title, the value at the end and a chevron.
    fn option_row(
        &self,
        id: &'static str,
        icon: Lucide,
        title: &'static str,
        value: String,
        stop: usize,
        cx: &App,
    ) -> Button {
        let theme = cx.theme();
        Button::new(id)
            .ghost()
            .w_full()
            .h(px(52.))
            .px_0()
            .accessibility_label(format!("{title}, {value}"))
            .track_focus(&self.focus.handle(stop, cx))
            .child(
                h_flex()
                    .w_full()
                    .gap(px(16.))
                    .child(Icon::new(icon).size(px(20.)).text_color(ink_soft(cx)))
                    .child(div().text_size(px(14.)).text_color(theme.foreground).child(title))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_right()
                            .text_size(px(14.))
                            .text_color(theme.muted_foreground)
                            .truncate()
                            .child(value),
                    )
                    .child(
                        Icon::new(IconName::ChevronRight).size(px(16.)).text_color(theme.muted_foreground.opacity(0.8)),
                    ),
            )
    }

    fn choice_row(
        &self,
        (id, icon, title): (&'static str, Lucide, &'static str),
        value: String,
        choices: Vec<Choice>,
        stop: usize,
        cx: &mut Context<Self>,
    ) -> Div {
        let picker = cx.entity().downgrade();
        let row = self
            .option_row(id, icon, title, value, stop, cx)
            .dropdown_menu_with_anchor(Anchor::TopRight, move |menu, _, _| {
                choice_items(menu.scrollable(true).max_h(px(360.)), &choices, &picker)
            });
        self.focus.wrap(stop, row, cx).w_full()
    }

    fn download_button(&self, cx: &mut Context<Self>) -> Stateful<Div> {
        let enabled = self.options().is_some();
        let format = self.format();
        let what = if format.is_video() {
            self.selected_video().map(video_label)
        } else {
            Some(format_name(format).to_owned())
        };
        let label = match (what, self.selected_bytes()) {
            (Some(what), Some(bytes)) => tf(Str::DownloadActionDetail, &[&what, &download_size(bytes)]),
            _ => t(Str::DownloadAction).to_owned(),
        };
        let fill = cx.theme().primary.darken(0.22);
        let button = div()
            .id("download-start-sheet")
            .role(Role::Button)
            .aria_label(label.clone())
            .w_full()
            .h(px(52.))
            .rounded(px(14.))
            .flex()
            .items_center()
            .justify_center()
            .bg(if enabled { fill } else { fill.opacity(0.38) })
            .text_size(px(16.))
            .font_bold()
            .text_color(white())
            .when(enabled, |button| button.cursor_pointer())
            .child(label)
            .on_click(cx.listener(|picker, _, window, cx| picker.download(window, cx)))
            .on_action(cx.listener(|picker, _: &Confirm, window, cx| picker.download(window, cx)));
        self.focus.item(STOP_DOWNLOAD, button, cx)
    }

    fn render_sheet(&mut self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let theme = cx.theme().clone();
        let mut column = v_flex().w_full().px(px(24.)).pb(px(24.)).gap(px(16.)).child(self.sheet_header(cx));
        match &self.phase {
            Phase::Live => column = column.child(Self::live_message(cx)),
            Phase::Failed(message, action) => column = column.child(self.failed(&message.clone(), action, cx)),
            Phase::Loading => {
                column = column.child(
                    h_flex()
                        .w_full()
                        .py(px(24.))
                        .gap(px(16.))
                        .justify_center()
                        .child(crate::spinner::Spinner::new())
                        .child(
                            div().text_size(px(14.)).text_color(theme.muted_foreground).child(t(Str::DownloadLoading)),
                        ),
                )
            }
            Phase::Ready(options) => {
                let (has_captions, format) = (!options.captions.is_empty(), self.format());
                if self.has_video() {
                    column = column.child(self.kind_segments(cx));
                }
                column = column.child(if self.has_video() && !self.audio_only {
                    self.quality_grid(cx)
                } else {
                    self.audio_cards(cx)
                });
                let mut rows = v_flex().w_full();
                if format.is_video() {
                    rows = rows.child(self.choice_row(
                        ("download-container", Lucide::File, t(Str::DownloadContainer)),
                        format_name(format).to_owned(),
                        self.container_choices(),
                        STOP_CONTAINER,
                        cx,
                    ));
                }
                if self.shows_audio_quality() {
                    rows = rows.child(self.choice_row(
                        ("download-quality", Lucide::Gauge, t(Str::DownloadQuality)),
                        self.audio_quality_label(),
                        self.audio_quality_choices(),
                        STOP_QUALITY,
                        cx,
                    ));
                }
                rows = rows.child(self.choice_row(
                    ("download-audio", Lucide::AudioLines, t(Str::DownloadAudioTrack)),
                    self.audio_track_label(),
                    self.audio_track_choices(),
                    STOP_AUDIO,
                    cx,
                ));
                if has_captions && self.captions_supported() {
                    rows = rows.child(self.choice_row(
                        ("download-captions", Lucide::Captions, t(Str::DownloadCaptions)),
                        self.caption_summary(),
                        self.caption_choices(),
                        STOP_CAPTIONS,
                        cx,
                    ));
                }
                let location = self
                    .option_row(
                        "download-location",
                        Lucide::Folder,
                        t(Str::DownloadSaveTo),
                        self.location_name(),
                        STOP_LOCATION,
                        cx,
                    )
                    .on_click(cx.listener(|picker, _, window, cx| picker.choose_folder(window, cx)));
                rows = rows.child(self.focus.wrap(STOP_LOCATION, location, cx).w_full());
                column = column.child(rows).child(self.save_as_quick(cx));
            }
        }
        column = column.child(self.download_button(cx));
        let measured = self.sheet_content.clone();
        let column = column.relative().child(
            canvas(
                |_, _, _| {},
                move |bounds, _, window, _| {
                    let height = f32::from(bounds.size.height);
                    if (measured.get() - height).abs() > 0.5 {
                        measured.set(height);
                        // Runs during paint, where a refresh is ignored.
                        window.on_next_frame(|window, _| window.refresh());
                    }
                },
            )
            .absolute()
            .top_0()
            .left_0()
            .size_full(),
        );
        v_flex()
            .w_full()
            .child(
                h_flex()
                    .w_full()
                    .justify_center()
                    .py(px(22.))
                    .child(div().w(px(32.)).h(px(4.)).rounded_full().bg(theme.muted_foreground.opacity(0.4))),
            )
            .child(
                div()
                    .id("download-sheet")
                    .w_full()
                    .h(px(self.sheet_height(window) - SHEET_HANDLE_HEIGHT))
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .child(column),
            )
            .into_any_element()
    }
}

impl Render for DownloadPicker {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let content = if is_narrow(window) { self.render_sheet(window, cx) } else { self.render_dialog(window, cx) };
        let stops = 0..STOP_AUDIO_CARDS + 8;
        self.focus.container(div().w_full(), stops, cx).child(content)
    }
}

type OnSave = Rc<dyn Fn(String, &mut Window, &mut App)>;

/// The sheet's file-name editor (the JVM `FileNameDialog`): a draft of the stem, kept on Save.
struct RenameDraft {
    input: Entity<InputState>,
    extension: String,
    stripped: bool,
    stripping: bool,
    on_save: OnSave,
    focus: FocusList,
    _changes: Subscription,
}

impl RenameDraft {
    fn open(
        stem: String,
        extension: String,
        window: &mut Window,
        cx: &mut App,
        on_save: impl Fn(String, &mut Window, &mut App) + 'static,
    ) {
        let view = cx.new(|cx| {
            let input = cx.new(|cx| InputState::new(window, cx).default_value(stem));
            let changes = cx.subscribe_in(&input, window, |draft: &mut Self, input, event, window, cx| {
                if !matches!(event, InputEvent::Change) {
                    return;
                }
                let raw = input.read(cx).value().to_string();
                if names::has_forbidden(&raw) {
                    input.update(cx, |input, cx| input.set_value(names::strip_forbidden(&raw), window, cx));
                    draft.stripped = true;
                    draft.stripping = true;
                } else if !std::mem::take(&mut draft.stripping) {
                    draft.stripped = false;
                }
                cx.notify();
            });
            input.update(cx, |input, cx| input.focus(window, cx));
            Self {
                input,
                extension,
                stripped: false,
                stripping: false,
                on_save: Rc::new(on_save),
                focus: FocusList::modal(None, cx),
                _changes: changes,
            }
        });
        let save = view.downgrade();
        window.open_dialog(cx, move |dialog, window, _| {
            let save = save.clone();
            chrome(dialog, elevated_dialog::DEFAULT_WIDTH, window)
                .on_ok(move |_, window, cx| save.update(cx, |draft, cx| draft.save(window, cx)).unwrap_or(false))
                .child(view.clone())
        });
    }

    /// Hands the finalized stem over; false (the dialog stays) when it is blank.
    fn save(&mut self, window: &mut Window, cx: &mut Context<Self>) -> bool {
        let stem = names::finalize(&self.input.read(cx).value());
        if stem.is_empty() {
            return false;
        }
        (self.on_save)(stem, window, cx);
        true
    }
}

impl Render for RenameDraft {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = cx.theme().clone();
        let blank = names::finalize(&self.input.read(cx).value()).is_empty();
        let error = blank || self.stripped;
        let tone = if error { theme.danger } else { theme.muted_foreground };
        let hint = if blank {
            t(Str::DownloadNameEmpty).to_owned()
        } else {
            tf(Str::DownloadForbiddenHint, &[&names::FORBIDDEN_DISPLAY])
        };
        let buttons = h_flex()
            .child(
                self.focus.button(
                    0,
                    text_button("rename-cancel", t(Str::DialogCancel), false, true, cx)
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    cx,
                ),
            )
            .child(self.focus.button(
                1,
                text_button("rename-save", t(Str::DownloadSave), true, !blank, cx).disabled(blank).on_click(
                    cx.listener(|draft, _, window, cx| {
                        if draft.save(window, cx) {
                            window.close_dialog(cx);
                        }
                    }),
                ),
                cx,
            ));
        v_flex()
            .w_full()
            .child(title_bar(t(Str::DownloadRename), cx))
            .child(
                v_flex()
                    .w_full()
                    .px(px(24.))
                    .py(px(8.))
                    .gap(px(4.))
                    .child(div().text_size(px(12.)).text_color(tone).child(t(Str::DownloadFileName)))
                    .child(
                        Input::new(&self.input)
                            .aria_label(t(Str::DownloadFileName))
                            .when(error, |input| input.border_color(theme.danger))
                            .suffix(div().text_color(theme.muted_foreground).child(self.extension.clone())),
                    )
                    .child(div().px(px(16.)).text_size(px(12.)).text_color(tone).child(hint)),
            )
            .child(button_bar(buttons, cx))
    }
}
