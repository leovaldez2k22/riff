package com.musicglass.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File

/**
 * Persisted window placement, mode, and geometry record.
 * Conforms to M2 desktop ergonomics requirements.
 */
internal data class WindowGeometryRecord(
    val mainWidth: Float = 1280f,
    val mainHeight: Float = 800f,
    val mainX: Float? = null,
    val mainY: Float? = null,
    val isMaximized: Boolean = false,
    val miniX: Float? = null,
    val miniY: Float? = null,
    val isAlwaysOnTop: Boolean = false,
    val lastMode: WindowMode = WindowMode.Main,
    val sidebarWidthDp: Float = 224f,
    val inspectorWidthDp: Float = 360f
)

/**
 * Manages persistent window state across application restarts and screen configuration changes.
 * Safely clamps restored coordinates into the primary screen's work area if external monitors were removed.
 */
internal class DesktopWindowStateManager(
    private val stateFile: File = if(System.getProperty("riff.data.dir")!=null) File(DesktopPaths.root,"window-state.json") else File(System.getProperty("user.home"), ".riff/desktop-window-state.json"),
    private val workAreas: () -> List<Rectangle> = ::desktopWorkAreas
) {
    init {
        stateFile.parentFile?.mkdirs()
    }

    fun loadState(): WindowGeometryRecord {
        if (!stateFile.exists() || stateFile.length() == 0L) {
            return defaultRecord()
        }

        return try {
            val content = stateFile.readText(Charsets.UTF_8)
            val map = parseSimpleJson(content)

            var w = (map["mainWidth"]?.toDoubleOrNull() ?: 1280.0).toFloat().coerceAtLeast(800f)
            var h = (map["mainHeight"]?.toDoubleOrNull() ?: 800.0).toFloat().coerceAtLeast(600f)
            var x: Float? = map["mainX"]?.toDoubleOrNull()?.toFloat()
            var y: Float? = map["mainY"]?.toDoubleOrNull()?.toFloat()

            // The entire frame, including the playback dock, must fit a real work area.
            val clamped=clampToScreenWorkArea(x?.toInt()?:0,y?.toInt()?:0,w.toInt(),h.toInt())
            x=clamped.x.toFloat();y=clamped.y.toFloat();w=clamped.width.toFloat();h=clamped.height.toFloat()

            val mode = when (map["lastMode"]) {
                "MiniCompact" -> WindowMode.MiniCompact
                "MiniExpanded" -> WindowMode.MiniExpanded
                else -> WindowMode.Main
            }

            WindowGeometryRecord(
                mainWidth = w,
                mainHeight = h,
                mainX = x,
                mainY = y,
                isMaximized = map["isMaximized"] == "true",
                miniX = map["miniX"]?.toDoubleOrNull()?.toFloat(),
                miniY = map["miniY"]?.toDoubleOrNull()?.toFloat(),
                isAlwaysOnTop = map["isAlwaysOnTop"] == "true",
                lastMode = mode,
                sidebarWidthDp = (map["sidebarWidthDp"]?.toDoubleOrNull() ?: 224.0).toFloat(),
                inspectorWidthDp = (map["inspectorWidthDp"]?.toDoubleOrNull() ?: 360.0).toFloat()
            )
        } catch (_: Exception) {
            defaultRecord()
        }
    }

    fun saveState(record: WindowGeometryRecord) {
        try {
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"mainWidth\": ${record.mainWidth},\n")
            sb.append("  \"mainHeight\": ${record.mainHeight},\n")
            record.mainX?.let { sb.append("  \"mainX\": $it,\n") }
            record.mainY?.let { sb.append("  \"mainY\": $it,\n") }
            sb.append("  \"isMaximized\": ${record.isMaximized},\n")
            record.miniX?.let { sb.append("  \"miniX\": $it,\n") }
            record.miniY?.let { sb.append("  \"miniY\": $it,\n") }
            sb.append("  \"isAlwaysOnTop\": ${record.isAlwaysOnTop},\n")
            sb.append("  \"lastMode\": \"${record.lastMode.name}\",\n")
            sb.append("  \"sidebarWidthDp\": ${record.sidebarWidthDp},\n")
            sb.append("  \"inspectorWidthDp\": ${record.inspectorWidthDp}\n")
            sb.append("}\n")
            stateFile.writeText(sb.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /**
     * Clamps window position and dimensions into current monitor work area.
     * Prevents windows from rendering off-screen after monitor disconnection.
     */
    private fun defaultRecord():WindowGeometryRecord {
        val rect=clampToScreenWorkArea(0,0,1280,800)
        return WindowGeometryRecord(mainWidth=rect.width.toFloat(),mainHeight=rect.height.toFloat(),mainX=rect.x.toFloat(),mainY=rect.y.toFloat())
    }
    fun clampToScreenWorkArea(x: Int, y: Int, width: Int, height: Int): Rectangle {
        val areas=workAreas().filter{it.width>0&&it.height>0}
        if(areas.isEmpty())return Rectangle(x,y,width,height)
        val candidate=Rectangle(x,y,width,height)
        val selected=areas.maxByOrNull{area->val overlap=area.intersection(candidate);if(overlap.isEmpty)0L else overlap.width.toLong()*overlap.height}?:areas.first()
        val w=width.coerceIn(1,selected.width);val h=height.coerceIn(1,selected.height)
        return Rectangle(x.coerceIn(selected.x,selected.x+selected.width-w),y.coerceIn(selected.y,selected.y+selected.height-h),w,h)
    }

}

internal fun parseSimpleJson(json: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val pattern = Regex("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|[0-9.-]+|true|false)")
    for (match in pattern.findAll(json)) {
        val key = match.groupValues[1]
        val rawVal = match.groupValues[2]
        val cleanVal = if (rawVal.startsWith("\"") && rawVal.endsWith("\"")) {
            rawVal.substring(1, rawVal.length - 1)
        } else rawVal
        result[key] = cleanVal
    }
    return result
}

private fun desktopWorkAreas():List<Rectangle> = try {
    val environment=GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary=environment.defaultScreenDevice
    environment.screenDevices.sortedBy{if(it==primary)0 else 1}.map{device->
        val config=device.defaultConfiguration;val bounds=config.bounds
        val insets=java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
        Rectangle(bounds.x+insets.left,bounds.y+insets.top,bounds.width-insets.left-insets.right,bounds.height-insets.top-insets.bottom)
    }
} catch(_:java.awt.HeadlessException){emptyList()}
