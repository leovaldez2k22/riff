package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.util.Log
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.map.Mapper
import coil3.request.Options
import okhttp3.OkHttpClient
import coil3.network.HttpException
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/** One cached artwork pipeline; network support does not depend on service-loader packaging. */
internal fun configureArtworkLoader(context: Context) {
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(context.applicationContext)
            .components {
                add(object : Mapper<String, String> {
                    override fun map(data: String, options: Options) = highQualityArtworkUrl(data)
                })
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder().addInterceptor { chain ->
                        val request = chain.request()
                        val response = chain.proceed(request)
                        // Some uploads have no max-resolution cover; keep their standard cover usable.
                        if (response.code == 404 && request.url.host == "i.ytimg.com" &&
                            request.url.encodedPath.endsWith("/maxresdefault.jpg") ||
                            response.code == 404 && request.url.host == "i.ytimg.com" &&
                            request.url.encodedPath.endsWith("/maxresdefault.webp")) {
                            response.close()
                            chain.proceed(request.newBuilder().url(request.url.newBuilder()
                                .encodedPath(request.url.encodedPath.replace("/maxresdefault.", "/hqdefault."))
                                .build()).build())
                        } else response
                    }.build()
                }))
            }
            .eventListenerFactory {
                object : EventListener() {
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        val host = (request.data as? String)?.let { Uri.parse(it).host }
                        val causes = generateSequence(result.throwable) { it.cause }.take(4)
                            .joinToString(">") { it.javaClass.simpleName }
                        val status = (result.throwable as? HttpException)?.response?.code
                        // Never log artwork URLs, request headers, account data, or exception messages.
                        Log.w("RiffArtwork", "load_failed host=$host causes=$causes http=$status")
                    }
                }
            }
            .build()
    }
}