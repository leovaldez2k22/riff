package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil

// Q18-013: the agreed sign-in design (docs/quality/sign-in-design.md): the record, Riff's glass, saved accounts.

/**
 * An account used on this phone. Riff keeps who it was and when, never its sign-in cookies. [scope] is the account's
 * opaque Riff scope (a digest, never a raw account id) and [index] Google's position for it in the web session when
 * last seen; both let Riff switch to it without Google's page (Q18-087).
 */
internal data class SavedAccount(val key: String, val name: String, val email: String?, val lastUsedMs: Long,
    val scope: String? = null, val index: String? = null)

internal const val SAVED_ACCOUNTS_LIMIT = 20

/** An account Google's web session holds, as Riff may keep it: the address, its position and its opaque scope. */
internal data class SessionAccount(val email: String?, val index: String, val scope: String?, val signedIn: Boolean)

/** The short list of accounts used on this phone, most recent first. Removing one never touches the Google account. */
internal class SavedAccounts(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("saved_accounts", Context.MODE_PRIVATE))
    var revision by mutableIntStateOf(0); private set

    fun all(): List<SavedAccount> = merged(runCatching {
        val list = JSONArray(prefs.getString("accounts", "[]"))
        (0 until list.length()).map { list.getJSONObject(it) }.map {
            SavedAccount(it.getString("key"), it.optString("name"), it.optString("email").takeIf(String::isNotBlank), it.optLong("used"),
                it.optString("scope").takeIf(String::isNotBlank), it.optString("index").takeIf(String::isNotBlank))
        }
    }.getOrDefault(emptyList()))

    /**
     * Q18-085: one account is one row. A row saved before the account's address was known (keyed by its scope) and
     * one saved after (keyed by the address) are the same account when they share the scope or the address; [aliases]
     * are other keys this account was saved under, such as its storage key.
     */
    fun used(key: String, name: String, email: String?, nowMs: Long, scope: String? = null, index: String? = null,
        aliases: Set<String> = emptySet()) {
        val rows = all()
        val same = rows.filter { it.key == key || it.key in aliases || sameAccount(it, email, scope) }
        val previous = same.maxByOrNull { it.lastUsedMs }
        val known = email ?: same.firstNotNullOfOrNull { it.email }
        val kept = scope ?: same.firstNotNullOfOrNull { it.scope }
        val account = SavedAccount(known ?: kept ?: key, name.ifBlank { previous?.name.orEmpty() }, known, nowMs, kept,
            index ?: previous?.index)
        save(listOf(account) + (rows - same.toSet()))
    }

    /** What Google's web session says: fills a missing address and the position of each account Riff already has. */
    fun learn(session: List<SessionAccount>) {
        val rows = all()
        val updated = rows.map { row ->
            val found = session.firstOrNull { it.scope != null && it.scope == row.scope }
                ?: session.firstOrNull { it.email != null && it.email.equals(row.email, ignoreCase = true) }
                ?: return@map row
            row.copy(key = if (row.email == null && found.email != null) found.email else row.key,
                email = row.email ?: found.email, scope = row.scope ?: found.scope, index = found.index)
        }
        if (updated != rows) save(updated)
    }

    fun remove(key: String) = save(all().filter { it.key != key })

    /** Q18-086: signing out of all accounts leaves none on the list. */
    fun clear() = save(emptyList())

    private fun save(accounts: List<SavedAccount>) {
        val list = JSONArray()
        merged(accounts).take(SAVED_ACCOUNTS_LIMIT).forEach {
            list.put(JSONObject().put("key", it.key).put("name", it.name).put("email", it.email ?: "").put("used", it.lastUsedMs)
                .put("scope", it.scope ?: "").put("index", it.index ?: ""))
        }
        prefs.edit().putString("accounts", list.toString()).apply()
        revision++
    }

    private companion object {
        fun sameAccount(row: SavedAccount, email: String?, scope: String?) =
            (email != null && row.email.equals(email, ignoreCase = true)) || (scope != null && (row.scope == scope || row.key == scope))

        /** Rows that are one account become one, the most recent first, with every detail either row knew. */
        fun merged(rows: List<SavedAccount>): List<SavedAccount> {
            val out = mutableListOf<SavedAccount>()
            for (row in rows.sortedByDescending { it.lastUsedMs }) {
                val at = out.indexOfFirst { it.key == row.key || sameAccount(it, row.email, row.scope) || sameAccount(row, it.email, it.scope) }
                if (at < 0) { out += row; continue }
                val kept = out[at]
                val email = kept.email ?: row.email
                out[at] = kept.copy(key = if (kept.email == null && email != null) email else kept.key,
                    name = kept.name.ifBlank { row.name }, email = email, scope = kept.scope ?: row.scope, index = kept.index ?: row.index)
            }
            return out
        }
    }
}

