#ifdef _WIN32

#include "vpresent_impl.h"
#include "mse_d3d11.h"
#include "mse_ffmpeg.h"

#include <d3d11.h>
#include <dxgi1_2.h>
#include <d3dcompiler.h>

#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* The vertex shader draws a triangle covering the viewport (SV_VertexID); draw() sets the
   viewport to the letterboxed picture rect. YUV → RGB is the 3×4 matrix m0..m2 (yuv_rows). Only
   8-bit 4:2:0 reaches the YUV shaders: mse_d3d11_pick keeps 10-bit decodes in software, and other
   software formats arrive as BGRA. */
static const char kShaderHlsl[] =
    "cbuffer K : register(b0) {\n"
    "    float4 crop;\n" /* xy = picture share of the padded texture, zw unused */
    "    float4 m0;\n"
    "    float4 m1;\n"
    "    float4 m2;\n"
    "}\n"
    "Texture2DArray<float4> Tex0 : register(t0);\n"
    "Texture2DArray<float4> Tex1 : register(t1);\n"
    "Texture2DArray<float4> Tex2 : register(t2);\n"
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
    /* A one-slice view always indexes into z=0. Stop half a texel short of the padding, or the
       linear filter blends it in. */
    "float4 Fetch(Texture2DArray<float4> t, float2 uv)\n"
    "{\n"
    "    float w, h, n;\n"
    "    t.GetDimensions(w, h, n);\n"
    "    return t.Sample(Samp, float3(min(uv, crop.xy - 0.5 / float2(w, h)), 0));\n"
    "}\n"
    "float4 Rgb(float y, float u, float v)\n"
    "{\n"
    "    float4 c = float4(y, u, v, 1.0);\n"
    "    return float4(saturate(float3(dot(m0, c), dot(m1, c), dot(m2, c))), 1.0);\n"
    "}\n"
    "float4 PSNV12(VSOut i) : SV_Target\n"
    "{\n"
    "    float2 uv = Fetch(Tex1, i.uv).rg;\n"
    "    return Rgb(Fetch(Tex0, i.uv).r, uv.x, uv.y);\n"
    "}\n"
    "float4 PSI420(VSOut i) : SV_Target\n"
    "{\n"
    "    return Rgb(Fetch(Tex0, i.uv).r, Fetch(Tex1, i.uv).r, Fetch(Tex2, i.uv).r);\n"
    "}\n"
    "float4 PSBGRA(VSOut i) : SV_Target\n"
    "{\n"
    "    return float4(Fetch(Tex0, i.uv).rgb, 1.0);\n"
    "}\n";

enum { PS_NV12, PS_I420, PS_BGRA, PS_N };
static const char *const kPsEntry[PS_N] = { "PSNV12", "PSI420", "PSBGRA" };

