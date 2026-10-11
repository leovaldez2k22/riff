package com.musicglass.personal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs

@OptIn(ExperimentalSabrApi::class)
internal object OfflineDownloadEngine {
    private val gate=Mutex()
    suspend fun run(context:Context,automatic:Boolean,
        dependencies:RuntimeDependencies=AppContainer.get(context).runtime,progress:(String)->Unit):Boolean=withContext(dependencies.dispatchers.io) {
        val store=OfflineMusicStore.get(context)
        if(automatic && !automaticConditionsAllowed(context,store,dependencies)) return@withContext false
        if(!gate.tryLock()) return@withContext false
        var ownedProvider:YouTubeMusicProvider?=null
        var engineResource:io.ktor.client.engine.HttpClientEngine?=null
        var clientResource:HttpClient?=null
        try {
            val process=AppContainer.get(context)
            val container=if(dependencies===process.runtime) process else AppContainer(context,dependencies)
            val scope=store.currentScope;val owner=process.sessions.capture();val provider by lazy {container.provider.also {if(container!==process) ownedProvider=it}}
            val resolver:StreamResolver=dependencies.resolver?:provider
            val engine=dependencies.providerTransport().also {engineResource=it}
            val http=HttpClient(engine).also {clientResource=it}
            if(automatic && store.smartEnabled && !store.playbackBusy) plan(context,store,scope,dependencies,provider)
            val jobs=store.rows(scope).filter {it.state in setOf("queued","failed") && (if(automatic) !it.pinned else it.pinned)}.sortedByDescending {it.priority}
            for(job in jobs) {
                ensureActive()
                if(!process.sessions.owns(owner) || scope!=store.currentScope) break
                if(automatic && (!store.smartEnabled || store.playbackBusy)) return@withContext false
                val id=job.track.videoId
                if(store.song(scope,id)?.state !in setOf("queued","failed")) continue
                val dir=store.directory(scope,id).apply {mkdirs()};val raw=File(dir,"audio.part");val assembled=File(dir,"assembled.part")
                var nextConditionsCheck=0L
                fun checkState() {
                    if(!process.sessions.owns(owner) || scope!=store.currentScope || store.song(scope,id)?.state !in setOf("downloading","assets")) throw CancellationException("Download cancelled")
                    if(automatic && (!store.smartEnabled || store.playbackBusy)) throw CancellationException("Automatic download paused")
                    val now=dependencies.clocks.monotonicMillis()
                    if(automatic && now>=nextConditionsCheck) {
                        nextConditionsCheck=now+1000
                        if(!automaticConditionsAllowed(context,store,dependencies)) throw CancellationException("Download conditions changed")
                    }
                }
                var phase="resolve"
                try {
                    if(!store.beginDownload(scope,id)) {store.discardUnlisted(scope,id);continue}
                    checkState();progress("Downloading ${job.track.title}")
                    if(store.audio(scope,id)==null) {
                        var stream=resolver.resolve(StreamRequest(id,download=true,quality=com.metrolist.innertubex.extraction.AudioQuality.HIGH)).getOrThrow()
                        val expected=stream.contentLength?:((stream.durationMs.takeIf {it>0}?:job.durationMs)*maxOf(stream.bitrate,320_000)/8000)
                        val stats=dependencies.storageCapacity(store.root)
                        if(!downloadFits(store.usedBytes(),expected,store.budgetBytes,stats.availableBytes,stats.totalBytes)) throw IOException("Storage limit reached. Free space or increase the download budget.")
                        require(stream.mimeType?.contains("mpegurl",true)!=true) {"No complete downloadable audio is available"}
                        val quality="${if(stream.bitrate>0) "${stream.bitrate/1000} kbps \u00B7 " else ""}${stream.mimeType?.substringBefore(';')?:"audio"} \u00B7 Highest available"
                        store.update(scope,id,"quality" to quality,"total" to (stream.contentLength?:0L),"duration" to (stream.durationMs.takeIf {it>0}?:job.durationMs))
                        // A failed/expired transfer gets a fresh exact-ID URL. SABR restarts safely instead of appending duplicate initialization.
                        var downloaded=false
                        val rejected=linkedSetOf<String>()
                        for(attempt in 0..2) {
                            checkState();ensureActive();raw.delete();store.update(scope,id,"bytes" to 0L)
                            try {
                                var bytes=0L;var lastProgress=0L
                                phase="transfer"
                                raw.outputStream().use {out->
                                    if(stream.sabrBootstrap!=null) {
                                        var nextRange=0L
                                        SabrAudioStream(http,stream.sabrBootstrap!!).chunks().collect {chunk->
                                            checkState();ensureActive();check(chunk.startRange==nextRange) {"Non-contiguous audio transfer"}
                                            out.write(chunk.data);nextRange=chunk.endRangeExclusive;bytes=nextRange
                                            if(bytes-lastProgress>256_000) {store.update(scope,id,"bytes" to bytes);lastProgress=bytes}
                                            if(bytes>512L*1024*1024) throw IOException("Song exceeds the per-file limit")
                                        }
                                    } else {
                                        val length=stream.contentLength?.takeIf {it>0} ?: throw IOException("Provider did not supply a complete audio length")
                                        while(bytes<length) {
                                            checkState();ensureActive()
                                            val end=if(stream.boundedRange) minOf(bytes+1_048_576-1,length-1) else length-1
                                            val url=if(stream.boundedRange) stream.url.toHttpUrl().newBuilder().setQueryParameter("range","$bytes-$end").build() else stream.url.toHttpUrl()
                                            val request=Request.Builder().url(url).apply {stream.headers.forEach {(k,v)->header(k,v)};if(bytes>0 && !stream.boundedRange) header("Range","bytes=$bytes-")}.build()
                                            dependencies.httpCalls.newCall(request).execute().use {response->
                                                if(!response.isSuccessful) throw IOException("Audio transfer rejected (${response.code})")
                                                val wanted=end-bytes+1;var received=0L
                                                response.body.byteStream().use {input->val buffer=ByteArray(64*1024);while(true) {
                                                    ensureActive();checkState();val n=input.read(buffer);if(n<0) break
                                                    received+=n;if(received>wanted) throw IOException("Unexpected audio range")
                                                    out.write(buffer,0,n);bytes+=n
                                                    if(bytes-lastProgress>256_000) {store.update(scope,id,"bytes" to bytes);lastProgress=bytes}
                                                }}
                                                if(received!=wanted) throw IOException("Incomplete audio transfer")
                                            }
                                        }
                                    }
                                    out.fd.sync()
                                }
                                stream.contentLength?.takeIf {it>0}?.let {if(raw.length()!=it) throw IOException("Incomplete audio length")}
                                phase="validate"
                                remux(raw,assembled,stream.durationMs.takeIf {it>0}?:job.durationMs,::checkState)
                                checkState();ensureActive()
                                val audio=File(dir,"audio.media");check(assembled.renameTo(audio)) {"Could not commit audio"};raw.delete()
                                // Before the bundle is marked complete, so a complete bundle never gains or loses its value later.
                                BundleLoudness.write(dir,stream.loudnessDb)
                                File(dir,"ready").writeText(audio.length().toString());store.update(scope,id,"bytes" to audio.length(),"total" to audio.length(),"downloaded_at" to dependencies.clocks.wallTimeMillis());downloaded=true;break
                            } catch(e:CancellationException) {throw e} catch(e:Exception) {
                                raw.delete();assembled.delete();if(dependencies.resolver==null) provider.invalidateStream(id)
                                if(attempt==2) throw e
                                stream.profileId?.let {rejected.add(it)}
                                dependencies.wait((attempt+1)*1000L);stream=resolver.resolve(StreamRequest(id,excludedProfiles=rejected.toSet(),download=true,quality=com.metrolist.innertubex.extraction.AudioQuality.HIGH)).getOrThrow()
                                store.update(scope,id,"quality" to "${if(stream.bitrate>0) "${stream.bitrate/1000} kbps \u00B7 " else ""}${stream.mimeType?.substringBefore(';')?:"audio"} \u00B7 Highest available","duration" to (stream.durationMs.takeIf {it>0}?:job.durationMs))
                            }
                        }
                        check(downloaded)
                    }
                    phase="assets"
                    if(!store.updateDownload(scope,id,"state" to "assets")) throw CancellationException("Download removed")
                    val duration=store.song(scope,id)?.durationMs?:job.durationMs
                    val art=store.artwork(scope,id)
                    if(art==null) {
                        try {
                            val url=validProviderArtworkUrl(job.track.artworkUrl)?.let(::highQualityArtworkUrl)?:throw IOException("No artwork")
                            val temp=File(dir,"artwork.part")
                            dependencies.httpCalls.newCall(Request.Builder().url(url).build()).execute().use {r->
                                if(!r.isSuccessful) throw IOException("Artwork unavailable")
                                val bytes=java.io.ByteArrayOutputStream();r.body.byteStream().use {input->val buffer=ByteArray(8192);while(true) {ensureActive();checkState();val count=input.read(buffer);if(count<0) break;if(bytes.size()+count>8_000_000) throw IOException("Artwork too large");bytes.write(buffer,0,count)}}
                                val data=bytes.toByteArray();if(android.graphics.BitmapFactory.decodeByteArray(data,0,data.size)==null) throw IOException("Invalid artwork");temp.writeBytes(data)
                            }
                            checkState();check(temp.renameTo(File(dir,"artwork")));store.markArtwork(scope,id,true);store.update(scope,id,"artwork" to "available")
                        } catch(e:CancellationException) {throw e} catch(_:Exception) {store.update(scope,id,"artwork" to "failed")}
                    }
                    if(store.lyrics(scope,id)==null) {
                        val lyrics=LyricsRepository.get(context)
                        try {
                            var best:LyricsDocument?=null
                            val finished=withTimeoutOrNull(50_000) {lyrics.lyrics(LyricsTrack("yt:$id",job.track.title,job.track.artist,job.track.album,duration)) {provider.lyricsFor(id,duration)}.collect {best=it};true}==true
                            checkState()
                            if(best!=null) {val temp=File(dir,"lyrics.part");temp.writeText(encodeLyricsDocument(best!!).toString());check(temp.renameTo(File(dir,"lyrics.json")));store.update(scope,id,"lyrics" to if(best!!.instrumental) "instrumental" else "available")}
                            else store.update(scope,id,"lyrics" to if(finished) "unavailable" else "failed")
                        } catch(e:CancellationException) {throw e}
                        catch(_:Exception) {store.update(scope,id,"lyrics" to "failed")}
                    }
                    checkState();store.updateDownload(scope,id,"state" to "complete","error" to "")
                } catch(e:CancellationException) {
                    store.updateDownload(scope,id,"state" to "queued")
                    raw.delete();assembled.delete();store.discardUnlisted(scope,id);throw e
                } catch(error:Exception) {
                    // An account switch can fail a request in flight. That is not a failed download: it waits for its owner.
                    if(!process.sessions.owns(owner) || scope!=store.currentScope) {store.updateDownload(scope,id,"state" to "queued");raw.delete();assembled.delete();break}
                    // A bulk removal may delete a file during IO. Never resurrect its row as failed.
                    if(store.song(scope,id)?.state !in setOf("downloading","assets")) {raw.delete();assembled.delete();store.discardUnlisted(scope,id);continue}
                    PlaybackEventLog(context).append("download_failed phase=$phase kind=${error::class.simpleName}")
                    raw.delete();assembled.delete();store.updateDownload(scope,id,"state" to if(store.audio(scope,id)!=null) "complete" else "failed","error" to "Download incomplete. Check storage and connection, then retry.")
                }
            }
            true
        } finally {
            try {clientResource?.close()} finally {
                try {engineResource?.close()} finally {
                    try {ownedProvider?.close()} finally {gate.unlock()}
                }
            }
        }
    }
    internal fun automaticConditionsAllowed(context:Context,store:OfflineMusicStore,
        dependencies:RuntimeDependencies=AppContainer.get(context).runtime):Boolean {
        val network=dependencies.networkConditions()
        return dependencies.batteryPercent() !in 0..19 && smartDownloadNetworkAllowed(store.allowMobileData,
            network.wifi,network.unmetered,network.validated)
    }
    private fun plan(context:Context,store:OfflineMusicStore,scope:String,dependencies:RuntimeDependencies,provider:YouTubeMusicProvider) {
        if(!store.smartEnabled) return
        val rows=store.rows(scope);val likes=provider.songLikes;val favorites=LocalCollections(context,provider.accountScopeKey()).onlineFavorites.map {it.videoId}.toSet()
        val chosen=smartCandidates(rows.filterNot {it.excluded}.map {SmartCandidate(it.track.videoId,it.lastPlayed,it.plays,it.listenedMs,it.durationMs,likes[it.track.videoId]==true || it.track.videoId in favorites,it.track.isLive,it.track.isMusic)},dependencies.clocks.wallTimeMillis())
        val ids=chosen.map {it.id}.toSet()
        rows.filter {!it.pinned && it.state!="metadata" && it.track.videoId !in ids}.forEach {store.remove(scope,it.track.videoId,exclude=false)}
        val stats=dependencies.storageCapacity(store.root);var estimated=store.usedBytes();var available=stats.availableBytes
        for((index,candidate) in chosen.withIndex()) {
            val row=rows.first {it.track.videoId==candidate.id};if(row.pinned || store.audio(scope,candidate.id)!=null) continue
            val estimate=candidate.durationMs*320_000/8000
            if(downloadFits(estimated,estimate,store.budgetBytes,available,stats.totalBytes)) {store.enqueue(row.track,false);store.update(scope,candidate.id,"priority" to (250-index));estimated+=estimate;available-=estimate}
        }
    }
    /** Rebuild container indexing without re-encoding: retain the selected source audio quality and make offline seeking reliable. */
    private suspend fun remux(input:File,output:File,expectedDuration:Long,checkState:()->Unit) {
        val extractor=MediaExtractor();var muxer:MediaMuxer?=null
        try {
            extractor.setDataSource(input.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:throw IOException("No valid audio")
            val format=extractor.getTrackFormat(track);extractor.selectTrack(track)
            val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            output.delete();muxer=MediaMuxer(output.absolutePath,if(mime.contains("opus") || mime.contains("vorbis")) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val target=muxer.addTrack(format);muxer.start();val buffer=ByteBuffer.allocate(2*1024*1024);val info=MediaCodec.BufferInfo();var endUs=0L;var samples=0
            while(true) {
                currentCoroutineContext().ensureActive();checkState();buffer.clear();val size=extractor.readSampleData(buffer,0);if(size<0) break
                val time=extractor.sampleTime;info.set(0,size,time,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(target,buffer,info);endUs=maxOf(endUs,time);samples++;extractor.advance()
            }
            if(samples==0 || expectedDuration<=0 || abs(endUs/1000-expectedDuration)>maxOf(3000,expectedDuration/20)) throw IOException("Incomplete song duration")
            muxer.stop()
        } finally {extractor.release();muxer?.release()}
    }
}
