package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/** A late decoder must never replay an earlier part of the outgoing song after handoff. */
internal fun crossfadeTailAligned(mainPosition: Long, tailPosition: Long): Boolean =
    abs(mainPosition - tailPosition) <= 120L

/** Align well inside the audible cutoff, leaving room for decoder timestamp jitter at handoff. */
internal class CrossfadeTailSynchronizer {
    private var direction=0
    private var alignedSince:Long?=null
    fun update(difference:Long,now:Long,settled:Boolean,playing:Boolean):Float {
        if(!settled || !playing) {direction=0;alignedSince=null;return 1f}
        if(direction==1 && difference<=20 || direction == -1 && difference>=-20) direction=0
        if(direction==0 && abs(difference)>80) direction=if(difference>0) 1 else -1
        if(direction==0 && abs(difference)<=80) {
            if(alignedSince==null) alignedSince=now
        } else alignedSince=null
        // AudioTrack may already hold hundreds of milliseconds at the previous speed.
        // Gentle correction keeps that delayed response inside the alignment margin.
        return when(direction) {1->1.05f;-1->.95f;else->1f}
    }
    fun ready(now:Long):Boolean=alignedSince?.let {now-it>=200}==true
}

/** Once the audible tail loses continuity, it stays silent for the rest of this blend. */
internal class CrossfadeTailContinuity {
    var ended = false; private set
    fun audible(playing: Boolean): Boolean {
        if (!playing) ended = true
        return !ended
    }
}

/**
 * The handover from the main player's copy of the outgoing song to the tail's. Each volume reaches its AudioTrack on its
 * own player's thread, so a swap in one step could leave both copies audible for a moment, the song 6 dB louder, when
 * the tail's landed first (owner, 31 Oct: the previous song jumped in volume during Automix, whose beat model loads the
 * CPU). In [HANDOFF_STEPS] linear steps the two always add up to the level, and a late write is off by one step at most.
 */
internal const val HANDOFF_STEPS = 6
internal const val HANDOFF_STEP_MS = 16L
/** (main, tail) after [step] of [HANDOFF_STEPS]: the same audio on both, so the gains sum linearly to [base]. */
internal fun handoffLevels(step: Int, base: Float): Pair<Float, Float> {
    val x = step.coerceIn(0, HANDOFF_STEPS).toFloat() / HANDOFF_STEPS
    return base * (1 - x) to base * x
}

internal const val CROSSFADE_PREFS = "crossfade"
internal fun crossfadeSeconds(value: Int) = value.coerceIn(1, 12)
/** Automix stage 1 before the choice below: the "Smart crossfade" switch. Still read, for settings saved then. */
internal const val SMART_CROSSFADE_KEY = "smart"

/**
 * How songs blend once Crossfade is on (plan, Feature 20: Settings → Sound → Crossfade / Smart / Automix (beta)).
 * Smart is stage 1, from each song's own end and start; Automix adds stage 2, the blend lined up on the beat.
 */
internal enum class BlendMode(val wire: String) { Crossfade("crossfade"), Smart("smart"), Automix("automix") }
internal const val BLEND_MODE_KEY = "mode"

/** The stored choice; before it existed the "Smart crossfade" switch was the choice between the first two. */
internal fun blendMode(wire: String?, smart: Boolean): BlendMode =
    BlendMode.entries.firstOrNull { it.wire == wire } ?: if (smart) BlendMode.Smart else BlendMode.Crossfade
internal fun blendMode(prefs: SharedPreferences): BlendMode = blendMode(prefs.getString(BLEND_MODE_KEY, null), prefs.getBoolean(SMART_CROSSFADE_KEY, false))

internal fun crossfadeSkipsItem(item:MediaItem):Boolean {
    val extras=item.mediaMetadata.extras
    // Audio items also carry videoUri=null. Presence of the key does not mean there is a video.
    return extras?.getBoolean("live")==true || extras?.getBoolean("video")==true ||
        !extras?.getString("videoUri").isNullOrBlank() || extras?.getBoolean("music",true)==false ||
        !(item.mediaId.startsWith("yt:") || item.mediaId.startsWith("local:"))
}

