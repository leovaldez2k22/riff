package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class EditionStatsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @Before fun noSolver() { shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, SolverService::class.java)) }
    @After fun close() { world?.close() }

    private fun checkPlayerMenu(source: String) {
        val provider = ProviderWorld(context).also { world = it }.provider
        val gateway = RecordingGateway(PlaybackUiState(ready = true, mediaId = if (source == "YouTube Music") "yt:fxsong00001" else "local:fixture", title = "Fixture song",
            artist = "Fixture artist", durationMs = 200_000, source = source, artworkUrl = "file:///missing-fixture-cover")).gateway
        val preferences = AccessibilitySettings(context)
        preferences.updatePlayerStats(false)
        preferences.updatePlayerLyricLine(false)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides preferences,
                LocalMotionPolicy provides MotionPolicy(reduced = true, scale = 1f)) {
                RiffTheme { Box(Modifier.fillMaxSize()) {
                    NowPlayingPanel(gateway, LocalCollections(context), provider, onClose = {}, onSwipeDrag = { _, _ -> },
                        onSwipeRelease = {}, onQueue = {}, onLibrary = {}, onLyrics = {}, onArtist = {}, onAlbum = {},
                        onFailureAction = {}, onNotice = {})
                } }
            }
        }
        compose.onNodeWithTag("player_more").performClick()
        compose.waitForIdle()
        if (Edition.plus) compose.onNodeWithText("Stats for nerds").assertExists()
        else compose.onNodeWithText("Stats for nerds").assertDoesNotExist()
        // Owner, 9 November 2026: the library has its own tab; the sound tools sit under Playback tools.
        compose.onNodeWithText("Library shortcuts").assertDoesNotExist()
        compose.onNodeWithText("Playback tools").performClick()
        compose.waitForIdle()
        if (Edition.plus) compose.onNodeWithText("Equalizer").assertExists()
        else compose.onNodeWithText("Equalizer").assertDoesNotExist()
        compose.onNodeWithText("Playback speed").assertExists()
    }

    @Test fun localSongMenuContainsOnlyThisEditionsActions() = checkPlayerMenu("On this phone")
    @Test fun catalogSongMenuContainsOnlyThisEditionsActions() = checkPlayerMenu("YouTube Music")

    @Test fun importingAnEnabledStatsPreferenceCannotStartPollingInTheLightEdition() {
        val prefs = context.getSharedPreferences("accessibility_display", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("player_stats", true).commit()
        val before = prefs.all.toMap()
        var calls = 0
        val recording = RecordingGateway(PlaybackUiState(mediaId = "local:fixture"))
        val gateway = object : PlaybackGateway by recording.gateway {
            override suspend fun nerdStats(): Bundle = Bundle().apply { calls++; putString("mime", "audio/opus") }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { RiffTheme { Edition.statsUi?.Line(gateway, true, Modifier) } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(Edition.plus, calls > 0)
        assertEquals(before, prefs.all)
        assertEquals(Edition.plus, compose.onAllNodesWithTag("player_stats_line").fetchSemanticsNodes().isNotEmpty())
    }
}
