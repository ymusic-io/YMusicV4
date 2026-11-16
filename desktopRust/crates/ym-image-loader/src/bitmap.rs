//! The decoded form the loader caches and hands out (the JVM `ImageBitmap`): the UI's own drawable
//! type, made once per decode so a memory hit draws without a new upload.

use std::sync::Arc;

use image::RgbaImage;

pub trait Bitmap: Clone + Send + Sync + 'static {
    fn from_rgba(image: RgbaImage) -> Self;

    /// A copy of the pixels, for the scaled disk cache.
    fn to_rgba(&self) -> RgbaImage;

    fn width(&self) -> u32;

    fn height(&self) -> u32;

    /// Estimated resident bytes (w×h×4), the memory cache's measure.
    fn byte_size(&self) -> u64 {
        u64::from(self.width()) * u64::from(self.height()) * 4
    }
}

impl Bitmap for Arc<RgbaImage> {
    fn from_rgba(image: RgbaImage) -> Self {
        Arc::new(image)
    }

    fn to_rgba(&self) -> RgbaImage {
        self.as_ref().clone()
    }

    fn width(&self) -> u32 {
        RgbaImage::width(self)
    }

    fn height(&self) -> u32 {
        RgbaImage::height(self)
    }
}
