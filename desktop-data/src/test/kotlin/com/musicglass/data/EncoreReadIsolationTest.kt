package com.musicglass.data

import com.musicglass.data.encore.EncoreReadDatabase
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.test.*

class EncoreReadIsolationTest {
    @Test fun theEncoreConnectionCannotWriteTheLibrary(){
        val directory=kotlin.io.path.createTempDirectory("riff-encore-readonly-").toFile()
        try{SqliteLibraryRepository(directory).use{store->
            store.upsert(TrackRecord(Scopes.LOCAL,"recording","Preserved row"))
            EncoreReadDatabase(store).use{reader->
                val failure=assertFailsWith<java.sql.SQLException>{reader.read("DELETE FROM track RETURNING id",emptyArray()){it.moveToFirst()}}
                assertTrue(failure.message.orEmpty().contains("READONLY",true),failure.message)
                assertNotNull(store.get(Scopes.LOCAL,"recording"))
            }
        }}finally{assertTrue(directory.deleteRecursively(),"Closed Encore readers retained Windows file locks")}
    }
    @Test fun aLongEncoreSnapshotCannotHoldTheProductionHistoryWriter(){
        val directory=kotlin.io.path.createTempDirectory("riff-encore-read-isolation-").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        var failure:Throwable?=null
        try{SqliteLibraryRepository(directory).use{store->
            store.upsert(TrackRecord(Scopes.LOCAL,"recording","Actual storage identity"));val history=HistoryRepository(store)
            EncoreReadDatabase(store).use{reader->
            fun count()=reader.read("SELECT COUNT(*) FROM history_occurrence",emptyArray()){it.moveToFirst();it.getLong(0)}
            val readThread=thread(name="Encore-held-snapshot"){
                try{reader.snapshot{assertEquals(0L,count());entered.countDown();check(release.await(5,TimeUnit.SECONDS));assertEquals(0L,count())}}
                catch(error:Throwable){failure=error}finally{finished.countDown()}
            }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val write=CompletableFuture.runAsync{history.record(ListeningSpan(Scopes.LOCAL,"span","play","recording",System.currentTimeMillis(),5000))}
            try{write.get(1,TimeUnit.SECONDS)}finally{release.countDown()}
            assertTrue(finished.await(3,TimeUnit.SECONDS));readThread.join();failure?.let{throw it}
            assertEquals(1L,count())
            }
        }}finally{release.countDown();directory.deleteRecursively()}
    }
}