/** Settings → Audio → Crossfade. Off by default. */
internal class CrossfadeSettings(context: Context) {
    companion object {
        @Volatile private var instance:CrossfadeSettings?=null
        fun get(context:Context)=instance?:synchronized(this) {instance?:CrossfadeSettings(context.applicationContext).also {instance=it}}
    }
    private val prefs = context.applicationContext.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var seconds by mutableIntStateOf(crossfadeSeconds(prefs.getInt("seconds", 6))); private set
    fun updateEnabled(value: Boolean) { enabled = value; prefs.edit { putBoolean("enabled", value) } }
    fun updateSeconds(value: Int) { seconds = crossfadeSeconds(value); prefs.edit { putInt("seconds", seconds) } }
    var mode by mutableStateOf(blendMode(prefs)); private set
    fun updateMode(value: BlendMode) { mode = value; prefs.edit { putString(BLEND_MODE_KEY, value.wire) } }
    /** Reads the stored values again, after an import wrote them. */
    fun reload() { enabled = prefs.getBoolean("enabled", false); seconds = crossfadeSeconds(prefs.getInt("seconds", 6)); mode = blendMode(prefs) }
}

/** Equal-power curves keep the blend about as loud as either song: (outgoing, incoming). */
internal fun crossfadeGains(progress: Float): Pair<Float, Float> {
    val t = progress.coerceIn(0f, 1f) * (PI / 2).toFloat()
    // Rounding puts the cosine of a quarter turn just below zero; a level is never negative.
    return cos(t).coerceIn(0f, 1f) to sin(t).coerceIn(0f, 1f)
}

/** How long the incoming song takes to come in at an echo-out's cut: short enough to land on its downbeat, without a click. */
internal const val ECHO_ENTRY_MS = 30L

/**
 * Report 1.5, an echo-out: the outgoing player stays at its level (its echo stage cuts the song and lets the echoes
 * ring), and the incoming one comes in at [cut], over [ramp], both as shares of the blend.
 */
internal fun echoGains(progress: Float, cut: Float, ramp: Float): Pair<Float, Float> =
    1f to ((progress - cut) / ramp.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)

/** A single smooth clock for both gains and the visuals, independent of decoder timestamps. */
internal class CrossfadeEnvelope(durationMs:Long,startedMs:Long) {
    private val duration=durationMs.coerceAtLeast(1)
    private var lastTick=startedMs
    private var elapsed=0L
    val progress:Float get()=(elapsed.toDouble()/duration).toFloat().coerceIn(0f,1f)
    fun advance(nowMs:Long,playing:Boolean):Float {
        // A delayed callback must not turn either volume into a large, single-step change.
        val delta=(nowMs-lastTick).coerceIn(0,50)
        lastTick=maxOf(lastTick,nowMs)
        if(playing) elapsed=(elapsed+delta).coerceAtMost(duration)
        return progress
    }
}

/** Player levels for one moment of a blend. Both already include the level the player had before it. */
internal data class CrossfadeLevels(val outgoing: Float, val incoming: Float, val progress: Float)

/**
 * The one holder of the player's level during a blend (I07): which entries are blending, how far, for how
 * long, and the level to put back afterwards. [release] hands that level out once; any later release
 * returns nothing, so the cleanup of a cancelled blend cannot write over a level set since by someone else.
 *
 * Early end: once the outgoing copy stops playing it is silent for the rest of the blend, and the
 * incoming song keeps its own curve. From then on the two no longer add up to constant power.
 */
