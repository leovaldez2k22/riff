package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** Feature 11's bundled list (owner, 8 November: AutoEq under MIT, import kept, nothing downloaded). Real AutoEq rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoEqLibraryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val all by lazy { AutoEqLibrary.load(context) }

    @Test fun theWholeSnapshotIsBundledWithEverySourceNamed() {
        assertEquals(8_827, all.size)
        assertEquals(49, all.map { it.source to it.rig }.distinct().size)
        assertTrue(all.all { it.source.isNotBlank() && it.rig.isNotBlank() && it.name.isNotBlank() })
        assertTrue(all.all { e -> e.fit().gains.let { g -> g.size == 7 && g.all { it in -12f..12f } } && e.errorDb in 0f..10f })
        assertTrue("Most fit well", all.count { it.fit().quality != FitQuality.Poor } > all.size * .95)
    }

    private fun sections(filters: String) = filters.split(';').map { f ->
        val (fc, gain, q) = f.drop(1).split(',').map { it.toFloat() }
        val shape = when (f[0]) { 'L' -> EqShape.LowShelf; 'H' -> EqShape.HighShelf; else -> EqShape.Bell }
        EqSection(shape, fc, q / 100f, gain / 10f)
    }

    /** scripts/autoeq/fit.py fitted the list off the phone; it must land where [fitAutoEq] would. Filters as extract_filters.py rounds them. */
    @Test fun theBundledGainsAreWhatThePhoneWouldFit() {
        // Name, source, rig, filters.
        val cases = listOf(
            listOf("Sennheiser HD 600", "Auriculares Argentina", "over-ear", "L105,63,70;P169,-21,77;P5646,-55,403;P8493,27,212;P493,9,117;H10000,-71,70;P3126,-20,371;P2134,16,283;P1310,-9,258;P4326,17,600"),
            listOf("Sony WH-1000XM4", "Rtings", "Bruel & Kjaer 5128 over-ear", "L105,-29,70;P139,-53,101;P2221,63,285;P728,20,70;P66,15,128;H10000,20,70;P8894,-32,178;P5132,-27,600;P6359,25,599;P585,-7,391"),
            listOf("Apple AirPods Pro", "HypetheSonics", "Bruel & Kjaer 5128 in-ear", "L105,4,70;P570,-18,71;P3630,24,129;P5833,-39,463;P7285,17,427;H10000,58,70;P9507,-48,427;P54,-6,184;P1919,7,363;P1936,-4,416"))
        cases.forEach { (name, source, rig, filters) ->
            val bundled = all.single { it.name == name && it.source == source && it.rig == rig }.fit()
            val phone = fitAutoEq(AutoEqTarget(name, eqCurve(sections(filters)), 0))
            phone.gains.indices.forEach { assertEquals("$name band $it", phone.gains[it], bundled.gains[it], .15f) }
            assertEquals(name, phone.errorDb, bundled.errorDb, .1f)
        }
    }

    @Test fun searchFindsEveryWordAndKeepsEachMeasurerApart() {
        val hd600 = AutoEqLibrary.search(all, "hd600")
        assertTrue("Joined words do not match a spaced name", hd600.none { it.name == "Sennheiser HD 600" })
        val found = AutoEqLibrary.search(all, "  sennheiser  HD-600 ")
        assertTrue(found.isNotEmpty() && found.all { e -> listOf("sennheiser", "hd", "600").all { it in e.key } })
        assertTrue("Exact names lead", found.takeWhile { it.name == "Sennheiser HD 600" }.size == found.count { it.name == "Sennheiser HD 600" })
        assertTrue("Several measurements of one model stay apart", found.filter { it.name == "Sennheiser HD 600" }.map { it.label }.distinct().size > 1)
        assertTrue(AutoEqLibrary.search(all, "e").size <= AutoEqLibrary.SHOWN)
        assertEquals(emptyList<AutoEqEntry>(), AutoEqLibrary.search(all, "  "))
        assertEquals(emptyList<AutoEqEntry>(), AutoEqLibrary.search(all, "no such headphones zzz"))
        val sony = AutoEqLibrary.search(all, "WH-1000XM4")
        assertTrue("A name starting with the words comes first", sony.isNotEmpty())
        val ranked = AutoEqLibrary.search(all, "sennheiser hd 600").filter { it.name == "Sennheiser HD 600" }
        assertEquals("AutoEq's own first choice leads", "oratory1990", ranked.first().source)
    }

    @Test fun aDamagedRowIsLeftOutAndTheRestRead() {
        val text = "# test\n#s\t0\tLab\tover-ear\nGood Cans\t0\t10 0 0 0 0 0 -10\t5\nShort\t0\t1 2 3\t5\nLoud\t0\t500 0 0 0 0 0 0\t5\n" +
            "No source\t7\t0 0 0 0 0 0 0\t5\n\t0\t0 0 0 0 0 0 0\t5\nBad error\t0\t0 0 0 0 0 0 0\tx\n"
        val read = AutoEqLibrary.read(ByteArrayInputStream(text.toByteArray()))
        assertEquals(listOf("Good Cans"), read.map { it.name })
        val fit = read.single().fit()
        assertEquals(listOf(1f, 0f, 0f, 0f, 0f, 0f, -1f), fit.gains); assertEquals(.5f, fit.errorDb); assertEquals("Good Cans · Lab", fit.name)
    }

    @Test fun applyingABundledProfileNamesItsMeasurer() {
        HeadphoneProfile.forget(context)
        val entry = AutoEqLibrary.search(all, "sennheiser hd 600").first()
        val applied = HeadphoneProfile.apply(context, entry.fit(), EqualizerConfig())
        assertEquals(EqMode.Manual, applied.mode); assertEquals(entry.fit().gains, applied.manual)
        assertEquals("Sennheiser HD 600 · oratory1990", HeadphoneProfile.name(context))
        HeadphoneProfile.forget(context)
    }
}
