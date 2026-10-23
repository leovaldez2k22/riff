package com.musicglass.personal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountProfileTest {
    @Test fun playbackLabelsSkipCountsBeforeTheCreatorAndRelativeDates() {
        assertEquals("Podcast Channel",playerArtistLabel("2.3M views • 2 days ago • Podcast Channel"))
        assertEquals("",playerArtistLabel("230K views · 2 days ago"))
        assertEquals("Views from the Bridge",playerArtistLabel("Views from the Bridge"))
    }
    @Test fun resolvedPodcastAuthorRepairsOldQueueMetadataWithoutChangingMusicArtists() {
        val stream=ProviderStream("https://example.test/audio",emptyMap(),"audio/mp4",resolvedAuthor="Actual Channel",resolvedChannelId="UC_actual")
        val podcast=ProviderTrack("video000001","Episode","230K views",isMusic=false)
        assertEquals("Actual Channel",resolvedPlaybackTrack(podcast,stream).artist)
        assertEquals("UC_actual",resolvedPlaybackTrack(podcast,stream).artistId)
        assertEquals("Singer",resolvedPlaybackTrack(podcast.copy(artist="Singer",isMusic=true),stream).artist)
        assertEquals("Actual Channel",resolvedPlaybackTrack(podcast.copy(isMusic=true),stream).artist)
        assertEquals("Existing Channel",resolvedPlaybackTrack(podcast.copy(artist="Existing Channel"),stream.copy(resolvedAuthor="")).artist)
    }
    @Test fun structuredActiveAccountHeaderUsesItsOwnNameAndGooglePhoto() {
        val root = Json.parseToJsonElement("""{
            "unrelatedCover":{"url":"https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg","width":2000,"height":2000},
            "actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{
                "activeAccountHeaderRenderer":{
                    "accountName":{"runs":[{"text":"Alex "},{"text":"River"}]},
                    "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/account=s88-c","width":88,"height":88}]}
                }
            }}}}}]
        }""")
        val profile = parseAccountProfile(root)!!
        assertEquals("Alex River", profile.name)
        assertEquals("https://yt3.ggpht.com/account=s88-c", profile.artworkUrl)
        assertEquals("https://yt3.ggpht.com/account=s1024-c", highQualityArtworkUrl(profile.artworkUrl!!))
    }

    @Test fun accountSwitcherOnlyUsesTheSelectedAccount() {
        val root = Json.parseToJsonElement("""{"contents":[
            {"accountItemRenderer":{"isSelected":false,"accountName":{"simpleText":"Another Person"},
                "accountPhoto":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/other=s88"}]}}},
            {"accountItemRenderer":{"isSelected":true,"accountName":{"simpleText":"Current Person"},
                "accountPhoto":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/current=s88"}]}}}
        ]}""")
        assertEquals("Current Person", parseAccountProfile(root)!!.name)
        assertEquals("https://lh3.googleusercontent.com/current=s88", parseAccountProfile(root)!!.artworkUrl)
        assertNull(parseAccountProfile(Json.parseToJsonElement("""{"accountItemRenderer":{
            "isSelected":false,"accountName":{"simpleText":"Another Person"}}}""")))
    }

    @Test fun missingProfileNeverUsesAnEmailOrAnUnrelatedMusicThumbnail() {
        assertNull(parseAccountProfile(Json.parseToJsonElement("""{
            "accountHeaderRenderer":{"accountName":{"simpleText":"person@example.com"}},
            "music":{"url":"https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"}
        }""")))
    }

    @Test fun playerArtistDoesNotIncludeAlbumDurationOrProvider() {
        assertEquals("Djo", playerArtistLabel("Song \u2022 Djo"))
        assertEquals("Duo", playerArtistLabel("Video \u2022 Duo \u2022 3:48"))
        assertEquals("Shaggy", playerArtistLabel("Shaggy • Hot Shot • 3:48"))
        assertEquals("Tenno", playerArtistLabel("Tenno • 200K views · YouTube Music"))
        assertEquals("Artist One & Artist Two", playerArtistLabel("Artist One & Artist Two · 1:03:12 · YouTube Music"))
        assertEquals("", playerArtistLabel("3:48 · YouTube Music"))
        assertEquals("Artist", playerArtistLabel("YouTube Music · Artist"))
    }

    /** Q18-040: the owner's account menu carried no address; the handle is shown instead and nothing is made up. */
    @Test fun anAccountWithoutAnAddressKeepsItsHandleAndNoEmail() {
        val profile = parseAccountProfile(Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{
            "accountName":{"runs":[{"text":"Alex River"}]},"channelHandle":{"runs":[{"text":"@alexriver"}]}}}"""))!!
        assertNull(profile.email); assertEquals("@alexriver", profile.handle)
        val both = parseAccountProfile(Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{
            "accountName":{"simpleText":"Alex River"},"email":{"simpleText":"alex.river@example.com"},"channelHandle":{"simpleText":"@alexriver"}}}"""))!!
        assertEquals("alex.river@example.com", both.email)
        // Something that is not a handle is left out.
        assertNull(parseAccountProfile(Json.parseToJsonElement("""{"activeAccountHeaderRenderer":{
            "accountName":{"simpleText":"Alex River"},"channelHandle":{"simpleText":"alex river"}}}"""))!!.handle)
    }
}
