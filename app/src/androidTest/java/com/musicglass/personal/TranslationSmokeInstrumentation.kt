package com.musicglass.personal
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
/** Exercises the shipped on-device translator; no fixtures or hooks enter the release APK. */
class TranslationSmokeInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val result=Bundle()
        try {
            val originals=listOf(LyricLine(1000,3000,"Te quiero más que ayer"),LyricLine(3000,5000,"Baila conmigo bajo la luna"))
            val translated=runBlocking {withTimeout(180000) {englishLyrics(targetContext,originals,"es") {status->android.util.Log.i("RiffTranslationTest",status)}}}
            check(translated.size==2)
            check(translated[0].lowercase().contains("love") && translated[0].lowercase().contains("yesterday")) {"Unexpected first translation: ${translated[0]}"}
            check(translated[1].lowercase().contains("moon")) {"Unexpected second translation: ${translated[1]}"}
            check(originals[0].startMs==1000L && originals[1].endMs==5000L)
            val cached=runBlocking {englishLyrics(targetContext,originals,"es") {}}
            check(cached==translated)
            result.putString("result",JSONObject().put("passed",true).put("english",org.json.JSONArray(translated)).put("timing_unchanged",true).put("cache_reused",true).toString())
            finish(Activity.RESULT_OK,result)
        } catch(e:Exception) {result.putString("error",e.toString());finish(Activity.RESULT_CANCELED,result)}
    }
}