/** When an account was last used, in words. */
internal fun lastUsedLabel(nowMs: Long, thenMs: Long): String {
    val days = ((nowMs - thenMs).coerceAtLeast(0) / 86_400_000L).toInt()
    return when {
        days == 0 -> string(R.string.signin_used_today)
        days == 1 -> string(R.string.signin_used_yesterday)
        days < 14 -> string(R.string.signin_used_days, days)
        days < 60 -> string(R.string.signin_used_weeks, days / 7)
        else -> string(R.string.signin_used_long)
    }
}

private val avatarColors = listOf(Color(0xFFFF5EAC), Color(0xFF6B4CFF), Color(0xFF2EC4B6), Color(0xFFFFB347),
    Color(0xFF4D9DE0), Color(0xFFE15554), Color(0xFF7BC950), Color(0xFFB07BFF))
internal fun avatarColor(key: String): Color = avatarColors[abs(key.hashCode() % avatarColors.size)]

/** Lengths of the agreed transitions, in ms: 1.5 times the first draft, as the owner chose. */
internal fun signInTransitionMs(from: SignInPhase, to: SignInPhase): Int = when (from to to) {
    SignInPhase.Welcome to SignInPhase.AllAccounts -> 2000
    SignInPhase.AllAccounts to SignInPhase.Welcome -> 1900
    SignInPhase.AllAccounts to SignInPhase.Google -> 2000
    SignInPhase.Welcome to SignInPhase.Google -> 2200
    SignInPhase.Google to SignInPhase.Welcome -> 2100
    SignInPhase.Google to SignInPhase.NoLoad -> 2900
    SignInPhase.NoLoad to SignInPhase.Google -> 2300
    SignInPhase.Google to SignInPhase.Checking -> 3800
    SignInPhase.Checking to SignInPhase.Connected -> 5100
    else -> 1200
}
internal const val SIGN_IN_STOP_SHEET_IN_MS = 1700
/** Q18-039: Checking's three ticks come no closer together than this, the first after Checking's own entry. */
internal const val SIGN_IN_TICK_GAP_MS = 1200L
internal const val SIGN_IN_CHECKING_STEPS = 3

/** When step [step] (0, 1, 2) of Checking may tick at the earliest, counted from Checking's start; 0 with Remove animations. */
internal fun signInTickSlotMs(step: Int, reduced: Boolean): Long = if (reduced) 0L else
    signInTransitionMs(SignInPhase.Google, SignInPhase.Checking) + step * SIGN_IN_TICK_GAP_MS

/** The shortest Checking: its entry, the three ticks, and a beat on the last before the record moves on (about 7.4 s). */
internal fun signInCheckingMinimumMs(reduced: Boolean): Long =
    if (reduced) 0L else signInTickSlotMs(SIGN_IN_CHECKING_STEPS - 1, false) + SIGN_IN_TICK_GAP_MS

/**
 * Q18-039: plays Checking at its agreed pace however quickly the real work finishes. Step [step] ticks at the later of
 * [done] and its slot; a slow step is never made slower. Returns true once the last tick has had its beat, or false the
 * moment [failed] says the work could not finish (the failure is shown at once, not after the animation). [now] is the
 * clock the slots are counted on.
 */
internal suspend fun signInCheckingTimeline(reduced: Boolean, now: () -> Long, done: (Int) -> Boolean,
    failed: () -> Boolean, onTick: (Int) -> Unit): Boolean {
    val start = now()
    for (step in 0 until SIGN_IN_CHECKING_STEPS) {
        while (!done(step) || now() - start < signInTickSlotMs(step, reduced)) {
            if (failed()) return false
            kotlinx.coroutines.delay(SIGN_IN_TIMELINE_POLL_MS)
        }
        onTick(step + 1)
    }
    while (now() - start < signInCheckingMinimumMs(reduced)) {
        if (failed()) return false
        kotlinx.coroutines.delay(SIGN_IN_TIMELINE_POLL_MS)
    }
    return !failed()
}
private const val SIGN_IN_TIMELINE_POLL_MS = 50L
internal const val SIGN_IN_STOP_SHEET_OUT_MS = 1100

/** The colour backdrop behind every screen: pink, violet and light pink light, soft as if blurred. */
@Composable internal fun SignInBackdrop(brightness: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color.Black)
        fun blob(color: Color, x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = (alpha * brightness).coerceIn(0f, 1f)), Color.Transparent),
                Offset(size.width * x, size.height * y), size.width * radius), size.width * radius, Offset(size.width * x, size.height * y))
        blob(Color(0xFFFF5EAC), .12f, .1f, .85f, .42f)
        blob(Color(0xFF6B4CFF), .98f, .46f, .8f, .36f)
        blob(Color(0xFFFF9BD3), .28f, .98f, .75f, .28f)
    }
}

