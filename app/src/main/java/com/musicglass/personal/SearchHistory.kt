package com.musicglass.personal
import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal const val SEARCH_HISTORY_LIMIT=30

internal fun updatedSearchHistory(previous:List<String>,query:String):List<String> {
    val clean=query.trim().replace(Regex("\\s+")," ").take(160)
    if(clean.isBlank()) return previous
    return (listOf(clean)+previous.filterNot {it.equals(clean,true)}).take(SEARCH_HISTORY_LIMIT)
}

/**
 * Combines the stored history, read after the screen appeared, with what the user did meanwhile:
 * new searches stay first, removed entries stay removed, and Clear drops everything stored.
 */
internal fun mergeLoadedHistory(stored:List<String>,session:List<String>,removed:Set<String>,cleared:Boolean):List<String> =
    if(cleared) session
    else (session+stored.filterNot {s->s in removed || session.any {it.equals(s,true)}}).take(SEARCH_HISTORY_LIMIT)

private const val SEARCH_PREFS="search_history"
/** The single list earlier builds shared between every account. It is never shown; see Earlier data. */
private const val LEGACY_SEARCH_KEY="queries"
private fun searchKey(owner:String)="queries_$owner"
private fun readQueries(text:String?):List<String> =
    runCatching {val a=JSONArray(text ?: "[]");(0 until a.length()).map {a.getString(it)}.take(SEARCH_HISTORY_LIMIT)}.getOrDefault(emptyList())

/** Searches saved before they were kept per owner. Reads preferences: call off the main thread. */
internal fun legacySearchCount(context:Context):Int =
    readQueries(context.applicationContext.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE).getString(LEGACY_SEARCH_KEY,null)).size

/**
 * Recent searches, kept separately for each owner (an account, or signed-out use); an unverified
 * account keeps them only until the screen closes. One per process, loaded and saved off the main
 * thread: reading the preferences file on first use stalled a frame for 112–340 ms in the stress
 * test, and SharedPreferences.edit() waits for that read.
 */
internal class SearchHistory internal constructor(context:Context,private val disk:CoroutineDispatcher) {
    companion object {
        @Volatile private var shared:SearchHistory?=null
        // One lane keeps saves, and a clear that follows them, in the order they were asked for.
        @OptIn(ExperimentalCoroutinesApi::class)
        fun get(context:Context):SearchHistory=shared ?: synchronized(this) {
            shared ?: SearchHistory(context.applicationContext,Dispatchers.IO.limitedParallelism(1)).also {shared=it}
        }
    }
    private val prefs=context.applicationContext.getSharedPreferences(SEARCH_PREFS,Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+disk)
    @Volatile private var owner:String?=null
    @Volatile private var loaded=false
    private val removedBeforeLoad=mutableSetOf<String>()
    private var clearedBeforeLoad=false

    var entries by mutableStateOf(emptyList<String>())
        private set

    /** Call from the UI (main thread). Shows [ownerKey]'s searches; another owner's are dropped from view at once. */
    suspend fun bind(ownerKey:String) {
        if(owner!=ownerKey) {owner=ownerKey;entries=emptyList();loaded=false;removedBeforeLoad.clear();clearedBeforeLoad=false}
        if(loaded) return
        if(ownerKey==UNVERIFIED_SCOPE_KEY) {loaded=true;return}
        val stored=withContext(disk) {readQueries(prefs.getString(searchKey(ownerKey),null))}
        if(owner!=ownerKey || loaded) return
        val merged=mergeLoadedHistory(stored,entries,removedBeforeLoad,clearedBeforeLoad)
        entries=merged;loaded=true;removedBeforeLoad.clear();clearedBeforeLoad=false
        if(merged!=stored) save()
    }
    fun record(query:String) {entries=updatedSearchHistory(entries,query);save()}
    fun remove(query:String) {entries=entries.filterNot {it==query};if(!loaded) removedBeforeLoad+=query;save()}
    fun clear() {entries=emptyList();if(!loaded) clearedBeforeLoad=true;save()}
    private fun save() {
        if(!loaded) return // bind() merges and saves once the stored list is known
        val key=owner?.takeIf {it!=UNVERIFIED_SCOPE_KEY}?.let(::searchKey) ?: return
        val snapshot=JSONArray(entries).toString()
        scope.launch {prefs.edit().putString(key,snapshot).apply()}
    }

