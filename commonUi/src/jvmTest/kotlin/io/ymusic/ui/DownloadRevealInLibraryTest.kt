package io.ymusic.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.download.DownloadFormat
import io.ymusic.download.DownloadScratch
import io.ymusic.download.DownloadService
import io.ymusic.download.DownloadState
import io.ymusic.download.DownloadStorage
import io.ymusic.download.DownloadStore
import io.ymusic.download.DownloadTask
import io.ymusic.download.SabrSpecResolver
import io.ymusic.download.downloadDatabaseJdbcDriver
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.settings.InMemorySettingsStore
import kmpcommon.uri.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test

/**
 * A completed download's "Reveal in Library" opens the file's actual folder — not the Library root. The
 * download location is a *nested* folder (Music > Downloads), so landing on root would show the "Music"
 * folder row, not the song; seeing the song proves we walked into the right folder.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadRevealInLibraryTest {

    private object NoopStorage : DownloadStorage {
        override fun scratch(taskId: String, name: String): DownloadScratch = throw UnsupportedOperationException()
        override fun deleteScratch(taskId: String) = Unit
        override suspend fun publish(scratch: DownloadScratch, fileName: String, destinationFolder: String): String =
            throw UnsupportedOperationException()
    }

    @Test
    fun reveal_in_library_opens_the_files_folder() {
        Locale.setDefault(Locale.ENGLISH)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engines = DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }

        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        val songUri = "file:///Music/Downloads/mysong.m4a"
        runBlocking {
            val music = store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music"))
            val downloads = store.upsertBucket(
                BucketDraft(Uri.parse("file:///Music/Downloads"), "Downloads", parentId = music.id),
            )
            store.upsertItem(
                downloads.id,
                MediaItemDraft(uri = Uri.parse(songUri), title = "MySong", mimeType = "audio/mp4", fileSize = 10, lastModified = 1L),
            )
        }

        // Deliberately no download location set: reveal must resolve the folder from the task's persisted
        // fileUri, not any current setting.
        val settings = InMemorySettingsStore()

        val downloadStore = DownloadStore(downloadDatabaseJdbcDriver())
        runBlocking {
            downloadStore.upsert(
                DownloadTask(
                    id = "t1", videoId = "vid", title = "MySong", artist = null, thumbnailUrl = null,
                    format = DownloadFormat.M4A, requestedHeight = null, state = DownloadState.COMPLETED,
                    downloadedBytes = 10, totalBytes = 10, fileUri = songUri, errorMessage = null,
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
        }
        val downloads = DownloadService(
            store = downloadStore,
            scope = scope,
            now = { 0L },
            resolver = SabrSpecResolver { throw UnsupportedOperationException() },
            client = CannedHttp { _, _ -> throw UnsupportedOperationException() },
            userAgent = "test",
            ffmpeg = null,
            storage = NoopStorage,
            downloadFolder = { "file:///tmp/dl" },
        )

        val graph = AppGraph(
            engines = engines,
            player = StubPlayer(),
            playerSurface = {},
            mediaStore = store,
            settings = settings,
            downloads = downloads,
        )
        try {
            runComposeUiTest {
                setContent { App(graph, mutableStateOf(AppSection.Downloads)) }

                // The completed card shows on the Downloads screen.
                waitUntilAtLeastOneExists(hasText("MySong"), 20_000)
                onNodeWithActionLabel("More").performClick()
                onNodeWithText("Reveal in Library").performClick()

                // We land inside Music > Downloads. "Music" is a Library breadcrumb ancestor — it never
                // appears on the Downloads screen, so seeing it proves we actually switched sections (not a
                // false pass off the still-visible download card). "MySong" is the song row, only listed
                // inside its folder (at root we'd see the "Music" folder, not the song).
                waitUntilAtLeastOneExists(hasText("Music"), 5_000)
                waitUntilAtLeastOneExists(hasText("MySong"), 5_000)
            }
        } finally {
            runBlocking { engines.close() }
            scope.cancel()
        }
    }
}
