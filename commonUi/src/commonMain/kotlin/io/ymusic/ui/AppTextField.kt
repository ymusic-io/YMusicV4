package io.ymusic.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's universal text input: no background box — just a floating label (sits like a placeholder
 * when empty, then floats up and turns [DesignColors.Accent] on focus) over a single bottom underline
 * (grey → accent on focus), with the label/text/underline flush to the field's edges (no inner
 * horizontal padding). [supportingText] shows under the field, in the error colour when [isError].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val colors = TextFieldDefaults.colors(
        // No fill — the field is just a floating label over the bottom underline.
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        cursorColor = DesignColors.Accent,
        errorCursorColor = MaterialTheme.colorScheme.error,
        focusedIndicatorColor = DesignColors.Accent,
        unfocusedIndicatorColor = DesignColors.OutlineSoft,
        errorIndicatorColor = MaterialTheme.colorScheme.error,
        focusedLabelColor = DesignColors.Accent,
        unfocusedLabelColor = DesignColors.InkSecondary,
        errorLabelColor = MaterialTheme.colorScheme.error,
        focusedTextColor = DesignColors.Ink,
        unfocusedTextColor = DesignColors.Ink,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester).tvTextFieldDpad(singleLine),
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 15.sp, color = DesignColors.Ink),
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else DesignColors.Accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        decorationBox = { inner ->
            TextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                isError = isError,
                label = { Text(label) },
                supportingText = supportingText?.let { { Text(it) } },
                suffix = suffix?.let { { Text(it, color = DesignColors.InkFaint) } },
                colors = colors,
                // Flush to the edges: no inner horizontal padding, just vertical breathing room.
                contentPadding = PaddingValues(vertical = 8.dp),
            )
        },
    )
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
