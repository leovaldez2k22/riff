package com.musicglass.desktop

import com.musicglass.data.*
import com.musicglass.data.encore.EncoreGenreArtist
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal val ENCORE_GENRES: Map<String, String> = linkedMapOf(
    "pop" to "Pop", "dance-pop" to "Pop", "synth-pop" to "Synth-pop", "electropop" to "Pop", "indie pop" to "Indie pop",
    "rock" to "Rock", "alternative rock" to "Alternative rock", "indie rock" to "Indie rock", "hard rock" to "Rock", "pop rock" to "Pop rock",
    "punk" to "Punk", "pop punk" to "Pop punk", "metal" to "Metal", "heavy metal" to "Metal", "nu metal" to "Metal",
    "hip hop" to "Hip hop", "hip-hop" to "Hip hop", "rap" to "Hip hop", "trap" to "Trap", "r&b" to "R&B", "rnb" to "R&B",
    "contemporary r&b" to "R&B", "soul" to "Soul", "funk" to "Funk", "disco" to "Disco",
    "electronic" to "Electronic", "edm" to "Electronic", "house" to "House", "deep house" to "House", "techno" to "Techno",
    "trance" to "Trance", "drum and bass" to "Drum and bass", "dubstep" to "Dubstep", "ambient" to "Ambient", "lo-fi" to "Lo-fi",
    "indie" to "Indie", "folk" to "Folk", "indie folk" to "Folk", "country" to "Country", "blues" to "Blues", "jazz" to "Jazz",
    "classical" to "Classical", "reggae" to "Reggae", "reggaeton" to "Reggaeton", "latin" to "Latin", "latin pop" to "Latin",
    "k-pop" to "K-pop", "j-pop" to "J-pop", "bollywood" to "Bollywood", "filmi" to "Bollywood", "soundtrack" to "Soundtrack",
    "afrobeats" to "Afrobeats", "gospel" to "Gospel", "singer-songwriter" to "Singer-songwriter")


