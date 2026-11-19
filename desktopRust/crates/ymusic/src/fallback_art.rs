//! The JVM `FallbackArt.kt`: the picture for art with no source, or whose load failed — one of sixteen
//! colours picked by a hash of the art's image key, fading darker downward under a music note.

use std::hash::{DefaultHasher, Hash, Hasher};
use std::path::{Path, PathBuf};

use gpui_kit::component::Icon;
use gpui_kit::*;

use crate::assets::MaterialIcon;
use crate::youtube_thumbnails::art_request_sources;

const ICON_SHARE: f32 = 0.6;
const ICON_ALPHA: f32 = 0.85;
/// `Icons.Default.MusicNote`'s path in its 24-unit viewport (assets/material/filled/music_note.svg).
const MUSIC_NOTE_PATH: &str =
    "M12,3v10.55c-0.59,-0.34,-1.27,-0.55,-2,-0.55c-2.21,0,-4,1.79,-4,4s1.79,4,4,4s4,-1.79,4,-4V7h4V3h-6z";

/// The JVM `FALLBACK_ART_PALETTE`: deep enough that the white note reads on each.
const PALETTE: [u32; 16] = [
    0xC62828, 0xAD1457, 0x6A1B9A, 0x4527A0, 0x283593, 0x1565C0, 0x0277BD, 0x00838F, 0x00695C, 0x2E7D32, 0x558B2F,
    0x827717, 0xE65100, 0xD84315, 0x4E342E, 0x37474F,
];

/// The gradient's top and bottom (`0xRRGGBB`) for `key`, the art's image key.
fn colors(key: Option<&str>) -> (u32, u32) {
    let mut hasher = DefaultHasher::new();
    key.hash(&mut hasher);
    let top = PALETTE[(hasher.finish() % PALETTE.len() as u64) as usize];
    let shade = |shift: u32| (((top >> shift) & 0xFF) * 65 / 100) << shift;
    (top, shade(16) | shade(8) | shade(0))
}

fn gradient(key: Option<&str>) -> Background {
    let (top, bottom) = colors(key);
    linear_gradient(180., linear_color_stop(rgb(top), 0.), linear_color_stop(rgb(bottom), 1.))
}

/// The fallback filling its parent, the note sized by the parent's height.
pub fn fallback_art(key: Option<&str>) -> Div {
    div().size_full().bg(gradient(key)).flex().items_center().justify_center().child(
        Icon::new(MaterialIcon::MusicNote)
            .h(relative(ICON_SHARE))
            .aspect_ratio(1.)
            .text_color(white().opacity(ICON_ALPHA)),
    )
}

/// Just the gradient, for the backdrop the fallback square sits on.
pub fn fallback_backdrop(key: Option<&str>) -> Div {
    div().size_full().bg(gradient(key))
}

/// The JVM `fallbackArtKey`: the fallback key of art `url`, its image identity.
pub fn fallback_art_key(url: Option<&str>) -> Option<String> {
    url.map(|url| art_request_sources(url).0)
}

/// The JVM `localArtFallback`: an async image's error for a local file's art (Library, local queue items).
pub fn local_art_fallback(url: Option<&str>) -> Div {
    fallback_art(fallback_art_key(url).as_deref())
}

/// The JVM `fallbackArtBitmap`, as a `px`-square PNG file in `dir` (one per colour, written once), for
/// the OS media surface, which takes art as a url.
pub fn fallback_art_file(key: Option<&str>, px: u32, dir: &Path) -> anyhow::Result<PathBuf> {
    let (top, bottom) = colors(key);
    let (top, bottom) = (format!("{top:06X}"), format!("{bottom:06X}"));
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
