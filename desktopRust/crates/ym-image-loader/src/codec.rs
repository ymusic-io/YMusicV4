//! The JVM `ImageCodec.kt` + its skiko actuals: scaled decode (the full-size image is dropped as soon
//! as it is scaled), the 16:9 letterbox crop, and the scaled cache's lossy WebP.

use std::fs::File;
use std::io::{BufRead, BufReader, Cursor, Seek, Write};
use std::path::Path;

use image::imageops::{self, FilterType};
use image::{DynamicImage, ImageDecoder, ImageReader, RgbaImage};

use crate::request::{ImageLoadError, ImageScale, Reason, SourceCrop};

/// The JVM `SCALED_WEBP_QUALITY`.
pub const SCALED_WEBP_QUALITY: f32 = 85.;

/// WebP's largest side.
const WEBP_MAX_SIDE: u32 = 16383;

/// A decoded image (RGBA) and the pixel size of the source it came from (after any crop).
pub struct DecodedImage {
    pub image: RgbaImage,
    pub source_width: u32,
    pub source_height: u32,
}

impl DecodedImage {
    /// The decode is smaller than its source: a scaled copy saves this work next time.
    pub fn downscaled(&self) -> bool {
        self.image.width() < self.source_width || self.image.height() < self.source_height
    }

    /// This image with `crop` applied; the source size follows the crop.
    pub fn cropped(self, crop: SourceCrop) -> Self {
        match crop {
            SourceCrop::None => self,
            SourceCrop::Letterbox16x9 => {
                let width = self.image.width();
                let height = width * 9 / 16;
                if height == 0 || height >= self.image.height() {
                    return self;
                }
                let top = (self.image.height() - height) / 2;
                let band = imageops::crop_imm(&self.image, 0, top, width, height).to_image();
                Self { image: band, source_width: self.source_width, source_height: self.source_width * 9 / 16 }
            }
        }
    }
}

/// An undecodable image, classified (the JVM `decodeChecked`).
fn undecodable(url: &str, error: image::ImageError) -> ImageLoadError {
    ImageLoadError::with_cause(Reason::Undecodable, format!("undecodable image: {url}"), error)
}

/// Blocking: decode `reader` into an image covering (or, with `Fit`, fitting) `size`, aspect kept, never
/// upscaled. The full-size decode is dropped as soon as it is scaled. `url` names it in errors.
pub fn decode_scaled_reader(
    reader: impl BufRead + Seek,
    size: (u32, u32),
    scale: ImageScale,
    url: &str,
) -> Result<DecodedImage, ImageLoadError> {
    let reader = ImageReader::new(reader)
        .with_guessed_format()
        .map_err(|error| ImageLoadError::with_cause(Reason::Undecodable, format!("unreadable image: {url}"), error))?;
    let mut decoder = reader.into_decoder().map_err(|error| undecodable(url, error))?;
    let orientation = decoder.orientation().map_err(|error| undecodable(url, error))?;
    let mut full = DynamicImage::from_decoder(decoder).map_err(|error| undecodable(url, error))?;
    full.apply_orientation(orientation);
    Ok(scale_down(full, size, scale))
}

/// Blocking: [`decode_scaled_reader`] over in-memory bytes.
pub fn decode_scaled(
    bytes: &[u8],
    size: (u32, u32),
    scale: ImageScale,
    url: &str,
) -> Result<DecodedImage, ImageLoadError> {
    decode_scaled_reader(Cursor::new(bytes), size, scale, url)
}

/// Blocking: [`decode_scaled_reader`] streamed from a file; a missing file is `NotFound`.
pub fn decode_scaled_file(
    path: &Path,
    size: (u32, u32),
    scale: ImageScale,
    url: &str,
) -> Result<DecodedImage, ImageLoadError> {
    let file = File::open(path).map_err(|error| {
        let reason = if error.kind() == std::io::ErrorKind::NotFound { Reason::NotFound } else { Reason::Undecodable };
        ImageLoadError::with_cause(reason, format!("unreadable image file: {url}"), error)
    })?;
    decode_scaled_reader(BufReader::new(file), size, scale, url)
}

/// Blocking: an already decoded picture (a video frame) scaled as a decode is, the original dropped.
pub fn scale_decoded(image: RgbaImage, size: (u32, u32), scale: ImageScale) -> DecodedImage {
    scale_down(DynamicImage::ImageRgba8(image), size, scale)
}

/// The skiko `decodeScaled` clamp: cover scales by the larger ratio, fit by the smaller; at or above
/// 1 the decode is kept as is.
fn scale_down(full: DynamicImage, size: (u32, u32), scale: ImageScale) -> DecodedImage {
    let (width, height) = (full.width(), full.height());
    let ratio_x = size.0 as f32 / width.max(1) as f32;
    let ratio_y = size.1 as f32 / height.max(1) as f32;
    let ratio = match scale {
        ImageScale::Fit => ratio_x.min(ratio_y),
        ImageScale::Cover => ratio_x.max(ratio_y),
    };
    if ratio >= 1. {
        return DecodedImage { image: full.into_rgba8(), source_width: width, source_height: height };
    }
    let scaled_width = ((width as f32 * ratio).round() as u32).max(1);
    let scaled_height = ((height as f32 * ratio).round() as u32).max(1);
    let scaled = match full {
        DynamicImage::ImageRgba8(rgba) => imageops::resize(&rgba, scaled_width, scaled_height, FilterType::Triangle),
        other => imageops::resize(&other.into_rgba8(), scaled_width, scaled_height, FilterType::Triangle),
    };
    DecodedImage { image: scaled, source_width: width, source_height: height }
}

/// Encodes an already-scaled image as lossy WebP into `sink` (the scaled cache's format: a fraction of
/// PNG's size for photos, alpha kept). libwebp encodes in memory first.
pub fn encode_webp(image: &RgbaImage, sink: &mut impl Write) -> std::io::Result<()> {
    if image.width() > WEBP_MAX_SIDE || image.height() > WEBP_MAX_SIDE {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("WebP encode: {}x{} exceeds the format's size", image.width(), image.height()),
        ));
    }
    let encoder = webp::Encoder::from_rgba(image.as_raw(), image.width(), image.height());
    let encoded = encoder.encode_simple(false, SCALED_WEBP_QUALITY).map_err(|error| {
        std::io::Error::other(format!("WebP encode failed ({}x{}): {error:?}", image.width(), image.height()))
    })?;
    sink.write_all(&encoded)
}
