package com.musicglass.personal

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient

/** Builds an extractor the same way YouTubeMusicProvider does (Kotlin defaults aren't visible to Java). */
internal fun providerShapedExtractor(http: HttpClient): InnerTubeExtractor {
    val innerTube = InnerTube(http)
    val store = RemotePlayerConfigStore(http, object : PlayerConfigRepository {
        override val enabled = false
        override val sourceUrl = ""
        override val defaultSourceUrl = ""
        override var cachedJson = ""
        override var cachedAtMs = 0L
        override var cachedSourceUrl = ""
        override var cachedEtag = ""
    })
    return InnerTubeExtractor(configParser = YtConfigParserImpl(http, innerTube, store),
        cipherService = YouTubeCipherService(http, store), innerTube = innerTube)
}
