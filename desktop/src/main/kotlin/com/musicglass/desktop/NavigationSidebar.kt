package com.musicglass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NavigationSidebar(
    currentRoute: WorkspaceRoute,
    isCompactRail: Boolean,
    font: FontFamily,
    onNavigate: (WorkspaceRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val backdrop = rememberRiffBackdrop()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .riffBackdropSource(backdrop)
            .background(RiffDesign.raised)
            .padding(vertical = 16.dp, horizontal = if (isCompactRail) 8.dp else 12.dp),
        horizontalAlignment = if (isCompactRail) Alignment.CenterHorizontally else Alignment.Start
    ) {
        // App header / Logo mark
        if (!isCompactRail) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(RiffDesign.pink, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(12.dp).background(Color.White, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Label("RIFF", font, Color.White, size = 20)
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(RiffDesign.pink, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(14.dp).background(Color.White, CircleShape))
            }
            Spacer(Modifier.height(20.dp))
        }

        // Navigation routes (primary sections)
        val primaryRoutes = listOf(
            WorkspaceRoute.Home,
            WorkspaceRoute.Search,
            WorkspaceRoute.Library,
            WorkspaceRoute.Podcasts,
            WorkspaceRoute.Radio,
            WorkspaceRoute.Settings,
            WorkspaceRoute.Encore
        )
        primaryRoutes.forEach { route ->
            val isSelected = currentRoute == route
            SidebarItem(
                route = route,
                isSelected = isSelected,
                isCompact = isCompactRail,
                font = font,
                onClick = { onNavigate(route) }
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))

        // Mini shortcut guide on expanded sidebar
        if (!isCompactRail) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Label("Search shortcut", font, RiffDesign.muted, size = 13)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Label("Ctrl + K", font, Color.White, size = 12)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    route: WorkspaceRoute,
    isSelected: Boolean,
    isCompact: Boolean,
    font: FontFamily,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemShape = RoundedCornerShape(14.dp)

    val backgroundModifier = if (isSelected) {
        Modifier
            .background(Color.White.copy(alpha = 0.12f), itemShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), itemShape)
    } else if (isFocused) {
        Modifier
            .background(Color.White.copy(alpha = 0.06f), itemShape)
            .border(1.dp, RiffDesign.pink.copy(alpha = 0.6f), itemShape)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 56.dp else 48.dp)
            .clip(itemShape)
            .then(backgroundModifier)
            .clickable(role = Role.Tab, onClickLabel = route.label, onClick = onClick)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .semantics {
                role = Role.Tab
                selected = isSelected
                contentDescription = route.label
            }
            .padding(horizontal = if (isCompact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isCompact) Arrangement.Center else Arrangement.Start
    ) {
        // Icon indicator
        val iconColor = if (isSelected) RiffDesign.pink else if (isFocused) Color.White else RiffDesign.muted
        Box(
            modifier = Modifier
                .size(if (isCompact) 28.dp else 24.dp),
            contentAlignment = Alignment.Center
        ) {
            SidebarIcon(route = route, color = iconColor)
        }

        if (!isCompact) {
            Spacer(Modifier.width(16.dp))
            val textColor = if (isSelected) Color.White else RiffDesign.muted
            Label(
                text = route.label,
                font = font,
                color = textColor,
                size = 15
            )
        }
    }
}

@Composable
private fun SidebarIcon(route: WorkspaceRoute, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.8.dp.toPx()
        when (route) {
            WorkspaceRoute.Home -> {
                // House outline
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.15f)
                    lineTo(size.width * 0.85f, size.height * 0.45f)
                    lineTo(size.width * 0.75f, size.height * 0.45f)
                    lineTo(size.width * 0.75f, size.height * 0.85f)
                    lineTo(size.width * 0.25f, size.height * 0.85f)
                    lineTo(size.width * 0.25f, size.height * 0.45f)
                    lineTo(size.width * 0.15f, size.height * 0.45f)
                    close()
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            WorkspaceRoute.Search -> {
                // Magnifying glass
                drawCircle(color, radius = size.width * 0.3f, center = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.64f, size.height * 0.64f), androidx.compose.ui.geometry.Offset(size.width * 0.88f, size.height * 0.88f), strokeWidth = stroke * 1.2f)
            }
            WorkspaceRoute.Library -> {
                // Shelf books / list
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.15f, size.height * 0.2f), size = androidx.compose.ui.geometry.Size(size.width * 0.7f, size.height * 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.4f), androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.4f), strokeWidth = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.6f), androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.6f), strokeWidth = stroke)
            }
            WorkspaceRoute.Settings -> {
                // Gear / slider toggles
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.35f), androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.35f), strokeWidth = stroke)
                drawCircle(color, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.4f, size.height * 0.35f))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.65f), androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.65f), strokeWidth = stroke)
                drawCircle(color, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.65f, size.height * 0.65f))
            }
            WorkspaceRoute.Podcasts -> {
                // Microphone icon
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 0.15f), size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.3f), size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.75f), androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.9f), strokeWidth = stroke)
            }
            WorkspaceRoute.Radio -> {
                // Radio broadcast waves
                drawCircle(color, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f))
                drawArc(color, -60f, 120f, false, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.2f), size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(color, 120f, 120f, false, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.2f), size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            WorkspaceRoute.Encore -> {
                // Sparkle / Star
                val center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f)
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x, size.height * 0.15f), androidx.compose.ui.geometry.Offset(center.x, size.height * 0.85f), strokeWidth = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.15f, center.y), androidx.compose.ui.geometry.Offset(size.width * 0.85f, center.y), strokeWidth = stroke)
                drawCircle(color, radius = 2.dp.toPx(), center = center)
            }
            else -> {
                // Generic music note / detail disc
                drawCircle(color, radius = size.width * 0.35f, center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(color, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f))
            }
        }
    }
}
