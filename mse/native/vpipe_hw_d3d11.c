#ifdef _WIN32

#include "mse_d3d11.h"
#include "vpipe_hw.h"

static const AVCodec *d3d_decoder(enum AVCodecID id)
{
    const char *name;
    const AVCodec *c;
    switch (id) {
    case AV_CODEC_ID_H264:
        name = "h264";
        break;
    case AV_CODEC_ID_HEVC:
        name = "hevc";
        break;
    case AV_CODEC_ID_VP9:
        name = "vp9";
        break;
    case AV_CODEC_ID_AV1:
        name = "av1";
        break;
    default:
        return NULL;
    }
    c = avcodec_find_decoder_by_name(name);
    return c && mse_d3d11_can_decode(c) ? c : NULL;
}

/* par_ok first: native av1 has no software path, so a stream D3D11 cannot take must not pick it. */
static int d3d_usable(const AVCodecParameters *par)
{
    return !mse_d3d11_lost() && mse_d3d11_par_ok(par) && d3d_decoder(par->codec_id);
}

static const VpHw k_d3d11 = {
    .name = "d3d11va",
    .pix_fmt = AV_PIX_FMT_D3D11,
    .readback_ok = 0,
    .usable = d3d_usable,
    .decoder = d3d_decoder,
    .device_ref = mse_d3d11_hwdev_ref,
    .get_format = mse_d3d11_pick,
    .lost = mse_d3d11_check_lost,
};

const VpHw *vpipe_hw_d3d11(void)
{
    return &k_d3d11;
}

#endif /* _WIN32 */
