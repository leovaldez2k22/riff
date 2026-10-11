package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Typed playback state, command order and queue-entry identity (Q06). Synthetic items only; nothing is played. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackStateTest {
    private fun status(count: Int = 1, state: Int = Player.STATE_READY, wants: Boolean = false, playing: Boolean = false,
        failed: Boolean = false, recovering: Boolean = false, resolving: Boolean = false) =
        playbackStatus(count, state, wants, playing, failed, recovering, resolving)

    @Test fun exactlyOneStatusDescribesEachSituation() {
        assertEquals(PlaybackStatus.Idle, status(count = 0, state = Player.STATE_IDLE))
        assertEquals("an empty queue is idle even if an error is left over", PlaybackStatus.Idle, status(count = 0, failed = true))
        assertEquals(PlaybackStatus.Playing, status(wants = true, playing = true))
        assertEquals(PlaybackStatus.Paused, status())
        assertEquals("restored after a restart, not prepared", PlaybackStatus.Paused, status(state = Player.STATE_IDLE))
        assertEquals("held by the system while the user still wants playback", PlaybackStatus.Paused, status(wants = true))
        assertEquals(PlaybackStatus.Buffering, status(state = Player.STATE_BUFFERING, wants = true))
        assertEquals(PlaybackStatus.Resolving, status(state = Player.STATE_BUFFERING, wants = true, resolving = true))
        assertEquals("a paused lookup is still a lookup", PlaybackStatus.Resolving, status(state = Player.STATE_BUFFERING, resolving = true))
        assertEquals(PlaybackStatus.Ended, status(state = Player.STATE_ENDED, wants = true))
        assertEquals(PlaybackStatus.Failed, status(state = Player.STATE_IDLE, wants = true, failed = true))
        assertEquals("a retry in progress is not shown as a failure", PlaybackStatus.Recovering,
            status(state = Player.STATE_IDLE, wants = true, failed = true, recovering = true))
    }

    @Test fun failuresKeepTheWordingUsersAlreadySee() {
        fun text(kind: PlaybackFailureKind, detail: String = "") = PlaybackFailure(kind, detail).message()
        assertEquals("YouTube Music could not start this track. Try another track.", text(PlaybackFailureKind.ProviderTrack))
        assertEquals("YouTube could not start this live stream. Try refreshing Radio.", text(PlaybackFailureKind.LiveStream))
        assertEquals("Playback failed (ERROR_CODE_IO_FILE_NOT_FOUND)", text(PlaybackFailureKind.Player, "ERROR_CODE_IO_FILE_NOT_FOUND"))
        assertEquals("This collection is too large to queue", text(PlaybackFailureKind.CollectionTooLarge))
        assertEquals("This on-device audio file is unavailable", text(PlaybackFailureKind.LocalUnavailable))
        PlaybackFailureKind.entries.forEach { assertTrue(it.name, text(it).isNotBlank()) }
        RecoveryReason.entries.forEach { assertEquals(it, RecoveryReason.of(it.wire)) }
        assertNull(RecoveryReason.of("")); assertNull(RecoveryReason.of(null))
    }

    @Test fun aLaterReplacementMakesEveryEarlierRequestObsolete() {
        val order = PlaybackCommandOrder()
        val first = order.arrive(replaces = true); val add = order.arrive(replaces = false)
        assertFalse(order.superseded(first)); assertFalse(order.superseded(add))
        val second = order.arrive(replaces = true)
        assertTrue(order.superseded(first)); assertTrue("an addition to a queue that is being replaced", order.superseded(add))
        assertFalse(order.superseded(second))
        val later = order.arrive(replaces = false)
        assertFalse("additions do not replace anything", order.superseded(second)); assertFalse(order.superseded(later))
    }

    @Test fun aPauseMadeAfterARequestKeepsItSilentAndALaterPlayStartsItAgain() {
        val order = PlaybackCommandOrder()
        order.userIntent(play = false)
        val request = order.arrive(replaces = true)
        assertTrue("a pause from before the request does not apply to it", order.autoplay(request, requested = true))
        order.userIntent(play = false)
        assertFalse(order.autoplay(request, requested = true))
        order.userIntent(play = true)
        assertTrue(order.autoplay(request, requested = true))
        assertFalse("a request that asked to load paused never starts by itself", order.autoplay(request, requested = false))
    }

    @Test fun everyStructuralEditRaisesTheRevision() {
        val order = PlaybackCommandOrder()
        assertEquals(0, order.queueRevision)
        assertEquals(1, order.edited()); assertEquals(2, order.edited()); assertEquals(2, order.queueRevision)
        order.arrive(true); order.userIntent(true)
        assertEquals("commands and transport do not count as edits", 2, order.queueRevision)
    }

    private class Lane(scope: CoroutineScope) {
        val order = PlaybackCommandOrder()
        val commands = OrderedCommands(scope, order, "cancelled", "failed")
        val applied = mutableListOf<String>(); val answers = mutableListOf<String>(); val dropped = mutableListOf<String>()
        fun send(name: String, replaces: Boolean, gate: CompletableDeferred<Unit>? = null, autoplay: Boolean = true) =
            commands.submit(replaces, { dropped += name }, { gate?.await(); name },
                { ticket, prepared -> applied += prepared + if (order.autoplay(ticket, autoplay)) ":play" else ":silent"; "ok" }) { answers += "$name=$it" }
    }
    private suspend fun settle() = repeat(20) { yield() }

    @Test fun aSlowOlderRequestCannotOverwriteTheNewest() = runBlocking {
        val lane = Lane(this); val slow = CompletableDeferred<Unit>()
        lane.send("large album", replaces = true, gate = slow); settle()
        lane.send("single", replaces = true); settle()
        assertTrue("the newer request waits its turn", lane.applied.isEmpty())
        slow.complete(Unit); settle()
        assertEquals(listOf("single:play"), lane.applied)
        assertEquals(listOf("large album=cancelled", "single=ok"), lane.answers)
        assertEquals(listOf("large album"), lane.dropped)
    }

    @Test fun rapidReplacementsApplyOnlyTheLast() = runBlocking {
        val lane = Lane(this)
        listOf("a", "b", "c", "d").forEach { lane.send(it, replaces = true) }
        settle()
        assertEquals(listOf("d:play"), lane.applied)
        assertEquals(listOf("a=cancelled", "b=cancelled", "c=cancelled", "d=ok"), lane.answers)
    }

    @Test fun additionsAreAllAppliedInTheOrderTheyWereMade() = runBlocking {
        val lane = Lane(this); val slow = CompletableDeferred<Unit>()
        lane.send("album", replaces = true)
        lane.send("add one", replaces = false, gate = slow); lane.send("add two", replaces = false); settle()
        assertEquals("the second addition does not jump the first", listOf("album:play"), lane.applied)
        slow.complete(Unit); settle()
        assertEquals(listOf("album:play", "add one:play", "add two:play"), lane.applied)
    }

    @Test fun anAdditionMeantForAQueueThatIsBeingReplacedIsDropped() = runBlocking {
        val lane = Lane(this); val slow = CompletableDeferred<Unit>()
        lane.send("add", replaces = false, gate = slow); settle()
        lane.send("album", replaces = true); slow.complete(Unit); settle()
        assertEquals(listOf("album:play"), lane.applied)
        assertEquals(listOf("add=cancelled", "album=ok"), lane.answers)
    }

    @Test fun aPauseWhileTheQueueIsBeingReadLoadsItSilent() = runBlocking {
        val lane = Lane(this); val slow = CompletableDeferred<Unit>()
        lane.send("album", replaces = true, gate = slow); settle()
        lane.order.userIntent(play = false)
        slow.complete(Unit); settle()
        assertEquals(listOf("album:silent"), lane.applied)
    }

    @Test fun aFailureWhilePreparingIsAnsweredAndTheNextCommandStillRuns() = runBlocking {
        val lane = Lane(this)
        lane.commands.submit(true, {}, { error("unreadable") }, { _, _: String -> "ok" }) { lane.answers += "broken=$it" }
        settle()
        lane.commands.submit(true, {}, { "x" }, { _, _ -> error("player refused") }) { lane.answers += "refused=$it" }
        settle()
        lane.send("next", replaces = true); settle()
        assertEquals(listOf("broken=failed", "refused=failed", "next=ok"), lane.answers)
    }

    @Test fun aCommandCutOffByShutdownIsStillAnsweredOnce() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job()); val lane = Lane(scope); val never = CompletableDeferred<Unit>()
        lane.send("album", replaces = true, gate = never); settle()
        scope.cancel(); settle()
        assertEquals(listOf("album=failed"), lane.answers); assertTrue(lane.applied.isEmpty())
    }

    // Removal
    @Test fun removingTheCurrentEntrySelectsTheFollowingSurvivorOtherwiseThePreceding() {
        assertEquals(RemovalPlan.Empties, removalPlan(count = 1, current = 0, removed = 0, next = C.INDEX_UNSET, previous = C.INDEX_UNSET))
        assertEquals("another entry: the selection is not touched", RemovalPlan.Proceed, removalPlan(3, 1, 0, 2, 0))
        assertEquals("a following entry takes over", RemovalPlan.Proceed, removalPlan(3, 1, 1, 2, 0))
        assertEquals(RemovalPlan.SelectBefore(1), removalPlan(3, 2, 2, C.INDEX_UNSET, 1))
        assertEquals("repeat all wraps to the first, which follows in play order", RemovalPlan.Proceed, removalPlan(3, 2, 2, 0, 1))
        assertEquals("shuffled: the player's own previous entry", RemovalPlan.SelectBefore(0), removalPlan(3, 1, 1, C.INDEX_UNSET, 0))
    }

    @Test fun removingEveryCopyOfASongFollowsTheSameRule() {
        assertEquals(RemovalPlan.Empties, removalPlan(2, 0, setOf(0, 1)))
        assertEquals(RemovalPlan.Proceed, removalPlan(4, 0, setOf(1, 3)))
        assertEquals("a survivor follows the current copy", RemovalPlan.Proceed, removalPlan(4, 1, setOf(1, 3)))
        assertEquals("only earlier entries survive", RemovalPlan.SelectBefore(1), removalPlan(4, 2, setOf(2, 3)))
        assertEquals(RemovalPlan.SelectBefore(0), removalPlan(4, 3, setOf(1, 2, 3)))
    }

    private fun item(video: String, entry: String, title: String = entry) = MediaItem.Builder().setMediaId("yt:$video").setUri("catalog://$video")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setExtras(Bundle().apply { putString(ENTRY_ID, entry) }).build()).build()
    private fun Player.entries() = (0 until mediaItemCount).map { getMediaItemAt(it).entryId() }
    private fun player(vararg items: MediaItem, at: Int = 0, test: (ExoPlayer) -> Unit) {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try { player.setMediaItems(items.toList(), at, 0); test(player) } finally { player.release() }
    }

    @Test fun inARealPlayerRemovingTheLastCurrentEntrySelectsTheOneBeforeItPaused() =
        player(item("aaaaaaaaaaa", "one"), item("bbbbbbbbbbb", "two"), item("ccccccccccc", "three"), at = 2) { player ->
            player.playWhenReady = true
            removePlaybackQueueItem(player, 2)
            assertEquals(listOf("one", "two"), player.entries())
            assertEquals("two", player.currentMediaItem?.entryId()); assertEquals(0, player.currentPosition)
            assertFalse("the queue has run out: nothing starts by itself", player.playWhenReady)
        }

    @Test fun inARealPlayerRemovingTheCurrentEntrySelectsTheOneAfterItAndKeepsPlaying() =
        player(item("aaaaaaaaaaa", "one"), item("bbbbbbbbbbb", "two"), item("ccccccccccc", "three"), at = 1) { player ->
            player.playWhenReady = true
            removePlaybackQueueItem(player, 1)
            assertEquals("three", player.currentMediaItem?.entryId()); assertTrue(player.playWhenReady)
            removePlaybackQueueItem(player, 0)
            assertEquals("removing another entry leaves the selection", "three", player.currentMediaItem?.entryId())
            removePlaybackQueueItem(player, 0)
            assertEquals(0, player.mediaItemCount); assertFalse("the last entry leaves an empty, paused queue", player.playWhenReady)
        }

    @Test fun repeatAllKeepsPlayingFromTheFirstWhenTheLastCurrentEntryIsRemoved() =
        player(item("aaaaaaaaaaa", "one"), item("bbbbbbbbbbb", "two"), at = 1) { player ->
            player.repeatMode = Player.REPEAT_MODE_ALL; player.playWhenReady = true
            removePlaybackQueueItem(player, 1)
            assertEquals("one", player.currentMediaItem?.entryId()); assertTrue(player.playWhenReady)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        }

    @Test fun twoCopiesOfOneRecordingStayTwoEntriesThroughMoveAndRemove() =
        player(item("aaaaaaaaaaa", "first copy"), item("aaaaaaaaaaa", "second copy"), item("bbbbbbbbbbb", "other"), at = 1) { player ->
            assertEquals("the selection is the entry, not the video", "second copy", player.currentMediaItem?.entryId())
            player.moveMediaItem(1, 2)
            assertEquals(listOf("first copy", "other", "second copy"), player.entries())
            assertEquals("second copy", player.currentMediaItem?.entryId()); assertEquals(2, player.currentMediaItemIndex)
            removePlaybackQueueItem(player, 0)
            assertEquals(listOf("other", "second copy"), player.entries())
            assertEquals("second copy", player.currentMediaItem?.entryId())
            assertEquals("second copy", player.currentMediaItem?.mediaMetadata?.title.toString())
        }
}
