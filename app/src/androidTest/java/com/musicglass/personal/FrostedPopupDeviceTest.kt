package com.musicglass.personal

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.sqrt

/** Pixel test proves a separate popup window samples and blurs the content behind it. */
class FrostedPopupDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FeatureTestActivity>()
    @Test fun popupBlurReducesBackdropDetailIndependentlyOfTheGlassShader() {
        var intensity by mutableFloatStateOf(0f)
        compose.setContent {
            val layer=rememberGraphicsLayer()
            val backdrop=remember(layer) {FrostedBackdrop(layer)}
            CompositionLocalProvider(LocalFrostedBackdrop provides backdrop,LocalFrostedIntensity provides intensity) {
                Box(Modifier.fillMaxSize().frostedBackdropSource(backdrop)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val width=8.dp.toPx()
                        repeat((size.width/width).toInt()+1) {index->
                            drawRect(if(index%2==0) Color.White else Color.Black,Offset(index*width,0f),Size(width,size.height))
                        }
                    }
                    Popup(alignment=Alignment.Center) {
                        Box(Modifier.size(200.dp).frostedPopupBackground().testTag("frost_pixels"))
                    }
                }
            }
        }
        fun deviation():Float {
            compose.waitForIdle();Thread.sleep(100)
            val image=compose.onNodeWithTag("frost_pixels").captureToImage()
            val pixels=image.toPixelMap()
            val row=(image.width/4 until image.width*3/4).map {pixels[it,image.height/2].red}
            val mean=row.average().toFloat()
            return sqrt(row.map {(it-mean)*(it-mean)}.average()).toFloat()
        }
        val sharp=deviation()
        compose.runOnIdle {intensity=1f}
        val blurred=deviation()
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("frost_pixels","sharp=$sharp blurred=$blurred")
        })
        assertTrue("Unblurred backdrop must be visible: $sharp",sharp>.1f)
        assertTrue("Frost must blur the stripes: sharp=$sharp blurred=$blurred",blurred<sharp*.3f)
    }
}
