//! The JVM app's `ActionIcon`: an icon-only action, its glyph centered in a fixed circular hit target.

use gpui_kit::component::button::{Button, ButtonVariants};
use gpui_kit::component::{Icon, Sizable};
use gpui_kit::*;

/// The standard glyph, with 8 around it in the standard hit target.
const ICON: f32 = 24.;
pub const HIT: f32 = 40.;

/// The size to draw a Lucide glyph at to cover what the JVM app's Material glyph of `material_size`
/// does: Lucide's strokes reach the edges of its 24 box, Material's ink keeps within a 20 live area.
/// Even, so it centers exactly in an even hit target.
pub fn lucide_size(material_size: f32) -> f32 {
    (material_size * 20. / 24. / 2.).round() * 2.
}

/// A ghost button: a Lucide `icon` matching the standard glyph in the standard hit target, `label`
/// spoken and shown as its tooltip.
pub fn action_icon(id: impl Into<ElementId>, icon: impl Into<Icon>, label: impl Into<SharedString>) -> Button {
    sized_action_icon(id, icon, label, ICON, HIT)
}

/// [`action_icon`] with a Lucide glyph matching the JVM app's `iconSize` = `icon_size`, in a `hit_size`
/// circle.
pub fn sized_action_icon(
    id: impl Into<ElementId>,
    icon: impl Into<Icon>,
    label: impl Into<SharedString>,
    icon_size: f32,
    hit_size: f32,
) -> Button {
    glyph_action_icon(id, icon, label, lucide_size(icon_size), hit_size)
}

/// [`action_icon`] with the glyph drawn at `glyph_size` as is: for a glyph from the JVM app's own
/// drawables, not Lucide.
pub fn glyph_action_icon(
    id: impl Into<ElementId>,
    icon: impl Into<Icon>,
    label: impl Into<SharedString>,
    glyph_size: f32,
    hit_size: f32,
) -> Button {
    let label = label.into();
    hit_target(id, hit_size)
        .child(icon.into().with_size(px(glyph_size)))
        .accessibility_label(label.clone())
        .tooltip(label)
}

/// An empty ghost button filling a `hit_size` circle, for content the caller draws (a spinner while busy).
pub fn hit_target(id: impl Into<ElementId>, hit_size: f32) -> Button {
    // The glyph goes in as a child: gpui-kit sizes an `.icon(..)` from the button's size.
    Button::new(id).ghost().size(px(hit_size)).p_0().rounded_full()
}

/// The JVM app's chip: 13.5 text inset 12 across and 7 above and below, so the label sits centered.
pub fn chip(button: Button) -> Button {
    button.rounded_full().h_auto().px(px(CHIP_PADDING_X)).py(px(CHIP_PADDING_Y)).text_size(px(CHIP_TEXT))
}

const CHIP_PADDING_X: f32 = 12.;
const CHIP_PADDING_Y: f32 = 7.;
const CHIP_TEXT: f32 = 13.5;
