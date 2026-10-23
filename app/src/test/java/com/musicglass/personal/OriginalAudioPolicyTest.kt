package com.musicglass.personal
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*

class OriginalAudioPolicyTest {
    private fun response(formats:String,hls:Boolean=true)="""{"videoDetails":{"videoId":"ABCDEFGHIJK"},"streamingData":{"adaptiveFormats":[$formats],"formats":[{"itag":137,"mimeType":"video/mp4","width":1920}],"hlsManifestUrl":"manifest","serverAbrStreamingUrl":"sabr"},"playabilityStatus":{"status":"OK"}}"""
    private fun format(id:String,name:String,bitrate:Int=128000,default:Boolean=false,tags:String="")="""{"itag":251,"mimeType":"audio/webm","url":"https://example.test/audio?sig=unchanged","bitrate":$bitrate,"xtags":"$tags","audioTrack":{"id":"$id","displayName":"$name","audioIsDefault":$default}}"""
    @Test fun originalWinsOverHigherBitrateLocaleDefaultAndKeepsBothOriginalQualities() {
        val raw=response(listOf(format("en.0","English (original)",96000),format("en.0","English",128000),format("hi.1","Hindi",256000,true)).joinToString(","))
        val result=Json.parseToJsonElement(originalAudioPlayerResponse(raw)).jsonObject
        val data=result["streamingData"]!!.jsonObject
        assertEquals(2,data["adaptiveFormats"]!!.jsonArray.size)
        assertFalse(data.containsKey("hlsManifestUrl"));assertTrue(data.containsKey("serverAbrStreamingUrl"))
        assertEquals(1,data["formats"]!!.jsonArray.size)
        assertEquals("ABCDEFGHIJK",result["videoDetails"]!!.jsonObject["videoId"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/audio?sig=unchanged",data["adaptiveFormats"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
    }
    @Test fun sourceLanguageIsNotAssumedToBeEnglish() {
        val raw=response(format("ja.0","日本語",tags="acont=original:lang=ja")+","+format("en.1","English",default=true,tags="acont=dubbed:lang=en"))
        val audio=Json.parseToJsonElement(originalAudioPlayerResponse(raw)).jsonObject["streamingData"]!!.jsonObject["adaptiveFormats"]!!.jsonArray
        assertEquals(1,audio.size);assertEquals("ja.0",audio[0].jsonObject["audioTrack"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
    @Test fun ambiguousMultipleTracksAndDubOnlyDoNotSilentlyPlayDefault() {
        for(formats in listOf(format("en.1","English",default=true)+","+format("fr.2","French"),format("en.1","English (dubbed)",default=true))) {
            val data=Json.parseToJsonElement(originalAudioPlayerResponse(response(formats))).jsonObject["streamingData"]!!.jsonObject
            assertTrue(data["adaptiveFormats"]!!.jsonArray.isEmpty());assertFalse(data.containsKey("serverAbrStreamingUrl"));assertFalse(data.containsKey("hlsManifestUrl"))
        }
    }
    @Test fun normalMusicSingleTrackAndNoStreamingDataRemainUnchanged() {
        for(raw in listOf(response("""{"itag":251,"mimeType":"audio/webm","url":"audio"}"""),response(format("en.0","English")),"""{"playabilityStatus":{"status":"ERROR"}}""")) assertEquals(raw,originalAudioPlayerResponse(raw))
    }
    @Test fun encodedOriginalTagsAreRecognizedAndDescriptionRejected() {
        val raw=response(format("de.0","Deutsch",tags="acont%3Doriginal%3Alang%3Dde")+","+format("de.1","Deutsch descriptive",tags="acont=descriptive:lang=de"))
        val audio=Json.parseToJsonElement(originalAudioPlayerResponse(raw)).jsonObject["streamingData"]!!.jsonObject["adaptiveFormats"]!!.jsonArray
        assertEquals(1,audio.size)
    }
    /** Q18-091: of 251 and its compressed copy, the normal one is kept; a song offered only compressed still plays. */
    @Test fun aCompressedCopyIsDroppedWhenTheNormalOneIsOffered() {
        val normal="""{"itag":251,"mimeType":"audio/webm","url":"normal","bitrate":140000}"""
        for(drc in listOf("""{"itag":251,"mimeType":"audio/webm","url":"drc","bitrate":150000,"isDrc":true}""",
            """{"itag":251,"mimeType":"audio/webm","url":"drc","bitrate":150000,"xtags":"drc=1"}""",
            """{"itag":251,"mimeType":"audio/webm","url":"https://example.test/a?xtags=drc%3D1&sig=x","bitrate":150000}""")) {
            val data=Json.parseToJsonElement(originalAudioPlayerResponse(response("$drc,$normal"))).jsonObject["streamingData"]!!.jsonObject
            assertEquals(listOf("normal"),data["adaptiveFormats"]!!.jsonArray.map {it.jsonObject["url"]!!.jsonPrimitive.content})
            assertEquals("the video format stays",1,data["formats"]!!.jsonArray.size)
        }
        val only=response("""{"itag":251,"mimeType":"audio/webm","url":"drc","isDrc":true}""")
        assertEquals(only,originalAudioPlayerResponse(only))
        val plain=response(normal+""","""+"""{"itag":140,"mimeType":"audio/mp4","url":"x","xtags":"lang=en"}""")
        assertEquals("no compressed copy: unchanged",plain,originalAudioPlayerResponse(plain))
    }
}
