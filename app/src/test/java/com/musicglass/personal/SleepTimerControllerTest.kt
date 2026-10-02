package com.musicglass.personal
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class SleepTimerControllerTest {
    @Test fun expiryPausesOnceAndClearsDeadline()=runBlocking {
        var now=1000L;var pauses=0;val deadlines=mutableListOf<Long>()
        val entered=CompletableDeferred<Long>();val release=CompletableDeferred<Unit>()
        val timer=SleepTimerController(this,{now},wait={entered.complete(it);release.await()},pause={pauses++},publish={deadlines+=it})
        timer.set(1);assertEquals(45_000L,entered.await());now=61_000;release.complete(Unit);yield()
        assertEquals(1,pauses);assertEquals(listOf(61_000L,0L),deadlines)
    }
    @Test fun cancellationPreventsLatePause()=runBlocking {
        var pauses=0;val entered=CompletableDeferred<Unit>()
        val timer=SleepTimerController(this,{1000L},wait={entered.complete(Unit);awaitCancellation()},pause={pauses++},publish={})
        timer.set(5);entered.await();timer.set(0);yield();assertEquals(0,pauses);timer.close()
    }
    @Test fun finalFifteenSecondsFadeAndRestoreAfterPause()=runBlocking {
        var now=1000L;var volume=.8f;val levels=mutableListOf<Float>();var levelAtPause=-1f;var pauses=0
        val timer=SleepTimerController(this,{now},wait={now+=it;yield()},pause={levelAtPause=volume;pauses++},publish={},readVolume={volume},writeVolume={volume=it;levels+=it})
        timer.set(1);while(pauses==0) yield()
        assertEquals(0f,levelAtPause,0f);assertEquals(.8f,volume,0f)
        val fade=levels.dropLast(1);assertEquals(.8f,fade.first(),0f)
        assertTrue(fade.zipWithNext().all {(a,b)->b<=a});assertTrue(fade.size>=150)
        assertEquals(1,pauses);timer.close()
    }
    @Test fun cancellingDuringFadeRestoresVolumeWithoutPausing()=runBlocking {
        var now=0L;var volume=.6f;var pauses=0;val fading=CompletableDeferred<Unit>()
        val timer=SleepTimerController(this,{now},wait={if(it>100) {now+=it+7500} else {fading.complete(Unit);awaitCancellation()}},pause={pauses++},publish={},readVolume={volume},writeVolume={volume=it})
        timer.set(1);fading.await();assertEquals(.3f,volume,.0001f)
        timer.set(0);yield();assertEquals(.6f,volume,0f);assertEquals(0,pauses);timer.close()
    }
    @Test fun replacingTimerDuringFadeRestoresLevelAndIgnoresCancelledJob()=runBlocking {
        var now=0L;var volume=.7f;var pauses=0;val fading=CompletableDeferred<Unit>();var calls=0
        val timer=SleepTimerController(this,{now},wait={calls++;if(calls==1) now+=it+7500 else {fading.complete(Unit);awaitCancellation()}},pause={pauses++},publish={},readVolume={volume},writeVolume={volume=it})
        timer.set(1);fading.await();assertTrue(volume<.7f);timer.set(10);yield()
        assertEquals(.7f,volume,0f);assertEquals(0,pauses);timer.close()
    }
}
