package com.musicglass.personal

/** Small deterministic k-means palette; combines nearby shades of a dominant gradient. */
internal fun dominantArtworkRgb(pixels:IntArray):Int {
    val colors=pixels.filter {p->maxOf((p shr 16)and 255,(p shr 8)and 255,p and 255) in 24..240}
        .ifEmpty {pixels.toList()}
    if(colors.isEmpty()) return 0x283444
    fun channel(p:Int,n:Int)=((p shr (16-n*8))and 255).toFloat()
    fun distance(p:Int,c:FloatArray)=(0..2).sumOf {n->val d=channel(p,n)-c[n];(d*d).toDouble()}
    val centers=mutableListOf(FloatArray(3) {channel(colors[0],it)})
    repeat(4) {val p=colors.maxByOrNull {v->centers.minOf {distance(v,it)}}!!;centers+=FloatArray(3) {channel(p,it)}}
    var counts=IntArray(5)
    repeat(10) {
        counts=IntArray(5);val sums=Array(5) {FloatArray(3)}
        colors.forEach {p->val k=centers.indices.minByOrNull {distance(p,centers[it])}!!
            counts[k]++;repeat(3) {n->sums[k][n]+=channel(p,n)}
        }
        centers.indices.forEach {k->if(counts[k]>0) repeat(3) {n->centers[k][n]=sums[k][n]/counts[k]}}
    }
    fun hue(c:FloatArray):Float {
        val high=c.max();val low=c.min();val delta=high-low
        if(delta<12f) return -1f
        val h=when(high) {c[0]->(c[1]-c[2])/delta;c[1]->2f+(c[2]-c[0])/delta;else->4f+(c[0]-c[1])/delta}*60f
        return (h+360f)%360f
    }
    fun related(a:FloatArray,b:FloatArray):Boolean {
        val ha=hue(a);val hb=hue(b)
        if(ha<0 || hb<0) return a===b
        val d=kotlin.math.abs(ha-hb)
        return minOf(d,360f-d)<52f && kotlin.math.abs(a.max()-b.max())<110f
    }
    val best=centers.indices.maxByOrNull {i->centers.indices.filter {j->related(centers[i],centers[j])}.sumOf {counts[it]}}!!
    val group=centers.indices.filter {related(centers[best],centers[it])}
    val total=group.sumOf {counts[it]}.coerceAtLeast(1)
    val c=FloatArray(3) {n->group.sumOf {k->(centers[k][n]*counts[k]).toDouble()}.toFloat()/total}
    return (c[0].toInt() shl 16) or(c[1].toInt() shl 8)or c[2].toInt()
}
