package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Feature 29: after an update, the release's main features once, from res/raw/whats_new.json (written for each
 * release beside the changelog). A fresh install gets the first-run tips instead. Both are in Settings → About.
 */

internal data class WhatsNewCard(val title: String, val body: String, val action: String = "")
internal data class WhatsNewRelease(val version: Int, val cards: List<WhatsNewCard>)

internal enum class WhatsNewShow { None, Release, Tips }

/**
 * What to show at start. [lastSeen] is the version code Riff last started as (0: never recorded), [current] this one,
 * [release] the version the sheet's content was written for. An update shows the sheet once, and only when its
 * content is newer than what was seen; a fresh install shows the tips.
 */
internal fun whatsNewDue(lastSeen: Int, current: Int, freshInstall: Boolean, release: Int): WhatsNewShow = when {
    lastSeen >= current -> WhatsNewShow.None
    lastSeen == 0 && freshInstall -> WhatsNewShow.Tips
    release > lastSeen && release <= current -> WhatsNewShow.Release
    else -> WhatsNewShow.None
}

/** The release's cards, or null when the file is not as described (a card without a title or text is left out). */
internal fun parseWhatsNew(text: String): WhatsNewRelease? = try {
    val root = Json.parseToJsonElement(text).jsonObject
    WhatsNewRelease(root.getValue("version").jsonPrimitive.int, root.getValue("cards").jsonArray.mapNotNull { element ->
        val card = element as? JsonObject ?: return@mapNotNull null
        fun text(key: String) = card[key]?.jsonPrimitive?.content.orEmpty()
        WhatsNewCard(text("title"), text("body"), text("action")).takeIf { it.title.isNotBlank() && it.body.isNotBlank() }
    })
} catch (_: IllegalArgumentException) { null } catch (_: NoSuchElementException) { null }

private const val SEEN_VERSION = "whats_new_seen_version"

/** What to show now, recording this version as seen so it shows once. */
internal fun whatsNewAtStart(context: Context, current: Int = BuildConfig.VERSION_CODE): WhatsNewShow {
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val release = loadWhatsNew(context)?.version ?: 0
    val show = whatsNewDue(prefs.getInt(SEEN_VERSION, 0), current, info.firstInstallTime == info.lastUpdateTime, release)
    prefs.edit().putInt(SEEN_VERSION, current).apply()
    return show
}

internal fun loadWhatsNew(context: Context): WhatsNewRelease? =
    context.resources.openRawResource(R.raw.whats_new).bufferedReader().use { parseWhatsNew(it.readText()) }

/** The sheet showing, if any. Settings → About opens it again. */
internal var whatsNewShowing by mutableStateOf(WhatsNewShow.None)

/** Where "Try it" goes. An unknown action only closes the sheet. */
internal fun tryWhatsNew(action: String, model: ShellModel) {
    when (action) {
        "encore" -> model.push(Route.Encore)
        "equalizer" -> model.push(Route.Settings(SettingsSection.Equalizer))
        "settings" -> model.push(Route.Settings())
        "player" -> if (model.sheet.isEmpty()) model.sheet = "mini"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewSheet(model: ShellModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (whatsNewShowing == WhatsNewShow.None) whatsNewShowing = whatsNewAtStart(context) }
    val show = whatsNewShowing
    if (show == WhatsNewShow.None) return
    val cards = remember(show) {
        if (show == WhatsNewShow.Release) loadWhatsNew(context)?.cards.orEmpty()
        else listOf(WhatsNewCard(string(R.string.tip_links_title), string(R.string.tip_links_body), "links"),
            WhatsNewCard(string(R.string.tip_car_title), string(R.string.tip_car_body)))
    }
    val close = { whatsNewShowing = WhatsNewShow.None }
    ModalBottomSheet(onDismissRequest = close, containerColor = Raised, scrimColor = Color.Black.copy(alpha = .55f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = RiffSpace.screenEdge)
            .padding(bottom = RiffSpace.block * 2).testTag("whats_new"), verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
            Text(string(if (show == WhatsNewShow.Release) R.string.whats_new else R.string.tips), color = White, style = RiffText.sectionHeading)
            cards.forEach { card ->
                Column(Modifier.fillMaxWidth().riffGlass(GlassRole.Menu, RiffRadius.card).padding(RiffSpace.block),
                    verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
                    Text(card.title, color = White, style = RiffText.sectionHeading)
                    Text(card.body, color = White.copy(alpha = .7f))
                    if (card.action.isNotEmpty()) Text(string(R.string.try_it), color = Pink, modifier = Modifier.clickable {
                        uiHaptic(); close()
                        if (card.action == "links") openLinkSettings(context) else tryWhatsNew(card.action, model)
                    }.padding(vertical = RiffSpace.tight).testTag("whats_new_try_${card.action}"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(string(R.string.not_now), color = White, modifier = Modifier.clickable { uiHaptic(); close() }
                    .padding(RiffSpace.rowGap).testTag("whats_new_close"))
            }
        }
    }
}

/** Feature 13's switch: Android's "Open by default" page for Riff. */
private fun openLinkSettings(context: Context) {
    try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
        android.net.Uri.parse("package:${context.packageName}"))) } catch (_: android.content.ActivityNotFoundException) {}
}