/** Feature 2: the sign-in screens' backdrop, recorded by the account screen, which their panels bend. */
internal val LocalSignInBackdrop = staticCompositionLocalOf<RiffBackdrop?> { null }

/**
 * Riff's glass on the sign-in panels: the Card lens over the sign-in backdrop (Feature 2), a dark tint that keeps the
 * words readable, a sheen from the top left and a thin light edge. Without a backdrop, the tint alone, a little darker.
 */
@Composable internal fun Modifier.signInGlass(corner: Dp = RiffSignIn.glass): Modifier {
    val shape = RoundedCornerShape(corner)
    val backdrop = LocalSignInBackdrop.current
    return (if (backdrop != null) riffGlass(GlassRole.Card, corner, backdrop = backdrop) else this).clip(shape)
        .background(Color(0xFF171C27).copy(alpha = if (backdrop != null) .45f else .63f))
        .background(Brush.linearGradient(listOf(White.copy(alpha = .13f), Color.Transparent), Offset.Zero, Offset(700f, 700f)))
        .border(RiffSignIn.glassEdge, White.copy(alpha = .15f), shape)
}

/**
 * The record: black grooves, a pink label reading "Riff", a small hole. It spins while something is working and
 * slows to a stop with "Riff" upright. [ring] draws the progress ring as part of the record, so the two always share
 * one centre. [grey] drains the colour (nothing loaded); [glow] and [aura] are the 5 to 6 celebration.
 */
@Composable internal fun RiffRecord(modifier: Modifier = Modifier, spinning: Boolean = false, ring: Float? = null,
    grey: Float = 0f, glow: Float = 0f, aura: Float = 0f, noSignal: Boolean = false) {
    val reduced = LocalMotionPolicy.current.reduced
    val turn = remember { Animatable(0f) }
    LaunchedEffect(spinning, reduced) {
        if (reduced) { turn.snapTo(0f); return@LaunchedEffect }
        if (spinning) while (isActive) turn.animateTo(turn.value + 360f, tween(1800, easing = LinearEasing))
        else {
            val upright = ceil(turn.value / 360f) * 360f
            val left = upright - turn.value
            if (left > 0f) turn.animateTo(upright, tween((left / 360f * 2600f).toInt().coerceAtLeast(300), easing = LinearOutSlowInEasing))
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val signal = rememberVectorPainter(Icons.Rounded.WifiOff)
    Canvas(modifier) {
        val r = size.minDimension * .42f
        val c = center
        if (glow > 0f) drawCircle(Brush.radialGradient(listOf(Pink.copy(alpha = .55f * glow), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        if (aura > 0f && aura < 1f) {
            val ar = r * (1f + aura * 1.4f)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Pink.copy(alpha = .45f * (1f - aura)), Color.Transparent), c, ar * 1.15f), ar * 1.15f, c)
        }
        // Disc and grooves.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF22222A), Color(0xFF09090C)), c, r), r, c)
        for (i in 1..7) drawCircle(White.copy(alpha = if (i % 2 == 0) .05f else .03f), r * (.48f + i * .068f), c, style = Stroke(r * .012f))
        drawCircle(White.copy(alpha = .12f), r, c, style = Stroke(r * .02f))
        val label = lerp(Pink, Color(0xFF6E6F78), grey.coerceIn(0f, 1f))
        val labelDeep = lerp(Color(0xFFF45B9A), Color(0xFF55565E), grey.coerceIn(0f, 1f))
        rotate(turn.value, c) {
            // A light reflection that turns with the record, so the spin is visible.
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, White.copy(alpha = .09f), Color.Transparent), c), -60f, 70f, true,
                Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
            drawCircle(Brush.linearGradient(listOf(label, labelDeep), Offset(c.x - r * .36f, c.y - r * .36f), Offset(c.x + r * .36f, c.y + r * .36f)), r * .36f, c)
            if (!noSignal) {
                val text = measurer.measure("Riff", TextStyle(color = Color(0xFF14070D), fontSize = (r * .2f).toSp(), fontWeight = FontWeight.Bold))
                drawText(text, topLeft = Offset(c.x - text.size.width / 2f, c.y - r * .1f - text.size.height))
            }
        }
        if (noSignal) {
            val s = r * .3f
            translate(c.x - s / 2, c.y - s / 2) { with(signal) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color(0xFF14070D))) } }
        }
        if (!noSignal) drawCircle(Color.Black, r * .045f, c)
        ring?.let { progress ->
            val ringR = size.minDimension * .49f
            drawCircle(White.copy(alpha = .1f), ringR, c, style = Stroke(r * .035f))
            drawArc(Pink, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(c.x - ringR, c.y - ringR), Size(ringR * 2, ringR * 2),
                style = Stroke(r * .035f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

/** Pink, black text, a soft pink glow: the main action on every screen. */
@Composable internal fun SignInPinkButton(text: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(hapticClick(onClick), colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.Black),
        shape = RoundedCornerShape(RiffSignIn.corner),
        modifier = modifier.fillMaxWidth().heightIn(min = RiffSignIn.button)
            .shadow(RiffSignIn.buttonGlow, RoundedCornerShape(RiffSignIn.corner), ambientColor = Pink, spotColor = Pink)
            .testTag(tag)) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = RiffSignIn.buttonText) }
}