/* Device-scoped: one process-wide device, so one pipeline. d3d11 lock. */
static struct {
    ID3D11VertexShader *vs;
    ID3D11PixelShader *ps[PS_N];
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
    /* Dynamic textures software frames are written into: Y + UV, Y + U + V, or BGRA. */
    ID3D11Texture2D *up[3];
    ID3D11ShaderResourceView *up_srv[3];
    int up_ps, up_w, up_h;
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

static void release_upload(D3dPresent *d)
{
    int i;
    for (i = 0; i < 3; i++) {
        if (d->up_srv[i]) {
            ID3D11ShaderResourceView_Release(d->up_srv[i]);
            d->up_srv[i] = NULL;
        }
        if (d->up[i]) {
            ID3D11Texture2D_Release(d->up[i]);
            d->up[i] = NULL;
        }
    }
    d->up_w = d->up_h = 0;
}

static void release_swapchain(D3dPresent *d)
{
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    unbind_rtv(d);
    release_views(d);
    release_upload(d);
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
    int i;
    if (g_pipe.cb) {
        ID3D11Buffer_Release(g_pipe.cb);
        g_pipe.cb = NULL;
    }
    if (g_pipe.samp) {
        ID3D11SamplerState_Release(g_pipe.samp);
        g_pipe.samp = NULL;
    }
    for (i = 0; i < PS_N; i++) {
        if (g_pipe.ps[i]) {
            ID3D11PixelShader_Release(g_pipe.ps[i]);
            g_pipe.ps[i] = NULL;
        }
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

static ID3DBlob *compile(const char *entry, const char *target)
{
    ID3DBlob *blob = NULL, *err = NULL;
    HRESULT hr = D3DCompile(kShaderHlsl, sizeof(kShaderHlsl) - 1, "vpresent_d3d11", NULL, NULL,
                            entry, target, 0, 0, &blob, &err);
    if (FAILED(hr)) {
        mse_critf(MSE_LOG_WARN, "d3d11 %s compile hr=0x%08lx %s", entry, (unsigned long)hr,
                  err ? (const char *)ID3D10Blob_GetBufferPointer(err) : "");
        blob = NULL;
    }
    if (err)
        ID3D10Blob_Release(err);
    return blob;
}

static int build_pipeline(ID3D11Device *dev)
{
    ID3DBlob *blob;
    D3D11_SAMPLER_DESC sd;
    D3D11_BUFFER_DESC bd;
    D3D11_BLEND_DESC blendDesc;
    D3D11_RASTERIZER_DESC rsDesc;
    const char *what;
    HRESULT hr;
    int i;

    if (g_pipe.vs && g_pipe.ps[PS_N - 1] && g_pipe.samp && g_pipe.cb && g_pipe.blend &&
        g_pipe.rast)
        return 0;

    blob = compile("VSMain", "vs_4_0");
    if (!blob)
        return -1;
    what = "CreateVertexShader";
    hr = ID3D11Device_CreateVertexShader(dev, ID3D10Blob_GetBufferPointer(blob),
                                         ID3D10Blob_GetBufferSize(blob), NULL, &g_pipe.vs);
    ID3D10Blob_Release(blob);
    if (FAILED(hr))
        goto fail;
    what = "CreatePixelShader";
    for (i = 0; i < PS_N; i++) {
        blob = compile(kPsEntry[i], "ps_4_0");
        if (!blob) {
            release_pipeline();
            return -1;
        }
        hr = ID3D11Device_CreatePixelShader(dev, ID3D10Blob_GetBufferPointer(blob),
                                            ID3D10Blob_GetBufferSize(blob), NULL, &g_pipe.ps[i]);
        ID3D10Blob_Release(blob);
        if (FAILED(hr))
            goto fail;
    }

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
    bd.ByteWidth = 64; /* float4 crop, m0, m1, m2 */
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

/* rgb = rows · (y, u, v, 1): the range expansion and the matrix of c in one affine map. */
static void yuv_rows(VpColor c, float m[12])
{
    const double co = 128.0 / 255.0;
    double kr, kb, kg, ys, yo, cs, rv, gu, gv, bu;
    if (c.matrix == AVCOL_SPC_SMPTE170M) {
        kr = 0.299;
        kb = 0.114;
    } else if (c.matrix == AVCOL_SPC_BT2020_NCL) {
        kr = 0.2627;
        kb = 0.0593;
    } else {
        kr = 0.2126;
        kb = 0.0722;
    }
    kg = 1.0 - kr - kb;
    if (c.full_range) {
        ys = 1.0;
        yo = 0.0;
        cs = 1.0;
    } else {
        ys = 255.0 / 219.0;
        yo = 16.0 / 255.0;
        cs = 255.0 / 224.0;
    }
    rv = 2.0 * (1.0 - kr);
    bu = 2.0 * (1.0 - kb);
    gu = -bu * kb / kg;
    gv = -rv * kr / kg;
    m[0] = (float)ys;
    m[1] = 0.0f;
    m[2] = (float)(rv * cs);
    m[3] = (float)(-ys * yo - rv * cs * co);
    m[4] = (float)ys;
    m[5] = (float)(gu * cs);
    m[6] = (float)(gv * cs);
    m[7] = (float)(-ys * yo - (gu + gv) * cs * co);
    m[8] = (float)ys;
    m[9] = (float)(bu * cs);
    m[10] = 0.0f;
    m[11] = (float)(-ys * yo - bu * cs * co);
}

/* Letterboxes the n views into the backbuffer through pixel shader ps. crop_x/y: the picture's
   share of the textures. */
static void draw(D3dPresent *d, ID3D11DeviceContext *ctx, int ps,
                 ID3D11ShaderResourceView *const *srvs, int n, float crop_x, float crop_y,
                 int src_w, int src_h, VpColor c)
{
    ID3D11ShaderResourceView *none[3] = { NULL, NULL, NULL };
    D3D11_VIEWPORT vp;
    float k[16];
    int vw = d->bb_w, vh = d->bb_h;

    /* Largest centered rect with the picture's aspect; the clear left the bars black. */
    if (src_w > 0 && src_h > 0 && d->bb_w > 0 && d->bb_h > 0) {
        if ((int64_t)src_w * d->bb_h > (int64_t)d->bb_w * src_h)
            vh = (int)(((int64_t)d->bb_w * src_h + src_w / 2) / src_w);
        else
            vw = (int)(((int64_t)d->bb_h * src_w + src_h / 2) / src_h);
        if (vw < 1) vw = 1;
        if (vh < 1) vh = 1;
    }
    k[0] = crop_x;
    k[1] = crop_y;
    k[2] = 0.0f;
    k[3] = 0.0f;
    yuv_rows(c, k + 4);
    ID3D11DeviceContext_UpdateSubresource(ctx, (ID3D11Resource *)g_pipe.cb, 0, NULL, k, 0, 0);

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
    ID3D11DeviceContext_PSSetShader(ctx, g_pipe.ps[ps], NULL, 0);
    ID3D11DeviceContext_PSSetConstantBuffers(ctx, 0, 1, &g_pipe.cb);
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, (UINT)n, srvs);
    ID3D11DeviceContext_PSSetSamplers(ctx, 0, 1, &g_pipe.samp);
    ID3D11DeviceContext_Draw(ctx, 3, 0);

    /* Unbind the views so a later decode into the same array slice does not fight an input
       binding (D3D11 debug layer whinges). */
    ID3D11DeviceContext_PSSetShaderResources(ctx, 0, (UINT)n, none);
}

static int draw_texture(D3dPresent *d, ID3D11Device *dev, ID3D11DeviceContext *ctx,
                        ID3D11Texture2D *tex, const D3D11_TEXTURE2D_DESC *td, UINT slice,
                        int src_w, int src_h, VpColor c)
{
    D3D11_SHADER_RESOURCE_VIEW_DESC sd;
    ID3D11ShaderResourceView *srvs[2] = { NULL, NULL };
    HRESULT hr;

    /* ffmpeg's d3d11va always creates an array texture (the hwaccel requires
       it); we create a one-element view at the frame's array slice. */
    memset(&sd, 0, sizeof(sd));
    sd.Format = DXGI_FORMAT_R8_UNORM;
    sd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
    sd.Texture2DArray.MostDetailedMip = 0;
    sd.Texture2DArray.MipLevels = 1;
    sd.Texture2DArray.FirstArraySlice = slice;
    sd.Texture2DArray.ArraySize = 1;
    hr = ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)tex, &sd, &srvs[0]);
    if (FAILED(hr)) {
        present_fail(d, "CreateShaderResourceView Y", hr);
        return -1;
    }
    sd.Format = DXGI_FORMAT_R8G8_UNORM;
    hr = ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)tex, &sd, &srvs[1]);
    if (FAILED(hr)) {
        present_fail(d, "CreateShaderResourceView UV", hr);
        ID3D11ShaderResourceView_Release(srvs[0]);
        return -1;
    }
    /* d3d11va pads the pool texture (128-aligned for AV1/HEVC); the padding is never written. */
    draw(d, ctx, PS_NV12, srvs, 2,
         src_w > 0 && td->Width > 0 ? (float)src_w / (float)td->Width : 1.0f,
         src_h > 0 && td->Height > 0 ? (float)src_h / (float)td->Height : 1.0f, src_w, src_h, c);
    ID3D11ShaderResourceView_Release(srvs[0]);
    ID3D11ShaderResourceView_Release(srvs[1]);
    return 0;
}

