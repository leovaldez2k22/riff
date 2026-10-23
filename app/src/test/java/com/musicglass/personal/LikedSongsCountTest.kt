package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q18-038: the real provider counts Liked music, through the fixture network: no live call, no account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LikedSongsCountTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }

    private fun world(reply: (ProviderWorld.Call) -> ProviderWorld.Reply) = ProviderWorld(context).also { world = it; it.route = reply }

    @Test fun theHeadersNumberIsUsedWithoutReadingFurther() = runBlocking {
        val w = world { call ->
            assertEquals("VLLM", call.browseId)
            ProviderWorld.Reply("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Liked music"}]},
                "secondSubtitle":{"runs":[{"text":"1,234 songs"},{"text":" • "},{"text":"70+ hours"}]}}},
                "contents":${fixtureCollectionPage(1..100, "p2")}}""")
        }
        assertEquals(LibraryCount(1234, true), w.provider.likedSongsCount().getOrThrow())
        assertEquals(1, w.calls.size)
    }

    @Test fun withoutAHeaderNumberEveryPageIsCounted() = runBlocking {
        val w = world { call ->
            ProviderWorld.Reply(when (call.continuation) {
                null -> fixtureCollectionPage(1..100, "p2")
                "p2" -> fixtureCollectionPage(101..200, "p3")
                else -> fixtureCollectionPage(201..237)
            })
        }
        val seen = mutableListOf<Int>()
        assertEquals(LibraryCount(237, true), w.provider.likedSongsCount { seen += it }.getOrThrow())
        assertEquals(listOf(100, 200, 237), seen)
        assertEquals(3, w.calls.size)
    }

    @Test fun anAccountChangeWhileCountingDropsTheCount() = runBlocking {
        lateinit var w: ProviderWorld
        w = world { call ->
            if (call.continuation == "p2") w.provider.signOut()
            ProviderWorld.Reply(if (call.continuation == null) fixtureCollectionPage(1..100, "p2") else fixtureCollectionPage(101..200))
        }
        val seen = mutableListOf<Int>()
        assertTrue(w.provider.likedSongsCount { seen += it }.isFailure)
        assertEquals(listOf(100), seen)
    }

    /** Q18-080: Liked songs reads Liked music, first page and the rest, and is read again after a like changes. */
    @Test fun likedSongsIsLikedMusicAndIsReadAgainAfterAnUnlike() = runBlocking {
        val w = world { call ->
            when {
                call.endpoint.startsWith("like/") -> ProviderWorld.Reply("""{"responseContext":{}}""")
                call.continuation == null -> { assertEquals("VLLM", call.browseId); ProviderWorld.Reply(fixtureCollectionPage(1..100, "p2")) }
                else -> ProviderWorld.Reply(fixtureCollectionPage(101..120))
            }
        }
        val first = w.provider.libraryCatalog("Favorites", null).getOrThrow()
        assertEquals(100, first.cards.size)
        assertEquals("p2", first.next)
        assertEquals(20, w.provider.libraryCatalog("Favorites", "p2").getOrThrow().cards.size)
        assertTrue(w.calls.none { it.browseId == "FEmusic_liked_videos" })
        w.provider.libraryCatalog("Favorites", null).getOrThrow()
        assertEquals(2, w.calls.size)
        w.provider.setSongLike(first.cards.first().videoId!!, false).getOrThrow()
        w.provider.libraryCatalog("Favorites", null).getOrThrow()
        assertEquals(2, w.calls.count { it.browseId == "VLLM" && it.continuation == null })
    }
}
