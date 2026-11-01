package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.YtAccountInfo
import io.ymusic.dynamicengine.yt.YtVerifyTarget
import io.ymusic.dynamicengine.yt.ytAccountInfo
import io.ymusic.dynamicengine.yt.ytLoginStatus
import io.ymusic.dynamicengine.yt.ytLoginUrl
import io.ymusic.dynamicengine.yt.ytLogout
import io.ymusic.dynamicengine.yt.ytRefreshSession
import io.ymusic.dynamicengine.yt.ytSwitchAccountUrl
import io.ymusic.dynamicengine.yt.ytVerifyTarget
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.captcha_verify
import io.ymusic.ui.resources.captcha_verify_hint
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_close
import io.ymusic.ui.resources.dialog_ok_done
import io.ymusic.ui.resources.signin_button
import io.ymusic.ui.resources.signin_enables
import io.ymusic.ui.resources.signin_feature_history
import io.ymusic.ui.resources.signin_feature_home
import io.ymusic.ui.resources.signin_feature_like
import io.ymusic.ui.resources.signin_feature_more
import io.ymusic.ui.resources.signin_feature_playlists
import io.ymusic.ui.resources.signin_feature_subscribe
import io.ymusic.ui.resources.signin_feature_subscriptions
import io.ymusic.ui.resources.signin_preface_subtitle
import io.ymusic.ui.resources.signin_preface_title
import io.ymusic.ui.resources.signin_screen_title
import io.ymusic.ui.resources.signin_unavailable
import io.ymusic.ui.resources.switch_account_hint
import kmpcommon.log.Logger
import kmpcommon.log.debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import projectcommon.error.Playability
import projectcommon.error.errorCode
import projectcommon.error.runSuspendCatching

/**
 * The observable state of the login browser — the shell's [LoginBrowser] writes it, the login chrome
 * reads it. A plain snapshot-state holder (no engine coupling), so a shell WebView only needs to
 * report page title / current URL / load progress and expose a back action.
 */
class LoginWebViewState {
    /** The current page's `<title>`; null before the first page paints. */
    var title: String? by mutableStateOf(null)

    /** The current URL, shown read-only in the chrome (not editable — this is a sign-in flow). */
    var currentUrl: String? by mutableStateOf(null)

    /** Load progress 0f‥1f (1 = finished). The chrome shows the bar only mid-load. */
    var progress: Float by mutableStateOf(0f)

    /** Whether the WebView has back history — drives the chrome/back-gesture behaviour. */
    var canGoBack: Boolean by mutableStateOf(false)

    /** Set by the shell WebView so the chrome/back gesture can walk the page's own history. */
    var back: (() -> Unit)? = null

    /** Set by shells that can open a DevTools window; null hides the chrome button.
     *  Snapshot state so the chrome shows the button once the shell wires it. */
    var openDevTools: (() -> Unit)? by mutableStateOf(null)

    fun goBack() {
        back?.invoke()
    }
}

/**
 * A platform browser WebView, injected by the platform shell (like [AppGraph.playerSurface]) so the
 * pure shared UI never depends on a WebView stack. It loads [url] presenting [userAgent] (null = the
 * platform default), reports title / URL / progress into [state], and calls [onPageChanged] after
 * each navigation. The shell copies sign-in cookies into the persistent jar the engine reads so the
 * login gate sees them.
 */
typealias LoginBrowser = @Composable (
    url: String,
    userAgent: String?,
    state: LoginWebViewState,
    onPageChanged: () -> Unit,
    modifier: Modifier,
) -> Unit

/** Desktop cookie sign-in (QR / paste), injected by the shell like [LoginBrowser]. */
typealias CookieSignIn = @Composable (
    onComplete: () -> Unit,
    onBack: () -> Unit,
) -> Unit

private const val LOGIN_POLL_MS = 1_000L

/**
 * Signed-in state for the app, derived entirely from the engine's cookie gate. Holds no cookies
 * itself: the auth cookies live in the shared platform jar (the engine reads them), and this only
 * caches the display card plus a [loginEpoch] the feeds watch to reload on a sign-in/out.
 */
