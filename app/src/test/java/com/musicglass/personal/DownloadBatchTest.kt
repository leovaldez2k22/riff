package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadBatchTest {
    private fun store()=OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply {isAccessible=true}
        .newInstance(RuntimeEnvironment.getApplication())
    private fun track(id:String)=ProviderTrack(id,"Song","Artist")
    private fun bundle(store:OfflineMusicStore,scope:String,id:String) {
        val dir=store.directory(scope,id).apply {mkdirs()}
        File(dir,"audio.media").writeBytes(byteArrayOf(1,2,3,4));File(dir,"ready").writeText("4")
        File(dir,"artwork").writeText("art");File(dir,"lyrics.json").writeText("lyrics")
        store.update(scope,id,"state" to "complete","bytes" to 4L)
    }
    @Test fun playlistDownloadDeduplicatesAndKeepsCompletedAndActiveSongs() {
        val store=store();val scope="batch"
        val ready=track("download001");val active=track("download002");val fresh=track("download003")
        store.enqueue(ready,scope=scope);bundle(store,scope,ready.videoId)
        store.enqueue(active,scope=scope);assertNotNull(store.beginDownload(scope,active.videoId))
        val count=store.enqueueSongs(listOf(ready,active,fresh,fresh,track("download004").copy(isLive=true),track("download005").copy(isMusic=false)),scope)
        assertEquals(3,count);assertEquals(3,store.rows(scope).size)
        assertEquals("complete",store.song(scope,ready.videoId)?.state)
        assertEquals("resolving",store.song(scope,active.videoId)?.state)
        assertEquals("queued",store.song(scope,fresh.videoId)?.state)
        assertNotNull(store.audio(scope,ready.videoId));assertTrue(store.rows(scope).all {it.pinned})
    }
    @Test fun deleteAllCancelsPendingWorkRemovesBundlesAndPreservesOtherAccountsAndHistory() {
        val store=store();val scope="delete"
        store.enqueue(track("delete00001"),scope=scope);bundle(store,scope,"delete00001")
        store.update(scope,"delete00001","lastPlayed" to 500L,"plays" to 3)
        store.enqueue(track("delete00002"),scope=scope);val ticket=store.beginDownload(scope,"delete00002")!!
        store.enqueue(track("delete00001"),scope="other");bundle(store,"other","delete00001")
        store.removeAll(scope)
        assertTrue(store.rows(scope).all {it.state=="metadata" && it.excluded && !it.pinned && it.bytes==0L})
        assertFalse(store.directory(scope,"delete00001").exists())
        assertNull(store.beginDownload(scope,"delete00002"))
        assertFalse(store.updateDownload(scope,"delete00002",ticket,"state" to "assets"))
        assertFalse(store.updateDownload(scope,"delete00001",0,"state" to "complete"))
        assertEquals(500L,store.song(scope,"delete00001")?.lastPlayed);assertEquals(3,store.song(scope,"delete00001")?.plays)
        assertNotNull(store.audio("other","delete00001"))
    }
    @Test fun frostAndRefractionHaveIndependentPersistedIntensities() {
        val context=RuntimeEnvironment.getApplication()
        val settings=AccessibilitySettings(context)
        settings.updateGlassIntensity(.3f);settings.updateFrostedIntensity(1.4f)
        val restored=AccessibilitySettings(context)
        assertEquals(.3f,restored.glassIntensity,0f);assertEquals(1.4f,restored.frostedIntensity,0f)
        restored.updateGlassIntensity(0f);assertEquals(1.4f,restored.frostedIntensity,0f)
    }
    @Test fun frostedGlassIsOnUntilTheOwnerTurnsItOff() {
        // Q18-049: on by default; a choice made in Settings is kept. Owner, 31 Oct: glass 125 %, frost 25 %.
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("accessibility_display",Context.MODE_PRIVATE).edit().remove("frosted_glass").remove("frosted_intensity").commit()
        assertTrue(AccessibilitySettings(context).frostedGlass)
        assertEquals(FROSTED_INTENSITY_DEFAULT,AccessibilitySettings(context).frostedIntensity,0f)
        context.getSharedPreferences("accessibility_display",Context.MODE_PRIVATE).edit().remove("glass_intensity").commit()
        assertEquals(1.25f,AccessibilitySettings(context).glassIntensity,0f)
        AccessibilitySettings(context).updateFrostedGlass(false)
        assertFalse(AccessibilitySettings(context).frostedGlass)
    }
    @Test fun nullVideoUriOnResolvedAudioDoesNotDisableCrossfade() {
        fun item(extras:Bundle)=MediaItem.Builder().setMediaId("yt:download001")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
        val audio=Bundle().apply {putString("videoUri",null);putBoolean("video",false);putBoolean("music",true)}
        assertTrue(audio.containsKey("videoUri"));assertFalse(crossfadeSkipsItem(item(audio)))
        assertTrue(crossfadeSkipsItem(item(Bundle(audio).apply {putString("videoUri","https://example.com/video")})))
        assertTrue(crossfadeSkipsItem(item(Bundle(audio).apply {putBoolean("live",true)})))
        assertTrue(crossfadeSkipsItem(item(Bundle(audio).apply {putBoolean("music",false)})))
    }
}
