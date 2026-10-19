package com.musicglass.personal

import kotlinx.serialization.json.*

internal enum class CatalogKind(private val labelRes: Int) { Song(R.string.song), Album(R.string.album), Artist(R.string.artist), Playlist(R.string.playlist), Podcast(R.string.podcast), Episode(R.string.episode), Live(R.string.live_radio); val label: String get() = string(labelRes) }
internal data class CatalogPage(val cards: List<ProviderHomeCard>, val next: String? = null,
    val title: String = "", val subtitle: String = "", val artworkUrl: String? = null,
    val shelves: List<ProviderHomeSection> = emptyList(), val description: String = "",
    val collectionNext:String?=null,val albumTracks: List<ProviderHomeCard> = emptyList(),val artistName:String="",val artistId:String?=null,val editable:Boolean=false,val sortEndpoints:Map<CollectionSort,CollectionSortEndpoint> = emptyMap(),
    /** Set when [albumTracks] is not the whole collection. Null on a single page, which makes no claim either way. */
    val truncation:CollectionTruncation?=null) {
    val selectedSort:CollectionSort? get()=sortEndpoints.entries.firstOrNull {it.value.selected}?.key
}
internal fun ProviderHomeCard.key() = videoId ?: browseId ?: "$title:$subtitle"
internal fun ProviderHomeCard.track(): ProviderTrack? = videoId?.takeIf { kind in setOf(CatalogKind.Song,CatalogKind.Episode,CatalogKind.Live) }?.let {
    ProviderTrack(it,title,playerArtistLabel(subtitle),artworkUrl,artistId,albumId,album,kind == CatalogKind.Live,kind == CatalogKind.Song)
}
/** The text of a primitive, or null for anything else. A field of the wrong type is a missing field, not a crash. */
internal val JsonElement.textOrNull:String? get()=(this as? JsonPrimitive)?.contentOrNull
internal fun catalogText(node: JsonElement?): String = when(node) {
    is JsonObject -> node["simpleText"]?.textOrNull
        ?: (node["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.get("text")?.textOrNull.orEmpty() }.orEmpty()
    is JsonPrimitive -> node.contentOrNull.orEmpty()
    else -> ""
}
internal fun catalogValue(node: JsonElement?, key: String): String? = when(node) {
    is JsonObject -> (node[key] as? JsonPrimitive)?.contentOrNull ?: node.values.firstNotNullOfOrNull { catalogValue(it,key) }
    is JsonArray -> node.firstNotNullOfOrNull { catalogValue(it,key) }
    else -> null
}
internal fun catalogRenderer(node: JsonElement?, key: String): JsonObject? = when(node) {
    is JsonObject -> (node[key] as? JsonObject) ?: node.values.firstNotNullOfOrNull { catalogRenderer(it,key) }
    is JsonArray -> node.firstNotNullOfOrNull { catalogRenderer(it,key) }
    else -> null
}
/** Q18-038: a count of a list's songs; [complete] is false only when counting stopped at its limit. */
internal data class LibraryCount(val total: Int, val complete: Boolean)
/** Past this many liked songs the count stops and shows a floor ("20000+"): about 200 paced pages. */
internal const val LIKED_SONGS_COUNT_LIMIT = 20_000
internal const val LIBRARY_PLAYLIST_SCAN_LIMIT = 2000

private val headerSongCount = Regex("""(\d[\d,.\u00A0\u202F ]*)(\+?)\s*(songs?|tracks?)\b""", RegexOption.IGNORE_CASE)

/**
 * Q18-038: the number of songs a playlist's header states ("1,234 songs" in its second subtitle), or null when it states
 * none, or only a floor ("100+ songs"): then the songs are counted page by page instead.
 */
internal fun collectionHeaderSongCount(root: JsonElement): Int? {
    for (renderer in listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer")) {
        val header = catalogRenderer(root, renderer) ?: continue
        for (field in listOf("secondSubtitle", "subtitle")) {
            val match = headerSongCount.find(catalogText(header[field])) ?: continue
            if (match.groupValues[2] == "+") return null
            return match.groupValues[1].filter(Char::isDigit).toIntOrNull()
        }
    }
    return null
}

/**
 * Q18-038: counts a list's songs to its end, [first] page then each next one; distinct songs only, stopping at a token
 * seen before or at [limit]. [onCount] hears the count after every page.
 */
internal suspend fun countThroughPages(first: ProviderSearchPage, limit: Int, next: suspend (String) -> ProviderSearchPage,
    onCount: (Int) -> Unit = {}): LibraryCount {
    val songs = HashSet<String>(); val tokens = HashSet<String>()
    var page = first
    while (true) {
        page.tracks.forEach { songs.add(it.videoId) }
        onCount(minOf(songs.size, limit))
        if (songs.size >= limit) return LibraryCount(limit, complete = false)
        val token = page.nextToken ?: break
        if (!tokens.add(token)) break
        page = next(token)
    }
    return LibraryCount(songs.size, complete = true)
}

internal fun catalogContinuation(node: JsonElement): String? {
    val next = catalogRenderer(node,"nextRadioContinuationData") ?: catalogRenderer(node,"nextContinuationData") ?: catalogRenderer(node,"continuationCommand")
    return next?.get("continuation")?.textOrNull ?: next?.get("token")?.textOrNull
}
internal fun catalogKind(page: String?, browse: String?, subtitle: String, live: Boolean): CatalogKind = when {
    live -> CatalogKind.Live
    browse?.startsWith("MPED") == true || page?.contains("PODCAST_EPISODE") == true || page?.contains("NON_MUSIC_AUDIO_TRACK") == true -> CatalogKind.Episode
    page?.contains("PODCAST_SHOW") == true || browse?.startsWith("MPSP") == true || subtitle.contains("podcast",true) || subtitle.contains("show \u00B7",true) -> CatalogKind.Podcast
    page?.contains("ARTIST") == true || page?.contains("USER_CHANNEL") == true || browse?.startsWith("UC") == true -> CatalogKind.Artist
    page?.contains("ALBUM") == true || browse?.startsWith("MPRE") == true -> CatalogKind.Album
    page?.contains("PLAYLIST") == true || browse?.startsWith("VL") == true -> CatalogKind.Playlist
    else -> CatalogKind.Song
}
internal fun parseCatalogCards(root: JsonElement): List<ProviderHomeCard> {
    val result = linkedMapOf<String,ProviderHomeCard>()
    fun visit(node: JsonElement) {
        when(node) {
            is JsonObject -> {
                val lockup = node["lockupViewModel"] as? JsonObject
                if(lockup != null) {
                    val id = lockup["contentId"]?.textOrNull
                    val meta = catalogRenderer(lockup["metadata"],"lockupMetadataViewModel")
                    val title = (meta?.get("title") as? JsonObject)?.get("content")?.textOrNull.orEmpty()
                    val image = lockup["contentImage"]
                    val live = image.toString().contains("THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE")
                        || catalogValue(image,"text") == "LIVE"
                    if(id?.matches(Regex("[A-Za-z0-9_-]{11}")) == true && title.isNotBlank()) {
                        val creator = catalogLockupCreator(meta)
                        val card = ProviderHomeCard(title,creator?.first.orEmpty(),providerThumbnail(image),videoId=id,artistId=creator?.second,
                            kind=if(live) CatalogKind.Live else CatalogKind.Song)
                        result[id] = card
                    }
                }
                val item = listOf("musicTwoRowItemRenderer","musicResponsiveListItemRenderer","musicMultiRowListItemRenderer",
                    "musicCardShelfRenderer","playlistPanelVideoRenderer","videoRenderer","gridVideoRenderer","richItemRenderer")
                    .firstNotNullOfOrNull { node[it] as? JsonObject }
                if (item != null && node["richItemRenderer"] == null) {
                    val cols = item["flexColumns"] as? JsonArray
                    fun column(i:Int) = ((cols?.getOrNull(i) as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)?.get("text")
                    val title = catalogText(item["title"]).ifBlank { catalogText(column(0)) }
                    val subtitleNode = item["subtitle"] ?: item["longBylineText"] ?: item["shortBylineText"] ?: item["ownerText"] ?: column(1)
                    val subtitle = catalogText(subtitleNode)
                    val navigation = item["navigationEndpoint"] ?: item["onTap"] ?: catalogRenderer(item["title"],"navigationEndpoint") ?: catalogRenderer(column(0),"navigationEndpoint")
                    val browse = catalogValue(navigation,"browseId")
                    val page = catalogValue(navigation,"pageType") ?: catalogValue(item["endpoint"],"pageType")
                    val video = (catalogValue(navigation,"videoId") ?: catalogValue(item["playlistItemData"],"videoId")
                        ?: (item["videoId"] as? JsonPrimitive)?.contentOrNull
                        ?: catalogValue(item["playNavigationEndpoint"],"videoId")
                        ?: catalogValue(catalogRenderer(item["overlay"],"musicPlayButtonRenderer")?.get("playNavigationEndpoint"),"videoId"))?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                    val live = item.toString().contains("\"LIVE\"") || item.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")
                    val kind = catalogKind(page,browse,subtitle,live).let {
                        if(it == CatalogKind.Song && (catalogValue(navigation,"musicVideoType") ?: catalogValue(item["playNavigationEndpoint"],"musicVideoType")) == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") CatalogKind.Episode else it }
                    // A collection's menu can contain playable video IDs. Only the main endpoint defines its kind.
                    if (title.isNotBlank() && (video != null || browse != null)) {
                        val bylines = listOf(item["longBylineText"], item["shortBylineText"], item["ownerText"], subtitleNode, column(1), column(2))
                        val artistRun = bylines.firstNotNullOfOrNull(::catalogArtistRun)
                        val albumRun = (subtitleNode as? JsonObject)?.get("runs")?.let {it as? JsonArray}?.firstOrNull {
                            catalogValue(it,"browseId")?.startsWith("MPRE") == true }
                        val artistName = catalogText(artistRun).ifBlank { (artistRun as? JsonObject)?.get("text")?.textOrNull.orEmpty() }
                        val art = providerThumbnail(item["thumbnail"]) ?: providerThumbnail(item["thumbnailRenderer"]) ?: providerThumbnail(item["thumbnails"])
                        val card = ProviderHomeCard(title, if (video != null) artistName.ifBlank { bylines.firstNotNullOfOrNull { playerArtistLabel(catalogText(it)).takeIf(String::isNotBlank) }.orEmpty() } else subtitle,
                            art ?: video?.let(::providerArtworkUrl), video,browse,kind,
                            catalogValue(artistRun,"browseId"),catalogValue(albumRun,"browseId"),
                            (albumRun as? JsonObject)?.get("text")?.textOrNull.orEmpty(),
                            catalogValue(item,"musicVideoType") in setOf("MUSIC_VIDEO_TYPE_OMV","MUSIC_VIDEO_TYPE_UGC"),
                            catalogValue(item,"musicVideoType")=="MUSIC_VIDEO_TYPE_OMV",
                            catalogValue(item["playlistItemData"],"playlistSetVideoId")?:catalogValue(item,"setVideoId"),playlistEntryAddedAt(item),
                            notInterestedToken(item["menu"]))
                        result[card.key()] = card
                    }
                }
                node.entries.filterNot {it.key=="counterpart"}.forEach {visit(it.value)}
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return result.values.toList()
}
internal fun podcastShelf(shelf: ProviderHomeSection): Boolean = shelf.title.contains("podcast",true)
    || shelf.title.contains("shows for you",true) || shelf.title.contains("episodes",true)
    || shelf.cards.isNotEmpty() && shelf.cards.all { it.kind in setOf(CatalogKind.Podcast,CatalogKind.Episode) }
private fun catalogArtistRun(node:JsonElement?):JsonObject? = when(node) {
    is JsonObject -> if(node["text"] is JsonPrimitive && catalogValue(node["navigationEndpoint"],"browseId")?.startsWith("UC")==true) node else node.values.firstNotNullOfOrNull(::catalogArtistRun)
    is JsonArray -> node.firstNotNullOfOrNull(::catalogArtistRun)
    else -> null
}
internal fun catalogPage(root: JsonElement): CatalogPage {
    val header = listOf("musicResponsiveHeaderRenderer","musicDetailHeaderRenderer","musicImmersiveHeaderRenderer","musicEditablePlaylistDetailHeaderRenderer")
        .firstNotNullOfOrNull { catalogRenderer(root,it) }
    val artistRun=catalogArtistRun(header)
    val shelves = mutableListOf<ProviderHomeSection>()
    fun visit(node:JsonElement) {
        when(node) {
            is JsonObject -> {
                val shelf = node["musicCarouselShelfRenderer"] ?: node["musicShelfRenderer"] ?: node["musicPlaylistShelfRenderer"]
                if (shelf is JsonObject) {
                    val title = catalogText(catalogRenderer(shelf,"musicCarouselShelfBasicHeaderRenderer")?.get("title"))
                        .ifBlank { catalogText(shelf["title"]) }
                    val cards = parseCatalogCards(shelf["contents"] ?: JsonArray(emptyList()))
                    if(cards.isNotEmpty()) shelves += ProviderHomeSection(title.ifBlank { "Songs" },cards)
                }
                node.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return CatalogPage(parseCatalogCards(root),catalogContinuation(root),catalogText(header?.get("title")),
        catalogText(header?.get("subtitle")) .ifBlank { catalogText(header?.get("description")) },
        providerThumbnail(header?.get("thumbnail")) ?: providerThumbnail(header?.get("foregroundThumbnail")),shelves,
        catalogText(catalogRenderer(root,"musicDescriptionShelfRenderer")?.get("description")).ifBlank {catalogText(header?.get("description"))},
        collectionShelf(root)?.let(::catalogContinuation),orderedCollectionTracks(root),
        catalogText(header?.get("straplineText")).ifBlank {catalogText(header?.get("strapline"))}.ifBlank {artistRun?.get("text")?.textOrNull.orEmpty()},
        catalogValue(header?.get("straplineText"),"browseId")?:catalogValue(artistRun,"browseId"),
        catalogRenderer(root,"musicEditablePlaylistDetailHeaderRenderer")!=null || catalogValue(root,"isEditable")=="true" || catalogRenderer(root,"playlistEditEndpoint")!=null,collectionSortEndpoints(root))
}
internal fun parseQueueTracks(root: JsonElement): List<ProviderTrack> = parseCatalogCards(
    catalogRenderer(root,"playlistPanelRenderer") ?: catalogRenderer(root,"playlistPanelContinuation") ?: root)
    .mapNotNull { it.track() }.filterNot(ProviderTrack::isLive).distinctBy(ProviderTrack::videoId)
/** The "Not interested" token of [videoId]'s own row in a watch-next response, if YouTube Music offers one. */
internal fun notInterestedTokenFor(root: JsonElement, videoId: String): String? {
    fun visit(node: JsonElement): String? = when(node) {
        is JsonObject -> (node["playlistPanelVideoRenderer"] as? JsonObject)?.takeIf { (it["videoId"] as? JsonPrimitive)?.contentOrNull == videoId }
            ?.let { notInterestedToken(it["menu"]) } ?: node.values.firstNotNullOfOrNull(::visit)
        is JsonArray -> node.firstNotNullOfOrNull(::visit)
        else -> null
    }
    return visit(root)
}
internal fun parseLikeStatus(root: JsonElement): Boolean? = catalogValue(root,"likeStatus")?.let {
    when(it) { "LIKE" -> true; "DISLIKE","INDIFFERENT" -> false; else -> null }
}

/** Balanced extraction avoids regex truncation inside nested YouTube renderer data. */
internal fun youtubeInitialData(html: String): JsonElement? {
    val marker = Regex("(?:var\\s+ytInitialData|window\\[\"ytInitialData\"\\]|ytInitialData)\\s*=") .find(html) ?: return null
    val start = html.indexOf('{',marker.range.last+1)
    if(start < 0) return null
    var depth=0;var quoted=false;var escaped=false
    for(i in start until minOf(html.length,start+5_000_000)) {
        val c=html[i]
        if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
        else when(c) { '"' -> quoted=true; '{' -> depth++; '}' -> { depth--; if(depth==0) return runCatching { Json.parseToJsonElement(html.substring(start,i+1)) }.getOrNull() } }
    }
    return null
}

/** Audio and official-video copies of one recording should not repeat in automatic radio. */
internal fun queueRecordingKey(title:String,artist:String):String {
    val clean=title.replace(Regex("(?i)\\s*[\\[(](?:official\\s+)?(?:music\\s+)?(?:video|audio|lyrics?|visuali[sz]er)[\\])]"),"")
    val primary=playerArtistLabel(artist).removeSuffix(" - Topic").split(Regex("(?i)\\s+(?:&|feat\\.?|ft\\.?)\\s+|,\\s*")).first().trim()
    return lyricsNormalize(clean)+":"+lyricsNormalize(primary)
}
internal fun queueCandidates(tracks: List<ProviderTrack>, known: Set<String>, knownRecordings:Set<String> = emptySet()): List<ProviderTrack> =
    tracks.filter { !it.isLive && it.videoId !in known && queueRecordingKey(it.title,it.artist) !in knownRecordings && it.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) }
        .distinctBy(ProviderTrack::videoId).distinctBy {queueRecordingKey(it.title,it.artist)}

internal fun catalogSearchFilter(root: JsonElement, label: String): String? {
    fun visit(node: JsonElement): String? = when(node) {
        is JsonObject -> {
            val chip=node["chipCloudChipRenderer"] as? JsonObject
            if(chip!=null && catalogText(chip["text"]).equals(label,true)) catalogValue(chip["navigationEndpoint"],"params")
            else node.values.firstNotNullOfOrNull(::visit)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::visit)
        else -> null
    }
    return visit(root)
}
internal fun relatedBrowseId(root: JsonElement): String? {
    fun visit(node: JsonElement): String? = when(node) {
        is JsonObject -> {
            val endpoint=node["browseEndpoint"] as? JsonObject
            if(endpoint!=null && catalogValue(endpoint,"pageType")=="MUSIC_PAGE_TYPE_TRACK_RELATED") catalogValue(endpoint,"browseId")
            else node.values.firstNotNullOfOrNull(::visit)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::visit)
        else -> null
    }
    return visit(root)
}
internal fun catalogBrowseFilter(root: JsonElement,label: String): Pair<String,String?>? {
    fun visit(node: JsonElement): Pair<String,String?>? = when(node) {
        is JsonObject -> {
            val chip=(node["chipCloudChipRenderer"] ?: node["musicNavigationButtonRenderer"]) as? JsonObject
            val name=catalogText(chip?.get("text")).ifBlank {catalogText(chip?.get("buttonText"))}
            val endpoint=chip?.get("navigationEndpoint") ?: chip?.get("clickCommand")
            val browse=catalogValue(endpoint,"browseId")
            if(chip!=null && name.equals(label,true) && browse!=null) browse to catalogValue(endpoint,"params")
            else node.values.firstNotNullOfOrNull(::visit)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::visit)
        else -> null
    }
    return visit(root)
}

internal fun orderedCollectionTracks(root:JsonElement):List<ProviderHomeCard> {
    val contents=collectionShelf(root)?.get("contents") as? JsonArray ?: return emptyList()
    return contents.flatMap(::parseCatalogCards).filter {it.kind in setOf(CatalogKind.Song,CatalogKind.Episode)}
}

private fun collectionShelf(root:JsonElement):JsonObject? = listOf("musicPlaylistShelfRenderer","musicPlaylistShelfContinuation","musicShelfContinuation","musicShelfRenderer")
    .firstNotNullOfOrNull {catalogRenderer(root,it)}
    ?: ((catalogRenderer(root,"appendContinuationItemsAction") ?: catalogRenderer(root,"reloadContinuationItemsCommand"))?.get("continuationItems") as? JsonArray)
        ?.let {JsonObject(mapOf("contents" to it))}

internal fun parseHomeMoods(root:JsonElement):List<HomeMood> {
    val result=linkedMapOf<String,HomeMood>()
    fun visit(node:JsonElement) {when(node) {
        is JsonObject -> {
            val chip=(node["chipCloudChipRenderer"]?:node["musicNavigationButtonRenderer"]) as? JsonObject
            if(chip!=null) {
                val title=catalogText(chip["text"]).ifBlank {catalogText(chip["buttonText"])}
                val endpoint=chip["navigationEndpoint"]?:chip["clickCommand"]?:chip["command"]
                val id=catalogValue(endpoint,"browseId");val params=catalogValue(endpoint,"params")
                if(title.isNotBlank() && title !in listOf("Podcasts","All","Music","Videos") && id!=null && (params!=null || id!="FEmusic_home"))
                    result[title]=HomeMood(title,id,params)
            }
            node.values.forEach(::visit)
        }
        is JsonArray->node.forEach(::visit)
        else->Unit
    }}
    visit(root);return result.values.toList()
}

internal fun playlistEntryAddedAt(item:JsonObject):Long? {
    val text=listOf("dateAdded","addedAt","addedTimestamp").firstNotNullOfOrNull {key->catalogValue(item["playlistItemData"],key)?: (item[key] as? JsonPrimitive)?.contentOrNull}?:return null
    return text.toLongOrNull()?.takeIf {it>0}?.let {if(it<1_000_000_000_000) it*1000 else it}
        ?: runCatching {java.time.Instant.parse(text).toEpochMilli()}.getOrNull()
}

/** Modern video tiles put creator links in attributed text, apart from the view-count row. */
private fun catalogLockupCreator(node: JsonElement?): Pair<String,String>? = when(node) {
    is JsonObject -> {
        val id = catalogValue(node["commandRuns"], "browseId")?.takeIf { it.startsWith("UC") }
        val content = (node["content"] as? JsonPrimitive)?.contentOrNull
        if(id != null && !content.isNullOrBlank()) content to id
        else node.values.firstNotNullOfOrNull(::catalogLockupCreator)
    }
    is JsonArray -> node.firstNotNullOfOrNull(::catalogLockupCreator)
    else -> null
}

internal data class CollectionSortEndpoint(
    val browseId:String?=null,val params:String?=null,val continuation:String?=null,
    val playlistEdit:PlaylistSortEdit?=null,val selected:Boolean=false
)

/** Only preference changes from a provider sort menu may be sent to edit_playlist. */
internal data class PlaylistSortEdit(val playlistId:String,val action:JsonObject,val params:String?=null) {
    fun validFor(id:String):Boolean = playlistId==id.removePrefix("VL") &&
        playlistId.matches(Regex("[A-Za-z0-9_-]{2,200}")) && validPlaylistSortAction(action)
}
internal fun validPlaylistSortAction(action:JsonObject):Boolean = when((action["action"] as? JsonPrimitive)?.contentOrNull) {
    "ACTION_SET_PLAYLIST_VIDEO_ORDER" -> action.keys==setOf("action","playlistVideoOrder") &&
        (action["playlistVideoOrder"] as? JsonPrimitive)?.intOrNull in setOf(0,1,2,6)
    "ACTION_SET_PLAYLIST_DYNAMIC_SORT_PREFERENCE" -> action.keys==setOf("action","playlistDynamicSortPreference") &&
        (action["playlistDynamicSortPreference"] as? JsonPrimitive)?.contentOrNull?.isNotBlank()==true
    else -> false
}

/** Follow supplied commands, preserving opaque params; never infer dates or vote counts. */
internal fun collectionSortEndpoints(root:JsonElement):Map<CollectionSort,CollectionSortEndpoint> {
    val found=linkedMapOf<CollectionSort,CollectionSortEndpoint>()
    fun item(item:JsonObject) {
        val label=catalogText(item["title"]).ifBlank {catalogText(item["text"])}
        val sort=when(label.lowercase()) {
            "top voted"->CollectionSort.TopVoted
            "manual ordering", "playlist order", "default"->CollectionSort.Original
            "newest first", "date added (newest)", "date added (newest first)"->CollectionSort.Newest
            "oldest first", "date added (oldest)", "date added (oldest first)"->CollectionSort.Oldest
            "title"->CollectionSort.Title
            "artist"->CollectionSort.Artist
            "album"->CollectionSort.Album
            else->return
        }
        val command=item["selectedCommand"] ?: item["navigationEndpoint"] ?: item["serviceEndpoint"]
        val reload=catalogRenderer(command,"browseSectionListReloadEndpoint") ?: catalogRenderer(command,"reloadContinuationItemsCommand")
        val token=catalogValue(reload,"continuation") ?: catalogValue(reload,"token")
        val browse=catalogRenderer(command,"browseEndpoint")
        val edit=catalogRenderer(command,"playlistEditEndpoint")
        val action=(edit?.get("actions") as? JsonArray)?.singleOrNull() as? JsonObject
        val playlistId=(edit?.get("playlistId") as? JsonPrimitive)?.contentOrNull
        val playlistEdit=if(playlistId!=null && action!=null && validPlaylistSortAction(action))
            PlaylistSortEdit(playlistId,action,(edit["params"] as? JsonPrimitive)?.contentOrNull) else null
        if(token!=null || browse!=null || playlistEdit!=null) found[sort]=CollectionSortEndpoint(
            catalogValue(browse,"browseId"),catalogValue(browse,"params"),token,playlistEdit,
            (item["selected"] as? JsonPrimitive)?.booleanOrNull==true || (item["isSelected"] as? JsonPrimitive)?.booleanOrNull==true)
    }
    fun visit(node:JsonElement) {when(node) {
        is JsonObject -> {
            (node["musicMultiSelectMenuItemRenderer"] as? JsonObject)?.let(::item)
            val submenu=node["sortFilterSubMenuRenderer"] as? JsonObject
            (submenu?.get("subMenuItems") as? JsonArray)?.forEach {(it as? JsonObject)?.let(::item)}
            node.values.forEach(::visit)
        }
        is JsonArray->node.forEach(::visit)
        else->Unit
    }}
    visit(root)
    return found
}
