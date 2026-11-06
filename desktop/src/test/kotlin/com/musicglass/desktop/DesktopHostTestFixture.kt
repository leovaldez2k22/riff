package com.musicglass.desktop

import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/** Each test owns its database; never read or modify the person's Riff library. */
internal class DesktopHostTestFixture : ExternalResource() {
    private val temporary = TemporaryFolder()
    private val hosts = mutableListOf<DesktopHost>()
    override fun before() { temporary.create() }
    fun create(): DesktopHost {
        val entries=FixtureTracks.all.map{QueueEntry(song=it)}
        return DesktopHost(audioBridge=testAudioBridge(),directory=temporary.newFolder(),
            executor=java.util.concurrent.Executor{it.run()},
            audioExecutor=java.util.concurrent.Executor{it.run()},
            initialState=HostSnapshot(searchSuggestionsEnabled=false,currentTrack=entries.first().song,currentEntryId=entries.first().queueId,queueEntries=entries,libraryTracks=FixtureTracks.all),
            describeSource={DurableAudioSource.Provider(it)},
            enableDataLayer=false, searchProvider={emptyList()}).also{hosts+=it}
    }
    override fun after() {
        try { hosts.forEach { it.close() } }
        finally { temporary.delete() }
    }
}