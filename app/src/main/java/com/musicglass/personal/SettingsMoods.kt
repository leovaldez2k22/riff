package com.musicglass.personal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/** A mood's colour and icon: Riff's four have their own, kept ones share lavender. */
private fun moodLook(mood: Mood): Pair<Color, ImageVector> = when (mood.id) {
    "party" -> Pink to Icons.Rounded.Celebration
    "focus" -> Sky to Icons.Rounded.Headphones
    "commute" -> Mint to Icons.Rounded.DirectionsBus
    "night" -> Lavender to Icons.Rounded.Bedtime
    else -> Lavender to Icons.Rounded.AutoAwesome
}

internal fun moodName(mood: Mood): String = mood.builtIn?.let { string(it) } ?: mood.name

/** What a mood sets, in a line: "Automix · Boost 150% · Even loudness". */
internal fun moodSummary(values: MoodValues): String = listOfNotNull(
    if (!values.blend) string(R.string.summary_no_crossfade) else when (values.mode) {
        BlendMode.Crossfade -> string(R.string.summary_crossfade_x, values.seconds)
        BlendMode.Smart -> string(R.string.smart_crossfade)
        else -> string(R.string.blend_automix_short)
    },
    values.boost?.let { string(R.string.summary_boost_x, it) } ?: string(R.string.summary_no_boost),
    string(R.string.mood_even_loudness).takeIf { values.normalize },
    values.dataSaver?.let { string(if (it) R.string.summary_data_saver_on else R.string.summary_data_saver_off) }).joinToString(" · ")

/** Moods, a section of its own (owner, 30 Oct): Riff's four, the ones kept, and keeping what is set now. */
@Composable internal fun MoodsSection(model: ShellModel, bottom: Dp) {
    val context = LocalContext.current
    val crossfade = remember(context) { CrossfadeSettings.get(context) }
    val audio = remember(context) { AudioBoostSettings(context) }
    val dataSaver = remember(context) { DataSaverSettings(context) }
    val store = remember(context) { MoodStore.get(context) }
    val now = currentMoodValues(crossfade, audio, dataSaver)
    val moods = builtInMoods + store.kept
    val on = activeMood(moods, now, store.chosen)
    SettingsPage(model, bottom, string(R.string.settings_moods)) {
        item { BentoHelp(string(R.string.moods_detail)) }
        val rows = moods.chunked(2).map { pair -> @Composable {
            BentoPair(
                { m -> MoodTile(pair[0], pair[0] == on, m, onRemove = { store.remove(pair[0].id) }) {
                    applyMood(pair[0].values, crossfade, audio, dataSaver); store.choose(pair[0].id); uiHaptic(HapticCue.Confirm) } },
                { m -> if (pair.size > 1) MoodTile(pair[1], pair[1] == on, m, onRemove = { store.remove(pair[1].id) }) {
                    applyMood(pair[1].values, crossfade, audio, dataSaver); store.choose(pair[1].id); uiHaptic(HapticCue.Confirm) } else Spacer(m) })
        } }
        bentoRows(*rows.toTypedArray(), {
            BentoLink(string(R.string.mood_keep), Icons.Rounded.AddCircleOutline, "settings_mood_keep", Modifier.fillMaxWidth(),
                value = if (on == null) moodSummary(now) else string(R.string.mood_already_x, moodName(on)), tint = Lavender) {
                if (on == null) store.choose(store.keep(now, string(R.string.mood_yours_x, store.kept.size + 1)).id)
            }
        })
    }
}

/** A mood as a tile in its own colour; ticked while its settings are all in place. A kept one can be removed. */
@Composable private fun MoodTile(mood: Mood, on: Boolean, modifier: Modifier, onRemove: () -> Unit, onPick: () -> Unit) {
    val (colour, icon) = moodLook(mood)
    val motion = LocalMotionPolicy.current
    BentoTile(modifier, lit = true, tint = colour, onClick = onPick, tag = "settings_mood_${mood.id}") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon, on || true, tint = colour)
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(on, enter = scaleIn(GlassMotion.pop(motion)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.CheckCircle, string(R.string.mood_is_on), tint = White)
            }
            if (mood.builtIn == null) IconButton(onRemove, Modifier.size(RiffSettings.badge)) {
                Icon(Icons.Rounded.Close, string(R.string.mood_remove_x, moodName(mood)), tint = Muted)
            }
        }
        Spacer(Modifier.weight(1f).height(RiffSettings.tileGap))
        Text(moodName(mood), color = White, fontSize = RiffSettings.title, fontWeight = FontWeight.SemiBold)
        Box { BentoHelp(moodSummary(mood.values), White.copy(alpha = .7f)) }
    }
}
