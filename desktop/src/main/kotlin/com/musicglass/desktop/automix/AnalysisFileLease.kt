package com.musicglass.desktop.automix
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** A snapshot can outlive analysis only while a real prepared decoder retains it. */
internal class AnalysisFileLease private constructor(val file:File,private val owned:Boolean):AutoCloseable{
    private val references=AtomicInteger(1)
    fun retain():AnalysisFileLease{
        while(true){val before=references.get();check(before>0){"Analysis input was released"};check(before<16){"Analysis input retention bound exceeded"};if(references.compareAndSet(before,before+1))return this}
    }
    override fun close(){
        val left=references.decrementAndGet();check(left>=0){"Analysis input released twice"}
        if(left==0&&owned)Files.deleteIfExists(file.toPath())
    }
    companion object{
        fun borrowed(file:File)=AnalysisFileLease(file.canonicalFile,false)
        fun snapshot(file:File,directory:File):AnalysisFileLease{
            check(file.name.startsWith("analysis-input-")&&file.canonicalFile.parentFile==directory.canonicalFile&&!Files.isSymbolicLink(file.toPath())){"Analysis snapshot is outside its owned directory"}
            return AnalysisFileLease(file.canonicalFile,true)
        }
    }
}
