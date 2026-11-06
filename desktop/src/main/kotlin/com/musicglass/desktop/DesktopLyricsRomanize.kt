package com.musicglass.desktop

import com.ibm.icu.text.Transliterator

internal enum class LyricsRomanMode{Original,Romanized,Both;val next get()=entries[(ordinal+1)%entries.size]}
/** Source romanization wins. Mechanical ICU output is labelled Auto and receives no invented word timing. */
internal object DesktopLyricsRomanize {
    private val transform by lazy{Transliterator.getInstance("Any-Latin; Latin-ASCII")}
    fun enrich(document:LyricsDocument):LyricsDocument=document.copy(lines=document.lines.map{line->
        if(line.romanized!=null||!needsRomanizing(line.text))line else{
            val value=synchronized(transform){transform.transliterate(line.text)}.trim().takeIf{it.isNotBlank()&&it!=line.text}
            line.copy(romanized=value,romanizedAuto=value!=null)
        }
    })
    private fun needsRomanizing(text:String)=text.codePoints().anyMatch{Character.isLetter(it)&&Character.UnicodeScript.of(it) !in setOf(Character.UnicodeScript.LATIN,Character.UnicodeScript.COMMON,Character.UnicodeScript.INHERITED)}
}
