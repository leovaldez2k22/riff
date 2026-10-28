package com.musicglass.personal

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Feature 10: Riff's own equalizer, under Settings and in the player's menu. The switch, then Dynamic (a tone pad:
 * left warmer, right brighter, up brings the middle forward) or Manual (seven faders and presets), the summed curve,
 * balance, and a way to the phone's own equalizer.
 */
@Composable internal fun EqualizerScreen(model: ShellModel, bottom: Dp) {
    val context = LocalContext.current
    val prefs = remember(context) { EqualizerSettings.prefs(context) }
    var config by remember(prefs) { mutableStateOf(EqualizerSettings.read(prefs)) }
    fun change(next: EqualizerConfig) { if (next != config) { config = next; EqualizerSettings.write(prefs, next) } }
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.back), model::back); ScreenTitle(string(R.string.equalizer)) }
        item {
            EqCard {
                SettingsToggleRow(string(R.string.equalizer), config.enabled, hapticToggle { change(config.copy(enabled = it)) },
                    Icons.Rounded.Equalizer, "equalizer_switch", string(R.string.eq_caption))
            }
        }
        item { EqTabs(config.mode) { uiHaptic(HapticCue.Selection); change(config.copy(mode = it)) } }
        item {
            if (config.mode == EqMode.Dynamic) EqCard(string(R.string.eq_tone)) {
                TonePad(config.toneX, config.toneY) { x, y -> change(config.copy(toneX = x, toneY = y)) }
                Row(Modifier.fillMaxWidth().padding(vertical = RiffSpace.item), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EqReadout("≋", config.toneX, string(R.string.eq_tilt)); EqReadout("↗", config.toneY, string(R.string.eq_contour))
                }
                SettingsDivider()
                EqChoiceRow(string(R.string.eq_broad), string(R.string.eq_broad_body), !config.focused, "equalizer_broad") { change(config.copy(focused = false)) }
                SettingsDivider()
                EqChoiceRow(string(R.string.eq_focused), string(R.string.eq_focused_body), config.focused, "equalizer_focused") { change(config.copy(focused = true)) }
            } else EqCard(string(R.string.eq_bands)) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = RiffSpace.item), horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                    (EQ_PRESETS.keys + if (config.preset == "Custom") listOf("Custom") else emptyList()).forEach { name ->
                        FilterChip(config.preset == name, {
                            uiHaptic(HapticCue.Selection)
                            EQ_PRESETS[name]?.let { change(config.copy(manual = it, preset = name)) }
                        }, { Text(eqPresetName(name)) }, modifier = Modifier.testTag("equalizer_preset_$name"))
                    }
                }
                ManualFaders(config.manual) { manual -> change(config.copy(manual = manual, preset = presetFor(manual))) }
            }
        }
        item { if (config.mode == EqMode.Dynamic) Text(string(R.string.eq_tone_help), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(horizontal = RiffSpace.item)) }
        item { EqCard(string(R.string.eq_curve)) { EqCurvePreview(config) } }
        item {
            EqCard {
                var balance by remember(config.balance) { mutableFloatStateOf(config.balance) }
                Row(Modifier.fillMaxWidth().padding(top = RiffSpace.item), verticalAlignment = Alignment.CenterVertically) {
                    Text(string(R.string.eq_balance), color = White, fontSize = RiffType.rowTitle, modifier = Modifier.weight(1f))
                    Text(balanceLabel(balance), color = Muted, fontSize = RiffType.detail)
                }
                CapsuleSlider(balance, { balance = if (kotlin.math.abs(it) < .06f) 0f else it }, valueRange = -1f..1f,
                    onValueChangeFinished = { change(config.copy(balance = balance)) }, haptics = SliderHaptics.Intensity,
                    label = string(R.string.eq_balance), spokenValue = balanceLabel(balance), modifier = Modifier.fillMaxWidth().testTag("equalizer_balance"))
            }
        }
        item { EqCard { PhoneEqualizerRow() } }
    }
}

@Composable private fun EqCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = RiffSpace.tight)) {
        if (title != null) Text(title.uppercase(), color = Muted, fontSize = RiffType.detail, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = RiffSpace.item, bottom = RiffSpace.item))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(RiffRadius.hero), color = White.copy(alpha = .045f),
            border = BorderStroke(RiffEqualizer.edge, White.copy(alpha = .08f))) {
            Column(Modifier.padding(horizontal = RiffSpace.block, vertical = RiffSpace.tight), content = content)
        }
    }
}