internal sealed interface ArtistGenreMatch {
    data class Ready(val mbid:String,val genres:List<String>):ArtistGenreMatch
    data object NoMatch:ArtistGenreMatch
    data object Ambiguous:ArtistGenreMatch
}
internal interface GenreHttp:AutoCloseable { suspend fun read(uri:URI):JsonObject }
internal class MusicBrainzHttp:GenreHttp {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    val requests=AtomicLong()
    override suspend fun read(uri:URI):JsonObject=runInterruptible(Dispatchers.IO){
        require(uri.scheme=="https"&&uri.host=="musicbrainz.org"&&uri.userInfo==null&&uri.fragment==null&&uri.port==-1&&uri.path.startsWith("/ws/2/artist/"))
        val version=checkNotNull(DesktopBuildIdentity.version())
        val request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept","application/json")
            .header("User-Agent","RiffDesktop/$version (https://github.com/leovaldez2k22/riff)").GET().build()
        requests.incrementAndGet()
        val exchange=client.sendAsync(request,HttpResponse.BodyHandler<ByteArray>{BoundedMediaBody(1000000)})
        try{
            val response=exchange.get(20,TimeUnit.SECONDS)
            check(response.statusCode()==200){"MusicBrainz HTTP ${response.statusCode()}"}
            Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
        }finally{if(!exchange.isDone)exchange.cancel(true)}
    }
    override fun close(){client.shutdownNow()}
}
/** Exact unique artist identity is required; a high fuzzy score alone never assigns a genre. */
internal class MusicBrainzGenreClient(private val http:GenreHttp=MusicBrainzHttp()):AutoCloseable {
    companion object {private val requestGuard=Mutex();private var lastStart=0L}
    private suspend fun request(uri:URI,allowed:()->Boolean):JsonObject=requestGuard.withLock{
        val wait=1100000000L-(System.nanoTime()-lastStart)
        if(wait>0)delay((wait+999999)/1000000)
        check(allowed()){"Genre lookup is disabled or the connection is metered/unknown"}
        lastStart=System.nanoTime();http.read(uri)
    }
    suspend fun lookup(name:String,allowed:()->Boolean={true}):ArtistGenreMatch {
        require(name.length in 1..180&&name.none{it<' '})
        val phrase=name.replace("\\","\\\\").replace("\"","\\\"")
        val query=java.net.URLEncoder.encode("artist:\"$phrase\"",Charsets.UTF_8)
        val search=request(URI("https://musicbrainz.org/ws/2/artist/?query=$query&fmt=json&limit=10"),allowed)
        val matches=(search["artists"] as? JsonArray).orEmpty().mapNotNull{it as? JsonObject}.filter{
            it["name"]?.jsonPrimitive?.contentOrNull?.let(EncoreGenreCache::key)==EncoreGenreCache.key(name)
        }.distinctBy{it["id"]?.jsonPrimitive?.contentOrNull}
        if(matches.isEmpty())return ArtistGenreMatch.NoMatch
        if(matches.size!=1)return ArtistGenreMatch.Ambiguous
        val id=matches.single()["id"]?.jsonPrimitive?.contentOrNull?.takeIf{it.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))}?:return ArtistGenreMatch.NoMatch
        val answer=request(URI("https://musicbrainz.org/ws/2/artist/$id?fmt=json&inc=genres%2Btags"),allowed)
        check(answer["id"]?.jsonPrimitive?.contentOrNull==id&&answer["name"]?.jsonPrimitive?.contentOrNull?.let(EncoreGenreCache::key)==EncoreGenreCache.key(name)){"MusicBrainz artist identity changed"}
        val tags=((answer["genres"] as? JsonArray).orEmpty()+(answer["tags"] as? JsonArray).orEmpty()).mapNotNull{it as? JsonObject}
        val genres=tags.mapNotNull{tag->
            val key=tag["name"]?.jsonPrimitive?.contentOrNull?.lowercase(java.util.Locale.ROOT)?:return@mapNotNull null
            val canonical=ENCORE_GENRES[key]?:return@mapNotNull null
            canonical to (tag["count"]?.jsonPrimitive?.intOrNull?:0).coerceAtLeast(0)
        }.groupBy({it.first},{it.second}).mapValues{it.value.maxOrNull()?:0}.entries.sortedWith(compareByDescending<Map.Entry<String,Int>>{it.value}.thenBy{it.key}).take(3).map{it.key}
        return ArtistGenreMatch.Ready(id,genres)
    }
    override fun close()=http.close()
}
internal sealed interface GenreLookupState {
    data object Idle:GenreLookupState
    data object Off:GenreLookupState
    data object WaitingForUnmetered:GenreLookupState
    data class Updating(val checked:Int,val total:Int):GenreLookupState
    data class Ready(val checked:Int,val tagged:Int,val ambiguous:Int):GenreLookupState
    data class Failed(val message:String):GenreLookupState
}
/** Default-off network work. Scoped facts are cached only while the original history epoch still exists. */
internal class DesktopEncoreGenres(private val store:SqliteLibraryRepository,
    private val clientFactory:()->MusicBrainzGenreClient={MusicBrainzGenreClient()},
    private val networkCost:()->Boolean={WindowsNetworkCost.current().unmetered},
    private val now:()->Long=System::currentTimeMillis):AutoCloseable {
    private val cache=EncoreGenreCache(store)
    private val guard=Mutex();private val generation=AtomicLong()
    private var client:MusicBrainzGenreClient?=null
    private var retryAfter=0L
    private val mutableState=MutableStateFlow<GenreLookupState>(GenreLookupState.Idle)
    val state:StateFlow<GenreLookupState> = mutableState
    fun invalidate(){generation.incrementAndGet();mutableState.value=GenreLookupState.Idle}
    fun cached(scopes:List<String>,names:List<String>):Map<String,List<String>> = names.take(200).mapNotNull{name->
        val fact=scopes.filter{store.privacy(it).genreLookup}.mapNotNull{cache.get(it,name)}.filter{it.status=="Ready"&&it.fetchedMs>=now()-EncoreGenreCache.KEEP_MS}.maxByOrNull{it.fetchedMs}
        fact?.genres?.filter{it in ENCORE_GENRES.values}?.takeIf{it.isNotEmpty()}?.let{name to it}
    }.toMap()
    suspend fun refresh(artists:List<EncoreGenreArtist>){
        if(!guard.tryLock())return
        try{
            val token=generation.get()
            val candidates=artists.take(200).filter{it.name.length in 1..180&&it.name.none{c->c<' '}}.filter{it.scopes.any{scope->store.privacy(scope).genreLookup}}
            if(candidates.isEmpty()){mutableState.value=GenreLookupState.Off;return}
            if(now()<retryAfter)return
            fun allowed()=generation.get()==token&&candidates.any{it.scopes.any{scope->store.privacy(scope).genreLookup}}&&networkCost()
            if(!allowed()){mutableState.value=GenreLookupState.WaitingForUnmetered;return}
            var checked=0;var tagged=0;var ambiguous=0
            for(artist in candidates){
                currentCoroutineContext().ensureActive()
                if(generation.get()!=token)return
                val scopes=artist.scopes.filter{store.privacy(it).genreLookup}
                val epochs=scopes.associateWith{scope->store.queryHistoryEpoch(scope)}
                val stale=scopes.filter{scope->cache.get(scope,artist.name)?.fetchedMs?.let{it<now()-EncoreGenreCache.KEEP_MS}?:true}
                if(stale.isEmpty())continue
                if(!allowed()){mutableState.value=GenreLookupState.WaitingForUnmetered;return}
                mutableState.value=GenreLookupState.Updating(checked,candidates.size)
                val actual=client?:clientFactory().also{client=it}
                val match=actual.lookup(artist.name,::allowed)
                if(generation.get()!=token)return
                val result=when(match){is ArtistGenreMatch.Ready->{if(match.genres.isNotEmpty())tagged++;Triple(match.mbid,"Ready",match.genres)};ArtistGenreMatch.NoMatch->Triple(null,"NoMatch",emptyList());ArtistGenreMatch.Ambiguous->{ambiguous++;Triple(null,"Ambiguous",emptyList())}}
                for(scope in stale)cache.put(CachedArtistGenre(scope,artist.name,result.first,result.second,result.third,now()),epochs.getValue(scope))
                checked++
            }
            mutableState.value=GenreLookupState.Ready(checked,tagged,ambiguous)
        }catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){retryAfter=now()+60000;mutableState.value=GenreLookupState.Failed(error.message?.take(160)?:"Genre lookup unavailable")}
        finally{guard.unlock()}
    }
    override fun close(){generation.incrementAndGet();client?.close();client=null}
}
