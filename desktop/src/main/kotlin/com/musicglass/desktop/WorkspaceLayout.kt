package com.musicglass.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Responsive desktop spatial configuration computed from available content dimensions. */
internal data class WorkspaceConstraints(
    val windowWidth: Dp,
    val windowHeight: Dp,
    val isCompactRail: Boolean,
    val sidebarWidth: Dp,
    val mainStageWidth: Dp,
    val isInspectorVisible: Boolean,
    val isNarrowInspectorOverlay: Boolean,
    val inspectorWidth: Dp,
    val mainContentMaxWidth: Dp,
    val sidebarDividerWidth: Dp = 8.dp,
    val inspectorDividerWidth: Dp = 8.dp,
    val dockHeight: Dp = 88.dp,
    val titlebarHeight: Dp = 44.dp
)

internal object WorkspaceLayoutCalculator {
    const val MIN_WINDOW_WIDTH = 800
    const val MIN_WINDOW_HEIGHT = 600
    const val MIN_MAIN_STAGE_WIDTH = 480
    const val EXPANDED_MAIN_STAGE_MIN_WIDTH = 640
    const val RAIL_WIDTH = 80
    const val SIDEBAR_DEFAULT_WIDTH = 224
    const val SIDEBAR_MIN_WIDTH = 180
    const val SIDEBAR_MAX_WIDTH = 320
    const val INSPECTOR_DEFAULT_WIDTH = 360
    const val INSPECTOR_MIN_WIDTH = 280
    const val INSPECTOR_MAX_WIDTH = 480
    const val DIVIDER_WIDTH = 8
    const val MAX_CENTER_CONTENT_WIDTH = 1280

    fun calculate(
        windowWidth: Dp,
        windowHeight: Dp,
        desiredInspectorTab: InspectorTab,
        userSidebarWidth: Dp = SIDEBAR_DEFAULT_WIDTH.dp,
        userInspectorWidth: Dp = INSPECTOR_DEFAULT_WIDTH.dp
    ): WorkspaceConstraints {
        val widthPx = windowWidth.value
        val isCompactRail = widthPx < 1024f

        val effectiveSidebarWidth = if (isCompactRail) {
            RAIL_WIDTH.dp
        } else {
            userSidebarWidth.coerceIn(SIDEBAR_MIN_WIDTH.dp, SIDEBAR_MAX_WIDTH.dp)
        }

        val sidebarDividerWidth = if (isCompactRail) 0.dp else DIVIDER_WIDTH.dp

        // Required main stage minimum depends on window scale (640dp on >=1440dp, 480dp otherwise)
        val requiredStageMin = if (widthPx >= 1440f) EXPANDED_MAIN_STAGE_MIN_WIDTH else MIN_MAIN_STAGE_WIDTH

        val wantsInspector = desiredInspectorTab != InspectorTab.None
        val clampedInspectorWidth = userInspectorWidth.coerceIn(INSPECTOR_MIN_WIDTH.dp, INSPECTOR_MAX_WIDTH.dp)

        // Potential main stage width including divider allocations
        val potentialStageWidth = widthPx - effectiveSidebarWidth.value - sidebarDividerWidth.value -
                (if (wantsInspector) (clampedInspectorWidth.value + DIVIDER_WIDTH) else 0f)

        // Docked inspector is allowed if the remaining stage meets or exceeds required threshold
        val canShowDockedInspector = wantsInspector && (potentialStageWidth >= requiredStageMin)

        // If inspector cannot be docked due to width constraints, activate narrow overlay sheet
        val isNarrowInspectorOverlay = wantsInspector && !canShowDockedInspector

        val actualInspectorWidth = if (canShowDockedInspector) clampedInspectorWidth else 0.dp
        val inspectorDividerWidth = if (canShowDockedInspector) DIVIDER_WIDTH.dp else 0.dp

        val actualMainStageWidth = (widthPx - effectiveSidebarWidth.value - sidebarDividerWidth.value -
                actualInspectorWidth.value - inspectorDividerWidth.value).coerceAtLeast(MIN_MAIN_STAGE_WIDTH.toFloat()).dp

        val contentMaxWidth = if (widthPx >= 1920f) {
            MAX_CENTER_CONTENT_WIDTH.dp
        } else {
            Dp.Unspecified
        }

        return WorkspaceConstraints(
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            isCompactRail = isCompactRail,
            sidebarWidth = effectiveSidebarWidth,
            mainStageWidth = actualMainStageWidth,
            isInspectorVisible = canShowDockedInspector,
            isNarrowInspectorOverlay = isNarrowInspectorOverlay,
            inspectorWidth = actualInspectorWidth,
            mainContentMaxWidth = contentMaxWidth,
            sidebarDividerWidth = sidebarDividerWidth,
            inspectorDividerWidth = inspectorDividerWidth
        )
    }
}
