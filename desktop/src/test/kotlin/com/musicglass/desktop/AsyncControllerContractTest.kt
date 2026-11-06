package com.musicglass.desktop

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.*

/** Control races only: this test explicitly uses test audio and does not certify production playback. */
class AsyncControllerContractTest {
    private class HeldOperations:Executor{
        val pending=ArrayDeque<Runnable>()
        override fun execute(command:Runnable){pending.add(command)}
        fun drain(){while(pending.isNotEmpty())pending.removeFirst().run()}
    }
    private fun withHost(test:(DesktopHost,HeldOperations)->Unit){
        val directory=kotlin.io.path.createTempDirectory("riff-actor-race-").toFile()
        val entries=FixtureTracks.all.map{QueueEntry(song=it)};val operations=HeldOperations()
        try{DesktopHost(audioBridge=testAudioBridge(),directory=directory,executor=Executor{it.run()},audioExecutor=operations,
            initialState=HostSnapshot(currentTrack=entries.first().song,currentEntryId=entries.first().queueId,queueEntries=entries),
            describeSource={DurableAudioSource.Provider(it)},enableDataLayer=false,searchProvider={emptyList()}).use{test(it,operations)}}
        finally{directory.deleteRecursively()}
    }
    @Test fun pauseWhileQueueIsLoadingCannotBeUndoneByItsLateCompletion()=withHost{host,operations->
        assertTrue(host.snapshot.busy)
        host.dispatch(DesktopCommand.SetPlaying(true));assertTrue(host.snapshot.playWhenReady)
        host.dispatch(DesktopCommand.SetPlaying(false));assertFalse(host.snapshot.playWhenReady)
        operations.drain();assertFalse(host.snapshot.isPlaying);assertFalse(host.snapshot.playWhenReady);assertFalse(host.snapshot.busy)
    }
    @Test fun supersededQueueLoadCannotReplaceTheNewSelectedTrack()=withHost{host,operations->
        val next=host.snapshot.queueEntries[1]
        host.dispatch(DesktopCommand.NextTrack)
        operations.drain()
        assertEquals(next.queueId,host.snapshot.currentEntryId);assertTrue(host.snapshot.isPlaying)
    }
    @Test fun lateQueueLoadCannotDiscardNewerArtworkMetadata()=withHost{host,operations->
        // A metadata-only update during held source resolution must survive its completion.
        val entries=host.snapshot.queueEntries
        val stateField=DesktopHost::class.java.getDeclaredField("mutableState").apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST") val state=stateField.get(host) as kotlinx.coroutines.flow.MutableStateFlow<HostSnapshot>
        val enriched=entries.map{it.copy(song=it.song.copy(artworkKey="a".repeat(64)))}
        state.value=host.snapshot.copy(queueEntries=enriched,currentTrack=enriched.first().song)
        operations.drain()
        assertEquals("a".repeat(64),host.snapshot.currentTrack.artworkKey)
        assertTrue(host.snapshot.queueEntries.all{it.song.artworkKey=="a".repeat(64)})
    }
    @Test fun quittingBeforeSourceLoadCompletesPreservesTheDesiredCheckpoint(){
        val directory=kotlin.io.path.createTempDirectory("riff-quit-pending-").toFile()
        val operations=HeldOperations();val entry=QueueEntry(song=FixtureTracks.all.first())
        try{
            DesktopHost(audioBridge=testAudioBridge(),directory=directory,executor=Executor{it.run()},audioExecutor=operations,
                initialState=HostSnapshot(currentTrack=entry.song,currentEntryId=entry.queueId,queueEntries=listOf(entry),playbackPositionMs=65000),
                describeSource={DurableAudioSource.Provider(it)},enableDataLayer=false,searchProvider={emptyList()}).use{host->
                assertTrue(host.snapshot.busy)
                host.dispatch(DesktopCommand.Quit)
                assertEquals(65000L,host.snapshot.playbackPositionMs)
            }
            DesktopSessionRepository(directory).use{db->
                val saved=assertNotNull(db.load())
                assertEquals(65000L,saved.getValue("position").let{(it as kotlinx.serialization.json.JsonPrimitive).content.toLong()})
            }
        }finally{directory.deleteRecursively()}
    }
    @Test fun pauseDuringSeekRemainsAuthoritativeWhenTheSeekCompletes()=withHost{host,operations->
        operations.drain();host.dispatch(DesktopCommand.SetPlaying(true));assertTrue(host.snapshot.isPlaying)
        host.dispatch(DesktopCommand.SeekTo(2500));assertTrue(host.snapshot.busy)
        host.dispatch(DesktopCommand.SetPlaying(false));assertFalse(host.snapshot.playWhenReady)
        operations.drain();assertFalse(host.snapshot.isPlaying);assertFalse(host.snapshot.playWhenReady);assertEquals(2500L,host.snapshot.playbackPositionMs)
    }

    @Test fun crossfadeReloadDuringSeekPreservesTheDesiredPlayingState()=withHost{host,operations->
        operations.drain();host.dispatch(DesktopCommand.SetPlaying(true));assertTrue(host.snapshot.playWhenReady)
        host.dispatch(DesktopCommand.SeekTo(1000));assertTrue(host.snapshot.busy);assertFalse(host.snapshot.isPlaying)
        host.dispatch(DesktopCommand.SetCrossfadeEnabled(true))
        assertTrue(host.snapshot.playWhenReady,"A temporary stopped seek state cannot replace the user's Play intent")
        operations.drain();assertTrue(host.snapshot.isPlaying);assertTrue(host.snapshot.playWhenReady);assertEquals(1000L,host.snapshot.playbackPositionMs)
    }
    @Test fun repeatReloadDuringSeekPreservesTheDesiredPlayingState()=withHost{host,operations->
        operations.drain();host.dispatch(DesktopCommand.SetPlaying(true));host.dispatch(DesktopCommand.SeekTo(1000))
        host.dispatch(DesktopCommand.ToggleRepeat);operations.drain()
        assertTrue(host.snapshot.isPlaying);assertTrue(host.snapshot.playWhenReady)
    }
    @Test fun pauseStillWinsWhenCrossfadeSupersedesAnInFlightSeek()=withHost{host,operations->
        operations.drain();host.dispatch(DesktopCommand.SetPlaying(true));host.dispatch(DesktopCommand.SeekTo(1000))
        host.dispatch(DesktopCommand.SetPlaying(false));host.dispatch(DesktopCommand.SetCrossfadeEnabled(true));operations.drain()
        assertFalse(host.snapshot.isPlaying);assertFalse(host.snapshot.playWhenReady)
    }
}
