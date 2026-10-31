#ifndef YMUSIC_QUICKJS_MSE_H
#define YMUSIC_QUICKJS_MSE_H

#include "quickjs/quickjs.h"
#include "qjs_mse_vt.h"

#ifdef __cplusplus
extern "C" {
#endif

void qjs_mse_set_vt(const QjsMseVt *vt);
void qjs_mse_install(JSContext *ctx);

#ifdef __cplusplus
}
#endif

#endif
