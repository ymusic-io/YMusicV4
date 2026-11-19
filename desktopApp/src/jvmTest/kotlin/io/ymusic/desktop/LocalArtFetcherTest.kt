package io.ymusic.desktop

import androidx.compose.ui.unit.IntSize
import io.ymusic.imageloader.ImageScale
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.ui.YoutubeArt
import io.ymusic.ui.mediaStoreArtFetcher
import kmpcommon.uri.Uri
import kmpffmpeg.metadata.FfmpegMetadataGetter
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The whole local-art pipeline on real pieces: ffmpeg muxes a cover into an mp3, the store hands
 *  out the item id, the fetcher resolves id → uri and extracts + decodes the cover. Skipped when no
 *  ffmpeg binary is on this machine. */
class LocalArtFetcherTest {
    @Test
    fun extractsAndDecodesTheEmbeddedCoverById() {
        // The binary under test is whatever the app would use; the FIXTURES need a fuller build,
        // because the shipped prebuilt is configured for metadata and muxing only and carries no
        // lavfi source to synthesise a cover with.
        val ffmpeg = resolveFfmpeg() ?: return // no binary on this machine — nothing to test against
        val fixtureFfmpeg = fixtureCapableFfmpeg() ?: return
        val dir = File(System.getProperty("java.io.tmpdir"), "local-art-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val cover = File(dir, "cover.png")
            val tone = File(dir, "tone.mp3")
            val song = File(dir, "song.mp3")
            run(fixtureFfmpeg, "-y", "-f", "lavfi", "-i", "color=red:s=64x48", "-frames:v", "1", cover.path)
            run(fixtureFfmpeg, "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", tone.path)
            run(
                fixtureFfmpeg, "-y", "-i", tone.path, "-i", cover.path,
                "-map", "0:a", "-map", "1:v", "-c", "copy",
                "-id3v2_version", "3", "-disposition:v:0", "attached_pic", song.path,
            )

            val decoded = runBlocking {
                val store = BetterMediaStore.create(mediaStoreJdbcDriver(File(dir, "store.db").path))
                val item = store.upsertItem(
                    bucketId = null,
                    draft = MediaItemDraft(
                        uri = Uri.parse("file://${song.path}"),
                        title = "song",
                        mimeType = "audio/mpeg",
                        fileSize = song.length(),
                        lastModified = song.lastModified(),
                        thumbnailStreamIndex = 1,
                    ),
                )
                val youtubeArt = YoutubeArt { _, _, _ -> error("embedded covers must not hit the network") }
                val fetcher = mediaStoreArtFetcher(FfmpegMetadataGetter(ffmpeg), store, youtubeArt, StdoutLogger())
                assertNull(
                    fetcher.fetch(Uri.parse("https://example.com/x.png"), IntSize(64, 64), ImageScale.Cover),
                    "fetcher must decline non-art urls",
                )
                fetcher.fetch(Uri.parse("art://pic/${item.id}"), size = IntSize(64, 64), scale = ImageScale.Cover)
            }
            assertNotNull(decoded, "fetcher returned null for its own scheme")
            assertEquals(64, decoded.bitmap.width)
            assertEquals(48, decoded.bitmap.height)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor() == 0) { "command failed: ${cmd.joinToString(" ")}\n$out" }
    }

    /** An ffmpeg that can synthesise test media (needs the lavfi input), or null. */
    private fun fixtureCapableFfmpeg(): String? =
        (listOfNotNull(resolveFfmpeg()) + listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg"))
            .firstOrNull { candidate ->
                runCatching {
                    val p = ProcessBuilder(candidate, "-hide_banner", "-formats")
                        .redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    p.waitFor()
                    out.contains("lavfi")
                }.getOrDefault(false)
            }
}
