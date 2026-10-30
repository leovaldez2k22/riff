package com.musicglass.personal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Time for the settings' little moving pictures, a frame at a time while shown; still under reduced motion. */
@Composable private fun rememberLoopClock(): () -> Long {
    val motion = LocalMotionPolicy.current
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion.reduced) { if (!motion.reduced) while (true) withFrameMillis { now = it } }
    return { now }
}

/** Sound: the blend gets the big tile, the booster grows a dial, and the rest are switches (owner, 30 Oct). */
@Composable internal fun SoundSection(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val audio = remember(context) { AudioBoostSettings(context) }
    val crossfade = remember(context) { CrossfadeSettings.get(context) }
    var boostLevel by remember(audio) { mutableFloatStateOf(audio.percent.toFloat()) }
    var length by remember(crossfade) { mutableFloatStateOf(crossfade.seconds.toFloat()) }
    val equalizerOn = remember(context) { EqualizerSettings.read(EqualizerSettings.prefs(context)).enabled }
    SettingsPage(model, bottom, string(R.string.settings_sound)) {
        bentoRows(
            { BlendTile(crossfade, length, { length = it }) },
            {
                BentoBeside(
                    tall = { m -> BoostTile(audio, boostLevel, m) },
                    top = { m -> BentoLink(string(R.string.equalizer), Icons.Rounded.Equalizer, "settings_equalizer", m,
                        value = string(if (equalizerOn) R.string.on else R.string.off), stacked = true) { model.push(Route.Settings(SettingsSection.Equalizer)) } },
                    bottom = { m -> BentoToggle(string(R.string.normalize_volume), audio.normalize, audio::updateNormalize,
                        Icons.Rounded.Compress, "settings_normalize_volume", m) })
            },
            {
                AnimatedVisibility(audio.enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    BentoSlider(string(R.string.boost_level), "${boostLevel.toInt()}%", Icons.AutoMirrored.Rounded.VolumeUp) {
                        CapsuleSlider(boostLevel, { boostLevel = it }, valueRange = 100f..300f,
                            onValueChangeFinished = { audio.updatePercent(boostLevel.toInt()) },
                            haptics = SliderHaptics.Boost, label = string(R.string.boost_level), spokenValue = quantity(R.plurals.n_percent, boostLevel.toInt()),
                            modifier = Modifier.fillMaxWidth().testTag("settings_boost_level"))
                    }
                }
            },
            {
                BentoPair(
                    { m -> BentoToggle(string(R.string.skip_end_silence), audio.skipEndSilence, audio::updateSkipEndSilence,
                        Icons.Rounded.SkipNext, "settings_skip_end_silence", m) },
                    { m -> BentoToggle(string(R.string.skip_all_silence), audio.skipAllSilence, audio::updateSkipAllSilence,
                        Icons.Rounded.ContentCut, "settings_skip_all_silence", m) })
            })
    }
}

/**
 * Between songs: the crossfade switch, a drawing of the two songs' levels across the blend that changes with the
 * choice, the Crossfade / Smart / Automix control (the tab bar's pill), and the length, for the plain crossfade only.
 */
