package com.musicglass.personal

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class UpdateEditionUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var requests = 0
    private fun checker(): UpdateChecker {
        val prefs = context.getSharedPreferences("edition_update_ui", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val body = """[{"tag_name":"v0.30.0","html_url":"https://github.com/example/riff/releases/tag/v0.30.0","assets":[{"name":"riff-0.30.0.apk","size":9000000},{"name":"riff-plus-0.30.0.apk","size":45000000}]}]"""
        return UpdateChecker(prefs, "https://feed.example/releases", listOf("github.com"), "0.30.0",
            BuildIdentity("a".repeat(40), "clean", "b".repeat(64), "release", 36),
            OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build(), FakeClock(), { NetworkConditions(wifi = true, unmetered = true, validated = true) },
            { false }, Dispatchers.Unconfined, {})
    }

    @Test fun aboutOffersTheOtherEditionAndItsSizeAndOffHidesItWithoutARequest() {
        val updates = checker()
        runBlocking { updates.checkNow() }
        assertEquals(1, requests)
        compose.setContent { RiffTheme { AboutSection(ShellModel(), 0.dp, {}, updateChecker = updates) } }
        compose.reach("settings_switch_edition")
        compose.onNodeWithTag("settings_switch_edition").assertHasClickAction()
        compose.onNodeWithText(string(if (Edition.plus) R.string.switch_to_riff else R.string.switch_get_plus)).assertExists()
        val bytes = if (Edition.plus) 9000000L else 45000000L
        val size = android.text.format.Formatter.formatShortFileSize(context, bytes)
        compose.onNodeWithText(size, substring = true).assertExists()
        assertEquals(1, requests)
        compose.reach("settings_check_updates")
        compose.onNodeWithTag("settings_check_updates").performClick()
        compose.waitForIdle()
        assertNull(updates.switchAvailable)
        compose.onNodeWithTag("settings_switch_edition").assertDoesNotExist()
        assertEquals(1, requests)
    }
}
