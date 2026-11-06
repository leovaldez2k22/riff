package com.musicglass.data

class LyricsCacheRepository(private val store:SqliteLibraryRepository){
    data class Entry(val payload:String,val fetchedMs:Long)
    fun get(scope:String,track:String):Entry?=store.query("SELECT payload,fetched_ms FROM lyrics_cache WHERE scope=? AND track_id=?",Scopes.validate(scope),track){Entry(it.getString(1),it.getLong(2))}.singleOrNull()
    fun put(scope:String,track:String,payload:String,fetchedMs:Long=System.currentTimeMillis())=store.transaction{
        require(payload.length<=2*1024*1024)
        execute("INSERT INTO lyrics_cache VALUES(?,?,?,?,?) ON CONFLICT(scope,track_id) DO UPDATE SET format=excluded.format,payload=excluded.payload,fetched_ms=excluded.fetched_ms",Scopes.validate(scope),track,"document-v1",payload,fetchedMs)
        execute("DELETE FROM lyrics_cache WHERE scope=? AND rowid IN(SELECT rowid FROM lyrics_cache WHERE scope=? ORDER BY fetched_ms DESC LIMIT -1 OFFSET 5000)",scope,scope)
    }
}
