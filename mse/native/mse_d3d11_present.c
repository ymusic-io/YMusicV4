#ifdef _WIN32

#include "mse_d3d11.h"
#include "mse_ffmpeg.h"

#include <d3d11.h>
#include <dxgi1_2.h>
#include <d3dcompiler.h>

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* NV12 → BGRA. BT.709 limited-range (16..235 / 16..240).
   The vertex shader draws a triangle covering the viewport (SV_VertexID);
   draw_nv12 sets the viewport to the letterboxed picture rect. 10-bit would
   need DXGI_FORMAT_P010 / 601 vs 709 and is deferred — the open_decoder HW
   check skips it. */
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

typedef struct {
    HWND hwnd;
    /* Engine that last attached; a stale engine's late detach must not tear down its successor. */
    const void *owner;
    IDXGISwapChain1 *swap;
    ID3D11RenderTargetView *rtv;
    ID3D11VertexShader *vs;
    ID3D11PixelShader *ps;
    ID3D11SamplerState *samp;
    ID3D11Buffer *cb;
    ID3D11BlendState *blend;
    ID3D11RasterizerState *rast;
    int bb_w, bb_h;
    int resize_failed;
    int fail_logged;
    int logged_present;
    int log_src_w, log_src_h, log_dst_w, log_dst_h;
} D3dPresent;

static D3dPresent g_p;

/* Per-frame failures: one WARN until a frame presents again. */
static void present_fail(const char *what, HRESULT hr)
{
    if (g_p.fail_logged)
        return;
    g_p.fail_logged = 1;
    mse_critf(MSE_LOG_WARN, "d3d11 %s hr=0x%08lx", what, (unsigned long)hr);
}

static void release_views(void)
{
    if (g_p.rtv) {
        ID3D11RenderTargetView_Release(g_p.rtv);
        g_p.rtv = NULL;
    }
}

/* Caller holds the d3d11 lock. */
static void unbind_rtv(void)
{
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    if (ctx && g_p.rtv)
        ID3D11DeviceContext_OMSetRenderTargets(ctx, 0, NULL, NULL);
}

static void release_swapchain(void)
{
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    unbind_rtv();
    release_views();
    if (g_p.swap) {
        IDXGISwapChain1_Release(g_p.swap);
        g_p.swap = NULL;
        /* DXGI destroys swapchains lazily; a new flip-model one on the same HWND fails until this. */
        if (ctx) {
            ID3D11DeviceContext_ClearState(ctx);
            ID3D11DeviceContext_Flush(ctx);
        }
    }
    g_p.hwnd = NULL;
    g_p.owner = NULL;
    g_p.bb_w = g_p.bb_h = 0;
    g_p.logged_present = 0;
    g_p.resize_failed = 0;
    g_p.fail_logged = 0;
}

static void release_pipeline(void)
{
    if (g_p.cb) {
        ID3D11Buffer_Release(g_p.cb);
        g_p.cb = NULL;
    }
    if (g_p.samp) {
        ID3D11SamplerState_Release(g_p.samp);
        g_p.samp = NULL;
    }
    if (g_p.ps) {
        ID3D11PixelShader_Release(g_p.ps);
        g_p.ps = NULL;
    }
    if (g_p.vs) {
        ID3D11VertexShader_Release(g_p.vs);
        g_p.vs = NULL;
    }
    if (g_p.blend) {
        ID3D11BlendState_Release(g_p.blend);
        g_p.blend = NULL;
    }
    if (g_p.rast) {
        ID3D11RasterizerState_Release(g_p.rast);
        g_p.rast = NULL;
    }
}

static int ensure_backbuffer_rtv(ID3D11Device *dev)
{
    ID3D11Texture2D *bb = NULL;
    HRESULT hr;
    if (g_p.rtv)
        return 0;
    if (!g_p.swap)
        return -1;
    hr = IDXGISwapChain1_GetBuffer(g_p.swap, 0, &IID_ID3D11Texture2D, (void **)&bb);
    if (FAILED(hr) || !bb) {
        present_fail("present GetBuffer", hr);
        return -1;
    }
    hr = ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)bb, NULL, &g_p.rtv);
    ID3D11Texture2D_Release(bb);
    if (FAILED(hr)) {
        present_fail("present CreateRTV", hr);
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

    if (g_p.vs && g_p.ps && g_p.samp && g_p.cb && g_p.blend && g_p.rast)
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
                                         ID3D10Blob_GetBufferSize(vsBlob), NULL, &g_p.vs);
    ID3D10Blob_Release(vsBlob);
    if (FAILED(hr)) {
        ID3D10Blob_Release(psBlob);
        goto fail;
    }
    what = "CreatePixelShader";
    hr = ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(psBlob),
                                        ID3D10Blob_GetBufferSize(psBlob), NULL, &g_p.ps);
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
    hr = ID3D11Device_CreateSamplerState(dev, &sd, &g_p.samp);
    if (FAILED(hr))
        goto fail;

    memset(&bd, 0, sizeof(bd));
    bd.ByteWidth = 16; /* float4 crop */
    bd.Usage = D3D11_USAGE_DEFAULT;
    bd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    what = "CreateBuffer";
    hr = ID3D11Device_CreateBuffer(dev, &bd, NULL, &g_p.cb);
    if (FAILED(hr))
        goto fail;

    memset(&blendDesc, 0, sizeof(blendDesc));
    blendDesc.RenderTarget[0].BlendEnable = FALSE;
    blendDesc.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    what = "CreateBlendState";
    hr = ID3D11Device_CreateBlendState(dev, &blendDesc, &g_p.blend);
    if (FAILED(hr))
        goto fail;

    memset(&rsDesc, 0, sizeof(rsDesc));
    rsDesc.FillMode = D3D11_FILL_SOLID;
    rsDesc.CullMode = D3D11_CULL_NONE;
    rsDesc.DepthClipEnable = TRUE;
    what = "CreateRasterizerState";
    hr = ID3D11Device_CreateRasterizerState(dev, &rsDesc, &g_p.rast);
    if (FAILED(hr))
        goto fail;

    return 0;

