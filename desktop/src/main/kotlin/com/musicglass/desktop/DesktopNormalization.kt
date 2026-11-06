package com.musicglass.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import kotlin.math.pow
import kotlinx.serialization.json.*

/** Same policy as Android LoudnessNormalization: provider metadata, never an invented LUFS estimate. */
internal fun validLoudnessDb(value:Double?):Double?=value?.takeIf{it.isFinite()&&it in -60.0..60.0}
internal fun normalizationGain(enabled:Boolean,loudnessDb:Double?):Float {
    val db=validLoudnessDb(loudnessDb)?:return 1f
    return if(!enabled||db<=0)1f else 10.0.pow(-minOf(db,12.0)/20).toFloat()
}
/** Each real decoder owns its own envelope, so an incoming track cannot change the outgoing trim. */
internal class DeckNormalization {
    private var gain:Float?=null
    fun process(pcm:FloatArray,frames:Int,target:Float){
        require(frames>=0&&frames*2<=pcm.size&&target.isFinite()&&target in normalizationGain(true,12.0)..1f)
        var current=gain?:target
        if(current==1f&&target==1f){gain=1f;return}
        val step=2f/(48000*.03f)
        for(frame in 0 until frames){current+=(target-current).coerceIn(-step,step);pcm[frame*2]*=current;pcm[frame*2+1]*=current}
        gain=current
    }
}
/** Download metadata is bound to the exact verified audio SHA. Missing/old/untrusted receipts mean unity gain. */
internal object DesktopLoudnessReceipt {
    fun path(audio:File)=File(audio.path+".loudness.json")
    fun write(audio:File,sha256:String,db:Double?){
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val target=path(audio).toPath()
        val value=validLoudnessDb(db)
        if(value==null){Files.deleteIfExists(target);return}
        val part=Files.createTempFile(target.parent,"loudness-",".part")
        try{
            val bytes=buildJsonObject{put("version",1);put("sha256",sha256);put("db",value)}.toString().toByteArray(Charsets.UTF_8)
            FileChannel.open(part,WRITE).use{channel->val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true)}
            Files.move(part,target,ATOMIC_MOVE,REPLACE_EXISTING)
        }finally{Files.deleteIfExists(part)}
    }
    fun read(audio:File):Double?=try{
        val receipt=path(audio)
        if(!audio.isFile||audio.length()>128L*1024*1024||!receipt.isFile||receipt.length()>512||Files.isSymbolicLink(receipt.toPath()))null
        else{
            val data=Json.parseToJsonElement(receipt.readText(Charsets.UTF_8)).jsonObject
            if(data["version"]?.jsonPrimitive?.intOrNull!=1||data["sha256"]?.jsonPrimitive?.content!=digest(audio))null else validLoudnessDb(data["db"]?.jsonPrimitive?.doubleOrNull)
        }
    }catch(cancel:java.io.InterruptedIOException){throw cancel}catch(_:Exception){null}
    private fun digest(file:File):String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException();val n=input.read(buffer);if(n<0)break;sha.update(buffer,0,n)}}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
}
