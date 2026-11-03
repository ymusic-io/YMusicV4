package io.ymusic.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.DynamicEngineManager
import io.ymusic.dynamicengine.embeddedEngineJsSource
import io.ymusic.settings.AppSettings
import io.ymusic.settings.InMemorySettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The drawer's Feedback section: with reports off it asks first; Continue turns them on, and the
 *  logs go out only on Copy or a contact row, once per visit; Cancel leaves without sending. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FeedbackScreenTest {

    private fun engines() =
        DynamicEngineManager(CannedHttp { _, _ -> throw UnsupportedOperationException() }, appVersion = 1, jsSource = embeddedEngineJsSource())

    private class RecordingClipboard : Clipboard {
        var text: String? = null
        override val nativeClipboard: NativeClipboard get() = throw UnsupportedOperationException()
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = clipEntry?.nativeClipEntry?.let { (it as java.awt.datatransfer.Transferable).getTransferData(DataFlavor.stringFlavor) as String }
        }
    }

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun contact_rows_send_the_logs_once_and_a_message_sends_its_own_report() {
        Locale.setDefault(Locale.ENGLISH)
        val captures = mutableListOf<Pair<String, String?>>()
        val graph = AppGraph(
            settings = InMemorySettingsStore(),
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            captureFeedbackReport = { subject, message -> synchronized(captures) { captures += subject to message } },
        )
        runBlocking { graph.settings.put(AppSettings.crashReports, false) }
        val clipboard = RecordingClipboard()
        val uriHandler = RecordingUriHandler()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalClipboard provides clipboard, LocalUriHandler provides uriHandler) {
                    FeedbackScreen(graph, onDismiss = {})
                }
            }
            waitUntilAtLeastOneExists(hasText("Anonymous reports are off"), 5_000)
            assertEquals(emptyList(), captures, "nothing is sent before the user agrees")
            onNodeWithText("Continue").performClick()

            waitUntilAtLeastOneExists(hasText("Copy"), 5_000)
            waitForIdle()
            assertTrue(graph.settings.flow(AppSettings.crashReports).value)
            assertEquals(null, clipboard.text, "arriving copies nothing")
            assertEquals(emptyList(), captures, "arriving sends nothing")

            onNodeWithText("Copy").performClick()
            waitUntil(timeoutMillis = 5_000) { clipboard.text != null && synchronized(captures) { captures.isNotEmpty() } }
            assertTrue("Device ID: " in clipboard.text!!, "copied: ${clipboard.text}")

            onNodeWithText("Open an issue on GitHub").performClick()
            onNodeWithText("Email feedback@ymusic.io").performClick()
            waitUntil(timeoutMillis = 5_000) { uriHandler.opened.size == 2 }
            waitForIdle()
            assertEquals(listOf<Pair<String, String?>>("Copy" to null), captures, "one logs report per visit")

            onNodeWithText("or via Sentry").performClick()
            onNode(hasSetTextAction()).performTextInput("  playback stops  ")
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 5_000) { synchronized(captures) { captures.size == 2 } }
            assertEquals(listOf("Copy" to null, "playback stops" to "playback stops"), captures)
        }
    }

    @Test
    fun cancel_leaves_without_sending() {
        Locale.setDefault(Locale.ENGLISH)
        var captures = 0
        var dismissed = false
        val graph = AppGraph(
            settings = InMemorySettingsStore(),
            engines = engines(),
            player = StubPlayer(),
            playerSurface = {},
            captureFeedbackReport = { _, _ -> captures++ },
        )
        runBlocking { graph.settings.put(AppSettings.crashReports, false) }
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalClipboard provides RecordingClipboard()) {
                    FeedbackScreen(graph, onDismiss = { dismissed = true })
                }
            }
            waitUntilAtLeastOneExists(hasText("Anonymous reports are off"), 5_000)
            onNodeWithText("Cancel").performClick()
            waitUntil(timeoutMillis = 5_000) { dismissed }
            assertEquals(0, captures)
            assertFalse(graph.settings.flow(AppSettings.crashReports).value)
        }
    }
}
