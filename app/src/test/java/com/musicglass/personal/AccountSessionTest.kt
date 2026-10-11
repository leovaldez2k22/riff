package com.musicglass.personal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Synthetic identities only; none of these values come from a real account. */
class AccountSessionTest {
    private val a = VerifiedAccountIdentity("fixture-sync-A")
    private val b = VerifiedAccountIdentity("fixture-sync-B")
    private val scopeA = providerScope(a)
    private val scopeB = providerScope(b)

    private class MemoryAliases : ScopeAliasStore {
        val primaries = mutableMapOf<String, String>()
        val recorded = mutableMapOf<String, Set<String>>()
        var writes = 0
        override fun primary(canonical: String) = primaries[canonical]
        override fun aliases(canonical: String) = recorded[canonical].orEmpty()
        override fun record(canonical: String, primary: String, aliases: Set<String>) {
            writes++; primaries[canonical] = primary; recorded[canonical] = aliases
        }
    }

    private class Source(scope: AccountScope) {
        var token = SessionToken(scope, 1, "fixture-nonce")
        fun become(scope: AccountScope): Boolean { token = SessionToken(scope, token.generation + 1, token.nonce); return true }
    }

    private class Recorder(private val log: MutableList<String>, private val name: String = "p") : SessionParticipant {
        override suspend fun quiesce(outgoing: SessionToken) { log += "$name.quiesce:${outgoing.scope}" }
        override suspend fun bind(current: SessionToken) { log += "$name.bind:${current.scope}" }
    }

    @Test fun scopeIgnoresChooserPositionAndHidesTheIdentifier() {
        assertEquals(scopeA, providerScope(VerifiedAccountIdentity("fixture-sync-A")))
        assertNotEquals(scopeA, scopeB)
        assertFalse(scopeA.opaqueId.contains("fixture-sync-A"))
        assertTrue(scopeA.opaqueId.matches(Regex("s2_[0-9a-f]{64}")))
    }

