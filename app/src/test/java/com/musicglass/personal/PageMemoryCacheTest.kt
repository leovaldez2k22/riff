package com.musicglass.personal
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PageMemoryCacheTest {
    private fun local(value:String)=ZonedDateTime.parse(value+"+05:30[Asia/Kolkata]")
    @Test fun localSixAndEighteenAreExactBoundaries() {
        assertEquals(local("2026-10-01T18:00:00").toInstant(),feedWindow(local("2026-10-02T05:59:59")))
        assertEquals(local("2026-10-02T06:00:00").toInstant(),feedWindow(local("2026-10-02T06:00:00")))
        assertEquals(local("2026-10-02T18:00:00").toInstant(),feedWindow(local("2026-10-02T18:00:00")))
        assertEquals(local("2026-10-03T06:00:00").toInstant(),nextFeedRefresh(local("2026-10-02T18:00:00")))
    }
    @Test fun sharedFetchSurvivesLeavingATab()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,false);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
            val first=launch {cache.get<String>("home") {calls++;entered.complete(Unit);release.await();"feed"}}
            entered.await();first.cancelAndJoin();release.complete(Unit)
            assertEquals("feed",cache.get<String>("home") {error("Unexpected second request")})
            assertEquals("feed",cache.peek<String>("home"));assertEquals(1,calls)
        } finally {owner.cancel()}
    }
    @Test fun boundariesInvalidateAllFeedsButNotCollections()=runBlocking {
        var now=local("2026-10-02T17:59:59");val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,true) {now};val library=PageMemoryCache(owner,false) {now}
            cache.get("home") {"old"};cache.get("radio") {"stations"};library.get("playlist") {"songs"}
            now=local("2026-10-02T18:00:00");cache.checkWindow();library.checkWindow()
            assertNull(cache.peek<String>("home"));assertNull(cache.peek<String>("radio"));assertEquals("songs",library.peek<String>("playlist"))
            assertEquals("fresh",cache.get("home") {"fresh"})
        } finally {owner.cancel()}
    }
    @Test fun failedRequestCanRetryAndAccountKeysAreSeparate()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,false)
            assertTrue(runCatching {cache.get("1:home") {error("Offline")}}.isFailure)
            assertEquals("account one",cache.get("1:home") {"account one"})
            assertEquals("account two",cache.get("2:home") {"account two"})
            cache.clear();assertNull(cache.peek<String>("1:home"))
        } finally {owner.cancel()}
    }
    @Test fun canceledGenerationCannotPublishStaleResults()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,false);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val old=async {runCatching {cache.get("home") {entered.complete(Unit);withContext(NonCancellable) {release.await()};"stale"}}}
            entered.await();cache.clear();release.complete(Unit);old.await()
            assertNull(cache.peek<String>("home"));assertEquals("fresh",cache.get("home") {"fresh"})
        } finally {owner.cancel()}
    }
    @Test fun invalidatingOnePlaylistKeepsTheOther()=runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,false);cache.put("VL123","one");cache.put("VL1234","two")
            cache.invalidate("VL123");assertNull(cache.peek<String>("VL123"));assertEquals("two",cache.peek<String>("VL1234"))
        } finally {owner.cancel()}
    }
    @Test fun refreshAdaptsToDeviceTimeZoneAndDst() {
        val now=ZonedDateTime.of(2026,11,1,1,30,0,0,ZoneId.of("America/New_York"))
        val next=nextFeedRefresh(now).atZone(now.zone)
        assertEquals(6,next.hour);assertEquals(now.toLocalDate(),next.toLocalDate())
    }
    @Test fun browsingManyVideoPairsCannotEvictRetainedTabFeeds() {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val cache=PageMemoryCache(owner,false,retain={it.startsWith("home:") || it=="radio"})
            val feed=Any();cache.put("home:",feed);cache.put("radio",feed)
            repeat(300) {cache.put("video-pair:$it",Any())}
            assertSame(feed,cache.peek<Any>("home:"));assertSame(feed,cache.peek<Any>("radio"))
            cache.clear();assertNull(cache.peek<Any>("home:"))
        } finally {owner.cancel()}
    }

    @Test fun replacementProcessStartsWithoutPreviousHomeFeed() {
        val oldOwner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val newOwner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val old=PageMemoryCache(oldOwner,false);old.put("home:","old recommendations")
            val fresh=PageMemoryCache(newOwner,false)
            assertNull(fresh.peek<String>("home:"))
            old.clear();assertNull(old.peek<String>("home:"))
        } finally {oldOwner.cancel();newOwner.cancel()}
    }

}
