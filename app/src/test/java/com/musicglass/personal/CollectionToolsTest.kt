package com.musicglass.personal
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class CollectionToolsTest {
    private data class Song(val title:String,val artist:String,val date:Long?=null)
    private val songs=listOf(Song("Zulu","Singer",100),Song("Été","Other",300),Song("Alpha","Singer",200),Song("Unknown","Singer"))
    private fun view(query:String="",sort:CollectionSort=CollectionSort.Original)=collectionView(songs,query,sort,{it.title},{it.artist},{it.date})
    @Test fun searchStaysWithinCollectionAndKeepsOriginalQueueIndexes() {
        assertEquals(listOf(1),view("ete").map {it.index});assertEquals(listOf(0,2,3),view("singer").map {it.index});assertEquals(listOf(2),view("singer alpha").map {it.index});assertTrue(view("not in collection").isEmpty())
    }
    @Test fun alphabeticalSortingAndFilteringDoNotLoseNativeQueuePosition() {
        assertEquals(listOf(2,1,3,0),view(sort=CollectionSort.Title).map {it.index})
        assertEquals(listOf(0,3,1,2),view(sort=CollectionSort.TitleDescending).map {it.index})
        assertEquals(2,view("alpha",CollectionSort.Title).single().index)
    }
    @Test fun dateSortingKeepsUnknownDatesLast() {
        assertEquals(listOf(1,2,0,3),view(sort=CollectionSort.Newest).map {it.index})
        assertEquals(listOf(0,2,1,3),view(sort=CollectionSort.Oldest).map {it.index})
    }
    @Test fun batchPlaylistSaveKeepsOnlyFailuresForRetry()=runBlocking {
        val calls=mutableListOf<String>()
        val result=addToSelectedPlaylists(linkedSetOf("one","two","three")) {id->calls+=id;if(id=="two") Result.failure(IllegalStateException("Read-only")) else Result.success(Unit)}
        assertEquals(listOf("one","two","three"),calls);assertEquals(setOf("one","three"),result.completed)
        assertEquals(setOf("two"),result.failures.keys)
        val retry=addToSelectedPlaylists(result.failures.keys) {Result.success(Unit)};assertEquals(setOf("two"),retry.completed)
    }
    @Test fun canceledBatchDoesNotContinueMutatingPlaylists()=runBlocking {
        val calls=mutableListOf<String>()
        val result=runCatching {addToSelectedPlaylists(linkedSetOf("one","two")) {id->calls+=id;Result.failure(CancellationException("Dismissed"))}}
        assertTrue(result.exceptionOrNull() is CancellationException);assertEquals(listOf("one"),calls)
    }
}
