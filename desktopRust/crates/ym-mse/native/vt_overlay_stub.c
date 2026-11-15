/* The JVM's JAWT overlay (mse_vt_present.m) does not exist in this host: video presents into a
 * layer the app owns (vpresent_create_vt_layer), whose overlay is always NULL, so these are never
 * reached. They satisfy vpresent_vt.c's references. */
#include <stddef.h>

typedef struct VPresenter VPresenter;

void vt_overlay_bind(void *ov, VPresenter *p) { (void)ov; (void)p; }
void vt_overlay_layout(void *ov, int x, int y, int w, int h) { (void)ov; (void)x; (void)y; (void)w; (void)h; }
void vt_overlay_set_hidden(void *ov, int hidden) { (void)ov; (void)hidden; }
void vt_overlay_reveal(void *ov) { (void)ov; }
void vt_overlay_unhost(void *ov) { (void)ov; }
void vt_overlay_release(void *ov) { (void)ov; }
