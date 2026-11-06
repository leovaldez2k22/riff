package com.musicglass.desktop
import kotlin.math.*
import kotlinx.serialization.json.*

internal fun checkedEqualizer(value:EqualizerConfig):EqualizerConfig {
    require(value.toneX in -5..5&&value.toneY in -5..5&&value.manual.size==7)
    require(value.manual.all{it.isFinite()&&it in -12f..12f}&&value.balance.isFinite()&&value.balance in -1f..1f)
    require(value.preset in EQ_PRESETS||value.preset=="Custom")
    return value.copy(manual=value.manual.toList())
}
internal data class EqualizerTarget(val config:EqualizerConfig,val sections:List<EqSection>,val preamp:Float,val left:Float,val right:Float)
/** Response/preamp work occurs on the command thread, never the real-time mixer. */
internal fun prepareEqualizer(value:EqualizerConfig,rate:Int=48000):EqualizerTarget {
    val config=checkedEqualizer(value);val sections=eqSections(config);val balance=balanceGains(if(config.enabled)config.balance else 0f)
    return EqualizerTarget(config,sections,10.0.pow(preampFor(sections,rate)/20.0).toFloat(),balance.first,balance.second)
}
/** Android TPT mixes and 30 ms gain/Q glide, without a PCM16 quantization step. One instance per deck. */
internal class CanonicalFloatEqualizer(private val rate:Int=48000,private val channels:Int=2){
    private var target=prepareEqualizer(EqualizerConfig(),rate)
    private var bassCutDb=0f;private var aboveBassCutDb=0f
    fun blendShelves(low:Float,above:Float){require(low.isFinite()&&above.isFinite()&&low in -30f..0f&&above in -30f..0f);bassCutDb=low;aboveBassCutDb=above}
    private val gain=FloatArray(12);private val width=FloatArray(12){1f}
    private val ic1=Array(12){DoubleArray(channels)};private val ic2=Array(12){DoubleArray(channels)}
    private val a1=DoubleArray(12);private val a2=DoubleArray(12);private val a3=DoubleArray(12)
    private val m0=DoubleArray(12);private val m1=DoubleArray(12);private val m2=DoubleArray(12)
    private var preamp=1f;private var left=1f;private var right=1f;private var countdown=0
    private val glide=(1-exp(-32/(.03f*rate))).toFloat()
    val configuration get()=target.config
    fun configure(value:EqualizerTarget){target=value}
    fun reset(){
        for(i in 0..11){val s=target.sections.getOrNull(i);gain[i]=s?.gainDb?:if(i==10)bassCutDb else aboveBassCutDb;width[i]=s?.q?:.7f;coefficients(i,s?.shape?:if(i==10)EqShape.LowShelf else EqShape.HighShelf,s?.frequency?:150f)}
        preamp=target.preamp;left=target.left;right=target.right;ic1.forEach{it.fill(0.0)};ic2.forEach{it.fill(0.0)};countdown=0
    }
    private fun approach(current:Float,desired:Float):Float{val next=current+(desired-current)*glide;return if(abs(desired-next)<.001f)desired else next}
    fun process(samples:FloatArray,frames:Int,balanceEnabled:Boolean=true,frameOffset:Int=0){
        require(frames>=0&&frameOffset>=0&&(frameOffset.toLong()+frames)*channels<=samples.size)
        if(!target.config.enabled&&bassCutDb==0f&&aboveBassCutDb==0f&&gain.all{it==0f}&&preamp==1f&&left==1f&&right==1f)return
        for(frame in 0 until frames){
            if(countdown--<=0){
                countdown=32
                for(i in 0..11){
                    val s=target.sections.getOrNull(i)
                    val desired=s?.gainDb?:if(i==10)bassCutDb else aboveBassCutDb
                    val was=gain[i];gain[i]=approach(gain[i],desired);width[i]=approach(width[i],s?.q?:.7f)
                    if(was!=0f&&gain[i]==0f){ic1[i].fill(0.0);ic2[i].fill(0.0)}
                    coefficients(i,s?.shape?:if(i==10)EqShape.LowShelf else EqShape.HighShelf,s?.frequency?:150f)
                }
                preamp=approach(preamp,target.preamp);left=approach(left,target.left);right=approach(right,target.right)
            }
            for(channel in 0 until channels){
                val at=(frameOffset+frame)*channels+channel;var value=samples[at].toDouble()
                if(!value.isFinite())throw StreamCorruptedException(java.io.IOException("Non-finite decoder PCM"))
                value*=preamp
                for(i in 0..11){
                    if(gain[i]==0f)continue
                    val v3=value-ic2[i][channel];val v1=a1[i]*ic1[i][channel]+a2[i]*v3;val v2=ic2[i][channel]+a2[i]*ic1[i][channel]+a3[i]*v3
                    ic1[i][channel]=2*v1-ic1[i][channel];ic2[i][channel]=2*v2-ic2[i][channel]
                    value=m0[i]*value+m1[i]*v1+m2[i]*v2
                }
                if(balanceEnabled&&channels==2)value*=if(channel==0)left else right
                samples[at]=value.toFloat()
            }
        }
    }
    private fun coefficients(i:Int,shape:EqShape,frequency:Float){
        if(gain[i]==0f)return
        val a=10.0.pow(gain[i]/40.0);val q=width[i].toDouble().coerceAtLeast(.05);val base=tan(PI*frequency.coerceAtMost(rate*.49f)/rate)
        val g:Double;val k:Double
        when(shape){
            EqShape.Bell->{g=base;k=1/(q*a);m0[i]=1.0;m1[i]=k*(a*a-1);m2[i]=0.0}
            EqShape.LowShelf->{g=base/sqrt(a);k=1/q;m0[i]=1.0;m1[i]=k*(a-1);m2[i]=a*a-1}
            EqShape.HighShelf->{g=base*sqrt(a);k=1/q;m0[i]=a*a;m1[i]=k*(1-a)*a;m2[i]=1-a*a}
        }
        a1[i]=1/(1+g*(g+k));a2[i]=g*a1[i];a3[i]=g*a2[i]
    }
}
internal fun EqualizerConfig.toJson()=buildJsonObject{
    put("enabled",enabled);put("mode",mode.key);put("x",toneX);put("y",toneY);put("focused",focused);put("manual",JsonArray(manual.map(::JsonPrimitive)));put("preset",preset);put("balance",balance)
}
internal fun equalizerFromJson(value:JsonObject)=checkedEqualizer(EqualizerConfig(
    value.getValue("enabled").jsonPrimitive.boolean,
    EqMode.entries.single{it.key==value.getValue("mode").jsonPrimitive.content},
    value.getValue("x").jsonPrimitive.int,value.getValue("y").jsonPrimitive.int,value.getValue("focused").jsonPrimitive.boolean,
    value.getValue("manual").jsonArray.map{it.jsonPrimitive.float},value.getValue("preset").jsonPrimitive.content,value.getValue("balance").jsonPrimitive.float))
internal fun migrateLegacyEqualizer(name:String):EqualizerConfig {
    if(name=="Flat")return EqualizerConfig()
    val preset=EQ_PRESETS.keys.firstOrNull{it.equals(name,true)}
    if(preset!=null)return EqualizerConfig(enabled=true,mode=EqMode.Manual,manual=EQ_PRESETS.getValue(preset),preset=preset)
    val old=DesktopEqPresets.bands(name)
    val manual=EqLayout.manualFrequencies.map{frequency->
        val upper=EQ_CENTERS.indexOfFirst{it>=frequency}.coerceAtLeast(1);val lower=upper-1
        val mix=ln(frequency/EQ_CENTERS[lower])/ln(EQ_CENTERS[upper]/EQ_CENTERS[lower]);(old[lower]+mix*(old[upper]-old[lower])).coerceIn(-12f,12f)
    }
    return EqualizerConfig(enabled=true,mode=EqMode.Manual,manual=manual,preset="Custom")
}
