package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/**
 * Q12: what a screen shows in place of content. Loading, nothing here, and something failed are three
 * different things and never share a look: a failure is never drawn as an empty list. A failure stays on
 * screen until it is retried or left; it is not only a passing message.
 */
internal enum class ProblemKind(val action: String) {
    /** No connection or no answer in time. Trying again is the only thing to do. */
    Offline("Retry"),
    /** The account's sign-in is no longer accepted. Trying again cannot help; signing in can. */
    SessionExpired("Sign in"),
    /** Anything else: the provider refused, failed or sent something unreadable. */
    Unavailable("Retry")
}

/** The kind of a failure, from the sentence the provider layer gave. Those sentences are fixed (ProviderContracts.kt). */
internal fun problemKind(message: String?): ProblemKind = when (message) {
    OFFLINE_MESSAGE, TIMEOUT_MESSAGE -> ProblemKind.Offline
    SESSION_EXPIRED_MESSAGE -> ProblemKind.SessionExpired
    else -> ProblemKind.Unavailable
}

/**
 * A failure in place of content: what happened, and the one action that fits it. With no [onSignIn] an
 * expired session offers Retry like the rest. Announced once, politely.
 */
@Composable
internal fun ContentProblem(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier, onSignIn: (() -> Unit)? = null) {
    val kind = problemKind(message).let { if (it == ProblemKind.SessionExpired && onSignIn == null) ProblemKind.Unavailable else it }
    Column(modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).padding(vertical = RiffSpace.item)
        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("content_problem"),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Text(message?.takeIf(String::isNotBlank) ?: ProviderFault.Unknown.message, color = White, fontSize = RiffType.message,
            modifier = Modifier.testTag("content_problem_message"))
        Row {
            TextButton(onClick = { uiHaptic(); if (kind == ProblemKind.SessionExpired) onSignIn?.invoke() else onRetry() },
                modifier = Modifier.heightIn(min = RiffSpace.touch).testTag("content_problem_action")) {
                Text(kind.action, color = Pink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Nothing to show, and nothing went wrong. */
@Composable
internal fun ContentEmpty(message: String, modifier: Modifier = Modifier) {
    Text(message, color = Muted, fontSize = RiffType.body,
        modifier = modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).padding(vertical = RiffSpace.item).testTag("content_empty"))
}
