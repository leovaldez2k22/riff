package com.musicglass.data

import java.text.Normalizer
import java.util.Locale

data class CachedArtistGenre(val scope:String,val name:String,val mbid:String?,val status:String,val genres:List<String>,val fetchedMs:Long)
/** Scoped cache of public artist facts. Enabling the preference alone does not execute network code here. */
class EncoreGenreCache(private val store:SqliteLibraryRepository){
    companion object {
        const val KEEP_MS=90L*86400000
        fun key(name:String):String=Normalizer.normalize(name.trim(),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
    fun get(scope:String,name:String):CachedArtistGenre?=store.query("SELECT * FROM encore_artist_genre WHERE scope=? AND artist_key=?",Scopes.validate(scope),key(name)){row->
        CachedArtistGenre(row.getString("scope"),row.getString("display_name"),row.getString("mbid"),row.getString("status"),row.getString("genres").split('|').filter(String::isNotEmpty),row.getLong("fetched_ms"))
    }.singleOrNull()
    fun put(value:CachedArtistGenre,expectedHistoryEpoch:Long):Boolean=store.transaction{
        val scope=Scopes.validate(value.scope)
        require(value.name.length in 1..180&&value.fetchedMs>=0&&value.status in setOf("Ready","NoMatch","Ambiguous"))
        require(value.mbid==null||value.mbid.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        require(if(value.status=="Ready")value.mbid!=null else value.mbid==null&&value.genres.isEmpty())
        require(value.genres.size<=3&&value.genres.all{it.length in 1..64&&'|' !in it})
        if(!privacy(scope).genreLookup||query("SELECT epoch FROM history_epoch WHERE scope=?",scope){it.getLong(1)}.singleOrNull().let{it?:0L}!=expectedHistoryEpoch)return@transaction false
        execute("INSERT OR REPLACE INTO encore_artist_genre VALUES(?,?,?,?,?,?,?)",scope,key(value.name),value.name,value.mbid,value.status,value.genres.joinToString("|"),value.fetchedMs)
        execute("DELETE FROM encore_artist_genre WHERE scope=? AND artist_key IN(SELECT artist_key FROM encore_artist_genre WHERE scope=? ORDER BY fetched_ms DESC,artist_key LIMIT -1 OFFSET 1000)",scope,scope)
        true
    }
}
