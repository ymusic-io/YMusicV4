#ifdef _WIN32

#include "vpresent_impl.h"
#include "mse_d3d11.h"
#include "mse_ffmpeg.h"

#include <d3d11.h>
#include <dxgi1_2.h>
#include <d3dcompiler.h>

#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* NV12 → BGRA. BT.709 limited-range (16..235 / 16..240).
   The vertex shader draws a triangle covering the viewport (SV_VertexID);
   draw_nv12 sets the viewport to the letterboxed picture rect. 10-bit (P010)
   never gets here: mse_d3d11_pick keeps it in software. */
static const char kShaderHlsl[] =
    "cbuffer K : register(b0) {\n"
    "    float4 crop;\n" /* xy = picture share of the padded texture, zw unused */
    "}\n"
    "Texture2DArray<float>  TexY : register(t0);\n"
    "Texture2DArray<float2> TexUV : register(t1);\n"
    "SamplerState Samp : register(s0);\n"
    "struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };\n"
    "VSOut VSMain(uint id : SV_VertexID)\n"
    "{\n"
    "    VSOut o;\n"
    "    float2 corner = float2((id << 1) & 2, id & 2);\n"
    "    o.uv = corner * crop.xy;\n"
    "    o.pos = float4(corner * float2(2, -2) + float2(-1, 1), 0, 1);\n"
    "    return o;\n"
    "}\n"
    "float4 PSMain(VSOut i) : SV_Target\n"
    "{\n"
    /* A one-slice view always indexes into z=0. */
    "    float yw, yh, yn, cw, ch, cn;\n"
    "    TexY.GetDimensions(yw, yh, yn);\n"
    "    TexUV.GetDimensions(cw, ch, cn);\n"
    /* Stop half a texel short of the padding, or the linear filter blends it in. */
    "    float2 yuv = min(i.uv, crop.xy - 0.5 / float2(yw, yh));\n"
    "    float2 cuv = min(i.uv, crop.xy - 0.5 / float2(cw, ch));\n"
    "    float y  = TexY.Sample(Samp, float3(yuv, 0)).r;\n"
    "    float2 uv = TexUV.Sample(Samp, float3(cuv, 0)).rg;\n"
    "    y  = (y  - 16.0/255.0) * (255.0/219.0);\n"
    "    float cb = (uv.x - 128.0/255.0) * (255.0/224.0);\n"
    "    float cr = (uv.y - 128.0/255.0) * (255.0/224.0);\n"
    /* BT.709 */
    "    float r = y + 1.5748 * cr;\n"
    "    float g = y - 0.1873 * cb - 0.4681 * cr;\n"
    "    float b = y + 1.8556 * cb;\n"
    "    return float4(saturate(r), saturate(g), saturate(b), 1.0);\n"
    "}\n";

/* Device-scoped: one process-wide device, so one pipeline. d3d11 lock. */
static struct {
    ID3D11VertexShader *vs;
    ID3D11PixelShader *ps;
    ID3D11SamplerState *samp;
    ID3D11Buffer *cb;
    ID3D11BlendState *blend;
    ID3D11RasterizerState *rast;
} g_pipe;

/* Swapchain state; every field under the d3d11 lock. */
typedef struct D3dPresent {
    struct D3dPresent *next;
    VPresenter *vp;
    HWND hwnd;
    IDXGISwapChain1 *swap;
    ID3D11RenderTargetView *rtv;
    int bb_w, bb_h;
    int resize_failed;
    int fail_logged;
    int logged_present;
    int log_src_w, log_src_h, log_dst_w, log_dst_h;
} D3dPresent;

/* Live presenters, so a device loss reaches every swapchain. d3d11 lock. */
static D3dPresent *g_live;

/* Per-frame failures: one WARN until a frame presents again. */
static void present_fail(D3dPresent *d, const char *what, HRESULT hr)
{
    if (d->fail_logged)
        return;
    d->fail_logged = 1;
    mse_critf(MSE_LOG_WARN, "d3d11 %s hr=0x%08lx", what, (unsigned long)hr);
}

static void release_views(D3dPresent *d)
{
    if (d->rtv) {
        ID3D11RenderTargetView_Release(d->rtv);
        d->rtv = NULL;
    }
}

