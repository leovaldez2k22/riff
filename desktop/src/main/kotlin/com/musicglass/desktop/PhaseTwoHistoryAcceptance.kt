package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Actual native playback, SQLite authority and restart; no window, fake clock or fixture audio runtime. */
internal object PhaseTwoHistoryAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2)
        val output=File(args[0]).canonicalFile.apply{mkdirs()}
        val source=File(args[1]).canonicalFile;check(source.isFile)
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        val evidence=linkedMapOf<String,Long>()
        suspend fun await(host:DesktopHost,seconds:Long=30,test:()->Boolean){withTimeout(seconds*1000){while(!test()){check(host.snapshot.running);delay(50)}}}
        suspend fun command(host:DesktopHost,value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
        fun storedHeard():Long=SqliteLibraryRepository(File(profile,"library")).use{HistoryRepository(it).summary(listOf(Scopes.LOCAL,Scopes.GUEST)).listenedMs}
        DesktopHost(directory=profile).use{host->
            await(host){host.snapshot.initialized};command(host,DesktopCommand.SetVolume(0f));command(host,DesktopCommand.AddFileToQueue(source))
            await(host){!host.snapshot.busy&&host.snapshot.queueEntries.size==1}
            command(host,DesktopCommand.SetPlaying(true));await(host){host.snapshot.isPlaying&&host.snapshot.playbackPositionMs>=1800&&(host.snapshot.encore?.listenedMs?:0)>=500}
            command(host,DesktopCommand.SetPlaying(false));await(host){!host.snapshot.isPlaying}
            val before=storedHeard();check(before>0);evidence["realPresentedHeardBeforeClearMs"]=before
            command(host,DesktopCommand.ClearHistory)
            await(host){host.snapshot.encore?.listenedMs==0L};delay(1500)
            check(storedHeard()==0L);evidence["heardAfterPausedClearAndWriterDrainMs"]=0
        }
        DesktopHost(directory=profile).use{host->
            await(host){host.snapshot.initialized&&!host.snapshot.busy}
            check(!host.snapshot.isPlaying);check(storedHeard()==0L)
            evidence["heardAfterRestartMs"]=0
            command(host,DesktopCommand.SetPlaying(true));await(host){host.snapshot.isPlaying&&(host.snapshot.encore?.listenedMs?:0)>=1000}
            command(host,DesktopCommand.SetHistoryPrivacy(PrivacyPreferences(saveHistory=false)))
            delay(300);val disabled=storedHeard();val position=host.snapshot.playbackPositionMs
            await(host){host.snapshot.isPlaying&&host.snapshot.playbackPositionMs>=position+2500};delay(300)
            check(storedHeard()==disabled){"History grew while actual hardware playback continued with consent disabled"}
            evidence["heardWhileConsentDisabledMs"]=disabled
            command(host,DesktopCommand.SetHistoryPrivacy(PrivacyPreferences(saveHistory=true)))
            await(host){host.snapshot.isPlaying&&(host.snapshot.encore?.listenedMs?:0)>disabled+1000}
            command(host,DesktopCommand.SetPlaying(false));await(host){!host.snapshot.isPlaying}
            val resumed=storedHeard();check(resumed>disabled);evidence["heardAfterReenabledMs"]=resumed
            host.persistPlaybackSnapshot()
        }
        // Graceful close drains the real history writer; compare durable totals after that receipt boundary.
        evidence["heardAfterReenabledMs"]=storedHeard()
        DesktopHost(directory=profile).use{host->
            await(host){host.snapshot.initialized&&!host.snapshot.busy};check(!host.snapshot.isPlaying)
            check(storedHeard()==evidence.getValue("heardAfterReenabledMs"))
            check(host.snapshot.historyPrivacy.saveHistory);evidence["finalPausedPositionMs"]=host.snapshot.playbackPositionMs
        }
        File(output,"acceptance-${profile.name}.json").writeText(buildJsonObject{put("status","PASS");put("muted",true);put("profile",profile.path);evidence.forEach{(key,value)->put(key,value)}}.toString())
        println("REAL_HISTORY_AUTHORITY_PASS "+evidence.toString())
    }
}
