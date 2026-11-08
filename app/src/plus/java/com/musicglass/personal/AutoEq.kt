package com.musicglass.personal

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * Feature 11 (Riff+, owner Q11): an AutoEq headphone profile, read from the ParametricEQ or GraphicEQ text file that
 * AutoEq publishes for each headphone, and fitted to Riff's seven manual bands (fixed centres and widths, shelves at
 * the ends). Seven bands cannot copy every profile, so the fit's error is measured and shown; a poor fit says so.
 */

/** The curve a profile asks for, at [eqProbeFrequencies], in dB; [name] from the file; [skipped] lines not understood. */
internal data class AutoEqTarget(val name: String, val curve: FloatArray, val skipped: Int)

internal enum class FitQuality { Good, Fair, Poor }

/** Seven gains for the manual bands, and how far from the profile they land (RMS dB, 20 Hz to 10 kHz). */
internal data class AutoEqFit(val name: String, val gains: List<Float>, val errorDb: Float) {
    val quality get() = when { errorDb <= 1f -> FitQuality.Good; errorDb <= 2f -> FitQuality.Fair; else -> FitQuality.Poor }
}

/** Longest file read, and the most filters or points taken from it. */
internal const val AUTOEQ_MAX_BYTES = 256 * 1024
private const val AUTOEQ_MAX_FILTERS = 64
private const val AUTOEQ_MAX_POINTS = 2_000
/** The fit is judged where headphones are measured reliably; above 10 kHz the measurements themselves disagree. */
private const val FIT_TOP_HZ = 10_000f

private val number = "[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?"
private val filterLine = Regex("(?i)^\\s*Filter\\s*\\d*\\s*:\\s*ON\\s+([A-Z]+)\\s+Fc\\s+($number)\\s*Hz\\s+Gain\\s+($number)\\s*dB(?:\\s+Q\\s+($number))?")

/** Reads an AutoEq ParametricEQ.txt or GraphicEQ.txt; null when it is neither. [fileName] names the headphone. */
internal fun readAutoEq(fileName: String, text: String): AutoEqTarget? {
    if (text.length > AUTOEQ_MAX_BYTES) return null
    val name = fileName.substringBeforeLast('.').replace(Regex("(?i)\\s*(ParametricEQ|GraphicEQ|FixedBandEQ)$"), "").trim().take(120).ifBlank { "Headphones" }
    val graphic = text.lineSequence().firstOrNull { it.trim().startsWith("GraphicEQ:", ignoreCase = true) }
    if (graphic != null) {
        val points = graphic.substringAfter(':').split(';').mapNotNull { pair ->
            val parts = pair.trim().split(Regex("\\s+"))
            val f = parts.getOrNull(0)?.toFloatOrNull(); val g = parts.getOrNull(1)?.toFloatOrNull()
            if (f != null && g != null && f in 10f..30_000f && g.isFinite() && abs(g) <= 40f) f to g else null
        }.take(AUTOEQ_MAX_POINTS).sortedBy { it.first }.distinctBy { it.first }
        if (points.size < 2) return null
        return AutoEqTarget(name, FloatArray(eqProbeFrequencies.size) { interpolateLog(points, eqProbeFrequencies[it]) }, 0)
    }
    var skipped = 0
    val sections = text.lineSequence().filter { it.trim().startsWith("Filter", ignoreCase = true) }.mapNotNull { line ->
        val m = filterLine.find(line) ?: run { skipped++; return@mapNotNull null }
        val shape = when (m.groupValues[1].uppercase()) {
            "PK", "PEQ" -> EqShape.Bell; "LSC", "LS", "LSQ" -> EqShape.LowShelf; "HSC", "HS", "HSQ" -> EqShape.HighShelf
            else -> { skipped++; return@mapNotNull null }
        }
        val f = m.groupValues[2].toFloat(); val g = m.groupValues[3].toFloat(); val q = m.groupValues[4].toFloatOrNull() ?: .707f
        if (f !in 10f..30_000f || abs(g) > 40f || q !in .05f..20f) { skipped++; null } else EqSection(shape, f, q, g)
    }.take(AUTOEQ_MAX_FILTERS).toList()
    if (sections.isEmpty()) return null
    return AutoEqTarget(name, eqCurve(sections), skipped)
}

/** Linear in dB between points spaced on a log-frequency axis; flat past either end. */
private fun interpolateLog(points: List<Pair<Float, Float>>, f: Float): Float {
    if (f <= points.first().first) return points.first().second
    if (f >= points.last().first) return points.last().second
    val i = points.indexOfFirst { it.first >= f }
    val (f0, g0) = points[i - 1]; val (f1, g1) = points[i]
    val t = (ln(f.toDouble()) - ln(f0.toDouble())) / (ln(f1.toDouble()) - ln(f0.toDouble()))
    return (g0 + (g1 - g0) * t).toFloat()
}

private fun manualCurve(gains: FloatArray, sampleRate: Int): FloatArray =
    eqCurve(eqSections(EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = gains.toList())), sampleRate)

