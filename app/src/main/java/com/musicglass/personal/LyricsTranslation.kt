package com.musicglass.personal
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
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
    require(lines.isNotEmpty()) {"No lyrics to translate"}
    status("Identifying language\u2026")
    val identifier=LanguageIdentification.getClient()
    val language=try {source ?: TranslateLanguage.fromLanguageTag(identifier.identifyLanguage(lines.joinToString("\n") {it.text}.take(5000)).awaitTranslation())}
        finally {identifier.close()}
    require(language!=null && language!="und") {"Choose the original language to translate these lyrics."}
    if(language==TranslateLanguage.ENGLISH) return lines.map {it.text}
    val text=language+"\n"+lines.joinToString("\n") {it.text}
    val key=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
    val dir=File(context.cacheDir,"lyric-translations");val file=File(dir,"$key.json")
    val cached=withContext(Dispatchers.IO) {runCatching {val a=JSONArray(file.readText());(0 until a.length()).map {a.getString(it)}}.getOrNull()}
    if(cached?.size==lines.size) return cached
    val translator=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language).setTargetLanguage(TranslateLanguage.ENGLISH).build())
    try {
        status("Preparing language model\u2026")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTranslation()
        val translations=mutableMapOf<String,String>()
        val result=lines.mapIndexed {index,line->
            status("Translating ${index+1} of ${lines.size} lines\u2026")
            translations[line.text] ?: translator.translate(line.text).awaitTranslation().also {translations[line.text]=it}
        }
        withContext(Dispatchers.IO) {
            dir.mkdirs();file.writeText(JSONArray(result).toString())
            dir.listFiles()?.sortedByDescending {it.lastModified()}?.drop(30)?.forEach {it.delete()}
        }
        return result
    } finally {translator.close()}
}
