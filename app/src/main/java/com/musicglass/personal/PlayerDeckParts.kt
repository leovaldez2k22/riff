package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

/*
 * Feature 34.3: the smaller parts of the redesigned player. All are white at a set opacity over the backdrop.
 */

/** What streams ask for on the network in use: "High quality", "Medium quality", "Low quality" or "Data saver". */
internal fun playerQualityLabel(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean("enabled", false)) return string(R.string.data_saver)
    val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
    return string(when (networkSongQuality(prefs, metered)) {
        SongQuality.High -> R.string.quality_high; SongQuality.Medium -> R.string.quality_medium; SongQuality.Low -> R.string.quality_low
    })
}

/** The middle of the time row: a headphone and the quality streams ask for, at 45 %. Read when the song changes. */
@Composable
internal fun PlayerQualityLabel(mediaId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(mediaId) { playerQualityLabel(context) }
    Row(modifier.testTag("player_quality"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Icon(Icons.Rounded.Headphones, null, tint = White.copy(alpha = .45f), modifier = Modifier.size(RiffPlayer.qualityIcon))
        Text(label, color = White.copy(alpha = .45f), fontSize = RiffPlayer.qualityText, fontWeight = FontWeight.Medium)
    }
}

/** Shuffle and repeat when on: full white with a small dot under, rather than the old pink. */
@Composable
internal fun ActiveDot(active: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(RiffPlayer.activeDot).clip(CircleShape).background(if (active) White else Color.Transparent))
    }
}
