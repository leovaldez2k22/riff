package com.musicglass.personal

import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 23: Shuffle always, Like only for a YouTube Music song while signed in, each showing its state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MediaButtonsTest {
    @Test fun likeIsOfferedOnlyWhenItCanBeSaved() {
        assertEquals(listOf(TOGGLE_SHUFFLE), mediaButtons(MediaButtonState(likeable = false, liked = false, shuffle = false)).map { it.sessionCommand?.customAction })
        assertEquals(listOf(TOGGLE_SHUFFLE, TOGGLE_LIKE), mediaButtons(MediaButtonState(likeable = true, liked = false, shuffle = false)).map { it.sessionCommand?.customAction })
    }

    @Test fun iconsFollowTheState() {
        val off = mediaButtons(MediaButtonState(likeable = true, liked = false, shuffle = false))
        assertEquals(listOf(CommandButton.ICON_SHUFFLE_OFF, CommandButton.ICON_HEART_UNFILLED), off.map { it.icon })
        val on = mediaButtons(MediaButtonState(likeable = true, liked = true, shuffle = true))
        assertEquals(listOf(CommandButton.ICON_SHUFFLE_ON, CommandButton.ICON_HEART_FILLED), on.map { it.icon })
    }

    @Test fun theButtonsSitBesideTheTransport() {
        val buttons = mediaButtons(MediaButtonState(likeable = true, liked = false, shuffle = false))
        assertEquals(CommandButton.SLOT_BACK_SECONDARY, buttons[0].slots[0])
        assertEquals(CommandButton.SLOT_FORWARD_SECONDARY, buttons[1].slots[0])
    }
}