/** Glass: the second action. */
@Composable internal fun SignInGlassButton(text: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(hapticClick(onClick), shape = RoundedCornerShape(RiffSignIn.corner),
        modifier = modifier.fillMaxWidth().heightIn(min = RiffSignIn.button).signInGlass(RiffSignIn.corner).testTag(tag)) {
        Text(text, color = White, fontWeight = FontWeight.Medium, fontSize = RiffSignIn.buttonText)
    }
}

/**
 * One saved account: avatar colour and initial, name, email, and "Current" or when it was last used.
 * Q18-046: the name has the row's full width. Q18-088: the email has a line of its own, the time the line under it.
 */
@Composable internal fun SavedAccountRow(account: SavedAccount, current: Boolean, nowMs: Long, onPick: () -> Unit,
    onRemove: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).clip(RoundedCornerShape(RiffSignIn.corner))
        .clickable(role = Role.Button, onClick = hapticClick(onPick)).padding(horizontal = RiffSignIn.rowSide, vertical = RiffSpace.item)
        .testTag("signin_account_${account.key.hashCode().toUInt()}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Box(Modifier.size(RiffSignIn.avatar).clip(CircleShape).background(avatarColor(account.key)), contentAlignment = Alignment.Center) {
            Text(account.name.trim().take(1).uppercase().ifBlank { "?" }, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = RiffSignIn.avatarText)
        }
        Column(Modifier.weight(1f)) {
            Text(account.name.ifBlank { string(R.string.your_youtube_music_account) }, color = White, fontSize = RiffSignIn.rowTitle,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("signin_account_name"))
            // Q18-088: the whole address, on its own line; shortened in the middle only when it cannot fit.
            if (account.email != null) Text(account.email, color = Muted, fontSize = RiffType.detail, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.testTag("signin_account_email"))
            Text(if (current) string(R.string.signin_current) else lastUsedLabel(nowMs, account.lastUsedMs),
                color = if (current) Pink else Muted, fontSize = RiffType.detail, maxLines = 1, softWrap = false)
        }
        if (onRemove != null) Box {
            IconButton({ uiHaptic(); menu = true }, Modifier.testTag("signin_account_menu")) {
                Icon(Icons.Rounded.MoreVert, string(R.string.signin_account_options, account.name), tint = Muted)
            }
            SongActionMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(string(R.string.signin_remove_from_phone), style = RiffMenu.text) },
                    onClick = { uiHaptic(); menu = false; onRemove() }, modifier = Modifier.testTag("signin_account_remove"))
            }
        }
    }
}

/** A step on the checking screen: a ring that fills with a tick when the step is done. */
@Composable internal fun SignInStepRow(text: String, done: Boolean, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = RiffSignIn.stepPad), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RiffSpace.rowGap)) {
        Box(Modifier.size(RiffSignIn.stepRing).clip(CircleShape).background(if (done) Pink else Color.Transparent)
            .border(RiffSignIn.stepRingLine, if (done || current) Pink else White.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(RiffSignIn.stepTick))
        }
        Text(text, color = if (done || current) White else Muted, fontSize = RiffSignIn.rowTitle)
    }
}

/** The top bar on Google's page and the accounts page: back, the small record's place, a title and a subtitle. */
@Composable internal fun SignInTopBar(title: String, subtitle: String?, onBack: () -> Unit, backLabel: String,
    trailing: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(SIGN_IN_BAR_HEIGHT).padding(horizontal = RiffSignIn.barSide).signInGlass(RiffSignIn.barCorner),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(hapticClick(onBack), Modifier.testTag("signin_back")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, tint = White)
        }
        Spacer(Modifier.width(SIGN_IN_BAR_RECORD))
        Column(Modifier.weight(1f).padding(start = RiffSpace.item)) {
            Text(title, color = White, fontSize = RiffSignIn.barTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

internal val SIGN_IN_BAR_HEIGHT: Dp = RiffSignIn.barHeight
internal val SIGN_IN_BAR_RECORD: Dp = RiffSignIn.barRecord
