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
struct AVCodec;
struct AVCodecContext;
struct AVCodecParameters;
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
/* 1 once the device was removed or reset (TDR, driver update): no new D3D11 decoders or
   swapchains for the rest of the process. Recreating the device is not supported. */
int mse_d3d11_lost(void);
/* Asks the device (GetDeviceRemovedReason); the first removal seen logs it once, latches
   mse_d3d11_lost and drops the swapchain. Returns mse_d3d11_lost(). */
int mse_d3d11_check_lost(void);

/* Fresh AVBufferRef wrapping the shared d3d11va hwdevice; NULL = no device.
   Caller av_buffer_unref's when done. */
struct AVBufferRef *mse_d3d11_hwdev_ref(void);
/* get_format filter: AV_PIX_FMT_D3D11 when offered for an 8-bit 4:2:0 stream (the only kind
   the present shader samples), else the first software format. */
enum AVPixelFormat mse_d3d11_pick(struct AVCodecContext *ctx, const enum AVPixelFormat *fmts);
/* 0 when the stream parameters already show it is not 8-bit 4:2:0 (format, bit depth, VP9 /
   HEVC / AV1 profile, av1C). Unknown passes; mse_d3d11_pick catches it at decode time. */
int mse_d3d11_par_ok(const struct AVCodecParameters *par);
/* 1 when `codec` has a d3d11va hwaccel and the GPU can run it. VP9/AV1 need the GPU's
   profile-0 decoder (enumerated once); H.264/HEVC only need the device, since their native
   decoders fall back to software internally while native av1 has no software path. */
int mse_d3d11_can_decode(const struct AVCodec *codec);

/* HWND of a heavyweight java.awt.Canvas via JAWT, or NULL. */
HWND mse_d3d11_jawt_hwnd(JNIEnv *env, jobject component);

/* HWND composition-surface present path. Attach/detach are called from the
   JAWT bridge; show runs from the decoder thread. All take the d3d11
   lock internally — no external synchronization required. */
int mse_d3d11_present_attach(HWND hwnd, const void *owner);
/* No-op unless `owner` made the current attach. */
void mse_d3d11_present_detach(const void *owner);
/* Releases the swapchain whoever owns it; mse_d3d11_check_lost calls it on device removal. */
void mse_d3d11_present_drop_all(void);
/* Letterboxes the NV12 slice into the HWND's client rect. 0 = presented, -2 = no swapchain
   for `owner` (none attached, or another engine's): drop the frame, -1 = failed. */
int mse_d3d11_present_show(ID3D11Texture2D *tex, UINT array_slice, int src_w, int src_h,
                           const void *owner);

#ifdef __cplusplus
}
#endif

#endif /* _WIN32 */
#endif
