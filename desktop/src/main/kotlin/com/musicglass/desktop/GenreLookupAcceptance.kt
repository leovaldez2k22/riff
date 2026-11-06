package com.musicglass.desktop

import com.musicglass.data.*
import com.musicglass.data.encore.EncoreGenreArtist
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Real Windows COM, actual HTTPS metadata and real scoped SQLite cache; not playback or visual acceptance. */
internal object GenreLookupAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()}
        val cost=WindowsNetworkCost.current()
        var requests=0L;var facts:List<String> = emptyList()
        fun report(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("scope","Actual Windows network cost + public MusicBrainz metadata + scoped SQLite; not playback/UI")
            put("networkCostFlags",cost.flags?.let(::JsonPrimitive)?:JsonNull);put("unmetered",cost.unmetered);put("artist","Daft Punk");put("httpsRequests",requests);put("genres",JsonArray(facts.map(::JsonPrimitive)))
            error?.let{put("error",it.take(200))}
        }.toString())}
        if(!cost.unmetered){report("DEFERRED",cost.error?:"Metered or unknown network cost");return@runBlocking}
        try{SqliteLibraryRepository(profile).use{store->
            val history=HistoryRepository(store)
            MusicBrainzHttp().use{http->DesktopEncoreGenres(store,clientFactory={MusicBrainzGenreClient(http)}).use{service->
                val artists=listOf(EncoreGenreArtist("Daft Punk",listOf(Scopes.LOCAL)))
                service.refresh(artists);check(http.requests.get()==0L)
                store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(genreLookup=true))
                service.invalidate();service.refresh(artists);requests=http.requests.get()
                val result=service.state.value;check(result is GenreLookupState.Ready){"Actual genre lookup: $result"}
                facts=service.cached(listOf(Scopes.LOCAL),listOf("Daft Punk"))["Daft Punk"].orEmpty();check(facts.isNotEmpty())
                check(EncoreGenreCache(store).get(Scopes.GUEST,"Daft Punk")==null)
                service.refresh(artists);check(http.requests.get()==requests){"Fresh cache made another network request"}
                store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(genreLookup=false));service.invalidate()
                check(service.cached(listOf(Scopes.LOCAL),listOf("Daft Punk")).isEmpty());service.refresh(artists);check(http.requests.get()==requests)
                history.clear(Scopes.LOCAL);check(EncoreGenreCache(store).get(Scopes.LOCAL,"Daft Punk")==null)
            }}
        };report("PASS")}catch(error:Throwable){report("FAIL",error.message);throw error}
    }
}
