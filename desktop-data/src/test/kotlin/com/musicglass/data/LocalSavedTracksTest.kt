package com.musicglass.data
import java.io.File
import kotlin.test.*
class LocalSavedTracksTest {
    @Test fun savedMembershipIsRealScopedStorageAndSurvivesRestart(){
        val dir=kotlin.io.path.createTempDirectory("riff-saved-").toFile()
        val original=TrackRecord(Scopes.GUEST,"youtube:abcdefghijk","Provider recording",sourceKind="youtube",isEpisode=true)
        try{
            SqliteLibraryRepository(dir).use{store->
                val repo=PlaylistRepository(store);assertFalse(repo.isSaved(original.id));assertTrue(repo.toggleSaved(original));assertTrue(repo.isSaved(original.id))
                assertEquals(1,repo.count(Scopes.LOCAL,PlaylistRepository.SAVED_PLAYLIST));assertTrue(repo.entries(Scopes.LOCAL,PlaylistRepository.SAVED_PLAYLIST).single().track.isEpisode)
                // A local playlist copy must refer to the same saved membership, not make a duplicate.
                assertFalse(repo.toggleSaved(original.copy(scope=Scopes.LOCAL)));assertEquals(0,repo.count(Scopes.LOCAL,PlaylistRepository.SAVED_PLAYLIST))
                assertTrue(repo.toggleSaved(original));assertFailsWith<IllegalArgumentException>{repo.toggleSaved(original.copy(scope="private_unconfigured"))}
            }
            SqliteLibraryRepository(dir).use{store->val repo=PlaylistRepository(store);assertTrue(repo.isSaved(original.id));assertEquals(1,repo.count(Scopes.LOCAL,PlaylistRepository.SAVED_PLAYLIST));repo.delete(Scopes.LOCAL,PlaylistRepository.SAVED_PLAYLIST);assertFalse(repo.isSaved(original.id));assertNotNull(store.get(Scopes.LOCAL,original.id))}
        }finally{dir.deleteRecursively()}
    }
}
