package com.musicglass.data
import kotlin.test.*
class EncoreGenreCacheTest {
    @Test fun optInScopeAndHistoryEpochFenceActualCacheWrites(){
        val directory=kotlin.io.path.createTempDirectory("riff-genre-cache-").toFile()
        try{SqliteLibraryRepository(directory).use{store->
            val history=HistoryRepository(store);val cache=EncoreGenreCache(store)
            val fact=CachedArtistGenre(Scopes.LOCAL,"Artist", "00000000-0000-0000-0000-000000000001","Ready",listOf("Electronic"),System.currentTimeMillis())
            assertFalse(cache.put(fact,history.epoch(Scopes.LOCAL)));assertNull(cache.get(Scopes.LOCAL,"Artist"))
            store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(genreLookup=true))
            val epoch=history.epoch(Scopes.LOCAL);assertTrue(cache.put(fact,epoch));assertEquals(fact,cache.get(Scopes.LOCAL,"Artist"));assertNull(cache.get(Scopes.GUEST,"Artist"))
            history.clear(Scopes.LOCAL);assertNull(cache.get(Scopes.LOCAL,"Artist"));assertFalse(cache.put(fact,epoch))
            store.setPrivacy(Scopes.LOCAL,PrivacyPreferences(genreLookup=false));assertFalse(cache.put(fact,history.epoch(Scopes.LOCAL)))
        }}finally{directory.deleteRecursively()}
    }
}
