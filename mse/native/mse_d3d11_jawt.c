#ifdef _WIN32

#include "mse_d3d11.h"
#include "mse_ffmpeg.h"

#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

#include <windows.h>
#include <stdint.h>

/* JAWT_GetAWT is in jawt.dll (JDK ships it next to the JVM). The JVM has
   already loaded it by the time this JNI runs; dlsym the same way mse_vt
   does on macOS so there's no link-time dependency on jawt.lib. */
static jboolean (WINAPI *g_jawt_get_awt)(JNIEnv *, JAWT *);

static int bind_jawt(void)
{
    HMODULE h;
    if (g_jawt_get_awt)
        return 1;
    h = GetModuleHandleW(L"jawt.dll");
    if (!h)
        h = LoadLibraryW(L"jawt.dll");
    if (!h) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt.dll not loaded");
        return 0;
    }
    g_jawt_get_awt = (jboolean (WINAPI *)(JNIEnv *, JAWT *))GetProcAddress(h, "_JAWT_GetAWT@8");
    if (!g_jawt_get_awt)
        g_jawt_get_awt = (jboolean (WINAPI *)(JNIEnv *, JAWT *))GetProcAddress(h, "JAWT_GetAWT");
    if (!g_jawt_get_awt) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetProcAddress failed");
        return 0;
    }
    return 1;
}

HWND mse_d3d11_jawt_hwnd(JNIEnv *env, jobject component)
{
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    JAWT_Win32DrawingSurfaceInfo *win;
    HWND hwnd = NULL;
    jclass canvas;

    if (!env || !component)
        return NULL;
    if (!bind_jawt())
        return NULL;
    canvas = (*env)->FindClass(env, "java/awt/Canvas");
    if (!canvas || !(*env)->IsInstanceOf(env, component, canvas)) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        mse_critf(MSE_LOG_WARN, "d3d11 jawt not a Canvas");
        return NULL;
    }
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_7;
    if (g_jawt_get_awt(env, &awt) == JNI_FALSE) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetAWT failed");
        return NULL;
    }
    ds = awt.GetDrawingSurface(env, component);
    if (!ds) {
        mse_critf(MSE_LOG_WARN, "d3d11 jawt GetDrawingSurface failed");
        return NULL;
    }
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(ds);
        mse_critf(MSE_LOG_WARN, "d3d11 jawt Lock failed");
        return NULL;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (dsi && dsi->platformInfo) {
        win = (JAWT_Win32DrawingSurfaceInfo *)dsi->platformInfo;
        hwnd = win->hwnd;
    }
    if (dsi)
        ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    if (!hwnd)
        mse_critf(MSE_LOG_WARN, "d3d11 jawt drawing surface has no HWND");
    return hwnd;
}

#endif /* _WIN32 */