fail:
    mse_critf(MSE_LOG_WARN, "d3d11 %s hr=0x%08lx", what, (unsigned long)hr);
    release_pipeline();
    return -1;
}

static void clear_backbuffer(ID3D11DeviceContext *ctx)
{
    const float black[4] = { 0, 0, 0, 1 };
    if (g_p.rtv)
        ID3D11DeviceContext_ClearRenderTargetView(ctx, g_p.rtv, black);
}

static int draw_nv12(ID3D11Device *dev, ID3D11DeviceContext *ctx, ID3D11Texture2D *tex,
                     UINT slice, int src_w, int src_h)
{
    D3D11_SHADER_RESOURCE_VIEW_DESC yd, uvd;
    D3D11_TEXTURE2D_DESC td;
    ID3D11ShaderResourceView *srvY = NULL, *srvUV = NULL;
    ID3D11ShaderResourceView *srvs[2];
    D3D11_VIEWPORT vp;
    float crop[4];
    int vw = g_p.bb_w, vh = g_p.bb_h;
    HRESULT hr;

    if (!tex || !g_p.rtv)
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
        present_fail("CreateShaderResourceView Y", hr);
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
        present_fail("CreateShaderResourceView UV", hr);
        ID3D11ShaderResourceView_Release(srvY);
        return -1;
    }

    /* Largest centered rect with the picture's aspect; the clear left the bars black. */
    if (src_w > 0 && src_h > 0 && g_p.bb_w > 0 && g_p.bb_h > 0) {
        if ((int64_t)src_w * g_p.bb_h > (int64_t)g_p.bb_w * src_h)
            vh = (int)(((int64_t)g_p.bb_w * src_h + src_w / 2) / src_w);
        else
            vw = (int)(((int64_t)g_p.bb_h * src_w + src_h / 2) / src_h);
        if (vw < 1) vw = 1;
        if (vh < 1) vh = 1;
    }
    /* d3d11va pads the pool texture (128-aligned for AV1/HEVC); the padding is never written. */
    ID3D11Texture2D_GetDesc(tex, &td);
    crop[0] = src_w > 0 && td.Width > 0 ? (float)src_w / (float)td.Width : 1.0f;
    crop[1] = src_h > 0 && td.Height > 0 ? (float)src_h / (float)td.Height : 1.0f;
    crop[2] = 0.0f;
    crop[3] = 0.0f;

    ID3D11DeviceContext_UpdateSubresource(ctx, (ID3D11Resource *)g_p.cb, 0, NULL, crop, 0, 0);

    memset(&vp, 0, sizeof(vp));
    vp.TopLeftX = (float)((g_p.bb_w - vw) / 2);
    vp.TopLeftY = (float)((g_p.bb_h - vh) / 2);
    vp.Width = (float)vw;
    vp.Height = (float)vh;
    vp.MaxDepth = 1.0f;
    ID3D11DeviceContext_RSSetViewports(ctx, 1, &vp);
    ID3D11DeviceContext_RSSetState(ctx, g_p.rast);
    ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &g_p.rtv, NULL);
    ID3D11DeviceContext_OMSetBlendState(ctx, g_p.blend, NULL, 0xffffffff);
    ID3D11DeviceContext_IASetPrimitiveTopology(ctx, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_IASetInputLayout(ctx, NULL);
    ID3D11DeviceContext_VSSetShader(ctx, g_p.vs, NULL, 0);
    ID3D11DeviceContext_VSSetConstantBuffers(ctx, 0, 1, &g_p.cb);
    ID3D11DeviceContext_PSSetShader(ctx, g_p.ps, NULL, 0);
    ID3D11DeviceContext_PSSetConstantBuffers(ctx, 0, 1, &g_p.cb);
    srvs[0] = srvY;
    srvs[1] = srvUV;
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, 2, srvs);
    ID3D11DeviceContext_PSSetSamplers(ctx, 0, 1, &g_p.samp);
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

