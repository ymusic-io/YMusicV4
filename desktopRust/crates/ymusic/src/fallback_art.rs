//! The JVM `FallbackArt.kt`: the art of a local item that has none, or whose every art source failed —
//! one of sixteen colours picked by a hash of the art url, fading darker downward under a music note.

use std::hash::{DefaultHasher, Hash, Hasher};

use image::RgbaImage;

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

/// The JVM `fallbackArtBitmap`: drawn at `width`×`height`; `key` is the art url. Blocking.
pub fn fallback_art_image(key: &str, (width, height): (u32, u32)) -> anyhow::Result<RgbaImage> {
    let mut hasher = DefaultHasher::new();
    key.hash(&mut hasher);
    let top = PALETTE[(hasher.finish() % PALETTE.len() as u64) as usize];
    let shade = |shift: u32| (((top >> shift) & 0xFF) * 65 / 100) << shift;
    let bottom = shade(16) | shade(8) | shade(0);
    let side = width.min(height) as f32 * ICON_SHARE;
    let (left, upper) = ((width as f32 - side) / 2., (height as f32 - side) / 2.);
    let note_scale = side / 24.;
    let svg = format!(
        r##"<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}"><defs><linearGradient id="shade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#{top:06X}"/><stop offset="1" stop-color="#{bottom:06X}"/></linearGradient></defs><rect width="{width}" height="{height}" fill="url(#shade)"/><path transform="translate({left} {upper}) scale({note_scale})" fill="#FFFFFF" fill-opacity="{ICON_ALPHA}" d="{MUSIC_NOTE_PATH}"/></svg>"##
    );
    let tree = resvg::usvg::Tree::from_str(&svg, &resvg::usvg::Options::default())?;
    let mut pixmap =
        resvg::tiny_skia::Pixmap::new(width, height).ok_or_else(|| anyhow::anyhow!("no {width}x{height} pixmap"))?;
    resvg::render(&tree, resvg::tiny_skia::Transform::identity(), &mut pixmap.as_mut());
    let pixels = pixmap.pixels().iter().flat_map(|pixel| {
        let color = pixel.demultiply();
        [color.red(), color.green(), color.blue(), color.alpha()]
    });
    RgbaImage::from_raw(width, height, pixels.collect()).ok_or_else(|| anyhow::anyhow!("{width}x{height} art"))
}
