package io.ymusic.ytmedia.sabr

/**
 * Everything the SABR protocol needs for one video, supplied by the caller as plain data —
 * this module has no knowledge of where it comes from (in the app: the webview player surface,
 * which owns the attestation chain; in tests: fixtures).
 */
class SabrStreamSpec(
    val videoId: String,
    /** `serverAbrStreamingUrl` with sig/n ALREADY decoded — :yt-media never touches player JS. */
    val sabrUrl: String,
    /** `videoPlaybackUstreamerConfig`, base64-decoded; passed through opaquely. */
    val ustreamerConfig: ByteArray,
    /** GVS PO token bound to [videoId], base64-decoded. Without it media stops ~40s in (part 58). */
    val poToken: ByteArray?,
    /** Must describe the InnerTube client that produced the player response. */
    val clientInfo: ClientInfo,
    /** `streamingData.adaptiveFormats` metadata. */
    val formats: List<AdaptiveFormat>,
    /**
     * Timed-text tracks from the player response. Not part of the SABR protocol — carried here because
     * they come from the same player response and the downloader needs them. Their [CaptionTrack.baseUrl]
     * arrives ready to GET (PO token bound by the download's spec builder).
     */
    val captionTracks: List<CaptionTrack> = emptyList(),
    /** The User-Agent the engine fetched this response and minted [poToken] under; null from a bundle that predates it. */
    val userAgent: String? = null,
)

/** One timed-text (caption/subtitle) track. [baseUrl] is a plain HTTP GET — not SABR. */
class CaptionTrack(
    val languageCode: String,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean,
    val isAsr: Boolean,
)
