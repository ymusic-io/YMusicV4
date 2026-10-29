#ifndef YMUSIC_MSE_CAPS_H
#define YMUSIC_MSE_CAPS_H

/*
 * The device's video decoders as the JS player's MediaCapabilities queries see them: one table per
 * process, filled once off the QuickJS thread (platform probe + the bundled software decoders, or
 * Android's MediaCodecList rows from Kotlin), then answered by lookup only.
 */

#ifdef __cplusplus
extern "C" {
#endif

enum { MSE_CAP_H264 = 1, MSE_CAP_HEVC = 2, MSE_CAP_VP8 = 3, MSE_CAP_VP9 = 4, MSE_CAP_AV1 = 5 };
/* The widest chroma subsampling a row decodes. */
enum { MSE_CAP_420 = 1, MSE_CAP_422 = 2, MSE_CAP_444 = 3 };

typedef struct MseCapRow {
    int codec;
    int max_bits;
    int chroma;
    /* Either orientation. */
    int max_w, max_h;
    /* At max_w x max_h; a smaller picture gets the same pixel rate. 0 = no limit. */
    double max_fps;
    int hw;
} MseCapRow;

#define MSE_CAPS_SUPPORTED 1
#define MSE_CAPS_SMOOTH 2
#define MSE_CAPS_EFFICIENT 4
/* This host has no table (Android until the MediaCodec rows are set). */
#define MSE_CAPS_NONE (-1)
/* The probe is still running. */
#define MSE_CAPS_PENDING (-2)

/* Starts the platform probe on its own thread, once. No-op on Android. Any thread. */
void mse_caps_start(void);
/* MSE_CAPS_* bits for a MediaCapabilities config (every codec in content_type must pass), or
   MSE_CAPS_NONE / MSE_CAPS_PENDING. w, h, fps <= 0 = any. Never waits for the probe. */
int mse_caps_decoding_info(const char *content_type, int w, int h, double fps, double bitrate);

/* Replaces the table with n rows of MSE_CAP_ROW_INTS ints:
   {codec, max_bits, chroma, max_w, max_h, max_fps (0 = no limit), hw}. */
#define MSE_CAP_ROW_INTS 7
void mse_caps_set_rows(const int *rows, int n);

#ifdef __cplusplus
}
#endif

#endif
