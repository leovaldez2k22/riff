package com.musicglass.personal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** The five roots of the shell. Their names are what the tab bar shows and what saved state holds. */
internal enum class RootTab {
    Home, Podcasts, Radio, Search, Library;
    companion object {
        fun of(name: String?): RootTab? = entries.firstOrNull { it.name == name }
    }
}

internal enum class SettingsSection(val wire: String) {
    Main("settings"), PrivacyData("privacy_data"), NotInterested("not_interested"), EarlierData("earlier_data"),
    Diagnostics("diagnostics"), Equalizer("equalizer")
}

/**
 * A screen above a root. These are the screens the app has; a name that is not here is not a route.
 * [wire] is the name older saved states used and the screen host switches on.
 */
internal sealed interface Route {
    val wire: String
    data object Account : Route { override val wire = "account" }
    data object DeviceAudio : Route { override val wire = "device" }
    data object Downloads : Route { override val wire = "downloads" }
    data object ListeningHistory : Route { override val wire = "listening_history" }
    data class Settings(val section: SettingsSection = SettingsSection.Main) : Route { override val wire get() = section.wire }
    /** An album, a playlist, a podcast show or an artist (an id beginning UC). Made only through [collection]. */
    class Collection internal constructor(val id: String, val title: String) : Route {
        override val wire get() = "collection"
        val isArtist get() = id.startsWith("UC")
        override fun equals(other: Any?) = other is Collection && other.id == id && other.title == title
        override fun hashCode() = 31 * id.hashCode() + title.hashCode()
        override fun toString() = "Collection($id)"
    }
    /**
     * Feature 8: everything behind an artist shelf's "See all" (albums, singles, videos): YouTube's own browse id and
     * params for that grid. Made only through [artistShelf].
     */
    class ArtistShelf internal constructor(val browseId: String, val params: String?, val title: String) : Route {
        override val wire get() = "artist_shelf"
        override fun equals(other: Any?) = other is ArtistShelf && other.browseId == browseId && other.params == params && other.title == title
        override fun hashCode() = (31 * browseId.hashCode() + (params?.hashCode() ?: 0)) * 31 + title.hashCode()
        override fun toString() = "ArtistShelf($browseId)"
    }

    companion object {
        const val MAX_TITLE = 180
        private val COLLECTION_ID = Regex("[A-Za-z0-9_-]{2,200}")
        /** InnerTube params are URL-safe base64, sometimes percent-encoded. */
        private val SHELF_PARAMS = Regex("[A-Za-z0-9_%=-]{1,600}")
        private const val FIELD = '\u001f'
        private fun shownTitle(title: String?) = title.orEmpty().filter { c -> c >= ' ' && c != FIELD }.take(MAX_TITLE)
        /** Null when [id] is not a provider browse id. The title is display text only, cut to [MAX_TITLE]. */
        fun collection(id: String?, title: String?): Collection? =
            id?.takeIf(COLLECTION_ID::matches)?.let { Collection(it, shownTitle(title).ifBlank { string(R.string.collection) }) }
        /** Null when [browseId] or [params] are not what YouTube sends; the title is display text only. */
        fun artistShelf(browseId: String?, params: String?, title: String?): ArtistShelf? {
            val id = browseId?.takeIf(COLLECTION_ID::matches) ?: return null
            val shelfParams = params?.takeIf(String::isNotEmpty)
            if (shelfParams != null && !SHELF_PARAMS.matches(shelfParams)) return null
            return ArtistShelf(id, shelfParams, shownTitle(title).ifBlank { string(R.string.see_all) })
        }
        /** A route that carries no argument, by its saved name. */
        fun plain(wire: String): Route? = when (wire) {
            Account.wire -> Account; DeviceAudio.wire -> DeviceAudio; Downloads.wire -> Downloads; ListeningHistory.wire -> ListeningHistory
            else -> SettingsSection.entries.firstOrNull { it.wire == wire }?.let(::Settings)
        }
        fun encode(route: Route): String = when (route) {
            is Collection -> listOf(route.wire, route.id, route.title).joinToString(FIELD.toString())
            is ArtistShelf -> listOf(route.wire, route.browseId, route.params.orEmpty(), route.title).joinToString(FIELD.toString())
            else -> route.wire
        }
        /** Null for anything that is not a route this build has, or whose arguments are not valid. */
        fun decode(text: String): Route? {
            val fields = text.split(FIELD)
            return when (fields[0]) {
                "collection" -> fields.takeIf { it.size == 3 }?.let { collection(it[1], it[2]) }
                "artist_shelf" -> fields.takeIf { it.size == 4 }?.let { artistShelf(it[1], it[2], it[3]) }
                else -> fields.takeIf { it.size == 1 }?.let { plain(it[0]) }
            }
        }
    }
}

