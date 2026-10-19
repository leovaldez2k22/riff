package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Q13: what a screen reader, a switch or a keyboard is told and can do - the queue's actions and positions,
 * whether a song is liked, which tab is chosen - and that touch feedback and motion follow the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @Before fun noSolver() { shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, SolverService::class.java)) }
    @After fun close() { world?.close() }

    private val track = PlaybackUiState(ready = true, playing = true, wantsPlayback = true, mediaId = "fxsong00002", title = "Fixture Song 2",
        artist = "Fixture Artist", durationMs = 200_000, source = "YouTube Music", index = 1,
        queue = (1..6).map { QueueEntry("fxsong0000$it", "Fixture Song $it", "Fixture Artist", queueId = "q$it") })
    private val player = RecordingGateway(track)
    private fun provider() = ProviderWorld(context, true).also { world = it; it.route = { error("unscripted ${it.endpoint}") } }.provider

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) { RiffTheme { Box(Modifier.fillMaxSize()) { content() } } }
    }
    /** The queue row that holds the remove button of entry [index]: the thing a screen reader lands on. */
    private fun row(index: Int) = compose.onNode(SemanticsMatcher("queue row $index") { node ->
        node.config.contains(SemanticsActions.CustomActions) && node.children.any { it.config.getOrNull(SemanticsProperties.TestTag) == "queue_remove_$index" }
    }, useUnmergedTree = true)
    private fun actions(index: Int) = row(index).fetchSemanticsNode().config[SemanticsActions.CustomActions]

    // ---- queue

    @Test fun aMoveThatChangesNothingIsNotOffered() {
        assertEquals(listOf(QueueRowAction.MoveDown, QueueRowAction.PlayNext, QueueRowAction.Remove), queueRowActions(0, 3, 6))
        assertEquals(listOf(QueueRowAction.MoveUp, QueueRowAction.PlayNext, QueueRowAction.Remove), queueRowActions(5, 3, 6))
        assertEquals("the current song cannot play after itself", listOf(QueueRowAction.MoveUp, QueueRowAction.MoveDown, QueueRowAction.Remove), queueRowActions(3, 3, 6))
        assertEquals("the next song already plays next", listOf(QueueRowAction.MoveUp, QueueRowAction.MoveDown, QueueRowAction.Remove), queueRowActions(4, 3, 6))
        assertEquals(listOf(QueueRowAction.Remove), queueRowActions(0, 0, 1))
    }

    @Test fun playNextLandsStraightAfterTheCurrentSong() {
        assertEquals(4, playNextTarget(5, 3))
        assertEquals("a song from earlier moves down past the current one, which moves up a place", 3, playNextTarget(0, 3))
    }

    @Test fun aQueueRowSaysWhereItIsAndOffersEveryMoveWithoutDragging() {
        val p = provider()
        show { InlineQueue(player.gateway, p, {}, {}, {}) }
        // The song playing is entry 1, so the list starts at entry 2.
        assertEquals(listOf("Move up", "Move down", "Remove from queue"), actions(2).map { it.label })
        assertEquals(listOf("Move up", "Move down", "Play next", "Remove from queue"), actions(4).map { it.label })
        assertEquals(listOf("Move up", "Play next", "Remove from queue"), actions(5).map { it.label })
        assertEquals("5 of 6", row(4).fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        compose.runOnIdle { actions(4).first { it.label == "Play next" }.action() }
        compose.runOnIdle { actions(4).first { it.label == "Move up" }.action() }
        compose.runOnIdle { actions(4).first { it.label == "Move down" }.action() }
        compose.runOnIdle { actions(4).first { it.label == "Remove from queue" }.action() }
        assertEquals(listOf("moveQueueItem(4,2)", "moveQueueItem(4,3)", "moveQueueItem(4,5)", "removeQueueItem(4)"), player.asked)
    }

    @Test fun theDragHandleIsNotASecondStopForAScreenReader() {
        val p = provider()
        show { InlineQueue(player.gateway, p, {}, {}, {}) }
        val handle = compose.onNodeWithTag("queue_drag_2", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse(handle.config.contains(SemanticsActions.OnClick))
        assertNull(handle.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("still a full-size target for a finger", 48.dp, with(compose.density) { handle.size.width.toDp() })
    }

    // ---- state

    @Test fun likingSaysWhetherTheSongIsLiked() {
        val p = provider()
        show { InlineQueue(player.gateway, p, {}, {}, {}) }
        val like = compose.onNodeWithTag("queue_like").fetchSemanticsNode().config
        assertEquals("Not liked", like[SemanticsProperties.StateDescription])
        compose.runOnIdle { p.songLikes["fxsong00002"] = true }
        assertEquals("Liked", compose.onNodeWithTag("queue_like").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun thePlayerLikeButtonKeepsOneNameAndSaysItsState() {
        val p = provider()
        show { NowPlayingPanel(player.gateway, LocalCollections(context), p, onClose = {}, onSwipeDrag = { _, _ -> }, onSwipeRelease = {},
            onQueue = {}, onLibrary = {}, onLyrics = {}, onArtist = {}, onAlbum = {}, onFailureAction = {}, onNotice = {}) }
        val like = compose.onNodeWithTag("playback_favorite").fetchSemanticsNode().config
        assertEquals(listOf("Like on YouTube Music"), like[SemanticsProperties.ContentDescription])
        assertEquals("Not liked", like[SemanticsProperties.StateDescription])
    }

    @Test fun exactlyOneTabIsChosenAndEachIsATab() {
        val model = ShellModel()
        show { Box(Modifier.fillMaxWidth().height(64.dp)) { LiquidTabBar(model, true, 64.dp) } }
        fun tabs() = listOf("home", "podcasts", "radio", "library", "search").map { compose.onNodeWithTag("tab_$it").fetchSemanticsNode().config }
        assertTrue(tabs().all { it[SemanticsProperties.Role] == Role.Tab })
        assertEquals(listOf(true, false, false, false, false), tabs().map { it[SemanticsProperties.Selected] })
        compose.runOnIdle { model.select("Library") }
        assertEquals(listOf(false, false, false, true, false), tabs().map { it[SemanticsProperties.Selected] })
    }

    // ---- keyboard

    @OptIn(ExperimentalTestApi::class)
    @Test fun aTabAndAFilterChipAnswerEnterAndSpace() {
        val model = ShellModel()
        var pressed = 0
        show { androidx.compose.foundation.layout.Column {
            GlassCategoryChip("Songs", key = "search:Songs") { pressed++ }
            Box(Modifier.fillMaxWidth().height(64.dp)) { LiquidTabBar(model, true, 64.dp) }
        } }
        compose.onNodeWithTag("search_filter_songs").performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("search_filter_songs").performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals(2, pressed) }
        compose.onNodeWithTag("tab_radio").performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("Radio", model.tab) }
        compose.onNodeWithTag("tab_library").performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals("Library", model.tab) }
    }

    // ---- touch feedback

    @Test fun everyCueHasAConstantAnOlderPhoneKnows() {
        // API 31-33 know none of the four constants added in 34; given one, the view does nothing at all.
        val before34 = setOf(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.REJECT, HapticFeedbackConstants.GESTURE_END,
            HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.CONTEXT_CLICK,
            HapticFeedbackConstants.VIRTUAL_KEY)
        for (sdk in 31..33) HapticCue.entries.forEach { assertTrue("$it on API $sdk", hapticConstant(it, sdk) in before34) }
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstant(HapticCue.Threshold, 33))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, hapticConstant(HapticCue.Threshold, 34))
        assertEquals(HapticFeedbackConstants.DRAG_START, hapticConstant(HapticCue.DragStart, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, hapticConstant(HapticCue.ToggleOn, 34))
    }

    private class RecordingView(context: Context) : View(context) {
        val cues = mutableListOf<Int>()
        override fun isAttachedToWindow() = true
        override fun performHapticFeedback(feedbackConstant: Int): Boolean { cues += feedbackConstant; return true }
    }

    @Test fun touchFeedbackFollowsTheAppSettingTheSystemSettingAndTheForeground() {
        val view = RecordingView(context)
        val settings = AccessibilitySettings(context)
        val owner = object : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
        owner.registry.currentState = Lifecycle.State.RESUMED
        UiHaptics.bind(view, owner.lifecycle, settings)
        fun cue(): Int { ShadowSystemClock.advanceBy(Duration.ofMillis(200)); val before = view.cues.size; uiHaptic(HapticCue.Confirm); return view.cues.size - before }
        try {
            settings.updateHapticFeedback(true)
            assertEquals(1, cue())
            settings.updateHapticFeedback(false)
            assertEquals("the app's own switch", 0, cue())
            settings.updateHapticFeedback(true)
            view.isHapticFeedbackEnabled = false
            assertEquals("the view's (system) switch", 0, cue())
            view.isHapticFeedbackEnabled = true
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals("not while the app is behind something else", 0, cue())
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(1, cue())
        } finally { UiHaptics.unbind(view); settings.updateHapticFeedback(true) }
    }

    // ---- motion

    @Test fun motionIsReducedByTheAppSettingOrByTheSystemTurningAnimationsOff() {
        var policy: MotionPolicy? = null
        val preference = androidx.compose.runtime.mutableStateOf(false)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.setContent { policy = rememberMotionPolicy(preference.value) }
        compose.runOnIdle { assertEquals(MotionPolicy(false, 1f), policy) }
        preference.value = true
        compose.runOnIdle { assertEquals(MotionPolicy(true, 1f), policy) }
        preference.value = false
        // The system setting can change while the app is open.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.runOnIdle { assertEquals(MotionPolicy(true, 0f), policy) }
    }

    @Test fun reducedMotionMakesEveryTimedAnimationInstant() {
        val reduced = MotionPolicy(true, 1f)
        listOf(GlassMotion.pressMs, GlassMotion.selectionMs, GlassMotion.playerMs).forEach {
            assertTrue(GlassMotion.timed<Float>(it, reduced) is androidx.compose.animation.core.SnapSpec)
            assertEquals(it, (GlassMotion.timed<Float>(it, MotionPolicy(false, 1f)) as androidx.compose.animation.core.TweenSpec).durationMillis)
        }
        assertTrue(GlassMotion.settle<Float>(reduced) is androidx.compose.animation.core.SnapSpec)
    }
}
