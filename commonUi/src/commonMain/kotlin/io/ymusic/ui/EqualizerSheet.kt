package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.settings.AppSettings
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.a11y_eq_preamp
import io.ymusic.ui.resources.settings_system_audio_fx
import io.ymusic.ui.resources.settings_system_audio_fx_hint
import io.ymusic.ui.resources.sound_bass
import io.ymusic.ui.resources.sound_db
import io.ymusic.ui.resources.sound_eq
import io.ymusic.ui.resources.sound_eq_band
import io.ymusic.ui.resources.sound_eq_hint
import io.ymusic.ui.resources.sound_ok
import io.ymusic.ui.resources.sound_preset_bass
import io.ymusic.ui.resources.sound_preset_classical
import io.ymusic.ui.resources.sound_preset_custom
import io.ymusic.ui.resources.sound_preset_electronic
import io.ymusic.ui.resources.sound_preset_flat
import io.ymusic.ui.resources.sound_preset_jazz
import io.ymusic.ui.resources.sound_preset_pop
import io.ymusic.ui.resources.sound_preset_rock
import io.ymusic.ui.resources.sound_preset_treble
import io.ymusic.ui.resources.sound_preset_voice
import io.ymusic.ui.resources.sound_reset
import io.ymusic.ui.resources.sound_virtualizer_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val EQ_HZ = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
private const val EQ_MAX_DB = 12f
private const val BASS_MAX_DB = 12f
private const val VIRTUALIZER_MAX = 2f

private val FaderBankCorner = 14.dp
private val FaderHeight = 180.dp
private val FaderTrackWidth = 4.dp
private val FaderCapWidth = 22.dp
private val FaderCapHeight = 14.dp
private val MasterColumnWidth = 40.dp
private val MasterTrackWidth = 6.dp
private val MasterCapWidth = 28.dp
private val MasterCapHeight = 16.dp
private const val PRE_INDEX = -1
private val KnobSize = 104.dp
private const val KNOB_SWEEP_DEGREES = 270f
private const val KNOB_HUB_FRACTION = 0.15f
private val KnobStroke = 7.dp
private val MenuCurveWidth = 26.dp
private val MenuCurveHeight = 12.dp
private const val DISABLED_ALPHA = 0.38f

private data class EqPreset(val label: StringResource, val bands: List<Float>)