/** No stack grows past this; opening one more screen drops the oldest. */
internal const val MAX_STACK_DEPTH = 16

/**
 * What of the shell is kept across a configuration change or a restart of the process: where the user is and
 * what they typed. Named and versioned. It never holds a credential, a stream address or what is playing.
 */
internal data class ShellSavedState(
    val tab: RootTab = RootTab.Home,
    val stacks: Map<RootTab, List<Route>> = emptyMap(),
    val lastCollection: Route.Collection? = null,
    val searchQuery: String = "",
    val libraryFilter: String = "All",
    val searchSort: String = "Relevance",
    val searchFilter: String = "All",
) {
    /** The form the system keeps: a marker naming the version, then one "name=value" for each field. */
    fun toSaved(): List<String> = buildList {
        add(MARKER)
        fun put(name: String, value: String) { add("$name=$value") }
        put("tab", tab.name); put("query", searchQuery); put("libraryFilter", libraryFilter)
        put("searchSort", searchSort); put("searchFilter", searchFilter)
        stacks.forEach { (root, routes) -> if (routes.isNotEmpty()) put("stack.${root.name}", routes.joinToString(ROW.toString(), transform = Route::encode)) }
        lastCollection?.let { put("lastCollection", Route.encode(it)) }
    }

    companion object {
        const val VERSION = 2
        /** First element of the named form. The positional form began with a tab name, which this never is. */
        const val MARKER = "riff-shell/$VERSION"
        private const val MARKER_PREFIX = "riff-shell/"
        const val MAX_QUERY = 160
        private const val ROW = '\u001e'
        val LIBRARY_FILTERS = listOf("All", "Songs", "Favorites", "Playlists", "Albums", "Artists", "Podcasts", "History", "On device")
        val SEARCH_FILTERS = listOf("All", "Songs", "Albums", "Artists", "Playlists", "Podcasts")
        val SEARCH_SORTS = listOf("Relevance", "Title")

        /**
         * Reads whatever was saved: this build's named form, or the positional form of builds up to 0.10.0-rc1.
         * A version this build does not know, or a field that does not validate, gives the default. Never throws.
         */
        fun fromSaved(saved: List<String?>): ShellSavedState = runCatching {
            val first = saved.firstOrNull().orEmpty()
            when {
                first == MARKER -> fromNamed(saved.drop(1).filterNotNull().mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap())
                first.startsWith(MARKER_PREFIX) -> ShellSavedState()
                else -> fromPositional(saved)
            }
        }.getOrDefault(ShellSavedState())

        private fun fromNamed(map: Map<String, String>): ShellSavedState {
            fun text(key: String) = map[key]
            return ShellSavedState(
                tab = RootTab.of(text("tab")) ?: RootTab.Home,
                stacks = RootTab.entries.associateWith { root ->
                    text("stack.${root.name}").orEmpty().split(ROW).filter(String::isNotEmpty).mapNotNull(Route::decode).takeLast(MAX_STACK_DEPTH)
                }.filterValues { it.isNotEmpty() },
                lastCollection = text("lastCollection")?.let(Route::decode) as? Route.Collection,
                searchQuery = text("query").orEmpty().take(MAX_QUERY),
                libraryFilter = text("libraryFilter")?.takeIf { it in LIBRARY_FILTERS } ?: "All",
                searchSort = text("searchSort")?.takeIf { it in SEARCH_SORTS } ?: "Relevance",
                searchFilter = text("searchFilter")?.takeIf { it in SEARCH_FILTERS } ?: "All")
        }

        /**
         * The older form: values by position, one selected collection for the whole shell and a trail of the
         * collections under it. Routes those builds had and this one does not are dropped.
         */
        private fun fromPositional(values: List<String?>): ShellSavedState {
            val expanded = values.size >= 14
            val rc7 = values.getOrNull(16) == "rc7"
            val tab = RootTab.of(values.getOrNull(0).let { if (it == "Samples") "Podcasts" else it }) ?: RootTab.Home
            val savedTabs = if (values.size >= 15) RootTab.entries else listOf(RootTab.Home, RootTab.Podcasts, RootTab.Search, RootTab.Library)
            val names = savedTabs.mapIndexed { index, root -> root to values.getOrNull(index + if (expanded) 10 else 5).orEmpty().split(',').take(8) }
            val selected = if (expanded) Route.collection(values.getOrNull(6), values.getOrNull(15)) else null
            val trail = if (expanded && rc7) values.getOrNull(18).orEmpty().split(ROW).mapNotNull {
                val row = it.split('\u001f')
                if (row.size == 3) RootTab.of(row[0])?.let { root -> Route.collection(row[1], row[2])?.let { c -> root to c } } else null
            }.take(8) else emptyList()
            // The selected collection is the top one of whichever stack ends in a collection; the active tab is asked first.
            val owner = (listOf(tab) + savedTabs).firstOrNull { root -> names.firstOrNull { it.first == root }?.second?.lastOrNull { Route.plain(it) != null || it == "collection" } == "collection" }
            val stacks = names.associate { (root, list) ->
                val under = ArrayDeque(trail.filter { it.first == root }.map { it.second })
                val collections = list.count { it == "collection" }
                var seen = 0
                root to list.mapNotNull { name ->
                    if (name != "collection") Route.plain(name)
                    else { seen++; if (seen == collections && root == owner) selected else under.removeFirstOrNull() }
                }
            }.filterValues { it.isNotEmpty() }
            return ShellSavedState(tab, stacks, selected,
                searchQuery = values.getOrNull(2).orEmpty().take(MAX_QUERY),
                libraryFilter = values.getOrNull(3)?.takeIf { it in if (rc7) LIBRARY_FILTERS else listOf("All", "Favorites", "Playlists", "History", "On device") } ?: "All",
                searchSort = values.getOrNull(9)?.takeIf { expanded && it in SEARCH_SORTS } ?: "Relevance",
                searchFilter = values.getOrNull(17)?.takeIf { expanded && rc7 && it in SEARCH_FILTERS } ?: "All")
        }
    }
}

