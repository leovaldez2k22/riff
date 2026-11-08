package com.musicglass.personal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The profile file a user picked, read off the main thread and fitted; null when it is not an AutoEq file. */
private fun readPickedProfile(context: android.content.Context, uri: Uri): Pair<AutoEqTarget, AutoEqFit>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8 * 1024); var total = 0
        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > AUTOEQ_MAX_BYTES) return@runCatching null; out.write(buffer, 0, n) }
        out.toByteArray()
    } ?: return@runCatching null
    val target = readAutoEq(name, bytes.toString(Charsets.UTF_8)) ?: return@runCatching null
    target to fitAutoEq(target)
}.getOrNull()

internal fun fitQualityLabel(fit: AutoEqFit) = string(when (fit.quality) {
    FitQuality.Good -> R.string.autoeq_fit_good; FitQuality.Fair -> R.string.autoeq_fit_fair; FitQuality.Poor -> R.string.autoeq_fit_poor
}, "%.1f".format(java.util.Locale.ROOT, fit.errorDb))

/** What the preview shows: the profile's own curve when it came from a file, its measurer when it came from the list. */
private data class ProfilePreview(val fit: AutoEqFit, val target: FloatArray? = null, val skipped: Int = 0, val entry: AutoEqEntry? = null)

/**
 * Feature 11 (Riff+): a headphone profile, found in the bundled AutoEq list or imported from an AutoEq file: preview
 * its fit, apply it, and revert to the curve before it.
 */
@Composable internal fun HeadphoneProfileCard(config: EqualizerConfig, change: (EqualizerConfig) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val applied = remember(revision, config) { HeadphoneProfile.name(context) }
    var reading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ProfilePreview?>(null) }
    var query by remember { mutableStateOf("") }
    var library by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var matches by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    // The list is read only once the listener starts searching, and searched off the main thread.
    LaunchedEffect(query.isNotBlank()) { if (query.isNotBlank() && library == null) library = withContext(Dispatchers.IO) { AutoEqLibrary.load(context) } }
    LaunchedEffect(query, library) { val all = library; matches = if (all == null) emptyList() else withContext(Dispatchers.Default) { AutoEqLibrary.search(all, query) } }
    var notice by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val read = withContext(Dispatchers.Default) { readPickedProfile(context, uri) }
            reading = false
            if (read == null) notice = string(R.string.autoeq_unreadable) else { notice = null; preview = ProfilePreview(read.second, read.first.curve, read.first.skipped) }
        }
    }
    Column(Modifier.testTag("headphone_profile")) {
        Text(string(R.string.autoeq_title), color = White, fontSize = RiffType.rowTitle)
        if (applied != null) {
            Text(string(R.string.autoeq_applied, applied, "%.1f".format(java.util.Locale.ROOT, HeadphoneProfile.errorDb(context))), color = Muted,
                fontSize = RiffType.detail, modifier = Modifier.testTag("headphone_profile_applied"))
        }
        Text(string(R.string.autoeq_help), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
        OutlinedTextField(query, { query = it.take(80) }, singleLine = true, shape = RoundedCornerShape(24.dp),
            label = { Text(string(R.string.autoeq_search), fontSize = RiffType.detail) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = White.copy(alpha = .65f)) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, string(R.string.clear_search), tint = White) } },
            textStyle = LocalTextStyle.current.copy(color = White),
            modifier = Modifier.fillMaxWidth().padding(top = RiffSpace.item).testTag("headphone_profile_search"))
        if (query.isNotBlank()) {
            when {
                library == null -> Text(string(R.string.loading), color = Muted, fontSize = RiffType.detail)
                matches.isEmpty() -> Text(string(R.string.autoeq_no_match), color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("headphone_profile_no_match"))
                else -> matches.forEach { entry ->
                    Column(Modifier.fillMaxWidth().clickable { uiHaptic(); preview = ProfilePreview(entry.fit(), entry = entry) }
                        .padding(vertical = RiffSpace.tight).testTag("headphone_profile_match")) {
                        Text(entry.name, color = White, fontSize = RiffType.rowTitle)
                        Text(string(R.string.autoeq_measured_by, entry.source, entry.rig), color = Muted, fontSize = RiffType.detail)
                    }
                }
            }
        }
        Text(string(R.string.autoeq_credit), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight).testTag("headphone_profile_credit"))
        notice?.let { Text(it, color = Pink, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight)) }
        Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
            TextButton({ uiHaptic(); runCatching { pick.launch(arrayOf("text/*", "application/octet-stream")) } }, enabled = !reading,
                modifier = Modifier.testTag("headphone_profile_import")) { Text(string(if (reading) R.string.loading else R.string.autoeq_import)) }
            if (applied != null) TextButton({ uiHaptic(HapticCue.Confirm); change(HeadphoneProfile.revert(context, config)); revision++ },
                modifier = Modifier.testTag("headphone_profile_revert")) { Text(string(R.string.autoeq_revert)) }
        }
    }
    preview?.let { shown ->
        val fit = shown.fit
        FrostedAlertDialog(onDismissRequest = { preview = null }, title = { Text(shown.entry?.name ?: fit.name) },
            text = { Column(Modifier.testTag("headphone_profile_preview")) {
                shown.entry?.let { Text(string(R.string.autoeq_measured_by, it.source, it.rig), color = Muted, fontSize = RiffType.detail) }
                FitPreview(shown.target, eqCurve(eqSections(EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = fit.gains))))
                Text(fitQualityLabel(fit), color = if (fit.quality == FitQuality.Poor) Pink else White, modifier = Modifier.testTag("headphone_profile_fit"))
                Text(string(if (shown.target != null) R.string.autoeq_preview_help else R.string.autoeq_preview_help_bundled), color = Muted,
                    fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                if (shown.skipped > 0) Text(quantity(R.plurals.n_autoeq_skipped, shown.skipped), color = Muted, fontSize = RiffType.detail)
            } },
            confirmButton = { TextButton({ uiHaptic(HapticCue.Confirm); change(HeadphoneProfile.apply(context, fit, config)); preview = null; revision++ },
                modifier = Modifier.testTag("headphone_profile_apply")) { Text(string(R.string.autoeq_apply)) } },
            dismissButton = { TextButton({ preview = null }) { Text(string(R.string.cancel)) } })
    }
}

/** The profile (white, faint; only for an imported file) and what the seven bands make of it (pink), each about its own middle. */
@Composable private fun FitPreview(target: FloatArray?, fitted: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = RiffSpace.item)) {
        val mid = size.height / 2; val range = 15f
        fun draw(curve: FloatArray, colour: Color) {
            val judged = curve.indices.filter { eqProbeFrequencies[it] <= 10_000f }
            val mean = judged.map { curve[it] }.average().toFloat()
            val path = Path()
            curve.forEachIndexed { i, db ->
                val y = mid - ((db - mean).coerceIn(-range, range) / range) * mid; val x = i / (curve.size - 1f) * size.width
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colour, style = Stroke(2.dp.toPx()))
        }
        drawLine(White.copy(alpha = .12f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        target?.let { draw(it, White.copy(alpha = .45f)) }; draw(fitted, Pink)
    }
}
