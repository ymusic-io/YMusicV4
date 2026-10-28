#ifndef YMUSIC_MSE_D3D11_H
#define YMUSIC_MSE_D3D11_H

#ifdef _WIN32

/*
 * Windows D3D11 shared device. One per process (mirrors the Linux `mse_hwdev.c`
 * singleton): the same ID3D11Device backs the ffmpeg d3d11va decoder AND the
 * HWND flip-model swapchain, so the decoder's ID3D11Texture2D can be bound as
 * a shader resource with no cross-device copy.
 */

#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <jni.h>

struct AVBufferRef;
struct AVCodecContext;
enum AVPixelFormat;

#ifdef __cplusplus
extern "C" {
#endif

/* NULL on first-call failure (no feature-level-11_1 D3D11 device available).
   Borrowed — do not Release the returned device directly. */
ID3D11Device *mse_d3d11_device(void);
ID3D11DeviceContext *mse_d3d11_context(void);
/* The mutex AVD3D11VADeviceContext.lock/unlock callbacks use. Also serializes
   immediate-context access between the decoder thread and the present thread
   (D3D11's immediate context is not free-threaded). */
void mse_d3d11_lock(void);
void mse_d3d11_unlock(void);

/* Fresh AVBufferRef wrapping the shared d3d11va hwdevice; NULL = no device.
   Caller av_buffer_unref's when done. */
struct AVBufferRef *mse_d3d11_hwdev_ref(void);
/* get_format filter: AV_PIX_FMT_D3D11 if offered, else first software format. */
enum AVPixelFormat mse_d3d11_pick(struct AVCodecContext *ctx, const enum AVPixelFormat *fmts);

/* HWND of a heavyweight java.awt.Canvas via JAWT, or NULL. */
HWND mse_d3d11_jawt_hwnd(JNIEnv *env, jobject component);

/* HWND composition-surface present path. Attach/detach are called from the
   JAWT bridge; show/resize run from the decoder thread. All take the d3d11
   lock internally — no external synchronization required. */
int mse_d3d11_present_attach(HWND hwnd, const void *owner);
/* No-op unless `owner` made the current attach. */
void mse_d3d11_present_detach(const void *owner);
void mse_d3d11_present_resize(int w, int h);
int mse_d3d11_present_show(ID3D11Texture2D *tex, UINT array_slice,
                           int src_w, int src_h, int dst_w, int dst_h);
/* 1 if a swapchain is live on the given HWND. */
int mse_d3d11_present_active_for(HWND hwnd);

#ifdef __cplusplus
}
#endif

#endif /* _WIN32 */
#endif
