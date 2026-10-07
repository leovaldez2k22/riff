package com.musicglass.personal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class CollectionSortMenuTest {
    @get:Rule val compose=createComposeRule()
    @Test fun playlistOffersAllSevenChoicesAndExplainsMissingVotesAndDates() {
        compose.setContent {CollectionTools("playlist","",{},CollectionSort.Original,{},false,playlist=true)}
        compose.onNodeWithTag("collection_sort").performClick()
        listOf("topvoted","original","newest","oldest","title","artist","album").forEach {
            compose.onNodeWithTag("collection_sort_$it").assertExists()
        }
        compose.onNodeWithTag("collection_sort_topvoted").assertIsNotEnabled()
        compose.onNodeWithTag("collection_sort_newest").assertIsNotEnabled()
        compose.onNodeWithTag("collection_sort_album").assertIsEnabled()
    }
    @Test fun providerCommandsEnableVoteAndDateChoices() {
        compose.setContent {CollectionTools("playlist","",{},CollectionSort.Original,{},false,playlist=true,
            serverSorts=setOf(CollectionSort.TopVoted,CollectionSort.Newest,CollectionSort.Oldest))}
        compose.onNodeWithTag("collection_sort").performClick()
        listOf("topvoted","newest","oldest").forEach {compose.onNodeWithTag("collection_sort_$it").assertIsEnabled()}
    }
}
