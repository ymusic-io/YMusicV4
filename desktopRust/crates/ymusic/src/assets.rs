//! The default component assets, the JVM app's Material glyphs and drawables, and the app logo.

use std::borrow::Cow;
use std::sync::{Arc, Mutex};

use gpui_kit::component::IconNamed;
use gpui_kit::{App, AssetSource, Img, RenderImage, Result, SharedString, img};

/// SponsorBlock's mark (the JVM app's ic_sponsor_block), a monochrome icon tinted by its text color.
pub const SPONSOR_BLOCK_ICON: &str = "brand/sponsor-block.svg";

/// The Telegram mark the JVM app's drawer shows (its `TelegramIcon`), monochrome, tinted by its text color.
pub const TELEGRAM_ICON: &str = "brand/telegram.svg";

/// The JVM cookie guide's pointer (its `drawCursor` path), with the tip 5 across and 1 down in its 20 box.
pub const GUIDE_CURSOR_ICON: &str = "guide/cursor.svg";

macro_rules! material_icons {
    ($($(#[$doc:meta])* $name:ident => $file:literal,)*) => {
        /// The JVM app's Material glyphs (Apache-2.0), path data from Compose's material-icons 1.7.3,
        /// monochrome and tinted by their text color. An AutoMirrored one goes through
        /// `action_icon::auto_mirrored` where the JVM app's would mirror.
        #[derive(Clone, Copy)]
        pub enum MaterialIcon {
            $($(#[$doc])* $name,)*
        }

        impl MaterialIcon {
            const ALL: &[MaterialIcon] = &[$(MaterialIcon::$name,)*];

            fn asset_path(self) -> &'static str {
                match self {
                    $(MaterialIcon::$name => concat!("material/", $file, ".svg"),)*
                }
            }

            fn svg(self) -> &'static [u8] {
                match self {
                    $(MaterialIcon::$name => include_bytes!(concat!("../assets/material/", $file, ".svg")),)*
                }
            }
        }
    };
}

material_icons! {
    /// `Icons.Default.Menu`.
    Menu => "filled/menu",
    /// `Icons.Default.PlayArrow`.
    PlayArrow => "filled/play_arrow",
    /// `Icons.Default.Pause`.
    Pause => "filled/pause",
    /// `Icons.Default.SkipNext`.
    SkipNext => "filled/skip_next",
    /// `Icons.Default.SkipPrevious`.
    SkipPrevious => "filled/skip_previous",
    /// `Icons.Default.FastRewind`.
    FastRewind => "filled/fast_rewind",
    /// `Icons.Default.FastForward`.
    FastForward => "filled/fast_forward",
    /// `Icons.Default.Shuffle`.
    Shuffle => "filled/shuffle",
    /// `Icons.Default.Repeat`.
    Repeat => "filled/repeat",
    /// `Icons.Default.RepeatOne`.
    RepeatOne => "filled/repeat_one",
    /// `Icons.Default.Tune`.
    Tune => "filled/tune",
    /// `Icons.Default.Search`.
    Search => "filled/search",
    /// `Icons.Default.Close`.
    Close => "filled/close",
    /// `Icons.Default.Check`.
    Check => "filled/check",
    /// `Icons.Default.Add`.
    Add => "filled/add",
    /// `Icons.Default.MoreVert`.
    MoreVert => "filled/more_vert",
    /// `Icons.Default.KeyboardArrowDown`.
    KeyboardArrowDown => "filled/keyboard_arrow_down",
    /// `Icons.Default.Fullscreen`.
    Fullscreen => "filled/fullscreen",
    /// `Icons.Default.FullscreenExit`.
    FullscreenExit => "filled/fullscreen_exit",
    /// `Icons.Default.FitScreen`.
    FitScreen => "filled/fit_screen",
    /// `Icons.Default.CropFree`.
    CropFree => "filled/crop_free",
    /// `Icons.Default.ClosedCaption`.
    ClosedCaption => "filled/closed_caption",
    /// `Icons.Default.DragHandle`.
    DragHandle => "filled/drag_handle",
    /// `Icons.Default.MusicNote`.
    MusicNote => "filled/music_note",
    /// `Icons.Default.Person`.
    Person => "filled/person",
    /// `Icons.Default.Album`.
    Album => "filled/album",
    /// `Icons.Default.Category`.
    Category => "filled/category",
    /// `Icons.Default.Folder`.
    Folder => "filled/folder",
    /// `Icons.Default.History`.
    History => "filled/history",
    /// `Icons.Default.Whatshot`.
    Whatshot => "filled/whatshot",
    /// `Icons.Default.Download`.
    Download => "filled/download",
    /// `Icons.Default.Refresh`.
    Refresh => "filled/refresh",
    /// `Icons.Default.ArrowUpward`.
    ArrowUpward => "filled/arrow_upward",
    /// `Icons.Default.ArrowDownward`.
    ArrowDownward => "filled/arrow_downward",
    /// `Icons.Default.VerticalAlignTop`.
    VerticalAlignTop => "filled/vertical_align_top",
    /// `Icons.Default.PlayCircleOutline`.
    PlayCircleOutline => "filled/play_circle_outline",
    /// `Icons.Default.StarBorder`.
    StarBorder => "filled/star_border",
    /// `Icons.Default.NorthWest`.
    NorthWest => "filled/north_west",
    /// `Icons.Default.ThumbUp`.
    ThumbUp => "filled/thumb_up",
    /// `Icons.Default.ThumbDown`.
    ThumbDown => "filled/thumb_down",
    /// `Icons.Default.Favorite`.
    Favorite => "filled/favorite",
    /// `Icons.Default.SelectAll`.
    SelectAll => "filled/select_all",
    /// `Icons.Default.GpsFixed`.
    GpsFixed => "filled/gps_fixed",
    /// `Icons.Outlined.OndemandVideo`.
    OndemandVideoOutlined => "outlined/ondemand_video",
    /// `Icons.Outlined.LibraryMusic`.
    LibraryMusicOutlined => "outlined/library_music",
    /// `Icons.Outlined.History`.
    HistoryOutlined => "outlined/history",
    /// `Icons.Outlined.FileDownload`.
    FileDownloadOutlined => "outlined/file_download",
    /// `Icons.Outlined.Settings`.
    SettingsOutlined => "outlined/settings",
    /// `Icons.Outlined.FavoriteBorder`.
    FavoriteBorderOutlined => "outlined/favorite_border",
    /// `Icons.Outlined.Home`.
    HomeOutlined => "outlined/home",
    /// `Icons.Outlined.MusicNote`.
    MusicNoteOutlined => "outlined/music_note",
    /// `Icons.Outlined.Person`.
    PersonOutlined => "outlined/person",
    /// `Icons.Outlined.Inbox`.
    InboxOutlined => "outlined/inbox",
    /// `Icons.Outlined.FilterAlt`.
    FilterAltOutlined => "outlined/filter_alt",
    /// `Icons.Outlined.Speed`.
    SpeedOutlined => "outlined/speed",
    /// `Icons.Outlined.Equalizer`.
    EqualizerOutlined => "outlined/equalizer",
    /// `Icons.Outlined.Info`.
    InfoOutlined => "outlined/info",
    /// `Icons.Outlined.Share`.
    ShareOutlined => "outlined/share",
    /// `Icons.Outlined.Lyrics`.
    LyricsOutlined => "outlined/lyrics",
    /// `Icons.Outlined.GraphicEq`.
    GraphicEqOutlined => "outlined/graphic_eq",
    /// `Icons.Outlined.Refresh`.
    RefreshOutlined => "outlined/refresh",
    /// `Icons.Outlined.ClosedCaptionOff`.
    ClosedCaptionOffOutlined => "outlined/closed_caption_off",
    /// `Icons.Outlined.Image`.
    ImageOutlined => "outlined/image",
    /// `Icons.Outlined.Folder`.
    FolderOutlined => "outlined/folder",
    /// `Icons.Outlined.InsertDriveFile`.
    InsertDriveFileOutlined => "outlined/insert_drive_file",
    /// `Icons.Outlined.HighQuality`.
    HighQualityOutlined => "outlined/high_quality",
    /// `Icons.Outlined.VolumeUp`.
    VolumeUpOutlined => "outlined/volume_up",
    /// `Icons.Outlined.Subtitles`.
    SubtitlesOutlined => "outlined/subtitles",
    /// `Icons.Outlined.ViewAgenda`.
    ViewAgendaOutlined => "outlined/view_agenda",
    /// `Icons.Outlined.RecordVoiceOver`.
    RecordVoiceOverOutlined => "outlined/record_voice_over",
    /// `Icons.Outlined.Edit`.
    EditOutlined => "outlined/edit",
    /// `Icons.Outlined.Headphones`.
    HeadphonesOutlined => "outlined/headphones",
    /// `Icons.Outlined.Videocam`.
    VideocamOutlined => "outlined/videocam",
    /// `Icons.Outlined.Delete`.
    DeleteOutlined => "outlined/delete",
    /// `Icons.Outlined.Schedule`.
    ScheduleOutlined => "outlined/schedule",
    /// `Icons.Outlined.ThumbUp`.
    ThumbUpOutlined => "outlined/thumb_up",
    /// `Icons.Outlined.ThumbDown`.
    ThumbDownOutlined => "outlined/thumb_down",
    /// `Icons.Outlined.PushPin`.
    PushPinOutlined => "outlined/push_pin",
    /// `Icons.Outlined.AccountCircle`.
    AccountCircleOutlined => "outlined/account_circle",
    /// `Icons.Outlined.Subscriptions`.
    SubscriptionsOutlined => "outlined/subscriptions",
    /// `Icons.Outlined.LibraryAdd`.
    LibraryAddOutlined => "outlined/library_add",
    /// `Icons.Outlined.RemoveCircleOutline`.
    RemoveCircleOutlineOutlined => "outlined/remove_circle_outline",
    /// `Icons.Outlined.Public`.
    PublicOutlined => "outlined/public",
    /// `Icons.Outlined.Email`.
    EmailOutlined => "outlined/email",
    /// `Icons.Rounded.CheckCircle`.
    CheckCircleRounded => "rounded/check_circle",
    /// `Icons.AutoMirrored.Filled.ArrowBack`.
    ArrowBack => "automirrored_filled/arrow_back",
    /// `Icons.AutoMirrored.Filled.KeyboardArrowRight`.
    KeyboardArrowRight => "automirrored_filled/keyboard_arrow_right",
    /// `Icons.AutoMirrored.Filled.PlaylistPlay`.
    PlaylistPlay => "automirrored_filled/playlist_play",
    /// `Icons.AutoMirrored.Filled.PlaylistAdd`.
    PlaylistAdd => "automirrored_filled/playlist_add",
    /// `Icons.AutoMirrored.Filled.PlaylistAddCheck`.
    PlaylistAddCheck => "automirrored_filled/playlist_add_check",
    /// `Icons.Default.PlaylistRemove`.
    PlaylistRemove => "filled/playlist_remove",
    /// `Icons.AutoMirrored.Filled.Sort`.
    Sort => "automirrored_filled/sort",
    /// `Icons.AutoMirrored.Filled.VolumeUp`.
    VolumeUp => "automirrored_filled/volume_up",
    /// `Icons.AutoMirrored.Filled.VolumeDown`.
    VolumeDown => "automirrored_filled/volume_down",
    /// `Icons.AutoMirrored.Filled.VolumeOff`.
    VolumeOff => "automirrored_filled/volume_off",
    /// `Icons.AutoMirrored.Filled.OpenInNew`.
    OpenInNew => "automirrored_filled/open_in_new",
    /// `Icons.AutoMirrored.Filled.Send`.
    Send => "automirrored_filled/send",
    /// `Icons.AutoMirrored.Outlined.Chat`.
    ChatOutlined => "automirrored_outlined/chat",
    /// `Icons.AutoMirrored.Outlined.TrendingUp`.
    TrendingUpOutlined => "automirrored_outlined/trending_up",
    /// `Icons.AutoMirrored.Outlined.PlaylistAdd`.
    PlaylistAddOutlined => "automirrored_outlined/playlist_add",
    /// `Icons.AutoMirrored.Outlined.QueueMusic`.
    QueueMusicOutlined => "automirrored_outlined/queue_music",
    /// `Icons.AutoMirrored.Outlined.OpenInNew`.
    OpenInNewOutlined => "automirrored_outlined/open_in_new",
    /// The JVM app's `ic_sleep_timer`: Material Symbols "snooze", rounded.
    SleepTimer => "symbols_rounded/snooze",
}

impl IconNamed for MaterialIcon {
    fn path(self) -> SharedString {
        self.asset_path().into()
    }
}

pub const LOGO_SVG: &str = include_str!("../assets/ymusic-logo.svg");

/// One rasterized logo per device-pixel size shown (a window shows one or two).
static LOGOS: Mutex<Vec<(u32, Arc<RenderImage>)>> = Mutex::new(Vec::new());

/// The app logo, full color, as an image `device_px` square: gpui minifies an image unfiltered, so it
/// must arrive at the size it is shown. Rasterized here rather than through the asset map.
pub fn logo(device_px: u32, cx: &App) -> Option<Img> {
    match logo_image(device_px.max(1)) {
        Ok(image) => Some(img(image)),
        Err(error) => {
            let message = "app logo not rasterized";
            match cx.try_global::<crate::graph::AppGraph>() {
                Some(graph) => graph.log.warn(message, Some(error.as_ref())),
                None => ym_common::log::stderr_line(format_args!("{message}: {error:#}")),
            }
            None
        }
    }
}

fn logo_image(side: u32) -> anyhow::Result<Arc<RenderImage>> {
    let mut logos = LOGOS.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some((_, image)) = logos.iter().find(|(size, _)| *size == side) {
        return Ok(image.clone());
    }
    let tree = resvg::usvg::Tree::from_str(LOGO_SVG, &resvg::usvg::Options::default())?;
    let mut pixmap =
        resvg::tiny_skia::Pixmap::new(side, side).ok_or_else(|| anyhow::anyhow!("no {side} px logo pixmap"))?;
    let scale = side as f32 / tree.size().width();
    resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(scale, scale), &mut pixmap.as_mut());
    // gpui images are straight BGRA.
    let pixels = pixmap.pixels().iter().flat_map(|pixel| {
        let color = pixel.demultiply();
        [color.blue(), color.green(), color.red(), color.alpha()]
    });
    let bgra = image::RgbaImage::from_raw(side, side, pixels.collect())
        .ok_or_else(|| anyhow::anyhow!("logo buffer does not match its size"))?;
    let image = Arc::new(RenderImage::new(vec![image::Frame::new(bgra)]));
    logos.push((side, image.clone()));
    Ok(image)
}

/// The gpui-kit component glyphs the app shows, drawn as the JVM app's Material 3 counterparts draw
/// them: the checkbox tick and a checked menu item, a submenu's arrow, a dropdown button's caret, and
/// the clear button of a search field and a toast's close.
const COMPONENT_ICONS: [(&str, MaterialIcon); 4] = [
    ("icons/check.svg", MaterialIcon::Check),
    ("icons/chevron-right.svg", MaterialIcon::KeyboardArrowRight),
    ("icons/chevron-down.svg", MaterialIcon::KeyboardArrowDown),
    ("icons/close.svg", MaterialIcon::Close),
];

pub struct AppAssets;

impl AssetSource for AppAssets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if path == SPONSOR_BLOCK_ICON {
            return Ok(Some(Cow::Borrowed(include_bytes!("../assets/sponsor-block.svg"))));
        }
        if path == TELEGRAM_ICON {
            return Ok(Some(Cow::Borrowed(include_bytes!("../assets/telegram.svg"))));
        }
        if path == GUIDE_CURSOR_ICON {
            return Ok(Some(Cow::Borrowed(include_bytes!("../assets/guide-cursor.svg"))));
        }
        if let Some(icon) = MaterialIcon::ALL.iter().find(|icon| icon.asset_path() == path) {
            return Ok(Some(Cow::Borrowed(icon.svg())));
        }
        if let Some((_, icon)) = COMPONENT_ICONS.iter().find(|(component_path, _)| *component_path == path) {
            return Ok(Some(Cow::Borrowed(icon.svg())));
        }
        gpui_kit::assets::Assets.load(path)
    }

    fn list(&self, path: &str) -> Result<Vec<SharedString>> {
        gpui_kit::assets::Assets.list(path)
    }
}