@Composable private fun BlendTile(crossfade: CrossfadeSettings, length: Float, onLength: (Float) -> Unit) {
    val motion = LocalMotionPolicy.current
    val modes = listOf(BlendMode.Crossfade, BlendMode.Smart, BlendMode.Automix)
    val clock = rememberLoopClock()
    // The drawing eases between the shapes as the choice changes.
    val curve by animateFloatAsState(if (crossfade.mode == BlendMode.Crossfade) 0f else 1f, GlassMotion.settle(motion), label = "blend curve")
    val beats by animateFloatAsState(if (crossfade.mode == BlendMode.Automix) 1f else 0f, GlassMotion.settle(motion), label = "blend beats")
    val span by animateFloatAsState(if (crossfade.mode == BlendMode.Crossfade) (length / 12f).coerceIn(.15f, 1f) else .6f,
        GlassMotion.settle(motion), label = "blend span")
    val on by animateFloatAsState(if (crossfade.enabled) 1f else .35f, GlassMotion.timed(GlassMotion.selectionMs, motion), label = "blend on")
    BentoTile(Modifier.fillMaxWidth(), lit = crossfade.enabled, tint = Lavender) {
        Row(Modifier.fillMaxWidth().toggleable(crossfade.enabled, role = Role.Switch, onValueChange = hapticToggle(crossfade::updateEnabled))
            .testTag("settings_crossfade"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
            SettingIcon(Icons.Rounded.ShuffleOn, crossfade.enabled, tint = Lavender)
            Column(Modifier.weight(1f)) {
                BentoTitle(string(R.string.blend_between_songs))
                BentoHelp(when {
                    !crossfade.enabled -> string(R.string.summary_no_crossfade)
                    crossfade.mode == BlendMode.Crossfade -> string(R.string.blend_length_every_song, length.roundToInt())
                    crossfade.mode == BlendMode.Smart -> string(R.string.blend_length_from_song)
                    else -> string(R.string.blend_length_on_the_beat)
                })
            }
            GlassSwitch(crossfade.enabled)
        }
        Canvas(Modifier.fillMaxWidth().height(RiffSettings.wave).padding(vertical = RiffSpace.item)) {
            val t = clock() / 1000f
            val breathe = if (curve > 0f && !motion.reduced) sin(t * 1.4f) * .08f * curve else 0f
            val width = size.width * (span + breathe).coerceIn(.12f, 1f)
            val start = (size.width - width) / 2f
            val stroke = Stroke(RiffSettings.waveStroke.toPx(), cap = StrokeCap.Round)
            fun level(x: Float, out: Boolean): Float {
                val p = ((x - start) / width).coerceIn(0f, 1f)
                val straight = if (out) 1f - p else p
                val bent = if (out) cos(p * PI.toFloat() / 2f) else sin(p * PI.toFloat() / 2f)
                return straight + (bent - straight) * curve
            }
            for (out in listOf(true, false)) {
                val path = Path()
                var x = 0f
                while (x <= size.width) {
                    val y = size.height - level(x, out) * size.height
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 4f
                }
                drawPath(path, (if (out) Pink else Lavender).copy(alpha = on), style = stroke)
            }
            // Automix: the beats the blend is lined up on.
            if (beats > .01f) for (i in 0..8) {
                val bx = start + width * i / 8f
                drawLine(White.copy(alpha = .5f * beats * on), Offset(bx, 0f), Offset(bx, RiffSettings.beatTick.toPx()), RiffSettings.waveStroke.toPx() / 2f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = RiffSpace.item), horizontalArrangement = Arrangement.SpaceBetween) {
            BentoHelp(string(R.string.blend_ending_song), Pink)
            BentoHelp(string(R.string.blend_next_song), Lavender)
        }
        GlassSegmented(listOf(string(R.string.blend_plain), string(R.string.smart_crossfade), string(R.string.blend_automix_short)),
            modes.indexOf(crossfade.mode).coerceAtLeast(0), { crossfade.updateMode(modes[it]); if (!crossfade.enabled) crossfade.updateEnabled(true) },
            "settings_blend", tags = modes.map { "settings_blend_${it.wire}" })
        Text(string(when (crossfade.mode) { BlendMode.Crossfade -> R.string.blend_plain_detail; BlendMode.Smart -> R.string.smart_crossfade_detail
            else -> R.string.blend_automix_detail }), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.item))
        // The length is the plain crossfade's: Smart and Automix take theirs from each song (owner, 30 Oct).
        AnimatedVisibility(crossfade.mode == BlendMode.Crossfade, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = RiffSpace.item)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(string(R.string.crossfade_length), color = White, fontSize = RiffType.body, modifier = Modifier.weight(1f))
                    Text("${length.roundToInt()} s", color = Pink, fontSize = RiffType.body, fontWeight = FontWeight.SemiBold)
                }
                CapsuleSlider(length, onLength, valueRange = 1f..12f, steps = 10,
                    onValueChangeFinished = { crossfade.updateSeconds(length.roundToInt()) },
                    haptics = SliderHaptics.Seconds, label = string(R.string.crossfade_length), spokenValue = quantity(R.plurals.n_seconds, length.roundToInt()),
                    modifier = Modifier.fillMaxWidth().testTag("settings_crossfade_length"))
            }
        }
    }
}

