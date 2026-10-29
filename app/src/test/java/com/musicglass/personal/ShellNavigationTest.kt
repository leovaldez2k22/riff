package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Q11: typed routes, bounded stacks and the named, versioned form the shell is saved in. */
class ShellNavigationTest {
    private val album = "MPREb_fixtureAlbum01"
    private val artist = "UCfixtureArtist00000001"
    private val everyRoute = listOf(Route.Account, Route.DeviceAudio, Route.Downloads, Route.ListeningHistory) +
        SettingsSection.entries.map { Route.Settings(it) } + Route.collection(album, "Fixture Album")!!

    // ---- routes

    @Test fun everyRouteSurvivesBeingSavedAndReadBack() {
        everyRoute.forEach { assertEquals(it, Route.decode(Route.encode(it))) }
        assertEquals("Each route has its own saved name, collections sharing one", everyRoute.size, everyRoute.map(Route::wire).toSet().size)
    }
    @Test fun aNameThatIsNotARouteOfThisBuildIsNotARoute() {
        // Screens earlier builds had, a prototype's names, and things that were never routes.
        listOf("", "album", "gallery", "podcast_show", "remote_library", "accessibility", "youtubeLive", "Account", "settings ", "collection",
            "account\u001fextra", "https://example.invalid/", "../settings").forEach { assertNull(it, Route.decode(it)) }
    }
    @Test fun aCollectionNeedsABrowseIdAndItsTitleIsOnlyText() {
        assertNull(Route.collection(null, "T")); assertNull(Route.collection("", "T")); assertNull(Route.collection("a", "T"))
        listOf("has space", "semi;colon", "slash/id", "https://x", "x".repeat(201), "id\u001fmore").forEach { assertNull(it, Route.collection(it, "T")) }
        assertEquals("Collection", Route.collection(album, "  ")!!.title); assertEquals("Collection", Route.collection(album, null)!!.title)
        assertEquals(Route.MAX_TITLE, Route.collection(album, "t".repeat(500))!!.title.length)
        assertEquals("Control characters cannot break the saved form", "ab", Route.collection(album, "a\u001f\u001e\nb")!!.title)
        assertTrue(Route.collection(artist, "A")!!.isArtist); assertFalse(Route.collection(album, "A")!!.isArtist)
        listOf("collection\u001f$album", "collection\u001fbad id\u001fT", "collection\u001f$album\u001fT\u001fmore").forEach { assertNull(Route.decode(it)) }
    }

    @Test fun anEncoreHistoryNeedsAKindEncoreKeepsAndAStatsKey() {
        val song = Route.encoreDetail(EncoreKind.SONG, "yt:fixture0001", "Song")!!
        val byName = Route.encoreDetail(EncoreKind.ALBUM, "name:album\u0000artist", "Album")!!
        listOf(song, byName, Route.encoreDetail(EncoreKind.ARTIST, "id:$artist", "Artist")!!).forEach { assertEquals(it, Route.decode(Route.encode(it))) }
        assertNull("genres have no history page", Route.encoreDetail("genre", "rock", "Rock"))
        assertNull(Route.encoreDetail(EncoreKind.SONG, "", "T")); assertNull(Route.encoreDetail(EncoreKind.SONG, "yt:a\u001fb", "T"))
        assertNull(Route.encoreDetail(EncoreKind.SONG, "yt:a\nb", "T")); assertNull(Route.encoreDetail(EncoreKind.SONG, "x".repeat(401), "T"))
        assertNull(Route.decode("encore_detail\u001fsong\u001fyt:a"))
    }

    // ---- the model

