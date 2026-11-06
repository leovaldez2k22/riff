package com.musicglass.desktop

import com.musicglass.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.*

class PhaseTwoIntegrationTest {
    @Test fun dpapiActuallyEncryptsAndRejectsTamperedCiphertext() {
        val directory=kotlin.io.path.createTempDirectory("riff-dpapi-").toFile()
        try{
            val vault=DpapiCredentialVault(directory);val secret="test-only-secret-do-not-log".toByteArray()
            vault.store("guest","refresh",secret)
            assertContentEquals(secret,vault.load("guest","refresh"))
            val file=directory.listFiles()!!.single();assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(String(secret)))
            val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
            assertFailsWith<IllegalStateException>{vault.load("guest","refresh")}
            vault.delete("guest","refresh");assertNull(vault.load("guest","refresh"))
        }finally{directory.deleteRecursively()}
    }
    @Test fun accountGenerationRejectsLateAndReusedScopeResponses():Unit=runBlocking {
        val accounts=AccountSessionCoordinator();val anonymous=accounts.token.value
        val first=accounts.transitionVerified("UC"+"a".repeat(22));assertFalse(accounts.isCurrent(anonymous))
        accounts.signOut();val second=accounts.transitionVerified("UC"+"a".repeat(22))
        assertEquals(first.scope,second.scope);assertFalse(accounts.isCurrent(first));assertTrue(accounts.isCurrent(second))
    }
    @Test fun realImageCacheKeepsContentAndPalettePairedAcrossReopen():Unit=runBlocking {
        val directory=kotlin.io.path.createTempDirectory("riff-art-").toFile()
        try{
            val repo=ArtworkRepository(File(directory,"art"),File(directory,"incoming"))
            fun encoded(rgb:Int):ByteArray {val image=BufferedImage(256,128,BufferedImage.TYPE_INT_RGB);val pixels=IntArray(256*128){rgb};image.setRGB(0,0,256,128,pixels,0,256);return java.io.ByteArrayOutputStream().use{out->ImageIO.write(image,"png",out);out.toByteArray()}}
            val a=async{repo.ingest(encoded(0xffff0000.toInt()))};val b=async{repo.ingest(encoded(0xff0000ff.toInt()))}
            val red=a.await();val blue=b.await();assertNotEquals(red.key,blue.key);assertNotEquals(red.dominant,blue.dominant)
            val reopened=ArtworkRepository(File(directory,"art"),File(directory,"incoming"))
            assertEquals(red.dominant,reopened.metadata(red.key)!!.dominant);assertContentEquals(red.mesh,reopened.metadata(red.key)!!.mesh)
            assertNotNull(reopened.bitmap(red.key));assertNotNull(reopened.bitmap(blue.key))
            assertFailsWith<IllegalArgumentException>{repo.ingest(byteArrayOf(1,2,3))}
        }finally{directory.deleteRecursively()}
    }
    @Test fun memoryArtworkIsReusedAcrossSurfacesAndEvictedDiskContentCanBeReacquired():Unit=runBlocking{
        // Image/cache contract only: the source path locates folder art; it is not a codec fixture.
        val directory=kotlin.io.path.createTempDirectory("riff-art-reacquire-").toFile()
        try{
            val music=File(directory,"music").apply{mkdirs()}
            val cover=File(music,"cover.png");ImageIO.write(BufferedImage(40,40,BufferedImage.TYPE_INT_RGB),"png",cover)
            val repo=ArtworkRepository(File(directory,"art"),File(directory,"incoming"))
            val record=repo.obtain(TrackRecord(Scopes.LOCAL,"track","Track",sourcePath=File(music,"actual-source-location.flac").path))
            val key=assertNotNull(record.artworkKey);val bitmap=assertNotNull(repo.bitmap(key))
            assertSame(bitmap,repo.cachedBitmap(key),"Moving between main and Mini-Player should reuse the decoded bitmap")
            assertTrue(File(directory,"art/$key.png").delete())
            val reopened=ArtworkRepository(File(directory,"art"),File(directory,"incoming"))
            val restored=reopened.obtain(record)
            assertEquals(key,restored.artworkKey);assertNotNull(reopened.bitmap(key))
            assertEquals(0L,repo.networkFetches.get());assertEquals(0L,reopened.networkFetches.get())
        }finally{directory.deleteRecursively()}
    }
    @Test fun fullReconciliationPreservesRenameAndHardlinkAndMarksDeletesUnavailable():Unit=runBlocking {
        val directory=kotlin.io.path.createTempDirectory("riff-watch-").toFile()
        try{SqliteLibraryRepository(File(directory,"db")).use{store->
            val music=File(directory,"音楽 café").apply{mkdirs()};val original=File(music,"first.flac").apply{writeBytes(byteArrayOf(1,2,3))}
            // This test isolates scanner identity/reconciliation; real compressed decoding is gated by nativeAudioTest.
            val scanner=LibraryScanner(store,{file->val attrs=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java);TrackRecord("local",file.canonicalPath,file.name,sourcePath=file.canonicalPath,fileIdentity=WindowsFileIdentity.of(file))})
            val root=LocalRoot("root",music.canonicalPath)
            assertTrue(scanner.scan(root).toList().last().complete);val id=store.tracks("local").single().id
            val renamed=File(music,"renamed.flac");assertTrue(original.renameTo(renamed));scanner.scan(root).toList()
            assertEquals(id,store.tracks("local").single().id);assertEquals(renamed.canonicalPath,store.get("local",id)!!.sourcePath)
            val link=File(music,"linked.flac");java.nio.file.Files.createLink(link.toPath(),renamed.toPath());scanner.scan(root).toList();assertEquals(1L,store.count("local"))
            renamed.delete();scanner.scan(root).toList();assertTrue(store.get("local",id)!!.available)
            link.delete();scanner.scan(root).toList();assertFalse(store.get("local",id)!!.available)
        }}finally{directory.deleteRecursively()}
    }
    @Test fun parserPreservesPlaylistOccurrencesAndExactBrowseIds(){
        val row="""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Recorded","navigationEndpoint":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}}}]}}}],"playlistItemData":{"videoId":"dQw4w9WgXcQ"}}}"""
        val page=parseDesktopCatalogPage(kotlinx.serialization.json.Json.parseToJsonElement("""{"musicPlaylistShelfRenderer":{"contents":[$row,$row],"continuations":[{"nextContinuationData":{"continuation":"exact-token"}}]}}"""))
        assertEquals(2,page.cards.size);assertEquals("exact-token",page.next);assertEquals("dQw4w9WgXcQ",page.cards.first().videoId)
    }
}
