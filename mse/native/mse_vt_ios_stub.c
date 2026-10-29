#ifdef __APPLE__
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
/* Desktop JAWT overlay lives in mse_vt_present.m (not built for iOS).
   -undefined dynamic_lookup would leave these NULL and SEGV on first frame. */
void mse_vt_set_overlay_hidden(int hid) { (void)hid; }
void mse_vt_layout_screen(int x, int y, int w, int h)
{
    (void)x;
    (void)y;
    (void)w;
    (void)h;
}
void *mse_vt_attach_component(void *env, void *component, const void *owner)
{
    (void)env;
    (void)component;
    (void)owner;
    return 0;
}
void mse_vt_order_window_below(void *env, void *below, void *above)
{
    (void)env;
    (void)below;
    (void)above;
}
void mse_vt_prepare_fullscreen(void *env, void *primary)
{
    (void)env;
    (void)primary;
}
void mse_vt_toggle_fullscreen(void *env, void *primary)
{
    (void)env;
    (void)primary;
}
#endif
#endif
