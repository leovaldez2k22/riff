package com.musicglass.personal

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Owner of locally stored data. Raw account identifiers never appear in a scope. */
internal sealed interface AccountScope {
    data object Guest : AccountScope
    data object DeviceLocal : AccountScope
    data class Provider(val opaqueId: String) : AccountScope
    /** Signed in without a stable identifier: nothing is saved and no existing data is adopted. */
    data object Unverified : AccountScope
}

/** The provider's stable account/channel identifier, already normalized. Never persisted or logged. */
internal data class VerifiedAccountIdentity(val stableId: String)

internal fun verifiedAccountIdentity(syncId: String?): VerifiedAccountIdentity? =
    signInSyncId(syncId)?.let(::VerifiedAccountIdentity)

private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Scope v2: the account-chooser position routes requests but is not part of identity. */
internal fun providerScope(identity: VerifiedAccountIdentity): AccountScope.Provider =
    AccountScope.Provider("s2_" + sha256Hex("riff-account-scope:v2:" + identity.stableId))

/** The pre-v2 key, which also hashed the chooser position. Kept only to find existing data. */
internal fun legacyScopeKey(syncId: String, authUser: String): String =
    sha256Hex("musicglass-account:$syncId:$authUser").take(32)

/** Stores receive this instead of a real key while the account is unverified; they refuse writes for it. */
internal const val UNVERIFIED_SCOPE_KEY = "unverified"
internal const val VERIFY_TO_SAVE_NOTICE = "Riff could not verify this account. Open Account and reconnect to save or download."
/** Why nothing can be saved for this owner. Signed in without a stable identity is not the same as signed out. */
internal fun saveUnavailableNotice(scope: AccountScope): String =
    if (scope == AccountScope.Unverified) VERIFY_TO_SAVE_NOTICE else "Sign in to save songs"
internal const val GUEST_SCOPE_KEY = "guest"

/**
 * Ownership marker, not a credential. The nonce changes every process so a callback from an
 * earlier process can never match a reset generation.
 */
internal data class SessionToken(val scope: AccountScope, val generation: Long, val nonce: String)

internal interface ScopeAliasStore {
    fun primary(canonical: String): String?
    fun aliases(canonical: String): Set<String>
    fun record(canonical: String, primary: String, aliases: Set<String>)
}

/** Opaque digests only; Q04 moves these rows into library-state.db. */
internal class PreferenceScopeAliasStore(context: Context) : ScopeAliasStore {
    private val prefs = context.applicationContext.getSharedPreferences("account_scopes", Context.MODE_PRIVATE)
    override fun primary(canonical: String): String? = prefs.getString("primary_$canonical", null)
    override fun aliases(canonical: String): Set<String> =
        prefs.getStringSet("aliases_$canonical", emptySet()).orEmpty().toSet()
    override fun record(canonical: String, primary: String, aliases: Set<String>) {
        prefs.edit {
            putString("primary_$canonical", primary).putStringSet("aliases_$canonical", aliases)
                .putInt("verification_version", 1)
        }
    }
}

private val COLLECTION_SCOPE_KEY = Regex("account_(.+)_online_(?:favorites|playlist|history)")

/** Every scope key that already owns saved tracks or download rows. Keys only; no contents are read. */
internal fun storedAccountScopeKeys(context: Context): Set<String> =
    context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE).all.keys
        .mapNotNullTo(mutableSetOf()) { COLLECTION_SCOPE_KEY.matchEntire(it)?.groupValues?.get(1) } +
        OfflineMusicStore.get(context).scopes()

/**
 * [primaryKey] is where this account's scoped stores read and write. [retainedAliases] are other
 * verified keys of the same account that still hold data; they are kept untouched until merged.
 */
internal data class ScopeStorage(val scope: AccountScope.Provider, val primaryKey: String, val retainedAliases: Set<String>)

/**
 * Maps a verified identity to its storage key without moving data. A legacy key is accepted as an
 * alias only when re-deriving it from this identity reproduces it; names, avatars and the currently
 * signed-in account are never used as proof.
 */
internal class AccountScopeRegistry(private val store: ScopeAliasStore) {
    /** [keysWithData] is read by the caller first so no store lock is taken while this one is held. */
    @Synchronized fun resolve(identity: VerifiedAccountIdentity, storedSyncId: String, authUser: String,
        keysWithData: Set<String>): ScopeStorage {
        val scope = providerScope(identity)
        val canonical = scope.opaqueId
        val sources = linkedSetOf(storedSyncId, identity.stableId)
        fun legacy(selector: String) = sources.map { legacyScopeKey(it, selector) }
        val found = (0 until LEGACY_SELECTORS).flatMap { legacy(it.toString()) }.filterTo(linkedSetOf()) { it in keysWithData }
        val known = store.aliases(canonical)
        val aliases = known + found
        val saved = store.primary(canonical)
        // Chosen once and persisted, so a later chooser reorder cannot move the account's data.
        val primary = saved ?: when {
            canonical in keysWithData -> canonical
            else -> legacy(authUser).firstOrNull { it in found } ?: found.firstOrNull() ?: canonical
        }
        if (saved != primary || aliases != known) store.record(canonical, primary, aliases)
        return ScopeStorage(scope, primary, (aliases - primary).filterTo(linkedSetOf()) { it in keysWithData })
    }