private val EQ_PRESETS = listOf(
    EqPreset(Res.string.sound_preset_flat, List(10) { 0f }),
    EqPreset(Res.string.sound_preset_bass, listOf(6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset(Res.string.sound_preset_treble, listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f)),
    EqPreset(Res.string.sound_preset_voice, listOf(-4f, -3f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    EqPreset(Res.string.sound_preset_jazz, listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
    EqPreset(Res.string.sound_preset_rock, listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    EqPreset(Res.string.sound_preset_pop, listOf(-1f, 2f, 4f, 4f, 1f, -1f, -2f, -2f, 1f, 2f)),
    EqPreset(Res.string.sound_preset_classical, listOf(5f, 4f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqPreset(Res.string.sound_preset_electronic, listOf(4f, 3f, 1f, 0f, -2f, 2f, 1f, 2f, 3f, 4f)),
)

/**
 * Equalizer: a mixing-desk bank of ten faders, a preset menu in the title bar, and bass / virtualizer knobs.
 * On shells that support it, a "Use system sound effects" switch heads it; the controls are read-only and dimmed
 * while [io.ymusic.player.PlaybackConfig.inAppAudioFx] is off.
 */
@Composable
internal fun EqualizerFxSheet(graph: AppGraph, onDismiss: () -> Unit) {
    val player = graph.player
    val config by player.config.collectAsState()
    val enabled = config.inAppAudioFx
    val bands = List(EQ_HZ.size) { config.eqBands.getOrElse(it) { 0f } }
    val scroll = rememberScrollState()
    val controls = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)
    ElevatedBarDialog(
        title = stringResource(Res.string.sound_eq),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        titleAction = if (enabled) {
            { PresetMenu(bands, onSelect = { player.setEqBands(it) }) }
        } else {
            null
        },
        buttons = {
            if (enabled) {
                DialogSecondaryButton(stringResource(Res.string.sound_reset)) {
                    player.setEqBands(List(EQ_HZ.size) { 0f })
                    player.setBassBoost(0f)
                    player.setVirtualizer(1f)
                    player.setPreamp(0f)
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.sound_ok), color = DesignColors.Accent)
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            if (graph.systemAudioEffects) {
                ToggleRow(
                    graph,
                    AppSettings.systemAudioEffects,
                    Res.string.settings_system_audio_fx,
                    Res.string.settings_system_audio_fx_hint,
                )
            }
            Column(Modifier.padding(horizontal = DesignSpacing.Space16)) {
                FaderBank(
                    preamp = config.preampDb,
                    bands = bands,
                    enabled = enabled,
                    onPreampChange = { player.setPreamp(it) },
                    onBandChange = { index, db -> player.setEqBands(bands.toMutableList().also { it[index] = db }) },
                    modifier = controls,
                )
                if (enabled) {
                    Text(
                        stringResource(Res.string.sound_eq_hint),
                        style = TextStyle(fontSize = 11.sp, color = DesignColors.InkSecondary),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = DesignSpacing.Space8),
                    )
                }
            }
            Row(
                controls.fillMaxWidth().padding(horizontal = DesignSpacing.Space24),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RotaryKnob(
                    enabled = enabled,
                    title = stringResource(Res.string.sound_bass),
                    fraction = (config.bassBoostDb + BASS_MAX_DB) / (2 * BASS_MAX_DB),
                    valueText = stringResource(Res.string.sound_db, dbLabel(config.bassBoostDb)),
                    keyStep = 1f / (2 * BASS_MAX_DB),
                    onChange = { player.setBassBoost((it * 2 * BASS_MAX_DB - BASS_MAX_DB).roundToInt().toFloat()) },
                )
                RotaryKnob(
                    enabled = enabled,
                    title = stringResource(Res.string.sound_virtualizer_title),
                    fraction = config.virtualizer / VIRTUALIZER_MAX,
                    valueText = "${trimFloat(config.virtualizer)}×",
                    keyStep = 0.1f / VIRTUALIZER_MAX,
                    onChange = { player.setVirtualizer((it * VIRTUALIZER_MAX * 10).roundToInt() / 10f) },
                )
            }
        }
    }
}

/** The current preset's name ("Custom" when the bands match none); opens the preset list, each with its curve. */
@Composable
private fun PresetMenu(bands: List<Float>, onSelect: (List<Float>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val match = EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .background(DesignColors.SettingsCard)
                .border(1.dp, DesignColors.OutlineSoft, shape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = DesignSpacing.Space8 + DesignSpacing.Space2, vertical = DesignSpacing.Space4 + DesignSpacing.Space2),
            horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space4 + DesignSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(match?.label ?: Res.string.sound_preset_custom),
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            val chevron = DesignColors.InkSecondary
            Canvas(Modifier.size(10.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.35f)
                    lineTo(size.width * 0.5f, size.height * 0.65f)
                    lineTo(size.width * 0.8f, size.height * 0.35f)
                }
                drawPath(path, chevron, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EQ_PRESETS.forEach { preset ->
                val selected = preset == match
                DropdownMenuItem(
                    leadingIcon = { MiniCurve(preset.bands, if (selected) DesignColors.Accent else DesignColors.InkSecondary) },
                    text = {
                        Text(
                            stringResource(preset.label),
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) DesignColors.Accent else DesignColors.Ink,
                            ),
                        )
                    },
                    onClick = {
                        onSelect(preset.bands)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MiniCurve(bands: List<Float>, color: Color) {
    Canvas(Modifier.size(MenuCurveWidth, MenuCurveHeight)) {
        val points = bands.indices.map { i ->
            Offset(size.width * i / (bands.size - 1), size.height / 2 - bands[i] / EQ_MAX_DB * size.height / 2)
        }
        drawPath(smoothPath(points), color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A mixing-desk bank over a ±12 dB scale: the PRE master fader (the level going into the equalizer) on the
 * left, then the ten band faders, each filled from the 0 dB line to its value. Press anywhere in a fader's
 * column and drag vertically to set it (whole-dB steps); the held fader's cap glows and its labels light up.
 */
@Composable
private fun FaderBank(
    preamp: Float,
    bands: List<Float>,
    enabled: Boolean,
    onPreampChange: (db: Float) -> Unit,
    onBandChange: (index: Int, db: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = DesignColors.Accent
    val master = DesignColors.Ink
    val track = if (DesignColors.isDark) Color(0xFF1A1A1A) else Color(0xFFD4D4D4)
    val rule = DesignColors.OutlineSoft
    val idleMark = DesignColors.InkFaint
    val masterCapBorder = DesignColors.InkFaint
    val changeBand by rememberUpdatedState(onBandChange)
    val changePreamp by rememberUpdatedState(onPreampChange)
    val isEnabled by rememberUpdatedState(enabled)
    // The held fader: PRE_INDEX for the master, else a band index.
    var active by remember { mutableStateOf<Int?>(null) }
    var focusedFader by remember { mutableStateOf<Int?>(null) }
    val lit = active ?: focusedFader
    val density = LocalDensity.current
    val inset = with(density) { MasterCapHeight.toPx() / 2 + 2.dp.toPx() }
    val masterWidth = with(density) { MasterColumnWidth.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FaderBankCorner))
            .background(Brush.verticalGradient(listOf(DesignColors.SettingsCard, DesignColors.Surface)))
            .border(1.dp, DesignColors.OutlineSoft, RoundedCornerShape(FaderBankCorner))
            .padding(horizontal = DesignSpacing.Space4, vertical = DesignSpacing.Space16),
    ) {
        FaderLabels(
            master = dbLabel(preamp),
            masterColor = if (preamp == 0f && lit != PRE_INDEX) DesignColors.InkSecondary else master,
            bands = bands.mapIndexed { i, db -> dbLabel(db) to (if (db == 0f && i != lit) DesignColors.InkSecondary else accent) },
            bold = true,
            modifier = Modifier.a11ySilent(),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().height(FaderHeight).padding(top = DesignSpacing.Space8)) {
            val height = constraints.maxHeight.toFloat()
            fun yOf(db: Float) = inset + (EQ_MAX_DB - db) / (2 * EQ_MAX_DB) * (height - 2 * inset)
            fun dbAt(y: Float) = ((1f - (y - inset) / (height - 2 * inset)) * 2 * EQ_MAX_DB - EQ_MAX_DB)
                .coerceIn(-EQ_MAX_DB, EQ_MAX_DB).roundToInt().toFloat()
            Canvas(
                Modifier.fillMaxSize().pointerInput(bands.size, height) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!isEnabled) return@awaitEachGesture
                        val bandWidth = (size.width - masterWidth) / bands.size
                        val index = if (down.position.x < masterWidth) {
                            PRE_INDEX
                        } else {
                            ((down.position.x - masterWidth) / bandWidth).toInt().coerceIn(0, bands.lastIndex)
                        }
                        fun set(y: Float) = if (index == PRE_INDEX) changePreamp(dbAt(y)) else changeBand(index, dbAt(y))
                        active = index
                        down.consume()
                        set(down.position.y)
                        drag(down.id) { move ->
                            set(move.position.y)
                            move.consume()
                        }
                        active = null
                    }
                },
            ) {
                val bandWidth = (size.width - masterWidth) / bands.size
                val zero = yOf(0f)
                drawLine(rule, Offset(masterWidth, zero), Offset(size.width, zero), strokeWidth = 1.5.dp.toPx())
                listOf(EQ_MAX_DB, -EQ_MAX_DB).forEach { db ->
                    drawLine(rule.copy(alpha = 0.5f), Offset(masterWidth, yOf(db)), Offset(size.width, yOf(db)), strokeWidth = 1.dp.toPx())
                }
                drawLine(rule, Offset(masterWidth, 0f), Offset(masterWidth, size.height), strokeWidth = 1.dp.toPx())
                drawFader(
                    x = masterWidth / 2,
                    db = preamp,
                    yOf = ::yOf,
                    track = track,
                    fill = master.copy(alpha = 0.55f),
                    trackWidth = MasterTrackWidth.toPx(),
                    capSize = Size(MasterCapWidth.toPx(), MasterCapHeight.toPx()),
                    capBrush = { top, bottom -> Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF1F1F1F)), startY = top, endY = bottom) },
                    capBorder = masterCapBorder,
                    mark = master,
                    glow = if (lit == PRE_INDEX) master.copy(alpha = 0.2f) else null,
                )
                bands.forEachIndexed { i, db ->
                    drawFader(
                        x = masterWidth + bandWidth * (i + 0.5f),
                        db = db,
                        yOf = ::yOf,
                        track = track,
                        fill = accent,
                        trackWidth = FaderTrackWidth.toPx(),
                        capSize = Size(FaderCapWidth.toPx(), FaderCapHeight.toPx()),
                        capBrush = { top, bottom -> Brush.verticalGradient(listOf(Color(0xFFEDEDED), Color(0xFFA9A9A9)), startY = top, endY = bottom) },
                        capBorder = null,
                        mark = if (db == 0f && i != lit) idleMark else accent,
                        glow = if (i == lit) accent.copy(alpha = 0.25f) else null,
                    )
                }
            }
            // Left to right like the canvas it overlays, in every locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize()) {
                    val focusChanged = { index: Int, focused: Boolean ->
                        if (focused) focusedFader = index else if (focusedFader == index) focusedFader = null
                    }
                    FaderTarget(
                        Modifier.width(MasterColumnWidth),
                        stringResource(Res.string.a11y_eq_preamp),
                        stringResource(Res.string.sound_db, dbLabel(preamp)),
                        preamp,
                        enabled,
                        onFocus = { focusChanged(PRE_INDEX, it) },
                        onSet = onPreampChange,
                    )
                    bands.forEachIndexed { i, db ->
                        FaderTarget(
                            Modifier.weight(1f),
                            null,
                            stringResource(Res.string.sound_eq_band, EQ_HZ[i], db.roundToInt()),
                            db,
                            enabled,
                            onFocus = { focusChanged(i, it) },
                            onSet = { onBandChange(i, it) },
                        )
                    }
                }
            }
        }
        FaderLabels(
            master = "PRE",
            masterColor = if (lit == PRE_INDEX) master else DesignColors.InkMuted,
            bands = EQ_HZ.mapIndexed { i, hz -> hz to (if (i == lit) accent else DesignColors.InkSecondary) },
            bold = false,
            boldIndex = lit,
            modifier = Modifier.padding(top = DesignSpacing.Space8).a11ySilent(),
        )
    }
}

/** A fader's column as screen readers and the keyboard reach it; drags stay with the bank's canvas
 *  underneath, which this node lets through. Up/Down step a whole dB. */
@Composable
private fun FaderTarget(
    modifier: Modifier,
    label: String?,
    value: String,
    db: Float,
    enabled: Boolean,
    onFocus: (Boolean) -> Unit,
    onSet: (db: Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusChanged by rememberUpdatedState(onFocus)
    LaunchedEffect(focused) { focusChanged(focused) }
    Box(
        modifier.fillMaxHeight().adjustable(
            label,
            value,
            (db + EQ_MAX_DB) / (2 * EQ_MAX_DB),
            1f / (2 * EQ_MAX_DB),
            enabled,
            interaction,
            vertical = true,
        ) { f -> onSet((f * 2 * EQ_MAX_DB - EQ_MAX_DB).roundToInt().toFloat()) },
    )
}

/** One label row aligned with the fader columns: [master] over the PRE column, then one per band. */
@Composable
private fun FaderLabels(
    master: String,
    masterColor: Color,
    bands: List<Pair<String, Color>>,
    bold: Boolean,
    boldIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth()) {
        Text(
            master,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = masterColor),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(MasterColumnWidth),
        )
        bands.forEachIndexed { i, (text, color) ->
            Text(
                text,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = if (bold || i == boldIndex) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun DrawScope.drawFader(
    x: Float,
    db: Float,
    yOf: (Float) -> Float,
    track: Color,
    fill: Color,
    trackWidth: Float,
    capSize: Size,
    capBrush: (top: Float, bottom: Float) -> Brush,
    capBorder: Color?,
    mark: Color,
    glow: Color?,
) {
    val radius = CornerRadius(trackWidth / 2)
    val top = yOf(EQ_MAX_DB)
    drawRoundRect(track, Offset(x - trackWidth / 2, top), Size(trackWidth, yOf(-EQ_MAX_DB) - top), radius)
    val y = yOf(db)
    val zero = yOf(0f)
    if (db != 0f) drawRoundRect(fill, Offset(x - trackWidth / 2, minOf(y, zero)), Size(trackWidth, abs(y - zero)), radius)
    val capTopLeft = Offset(x - capSize.width / 2, y - capSize.height / 2)
    val corner = CornerRadius(4.dp.toPx())
    if (glow != null) drawCircle(glow, capSize.width * 0.9f, Offset(x, y))
    drawRoundRect(Color.Black.copy(alpha = 0.45f), capTopLeft + Offset(0f, 2.dp.toPx()), capSize, corner)
    drawRoundRect(capBrush(capTopLeft.y, capTopLeft.y + capSize.height), capTopLeft, capSize, corner)
    if (capBorder != null) drawRoundRect(capBorder, capTopLeft, capSize, corner, style = Stroke(1.dp.toPx()))
    drawLine(
        mark,
        Offset(x - capSize.width / 2 + 4.dp.toPx(), y),
        Offset(x + capSize.width / 2 - 4.dp.toPx(), y),
        strokeWidth = 2.dp.toPx(),
    )
}

private fun dbLabel(db: Float): String {
    val value = db.roundToInt()
    return when {
        value > 0 -> "+$value"
        value < 0 -> "−${-value}"
        else -> "0"
    }
}

/**
 * A 270° rotary control: drag around the dial, clockwise to raise. The value moves by the angle swept, not
 * to the touch point, so grabbing anywhere never jumps it. Near the hub, where the angle is unstable, an
 * up/right drag raises instead. The value arc fills from the lower-left stop; the dot points at the value.
 */
@Composable
private fun RotaryKnob(
    enabled: Boolean,
    title: String,
    fraction: Float,
    valueText: String,
    keyStep: Float,
    onChange: (Float) -> Unit,
) {
    val accent = DesignColors.Accent
    val track = DesignColors.OutlineSoft
    val faceLight = DesignColors.SurfaceElevated
    val faceDark = DesignColors.SettingsCard
    val current by rememberUpdatedState(fraction.coerceIn(0f, 1f))
    val change by rememberUpdatedState(onChange)
    val isEnabled by rememberUpdatedState(enabled)
    var dragging by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(KnobSize).adjustable(title, valueText, current, keyStep, enabled, interaction, vertical = true) {
                change(it)
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!isEnabled) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    var value = current
                    val hub = Offset(size.width / 2f, size.height / 2f)
                    var lastAngle = angleDegrees(down.position - hub)
                    drag(down.id) { move ->
                        val fromHub = move.position - hub
                        val angle = angleDegrees(fromHub)
                        val step = if (fromHub.getDistance() < size.width * KNOB_HUB_FRACTION) {
                            val delta = move.positionChange()
                            (delta.x - delta.y) / (size.height * 1.5f)
                        } else {
                            ((angle - lastAngle + 540f) % 360f - 180f) / KNOB_SWEEP_DEGREES
                        }
                        lastAngle = angle
                        value = (value + step).coerceIn(0f, 1f)
                        change(value)
                        move.consume()
                    }
                    dragging = false
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = KnobStroke.toPx()
                val pad = stroke / 2 + 2.dp.toPx()
                val arcTopLeft = Offset(pad, pad)
                val arcSize = Size(size.width - 2 * pad, size.height - 2 * pad)
                drawArc(track, 135f, KNOB_SWEEP_DEGREES, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = KNOB_SWEEP_DEGREES * current
                if (sweep > 0f) {
                    drawArc(
                        accent.copy(alpha = if (dragging || focused) 0.35f else 0.2f),
                        135f,
                        sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(stroke * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(accent, 135f, sweep, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val faceRadius = size.minDimension / 2 - pad - stroke - 6.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(faceLight, faceDark), center = center, radius = faceRadius), faceRadius)
                drawCircle(track, faceRadius, style = Stroke(1.dp.toPx()))
                val angle = (135f + sweep) * PI.toFloat() / 180f
                val dotDistance = faceRadius - 9.dp.toPx()
                drawCircle(accent, 3.5.dp.toPx(), center + Offset(cos(angle) * dotDistance, sin(angle) * dotDistance))
            }
            Text(
                valueText,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                modifier = Modifier.a11ySilent(),
            )
        }
        Text(
            title,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.InkSecondary),
            modifier = Modifier.padding(top = DesignSpacing.Space4).a11ySilent(),
        )
    }
}

/** Screen-space angle of [v] in degrees; y points down, so clockwise is positive. */
private fun angleDegrees(v: Offset): Float = atan2(v.y, v.x) * 180f / PI.toFloat()

/** A Catmull-Rom spline through [points], as cubic Béziers. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (i in 0 until points.lastIndex) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
}

internal fun eqPresetLabel(eqBands: List<Float>): StringResource {
    val bands = List(EQ_HZ.size) { eqBands.getOrElse(it) { 0f } }
    return EQ_PRESETS.firstOrNull { bandsMatch(bands, it.bands) }?.label ?: Res.string.sound_preset_custom
}

private fun bandsMatch(a: List<Float>, b: List<Float>): Boolean =
    a.size == b.size && a.indices.all { abs(a[it] - b[it]) < 0.2f }
