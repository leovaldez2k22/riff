package com.musicglass.desktop

import com.musicglass.data.encore.EncoreSort
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.*
import kotlinx.serialization.json.*

/** Local-only, formula-safe exports of the actual selected music period. No stream URLs or credentials. */
internal object DesktopEncoreExport {
    fun csv(summary:com.musicglass.data.encore.EncoreSummary):String=buildString{
        append("list,rank,title,subtitle,plays,listened_ms\r\n")
        for(kind in listOf("song","album","artist","genre"))summary.top(kind,EncoreSort.Minutes).forEachIndexed{index,item->
            fun field(value:String):String{
                val safe=if(value.trimStart().firstOrNull() in setOf('=','+','-','@')||value.firstOrNull() in setOf('\t','\r','\n'))"'$value"else value
                return "\""+safe.replace("\"","\"\"")+"\""
            }
            append(listOf(kind,"${index+1}",field(item.title),field(item.subtitle),"${item.plays}","${item.listenedMs}").joinToString(","));append("\r\n")
        }
    }
    fun json(summary:com.musicglass.data.encore.EncoreSummary):String=buildJsonObject{
        put("schema",1);put("range",summary.period.range.name);put("startInclusive",summary.period.start.toString());put("endExclusive",summary.period.end.toString())
        put("heardMs",summary.listenedMs);put("plays",summary.plays);put("songs",summary.songs);put("artists",summary.artists);put("albums",summary.albums)
        put("historyStart",summary.historyStart?.let{JsonPrimitive(it.toString())}?:JsonNull)
        put("hours",JsonArray(summary.hours.map(::JsonPrimitive)))
        put("calendar",JsonObject(summary.calendar.mapKeys{it.key.toString()}.mapValues{JsonPrimitive(it.value)}))
        put("top",buildJsonObject{for(kind in listOf("song","album","artist","genre"))put(kind,JsonArray(summary.top(kind).map{item->buildJsonObject{
            put("key",item.key);put("title",item.title);put("subtitle",item.subtitle);put("plays",item.plays);put("heardMs",item.listenedMs)
        }}))})
        put("streakDays",summary.habits.streakDays);put("completion",summary.habits.completion?.let(::JsonPrimitive)?:JsonNull)
        put("sources",JsonObject(summary.habits.sources.mapValues{JsonPrimitive(it.value)}))
    }.toString()
    /** Same-volume forced staging; a failed Windows replacement leaves the previous export intact. */
    fun write(file:File,summary:com.musicglass.data.encore.EncoreSummary,format:String,replace:Boolean=false){
        require(format in setOf("csv","json"))
        writeBytes(file,(if(format=="csv")csv(summary)else json(summary)).toByteArray(Charsets.UTF_8),replace)
    }
    fun png(bitmap:ImageBitmap):ByteArray {
        require(bitmap.width>0&&bitmap.height>0&&bitmap.width.toLong()*bitmap.height<=64000000)
        val scale=maxOf(1080f/bitmap.width,1920f/bitmap.height)
        val width=bitmap.width*scale;val height=bitmap.height*scale
        return org.jetbrains.skia.Surface.makeRasterN32Premul(1080,1920).use{surface->
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use{image->
                surface.canvas.clear(0xFF000000.toInt())
                surface.canvas.drawImageRect(image,org.jetbrains.skia.Rect.makeXYWH((1080-width)/2,(1920-height)/2,width,height))
            }
            surface.makeImageSnapshot().use{image->checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use{it.bytes}}
        }
    }
    fun writeBytes(file:File,bytes:ByteArray,replace:Boolean=false){
        require(bytes.size<=16*1024*1024)
        val target=file.absoluteFile.toPath();val parent=checkNotNull(target.parent);check(Files.isDirectory(parent))
        if(!replace)check(!Files.exists(target)){"The export file already exists"}
        val temporary=Files.createTempFile(parent,".riff-encore-",".part")
        try{
            FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING).use{channel->val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true)}
            if(replace)Files.move(temporary,target,ATOMIC_MOVE,REPLACE_EXISTING)else Files.createLink(target,temporary)
        }finally{Files.deleteIfExists(temporary)}
    }
}
