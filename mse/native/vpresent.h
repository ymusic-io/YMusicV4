#ifndef YMUSIC_VPRESENT_H
#define YMUSIC_VPRESENT_H

/*
 * Video presenter: where decoded pictures go for one surface. Engines show frames under a bind
 * token; a presenter in CPU mode converts them to BGRA for the sink instead.
 * Lock order: engine lock → present_mu → mu; present_mu → d3d11 lock.
 */

#include <stdint.h>

struct AVFrame;
struct AVCodecParameters;

#ifdef __cplusplus
extern "C" {
#endif

typedef struct VPresenter VPresenter;

enum { VP_MODE_NONE = 0, VP_MODE_GPU = 1, VP_MODE_CPU = 2 };
/* VP_DROPPED counts as shown for scheduling. */
enum { VP_FAIL = -1, VP_OK = 0, VP_DROPPED = 1 };

typedef struct {
    int matrix; /* AVCOL_SPC_BT709, AVCOL_SPC_SMPTE170M or AVCOL_SPC_BT2020_NCL */
    int full_range;
} VpColor;

typedef struct {
    /* CPU mode; runs under present_mu. bgra stays valid until the second show after this one. */
    void (*on_frame)(void *user, int w, int h, const uint8_t *bgra);
    /* Any thread, never under mu. */
    void (*on_mode)(void *user, int mode);
    /* The presenter is freed: the sink's last call. Not called when the create fails. */
    void (*release)(void *user);
    void *user;
} VpSink;

VPresenter *vpresent_create_cpu(const VpSink *s);
#ifdef _WIN32
/* A swapchain on the HWND; NULL when D3D11 cannot present there. */
VPresenter *vpresent_create_d3d11(void *hwnd, const VpSink *s);
#endif
#ifdef __APPLE__
/* Shows into a borrowed AVSampleBufferDisplayLayer (iOS: the Kotlin-owned one); never releases it. */
VPresenter *vpresent_create_vt_layer(void *layer, const VpSink *s);
/* macOS JVM (mse_vt_present.m): its own layer in an NSView over the JAWT Canvas (JNIEnv *env,
   jobject canvas); NULL when JAWT gives no surface. Turns CPU if the view cannot be installed. */
VPresenter *vpresent_create_vt_overlay(void *env, void *canvas, const VpSink *s);
/* Overlay presenters only: the Canvas screen rect (AWT y-down), and hiding the view (the
   unhide waits for the next presented frame). No-ops for other presenters. */
void vpresent_vt_layout(VPresenter *p, int x, int y, int w, int h);
void vpresent_vt_set_hidden(VPresenter *p, int hidden);
/* VT presenters: mse_vt_picture_ok / mse_vt_resume_presents on its layer. */
int vpresent_vt_picture_ok(VPresenter *p);
void vpresent_vt_resume(VPresenter *p);
#endif
void vpresent_retain(VPresenter *p);
void vpresent_release(VPresenter *p);
/* Surface gone: drops it now; later shows are VP_DROPPED. */
void vpresent_detach(VPresenter *p);
/* Shows from any other engine are VP_DROPPED. A new engine after another flushes to black. */
void vpresent_bind(VPresenter *p, const void *engine);
/* No-op unless `engine` is bound. */
void vpresent_unbind(VPresenter *p, const void *engine);
/* CPU fit box: min-fit, never upscale; 0 on a side = unconstrained, 0×0 = native. */
void vpresent_set_size(VPresenter *p, int w, int h);
/* 1 when frames of av_pix_fmt are drawn without a CPU copy. */
int vpresent_zero_copy(VPresenter *p, int av_pix_fmt);
int vpresent_mode(VPresenter *p);
int vpresent_show_frame(VPresenter *p, const void *engine, const struct AVFrame *f, VpColor c);
#ifdef __APPLE__
int vpresent_show_cvpb(VPresenter *p, const void *engine, void *cvpb, VpColor c);
#endif
void vpresent_flush(VPresenter *p, const void *engine);
/* Last picture as XRGB pixels (CPU mode, or a GPU kind that reads its picture back); returns its
   pixel count, copies only when it fits cap_px. */
int vpresent_snapshot(VPresenter *p, uint32_t *xrgb, int cap_px, int *w, int *h);

/* Frame tags first, then the stream's; untagged is BT.709 above 576 lines, else BT.601. The
   container's range wins over the frame's: VideoToolbox reports full range for tv-range HEVC. */
VpColor vp_color_of(const struct AVFrame *f, const struct AVCodecParameters *par);

/* Frame → BGRA: HW download, colour, fit (as vpresent_set_size). Two output buffers: *bgra stays
   valid until the second convert after this one. Not thread-safe. */
typedef struct VpCpu VpCpu;
VpCpu *vp_cpu_create(void);
void vp_cpu_destroy(VpCpu *c);
int vp_cpu_convert(VpCpu *c, const struct AVFrame *f, VpColor col, int fit_w, int fit_h,
                   const uint8_t **bgra, int *w, int *h);
#ifdef __APPLE__
int vp_cpu_convert_cvpb(VpCpu *c, void *cvpb, VpColor col, int fit_w, int fit_h,
                        const uint8_t **bgra, int *w, int *h);
#endif

#ifdef __cplusplus
}
#endif

#endif
