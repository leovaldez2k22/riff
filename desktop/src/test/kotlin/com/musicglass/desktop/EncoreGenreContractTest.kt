package com.musicglass.desktop

import com.musicglass.data.*
import com.musicglass.data.encore.EncoreGenreArtist
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Explicit metadata fixtures. Real MusicBrainz connectivity has a separate acceptance executable. */
class EncoreGenreContractTest {
    private val id="00000000-0000-0000-0000-000000000001"
    @Test fun exactUniqueIdentityAndVocabularyAreRequired()=runBlocking{
        var calls=0
        val http=object:GenreHttp{
            override suspend fun read(uri:java.net.URI):JsonObject{
                calls++
                return Json.parseToJsonElement(if(uri.path.endsWith("/$id"))"""{"id":"$id","name":"Artist","genres":[{"name":"electronic","count":9},{"name":"seen live","count":100}],"tags":[{"name":"house","count":4}]}"""else"""{"artists":[{"id":"$id","name":"Artist","score":100}]}""").jsonObject
            }
            override fun close(){}
        }
        MusicBrainzGenreClient(http).use{client->assertEquals(ArtistGenreMatch.Ready(id,listOf("Electronic","House")),client.lookup("Artist"))}
        assertEquals(2,calls)
    }
    @Test fun homonymsAndFuzzyMatchesNeverAcquireAnInventedGenre()=runBlocking{
        for(answer in listOf("""{"artists":[{"id":"$id","name":"Artist"},{"id":"00000000-0000-0000-0000-000000000002","name":"Artist"}]}""","""{"artists":[{"id":"$id","name":"Different artist","score":100}]}""")){
            var calls=0
            val http=object:GenreHttp{override suspend fun read(uri:java.net.URI):JsonObject{calls++;return Json.parseToJsonElement(answer).jsonObject};override fun close(){}}
            MusicBrainzGenreClient(http).use{client->val value=client.lookup("Artist");assertTrue(value==ArtistGenreMatch.NoMatch||value==ArtistGenreMatch.Ambiguous)}
            assertEquals(1,calls)
        }
    }
    @Test fun disabledOrMeteredLookupsDoNotEvenConstructTheNetworkClient()=runBlocking{
        val directory=kotlin.io.path.createTempDirectory("riff-genre-consent-").toFile()
        try{SqliteLibraryRepository(directory).use{store->
            var constructed=0
            DesktopEncoreGenres(store,clientFactory={constructed++;error("Network client must stay unused")},networkCost={false}).use{service->
                service.refresh(listOf(EncoreGenreArtist("Artist",listOf(Scopes.LOCAL))));assertEquals(0,constructed);assertEquals(GenreLookupState.Off,service.state.value)
                store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(genreLookup=true))
                service.refresh(listOf(EncoreGenreArtist("Artist",listOf(Scopes.LOCAL))));assertEquals(0,constructed);assertEquals(GenreLookupState.WaitingForUnmetered,service.state.value)
            }
        }}finally{directory.deleteRecursively()}
    }
}
