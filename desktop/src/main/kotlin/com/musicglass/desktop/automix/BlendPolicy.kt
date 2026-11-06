package com.musicglass.desktop.automix
import kotlin.math.*
internal const val SILENCE_DBFS=-50.0
internal enum class BlendMode(val wire:String){Crossfade("crossfade"),Smart("smart"),Automix("automix")}
internal fun crossfadeGains(progress:Float):Pair<Float,Float>{val t=progress.coerceIn(0f,1f)*(PI/2).toFloat();return cos(t).coerceIn(0f,1f) to sin(t).coerceIn(0f,1f)}
