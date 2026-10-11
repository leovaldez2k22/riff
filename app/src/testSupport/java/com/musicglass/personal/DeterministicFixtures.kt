package com.musicglass.personal

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.ArrayDeque

/** Policy time only. Never use this clock to measure real decoder progress. */
internal class FakeClock(var wall:Long=1_800_000_000_000L,var monotonic:Long=0L):AppClocks {
    override fun wallTimeMillis()=wall
    override fun monotonicMillis()=monotonic
    fun advance(millis:Long) {require(millis>=0);wall+=millis;monotonic+=millis}
}

internal data class FakeIdentity(val scope:String,val generation:Long,val verified:Boolean)
internal class FakeSession {
    var current=FakeIdentity("fixture-A",0,true);private set
    fun switchTo(scope:String,verified:Boolean=true):FakeIdentity {
        current=FakeIdentity(scope,current.generation+1,verified);return current
    }
}

internal class FakeCatalog {
    data class Request(val identity:FakeIdentity,val page:String,
        val response:CompletableDeferred<List<ProviderTrack>> = CompletableDeferred())
    private val requests=mutableListOf<Request>()
    @Synchronized fun enqueue(identity:FakeIdentity,page:String)=Request(identity,page).also {requests+=it}
    suspend fun load(request:Request):List<ProviderTrack> = request.response.await()
    @Synchronized fun recorded()=requests.toList()
}

internal enum class FixtureFailure { OFFLINE, FORBIDDEN, RATE_LIMITED, SERVER, AUTH_EXPIRED, MALFORMED }
internal class FixtureException(val failure:FixtureFailure):IOException("Synthetic fixture: $failure")

/** Responses are completed by the test, including in reverse order. No live provider fallback. */
internal class ScriptedResolver:StreamResolver {
    private val scripts=ArrayDeque<CompletableDeferred<Result<ProviderStream>>>()
    private val recorded=mutableListOf<StreamRequest>()
    @Synchronized fun enqueue()=CompletableDeferred<Result<ProviderStream>>().also {scripts.addLast(it)}
    fun fail(failure:FixtureFailure) {enqueue().complete(Result.failure(FixtureException(failure)))}
    @Synchronized fun requests()=recorded.toList()
    override suspend fun resolve(request:StreamRequest):Result<ProviderStream> {
        val response=synchronized(this) {
            recorded+=request
            check(scripts.isNotEmpty()) {"Unscripted resolver request"}
            scripts.removeFirst()
        }
        return response.await()
    }
}

internal fun fixtureStream(id:String="fixtureA001",expiresAt:Long=Long.MAX_VALUE)=ProviderStream(
    "http://127.0.0.1/fixtures/$id.wav",emptyMap(),"audio/wav",
    sourceVideoId=id,durationMs=30_000,expiresAtMs=expiresAt)
