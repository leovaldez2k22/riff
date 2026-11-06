package com.musicglass.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

class CodecAcceptanceTest {
    @Test fun codecCapabilityManifestMatchesActualDecodedCorpus(){
        val root=File(checkNotNull(System.getProperty("riff.repo.root")))
        val manifest=Json.parseToJsonElement(File(root,"artifacts/desktop/phase3/corpus/manifest.json").readText()).jsonObject
        val accepted=mutableListOf<JsonObject>()
        for(element in manifest.getValue("cases").jsonArray){
            val item=element.jsonObject;val name=item.getValue("name").jsonPrimitive.content
            val source=File(root,item.getValue("source").jsonPrimitive.content)
            val reference=File(root,item.getValue("reference").jsonPrimitive.content).readBytes()
            assertTrue(reference.size in 8..32*1024*1024)
            assertEquals(item.getValue("sourceSha256").jsonPrimitive.content,sha(source.readBytes()))
            val expected=ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val buffer=FloatArray(8192);var frames=0L;var maximum=0f;var error=0f
            FfmpegPcmDecoder(source.path).use{decoder->
                assertEquals(item.getValue("format").jsonObject.getValue("codec_name").jsonPrimitive.content,decoder.codecName,"$name actual codec")
                item["format"]?.jsonObject?.get("profile")?.jsonPrimitive?.contentOrNull?.let{assertEquals(it,decoder.profile,"$name actual profile")}
                while(true){val count=decoder.read(buffer,4096);if(count==0)break;assertTrue(expected.remaining()>=count*2,"$name produced extra PCM");for(index in 0 until count*2){val value=buffer[index];assertTrue(value.isFinite(),"$name nonfinite PCM");maximum=maxOf(maximum,abs(value));error=maxOf(error,abs(value-expected.get()))};frames+=count}
                assertEquals(0,expected.remaining(),"$name lost reference frames")
                assertEquals(item.getValue("frames").jsonPrimitive.long,frames,"$name frame count")
                assertTrue(maximum>.00001f,"$name silent decode");assertTrue(error<=.00001f,"$name maximum PCM deviation $error")
                val target=minOf(48000L,frames/3);assertEquals(target,decoder.seek(target))
                val count=decoder.read(buffer,1024);val ref=ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();ref.position((target*2).toInt())
                repeat(count*2){assertEquals(ref.get(),buffer[it],.00001f,"$name seek sample $it")}
                accepted+=buildJsonObject{put("case",name);put("codec",decoder.codecName);put("profile",decoder.profile);put("sourceRate",decoder.sourceRate);put("sourceChannels",decoder.sourceChannels);put("frames",frames);put("maximumError",error);put("sourceSha256",item.getValue("sourceSha256"));put("referenceSha256",item.getValue("referenceSha256"))}
            }
            assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts(),"$name leaked native handles")
        }
        val native=File(checkNotNull(System.getProperty("riff.native.dir")),"riff_audio.dll")
        val evidence=buildJsonObject{put("schema",1);put("ffmpeg",NativeAudio.version());put("nativeSha256",sha(native.readBytes()));put("accepted",JsonArray(accepted));put("note","Exact tested source variants; no claim about untested profiles or channel/rate combinations")}
        File(root,"artifacts/desktop/phase3/codec-capabilities.json").writeText(evidence.toString())
        File(native.parentFile,"codec-capabilities.json").writeText(evidence.toString())
    }
    @Test fun invalidContainersAndTruncatedRecordingsCannotBecomeSyntheticMusic(){
        val root=File(checkNotNull(System.getProperty("riff.repo.root")))
        val corpus=File(root,"artifacts/desktop/phase3/corpus")
        val directory=kotlin.io.path.createTempDirectory("riff-damaged-audio-").toFile()
        val evidence=mutableListOf<JsonObject>()
        try{
            val invalid=File(directory,"not-a-recording.mp3").apply{writeBytes(ByteArray(4096))}
            assertFailsWith<UnsupportedContainerException>{FfmpegPcmDecoder(invalid.path).close()}
            assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
            for(name in listOf("mp3.mp3","flac24.flac","alac.m4a","opus.webm")){
                val source=File(corpus,name).readBytes();val damaged=File(directory,name).apply{writeBytes(source.copyOf(source.size/2))}
                val reference=ByteBuffer.wrap(File(corpus,"$name.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var frames=0L;var peak=0f;var prefixError=0f;var failure:String?=null
                try{
                    FfmpegPcmDecoder(damaged.path).use{decoder->
                        val buffer=FloatArray(8192)
                        while(true){
                            val count=decoder.read(buffer,4096);if(count==0)break
                            assertTrue(count in 1..4096)
                            repeat(count*2){index->
                                assertTrue(buffer[index].isFinite(),"$name nonfinite damaged-file output")
                                peak=maxOf(peak,abs(buffer[index]))
                                if(frames*2+index<2048)prefixError=maxOf(prefixError,abs(buffer[index]-reference.get((frames*2+index).toInt())))
                            }
                            frames+=count
                            assertTrue(frames<=reference.limit()/2+4096,"$name unbounded damaged-file output")
                        }
                    }
                }catch(error:AudioOperationException){failure=error.code}
                if(failure==null){assertTrue(frames>0&&peak>.00001f,"$name silently substituted audio");assertTrue(prefixError<=.00001f,"$name fabricated decoded prefix")}
                assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts(),"$name native handles after damaged decode")
                evidence+=buildJsonObject{put("case",name);put("truncatedSha256",sha(damaged.readBytes()));put("actualDecodedFrames",frames);put("peak",peak);put("prefixError",prefixError);put("structuredFailure",failure?:"bounded reference-matching prefix then EOF")}
            }
            File(root,"artifacts/desktop/phase3/damaged-codec-evidence.json").writeText(JsonArray(evidence).toString())
        }finally{directory.deleteRecursively()}
    }
    @Test fun unknownLengthEncodedReaderAndUnicodePathDecodeTheirActualReferences(){
        val root=File(checkNotNull(System.getProperty("riff.repo.root")))
        val corpus=File(root,"artifacts/desktop/phase3/corpus")
        val encoded=File(corpus,"opus.opus").readBytes()
        var closed=false
        val source=object:CachedByteSource(0){
            override fun length()=-1L
            override fun readAt(position:Long,target:ByteArray,offset:Int,count:Int):Int{
                require(position>=0);if(position>=encoded.size)return -1
                val size=minOf(count,encoded.size-position.toInt());encoded.copyInto(target,offset,position.toInt(),position.toInt()+size);return size
            }
            override fun close(){closed=true}
        }
        fun verify(decoder:FfmpegPcmDecoder,name:String){decoder.use{
            val ref=ByteBuffer.wrap(File(corpus,"$name.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val buffer=FloatArray(8192);var frames=0L
            while(true){val count=it.read(buffer,4096);if(count==0)break;assertTrue(ref.remaining()>=count*2);repeat(count*2){index->assertEquals(ref.get(),buffer[index],.00001f,"$name actual frame ${frames+index/2}")};frames+=count}
            assertEquals(0,ref.remaining());assertTrue(frames>0)
        }}
        verify(FfmpegPcmDecoder(source),"opus.opus");assertTrue(closed)
        val directory=kotlin.io.path.createTempDirectory("riff-unicode-codec-").toFile()
        try{val unicode=File(directory,"音楽 café 🎵.flac");File(corpus,"flac24.flac").copyTo(unicode);verify(FfmpegPcmDecoder(unicode.path),"flac24.flac")}
        finally{directory.deleteRecursively()}
        assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
        File(root,"artifacts/desktop/phase3/unknown-length-unicode-evidence.json").writeText("""{"unknownLength":"actual Ogg Opus, every reference frame matched","unicodePath":"actual 96 kHz FLAC, every reference frame matched","nativeHandles":0}""")
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
