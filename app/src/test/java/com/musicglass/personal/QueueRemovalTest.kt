package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueRemovalTest {
    @get:Rule val compose=createComposeRule()

    @Test fun visibleRemoveButtonRemovesOnlyTheSelectedOccurrence() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        // This view reads only the like map; cancel the provider's delayed network prewarm.
        val provider=YouTubeMusicProvider(context).also {it.close()}
        var state by mutableStateOf(PlaybackUiState(title="Playing",queue=listOf(
            QueueEntry("yt:same","Playing","Artist",queueId="one"),
            QueueEntry("yt:same","First copy","Artist",queueId="two"),
            QueueEntry("yt:same","Second copy","Artist",queueId="three"))))
        val removed=mutableListOf<Int>()
        val playback=Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader,arrayOf(PlaybackGateway::class.java)) {proxy,method,args->
            when(method.name) {
                "equals"->proxy===args?.firstOrNull()
                "hashCode"->System.identityHashCode(proxy)
                "toString"->"Queue removal fixture"
                "getState"->state
                "getPositionMs"->0L
                "removeQueueItem"->{val index=args!![0] as Int;removed+=index;state=state.copy(queue=state.queue.filterIndexed {i,_->i!=index});null}
                else->error("Unexpected gateway call: ${method.name}")
            }
        } as PlaybackGateway
        compose.setContent {MaterialTheme {InlineQueue(playback,provider,{},{},{})}}
        compose.onNodeWithTag("queue_remove_2").assertIsDisplayed().performClick()
        compose.onNodeWithText("First copy").assertIsDisplayed()
        compose.onNodeWithText("Second copy").assertDoesNotExist()
        assertEquals(listOf(2),removed)
        assertEquals(listOf("one","two"),state.queue.map {it.queueId})
    }

    @Test fun deletingTheLastSongStopsAndClearsTheQueue() {
        var count=1;var playing=true
        val calls=mutableListOf<String>()
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {_,method,args->
            when(method.name) {
                "getMediaItemCount"->count
                "pause"->{playing=false;calls+="pause";null}
                "removeMediaItem"->{assertEquals(0,args!![0]);count--;calls+="remove";null}
                else->error("Unexpected player call: ${method.name}")
            }
        } as Player
        removePlaybackQueueItem(player,0)
        assertEquals(0,count);assertFalse(playing)
        assertEquals(listOf("pause","remove"),calls)
        removePlaybackQueueItem(player,0)
        assertEquals(2,calls.size)
    }
}