static void unbind_rtv(D3dPresent *d)
{
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    if (ctx && d->rtv)
        ID3D11DeviceContext_OMSetRenderTargets(ctx, 0, NULL, NULL);
}

static void release_swapchain(D3dPresent *d)
{
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    unbind_rtv(d);
    release_views(d);
    if (d->swap) {
        IDXGISwapChain1_Release(d->swap);
        d->swap = NULL;
        /* DXGI destroys swapchains lazily; a new flip-model one on the same HWND fails until this. */
        if (ctx) {
            ID3D11DeviceContext_ClearState(ctx);
            ID3D11DeviceContext_Flush(ctx);
        }
    }
    d->hwnd = NULL;
    d->bb_w = d->bb_h = 0;
    d->logged_present = 0;
    d->resize_failed = 0;
    d->fail_logged = 0;
}

static void release_pipeline(void)
{
    if (g_pipe.cb) {
        ID3D11Buffer_Release(g_pipe.cb);
        g_pipe.cb = NULL;
    }
    if (g_pipe.samp) {
        ID3D11SamplerState_Release(g_pipe.samp);
        g_pipe.samp = NULL;
    }
    if (g_pipe.ps) {
        ID3D11PixelShader_Release(g_pipe.ps);
        g_pipe.ps = NULL;
    }
    if (g_pipe.vs) {
        ID3D11VertexShader_Release(g_pipe.vs);
        g_pipe.vs = NULL;
    }
    if (g_pipe.blend) {
        ID3D11BlendState_Release(g_pipe.blend);
        g_pipe.blend = NULL;
    }
    if (g_pipe.rast) {
        ID3D11RasterizerState_Release(g_pipe.rast);
        g_pipe.rast = NULL;
    }
}

static int ensure_backbuffer_rtv(D3dPresent *d, ID3D11Device *dev)
{
    ID3D11Texture2D *bb = NULL;
    HRESULT hr;
    if (d->rtv)
        return 0;
    if (!d->swap)
        return -1;
    hr = IDXGISwapChain1_GetBuffer(d->swap, 0, &IID_ID3D11Texture2D, (void **)&bb);
    if (FAILED(hr) || !bb) {
        present_fail(d, "present GetBuffer", hr);
        return -1;
    }
    hr = ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)bb, NULL, &d->rtv);
    ID3D11Texture2D_Release(bb);
    if (FAILED(hr)) {
        present_fail(d, "present CreateRTV", hr);
        return -1;
    }
    return 0;
}

