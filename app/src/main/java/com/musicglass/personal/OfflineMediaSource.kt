package com.musicglass.personal

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File

/** Existing WebM downloads can lack usable Cues. Supply a precise cluster index on the loader thread. */
@androidx.annotation.OptIn(markerClass=[UnstableApi::class])
internal fun offlineMediaSource(context:Context,item:MediaItem):MediaSource {
    val file=File(requireNotNull(item.localConfiguration?.uri?.path))
    val standard=DefaultExtractorsFactory()
    val extractors=object:ExtractorsFactory {
        override fun createExtractors():Array<Extractor> = wrap(standard.createExtractors())
        override fun createExtractors(uri:Uri,headers:Map<String,List<String>>):Array<Extractor> = wrap(standard.createExtractors(uri,headers))
        private fun wrap(list:Array<Extractor>)=list.map {extractor ->
            if(extractor is MatroskaExtractor) object:Extractor by extractor {
                override fun init(output:ExtractorOutput) {
                    extractor.init(object:ExtractorOutput by output {
                        override fun seekMap(map:SeekMap) {
                            val index=if(!map.isSeekable) WebmSeekIndex.read(file) else null
                            output.seekMap(if(index==null) map else object:SeekMap {
                                override fun isSeekable()=true
                                override fun getDurationUs()=index.durationUs
                                override fun getSeekPoints(timeUs:Long):SeekMap.SeekPoints {
                                    val result=index.timesUs.binarySearch(timeUs)
                                    val i=(if(result>=0) result else -result-2).coerceIn(0,index.timesUs.lastIndex)
                                    val first=SeekPoint(index.timesUs[i],index.positions[i])
                                    val next=if(i<index.timesUs.lastIndex && first.timeUs<timeUs) SeekPoint(index.timesUs[i+1],index.positions[i+1]) else first
                                    return SeekMap.SeekPoints(first,next)
                                }
                            })
                        }
                    })
                }
            } else extractor
        }.toTypedArray()
    }
    return DefaultMediaSourceFactory(context,extractors).createMediaSource(item)
}
