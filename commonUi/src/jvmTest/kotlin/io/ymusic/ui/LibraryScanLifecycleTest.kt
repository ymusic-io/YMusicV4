package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.mediastore.BetterMediaStore
import io.ymusic.mediastore.mediaStoreJdbcDriver
import io.ymusic.mediastore.model.BucketDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test

/**
 * A scan runs on a Library-screen-level scope, not the Folders tab's — so switching tabs (which
 * drops the Folders composable) must not cancel an in-flight scan (regression: it did, and the
 * "Scanning…" status vanished on return).
 */
@OptIn(ExperimentalTestApi::class)
class LibraryScanLifecycleTest {

    @Test
    fun an_in_flight_scan_survives_a_tab_switch() {
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
        runBlocking { store.upsertBucket(BucketDraft(Uri.parse("file:///Music"), "Music")) }
        try {
            runComposeUiTest {
                val graph = AppGraph(
                    engines = engines,
                    player = StubPlayer(),
                    playerSurface = {},
                    mediaStore = store,
                    scanLibraryFolder = { ScanResult.NoFolders },
                    // Report 129 then park, so the scan stays in-flight for the whole test.
                    rescanLibrary = { _, onProgress -> onProgress(129); CompletableDeferred<ScanResult?>().await() },
                )
                val section = androidx.compose.runtime.mutableStateOf(AppSection.Library)
                setContent { App(graph, section) }

                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)
                onNodeWithActionLabel("Rescan folders").performClick()
                waitUntilAtLeastOneExists(hasText("Scanning 129…"), 10_000)

                // Leave the Folders tab and come back — the scan must still be running.
                onNodeWithText("Songs").performClick()
                waitUntilAtLeastOneExists(hasText("Artists"), 5_000) // on Songs tab now
                onNodeWithText("Folders").performClick()
                waitUntilAtLeastOneExists(hasText("Scanning 129…"), 5_000)
            }
        } finally {
            runBlocking { engines.close() }
        }
    }
}
