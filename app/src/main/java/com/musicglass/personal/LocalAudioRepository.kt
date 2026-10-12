package com.musicglass.personal

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalAudioTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/** Reads only metadata supplied by Android MediaStore after the user grants audio access. */
internal class LocalAudioRepository(private val context: Context) {
    suspend fun list(): Result<List<LocalAudioTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val columns = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
            )
            val tracks = mutableListOf<LocalAudioTrack>()
            val cursor = context.contentResolver.query(source, columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")
                ?: error("MediaStore audio query returned no cursor")
            cursor.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    tracks += LocalAudioTrack(id, ContentUris.withAppendedId(source, id),
                        cursor.getString(titleColumn).orEmpty().ifBlank { string(R.string.unknown_track) },
                        cursor.getString(artistColumn).orEmpty().ifBlank { string(R.string.unknown_artist) },
                        cursor.getString(albumColumn).orEmpty(),
                        cursor.getLong(durationColumn).coerceAtLeast(0L))
                }
            }
            tracks
        }
    }
}
