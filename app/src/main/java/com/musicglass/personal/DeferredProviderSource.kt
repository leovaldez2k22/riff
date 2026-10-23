package com.musicglass.personal

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import java.io.IOException

/** Resolve on demand; the complete album/restored queue is visible immediately. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class DeferredProviderSource(private val item: MediaItem,
    private val resolve: suspend ()->MediaSource): CompositeMediaSource<Unit>() {
    private var scope: CoroutineScope?=null
    private var child: MediaSource?=null
    private var failure: IOException?=null
    override fun getMediaItem()=child?.mediaItem ?: item
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        failure=null
        scope=CoroutineScope(SupervisorJob()+Handler(Looper.myLooper()!!).asCoroutineDispatcher()).also {s->s.launch {
            try {val source=resolve();ensureActive();child=source;prepareChildSource(Unit,source)}
            // Q18-103: only a release cancels this scope; a deadline inside the lookup is its failure, never silence.
            catch(e:CancellationException){ensureActive();failure=IOException("The selected YouTube track could not be prepared",e)}
            // Keep the cause: recovery (classifyFault) reads it to tell an outage from a bad track.
            catch(e:Exception){failure=IOException("The selected YouTube track could not be prepared",e)}
        }}
    }
    override fun onChildSourceInfoRefreshed(id:Unit,source:MediaSource,timeline:Timeline)=refreshSourceInfo(timeline)
    override fun createPeriod(id:MediaSource.MediaPeriodId,allocator:Allocator,startPositionUs:Long):MediaPeriod =
        checkNotNull(child).createPeriod(id,allocator,startPositionUs)
    override fun releasePeriod(mediaPeriod:MediaPeriod) {child?.releasePeriod(mediaPeriod)}
    override fun maybeThrowSourceInfoRefreshError() {failure?.let {throw it};super.maybeThrowSourceInfoRefreshError()}
    override fun releaseSourceInternal() {scope?.cancel();scope=null;super.releaseSourceInternal();child=null;failure=null}
}
