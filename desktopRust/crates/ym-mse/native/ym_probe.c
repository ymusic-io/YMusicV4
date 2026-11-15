/* Library scan metadata and artwork, read in-process through libav. */
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libswscale/swscale.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    char title[512];
    char artist[512];
    char album[512];
    char genre[256];
    char date[64];
    char track[64];
    char audio_codec[64];
    char video_codec[64];
    char subtitle_codec[64];
    char format[128];
    long long duration_ms; /* -1 unknown */
    long long bitrate;     /* -1 unknown */
    int width, height;     /* 0 without video */
    int thumbnail_stream;  /* attached picture stream index, -1 none */
    int has_audio;
    char composer[256];        /* the container's; downloads write `videoId|gain=dB` here */
    char stream_composer[256]; /* the first audio stream's (Ogg/Opus keep tags per stream) */
} YmProbe;

static void copy_tag(AVDictionary *dict, const char *key, char *out, size_t cap)
{
    AVDictionaryEntry *entry = av_dict_get(dict, key, NULL, 0);
    if (entry && entry->value && !out[0]) {
        strncpy(out, entry->value, cap - 1);
        out[cap - 1] = 0;
    }
}

static void copy_name(const char *name, char *out, size_t cap)
{
    if (name && !out[0]) {
        strncpy(out, name, cap - 1);
        out[cap - 1] = 0;
    }
}

/* 0 on success; a negative AVERROR when the file cannot be opened or read as media. */
int ym_probe(const char *path, YmProbe *out)
{
    AVFormatContext *ctx = NULL;
    int rc;
    memset(out, 0, sizeof(*out));
    out->duration_ms = -1;
    out->bitrate = -1;
    out->thumbnail_stream = -1;
    rc = avformat_open_input(&ctx, path, NULL, NULL);
    if (rc < 0)
        return rc;
    rc = avformat_find_stream_info(ctx, NULL);
    if (rc < 0) {
        avformat_close_input(&ctx);
        return rc;
    }
    copy_name(ctx->iformat ? ctx->iformat->name : NULL, out->format, sizeof(out->format));
    copy_tag(ctx->metadata, "title", out->title, sizeof(out->title));
    copy_tag(ctx->metadata, "artist", out->artist, sizeof(out->artist));
    copy_tag(ctx->metadata, "album_artist", out->artist, sizeof(out->artist));
    copy_tag(ctx->metadata, "album", out->album, sizeof(out->album));
    copy_tag(ctx->metadata, "genre", out->genre, sizeof(out->genre));
    copy_tag(ctx->metadata, "date", out->date, sizeof(out->date));
    copy_tag(ctx->metadata, "year", out->date, sizeof(out->date));
    copy_tag(ctx->metadata, "track", out->track, sizeof(out->track));
    copy_tag(ctx->metadata, "composer", out->composer, sizeof(out->composer));
    if (ctx->duration > 0)
        out->duration_ms = ctx->duration / 1000;
    if (ctx->bit_rate > 0)
        out->bitrate = ctx->bit_rate;
    for (unsigned i = 0; i < ctx->nb_streams; i++) {
        AVStream *stream = ctx->streams[i];
        AVCodecParameters *par = stream->codecpar;
        const char *name = avcodec_get_name(par->codec_id);
        if (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            if (out->thumbnail_stream < 0)
                out->thumbnail_stream = (int)i;
            continue;
        }
        switch (par->codec_type) {
        case AVMEDIA_TYPE_AUDIO:
            out->has_audio = 1;
            copy_name(name, out->audio_codec, sizeof(out->audio_codec));
            /* Stream tags fill what the container lacks (Ogg/Opus keep them per stream). */
            copy_tag(stream->metadata, "title", out->title, sizeof(out->title));
            copy_tag(stream->metadata, "artist", out->artist, sizeof(out->artist));
            copy_tag(stream->metadata, "album", out->album, sizeof(out->album));
            copy_tag(stream->metadata, "composer", out->stream_composer, sizeof(out->stream_composer));
            break;
        case AVMEDIA_TYPE_VIDEO:
            copy_name(name, out->video_codec, sizeof(out->video_codec));
            out->width = par->width;
            out->height = par->height;
            break;
        case AVMEDIA_TYPE_SUBTITLE:
            copy_name(name, out->subtitle_codec, sizeof(out->subtitle_codec));
            break;
        default:
            break;
        }
    }
    avformat_close_input(&ctx);
    return 0;
}