static int build_pipeline(ID3D11Device *dev)
{
    ID3DBlob *vsBlob = NULL, *psBlob = NULL, *err = NULL;
    D3D11_SAMPLER_DESC sd;
    D3D11_BUFFER_DESC bd;
    D3D11_BLEND_DESC blendDesc;
    D3D11_RASTERIZER_DESC rsDesc;
    const char *what;
    HRESULT hr;

    if (g_pipe.vs && g_pipe.ps && g_pipe.samp && g_pipe.cb && g_pipe.blend && g_pipe.rast)
        return 0;

    hr = D3DCompile(kShaderHlsl, sizeof(kShaderHlsl) - 1, "mse_nv12", NULL, NULL,
                    "VSMain", "vs_4_0", 0, 0, &vsBlob, &err);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 VS compile hr=0x%08lx %s", (unsigned long)hr,
                  err ? (const char *)ID3D10Blob_GetBufferPointer(err) : "");
        if (err) ID3D10Blob_Release(err);
        return -1;
    }
    hr = D3DCompile(kShaderHlsl, sizeof(kShaderHlsl) - 1, "mse_nv12", NULL, NULL,
                    "PSMain", "ps_4_0", 0, 0, &psBlob, &err);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 PS compile hr=0x%08lx %s", (unsigned long)hr,
                  err ? (const char *)ID3D10Blob_GetBufferPointer(err) : "");
        if (err) ID3D10Blob_Release(err);
        ID3D10Blob_Release(vsBlob);
        return -1;
    }
    if (err) ID3D10Blob_Release(err);

    what = "CreateVertexShader";
    hr = ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(vsBlob),
                                         ID3D10Blob_GetBufferSize(vsBlob), NULL, &g_pipe.vs);
    ID3D10Blob_Release(vsBlob);
    if (FAILED(hr)) {
        ID3D10Blob_Release(psBlob);
        goto fail;
    }
    what = "CreatePixelShader";
    hr = ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(psBlob),
                                        ID3D10Blob_GetBufferSize(psBlob), NULL, &g_pipe.ps);
    ID3D10Blob_Release(psBlob);
    if (FAILED(hr))
        goto fail;

    memset(&sd, 0, sizeof(sd));
    sd.Filter = D3D11_FILTER_MIN_MAG_LINEAR_MIP_POINT;
    sd.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sd.ComparisonFunc = D3D11_COMPARISON_NEVER;
    sd.MinLOD = 0;
    sd.MaxLOD = D3D11_FLOAT32_MAX;
    what = "CreateSamplerState";
    hr = ID3D11Device_CreateSamplerState(dev, &sd, &g_pipe.samp);
    if (FAILED(hr))
        goto fail;

    memset(&bd, 0, sizeof(bd));
    bd.ByteWidth = 16; /* float4 crop */
    bd.Usage = D3D11_USAGE_DEFAULT;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    what = "CreateBuffer";
    hr = ID3D11Device_CreateBuffer(dev, &bd, NULL, &g_pipe.cb);
    if (FAILED(hr))
        goto fail;

    memset(&blendDesc, 0, sizeof(blendDesc));
    blendDesc.RenderTarget[0].BlendEnable = FALSE;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    what = "CreateBlendState";
    hr = ID3D11Device_CreateBlendState(dev, &blendDesc, &g_pipe.blend);
    if (FAILED(hr))
        goto fail;

    memset(&rsDesc, 0, sizeof(rsDesc));
    rsDesc.FillMode = D3D11_FILL_SOLID;
    rsDesc.CullMode = D3D11_CULL_NONE;
    rsDesc.DepthClipEnable = TRUE;
    what = "CreateRasterizerState";
    hr = ID3D11Device_CreateRasterizerState(dev, &rsDesc, &g_pipe.rast);
    if (FAILED(hr))
        goto fail;

    return 0;

fail:
    mse_critf(MSE_LOG_WARN, "d3d11 %s hr=0x%08lx", what, (unsigned long)hr);
    release_pipeline();
    return -1;
}

static void clear_backbuffer(D3dPresent *d, ID3D11DeviceContext *ctx)
{
    const float black[4] = { 0, 0, 0, 1 };
    if (d->rtv)
        ID3D11DeviceContext_ClearRenderTargetView(ctx, d->rtv, black);
}