/** Dynamic | Manual: the chosen side is a white pill with dark text. */
@Composable private fun EqTabs(mode: EqMode, onChange: (EqMode) -> Unit) {
    val shape = RoundedCornerShape(RiffRadius.pill)
    Row(Modifier.fillMaxWidth().clip(shape).background(White.copy(alpha = .08f)).padding(RiffSpace.tight)) {
        EqMode.entries.forEach { tab ->
            val chosen = tab == mode
            Box(Modifier.weight(1f).heightIn(min = RiffEqualizer.tab).clip(shape).background(if (chosen) White else Color.Transparent)
                .clickable(role = Role.Tab, onClick = { if (!chosen) onChange(tab) }).testTag("equalizer_tab_${tab.key}"),
                contentAlignment = Alignment.Center) {
                Text(string(if (tab == EqMode.Dynamic) R.string.eq_dynamic else R.string.eq_manual),
                    color = if (chosen) Color.Black else White, fontSize = RiffType.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The tone pad: 11 × 11 dots on one canvas, a puck that springs between them, a tick at each new step. The gesture
 * is taken on first touch, so a drag inside the pad never scrolls the page.
 */
@Composable private fun TonePad(x: Int, y: Int, onMove: (Int, Int) -> Unit) {
    val motion = LocalMotionPolicy.current
    val puckX by animateFloatAsState(x.toFloat(), GlassMotion.settle(motion), label = "tone x")
    val puckY by animateFloatAsState(y.toFloat(), GlassMotion.settle(motion), label = "tone y")
    val latest by rememberUpdatedState(onMove)
    val current by rememberUpdatedState(x to y)
    val description = string(R.string.eq_tone_position, eqStepLabel(x), eqStepLabel(y))
    val steps = EqLayout.TONE_STEPS; val count = steps * 2 + 1
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = RiffSpace.item).testTag("equalizer_tone_pad")
        .semantics { contentDescription = description }
        .pointerInput(Unit) {
            fun stepAt(position: Offset): Pair<Int, Int> {
                val cell = size.width / count.toFloat()
                return (floor(position.x / cell).toInt() - steps).coerceIn(-steps, steps) to
                    (steps - floor(position.y / cell).toInt()).coerceIn(-steps, steps)
            }
            fun move(position: Offset) {
                val step = stepAt(position)
                if (step != current) { uiHaptic(HapticCue.TickLight); latest(step.first, step.second) }
            }
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume(); move(down.position)
                while (true) {
                    val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume(); move(change.position)
                }
            }
        }) {
        val cell = size.width / count
        val dot = RiffEqualizer.dot.toPx(); val puck = RiffEqualizer.puck.toPx()
        for (i in 0 until count) for (j in 0 until count) {
            val onLine = i - steps == x || steps - j == y
            drawCircle(White.copy(alpha = if (onLine) .55f else .25f), dot, Offset((i + .5f) * cell, (j + .5f) * cell))
        }
        val centre = Offset((puckX + steps + .5f) * cell, (steps - puckY + .5f) * cell)
        drawCircle(Pink.copy(alpha = .25f), puck * 1.6f, centre)
        drawCircle(White, puck, centre)
    }
}

@Composable private fun EqReadout(symbol: String, value: Int, label: String) {
    Text("$symbol ${eqStepLabel(value)}", color = White, fontSize = RiffEqualizer.readout, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { contentDescription = "$label ${eqStepLabel(value)}" })
}

@Composable private fun EqChoiceRow(title: String, body: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).testTag(tag).clickable(role = Role.RadioButton, onClick = hapticClick(onClick))
        .padding(vertical = RiffSpace.item), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = RiffType.rowTitle)
            Text(body, color = Muted, fontSize = RiffType.detail)
        }
        if (chosen) Icon(Icons.Rounded.Check, null, tint = Pink)
    }
}

/**
 * Seven vertical faders on one canvas. The fill runs from the centre line, as the value is signed; a finger picks
 * the fader it lands nearest to and keeps it for the whole drag.
 */
