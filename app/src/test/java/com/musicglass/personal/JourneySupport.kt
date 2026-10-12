package com.musicglass.personal

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollToNode
import java.lang.reflect.Proxy

/** Q12 journey tests: one screen or the shell itself on the scripted provider, with synthetic content only. */

/** A gateway that shows [state] and writes down everything it is asked to do; nothing is played. */
internal class RecordingGateway(var shown: PlaybackUiState = PlaybackUiState()) {
    val asked = mutableListOf<String>()
    val gateway = Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { self, method, args ->
        when (method.name) {
            "getState" -> shown
            "getPositionMs", "currentPositionMs" -> shown.positionMs
            "hasProviderItems" -> false
            "equals" -> self === args[0]; "hashCode" -> System.identityHashCode(self); "toString" -> "recording gateway"
            else -> { asked += method.name + args.orEmpty().filter { it is Number || it is String || it is Boolean }.joinToString(",", "(", ")"); null }
        }
    } as PlaybackGateway
}

/** A whole screen on Robolectric draws a frame slowly, so a wait is given far longer than the work needs. */
internal const val WAIT_MS = 30_000L

internal fun reply(fixture: String) = ProviderWorld.Reply(providerFixture(fixture))

internal fun ComposeContentTestRule.count(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
internal fun ComposeContentTestRule.awaitTag(tag: String) = waitUntil(WAIT_MS) { count(tag) > 0 }
internal fun ComposeContentTestRule.awaitNoTag(tag: String) = waitUntil(WAIT_MS) { count(tag) == 0 }
/** The one list on screen, scrolled until [tag] is in it. */
internal fun ComposeContentTestRule.reach(tag: String): SemanticsNodeInteraction = onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
/** How far the one list on screen is scrolled, as the list itself reports it. */
internal fun ComposeContentTestRule.listOffset(): Float =
    onNode(hasScrollToNodeAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