static int draw_nv12(D3dPresent *d, ID3D11Device *dev, ID3D11DeviceContext *ctx,
                     ID3D11Texture2D *tex, const D3D11_TEXTURE2D_DESC *td, UINT slice, int src_w,
                     int src_h)
{
    D3D11_SHADER_RESOURCE_VIEW_DESC yd, uvd;
    ID3D11ShaderResourceView *srvY = NULL, *srvUV = NULL;
    ID3D11ShaderResourceView *srvs[2];
    D3D11_VIEWPORT vp;
    float crop[4];
    int vw = d->bb_w, vh = d->bb_h;
    HRESULT hr;

    if (!tex || !d->rtv)
        return -1;

    /* ffmpeg's d3d11va always creates an array texture (the hwaccel requires
       it); we create a one-element view at the frame's array slice. */
    memset(&yd, 0, sizeof(yd));
    yd.Format = DXGI_FORMAT_R8_UNORM;
    yd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
    yd.Texture2DArray.MostDetailedMip = 0;
    yd.Texture2DArray.MipLevels = 1;
    yd.Texture2DArray.FirstArraySlice = slice;
    yd.Texture2DArray.ArraySize = 1;
    hr = ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)tex, &yd, &srvY);
    if (FAILED(hr)) {
        present_fail(d, "CreateShaderResourceView Y", hr);
        return -1;
    }

    memset(&uvd, 0, sizeof(uvd));
    uvd.Format = DXGI_FORMAT_R8G8_UNORM;
    uvd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
    uvd.Texture2DArray.MostDetailedMip = 0;
    uvd.Texture2DArray.MipLevels = 1;
    uvd.Texture2DArray.FirstArraySlice = slice;
    uvd.Texture2DArray.ArraySize = 1;
    hr = ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)tex, &uvd, &srvUV);
    if (FAILED(hr)) {
        present_fail(d, "CreateShaderResourceView UV", hr);
        ID3D11ShaderResourceView_Release(srvY);
        return -1;
    }

    /* Largest centered rect with the picture's aspect; the clear left the bars black. */
    if (src_w > 0 && src_h > 0 && d->bb_w > 0 && d->bb_h > 0) {
        if ((int64_t)src_w * d->bb_h > (int64_t)d->bb_w * src_h)
            vh = (int)(((int64_t)d->bb_w * src_h + src_w / 2) / src_w);
        else
            vw = (int)(((int64_t)d->bb_h * src_w + src_h / 2) / src_h);
        if (vw < 1) vw = 1;
        if (vh < 1) vh = 1;
    }
    /* d3d11va pads the pool texture (128-aligned for AV1/HEVC); the padding is never written. */
    crop[0] = src_w > 0 && td->Width > 0 ? (float)src_w / (float)td->Width : 1.0f;
    crop[1] = src_h > 0 && td->Height > 0 ? (float)src_h / (float)td->Height : 1.0f;
    crop[2] = 0.0f;
    crop[3] = 0.0f;

    ID3D11DeviceContext_UpdateSubresource(ctx, (ID3D11Resource *)g_pipe.cb, 0, NULL, crop, 0, 0);

    memset(&vp, 0, sizeof(vp));
    vp.TopLeftX = (float)((d->bb_w - vw) / 2);
    vp.TopLeftY = (float)((d->bb_h - vh) / 2);
    vp.Width = (float)vw;
    vp.Height = (float)vh;
    vp.MaxDepth = 1.0f;
    ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
    ID3D11DeviceContext_RSSetState(ctx, g_pipe.rast);
    ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &d->rtv, NULL);
    ID3D11DeviceContext_OMSetBlendState(ctx, g_pipe.blend, NULL, 0xffffffff);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
    ID3D11DeviceContext_VSSetShader(ctx, g_pipe.vs, NULL, 0);
    ID3D11DeviceContext_VSSetConstantBuffers(ctx, 0, 1, &g_pipe.cb);
    ID3D11DeviceContext_PSSetShader(ctx, g_pipe.ps, NULL, 0);
    ID3D11DeviceContext_PSSetConstantBuffers(ctx, 0, 1, &g_pipe.cb);
    srvs[0] = srvY;
    srvs[1] = srvUV;
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, 2, srvs);
    ID3D11DeviceContext_PSSetSamplers(ctx, 0, 1, &g_pipe.samp);
    ID3D11DeviceContext_Draw(ctx, 3, 0);

    /* Unbind SRVs so a subsequent decode write to the same array slice does
       not fight an input binding (D3D11 debug layer whinges). */
    srvs[0] = NULL;
    srvs[1] = NULL;
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, 2, srvs);

    ID3D11ShaderResourceView_Release(srvY);
    ID3D11ShaderResourceView_Release(srvUV);
    return 0;
}