@Composable private fun ManualFaders(gains: List<Float>, onChange: (List<Float>) -> Unit) {
    val latestGains by rememberUpdatedState(gains); val latest by rememberUpdatedState(onChange)
    val bands = EqLayout.manualFrequencies.size; val range = EqLayout.MANUAL_RANGE_DB
    Row(Modifier.fillMaxWidth()) {
        gains.forEach { Text(eqLabel(it), color = White, fontSize = RiffEqualizer.label, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
    }
    Canvas(Modifier.fillMaxWidth().height(RiffEqualizer.faders).padding(vertical = RiffSpace.item).testTag("equalizer_faders")
        .semantics { stateDescription = gains.joinToString(" ") { eqLabel(it) } }
        .pointerInput(Unit) {
            fun valueAt(yPx: Float) = manualStep((size.height / 2f - yPx) / (size.height / 2f) * range)
            fun set(band: Int, yPx: Float) {
                val value = valueAt(yPx)
                if (value != latestGains[band]) {
                    uiHaptic(if (value == 0f) HapticCue.Threshold else HapticCue.TickLight)
                    latest(latestGains.toMutableList().also { it[band] = value })
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                val band = (down.position.x / (size.width / bands.toFloat())).toInt().coerceIn(0, bands - 1)
                set(band, down.position.y)
                while (true) {
                    val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume(); set(band, change.position.y)
                }
            }
        }) {
        val column = size.width / bands; val track = RiffEqualizer.track.toPx(); val knob = RiffEqualizer.knob.toPx()
        val mid = size.height / 2
        drawLine(White.copy(alpha = .12f), Offset(0f, mid), Offset(size.width, mid), RiffEqualizer.edge.toPx())
        gains.forEachIndexed { i, gain ->
            val cx = (i + .5f) * column; val y = mid - gain / range * (mid - knob / 2)
            drawLine(White.copy(alpha = .14f), Offset(cx, knob / 2), Offset(cx, size.height - knob / 2), track, StrokeCap.Round)
            drawLine(Pink, Offset(cx, mid), Offset(cx, y), track, StrokeCap.Round)
            drawCircle(White, knob / 2, Offset(cx, y))
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = RiffSpace.item)) {
        EqLayout.manualLabels.forEach { Text(it, color = Muted, fontSize = RiffEqualizer.label, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
    }
}

/** The summed response, ±12 dB, gliding to a new shape when a tab or setting changes. */
@Composable private fun EqCurvePreview(config: EqualizerConfig) {
    val motion = LocalMotionPolicy.current
    val target = remember(config) { eqCurve(eqSections(config)) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        from = FloatArray(target.size) { from[it] + (to[it] - from[it]) * progress.value }; to = target
        progress.snapTo(0f); progress.animateTo(1f, GlassMotion.timed(GlassMotion.selectionMs, motion))
    }
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(RiffEqualizer.curve).padding(vertical = RiffSpace.item).testTag("equalizer_curve")) {
        val mid = size.height / 2; val range = EqLayout.MANUAL_RANGE_DB
        drawLine(White.copy(alpha = .12f), Offset(0f, mid), Offset(size.width, mid), RiffEqualizer.edge.toPx())
        path.reset(); val t = progress.value
        for (i in to.indices) {
            val db = (from[i] + (to[i] - from[i]) * t).coerceIn(-range, range)
            val point = Offset(i / (to.size - 1f) * size.width, mid - db / range * mid)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, Pink, style = Stroke(RiffEqualizer.track.toPx(), cap = StrokeCap.Round))
    }
}

/** The phone's own equalizer, when it has one. Riff's and the phone's curves add together. */
@Composable private fun PhoneEqualizerRow() {
    val context = LocalContext.current
    val intent = remember(context) {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    val available = remember(intent) { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    if (available) SettingsLinkRow(string(R.string.eq_phone), icon = Icons.Rounded.Tune, tag = "equalizer_phone") { launcher.launch(intent) }
    else Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).padding(vertical = RiffSpace.item), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Muted.copy(alpha = .5f))
        Text(string(R.string.eq_phone_none), color = Muted, fontSize = RiffType.body)
    }
    Text(string(R.string.eq_phone_note), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(bottom = RiffSpace.item))
}

/** A tone step as the readouts write it: signed, 0 without a sign. */
internal fun eqStepLabel(step: Int): String = when { step > 0 -> "+$step"; step < 0 -> "−${-step}"; else -> "0" }

private fun balanceLabel(balance: Float): String {
    val percent = (kotlin.math.abs(balance) * 100).roundToInt()
    return when { percent == 0 -> string(R.string.eq_balance_centre); balance < 0 -> string(R.string.eq_balance_left, percent); else -> string(R.string.eq_balance_right, percent) }
}

private fun eqPresetName(name: String): String = string(when (name) {
    "Flat" -> R.string.eq_preset_flat; "Bass boost" -> R.string.eq_preset_bass_boost; "Bass reduce" -> R.string.eq_preset_bass_reduce
    "Treble boost" -> R.string.eq_preset_treble_boost; "Vocal" -> R.string.eq_preset_vocal; "Rock" -> R.string.eq_preset_rock
    "Pop" -> R.string.eq_preset_pop; "Electronic" -> R.string.eq_preset_electronic; "Hip-hop" -> R.string.eq_preset_hip_hop
    "Jazz" -> R.string.eq_preset_jazz; "Classical" -> R.string.eq_preset_classical; "Acoustic" -> R.string.eq_preset_acoustic
    "Loudness" -> R.string.eq_preset_loudness; "Small speakers" -> R.string.eq_preset_small_speakers
    else -> R.string.eq_preset_custom
})
