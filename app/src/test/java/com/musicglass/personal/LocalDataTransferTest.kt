package com.musicglass.personal

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Export and import of local data (Q05A); synthetic owners, songs and files only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalDataTransferTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val store = OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    private val accountA = "s2_" + "a".repeat(64)
    private val accountB = "s2_" + "b".repeat(64)
    private val opened = mutableListOf<LibraryStateDb>()
    private var hook: (String) -> Unit = {}
    private val preferenceFiles = listOf("music_collections", "search_history", "not_interested", "accessibility_display", AUDIO_BOOST_PREFS,
        CROSSFADE_PREFS, DATA_SAVER_PREFS, "offline_settings", "account_scopes", "speed_dial_pins")
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    @After fun close() { opened.forEach { it.close() } }

    /** Riff's stores as one install has them. A new one after [wipe] stands for a clean install. */
    private inner class Phone(var current: CurrentOwner = CurrentOwner(accountA, setOf(accountA)), maxBytes: Long = LOCAL_DATA_MAX_BYTES, historyCap: Int = HISTORY_ROW_CAP) {
        val events = mutableListOf<String>()
        val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db").also { opened += it }
        val epochs = ScopeEpochs(db).also { it.warm() }
        val history = HistoryRepository(db, epochs, clock::wallTimeMillis, historyCap) { historyRetentionDays(context) }
        val searches = SearchHistory(context, Dispatchers.Unconfined)
        val hidden = NotInterestedStore(context)
        val pins = SpeedDialPins(context)
        val transfer = LocalDataTransfer(context, db, history, { store }, { searches }, { hidden }, { current }, Dispatchers.Unconfined,
            clock::wallTimeMillis, Mutex(), events::add, { hook(it) }, maxBytes, historyCap) { pins }
    }
    private fun wipe() {
        preferenceFiles.forEach { prefs(it).edit().clear().commit() }
        store.scopes().forEach { store.deleteScope(it) }
    }

    private var serial = 0
    private fun id(n: Int) = "fixture%04d".format(n)
    private fun track(n: Int) = ProviderTrack(id(n), "Song $n", "Artist $n")
    private fun Phone.play(owner: String, n: Int = 1, at: Long = clock.wall - serial * 1000L, event: String = "e-${serial++}") {
        val local = owner == DEVICE_LOCAL_SCOPE_KEY
        assertEquals(StoreOutcome.Done, history.record(HistoryEntry(if (local) "local:$n" else "yt:${id(n)}", "Song $n", "Artist $n",
            if (local) null else providerArtworkUrl(id(n)), at, eventId = event, durationMs = 200_000), history.ticket(owner)))
    }
    private fun saved(owner: String, list: String, vararg numbers: Int) = prefs("music_collections").edit().putString("account_${owner}_$list",
        JSONArray(numbers.map { JSONObject().put("id", id(it)).put("title", "Song $it").put("artist", "Artist $it").put("artwork", providerArtworkUrl(id(it))) }).toString()).commit()
    private fun Phone.search(owner: String, vararg queries: String) = runBlocking { searches.bind(owner); queries.forEach(searches::record) }
    private fun download(owner: String, n: Int) {
        store.enqueue(track(n), scope = owner)
        val dir = store.directory(owner, id(n)).apply { mkdirs() }
        File(dir, "audio.media").writeBytes(byteArrayOf(1, 2, 3)); File(dir, "ready").writeText("3")
        store.update(owner, id(n), "state" to "complete", "bytes" to 3L)
    }
    private fun Phone.seed(owner: String) {
        repeat(4) { play(owner, it + 1) }
        if (owner == DEVICE_LOCAL_SCOPE_KEY) return
        search(owner, "first search", "second search")
        if (owner == GUEST_SCOPE_KEY) return
        saved(owner, "online_favorites", 11, 12); saved(owner, "online_playlist", 13); saved(owner, "online_history", 14, 15)
        download(owner, 21); download(owner, 22)
    }
    private fun seedDevice() {
        prefs("accessibility_display").edit().putBoolean("reduce_motion", true).putFloat("glass_intensity", 0.5f).commit()
        prefs(CROSSFADE_PREFS).edit().putBoolean("enabled", true).putInt("seconds", 9).commit()
        prefs(AUDIO_BOOST_PREFS).edit().putInt("percent", 150).putBoolean(NORMALIZE_KEY, true).commit()
        prefs("music_collections").edit().putBoolean("history_enabled", true).commit()
        prefs(DATA_SAVER_PREFS).edit().putString(SONG_QUALITY_KEY, SongQuality.High.key).commit()
        // Never exported: they could start transfers or use mobile data on the other phone.
        prefs("offline_settings").edit().putBoolean("smart_enabled", true).putBoolean("allow_mobile_data", true).putLong("budget_bytes", 1073741824L).commit()
    }

    private data class Kept(val plays: List<HistoryEntry>, val favorites: List<String>, val playlist: List<String>, val recents: List<String>,
        val searches: List<String>, val downloads: Set<String>)
    private fun Phone.plays(owner: String): List<HistoryEntry> {
        val all = mutableListOf<HistoryEntry>(); var cursor: HistoryCursor? = null
        do { val page = history.page(owner, cursor); all += page.entries; cursor = page.next } while (cursor != null)
        return all
    }
    private fun Phone.kept(owner: String) = Kept(plays(owner), storedCollection(context, owner, "online_favorites").map { it.videoId },
        storedCollection(context, owner, "online_playlist").map { it.videoId }, storedCollection(context, owner, "online_history").map { it.videoId },
        runBlocking { searches.stored(owner) }, store.rows(owner).mapTo(mutableSetOf()) { it.track.videoId })
    private fun settings() = preferenceFiles.filter { it != "search_history" && it != "account_scopes" }.associateWith { name ->
        prefs(name).all.filterKeys { !it.startsWith("account_") }.toSortedMap().toString() }

    private fun Phone.exported(vararg owners: String): ByteArray {
        val out = ByteArrayOutputStream()
        val outcome = runBlocking { transfer.export(owners.toSet(), { out }) }
        assertTrue(outcome.toString(), outcome is ExportOutcome.Done)
        return out.toByteArray()
    }
    private fun Phone.open(bytes: ByteArray) = runBlocking { transfer.open({ bytes.inputStream() }) }
    private fun Phone.plan(bytes: ByteArray) = (open(bytes) as? ImportOpen.Ready)?.plan ?: throw AssertionError("not ready: ${open(bytes)}")
    private fun Phone.import(bytes: ByteArray, adopt: Set<Int> = emptySet(), cancelled: () -> Boolean = { false }) =
        runBlocking { transfer.import(plan(bytes), adopt, cancelled) }
    private fun Phone.report(bytes: ByteArray, adopt: Set<Int> = emptySet()) =
        (import(bytes, adopt) as? ImportOutcome.Finished)?.report ?: throw AssertionError("not finished")
    private fun file(vararg lines: String, end: Boolean = true) =
        (listOf("""{"riff":"local-data","schema":1,"exported_at":1,"app":"t"}""") + lines + (if (end) listOf("""{"t":"end","records":0}""") else emptyList()))
            .joinToString("\n", postfix = "\n").toByteArray()
    private fun providerOwner(digest: String = accountA) = """{"t":"owner","kind":"provider","id":"$digest"}"""
    private fun playLine(event: String, n: Int = 1, at: Long = clock.wall - 5_000, local: Boolean = false) =
        """{"t":"play","e":"$event","id":"${if (local) "local:$n" else "yt:" + id(n)}","title":"Song $n","artist":"Artist $n","at":$at}"""

    // ---- EXP01: export, then import on a clean install

    @Test fun aCleanInstallMatchesTheSourceForEverythingIncluded() {
        val source = Phone()
        seedDevice(); source.seed(DEVICE_LOCAL_SCOPE_KEY); source.seed(GUEST_SCOPE_KEY); source.seed(accountA)
        source.hidden.add(NotInterestedEntry(id(31), "Hidden song", "Artist", providerArtworkUrl(id(31)), true, clock.wall - 10))
        source.pins.pin(ProviderHomeCard("Pinned episode", "A show", providerArtworkUrl(id(41)), videoId = id(41), kind = CatalogKind.Episode))
        source.pins.pin(ProviderHomeCard("Pinned podcast", "Podcast", null, browseId = "MPSPPLtest_41", kind = CatalogKind.Podcast))
        val pinsBefore = source.pins.cards
        val owners = listOf(DEVICE_LOCAL_SCOPE_KEY, GUEST_SCOPE_KEY, accountA)
        val before = owners.associateWith { source.kept(it) }
        val hiddenBefore = source.hidden.entries; val settingsBefore = settings()
        val bytes = source.exported(*owners.toTypedArray())

        wipe()
        val fresh = Phone()
        assertEquals(Kept(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptySet()), fresh.kept(accountA))
        val plan = fresh.plan(bytes)
        assertEquals(listOf(Destination.Direct, Destination.Direct, Destination.Account), plan.sections.map { it.destination })
        assertEquals(SectionCounts(settings = 8, hidden = 1, plays = 4, pins = 2), plan.sections[0].counts)
        assertEquals(SectionCounts(searches = 2, plays = 4), plan.sections[1].counts)
        assertEquals(SectionCounts(saved = 3, recents = 2, searches = 2, plays = 4, downloads = 2), plan.sections[2].counts)
        assertEquals(0, plan.malformed)
        val report = runBlocking { (fresh.transfer.import(plan) as ImportOutcome.Finished).report }
        assertEquals(ImportReport(added = SectionCounts(settings = 8, hidden = 1, saved = 3, recents = 2, searches = 4, plays = 12, downloads = 2, pins = 2)), report)
        assertEquals(pinsBefore, fresh.pins.cards)
        owners.forEach { assertEquals(it, before[it], fresh.kept(it)) }
        assertEquals(hiddenBefore, fresh.hidden.entries)
        // The download settings were not carried over; everything else was.
        assertEquals(settingsBefore - "offline_settings", settings() - "offline_settings")
        assertTrue(prefs("offline_settings").all.isEmpty())
        assertFalse("the private copy is removed", plan.file.exists())
    }

    // Feature 12: Encore's numbers are written for reading and passed over on import, which rebuilds them from the plays.
    @Test fun encoresNumbersTravelForReadingAndImportPassesThemOver() {
        val source = Phone(); source.seed(accountA); source.play(accountA, 1)
        val bytes = source.exported(accountA)
        val lines = bytes.toString(Charsets.UTF_8).trim().lines().drop(1).map(::JSONObject)
        val years = lines.filter { it.getString("t") == "encore" }
        assertEquals(1, years.size)
        assertEquals(5, years.single().getInt("plays"))
        val songs = lines.filter { it.getString("t") == "encore_top" && it.getString("kind") == EncoreKind.SONG }
        assertEquals(listOf(1, 2, 3, 4), songs.map { it.getInt("rank") })
        assertEquals("the song played twice leads", "Song 1", songs.first().getString("title"))
        assertTrue(lines.filter { it.getString("t") == "encore_top" }.map { it.getString("kind") }.toSet() == setOf(EncoreKind.SONG, EncoreKind.ARTIST))

        wipe()
        val plan = Phone().plan(bytes)
        assertEquals(0, plan.malformed)
        assertEquals(SectionCounts(saved = 3, recents = 2, searches = 2, plays = 5, downloads = 2), plan.sections.single().counts)
    }

    @Test fun importedDownloadsAreNamesOnlyAndNothingIsQueued() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val fresh = Phone(); download(accountA, 22)
        fresh.report(bytes)
        val rows = store.rows(accountA).associateBy { it.track.videoId }
        assertEquals("metadata", rows.getValue(id(21)).state); assertFalse(rows.getValue(id(21)).pinned); assertEquals(0L, rows.getValue(id(21)).bytes)
        assertNull("no audio appears", store.audio(accountA, id(21)))
        assertEquals("a song already downloaded here is left as it is", "complete", rows.getValue(id(22)).state)
        assertEquals(1, store.downloadCount(accountA))
        assertNull("no queue is created", PlaybackSnapshotRepository(fresh.db, fresh.epochs, clock::wallTimeMillis, File(context.filesDir, "r.json")).load(accountA))
    }

    @Test fun theFileHoldsNoCredentialsStreamsOrStorageKeys() {
        val phone = Phone(); seedDevice()
        val legacyKey = "0123456789abcdef0123456789abcdef"
        prefs("account_scopes").edit().putString("primary_$accountB", legacyKey).commit()
        prefs("provider_session").edit().putString("sync", "fixture-sync-secret").putString("cookie", "SAPISID=fixture-cookie-secret").commit()
        phone.seed(DEVICE_LOCAL_SCOPE_KEY); phone.seed(GUEST_SCOPE_KEY); phone.seed(accountA); phone.seed(legacyKey)
        // Artwork that could carry a signature, an address that is not an image host, and an image of the phone's own library.
        prefs("music_collections").edit().putString("account_${accountA}_online_favorites", JSONArray(listOf(
            JSONObject().put("id", id(41)).put("title", "Signed").put("artist", "A").put("artwork", "https://i.ytimg.com/vi/x/hq.jpg?sqp=abc&rs=SIGNATUREVALUE"),
            JSONObject().put("id", id(42)).put("title", "Stream").put("artist", "A").put("artwork", "https://rr1.googlevideo.com/videoplayback?expire=1&sig=abc"))).toString()).commit()
        assertEquals(StoreOutcome.Done, phone.history.record(HistoryEntry("local:9", "Local", "Me", "content://media/external/audio/albumart/9", clock.wall, eventId = "local-art"), phone.history.ticket(DEVICE_LOCAL_SCOPE_KEY)))
        val text = phone.exported(DEVICE_LOCAL_SCOPE_KEY, GUEST_SCOPE_KEY, accountA, legacyKey).toString(Charsets.UTF_8)
        listOf("fixture-sync-secret", "fixture-cookie-secret", "SAPISID", "cookie", "token", "googlevideo", "videoplayback", "expire=", "sqp=", "SIGNATUREVALUE",
            "content:", "file:", legacyKey, "smart_enabled", "allow_mobile_data", "budget_bytes", "Authorization", "password").forEach {
            assertFalse("the file must not contain $it", text.contains(it, ignoreCase = true))
        }
        val allowed = Regex("""https:\\/\\/[a-z0-9.-]*(ytimg|ggpht|googleusercontent)\.com\\/[^"?#]*""")
        Regex("""[a-z][a-z0-9+.-]*:\\?/\\?/[^"]*""").findAll(text).forEach { assertTrue("unexpected address ${it.value}", allowed.matches(it.value)) }
        val lines = text.trim().lines()
        assertTrue(lines.all { it.length <= LOCAL_DATA_MAX_LINE })
        assertEquals(setOf("owner", "setting", "track", "search", "play", "download", "encore", "encore_top", "end"), lines.drop(1).mapTo(mutableSetOf()) { JSONObject(it).getString("t") })
        // An account is named by the digest of its identity, also when its data is stored under an older key.
        assertEquals(listOf(null, null, accountA, accountB), lines.filter { it.contains("\"owner\"") }.map { JSONObject(it).opt("id") })
        assertEquals(JSONObject().put("riff", "local-data").put("schema", 1).put("exported_at", clock.wall).put("app", BuildConfig.VERSION_NAME).toString(), lines.first())
    }

    @Test fun onlyTheChosenOwnersAreWritten() {
        val phone = Phone(); phone.seed(GUEST_SCOPE_KEY); phone.seed(accountA); phone.seed(accountB)
        val inventory = runBlocking { phone.transfer.inventory() }
        assertEquals(listOf(DEVICE_LOCAL_SCOPE_KEY, GUEST_SCOPE_KEY, accountA, accountB), inventory.map { it.key })
        assertEquals(SectionCounts(saved = 3, recents = 2, searches = 2, plays = 4, downloads = 2), inventory[2].counts)
        val plan = phone.plan(phone.exported(accountB))
        assertEquals(listOf(OwnerKind.Provider to Destination.Other), plan.sections.map { it.kind to it.destination })
    }

    // ---- ownership

    @Test fun anotherAccountsSectionIsNeverAdoptedByItself() {
        val source = Phone(); source.seed(GUEST_SCOPE_KEY); source.seed(accountA)
        val bytes = source.exported(GUEST_SCOPE_KEY, accountA)
        wipe()
        val phone = Phone(CurrentOwner(accountB, setOf(accountB)))
        val plan = phone.plan(bytes)
        assertEquals(listOf(Destination.Direct, Destination.Other), plan.sections.map { it.destination }); assertTrue(plan.canAdopt)
        val report = runBlocking { (phone.transfer.import(plan) as ImportOutcome.Finished).report }
        assertEquals(1, report.sectionsLeft); assertEquals(SectionCounts(searches = 2, plays = 4), report.added)
        listOf(accountA, accountB).forEach { assertEquals(Kept(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptySet()), phone.kept(it)) }
        assertEquals(4, phone.history.count(GUEST_SCOPE_KEY))
    }

    @Test fun onTheUsersWordAnotherAccountsSectionIsAddedToTheAccountSignedInNow() {
        val source = Phone(); source.seed(accountA)
        val expected = source.kept(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val phone = Phone(CurrentOwner(accountB, setOf(accountB)))
        phone.play(accountB, 7, event = "own-play"); saved(accountB, "online_favorites", 51)
        val report = phone.report(bytes, adopt = setOf(0))
        assertEquals(0, report.sectionsLeft)
        val now = phone.kept(accountB)
        assertEquals("the account's own tracks stay first", listOf(id(51)) + expected.favorites, now.favorites)
        assertEquals(expected.plays.map { it.eventId }.toSet() + "own-play", now.plays.map { it.eventId }.toSet())
        assertEquals(expected.searches, now.searches); assertEquals(expected.downloads, now.downloads)
        assertEquals(0, phone.history.count(accountA))
    }

    @Test fun signedOutOrUnverifiedAnAccountsSectionCannotBeImported() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        for (current in listOf(CurrentOwner(GUEST_SCOPE_KEY), CurrentOwner(UNVERIFIED_SCOPE_KEY), CurrentOwner(UNVERIFIED_SCOPE_KEY, setOf(accountA)))) {
            val phone = Phone(current)
            val plan = phone.plan(bytes)
            assertEquals(Destination.Other, plan.sections.single().destination); assertFalse(plan.canAdopt)
            // Even if asked to, there is no verified account to add it to.
            val report = runBlocking { (phone.transfer.import(plan, adopt = setOf(0)) as ImportOutcome.Finished).report }
            assertEquals(ImportReport(sectionsLeft = 1), report)
            listOf(accountA, GUEST_SCOPE_KEY, UNVERIFIED_SCOPE_KEY).forEach { assertEquals(0, phone.history.count(it)); assertTrue(store.rows(it).isEmpty()) }
        }
    }

    @Test fun theSameAccountIsRecognisedUnderAnOlderStorageKey() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val legacyKey = "fedcba9876543210fedcba9876543210"
        val phone = Phone(CurrentOwner(legacyKey, setOf(accountA, legacyKey)))
        assertEquals(Destination.Account, phone.plan(bytes).sections.single().destination)
        phone.report(bytes)
        assertEquals(4, phone.history.count(legacyKey)); assertEquals(0, phone.history.count(accountA))
    }

    @Test fun theAccountIsDecidedAgainWhenTheImportRuns() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val phone = Phone()
        val plan = phone.plan(bytes)
        assertEquals(Destination.Account, plan.sections.single().destination)
        phone.current = CurrentOwner(accountB, setOf(accountB)) // the account changed while the preview was open
        val report = runBlocking { (phone.transfer.import(plan) as ImportOutcome.Finished).report }
        assertEquals(ImportReport(sectionsLeft = 1), report)
        assertEquals(0, phone.history.count(accountB)); assertEquals(0, phone.history.count(accountA))
    }

    // ---- EXP02: files that must be refused or read with care

    @Test fun filesThatAreNotRiffsNewerOrTooLargeAreRefusedWhole() {
        val phone = Phone(maxBytes = 4_096); phone.seed(accountA)
        val before = phone.kept(accountA)
        fun refused(bytes: ByteArray) = (phone.open(bytes) as ImportOpen.Rejected).reason
        assertEquals(RejectReason.NotRiff, refused(ByteArray(0)))
        assertEquals(RejectReason.NotRiff, refused("just some text\nand more\n".toByteArray()))
        assertEquals(RejectReason.NotRiff, refused("""{"t":"owner","kind":"guest"}""".toByteArray()))
        assertEquals(RejectReason.NotRiff, refused(ByteArray(600) { (it * 31).toByte() }))
        assertEquals(RejectReason.NotRiff, refused("""{"riff":"local-data","schema":0}""".toByteArray()))
        assertEquals(RejectReason.Newer, refused(("""{"riff":"local-data","schema":2,"exported_at":1}""" + "\n" + providerOwner() + "\n" + playLine("n1") + "\n" + """{"t":"end"}""").toByteArray()))
        assertEquals(RejectReason.TooLarge, refused(file(*Array(200) { playLine("big-$it") })))
        val endless = object : InputStream() { override fun read() = 'x'.code }
        assertEquals(RejectReason.TooLarge, (runBlocking { phone.transfer.open({ endless }) } as ImportOpen.Rejected).reason)
        assertEquals(RejectReason.Unreadable, (runBlocking { phone.transfer.open({ throw SecurityException("fixture") }) } as ImportOpen.Rejected).reason)
        val failing = object : InputStream() { override fun read(): Int = throw IOException("fixture") }
        assertEquals(RejectReason.Unreadable, (runBlocking { phone.transfer.open({ failing }) } as ImportOpen.Rejected).reason)
        assertEquals(before, phone.kept(accountA))
        assertTrue(File(context.cacheDir, "local-data-transfer").listFiles().isNullOrEmpty())
        assertTrue(phone.events.toString(), phone.events.all { it.matches(Regex("local_data action=import_open result=rejected reason=[a-z]+")) })
    }

    @Test fun aFileCutShortAnywhereIsRefused() {
        val source = Phone(); source.seed(GUEST_SCOPE_KEY); source.seed(accountA)
        val bytes = source.exported(GUEST_SCOPE_KEY, accountA)
        wipe()
        val phone = Phone()
        val lastLine = bytes.toString(Charsets.UTF_8).trimEnd().lastIndexOf('\n') + 1
        for (cut in (1 until lastLine step 97) + listOf(lastLine - 1, lastLine, lastLine + 3)) {
            val reason = (phone.open(bytes.copyOf(cut)) as? ImportOpen.Rejected)?.reason
            assertTrue("cut at $cut gave $reason", reason == RejectReason.Truncated || reason == RejectReason.NotRiff)
        }
        assertEquals(0, phone.history.count(accountA)); assertEquals(0, phone.history.count(GUEST_SCOPE_KEY))
        assertTrue(phone.open(bytes) is ImportOpen.Ready)
        // Some editors and file managers put a byte-order mark in front; the file is still this file.
        assertTrue(phone.open("\uFEFF".toByteArray() + bytes) is ImportOpen.Ready)
    }

    @Test fun songQualityTravelsOnlyAsOneOfItsThreeChoices() { // Q18-099
        val phone = Phone()
        val key = "$DATA_SAVER_PREFS.$SONG_QUALITY_KEY"
        val plan = phone.plan(file("""{"t":"owner","kind":"device"}""", """{"t":"setting","k":"$key","v":"ultra"}""",
            """{"t":"setting","k":"$key","v":3}""", """{"t":"setting","k":"$key","v":"low"}"""))
        assertEquals(2, plan.malformed)
        runBlocking { phone.transfer.import(plan) }
        assertEquals(SongQuality.Low.key, prefs(DATA_SAVER_PREFS).getString(SONG_QUALITY_KEY, null))
    }

    @Test fun recordsThatCannotBeReadAreSkippedOneByOne() {
        val phone = Phone()
        val bytes = file(
            playLine("before-any-owner"),                                                            // 1: no owner yet
            """{"t":"owner","kind":"device"}""",
            playLine("d1", local = true),
            playLine("d2"),                                                                          // 2: a provider song in the device's history
            """{"t":"setting","k":"crossfade.seconds","v":7}""",
            """{"t":"setting","k":"crossfade.seconds","v":99}""",                                    // 3: out of range
            """{"t":"setting","k":"offline_settings.smart_enabled","v":true}""",                     // 4: not a setting that travels
            """{"t":"setting","k":"audio_boost.enabled","v":"yes"}""",                               // 5: wrong type
            """{"t":"hidden","key":"${id(5)}","title":"Hidden","song":true,"at":5}""",
            """{"t":"hidden","key":"bad key!"}""",                                                   // 6
            providerOwner(),
            playLine("p1"),
            "{ this is not json",                                                                    // 7
            "x".repeat(LOCAL_DATA_MAX_LINE + 50),                                                    // 8: too long
            """{"t":"play","e":"deep","id":"yt:${id(1)}","at":5,"x":${"[".repeat(3000)}${"]".repeat(3000)}}""", // 9: nested
            """{"t":"play","e":"p2","id":"yt:short","title":"x","artist":"y","at":5}""",             // 10: not a song identifier
            """{"t":"play","e":"bad event!","id":"yt:${id(1)}","at":5}""",                           // 11
            playLine("p3", at = 0), playLine("p4", at = clock.wall + 3 * 86_400_000L),               // 12, 13: no time, far in the future
            playLine("p5", local = true),                                                            // 14: a file of some phone in an account's history
            """{"t":"track","list":"favorites","id":"${id(6)}","title":"Fav","artist":"A","artwork":"https://evil.example/a.jpg"}""",
            """{"t":"track","list":"elsewhere","id":"${id(6)}"}""",                                  // 15: unknown list
            """{"t":"search","q":"   "}""",                                                          // 16
            """{"t":"search","q":"  two   words "}""",
            """{"t":"download","id":"${id(7)}","title":"Live","artist":"A","live":true}""",          // 17: live is never downloaded
            """{"t":"mystery","id":1}""",                                                            // 18
            """["not","an","object"]""",                                                             // 19
            """{"t":"owner","kind":"provider","id":"not-a-digest"}""",                               // 20: and what follows has no owner
            playLine("orphan-1"), playLine("orphan-2"),                                              // 21, 22
            """{"t":"owner","kind":"martian"}""",                                                    // 23
            playLine("orphan-3"),                                                                    // 24
            """{"t":"owner","kind":"guest"}""",
            """{"t":"track","list":"favorites","id":"${id(8)}"}""",                                  // 25: signed-out listening saves no tracks
            playLine("g1"))
        val plan = phone.plan(bytes)
        assertEquals(25, plan.malformed)
        assertEquals(listOf(SectionCounts(settings = 1, hidden = 1, plays = 1), SectionCounts(saved = 1, searches = 1, plays = 1), SectionCounts(plays = 1)), plan.sections.map { it.counts })
        val report = runBlocking { (phone.transfer.import(plan) as ImportOutcome.Finished).report }
        assertEquals(ImportReport(added = SectionCounts(settings = 1, hidden = 1, saved = 1, searches = 1, plays = 3), malformed = 25), report)
        assertEquals(listOf("d1"), phone.plays(DEVICE_LOCAL_SCOPE_KEY).map { it.eventId }); assertEquals(listOf("p1"), phone.plays(accountA).map { it.eventId })
        assertEquals(7, prefs(CROSSFADE_PREFS).getInt("seconds", 0)); assertTrue(prefs("offline_settings").all.isEmpty()); assertTrue(prefs(AUDIO_BOOST_PREFS).all.isEmpty())
        assertEquals(listOf("two words"), runBlocking { phone.searches.stored(accountA) })
        // An address that is not one of the provider's image hosts is replaced by the default image.
        assertEquals(providerArtworkUrl(id(6)), JSONArray(prefs("music_collections").getString("account_${accountA}_online_favorites", "[]")).getJSONObject(0).getString("artwork"))
        assertFalse(prefs("music_collections").contains("account_guest_online_favorites"))
    }

    @Test fun importingTheSameFileAgainAddsNothing() {
        val source = Phone(); seedDevice(); source.seed(DEVICE_LOCAL_SCOPE_KEY); source.seed(GUEST_SCOPE_KEY); source.seed(accountA)
        source.hidden.add(NotInterestedEntry(id(31), "Hidden", at = 5))
        val owners = arrayOf(DEVICE_LOCAL_SCOPE_KEY, GUEST_SCOPE_KEY, accountA)
        val bytes = source.exported(*owners)
        wipe()
        val phone = Phone(); phone.report(bytes)
        val once = owners.map { phone.kept(it) }; val settingsOnce = settings()
        val again = phone.report(bytes)
        assertEquals(SectionCounts(settings = 8), again.added) // settings are written again with the same values
        assertEquals(12 + 5 + 4 + 2 + 1, again.present)
        assertEquals(once, owners.map { phone.kept(it) }); assertEquals(settingsOnce, settings())
        assertEquals(1, phone.hidden.entries.size)
    }

    // ---- limits; import never removes

    @Test fun importNeverPushesOutWhatIsAlreadyHere() {
        val phone = Phone(historyCap = 5)
        repeat(3) { phone.play(accountA, 1, at = clock.wall - 86_400_000L * 20, event = "old-$it") }
        saved(accountA, "online_favorites", *IntArray(199) { 100 + it })
        phone.search(accountA, *Array(29) { "own $it" })
        val bytes = file(providerOwner(), *Array(6) { playLine("new-$it", at = clock.wall - it * 1000L) },
            *Array(5) { """{"t":"track","list":"favorites","id":"${id(500 + it)}","title":"T","artist":"A"}""" },
            *Array(4) { """{"t":"search","q":"imported $it"}""" })
        val report = phone.report(bytes)
        assertEquals(SectionCounts(saved = 1, searches = 1, plays = 2), report.added)
        assertEquals(4 + 4, report.overLimit); assertEquals(3, report.present)
        assertEquals(0, phone.history.prune(accountA))
        assertEquals(setOf("old-0", "old-1", "old-2", "new-0", "new-1"), phone.plays(accountA).mapTo(mutableSetOf()) { it.eventId })
        val favorites = storedCollection(context, accountA, "online_favorites").map { it.videoId }
        assertEquals((0 until 199).map { id(100 + it) } + id(500), favorites)
        val searches = runBlocking { phone.searches.stored(accountA) }
        assertEquals(30, searches.size); assertEquals("own 28", searches.first()); assertEquals("imported 0", searches.last())
    }

    @Test fun playsOlderThanTheHistoryPeriodAreNotAddedAndAShorterPeriodIsNotApplied() {
        val phone = Phone()
        setHistoryRetentionDays(context, 90)
        phone.play(accountA, 1, at = clock.wall - 80 * 86_400_000L, event = "kept-80-days")
        val bytes = file("""{"t":"owner","kind":"device"}""", """{"t":"setting","k":"music_collections.history_retention_days","v":30}""",
            providerOwner(), playLine("recent"), playLine("ancient", at = clock.wall - 120 * 86_400_000L))
        val report = phone.report(bytes)
        assertEquals(ImportReport(added = SectionCounts(plays = 1), expired = 1, settingsKept = 1), report)
        assertEquals(90, historyRetentionDays(context))
        assertEquals(0, phone.history.pruneAll(clock.wall)); assertEquals(2, phone.history.count(accountA))
        // A longer period removes nothing, so it is applied. With no period in force, none is ever introduced.
        assertEquals(1, phone.report(file("""{"t":"owner","kind":"device"}""", """{"t":"setting","k":"music_collections.history_retention_days","v":365}""")).added.settings)
        assertEquals(365, historyRetentionDays(context))
        setHistoryRetentionDays(context, null)
        assertEquals(1, phone.report(file("""{"t":"owner","kind":"device"}""", """{"t":"setting","k":"music_collections.history_retention_days","v":30}""")).settingsKept)
        assertNull(historyRetentionDays(context))
    }

    @Test fun aSectionWithMoreRecordsThanAListMayHoldIsReadOnlyUpToTheLimit() {
        val phone = Phone()
        val bytes = file(providerOwner(), *Array(LOCAL_DATA_LIST_CAP + 25) { """{"t":"download","id":"${id(it)}","title":"T","artist":"A"}""" })
        val plan = phone.plan(bytes)
        assertEquals(25, plan.overLimit); assertEquals(LOCAL_DATA_LIST_CAP, plan.sections.single().counts.downloads)
        val report = runBlocking { (phone.transfer.import(plan) as ImportOutcome.Finished).report }
        assertEquals(LOCAL_DATA_LIST_CAP, report.added.downloads); assertEquals(25, report.overLimit)
        assertEquals(LOCAL_DATA_LIST_CAP, store.rows(accountA).size); assertEquals(0, store.downloadCount(accountA))
    }

    // ---- cancel

    @Test fun cancellingBeforeTheFirstWriteChangesNothing() {
        val source = Phone(); seedDevice(); source.seed(DEVICE_LOCAL_SCOPE_KEY); source.seed(accountA)
        val bytes = source.exported(DEVICE_LOCAL_SCOPE_KEY, accountA)
        wipe()
        val phone = Phone(); phone.play(accountA, 9, event = "own")
        val before = phone.kept(accountA); val settingsBefore = settings()
        assertEquals(ImportOpen.Cancelled, runBlocking { phone.transfer.open({ bytes.inputStream() }, { true }) })
        // The request arrives when every play has been written but nothing is committed.
        var stop = false
        hook = { if (it == "history_written") stop = true }
        val plan = phone.plan(bytes)
        assertEquals(ImportOutcome.Cancelled, runBlocking { phone.transfer.import(plan, cancelled = { stop }) })
        assertEquals(before, phone.kept(accountA)); assertEquals(settingsBefore, settings()); assertEquals(0, phone.history.count(DEVICE_LOCAL_SCOPE_KEY))
        assertFalse(plan.file.exists())
        assertEquals("local_data action=import result=cancelled", phone.events.last())
    }

    @Test fun cancellingAfterTheFirstWriteLetsTheImportFinish() {
        val source = Phone(); seedDevice(); source.seed(accountA)
        val bytes = source.exported(DEVICE_LOCAL_SCOPE_KEY, accountA)
        wipe()
        val phone = Phone()
        var stop = false
        hook = { if (it == "history_committed") stop = true }
        val outcome = phone.import(bytes, cancelled = { stop })
        assertEquals(SectionCounts(settings = 8, saved = 3, recents = 2, searches = 2, plays = 4, downloads = 2), (outcome as ImportOutcome.Finished).report.added)
    }

    // ---- storage failures

    @Test fun aFullDiskWhileTheFileIsCopiedOrTheHistoryIsWrittenChangesNothing() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val phone = Phone(); phone.play(accountA, 9, event = "own")
        val before = phone.kept(accountA)
        hook = { if (it == "import_copy") throw IOException("No space left on device") }
        assertEquals(ImportOpen.Rejected(RejectReason.Storage), phone.open(bytes))
        hook = {}
        val plan = phone.plan(bytes)
        hook = { if (it == "history_written") throw SQLiteFullException("database or disk is full") }
        assertEquals(ImportOutcome.Failed("SQLiteFullException"), runBlocking { phone.transfer.import(plan) })
        assertEquals(before, phone.kept(accountA))
        assertEquals("local_data action=import result=failed kind=SQLiteFullException", phone.events.last())
        hook = {}
        assertEquals(4, phone.report(bytes).added.plays)
    }

    @Test fun aKindOfDataThatCannotBeStoredIsNamedAndTheRestIsImported() {
        val source = Phone(); source.seed(accountA)
        val bytes = source.exported(accountA)
        wipe()
        val phone = Phone()
        hook = { if (it == "store_search history") throw IOException("No space left on device") }
        val report = phone.report(bytes)
        assertEquals(listOf("search history"), report.notStored)
        assertEquals(SectionCounts(saved = 3, recents = 2, plays = 4, downloads = 2), report.added)
        assertTrue(runBlocking { phone.searches.stored(accountA) }.isEmpty())
        assertTrue(phone.events.last().startsWith("local_data action=import result=partial "))
        // Importing the same file again completes it and adds nothing twice.
        hook = {}
        val again = phone.report(bytes)
        assertEquals(SectionCounts(searches = 2), again.added); assertTrue(again.notStored.isEmpty())
    }

    @Test fun anExportThatCannotBeFinishedNeverReachesTheChosenDocument() {
        val phone = Phone(); phone.seed(accountA)
        var opened = 0
        val sink = { opened++; ByteArrayOutputStream() }
        assertEquals(ExportOutcome.Cancelled, runBlocking { phone.transfer.export(setOf(accountA), sink, { true }) })
        hook = { if (it == "export_assembled") throw IOException("No space left on device") }
        assertEquals(ExportOutcome.Failed("IOException"), runBlocking { phone.transfer.export(setOf(accountA), sink) })
        hook = {}
        assertEquals(ExportOutcome.TooLarge, runBlocking { Phone(maxBytes = 500).transfer.export(setOf(accountA), sink) })
        assertEquals(0, opened)
        // The document itself cannot be written: reported as a failure, never as done.
        assertEquals(ExportOutcome.Failed("write"), runBlocking { phone.transfer.export(setOf(accountA), { throw IOException("fixture") }) })
        assertTrue(File(context.cacheDir, "local-data-transfer").listFiles().isNullOrEmpty())
        assertEquals(listOf("cancelled", "failed kind=IOException", "failed kind=write"), phone.events.map { it.removePrefix("local_data action=export result=") })
    }

    @Test fun eventsCarryCountsAndFixedWordsOnly() {
        val phone = Phone(); phone.seed(accountA)
        val bytes = phone.exported(accountA)
        phone.report(bytes)
        assertEquals(3, phone.events.size)
        val allowed = Regex("local_data action=(export|import_open|import) result=[a-z]+( [a-z_]+=[0-9]+)*")
        phone.events.forEach { assertTrue(it, allowed.matches(it)) }
    }
}
