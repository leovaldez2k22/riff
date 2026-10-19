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

/** Rebuilds received audio into a seekable file without re-encoding. Throws when the audio is not whole. */
internal fun interface AudioAssembler {
    suspend fun assemble(source:File,output:File,expectedDurationMs:Long,check:()->Unit)
}

/**
 * One run over the songs waiting in the active account (Q09). For each: resolve, transfer to a
 * temporary file, rebuild and check, commit with a marker, then artwork and lyrics. The transfer
 * always starts from the first byte with a fresh stream; nothing is appended to an earlier attempt.
 */
@OptIn(ExperimentalSabrApi::class)
internal object OfflineDownloadEngine {
    private val gate=Mutex()
    /** Not failures of the download: they say who must stop. Never swallowed by a step's own error handling. */
    private sealed class Control(reason:String):RuntimeException(reason)
    /** The whole run ends: the account changed, or Smart Downloads may no longer run. The song in hand waits again. */
    private class RunStopped(val reason:String):Control(reason)
    /** This song was taken from its transfer: removed or paused. The run goes on with the next song. */
    private class Superseded:Control("superseded")
    /** A failure that a fresh stream cannot cure, so it is not tried again in this run. */
    private class Stop(val failure:DownloadFailure):IOException(failure.wire)
    private class Rejected(code:Int):IOException("Audio transfer rejected ($code)")
    private class Unusable(cause:Throwable):IOException("Audio could not be rebuilt",cause)

