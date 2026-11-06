package com.musicglass.data

/** Opaque provider payloads are scoped by account generation at the caller; no credentials enter this table. */
class CatalogCacheRepository(private val store:SqliteLibraryRepository){
    data class Page(val payload:String,val next:String?,val fetchedMs:Long)
    fun put(scope:String,key:String,cursor:String?,payload:String,next:String?)=store.transaction{
        require(key.length<=1000 && (cursor?.length?:0)<=32768 && payload.length<=4*1024*1024)
        execute("INSERT INTO catalog_page VALUES(?,?,?,?,?,?) ON CONFLICT(scope,request_key,cursor) DO UPDATE SET payload=excluded.payload,next_cursor=excluded.next_cursor,fetched_ms=excluded.fetched_ms",Scopes.validate(scope),key,cursor.orEmpty(),payload,next,System.currentTimeMillis())
        execute("DELETE FROM catalog_page WHERE scope=? AND rowid IN(SELECT rowid FROM catalog_page WHERE scope=? ORDER BY fetched_ms DESC LIMIT -1 OFFSET 200)",scope,scope)
    }
    fun get(scope:String,key:String,cursor:String?):Page?=store.query("SELECT payload,next_cursor,fetched_ms FROM catalog_page WHERE scope=? AND request_key=? AND cursor=?",Scopes.validate(scope),key,cursor.orEmpty()){Page(it.getString(1),it.getString(2),it.getLong(3))}.singleOrNull()
}
