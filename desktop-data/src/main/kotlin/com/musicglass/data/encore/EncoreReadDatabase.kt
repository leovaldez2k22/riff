package com.musicglass.data.encore

import com.musicglass.data.SqliteLibraryRepository
import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** One bounded read-only WAL connection per active projection; never locks the playback writer. */
class EncoreReadDatabase(store:SqliteLibraryRepository,private val queryObserver:((String,Long)->Unit)?=null):AutoCloseable {
    private val connection=DriverManager.getConnection("jdbc:sqlite:"+File(store.directory,"library.sqlite").absolutePath,
        org.sqlite.SQLiteConfig().apply{setReadOnly(true);setBusyTimeout(5000)}.toProperties())
    private val statements=java.util.LinkedHashMap<String,PreparedStatement>(32,.75f,true)
    init{try{connection.createStatement().use{it.execute("PRAGMA query_only=ON");it.execute("PRAGMA cache_size=-4096")}}catch(error:Throwable){connection.close();throw error}}
    val readableDatabase get()=this
    @Synchronized fun <T> snapshot(action:()->T):T {
        if(!connection.autoCommit)return action()
        connection.autoCommit=false
        try{val result=action();connection.commit();return result}
        catch(error:Throwable){connection.rollback();throw error}
        finally{connection.autoCommit=true}
    }
    @Synchronized fun <T> read(sql:String,args:Array<String>,project:(EncoreCursor)->T):T {
        val start=if(queryObserver==null)0 else System.nanoTime()
        try{
            val statement=statements[sql]?:connection.prepareStatement(sql).also{created->
                created.queryTimeout=5;statements[sql]=created
                if(statements.size>64){val first=statements.entries.iterator();first.next().value.close();first.remove()}
            }
            statement.clearParameters();args.forEachIndexed{index,value->statement.setString(index+1,value)}
            return statement.executeQuery().use{rows->project(EncoreCursor(rows))}
        }finally{queryObserver?.invoke(sql,(System.nanoTime()-start)/1000000)}
    }
    @Synchronized override fun close(){statements.values.forEach{it.close()};statements.clear();connection.close()}
}
class EncoreCursor internal constructor(private val rows:ResultSet){
    private var advanced=false
    fun moveToNext():Boolean{advanced=true;return rows.next()}
    fun moveToFirst():Boolean{check(!advanced){"Encore cursor is forward-only"};return moveToNext()}
    fun isNull(column:Int)=rows.getObject(column+1)==null
    fun getString(column:Int):String=rows.getString(column+1).orEmpty()
    fun getLong(column:Int):Long=rows.getLong(column+1)
    fun getInt(column:Int):Int=rows.getInt(column+1)
}