    @Test fun legacyKeyReproducesThePreV2Derivation() {
        val expected = MessageDigest.getInstance("SHA-256").digest("musicglass-account:fixture-sync-A:1".toByteArray())
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, legacyScopeKey("fixture-sync-A", "1"))
        assertNotEquals(legacyScopeKey("fixture-sync-A", "0"), legacyScopeKey("fixture-sync-A", "1"))
    }

    @Test fun missingStableIdentifierIsNeverAnIdentity() {
        listOf(null, "", "   ", "null", "||fixture-other").forEach { assertNull(it, verifiedAccountIdentity(it)) }
        assertEquals(a, verifiedAccountIdentity(" fixture-sync-A||fixture-other"))
    }

    @Test fun accountWithoutExistingDataUsesItsCanonicalKey() {
        val store = MemoryAliases()
        val storage = AccountScopeRegistry(store).resolve(a, a.stableId, "0", setOf(GUEST_SCOPE_KEY))
        assertEquals(scopeA.opaqueId, storage.primaryKey)
        assertTrue(storage.retainedAliases.isEmpty())
    }

    @Test fun existingDataStaysReachableWhenTheChooserOrderChanges() {
        val store = MemoryAliases(); val registry = AccountScopeRegistry(store)
        val legacy = legacyScopeKey(a.stableId, "1")
        assertEquals(legacy, registry.resolve(a, a.stableId, "1", setOf(legacy)).primaryKey)
        // The same account now sits at position 0. The pre-v2 key would have changed here.
        val reordered = registry.resolve(a, a.stableId, "0", setOf(legacy))
        assertEquals(legacy, reordered.primaryKey)
        assertEquals(scopeA, reordered.scope)
        assertTrue(reordered.retainedAliases.isEmpty())
    }

    @Test fun sameIdentityResolvedAgainWritesNothingNew() {
        val store = MemoryAliases(); val registry = AccountScopeRegistry(store)
        val legacy = legacyScopeKey(a.stableId, "0")
        registry.resolve(a, a.stableId, "0", setOf(legacy))
        val writes = store.writes
        registry.resolve(a, a.stableId, "0", setOf(legacy))
        assertEquals(writes, store.writes)
    }

    @Test fun dataSplitAcrossPositionsIsRetainedNotDropped() {
        val registry = AccountScopeRegistry(MemoryAliases())
        val first = legacyScopeKey(a.stableId, "0"); val second = legacyScopeKey(a.stableId, "2")
        val storage = registry.resolve(a, a.stableId, "2", setOf(first, second))
        assertEquals(second, storage.primaryKey)
        assertEquals(setOf(first), storage.retainedAliases)
    }

    @Test fun anotherAccountsDataIsNeverAdopted() {
        val store = MemoryAliases()
        val othersData = setOf(legacyScopeKey(b.stableId, "0"), legacyScopeKey(b.stableId, "1"), scopeB.opaqueId)
        val storage = AccountScopeRegistry(store).resolve(a, a.stableId, "0", othersData)
        assertEquals(scopeA.opaqueId, storage.primaryKey)
        assertTrue(storage.retainedAliases.isEmpty())
        assertTrue(store.aliases(scopeA.opaqueId).isEmpty())
    }

    @Test fun switchQuiescesOutgoingBeforeCommitAndBindsIncomingAfter() = runBlocking {
        val source = Source(scopeA); val log = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token })
        coordinator.register(Recorder(log))
        val stale = coordinator.capture()
        var midTransition: Boolean? = null
        val result = coordinator.transition("sign_in", scopeB) {
            log += "commit"; midTransition = coordinator.accepts(coordinator.capture()); source.become(scopeB)
        }
        assertEquals(listOf("p.quiesce:$scopeA", "commit", "p.bind:$scopeB"), log)
        assertTrue(result is SessionTransition.Committed && result.ownerChanged)
        assertEquals(false, midTransition)
        assertFalse(coordinator.accepts(stale))
        assertTrue(coordinator.accepts(coordinator.capture()))
    }

    @Test fun delayedResultForAIsNotPublishedAfterSwitchToB() = runBlocking {
        withTimeout(5000) {
            val source = Source(scopeA); val coordinator = SessionCoordinator({ source.token })
            val session = FakeSession(); val catalog = FakeCatalog(); val published = mutableListOf<String>()
            val request = catalog.enqueue(session.current, "home"); val token = coordinator.capture()
            val pending = async { catalog.load(request).also { if (coordinator.accepts(token)) published += it.map(ProviderTrack::videoId) } }
            yield()
            coordinator.transition("sign_in", scopeB) { source.become(scopeB) }
            request.response.complete(listOf(ProviderTrack("fixtureA001", "A track", "Synthetic A")))
            pending.await()
            assertTrue(published.isEmpty())
        }
    }

    @Test fun returningToAGivesTheSameOwnerButRejectsItsOldWork() = runBlocking {
        val source = Source(scopeA); val coordinator = SessionCoordinator({ source.token })
        val first = coordinator.capture()
        coordinator.transition("sign_in", scopeB) { source.become(scopeB) }
        coordinator.transition("sign_in", scopeA) { source.become(scopeA) }
        assertEquals(first.scope, coordinator.capture().scope)
        assertFalse(coordinator.accepts(first))
    }

    @Test fun signOutMovesToGuest() = runBlocking {
        val source = Source(scopeA); val log = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token }); coordinator.register(Recorder(log))
        val result = coordinator.transition("sign_out", AccountScope.Guest) { source.become(AccountScope.Guest) }
        assertTrue(result is SessionTransition.Committed)
        assertEquals(listOf("p.quiesce:$scopeA", "p.bind:${AccountScope.Guest}"), log)
    }

    @Test fun declinedCommitKeepsTheOwnerAndRebindsIt() = runBlocking {
        val source = Source(scopeA); val log = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token }); coordinator.register(Recorder(log))
        val result = coordinator.transition("sign_in", scopeB) { false }
        assertEquals(SessionTransition.Rejected(source.token), result)
        assertEquals(scopeA, coordinator.capture().scope)
        assertEquals(listOf("p.quiesce:$scopeA", "p.bind:$scopeA"), log)
    }

    @Test fun failedQuiesceNeverReachesTheCommit() = runBlocking {
        val source = Source(scopeA); var committed = false
        val coordinator = SessionCoordinator({ source.token })
        coordinator.register(object : SessionParticipant {
            override suspend fun quiesce(outgoing: SessionToken) = throw java.io.IOException("Synthetic persistence failure")
        })
        val result = coordinator.transition("sign_in", scopeB) { committed = true; source.become(scopeB) }
        assertEquals(SessionTransition.Failed(source.token, "quiesce"), result)
        assertFalse(committed)
        assertEquals(scopeA, coordinator.capture().scope)
        assertTrue(coordinator.accepts(coordinator.capture()))
    }

    @Test fun refreshingTheSameAccountDoesNotInterruptIt() = runBlocking {
        val source = Source(scopeA); val log = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token }); coordinator.register(Recorder(log))
        val result = coordinator.transition("sign_in", scopeA) { source.become(scopeA) }
        assertTrue(result is SessionTransition.Committed && !result.ownerChanged)
        assertTrue(log.isEmpty())
    }

    @Test fun transitionsNeverInterleave() = runBlocking {
        withTimeout(5000) {
            val source = Source(scopeA); val log = mutableListOf<String>(); val hold = CompletableDeferred<Unit>()
            val coordinator = SessionCoordinator({ source.token })
            coordinator.register(object : SessionParticipant {
                override suspend fun quiesce(outgoing: SessionToken) { log += "quiesce:${outgoing.scope}"; if (outgoing.scope == scopeA) hold.await() }
            })
            val first = async { coordinator.transition("sign_in", scopeB) { log += "commit:B"; source.become(scopeB) } }
            yield()
            val second = async { coordinator.transition("sign_out", AccountScope.Guest) { log += "commit:Guest"; source.become(AccountScope.Guest) } }
            yield()
            assertEquals(listOf("quiesce:$scopeA"), log)
            hold.complete(Unit); first.await(); second.await()
            assertEquals(listOf("quiesce:$scopeA", "commit:B", "quiesce:$scopeB", "commit:Guest"), log)
        }
    }

    @Test fun removedParticipantIsNoLongerCalled() = runBlocking {
        val source = Source(scopeA); val log = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token })
        coordinator.register(Recorder(log)).close()
        coordinator.transition("sign_in", scopeB) { source.become(scopeB) }
        assertTrue(log.isEmpty())
    }

    @Test fun transitionEventsCarryOwnerKindsOnly() = runBlocking {
        val source = Source(scopeA); val events = mutableListOf<String>()
        val coordinator = SessionCoordinator({ source.token }) { events += it }
        coordinator.transition("sign_in", scopeB) { source.become(scopeB) }
        coordinator.transition("sign_out", AccountScope.Guest) { false }
        assertEquals(listOf(
            "account_transition reason=sign_in result=committed owner_changed=true from=provider to=provider",
            "account_transition reason=sign_out result=rejected owner=provider"), events)
        assertTrue(events.none { scopeA.opaqueId in it || scopeB.opaqueId in it || a.stableId in it })
    }

    @Test fun rememberedKeyNeedsNoLookAtStoredData() {
        val store = MemoryAliases(); val registry = AccountScopeRegistry(store)
        assertNull(registry.remembered(a))
        val legacy = legacyScopeKey(a.stableId, "1")
        registry.resolve(a, a.stableId, "1", setOf(legacy))
        val writes = store.writes
        assertEquals(legacy, registry.remembered(a)?.primaryKey)
        assertNull(registry.remembered(b))
        assertEquals(writes, store.writes)
    }

    @Test fun aliasEmptiedByAMergeIsNoLongerRetained() {
        val registry = AccountScopeRegistry(MemoryAliases())
        val first = legacyScopeKey(a.stableId, "0"); val second = legacyScopeKey(a.stableId, "2")
        assertEquals(setOf(first), registry.resolve(a, a.stableId, "2", setOf(first, second)).retainedAliases)
        val merged = registry.resolve(a, a.stableId, "2", setOf(second))
        assertEquals(second, merged.primaryKey)
        assertTrue(merged.retainedAliases.isEmpty())
    }

    @Test fun ownerKeepsItsTransfersAcrossARefreshButNotAcrossASwitch() = runBlocking {
        val source = Source(scopeA); val coordinator = SessionCoordinator({ source.token })
        val transfer = coordinator.capture()
        var ownedDuringRefresh = false; var ownedDuringSwitch = true
        coordinator.transition("refresh", scopeA) { ownedDuringRefresh = coordinator.owns(transfer); source.become(scopeA) }
        assertTrue(ownedDuringRefresh)
        assertTrue(coordinator.owns(transfer))
        assertFalse(coordinator.accepts(transfer))
        coordinator.transition("switch", scopeB) { ownedDuringSwitch = coordinator.owns(transfer); source.become(scopeB) }
        assertFalse(ownedDuringSwitch)
        assertFalse(coordinator.owns(transfer))
        coordinator.transition("switch", scopeA) { source.become(scopeA) }
        assertTrue(coordinator.owns(transfer))
    }

    @Test fun unverifiedAndSignedOutGiveDifferentSaveNotices() {
        assertEquals(VERIFY_TO_SAVE_NOTICE, saveUnavailableNotice(AccountScope.Unverified))
        assertNotEquals(VERIFY_TO_SAVE_NOTICE, saveUnavailableNotice(AccountScope.Guest))
    }
}
