package com.musicglass.desktop

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** Read-only Windows Network List Manager cost query. No registry, service or network setting changes. */
internal object WindowsNetworkCost {
    data class Result(val flags:Int?,val error:String?=null){val unmetered:Boolean get()=flags==1}
    private interface Ole:StdCallLibrary {
        fun CoInitializeEx(reserved:Pointer?,mode:Int):Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid:Pointer,outer:Pointer?,context:Int,iid:Pointer,out:PointerByReference):Int
    }
    private val ole by lazy{Native.load("ole32",Ole::class.java)}
    private fun guid(value:String)=Memory(16).apply{
        val id=java.util.UUID.fromString(value)
        setInt(0,(id.mostSignificantBits ushr 32).toInt());setShort(4,(id.mostSignificantBits ushr 16).toShort());setShort(6,id.mostSignificantBits.toShort())
        for(index in 0..7)setByte(8L+index,(id.leastSignificantBits ushr ((7-index)*8)).toByte())
    }
    fun current():Result {
        if(!System.getProperty("os.name").startsWith("Windows"))return Result(null,"Windows cost API is unavailable")
        return try{
            val initialized=ole.CoInitializeEx(null,0)
            if(initialized<0&&initialized!=0x80010106.toInt())return Result(null,"COM initialization HRESULT ${initialized.toUInt().toString(16)}")
            try{
                guid("dcb00c01-570f-4a9b-8d69-199fdba5723b").use{clsid->guid("dcb00008-570f-4a9b-8d69-199fdba5723b").use{iid->
                    val out=PointerByReference();val opened=ole.CoCreateInstance(clsid,null,0x17,iid,out)
                    if(opened<0)return Result(null,"Network cost HRESULT ${opened.toUInt().toString(16)}")
                    val instance=out.value?:return Result(null,"Windows returned no cost manager")
                    val table=instance.getPointer(0)
                    try{
                        val flags=IntByReference()
                        val status=com.sun.jna.Function.getFunction(table.getPointer(3L*Native.POINTER_SIZE),com.sun.jna.Function.ALT_CONVENTION).invokeInt(arrayOf(instance,flags,null))
                        if(status<0)Result(null,"GetCost HRESULT ${status.toUInt().toString(16)}")else Result(flags.value)
                    }finally{com.sun.jna.Function.getFunction(table.getPointer(2L*Native.POINTER_SIZE),com.sun.jna.Function.ALT_CONVENTION).invokeInt(arrayOf(instance))}
                }}
            }finally{if(initialized>=0)ole.CoUninitialize()}
        }catch(error:Exception){Result(null,"Network cost unavailable (${error.javaClass.simpleName})")}
        catch(error:LinkageError){Result(null,"Network cost native binding unavailable (${error.javaClass.simpleName})")}
    }
}
