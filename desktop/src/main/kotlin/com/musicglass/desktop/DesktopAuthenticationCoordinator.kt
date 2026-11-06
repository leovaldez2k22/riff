package com.musicglass.desktop

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.*
import java.time.Duration
import java.util.Base64
import java.security.SecureRandom
import java.security.MessageDigest
import com.sun.net.httpserver.HttpServer

/** Documented YouTube Data API grant only. This is deliberately not an InnerTube cookie login. */
internal class DesktopAuthenticationCoordinator(private val directory:File,private val accounts:AccountSessionCoordinator){
    private val vault=DpapiCredentialVault(File(directory,"credentials"))
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val configuration get()=File(directory,"desktop-oauth.json")
    val configured get()=configuration.isFile
    private var activeScope:String?=null
    private fun settings():JsonObject {
        require(configuration.isFile&&configuration.length()<=65536){"Configure a Google Desktop OAuth client in desktop-oauth.json first"}
        val root=Json.parseToJsonElement(configuration.readText()).jsonObject
        return root["installed"]?.jsonObject?:error("Only a Desktop OAuth client configuration is supported")
    }
    suspend fun signIn():AccountToken=withContext(Dispatchers.IO){
        val expected=accounts.token.value
        val config=settings();val client=config.getValue("client_id").jsonPrimitive.content
        require(client.endsWith(".apps.googleusercontent.com"))
        val state=random();val verifier=random();val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val code=CompletableDeferred<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),1)
        val callback="http://127.0.0.1:${server.address.port}/oauth/callback"
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor{r->Thread(r,"RiffOAuthCallback").apply{isDaemon=true}}
        server.executor=executor
        server.createContext("/oauth/callback"){exchange->
            try{
                require(exchange.requestMethod=="GET"&&exchange.requestURI.path=="/oauth/callback")
                require(exchange.requestURI.rawQuery.orEmpty().length<=16384)
                val params=exchange.requestURI.rawQuery.orEmpty().split('&').filter{it.isNotEmpty()}.map{val pair=it.split('=',limit=2);decode(pair[0]) to decode(pair.getOrElse(1){""})}
                require(params.size<=10&&params.map{it.first}.distinct().size==params.size)
                val values=params.toMap();require(MessageDigest.isEqual(state.toByteArray(),values["state"].orEmpty().toByteArray())){"OAuth state mismatch"}
                values["error"]?.let{error("Provider declined authorization")}
                val value=values["code"]?.takeIf{it.length in 1..8192}?:error("Missing authorization code")
                if(code.complete(value)){val message="Riff authorization received. You may close this tab.".toByteArray();exchange.sendResponseHeaders(200,message.size.toLong());exchange.responseBody.use{it.write(message)}}else exchange.sendResponseHeaders(409,-1)
            }catch(_:Exception){exchange.sendResponseHeaders(400,-1)}finally{exchange.close()}
        }
        try{
            server.start()
            val query=form(mapOf("client_id" to client,"redirect_uri" to callback,"response_type" to "code","scope" to "https://www.googleapis.com/auth/youtube.readonly","state" to state,"code_challenge" to challenge,"code_challenge_method" to "S256","access_type" to "offline","prompt" to "consent"))
            java.awt.Desktop.getDesktop().browse(URI("https://accounts.google.com/o/oauth2/v2/auth?$query"))
            val authorization=withTimeout(180000){code.await()}
            val grant=linkedMapOf("client_id" to client,"code" to authorization,"code_verifier" to verifier,"redirect_uri" to callback,"grant_type" to "authorization_code")
            config["client_secret"]?.jsonPrimitive?.contentOrNull?.let{grant["client_secret"]=it}
            val tokens=postToken(grant)
            val access=tokens.getValue("access_token").jsonPrimitive.content
            val channel=channel(access)
            val refresh=tokens["refresh_token"]?.jsonPrimitive?.contentOrNull?:error("Provider supplied no renewable session; authorize again")
            check(accounts.isCurrent(expected)){"Account changed during authorization"}
            val scope=accounts.scopeFor(channel)
            val bytes=refresh.toByteArray();try{vault.store(scope,"refresh",bytes)}finally{bytes.fill(0)}
            val token=accounts.transitionVerified(channel)
            activeScope=token.scope
            val pointer=token.scope.toByteArray();try{vault.store("guest","active",pointer)}finally{pointer.fill(0)}
            token
        }finally{server.stop(0);executor.shutdownNow()}
    }
    suspend fun signOut(){accounts.signOut();activeScope?.let{vault.delete(it,"refresh")};vault.delete("guest","active");activeScope=null}
    suspend fun restoreSession():AccountToken?=withContext(Dispatchers.IO){
        if(!configured)return@withContext null
        val pointer=vault.load("guest","active")?:return@withContext null
        val scope=try{String(pointer)}finally{pointer.fill(0)}
        com.musicglass.data.Scopes.validate(scope);require(scope.startsWith("account_"))
        val secret=vault.load(scope,"refresh")?:return@withContext null
        val config=settings();val expected=accounts.token.value
        val grant=linkedMapOf("client_id" to config.getValue("client_id").jsonPrimitive.content,"refresh_token" to String(secret),"grant_type" to "refresh_token");secret.fill(0)
        config["client_secret"]?.jsonPrimitive?.contentOrNull?.let{grant["client_secret"]=it}
        val access=postToken(grant).getValue("access_token").jsonPrimitive.content
        val channel=channel(access);check(accounts.scopeFor(channel)==scope){"Stored session belongs to a different channel; sign in again"}
        check(accounts.isCurrent(expected)){"Account changed while restoring session"}
        accounts.transitionVerified(channel).also{activeScope=it.scope}
    }
    suspend fun readOnlyLibrary(path:String,parameters:Map<String,String> = emptyMap()):JsonObject=withContext(Dispatchers.IO){
        require(path in setOf("playlists","playlistItems","subscriptions","channels"))
        val scope=checkNotNull(activeScope){"Sign in first"};val expected=accounts.token.value;check(expected.scope==scope)
        val config=settings();val secret=checkNotNull(vault.load(scope,"refresh")){"Session expired; sign in again"}
        val grant=linkedMapOf("client_id" to config.getValue("client_id").jsonPrimitive.content,"refresh_token" to String(secret),"grant_type" to "refresh_token")
        secret.fill(0);config["client_secret"]?.jsonPrimitive?.contentOrNull?.let{grant["client_secret"]=it}
        val access=postToken(grant).getValue("access_token").jsonPrimitive.content
        val query=form(parameters+mapOf("part" to if(path=="playlistItems")"snippet,contentDetails"else"snippet","maxResults" to "50"))
        val result=get("https://www.googleapis.com/youtube/v3/$path?$query",access)
        check(accounts.isCurrent(expected)){"Account changed while loading"};result
    }
    private fun channel(access:String):String=get("https://www.googleapis.com/youtube/v3/channels?part=id&mine=true",access)["items"]?.jsonArray?.singleOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content?:error("A single verified YouTube channel is required")
    private fun get(url:String,access:String):JsonObject {
        val request=HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer $access").GET().build()
        return response(request)
    }
    private fun postToken(values:Map<String,String>):JsonObject=response(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(25)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(values))).build())
    private fun response(request:HttpRequest):JsonObject {
        val response=http.send(request,HttpResponse.BodyHandler<ByteArray>{BoundedMediaBody(4L*1024*1024)})
        check(response.statusCode()==200){"Provider authorization request failed (${response.statusCode()}); sign in again"}
        return Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
    }
    companion object {
        private fun random()=ByteArray(32).also{SecureRandom().nextBytes(it)}.let{Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
        private fun form(values:Map<String,String>)=values.entries.joinToString("&"){encode(it.key)+"="+encode(it.value)}
        private fun encode(value:String)=java.net.URLEncoder.encode(value,Charsets.UTF_8)
        private fun decode(value:String)=java.net.URLDecoder.decode(value,Charsets.UTF_8)
    }
}
