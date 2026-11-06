package com.musicglass.desktop

import com.musicglass.data.*
import com.musicglass.data.encore.EncorePeriod
import com.musicglass.data.encore.EncoreRange
import com.musicglass.data.encore.EncoreKind
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Actual recording -> WASAPI -> heard-time writer -> SQLite music rollups -> production view Flow -> restart. */
internal object EncorePlaybackAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2)
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val input=File(args[1]).canonicalFile
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        val evidence=linkedMapOf<String,Long>();var trackId="";var storyKey=""
        fun report(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("track",trackId);put("protocol",AudioProtocol.BUILD_ID)
            evidence.forEach{(key,value)->put(key,value)};error?.let{put("error",it.take(200))}
        }.toString())}
        suspend fun await(test:()->Boolean){withTimeout(45000){while(!test())delay(25)}}
        suspend fun command(host:DesktopHost,value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
        fun period()=EncorePeriod.containing(EncoreRange.All,LocalDate.now(),EncorePeriod.firstDayOfWeek(),null)
        suspend fun summary(host:DesktopHost)=withTimeout(15000){checkNotNull(host.observeEncore(period()).filterNotNull().first())}
        try{withTimeout(180000){
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};command(host,DesktopCommand.SetVolume(0f));command(host,DesktopCommand.AddFileToQueue(input))
                await{!host.snapshot.busy&&host.snapshot.queueEntries.size==1};trackId=host.snapshot.currentTrack.id
                command(host,DesktopCommand.ToggleRepeat)
                for(rate in listOf(1f,2f)){
                    command(host,DesktopCommand.SetPlaybackSpeed(rate));await{!host.snapshot.busy}
                    command(host,DesktopCommand.SetPlaying(true))
                    await{host.snapshot.isPlaying&&host.clockAnchor.value?.let{it.playbackRate==rate&&it.samplesRendered>4800}==true}
                    val before=checkNotNull(host.clockAnchor.value)
                    await{host.clockAnchor.value?.let{it.timelineEpoch==before.timelineEpoch&&it.samplesRendered>=before.samplesRendered+288000}==true}
                    val after=checkNotNull(host.clockAnchor.value)
                    command(host,DesktopCommand.SetPlaying(false));await{!host.snapshot.isPlaying};delay(300)
                    val current=summary(host)
                    check(current.listenedMs>0&&current.top(EncoreKind.SONG).any{it.key==trackId})
                    check(current.hours.sum()==current.listenedMs){"Actual hardware history and hourly totals disagree"}
                    evidence["rate${rate}HardwareMeasuredMs"]=(after.samplesRendered-before.samplesRendered)/48
                    evidence["rate${rate}TotalHeardMs"]=current.listenedMs
                    val paused=current.listenedMs;delay(1500);check(summary(host).listenedMs==paused){"Pause inflated heard time"}
                    report("RUNNING")
                }
                val data=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(data.success)
                val diagnostics=Json.parseToJsonElement(checkNotNull(data.details)).jsonObject
                check(diagnostics.getValue("underrunFrames").jsonPrimitive.long==0L)
                check(diagnostics.getValue("decodedPeak").jsonPrimitive.float>.00001f)
                evidence["underrunFrames"]=0
                val current=summary(host);storyKey="${current.period.range}:${current.period.start}"
                command(host,DesktopCommand.MarkEncoreStorySeen(current.period));check(storyKey in host.snapshot.encoreSeen)
            }
            // Actual close drains queued history, then a new host/worker reads the same authority.
            val durable=SqliteLibraryRepository(File(profile,"library")).use{store->
                com.musicglass.data.encore.EncoreRepository(com.musicglass.data.encore.EncoreReadDatabase(store)).use{repository->
                repository.summary(listOf(Scopes.LOCAL,Scopes.GUEST),EncorePeriod.containing(EncoreRange.All,LocalDate.now(),EncorePeriod.firstDayOfWeek(),repository.historyStart(listOf(Scopes.LOCAL,Scopes.GUEST))),System.currentTimeMillis()).listenedMs
                }
            }
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy};check(!host.snapshot.isPlaying)
                val restored=summary(host);check(restored.listenedMs==durable);check(storyKey in host.snapshot.encoreSeen);evidence["storySeenRestored"]=1;check(restored.top(EncoreKind.SONG).any{it.key==trackId})
                check(host.encoreTrack(trackId)?.id==trackId);evidence["restoredMusicHeardMs"]=restored.listenedMs
                command(host,DesktopCommand.ClearHistory)
                await{host.snapshot.encore?.listenedMs==0L};check(host.snapshot.encoreSeen.isEmpty());check(summary(host).empty);delay(1200);check(summary(host).empty)
                evidence["heardAfterClearAndWriterDrainMs"]=0
            }
            report("PASS")
        }}catch(failure:Throwable){report("FAIL",failure.message);throw failure}
    }
}
