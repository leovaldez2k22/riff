package com.musicglass.personal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.rounded.Tune

/**
 * Feature 2, step 1: every glass role over one test pattern, so the glass is tuned in one place. Debug builds reach it
 * from Diagnostics. The pattern has hard stripes and fine lines, which show the bend, the fringe and the blur.
 */
@Composable internal fun GlassGalleryScreen(model: ShellModel, bottom: Dp) {
    val backdrop = rememberRiffBackdrop()
    var on by remember { mutableStateOf(true) }
    var level by remember { mutableFloatStateOf(.4f) }
    ScreenList(model, bottom) {
        item(key = "back") { BackHeading(string(R.string.diagnostics), model::back) }
        item(key = "title") { ScreenTitle(string(R.string.glass_gallery), string(R.string.glass_gallery_detail)) }
        item(key = "gallery") {
            CompositionLocalProvider(LocalRiffBackdrop provides backdrop) {
                Box(Modifier.fillMaxWidth().height(RiffGlassControl.galleryHeight).testTag("glass_gallery")) {
                    Canvas(Modifier.matchParentSize().riffBackdropSource(backdrop)) { testPattern() }
                    Column(Modifier.matchParentSize().padding(RiffSpace.block), verticalArrangement = Arrangement.SpaceEvenly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.block), verticalAlignment = Alignment.CenterVertically) {
                            GalleryPiece(GlassRole.Bar, Modifier.fillMaxWidth(.62f).height(RiffGlassControl.galleryBar))
                            GalleryPiece(GlassRole.Button, Modifier.size(RiffGlassControl.galleryBar))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.block), verticalAlignment = Alignment.CenterVertically) {
                            GalleryPiece(GlassRole.Lens, Modifier.size(RiffGlassControl.galleryLens))
                            GalleryPiece(GlassRole.Card, Modifier.weight(1f).height(RiffGlassControl.galleryCard), RiffRadius.card)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.block), verticalAlignment = Alignment.CenterVertically) {
                            GalleryPiece(GlassRole.Menu, Modifier.weight(1f).height(RiffGlassControl.galleryCard), RiffRadius.card)
                            GalleryPiece(GlassRole.Slider, Modifier.size(RiffGlassControl.galleryLens))
                        }
                    }
                }
            }
        }
        item(key = "controls") {
            SettingsSection(string(R.string.glass_gallery_controls)) {
                SettingsToggleRow(string(R.string.glass_gallery_switch), on, { on = it }, androidx.compose.material.icons.Icons.Rounded.Tune, "glass_gallery_switch")
                CapsuleSlider(level, { level = it }, valueRange = 0f..1f, label = string(R.string.glass_gallery_slider))
                ThinSlider(level, { level = it }, valueRange = 0f..1f)
            }
        }
    }
}

@Composable private fun GalleryPiece(role: GlassRole, modifier: Modifier, corner: Dp? = null) {
    Box(modifier.riffGlass(role, corner), contentAlignment = Alignment.Center) {
        Text(role.name, color = White, style = RiffText.subtitle)
    }
}

/** Stripes in Riff's colours under fine white lines. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.testPattern() {
    val colours = listOf(Pink, Color(0xFF3D7BFF), Color(0xFFFFC23D), Color(0xFF2ED19A), Color(0xFF8B5CF6))
    val band = size.width / 10f
    for (i in 0 until 10) drawRect(colours[i % colours.size], Offset(i * band, 0f), androidx.compose.ui.geometry.Size(band, size.height))
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f))))
    val step = size.height / 24f
    for (i in 0..24) drawLine(White.copy(alpha = .7f), Offset(0f, i * step), Offset(size.width, i * step + band), strokeWidth = 1.5f)
}
