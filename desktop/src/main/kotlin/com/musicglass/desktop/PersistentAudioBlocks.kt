package com.musicglass.desktop

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** Immutable verified encoded blocks. An active OS file lock pins a representation against eviction. */
internal class PersistentAudioBlocks(private val root:File,representation:String,private val budget:Long=256L*1024*1024):AutoCloseable {
    private val key=com.musicglass.data.SqliteLibraryRepository.digest(representation)
    private val representationHash=MessageDigest.getInstance("SHA-256").digest(representation.toByteArray())
    private val directory:File
    private val leaseFile:File
    private val leaseChannel:FileChannel
    private val lease:FileLock
    init {
        if(!root.mkdirs()&&!root.isDirectory)throw IOException("Persistent cache directory is unavailable")
        var result:Triple<File,FileChannel,FileLock>?=null
        directory=File(root,key)
        synchronized(GUARD){global{
            if(!directory.mkdirs()&&!directory.isDirectory)throw IOException("Persistent representation directory is unavailable")
            require(directory.canonicalFile.parentFile==root.canonicalFile){"Cache representation escaped its root"}
            val file=File(directory,"${java.util.UUID.randomUUID()}.lease")
            val channel=FileChannel.open(file.toPath(),CREATE_NEW,READ,WRITE)
            try{result=Triple(file,channel,channel.lock())}catch(error:Throwable){channel.close();file.delete();throw error}
            evict()
        }}
        leaseFile=result!!.first;leaseChannel=result!!.second;lease=result!!.third
    }
    data class Block(val total:Long,val bytes:ByteArray)
    fun read(index:Long):Block?=synchronized(GUARD){
        require(index in 0..511)
        val file=File(directory,"$index.block");if(!file.isFile)return null
        try{
            require(file.length() in 89..262232)
            java.io.DataInputStream(file.inputStream().buffered()).use{input->
                require(input.readInt()==0x52414232);val identity=ByteArray(32);input.readFully(identity);require(MessageDigest.isEqual(identity,representationHash)&&input.readLong()==index)
                val total=input.readLong();val count=input.readInt();require(total in 1..134217728&&count==minOf(262144L,total-index*262144).toInt()&&count>0)
                val hash=ByteArray(32);input.readFully(hash);val data=ByteArray(count);input.readFully(data);require(input.read()==-1)
                require(MessageDigest.isEqual(hash,MessageDigest.getInstance("SHA-256").digest(data)))
                file.setLastModified(System.currentTimeMillis());directory.setLastModified(System.currentTimeMillis());Block(total,data)
            }
        }catch(_:Exception){file.delete();null}
    }
    fun write(index:Long,total:Long,bytes:ByteArray)=synchronized(GUARD){global{
        require(index in 0..511&&total in 1..134217728&&bytes.size==minOf(262144L,total-index*262144).toInt())
        read(index)?.let{check(it.total==total&&it.bytes.contentEquals(bytes)){"Cached media representation changed"};return@global}
        evict()
        if(used()+bytes.size+88>budget)return@global
        val target=File(directory,"$index.block");val temporary=File(directory,"${java.util.UUID.randomUUID()}.tmp")
        try{
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes)
            FileChannel.open(temporary.toPath(),CREATE_NEW,WRITE).use{channel->
                val header=ByteBuffer.allocate(88).putInt(0x52414232).put(representationHash).putLong(index).putLong(total).putInt(bytes.size).put(hash).flip()
                while(header.hasRemaining())channel.write(header)
                val data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true)
            }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{temporary.delete()}
    }}
    private fun used():Long=root.listFiles().orEmpty().filter{it.name.matches(Regex("[0-9a-f]{64}"))&&Files.isDirectory(it.toPath(),LinkOption.NOFOLLOW_LINKS)}.sumOf{dir->dir.listFiles().orEmpty().sumOf{it.length()}}
    private fun evict(){
        var size=used()
        for(candidate in root.listFiles().orEmpty().filter{it.name.matches(Regex("[0-9a-f]{64}"))&&Files.isDirectory(it.toPath(),LinkOption.NOFOLLOW_LINKS)}.sortedBy{it.lastModified()}){
            if(size<=budget-4*1024*1024)break
            if(candidate==directory)continue
            val leases=candidate.listFiles{file->file.name.endsWith(".lease")}.orEmpty()
            var active=false
            for(file in leases){
                try{FileChannel.open(file.toPath(),READ,WRITE).use{channel->val lock=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null};if(lock==null)active=true else{lock.release();file.delete()}}}catch(_:IOException){active=true}
            }
            if(active)continue
            require(candidate.canonicalFile.parentFile==root.canonicalFile)
            val bytes=candidate.listFiles().orEmpty().sumOf{it.length()}
            Files.walkFileTree(candidate.toPath(),object:SimpleFileVisitor<Path>(){
                override fun visitFile(file:Path,attrs:java.nio.file.attribute.BasicFileAttributes):FileVisitResult{Files.delete(file);return FileVisitResult.CONTINUE}
                override fun postVisitDirectory(dir:Path,error:IOException?):FileVisitResult{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE}
            });size-=bytes
        }
    }
    private fun <T> global(action:()->T):T {
        FileChannel.open(File(root,"cache.lock").toPath(),CREATE,READ,WRITE).use{channel->
            val deadline=System.nanoTime()+5_000_000_000
            while(true){val lock=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null};if(lock!=null)return lock.use{action()};if(System.nanoTime()>=deadline)throw IOException("Audio cache lock timed out");Thread.sleep(5)}
        }
    }
    override fun close()=synchronized(GUARD){lease.release();leaseChannel.close();leaseFile.delete();Unit}
    companion object{private val GUARD=Any()}
}
