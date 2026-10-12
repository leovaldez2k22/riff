package com.musicglass.personal

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Q12: the decisions of the core journeys that had no test of their own. Synthetic content only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JourneyDecisionsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }
    private val player = RecordingGateway()

    // ---- UX01: first launch, signed out

    @Test fun signedOutTheLibraryIsUsefulAtOnceAndWaitsForNothing() {
        val w = ProviderWorld(context, signedIn = false).also { world = it }
        w.route = { error("Signed out, the Library asks the provider nothing: ${it.endpoint}") }
        val model = ShellModel().apply { select("Library") }
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                AccountLibraryScreen(model, 0.dp, LocalCollections(context), player.gateway, w.provider) {}
            }
        }
        compose.waitForIdle()
        assertEquals("No spinner with nothing behind it", 0, compose.count("list_skeleton"))
        assertEquals(0, compose.count("content_problem"))
        // What works without an account is offered, and signing in is one press away.
        compose.onNodeWithTag("library_history").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sign in to YouTube Music"))
        compose.onNodeWithText("Sign in to YouTube Music").performClick()
        assertEquals(Route.Account, model.current)
        assertTrue(w.calls.isEmpty())
    }

    // ---- UX06: lyrics timing

    @Test fun lyricsTimingStartsAtItsDefaultStepsInsideItsLimitsAndResetReturnsToTheDefault() {
        assertEquals(200L, DEFAULT_LYRICS_OFFSET_MS)
        var closed = false
        compose.setContent {
            var offset by remember { mutableLongStateOf(DEFAULT_LYRICS_OFFSET_MS) }
            LyricsTimingDialog(offset, { offset = it }) { closed = true }
        }
        val value = { compose.onNodeWithTag("lyrics_offset_value") }
        value().assertTextEquals("+0.2s")
        compose.onNodeWithTag("lyrics_offset_plus").performClick(); value().assertTextEquals("+0.3s")
        repeat(4) { compose.onNodeWithTag("lyrics_offset_minus").performClick() }
        value().assertTextEquals("-0.1s")
        compose.onNodeWithTag("lyrics_offset_reset").performClick(); value().assertTextEquals("+0.2s")
        compose.onNodeWithText("Done").performClick(); assertTrue(closed)
    }
    @Test fun lyricsTimingCannotLeaveItsLimits() {
        assertEquals(LYRICS_OFFSET_LIMIT_MS, lyricsOffsetStep(LYRICS_OFFSET_LIMIT_MS, later = true))
        assertEquals(-LYRICS_OFFSET_LIMIT_MS, lyricsOffsetStep(-LYRICS_OFFSET_LIMIT_MS, later = false))
        assertEquals(300L, lyricsOffsetStep(200L, later = true)); assertEquals(100L, lyricsOffsetStep(200L, later = false))
        assertEquals("+0.0s", lyricsOffsetLabel(0)); assertEquals("-5.0s", lyricsOffsetLabel(-5000))
    }

    // ---- UX10: widgets show the selected track as it is

    private fun widget(preset: Int, state: RiffWidgets.WidgetState) =
        RiffWidgets.views(context, riffWidgetPresets[preset].layout, preset, 7, state, null).apply(context, FrameLayout(context))
    @Test fun aWidgetShowsTheSelectedTrackAndWhetherItIsPlaying() {
        val playing = widget(4, RiffWidgets.WidgetState("Fixture Song", "Fixture Artist", null, playing = true, next = "Fixture Next", available = true))
        assertEquals("Fixture Song", playing.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("Fixture Artist", playing.findViewById<TextView>(R.id.widget_artist).text.toString())
        assertEquals("Up next: Fixture Next", playing.findViewById<TextView>(R.id.widget_up_next).text.toString())
        assertEquals(R.drawable.widget_pause, shadowOf(playing.findViewById<ImageView>(R.id.widget_toggle).drawable).createdFromResId)
        val paused = widget(4, RiffWidgets.WidgetState("Fixture Song", "Fixture Artist", null, playing = false, next = "", available = true))
        assertEquals(R.drawable.widget_toggle, shadowOf(paused.findViewById<ImageView>(R.id.widget_toggle).drawable).createdFromResId)
        assertEquals("No next song is not written as one", "", paused.findViewById<TextView>(R.id.widget_up_next).text.toString())
    }
    @Test fun aWidgetWithNothingSelectedSaysSoAndShowsPlayNotPause() {
        riffWidgetPresets.indices.forEach { preset ->
            val idle = widget(preset, RiffWidgets.WidgetState())
            assertEquals("Riff", idle.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals(R.drawable.widget_toggle, shadowOf(idle.findViewById<ImageView>(R.id.widget_toggle).drawable).createdFromResId)
        }
    }
    @Test fun aWidgetAcceptsOnlyTheThreeTransportCommands() {
        assertTrue(listOf("toggle", "previous", "next").all(::widgetCommandAllowed))
        assertFalse(listOf("", "seek", "clear", "Toggle", "delete").any(::widgetCommandAllowed))
    }

    // ---- rows: long titles and missing artwork change nothing about the layout

    private fun rowHeights(vararg cards: ProviderHomeCard): List<Float> {
        compose.setContent { Column(Modifier.width(320.dp)) { cards.forEach { CatalogCardRow(it) {} } } }
        return cards.map { compose.onNodeWithTag("catalog_${it.kind.name.lowercase()}_${it.key()}").getUnclippedBoundsInRoot().let { b -> (b.bottom - b.top).value } }
    }
    @Test fun aVeryLongTitleIsCutWithAnEllipsisAndTheRowKeepsItsHeight() {
        val (short, long) = rowHeights(
            ProviderHomeCard("Row", "Fixture Artist", null, videoId = fixtureVideoId(1)),
            ProviderHomeCard("Fixture title that goes on ".repeat(20), "Fixture Artist", null, videoId = fixtureVideoId(2)))
        assertEquals(short, long, 0.01f)
    }
    @Test fun aRowWithoutArtworkIsTheSameSizeAsOneWithIt() {
        val (without, with) = rowHeights(
            ProviderHomeCard("Row", "Fixture Artist", null, videoId = fixtureVideoId(1)),
            ProviderHomeCard("Row", "Fixture Artist", "https://fixture.invalid/art.jpg", videoId = fixtureVideoId(2)))
        assertEquals(without, with, 0.01f)
    }

    // ---- tokens

    @Test fun textColoursAreReadableOnTheSurfacesTheyAreDrawnOn() {
        listOf(Ink, Raised).forEach { surface ->
            listOf(White, Muted, Pink).forEach { text ->
                assertTrue("$text on $surface: ${contrastRatio(text, surface)}", contrastRatio(text, surface) >= 4.5)
            }
        }
        assertTrue("The words on a filled button", contrastRatio(Ink, Pink) >= 7.0)
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01); assertEquals(1.0, contrastRatio(Pink, Pink), 0.0001)
    }
    @Test fun anythingPressableIsAtLeastTheSmallestTouchSize() {
        assertTrue(RiffSpace.touch >= 48.dp); assertTrue(RiffSpace.listRow >= RiffSpace.touch)
        assertTrue(RiffSpace.listArtwork <= RiffSpace.listRow)
    }
}