    suspend fun count(ownerKey:String):Int=withContext(disk) {readQueries(prefs.getString(searchKey(ownerKey),null)).size}

    /**
     * Removes [ownerKey]'s searches. The list on screen is emptied first, so a search made while this
     * runs cannot write the old ones back; saves already queued run before the removal. Returns how
     * many were removed, or null when the removal could not be stored.
     */
    @SuppressLint("UseKtx","ApplySharedPref") // The result of commit() is the outcome reported to the user.
    suspend fun clearOwner(ownerKey:String):Int? {
        val shown=if(owner==ownerKey) entries.size.also {entries=emptyList();if(!loaded) clearedBeforeLoad=true} else 0
        return withContext(disk) {
            val stored=readQueries(prefs.getString(searchKey(ownerKey),null)).size
            if(prefs.edit().remove(searchKey(ownerKey)).commit()) maxOf(stored,shown) else null
        }
    }

    /**
     * On the user's confirmation: adds the shared list of earlier builds to [ownerKey]'s searches, the
     * owner's own first, and removes the shared list in the same write. Returns how many were added.
     */
    @SuppressLint("UseKtx","ApplySharedPref")
    suspend fun importLegacy(ownerKey:String):Int? {
        if(ownerKey==UNVERIFIED_SCOPE_KEY || ownerKey==DEVICE_LOCAL_SCOPE_KEY) return null
        val merged=withContext(disk) {
            val own=readQueries(prefs.getString(searchKey(ownerKey),null))
            val combined=(own+readQueries(prefs.getString(LEGACY_SEARCH_KEY,null)).filterNot {old->own.any {it.equals(old,true)}}).take(SEARCH_HISTORY_LIMIT)
            if(prefs.edit().putString(searchKey(ownerKey),JSONArray(combined).toString()).remove(LEGACY_SEARCH_KEY).commit()) combined to combined.size-own.size else null
        } ?: return null
        if(owner==ownerKey && loaded) entries=(entries+merged.first.filterNot {old->entries.any {it.equals(old,true)}}).take(SEARCH_HISTORY_LIMIT)
        return merged.second
    }

    /** Every owner that has stored searches. */
    suspend fun owners():Set<String> =withContext(disk) {prefs.all.keys.mapNotNullTo(mutableSetOf()) {key->key.removePrefix("queries_").takeIf {it!=key && it.isNotEmpty()}}}

    /** [ownerKey]'s stored searches, newest first. For the export. */
    suspend fun stored(ownerKey:String):List<String> =withContext(disk) {readQueries(prefs.getString(searchKey(ownerKey),null))}

    /**
     * Adds searches from an imported file, by owner: each owner's own stay first and none is removed.
     * One write for every owner. Returns how many were added, or null when nothing was stored.
     */
    @SuppressLint("UseKtx","ApplySharedPref")
    suspend fun importEntries(additions:Map<String,List<String>>):Int? {
        val merged=withContext(disk) {
            val editor=prefs.edit();val lists=mutableMapOf<String,List<String>>();var added=0
            for((ownerKey,queries) in additions) {
                if(ownerKey==UNVERIFIED_SCOPE_KEY || ownerKey==DEVICE_LOCAL_SCOPE_KEY) continue
                val own=readQueries(prefs.getString(searchKey(ownerKey),null));var combined=own
                for(query in queries) {
                    val clean=query.trim().replace(Regex("\\s+")," ").take(160)
                    if(clean.isNotBlank() && combined.size<SEARCH_HISTORY_LIMIT && combined.none {it.equals(clean,true)}) combined=combined+clean
                }
                if(combined.size!=own.size) {editor.putString(searchKey(ownerKey),JSONArray(combined).toString());lists[ownerKey]=combined;added+=combined.size-own.size}
            }
            if(editor.commit()) lists to added else null
        } ?: return null
        merged.first[owner]?.takeIf {loaded}?.let {list->entries=(entries+list.filterNot {old->entries.any {it.equals(old,true)}}).take(SEARCH_HISTORY_LIMIT)}
        return merged.second
    }

    @SuppressLint("UseKtx","ApplySharedPref")
    suspend fun deleteLegacy():Boolean=withContext(disk) {prefs.edit().remove(LEGACY_SEARCH_KEY).commit()}
}