    private fun classify(error:Throwable):DownloadFailure=when(error) {
        is Stop -> error.failure
        is Rejected -> DownloadFailure.Rejected
        is Unusable -> DownloadFailure.Damaged
        is IOException,is IllegalStateException -> DownloadFailure.Incomplete
        else -> DownloadFailure.Other
    }

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
            val events=PlaybackEventLog(context)
            // Finished downloads whose files are gone say so before anything new is fetched.
            store.verifyComplete(scope).let {if(it>0) events.append("download_integrity damaged=$it")}
            if(automatic && store.smartEnabled && !store.playbackBusy) plan(context,store,scope,dependencies,provider)
            fun space()=dependencies.storageCapacity(store.root)
            var pass=0
            // Songs queued while a run is going are taken up by that run. Failed songs are tried once per run, in its first pass.
            while(true) {
            val first=pass++==0
            val jobs=store.rows(scope).filter {(it.status==DownloadState.Queued || first && it.status==DownloadState.Failed && it.failures<DOWNLOAD_AUTOMATIC_RETRIES) &&
                (if(automatic) !it.pinned else it.pinned)}.sortedByDescending {it.priority}
            if(jobs.isEmpty()) break
            var stopped=false
            for(job in jobs) {
                when(song(context,automatic,dependencies,progress,store,process,scope,owner,{provider},resolver,http,events,::space,job)) {
                    SongOutcome.Next -> {}
                    SongOutcome.Stopped -> {stopped=true;break}
                    SongOutcome.Refused -> return@withContext false
                }
            }
            if(stopped) break
            }
            true
        } finally {release(clientResource,engineResource,ownedProvider)}
    }
    /** What a run holds, let go in order; the gate last, whatever a close throws. */
    /** How one song ended for the run: go on, stop the run here (the owner changed), or end the run unfinished. */
    private enum class SongOutcome {Next,Stopped,Refused}
    /**
     * One song of a run. Its own function, not the run's loop body: the run's coroutine with all of this inline came
     * to 71,252 code units in a debug build, past 65,535, and there a cancellation rethrown from the song never
     * reached the run's finally, so the gate stayed locked and no later run could start (Q18-026).
     */
    private suspend fun CoroutineScope.song(context:Context,automatic:Boolean,dependencies:RuntimeDependencies,progress:(String)->Unit,
        store:OfflineMusicStore,process:AppContainer,scope:String,owner:SessionToken,provider:()->YouTubeMusicProvider,
        resolver:StreamResolver,http:HttpClient,events:PlaybackEventLog,space:()->StorageCapacity,job:OfflineSong):SongOutcome {
        ensureActive()
        if(!process.sessions.owns(owner) || scope!=store.currentScope) return SongOutcome.Stopped
        if(automatic && (!store.smartEnabled || store.playbackBusy)) return SongOutcome.Refused
        val id=job.track.videoId
        // The row's generation is this transfer's ticket. Every later write shows it.
        val generation=store.beginDownload(scope,id)
        if(generation==null) {store.discardUnlisted(scope,id);return SongOutcome.Next}
        val dir=store.directory(scope,id).apply {mkdirs()}
        val raw=File(dir,BundleValidator.SOURCE_PART);val assembled=File(dir,BundleValidator.ASSEMBLED_PART)
        var nextConditionsCheck=0L
        fun checkState() {
            if(!process.sessions.owns(owner) || scope!=store.currentScope) throw RunStopped("owner")
            if(!store.holds(scope,id,generation)) throw Superseded()
            if(automatic && (!store.smartEnabled || store.playbackBusy)) throw RunStopped("automatic_paused")
            val now=dependencies.clocks.monotonicMillis()
            if(automatic && now>=nextConditionsCheck) {
                nextConditionsCheck=now+1000
                if(!automaticConditionsAllowed(context,store,dependencies)) throw RunStopped("conditions")
            }
        }
        fun advance(vararg fields:Pair<String,Any>) {if(!store.updateDownload(scope,id,generation,*fields)) throw Superseded()}
        var phase="resolve"
        try {
            checkState();progress(string(R.string.downloading_x_2, job.track.title))
            if(store.audio(scope,id)==null) {
                var stream=resolver.resolve(StreamRequest(id,download=true,quality=com.metrolist.innertubex.extraction.AudioQuality.HIGH)).getOrThrow()
                if(stream.mimeType?.contains("mpegurl",true)==true) throw Stop(DownloadFailure.Unavailable)
                val rejected=linkedSetOf<String>()
                for(attempt in 0..2) {
                    checkState();ensureActive();raw.delete();assembled.delete()
                    val duration=stream.durationMs.takeIf {it>0}?:job.durationMs
                    val expected=stream.contentLength?.takeIf {it>0}?:(duration*maxOf(stream.bitrate,320_000)/8000)
                    // What it will occupy when done counts against the budget; the source and the rebuilt copy must both fit while it is made.
                    when(space().let {NetworkStoragePolicy.admit(store.usedBytes(scope),expected,store.budgetBytes,it.availableBytes,it.totalBytes)}) {
                        NetworkStoragePolicy.Verdict.Fits -> {}
                        NetworkStoragePolicy.Verdict.OverBudget -> throw Stop(DownloadFailure.Budget)
                        NetworkStoragePolicy.Verdict.DeviceFull -> throw Stop(DownloadFailure.Storage)
                        NetworkStoragePolicy.Verdict.Invalid -> throw Stop(DownloadFailure.Unavailable)
                    }
                    val quality="${if(stream.bitrate>0) "${stream.bitrate/1000} kbps · " else ""}${stream.mimeType?.substringBefore(';')?:"audio"} · Highest available"
                    advance("state" to DownloadState.Transferring.db,"quality" to quality,"total" to (stream.contentLength?:0L),"duration" to duration,"bytes" to 0L)
                    try {
                        var bytes=0L;var lastProgress=0L;var lastSpaceCheck=0L
                        phase="transfer"
                        raw.outputStream().use {out->
                            fun received(data:ByteArray,offset:Int,count:Int) {
                                // A write that fails here is the device running out of room, not the provider.
                                try {out.write(data,offset,count)} catch(e:IOException) {throw Stop(DownloadFailure.Storage)}
                                bytes+=count
                                if(bytes>NetworkStoragePolicy.SONG_LIMIT) throw Stop(DownloadFailure.Unavailable)
                                if(bytes-lastProgress>256_000) {advance("bytes" to bytes);lastProgress=bytes}
                                if(bytes-lastSpaceCheck>1_048_576) {
                                    lastSpaceCheck=bytes
                                    if(!space().let {NetworkStoragePolicy.transferMayContinue(bytes,expected,it.availableBytes,it.totalBytes)}) throw Stop(DownloadFailure.Storage)
                                }
                            }
                            if(stream.sabrBootstrap!=null) {
                                var nextRange=0L
                                SabrAudioStream(http,stream.sabrBootstrap!!).chunks().collect {chunk->
                                    checkState();ensureActive();check(chunk.startRange==nextRange) {"Non-contiguous audio transfer"}
                                    received(chunk.data,0,chunk.data.size);nextRange=chunk.endRangeExclusive
                                    check(bytes==nextRange) {"Audio range does not match its data"}
                                }
                            } else {
                                val length=stream.contentLength?.takeIf {it>0} ?: throw Stop(DownloadFailure.Unavailable)
                                while(bytes<length) {
                                    checkState();ensureActive()
                                    val end=if(stream.boundedRange) minOf(bytes+1_048_576-1,length-1) else length-1
                                    val url=if(stream.boundedRange) stream.url.toHttpUrl().newBuilder().setQueryParameter("range","$bytes-$end").build() else stream.url.toHttpUrl()
                                    val request=Request.Builder().url(url).apply {stream.headers.forEach {(k,v)->header(k,v)};if(bytes>0 && !stream.boundedRange) header("Range","bytes=$bytes-")}.build()
                                    dependencies.httpCalls.newCall(request).execute().use {response->
                                        if(!response.isSuccessful) throw Rejected(response.code)
                                        val wanted=end-bytes+1;var got=0L
                                        response.body.byteStream().use {input->val buffer=ByteArray(64*1024);while(true) {
                                            ensureActive();checkState();val n=input.read(buffer);if(n<0) break
                                            got+=n;if(got>wanted) throw IOException("Unexpected audio range")
                                            received(buffer,0,n)
                                        }}
                                        if(got!=wanted) throw IOException("Incomplete audio transfer")
                                    }
                                }
                            }
                            try {out.fd.sync()} catch(e:IOException) {throw Stop(DownloadFailure.Storage)}
                        }
                        stream.contentLength?.takeIf {it>0}?.let {if(raw.length()!=it) throw IOException("Incomplete audio length")}
                        phase="validate"
                        advance("state" to DownloadState.Validating.db,"bytes" to raw.length())
                        if(!space().let {NetworkStoragePolicy.rebuildMayStart(raw.length(),it.availableBytes,it.totalBytes)}) throw Stop(DownloadFailure.Storage)
                        try {dependencies.assembler.assemble(raw,assembled,duration,::checkState)}
                        catch(e:CancellationException) {throw e} catch(e:Control) {throw e}
                        catch(e:Exception) {
                            // A rebuild that stops because the device filled up is a storage failure, not bad audio.
                            if(space().availableBytes<raw.length()) throw Stop(DownloadFailure.Storage)
                            throw Unusable(e)
                        }
                        checkState();ensureActive()
                        phase="commit"
                        // Under the store's lock, so a removal cannot delete the folder between the check and the rename.
                        val audio=try {store.commitAudio(scope,id,generation) {BundleValidator.commit(dir,assembled,stream.loudnessDb)}}
                            catch(e:IOException) {throw Stop(DownloadFailure.Commit)}
                        if(audio==null) throw Superseded()
                        raw.delete()
                        advance("bytes" to audio.length(),"total" to audio.length(),"downloaded_at" to dependencies.clocks.wallTimeMillis())
                        break
                    } catch(e:CancellationException) {throw e} catch(e:Control) {throw e} catch(e:Stop) {throw e} catch(e:Exception) {
                        raw.delete();assembled.delete();if(dependencies.resolver==null) provider().invalidateStream(id)
                        if(attempt==2) throw e
                        events.append("download_retry phase=$phase attempt=${attempt+1} failure=${classify(e).wire}")
                        stream.profileId?.let {rejected.add(it)}
                        advance("state" to DownloadState.Resolving.db,"bytes" to 0L);phase="resolve"
                        dependencies.wait((attempt+1)*1000L);checkState()
                        stream=resolver.resolve(StreamRequest(id,excludedProfiles=rejected.toSet(),download=true,quality=com.metrolist.innertubex.extraction.AudioQuality.HIGH)).getOrThrow()
                        if(stream.mimeType?.contains("mpegurl",true)==true) throw Stop(DownloadFailure.Unavailable)
                    }
                }
            }
            // From here the song is playable. Artwork and lyrics have their own states and cannot undo that.
            phase="assets"
            advance("state" to DownloadState.Assets.db)
            val duration=store.song(scope,id)?.durationMs?:job.durationMs
            if(store.artwork(scope,id)==null) {
                try {
                    val url=validProviderArtworkUrl(job.track.artworkUrl)?.let(::highQualityArtworkUrl)?:throw IOException("No artwork")
                    val temp=File(dir,"artwork.part")
                    dependencies.httpCalls.newCall(Request.Builder().url(url).build()).execute().use {r->
                        if(!r.isSuccessful) throw IOException("Artwork unavailable")
                        val bytes=java.io.ByteArrayOutputStream();r.body.byteStream().use {input->val buffer=ByteArray(8192);while(true) {ensureActive();checkState();val count=input.read(buffer);if(count<0) break;if(bytes.size()+count>8_000_000) throw IOException("Artwork too large");bytes.write(buffer,0,count)}}
                        val data=bytes.toByteArray();if(android.graphics.BitmapFactory.decodeByteArray(data,0,data.size)==null) throw IOException("Invalid artwork");temp.writeBytes(data)
                    }
                    checkState();check(temp.renameTo(File(dir,"artwork")));store.markArtwork(scope,id,true);advance("artwork" to "available")
                } catch(e:CancellationException) {throw e} catch(e:Control) {throw e}
                catch(_:Exception) {File(dir,"artwork.part").delete();advance("artwork" to "failed")}
            }
            if(store.lyrics(scope,id)==null) {
                try {
                    var best:LyricsDocument?=null
                    val track=LyricsTrack("yt:$id",job.track.title,job.track.artist,job.track.album,duration)
                    val source=dependencies.downloadLyrics?.invoke(track)?:LyricsRepository.get(context).lyrics(track) {provider().lyricsFor(id,duration)}
                    val finished=withTimeoutOrNull(50_000) {source.collect {best=it};true}==true
                    checkState()
                    if(best!=null) {val temp=File(dir,"lyrics.part");temp.writeText(encodeLyricsDocument(best!!).toString());check(temp.renameTo(File(dir,"lyrics.json")));advance("lyrics" to if(best!!.instrumental) "instrumental" else "available")}
                    else advance("lyrics" to if(finished) "unavailable" else "failed")
                } catch(e:CancellationException) {throw e} catch(e:Control) {throw e}
                catch(_:Exception) {File(dir,"lyrics.part").delete();advance("lyrics" to "failed")}
            }
            checkState();advance("state" to DownloadState.Complete.db,"error" to "","failures" to 0)
        } catch(e:CancellationException) {
            // The job or the service was stopped. The song waits; what was committed stays.
            store.updateDownload(scope,id,generation,"state" to DownloadState.Queued.db)
            BundleValidator.sweep(dir);store.discardUnlisted(scope,id);throw e
        } catch(e:Superseded) {
            // Removed or paused. Whoever took the row decides its state; only this transfer's unfinished files go.
            BundleValidator.sweep(dir);store.discardUnlisted(scope,id);return SongOutcome.Next
        } catch(e:RunStopped) {
            store.updateDownload(scope,id,generation,"state" to DownloadState.Queued.db)
            BundleValidator.sweep(dir);events.append("download_stopped reason=${e.reason} phase=$phase")
            return SongOutcome.Refused
        } catch(error:Exception) {
            // An account switch can fail a request in flight. That is not a failed download: it waits for its owner.
            if(!process.sessions.owns(owner) || scope!=store.currentScope) {store.updateDownload(scope,id,generation,"state" to DownloadState.Queued.db);BundleValidator.sweep(dir);return SongOutcome.Stopped}
            // A removal may delete a file during IO. Never resurrect its row as failed.
            if(!store.holds(scope,id,generation)) {BundleValidator.sweep(dir);store.discardUnlisted(scope,id);return SongOutcome.Next}
            val failure=classify(error)
            events.append("download_failed phase=$phase kind=${error::class.simpleName} failure=${failure.wire}")
            BundleValidator.sweep(dir)
            // Audio that was committed is a finished download even if a later step threw.
            if(store.audio(scope,id)!=null) store.updateDownload(scope,id,generation,"state" to DownloadState.Complete.db,"error" to "","failures" to 0)
            else store.failDownload(scope,id,generation,failure)
        }
        return SongOutcome.Next
    }
    private fun release(client:HttpClient?,engine:io.ktor.client.engine.HttpClientEngine?,provider:YouTubeMusicProvider?) {
        try {client?.close()} finally {
            try {engine?.close()} finally {
                try {provider?.close()} finally {gate.unlock()}
            }
        }
    }
    internal fun automaticConditionsAllowed(context:Context,store:OfflineMusicStore,
        dependencies:RuntimeDependencies=AppContainer.get(context).runtime):Boolean =
        NetworkStoragePolicy.automaticAllowed(store.allowMobileData,dependencies.networkConditions(),dependencies.batteryPercent())
    private fun plan(context:Context,store:OfflineMusicStore,scope:String,dependencies:RuntimeDependencies,provider:YouTubeMusicProvider) {
        if(!store.smartEnabled) return
        val rows=store.rows(scope);val likes=provider.songLikes;val favorites=LocalCollections(context,provider.accountScopeKey()).onlineFavorites.map {it.videoId}.toSet()
        // A song the user removed is excluded and is not chosen again until the user downloads it by hand.
        val chosen=smartCandidates(rows.filterNot {it.excluded}.map {SmartCandidate(it.track.videoId,it.lastPlayed,it.plays,it.listenedMs,it.durationMs,likes[it.track.videoId]==true || it.track.videoId in favorites,it.track.isLive,it.track.isMusic)},dependencies.clocks.wallTimeMillis())
        val ids=chosen.map {it.id}.toSet()
        // The selection rolls: what it no longer holds goes. A song the user pinned is never part of that.
        rows.filter {!it.pinned && it.state!="metadata" && it.track.videoId !in ids}.forEach {store.remove(scope,it.track.videoId,exclude=false)}
        val stats=dependencies.storageCapacity(store.root);var estimated=store.usedBytes(scope);var available=stats.availableBytes
        for((index,candidate) in chosen.withIndex()) {
            val row=rows.first {it.track.videoId==candidate.id};if(row.pinned || store.audio(scope,candidate.id)!=null) continue
            // It failed in as many runs as are tried; choosing it again would only repeat that.
            if(row.status==DownloadState.Failed && row.failures>=DOWNLOAD_AUTOMATIC_RETRIES) continue
            val estimate=candidate.durationMs*320_000/8000
            if(NetworkStoragePolicy.admit(estimated,estimate,store.budgetBytes,available,stats.totalBytes)==NetworkStoragePolicy.Verdict.Fits) {
                if(row.status?.pending!=true && row.status!=DownloadState.Failed) store.enqueue(row.track,false)
                store.update(scope,candidate.id,"priority" to (250-index));estimated+=estimate;available-=estimate
            }
        }
    }
}

