package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/*
 * Moods (owner, 30 Oct): one tap sets several of the sound settings at once. Four come with Riff and the listener can
 * keep what is set now as another. A mood is on while everything it sets is still so, worked out each time rather
 * than remembered, so changing one setting by hand simply leaves it.
 */

/** What a mood sets. [boost] null is the booster off; [dataSaver] null leaves Data saver as it is. */
internal data class MoodValues(val blend: Boolean, val mode: BlendMode, val seconds: Int, val boost: Int?, val normalize: Boolean,
    val dataSaver: Boolean? = null)

/** A mood: Riff's own ([builtIn] names it from strings.xml) or one the listener kept ([name]). */
internal data class Mood(val id: String, val values: MoodValues, val builtIn: Int? = null, val name: String = "")

internal val builtInMoods = listOf(
    Mood("party", MoodValues(true, BlendMode.Automix, 6, 150, normalize = false), R.string.mood_party),
    Mood("focus", MoodValues(true, BlendMode.Smart, 6, null, normalize = true), R.string.mood_focus),
    Mood("commute", MoodValues(true, BlendMode.Crossfade, 3, 130, normalize = true, dataSaver = true), R.string.mood_commute),
    Mood("night", MoodValues(true, BlendMode.Smart, 6, null, normalize = true, dataSaver = false), R.string.mood_night))

/** Whether [now] is what [mood] sets: the length counts only for the plain crossfade, Data saver only when it sets it. */
internal fun moodMatches(mood: MoodValues, now: MoodValues): Boolean =
    mood.blend == now.blend && (!mood.blend || mood.mode == now.mode) &&
        (!mood.blend || mood.mode != BlendMode.Crossfade || mood.seconds == now.seconds) &&
        mood.boost == now.boost && mood.normalize == now.normalize && (mood.dataSaver == null || mood.dataSaver == now.dataSaver)

/** The first mood whose settings are all in place, or null. */
internal fun activeMood(moods: List<Mood>, now: MoodValues): Mood? = moods.firstOrNull { moodMatches(it.values, now) }

/** A kept mood as one line: its name and values, tab separated (a name has no tab or line break in it). */
internal fun encodeMood(mood: Mood): String = with(mood.values) {
    listOf(mood.id, mood.name.replace(Regex("[\t\n]"), " "), blend, mode.wire, seconds, boost ?: -1, normalize, dataSaver?.toString() ?: "-").joinToString("\t")
}

internal fun decodeMood(line: String): Mood? {
    val f = line.split('\t')
    if (f.size != 8) return null
    val mode = BlendMode.entries.firstOrNull { it.wire == f[3] } ?: return null
    return Mood(f[0], MoodValues(f[2].toBooleanStrictOrNull() ?: return null, mode, f[4].toIntOrNull() ?: return null,
        f[5].toIntOrNull()?.takeIf { it >= 0 }, f[6].toBooleanStrictOrNull() ?: return null, f[7].toBooleanStrictOrNull()), name = f[1])
}

/** The moods the listener kept, in the order kept, at most [MAX_KEPT_MOODS]. */
internal class MoodStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moods", Context.MODE_PRIVATE)
    var kept by mutableStateOf(read()); private set

    private fun read() = prefs.getString("kept", "").orEmpty().lines().mapNotNull(::decodeMood)
    private fun write(moods: List<Mood>) { kept = moods; prefs.edit { putString("kept", moods.joinToString("\n", transform = ::encodeMood)) } }

    /** Keeps [values] as a new mood named [name]; the oldest goes when there are too many. */
    fun keep(values: MoodValues, name: String): Mood {
        val mood = Mood("kept-${System.currentTimeMillis()}", values, name = name)
        write((kept + mood).takeLast(MAX_KEPT_MOODS))
        return mood
    }

    fun remove(id: String) = write(kept.filterNot { it.id == id })

    companion object {
        const val MAX_KEPT_MOODS = 8
        @Volatile private var instance: MoodStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: MoodStore(context).also { instance = it } }
    }
}

/** What is set now, as a mood would say it. */
internal fun currentMoodValues(crossfade: CrossfadeSettings, audio: AudioBoostSettings, dataSaver: DataSaverSettings) =
    MoodValues(crossfade.enabled, crossfade.mode, crossfade.seconds, audio.percent.takeIf { audio.enabled }, audio.normalize, dataSaver.enabled)

/** Puts [mood]'s settings in place. */
internal fun applyMood(mood: MoodValues, crossfade: CrossfadeSettings, audio: AudioBoostSettings, dataSaver: DataSaverSettings) {
    crossfade.updateEnabled(mood.blend)
    if (mood.blend) { crossfade.updateMode(mood.mode); if (mood.mode == BlendMode.Crossfade) crossfade.updateSeconds(mood.seconds) }
    audio.updateEnabled(mood.boost != null)
    mood.boost?.let(audio::updatePercent)
    audio.updateNormalize(mood.normalize)
    mood.dataSaver?.let(dataSaver::updateEnabled)
}
