package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** A fixture from src/test/resources/quality/provider. Invented and generated, not recorded; see docs/quality/provider-compatibility.md. */
internal fun providerFixture(name:String):String =
    requireNotNull(ProviderWorld::class.java.getResourceAsStream("/quality/provider/$name")) {"No fixture $name"}.use {it.readBytes().toString(Charsets.UTF_8)}
internal fun providerFixtureJson(name:String):JsonElement = Json.parseToJsonElement(providerFixture(name))

/**
 * The real provider class on the pinned library, with the network replaced: every request it would send
 * is recorded and answered by the test. Nothing leaves the process and no account is involved; the
 * session fields are synthetic.
 */
internal class ProviderWorld(private val context:Context,signedIn:Boolean=true,sync:String="fixture-sync-A"):AutoCloseable {
    /** One request the provider made: the endpoint (the part after /youtubei/v1/) and its JSON body. */
    data class Call(val endpoint:String,val body:JsonObject,val query:Map<String,String> = emptyMap(),
        val host:String="",val headers:List<Pair<String,String>> = emptyList()) {
        fun field(name:String):String?=(body[name] as? JsonPrimitive)?.contentOrNull
        val browseId get()=field("browseId")
        /** The page token, wherever the library put it: the body for browse, the address for search. */
        val continuation get()=field("continuation")?:query["continuation"]?:query["ctoken"]
    }
    class Reply(val body:String,val status:Int=200,val failure:IOException?=null,val headers:Map<String,String> = emptyMap())
    val calls=CopyOnWriteArrayList<Call>()
    @Volatile var route:(Call)->Reply={error("Unscripted provider request: ${it.endpoint} ${it.body.keys}")}
    val clock=FakeClock()
    private val transport={OriginalAudioEngine.create {config {addInterceptor {chain->
        val request=chain.request()
        val text=Buffer().also {request.body?.writeTo(it)}.readUtf8()
        val call=Call(request.url.encodedPath.substringAfter("/youtubei/v1/",request.url.encodedPath),
            runCatching {Json.parseToJsonElement(text) as JsonObject}.getOrDefault(JsonObject(emptyMap())),
            request.url.queryParameterNames.associateWith {request.url.queryParameter(it).orEmpty()},
            request.url.host,request.headers.toList())
        calls+=call
        val reply=route(call)
        reply.failure?.let {throw it}
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("fixture")
            .apply {reply.headers.forEach {(name,value)->header(name,value)}}
            .body(reply.body.toResponseBody("application/json".toMediaType())).build()
    }}}}
    val runtime=RuntimeDependencies(
        networkConditions={NetworkConditions(true,true,true)},storageCapacity={StorageCapacity(1L shl 40,1L shl 41)},batteryPercent={80},
        httpCalls=OkHttpClient.Builder().addInterceptor {throw IOException("Unexpected outbound fixture request")}.build(),
        clocks=clock,dispatchers=AppDispatchers(Dispatchers.Unconfined,Dispatchers.Unconfined),
        providerTransport=transport,resolver=ScriptedResolver(),storedScopeKeys={emptySet()},startProviderBackgroundWork=false,wait={})
    val provider:YouTubeMusicProvider
    init {
        val edit=context.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear()
        CookieManager.getInstance().removeAllCookies(null)
        if(signedIn) {edit.putString("sync",sync).putString("account","0").putString("visitor","fixture-visitor").commit()
            CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")}
        else edit.putBoolean("signed_out",true).commit()
        provider=AppContainer(context,runtime).provider
    }
    fun endpoints()=calls.map {it.endpoint}
    override fun close() {
        runCatching {provider.close()}
        context.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear().putBoolean("signed_out",true).commit()
        CookieManager.getInstance().removeAllCookies(null)
    }
}

/** An 11-character invented video id, and one playlist row for it in the shape the collection parser reads. */
internal fun fixtureVideoId(n:Int)="fx%09d".format(n)
private fun fixtureRow(n:Int)="""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Row $n","navigationEndpoint":{"watchEndpoint":{"videoId":"${fixtureVideoId(n)}","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Fixture Artist"}]}}}],"playlistItemData":{"videoId":"${fixtureVideoId(n)}","playlistSetVideoId":"row-set-$n"}}}"""
/** A collection page holding rows [numbers], followed by the page [next] names (none when null). */
internal fun fixtureCollectionPage(numbers:Iterable<Int>,next:String?=null)=
    """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${numbers.joinToString(",",transform=::fixtureRow)}],"continuations":[${next?.let {"""{"nextContinuationData":{"continuation":"$it"}}"""}.orEmpty()}]}}}"""
