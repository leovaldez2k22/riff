package com.musicglass.personal

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand

/**
 * Feature 23: Like and Shuffle beside the transport buttons on the notification, the lock screen and the car.
 * Like is offered only for a YouTube Music song while signed in, and changes the like the same way the player does.
 */
internal const val TOGGLE_LIKE = "com.musicglass.personal.TOGGLE_LIKE"
internal const val TOGGLE_SHUFFLE = "com.musicglass.personal.TOGGLE_SHUFFLE"

/** What the buttons show; they are sent again only when this changes. */
internal data class MediaButtonState(val likeable: Boolean, val liked: Boolean, val shuffle: Boolean)

@UnstableApi
internal fun mediaButtons(state: MediaButtonState): List<CommandButton> = buildList {
    add(CommandButton.Builder(if (state.shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
        .setDisplayName(string(R.string.shuffle))
        .setSessionCommand(SessionCommand(TOGGLE_SHUFFLE, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build())
    if (state.likeable) add(CommandButton.Builder(if (state.liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
        .setDisplayName(string(if (state.liked) R.string.liked else R.string.like_on_youtube_music))
        .setSessionCommand(SessionCommand(TOGGLE_LIKE, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW).build())
}

/** Session commands the buttons send, given to every controller that may show them. */
internal val mediaButtonCommands = listOf(TOGGLE_LIKE, TOGGLE_SHUFFLE)

/**
 * Who may press them: Riff, the system's notification and lock screen, and the car. Other apps may play and pause
 * but not change the account's likes.
 */
@UnstableApi
internal fun mayPressMediaButtons(session: MediaSession, controller: MediaSession.ControllerInfo, own: Boolean): Boolean =
    own || session.isMediaNotificationController(controller) || session.isAutoCompanionController(controller) ||
        session.isAutomotiveController(controller) || controller.packageName == "com.android.systemui"
