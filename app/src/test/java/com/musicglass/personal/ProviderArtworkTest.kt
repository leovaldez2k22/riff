package com.musicglass.personal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderArtworkTest {
    @Test fun choosesLargestThumbnailRegardlessOfOrderAndRejectsImpostorHosts() {
        val root = Json.parseToJsonElement("""{"thumbnails":[
            {"url":"https://i.ytimg.com/large.jpg","width":544,"height":544},
            {"url":"https://i.ytimg.com/tiny.jpg","width":60,"height":60},
            {"url":"https://ytimg.com.evil.example/fake.jpg","width":4096,"height":4096},
            {"url":"https://lh3.googleusercontent.com/medium","width":226,"height":226}] }""")
        assertEquals("https://i.ytimg.com/large.jpg", providerThumbnail(root))
    }
    @Test fun upgradesSavedGoogleCoverWithoutLosingCropOrQuery() {
        assertEquals("https://lh3.googleusercontent.com/cover=w1024-h1024-l90-rj?x=1",
            highQualityArtworkUrl("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj?x=1"))
        assertEquals("https://yt3.googleusercontent.com/avatar=s1024-c",
            highQualityArtworkUrl("https://yt3.googleusercontent.com/avatar=s88-c"))
    }
    @Test fun removesTinyYoutubeRenditionParameters() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            highQualityArtworkUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=tiny&v=1"))
    }
    @Test fun preservesLargeCoversAndUnrelatedOrLocalImages() {
        for (url in listOf("https://lh3.googleusercontent.com/cover=w2048-h2048-l90-rj",
            "android.resource://com.musicglass.personal/123", "demo:2",
            "https://googleusercontent.com.evil.example/cover=w60-h60",
            "https://user:secret@lh3.googleusercontent.com/cover=w60-h60")) {
            assertEquals(url, highQualityArtworkUrl(url))
        }
    }
}