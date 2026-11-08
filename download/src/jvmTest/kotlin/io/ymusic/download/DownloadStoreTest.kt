package io.ymusic.download

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadStoreTest {

    private fun store() = DownloadStore(downloadDatabaseJdbcDriver())

    private fun task(id: String, state: DownloadState = DownloadState.QUEUED) = DownloadTask(
        id = id, videoId = "vid$id", title = "Song $id", artist = "Artist", thumbnailUrl = null,
        format = DownloadFormat.MP3, requestedHeight = null, state = state,
        downloadedBytes = 0, totalBytes = 0, fileUri = null, errorMessage = null,
        createdAt = 100, updatedAt = 100,
    )

    @Test
    fun upsert_and_read_round_trips() = runTest {
        val store = store()
        store.upsert(task("a"))
        store.upsert(task("b"))
        val all = store.all()
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertEquals("Song a", all.first().title)
        assertEquals(DownloadFormat.MP3, all.first().format)
    }

    @Test
    fun update_state_and_progress_and_completed() = runTest {
        val store = store()
        store.upsert(task("a"))
        store.updateProgress("a", downloaded = 500, total = 1000, updatedAt = 200)
        store.updateState("a", DownloadState.DOWNLOADING, error = null, updatedAt = 200)
        var row = store.all().first()
        assertEquals(DownloadState.DOWNLOADING, row.state)
        assertEquals(500, row.downloadedBytes)
        assertEquals(0.5f, row.progress)

        store.updateCompleted("a", fileUri = "/out/a.mp3", bytes = 1000, loudnessGainDb = null, updatedAt = 300)
        row = store.all().first()
        assertEquals(DownloadState.COMPLETED, row.state)
        assertEquals("/out/a.mp3", row.fileUri)
        assertNull(row.errorMessage)
    }

    @Test
    fun delete_removes_row() = runTest {
        val store = store()
        store.upsert(task("a"))
        store.delete("a")
        assertEquals(emptyList(), store.all())
    }
}
