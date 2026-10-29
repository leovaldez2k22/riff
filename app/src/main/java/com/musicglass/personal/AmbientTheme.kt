package com.musicglass.personal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Typography
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * Feature 3: Inter 4.1's variable font, in the two optical sizes SF Pro has. Text (opsz 14) is drawn for reading
 * sizes; Display (opsz 32), tighter and finer, for 20 sp and up, as SF switches from Text to Display.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun interFamily(opticalSize: Float) = FontFamily(listOf(300, 400, 500, 600, 700, 800).map { weight ->
    Font(R.font.inter_variable, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)))
})
internal val InterFamily = interFamily(14f)
internal val InterDisplayFamily = interFamily(32f)

private val baseTypography = Typography()

/**
 * Inter's own spacing for a size, in em so it scales with whatever size a text is given (Inter's published dynamic
 * metrics: -0.0223 + 0.185 e^(-0.1745 size)). Material's defaults keep Roboto's spacing (+0.5 sp on body text), which
 * set Inter wide and made small text look like another font (phone checks 29 Oct, bug 17).
 */
internal fun interTracking(sizeSp: Float) = (-.0223f + .185f * kotlin.math.exp(-.1745f * sizeSp)).em

private fun TextStyle.inter(family: FontFamily) = copy(fontFamily = family, letterSpacing = interTracking(fontSize.value))

internal val GlassTypography = Typography(
    displayLarge = baseTypography.displayLarge.inter(InterDisplayFamily),
    displayMedium = baseTypography.displayMedium.inter(InterDisplayFamily),
    displaySmall = baseTypography.displaySmall.inter(InterDisplayFamily),
    headlineLarge = baseTypography.headlineLarge.inter(InterDisplayFamily),
    headlineMedium = baseTypography.headlineMedium.inter(InterDisplayFamily),
    headlineSmall = baseTypography.headlineSmall.inter(InterDisplayFamily),
    titleLarge = baseTypography.titleLarge.inter(InterFamily),
    titleMedium = baseTypography.titleMedium.inter(InterFamily),
    titleSmall = baseTypography.titleSmall.inter(InterFamily),
    bodyLarge = baseTypography.bodyLarge.inter(InterFamily),
    bodyMedium = baseTypography.bodyMedium.inter(InterFamily),
    bodySmall = baseTypography.bodySmall.inter(InterFamily),
    labelLarge = baseTypography.labelLarge.inter(InterFamily),
    labelMedium = baseTypography.labelMedium.inter(InterFamily),
    labelSmall = baseTypography.labelSmall.inter(InterFamily),
)

internal data class AmbientPalette(val top: Color, val middle: Color, val accent: Color = top)

// Twelve coordinated, low-luminance combinations. Glows blend before fading to AMOLED black.
internal val ambientPalettes = listOf(
    AmbientPalette(Color(0xFF271044), Color(0xFF123653), Color(0xFF742A69)),
    AmbientPalette(Color(0xFF092F43), Color(0xFF30305B), Color(0xFF185B59)),
    AmbientPalette(Color(0xFF402047), Color(0xFF4C2D3E), Color(0xFF173A6A)),
    AmbientPalette(Color(0xFF173C3B), Color(0xFF243957), Color(0xFF556331)),
    AmbientPalette(Color(0xFF44251A), Color(0xFF552B48), Color(0xFF304968)),
    AmbientPalette(Color(0xFF1B2D54), Color(0xFF3C245A), Color(0xFF1B5557)),
    AmbientPalette(Color(0xFF44233D), Color(0xFF223C60), Color(0xFF674120)),
    AmbientPalette(Color(0xFF293D23), Color(0xFF2D4660), Color(0xFF61462A)),
    AmbientPalette(Color(0xFF432F19), Color(0xFF473456), Color(0xFF18525A)),
    AmbientPalette(Color(0xFF31304B), Color(0xFF174B57), Color(0xFF5B314C)),
    AmbientPalette(Color(0xFF13414A), Color(0xFF4C2A5A), Color(0xFF575126)),
    AmbientPalette(Color(0xFF46222A), Color(0xFF363A62), Color(0xFF305039)),
)

private fun paletteIndex(now: LocalDateTime): Int =
    (now.dayOfYear * 5 + now.hour / 2) % ambientPalettes.size

@Composable
internal fun rememberAmbientPalette(motion: MotionPolicy): State<AmbientPalette> {
    var index by remember { mutableIntStateOf(paletteIndex(LocalDateTime.now())) }
    LaunchedEffect(Unit) {
        while (true) {
            // Recompute across time-zone changes and every two-hour color boundary.
            delay(60_000L)
            index = paletteIndex(LocalDateTime.now())
        }
    }
    val selected = ambientPalettes[index]
    val animation = GlassMotion.timed<Color>(GlassMotion.ambientMs, motion)
    val top = animateColorAsState(selected.top, animation, label = "ambient top")
    val middle = animateColorAsState(selected.middle, animation, label = "ambient middle")
    val accent = animateColorAsState(selected.accent, animation, label = "ambient accent")
    return remember(top,middle,accent) {derivedStateOf {AmbientPalette(top.value,middle.value,accent.value)}}
}

@Composable
internal fun AmbientBackdrop(palette: State<AmbientPalette>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().drawBehind {
        val colors=palette.value
        drawRect(Color.Black)
        drawRect(Brush.verticalGradient(
            0f to colors.top.copy(alpha = .74f),
            .18f to colors.middle.copy(alpha = .42f),
            .53f to Color.Transparent,
        ))
        drawRect(Brush.radialGradient(listOf(colors.accent.copy(alpha = .62f),
            Color.Transparent), center = Offset(size.width * .08f, size.height * .10f),
            radius = size.height * .36f))
        drawRect(Brush.radialGradient(listOf(colors.middle.copy(alpha = .58f),
            Color.Transparent), center = Offset(size.width * .92f, size.height * .03f),
            radius = size.height * .36f))
        drawRect(Brush.radialGradient(listOf(colors.top.copy(alpha = .36f),
            Color.Transparent), center = Offset(size.width * .55f, size.height * .22f),
            radius = size.height * .31f))
    })
}