int mse_d3d11_present_attach(HWND hwnd, const void *owner)
{
    ID3D11Device *dev;
    IDXGIDevice *dxgiDev = NULL;
    IDXGIAdapter *adapter = NULL;
    IDXGIFactory2 *factory = NULL;
    DXGI_SWAP_CHAIN_DESC1 scd;
    HRESULT hr;
    int ok = 0;

    if (!hwnd)
        return 0;
    dev = mse_d3d11_device();
    if (!dev)
        return 0;

    mse_d3d11_lock();
    if (g_p.hwnd == hwnd && g_p.swap) {
        g_p.owner = owner;
        mse_d3d11_unlock();
        return 1;
    }
    release_swapchain();
    if (build_pipeline(dev) != 0)
        goto done;

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

    {
        RECT rc;
        int w = 1, h = 1;
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
                                                  NULL, NULL, &g_p.swap);
        if (FAILED(hr)) {
            /* Older driver — fall back to BitBlt model. */
            scd.SwapEffect = DXGI_SWAP_EFFECT_SEQUENTIAL;
            scd.BufferCount = 1;
            hr = IDXGIFactory2_CreateSwapChainForHwnd(factory, (IUnknown *)dev, hwnd, &scd,
                                                      NULL, NULL, &g_p.swap);
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
        g_p.hwnd = hwnd;
        g_p.bb_w = (int)scd.Width;
        g_p.bb_h = (int)scd.Height;
    }

    if (ensure_backbuffer_rtv(dev) != 0)
        goto done;

    g_p.owner = owner;
    ok = 1;
    mse_critf(MSE_LOG_INFO, "d3d11 swapchain %dx%d hwnd=%p", g_p.bb_w, g_p.bb_h, (void *)hwnd);

done:
    if (!ok)
        release_swapchain();
    if (factory) IDXGIFactory2_Release(factory);
    if (adapter) IDXGIAdapter_Release(adapter);
    if (dxgiDev) IDXGIDevice_Release(dxgiDev);
    mse_d3d11_unlock();
    return ok;
}

void mse_d3d11_present_detach(const void *owner)
{
    mse_d3d11_lock();
    if (g_p.owner == owner)
        release_swapchain();
    mse_d3d11_unlock();
}

int mse_d3d11_present_show(ID3D11Texture2D *tex, UINT array_slice, int src_w, int src_h,
                           const void *owner)
{
    ID3D11Device *dev;
    ID3D11DeviceContext *ctx;
    HRESULT hr;
    int r = -1;

    dev = mse_d3d11_device();
    ctx = mse_d3d11_context();
    if (!dev || !ctx || !tex)
        return -1;

    mse_d3d11_lock();
    if (!g_p.swap || g_p.owner != owner) {
        mse_d3d11_unlock();
        return -2;
    }
    if (g_p.hwnd) {
        RECT rc;
        if (GetClientRect(g_p.hwnd, &rc)) {
            int w = rc.right - rc.left, hgt = rc.bottom - rc.top;
            int dw, dh;
            if (w < 1) w = 1;
            if (hgt < 1) hgt = 1;
            dw = w > g_p.bb_w ? w - g_p.bb_w : g_p.bb_w - w;
            dh = hgt > g_p.bb_h ? hgt - g_p.bb_h : g_p.bb_h - hgt;
            /* The Canvas jitters ±1px on fractional DPI; SCALING_STRETCH absorbs that. */
            if (dw >= 2 || dh >= 2) {
                unbind_rtv();
                release_views();
                hr = IDXGISwapChain1_ResizeBuffers(g_p.swap, 0, (UINT)w, (UINT)hgt,
                                                   DXGI_FORMAT_UNKNOWN, 0);
                if (SUCCEEDED(hr)) {
                    g_p.bb_w = w;
                    g_p.bb_h = hgt;
                    g_p.resize_failed = 0;
                } else if (!g_p.resize_failed) {
                    g_p.resize_failed = 1;
                    mse_critf(MSE_LOG_WARN, "d3d11 ResizeBuffers %dx%d hr=0x%08lx", w, hgt,
                              (unsigned long)hr);
                }
            }
        }
    }
    if (ensure_backbuffer_rtv(dev) != 0) {
        mse_d3d11_unlock();
        return -1;
    }
    clear_backbuffer(ctx);
    if (draw_nv12(dev, ctx, tex, array_slice, src_w, src_h) == 0) {
        hr = IDXGISwapChain1_Present(g_p.swap, 1, 0);
        if (SUCCEEDED(hr)) {
            r = 0;
            g_p.fail_logged = 0;
#if !PRODUCTION
            if (!g_p.logged_present ||
                g_p.log_src_w != src_w || g_p.log_src_h != src_h ||
                g_p.log_dst_w != g_p.bb_w || g_p.log_dst_h != g_p.bb_h) {
                g_p.logged_present = 1;
                g_p.log_src_w = src_w;
                g_p.log_src_h = src_h;
                g_p.log_dst_w = g_p.bb_w;
                g_p.log_dst_h = g_p.bb_h;
                mse_logf("d3d11 present %dx%d -> %dx%d", src_w, src_h, g_p.bb_w, g_p.bb_h);
            }
#endif
        } else {
            present_fail("Present", hr);
        }
    }
    mse_d3d11_unlock();
    return r;
}

#endif /* _WIN32 */
