package com.musicglass.personal

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneEqualizerUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aPhoneWithoutASystemPanelExplainsWhyTheActionIsUnavailable() {
        compose.setContent { RiffTheme { PhoneEqualizerTile(Modifier) } }
        compose.onNodeWithTag("settings_phone_equalizer")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText(string(R.string.eq_phone_none)).assertExists()
    }

    @Test fun anAvailableSystemPanelMakesThePhoneControlsActionClickable() {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "fixture.equalizer"
                name = "fixture.equalizer.Panel"
                exported = true
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(phoneEqualizerIntent(context), info)
        compose.setContent { RiffTheme { PhoneEqualizerTile(Modifier) } }
        compose.onNodeWithTag("settings_phone_equalizer").assertHasClickAction()
        compose.onNodeWithText(string(R.string.phone_equalizer_open)).assertExists()
    }
}
