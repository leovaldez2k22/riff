package com.musicglass.personal

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * F2 on a device (Q19F3): the shared card is a content:// picture the receiver can read and nothing else in Riff's
 * files is reachable through it; Save adds exactly one picture to Pictures/Riff, and nothing when it fails. Every
 * picture this test saves is deleted again.
 */
class LyricShareDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker = "Riff lyrics Q19 device test"
    private fun card() = renderLyricCard(listOf("Is this the real life?"), "Q19 device test", "Riff", LyricCardLook.Aurora, 0)

    private fun saved(): List<Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val found = mutableListOf<Uri>()
        context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("$marker%"), null)?.use { rows ->
            while (rows.moveToNext()) {
                assertTrue(rows.getString(1).trimEnd('/') == "Pictures/Riff")
                found += ContentUris.withAppendedId(collection, rows.getLong(0))
            }
        }
        return found
    }
    @After fun removeWhatThisTestSaved() { saved().forEach { context.contentResolver.delete(it, null, null) } }

    @Test fun theSharedCardIsAReadableContentPictureOfTheCard() {
        val image = card()
        val intent = lyricCardShareIntent(context, image)
        // Q18-045: IntentCompat, since the two-argument getParcelableExtra exists only from API 33 and Riff starts at 31.
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        val read = context.contentResolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)
        assertEquals(image.width to image.height, read.width to read.height)
    }

    @Test fun nothingOutsideTheSharedLyricsFolderCanBeShared() {
        val other = File(context.cacheDir, "not-shared.txt").apply { writeText("private") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.files", other)
            }
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.files", File(context.filesDir, "anything.png"))
            }
        } finally { other.delete() }
    }

    @Test fun saveAddsOnePictureToPicturesRiffAndAFailedSaveAddsNone() {
        removeWhatThisTestSaved()
        assertTrue(saveLyricCard(context, card(), "Q19 device test"))
        assertEquals(1, saved().size)
        val broken = card().also { it.recycle() }
        assertFalse(saveLyricCard(context, broken, "Q19 device test"))
        assertEquals(1, saved().size)
    }
}
