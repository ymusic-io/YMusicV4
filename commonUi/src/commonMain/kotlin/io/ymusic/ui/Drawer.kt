package io.ymusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.app_name
import io.ymusic.ui.resources.badge_premium
import io.ymusic.ui.resources.update_check
import io.ymusic.ui.resources.ymusic_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The premium badge's green chip (mockup values; dark counterparts keep the same relationships). */
private val BrandGreenInk: Color @Composable get() = if (DesignColors.isDark) Color(0xFF4ADE80) else Color(0xFF15803D)
private val BrandGreenBg: Color @Composable get() = if (DesignColors.isDark) Color(0xFF11301C) else Color(0xFFEAFAF0)
private val BrandGreenBorder: Color @Composable get() = if (DesignColors.isDark) Color(0xFF1D4A2C) else Color(0xFFCDEED9)

/** The navigation drawer (design/final/drawer.html): brand header + three nav sections. */
@Composable
fun AppDrawer(
    section: AppSection,
    appVersion: String?,
    onSelect: (AppSection) -> Unit,
    showCatalogue: Boolean = false,
    guest: Boolean = false,
    /** The updater: "Check for update" is highlighted while it has an update waiting. */
    updates: AppUpdates? = null,
    /** "Check for update", or null where the app cannot update itself (iOS). */
    onCheckUpdate: (() -> Unit)? = null,
    /** Shows the PREMIUM badge. */
    premium: Boolean = false,
    /** Lists Donate (only where a donation screen exists). */
    showDonate: Boolean = false,
    websiteUrl: String = AppLinks(null).website,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = DesignColors.Surface,
        drawerShape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            val updateStatus = updates?.status?.collectAsState()?.value
            val updateWaiting = updateStatus is AppUpdateStatus.Offered
            DrawerHeader(appVersion, premium, onCheckUpdate, updateWaiting, websiteUrl)
            HorizontalDivider(color = DesignColors.Divider)
            // When the drawer opens, land focus on the current section's item so keyboard users
            // can arrow-navigate immediately. remember(Unit) ties it to the drawerContent's
            // lifetime — ModalNavigationDrawer only composes drawerContent while visible, so this
            // LaunchedEffect fires each time the drawer re-opens.
            val activeItemFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                runCatching { activeItemFocus.requestFocus() }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).listKeyboardNavigation().padding(vertical = 8.dp)) {
                AppSection.entries
                    .filter { it != AppSection.Catalogue || showCatalogue }
                    .filter { it != AppSection.PlayAgain || !guest }
                    .filter { it != AppSection.Donate || showDonate }
                    .groupBy { it.group }
                    .entries
                    .forEachIndexed { index, (_, sections) ->
                        if (index > 0) {
                            HorizontalDivider(color = DesignColors.Divider, modifier = Modifier.padding(vertical = 4.dp))
                        }
                        sections.forEach { s ->
                            val active = s == section
                            DrawerItem(s, active = active, focusRequester = if (active) activeItemFocus else null) { onSelect(s) }
                        }
                    }
            }
        }
    }
}

@Composable
private fun DrawerHeader(appVersion: String?, premium: Boolean, onCheckUpdate: (() -> Unit)?, updateWaiting: Boolean, websiteUrl: String) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 13.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(Res.drawable.ymusic_logo),
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)),
            )
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.app_name),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = DesignColors.InkStrong),
                    )
                    if (premium) {
                        Text(
                            stringResource(Res.string.badge_premium),
                            style = TextStyle(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.54.sp,
                                color = BrandGreenInk,
                            ),
                            modifier = Modifier
                                .background(BrandGreenBg, RoundedCornerShape(5.dp))
                                .border(1.dp, BrandGreenBorder, RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                appVersion?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 11.sp, color = DesignColors.InkFaint),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        // Text links under the logo, as in YMusicLegacy's drawer header; accent and a trailing dot while an update is waiting.
        val uriHandler = LocalUriHandler.current
        Column(Modifier.padding(top = DesignSpacing.Space8)) {
            if (onCheckUpdate != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderLink(stringResource(Res.string.update_check), highlighted = updateWaiting, onClick = onCheckUpdate)
                    if (updateWaiting) {
                        Box(Modifier.size(DesignSpacing.Space8).background(DesignColors.Accent, CircleShape))
                    }
                }
            }
            HeaderLink("ymusic.io") { uriHandler.openUri(websiteUrl) }
        }
    }
}

@Composable
private fun HeaderLink(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) DesignColors.Accent else DesignColors.InkSecondary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(DesignSpacing.Space4))
            .clickable(onClick = onClick)
            .padding(DesignSpacing.Space4),
    )
}

@Composable
private fun DrawerItem(section: AppSection, active: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) =
    DrawerRow(section.icon, stringResource(section.label), active, focusRequester, onClick)

@Composable
private fun DrawerRow(icon: ImageVector, label: String, active: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val focusMod = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Box(Modifier.fillMaxWidth().then(focusMod).clickable(onClick = onClick).background(if (active) scheme.primaryContainer else Color.Transparent)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 8.dp)
                    .size(width = 3.dp, height = 33.dp)
                    .background(DesignColors.Accent, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(23.dp),
                tint = if (active) scheme.onPrimaryContainer else DesignColors.Ink,
            )
            val labelStyle = when {
                active -> TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onPrimaryContainer)
                else -> TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkStrong)
            }
            Text(label, style = labelStyle, modifier = Modifier.weight(1f))
        }
    }
}