static int ensure_upload(D3dPresent *d, ID3D11Device *dev, int ps, int w, int h)
{
    static const DXGI_FORMAT kNv12[] = { DXGI_FORMAT_R8_UNORM, DXGI_FORMAT_R8G8_UNORM };
    static const DXGI_FORMAT kI420[] = { DXGI_FORMAT_R8_UNORM, DXGI_FORMAT_R8_UNORM,
                                         DXGI_FORMAT_R8_UNORM };
    static const DXGI_FORMAT kBgra[] = { DXGI_FORMAT_B8G8R8A8_UNORM };
    const DXGI_FORMAT *fmt = ps == PS_NV12 ? kNv12 : ps == PS_I420 ? kI420 : kBgra;
    int n = ps == PS_NV12 ? 2 : ps == PS_I420 ? 3 : 1, i;
    HRESULT hr;

    if (d->up[0] && d->up_ps == ps && d->up_w == w && d->up_h == h)
        return 0;
    release_upload(d);
    for (i = 0; i < n; i++) {
        D3D11_TEXTURE2D_DESC td;
        D3D11_SHADER_RESOURCE_VIEW_DESC sd;
        memset(&td, 0, sizeof(td));
        td.Width = (UINT)(i ? (w + 1) / 2 : w);
        td.Height = (UINT)(i ? (h + 1) / 2 : h);
        td.MipLevels = 1;
        td.ArraySize = 1;
        td.Format = fmt[i];
        td.SampleDesc.Count = 1;
        td.Usage = D3D11_USAGE_DYNAMIC;
        td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        td.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        hr = ID3D11Device_CreateTexture2D(dev, &td, NULL, &d->up[i]);
        if (FAILED(hr)) {
            present_fail(d, "upload CreateTexture2D", hr);
            release_upload(d);
            return -1;
        }
        memset(&sd, 0, sizeof(sd));
        sd.Format = fmt[i];
        sd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
        sd.Texture2DArray.MipLevels = 1;
        sd.Texture2DArray.ArraySize = 1;
        hr = ID3D11Device_CreateShaderResourceView(dev, (ID3D11Resource *)d->up[i], &sd,
                                                   &d->up_srv[i]);
        if (FAILED(hr)) {
            present_fail(d, "upload CreateShaderResourceView", hr);
            release_upload(d);
            return -1;
        }
    }
    d->up_ps = ps;
    d->up_w = w;
    d->up_h = h;
    return 0;
}

