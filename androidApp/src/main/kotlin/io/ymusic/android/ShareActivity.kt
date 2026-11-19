package io.ymusic.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import io.ymusic.ui.LinkAction
import io.ymusic.ui.LinkPlayChooser
import io.ymusic.ui.LinkQuickDownload
import io.ymusic.ui.quickDownloadLink
import io.ymusic.ui.userMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The share-sheet targets (YouTube's Share → YMusic): one activity behind four aliases — Play (asks
 * play / play single / queue next / queue last), MP3 download, M4A download, Other download (the
 * options dialog). It picks the link out of the shared text and the action, then hands both to
 * [MainActivity], whose App resolves and runs them — except MP3 / M4A, which download in the background
 * without opening the app whenever App wouldn't need to show anything ([quickDownloadLink]).
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = sharedUrl(intent)
        if (url == null) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val action = when (intent.component?.className?.substringAfterLast('.')) {
            "ShareDownloadMp3" -> LinkAction.DOWNLOAD_MP3
            "ShareDownloadM4a" -> LinkAction.DOWNLOAD_M4A
            "ShareDownload" -> LinkAction.DOWNLOAD
            else -> null
        }
        when (action) {
            LinkAction.DOWNLOAD_MP3, LinkAction.DOWNLOAD_M4A -> {
                if (savedInstanceState == null) quickDownload(url, action) else finish()
                return
            }
            null -> Unit
            else -> {
                forward(url, action)
                return
            }
        }
        val settings = PrefsSettingsStore.open(this)
        setContent {
            LinkPlayChooser(settings) { picked ->
                if (picked != null) forward(url, picked) else finish()
            }
        }
    }

    /**
     * Stays up, invisible and letting touches through to the app below, until the download's foreground
     * service has started (or [FOREGROUND_WAIT_MS] passed) or the link is found to need App: Android only
     * lets a visible app start that service and, on fallback, MainActivity. The work itself is app-scoped,
     * so leaving early doesn't drop it.
     */
    private fun quickDownload(url: String, action: LinkAction) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // Android 12+ passes touches through another app's window only when it is fully transparent.
        window.attributes = window.attributes.apply { alpha = 0f }
        val app = application as YMusicApplication
        val startsBefore = DownloadForegroundService.foregroundStarts.value
        val work = app.appScope.launch {
            val outcome = quickDownloadLink(app.graphAsync().await(), url, action)
            if (outcome == LinkQuickDownload.NeedsUi) {
                app.startActivity(openLinkIntent(app, url, action))
                return@launch
            }
            outcome.userMessage()?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
            if (outcome == LinkQuickDownload.Enqueued) {
                withTimeoutOrNull(FOREGROUND_WAIT_MS) {
                    DownloadForegroundService.foregroundStarts.first { it > startsBefore }
                }
            }
        }
        lifecycleScope.launch {
            work.join()
            finish()
        }
    }

    private fun forward(url: String, action: LinkAction) {
        startActivity(openLinkIntent(this, url, action))
        finish()
    }

    private fun openLinkIntent(context: Context, url: String, action: LinkAction): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_LINK)
            .setData(Uri.parse(url))
            .putExtra(MainActivity.EXTRA_LINK_ACTION, action.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The first web link in the shared text (YouTube shares "https://youtu.be/…?si=…", other apps
     *  wrap it in a sentence); schemeless matches get https. */
    private fun sharedUrl(intent: Intent): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return null
        val match = matcher.group()
        return if (match.contains("://")) match else "https://$match"
    }

    private companion object {
        const val FOREGROUND_WAIT_MS = 5_000L
    }
}