/** RMS of [curve] − [target] over the judged range, after the best constant offset (Riff sets its own pre-amp). */
internal fun fitErrorDb(curve: FloatArray, target: FloatArray): Float {
    val judged = eqProbeFrequencies.indices.filter { eqProbeFrequencies[it] <= FIT_TOP_HZ }
    val offset = judged.sumOf { (target[it] - curve[it]).toDouble() } / judged.size
    return sqrt(judged.sumOf { val d = curve[it] + offset - target[it]; d * d } / judged.size).toFloat()
}

/**
 * Least squares on the seven gains with a free offset, by Gauss–Newton: a band's response in dB is nearly, not
 * exactly, proportional to its gain, so a few rounds settle it. Gains stay within ±12 dB.
 */
internal fun fitAutoEq(target: AutoEqTarget, sampleRate: Int = 48_000): AutoEqFit {
    val bands = EqLayout.manualFrequencies.size
    val judged = eqProbeFrequencies.indices.filter { eqProbeFrequencies[it] <= FIT_TOP_HZ }
    // The target less its mean over the judged range: the offset is free.
    val mean = judged.sumOf { target.curve[it].toDouble() } / judged.size
    val goal = DoubleArray(judged.size) { target.curve[judged[it]] - mean }
    val gains = FloatArray(bands)
    repeat(12) {
        val now = manualCurve(gains, sampleRate)
        val nowMean = judged.sumOf { now[it].toDouble() } / judged.size
        val residual = DoubleArray(judged.size) { goal[it] - (now[judged[it]] - nowMean) }
        val jac = Array(bands) { b ->
            val step = gains.copyOf().also { it[b] += .5f }
            val c = manualCurve(step, sampleRate); val cMean = judged.sumOf { c[it].toDouble() } / judged.size
            DoubleArray(judged.size) { ((c[judged[it]] - cMean) - (now[judged[it]] - nowMean)) / .5 }
        }
        // Normal equations, with a little damping so near-flat bands stay put.
        val a = Array(bands) { i -> DoubleArray(bands) { j -> jac[i].indices.sumOf { jac[i][it] * jac[j][it] } + if (i == j) 1e-3 else 0.0 } }
        val r = DoubleArray(bands) { i -> jac[i].indices.sumOf { jac[i][it] * residual[it] } }
        val delta = solve(a, r) ?: return@repeat
        for (b in 0 until bands) gains[b] = (gains[b] + delta[b].toFloat()).coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
    }
    val rounded = gains.map { Math.round(it * 10) / 10f }
    return AutoEqFit(target.name, rounded, fitErrorDb(manualCurve(rounded.toFloatArray(), sampleRate), target.curve))
}

/** Gaussian elimination with partial pivoting; null when singular. */
private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size; val m = Array(n) { a[it].copyOf() + b[it] }
    for (c in 0 until n) {
        val p = (c until n).maxBy { abs(m[it][c]) }
        if (abs(m[p][c]) < 1e-12) return null
        val t = m[c]; m[c] = m[p]; m[p] = t
        for (r in c + 1 until n) { val f = m[r][c] / m[c][c]; for (k in c..n) m[r][k] -= f * m[c][k] }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) x[r] = (m[r][n] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
    return x
}

/** The applied profile and the curve it replaced, so Revert gives the listener's own back. */
internal object HeadphoneProfile {
    private fun prefs(context: android.content.Context) = context.applicationContext.getSharedPreferences("headphone_profile", android.content.Context.MODE_PRIVATE)
    fun name(context: android.content.Context): String? = prefs(context).getString("name", null)
    fun errorDb(context: android.content.Context): Float = prefs(context).getFloat("error", 0f)

    /** Applies [fit] as the manual curve. The curve before it is kept only the first time, so a second profile still reverts to the listener's. */
    fun apply(context: android.content.Context, fit: AutoEqFit, current: EqualizerConfig): EqualizerConfig {
        val p = prefs(context); val edit = p.edit()
        if (!p.contains("before_manual")) edit.putString("before_manual", current.manual.joinToString(",")).putString("before_mode", current.mode.key)
            .putString("before_preset", current.preset).putBoolean("before_enabled", current.enabled)
        edit.putString("name", fit.name).putFloat("error", fit.errorDb).apply()
        return current.copy(enabled = true, mode = EqMode.Manual, manual = fit.gains, preset = "Custom")
    }

    fun revert(context: android.content.Context, current: EqualizerConfig): EqualizerConfig {
        val p = prefs(context)
        val back = if (!p.contains("before_manual")) current else current.copy(
            manual = EqualizerSettings.parseManual(p.getString("before_manual", null)),
            mode = EqMode.entries.firstOrNull { it.key == p.getString("before_mode", null) } ?: current.mode,
            preset = p.getString("before_preset", null)?.takeIf { it in EQ_PRESETS.keys || it == "Custom" } ?: "Custom",
            enabled = p.getBoolean("before_enabled", current.enabled))
        p.edit().clear().apply()
        return back
    }

    /** The listener moved a fader: the curve is theirs now, and Revert has nothing to give back. */
    fun forget(context: android.content.Context) { prefs(context).edit().clear().apply() }
}