/**
 * One screen of the shell: a root ([depth] 0, no route) or the route [depth] screens above it. Two
 * collections opened one from the other are two screens with two keys, so each keeps its own scroll
 * position and Back returns to the first where it was left.
 */
internal data class ShellScreen(val tab: String, val depth: Int, val route: Route?) {
    val wire: String get() = route?.wire.orEmpty()
    val collection: Route.Collection? get() = route as? Route.Collection
    val key: String get() = "$tab/$depth/" + when (route) {
        is Route.Collection -> "collection:${route.id}"
        is Route.ArtistShelf -> "shelf:${route.browseId}:${route.params.orEmpty()}"
        else -> wire
    }
}

/** Where the user is in the shell, and the few things about a root that outlive leaving it. */
/** F1: a song that takes longer than this to start (a slow stream, a failure) no longer pulls the player open. */
internal const val SONG_TAP_OPENS_PLAYER_WITHIN_MS = 30_000L

/** F1: screens that start a song the owner tapped tell the shell here; RiffApp listens while it is shown. */
internal object SongTaps {
    @Volatile internal var listener: ((String) -> Unit)? = null
    fun tapped(mediaId: String) { listener?.invoke(mediaId) }
}

internal class ShellModel {
    private var root by mutableStateOf(RootTab.Home)
    private var routes by mutableStateOf(mapOf<RootTab, List<Route>>())
    private var lastCollection by mutableStateOf<Route.Collection?>(null)

