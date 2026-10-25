#ifndef YMUSIC_QUICKJS_MSE_H
#define YMUSIC_QUICKJS_MSE_H

#include "quickjs/quickjs.h"
#include "qjs_mse_vt.h"

#ifdef __cplusplus
extern "C" {
#endif

void qjs_mse_set_vt(const QjsMseVt *vt);

/* 1 when the decoder createDecoderByType(mime) picks takes width x height (at fps when > 0),
   0 when it does not, -1 unknown. */
typedef int (*QjsMseVideoSizeOk)(const char *mime, int width, int height, double fps);
void qjs_mse_set_video_size_ok(QjsMseVideoSizeOk fn);
void qjs_mse_install(JSContext *ctx);

#ifdef __cplusplus
}
#endif

#endif
