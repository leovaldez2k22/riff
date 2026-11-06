package com.musicglass.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

internal data class LegacyQueueImport(val paths:List<String>,val current:String,val positionMs:Long)
/** Read-only legacy adapter. Unescaped delimiters and fabricated IDs are never guessed into tracks. */
internal object LegacySessionImporter {
    fun inspect(legacy:File,destination:File):LegacyQueueImport? {
        val files=listOf(File(legacy,"library-state.db"),File(legacy,"library-state.db-wal")).filter{it.isFile}
        if(files.isEmpty())return null
        val lines=mutableListOf<String>()
        val backups=File(destination,"legacy-backup").apply{mkdirs()}
        for(file in files) {
            require(file.length()<=8*1024*1024){"Legacy preview storage exceeds safe import size"}
            val bytes=file.readBytes()
            val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val backup=File(backups,"${file.name}.$digest.original")
            if(!backup.exists())FileChannel.open(backup.toPath(),CREATE_NEW,WRITE).use{channel->val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true)}
            check(backup.readBytes().contentEquals(bytes)){"Legacy backup digest mismatch"}
            val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(text.isEmpty() || text.endsWith("\n")){"Legacy preview journal is incomplete; original backup preserved"}
            lines+=text.lineSequence().filter{it.isNotEmpty()}.toList()
        }
        require(lines.size<=50000)
        val snapshots=lines.filter{it.startsWith("SNAP|default|")}.map {line->
            val parts=line.split('|');require(parts.size==6){"Ambiguous legacy delimiters; backup preserved"}
            val position=parts[3].toLong();val stamp=parts[4].toLong();require(position>=0&&stamp>=0)
            Triple(parts,position,stamp)
        }
        val last=snapshots.maxByOrNull{it.third}?:return null
        val paths=last.first[5].split(',').filter{it.isNotBlank()}
        require(paths.size<=5000 && paths.all {it==File(it).absolutePath && File(it).isFile && !it.contains('|')}) {"Legacy queue includes preview IDs or unavailable/ambiguous files; reimport the originals"}
        require(paths.contains(last.first[2])){"Legacy current track is absent from its queue"}
        return LegacyQueueImport(paths,last.first[2],last.second)
    }
}
