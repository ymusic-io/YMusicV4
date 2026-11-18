//! The JVM `FallbackArt.kt`: the picture for an item with no art, or whose every art source failed —
//! the accent fading darker downward under a large music note. The accent is capped at a luminance
//! white reads on, so it reads in light and dark themes alike.

use std::path::{Path, PathBuf};

use gpui_kit::component::Icon;
use gpui_kit::*;

use crate::accent::oklab_lerp;
use crate::assets::MaterialIcon;

/// The JVM `FALLBACK_ART_SHADE`: how far the bottom edge darkens the accent toward black.
const SHADE: f32 = 0.35;
/// The JVM `FALLBACK_ART_ICON_SHARE`: the note's side as a share of the art's shorter side.
const ICON_SHARE: f32 = 0.6;
/// The JVM `FALLBACK_ART_ICON_ALPHA`.
const ICON_ALPHA: f32 = 0.85;
/// `Icons.Default.MusicNote`'s path in its 24-unit viewport (assets/material/filled/music_note.svg).
const MUSIC_NOTE_PATH: &str =
    "M12,3v10.55c-0.59,-0.34,-1.27,-0.55,-2,-0.55c-2.21,0,-4,1.79,-4,4s1.79,4,4,4s4,-1.79,4,-4V7h4V3h-6z";

fn gradient(accent: Hsla) -> Background {
    linear_gradient(180., linear_color_stop(accent, 0.), linear_color_stop(oklab_lerp(accent, black(), SHADE), 1.))
}

/// The fallback filling its parent; `side` is the shorter side it is drawn at, for the note's size.
pub fn fallback_art(accent: Hsla, side: Pixels) -> Div {
    div()
        .size_full()
        .bg(gradient(accent))
        .flex()
        .items_center()
        .justify_center()
        .child(Icon::new(MaterialIcon::MusicNote).size(side * ICON_SHARE).text_color(white().opacity(ICON_ALPHA)))
}

/// Just the gradient, for the backdrop the fallback square sits on.
pub fn fallback_backdrop(accent: Hsla) -> Div {
    div().size_full().bg(gradient(accent))
}

/// The JVM `fallbackArtBitmap`, as a `px`-square PNG file in `dir` (one per accent, written once), for
/// the OS media surface, which takes art as a url.
pub fn fallback_art_file(accent: Hsla, px: u32, dir: &Path) -> anyhow::Result<PathBuf> {
    let (top, bottom) = (hex(accent), hex(oklab_lerp(accent, black(), SHADE)));
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

fn hex(color: Hsla) -> String {
    let rgba = color.to_rgb();
    let channel = |value: f32| ((value * 255. + 0.5) as i32).clamp(0, 255);
    format!("{:02X}{:02X}{:02X}", channel(rgba.r), channel(rgba.g), channel(rgba.b))
}
