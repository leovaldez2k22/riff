package com.musicglass.personal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * The rules for what the app's browsers may load and what it hands to other apps (Q15). They are plain
 * functions so that each can be tested with the addresses an attacker would try.
 */

/** Longest address looked at. */
internal const val MAX_WEB_ADDRESS = 4_096

/**
 * [url] as an HTTPS address on one of [domains] or a real subdomain of one, or null. Refused: any other
 * scheme, a user name or password before the host, a port other than 443, a backslash or a control
 * character anywhere (browsers and parsers have disagreed about both), and an over-long address.
 */
internal fun httpsAddressOn(url: String?, domains: Collection<String>): Uri? {
    val uri = httpsAddress(url) ?: return null
    val host = uri.host!!.lowercase()
    return uri.takeIf { domains.any { host == it || host.endsWith(".$it") } }
}

/** [url] as a well-formed HTTPS address on any public-looking host, or null. The same refusals apply. */
internal fun httpsAddress(url: String?): Uri? {
    if (url == null || url.length > MAX_WEB_ADDRESS) return null
    if (url.any { it == '\\' || it <= ' ' || it == '\u007f' }) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || !uri.isHierarchical) return null
    val host = uri.host?.lowercase().orEmpty()
    if (host.isEmpty() || '.' !in host || host.startsWith(".") || host.endsWith(".")) return null
    if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }) return null
    if (uri.userInfo != null || '@' in uri.encodedAuthority.orEmpty()) return null
    if (uri.port != -1 && uri.port != 443) return null
    return uri
}

/**
 * Opens a web page in the user's browser. Only an HTTPS address that passes [httpsAddress] (and [domains],
 * when given) is opened, as a plain view of a browsable page: the address comes from a provider's answer or
 * a stored file, so it is never trusted to name a scheme, an app or a component. False when nothing opened.
 */
internal fun openWebPage(context: Context, url: String?, domains: Collection<String>? = null): Boolean {
    val uri = (if (domains == null) httpsAddress(url) else httpsAddressOn(url, domains)) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
}

/** A YouTube video identifier and nothing else: it is written into an address. */
internal fun isVideoId(id: String) = id.length == 11 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

/**
 * The base of every WebViewClient in the app. A certificate error always ends the load: the default does
 * the same, and writing it down means a subclass cannot change it by accident and a test can hold it.
 */
internal open class StrictWebViewClient : WebViewClient() {
    final override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) = handler.cancel()
}

/** The one MediaStore address a local track may have: the audio row its own number names. */
internal fun localAudioUri(text: String?, id: Long): Uri? =
    if (id >= 0 && text == "content://media/external/audio/media/$id") Uri.parse(text) else null

/**
 * Whether a media controller is this app itself. Both the package name and the user id must match: a
 * package name alone is what the other side says about itself.
 */
internal fun isOwnController(packageName: String?, uid: Int, ownPackage: String, ownUid: Int) =
    packageName == ownPackage && uid == ownUid

/**
 * Player commands another app's controller is not given. Transport stays: play, pause, seek, next, previous,
 * stop, speed, repeat, shuffle, reading the queue and the metadata. What goes is rewriting the queue from
 * outside. The system's own controllers (the notification, the lock screen, Bluetooth, the car) need none of
 * these.
 */
internal val foreignControllerDenied = intArrayOf(
    Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_SET_PLAYLIST_METADATA)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun foreignPlayerCommands(defaults: Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS): Player.Commands =
    defaults.buildUpon().removeAll(*foreignControllerDenied).build()
