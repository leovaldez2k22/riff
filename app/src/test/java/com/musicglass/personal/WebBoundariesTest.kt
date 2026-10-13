package com.musicglass.personal

import android.app.Application
import android.content.Intent
import android.webkit.SslErrorHandler
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.File

/**
 * SEC01 and SEC02: the addresses and callers an attacker would try are refused, nothing they send is
 * executed, and what a legitimate caller needs still works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebBoundariesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** Addresses made to look like Google's or YouTube's. None of them is. */
    private val lookalikes = listOf(
        "https://accounts.google.com.attacker.example/signin", "https://google.com.attacker.example/",
        "https://attacker.example/accounts.google.com", "https://attacker.example/?next=https://accounts.google.com",
        "https://notgoogle.com/", "https://xn--ggle-0nda.com/", "https://googlе.com/", "https://google.com.",
        "https://accounts.google.com@attacker.example/", "https://user:pw@accounts.google.com/",
        "https://attacker.example\\@accounts.google.com/", "https://accounts.google.com\\.attacker.example/",
        "https://attacker.example#@accounts.google.com/", "https://accounts.google.com:8443/", "https://accounts.google.com:80/",
        "https://accounts.google.com%2eattacker.example/", "https://accounts.google.com%00.attacker.example/",
        "https:accounts.google.com", "https:///accounts.google.com", "https://",
        "http://accounts.google.com/", "HTTP://accounts.google.com/", "ftp://accounts.google.com/",
        "file:///sdcard/picture.jpg", "file://accounts.google.com/etc/hosts", "content://media/external/audio/media/1",
        "content://com.musicglass.personal.provider/x", "javascript:alert(1)", "data:text/html,<script>1</script>",
        "blob:https://accounts.google.com/1", "about:blank", "intent://accounts.google.com/#Intent;scheme=https;end",
        "market://details?id=com.attacker", "https://accounts.google.com/\nhttps://attacker.example/", " https://accounts.google.com/",
        "https://accounts.google.com/ ", "https://accounts.google.com/\u0000", "", "https://" + "a".repeat(5_000) + ".google.com/",
    )

    @Test fun lookalikeAddressesAreRefusedForSignInAndRealOnesAreNot() {
        for (address in lookalikes) assertFalse(address, allowedSignInUrl(address))
        for (address in listOf("https://accounts.google.com/ServiceLogin?service=youtube", "https://music.youtube.com/",
            "https://www.youtube.com/signin?action_handle_signin=true", "https://lh3.googleusercontent.com/a/x=s96",
            "https://ssl.gstatic.com/accounts/ui/avatar_2x.png", "https://accounts.google.com:443/", "https://ACCOUNTS.GOOGLE.COM/",
            signInStartUrl(false), signInStartUrl(true), signInMusicUrl("1")))
            assertTrue(address, allowedSignInUrl(address))
    }

    @Test fun anAppLinkIsNeverExecutedAndItsFallbackMustBeAnApprovedHttpsAddress() {
        fun link(fallback: String, scheme: String = "https", host: String = "accounts.google.com") =
            "intent://$host/setup#Intent;scheme=$scheme;package=com.attacker;component=com.attacker/.Steal;" +
                "S.browser_fallback_url=${android.net.Uri.encode(fallback)};end"
        assertEquals("https://accounts.google.com/done", signInWebFallback(link("https://accounts.google.com/done")))
        for (bad in lookalikes.filter { it.isNotEmpty() }) {
            // The fallback is refused; what is left is the link's own address, and only if that is approved.
            val result = signInWebFallback(link(bad, host = "attacker.example"))
            assertNull("$bad -> $result", result)
        }
        // The link's own address, when there is no fallback, is held to the same rule.
        assertNull(signInWebFallback("intent://attacker.example/#Intent;scheme=https;end"))
        assertNull(signInWebFallback("intent://accounts.google.com/#Intent;scheme=http;end"))
        assertNull(signInWebFallback("intent://x/#Intent;scheme=file;end"))
        assertNull(signInWebFallback("intent:#Intent;action=android.intent.action.CALL;end"))
        assertNull(signInWebFallback("https://accounts.google.com/"))
        // Looking at the link started nothing.
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun onlyAnHttpsPageIsHandedToTheBrowserAndNeverAComponentOrAnotherScheme() {
        for (address in lookalikes.filter { "google.com" !in it || !it.startsWith("https://") } + listOf("tel:112", "sms:1?body=x", "geo:0,0",
            "https://localhost/", "https://intranet/", "https://127.0.0.1@attacker.example/", "myapp://open?x=1")) {
            if (httpsAddress(address) != null) continue
            assertFalse(address, openWebPage(app, address))
            assertNull(address, shadowOf(app).nextStartedActivity)
        }
        for (never in listOf("tel:112", "file:///sdcard/a", "content://x/y", "intent://x#Intent;end", "javascript:1", "http://commons.wikimedia.org/", "myapp://x",
            "https://user@commons.wikimedia.org/", "https://commons.wikimedia.org\\@attacker.example/", "https://localhost/", "https://commons.wikimedia.org:8443/"))
            assertNull(never, httpsAddress(never))
        assertTrue(openWebPage(app, "https://commons.wikimedia.org/wiki/File:Example.jpg"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action); assertEquals("https", started.data!!.scheme)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(started.component); assertNull(started.`package`); assertNull(started.extras)
        // With a list of domains, another host is refused even though it is a proper HTTPS address.
        assertFalse(openWebPage(app, "https://attacker.example/licence", listOf("creativecommons.org")))
        assertTrue(openWebPage(app, "https://creativecommons.org/licenses/by/4.0/", listOf("creativecommons.org")))
    }

    @Test fun aCertificateErrorAlwaysEndsTheLoad() {
        val handler = Shadow.newInstanceOf(SslErrorHandler::class.java)
        object : StrictWebViewClient() {}.onReceivedSslError(null, handler, null)
        assertTrue(shadowOf(handler).wasCancelCalled()); assertFalse(shadowOf(handler).wasProceedCalled())
    }

    /** No client in the app is built on the plain base class, and nothing anywhere lets a certificate error pass. */
    @Test fun everyBrowserInTheCodeUsesTheStrictClientAndNoneAddsABridgeOrOpensFiles() {
        val sources = File("src/main/java/com/musicglass/personal").listFiles { f -> f.extension == "kt" }!!
        val all = sources.associate { it.name to it.readText() }
        val browsers = all.filter { "WebView(" in it.value && it.key != "WebBoundaries.kt" }
        assertEquals(setOf("AccountScreen.kt", "AndroidTokenRuntime.kt", "InlineYouTubeVideo.kt"), browsers.keys)
        for ((name, text) in all) {
            assertFalse(name, Regex("handler\\??\\.proceed\\(").containsMatchIn(text))
            if (name != "WebBoundaries.kt") assertFalse(name, "onReceivedSslError" in text || "SslErrorHandler" in text)
            assertFalse(name, "addJavascriptInterface" in text)
            assertFalse(name, Regex("allowFileAccess\\s*=\\s*true|allowFileAccessFromFileURLs\\s*=\\s*true|allowUniversalAccessFromFileURLs\\s*=\\s*true|setAllowFileAccess\\(true").containsMatchIn(text))
            assertFalse(name, "MIXED_CONTENT_ALWAYS_ALLOW" in text)
            if (name != "WebBoundaries.kt") assertFalse(name, Regex("object\\s*:\\s*WebViewClient\\(\\)").containsMatchIn(text))
        }
        for ((name, text) in browsers) {
            assertTrue(name, "StrictWebViewClient()" in text)
            assertTrue(name, Regex("allowFileAccess\\s*=\\s*false").containsMatchIn(text))
        }
        // Content addresses stay open in one browser only, for the system picker's result (see security-review.md).
        assertEquals(listOf("AccountScreen.kt"), all.filter { Regex("allowContentAccess\\s*=\\s*true").containsMatchIn(it.value) }.keys.toList())
    }

    @Test fun onlyAVideoIdentifierIsWrittenIntoThePlayerAddress() {
        assertTrue(isVideoId("dQw4w9WgXcQ")); assertTrue(isVideoId("a-b_c-d_e-0"))
        for (bad in listOf("", "dQw4w9WgXc", "dQw4w9WgXcQQ", "dQw4w9WgXc?", "../../../x1", "x?autoplay=", "dQw4w9WgXc\u0000", "dQw4w9Wg/cQ", "dQw4w9WgXc#", "dQw4w9Wg%2F"))
            assertFalse(bad, isVideoId(bad))
    }

    @Test fun aLocalTrackMayOnlyNameItsOwnRowInTheMediaStore() {
        assertEquals("content://media/external/audio/media/42", localAudioUri("content://media/external/audio/media/42", 42).toString())
        for ((text, id) in listOf("content://media/external/audio/media/43" to 42L, "content://media/external/audio/media/42/../43" to 42L,
            "content://media/external/images/media/42" to 42L, "content://com.attacker.provider/audio/media/42" to 42L,
            "file:///data/user/0/com.musicglass.personal/shared_prefs/provider_session.xml" to 42L, "content://media/external/audio/media/-1" to -1L,
            "content://media/external/audio/media/42?x=1" to 42L, "CONTENT://media/external/audio/media/42" to 42L, "" to 42L, null to 42L))
            assertNull("$text", localAudioUri(text, id))
    }

    @Test fun onlyThisAppWithItsOwnUserIdIsTreatedAsItself() {
        assertTrue(isOwnController("com.musicglass.personal", 10123, "com.musicglass.personal", 10123))
        // A package name alone is a claim. So is a user id alone.
        assertFalse(isOwnController("com.musicglass.personal", 10999, "com.musicglass.personal", 10123))
        assertFalse(isOwnController("com.attacker", 10123, "com.musicglass.personal", 10123))
        assertFalse(isOwnController("com.musicglass.personal.benchmark", 10123, "com.musicglass.personal", 10123))
        assertFalse(isOwnController(null, 10123, "com.musicglass.personal", 10123))
        assertFalse(isOwnController("android.media.session.MediaController", 1000, "com.musicglass.personal", 10123))
    }

    @Test fun anotherAppsControllerKeepsTransportAndLosesTheQueue() {
        val commands = foreignPlayerCommands()
        for (kept in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA))
            assertTrue("command $kept", commands.contains(kept))
        for (gone in foreignControllerDenied) assertFalse("command $gone", commands.contains(gone))
        assertEquals(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.size() - foreignControllerDenied.size, commands.size())
        // The service applies both rules, and refuses the app's own commands to anyone else before reading them.
        val service = File("src/main/java/com/musicglass/personal/MusicPlaybackService.kt").readText()
        val connect = service.substringAfter("override fun onConnect").substringBefore("override fun onCustomCommand")
        assertTrue(connect.contains("isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid())"))
        assertTrue(connect.contains("setAvailablePlayerCommands(foreignPlayerCommands())"))
        val custom = service.substringAfter("override fun onCustomCommand").substringBefore("customCommand.customAction")
        assertTrue(custom.contains("if (!isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid())) {"))
        assertTrue(custom.contains("SessionError.ERROR_PERMISSION_DENIED"))
    }
}
