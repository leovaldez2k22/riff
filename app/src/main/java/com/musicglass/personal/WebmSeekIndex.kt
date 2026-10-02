package com.musicglass.personal

import java.io.File
import java.io.RandomAccessFile

/** Read only container headers; compressed audio is untouched. Positions are absolute cluster starts. */
internal data class WebmSeekIndex(val durationUs:Long,val timesUs:LongArray,val positions:LongArray) {
    companion object {
        fun read(file:File):WebmSeekIndex? = runCatching {
            RandomAccessFile(file,"r").use {f ->
                val length=f.length()
                fun vint(id:Boolean):Long {
                    val first=f.readUnsignedByte();require(first!=0)
                    val width=Integer.numberOfLeadingZeros(first)-24+1
                    require(width in 1..if(id) 4 else 8)
                    var value=if(id) first.toLong() else (first and (255 ushr width)).toLong()
                    repeat(width-1) {value=(value shl 8) or f.readUnsignedByte().toLong()}
                    return if(!id && value==(1L shl (7*width))-1) -1 else value
                }
                data class Element(val id:Long,val start:Long,val body:Long,val end:Long)
                fun element(limit:Long):Element {
                    val start=f.filePointer;val id=vint(true);val size=vint(false);val body=f.filePointer
                    val end=if(size<0) limit else body+size
                    require(end in body..limit)
                    return Element(id,start,body,end)
                }
                fun integer(end:Long):Long {
                    val size=(end-f.filePointer).toInt();require(size in 1..8)
                    var value=0L;repeat(size) {value=(value shl 8) or f.readUnsignedByte().toLong()};return value
                }
                var segment:Element?=null
                while(f.filePointer<length) {val e=element(length);if(e.id==0x18538067L) {segment=e;break};f.seek(e.end)}
                val s=segment?:return@use null
                var scale=1_000_000L;var duration=0.0
                val times=mutableListOf<Long>();val positions=mutableListOf<Long>()
                f.seek(s.body)
                while(f.filePointer<s.end) {
                    val e=element(s.end)
                    when(e.id) {
                        0x1549A966L -> while(f.filePointer<e.end) {
                            val info=element(e.end)
                            if(info.id==0x2AD7B1L) scale=integer(info.end)
                            if(info.id==0x4489L) duration=when(info.end-info.body) {4L->f.readFloat().toDouble();8L->f.readDouble();else->0.0}
                            f.seek(info.end)
                        }
                        0x1F43B675L -> {
                            // Finite clusters emitted by MediaMuxer. Unknown-size clusters are safely unsupported.
                            require(e.end<s.end || e.end-e.body<64L*1024*1024)
                            while(f.filePointer<e.end) {
                                val child=element(e.end)
                                if(child.id==0xE7L) {times.add(integer(child.end));positions.add(e.start);break}
                                f.seek(child.end)
                            }
                        }
                    }
                    f.seek(e.end)
                    require(positions.size<=100_000)
                }
                require(scale in 1..1_000_000_000 && duration.isFinite() && duration>0 && times.size>1)
                val us=times.map {Math.multiplyExact(it,scale)/1000}.toLongArray()
                require(us.toList().zipWithNext().all {(a,b)->a<b})
                val durationUs=(duration*scale/1000).toLong();require(durationUs>us.last())
                WebmSeekIndex(durationUs,us,positions.toLongArray())
            }
        }.getOrNull()
    }
}
