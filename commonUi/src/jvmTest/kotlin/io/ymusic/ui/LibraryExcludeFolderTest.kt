package io.ymusic.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
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
import kmpcommon.uri.Uri
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test

/**
 * Excluding a subfolder from the Folders tab hides its songs from the library (the Songs tab), but
 * keeps the folder listed so it can be re-included. Drives the real store through the UI.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryExcludeFolderTest {

    @Test
    fun excluding_a_subfolder_hides_its_songs_but_keeps_the_folder() {
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
            // Music > Rock (RockSong); a separate Keep root (KeepSong) that must survive exclusion.
            val music = store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music"))
            val rock = store.upsertBucket(BucketDraft(Uri.parse("file:///Music/Rock"), "Rock", parentId = music.id))
            store.upsertItem(rock.id, song("file:///Music/Rock/r.mp3", "RockSong"))
            val keep = store.upsertBucket(BucketDraft(Uri.parse("file:///Keep"), "Keep"))
            store.upsertItem(keep.id, song("file:///Keep/k.mp3", "KeepSong"))
        }
        try {
            runComposeUiTest {
                val section = mutableStateOf(AppSection.Library)
                setContent {
                    App(
                        AppGraph(engines = engines, player = StubPlayer(), playerSurface = {}, mediaStore = store),
                        section,
                    )
                }

                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)

                // Both songs are in the library to begin with.
                onNodeWithText("Songs").performClick()
                waitUntilAtLeastOneExists(hasText("RockSong"), 5_000)
                onAllNodesWithText("KeepSong").assertCountEquals(1)

                // Open Music > Rock and exclude it via the folder's overflow menu.
                onNodeWithText("Folders").performClick()
                onNodeWithText("Music").performClick()
                waitUntilAtLeastOneExists(hasText("Rock"), 5_000)
                onNodeWithActionLabel("More").performClick() // Rock is the only row here
                onNodeWithText("Exclude folder").performClick()

                // Rock's song leaves the library; the sibling stays. The folder itself is still listed.
                onNodeWithText("Songs").performClick()
                waitUntilDoesNotExist(hasText("RockSong"), 5_000)
                onAllNodesWithText("KeepSong").assertCountEquals(1)
                onNodeWithText("Folders").performClick()
                waitUntilAtLeastOneExists(hasText("Rock"), 5_000)
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
