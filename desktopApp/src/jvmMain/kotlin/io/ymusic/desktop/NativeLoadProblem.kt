package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import io.ymusic.mse.MseLibrary
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.LanguageDirection
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.error_generic
import io.ymusic.ui.resources.media_engine_unavailable
import io.ymusic.ui.resources.native_blocked
import io.ymusic.ui.resources.native_blocked_help
import kmpcommon.log.Logger
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.TechnicalException
import projectcommon.error.runSuspendCatching
import java.util.Collections
import java.util.IdentityHashMap

/** The native libraries the app can't do without, and the codes their failures carry. */
internal enum class NativeComponent(val blockedCode: String, val failedCode: String) {
    /** libav and the JNI over it: all audio and video, local and YouTube. Browsing and updates don't need it. */
    Media("E80077", "E80078"),

    /** QuickJS, which runs the scraping engine, and whatever else boot needs: no app without it. */
    Engine("E10002", "E10003"),
}

internal sealed interface NativeLoadProblem {
    /** An App Control policy refused a DLL. A state of the machine, not a defect of this launch. */
    data object Blocked : NativeLoadProblem

    /** Any other failure, with the code the user can report. */
    data class Failed(val code: String) : NativeLoadProblem
}

internal fun mediaLibraryLoaded(): Boolean = MseLibrary.loadFailure == null

/** Null when [error] isn't a native library failing to load. */
internal fun classifyNativeLoadFailure(
    error: Throwable,
    component: NativeComponent,
    policyMessages: List<String>,
): NativeLoadProblem? {
    val linked = error.linkedThrowables()
    if (linked.none { it is LinkageError }) return null
    val blocked = linked.any { link -> policyMessages.any { link.message?.containsSystemText(it) == true } }
    return if (blocked) NativeLoadProblem.Blocked else NativeLoadProblem.Failed(component.failedCode)
}

/**
 * Whether this load error holds [text], read with FormatMessageW. HotSpot reads the same message through the
 * ANSI code page, so a display language outside it arrives with `?` for every character the page lacks.
 */
internal fun String.containsSystemText(text: String): Boolean =
    indices.any { start ->
        start + text.length <= length &&
            text.indices.all { offset ->
                val seen = this[start + offset]
                seen == text[offset] || (seen == '?' && text[offset].code > ASCII_MAX)
            }
    }

/** The block's wording in the language of the load error, read from Windows. */
internal fun windowsPolicyMessages(log: Logger): List<String> {
    if (!isWindows) return emptyList()
    val translated = try {
        POLICY_ERRORS.mapNotNull(::windowsSystemMessage)
    } catch (error: LinkageError) {
        log.log(Logger.Level.WARN, "no system text for a policy block", error)
        emptyList()
    }
    return translated + ENGLISH_POLICY_MESSAGE
}

/**
 * A block repeats on every launch until the machine or the build changes, so it reaches telemetry once per
 * app version and otherwise stays in the app log. Any other failure is ours and is reported every time.
 */
internal suspend fun reportNativeLoadProblem(
    problem: NativeLoadProblem,
    component: NativeComponent,
    error: Throwable,
    settings: SettingsStore,
    appVersionCode: Int,
    log: Logger,
) {
    log.log(Logger.Level.WARN, "${component.name} native library did not load", error)
    when (problem) {
        is NativeLoadProblem.Failed ->
            log.captureError(TechnicalException(problem.code, "${component.name} native library failed to load", cause = error))
        NativeLoadProblem.Blocked -> {
            if (settings.flow(AppSettings.nativeBlockReportedVersion).value == appVersionCode) return
            runSuspendCatching { settings.put(AppSettings.nativeBlockReportedVersion, appVersionCode) }
                .onFailure { log.log(Logger.Level.WARN, "native block report not remembered", it) }
            val blocked = "${component.name} native library blocked by an Application Control policy"
            log.captureError(TechnicalException(component.blockedCode, blocked, cause = error))
        }
    }
}

/** The app, under a banner while the media library is down: it still works, it just can't play. */
@Composable
internal fun WithMediaBanner(problem: NativeLoadProblem?, content: @Composable () -> Unit) {
    if (problem == null) return content()
    Column(Modifier.fillMaxSize()) {
        LanguageDirection { MediaUnavailableBanner(problem) }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun MediaUnavailableBanner(problem: NativeLoadProblem) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = DesignSpacing.Space16, vertical = DesignSpacing.Space8),
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space4),
    ) {
        val media = stringResource(Res.string.media_engine_unavailable)
        val lines = when (problem) {
            NativeLoadProblem.Blocked -> listOf(
                stringResource(Res.string.native_blocked),
                media,
                stringResource(Res.string.native_blocked_help),
            )
            is NativeLoadProblem.Failed -> listOf(media, stringResource(Res.string.error_generic, problem.code))
        }
        for (line in lines) {
            Text(line, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** In place of the app when the engine library is down. */
@Composable
internal fun AppUnavailableMessage(problem: NativeLoadProblem) {
    val lines = when (problem) {
        NativeLoadProblem.Blocked -> listOf(
            stringResource(Res.string.native_blocked),
            stringResource(Res.string.native_blocked_help),
        )
        is NativeLoadProblem.Failed -> listOf(stringResource(Res.string.error_generic, problem.code))
    }
    LanguageDirection {
        for (line in lines) Text(line, color = MaterialTheme.colorScheme.error)
    }
}

private fun Throwable.linkedThrowables(): Set<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun visit(link: Throwable?) {
        if (link == null || !seen.add(link)) return
        visit(link.cause)
        link.suppressed.forEach(::visit)
    }
    visit(this)
    return seen
}

private fun windowsSystemMessage(code: Int): String? {
    val buffer = Memory(SYSTEM_MESSAGE_CHARS * Char.SIZE_BYTES.toLong())
    val length = NativeLibrary.getInstance("kernel32")
        .getFunction("FormatMessageW", Function.ALT_CONVENTION)
        .invokeInt(arrayOf(FORMAT_MESSAGE_FLAGS, null, code, 0, buffer, SYSTEM_MESSAGE_CHARS, null))
    return if (length > 0) buffer.getWideString(0).trim().trimEnd('.') else null
}

/** ERROR_SYSTEM_INTEGRITY_POLICY_VIOLATION, then the reputation verdicts Smart App Control reports. */
private val POLICY_ERRORS = listOf(4551, 4556, 4557, 4558, 4559, 4580, 4581, 4582)

/** Still catches the common case if the system text can't be read. */
private const val ENGLISH_POLICY_MESSAGE = "Application Control policy"

private const val ASCII_MAX = 127

private const val FORMAT_MESSAGE_IGNORE_INSERTS = 0x200
private const val FORMAT_MESSAGE_FROM_SYSTEM = 0x1000
private const val FORMAT_MESSAGE_FLAGS = FORMAT_MESSAGE_FROM_SYSTEM or FORMAT_MESSAGE_IGNORE_INSERTS
private const val SYSTEM_MESSAGE_CHARS = 512