/** The booster: a switch, and a dial that fills to the level when it is on. */
@Composable private fun BoostTile(audio: AudioBoostSettings, level: Float, modifier: Modifier) {
    val motion = LocalMotionPolicy.current
    val fill by animateFloatAsState(if (audio.enabled) ((level - 100f) / 200f).coerceIn(.04f, 1f) else 0f, GlassMotion.settle(motion), label = "boost dial")
    BentoTile(modifier.toggleable(audio.enabled, role = Role.Switch, onValueChange = hapticToggle(audio::updateEnabled)).testTag("settings_volume_boost"),
        lit = audio.enabled) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(Icons.AutoMirrored.Rounded.VolumeUp, audio.enabled)
            Spacer(Modifier.weight(1f))
            GlassSwitch(audio.enabled)
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = RiffSpace.item), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(RiffSettings.dial)) {
                val stroke = RiffSettings.dialStroke.toPx()
                val box = Size(size.width - stroke, size.height - stroke)
                val corner = Offset(stroke / 2f, stroke / 2f)
                drawArc(White.copy(alpha = .1f), 135f, 270f, false, corner, box, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fill > .001f) drawArc(Brush.sweepGradient(listOf(Lavender, Pink, Lavender)), 135f, 270f * fill, false, corner, box,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(if (audio.enabled) "${level.toInt()}%" else string(R.string.off), color = White, fontSize = RiffSettings.title, fontWeight = FontWeight.SemiBold)
        }
        BentoTitle(string(R.string.volume_booster))
    }
}

/** Look and feel: a piece of real glass at the top that follows the sliders, then the switches. */
/** The phone has turned the glass down for heat or power saver: said here, so the glass switches do not look broken. */
@Composable private fun GlassCutTile(text: String) {
    BentoTile(Modifier.fillMaxWidth(), lit = true, tint = Coral, tag = "settings_glass_cut") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
            SettingIcon(Icons.Rounded.Thermostat, on = true, tint = Coral)
            Column(Modifier.weight(1f)) { BentoHelp(text) }
        }
    }
}

@Composable internal fun LookSection(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) {
    val preferences = LocalAccessibilitySettings.current
    SettingsPage(model, bottom, string(R.string.settings_look_and_feel)) {
        bentoRows(
            {
                val note = glassCutNote(android.os.Build.VERSION.SDK_INT, preferences.solidControls, LocalGlassStyle.current.quality)
                Column(verticalArrangement = Arrangement.spacedBy(RiffSettings.tileGap)) {
                    GlassSampleTile()
                    if (note != null) GlassCutTile(string(note))
                }
            },
            {
                BentoSlider(string(R.string.glass_intensity), "${(preferences.glassIntensity * 100).toInt()}%", Icons.Rounded.Opacity) {
                    CapsuleSlider(preferences.glassIntensity, preferences::updateGlassIntensity, valueRange = 0f..1.5f,
                        haptics = SliderHaptics.Intensity, label = string(R.string.glass_intensity),
                        spokenValue = quantity(R.plurals.n_percent, (preferences.glassIntensity * 100).toInt()),
                        modifier = Modifier.fillMaxWidth().testTag("settings_glass_intensity"))
                }
            },
            {
                BentoSlider(string(R.string.frosted_glass_intensity), "${(preferences.frostedIntensity * 100).toInt()}%", Icons.Rounded.BlurOn) {
                    CapsuleSlider(preferences.frostedIntensity, preferences::updateFrostedIntensity, valueRange = 0f..1.5f,
                        haptics = SliderHaptics.Intensity, label = string(R.string.frosted_glass_intensity),
                        spokenValue = quantity(R.plurals.n_percent, (preferences.frostedIntensity * 100).toInt()),
                        modifier = Modifier.fillMaxWidth().testTag("settings_frosted_intensity"))
                }
            },
            {
                BentoPair(
                    { m -> BentoToggle(string(R.string.frosted_glass), preferences.frostedGlass, preferences::updateFrostedGlass,
                        Icons.Rounded.BlurOn, "settings_frosted_glass", m) },
                    { m -> BentoToggle(string(R.string.calm_transitions), preferences.motionPace == MotionPace.Calm,
                        { calm -> preferences.updateMotionPace(if (calm) MotionPace.Calm else MotionPace.Quick) },
                        Icons.Rounded.SlowMotionVideo, "settings_calm_transitions", m) })
            },
            {
                BentoPair(
                    { m -> BentoToggle(string(R.string.reduce_motion), preferences.reduceMotion, preferences::updateReduceMotion,
                        Icons.Rounded.MotionPhotosOff, "settings_reduce_motion", m) },
                    { m -> BentoToggle(string(R.string.high_contrast_controls), preferences.solidControls, preferences::updateSolidControls,
                        Icons.Rounded.Contrast, "settings_solid_controls", m) })
            },
            {
                BentoPair(
                    { m -> BentoToggle(string(R.string.haptic_feedback), preferences.hapticFeedback, preferences::updateHapticFeedback,
                        Icons.Rounded.Vibration, "settings_haptics", m) },
                    { m -> BentoToggle(string(R.string.show_stats_for_nerds), preferences.playerStats, preferences::updatePlayerStats,
                        Icons.Rounded.Analytics, "settings_player_stats", m) })
            })
    }
}

