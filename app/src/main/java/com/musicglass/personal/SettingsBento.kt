package com.musicglass.personal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The settings pages' pieces (owner, 30 Oct): the opening page's tiles and, inside each section, a bento of glass tiles
 * of mixed sizes. Every tile is Riff's card glass over the page's own background; a switch's icon pops as it turns on
 * or off; the tiles come in one after another as a page opens.
 */

/** Each row of a section's bento, coming in a moment after the one above it. */
internal fun LazyListScope.bentoRows(vararg rows: @Composable () -> Unit) =
    rows.forEachIndexed { index, row -> item { Box(Modifier.bentoEnter(index)) { row() } } }

/** Fades and rises into place, [order] steps after the page opens; once only, not again as the list scrolls back. */
@Composable internal fun Modifier.bentoEnter(order: Int): Modifier {
    val motion = LocalMotionPolicy.current
    var arrived by rememberSaveable { mutableStateOf(motion.reduced) }
    val progress = remember { Animatable(if (arrived) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (arrived) return@LaunchedEffect
        delay(((order * GlassMotion.staggerMs).coerceAtMost(GlassMotion.staggerMaxMs) * motion.scale).toLong())
        progress.animateTo(1f, GlassMotion.timed(GlassMotion.screenMs, motion))
        arrived = true
    }
    val rise = with(LocalDensity.current) { RiffSettings.enterRise.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p; translationY = (1f - p) * rise
        val grow = .96f + .04f * p
        scaleX = grow; scaleY = grow
    }
}

/** Two tiles side by side, as tall as the taller one. Never a [GlassSegmented] inside: it can't be measured ahead. */
@Composable internal fun BentoPair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(RiffSettings.tileGap)) {
        left(Modifier.weight(1f).fillMaxHeight()); right(Modifier.weight(1f).fillMaxHeight())
    }
}

/** A tall tile beside two stacked ones. */
@Composable internal fun BentoBeside(tall: @Composable (Modifier) -> Unit, top: @Composable (Modifier) -> Unit,
    bottom: @Composable (Modifier) -> Unit, tallFirst: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(RiffSettings.tileGap)) {
        if (tallFirst) tall(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RiffSettings.tileGap)) {
            top(Modifier.fillMaxWidth()); bottom(Modifier.fillMaxWidth())
        }
        if (!tallFirst) tall(Modifier.weight(1f).fillMaxHeight())
    }
}

/**
 * One glass tile: Riff's card glass, a thin edge that turns pink while [lit], and a [tint] that fades in under it. A
 * tile with [onClick] shrinks a little while pressed.
 */
@Composable internal fun BentoTile(modifier: Modifier = Modifier, lit: Boolean = false, tint: Color = Pink,
    onClick: (() -> Unit)? = null, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotionPolicy.current
    val shape = RoundedCornerShape(RiffRadius.hero)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed && !motion.reduced) .97f else 1f, GlassMotion.settle(motion), label = "tile press")
    val wash by animateColorAsState(if (lit) tint.copy(alpha = .16f) else Color.Transparent,
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "tile tint")
    val edge by animateColorAsState(if (lit) tint.copy(alpha = .5f) else White.copy(alpha = .12f),
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "tile edge")
    Column(modifier.heightIn(min = RiffSettings.tile).graphicsLayer { scaleX = squeeze; scaleY = squeeze }
        .riffGlass(GlassRole.Card, RiffRadius.hero, backdrop = LocalPageBackdrop.current).clip(shape)
        .background(wash).border(RiffSettings.hairline, edge, shape)
        .then(if (onClick != null) Modifier.clickable(press, null, role = Role.Button, onClick = hapticClick(onClick)) else Modifier)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .padding(RiffSettings.tilePad), content = content)
}

/**
 * A setting's icon in its round badge. As the switch turns on it pops past full size, tips back and turns pink; off,
 * it dips and greys. Under reduced motion only the colour changes.
 */
@Composable internal fun SettingIcon(icon: ImageVector, on: Boolean, badge: Boolean = true, tint: Color = Pink) {
    val motion = LocalMotionPolicy.current
    val scale = remember { Animatable(1f) }
    val turn = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        // Not on the first showing: only when the switch moves.
        if (!settled) { settled = true; return@LaunchedEffect }
        if (motion.reduced) return@LaunchedEffect
        launch {
            scale.animateTo(if (on) 1.32f else .76f, GlassMotion.timed(GlassMotion.pressMs, motion))
            scale.animateTo(1f, GlassMotion.pop(motion))
        }
        launch {
            turn.animateTo(if (on) -18f else 12f, GlassMotion.timed(GlassMotion.pressMs, motion))
            turn.animateTo(0f, GlassMotion.pop(motion))
        }
    }
    val colour by animateColorAsState(if (on) tint else Muted, GlassMotion.timed(GlassMotion.selectionMs, motion), label = "setting icon")
    val back by animateColorAsState(if (on) tint.copy(alpha = .18f) else White.copy(alpha = .08f),
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "setting badge")
    val spin = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = turn.value }
    if (badge) Box(spin.size(RiffSettings.badge).clip(CircleShape).background(back), contentAlignment = Alignment.Center) {
        SwitchIcon(icon, on, colour, Modifier.size(RiffSettings.badgeIcon))
    } else SwitchIcon(icon, on, colour, spin.size(RiffSettings.rowIcon))
}

/** A switch as a tile: its icon and the switch across the top, its name and a line of help under them. */
@Composable internal fun BentoToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector, tag: String,
    modifier: Modifier = Modifier, subtitle: String? = null) {
    BentoTile(modifier.toggleable(checked, role = Role.Switch, onValueChange = hapticToggle(onChange)).testTag(tag), lit = checked) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(icon, checked)
            Spacer(Modifier.weight(1f))
            GlassSwitch(checked)
        }
        Spacer(Modifier.weight(1f).heightIn(min = RiffSettings.tileGap))
        BentoTitle(title)
        if (subtitle != null) BentoHelp(subtitle)
    }
}

/** A tile that opens a page or does something: its icon, its name, what it is set to, and an arrow. */
@Composable internal fun BentoLink(title: String, icon: ImageVector, tag: String, modifier: Modifier = Modifier,
    value: String? = null, tint: Color = Pink, onClick: () -> Unit) {
    BentoTile(modifier, onClick = onClick, tag = tag) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
            SettingIcon(icon, true, tint = tint)
            Column(Modifier.weight(1f)) {
                BentoTitle(title)
                if (value != null) BentoHelp(value)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
        }
    }
}

/** A tile holding a slider: its icon and name, the value on the right, the slider under them. */
@Composable internal fun BentoSlider(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier,
    slider: @Composable () -> Unit) {
    BentoTile(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
            SettingIcon(icon, true)
            BentoTitle(title, Modifier.weight(1f))
            Text(value, color = Pink, fontSize = RiffType.body, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.padding(top = RiffSpace.item)) { slider() }
    }
}

@Composable internal fun BentoTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = White, fontSize = RiffSettings.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)

@Composable internal fun BentoHelp(text: String, colour: Color = Muted) =
    Text(text, color = colour, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight / 2))

/** A small heading over a group of tiles. */
@Composable internal fun BentoHeading(text: String) =
    Text(text, color = Muted, fontSize = RiffType.detail, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = RiffSpace.tight, top = RiffSpace.item))
