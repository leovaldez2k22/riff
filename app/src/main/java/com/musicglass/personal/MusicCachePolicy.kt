package com.musicglass.personal

internal fun reusableStream(expiresAt:Long,now:Long,live:Boolean)=!live && expiresAt>now+60_000

/** SABR fragments and direct files can share an itag but have different byte layouts. */
internal fun audioStreamCacheKey(scope:String,videoId:String,itag:Int,mimeType:String?,bitrate:Int,sabr:Boolean):String =
    "audio:v2:$scope:${if(sabr) "sabr" else "direct"}:$videoId:$itag:$mimeType:$bitrate"
internal data class SmartCandidate(val id:String,val lastPlayed:Long,val plays:Int,val listenedMs:Long,val durationMs:Long,val favorite:Boolean=false,val live:Boolean=false,val music:Boolean=true)
/** Membership is the most recent 250 qualifying songs; score only determines download priority. */
internal fun smartCandidates(rows:List<SmartCandidate>,now:Long):List<SmartCandidate> = rows.filter {
    !it.live && it.music && it.durationMs in 1..1_200_000 && it.listenedMs>=minOf(30_000,it.durationMs/2)
}.distinctBy {it.id}.sortedByDescending {it.lastPlayed}.take(250).sortedByDescending {
    val days=((now-it.lastPlayed).coerceAtLeast(0)/86_400_000.0)
    100.0/(1+days)+minOf(it.plays,20)*2+minOf(1.0,it.listenedMs.toDouble()/it.durationMs)*10+if(it.favorite) 10 else 0
}
internal fun downloadFits(currentBytes:Long,expectedBytes:Long,budgetBytes:Long,availableBytes:Long,totalBytes:Long):Boolean {
    val reserve=NetworkStoragePolicy.deviceReserve(totalBytes)
    return currentBytes>=0 && expectedBytes>0 && expectedBytes<=budgetBytes && currentBytes<=budgetBytes-expectedBytes &&
        expectedBytes<=availableBytes && availableBytes-expectedBytes>=reserve
}

/** Mobile-data permission also covers metered Wi-Fi/VPN; Wi-Fi-only remains conservative by default. */
internal fun smartDownloadNetworkAllowed(allowMobileData:Boolean,wifi:Boolean,unmetered:Boolean,validated:Boolean) =
    validated && (allowMobileData || wifi && unmetered)