static int create_swapchain(D3dPresent *d, ID3D11Device *dev, HWND hwnd)
{
    IDXGIDevice *dxgiDev = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory2 *factory = NULL;
    DXGI_SWAP_CHAIN_DESC1 scd;
    RECT rc;
    int w = 1, h = 1, ok = 0;
    HRESULT hr;

    hr = ID3D11Device_QueryInterface(dev, &IID_IDXGIDevice, (void **)&dxgiDev);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 QI IDXGIDevice hr=0x%08lx", (unsigned long)hr);
        goto done;
    }
    hr = IDXGIDevice_GetAdapter(dxgiDev, &adapter);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 GetAdapter hr=0x%08lx", (unsigned long)hr);
        goto done;
    }
    hr = IDXGIAdapter_GetParent(adapter, &IID_IDXGIFactory2, (void **)&factory);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 GetParent IDXGIFactory2 hr=0x%08lx", (unsigned long)hr);
        goto done;
    }

    if (GetClientRect(hwnd, &rc)) {
        w = rc.right - rc.left;
        h = rc.bottom - rc.top;
        if (w < 1) w = 1;
        if (h < 1) h = 1;
    }
    memset(&scd, 0, sizeof(scd));
    scd.Width = (UINT)w;
    scd.Height = (UINT)h;
    scd.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    scd.SampleDesc.Count = 1;
    scd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    scd.BufferCount = 2;
    scd.Scaling = DXGI_SCALING_STRETCH;
    /* FLIP_DISCARD: Win10+, best for composition surfaces. SEQUENTIAL
       works on Win8.1 but keeps a stale buffer around we never read. */
    scd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    scd.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
    hr = IDXGIFactory2_CreateSwapChainForHwnd(factory, (IUnknown *)dev, hwnd, &scd,
                                              NULL, NULL, &d->swap);
    if (FAILED(hr)) {
        /* Older driver — fall back to BitBlt model. */
        scd.SwapEffect = DXGI_SWAP_EFFECT_SEQUENTIAL;
        scd.BufferCount = 1;
        hr = IDXGIFactory2_CreateSwapChainForHwnd(factory, (IUnknown *)dev, hwnd, &scd,
                                                  NULL, NULL, &d->swap);
    }
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 CreateSwapChainForHwnd hr=0x%08lx", (unsigned long)hr);
        goto done;
    }
    /* No DWM Alt+Enter — the Canvas is windowed under Compose. */
    hr = IDXGIFactory2_MakeWindowAssociation(factory, hwnd, DXGI_MWA_NO_ALT_ENTER |
                                                            DXGI_MWA_NO_WINDOW_CHANGES);
    if (FAILED(hr))
        mse_critf(MSE_LOG_WARN, "d3d11 MakeWindowAssociation hr=0x%08lx", (unsigned long)hr);
    d->hwnd = hwnd;
    d->bb_w = (int)scd.Width;
    d->bb_h = (int)scd.Height;
    if (ensure_backbuffer_rtv(d, dev) != 0)
        goto done;
    ok = 1;
    mse_critf(MSE_LOG_INFO, "d3d11 swapchain %dx%d hwnd=%p", d->bb_w, d->bb_h, (void *)hwnd);

done:
    if (factory) IDXGIFactory2_Release(factory);
    if (adapter) IDXGIAdapter_Release(adapter);
    if (dxgiDev) IDXGIDevice_Release(dxgiDev);
    return ok ? 0 : -1;
}

/* Follows the HWND's client rect. */
static void resize_backbuffer(D3dPresent *d)
{
    RECT rc;
    int w, h, dw, dh;
    HRESULT hr;
    if (!d->hwnd || !GetClientRect(d->hwnd, &rc))
        return;
    w = rc.right - rc.left;
    h = rc.bottom - rc.top;
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    dw = w > d->bb_w ? w - d->bb_w : d->bb_w - w;
    dh = h > d->bb_h ? h - d->bb_h : d->bb_h - h;
    /* The Canvas jitters ±1px on fractional DPI; SCALING_STRETCH absorbs that. */
    if (dw < 2 && dh < 2)
        return;
    unbind_rtv(d);
    release_views(d);
    hr = IDXGISwapChain1_ResizeBuffers(d->swap, 0, (UINT)w, (UINT)h, DXGI_FORMAT_UNKNOWN, 0);
    if (SUCCEEDED(hr)) {
        d->bb_w = w;
        d->bb_h = h;
        d->resize_failed = 0;
    } else if (!d->resize_failed) {
        d->resize_failed = 1;
        mse_critf(MSE_LOG_WARN, "d3d11 ResizeBuffers %dx%d hr=0x%08lx", w, h, (unsigned long)hr);
    }
}

/* NV12 is the only texture the shader samples; logs the first other format once. */
static int texture_nv12(ID3D11Texture2D *tex, D3D11_TEXTURE2D_DESC *td)
{
    static int fmt_logged;
    ID3D11Texture2D_GetDesc(tex, td);
    if (td->Format == DXGI_FORMAT_NV12)
        return 1;
    if (!fmt_logged) {
        fmt_logged = 1;
        mse_critf(MSE_LOG_WARN, "d3d11 present takes NV12 only, got DXGI format %d",
                  (int)td->Format);
    }
    return 0;
}

