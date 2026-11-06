package com.musicglass.desktop.automix
import com.musicglass.desktop.CanonicalFloatEqualizer
import kotlin.math.*

/** Immutable timing; no allocations in the per-frame render path. */
internal class DesktopTransitionRenderer(val plan:SmartPlan,val smart:Boolean){
    val startFrame=plan.startMs*48
    val endFrame=plan.endMs*48
    private val swap=plan.swap?:BassSwap((plan.startMs+plan.endMs)/2,BASS_SWAP_MS)
    private val echo=if(plan.echo!=null)FloatEcho()else null
    private val trim=plan.trimDb?.let{lane->FloatArray(lane.size){10.0.pow(lane[it]/20.0).toFloat()}}
    private val floor=10.0.pow(BASS_SWAP_CUT_DB/20.0).toFloat()
    init{require(plan.startMs>=0&&plan.endMs>plan.startMs&&plan.endMs<=86400000&&plan.incomingStartMs in 0..86400000)}
    fun reset(){echo?.reset()}
    private fun cut(part:Float,volume:Float):Float=if(volume<=0f)0f else (20*log10(maxOf(part/volume,floor))).coerceIn(BASS_SWAP_CUT_DB,0f)+0f
    /** Actual decoded/normalized frames enter here; user volume and limiting remain in final shared DSP. */
    fun render(a:FloatArray,b:FloatArray,frames:Int,incomingFrames:Int,sourceFrame:Long,eqA:CanonicalFloatEqualizer,eqB:CanonicalFloatEqualizer,stereoA:Boolean,stereoB:Boolean,userVolume:Float){
        require(frames>=0&&incomingFrames in 0..frames&&frames*2<=a.size&&incomingFrames*2<=b.size&&userVolume in 0f..1f)
        var at=0
        while(at<frames){
            val n=minOf(32,frames-at)
            val progress=((sourceFrame+at-startFrame).toDouble()/(endFrame-startFrame)).toFloat().coerceIn(0f,1f)
            if(smart&&plan.echo==null){
                val angle=progress*(PI/2).toFloat();val bodyA=cos(angle).coerceIn(0f,1f);val bodyB=sin(angle).coerceIn(0f,1f)
                val share=bassShare((sourceFrame+at)/48,swap);val levelA=maxOf(bodyA,1-share);val levelB=maxOf(bodyB,share)
                eqA.blendShelves(cut(1-share,levelA),cut(bodyA,levelA));eqB.blendShelves(cut(share,levelB),cut(bodyB,levelB))
            }else{eqA.blendShelves(0f,0f);eqB.blendShelves(0f,0f)}
            eqA.process(a,n,stereoA,at)
            if(at<incomingFrames)eqB.process(b,minOf(n,incomingFrames-at),stereoB,at)
            at+=n
        }
        plan.echo?.let{echo?.process(a,frames,sourceFrame,it)}
        val maximumGain=if(userVolume>0)1f/userVolume else Float.POSITIVE_INFINITY
        val cue=plan.echo
        for(frame in 0 until frames){
            val absolute=sourceFrame+frame
            val progress=((absolute-startFrame).toDouble()/(endFrame-startFrame)).toFloat().coerceIn(0f,1f)
            val gainA:Float;val gainB:Float
            if(cue!=null){gainA=1f;gainB=((absolute/48.0-cue.cutMs)/30).toFloat().coerceIn(0f,1f)}
            else{
                val angle=progress*(PI/2).toFloat();val bodyA=cos(angle).coerceIn(0f,1f);val bodyB=sin(angle).coerceIn(0f,1f)
                if(smart){
                    val share=bassShare(absolute/48,swap)
                    val index=((absolute/48-plan.startMs)/ENVELOPE_BLOCK_MS).toInt();val lane=trim
                    val gain=if(lane!=null&&index>=0&&index<lane.size)lane[index]else 1f
                    gainA=minOf(maxOf(bodyA,1-share)*gain,maximumGain);gainB=minOf(maxOf(bodyB,share)*gain,maximumGain)
                }else{gainA=bodyA;gainB=bodyB}
            }
            for(channel in 0..1){val i=frame*2+channel;a[i]=a[i]*gainA+(if(frame<incomingFrames)b[i]else 0f)*gainB}
        }
    }
}