/* The attached picture's encoded bytes into a malloc'd *out (free with ym_free); 0, or a negative AVERROR. */
int ym_attached_pic(const char *path, int stream_index, uint8_t **out, int *out_len)
{
    AVFormatContext *ctx = NULL;
    AVPacket *pic;
    int rc;
    *out = NULL;
    *out_len = 0;
    rc = avformat_open_input(&ctx, path, NULL, NULL);
    if (rc < 0)
        return rc;
    if (stream_index < 0 || (unsigned)stream_index >= ctx->nb_streams
        || !(ctx->streams[stream_index]->disposition & AV_DISPOSITION_ATTACHED_PIC)) {
        avformat_close_input(&ctx);
        return AVERROR_STREAM_NOT_FOUND;
    }
    pic = &ctx->streams[stream_index]->attached_pic;
    if (pic->size <= 0) {
        avformat_close_input(&ctx);
        return AVERROR_INVALIDDATA;
    }
    *out = malloc(pic->size);
    if (!*out) {
        avformat_close_input(&ctx);
        return AVERROR(ENOMEM);
    }
    memcpy(*out, pic->data, pic->size);
    *out_len = pic->size;
    avformat_close_input(&ctx);
    return 0;
}

static int decode_one(AVCodecContext *dec, AVPacket *packet, AVFrame *frame)
{
    int rc = avcodec_send_packet(dec, packet);
    if (rc < 0 && rc != AVERROR(EAGAIN) && rc != AVERROR_EOF)
        return rc;
    rc = avcodec_receive_frame(dec, frame);
    return rc == AVERROR(EAGAIN) ? 1 : rc;
}

/* A decoded frame a tenth into the video, scaled to fit max_side, as tightly packed RGBA in a
 * malloc'd *out (free with ym_free); 0, or a negative AVERROR. */
int ym_video_frame(const char *path, int max_side, uint8_t **out, int *width, int *height)
{
    AVFormatContext *ctx = NULL;
    AVCodecContext *dec = NULL;
    const AVCodec *codec = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    struct SwsContext *sws = NULL;
    int stream, rc, got = 0, packets = 0;
    *out = NULL;
    *width = *height = 0;
    rc = avformat_open_input(&ctx, path, NULL, NULL);
    if (rc < 0)
        return rc;
    rc = avformat_find_stream_info(ctx, NULL);
    if (rc < 0)
        goto done;
    stream = av_find_best_stream(ctx, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (stream < 0) {
        rc = stream;
        goto done;
    }
    dec = avcodec_alloc_context3(codec);
    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (!dec || !packet || !frame) {
        rc = AVERROR(ENOMEM);
        goto done;
    }
    rc = avcodec_parameters_to_context(dec, ctx->streams[stream]->codecpar);
    if (rc < 0)
        goto done;
    rc = avcodec_open2(dec, codec, NULL);
    if (rc < 0)
        goto done;
    /* The first keyframe at or after the target: one decode, past a fade-in at the start. */
    if (ctx->duration > 0 && avformat_seek_file(ctx, -1, ctx->duration / 10, ctx->duration / 10, INT64_MAX, 0) < 0)
        av_seek_frame(ctx, -1, ctx->duration / 10, AVSEEK_FLAG_BACKWARD);
    /* Bounded so a file with an undecodable video stream gives up instead of reading it all. */
    while (!got && packets < 2000) {
        rc = av_read_frame(ctx, packet);
        if (rc < 0) {
            rc = decode_one(dec, NULL, frame);
            if (rc == 0)
                got = 1;
            break;
        }
        if (packet->stream_index == stream) {
            packets++;
            rc = decode_one(dec, packet, frame);
            if (rc == 0)
                got = 1;
            else if (rc < 0 && rc != AVERROR_INVALIDDATA) {
                av_packet_unref(packet);
                goto done;
            }
        }
        av_packet_unref(packet);
    }
    if (!got) {
        rc = rc < 0 ? rc : AVERROR_INVALIDDATA;
        goto done;
    }
    {
        int src_w = frame->width, src_h = frame->height;
        int dst_w = src_w, dst_h = src_h;
        if (src_w <= 0 || src_h <= 0) {
            rc = AVERROR_INVALIDDATA;
            goto done;
        }
        if (max_side > 0 && (src_w > max_side || src_h > max_side)) {
            if (src_w >= src_h) {
                dst_w = max_side;
                dst_h = (int)((long long)src_h * max_side / src_w);
            } else {
                dst_h = max_side;
                dst_w = (int)((long long)src_w * max_side / src_h);
            }
            if (dst_w < 1)
                dst_w = 1;
            if (dst_h < 1)
                dst_h = 1;
        }
        sws = sws_getContext(src_w, src_h, frame->format, dst_w, dst_h, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL,
                             NULL);
        if (!sws) {
            rc = AVERROR(EINVAL);
            goto done;
        }
        *out = malloc((size_t)dst_w * dst_h * 4);
        if (!*out) {
            rc = AVERROR(ENOMEM);
            goto done;
        }
        {
            uint8_t *planes[4] = {*out, NULL, NULL, NULL};
            int strides[4] = {dst_w * 4, 0, 0, 0};
            sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize, 0, src_h, planes, strides);
        }
        *width = dst_w;
        *height = dst_h;
        rc = 0;
    }
done:
    sws_freeContext(sws);
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&dec);
    avformat_close_input(&ctx);
    return rc;
}

void ym_free(void *pointer)
{
    free(pointer);
}
