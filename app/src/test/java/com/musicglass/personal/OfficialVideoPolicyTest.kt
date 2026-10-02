package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test
class OfficialVideoPolicyTest {
    private val track=ProviderTrack("5qZQEq_C3vc","Numb","Linkin Park",artistId="UCartist")
    @Test fun requiresOfficialEvidenceAndArtistOwnership() {
        assertTrue(officialVideoMatches(track,"Linkin Park - Numb (Official Music Video)","Linkin Park",officialEvidence=true))
        assertFalse(officialVideoMatches(track,"Numb Official Music Video","Fan Channel",officialEvidence=true))
        assertFalse(officialVideoMatches(track,"Numb","Linkin Park",officialEvidence=false))
        assertFalse(officialVideoMatches(track,"Numb","Linkin Park - Topic",officialEvidence=true))
    }
    @Test fun rejectsLyricsAudioAndDifferentRecordingsEvenFromArtist() {
        for(title in listOf("Numb (Official Lyric Video)","Numb (Lyrics)","Numb (Official Audio)","Numb Visualizer","Numb (Live)","Numb slowed", "Somewhere I Belong", "Numb / Encore (Official Music Video)", "Numb (Remix)", "Numb (2017 One More Light World Tour - Amsterdam)")) assertFalse(title,officialVideoMatches(track,title,"Linkin Park",officialEvidence=true))
    }
    @Test fun acceptsPresentationLabelsButNotLongerSongNames() {
        assertTrue(officialVideoMatches(track,"Numb (Official Music Video) [4K Upgrade] - Linkin Park","Linkin Park",officialEvidence=true))
        assertFalse(officialVideoMatches(track,"Numb Again","Linkin Park",officialEvidence=true))
        assertFalse(officialVideoMatches(track,"Numb/Encore","Linkin Park",officialEvidence=true))
    }
    @Test fun acceptsVevoAndCanonicalArtistChannel() {
        assertTrue(officialVideoMatches(track,"Numb","LinkinParkVEVO",officialEvidence=true))
        assertTrue(officialVideoMatches(track,"Numb","Linkin Park Official","UCartist",true))
    }
}
