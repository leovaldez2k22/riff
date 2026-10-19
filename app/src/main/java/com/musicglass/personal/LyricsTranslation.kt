package com.musicglass.personal
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitTranslation():T=suspendCancellableCoroutine {c->
    addOnSuccessListener {if(c.isActive) c.resume(it)}
    addOnFailureListener {if(c.isActive) c.resumeWithException(it)}
    addOnCanceledListener {c.cancel()}
}
/** Original lyric lines and word timing are retained. English is a separate line-aligned layer. */
internal suspend fun englishLyrics(context:Context,lines:List<LyricLine>,source:String?,status:(String)->Unit):List<String> {
    require(lines.isNotEmpty()) {string(R.string.no_lyrics_to_translate)}
    status(string(R.string.identifying_language))
    val identifier=LanguageIdentification.getClient()
    val language=try {source ?: TranslateLanguage.fromLanguageTag(identifier.identifyLanguage(lines.joinToString("\n") {it.text}.take(5000)).awaitTranslation())}
        finally {identifier.close()}
    require(language!=null && language!="und") {string(R.string.choose_the_original_language_to_translate_th)}
    if(language==TranslateLanguage.ENGLISH) return lines.map {it.text}
    return translateLyricLines(File(context.cacheDir,"lyric-translations"),language,lines,status) {
        val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language).setTargetLanguage(TranslateLanguage.ENGLISH).build())
        object:LineTranslator {
            override suspend fun prepare() {translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTranslation()}
            override suspend fun translate(text:String):String=translator.translate(text).awaitTranslation()
            override fun close()=translator.close()
        }
    }
}
/** One language model: [prepare] fetches it if the phone does not have it yet. */
internal interface LineTranslator:AutoCloseable {suspend fun prepare();suspend fun translate(text:String):String}
/**
 * The English layer for [lines], one entry per line, from [dir]'s cache or [open]'s translator. Only a translation that
 * reached the last line is cached: a failure or a cancel at any point (the model download included) writes nothing,
 * so a later try starts clean and nothing half-translated is ever shown as done (LYR02).
 */
internal suspend fun translateLyricLines(dir:File,language:String,lines:List<LyricLine>,status:(String)->Unit,open:()->LineTranslator):List<String> {
    val text=language+"\n"+lines.joinToString("\n") {it.text}
    val key=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
    val file=File(dir,"$key.json")
    val cached=withContext(Dispatchers.IO) {runCatching {val a=JSONArray(file.readText());(0 until a.length()).map {a.getString(it)}}.getOrNull()}
    if(cached?.size==lines.size) return cached
    open().use {translator->
        status(string(R.string.preparing_language_model))
        translator.prepare()
        val translations=mutableMapOf<String,String>()
        val result=lines.mapIndexed {index,line->
            status(string(R.string.translating_x_of_x_lines, index+1, lines.size))
            translations[line.text] ?: translator.translate(line.text).also {translations[line.text]=it}
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs();file.writeText(JSONArray(result).toString())
            dir.listFiles()?.sortedByDescending {it.lastModified()}?.drop(30)?.forEach {it.delete()}
        }
        return result
    }
}
/**
 * What the lyrics panel shows of a translation. Each [run] starts from no English; a failure leaves none and says why,
 * a cancel leaves none and says nothing. The original lines, their timing and the karaoke are never touched here (LYR02).
 */
@Stable
internal class LyricTranslationRun(private val translate:suspend (List<LyricLine>,String?,(String)->Unit)->List<String>) {
    var english by mutableStateOf<List<String>>(emptyList()); private set
    var status by mutableStateOf(""); private set
    suspend fun run(on:Boolean,lines:List<LyricLine>?,source:String?) {
        english=emptyList();status=""
        if(on && lines?.isNotEmpty()==true) try {
            english=translate(lines,source) {status=it};status=""
        } catch(e:CancellationException) {status="";throw e} catch(e:Exception) {english=emptyList();status=e.message?:string(R.string.translation_unavailable_try_again)}
    }
}
