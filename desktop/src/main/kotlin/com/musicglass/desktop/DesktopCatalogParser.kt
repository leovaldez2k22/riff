package com.musicglass.desktop
import kotlinx.serialization.json.*
// Parser rules ported from Android ProviderCatalog; primary endpoints retain exact provider IDs.
internal enum class CatalogKind {Song,Album,Artist,Playlist,Podcast,Episode,Live}
internal data class ProviderHomeCard(val title: String, val subtitle: String,
    val artworkUrl: String?, val videoId: String? = null, val browseId: String? = null, val kind: CatalogKind = CatalogKind.Song,
    val artistId: String? = null, val albumId: String? = null, val album: String = "",val musicVideo:Boolean=false,val officialMusicVideo:Boolean=false,val setVideoId:String?=null,val addedAt:Long?=null,
    val notInterestedToken:String?=null,
    /** The row's stated length (Q18-093); null when YouTube shows none. */
    val durationMs:Long?=null)
/** Feature 8: where a shelf's "See all" leads: YouTube's browse id, and its params for grids of albums, singles or videos. */
internal data class ProviderMoreEndpoint(val browseId: String, val params: String? = null)
internal data class ProviderHomeSection(val title: String, val cards: List<ProviderHomeCard>,
    /** Null when YouTube sent no "See all" for this shelf; then none is shown. */
    val more: ProviderMoreEndpoint? = null)
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
                    val candidateVideo = (catalogValue(navigation,"videoId") ?: catalogValue(item["playlistItemData"],"videoId")
                        ?: (item["videoId"] as? JsonPrimitive)?.contentOrNull
                        ?: catalogValue(item["playNavigationEndpoint"],"videoId")
                        ?: catalogValue(catalogRenderer(item["overlay"],"musicPlayButtonRenderer")?.get("playNavigationEndpoint"),"videoId"))?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                    val live = item.toString().contains("\"LIVE\"") || item.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")
                    val kind = catalogKind(page,browse,subtitle,live).let {
                        if(it == CatalogKind.Song && (catalogValue(navigation,"musicVideoType") ?: catalogValue(item["playNavigationEndpoint"],"musicVideoType")) == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") CatalogKind.Episode else it }
                    val video = candidateVideo.takeIf { kind in setOf(CatalogKind.Song,CatalogKind.Episode,CatalogKind.Live) }
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
                            notInterestedToken(item["menu"]),catalogDurationMs(item))
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
internal fun catalogShelfMore(shelf: JsonObject): ProviderMoreEndpoint? {
    val header = catalogRenderer(shelf, "musicCarouselShelfBasicHeaderRenderer")
    val candidates = listOf(
        shelf["bottomEndpoint"],
        catalogRenderer(header?.get("moreContentButton"), "buttonRenderer")?.get("navigationEndpoint"),
        catalogRenderer(header?.get("title"), "navigationEndpoint"))
    for (candidate in candidates) {
        val endpoint = catalogRenderer(candidate, "browseEndpoint") ?: continue
        val id = endpoint["browseId"]?.textOrNull?.takeIf(String::isNotBlank) ?: continue
        return ProviderMoreEndpoint(id, endpoint["params"]?.textOrNull?.takeIf(String::isNotBlank))
    }
    return null
}
private fun catalogArtistRun(node:JsonElement?):JsonObject? = when(node) {
    is JsonObject -> if(node["text"] is JsonPrimitive && catalogValue(node["navigationEndpoint"],"browseId")?.startsWith("UC")==true) node else node.values.firstNotNullOfOrNull(::catalogArtistRun)
    is JsonArray -> node.firstNotNullOfOrNull(::catalogArtistRun)
    else -> null
}
private val rowLength=Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})""")
/** Q18-093: a row's length, from its fixed column ("3:45", "1:02:03") or a queue row's lengthText. */
internal fun catalogDurationMs(item:JsonObject):Long? {
    val fixed=(item["fixedColumns"] as? JsonArray)?.mapNotNull {
        catalogText(((it as? JsonObject)?.get("musicResponsiveListItemFixedColumnRenderer") as? JsonObject)?.get("text")) }.orEmpty()
    return (fixed+catalogText(item["lengthText"])).firstNotNullOfOrNull { text ->
        rowLength.matchEntire(text.trim())?.destructured?.let { (h,m,s) -> ((h.toLongOrNull()?:0)*3600+m.toLong()*60+s.toLong())*1000 }
    }
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


internal fun ProviderHomeCard.key()=videoId?:browseId?:"$title:$subtitle"
internal fun providerArtworkUrl(id:String)="https://i.ytimg.com/vi/$id/hqdefault.jpg"
internal fun playerArtistLabel(text:String)=text.split('\u2022','\u00b7').first().trim()
internal fun notInterestedToken(node:JsonElement?):String?=catalogValue(node,"feedbackToken")
internal fun providerThumbnail(node:JsonElement?):String? {
    val urls=mutableListOf<Pair<String,Int>>()
    fun visit(value:JsonElement?){when(value){is JsonObject->{val url=value["url"]?.textOrNull;if(url?.startsWith("https://")==true)urls+=url to ((value["width"] as? JsonPrimitive)?.intOrNull?:0);value.values.forEach(::visit)};is JsonArray->value.forEach(::visit);else->Unit}}
    visit(node);return urls.maxByOrNull{it.second}?.first
}
internal class ProviderUnavailableException(message:String):java.io.IOException(message)
internal data class DesktopCatalogPage(val cards:List<ProviderHomeCard>,val next:String?,val title:String="",val cached:Boolean=false,val raw:String?=null)
internal fun parseDesktopCatalogPage(root:JsonElement):DesktopCatalogPage {
    val notice=catalogRenderer(root,"musicTastebuilderRenderer")?.let{catalogText(it["message"])}
    val restriction=Regex("YouTube Music isn.t available in your country",RegexOption.IGNORE_CASE).containsMatchIn(root.toString())
    if(restriction)throw ProviderUnavailableException("YouTube Music reports it is unavailable in this network’s country. Local music and public YouTube search remain available.")
    check(catalogRenderer(root,"error")==null){"Provider returned an error envelope"}
    val header=listOf("musicResponsiveHeaderRenderer","musicDetailHeaderRenderer","musicImmersiveHeaderRenderer").firstNotNullOfOrNull{catalogRenderer(root,it)}
    val collection=listOf("musicPlaylistShelfRenderer","musicPlaylistShelfContinuation","musicShelfContinuation").firstNotNullOfOrNull{catalogRenderer(root,it)}
    // A playlist row is an occurrence; preserve repeated video IDs by parsing each row separately.
    val contents=collection?.get("contents") as? JsonArray
    return DesktopCatalogPage(contents?.flatMap(::parseCatalogCards)?:parseCatalogCards(root),catalogContinuation(collection?:root),catalogText(header?.get("title")),raw=root.toString())
}

/** Full search and quick results share renderer semantics, never collection-menu video identities. */
internal fun playableSearchTracks(cards: List<ProviderHomeCard>): List<SongItem> = cards
    .filter { it.kind in setOf(CatalogKind.Song, CatalogKind.Episode, CatalogKind.Live)
        && it.videoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true }
    .distinctBy { it.videoId }.map { it.song() }

internal data class DesktopSearchResult(val cards: List<ProviderHomeCard>, val topResult: ProviderHomeCard? = null) {
    val tracks get() = playableSearchTracks(cards)
}
/** Confidence comes from the provider's dedicated shelf, never a local title heuristic. */
internal fun parseDesktopSearchResult(root: JsonElement): DesktopSearchResult {
    val cards=parseDesktopCatalogPage(root).cards
    val shelf=catalogRenderer(root,"musicCardShelfRenderer")
    val top=shelf?.let { parseCatalogCards(buildJsonObject { put("musicCardShelfRenderer",it) }).firstOrNull() }
        ?.takeIf { it.kind in setOf(CatalogKind.Artist,CatalogKind.Album) && it.browseId!=null }
    return DesktopSearchResult(cards,top)
}
