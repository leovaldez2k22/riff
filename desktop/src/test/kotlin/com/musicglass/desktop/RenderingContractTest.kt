package com.musicglass.desktop

import java.io.File
import java.security.MessageDigest
import kotlin.test.*
import androidx.compose.ui.graphics.skiaImageFilter
import org.jetbrains.skia.*

class RenderingContractTest {
    @Test fun shaderRemainsIdenticalToAndroid() {
        val source=File("../app/src/main/java/com/musicglass/personal/RiffGlass.kt").readText()
        val shader=source.substringAfter("private const val GLASS_SHADER = \"\"\"").substringBefore("\"\"\"").trim()
        assertEquals(shader,javaClass.getResource("/shaders/riff-glass.sksl")!!.readText().trim())
    }
    @Test fun bundledFontRemainsIdentical() {
        fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        assertEquals(hash(File("../app/src/main/res/font/inter_variable.ttf").readBytes()),
            hash(javaClass.getResourceAsStream("/fonts/inter_variable.ttf")!!.use { it.readBytes() }))
    }
    @Test fun artworkBitmapPreservesOpaquePixels() {
        val pixels = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt(), 0xff000000.toInt(), 0xffffffff.toInt())
        val image = opaqueArtworkBitmap(pixels, 2, 2)
        val actual = IntArray(4)
        image.readPixels(actual)
        assertContentEquals(pixels, actual)
        assertFailsWith<IllegalArgumentException> { opaqueArtworkBitmap(intArrayOf(0x12345678), 1, 1) }
    }
    @Test fun artworkSeamAndLegibilityAreStable() {
        val cells=IntArray(36) { it }
        assertContentEquals(cells.sliceArray(30..35),deckMesh(cells).sliceArray(0..5))
        assertEquals(0xFF0B0B0B.toInt(),deckTone(0xFF000000.toInt()))
        assertEquals(0x283444,dominantArtworkRgb(intArrayOf()))
        val solid=IntArray(128*128) { 0xFF123456.toInt() }
        assertContentEquals(solid,boxBlur(solid,128,128))
    }
    @Test fun runtimeFilterRendersEveryOpticalRole() {
        for(role in GlassRole.entries) {
            val piece=GlassPiece()
            val filter=glassEffect(piece,glassSpec(role,GlassStyle()),2f,20f,120f,64f,24f,1f,1f)!!
            Surface.makeRasterN32Premul(180,120).use { target ->
                Surface.makeRasterN32Premul(180,120).use { source ->
                    source.canvas.clear(0xFF19283A.toInt())
                    Paint().use { paint ->
                        paint.color=0xFFFF5EAC.toInt()
                        source.canvas.drawRect(Rect.makeXYWH(24f,20f,30f,70f),paint)
                    }
                    source.makeImageSnapshot().use { image ->
                        Paint().use { paint ->
                            paint.imageFilter=filter.skiaImageFilter
                            target.canvas.drawImage(image,0f,0f,paint)
                        }
                    }
                    assertNotNull(target.makeImageSnapshot().encodeToData())
                }
            }
            (piece.lens as? AutoCloseable)?.close()
        }
    }
}



class KaraokeContractTest {
    @get:org.junit.Rule
    internal val hosts = DesktopHostTestFixture()
    @kotlin.test.Test fun heldWordsRetainTimingAndJoinedScriptBounds() {
        val parts=listOf(LyricPart(0,3000,0,7))
        val held=heldLyricWords("الموسيقى",parts)
        kotlin.test.assertEquals(1,held.size)
        kotlin.test.assertEquals(0f,heldEmphasis(-1,held.single()))
        kotlin.test.assertEquals(1f,heldEmphasis(1000,held.single()))
        kotlin.test.assertEquals(0f,heldEmphasis(4000,held.single()))
    }
    @kotlin.test.Test fun reducedMotionKeepsTheCurrentVisualStateImmediate() {
        kotlin.test.assertEquals(700,GlassMotion.playerOpenMs)
        kotlin.test.assertEquals(600,GlassMotion.playerCloseMs)
        kotlin.test.assertEquals(195,GlassMotion.exitMs(260))
        val host=hosts.create()
        host.dispatch(DesktopCommand.SetReducedMotion(true))
        kotlin.test.assertTrue(host.state.value.reducedMotion)
        host.dispatch(DesktopCommand.Quit)
        kotlin.test.assertFalse(host.state.value.running)
        kotlin.test.assertFailsWith<IllegalStateException> { host.dispatch(DesktopCommand.ShowMain) }
    }
}

class WindowHitTestContractTest {
    @kotlin.test.Test fun allEdgesWinOverTheCustomCaption() {
        val caption=java.awt.Rectangle(0,0,1000,48);val maximize=java.awt.Rectangle(1000,0,100,48)
        val points=listOf(Triple(2,2,13),Triple(1198,2,14),Triple(2,798,16),Triple(1198,798,17),
            Triple(2,400,10),Triple(1198,400,11),Triple(400,2,12),Triple(400,798,15))
        points.forEach { (x,y,expected) -> kotlin.test.assertEquals(expected,chromeHitTest(x,y,1200,800,8,false,caption,maximize)) }
        kotlin.test.assertEquals(9,chromeHitTest(1050,24,1200,800,8,false,caption,maximize))
        kotlin.test.assertEquals(2,chromeHitTest(400,24,1200,800,8,false,caption,maximize))
        kotlin.test.assertEquals(2,chromeHitTest(2,2,1200,800,8,true,caption,maximize))
    }
}

class TokenParityContractTest {
    @kotlin.test.Test fun canonicalObjectsRemainVerbatim() {
        val android=java.io.File("../app/src/main/java/com/musicglass/personal/DesignTokens.kt").readText().replace("\r\n","\n")
        val desktop=java.io.File("src/main/kotlin/com/musicglass/desktop/CanonicalTokens.kt").readText().replace("\r\n","\n")
        val end=android.lastIndexOf("/**",android.indexOf(" * The one theme"))
        kotlin.test.assertTrue(desktop.contains(android.substring(android.indexOf("internal object RiffSpace"),end).trim()))
        kotlin.test.assertTrue(desktop.contains(android.substring(android.indexOf("internal object RiffContrast")).trim()))
    }
}
