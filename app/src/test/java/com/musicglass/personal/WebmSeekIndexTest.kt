package com.musicglass.personal

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer

class WebmSeekIndexTest {
    private fun element(id:String,data:ByteArray):ByteArray {
        val tag=id.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
        val size=if(data.size<127) byteArrayOf((128+data.size).toByte()) else byteArrayOf((64+(data.size shr 8)).toByte(),data.size.toByte())
        return tag+size+data
    }
    private fun container(times:List<Int> = listOf(0,1000,2000),duration:Double=3000.0):ByteArray {
        val info=element("1549a966",element("2ad7b1",byteArrayOf(0x0f,0x42,0x40))+element("4489",ByteBuffer.allocate(8).putDouble(duration).array()))
        val clusters=times.fold(byteArrayOf()) {bytes,t->bytes+element("1f43b675",element("e7",byteArrayOf((t shr 8).toByte(),t.toByte()))+element("a3",ByteArray(40) {0x55}))}
        return element("1a45dfa3",byteArrayOf())+element("18538067",info+clusters)
    }
    private fun index(bytes:ByteArray):WebmSeekIndex? {
        val file=File.createTempFile("riff-seek-",".webm")
        return try {file.writeBytes(bytes);WebmSeekIndex.read(file)} finally {file.delete()}
    }
    @Test fun readsScaledTimestampsAndAbsoluteClusterPositions() {
        val bytes=container();val index=index(bytes)!!
        assertEquals(3_000_000,index.durationUs);assertArrayEquals(longArrayOf(0,1_000_000,2_000_000),index.timesUs)
        index.positions.forEach {assertEquals(0x1f,bytes[it.toInt()].toInt() and 255)}
    }
    @Test fun rejectsMissingDurationOrNonMonotonicClusters() {
        assertNull(index(container(duration=0.0)));assertNull(index(container(listOf(1000,0,2000))))
        assertNull(index(container(listOf(0,0,2000))));assertNull(index(container(listOf(0))))
    }
    @Test fun rejectsTruncatedAndNonWebmFiles() {
        val bytes=container();assertNull(index(bytes.copyOf(bytes.size-1)))
        assertNull(index(byteArrayOf(0,0,0,0)));assertNull(index(byteArrayOf(1,2,3)))
    }
}
