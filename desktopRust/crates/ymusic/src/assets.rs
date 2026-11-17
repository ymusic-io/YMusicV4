//! The default component assets, the extra Lucide icons the shell uses, and the app logo.

use std::borrow::Cow;
use std::sync::{Arc, Mutex};

use gpui_kit::{App, AssetSource, Img, RenderImage, Result, SharedString, img};

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
        Image,
        Scan,
        Shrink,
        SlidersVertical,
        MicVocal,
        ListChecks,
        ListX
    ]
);

/// SponsorBlock's mark (the JVM app's ic_sponsor_block), a monochrome icon tinted by its text color.
pub const SPONSOR_BLOCK_ICON: &str = "brand/sponsor-block.svg";

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

pub struct AppAssets;

impl AssetSource for AppAssets {
    fn load(&self, path: &str) -> Result<Option<Cow<'static, [u8]>>> {
        if path == SPONSOR_BLOCK_ICON {
            return Ok(Some(Cow::Borrowed(include_bytes!("../assets/sponsor-block.svg"))));
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
