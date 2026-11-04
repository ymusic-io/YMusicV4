package io.ymusic.download

/** How an MP3 download is encoded. [kbps] is the bitrate shown for it. */
enum class Mp3Encoding(val kbps: Int) {
    /** LAME ABR at 128 kbps. */
    ABR_128(128),

    /** LAME VBR V0 (`-q:a 0`); averages about 256 kbps on YouTube audio. */
    VBR_V0(256),

    /** LAME ABR at 320 kbps. */
    ABR_320(320),
    ;

    companion object {
        fun of(quality: AudioQuality): Mp3Encoding = when (quality) {
            AudioQuality.SAVE -> ABR_128
            AudioQuality.MEDIUM -> VBR_V0
            AudioQuality.BEST -> ABR_320
        }
    }
}
