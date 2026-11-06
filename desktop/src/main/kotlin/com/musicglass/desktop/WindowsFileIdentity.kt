package com.musicglass.desktop

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import com.musicglass.data.SqliteLibraryRepository
import java.io.File

/** NTFS/ReFS file IDs survive rename and hardlinks. Windows BasicFileAttributes.fileKey can be null. */
internal object WindowsFileIdentity {
    private interface Kernel:StdCallLibrary {
        fun CreateFileW(path:WString,access:Int,sharing:Int,security:Pointer?,disposition:Int,flags:Int,template:Pointer?):Pointer?
        fun GetFileInformationByHandleEx(handle:Pointer,infoClass:Int,information:Pointer,size:Int):Boolean
        fun CloseHandle(handle:Pointer):Boolean
    }
    private val kernel:Kernel by lazy{Native.load("kernel32",Kernel::class.java)}
    fun of(file:File):String {
        val path=file.canonicalPath
        val nativePath=if(path.startsWith("\\\\?\\"))path else if(path.startsWith("\\\\"))"\\\\?\\UNC\\"+path.removePrefix("\\\\")else"\\\\?\\$path"
        val handle=kernel.CreateFileW(WString(nativePath),0x80,7,null,3,0x02200000,null)
        check(handle!=null&&Pointer.nativeValue(handle)!=-1L){"Cannot read Windows file identity (${Native.getLastError()})"}
        try{Memory(24).use{information->
            check(kernel.GetFileInformationByHandleEx(handle,18,information,24)){"Cannot read Windows file ID (${Native.getLastError()})"}
            return SqliteLibraryRepository.digest(information.getByteArray(0,24).joinToString(""){"%02x".format(it)})
        }}finally{kernel.CloseHandle(handle)}
    }
}