internal class AccountController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<YtAccountInfo?>(null)
    val account: StateFlow<YtAccountInfo?> = _account.asStateFlow()

    /** False until the first login-status + account probe (or post-login fetch) finishes. */
    private val _profileReady = MutableStateFlow(false)
    val profileReady: StateFlow<Boolean> = _profileReady.asStateFlow()

    /** Bumped on every sign-in/out so the feed caches clear and reload (mirrors [AppGraph.localeEpoch]). */
    val loginEpoch = MutableStateFlow(0)

    private val log = graph.logger?.fork("login")

    init {
        // The auth cookies persist in the platform jar across launches, so probe on start: an already
        // signed-in user comes back signed in without touching the login screen.
        scope.launch {
            // An engine failure here is NOT "signed out" — log it, or a signed-in user silently
            // landing on the login screen looks like a cookie bug.
            val ok = runSuspendCatching { graph.engines.ytLoginStatus() }
                .onFailure { log?.log(Logger.Level.WARN, "startup login-status probe failed", it) }
                .getOrDefault(false)
            _signedIn.value = ok
            if (ok) {
                val acct = runSuspendCatching { graph.engines.ytAccountInfo() }
                    .onFailure { log?.log(Logger.Level.WARN, "account info fetch failed", it) }
                    .getOrNull()
                if (acct != null && !acct.signedIn) {
                    // The engine found no account behind the cookies (a revoked jar — remote
                    // logout). Run the full sign-out so the state, session and feeds all reset
                    // instead of showing a phantom profile. A fetch FAILURE is not this: acct
                    // stays null and the cookie gate's answer stands.
                    signOut()
                } else {
                    _account.value = acct
                }
            }
            _profileReady.value = true
        }
    }

    /** After the login screen's cookie gate passed: pull the account card, reload every feed, and
     *  stale the player's page so the next track re-fetches /embed authenticated. [then] runs last,
     *  under the refreshed session. */
    fun onLoginComplete(then: (() -> Unit)? = null) {
        scope.launch {
            _profileReady.value = false
            _signedIn.value = true
            // Re-scrape the session under the new cookies so authenticated calls carry the account's
            // SESSION_INDEX before the account fetch and feed reload below. A failure means every
            // later authenticated call runs under the wrong identity — log it loudly.
            runSuspendCatching { graph.engines.ytRefreshSession() }
                .onFailure { log?.log(Logger.Level.WARN, "post-login session refresh failed", it) }
            // Force a fresh fetch (and re-cache) on sign-in; the startup probe below uses the cache.
            _account.value = runSuspendCatching { graph.engines.ytAccountInfo(refresh = true) }
                .onFailure { log?.log(Logger.Level.WARN, "post-login account fetch failed", it) }
                .getOrNull()
            loginEpoch.value += 1
            _profileReady.value = true
            graph.player.refreshOnNext()
            val playerError = graph.player.status.value.error
            if (playerError?.playability() == Playability.SignInRequired || playerError?.code == CAPTCHA_CODE) {
                graph.player.play()
            }
            graph.requestRefresh()
            then?.invoke()
        }
    }

    /** After the captcha browser closes: re-scrape the session under the cookies the cleared
     *  challenge left, then [retry] what failed (a still-blocked scrape fails it again, visibly). */
    fun onCaptchaVerified(retry: () -> Unit) {
        scope.launch {
            runSuspendCatching { graph.engines.ytRefreshSession() }
                .onFailure { log?.log(Logger.Level.WARN, "post-verify session refresh failed", it) }
            retry()
        }
    }

    fun signOut() {
        log?.log(Logger.Level.INFO, "DBG signOut clicked scopeActive=${scope.coroutineContext[kotlinx.coroutines.Job]?.isActive}")
        scope.launch {
            log?.log(Logger.Level.INFO, "DBG signOut launched")
            // The UI flips to signed-out either way (the user asked to leave), but a failed engine
            // logout leaves the jar authenticated — that mismatch must reach telemetry.
            runSuspendCatching { graph.engines.ytLogout() }
                .onFailure { log?.captureError(it, "logout failed — jar may stay authenticated") }
            log?.log(Logger.Level.INFO, "DBG ytLogout returned")
            // Re-scrape anonymous so the account identifiers drop out of subsequent requests.
            runSuspendCatching { graph.engines.ytRefreshSession() }
                .onFailure { log?.log(Logger.Level.WARN, "post-logout session refresh failed", it) }
            log?.log(Logger.Level.INFO, "DBG refresh returned")
            _signedIn.value = false
            _account.value = null
            loginEpoch.value += 1
            graph.player.refreshOnNext()
            graph.requestRefresh()
        }
    }
}

