//! The JVM `FallbackArt.kt`: the picture for an item with no art, or whose every art source failed —
//! the item's own colour fading darker downward under a large music note. The colour comes from a
//! stable hash of the item's file name (`fallback_art_key`), so a song keeps it across launches and
//! both apps; the JVM app computes the same integers.

use std::path::{Path, PathBuf};

use gpui_kit::component::Icon;
use gpui_kit::*;

use crate::assets::MaterialIcon;
use crate::playback::{QueueItem, Source};

/// The JVM `FALLBACK_ART_ICON_SHARE`: the note's side as a share of the art's shorter side.
const ICON_SHARE: f32 = 0.6;
/// The JVM `FALLBACK_ART_ICON_ALPHA`.
const ICON_ALPHA: f32 = 0.85;
/// `Icons.Default.MusicNote`'s path in its 24-unit viewport (assets/material/filled/music_note.svg).
const MUSIC_NOTE_PATH: &str =
    "M12,3v10.55c-0.59,-0.34,-1.27,-0.55,-2,-0.55c-2.21,0,-4,1.79,-4,4s1.79,4,4,4s4,-1.79,4,-4V7h4V3h-6z";

/// The JVM `FALLBACK_ART_PALETTE`: sixteen deep colours, each dark enough that the white note reads
/// at 3.8:1 or better.
const PALETTE: [u32; 16] = [
    0xC62828, 0xAD1457, 0x6A1B9A, 0x4527A0, 0x283593, 0x1565C0, 0x0277BD, 0x00838F, 0x00695C, 0x2E7D32, 0x558B2F,
    0x827717, 0xE65100, 0xD84315, 0x4E342E, 0x37474F,
];
/// The JVM `FALLBACK_ART_BOTTOM_PERCENT`: the bottom edge keeps this share of each top channel.
const BOTTOM_PERCENT: u32 = 65;

/// The JVM `FallbackArtColors`: the gradient's top and bottom as `0xRRGGBB`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct FallbackColors {
    pub top: u32,
    pub bottom: u32,
}

/// The JVM `fallbackArtKey`: the file's name (NFC, without the extension), else the YouTube video id,
/// else the title (NFC).
pub fn fallback_art_key(file_name: Option<&str>, video_id: Option<&str>, title: &str) -> String {
    if let Some(name) = file_name.filter(|name| !name.trim().is_empty()) {
        let normalized = nfc(name);
        return match normalized.rfind('.') {
            Some(dot) if dot > 0 => normalized[..dot].to_owned(),
            _ => normalized,
        };
    }
    if let Some(video_id) = video_id.filter(|id| !id.trim().is_empty()) {
        return video_id.to_owned();
    }
    nfc(title)
}

/// The JVM `PlayableItem.fallbackArtKey` for a queue item.
pub fn item_fallback_key(item: &QueueItem) -> String {
    let file_name = match &item.source {
        Source::Local(path) => path.file_name().map(|name| name.to_string_lossy().into_owned()),
        Source::YouTube(_) => None,
    };
    let video_id = item.video_id().or(item.youtube_vid.as_deref());
    fallback_art_key(file_name.as_deref(), video_id, &item.title)
}

fn nfc(text: &str) -> String {
    icu_normalizer::ComposingNormalizerBorrowed::new_nfc().normalize(text).into_owned()
}

/// The JVM `fnv1a32`: "" is 811c9dc5, "a" e40c292c, "foobar" bf9cf968.
fn fnv1a32(bytes: &[u8]) -> u32 {
    bytes.iter().fold(0x811C_9DC5_u32, |hash, byte| (hash ^ u32::from(*byte)).wrapping_mul(0x0100_0193))
}

/// The JVM `fallbackArtColors`: the palette entry FNV-1a 32 of the key's UTF-8 bytes picks.
pub fn fallback_colors(key: &str) -> FallbackColors {
    let top = PALETTE[(fnv1a32(key.as_bytes()) % PALETTE.len() as u32) as usize];
    let shade = |shift: u32| (((top >> shift) & 0xFF) * BOTTOM_PERCENT / 100) << shift;
    FallbackColors { top, bottom: shade(16) | shade(8) | shade(0) }
}

/// The fallback colours of a queue item, or of nothing playing.
pub fn item_fallback_colors(item: Option<&QueueItem>) -> FallbackColors {
    fallback_colors(&item.map(item_fallback_key).unwrap_or_default())
}

fn gradient(colors: FallbackColors) -> Background {
    linear_gradient(180., linear_color_stop(rgb(colors.top), 0.), linear_color_stop(rgb(colors.bottom), 1.))
}

/// The fallback filling its parent; `side` is the shorter side it is drawn at, for the note's size.
pub fn fallback_art(colors: FallbackColors, side: Pixels) -> Div {
    div()
        .size_full()
        .bg(gradient(colors))
        .flex()
        .items_center()
        .justify_center()
        .child(Icon::new(MaterialIcon::MusicNote).size(side * ICON_SHARE).text_color(white().opacity(ICON_ALPHA)))
}

/// Just the gradient, for the backdrop the fallback square sits on.
pub fn fallback_backdrop(colors: FallbackColors) -> Div {
    div().size_full().bg(gradient(colors))
}

/// The JVM `fallbackArtBitmap`, as a `px`-square PNG file in `dir` (one per colour, written once), for
/// the OS media surface, which takes art as a url.
pub fn fallback_art_file(colors: FallbackColors, px: u32, dir: &Path) -> anyhow::Result<PathBuf> {
    let (top, bottom) = (format!("{:06X}", colors.top), format!("{:06X}", colors.bottom));
    let path = dir.join(format!("fallback-art-{top}-{px}.png"));
    if path.is_file() {
        return Ok(path);
    }
    let icon = 24. * ICON_SHARE;
    let offset = (24. - icon) / 2.;
    let svg = format!(
        r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><defs><linearGradient id="shade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#{top}"/><stop offset="1" stop-color="#{bottom}"/></linearGradient></defs><rect width="24" height="24" fill="url(#shade)"/><path transform="translate({offset} {offset}) scale({ICON_SHARE})" fill="#FFFFFF" fill-opacity="{ICON_ALPHA}" d="{MUSIC_NOTE_PATH}"/></svg>"##
    );
    let tree = resvg::usvg::Tree::from_str(&svg, &resvg::usvg::Options::default())?;
    let mut pixmap = resvg::tiny_skia::Pixmap::new(px, px).ok_or_else(|| anyhow::anyhow!("no {px} px pixmap"))?;
    let scale = px as f32 / 24.;
    resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(scale, scale), &mut pixmap.as_mut());
    let pixels = pixmap.pixels().iter().flat_map(|pixel| {
        let color = pixel.demultiply();
        [color.red(), color.green(), color.blue(), color.alpha()]
    });
    let image = image::RgbaImage::from_raw(px, px, pixels.collect()).ok_or_else(|| anyhow::anyhow!("{px} px art"))?;
    std::fs::create_dir_all(dir)?;
    let partial = path.with_extension("png.tmp");
    image.save_with_format(&partial, image::ImageFormat::Png)?;
    std::fs::rename(&partial, &path)?;
    Ok(path)
}
