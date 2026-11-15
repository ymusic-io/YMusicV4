//! The default component assets, the extra Lucide icons the shell uses, and library artwork.

use std::borrow::Cow;
use std::sync::Arc;

use gpui_kit::{AssetSource, Result, SharedString};

use crate::local_art::LocalArt;

gpui_kit::assets::icon_assets!(
    ShellIcons,
    [
        MonitorPlay,
        Inbox,
        Rewind,
        FastForward,
        CirclePlay,
        LibraryBig,
        RotateCcwClock,
        Download,
        Settings,
        Heart,
        Send,
        MessageCircle,
        Play,
        Pause,
        SkipBack,
        SkipForward,
        Volume2,
        Repeat,
        Repeat1,
        Shuffle,
        Timer,
        GripVertical,
        FolderOpen,
        X,
        SlidersHorizontal,
        User,
        Disc,
        Music,
        Film,
        Folder,
        House,
        TrendingUp,
        Star,
        Menu,
        ArrowUpLeft,
        ArrowLeft,
        Search,
        Captions,
        Maximize,
        Minimize,
        ShieldCheck,
        ShieldOff,
        ListPlus,
        ListCheck,
        Share2,
        RefreshCw,
        ArrowUpDown,
        Tag,
        ExternalLink,
        Gauge,
        Volume1,
        VolumeX,
        CircleUser,
        Clock,
        ThumbsUp,
        SquarePlay,
        Trash,
        ThumbsDown,
        Pin,
        ListMusic,
        ArrowUpToLine,
        CircleMinus,
        Flame,
        SquarePen,
        Funnel,
        MousePointer2,
        File,
        Rows2,
        Headphones,
        Video,
        AudioLines,
        Image
    ]
);

/// SponsorBlock's mark (the JVM app's ic_sponsor_block), a monochrome icon tinted by its text color.
pub const SPONSOR_BLOCK_ICON: &str = "brand/sponsor-block.svg";

pub const LOGO_SVG: &str = include_str!("../assets/ymusic-logo.svg");
const LOGO_PREFIX: &str = "brand/ymusic-logo@";

/// The app logo, full color, for `img()`, rasterized at `device_px` square: gpui draws an SVG image
/// at its own size and minifies it unfiltered, so it must arrive at the size it is shown.
pub fn logo_path(device_px: u32) -> SharedString {
    format!("{LOGO_PREFIX}{device_px}.svg").into()
}

fn logo_at(path: &str) -> Option<String> {
    let size: u32 = path.strip_prefix(LOGO_PREFIX)?.strip_suffix(".svg")?.parse().ok()?;
    Some(LOGO_SVG.replacen(r#"width="1024" height="1024""#, &format!(r#"width="{size}" height="{size}""#), 1))
}

pub struct AppAssets {
    pub local_art: Arc<LocalArt>,
}

impl AssetSource for AppAssets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if LocalArt::handles(path) {
            return self.local_art.load(path).map(Some);
        }
        if path == SPONSOR_BLOCK_ICON {
            return Ok(Some(Cow::Borrowed(include_bytes!("../assets/sponsor-block.svg"))));
        }
        if let Some(svg) = logo_at(path) {
            return Ok(Some(Cow::Owned(svg.into_bytes())));
        }
        match ShellIcons.load(path) {
            Ok(Some(bytes)) => Ok(Some(bytes)),
            _ => gpui_kit::assets::Assets.load(path),
        }
    }

    fn list(&self, path: &str) -> Result<Vec<SharedString>> {
        let mut names = ShellIcons.list(path).unwrap_or_default();
        names.extend(gpui_kit::assets::Assets.list(path)?);
        Ok(names)
    }
}