/** Stripes under a pill of the app's own glass, so a slider's change shows at once. */
@Composable private fun GlassSampleTile() {
    val stripes = rememberRiffBackdrop()
    val clock = rememberLoopClock()
    BentoTile(Modifier.fillMaxWidth().height(RiffSettings.glassSample)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(RiffRadius.card))) {
            Canvas(Modifier.fillMaxSize().riffBackdropSource(stripes)) {
                // Slowly drifting stripes, so the glass's bend shows as they pass under it.
                val band = RiffSettings.stripe.toPx()
                val shift = (clock() / 40f) % (band * 2f)
                var x = -band * 2f + shift
                var i = 0
                while (x < size.width + band) {
                    drawRect(if (i % 2 == 0) Pink.copy(alpha = .55f) else Lavender.copy(alpha = .45f), Offset(x, 0f), Size(band, size.height))
                    x += band; i++
                }
            }
            Box(Modifier.align(Alignment.Center).fillMaxWidth(.62f).height(RiffSettings.glassPill)
                .riffGlass(GlassRole.Bar, RiffRadius.pill, backdrop = stripes))
            Text(string(R.string.settings_preview), color = White, fontSize = RiffType.detail, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(RiffSpace.item).background(Color.Black.copy(alpha = .25f), RoundedCornerShape(RiffRadius.pill))
                    .padding(horizontal = RiffSpace.item, vertical = RiffSpace.tight / 2))
        }
    }
}

/** Lyrics: a preview that sings a line as the player's lyric line does, then the switches. */
@Composable internal fun LyricsSection(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) {
    val preferences = LocalAccessibilitySettings.current
    SettingsPage(model, bottom, string(R.string.settings_lyrics)) {
        bentoRows(
            { LyricsPreviewTile(preferences.lyricsBlur) },
            {
                BentoPair(
                    { m -> BentoToggle(string(R.string.blur_other_lyric_lines), preferences.lyricsBlur, preferences::updateLyricsBlur,
                        Icons.Rounded.BlurOn, "settings_lyrics_blur", m) },
                    { m -> BentoToggle(string(R.string.lyric_line_in_player), preferences.playerLyricLine, preferences::updatePlayerLyricLine,
                        Icons.Rounded.Subtitles, "settings_player_lyric_line", m) })
            })
    }
}

/** How long the preview sings its line, and then rests on the instrumental dots, before it starts again. */
private const val PREVIEW_SING_MS = 2_600L
private const val PREVIEW_REST_MS = 1_500L

@Composable private fun LyricsPreviewTile(blur: Boolean) {
    val clock = rememberLoopClock()
    val line = string(R.string.lyrics_preview_line)
    // Each word sung in turn across the line's time.
    val parts = remember(line) {
        val words = Regex("\\S+").findAll(line).toList()
        words.mapIndexed { i, w -> LyricPart(PREVIEW_SING_MS * i / words.size, PREVIEW_SING_MS * (i + 1) / words.size, w.range.first, w.range.last + 1) }
    }
    val other = if (blur) White.copy(alpha = .22f) else White.copy(alpha = .4f)
    BentoTile(Modifier.fillMaxWidth(), tint = Mint, lit = true) {
        BentoHelp(string(R.string.settings_preview), Mint)
        Column(Modifier.fillMaxWidth().padding(vertical = RiffSpace.item), verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
            Text(string(R.string.lyrics_preview_before), color = other, fontSize = RiffSettings.lyricOther, fontWeight = FontWeight.SemiBold)
            SweptLyricLine(line, parts, RiffSettings.lyricLine) { clock() % (PREVIEW_SING_MS + PREVIEW_REST_MS) }
            Text(string(R.string.lyrics_preview_after), color = other, fontSize = RiffSettings.lyricOther, fontWeight = FontWeight.SemiBold)
        }
        // The three dots of an instrumental break, filling while the line rests.
        Canvas(Modifier.height(RiffSettings.lyricDot).fillMaxWidth()) {
            val at = clock() % (PREVIEW_SING_MS + PREVIEW_REST_MS) - PREVIEW_SING_MS
            val progress = (at.toFloat() / PREVIEW_REST_MS).coerceIn(0f, 1f)
            val radius = size.height / 2f
            repeat(LYRIC_GAP_DOTS) { dot ->
                drawCircle(White.copy(alpha = .25f + .67f * lyricGapDotFill(progress, dot)), radius, Offset(radius + dot * radius * 4, radius))
            }
        }
    }
}
