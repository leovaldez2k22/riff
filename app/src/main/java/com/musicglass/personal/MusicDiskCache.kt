package com.musicglass.personal

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal object MusicDiskCache {
    @Volatile private var instance:SimpleCache?=null
    fun get(context:Context):SimpleCache=instance ?: synchronized(this) {instance ?: File(context.cacheDir,"audio-cache-v1").let {dir->SimpleCache(dir,FreeSpaceCacheEvictor(dir),StandaloneDatabaseProvider(context.applicationContext))}.also {instance=it}}
    fun audioFactory(context:Context,upstream:androidx.media3.datasource.DataSource.Factory):androidx.media3.datasource.cache.CacheDataSource.Factory {
        val cache=get(context)
        return androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory {
                val sink=androidx.media3.datasource.cache.CacheDataSink.Factory().setCache(cache).createDataSink()
                object:androidx.media3.datasource.DataSink {
                    override fun open(spec:androidx.media3.datasource.DataSpec) {
                        // Progressive loading marks unknown lengths as uncacheable. SABR songs have a
                        // finite end even though their adapter cannot know the byte length up front.
                        val safe=if(spec.uri.scheme=="sabr") spec.buildUpon().setFlags(spec.flags and androidx.media3.datasource.DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()).build() else spec
                        sink.open(safe)
                    }
                    override fun write(buffer:ByteArray,offset:Int,length:Int)=sink.write(buffer,offset,length)
                    override fun close()=sink.close()
                }
            }
    }
    @Volatile private var indexInstance:CachedStreamIndex?=null
    fun index(context:Context):CachedStreamIndex=indexInstance ?: synchronized(this) {indexInstance ?: CachedStreamIndex(File(context.cacheDir,"audio-cache-index-v1.tsv")).also {indexInstance=it}}
    /** What can be played of a song before looking it up, or null. An entry whose bytes the cache has let go is forgotten. */
    fun playable(context:Context,scope:String,videoId:String,quality:String):Pair<CachedStreamEntry,CachedPart>? {
        val index=index(context);val entry=index.get(scope,videoId,quality) ?: return null
        val cache=get(context)
        val known=androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(entry.cacheKey))
        val part=cachedPart(entry,known) {position,length->cache.isCached(entry.cacheKey,position,length)}
        if(part==null) index.remove(scope,videoId,quality)
        return part?.let {entry to it}
    }
    fun holdsAround(context:Context,entry:CachedStreamEntry,positionMs:Long,durationMs:Long):Boolean {
        val length=entry.contentLength ?: return false
        return cachedAround(positionMs,durationMs,length) {position,bytes->get(context).isCached(entry.cacheKey,position,bytes)}
    }
    /**
     * Copies a streamed song the cache holds whole into [into], for Sing (owner, 9 November 2026). False when not all
     * of it is here yet, or it could not be read; nothing is fetched.
     */
    fun copyWhole(context:Context,scope:String,videoId:String,quality:String,into:File):Boolean {
        val (entry,part)=playable(context,scope,videoId,quality) ?: return false
        if(part!=CachedPart.Whole) return false
        val cache=get(context)
        val length=androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(entry.cacheKey)).takeIf {it>0} ?: entry.contentLength ?: return false
        if(!cache.isCached(entry.cacheKey,0,length)) return false
        val source=androidx.media3.datasource.cache.CacheDataSource(cache,null)
        return try {
            source.open(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.EMPTY).setKey(entry.cacheKey).setPosition(0).setLength(length).build())
            var done=0L
            into.outputStream().buffered(1 shl 16).use {out->val buffer=ByteArray(1 shl 16);while(true) {val n=source.read(buffer,0,buffer.size);if(n<0) break;out.write(buffer,0,n);done+=n}}
            done==length
        } catch(_:java.io.IOException) {false} finally {try {source.close()} catch(_:java.io.IOException) {}}
    }
    fun clear(context:Context) {val cache=get(context);cache.keys.toList().forEach {cache.removeResource(it)};index(context).clear()}
}

/** What the music cache and Sing's store leave free on the phone (owner, 9 November 2026: no size cap, only this). */
internal const val CACHE_KEEP_FREE_BYTES = 2L * 1024 * 1024 * 1024

/**
 * The music cache's evictor: no size limit (owner, 9 November 2026), but the oldest-played audio goes first whenever
 * the phone would have less than [CACHE_KEEP_FREE_BYTES] free, so the cache can never fill it. Least recently used,
 * as Media3's own size-limited evictor orders it.
 */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class FreeSpaceCacheEvictor(private val dir:File,private val keepFree:Long=CACHE_KEEP_FREE_BYTES,
    private val free:()->Long={dir.usableSpace}):androidx.media3.datasource.cache.CacheEvictor {
    private val spans=java.util.TreeSet<androidx.media3.datasource.cache.CacheSpan> {a,b->
        if(a.lastTouchTimestamp==b.lastTouchTimestamp) a.compareTo(b) else a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)}
    override fun requiresCacheSpanTouches()=true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache:androidx.media3.datasource.cache.Cache,key:String,position:Long,length:Long) {evict(cache,maxOf(length,0L))}
    override fun onSpanAdded(cache:androidx.media3.datasource.cache.Cache,span:androidx.media3.datasource.cache.CacheSpan) {spans.add(span);evict(cache,0)}
    override fun onSpanRemoved(cache:androidx.media3.datasource.cache.Cache,span:androidx.media3.datasource.cache.CacheSpan) {spans.remove(span)}
    override fun onSpanTouched(cache:androidx.media3.datasource.cache.Cache,oldSpan:androidx.media3.datasource.cache.CacheSpan,newSpan:androidx.media3.datasource.cache.CacheSpan) {
        onSpanRemoved(cache,oldSpan);onSpanAdded(cache,newSpan)}
    private fun evict(cache:androidx.media3.datasource.cache.Cache,needed:Long) {
        while(spans.isNotEmpty() && free()-needed<keepFree) cache.removeSpan(spans.first())
    }
}
