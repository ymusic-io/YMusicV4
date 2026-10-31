package io.ymusic.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import io.ymusic.mediastore.model.MediaStoreItem
import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Library's destructive "Remove from disk": the menu item exists only when the shell provides
 * a delete hook, nothing happens before the dialog's confirm, and confirming deletes the FILE
 * (the shell hook) and then the store row — which removes the song from the list.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryDeleteFromDiskTest {

    @Test
    fun remove_from_disk_confirms_then_deletes_file_and_row() {
        Locale.setDefault(Locale.ENGLISH)
        val client = CannedHttp { request, _ ->
            val url = request.url.toString()
            if (request.method == "GET" && url.contains("www.youtube.com") && !url.contains("youtubei"))
                textResponse(request, HOMEPAGE_FIXTURE, "text/html")
            else textResponse(request, "{}", "application/json")
        }
        val engines = DynamicEngineManager(client, appVersion = 1, jsSource = embeddedEngineJsSource())
        runBlocking { engines.start() }
        val store = BetterMediaStore.create(mediaStoreJdbcDriver(":memory:"))
        runBlocking {
            val music = store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music"))
            store.upsertItem(music.id, song("file:///Music/doomed.mp3", "DoomedSong"))
            store.upsertItem(music.id, song("file:///Music/keep.mp3", "KeepSong"))
        }
        val deleted = mutableListOf<MediaStoreItem>()
        try {
            runComposeUiTest {
                val section = mutableStateOf(AppSection.Library)
                setContent {
                    App(
                        AppGraph(
                            engines = engines,
                            player = StubPlayer(),
                            playerSurface = {},
                            mediaStore = store,
                            deleteFromDisk = { deleted += it },
                        ),
                        section,
                    )
                }

                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)
                onNodeWithText("Songs").performClick()
                waitUntilAtLeastOneExists(hasText("DoomedSong"), 5_000)

                // Songs sort by title: DoomedSong is the first row's ⋮.
                onAllNodesWithActionLabel("More")[0].performClick()
                onNodeWithText("Remove from disk").performClick()

                // The confirm dialog names the file; canceling deletes nothing.
                waitUntilAtLeastOneExists(hasText("Delete file?"), 5_000)
                onNodeWithText("Cancel").performClick()
                waitUntilDoesNotExist(hasText("Delete file?"), 5_000)
                assertTrue(deleted.isEmpty(), "cancel must not delete")

                // Confirm: the hook gets the item, the row leaves the list, the sibling stays.
                onAllNodesWithActionLabel("More")[0].performClick()
                onNodeWithText("Remove from disk").performClick()
                waitUntilAtLeastOneExists(hasText("Delete file?"), 5_000)
                onNodeWithText("Delete").performClick()
                waitUntilDoesNotExist(hasText("DoomedSong"), 5_000)
                waitUntilAtLeastOneExists(hasText("KeepSong"), 5_000)
                assertEquals(listOf("DoomedSong"), deleted.map { it.title })
                assertEquals(listOf("KeepSong"), runBlocking { store.allItems() }.map { it.title })
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    private fun song(uri: String, title: String) = MediaItemDraft(
        uri = Uri.parse(uri),
        title = title,
        mimeType = "audio/mpeg",
        fileSize = title.length.toLong(),
        lastModified = 1_000L,
    )
}