static int d3d_show(VPresenter *p, const AVFrame *f, VpColor c)
{
    D3dPresent *d = p->impl;
    ID3D11Device *dev = mse_d3d11_device();
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    ID3D11Texture2D *tex;
    D3D11_TEXTURE2D_DESC td;
    int src_w = f->width, src_h = f->height;
    int r = VP_FAIL;
    HRESULT hr;

    (void)c;
    if (!dev || !ctx || mse_d3d11_lost())
        return VP_DROPPED;
    tex = f->format == AV_PIX_FMT_D3D11 ? (ID3D11Texture2D *)f->data[0] : NULL;
    if (!tex || !texture_nv12(tex, &td))
        return VP_FAIL;

    mse_d3d11_lock();
    if (!d->swap) {
        mse_d3d11_unlock();
        return VP_DROPPED;
    }
    resize_backbuffer(d);
    if (ensure_backbuffer_rtv(d, dev) != 0)
        goto out;
    clear_backbuffer(d, ctx);
    if (draw_nv12(d, dev, ctx, tex, &td, (UINT)(uintptr_t)f->data[1], src_w, src_h) == 0) {
        hr = IDXGISwapChain1_Present(d->swap, 1, 0);
        if (SUCCEEDED(hr)) {
            r = VP_OK;
            d->fail_logged = 0;
#if !PRODUCTION
            if (!d->logged_present ||
                d->log_src_w != src_w || d->log_src_h != src_h ||
                d->log_dst_w != d->bb_w || d->log_dst_h != d->bb_h) {
                d->logged_present = 1;
                d->log_src_w = src_w;
                d->log_src_h = src_h;
                d->log_dst_w = d->bb_w;
                d->log_dst_h = d->bb_h;
                mse_logf("d3d11 present %dx%d -> %dx%d", src_w, src_h, d->bb_w, d->bb_h);
            }
#endif
        } else {
            present_fail(d, "Present", hr);
        }
    }
out:
    if (r != VP_OK && mse_d3d11_check_lost())
        r = VP_DROPPED;
    mse_d3d11_unlock();
    return r;
}

static void d3d_flush(VPresenter *p)
{
    D3dPresent *d = p->impl;
    ID3D11Device *dev = mse_d3d11_device();
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    HRESULT hr;
    if (!dev || !ctx || mse_d3d11_lost())
        return;
    mse_d3d11_lock();
    if (d->swap) {
        resize_backbuffer(d);
        if (ensure_backbuffer_rtv(d, dev) == 0) {
            clear_backbuffer(d, ctx);
            hr = IDXGISwapChain1_Present(d->swap, 1, 0);
            if (FAILED(hr))
                present_fail(d, "Present", hr);
        }
    }
    mse_d3d11_unlock();
}

static void d3d_detach(VPresenter *p)
{
    mse_d3d11_lock();
    release_swapchain(p->impl);
    mse_d3d11_unlock();
}

static int d3d_zero_copy(VPresenter *p, int av_pix_fmt)
{
    (void)p;
    return av_pix_fmt == AV_PIX_FMT_D3D11;
}

static void d3d_destroy(VPresenter *p)
{
    D3dPresent *d = p->impl, **it;
    mse_d3d11_lock();
    for (it = &g_live; *it; it = &(*it)->next) {
        if (*it == d) {
            *it = d->next;
            break;
        }
    }
    release_swapchain(d);
    mse_d3d11_unlock();
    free(d);
}

static const VpOps k_d3d11_ops = {
    .kind = "d3d11",
    .show = d3d_show,
    .flush = d3d_flush,
    .detach = d3d_detach,
    .zero_copy = d3d_zero_copy,
    .destroy = d3d_destroy,
};

VPresenter *vpresent_create_d3d11(void *hwnd, const VpSink *s)
{
    ID3D11Device *dev = mse_d3d11_device();
    D3dPresent *d;
    VPresenter *p;
    int ok;
    if (!hwnd || !dev || mse_d3d11_lost())
        return NULL;
    d = calloc(1, sizeof(*d));
    if (!d)
        return NULL;
    p = vp_alloc(&k_d3d11_ops, d, s, VP_MODE_GPU);
    if (!p) {
        free(d);
        return NULL;
    }
    d->vp = p;
    mse_d3d11_lock();
    ok = build_pipeline(dev) == 0 && create_swapchain(d, dev, (HWND)hwnd) == 0;
    if (ok) {
        d->next = g_live;
        g_live = d;
    }
    mse_d3d11_unlock();
    if (!ok) {
        vpresent_release(p);
        return NULL;
    }
    return p;
}

