package com.musicglass.personal

import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal fun feedWindow(now:ZonedDateTime):Instant {
    val date=now.toLocalDate();val hour=now.hour
    return (if(hour<6) date.minusDays(1).atTime(18,0) else date.atTime(if(hour<18) 6 else 18,0)).atZone(now.zone).toInstant()
}
internal fun nextFeedRefresh(now:ZonedDateTime):Instant =
    (when {now.hour<6->now.toLocalDate().atTime(6,0);now.hour<18->now.toLocalDate().atTime(18,0);else->now.toLocalDate().plusDays(1).atTime(6,0)}).atZone(now.zone).toInstant()

/** Process-owned responses and shared requests. Leaving a composition never cancels its fetch. */
internal class PageMemoryCache(private val scope:CoroutineScope,private val scheduled:Boolean=true,
    private val retain:(String)->Boolean={false},private val clock:()->ZonedDateTime={ZonedDateTime.now()}) {
    private val values=linkedMapOf<String,Any>();private val flights=mutableMapOf<String,Deferred<Any>>()
    private var window=feedWindow(clock());private var zone=clock().zone
    val revision=MutableStateFlow(0L)
    init {if(scheduled) scope.launch {while(isActive) {
        val now=clock();delay(Duration.between(now.toInstant(),nextFeedRefresh(now)).toMillis().coerceIn(1,60_000));checkWindow()
    }}}
    @Synchronized fun checkWindow() {
        if(!scheduled) return
        val now=clock();val current=feedWindow(now)
        if(current!=window || zone!=now.zone) {window=current;zone=now.zone;clear()}
    }
    @Synchronized fun clear() {values.clear();flights.values.forEach {it.cancel()};flights.clear();revision.value++}
    @Synchronized fun invalidate(prefix:String) {values.remove(prefix);flights.remove(prefix)?.cancel()}
    @Synchronized fun put(key:String,value:Any) {values[key]=value;while(values.size>96) {val keyToEvict=values.keys.firstOrNull {it!=key && !retain(it)}?:break;values.remove(keyToEvict)}}
    @Suppress("UNCHECKED_CAST") @Synchronized fun <T> peek(key:String):T? {checkWindow();return values[key] as? T}
    @Suppress("UNCHECKED_CAST") suspend fun <T:Any> get(key:String,load:suspend ()->T):T {
        val job=synchronized(this) {
            checkWindow();(values[key] as? T)?.let {return it}
            val generation=revision.value
            flights[key]?:scope.async(start=CoroutineStart.LAZY) {
                val value=load()
                ensureActive()
                synchronized(this@PageMemoryCache) {if(revision.value!=generation) throw CancellationException("Cache window changed");put(key,value)}
                value as Any
            }.also {created->flights[key]=created;created.invokeOnCompletion {synchronized(this) {if(flights[key]===created) flights.remove(key)}};created.start()}
        }
        return job.await() as T
    }
}
internal data class HomeMood(val title:String,val browseId:String,val params:String?)
