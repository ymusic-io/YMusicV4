#ifdef _WIN32

#include "mse_d3d11.h"
#include "mse_ffmpeg.h"
#include "vpresent_impl.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static ID3D11Device *g_dev;
static ID3D11DeviceContext *g_ctx;
/* Recursive: AVD3D11VADeviceContext.lock must be. */
static pthread_mutex_t g_mu;
static int g_mu_ready;

static void d3d11_init(void)
{
    static const D3D_FEATURE_LEVEL want[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
    };
    D3D_FEATURE_LEVEL got = 0;
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    HRESULT hr;
    pthread_mutexattr_t at;

    pthread_mutexattr_init(&at);
    pthread_mutexattr_settype(&at, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&g_mu, &at);
    pthread_mutexattr_destroy(&at);
    g_mu_ready = 1;

    hr = D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, flags,
                           want, (UINT)(sizeof(want) / sizeof(want[0])),
                           D3D11_SDK_VERSION, &g_dev, &got, &g_ctx);
    if (FAILED(hr)) {
        /* Some drivers reject VIDEO_SUPPORT; such a device still presents, decode stays software. */
        hr = D3D11CreateDevice(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL,
                               D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                               want, (UINT)(sizeof(want) / sizeof(want[0])),
                               D3D11_SDK_VERSION, &g_dev, &got, &g_ctx);
    }
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 CreateDevice failed hr=0x%08lx (software fallback)",
                  (unsigned long)hr);
        g_dev = NULL;
        g_ctx = NULL;
        return;
    }
    if (got < D3D_FEATURE_LEVEL_11_0) {
        mse_critf(MSE_LOG_WARN, "d3d11 feature level 0x%x < 11_0 (software fallback)", (unsigned)got);
        if (g_ctx) ID3D11DeviceContext_Release(g_ctx);
        if (g_dev) ID3D11Device_Release(g_dev);
        g_ctx = NULL;
        g_dev = NULL;
        return;
    }
    mse_critf(MSE_LOG_INFO, "d3d11 device feature-level=0x%x", (unsigned)got);
}

ID3D11Device *mse_d3d11_device(void)
{
    pthread_once(&g_once, d3d11_init);
    return g_dev;
}

ID3D11DeviceContext *mse_d3d11_context(void)
{
    pthread_once(&g_once, d3d11_init);
    return g_ctx;
}

void mse_d3d11_lock(void)
{
    pthread_once(&g_once, d3d11_init);
    if (g_mu_ready)
        pthread_mutex_lock(&g_mu);
}

void mse_d3d11_unlock(void)
{
    if (g_mu_ready)
        pthread_mutex_unlock(&g_mu);
}

static volatile LONG g_lost;

int mse_d3d11_lost(void)
{
    return InterlockedCompareExchange(&g_lost, 0, 0) != 0;
}

int mse_d3d11_check_lost(void)
{
    HRESULT why;
    if (mse_d3d11_lost())
        return 1;
    pthread_once(&g_once, d3d11_init);
    if (!g_dev)
        return 0;
    why = ID3D11Device_GetDeviceRemovedReason(g_dev);
    if (why == S_OK)
        return 0;
    if (InterlockedExchange(&g_lost, 1) == 0) {
        mse_critf(MSE_LOG_WARN, "d3d11 device removed reason=0x%08lx: hardware video off",
                  (unsigned long)why);
        vpresent_d3d11_device_lost();
    }
    return 1;
}

#endif /* _WIN32 */
