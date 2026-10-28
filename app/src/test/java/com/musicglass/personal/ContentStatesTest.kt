package com.musicglass.personal

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 28: offline failures offer the downloads; other failures and empty screens keep their own look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentStatesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineOffersTheDownloads() {
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalOpenDownloads provides { opened++ }) { ContentProblem(OFFLINE_MESSAGE, onRetry = {}) }
        }
        compose.onNodeWithTag("content_problem_downloads").performClick()
        assertEquals(1, opened)
    }

    @Test fun otherFailuresDoNot() {
        compose.setContent {
            CompositionLocalProvider(LocalOpenDownloads provides {}) { ContentProblem("YouTube Music refused this.", onRetry = {}) }
        }
        compose.onAllNodesWithTag("content_problem_downloads").assertCountEquals(0)
    }

    @Test fun anEmptyStateCarriesItsAction() {
        var tapped = false
        compose.setContent { EmptyState(Icons.Rounded.Search, "Nothing here", "Try another word", action = "Search" to { tapped = true }) }
        compose.onNodeWithTag("empty_state_action").performClick()
        assert(tapped)
    }
}
