package com.musicglass.personal

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import java.io.File

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal object MusicDiskCache {
    @Volatile private var instance:SimpleCache?=null
    fun get(context:Context):SimpleCache=instance ?: synchronized(this) {instance ?: SimpleCache(File(context.cacheDir,"audio-cache-v1"),LeastRecentlyUsedCacheEvictor(256L*1024*1024),StandaloneDatabaseProvider(context.applicationContext)).also {instance=it}}
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
    fun clear(context:Context) {val cache=get(context);cache.keys.toList().forEach {cache.removeResource(it)}}
}