/**
 * The full-screen sign-in browser: a browser-like chrome (back · page title · read-only URL ·
 * progress) over the shell's WebView, polling the engine's COOKIE-ONLY gate. The moment the essential
 * cookies land it calls [onComplete] — never a network call mid-flow, which YouTube answers by
 * revoking a half-set jar.
 *
 * [switchAccount] mode opens YouTube's channel switcher instead, with NO polling — switching swaps
 * identities within an already-valid jar, so there is no gate to flip. A dismissable hint tells the
 * user to close the screen when done; the CLOSE is the completion signal (App refreshes the session
 * and account when this layer pops).
 */
@Composable
internal fun YtLoginScreen(
    graph: AppGraph,
    state: LoginWebViewState,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    switchAccount: Boolean = false,
) {
    var entryUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.loginBrowser, switchAccount) {
        if (graph.loginBrowser == null && !switchAccount) return@LaunchedEffect
        entryUrl = runSuspendCatching {
            if (switchAccount) graph.engines.ytSwitchAccountUrl() else graph.engines.ytLoginUrl()
        }.onFailure {
            graph.logger?.fork("login")?.captureError(it, "login entry url failed — blank login screen")
        }
            .getOrNull()
    }

    val browser = graph.loginBrowser
    val cookieSignIn = graph.cookieSignIn
    if (!switchAccount && cookieSignIn != null) {
        cookieSignIn(onComplete, onBack)
        return
    }
    // Poll the cookie gate while the user signs in. Cookie-only, so polling costs nothing and never
    // disturbs the half-set jar. Stops itself the instant it flips (onComplete pops this screen).
    // The account switcher doesn't poll — the jar is signed in throughout; closing IS completion.
    // A navigation also wakes it: the hop onto youtube.com is where the auth cookies land.
    val pageChanged = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(browser, switchAccount) {
        if (browser == null || switchAccount) return@LaunchedEffect
        var done = false
        while (isActive && !done) {
            withTimeoutOrNull(LOGIN_POLL_MS) { pageChanged.receive() }
            if (runSuspendCatching { graph.engines.ytLoginStatus() }
                    .onFailure { graph.logger?.fork("login")?.debug { "login poll failed: $it" } }
                    .getOrDefault(false)
            ) {
                done = true
                onComplete()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DesignColors.Surface).padding(bottom = LocalMiniPlayerInset.current)) {
        LoginChrome(state, onBack = onBack)
        var hintDismissed by remember { mutableStateOf(false) }
        if (switchAccount && !hintDismissed) {
            BrowserHint(stringResource(Res.string.switch_account_hint), onDismiss = { hintDismissed = true })
        }
        val url = entryUrl
        when {
            browser == null -> CenteredBox {
                Text(stringResource(Res.string.signin_unavailable), color = DesignColors.InkMuted)
            }
            url == null -> CenteredBox { CircularProgressIndicator(color = DesignColors.Accent) }
            else -> browser(url, null, state, { pageChanged.trySend(Unit) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/**
 * The captcha browser: the login chrome over the shell's WebView on the engine's verify target,
 * presenting the scrape's own User-Agent so the cleared challenge belongs to the identity the engine
 * scrapes with (cookies land in the same jar). Nothing tells us the challenge was solved, so Done,
 * the back arrow and closing all mean the same: [onDone] — App refreshes the session and retries.
 */
@Composable
internal fun YtVerifyScreen(graph: AppGraph, state: LoginWebViewState, onDone: () -> Unit) {
    var target by remember { mutableStateOf<YtVerifyTarget?>(null) }
    var failedCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph) {
        runSuspendCatching { graph.engines.ytVerifyTarget() }
            .onSuccess { target = it }
            .onFailure {
                graph.logger?.fork("verify")?.captureError(it, "verify target failed")
                failedCode = it.errorCode() ?: "E20147"
            }
    }
    Column(Modifier.fillMaxSize().background(DesignColors.Surface).padding(bottom = LocalMiniPlayerInset.current)) {
        // A double tap must not pop the layer underneath too.
        var finished by remember { mutableStateOf(false) }
        val finish = {
            if (!finished) {
                finished = true
                onDone()
            }
        }
        LoginChrome(
            state,
            onBack = finish,
            fallbackTitle = stringResource(Res.string.captcha_verify),
            onDone = finish,
        )
        val browser = graph.loginBrowser
        if (browser != null) BrowserHint(stringResource(Res.string.captcha_verify_hint))
        val verify = target
        val failed = failedCode
        when {
            browser == null -> CenteredBox {
                Text(stringResource(Res.string.signin_unavailable), color = DesignColors.InkMuted)
            }
            failed != null -> CenteredBox { Text(errorMessage(failed), color = MaterialTheme.colorScheme.error) }
            verify == null -> CenteredBox { CircularProgressIndicator(color = DesignColors.Accent) }
            else -> browser(verify.url, verify.userAgent, state, {}, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** An instruction strip under the browser chrome; [onDismiss] null = not dismissable. */
@Composable
private fun BrowserHint(text: String, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = DesignSpacing.MarginCompact, end = DesignSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer),
            modifier = Modifier.weight(1f).padding(vertical = DesignSpacing.Space8),
        )
        if (onDismiss != null) {
            ActionIcon(
                Icons.Default.Close,
                stringResource(Res.string.cd_close),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun LoginChrome(
    state: LoginWebViewState,
    onBack: () -> Unit,
    fallbackTitle: String = stringResource(Res.string.signin_screen_title),
    onDone: (() -> Unit)? = null,
) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(
                state.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.currentUrl?.takeIf { it.isNotBlank() }?.let { u ->
                Text(
                    u,
                    style = TextStyle(fontSize = 11.5.sp, color = DesignColors.InkFaint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.openDevTools?.let { open ->
            ActionIcon(Icons.Default.Code, "DevTools", onClick = open)
        }
        if (onDone != null) {
            TextButton(onClick = onDone) {
                Text(
                    stringResource(Res.string.dialog_ok_done),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Accent),
                )
            }
        }
    }
    // The load bar shows only mid-navigation; a finished (1f) or idle (0f) page hides it.
    if (state.progress > 0.001f && state.progress < 0.999f) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = DesignColors.Accent,
            trackColor = Color.Transparent,
        )
    }
}

/** The signed-out Discover "Account" tab — the "Sign in with YouTube" preface (design mirrors the
 *  legacy LoginIntroduceFragment). */
@Composable
internal fun LoginPreface(onSignIn: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = gridMetricsFor(maxWidth).margin
        val centerVertically = maxWidth >= 840.dp && maxHeight >= 600.dp
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalMiniPlayerInset.current),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DesignSpacing.FormMaxWidth)
                    .fillMaxWidth()
                    .then(if (centerVertically) Modifier.heightIn(min = viewportHeight) else Modifier)
                    .padding(horizontal = margin, vertical = DesignSpacing.Space16),
                verticalArrangement = if (centerVertically) {
                    Arrangement.Center
                } else {
                    Arrangement.Top
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(56.dp).background(Color(0xFFFF0000), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Column {
                        Text(
                            stringResource(Res.string.signin_preface_title),
                            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                        )
                        Text(
                            stringResource(Res.string.signin_preface_subtitle),
                            style = TextStyle(fontSize = 14.sp, color = DesignColors.InkMuted),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = DesignColors.Divider)
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(Res.string.signin_enables),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
                )
                Spacer(Modifier.height(12.dp))
                for (feature in LOGIN_FEATURES) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(DesignColors.Accent, CircleShape))
                        Text(stringResource(feature), style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink))
                    }
                }
                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(DesignColors.Accent, RoundedCornerShape(8.dp))
                        .clickable(onClick = onSignIn)
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Res.string.signin_button).uppercase(),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.OnAccent),
                    )
                }
            }
        }
    }
}

private val LOGIN_FEATURES = listOf(
    Res.string.signin_feature_home,
    Res.string.signin_feature_subscriptions,
    Res.string.signin_feature_playlists,
    Res.string.signin_feature_history,
    Res.string.signin_feature_subscribe,
    Res.string.signin_feature_like,
    Res.string.signin_feature_more,
)

// The signed-in Account tab is AccountLibraryScreen (AccountLibrary.kt) — the account card plus
// the engine's library feed.
