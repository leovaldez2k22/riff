package com.musicglass.personal
import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray

internal fun updatedSearchHistory(previous:List<String>,query:String):List<String> {
    val clean=query.trim().replace(Regex("\\s+")," ").take(160)
    if(clean.isBlank()) return previous
    return (listOf(clean)+previous.filterNot {it.equals(clean,true)}).take(30)
}
internal class SearchHistory(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("search_history",0)
    var entries by mutableStateOf(runCatching {val a=JSONArray(prefs.getString("queries","[]"));(0 until a.length()).map {a.getString(it)}.take(30)}.getOrDefault(emptyList()))
        private set
    fun record(query:String) {entries=updatedSearchHistory(entries,query);save()}
    fun remove(query:String) {entries=entries.filterNot {it==query};save()}
    fun clear() {entries=emptyList();save()}
    private fun save() {prefs.edit().putString("queries",JSONArray(entries).toString()).apply()}
}
