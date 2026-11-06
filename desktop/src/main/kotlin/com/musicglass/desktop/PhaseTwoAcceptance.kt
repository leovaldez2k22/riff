package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Uses the production host, separate native worker and physical sink. No fixture decoder/data adapter. */
internal object PhaseTwoAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        val base=File(args[0]).canonicalFile.apply{mkdirs()}
        val root=File(base,"profile-${System.currentTimeMillis()}").apply{mkdirs()}
        System.setProperty("riff.data.dir",root.path)
        val corpus=File(args[1]);val music=File(root,"音楽").apply{mkdirs()}
        for(ext in listOf("mp3","flac","m4a"))File(corpus,"reference.$ext").copyTo(File(music,"recorded.$ext"))
        val report=linkedMapOf<String,String>()
        suspend fun await(timeout:Long=30000,predicate:()->Boolean){withTimeout(timeout){while(!predicate())delay(50)}}
        var savedId="";var position=0L;var cachedArt:com.musicglass.data.TrackRecord?=null;var cachedDominant:Int?=null
        DesktopHost(directory=root).use{host->
            await{host.snapshot.initialized};host.dispatch(DesktopCommand.SetVolume(0f));host.awaitIdle().get();host.dispatch(DesktopCommand.RegisterLibraryRoot(music))
            await(60000){host.snapshot.libraryTracks.size==3 && host.snapshot.scanStatus?.startsWith("Library:")==true}
            check(host.snapshot.libraryTracks.all{it.durationMs>0&&it.available});report["compressedRootScan"]="3 actual MP3/FLAC/M4A files decoded by native worker"
            host.dispatch(DesktopCommand.CreatePlaylist("Acceptance local playlist"));await{host.snapshot.libraryPlaylists.size==1}
            val playlist=host.snapshot.libraryPlaylists.single().id;host.dispatch(DesktopCommand.OpenPlaylistDetail(playlist));val song=host.snapshot.libraryTracks.first{it.playbackSource.endsWith(".flac")}
            repeat(2){host.dispatch(DesktopCommand.AddToPlaylist(playlist,song))}
            await{host.snapshot.libraryPlaylists.single().tracks.size==2};report["duplicatePlaylist"]="two persisted occurrences"
            println("Decoded scan and duplicate playlist verified; testing rename")
            val renamed=File(music,"renamed.flac");check(File(song.playbackSource).renameTo(renamed))
            await(60000){host.snapshot.libraryTracks.any{it.id==song.id&&it.playbackSource==renamed.canonicalPath}}
            println("Native watcher rename verified")
            report["nativeWatcherRename"]="stable track identity; new source path"
            val current=host.snapshot.libraryTracks.first{it.id==song.id}
            host.dispatch(DesktopCommand.PlayTrack(current));await{host.snapshot.isPlaying&&host.snapshot.playbackPositionMs>1000}
            host.dispatch(DesktopCommand.SeekTo(2000));await{host.snapshot.playbackPositionMs>=2000}
            delay(2000);host.dispatch(DesktopCommand.SetPlaying(false));host.awaitIdle().get()
            await{(host.snapshot.encore?.listenedMs?:0)>500};report["presentedListening"]="${host.snapshot.encore?.listenedMs}ms from WASAPI presented frames"
            savedId=host.snapshot.currentTrack.id;position=host.snapshot.playbackPositionMs
            host.persistPlaybackSnapshot()
            host.dispatch(DesktopCommand.BrowseProvider())
            await(60000){host.snapshot.catalogPage is PageState.Ready}
            val online=host.snapshot.catalogPage as PageState.Ready
            check(!online.cached)
            val card=online.items.first{it.artworkUrl!=null}
            cachedArt=host.artworkRepository!!.obtain(card.song().record())
            cachedDominant=host.artworkRepository!!.metadata(cachedArt!!.artworkKey!!)!!.dominant
            report["productionCatalogCache"]="${online.items.size} actual Music cards committed through host to SQLite"

        }
        // Inject only a transport failure, never response data: offline content must come from the actual prior request.
        DesktopHost(directory=root,catalogGateway={throw java.io.IOException("Injected network loss for offline acceptance")}).use{host->
            await{host.snapshot.initialized};await{host.snapshot.libraryTracks.size==3}
            check(host.snapshot.currentTrack.id==savedId);check(kotlin.math.abs(host.snapshot.playbackPositionMs-position)<200)
            await{host.snapshot.libraryPlaylists.size==1};host.dispatch(DesktopCommand.OpenPlaylistDetail(host.snapshot.libraryPlaylists.single().id))
            await{host.snapshot.libraryPlaylists.singleOrNull()?.tracks?.size==2}
            host.dispatch(DesktopCommand.BrowseProvider())
            await(60000){(host.snapshot.catalogPage as? PageState.Ready)?.cached==true}
            val art=checkNotNull(cachedArt);val artwork=checkNotNull(host.artworkRepository)
            val reopened=artwork.obtain(art.copy(artworkKey=null))
            check(reopened.artworkKey==art.artworkKey);check(artwork.networkFetches.get()==0L)
            check(artwork.metadata(reopened.artworkKey!!)!!.dominant==cachedDominant);check(artwork.bitmap(reopened.artworkKey!!)!=null)
            report["offlineCache"]="Actual prior Music page restored after injected transport loss; cover + matching palette reopened with zero HTTP fetches"
            report["restart"]="SQLite library + duplicate playlist + real source queue restored at ${host.snapshot.playbackPositionMs}ms"
        }
        DesktopProvider(File(root,"provider")).use{provider->
            val first=provider.searchPage("Daft Punk");check(first.cards.isNotEmpty()){"Public search returned no cards"}
            report["publicCatalog"]="${first.cards.size} real public YouTube search cards"
            val token=checkNotNull(first.next){"No real continuation returned"}
            val second=provider.searchPage("Daft Punk",token);check(second.cards.isNotEmpty())
            check(second.cards.map{it.key()}!=first.cards.map{it.key()}){"Continuation repeated the first page"}
            report["realPagination"]="${second.cards.size} actual continuation cards"
            val home=provider.catalog();check(home.cards.isNotEmpty())
            report["musicHome"]="${home.cards.size} genuine Music cards"
            val homeToken=checkNotNull(home.next){"Music Home supplied no continuation"}
            val homeNext=provider.catalog(cursor=homeToken);check(homeNext.cards.isNotEmpty());check(home.cards.map{it.key()}!=homeNext.cards.map{it.key()})
            report["musicPagination"]="${homeNext.cards.size} genuine continuation cards"
            val explore=provider.catalog("FEmusic_explore")
            val album=checkNotNull(explore.cards.firstOrNull{it.kind==CatalogKind.Album&&it.browseId!=null})
            val collection=provider.catalog(album.browseId!!);check(collection.cards.any{it.videoId!=null})
            report["realAlbum"]="${collection.cards.size} cards from exact returned album endpoint"

        }
        File(base,"acceptance-${root.name}.json").writeText(buildJsonObject{put("profile",root.path);report.forEach{(key,value)->put(key,value)};put("auth","BLOCKED: owner has no Desktop OAuth client configured; no private Music contract verified");put("differentUserDpapi","NOT VERIFIED: requires a second Windows user")}.toString())
        println(report.entries.joinToString("\n"){"${it.key}: ${it.value}"})
    }
}
