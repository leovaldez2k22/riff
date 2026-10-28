package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
internal enum class ProblemKind(private val actionRes: Int) {
    /** No connection or no answer in time. Trying again is the only thing to do. */
    Offline(R.string.retry),
    /** The account's sign-in is no longer accepted. Trying again cannot help; signing in can. */
    SessionExpired(R.string.sign_in),
    /** Anything else: the provider refused, failed or sent something unreadable. */
    Unavailable(R.string.retry);
    val action: String get() = string(actionRes)
}

/** The kind of a failure, from the sentence the provider layer gave. Those sentences are fixed (ProviderContracts.kt). */
internal fun problemKind(message: String?): ProblemKind = when (message) {
    OFFLINE_MESSAGE, TIMEOUT_MESSAGE -> ProblemKind.Offline
    SESSION_EXPIRED_MESSAGE -> ProblemKind.SessionExpired
    else -> ProblemKind.Unavailable
}

/** Feature 28: opens Downloads, offered beside Retry whenever a screen fails for want of a connection. */
internal val LocalOpenDownloads = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * A failure in place of content: what happened, and the one action that fits it. With no [onSignIn] an
 * expired session offers Retry like the rest. Offline, it also says downloads still play and offers them.
 * Announced once, politely.
 */
@Composable
internal fun ContentProblem(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier, onSignIn: (() -> Unit)? = null) {
    val kind = problemKind(message).let { if (it == ProblemKind.SessionExpired && onSignIn == null) ProblemKind.Unavailable else it }
    Column(modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).padding(vertical = RiffSpace.item)
        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("content_problem"),
        verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Text(message?.takeIf(String::isNotBlank) ?: ProviderFault.Unknown.message, color = White, fontSize = RiffType.message,
            modifier = Modifier.testTag("content_problem_message"))
        val downloads = LocalOpenDownloads.current.takeIf { kind == ProblemKind.Offline }
        if (downloads != null) Text(string(R.string.your_downloads_still_play), color = Muted, fontSize = RiffType.body)
        Row {
            TextButton(onClick = { uiHaptic(); if (kind == ProblemKind.SessionExpired) onSignIn?.invoke() else onRetry() },
                modifier = Modifier.heightIn(min = RiffSpace.touch).testTag("content_problem_action")) {
                Text(kind.action, color = Pink, fontWeight = FontWeight.SemiBold)
            }
            if (downloads != null) TextButton(onClick = { uiHaptic(); downloads() },
                modifier = Modifier.heightIn(min = RiffSpace.touch).testTag("content_problem_downloads")) {
                Text(string(R.string.downloads), color = White)
            }
        }
    }
}

/** Feature 28: nothing here yet, said with an icon, a title, a line of help and an optional action. */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = RiffSpace.block).testTag("empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(RiffSpace.touch))
        Text(title, color = White, fontSize = RiffType.message, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        body?.let { Text(it, color = Muted, fontSize = RiffType.body, textAlign = TextAlign.Center) }
        action?.let { (label, onClick) ->
            TextButton(onClick = { uiHaptic(); onClick() }, modifier = Modifier.heightIn(min = RiffSpace.touch).testTag("empty_state_action")) {
                Text(label, color = Pink, fontWeight = FontWeight.SemiBold)
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