    /**
     * The key chosen earlier for this identity, without looking at stored data. Lets every launch
     * after the first answer from preferences alone; aliases are reconciled in the background.
     */
    @Synchronized fun remembered(identity: VerifiedAccountIdentity): ScopeStorage? {
        val scope = providerScope(identity)
        val primary = store.primary(scope.opaqueId) ?: return null
        return ScopeStorage(scope, primary, store.aliases(scope.opaqueId) - primary)
    }

    private companion object { const val LEGACY_SELECTORS = 32 }
}

/** Work that must stop for the outgoing owner and restart, paused, for the incoming one. */
internal interface SessionParticipant {
    /** The outgoing owner is still active: stop its provider work and keep recoverable state. */
    suspend fun quiesce(outgoing: SessionToken) {}
    /** The given owner is committed: load its state paused. Never start playback here. */
    suspend fun bind(current: SessionToken) {}
}

internal sealed interface SessionTransition {
    data class Committed(val outgoing: SessionToken, val incoming: SessionToken, val ownerChanged: Boolean) : SessionTransition
    /** The commit declined, for example a stale verification. The previous owner stays active. */
    data class Rejected(val current: SessionToken) : SessionTransition
    data class Failed(val current: SessionToken, val stage: String) : SessionTransition
}

/**
 * Serializes verified account switches and sign-out (ADR-Q04). The provider remains the single
 * source of the active session; this class only orders the steps around its commit.
 */
internal class SessionCoordinator(private val session: () -> SessionToken, private val event: (String) -> Unit = {}) {
    private val gate = Mutex()
    private val participants = CopyOnWriteArrayList<SessionParticipant>()
    @Volatile private var transitioning = false
    @Volatile private var ownerChanging = false

    fun capture(): SessionToken = session()

    /**
     * For work that belongs to an owner rather than to one request, such as a transfer: true while
     * the same owner is active, including after its cookies were refreshed.
     */
    fun owns(token: SessionToken): Boolean = !ownerChanging && session().let { it.scope == token.scope && it.nonce == token.nonce }

    /** False for any token captured before or during a transition, even if the owner ends up the same. */
    fun accepts(token: SessionToken): Boolean = !transitioning && token == session()

    fun register(participant: SessionParticipant): AutoCloseable {
        participants += participant
        return AutoCloseable { participants -= participant }
    }

    suspend fun transition(reason: String, incoming: AccountScope, commit: () -> Boolean): SessionTransition = gate.withLock {
        val outgoing = session()
        val ownerChanges = incoming != outgoing.scope
        transitioning = true
        ownerChanging = ownerChanges
        try {
            if (ownerChanges && !attempt { participants.forEach { it.quiesce(outgoing) } }) {
                transitioning = false; ownerChanging = false
                attempt { participants.forEach { it.bind(outgoing) } }
                return@withLock finish(reason, SessionTransition.Failed(outgoing, "quiesce"))
            }
            var committed = false
            val commitRan = attempt { committed = commit() }
            transitioning = false; ownerChanging = false
            val current = session()
            if (!commitRan || !committed) {
                if (ownerChanges) attempt { participants.forEach { it.bind(current) } }
                return@withLock finish(reason, if (commitRan) SessionTransition.Rejected(current)
                    else SessionTransition.Failed(current, "commit"))
            }
            val changed = current.scope != outgoing.scope
            if (changed && !attempt { participants.forEach { it.bind(current) } })
                return@withLock finish(reason, SessionTransition.Failed(current, "bind"))
            finish(reason, SessionTransition.Committed(outgoing, current, changed))
        } finally {
            transitioning = false; ownerChanging = false
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /** Sanitized: owner kinds only, never a scope identifier. */
    private fun finish(reason: String, result: SessionTransition): SessionTransition {
        event("account_transition reason=$reason " + when (result) {
            is SessionTransition.Committed ->
                "result=committed owner_changed=${result.ownerChanged} from=${kind(result.outgoing)} to=${kind(result.incoming)}"
            is SessionTransition.Rejected -> "result=rejected owner=${kind(result.current)}"
            is SessionTransition.Failed -> "result=failed stage=${result.stage} owner=${kind(result.current)}"
        })
        return result
    }

    private fun kind(token: SessionToken) = when (token.scope) {
        AccountScope.Guest -> "guest"
        AccountScope.DeviceLocal -> "device"
        AccountScope.Unverified -> "unverified"
        is AccountScope.Provider -> "provider"
    }
}
