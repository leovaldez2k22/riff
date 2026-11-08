package com.musicglass.personal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

private const val RIBBONS = "M27,49 C37,57 43,41 51,34 C61,25 71,30 78,41 C66,34 61,43 53,51 C43,61 34,60 27,49 Z " +
    "M29,64 C41,73 47,59 55,52 C66,41 76,46 81,56 C68,49 64,63 56,70 C46,80 36,76 29,64 Z"
private const val BADGE = "M72.7,25 L75.3,25 L75.3,27.7 L78,27.7 L78,30.3 L75.3,30.3 L75.3,33 L72.7,33 L72.7,30.3 L70,30.3 L70,27.7 L72.7,27.7 Z"

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** The launcher icon as Android draws it, from the same paths as the generated vectors: a rounded tile, 108-unit canvas. */
@Composable internal fun LauncherIconPreview(style: IconStyle, accent: AccentPreset, modifier: Modifier = Modifier) {
    val ribbons = remember { PathParser().parsePathString(RIBBONS).toPath() }
    val badge = remember { PathParser().parsePathString(BADGE).toPath() }
    Canvas(modifier) {
        val tile = if (style == IconStyle.C) Color(accent.icon) else Color(0xFF09090B)
        drawRoundRect(tile, cornerRadius = CornerRadius(size.minDimension * .26f), size = Size(size.width, size.height))
        val unit = size.minDimension / 72f // the 72-unit middle of the 108 canvas is what a launcher mask shows
        translate(size.width / 2 - 54f * unit, size.height / 2 - 54f * unit) {
            scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) { scale(.9f, .9f, pivot = androidx.compose.ui.geometry.Offset(54f, 54f)) {
                when (style) {
                    IconStyle.A -> { drawPath(ribbons, Color(accent.icon)); drawPath(badge, Color(accent.icon)) }
                    IconStyle.C -> { drawPath(ribbons, Color(0xFF09090B)); drawPath(badge, Color(0xFF09090B)) }
                    IconStyle.D -> {
                        rotate(90f, pivot = androidx.compose.ui.geometry.Offset(54f, 54f)) { drawPath(ribbons, Color(accent.partner)) }
                        drawPath(ribbons, Color(accent.icon))
                    }
                }
            } }
        }
    }
}

/** Feature 21 (Riff+): the tile on Look and feel that opens Appearance. */
@Composable internal fun AppearanceTile(model: ShellModel) {
    val context = LocalContext.current
    val choice = remember(RiffAccent.ui) { Appearance.choice(context) }
    BentoLink(string(R.string.appearance), Icons.Rounded.Palette, "settings_appearance", Modifier.fillMaxWidth(),
        value = string(R.string.appearance_value, string(choice.style.label), string(choice.accent.label))) {
        model.push(Route.Settings(SettingsSection.Appearance))
    }
}

/** Feature 21 (Riff+): icon style, colour and whether the app takes the colour too. */
@Composable internal fun AppearanceScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(Appearance.choice(context)) }
    fun pick(next: AppearanceChoice) {
        if (next == choice) return
        uiHaptic(HapticCue.Selection)
        choice = next
        Appearance.save(context, next)
        context.activity()?.let { Appearance.applyLauncher(it, next) }
    }
    SettingsPage(model, bottom, string(R.string.appearance)) {
        item {
            BentoTile(Modifier.fillMaxWidth()) {
                BentoTitle(string(R.string.icon_style))
                Row(Modifier.fillMaxWidth().padding(top = RiffSpace.item), horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                    IconStyle.entries.forEach { style ->
                        val selected = style == choice.style
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(RiffRadius.card))
                            .selectable(selected, role = Role.RadioButton) { pick(choice.copy(style = style)) }
                            .padding(RiffSpace.tight).testTag("appearance_style_${style.key}"), horizontalAlignment = Alignment.CenterHorizontally) {
                            LauncherIconPreview(style, choice.accent, Modifier.fillMaxWidth(.8f).aspectRatio(1f)
                                .then(if (selected) Modifier.border(RiffAppearance.iconRing, White, RoundedCornerShape(RiffAppearance.iconCorner)) else Modifier))
                            Text(string(style.label), color = if (selected) White else Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                        }
                    }
                }
            }
        }
        item {
            BentoTile(Modifier.fillMaxWidth()) {
                BentoTitle(string(R.string.accent_colour))
                AccentPreset.entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = RiffSpace.item), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { accent ->
                            val selected = accent == choice.accent
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(RiffRadius.card))
                                .selectable(selected, role = Role.RadioButton) { pick(choice.copy(accent = accent)) }
                                .padding(vertical = RiffSpace.tight).testTag("appearance_accent_${accent.key}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(RiffAppearance.swatch).clip(CircleShape).background(Color(accent.ui))
                                    .then(if (selected) Modifier.border(RiffAppearance.swatchRing, White, CircleShape) else Modifier))
                                Text(string(accent.label), color = if (selected) White else Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                            }
                        }
                    }
                }
            }
        }
        item {
            // Rose is Riff's own pink: tinting changes nothing, so the switch is shown but cannot be pressed (owner, 8 Nov).
            val usable = choice.accent != AccentPreset.Rose
            val spoken = string(R.string.appearance_tint)
            BentoTile(Modifier.fillMaxWidth().alpha(if (usable) 1f else .45f)
                .then(if (usable) Modifier.toggleable(choice.tint, role = Role.Switch) { pick(choice.copy(tint = it)) }
                    else Modifier.semantics { disabled(); contentDescription = spoken; stateDescription = string(if (choice.tint) R.string.on else R.string.off) })
                .testTag("appearance_tint"), lit = usable && choice.tint) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BentoTitle(spoken, Modifier.weight(1f))
                    GlassSwitch(choice.tint)
                }
                BentoHelp(string(if (usable) R.string.appearance_tint_help else R.string.appearance_tint_rose))
            }
        }
        item { Text(string(R.string.appearance_launcher_note), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(horizontal = RiffSpace.item)) }
        item {
            // The visualizer is a look, so it is switched here rather than in the player's menu (owner, 9 November 2026).
            val shown = remember(VisualizerSettings.revision) { VisualizerSettings.shown(context) }
            BentoTile(Modifier.fillMaxWidth().toggleable(shown, role = Role.Switch) { uiHaptic(HapticCue.Selection); VisualizerSettings.setShown(context, it) }
                .testTag("appearance_visualizer"), lit = shown) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BentoTitle(string(R.string.appearance_visualizer), Modifier.weight(1f))
                    GlassSwitch(shown)
                }
                BentoHelp(string(R.string.appearance_visualizer_help))
            }
        }
        item { Spacer(Modifier.heightIn(min = RiffSpace.item)) }
    }
}