internal class CrossfadeOwner {
    class Grant internal constructor(val outgoingId: String, val incomingId: String, val durationMs: Long,
        val base: Float, startedMs: Long,
        /** Report 1.5: an echo-out, cut at this share of the blend; null for the equal-power fade. */
        private val echoCut: Float? = null) {
        private val envelope = CrossfadeEnvelope(durationMs, startedMs)
        private val continuity = CrossfadeTailContinuity()
        val progress: Float get() = envelope.progress
        val outgoingEnded: Boolean get() = continuity.ended
        internal fun levels(nowMs: Long, incomingPlaying: Boolean, outgoingPlaying: Boolean): CrossfadeLevels {
            // Decoder startup or buffering pauses the ramp; it never compresses the selected fade.
            val progress = envelope.advance(nowMs, incomingPlaying)
            val (out, incoming) = echoCut?.let { echoGains(progress, it, ECHO_ENTRY_MS.toFloat() / durationMs) } ?: crossfadeGains(progress)
            // A buffered or ended copy cannot become audible again over the next song.
            return CrossfadeLevels(if (continuity.audible(outgoingPlaying)) base * out else 0f, base * incoming, progress)
        }
    }

    var active: Grant? = null; private set

    /** Starts a blend from [base], the player's level now. Refused while another blend holds the level. */
    fun take(outgoingId: String, incomingId: String, durationMs: Long, base: Float, nowMs: Long, echoCut: Float? = null): Grant? {
        if (active != null || !base.isFinite() || durationMs <= 0) return null
        return Grant(outgoingId, incomingId, durationMs, base.coerceIn(0f, 1f), nowMs, echoCut).also { active = it }
    }

    /** The levels to apply now, or null when [grant] no longer holds the level. */
    fun levels(grant: Grant, nowMs: Long, incomingPlaying: Boolean, outgoingPlaying: Boolean): CrossfadeLevels? =
        if (active === grant) grant.levels(nowMs, incomingPlaying, outgoingPlaying) else null

    /** Ends the blend held by [grant] (default: whichever is active) and returns the level to put back, once. */
    fun release(grant: Grant? = active): Float? {
        val held = active ?: return null
        if (grant !== held) return null
        active = null
        return held.base
    }
}

/**
 * Only automatic song changes are blended: not the last song, repeat-one, live radio, videos, songs too
 * short to fade, or while the sleep timer runs (it owns the player volume then).
 */
internal fun shouldStartCrossfade(fadeMs: Long, positionMs: Long, durationMs: Long, playing: Boolean, hasNext: Boolean,
    repeatOne: Boolean, skipItem: Boolean, sleepActive: Boolean): Boolean {
    if (!playing || !hasNext || repeatOne || skipItem || sleepActive || durationMs <= 0) return false
    if (durationMs < fadeMs * 2 + 10_000) return false
    val remaining = durationMs - positionMs
    return remaining in minOf(1_500L,fadeMs/2)..fadeMs
}

internal fun shouldPrepareCrossfade(fadeMs:Long,positionMs:Long,durationMs:Long,playing:Boolean,hasNext:Boolean,
    repeatOne:Boolean,skipItem:Boolean,sleepActive:Boolean):Boolean =
    durationMs-positionMs<=fadeMs+30_000 && shouldStartCrossfade(fadeMs,maxOf(positionMs,durationMs-fadeMs),durationMs,
        playing,hasNext,repeatOne,skipItem,sleepActive)