    /** The selected root, by name. A name that is not a root is ignored. */
    var tab: String
        get() = root.name
        set(value) { RootTab.of(value)?.let(::show) }
    /** Each root's screens by their saved names, oldest first. */
    val stacks: Map<String, List<String>> get() = RootTab.entries.associate { it.name to routes[it].orEmpty().map(Route::wire) }
    /** The screen showing above the selected root; null at the root itself. */
    val current: Route? get() = routes[root]?.lastOrNull()
    /** [current] by its saved name, empty at a root. */
    val route: String get() = current?.wire.orEmpty()
    /** The screen showing: its root, how deep it is, and which collection it is. What a screen remembers is kept under its key. */
    val screen: ShellScreen get() = routes[root].orEmpty().let { stack -> ShellScreen(root.name, stack.size, stack.lastOrNull()) }
    /** The key of every screen that is open under any root, and of every root. */
    val screenKeys: Set<String> get() = RootTab.entries.flatMapTo(HashSet()) { tab ->
        val stack = routes[tab].orEmpty()
        (0..stack.size).map { depth -> ShellScreen(tab.name, depth, stack.getOrNull(depth - 1)).key }
    }
    /** The last search shown, kept in memory so that coming back to Search shows it again where it was. Never saved. */
    var searchSnapshot: SearchSnapshot? = null
    private var searchOwner: String? = null
    /**
     * The search on screen belongs to the owner it was made under. When the owner changes, the query, its
     * filter, sort and results are dropped, so they are neither shown to the next account nor saved to its
     * recent searches (Q18-012).
     */
    fun searchOwnerIs(owner: String) {
        if (searchOwner != null && searchOwner != owner) {
            searchQuery = ""; searchFilter = "All"; searchSort = "Relevance"; searchSnapshot = null
        }
        searchOwner = owner
    }
    /** The collection on screen, or the one last left, which is still drawn while its screen animates away. */
    private val shownCollection: Route.Collection? get() = routes[root]?.lastOrNull { it is Route.Collection } as? Route.Collection ?: lastCollection
    val selectedPlaylistId: String get() = shownCollection?.id.orEmpty()
    val selectedCollectionTitle: String get() = shownCollection?.title ?: string(R.string.collection)

    var miniVisible by mutableStateOf(false)
    /** F1: when the owner last tapped a song to play it; the full player opens once that song is the one playing. */
    private var playerWantedAtMs: Long? = null
    fun songTapped(mediaId: String, currentMediaId: String, nowMs: Long) {
        if (mediaId == currentMediaId && miniVisible) openPlayer() else playerWantedAtMs = nowMs
    }
    /** The playing song changed (or the first one started). A recent tap opens the player; a stale one is dropped. */
    fun mediaChanged(mediaId: String, nowMs: Long) {
        val wanted = playerWantedAtMs ?: return
        if (mediaId.isBlank()) return
        playerWantedAtMs = null
        if (nowMs - wanted in 0..SONG_TAP_OPENS_PLAYER_WITHIN_MS && miniVisible) openPlayer()
    }
    private fun openPlayer() { if (sheet.isEmpty()) sheet = "mini" }
    var searchQuery by mutableStateOf("")
    var homeMood by mutableStateOf("")
    var podcastGenre by mutableStateOf("For you")
    var radioCategory by mutableStateOf("Featured")
    var libraryFilter by mutableStateOf("All")
    var searchSort by mutableStateOf("Relevance")
    var searchFilter by mutableStateOf("All")
    var sheet by mutableStateOf("")
    var searchFocusRequest by mutableIntStateOf(0)
    private var lastTapName = ""
    private var lastTapMs: Long? = null
    var rootRevisions by mutableStateOf(mapOf<String, Int>())
        private set

