package com.musicglass.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

/** Import-only reader. Original files are never opened for writing, upgraded, or removed. */
class LegacyDesktopStoreReader(private val store:SqliteLibraryRepository) {
    fun importFile(source:File,expectedSchema:Int?=null,checkpoint:(String)->Unit={}):ImportAudit {
        require(source.isFile&&source.length()<=32L*1024*1024)
        val bytes=source.inputStream().use{it.readNBytes(32*1024*1024+1)};require(bytes.size<=32*1024*1024)
        val digest=sha(bytes);val backups=File(store.directory,"migration-backups").apply{mkdirs()}
        val backup=File(backups,"$digest.original");publish(backup,bytes);check(sha(backup.readBytes())==digest)
        checkpoint("backed_up")
        val report=File(backups,"$digest.report.txt")
        val prior=store.query("SELECT * FROM import_receipt WHERE digest=?",digest){ImportAudit(digest,it.getLong("imported"),it.getLong("quarantined"),it.getString("backup_path"),it.getString("report_path"))}.singleOrNull()
        if(prior!=null){publishReport(prior);return prior}
        var imported=0L;var quarantined=0L
        val lines=try{Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().split('\n')}
            catch(_:java.nio.charset.CharacterCodingException){listOf("INVALID_UTF8")}
        require(lines.size<=200000)
        val schemas=lines.filter{it.startsWith("SCHEMA|")}.map{it.removeSuffix("\r").split('|').getOrNull(1)}
        val unsupportedSchema=schemas.any{it !in setOf("1","2")} || (schemas.isEmpty()&&expectedSchema !in setOf(1,2))
        val audit=store.transaction(durable=true){
            lines.forEachIndexed {index,line->
                if(line.isBlank())return@forEachIndexed
                val fields=line.removeSuffix("\r").split('|')
                try {
                    savepoint { require(index!=lines.lastIndex||bytes.lastOrNull()==10.toByte()){"Unterminated legacy record: possible torn journal tail"}; require(!unsupportedSchema){"Unknown schema: no records adopted; original preserved"}; when(fields[0]) {
                        "SCHEMA" -> require(fields.size==2&&fields[1] in setOf("1","2")){"Unsupported legacy schema"}
                        "LIB" -> {
                            require(fields.size==9){"Ambiguous legacy delimiters"}
                            require(fields[1]=="default"){"Unverified legacy owner; no automatic adoption"}
                            val id=fields[2];val local=File(id)
                            val isLocal=local.isAbsolute&&!id.contains(',')
                            val youtube=id.removePrefix("youtube:").removePrefix("yt:")
                            require(isLocal||((id.startsWith("youtube:")||id.startsWith("yt:"))&&youtube.matches(Regex("[A-Za-z0-9_-]{11}")))){"Preview or unresolvable source identity"}
                            val duration=fields[6].toLong();val added=fields[8].toLong();require(duration>=0&&added>=0)
                            val scope=if(isLocal)Scopes.LOCAL else Scopes.GUEST
                            upsert(TrackRecord(scope,if(isLocal)id else "youtube:$youtube",fields[3],fields[4],fields[5],duration,
                                if(isLocal)"local"else"youtube",if(isLocal)id else null,available=!isLocal||local.isFile,artworkUrl=fields[7].takeIf{it.startsWith("https://")},addedMs=added))
                            imported++
                        }
                        "PLAYLIST" -> {
                            require(fields.size==6&&fields[1]=="default"){"Ambiguous or unverified playlist owner"}
                            val ids=fields[5].split(',').filter{it.isNotBlank()};require(ids.size<=5000)
                            val tracks=ids.map{id->get(Scopes.LOCAL,id)?:get(Scopes.GUEST,id)?:error("Playlist references a missing source")}
                            val playlist=UUID.nameUUIDFromBytes("legacy-local-playlist:${fields[2]}".toByteArray()).toString()
                            execute("INSERT INTO playlist VALUES(?,?,?,?,?,NULL) ON CONFLICT(scope,id) DO UPDATE SET title=excluded.title,updated_ms=excluded.updated_ms",Scopes.LOCAL,playlist,fields[3],"Imported legacy playlist",fields[4].toLong())
                            execute("DELETE FROM playlist_entry WHERE scope=? AND playlist_id=?",Scopes.LOCAL,playlist)
                            tracks.forEachIndexed{ordinal,track->upsert(track.copy(scope=Scopes.LOCAL));execute("INSERT INTO playlist_entry VALUES(?,?,?,?,?)",Scopes.LOCAL,playlist,UUID.nameUUIDFromBytes("$playlist:$ordinal".toByteArray()).toString(),ordinal,track.id)}
                            imported++
                        }
                        "SNAP" -> {require(fields.size==6){"Ambiguous legacy queue"};throw IllegalArgumentException("Queue reconciliation remains with the preserved session importer")}
                        "HIST","ENCORE" -> throw IllegalArgumentException("Legacy counters do not prove presented listening; preserved without inventing statistics")
                        else -> throw IllegalArgumentException("Unknown or malformed legacy record")
                    } }
                }catch(error:Exception){
                    quarantined++;execute("INSERT INTO quarantine VALUES(?,?,?)",digest,index+1,error.message.orEmpty().take(160))
                }
                checkpoint("row:${index+1}")
            }
            val result=ImportAudit(digest,imported,quarantined,backup.path,report.path)
            execute("INSERT INTO import_receipt VALUES(?,?,?,?,?)",digest,imported,quarantined,backup.path,report.path)
            checkpoint("before_commit");result
        }
        checkpoint("committed");publishReport(audit);checkpoint("report_published");return audit
    }
    fun importVerifiedSnapshot(exactJson:String,tracks:List<TrackRecord>):ImportAudit {
        val digest=sha(exactJson.toByteArray());val folder=File(store.directory,"migration-backups").apply{mkdirs()}
        val backup=File(folder,"$digest.phase1.json");publish(backup,exactJson.toByteArray())
        val prior=store.query("SELECT * FROM import_receipt WHERE digest=?",digest){ImportAudit(digest,it.getLong(2),it.getLong(3),it.getString(4),it.getString(5))}.singleOrNull()
        if(prior!=null){publishReport(prior);return prior}
        val report=File(folder,"$digest.report.txt")
        val audit=store.transaction(durable=true){
            var adopted=0L;var rejected=0L
            tracks.forEachIndexed{index,track->
                val local=track.sourceKind=="local" && track.sourcePath?.let{File(it).isAbsolute}==true
                val video=track.sourceKind=="youtube" && track.id.matches(Regex("(?:youtube:|yt:)[A-Za-z0-9_-]{11}"))
                if(local||video){upsert(track);adopted++}else{execute("INSERT INTO quarantine VALUES(?,?,?)",digest,index+1,"Unresolvable or fixture snapshot source preserved without adoption");rejected++}
            }
            execute("INSERT INTO import_receipt VALUES(?,?,?,?,?)",digest,adopted,rejected,backup.path,report.path);ImportAudit(digest,adopted,rejected,backup.path,report.path)
        }
        publishReport(audit);return audit
    }
    private fun publishReport(audit:ImportAudit){
        val lines=store.query("SELECT line_number,reason FROM quarantine WHERE digest=? ORDER BY line_number",audit.digest){"Line ${it.getLong(1)}: ${it.getString(2)}"}
        publish(File(audit.reportPath),("Riff legacy reconciliation\nImported: ${audit.imported}\nQuarantined: ${audit.quarantined}\nBackup SHA-256: ${audit.digest}\n"+lines.joinToString("\n")+"\n").toByteArray())
    }
    companion object {
        private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        private fun publish(target:File,bytes:ByteArray){
            if(target.exists()){check(target.readBytes().contentEquals(bytes)){"Migration artifact differs; original preserved"};return}
            val temporary=File(target.parentFile,".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temporary.toPath(),CREATE_NEW,WRITE).use{channel->val b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true)}
                try{Files.move(temporary.toPath(),target.toPath(),ATOMIC_MOVE)}catch(error:java.nio.file.FileAlreadyExistsException){check(target.readBytes().contentEquals(bytes))}
            }finally{temporary.delete()}
        }
    }
}
