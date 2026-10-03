package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on Home and Library when Google no longer accepts the saved sign-in (W5). One compact row
 * between the status bar and the category chips: the chips belong to the glass chrome, which is
 * drawn above screen content, so a taller card would sit underneath them. The full explanation is on
 * the Account screen. Downloads, local saves, search and radio keep working, so it is dismissible.
 */
@Composable
internal fun SessionExpiredBanner(onSignIn: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = top + 4.dp).height(52.dp)
        .background(Color(0xFA1B1B22), RoundedCornerShape(26.dp))
        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
        .padding(start = 18.dp, end = 2.dp)
        .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }.testTag("session_expired_banner"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Session expired", color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = { uiHaptic(); onSignIn() }, modifier = Modifier.testTag("session_sign_in_again")) {
            Text("Sign in again", color = Pink, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        IconButton(onClick = { uiHaptic(); onDismiss() }) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = Muted)
        }
    }
}
