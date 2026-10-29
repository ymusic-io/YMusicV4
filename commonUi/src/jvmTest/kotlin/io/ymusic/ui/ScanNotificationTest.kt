package io.ymusic.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
 * A scan is app-level, not Library-level: starting one in the Folders tab and then navigating to
 * Discover must NOT cancel it, and its completion summary must still reach the user (as the app
 * snackbar) on whatever screen is open — a notification, not a Library-only banner.
 */
@OptIn(ExperimentalTestApi::class)
class ScanNotificationTest {

    @Test
    fun scan_survives_leaving_library_and_notifies_on_discover() {
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
        val gate = CompletableDeferred<Unit>()
        try {
            runComposeUiTest {
                val graph = AppGraph(
                    engines = engines,
                    player = StubPlayer(),
                    playerSurface = {},
                    mediaStore = store,
                    scanLibraryFolder = { ScanResult.NoFolders },
                    // Report progress, then block until the test lets it finish — so it is still
                    // in-flight when we navigate away.
                    rescanLibrary = { _, onProgress -> onProgress(5); gate.await(); ScanResult.Done(5, 2, 1, 0, 0) },
                )
                val section = mutableStateOf(AppSection.Library)
                setContent { App(graph, section) }

                waitUntilAtLeastOneExists(hasText("Folders"), 20_000)
                onNodeWithActionLabel("Rescan folders").performClick()
                waitUntilAtLeastOneExists(hasText("Scanning 5…"), 10_000)

                // Leave Library for Discover WHILE the scan runs.
                section.value = AppSection.Discover
                waitForIdle()

                // Let the scan finish now that we're on Discover; its summary must still notify here.
                gate.complete(Unit)
                waitUntilAtLeastOneExists(hasText("Scanned 5: 2 added, 1 updated, 0 removed"), 10_000)
            }
        } finally {
            gate.complete(Unit)
            runBlocking { engines.close() }
        }
    }
}
