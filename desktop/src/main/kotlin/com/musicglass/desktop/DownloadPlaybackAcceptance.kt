package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Real provider download -> fully decoded/hash-committed file -> fresh production worker local playback. */
internal object DownloadPlaybackAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2);val output=File(args[0]).canonicalFile.apply{mkdirs()};val id=args[1]
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        var bytes=0L;var sha="";var localFrames=0L;var restartedFrames=0L;var sought=0L;var undo=false;var removed=false;var deferred=false;var normalizationKnown=false;var appliedNormalizationGain=1f;var normalizationRestored=false;var normalizationToggled=false
        fun receipt(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("videoId",id);put("downloadBytes",bytes);put("downloadSha256",sha);put("firstLocalHardwareFrames",localFrames);put("restartedLocalHardwareFrames",restartedFrames);put("seekPositionMs",sought);put("removalUndone",undo);put("expiredRemovalCommitted",removed);put("windowsLockedRemovalDeferred",deferred)
            put("normalizationKnown",normalizationKnown);put("normalizationGain",appliedNormalizationGain);put("normalizationRestored",normalizationRestored);put("normalizationToggled",normalizationToggled)
            put("scope","Actual public provider -> verified disk audio -> production worker local decoder after restart; does not certify screen interaction, physical network disconnection, private account downloads or every provider representation")
            error?.let{put("error",it.take(180))}
        }.toString())}
        try{withTimeout(360000){
            val metadata=DesktopProvider(File(profile,"metadata")).use{checkNotNull(it.resolve(id).mediaMetadata)}
            val song=EmptyDesktopTrack.copy(id="youtube:$id",title=checkNotNull(metadata.title),artist=metadata.author.orEmpty(),durationMs=(metadata.durationSeconds?:0)*1000L,scope=Scopes.GUEST,artworkUrl=providerArtworkUrl(id))
            suspend fun await(test:()->Boolean){withTimeout(180000){while(!test())delay(25)}}
            suspend fun command(host:DesktopHost,cmd:DesktopCommand){host.dispatch(cmd);host.awaitIdle().get()}
            fun diagnostics(host:DesktopHost):JsonObject{val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success);return Json.parseToJsonElement(checkNotNull(result.details)).jsonObject}
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};command(host,DesktopCommand.SetVolume(0f));command(host,DesktopCommand.SetNormalization(true));command(host,DesktopCommand.DownloadTrack(song))
                await{host.snapshot.downloads.any{it.job.trackId==song.id&&it.job.state in setOf(DownloadState.Complete,DownloadState.Failed)}}
                val download=host.snapshot.downloads.single{it.job.trackId==song.id};check(download.job.state==DownloadState.Complete){"Download failed: ${download.job.errorCode}"}
                bytes=download.job.bytes;sha=checkNotNull(download.job.sha256);check(bytes>0)
                command(host,DesktopCommand.PlayTrack(song));await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>500}
                var diagnostic=diagnostics(host);check(diagnostic.getValue("primarySourceKind").jsonPrimitive.content=="local")
                normalizationKnown=diagnostic["primaryLoudnessDb"] !is JsonNull
                appliedNormalizationGain=diagnostic.getValue("primaryNormalizationGain").jsonPrimitive.float
                check(diagnostic.getValue("normalizationEnabled").jsonPrimitive.boolean)
                val db=diagnostic["primaryLoudnessDb"]?.jsonPrimitive?.doubleOrNull
                check(kotlin.math.abs(appliedNormalizationGain-normalizationGain(true,db))<.000001f)
                command(host,DesktopCommand.SetNormalization(false));check(diagnostics(host).getValue("primaryNormalizationGain").jsonPrimitive.float==1f)
                command(host,DesktopCommand.SetNormalization(true));check(diagnostics(host).getValue("normalizationEnabled").jsonPrimitive.boolean);normalizationToggled=true
                check(diagnostic.getValue("decodedPeak").jsonPrimitive.float>.00001f);check(diagnostic.getValue("underrunFrames").jsonPrimitive.long==0L);localFrames=diagnostic.getValue("presentedFrames").jsonPrimitive.long
                command(host,DesktopCommand.SeekTo(12000));await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=12000};sought=host.snapshot.playbackPositionMs
                command(host,DesktopCommand.SetPlaying(false))
                command(host,DesktopCommand.RemoveDownload(song.scope,song.id));check(host.snapshot.pendingDownloadRemoval==(song.scope to song.id))
                command(host,DesktopCommand.UndoDownloadRemoval);check(host.snapshot.pendingDownloadRemoval==null);check(host.snapshot.downloads.single{it.job.trackId==song.id}.job.state==DownloadState.Complete);undo=true
            }
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy&&host.snapshot.downloads.any{it.job.trackId==song.id&&it.job.state==DownloadState.Complete}}
                check(host.snapshot.normalizationEnabled);normalizationRestored=true
                check(!host.snapshot.isPlaying);command(host,DesktopCommand.SetPlaying(true))
                await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=sought+300}
                val diagnostic=diagnostics(host);check(diagnostic.getValue("primarySourceKind").jsonPrimitive.content=="local");check(diagnostic.getValue("underrunFrames").jsonPrimitive.long==0L)
                restartedFrames=diagnostic.getValue("presentedFrames").jsonPrimitive.long;check(restartedFrames>0)
                command(host,DesktopCommand.SetPlaying(false));command(host,DesktopCommand.RemoveDownload(song.scope,song.id))
                await{host.snapshot.pendingDownloadRemoval==null&&host.snapshot.downloads.none{it.job.trackId==song.id&&it.job.state!=DownloadState.Removing}}
                deferred=host.snapshot.downloads.any{it.job.trackId==song.id&&it.job.state==DownloadState.Removing}
                if(deferred)check(File(profile,"downloads").listFiles().orEmpty().any{it.extension=="media"})
            }
            // Pausing retains the native decoder/file handle. Quit releases it; recovery must finish deletion.
            SqliteLibraryRepository(File(profile,"library")).use{store->
                DesktopDownloads(store,File(profile,"downloads"),{error("Removal recovery must not fetch audio")}).use{manager->
                    await{manager.repository.get(song.scope,song.id)==null}
                    check(File(profile,"downloads").listFiles().orEmpty().none{it.extension in setOf("part","media")});removed=true
                }
            }
            receipt("PASS")
        }}catch(error:Throwable){receipt("FAIL",error.message);throw error}
    }
}
