package com.musicglass.desktop

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.*
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File

/** Public read-only provider session. No cookie/account writes are performed by this gateway. */
internal class DesktopProvider(private val configDirectory: File) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 45_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
        install(ContentNegotiation) { json(json) }
    }
    private val tube = InnerTube(client)
    val localeDescription get()=tube.locale.toString()
    private val config = object : PlayerConfigRepository {
        private val prefs = java.util.Properties().apply {
            val f = File(configDirectory, "player-config.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        private fun save() {
            configDirectory.mkdirs()
            val f = File(configDirectory, "player-config.properties")
            val temp = File(configDirectory, "player-config.properties.tmp")
            temp.outputStream().use { prefs.store(it, "Provider player configuration; no credentials") }
            java.nio.file.Files.move(temp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        override val enabled = true
        override val sourceUrl = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        override val defaultSourceUrl get() = sourceUrl
        override var cachedJson: String
            get() = prefs.getProperty("json", "")
            set(v) { prefs.setProperty("json", v); save() }
        override var cachedAtMs: Long
            get() = prefs.getProperty("at", "0").toLongOrNull() ?: 0
            set(v) { prefs.setProperty("at", v.toString()); save() }
        override var cachedSourceUrl: String
            get() = prefs.getProperty("source", "")
            set(v) { prefs.setProperty("source", v); save() }
        override var cachedEtag: String
            get() = prefs.getProperty("etag", "")
            set(v) { prefs.setProperty("etag", v); save() }
    }
    private val store = RemotePlayerConfigStore(client, config)
    private val cipher = YouTubeCipherService(client, store)
    private val extractor = InnerTubeExtractor(YtConfigParserImpl(client, tube, store), cipher, tube)
    suspend fun resolve(videoId: String): ExtractedStream = withTimeout(90_000) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        cipher.initialize()
        extractor.extract(videoId, hints = ContentHints(), audioQuality = AudioQuality.AUTO)
            ?: throw java.io.IOException("Provider returned no playable source")
    }
    suspend fun suggestions(query:String):SearchSuggestions=withContext(Dispatchers.IO){withTimeout(15000){
        require(query.length in 1..100)
        val response=tube.getSearchSuggestions(YouTubeClient.WEB_REMIX,query,false)
        check(response.status.value==200){"Suggestions HTTP ${response.status.value}"}
        val bytes=response.bodyAsChannel().toInputStream().use{it.readNBytes(1_000_001)}
        require(bytes.size<=1_000_000){"Suggestions response exceeds byte limit"}
        parseSearchSuggestions(json.parseToJsonElement(bytes.toString(Charsets.UTF_8)),query)
    }}
    suspend fun search(query: String): List<SongItem> = searchResults(query).tracks
    suspend fun searchResults(query: String): DesktopSearchResult = withTimeout(45_000) {
        require(query.length in 1..200)
        // Android also falls back to mixed search when the provider retires a static Songs filter.
        val response = tube.search(YouTubeClient.WEB_REMIX, query, setLogin = false)
        check(response.status.value == 200) { "Provider search HTTP ${response.status.value}" }
        val root = json.parseToJsonElement(response.bodyAsText())
        // Keep the primary endpoint's type: a collection's play button is not a song row.
        val music = try { parseDesktopSearchResult(root) }
            catch (_: ProviderUnavailableException) { DesktopSearchResult(emptyList()) }
        if (music.tracks.isNotEmpty()) music else music.copy(cards=(music.cards+searchPage(query).cards).distinctBy{it.key()})
    }

    suspend fun searchPage(query:String,cursor:String?=null):DesktopCatalogPage=withTimeout(45_000){
        require(query.length in 1..200 && (cursor?.length?:0)<=32768)
        val response=tube.search(YouTubeClient.WEB,query=query,continuation=cursor,setLogin=false)
        check(response.status.value==200){"Public video search HTTP ${response.status.value}"}
        val payload=json.parseToJsonElement(response.bodyAsText())
        (payload as? JsonObject)?.get("responseContext")?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull?.let{tube.visitorData=it}
        parseDesktopCatalogPage(payload)
    }
    suspend fun catalog(browseId:String="FEmusic_home",cursor:String?=null,params:String?=null):DesktopCatalogPage=withTimeout(45_000){
        require(browseId.length in 1..300 && (cursor?.length?:0)<=32768)
        val response=tube.browse(YouTubeClient.WEB_REMIX,browseId=if(cursor==null)browseId else null,params=if(cursor==null)params else null,continuation=cursor,setLogin=false)
        check(response.status.value==200){"Catalog HTTP ${response.status.value}"}
        val payload=json.parseToJsonElement(response.bodyAsText())
        (payload as? JsonObject)?.get("responseContext")?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull?.let{tube.visitorData=it}
        parseDesktopCatalogPage(payload)
    }
    suspend fun recommendations(videoId:String,cursor:String?=null):DesktopCatalogPage=withTimeout(45_000){
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        val response=tube.next(YouTubeClient.WEB_REMIX,videoId=videoId,playlistId="RDAMVM$videoId",params="wAEB",continuation=cursor)
        check(response.status.value==200){"Recommendations HTTP ${response.status.value}"}
        val payload=json.parseToJsonElement(response.bodyAsText())
        (payload as? JsonObject)?.get("responseContext")?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull?.let{tube.visitorData=it}
        parseDesktopCatalogPage(payload)
    }
    suspend fun openAudio(videoId: String): CachedByteSource {
        val stream = resolve(videoId)
        val duration = (stream.mediaMetadata?.durationSeconds ?: 0) * 1000L
        val directory = File(DesktopPaths.root, "audio-cache")
        return (stream.sabrBootstrap?.let { bootstrap ->
            GrowingMediaByteSource(directory, duration, com.metrolist.innertubex.sabr.SabrAudioStream(client, bootstrap).bytes(),reconnect={
                val renewed=resolve(videoId)
                check(renewed.itag==stream.itag&&renewed.codecs==stream.codecs&&renewed.mimeType==stream.mimeType){"Media representation changed during reconnect"}
                com.metrolist.innertubex.sabr.SabrAudioStream(client,checkNotNull(renewed.sabrBootstrap){"Media transport changed during reconnect"}).bytes()
            })
        } ?: HttpRangeByteSource(java.net.URI(stream.audioUrl), stream.headers, directory, duration,representationKey=representation(stream),refreshLease={
            val renewed=runBlocking{resolve(videoId)}
            check(renewed.sabrBootstrap==null){"Provider transport changed; reopen at content time"}
            MediaLease(java.net.URI(renewed.audioUrl),renewed.headers,representation(renewed))
        })).also{it.loudnessDb=validLoudnessDb(stream.loudnessDb)}
    }
    private fun representation(stream:ExtractedStream):String? {
        val uri=java.net.URI(stream.audioUrl)
        val parameters=uri.rawQuery.orEmpty().split('&').mapNotNull{val pair=it.split('=',limit=2);if(pair.size==2)pair[0] to java.net.URLDecoder.decode(pair[1],Charsets.UTF_8)else null}.groupBy({it.first},{it.second})
        val modified=parameters["lmt"]?.singleOrNull()?.takeIf{it.matches(Regex("[0-9]{1,24}"))}?:return null
        val length=stream.contentLengthBytes?.takeIf{it>0}?:return null
        return com.musicglass.data.SqliteLibraryRepository.digest("${stream.videoId}:${stream.itag}:${stream.mimeType}:${stream.codecs}:$length:$modified")
    }
    suspend fun captureResolvedStream(stream: ExtractedStream, destination: File): Long = withTimeout(150_000) {
        destination.parentFile.mkdirs()
        var bytes = 0L
        destination.outputStream().use { output ->
            val bootstrap = stream.sabrBootstrap
            if (bootstrap != null) {
                com.metrolist.innertubex.sabr.SabrAudioStream(client, bootstrap).bytes().collect { chunk ->
                    bytes += chunk.size
                    check(bytes <= 64L * 1024 * 1024) { "Provider probe exceeds capture limit" }
                    output.write(chunk)
                }
            } else withContext(Dispatchers.IO) {
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI(stream.audioUrl)).timeout(java.time.Duration.ofSeconds(120))
                stream.headers.forEach { (k,v) -> request.header(k,v) }
                val response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                    .send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                check(response.statusCode() in 200..299) { "Provider media HTTP ${response.statusCode()}" }
                response.body().use { input -> val buffer=ByteArray(65536); while(true) { val n=input.read(buffer); if(n<0) break; bytes+=n; check(bytes<=64L*1024*1024); output.write(buffer,0,n) } }
            }
        }
        bytes
    }
    override fun close() { tube.close(); client.close() }
}

/** Explicit read-only diagnostic; prints capabilities, never signed URLs or credentials. */
internal object DesktopProviderProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "Arguments: isolated state directory, video ID" }
        DesktopProvider(File(args[0])).use { provider ->
            val start = System.nanoTime()
            val stream = provider.resolve(args[1])
            println("RIFF_PROVIDER_RESOLVED codec=${stream.codecs} mime=${stream.mimeType} bytes=${stream.contentLengthBytes} sabr=${stream.sabrBootstrap != null} profile=${stream.profileId} elapsedMs=${(System.nanoTime()-start)/1_000_000}")
            val destination = File(args[0], "actual-provider-audio.webm")
            val count = provider.captureResolvedStream(stream, destination)
            println("RIFF_PROVIDER_CAPTURED bytes=$count")
        }
    }
}