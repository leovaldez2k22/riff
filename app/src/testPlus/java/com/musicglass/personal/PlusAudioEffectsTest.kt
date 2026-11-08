package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlusAudioEffectsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun mainAndTailKeepTheOriginalOrderAndFollowSettingsUntilRelease() {
        val prefs = EqualizerSettings.prefs(context)
        val first = EqualizerConfig(enabled = true, toneX = 4, balance = .3f)
        EqualizerSettings.write(prefs, first)
        val effects = PlusAudioEffects(context)
        effects.start()
        // Sing first, so the equalizer shapes the instrumental too (feature 20).
        assertTrue(effects.beforeBoost[0] is SingProcessor)
        val main = effects.beforeBoost[1] as EqualizerProcessor
        val tail = effects.createTail()
        assertTrue(tail.processors[0] is SingProcessor)
        val outgoing = tail.processors[1] as EqualizerProcessor
        assertTrue(tail.processors[2] is EchoProcessor)
        assertEquals(first, main.equalizer.config)
        assertEquals(first, outgoing.equalizer.config)
        val next = first.copy(toneX = -3, focused = true)
        EqualizerSettings.write(prefs, next)
        assertEquals(next, main.equalizer.config)
        assertEquals(next, outgoing.equalizer.config)
        effects.shelves(Shelves(-24f, -4f), Shelves(-5f, -20f))
        assertEquals(-24f, outgoing.equalizer.bassCutDb)
        assertEquals(-4f, outgoing.equalizer.aboveBassCutDb)
        assertEquals(-5f, main.equalizer.bassCutDb)
        val cue = EchoOut(1000, 250, 500)
        effects.echo(cue)
        assertEquals(cue, (tail.processors[2] as EchoProcessor).echo.cue)
        tail.release(); effects.release()
        EqualizerSettings.write(prefs, first)
        assertEquals(next, main.equalizer.config)
    }

    @Test fun releasingAnEarlierTailDoesNotDetachItsReplacement() {
        val effects = PlusAudioEffects(context)
        effects.start()
        val old = effects.createTail()
        val current = effects.createTail()
        old.release()
        effects.shelves(Shelves(-18f), Shelves())
        assertEquals(-18f, (current.processors[1] as EqualizerProcessor).equalizer.bassCutDb)
        assertEquals(0f, (old.processors[1] as EqualizerProcessor).equalizer.bassCutDb)
        current.release(); effects.release()
    }
}