/** Rebuild container indexing without re-encoding: retain the selected source audio quality and make offline seeking reliable. */
internal object AndroidAudioAssembler:AudioAssembler {
    override suspend fun assemble(source:File,output:File,expectedDurationMs:Long,check:()->Unit) {
        val extractor=MediaExtractor();var muxer:MediaMuxer?=null
        try {
            extractor.setDataSource(source.absolutePath)
            val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:throw IOException("No valid audio")
            val format=extractor.getTrackFormat(track);extractor.selectTrack(track)
            val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            output.delete();muxer=MediaMuxer(output.absolutePath,if(mime.contains("opus") || mime.contains("vorbis")) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val target=muxer.addTrack(format);muxer.start();val buffer=ByteBuffer.allocate(2*1024*1024);val info=MediaCodec.BufferInfo();var endUs=0L;var samples=0
            while(true) {
                currentCoroutineContext().ensureActive();check();buffer.clear();val size=extractor.readSampleData(buffer,0);if(size<0) break
                val time=extractor.sampleTime;info.set(0,size,time,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(target,buffer,info);endUs=maxOf(endUs,time);samples++;extractor.advance()
            }
            // Duration consistency: what was received must run as long as the provider said, within 3 s or 5 percent.
            if(samples==0 || expectedDurationMs<=0 || abs(endUs/1000-expectedDurationMs)>maxOf(3000,expectedDurationMs/20)) throw IOException("Incomplete song duration")
            muxer.stop()
        } finally {extractor.release();muxer?.release()}
    }
}
