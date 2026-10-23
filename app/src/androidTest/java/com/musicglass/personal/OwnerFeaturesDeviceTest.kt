package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * C7 (final-stretch plan, 20 October 2026): the owner's features of 20 October on a device, drawn and stored by the
 * real code. F6 the player's clock in hours; F7 speed-dial pins kept across a new store (as after a restart) and drawn
 * first with their badge; F8 frosted glass by default at 20 %; F9 the backup writing, reading and removing its files
 * in Documents through MediaStore. No provider request, no network, nothing played. Every setting and file a test
 * changes is put back or removed. F10 (Not interested to YouTube Music) has no device part: it is covered by the JVM
 * tests against recorded endpoints and live in B7.
 */
class OwnerFeaturesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FeatureTestActivity>()
    private val app: Context get() = compose.activity.applicationContext

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun player(durationMs: Long, positionMs: Long) {
        val state = PlaybackUiState(ready = true, playing = false, mediaId = "yt:fxsong00001", title = "Fixture Episode",
            artist = "Fixture Show", durationMs = durationMs, positionMs = positionMs, source = "YouTube Music", index = 0,
            queue = listOf(QueueEntry("yt:fxsong00001", "Fixture Episode", "Fixture Show", queueId = "q1")))
        val gateway = Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { self, method, args ->
            when (method.name) {
                "getState" -> state
                "getPositionMs", "currentPositionMs" -> positionMs
                "hasProviderItems" -> false
                "equals" -> self === args[0]; "hashCode" -> System.identityHashCode(self); "toString" -> "fixture gateway"
                else -> null
            }
        } as PlaybackGateway
        val provider = YouTubeMusicProvider.shared(compose.activity)
        val accessibility = AccessibilitySettings(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides accessibility, LocalMotionPolicy provides MotionPolicy(false, 1f)) {
                RiffTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1B2A22)).testTag("stage")) {
                        PlayerMorphHost({ 1f }, ShellModel(), false, gateway, LocalCollections(compose.activity), 24f,
                            miniBounds = null, miniElementBounds = emptyMap<String, Rect>(), fullDragging = false, provider = provider,
                            onFailureAction = {}, onMorphDrag = { _, _ -> }, onNotice = {}, onArtist = {}, onAlbum = {},
                            onMorphRelease = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { exists("player_close") }
    }

    @Test fun theClockCountsHoursOnALongEpisode() {
        // 2 h episode at 1:02:05; 57:55 left.
        player(durationMs = 7_200_000, positionMs = 3_725_000)
        compose.waitUntil(5_000) { shown("1:02:05") }
        assertTrue("remaining time", shown("−57:55"))
        assertFalse("minutes are not stacked past the hour", shown("62:05"))
    }

    @Test fun underAnHourTheClockStaysInMinutes() {
        player(durationMs = 230_000, positionMs = 62_000)
        compose.waitUntil(5_000) { shown("1:02") }
        assertTrue(shown("−2:48"))
        assertFalse(shown("0:01:02"))
    }

    @Test fun pinsSurviveANewStoreAreDrawnFirstWithTheirBadgeAndStopAtTheTileCount() {
        val prefs = app.getSharedPreferences("speed_dial_pins", Context.MODE_PRIVATE)
        val before = prefs.getString("pins", null)
        try {
            prefs.edit().remove("pins").commit()
            val song = ProviderHomeCard("Pinned Song", "Fixture Artist", null, videoId = "fxpinsong01")
            val episode = ProviderHomeCard("Pinned Episode", "Fixture Show", null, videoId = "fxpinepis01", kind = CatalogKind.Episode)
            val album = ProviderHomeCard("Pinned Album", "Fixture Artist", null, browseId = "MPREb_fxpin01", kind = CatalogKind.Album)
            val first = SpeedDialPins(app)
            listOf(song, episode, album).forEach { assertTrue(first.pin(it)) }
            // A new store reads what the last one wrote, as Riff does after it is closed and opened again.
            val again = SpeedDialPins(app)
            assertEquals(listOf("fxpinsong01", "fxpinepis01", "MPREb_fxpin01"), again.cards.map { it.key() })

            val feed = (1..4).map { ProviderHomeCard("Feed Song $it", "Fixture Artist", null, videoId = "fxfeedsng0$it") }
            val cards = withSpeedDialPins(again.cards, listOf(feed[0], song) + feed.drop(1))
            compose.setContent { RiffTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
                HomeSpeedDial(cards, again.cards.mapTo(HashSet()) { it.key() }) {}
            } } }
            compose.waitForIdle()
            val badges = compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.pinned), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertEquals("one badge per pin, and a pinned song in the feed is not shown twice", 3, badges.size)
            fun place(title: String) = compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().single()
                .boundsInRoot.let { it.top to it.left }
            val order = (listOf(song, episode, album) + feed).map { it.title }.sortedWith(compareBy({ place(it).first }, { place(it).second }))
            assertEquals(listOf("Pinned Song", "Pinned Episode", "Pinned Album", "Feed Song 1", "Feed Song 2", "Feed Song 3", "Feed Song 4"), order)

            (1..SPEED_DIAL_PIN_LIMIT - 3).forEach { assertTrue(again.pin(ProviderHomeCard("Fill $it", "", null, videoId = "fxpinfill%02d".format(it)))) }
            assertFalse("every tile is pinned", again.pin(ProviderHomeCard("One more", "", null, videoId = "fxpinmore01")))
            again.cards.map { it.key() }.forEach(again::unpin)
            assertTrue(SpeedDialPins(app).cards.isEmpty())
        } finally {
            prefs.edit().apply { if (before == null) remove("pins") else putString("pins", before) }.commit()
        }
    }

    @Test fun aNewInstallHasFrostedGlassAt20PercentAndAChoiceIsKept() {
        val prefs = app.getSharedPreferences("accessibility_display", Context.MODE_PRIVATE)
        val saved = prefs.all.filterKeys { it == "frosted_glass" || it == "frosted_intensity" }
        try {
            prefs.edit().remove("frosted_glass").remove("frosted_intensity").commit()
            val fresh = AccessibilitySettings(app)
            assertTrue(fresh.frostedGlass); assertEquals(.2f, fresh.frostedIntensity, 1e-6f)
            fresh.updateFrostedIntensity(.6f); fresh.updateFrostedGlass(false)
            Thread.sleep(200)
            val reopened = AccessibilitySettings(app)
            assertFalse(reopened.frostedGlass); assertEquals(.6f, reopened.frostedIntensity, 1e-6f)
        } finally {
            prefs.edit().apply {
                remove("frosted_glass"); remove("frosted_intensity")
                (saved["frosted_glass"] as? Boolean)?.let { putBoolean("frosted_glass", it) }
                (saved["frosted_intensity"] as? Float)?.let { putFloat("frosted_intensity", it) }
            }.commit()
        }
    }

    @Test fun theBackupWritesReadsAndRemovesItsFilesInDocuments() = runBlocking {
        val folder = MediaStoreBackupFolder(app.contentResolver, backupFolderName(app.packageName))
        // The folder itself: a file is created, rewritten whole (not appended to), read back and removed.
        val probe = "riff-backup-c7-probe.jsonl"
        try {
            folder.output(probe).use { it.write("a first, longer line\n".toByteArray()) }
            folder.output(probe).use { it.write("short\n".toByteArray()) }
            assertEquals("short\n", folder.list().getValue(probe)().use { it.readBytes().decodeToString() })
        } finally { folder.delete(probe) }
        assertFalse(probe in folder.list())

        // The real backup of this install, then the switch that removes it. A folder picked for a restore earlier on
        // this device is set aside, so the backup writes Riff's own files in Documents.
        val backup = AppContainer.get(app).localBackup
        val wasOn = backup.enabled
        val prefs = app.getSharedPreferences("local_backup", Context.MODE_PRIVATE)
        val tree = prefs.getString("tree", null); val offered = prefs.getBoolean("restore_offered", false)
        prefs.edit().remove("tree").putBoolean("restore_offered", true).commit()
        try {
            backup.turnBackup(true)
            val outcome = backup.backUp(force = true)
            assertTrue("$outcome", outcome is BackupOutcome.Done && outcome.files >= 1)
            val written = folder.list().filterKeys { backupBaseName(it) != null }
            // Q18-069: beside an earlier install's file of the same name Android numbers this one ("riff-backup-phone (1).jsonl").
            assertTrue("this phone's file: ${written.keys}", written.keys.any { backupBaseName(it) == "riff-backup-phone.jsonl" })
            written.values.forEach { open -> assertTrue(open().bufferedReader().use { it.readLine() }.startsWith("{\"riff\":\"local-data\"")) }
            assertTrue(backup.lastBackupAt > 0)
            assertEquals(written.size, backup.turnBackup(false))
            assertTrue("nothing left: ${folder.list().keys}", folder.list().keys.none { it in written })
        } finally {
            backup.turnBackup(wasOn)
            prefs.edit().putBoolean("restore_offered", offered).apply { if (tree != null) putString("tree", tree) }.commit()
            // Turned back on, it writes again only when Riff next leaves the screen; the lane's own emulator is wiped.
        }
    }
}
