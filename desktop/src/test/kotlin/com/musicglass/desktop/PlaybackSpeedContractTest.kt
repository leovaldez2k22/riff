package com.musicglass.desktop
import kotlin.test.*
import java.io.*
class PlaybackSpeedContractTest {
    @Test fun sourceRateContractRoundTripsAndRejectsInvalidSteps(){
        val command=AudioCommand.PrepareSource(81,"occurrence","youtube:abcdefghijk",0,1.55f)
        val bytes=ByteArrayOutputStream();AudioIpcTransport.writeCommand(DataOutputStream(bytes),command)
        assertEquals(command,AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        for(rate in listOf(Float.NaN,Float.POSITIVE_INFINITY,.45f,2.05f,1.123f))assertFailsWith<IllegalArgumentException>{checkedPlaybackRate(rate)}
        for(step in 10..40)assertEquals(step/20f,checkedPlaybackRate(step/20f))
    }
    @Test fun authoritativeEpisodeKindTravelsWithTheSession(){
        val song=EmptyDesktopTrack.copy(id="youtube:abcdefghijk",scope="guest",isEpisode=true)
        assertEquals(song,songFromSession(song.toSessionJson()));assertFalse(EmptyDesktopTrack.isEpisode)
        val card=ProviderHomeCard("Episode","Show",null,"abcdefghijk",kind=CatalogKind.Episode)
        assertTrue(card.song().isEpisode);assertFalse(card.copy(kind=CatalogKind.Song).song().isEpisode)
    }
    @Test fun longRecordingsUseTheSafeActualRateWithoutChangingShortTrackPreferences(){
        val state=HostSnapshot(musicPlaybackRate=2f,episodePlaybackRate=1.5f)
        assertEquals(2f,effectivePlaybackSpeed(state,EmptyDesktopTrack.copy(durationMs=300000)))
        assertEquals(1.5f,effectivePlaybackSpeed(state,EmptyDesktopTrack.copy(durationMs=300000,isEpisode=true)))
        assertEquals(1f,effectivePlaybackSpeed(state,EmptyDesktopTrack.copy(durationMs=14400001)))
    }
}
