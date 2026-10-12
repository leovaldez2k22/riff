package com.musicglass.personal

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Q10: a collection that is not whole says so on screen, and what means "all of it" cannot be pressed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class CollectionTruncationUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private var world:ProviderWorld?=null
    @After fun close() {world?.close()}
    private val notices=mutableListOf<String>()
    /** Shows what is playing (nothing); any request to play would be a defect here. */
    private val gateway=Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader,arrayOf(PlaybackGateway::class.java)) {self,method,args->
        when(method.name) {
            "getState"->PlaybackUiState();"getPositionMs","currentPositionMs"->0L
            "equals"->self===args[0];"hashCode"->System.identityHashCode(self);"toString"->"fixture gateway"
            else->throw AssertionError("Unexpected playback call ${method.name}")
        }
    } as PlaybackGateway
    private fun cards(count:Int)=(1..count).map {ProviderHomeCard("Row $it","Fixture Artist",null,videoId=fixtureVideoId(it),setVideoId="row-set-$it")}
    private fun show(page:CatalogPage) {
        val provider=ProviderWorld(context).also {world=it}.provider
        val model=ShellModel().apply {openCollection("VLPLfixtureOwned0000000001","Fixture list")}
        compose.setContent {ImmersiveCollectionDetail(model,0.dp,page,provider,gateway,{notices+=it})}
        compose.waitForIdle()
    }
    private fun reach(tag:String)=compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))

    private fun truncated(cut:CollectionTruncation) {
        val rows=cards(3)
        show(CatalogPage(rows,title="Fixture list",albumTracks=rows,editable=true,truncation=cut))
        listOf("album_play","album_shuffle","collection_download_all").forEach {reach(it);compose.onNodeWithTag(it).assertIsNotEnabled()}
        reach("collection_truncated");compose.onNodeWithTag("collection_truncated").assertTextEquals(cut.label)
        assertEquals(emptyList<String>(),notices)
    }
    @Test fun aCollectionCutAtTheLimitIsLabelledAndItsWholeCollectionActionsAreDisabled()=truncated(CollectionTruncation.Limit)
    @Test fun aCollectionTheProviderStoppedSendingIsLabelledAndItsWholeCollectionActionsAreDisabled()=truncated(CollectionTruncation.RepeatedPage)
    @Test fun aWholeCollectionHasNoSuchLabelAndItsActionsCanBePressed() {
        val rows=cards(3)
        show(CatalogPage(rows,title="Fixture list",albumTracks=rows,editable=true))
        listOf("album_play","album_shuffle","collection_download_all").forEach {reach(it);compose.onNodeWithTag(it).assertIsEnabled()}
        assertEquals(0,compose.onAllNodesWithTag("collection_truncated").fetchSemanticsNodes().size)
    }
}
