package com.musicglass.desktop

import com.musicglass.data.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class LyricsProductionTest {
    @Test fun videoTitleNormalizationRetainsRecordingVersions(){
        assertEquals("Never Gonna Give You Up",lyricsRecordingTitle("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)","Rick Astley"))
        assertEquals("Song (Live)",lyricsRecordingTitle("Artist - Song (Live)","Artist"))
        assertEquals("Song (Remix)",lyricsRecordingTitle("Artist - Song (Remix)","Artist"))
        assertEquals("Other - Song",lyricsRecordingTitle("Other - Song","Artist"))
    }
    @Test fun offlineRomanizationPreservesSourceAndOriginalTiming(){
        val original=LyricLine(1000,2000,"नमस्ते",listOf(LyricPart(1000,2000,0,6)))
        val supplied=original.copy(romanized="Source supplied")
        val enriched=DesktopLyricsRomanize.enrich(LyricsDocument("Local","local",listOf(original,supplied)))
        assertEquals("namaste",enriched.lines[0].romanized);assertTrue(enriched.lines[0].romanizedAuto)
        assertEquals(original.parts,enriched.lines[0].parts);assertEquals(original.text,enriched.lines[0].text)
        assertEquals("Source supplied",enriched.lines[1].romanized);assertFalse(enriched.lines[1].romanizedAuto)
        assertEquals(LyricsRomanMode.Original,LyricsRomanMode.Both.next)
    }
    @Test fun miniPlayerUsesActualDocumentOffsetAndRomanizationWithoutTimingPlainLyrics(){
        val document=LyricsDocument("Local","authored",listOf(LyricLine(1000,2000,"Original",romanized="Roman")))
        val loaded=LyricsLoadState.Loaded(document,offsetMs=200,romanMode=LyricsRomanMode.Both)
        assertNull(miniLyricText(loaded,0));assertEquals("Original\nRoman",miniLyricText(loaded,900))
        assertEquals("Roman",miniLyricText(loaded.copy(romanMode=LyricsRomanMode.Romanized),900))
        assertNull(miniLyricText(loaded,2500))
        assertNull(miniLyricText(LyricsLoadState.Loaded(document.copy(lines=listOf(LyricLine(-1,-1,"Plain text")))),900))
        assertNull(miniLyricText(null,900))
    }
    @Test fun secureTtmlRejectsExternalEntitiesAndDeepNesting(){
        assertNull(parseTtml("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///C:/Windows/win.ini'>]><tt><body><p begin='0s' end='2s'>&x;</p></body></tt>",3000))
        val deep="<tt>"+"<div>".repeat(40)+"<p begin='0s' end='2s'>text</p>"+"</div>".repeat(40)+"</tt>"
        assertNull(parseTtml(deep,3000))
    }
    @Test fun lrcAndEnhancedLrcRetainOnlyActualWordTiming(){
        val plain=assertNotNull(parseLrc("[00:01.00]first\n[00:06.00]second",10000,"Local","file"))
        assertTrue(plain.timed);assertFalse(plain.wordTimed);assertFalse(withInstrumentalGaps(plain.lines).any{it.gap})
        val enhanced=assertNotNull(parseLrc("[00:01.00]<00:01.00>Hello <00:02.00>world<00:03.00>",5000,"Local","file"))
        assertTrue(enhanced.wordTimed);assertEquals(1000L,enhanced.lines.single().parts.first().startMs)
    }
    @Test fun heldWordAndBackingVocalPreventInventedInstrumentalGaps(){
        val held=LyricLine(1000,2000,"held",listOf(LyricPart(1000,7000,0,4)),LyricBacking(2000,8000,"back"),true)
        assertEquals(8000L,held.untilMs)
        assertFalse(withInstrumentalGaps(listOf(held,LyricLine(10000,12000,"next",knownEnd=true))).any{it.gap})
    }
    @Test fun hardwareClockFreezesPausedAndStaleEpochsDoNotDriveAnotherTrack(){
        val anchor=AudioEvent.ClockAnchor(1,4,1000,1_000_000_000,48000,48000,100f,currentEntryId="a")
        assertEquals(1120L,LyricsPresentationClock.position(anchor,"a",true,1_120_000_000,0,10000))
        assertEquals(1000L,LyricsPresentationClock.position(anchor,"a",false,1_120_000_000,0,10000))
        assertEquals(1250L,LyricsPresentationClock.position(anchor,"a",true,9_000_000_000,0,10000))
        assertEquals(3000L,LyricsPresentationClock.position(anchor,"b",true,1_120_000_000,3000,10000))
        assertEquals(700L,LyricsPresentationClock.position(anchor.copy(timelineEpoch=5,contentPositionMs=600),"a",true,1_100_000_000,3000,10000))
    }
    @Test fun localSidecarParsesPersistsAndRestoresWithoutNetwork():Unit=runBlocking{
        val root=kotlin.io.path.createTempDirectory("riff-lyrics-").toFile()
        try{
            val audio=File(root,"recorded.flac").apply{writeBytes(byteArrayOf(1))}
            val sidecar=File(root,"recorded.lrc").apply{writeText("[00:01.00]Actual local text\n[00:04.00]Second line")}
            val song=EmptyDesktopTrack.copy(id=audio.path,sourcePath=audio.path,durationMs=6000)
            SqliteLibraryRepository(File(root,"db")).use{store->
                val repo=SyncedLyricsRepository(store);val loaded=assertIs<LyricsLoadState.Loaded>(repo.load(song));assertEquals("Actual local text",loaded.document.lines.first().text)
                repo.preferences(song,300,true)
            }
            sidecar.delete()
            SqliteLibraryRepository(File(root,"db")).use{store->val loaded=assertIs<LyricsLoadState.Loaded>(SyncedLyricsRepository(store).load(song));assertEquals(300L,loaded.offsetMs);assertTrue(loaded.romanized);assertTrue(loaded.cached)}
        }finally{root.deleteRecursively()}
    }
}
