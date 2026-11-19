package io.ymusic.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import io.ymusic.imageloader.DecodedImage
import io.ymusic.imageloader.ImageScale
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.mediastore.model.MediaStoreItem
import kmpcommon.log.Crumb
import kmpcommon.log.CrumbLogger
import kmpcommon.log.Logger
import kmpcommon.uri.Uri
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MediaStoreArtTest {

    private fun item(
        thumbnailStreamIndex: Int? = null,
        videoCodec: String? = null,
        youtubeVid: String? = null,
    ) = MediaStoreItem(
        id = 7L,
        bucketId = null,
        uri = Uri.parse("file:///song.m4a"),
        title = "A song",
        mimeType = "audio/mp4",
        artist = null,
        album = null,
        genre = null,
        trackNumber = null,
        year = null,
        durationMs = null,
        audioCodec = "aac",
        videoCodec = videoCodec,
        subtitleCodec = null,
        bitrate = null,
        width = null,
        height = null,
        thumbnailStreamIndex = thumbnailStreamIndex,
        youtubeVid = youtubeVid,
        youtubeItags = null,
        fileSize = 1L,
        lastModified = 0L,
        createdAt = 0L,
    )

    @Test
    fun any_artwork_source_mints_the_one_art_url() {
        assertEquals("art://pic/7", localArtUrl(item(thumbnailStreamIndex = 0)))
        assertEquals("art://pic/7", localArtUrl(item(videoCodec = "h264")))
        assertEquals("art://pic/7", localArtUrl(item(youtubeVid = "dQw4w9WgXcQ")))
    }

    @Test
    fun nothing_to_show_still_has_the_art_url() {
        assertEquals("art://pic/7", localArtUrl(item()))
        assertFalse(hasArtSource(item()))
    }

    @Test
    fun youtube_thumb_url_is_the_stable_mqdefault_variant() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", youtubeThumbUrl("dQw4w9WgXcQ"))
    }

    @Test
    fun no_cover_but_a_video_id_delegates_to_youtube_art() = runBlocking {
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val stored = store.upsertItem(
            bucketId = null,
            draft = MediaItemDraft(
                uri = Uri.parse("file:///song.m4a"),
                title = "A song",
                mimeType = "audio/mp4",
                fileSize = 1L,
                lastModified = 0L,
                youtubeVid = "dQw4w9WgXcQ",
            ),
        )
        var requested: String? = null
        val youtubeArt = YoutubeArt { videoId, _, _ ->
            requested = videoId
            DecodedImage(ImageBitmap(4, 4), 4, 4)
        }
        val fetcher = mediaStoreArtFetcher(FfmpegMetadataGetter("ffmpeg-never-run"), store, youtubeArt, PrintLogger())

        assertNull(
            fetcher.fetch(Uri.parse("https://example.com/x.png"), IntSize(64, 64), ImageScale.Cover),
            "fetcher must decline non-art urls",
        )
        val decoded = fetcher.fetch(Uri.parse("art://pic/${stored.id}"), IntSize(64, 64), ImageScale.Cover)
        assertNotNull(decoded, "fetcher returned null for its own scheme")
        assertEquals("dQw4w9WgXcQ", requested)
    }
}

private class PrintLogger(crumbs: List<Crumb> = emptyList()) : CrumbLogger(crumbs) {
    override fun fork(crumbs: List<Crumb>): CrumbLogger = PrintLogger(crumbs)

    override fun log(level: Logger.Level, message: String, error: Throwable?) {
        println("[$level] $path: $message" + (error?.let { " ($it)" } ?: ""))
    }
}
