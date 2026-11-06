package com.musicglass.desktop
import java.io.File
import kotlinx.coroutines.runBlocking
internal object PhaseTwoCatalogProbe {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val directory=File(args[0]).apply{mkdirs()}
        DesktopProvider(File(directory,"provider")).use{provider->
            println("Provider locale: ${provider.localeDescription}")
            for(id in listOf("FEmusic_home","FEmusic_explore","FEmusic_moods_and_genres"))try{
                val page=provider.catalog(id);File(directory,"$id.json").writeText(page.raw.orEmpty());println("$id cards=${page.cards.size} cursor=${page.next!=null}")
                if(id=="FEmusic_home"&&page.next!=null){val next=provider.catalog(cursor=page.next);File(directory,"home-next.json").writeText(next.raw.orEmpty());println("Music Home continuation cards=${next.cards.size}")}
                if(id=="FEmusic_explore"){page.cards.firstOrNull{it.kind==CatalogKind.Album&&it.browseId!=null}?.let{album->val loaded=provider.catalog(album.browseId!!);File(directory,"album.json").writeText(loaded.raw.orEmpty());println("Actual album cards=${loaded.cards.size}")}}
                page.cards.take(5).forEach{println("${it.kind} browseId=${it.browseId} videoId=${it.videoId}")}
            }catch(error:Exception){println("$id failed: ${error.javaClass.simpleName}")}
            val search=provider.searchPage("Daft Punk");println("Public search cards=${search.cards.size} cursor=${search.next!=null}");File(directory,"public-search.json").writeText(search.raw.orEmpty());if(search.next!=null){val second=provider.searchPage("Daft Punk",search.next);println("Public continuation cards=${second.cards.size}")}
            val radio=provider.recommendations("dQw4w9WgXcQ");File(directory,"radio.json").writeText(radio.raw.orEmpty());println("Radio cards=${radio.cards.size} cursor=${radio.next!=null}")
        }
    }
}
