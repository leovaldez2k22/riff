package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.cast.Cast
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/*
 * Feature 19: the speakers and TVs on this Wi-Fi, under the player's output pill. Choosing one starts a Cast session;
 * the playback service then moves playback there (CastSync).
 */

/** Cast works only with Google Play services; without them the pill opens the system's output switcher as before. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun castAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS &&
        Cast.getSingletonInstance(context).let { cast -> if (cast.needsInitialization()) cast.initialize(); cast.castContextLoadFailure == null }

/** The speaker playing now, or null when nothing is cast. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun castingTo(context: Context): String? =
    Cast.getSingletonInstance(context).takeUnless { it.needsInitialization() }?.currentCastSession?.takeIf { it.isConnected }?.castDevice?.friendlyName

private val castSelector = MediaRouteSelector.Builder()
    .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()

/** The Cast devices found while the picker is open. Discovery runs only then: it costs the battery. */
@Composable
private fun castRoutes(): List<MediaRouter.RouteInfo> {
    val context = LocalContext.current
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    DisposableEffect(context) {
        val router = MediaRouter.getInstance(context)
        fun refresh() { routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(castSelector) } }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(castSelector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
        onDispose { router.removeCallback(callback) }
    }
    return routes
}

/**
 * Speakers and TVs: owner, 10 November 2026, a frosted pop-out from the output button like the player's ⋯ menu, not a
 * grey sheet. Discovery runs only while it is open (its rows are composed only then).
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun CastPicker(expanded: Boolean, anchor: androidx.compose.ui.unit.IntRect?, casting: String?, onPhoneOutputs: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    PlayerActionMenu(expanded, onDismiss, anchor, paneTitle = string(R.string.speakers_and_tvs)) {
        val routes = castRoutes()
        Text(string(R.string.speakers_and_tvs), color = White.copy(alpha = .55f), style = RiffMenu.text,
            modifier = Modifier.padding(horizontal = RiffMenu.edge, vertical = RiffSpace.item))
        OutputRow(Icons.Rounded.Smartphone, string(R.string.this_phone), string(R.string.phone_bluetooth_and_headphones), "cast_phone") {
            if (casting != null) Cast.getSingletonInstance(context).endCurrentSession(true)
            onDismiss(); onPhoneOutputs()
        }
        routes.forEach { route ->
            val playing = route.isSelected && casting != null
            OutputRow(if (playing) Icons.Rounded.CastConnected else if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV) Icons.Rounded.Tv else Icons.Rounded.Speaker,
                route.name, if (playing) string(R.string.casting_to_x, route.name) else route.description.orEmpty(), "cast_route") {
                if (!playing) MediaRouter.getInstance(context).selectRoute(route)
                onDismiss()
            }
        }
        if (routes.isEmpty()) Text(string(R.string.looking_for_speakers), color = White.copy(alpha = .55f), style = RiffMenu.text,
            modifier = Modifier.padding(horizontal = RiffMenu.edge, vertical = RiffSpace.rowGap).testTag("cast_looking"))
        if (casting != null) OutputRow(Icons.Rounded.CastConnected, string(R.string.stop_casting), casting, "cast_stop") {
            Cast.getSingletonInstance(context).endCurrentSession(true); onDismiss()
        }
    }
}

/** A pop-out row as in the ⋯ menu, with the output's detail under its name. */
@Composable
private fun OutputRow(icon: ImageVector, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RiffMenu.row).clickable(onClick = hapticClick(onClick))
        .padding(horizontal = RiffMenu.edge, vertical = RiffSpace.item).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffMenu.iconGap)) {
        Icon(icon, contentDescription = null, tint = White, modifier = Modifier.size(RiffMenu.icon))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, style = RiffMenu.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = White.copy(alpha = .55f), style = RiffText.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
