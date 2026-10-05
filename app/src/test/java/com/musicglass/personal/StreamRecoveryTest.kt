package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.innertubex.sabr.SabrFailureKind
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamRecoveryTest {
    @Test fun tokenlessAttestationFailureMovesToAnotherProfileImmediately() {
        val budget=StreamRecoveryBudget()
        assertEquals(StreamRecoveryAction.EXCLUDE_PROFILE,
            budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"VISIONOS_SABR__nopo"))
        assertEquals(setOf("VISIONOS_SABR__nopo"),budget.excludedProfiles)
        assertNull(budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"VISIONOS_SABR__nopo"))
        // A subsequent attested profile still gets its one credential refresh.
        assertEquals(StreamRecoveryAction.REFRESH_ATTESTATION,
            budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"WEB_REMIX_SABR__po"))
    }

    @Test fun attestationRefreshThenRejectedProfilesAreBounded() {
        val budget=StreamRecoveryBudget()
        assertEquals(StreamRecoveryAction.REFRESH_ATTESTATION,budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"first"))
        assertEquals(StreamRecoveryAction.EXCLUDE_PROFILE,budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"first"))
        assertEquals(setOf("first"),budget.excludedProfiles)
        assertNull(budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,"first"))
        assertEquals(StreamRecoveryAction.EXCLUDE_PROFILE,budget.next(SabrFailureKind.RELOAD_PLAYER,null,"second"))
        assertEquals(StreamRecoveryAction.EXCLUDE_PROFILE,budget.next(SabrFailureKind.PROTOCOL,null,"third"))
        assertNull(budget.next(SabrFailureKind.PROTOCOL,null,"fourth"))
    }

    @Test fun expiredOrForbiddenUrlsAreRefreshedOnceBeforeFallback() {
        for(status in listOf(403,410)) {
            val budget=StreamRecoveryBudget()
            assertEquals(StreamRecoveryAction.REFRESH_STREAM,budget.next(null,status,"direct"))
            assertEquals(StreamRecoveryAction.EXCLUDE_PROFILE,budget.next(null,status,"direct"))
            assertNull(budget.next(null,status,"direct"))
        }
    }

    @Test fun outagesAndUnavailableTracksDoNotUseCredentialRecovery() {
        val budget=StreamRecoveryBudget()
        for(status in listOf(null,401,404,429,500,503)) assertNull(budget.next(null,status,"first"))
        assertEquals(StreamRecoveryAction.REFRESH_ATTESTATION,budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,null))
        assertNull(budget.next(SabrFailureKind.ATTESTATION_REQUIRED,null,null))
    }

    @Test fun media3KeepsThePlaylistPositionAndModesWhenTheStreamIsReplaced() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val before=MediaItem.Builder().setMediaId("before").setUri("file:///before.mp3").build()
        val rejected=MediaItem.Builder().setMediaId("yt:abcdefghijk").setUri("file:///old.mp3").build()
        val fresh=rejected.buildUpon().setUri("file:///fresh.mp3").build()
        val after=MediaItem.Builder().setMediaId("after").setUri("file:///after.mp3").build()
        val player=androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            player.setMediaItems(listOf(before,rejected,after),1,167_000)
            player.repeatMode=Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled=true
            player.playWhenReady=false
            val expected=player.currentMediaItem!!
            assertTrue(replaceFailedStream(player,expected,fresh))
            assertEquals(3,player.mediaItemCount)
            assertEquals("before",player.getMediaItemAt(0).mediaId)
            assertEquals("file:///fresh.mp3",player.getMediaItemAt(1).localConfiguration!!.uri.toString())
            assertEquals("after",player.getMediaItemAt(2).mediaId)
            assertEquals(1,player.currentMediaItemIndex)
            assertEquals(167_000L,player.currentPosition)
            assertEquals(Player.REPEAT_MODE_ALL,player.repeatMode)
            assertTrue(player.shuffleModeEnabled)
            assertFalse(player.playWhenReady)
        } finally {player.release()}
    }

    private class PlayerFixture {
        val rejected=MediaItem.Builder().setMediaId("yt:abcdefghijk").setUri("sabr://old").build()
        val fresh=MediaItem.Builder().setMediaId("yt:abcdefghijk").setUri("sabr://new").build()
        var item=rejected
        var index=3
        var position=167_000L
        var wants=true
        val calls=mutableListOf<String>()
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {_,method,args->
            when(method.name) {
                "getCurrentMediaItem"->item
                "getCurrentMediaItemIndex"->index
                "getCurrentPosition"->position
                "getPlayWhenReady"->wants
                "replaceMediaItem"->{assertEquals(index,args!![0]);item=args[1] as MediaItem;calls+="replace";null}
                "seekTo"->{assertEquals(index,args!![0]);position=args[1] as Long;calls+="seek";null}
                "prepare"->{calls+="prepare";null}
                "setPlayWhenReady"->{wants=args!![0] as Boolean;calls+="intent";null}
                else->error("Unexpected player call: ${method.name}")
            }
        } as Player
    }

    @Test fun freshBootstrapReplacesRejectedSourceAndPreservesPositionAndCurrentIndex() {
        val f=PlayerFixture()
        // The queue was reordered while resolving; recover at its current index.
        f.index=6
        assertTrue(replaceFailedStream(f.player,f.rejected,f.fresh))
        assertSame(f.fresh,f.item)
        assertEquals(167_000L,f.position)
        assertTrue(f.wants)
        assertEquals(listOf("replace","seek","prepare","intent"),f.calls)
    }

    @Test fun aPauseDuringResolutionRemainsPaused() {
        val f=PlayerFixture();f.wants=false
        assertTrue(replaceFailedStream(f.player,f.rejected,f.fresh))
        assertFalse(f.wants)
    }

    @Test fun lateRecoveryCannotReplaceAnotherSelectionEvenOfTheSameSong() {
        val f=PlayerFixture()
        f.item=f.rejected.buildUpon().build()
        assertFalse(replaceFailedStream(f.player,f.rejected,f.fresh))
        assertTrue(f.calls.isEmpty())
    }
}