/**
 * Blends songs with a second, short-lived player: it picks up the end of the current song at the same
 * position, the main player moves on to the next song, and their volumes cross over. The main player
 * (and its media session, queue and notification) is never paused or rebuilt.
 */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class Crossfader(private val context: Context, private val scope: CoroutineScope,
    private val createTail: () -> Pair<ExoPlayer, () -> Unit>, private val sleepActive: () -> Boolean,
    private val tailItem:(MediaItem)->MediaItem={it},private val onBlend:(SongCrossfade?)->Unit={},
    private val clock:()->Long=AndroidAppClocks::monotonicMillis,
    private val wait:suspend (Long)->Unit={delay(it)},
    /** Automix stage 1: the smart blend from the outgoing song to the next, or null for the plain crossfade. */
    private val smart:(MediaItem,MediaItem)->SmartBlend?={_,_->null},
    /** Feature 9, Path A: where the outgoing song's sound ends, so the plain blend finishes there and not in its silence. */
    private val audibleEnd:(MediaItem)->Long?={null},
    /** Sets how far the outgoing (tail) and incoming (main) players' equalizers cut the bass and what is above it. */
    private val shelves:(Shelves,Shelves)->Unit={_,_->},
    /** Report 1.5: arms (or, with null, disarms) the outgoing (tail) player's echo-out. */
    private val echo:(EchoOut?)->Unit={},private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    private var monitor: Job? = null
    private var fade: Job? = null
    private var handledKey: String? = null
    private var mainPlayer:ExoPlayer?=null
    private var handoff=false
    internal val owner=CrossfadeOwner()
    private var visualBlend:SongCrossfade?=null
    private var activeTail:ExoPlayer?=null
    private var expectedTransition:String?=null
    private var expectedSeek:String?=null
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key=="enabled" && !prefs.getBoolean("enabled",false)) cancel()
        // Duration may change after the outgoing decoder has already been prepared.
        if(key=="seconds" && visualBlend==null) {cancel();handledKey=null}
    }
    private val listener=object:Player.Listener {
        override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {
            val expected = expectedTransition
            if (expected != null && mainPlayer?.let(::itemKey) == expected &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) expectedTransition = null
            else if(!handoff) cancel()
            handledKey=null
        }
        override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
            if(!playWhenReady) {cancel();handledKey=null}
        }
        override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
            val target="${newPosition.mediaItemIndex}:${newPosition.mediaItem?.mediaId}"
            if(reason==Player.DISCONTINUITY_REASON_SEEK && expectedSeek==target) expectedSeek=null
            else if(reason==Player.DISCONTINUITY_REASON_SEEK && !handoff) {cancel();handledKey=null}
        }
    }

    private fun itemKey(player: Player) = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}"

    fun start(main: ExoPlayer) {
        monitor?.cancel()
        mainPlayer?.removeListener(listener)
        mainPlayer=main;main.addListener(listener)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        monitor = scope.launch {
            var preloading:Boolean?=null
            while (isActive) {
                wait(if (main.isPlaying) 200 else 1_000)
                val enabled=prefs.getBoolean("enabled",false)
                if(preloading!=enabled) {
                    main.setPreloadConfiguration(if(enabled) ExoPlayer.PreloadConfiguration(12_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
                    preloading=enabled
                }
                // Qualify the member: CoroutineScope.cancel() would kill this monitor forever.
                if(!enabled) {this@Crossfader.cancel();handledKey=null;continue}
                if (fade?.isActive == true) {
                    continue
                }
                val item = main.currentMediaItem ?: continue
                val key = itemKey(main)
                if (key == handledKey) {
                    continue
                }
                val nextIndex=main.nextMediaItemIndex
                val skipNext=nextIndex<0 || crossfadeSkipsItem(main.getMediaItemAt(nextIndex))
                // Automix stage 1: the analysed end and outro move the blend; without them it is the plain one, with the bass swap.
                val blend=if(nextIndex>=0 && !skipNext && blendMode(prefs)!=BlendMode.Crossfade) smart(item,main.getMediaItemAt(nextIndex)) else null
                val plan=blend?.plan?.takeIf {main.duration>0 && it.endMs<=main.duration}
                val fadeMs = plan?.blendMs ?: (crossfadeSeconds(prefs.getInt("seconds", 6)) * 1_000L)
                val endMs = plan?.endMs ?: audibleEnd(item)?.takeIf {main.duration>0 && it<=main.duration} ?: main.duration
                if (shouldPrepareCrossfade(fadeMs, main.currentPosition, endMs, main.isPlaying, main.hasNextMediaItem(),
                        main.repeatMode == Player.REPEAT_MODE_ONE, crossfadeSkipsItem(item) || skipNext, sleepActive())) {
                    handledKey = key
                    fade = launch { blend(main, item, key, fadeMs, endMs, blend != null, plan) }
                }
            }
        }
    }

    /**
     * [endMs] is where the outgoing song's sound ends; [smart] adds the bass swap; [plan], when there is one, says where
     * the next song enters, where the swap goes, the loudness trim and any echo-out.
     */
    private suspend fun blend(main: ExoPlayer, item: MediaItem, key: String, fadeMs: Long, endMs: Long = main.duration,
        smart: Boolean = false, plan: SmartPlan? = null) {
        val entryMs = plan?.incomingStartMs ?: 0L
        // A copy that cannot be built is a skipped blend, not a failure of the service that plays.
        val (tail, releaseTail) = try { createTail() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log("crossfade_skipped reason=tail_unavailable type=${e::class.simpleName}"); return }
        activeTail=tail
        log("crossfade_tail_created")
        // Asked again at every step up to the handoff: the user may turn repeat-one or the sleep timer on at any time.
        fun abandoned()=itemKey(main)!=key || !main.playWhenReady || sleepActive() || main.repeatMode==Player.REPEAT_MODE_ONE
        var grant:CrossfadeOwner.Grant?=null
        try {
            tail.volume = 0f
            tail.setSeekParameters(SeekParameters.EXACT)
            tail.playbackParameters=main.playbackParameters
            tail.setMediaItem(tailItem(item), main.currentPosition)
            tail.playWhenReady = false
            tail.prepare()
            if (withTimeoutOrNull(25_000) {
                    while (tail.playbackState != Player.STATE_READY) {
                        if(abandoned() || tail.playerError!=null) return@withTimeoutOrNull false
                        wait(20)
                    }
                    true
                } != true) {
                log("crossfade_skipped reason=tail_not_ready"); return
            }
            log("crossfade_prepared")
            // Armed now, in the song's own time: the player processes its audio well ahead of what is heard, so arming
            // it at the blend would miss the start of the send.
            plan?.echo?.let(echo)
            var bufferedSince:Long?=null
            while(endMs-main.currentPosition>fadeMs+12_000 || !main.isPlaying) {
                if(abandoned() || main.playerError!=null || main.playbackState==Player.STATE_ENDED) {log("crossfade_skipped reason=left_before_warmup");return}
                // A short network stall is not a user pause. Keep the prepared, muted copy;
                // abandoning it here marks this song handled and loses its eventual crossfade.
                if(!main.isPlaying) {
                    val now=clock()
                    if(bufferedSince==null) {bufferedSince=now;log("crossfade_waiting reason=main_buffering")}
                    if(now-bufferedSince>=15_000) {log("crossfade_skipped reason=main_stalled_before_warmup");return}
                } else bufferedSince=null
                wait(50)
            }
            if (abandoned() || !main.isPlaying) {log("crossfade_skipped reason=not_playing_before_alignment");return}
            // Seek ahead while muted and paused, giving the decoder time to finish the seek.
            // A seek followed immediately by play can report old READY state before it buffers.
            val target=main.currentPosition+750
            tail.seekTo(target)
            if(withTimeoutOrNull(3_000) {
                    while(main.currentPosition<target || tail.playbackState!=Player.STATE_READY) {
                        if(abandoned() || tail.playerError!=null) return@withTimeoutOrNull false
                        wait(10)
                    }
                    true
                } != true) {
                log("crossfade_skipped reason=tail_seek_not_ready ahead=${target-main.currentPosition} tailState=${tail.playbackState} mainState=${main.playbackState}");return
            }
            tail.play()
            // READY/isPlaying may initially use the software clock before AudioTrack timestamps
            // settle. Warm up muted before checking alignment against the playing decoder.
            val clockStarted=clock()
            var lastSyncLog=0L
            if (withTimeoutOrNull(14_000) {
                    var synchronizer=CrossfadeTailSynchronizer()
                    var coarseAligned=false
                    var lagReference:Long?=null
                    var lagSince=0L
                    var settleUntil=clockStarted+2_000
                    while(true) {
                        if(abandoned() || tail.playerError!=null) return@withTimeoutOrNull false
                        val now=clock()
                        val difference=main.currentPosition-tail.currentPosition
                        val parameters=main.playbackParameters
                        // A compressed stream can start hundreds of milliseconds late. Small speed
                        // corrections alone cannot recover that lag after a seek near the end.
                        // Measure a stable startup lag, then seek the still-muted copy ahead once.
                        // Include that lag in the target to compensate for its queued audio.
                        val bothPlaying=main.isPlaying && tail.isPlaying
                        if(!coarseAligned && now-clockStarted>=1_000 && bothPlaying && difference>120 &&
                            main.duration-main.currentPosition>difference+500) {
                            if(lagReference==null || abs(difference-lagReference!!)>20) {
                                lagReference=difference;lagSince=now
                            } else if(now-lagSince>=200) {
                                coarseAligned=true
                                tail.playbackParameters=parameters
                                // Center post-seek timestamp variation inside the 80 ms target.
                                tail.seekTo(main.currentPosition+difference+40)
                                synchronizer=CrossfadeTailSynchronizer()
                                settleUntil=now+1_000
                                log("crossfade_align_seek ahead_ms=$difference")
                                continue
                            }
                        } else {lagReference=null;lagSince=now}
                        val settled=now>=settleUntil
                        val correction=synchronizer.update(difference,now,settled,main.isPlaying && tail.isPlaying)
                        if(BuildConfig.DEBUG && now-lastSyncLog>=250) {
                            log("crossfade_sync elapsed=${now-clockStarted} remaining=${main.duration-main.currentPosition} delta=$difference speed=${tail.playbackParameters.speed} playing=${tail.isPlaying}")
                            lastSyncLog=now
                        }
                        val desired=if(correction==1f) parameters
                            else PlaybackParameters(parameters.speed*correction,parameters.pitch)
                        if(tail.playbackParameters!=desired) tail.playbackParameters=desired
                        if(endMs-main.currentPosition<=fadeMs && synchronizer.ready(now))
                            return@withTimeoutOrNull true
                        wait(10)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } != true) {
                log("crossfade_skipped reason=tail_not_aligned delta=${main.currentPosition-tail.currentPosition} state=${tail.playbackState} playing=${tail.isPlaying} speed=${tail.playbackParameters.speed} mainState=${main.playbackState} mainPlaying=${main.isPlaying} wants=${main.playWhenReady} suppression=${main.playbackSuppressionReason}"); return
            }
            tail.playbackParameters=main.playbackParameters
            if (abandoned() || !main.isPlaying) {log("crossfade_skipped reason=not_playing_at_handoff");return}
            if(!tail.isPlaying || !crossfadeTailAligned(main.currentPosition,tail.currentPosition)) {
                log("crossfade_skipped reason=handoff_drift delta=${main.currentPosition-tail.currentPosition}");return
            }
            // Muted preparation prevents two copies of the outgoing song doubling its volume.
            val tailDuration=tail.duration.takeIf {it>0}?:main.duration
            val overlap = minOf(endMs-main.currentPosition,tailDuration-tail.currentPosition,fadeMs)
            if(overlap<500) {log("crossfade_skipped reason=too_little_left ms=$overlap");return}
            val incomingId=main.getMediaItemAt(main.nextMediaItemIndex).mediaId
            // The level is read at the moment it is taken, not when preparation began.
            // Report 1.5: an echo-out cuts where its plan says, wherever in it the blend began.
            val echoOut=plan?.echo
            val echoCut=echoOut?.let {((it.cutMs-(endMs-overlap)).toFloat()/overlap).coerceIn(0f,1f)}
            val held=owner.take(item.mediaId,incomingId,overlap,main.volume,clock(),echoCut)
            if(held==null) {log("crossfade_skipped reason=level_owned");return}
            grant=held
            val base=held.base
            for(step in 1..HANDOFF_STEPS) {
                val (mainLevel,tailLevel)=handoffLevels(step,base)
                // Down first: a write that lands late leaves a dip, never the two copies together.
                main.volume = mainLevel
                tail.volume = tailLevel
                if(step<HANDOFF_STEPS) wait(HANDOFF_STEP_MS)
            }
            val visual=SongCrossfade(songPresentation(item),incomingId,overlap)
            visualBlend=visual;onBlend(visual)
            expectedTransition="${main.nextMediaItemIndex}:${main.getMediaItemAt(main.nextMediaItemIndex).mediaId}"
            expectedSeek=expectedTransition
            handoff=true
            try {if(entryMs>0) main.seekTo(main.nextMediaItemIndex,entryMs) else main.seekToNextMediaItem()} finally {handoff=false}
            val next = itemKey(main)
            log("crossfade_start ms=$overlap smart=$smart${if(echoOut!=null) " echo=1" else ""}")
            val swap=plan?.swap ?: BassSwap(endMs-overlap/2,BASS_SWAP_MS)
            var stalledSince:Long?=null
            while (true) {
                wait(16)
                if (itemKey(main) != next || !main.playWhenReady || sleepActive() ||
                    main.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE || main.playerError!=null) break
                val now=clock()
                if(main.isPlaying) stalledSince=null
                else {
                    if(stalledSince==null) stalledSince=now
                    if(now-stalledSince>=15_000) {log("crossfade_cancelled reason=incoming_stalled");break}
                }
                // Someone else ended the blend and has the level back: write nothing more.
                val levels=owner.levels(held,clock(),main.isPlaying,tail.isPlaying)?:break
                // Where the outgoing song is: the swap and the trim are placed in its time.
                val songAt=endMs-overlap+(levels.progress*overlap).toLong()
                if(smart && echoOut==null) {
                    // Report 1.1: the bass leaves the fade; each player plays at the louder of its body and its bass.
                    val share=bassShare(songAt,swap)
                    val outgoing=deckLevel(levels.outgoing,if(held.outgoingEnded) 0f else held.base*(1-share))
                    val incoming=deckLevel(levels.incoming,held.base*share)
                    // Report 1.6: the predicted trim; a level never goes past 1.
                    val trim=dbToGain(plan?.trimAt(songAt)?:0f)
                    tail.volume = (outgoing.volume*trim).coerceAtMost(1f)
                    main.volume = (incoming.volume*trim).coerceAtMost(1f)
                    shelves(outgoing.shelves,incoming.shelves)
                } else {
                    tail.volume = levels.outgoing
                    main.volume = levels.incoming
                }
                // Full player, mini player and queue all draw from this one value.
                visual.progress=levels.progress
                if (levels.progress>=1f) break
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("crossfade_failed type=${e::class.simpleName}") }
        finally {
            runCatching { tail.volume=0f }
            if(smart) shelves(Shelves(),Shelves())
            if(plan?.echo!=null) echo(null)
            // Only a blend that still holds the level puts it back; cancel() may already have, once.
            owner.release(grant)?.let {clearVisualBlend();main.volume=it}
            if(activeTail===tail) activeTail=null
            expectedTransition=null
            expectedSeek=null
            runCatching { tail.stop() }
            runCatching { releaseTail() }
            runCatching { tail.release() }
            log("crossfade_tail_released")
        }
    }

    /** Ends any blend at once: used when the user pauses, skips or the service stops. */
    fun cancel() {
        // Coroutine cleanup runs later: mute and pause now so user actions cannot leave overlap.
        activeTail?.let {it.volume=0f;it.pause()}
        shelves(Shelves(),Shelves())
        echo(null)
        expectedTransition=null
        expectedSeek=null
        // Restore synchronously before a sleep timer or another volume owner captures the level.
        owner.release()?.let {mainPlayer?.volume=it}
        clearVisualBlend()
        if(fade?.isActive==true) log("crossfade_cancelled reason=ended_from_outside")
        fade?.cancel()
    }

    private fun clearVisualBlend() {
        if(visualBlend!=null) {visualBlend=null;onBlend(null)}
    }
    fun release() { cancel(); monitor?.cancel();mainPlayer?.removeListener(listener);mainPlayer=null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
}