    private fun show(next: RootTab) {
        if (next != root) (current as? Route.Collection)?.let { lastCollection = it }
        root = next
    }
    fun select(name: String) { tab = name }
    /** A second tap on the selected root within 350 ms returns it to its first screen. True when that happened. */
    fun selectFromTap(name: String, nowMs: Long): Boolean {
        val previous = lastTapMs
        val reset = lastTapName == name && previous != null && nowMs - previous in 0..350
        if (reset) {
            resetRoot(name)
            rootRevisions = rootRevisions + (name to ((rootRevisions[name] ?: 0) + 1))
            if (name == "Library") libraryFilter = "All"
            if (name == "Home") homeMood = ""
            if (name == "Podcasts") podcastGenre = "For you"
            if (name == "Radio") radioCategory = "Featured"
            if (name == "Search") searchFocusRequest++
            lastTapMs = null; lastTapName = ""
        } else { lastTapMs = nowMs; lastTapName = name }
        tab = name
        return reset
    }
    /** Closes every screen above [name]. */
    fun resetRoot(name: String) { RootTab.of(name)?.let { routes = routes - it } }
    fun push(route: Route) { routes = routes + (root to (routes[root].orEmpty() + route).takeLast(MAX_STACK_DEPTH)) }
    /** Opens an album, playlist, show or artist above the current screen. False, and nothing opens, when [id] is not a browse id. */
    fun openCollection(id: String, title: String): Boolean {
        val collection = Route.collection(id, title) ?: return false
        push(collection); return true
    }
    /** Feature 8: opens an artist shelf's full list. False, and nothing opens, when YouTube sent no usable link. */
    fun openArtistShelf(more: ProviderMoreEndpoint, title: String): Boolean {
        // A playlist of songs ("VL…") is an ordinary collection, with its paging, sort and song menus.
        if (more.browseId.startsWith("VL") && more.params == null) return openCollection(more.browseId, title)
        val shelf = Route.artistShelf(more.browseId, more.params, title) ?: return false
        push(shelf); return true
    }
    fun back() {
        val stack = routes[root].orEmpty()
        when {
            sheet == "queue" || sheet == "lyrics" || sheet == "libraryShortcuts" -> sheet = "mini"
            sheet.isNotEmpty() -> sheet = ""
            stack.isNotEmpty() -> {
                (stack.last() as? Route.Collection)?.let { lastCollection = it }
                routes = if (stack.size == 1) routes - root else routes + (root to stack.dropLast(1))
            }
            root != RootTab.Home -> show(RootTab.Home)
        }
    }

    fun savedState() = ShellSavedState(root, routes, shownCollection, searchQuery, libraryFilter, searchSort, searchFilter)
    private fun restore(state: ShellSavedState) {
        root = state.tab; routes = state.stacks; lastCollection = state.lastCollection
        searchQuery = state.searchQuery; libraryFilter = state.libraryFilter; searchSort = state.searchSort; searchFilter = state.searchFilter
    }

    companion object {
        /** Saves a [ShellSavedState]; restores that, or the positional list older builds saved. The player is never restored open. */
        val Saver = Saver<ShellModel, List<String>>(
            save = { it.savedState().toSaved() },
            restore = { saved -> ShellModel().apply { restore(ShellSavedState.fromSaved(saved)) } })
    }
}
