package com.musicglass.personal
import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal fun updatedSearchHistory(previous:List<String>,query:String):List<String> {
    val clean=query.trim().replace(Regex("\\s+")," ").take(160)
    if(clean.isBlank()) return previous
    return (listOf(clean)+previous.filterNot {it.equals(clean,true)}).take(30)
}

/**
 * Combines the stored history, read after the screen appeared, with what the user did meanwhile:
 * new searches stay first, removed entries stay removed, and Clear drops everything stored.
 */
internal fun mergeLoadedHistory(stored:List<String>,session:List<String>,removed:Set<String>,cleared:Boolean):List<String> =
    if(cleared) session
    else (session+stored.filterNot {s->s in removed || session.any {it.equals(s,true)}}).take(30)

/**
 * One per process, loaded and saved off the main thread: reading the preferences file on first use
 * stalled a frame for 112–340 ms in the stress test, and SharedPreferences.edit() waits for that read.
 */
internal class SearchHistory private constructor(context:Context) {
    companion object {
        @Volatile private var shared:SearchHistory?=null
        fun get(context:Context):SearchHistory=shared ?: synchronized(this) {shared ?: SearchHistory(context.applicationContext).also {shared=it}}
    }
    private val prefs=context.getSharedPreferences("search_history",0)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val disk=Dispatchers.IO.limitedParallelism(1) // keeps saves in order
    private val scope=CoroutineScope(SupervisorJob()+disk)
    private var loaded=false
    private val removedBeforeLoad=mutableSetOf<String>()
    private var clearedBeforeLoad=false

    var entries by mutableStateOf(emptyList<String>())
        private set

    /** Call from the UI (main thread); returns at once after the first load. */
    suspend fun load() {
        if(loaded) return
        val stored=withContext(disk) {
            runCatching {val a=JSONArray(prefs.getString("queries","[]"));(0 until a.length()).map {a.getString(it)}.take(30)}.getOrDefault(emptyList())
        }
        if(loaded) return
        val merged=mergeLoadedHistory(stored,entries,removedBeforeLoad,clearedBeforeLoad)
        entries=merged;loaded=true;removedBeforeLoad.clear()
        if(merged!=stored) save()
    }
    fun record(query:String) {entries=updatedSearchHistory(entries,query);save()}
    fun remove(query:String) {entries=entries.filterNot {it==query};if(!loaded) removedBeforeLoad+=query;save()}
    fun clear() {entries=emptyList();if(!loaded) clearedBeforeLoad=true;save()}
    private fun save() {
        if(!loaded) return // load() merges and saves once the stored list is known
        val snapshot=JSONArray(entries).toString()
        scope.launch {prefs.edit().putString("queries",snapshot).apply()}
    }
}
