package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadControlsTest {
    @get:Rule val compose=createComposeRule()
    private fun playback(state:PlaybackUiState)=Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader,arrayOf(PlaybackGateway::class.java)) {proxy,method,args->
        when(method.name) {
            "equals"->proxy===args?.firstOrNull();"hashCode"->System.identityHashCode(proxy);"toString"->"Controls fixture"
            "getState"->state;"getPositionMs"->0L
            else->error("Unexpected playback call: ${method.name}")
        }
    } as PlaybackGateway

    @Test fun clearingPlaybackCacheRequiresConfirmationAndCancelKeepsTheAudio() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        YouTubeMusicProvider.shared(context).close()
        val cache=MusicDiskCache.get(context)
        val key="confirmation-fixture"
        val hole=cache.startReadWrite(key,0,4)
        try {val file=cache.startFile(key,0,4);file.writeBytes(byteArrayOf(1,2,3,4));cache.commitFile(file,4)}
        finally {cache.releaseHoleSpan(hole)}
        var notice=""
        compose.setContent {MaterialTheme {Column {DownloadSettings(ShellModel()) {notice=it}}}}
        compose.onNodeWithTag("settings_clear_cache").performClick()
        compose.onNodeWithText("Clear playback cache?").assertIsDisplayed()
        assertTrue(cache.isCached(key,0,4));assertEquals("",notice)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear playback cache?").assertDoesNotExist();assertTrue(cache.isCached(key,0,4))
        compose.onNodeWithTag("settings_clear_cache").performClick()
        compose.onNodeWithTag("confirm_clear_cache").performClick()
        compose.waitUntil(10_000) {notice.isNotEmpty()}
        assertFalse(cache.isCached(key,0,4));assertEquals("Playback cache cleared; downloads kept",notice)
    }

    @Test fun tappingQueueThumbnailReturnsToPlayerWithoutChangingPlayback() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val provider=YouTubeMusicProvider(context).also {it.close()}
        val gateway=playback(PlaybackUiState(title="Song",mediaId="yt:download001"))
        var returned=0
        compose.setContent {MaterialTheme {InlineQueue(gateway,provider,{returned++},{},{})}}
        compose.onNodeWithTag("queue_artwork_back").performClick()
        assertEquals(1,returned)
    }
}
