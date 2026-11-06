package com.musicglass.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Explicit acceptance command, using the production composition and an isolated owned data root. */
internal object PhaseOneAcceptance {
    fun run(args:Array<String>) {
        require(args.size==4) { "--phase1-acceptance <isolated-directory> <corpus-directory> <local|remote|restore>" }
        val directory=File(args[1]).canonicalFile
        require(directory.path.contains("phase1",ignoreCase=true)) { "Acceptance requires a named phase1 directory" }
        System.setProperty("riff.data.dir",directory.path)
        System.setProperty("riff.provider.diagnostic","true")
        File(directory,"acceptance-failure.txt").takeIf{it.isFile}?.delete()
        val corpus=File(args[2]).canonicalFile
        fun DesktopHost.command(c:DesktopCommand):HostSnapshot { dispatch(c);return awaitIdle().get(150,TimeUnit.SECONDS).also { check(it.notificationMessage==null || it.notificationMessage.startsWith("Added ")) { it.notificationMessage.orEmpty() } } }
        fun waitFor(host:DesktopHost,seconds:Long,predicate:(HostSnapshot)->Boolean) {
            val until=System.nanoTime()+seconds*1_000_000_000
            while(!predicate(host.snapshot)) { check(System.nanoTime()<until) { "Acceptance timeout: ${host.snapshot.notificationMessage}" };check(host.snapshot.notificationMessage==null||host.snapshot.notificationMessage!!.startsWith("Added ")) { host.snapshot.notificationMessage.orEmpty() };Thread.sleep(100) }
        }
        val results=mutableListOf<JsonObject>()
        DesktopHost(directory=directory).use { host ->
            val initial=host.awaitIdle().get(150,TimeUnit.SECONDS);check(initial.initialized){initial.notificationMessage.orEmpty()}
            when(args[3]) {
                "local" -> {
                    require(initial.queueEntries.isEmpty()) { "Use a fresh isolated directory" }
                    for(name in listOf("reference.mp3","reference.flac","reference.m4a")) host.command(DesktopCommand.AddFileToQueue(File(corpus,name)))
                    check(host.snapshot.queueEntries.size==3)
                    host.command(DesktopCommand.SetVolume(.10f))
                    val song=host.snapshot.queueEntries.first().song
                    host.command(DesktopCommand.PlayCollection(listOf(song,host.snapshot.queueEntries[1].song,song)))
                    waitFor(host,15){it.isPlaying&&it.playbackPositionMs>500}
                    host.command(DesktopCommand.SeekTo(1500));waitFor(host,10){it.playbackPositionMs>1700}
                    host.command(DesktopCommand.NextTrack);waitFor(host,10){it.currentTrack.id.endsWith(".flac")&&it.playbackPositionMs>200}
                    host.command(DesktopCommand.PreviousTrack)
                    host.command(DesktopCommand.SetPlaying(false));host.command(DesktopCommand.SeekTo(1234))
                    val last=host.snapshot.queueEntries.last().queueId
                    host.command(DesktopCommand.MoveQueueEntryToPosition(last,1))
                    check(host.snapshot.queueEntries.map{it.queueId}.distinct().size==3)
                    check(host.snapshot.playbackPositionMs==1234L)
                    results+=buildJsonObject{put("local",true);put("position",host.snapshot.playbackPositionMs);put("duplicates",true);put("queueRevision",host.snapshot.queueRevision)}
                }
                "remote" -> {
                    val songs=runBlocking { DesktopProvider(File(directory,"provider")).use { it.search("Rick Astley Never Gonna Give You Up") } }
                    check(songs.isNotEmpty()){ "Actual provider catalog search returned no tracks" }
                    val song=songs.firstOrNull{it.id=="youtube:dQw4w9WgXcQ"}?:songs.first()
                    val local=initial.libraryTracks.firstOrNull()?:error("Import local corpus before remote gate")
                    host.command(DesktopCommand.SetVolume(.10f))
                    host.command(DesktopCommand.PlayCollection(listOf(song,local)))
                    waitFor(host,120){it.isPlaying&&it.playbackPositionMs>1000}
                    check(host.snapshot.currentTrack.durationMs>60000){"Remote duration unavailable"}
                    println("RIFF_ACCEPTANCE_REMOTE_STARTED durationMs=${host.snapshot.currentTrack.durationMs}")
                    val remoteId=host.snapshot.currentEntryId
                    waitFor(host,100){it.playbackPositionMs>61000}
                    host.command(DesktopCommand.SeekTo(65000))
                    waitFor(host,25){it.playbackPositionMs>66000}
                    // Full track from its start after testing late seek, then actual automatic queue transition.
                    host.command(DesktopCommand.SeekTo(0))
                    val duration=host.snapshot.currentTrack.durationMs
                    var heartbeat=System.nanoTime()
                    val deadline=System.nanoTime()+(duration/1000+120)*1_000_000_000
                    while(host.snapshot.currentEntryId==remoteId) {
                        check(System.nanoTime()<deadline){"Remote full-track transition timeout"}
                        check(host.snapshot.notificationMessage==null || host.snapshot.notificationMessage!!.startsWith("Added ")){host.snapshot.notificationMessage.orEmpty()}
                        if(System.nanoTime()-heartbeat>30_000_000_000){println("RIFF_ACCEPTANCE_REMOTE_PROGRESS ms=${host.snapshot.playbackPositionMs}");heartbeat=System.nanoTime()}
                        Thread.sleep(100)
                    }
                    waitFor(host,15){it.isPlaying&&it.playbackPositionMs>200}
                    host.command(DesktopCommand.SetPlaying(false));host.command(DesktopCommand.SeekTo(1234))
                    results+=buildJsonObject{put("remoteFullTrack",true);put("duration",duration);put("lateSeek",true);put("automaticTransition",true)}
                }
                "restore" -> {
                    val expected=if(File(directory,"crash-ready.json").isFile)2345L else 1234L
                    check(initial.queueEntries.isNotEmpty());check(!initial.isPlaying);check(initial.playbackPositionMs==expected)
                    host.command(DesktopCommand.SetPlaying(true));waitFor(host,15){it.playbackPositionMs>1400}
                    host.command(DesktopCommand.SetPlaying(false));host.command(DesktopCommand.SeekTo(1234))
                    results+=buildJsonObject{put("restore",true);put("position",initial.playbackPositionMs);put("queue",initial.queueEntries.size)}
                }
                "dsp-session" -> {
                    val first=initial.libraryTracks.first{it.id.endsWith(".flac")}
                    val second=initial.libraryTracks.first{it.id.endsWith(".m4a")}
                    host.command(DesktopCommand.SetVolume(.1f));host.command(DesktopCommand.SetCrossfadeEnabled(true))
                    host.command(DesktopCommand.SetEqPreset("Rock"));host.command(DesktopCommand.SetVolumeBoost(true))
                    host.command(DesktopCommand.PlayCollection(listOf(first,second)));host.command(DesktopCommand.SeekTo(4000))
                    waitFor(host,15){it.secondaryEntryId!=null&&it.secondaryPositionMs>100}
                    host.command(DesktopCommand.SetPlaying(false));val checkpoint=host.snapshot
                    check(checkpoint.secondaryEntryId!=null && checkpoint.secondaryPositionMs>0)
                    host.close()
                    DesktopHost(directory=directory).use{restored->
                        val state=restored.awaitIdle().get(90,TimeUnit.SECONDS)
                        check(state.initialized&&!state.isPlaying){state.notificationMessage.orEmpty()}
                        check(state.currentEntryId==checkpoint.currentEntryId && state.secondaryEntryId==checkpoint.secondaryEntryId)
                        check(state.playbackPositionMs==checkpoint.playbackPositionMs && state.secondaryPositionMs==checkpoint.secondaryPositionMs)
                        check(state.crossfadeEnabled && state.eqPreset=="Rock" && state.volumeBoost)
                        restored.command(DesktopCommand.SetPlaying(true));waitFor(restored,15){it.currentTrack.id==second.id}
                        restored.command(DesktopCommand.SetPlaying(false));restored.command(DesktopCommand.SetCrossfadeEnabled(false))
                        restored.command(DesktopCommand.SetEqPreset("Flat"));restored.command(DesktopCommand.SetVolumeBoost(false));restored.command(DesktopCommand.SeekTo(1234))
                    }
                    results+=buildJsonObject{put("crossfadeSession",true);put("primaryPosition",checkpoint.playbackPositionMs);put("secondaryPosition",checkpoint.secondaryPositionMs);put("actualDspControls",true)}
                }
                "worker-crash" -> {
                    check(initial.queueEntries.isNotEmpty())
                    host.command(DesktopCommand.SetPlaying(true));waitFor(host,15){it.isPlaying&&it.playbackPositionMs>1500}
                    val oldPid=checkNotNull(host.audioBridge.workerPid)
                    val queue=host.snapshot.queueEntries.map{it.queueId}
                    ProcessHandle.of(oldPid).orElseThrow().destroyForcibly()
                    val until=System.nanoTime()+30_000_000_000
                    while(!host.snapshot.notificationMessage.orEmpty().contains("Audio restarted")) {check(System.nanoTime()<until){"Owned worker did not recover: ${host.snapshot.notificationMessage}"};Thread.sleep(100)}
                    check(!host.snapshot.isPlaying);check(queue==host.snapshot.queueEntries.map{it.queueId})
                    check(host.audioBridge.workerPid!=oldPid)
                    host.command(DesktopCommand.DismissNotification());host.command(DesktopCommand.SetPlaying(true))
                    val saved=host.snapshot.playbackPositionMs;waitFor(host,15){it.playbackPositionMs>saved+200}
                    host.command(DesktopCommand.SetPlaying(false));host.command(DesktopCommand.SeekTo(1234))
                    results+=buildJsonObject{put("workerKilled",true);put("recoveredPaused",true);put("genuineResume",true)}
                }
                "crash-hold" -> {
                    check(initial.queueEntries.isNotEmpty());host.command(DesktopCommand.SeekTo(2345));host.command(DesktopCommand.SetVolume(.23f))
                    File(directory,"crash-ready.json").writeText(buildJsonObject{put("processId",ProcessHandle.current().pid());put("workerId",host.audioBridge.workerPid);put("position",2345);put("volume",.23f);put("queue",JsonArray(host.snapshot.queueEntries.map{JsonPrimitive(it.queueId)}))}.toString())
                    Thread.sleep(600_000)
                    error("Crash harness did not terminate its owned process")
                }
                else -> error("Unknown acceptance mode")
            }
        }
        File(directory,"${args[3]}-acceptance.json").writeText(JsonArray(results).toString())
        println("RIFF_PHASE1_ACCEPTANCE_PASS ${args[3]}")
    }
}
