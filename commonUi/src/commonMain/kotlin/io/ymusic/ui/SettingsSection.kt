package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One Settings group: an accent header over a rounded card holding its rows. */
@Composable
internal fun SettingsSection(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title),
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Accent),
        modifier = Modifier.semantics { heading() }.padding(
            start = DesignSpacing.SettingsCardInset + DesignSpacing.SettingsRowHorizontal,
            end = DesignSpacing.SettingsCardInset + DesignSpacing.SettingsRowHorizontal,
            top = DesignSpacing.SettingsSectionGap,
            bottom = DesignSpacing.Space8,
        ),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignSpacing.SettingsCardInset)
            .clip(RoundedCornerShape(DesignSpacing.SettingsCardCorner))
            .background(DesignColors.SettingsCard),
        content = content,
    )
}
