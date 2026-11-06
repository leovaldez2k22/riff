package com.musicglass.desktop
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** Actual canonical commands, independent real decoder decks, hardware progression and persisted settings. */
internal object CanonicalEqualizerAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val input=File(args[1]).canonicalFile
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        val configurations=EQ_PRESETS.map{(name,gains)->EqualizerConfig(true,EqMode.Manual,manual=gains,preset=name)}+
            listOf(EqualizerConfig(),EqualizerConfig(true,EqMode.Dynamic,-5,5),EqualizerConfig(true,EqMode.Dynamic,5,-5,true),EqualizerConfig(true,EqMode.Dynamic,2,3,true,balance=-.5f))
        var tested=0;var heard=0L;var restored=false
        fun report(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("actualHardwareConfigurations",tested);put("presentedFramesAfterConfiguration",heard);put("settingsRestoredAndPlayed",restored);put("protocol",AudioProtocol.BUILD_ID);error?.let{put("error",it.take(180))}
        }.toString())}
        suspend fun await(test:()->Boolean){withTimeout(30000){while(!test())delay(10)}}
        fun diagnostics(host:DesktopHost):JsonObject{val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success);return Json.parseToJsonElement(checkNotNull(result.details)).jsonObject}
        try{withTimeout(180000){
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};host.dispatch(DesktopCommand.SetVolume(0f));host.dispatch(DesktopCommand.AddFileToQueue(input));host.awaitIdle().get();await{!host.snapshot.busy&&host.snapshot.queueEntries.isNotEmpty()}
                host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying};val worker=host.audioBridge.workerPid
                for(config in configurations){
                    host.dispatch(DesktopCommand.SetEqualizer(config));host.awaitIdle().get();check(host.snapshot.equalizerConfig==config)
                    val before=checkNotNull(host.clockAnchor.value)
                    await{host.clockAnchor.value?.let{it.timelineEpoch==before.timelineEpoch&&it.samplesRendered>=before.samplesRendered+4800}==true}
                    heard+=checkNotNull(host.clockAnchor.value).samplesRendered-before.samplesRendered
                    val data=diagnostics(host);check(data.getValue("canonicalEqualizer").jsonPrimitive.boolean);check(data.getValue("equalizerMode").jsonPrimitive.content==config.mode.key);check(data.getValue("equalizerEnabled").jsonPrimitive.boolean==config.enabled)
                    check(data.getValue("underrunFrames").jsonPrimitive.long==0L);check(data.getValue("decodedPeak").jsonPrimitive.float>.00001f);check(host.audioBridge.workerPid==worker)
                    tested++
                }
                host.dispatch(DesktopCommand.SetPlaying(false));host.awaitIdle().get()
            }
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy};check(host.snapshot.equalizerConfig==configurations.last());check(!host.snapshot.isPlaying)
                host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying&&host.clockAnchor.value?.samplesRendered?.let{it>4800}==true}
                val data=diagnostics(host);check(data.getValue("canonicalEqualizer").jsonPrimitive.boolean);check(data.getValue("equalizerMode").jsonPrimitive.content==configurations.last().mode.key);check(data.getValue("underrunFrames").jsonPrimitive.long==0L);restored=true
            }
            report("PASS")
        }}catch(error:Throwable){report("FAIL",error.message);throw error}
    }
}
