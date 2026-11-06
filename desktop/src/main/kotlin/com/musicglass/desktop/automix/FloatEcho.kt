package com.musicglass.desktop.automix

import kotlin.math.PI
import kotlin.math.exp
/** Android's actual one-beat echo-out in Float32, with one owned stereo delay line. */
internal class FloatEcho {
    private val line=FloatArray(48000*1500/1000*2)
    private val high=FloatArray(2);private val highIn=FloatArray(2);private val low=FloatArray(2)
    private val highCoefficient=exp(-2*PI*200/48000).toFloat()
    private val lowCoefficient=(1-exp(-2*PI*6000/48000)).toFloat()
    private var head=0;private var positionUs=0.0;private var nextFrame=0L
    private val frameUs=1e6/48000
    fun reset(sourceFrame:Long=0){require(sourceFrame>=0);line.fill(0f);high.fill(0f);highIn.fill(0f);low.fill(0f);head=0;positionUs=sourceFrame*frameUs;nextFrame=sourceFrame}
    fun process(samples:FloatArray,frames:Int,sourceFrame:Long,cue:EchoOut?){
        require(frames>=0&&frames*2<=samples.size&&sourceFrame>=0)
        if(sourceFrame!=nextFrame)reset(sourceFrame)
        nextFrame=sourceFrame+frames
        if(cue==null){positionUs+=frames*frameUs;return}
        require(cue.delayMs in 1..60000&&cue.sendFromMs>=0&&cue.cutMs>cue.sendFromMs)
        val delay=(cue.delayMs*48*2).toInt().coerceIn(2,line.size)
        val sendFrom=cue.sendFromMs*1000.0;val cut=cue.cutMs*1000.0
        for(frame in 0 until frames){
            val at=positionUs
            val send=when{at<sendFrom->0f;at<cut->((at-sendFrom)/(cut-sendFrom)).toFloat();else->0f}
            val dry=if(at<cut)1f else (1-(at-cut)/5000.0).toFloat().coerceAtLeast(0f)
            for(channel in 0..1){
                val index=frame*2+channel;val x=samples[index];check(x.isFinite())
                val echo=line[(head-delay).mod(line.size)]
                high[channel]=highCoefficient*(high[channel]+echo-highIn[channel]);highIn[channel]=echo
                low[channel]+=(high[channel]-low[channel])*lowCoefficient
                line[head]=x*send*dry+low[channel]*.55f
                if(++head==line.size)head=0
                samples[index]=(x*dry+echo).coerceIn(-1f,32767/32768f)
            }
            positionUs+=frameUs
        }
    }
}
