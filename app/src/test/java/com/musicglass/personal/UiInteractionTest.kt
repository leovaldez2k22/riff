package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test

class UiInteractionTest {
    @Test fun doubleTapResetsOnlyItsOwnTabAndRootState() {
        for(name in listOf("Home","Podcasts","Radio","Library","Search")) {
            // Q11: stacks hold typed routes for real roots, so the other stack belongs to another root rather than to "Other".
            val other=if(name=="Home") "Library" else "Home"
            val model=ShellModel();model.select(other);model.push(Route.Account);model.select(name);model.openCollection("MPREb_fixtureAlbum01","Album")
            assertEquals(listOf("collection"),model.stacks[name])
            assertFalse(model.selectFromTap(name,1000));assertTrue(model.selectFromTap(name,1200))
            assertTrue(model.stacks[name].orEmpty().isEmpty());assertEquals(listOf("account"),model.stacks[other])
            assertEquals(1,model.rootRevisions[name]);assertEquals(name,model.tab)
            assertEquals(if(name=="Search") 1 else 0,model.searchFocusRequest)
        }
    }
    @Test fun switchingTabsAndProgrammaticNavigationDoNotCreateDoubleTaps() {
        val model=ShellModel();model.select("Home");assertFalse(model.selectFromTap("Home",1000))
        assertFalse(model.selectFromTap("Radio",1100));assertFalse(model.selectFromTap("Home",1200))
        assertFalse(model.selectFromTap("Home",2000));assertTrue(model.selectFromTap("Home",2200))
        assertFalse(model.selectFromTap("Home",2300))
    }
    @Test fun seekingPulsesAtPickupAndCommitOnly() {
        val tracker=SliderHapticTracker(SliderHaptics.Seek)
        assertEquals(HapticCue.Selection,tracker.change(5f,0f..100f))
        for(value in 6..90) assertNull(tracker.change(value.toFloat(),0f..100f))
        assertEquals(HapticCue.DragEnd,tracker.finish());assertNull(tracker.finish())
    }
    @Test fun volumeUsesRealStepsAndIntensityPreservesContinuousValues() {
        val volume=SliderHapticTracker(SliderHaptics.Volume)
        volume.change(5f,0f..10f);assertNull(volume.change(5.2f,0f..10f))
        assertEquals(HapticCue.Selection,volume.change(5.6f,0f..10f));assertEquals(HapticCue.Threshold,volume.change(10f,0f..10f))
        val intensity=SliderHapticTracker(SliderHaptics.Intensity)
        intensity.change(.2f,0f..1.5f);assertNull(intensity.change(.24f,0f..1.5f))
        assertEquals(HapticCue.TickMedium,intensity.change(.26f,0f..1.5f));assertNull(intensity.change(.49f,0f..1.5f))
        assertEquals(HapticCue.TickMedium,intensity.change(.5f,0f..1.5f));assertNull(intensity.change(.52f,0f..1.5f))
    }
    @Test fun glassIntensityTicksEveryQuarterBothWays() {
        val glass=SliderHapticTracker(SliderHaptics.Intensity);glass.change(0f,0f..1.5f)
        val ticks=(1..150).map {glass.change(it/100f,0f..1.5f)}.withIndex().filter {it.value!=null}.map {it.index+1}
        assertEquals(listOf(25,50,75,100,125,150),ticks)
        val down=(149 downTo 0).map {glass.change(it/100f,0f..1.5f)}.count {it==HapticCue.TickMedium}
        assertEquals(6,down)
    }
    @Test fun boostIsStrongestAtTheHundredsLighterAtTheFiftiesAndLightestBetween() {
        val boost=SliderHapticTracker(SliderHaptics.Boost);boost.change(100f,100f..300f)
        val cues=(101..300).mapNotNull {v->boost.change(v.toFloat(),100f..300f)?.let {v to it}}.toMap()
        assertEquals(mapOf(125 to HapticCue.TickLight,150 to HapticCue.TickMedium,175 to HapticCue.TickLight,200 to HapticCue.TickStrong,
            225 to HapticCue.TickLight,250 to HapticCue.TickMedium,275 to HapticCue.TickLight,300 to HapticCue.TickStrong),cues)
        // Back down to 100 the start is a strong mark too, and a jump over several marks gives the strongest of them.
        boost.change(110f,100f..300f);assertEquals(HapticCue.TickStrong,boost.change(100f,100f..300f))
        assertEquals(HapticCue.TickStrong,boost.change(240f,100f..300f))
    }
    @Test fun crossfadeTicksAtEachSecond() {
        val seconds=SliderHapticTracker(SliderHaptics.Seconds);seconds.change(1f,1f..12f)
        assertEquals((2..12).map {HapticCue.TickMedium},(2..12).map {seconds.change(it.toFloat(),1f..12f)})
        assertNull(seconds.change(12f,1f..12f))
    }
}
