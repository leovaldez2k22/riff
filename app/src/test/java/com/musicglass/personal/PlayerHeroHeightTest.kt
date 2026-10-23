package com.musicglass.personal

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Q18-079: at the largest text the open queue had half the window and its heading was cut off. */
class PlayerHeroHeightTest {
    @Test fun theOpenQueueHasTheWholeWindowAboveTheNavigationBar() {
        assertEquals(400.dp, playerHeroHeight(800f, 58.dp, queueOpen = false))
        assertEquals(742.dp, playerHeroHeight(800f, 58.dp, queueOpen = true))
        assertEquals("landscape, short window: never less than the artwork had", 180.dp, playerHeroHeight(360f, 200.dp, queueOpen = true))
    }
}