static int upload_plane(D3dPresent *d, ID3D11DeviceContext *ctx, ID3D11Texture2D *t,
                        const uint8_t *src, int ls, int row_bytes, int rows)
{
    D3D11_MAPPED_SUBRESOURCE m;
    uint8_t *dst;
    int y;
    HRESULT hr = ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)t, 0, D3D11_MAP_WRITE_DISCARD, 0,
                                         &m);
    if (FAILED(hr)) {
        present_fail(d, "upload Map", hr);
        return -1;
    }
    dst = (uint8_t *)m.pData;
    if (m.RowPitch == (UINT)row_bytes && ls == row_bytes) {
        memcpy(dst, src, (size_t)row_bytes * (size_t)rows);
    } else {
        for (y = 0; y < rows; y++)
            memcpy(dst + (size_t)y * m.RowPitch, src + (ptrdiff_t)y * ls, (size_t)row_bytes);
    }
    ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)t, 0);
    return 0;
}

/* Software picture into the upload textures: f's planes, or the BGRA bgra (w × h). */
static int upload(D3dPresent *d, ID3D11Device *dev, ID3D11DeviceContext *ctx, int ps,
                  const AVFrame *f, const uint8_t *bgra, int w, int h)
{
    int cw = (w + 1) / 2, ch = (h + 1) / 2;
    if (ensure_upload(d, dev, ps, w, h) != 0)
        return -1;
    if (ps == PS_BGRA)
        return upload_plane(d, ctx, d->up[0], bgra, w * 4, w * 4, h);
    if (upload_plane(d, ctx, d->up[0], f->data[0], f->linesize[0], w, h) != 0)
        return -1;
    if (ps == PS_NV12)
        return upload_plane(d, ctx, d->up[1], f->data[1], f->linesize[1], cw * 2, ch);
    if (upload_plane(d, ctx, d->up[1], f->data[1], f->linesize[1], cw, ch) != 0)
        return -1;
    return upload_plane(d, ctx, d->up[2], f->data[2], f->linesize[2], cw, ch);
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

/* The backbuffer drawn: present it. Caller holds the d3d11 lock. */
static int present_swap(D3dPresent *d, int src_w, int src_h, const char *path)
{
    HRESULT hr = IDXGISwapChain1_Present(d->swap, 1, 0);
    if (FAILED(hr)) {
        present_fail(d, "Present", hr);
        return VP_FAIL;
    }
    d->fail_logged = 0;
#if !PRODUCTION
    if (!d->logged_present || d->log_src_w != src_w || d->log_src_h != src_h ||
        d->log_dst_w != d->bb_w || d->log_dst_h != d->bb_h) {
        d->logged_present = 1;
        d->log_src_w = src_w;
        d->log_src_h = src_h;
        d->log_dst_w = d->bb_w;
        d->log_dst_h = d->bb_h;
        mse_logf("d3d11 present %s %dx%d -> %dx%d", path, src_w, src_h, d->bb_w, d->bb_h);
    }
#else
    (void)src_w;
    (void)src_h;
    (void)path;
#endif
    return VP_OK;
}

static int d3d_show(VPresenter *p, const AVFrame *f, VpColor c)
{
    static const char *const kPath[PS_N] = { "nv12", "i420", "bgra" };
    D3dPresent *d = p->impl;
    ID3D11Device *dev = mse_d3d11_device();
    ID3D11DeviceContext *ctx = mse_d3d11_context();
    ID3D11Texture2D *tex = NULL;
    D3D11_TEXTURE2D_DESC td;
    const uint8_t *bgra = NULL;
    int src_w = f->width, src_h = f->height, bw = 0, bh = 0, ps;
    int r = VP_FAIL;

    if (!dev || !ctx || mse_d3d11_lost())
        return VP_DROPPED;
    if (src_w <= 0 || src_h <= 0)
        return VP_FAIL;
    if (f->format == AV_PIX_FMT_D3D11) {
        tex = (ID3D11Texture2D *)f->data[0];
        if (!tex || !texture_nv12(tex, &td))
            return VP_FAIL;
        ps = PS_NV12;
    } else if (f->format == AV_PIX_FMT_NV12 && f->data[0] && f->data[1]) {
        ps = PS_NV12;
    } else if ((f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P) &&
               f->data[0] && f->data[1] && f->data[2]) {
        ps = PS_I420;
    } else {
        /* Other formats convert to BGRA, at most backbuffer-sized, before the d3d11 lock. */
        int fit_w, fit_h;
        mse_d3d11_lock();
        fit_w = d->bb_w;
        fit_h = d->bb_h;
        mse_d3d11_unlock();
        if (!p->cpu)
            p->cpu = vp_cpu_create();
        if (!p->cpu || vp_cpu_convert(p->cpu, f, c, fit_w, fit_h, &bgra, &bw, &bh) != 0)
            return VP_FAIL;
        ps = PS_BGRA;
    }

    mse_d3d11_lock();
    if (!d->swap) {
        mse_d3d11_unlock();
        return VP_DROPPED;
    }
    resize_backbuffer(d);
    if (ensure_backbuffer_rtv(d, dev) != 0)
        goto out;
    if (tex) {
        clear_backbuffer(d, ctx);
        if (draw_texture(d, dev, ctx, tex, &td, (UINT)(uintptr_t)f->data[1], src_w, src_h, c) != 0)
            goto out;
    } else {
        if (upload(d, dev, ctx, ps, f, bgra, ps == PS_BGRA ? bw : src_w,
                   ps == PS_BGRA ? bh : src_h) != 0)
            goto out;
        clear_backbuffer(d, ctx);
        draw(d, ctx, ps, d->up_srv, ps == PS_I420 ? 3 : ps == PS_NV12 ? 2 : 1, 1.0f, 1.0f, src_w,
             src_h, c);
    }
    r = present_swap(d, src_w, src_h, tex ? "d3d11" : kPath[ps]);
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
        vp_discard(p);
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

#endif /* _WIN32 */
