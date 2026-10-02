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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDateTime
import kotlinx.coroutines.delay

internal val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val baseTypography = Typography()
internal val GlassTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = InterFamily),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = InterFamily),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = InterFamily),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = InterFamily),
    headlineMedium = baseTypography.headlineMedium.copy(fontFamily = InterFamily),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = InterFamily),
    titleLarge = baseTypography.titleLarge.copy(fontFamily = InterFamily),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = InterFamily),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = InterFamily),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = InterFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = InterFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = InterFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = InterFamily),
    labelMedium = baseTypography.labelMedium.copy(fontFamily = InterFamily),
    labelSmall = baseTypography.labelSmall.copy(fontFamily = InterFamily),
)

internal data class AmbientPalette(val top: Color, val middle: Color, val accent: Color = top)

// Twelve coordinated, low-luminance combinations. Glows blend before fading to AMOLED black.
private val ambientPalettes = listOf(
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
