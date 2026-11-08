package com.musicglass.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight

/** Feature 17 (Riff+): the song and artist background, looked up when this opens and cancelled when it closes. */
@Composable internal fun SongBackgroundDialog(title: String, artist: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var found by remember(title, artist, attempt) { mutableStateOf<SongBackground?>(null) }
    LaunchedEffect(title, artist, attempt) { found = suspendCatching { songBackground(context, title, artist) }.getOrElse { SongBackground(BackgroundStatus.Unavailable) } }
    FrostedAlertDialog(onDismissRequest = onDismiss, title = { Text(string(R.string.about_this_song)) },
        text = {
            Column(Modifier.heightIn(max = RiffBackgroundPanel.maxHeight).verticalScroll(rememberScrollState()).testTag("song_background"), verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                val shown = found
                when {
                    shown == null -> Text(string(R.string.background_loading), color = Muted, modifier = Modifier.testTag("song_background_loading"))
                    shown.status == BackgroundStatus.NotFound -> Text(string(R.string.background_not_found), color = Muted)
                    shown.status == BackgroundStatus.Ambiguous -> Text(string(R.string.background_ambiguous), color = Muted, modifier = Modifier.testTag("song_background_ambiguous"))
                    shown.status == BackgroundStatus.Unavailable -> {
                        Text(string(R.string.background_unavailable), color = Muted)
                        TextButton({ uiHaptic(); attempt++ }) { Text(string(R.string.background_retry)) }
                    }
                    else -> {
                        shown.song?.let { s ->
                            Section(string(R.string.background_song), s.title, listOfNotNull(
                                s.released?.let { string(R.string.background_released, it) }, s.album?.let { string(R.string.background_album, it) },
                                s.writers.takeIf { it.isNotEmpty() }?.let { string(R.string.background_writers, it.joinToString(", ")) }), s.article, s.musicBrainzUrl)
                        }
                        shown.artist?.let { a ->
                            Section(string(R.string.background_artist), a.name, listOfNotNull(a.disambiguation, listOfNotNull(a.kind, a.country).joinToString(" · ").ifBlank { null },
                                a.begin?.let { string(R.string.background_started, it) }), a.article, a.musicBrainzUrl)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) } })
}

@Composable private fun Section(heading: String, name: String, facts: List<String>, article: BackgroundArticle?, musicBrainz: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Text(heading, color = Muted, fontSize = RiffType.detail)
        Text(name, color = White, fontWeight = FontWeight.SemiBold)
        facts.forEach { Text(it, color = White, fontSize = RiffType.detail) }
        if (article != null) {
            Text(article.extract, color = White, modifier = Modifier.padding(top = RiffSpace.tight).testTag("song_background_extract"))
            Text(string(if (article.fallback) R.string.background_wikipedia_fallback else R.string.background_wikipedia), color = Muted, fontSize = RiffType.detail)
        } else Text(string(R.string.background_no_article), color = Muted, fontSize = RiffType.detail)
        Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
            if (article != null) TextButton({ uiHaptic(); openWebPage(context, article.url, listOf("${article.lang}.wikipedia.org")) }) { Text(string(R.string.background_open_article)) }
            TextButton({ uiHaptic(); openWebPage(context, musicBrainz, listOf("musicbrainz.org")) }) { Text(string(R.string.background_open_musicbrainz)) }
        }
    }
}
