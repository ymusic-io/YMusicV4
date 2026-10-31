package io.ymusic.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * The local Playlists tab, driven through the UI over the real store: the three computed system
 * playlists are always present (and Favorite is gone), and a song can be added to a brand-new user
 * playlist via the song-row menu, which then lists that song when opened.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryPlaylistTest {

    @Test
    fun add_a_song_to_a_new_playlist_and_open_it() {
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
            store.upsertItem(music.id, song("file:///Music/a.mp3", "MySong"))
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

                // The Playlists tab shows the system playlists — and neither "Favorite" nor "Last
                // added" (the latter is covered by the Songs tab's "Date added" sort).
                onNodeWithText("Playlists").performClick()
                waitUntilAtLeastOneExists(hasText("Recent play"), 5_000)
                onNodeWithText("Top tracks").assertIsDisplayed()
                onNodeWithText("Favorite").assertDoesNotExist()
                onNodeWithText("Last added").assertDoesNotExist()

                // Add MySong to a new playlist from the Songs tab's row menu.
                onNodeWithText("Songs").performClick()
                waitUntilAtLeastOneExists(hasText("MySong"), 5_000)
                onNodeWithActionLabel("More").performClick()
                onNodeWithText("Add to playlist").performClick()
                waitUntilAtLeastOneExists(hasText("New playlist…"), 5_000)
                onNodeWithText("New playlist…").performClick()
                waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
                onNode(hasSetTextAction()).performTextInput("Chill")
                onNodeWithText("Create").performClick()
                waitUntilDoesNotExist(hasText("Create"), 5_000)

                // The new playlist appears in Playlists and lists the song when opened.
                onNodeWithText("Playlists").performClick()
                waitUntilAtLeastOneExists(hasText("Chill"), 5_000)
                onNodeWithText("Chill").performClick()
                waitUntilAtLeastOneExists(hasText("MySong"), 5_000)
                // A user playlist carries a sort control, on its manual order by default; a system playlist doesn't.
                onNodeWithText("Manual").assertIsDisplayed()
                onNodeWithActionLabel("Back").performClick()
                waitUntilAtLeastOneExists(hasText("Top tracks"), 5_000)
                onNodeWithText("Top tracks").performClick()
                waitUntilDoesNotExist(hasText("Manual"), 5_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }

    @Test
    fun creating_a_duplicate_playlist_name_shows_an_error_and_blocks_create() {
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
            // A song so the library isn't empty (empty → no tabs), plus the playlist to collide with.
            val music = store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music"))
            store.upsertItem(music.id, song("file:///Music/a.mp3", "MySong"))
            store.createPlaylist("Chill")
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
                onNodeWithText("Playlists").performClick()
                waitUntilAtLeastOneExists(hasText("Chill"), 5_000)

                // Open the create dialog and type the existing name.
                onNodeWithText("New playlist", substring = true).performClick()
                waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
                onNode(hasSetTextAction()).performTextInput("Chill")

                // The error shows under the field and Create is disabled.
                waitUntilAtLeastOneExists(hasText("A playlist with this name already exists"), 5_000)
                onNodeWithText("Create").assertIsNotEnabled()
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
