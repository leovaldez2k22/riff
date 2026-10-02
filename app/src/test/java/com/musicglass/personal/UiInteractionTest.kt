package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test

class UiInteractionTest {
    @Test fun doubleTapResetsOnlyItsOwnTabAndRootState() {
        for(name in listOf("Home","Podcasts","Radio","Library","Search")) {
            val model=ShellModel();model.stacks=mapOf(name to listOf("collection"),"Other" to listOf("account"))
            assertFalse(model.selectFromTap(name,1000));assertTrue(model.selectFromTap(name,1200))
            assertTrue(model.stacks[name].orEmpty().isEmpty());assertEquals(listOf("account"),model.stacks["Other"])
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
        intensity.change(.2f,0f..1.5f);assertNull(intensity.change(.96f,0f..1.5f))
        assertEquals(HapticCue.Threshold,intensity.change(1.01f,0f..1.5f));assertNull(intensity.change(1.2f,0f..1.5f))
    }
}