void vpresent_d3d11_device_lost(void)
{
    VPresenter **lost = NULL;
    D3dPresent *d;
    int n = 0, i;
    mse_d3d11_lock();
    for (d = g_live; d; d = d->next)
        n++;
    if (n)
        lost = calloc((size_t)n, sizeof(*lost));
    n = 0;
    for (d = g_live; d; d = d->next) {
        release_swapchain(d);
        if (lost && vp_retain_live(d->vp))
            lost[n++] = d->vp;
    }
    mse_d3d11_unlock();
    for (i = 0; i < n; i++) {
        vp_gpu_lost(lost[i]);
        vpresent_release(lost[i]);
    }
    free(lost);
}

/* Owner-token API over one process-wide presenter. */
static pthread_mutex_t g_shim_mu = PTHREAD_MUTEX_INITIALIZER;
static VPresenter *g_shim;
static const void *g_shim_owner;

/* Caller holds g_shim_mu. */
static int shim_on(HWND hwnd)
{
    D3dPresent *d;
    int on;
    if (!g_shim)
        return 0;
    d = g_shim->impl;
    mse_d3d11_lock();
    on = d->hwnd == hwnd && d->swap;
    mse_d3d11_unlock();
    return on;
}

int mse_d3d11_present_attach(HWND hwnd, const void *owner)
{
    VPresenter *p;
    int ok = 0;
    if (!hwnd || !mse_d3d11_device() || mse_d3d11_lost())
        return 0;
    pthread_mutex_lock(&g_shim_mu);
    if (shim_on(hwnd)) {
        vpresent_unbind(g_shim, g_shim_owner);
        vpresent_bind(g_shim, owner);
        g_shim_owner = owner;
        pthread_mutex_unlock(&g_shim_mu);
        return 1;
    }
    if (g_shim) {
        vpresent_detach(g_shim);
        vpresent_release(g_shim);
        g_shim = NULL;
        g_shim_owner = NULL;
    }
    p = vpresent_create_d3d11(hwnd, NULL);
    if (p) {
        vpresent_bind(p, owner);
        g_shim = p;
        g_shim_owner = owner;
        ok = 1;
    }
    pthread_mutex_unlock(&g_shim_mu);
    return ok;
}

void mse_d3d11_present_detach(const void *owner)
{
    pthread_mutex_lock(&g_shim_mu);
    if (g_shim && g_shim_owner == owner) {
        vpresent_detach(g_shim);
        vpresent_release(g_shim);
        g_shim = NULL;
        g_shim_owner = NULL;
    }
    pthread_mutex_unlock(&g_shim_mu);
}

int mse_d3d11_present_show(ID3D11Texture2D *tex, UINT array_slice, int src_w, int src_h,
                           const void *owner)
{
    static const VpColor bt709 = { AVCOL_SPC_BT709, 0 };
    D3D11_TEXTURE2D_DESC td;
    VPresenter *p;
    AVFrame f;
    int r;
    if (!mse_d3d11_device() || !mse_d3d11_context() || mse_d3d11_lost())
        return -2;
    if (!tex)
        return -1;
    if (!texture_nv12(tex, &td))
        return -1;
    pthread_mutex_lock(&g_shim_mu);
    p = g_shim;
    if (p)
        vpresent_retain(p);
    pthread_mutex_unlock(&g_shim_mu);
    if (!p)
        return -2;
    memset(&f, 0, sizeof(f));
    f.format = AV_PIX_FMT_D3D11;
    f.data[0] = (uint8_t *)tex;
    f.data[1] = (uint8_t *)(uintptr_t)array_slice;
    f.width = src_w;
    f.height = src_h;
    r = vpresent_show_frame(p, owner, &f, bt709);
    vpresent_release(p);
    return r == VP_OK ? 0 : r == VP_DROPPED ? -2 : -1;
}

#endif /* _WIN32 */