    @Test fun screensOpenAboveTheSelectedRootAndBackClosesThemInOrder() {
        val model = ShellModel()
        assertNull(model.current); assertEquals("", model.route)
        model.push(Route.Settings()); model.push(Route.Settings(SettingsSection.PrivacyData)); model.push(Route.Settings(SettingsSection.EarlierData))
        assertEquals(Route.Settings(SettingsSection.EarlierData), model.current); assertEquals("earlier_data", model.route)
        assertEquals(listOf("settings", "privacy_data", "earlier_data"), model.stacks["Home"])
        model.back(); assertEquals("privacy_data", model.route); model.back(); model.back(); assertNull(model.current)
        model.select("Library"); model.back(); assertEquals("Back at a root other than Home goes Home", "Home", model.tab)
        model.back(); assertEquals("Home", model.tab)
    }
    /** Q18-012: the search on screen is dropped when the account changes, and kept while it does not. */
    @Test fun theSearchOnScreenIsDroppedWhenTheOwnerChanges() {
        val model = ShellModel()
        model.searchOwnerIs("owner-a")
        model.searchQuery = "aaa"; model.searchFilter = "Songs"; model.searchSort = "Title"
        model.searchSnapshot = SearchSnapshot("aaa", "Songs", 1, emptyList(), null, PageTokens())
        model.searchOwnerIs("owner-a")
        assertEquals("aaa", model.searchQuery); assertNotNull(model.searchSnapshot)
        model.searchOwnerIs("owner-b")
        assertEquals("", model.searchQuery); assertEquals("All", model.searchFilter); assertEquals("Relevance", model.searchSort)
        assertNull(model.searchSnapshot)
    }
    @Test fun backClosesThePlayerBeforeItClosesAScreen() {
        val model = ShellModel(); model.push(Route.Account)
        model.sheet = "lyrics"; model.back(); assertEquals("mini", model.sheet)
        model.back(); assertEquals("", model.sheet); assertEquals(Route.Account, model.current)
        model.back(); assertNull(model.current)
    }
    @Test fun eachRootKeepsItsOwnCollection() {
        val model = ShellModel()
        assertTrue(model.openCollection(album, "Album")); assertEquals(album, model.selectedPlaylistId)
        model.select("Search"); assertTrue(model.openCollection(artist, "Artist"))
        assertEquals(artist, model.selectedPlaylistId); assertEquals("Artist", model.selectedCollectionTitle)
        // Before Q11 one selected collection was shared by every root, so Home came back showing the artist.
        model.select("Home"); assertEquals(album, model.selectedPlaylistId); assertEquals("Album", model.selectedCollectionTitle)
    }
    @Test fun theCollectionJustLeftIsStillNamedWhileItsScreenAnimatesAway() {
        val model = ShellModel(); model.openCollection(album, "Album"); model.back()
        assertNull(model.current); assertEquals(album, model.selectedPlaylistId)
        assertEquals("", ShellModel().selectedPlaylistId); assertEquals("Collection", ShellModel().selectedCollectionTitle)
    }
    @Test fun somethingThatIsNotABrowseIdOpensNothing() {
        val model = ShellModel()
        assertFalse(model.openCollection("not an id", "T")); assertFalse(model.openCollection("", "T")); assertNull(model.current)
    }
    @Test fun aStackNeverGrowsPastItsBound() {
        val model = ShellModel()
        repeat(MAX_STACK_DEPTH + 9) { model.openCollection("MPREb_fixture%05d".format(it), "Album $it") }
        assertEquals(MAX_STACK_DEPTH, model.stacks["Home"]!!.size)
        assertEquals("The newest is on top", "MPREb_fixture%05d".format(MAX_STACK_DEPTH + 8), model.selectedPlaylistId)
        repeat(MAX_STACK_DEPTH) { model.back() }; assertNull(model.current)
    }
    @Test fun aNameThatIsNotARootSelectsNothing() {
        val model = ShellModel(); model.select("Library"); model.tab = "Samples"; model.select(""); model.resetRoot("Nowhere")
        assertEquals("Library", model.tab)
    }

    // ---- what is saved

    private fun roundTrip(model: ShellModel) = ShellModel.Saver.restore(with(ShellModel.Saver) { object : androidx.compose.runtime.saveable.SaverScope { override fun canBeSaved(value: Any) = true }.save(model) }!!)!!
    @Test fun theShellComesBackWhereItWas() {
        val model = ShellModel()
        model.push(Route.Settings()); model.push(Route.Settings(SettingsSection.PrivacyData))
        model.select("Search"); model.searchQuery = "fixture = query"; model.searchFilter = "Albums"; model.searchSort = "Title"
        model.openCollection(artist, "Fixture Artist"); model.openCollection(album, "Fixture Album")
        model.select("Library"); model.libraryFilter = "Playlists"; model.push(Route.Downloads)
        model.miniVisible = true; model.sheet = "queue"; model.homeMood = "Relax"
        val back = roundTrip(model)
        assertEquals("Library", back.tab); assertEquals(Route.Downloads, back.current)
        assertEquals(model.stacks, back.stacks)
        assertEquals("fixture = query", back.searchQuery); assertEquals("Albums", back.searchFilter); assertEquals("Title", back.searchSort); assertEquals("Playlists", back.libraryFilter)
        back.select("Search"); assertEquals(album, back.selectedPlaylistId); assertEquals("Fixture Album", back.selectedCollectionTitle)
        back.back(); assertEquals(artist, back.selectedPlaylistId); assertEquals("Fixture Artist", back.selectedCollectionTitle)
        // The player is never restored open, and nothing about what was playing is kept.
        assertFalse(back.miniVisible); assertEquals("", back.sheet); assertEquals("", back.homeMood)
    }
    @Test fun theSavedFormIsNamedVersionedAndHoldsOnlyNavigation() {
        val model = ShellModel(); model.openCollection(album, "Fixture Album"); model.searchQuery = "q"
        val saved = model.savedState().toSaved()
        assertEquals(ShellSavedState.MARKER, saved.first()); assertEquals("riff-shell/2", saved.first())
        val names = saved.drop(1).map { it.substringBefore('=') }.toSet()
        assertEquals(setOf("tab", "query", "libraryFilter", "searchSort", "searchFilter", "stack.Home", "lastCollection"), names)
        val text = saved.joinToString("\n").lowercase()
        listOf("cookie", "sapisid", "token", "googlevideo", "http", "sheet", "mini", "position").forEach { assertFalse(it, text.contains(it)) }
    }
    @Test fun aSavedStateFromANewerBuildOrOneThatIsDamagedGivesTheStartScreen() {
        fun restore(vararg values: String?) = ShellModel.Saver.restore(values.toList().map { it.orEmpty() })!!
        listOf(restore("riff-shell/3", "tab=Library", "stack.Library=account"), restore("riff-shell/"), restore("riff-shell/2"), restore()).forEach {
            assertEquals("Home", it.tab); assertNull(it.current); assertEquals("All", it.libraryFilter)
        }
        val damaged = restore(ShellSavedState.MARKER, "tab=Nowhere", "no equals sign", "=", "query=" + "q".repeat(500), "libraryFilter=Everything", "searchSort=Loudest",
            "searchFilter=Videos", "stack.Home=account\u001ealbum\u001ecollection\u001fbad id\u001fT\u001esettings\u001ecollection\u001f$album\u001fKept",
            "stack.Nowhere=account", "stack.Radio=" + List(40) { "downloads" }.joinToString("\u001e"), "lastCollection=account", "cookie=fixture")
        assertEquals("Home", damaged.tab); assertEquals(ShellSavedState.MAX_QUERY, damaged.searchQuery.length)
        assertEquals("All", damaged.libraryFilter); assertEquals("Relevance", damaged.searchSort); assertEquals("All", damaged.searchFilter)
        assertEquals("Routes that do not validate are dropped; the rest keep their order", listOf("account", "settings", "collection"), damaged.stacks["Home"])
        assertEquals(album, damaged.selectedPlaylistId); assertEquals("Kept", damaged.selectedCollectionTitle)
        assertEquals(MAX_STACK_DEPTH, damaged.stacks["Radio"]!!.size)
        assertEquals(setOf("Home", "Podcasts", "Radio", "Search", "Library"), damaged.stacks.keys)
        // Null and wrongly typed elements cannot make it throw either.
        assertEquals(RootTab.Home, ShellSavedState.fromSaved(listOf(null, null)).tab)
        assertEquals(RootTab.Search, ShellSavedState.fromSaved(listOf(ShellSavedState.MARKER, null, "tab=Search", null)).tab)
    }

