package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.AppTextField
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.DialogSecondaryButton
import io.ymusic.ui.ElevatedBarDialog
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cookie_password
import io.ymusic.ui.resources.cookie_protect_body
import io.ymusic.ui.resources.cookie_protect_confirm
import io.ymusic.ui.resources.cookie_protect_mismatch
import io.ymusic.ui.resources.cookie_protect_skip
import io.ymusic.ui.resources.cookie_protect_submit
import io.ymusic.ui.resources.cookie_protect_title
import io.ymusic.ui.resources.cookie_unlock_body
import io.ymusic.ui.resources.cookie_unlock_cant_unlock
import io.ymusic.ui.resources.cookie_unlock_guest
import io.ymusic.ui.resources.cookie_unlock_guest_hint
import io.ymusic.ui.resources.cookie_unlock_sign_out
import io.ymusic.ui.resources.cookie_unlock_sign_out_body
import io.ymusic.ui.resources.cookie_unlock_sign_out_hint
import io.ymusic.ui.resources.cookie_unlock_sign_out_title
import io.ymusic.ui.resources.cookie_unlock_submit
import io.ymusic.ui.resources.cookie_unlock_title
import io.ymusic.ui.resources.cookie_unlock_wrong
import io.ymusic.ui.resources.device_link_error_generic
import io.ymusic.ui.resources.dialog_cancel
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** After sign-in: seal the jar under a password, or keep the default per-user key ("Never mind"). */
@Composable
internal fun CookieProtectStep(
    protect: suspend (CharArray) -> Unit,
    logger: Logger?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || password.isEmpty() || confirm.isEmpty()) return
        scope.launch {
            if (password != confirm) {
                error = getString(Res.string.cookie_protect_mismatch)
                return@launch
            }
            busy = true
            try {
                protect(password.toCharArray())
                onDone()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                logger?.captureError(failure, "protect cookie store failed")
                error = getString(Res.string.device_link_error_generic)
                busy = false
            }
        }
    }

    LockForm(
        title = stringResource(Res.string.cookie_protect_title),
        body = stringResource(Res.string.cookie_protect_body),
    ) {
        AppTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = stringResource(Res.string.cookie_password),
            autoFocus = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        AppTextField(
            value = confirm,
            onValueChange = { confirm = it; error = null },
            label = stringResource(Res.string.cookie_protect_confirm),
            isError = error != null,
            supportingText = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        BusyOr(busy) {
            AccentButton(
                text = stringResource(Res.string.cookie_protect_submit),
                enabled = password.isNotEmpty() && confirm.isNotEmpty(),
                onClick = ::submit,
            )
        }
        OutlinedActionButton(
            text = stringResource(Res.string.cookie_protect_skip),
            enabled = !busy,
            onClick = onDone,
        )
    }
}

/** Launch gate for a password-sealed jar: unlock it, run as a guest, or forget the sign-in. */
@Composable
internal fun CookieUnlockScreen(
    locked: CookieStoreOpen.Locked,
    logger: Logger?,
    onSession: (CookieSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    fun run(action: suspend () -> CookieSession?, onRefused: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val session = action()
                if (session != null) {
                    onSession(session)
                    return@launch
                }
                onRefused()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                logger?.captureError(failure, "cookie store unlock failed")
                error = getString(Res.string.device_link_error_generic)
            }
            busy = false
        }
    }

    fun unlock() {
        if (password.isEmpty()) return
        run({ locked.unlock(password.toCharArray()) }, onRefused = { error = getString(Res.string.cookie_unlock_wrong) })
    }

    LockForm(
        title = stringResource(Res.string.cookie_unlock_title),
        body = stringResource(Res.string.cookie_unlock_body),
    ) {
        AppTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = stringResource(Res.string.cookie_password),
            autoFocus = true,
            isError = error != null,
            supportingText = error,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { unlock() }),
        )
        BusyOr(busy) {
            AccentButton(
                text = stringResource(Res.string.cookie_unlock_submit),
                enabled = password.isNotEmpty(),
                onClick = ::unlock,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(Modifier.weight(1f), color = DesignColors.Divider)
            Text(
                stringResource(Res.string.cookie_unlock_cant_unlock),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkMuted),
                modifier = Modifier.padding(horizontal = DesignSpacing.Space16),
            )
            HorizontalDivider(Modifier.weight(1f), color = DesignColors.Divider)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(onClick = { run({ locked.asGuest() }) }, enabled = !busy) {
                    Text(stringResource(Res.string.cookie_unlock_guest), color = DesignColors.Ink)
                }
                Text(
                    stringResource(Res.string.cookie_unlock_guest_hint),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkMuted, lineHeight = 16.sp),
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(onClick = { confirmSignOut = true }, enabled = !busy) {
                    Text(stringResource(Res.string.cookie_unlock_sign_out), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    stringResource(Res.string.cookie_unlock_sign_out_hint),
                    style = TextStyle(fontSize = 12.sp, color = DesignColors.InkMuted, lineHeight = 16.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (confirmSignOut) {
        ElevatedBarDialog(
            title = stringResource(Res.string.cookie_unlock_sign_out_title),
            onDismiss = { confirmSignOut = false },
            canScrollBackward = false,
            canScrollForward = false,
            buttons = {
                DialogSecondaryButton(stringResource(Res.string.dialog_cancel)) { confirmSignOut = false }
                TextButton(onClick = {
                    confirmSignOut = false
                    run({ locked.signOut() })
                }) {
                    Text(stringResource(Res.string.cookie_unlock_sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
        ) {
            Text(
                stringResource(Res.string.cookie_unlock_sign_out_body),
                style = TextStyle(fontSize = 14.sp, color = DesignColors.Ink, lineHeight = 20.sp),
                modifier = Modifier.padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
            )
        }
    }
}

@Composable
private fun LockForm(title: String, body: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(DesignColors.Surface)) {
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DesignSpacing.FormMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = DesignSpacing.MarginCompact, vertical = DesignSpacing.Space16),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterVertically),
            ) {
                Text(
                    title,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    body,
                    style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted, lineHeight = 19.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                content()
            }
        }
    }
}

/** The primary button, or a spinner in its place while the (deliberately slow) key derivation runs. */
@Composable
private fun BusyOr(busy: Boolean, button: @Composable () -> Unit) {
    if (busy) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = DesignColors.Accent)
        }
    } else {
        button()
    }
}