    // ---- the positional form of builds up to 0.10.0-rc1

    private fun positional(tab: String, selectedId: String, selectedTitle: String, stacks: Map<String, String>, trail: List<Triple<String, String, String>>) =
        listOf(tab, "true", "saved query", "Albums", "", "", selectedId, "YouTube Music", "Songs", "Title",
            *listOf("Home", "Podcasts", "Radio", "Search", "Library").map { stacks[it].orEmpty() }.toTypedArray(), selectedTitle, "rc7", "Artists",
            trail.joinToString("\u001e") { listOf(it.first, it.second, it.third).joinToString("\u001f") })
    @Test fun anOlderSavedStateIsReadWithItsCollectionsInTheirPlaces() {
        // Search: artist, then the album opened from it. The album was the selected collection; the artist was on the trail.
        val model = ShellModel.Saver.restore(positional("Search", album, "Fixture Album",
            mapOf("Home" to "settings,privacy_data", "Search" to "collection,collection", "Library" to "downloads,remote_library,accessibility,device"),
            listOf(Triple("Search", artist, "Fixture Artist"))))!!
        assertEquals("Search", model.tab); assertEquals("saved query", model.searchQuery)
        assertEquals("Albums", model.libraryFilter); assertEquals("Title", model.searchSort); assertEquals("Artists", model.searchFilter)
        assertEquals(listOf("collection", "collection"), model.stacks["Search"])
        assertEquals(album, model.selectedPlaylistId); assertEquals("Fixture Album", model.selectedCollectionTitle)
        model.back(); assertEquals(artist, model.selectedPlaylistId); assertEquals("Fixture Artist", model.selectedCollectionTitle)
        assertEquals("Screens this build still has, which the older reader dropped, are kept", listOf("settings", "privacy_data"), model.stacks["Home"])
        assertEquals("Screens that no longer exist are dropped", listOf("downloads", "device"), model.stacks["Library"])
        assertFalse(model.miniVisible)
    }
    @Test fun anOlderCollectionWithNoUsableIdIsDroppedRatherThanOpenedEmpty() {
        val model = ShellModel.Saver.restore(positional("Home", "not an id", "T", mapOf("Home" to "account,collection"), emptyList()))!!
        assertEquals(listOf("account"), model.stacks["Home"]); assertEquals(Route.Account, model.current); assertEquals("", model.selectedPlaylistId)
        // A collection in a root that is not the one the selected collection belongs to, with no trail entry: dropped.
        val two = ShellModel.Saver.restore(positional("Home", album, "A", mapOf("Home" to "collection", "Library" to "collection"), emptyList()))!!
        assertEquals(listOf("collection"), two.stacks["Home"]); assertEquals(emptyList<String>(), two.stacks["Library"]); assertEquals(album, two.selectedPlaylistId)
    }
}
