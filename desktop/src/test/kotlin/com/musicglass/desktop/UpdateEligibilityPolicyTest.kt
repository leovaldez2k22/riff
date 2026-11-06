package com.musicglass.desktop

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlin.test.*

class UpdateEligibilityPolicyTest {
    private val f = UpdatePolicyFixture
    private suspend fun evaluate(m: ValidatedUpdateManifest=f.manifest(), c: UpdateLocalConfiguration=f.config(),
        s: UpdateFreshnessStore=MemoryUpdateFreshnessStore(), time: Long=UpdatePolicyFixture.NOW) =
        UpdateEligibilityPolicy.evaluate(m,c,s,f.clock(time))
    private fun denied(code: UpdatePolicyError, result: UpdatePolicyResult) {
        when(result) {
            is UpdatePolicyResult.Rejected -> assertEquals(code,result.code)
            is UpdatePolicyResult.AuthenticatedIneligible -> assertEquals(code,result.reason)
            else -> fail("Expected typed rejection, got ${result.javaClass.simpleName}")
        }
    }
    @Test fun validOfferCommitsDigestVersionAndTimeBeforeExposure() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore();val m=f.manifest()
        val offer=assertIs<EligibleUpdate>(evaluate(m,s=s))
        assertSame(m,offer.manifest);assertEquals(f.scope,offer.scope);assertEquals(UpdatePolicyFixture.NOW,offer.evaluatedAt)
        assertEquals(m.payloadSha256,s.record!!.digest);assertEquals(m.version,s.record!!.sequenceVersion)
        assertEquals(1,s.commits)
    }
    @Test fun invalidLocalConfigurationFailsWithoutOpeningStore() = runBlocking<Unit> {
        for(c in listOf(f.config(installed="v0.1.0"),f.config(bootstrap=UpdateBootstrap(0,"0.1.0")),
            f.config(bootstrap=UpdateBootstrap(1,"bad")),f.config(bootstrap=UpdateBootstrap(1,"0.1.0","INVALID")),
            f.config(workers=emptySet()),f.config(migrations=emptySet()),f.config(hosts=setOf("https://evil.invalid")),
            f.config(scope=f.scope.copy(target="linux-x64")),f.config(windowsBuild=-1))) {
            val s=MemoryUpdateFreshnessStore();denied(UpdatePolicyError.CONFIGURATION_ERROR,evaluate(c=c,s=s));assertEquals(0,s.commits)
        }
    }
    @Test fun productChannelTargetSignerPermissionsAndHostAreScoped() = runBlocking<Unit> {
        val others=listOf(f.manifest(transform={it.replace("com.musicglass.desktop","other.product")}),
            f.manifest(transform={it.replace("\"channel\":\"preview\"","\"channel\":\"stable\"")}),
            f.manifest(transform={it.replace("windows-x64","linux-arm64").replace("\"os\":\"windows\"","\"os\":\"linux\"").replace("\"arch\":\"x64\"","\"arch\":\"arm64\"")}),
            f.manifest(transform={it.replace("releases.example.invalid","elsewhere.example.invalid")}))
        for(m in others) { val s=MemoryUpdateFreshnessStore();denied(UpdatePolicyError.WRONG_TARGET,evaluate(m,s=s));assertNull(s.record) }
        // The product identity is fixed by contract; stable is valid configuration but this signer permits preview only.
        denied(UpdatePolicyError.CONFIGURATION_ERROR,evaluate(others[0],f.config(scope=f.scope.copy(appId="other.product"))))
        denied(UpdatePolicyError.WRONG_TARGET,evaluate(others[1],f.config(scope=f.scope.copy(channel="stable"))))
        assertIs<EligibleUpdate>(evaluate(c=f.config(hosts=setOf("RELEASES.EXAMPLE.INVALID"))))
    }
    @Test fun bootstrapAboveStoredUsesCompleteBootstrapBinding() = runBlocking<Unit> {
        val m=f.manifest(20,"2.0.0");val s=MemoryUpdateFreshnessStore(f.record(10,"1.0.0"))
        assertIs<EligibleUpdate>(evaluate(m,f.config(bootstrap=UpdateBootstrap(20,"2.0.0",m.payloadSha256)),s))
        assertEquals(m.payloadSha256,s.record!!.digest);assertEquals(20,s.record!!.sequence)
    }
    @Test fun storedAboveBootstrapRetainsEntireStoredBinding() = runBlocking<Unit> {
        val m=f.manifest(20,"2.0.0");val s=MemoryUpdateFreshnessStore(f.record(20,"2.0.0",m.payloadSha256))
        assertIs<EligibleUpdate>(evaluate(m,f.config(bootstrap=UpdateBootstrap(10,"1.0.0","b".repeat(64))),s))
        assertEquals(m.payloadSha256,s.record!!.digest)
        denied(UpdatePolicyError.STALE_RELEASE,evaluate(f.manifest(19,"2.1.0"),s=s))
    }
    @Test fun absentStateBootstrapsWithKnownOrUnknownDigest() = runBlocking<Unit> {
        val m=f.manifest()
        for(digest in listOf(null,m.payloadSha256)) {
            val s=MemoryUpdateFreshnessStore()
            assertIs<EligibleUpdate>(evaluate(m,f.config(bootstrap=UpdateBootstrap(2,"0.2.0",digest)),s))
            assertEquals(m.payloadSha256,s.record!!.digest)
        }
    }
    @Test fun equalSequenceLocalVersionsOrKnownDigestsCannotConflict() = runBlocking<Unit> {
        for(b in listOf(UpdateBootstrap(2,"0.3.0"),UpdateBootstrap(2,"0.2.0","b".repeat(64)))) {
            val original=f.record(2,"0.2.0");val s=MemoryUpdateFreshnessStore(original)
            denied(UpdatePolicyError.STATE_UNAVAILABLE,evaluate(c=f.config(bootstrap=b),s=s));assertSame(original,s.record);assertEquals(0,s.commits)
        }
    }
    @Test fun equalSequenceMergeUsesTheOneKnownDigestOrLeavesUnbound() = runBlocking<Unit> {
        val m=f.manifest()
        for(pair in listOf(null to null,null to m.payloadSha256,m.payloadSha256 to null,m.payloadSha256 to m.payloadSha256)) {
            val s=MemoryUpdateFreshnessStore(f.record(2,"0.2.0",pair.second))
            assertIs<EligibleUpdate>(evaluate(m,f.config(bootstrap=UpdateBootstrap(2,"0.2.0",pair.first)),s))
            assertEquals(m.payloadSha256,s.record!!.digest)
        }
    }
    @Test fun unboundBootstrapBindsOnlyItsDeclaredVersion() = runBlocking<Unit> {
        for(pair in listOf("0.1.0" to UpdatePolicyError.ROLLBACK_REJECTED,"0.3.0" to UpdatePolicyError.RELEASE_CONFLICT)) {
            val s=MemoryUpdateFreshnessStore();denied(pair.second,evaluate(f.manifest(version=pair.first),f.config(bootstrap=UpdateBootstrap(2,"0.2.0")),s))
            assertEquals(0,s.commits)
        }
    }
    @Test fun independentVersionAndTimeFloorsSurviveBootstrapReplacement() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore(f.record(10,"1.0.0",highest="3.0.0",time=UpdatePolicyFixture.NOW+200))
        val c=f.config(bootstrap=UpdateBootstrap(20,"2.0.0"))
        denied(UpdatePolicyError.ROLLBACK_REJECTED,evaluate(f.manifest(21,"2.5.0"),c,s))
        assertIs<EligibleUpdate>(evaluate(f.manifest(21,"3.0.0"),c,s))
        assertEquals(UpdatePolicyFixture.NOW+200,s.record!!.lastEvaluationTime)
        assertEquals(f.version("3.0.0"),s.record!!.highestVersion)
    }
    @Test fun installedVersionIsAnIndependentFloor() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore()
        denied(UpdatePolicyError.ROLLBACK_REJECTED,evaluate(f.manifest(),f.config(installed="0.3.0"),s));assertEquals(0,s.commits)
    }
    @Test fun changedSameSequencePayloadConflictsAndIdenticalChecksAreIdempotent() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore();val m=f.manifest()
        assertIs<EligibleUpdate>(evaluate(m,s=s));val accepted=s.record
        assertIs<EligibleUpdate>(evaluate(m,s=s));assertEquals(accepted,s.record)
        denied(UpdatePolicyError.RELEASE_CONFLICT,evaluate(f.manifest(notes="Changed notes"),s=s));assertEquals(accepted,s.record)
    }
    @Test fun higherSequenceCannotLowerAcceptedVersion() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore();assertIs<EligibleUpdate>(evaluate(f.manifest(10,"1.0.0"),s=s))
        val accepted=s.record
        denied(UpdatePolicyError.ROLLBACK_REJECTED,evaluate(f.manifest(11,"0.9.0"),s=s));assertEquals(accepted,s.record)
    }
    @Test fun exactTimeBoundariesUseEffectiveTimeWithoutSleeping() = runBlocking<Unit> {
        val now=UpdatePolicyFixture.NOW
        assertIs<EligibleUpdate>(evaluate(f.manifest(published=now+300)))
        denied(UpdatePolicyError.NOT_YET_VALID,evaluate(f.manifest(published=now+301)))
        denied(UpdatePolicyError.EXPIRED,evaluate(f.manifest(expires=now)))
        assertIs<EligibleUpdate>(evaluate(f.manifest(expires=now+1)))
        for(offset in listOf(299L,300L)) {
            val s=MemoryUpdateFreshnessStore(f.record(1,"0.1.0",null,time=now+offset))
            assertIs<EligibleUpdate>(evaluate(s=s));assertEquals(now+offset,s.record!!.lastEvaluationTime)
        }
        val s=MemoryUpdateFreshnessStore(f.record(1,"0.1.0",null,time=now+301))
        denied(UpdatePolicyError.CLOCK_INVALID,evaluate(s=s));assertEquals(0,s.commits)
        denied(UpdatePolicyError.EXPIRED,evaluate(f.manifest(expires=now+100),s=MemoryUpdateFreshnessStore(f.record(1,"0.1.0",null,time=now+200))))
    }
    @Test fun clockIsCapturedOnceAndNegativeTimeFailsClosed() = runBlocking<Unit> {
        var calls=0
        val clock=object: Clock() {
            override fun getZone()=java.time.ZoneOffset.UTC
            override fun withZone(zone: ZoneId)=this
            override fun instant(): Instant { calls++;return Instant.ofEpochSecond(UpdatePolicyFixture.NOW) }
        }
        assertIs<EligibleUpdate>(UpdateEligibilityPolicy.evaluate(f.manifest(),f.config(),MemoryUpdateFreshnessStore(),clock));assertEquals(1,calls)
        denied(UpdatePolicyError.CLOCK_INVALID,evaluate(time=-1))
    }
    @Test fun incompatibilityCommitsMetadataKnowledgeBeforeRejection() = runBlocking<Unit> {
        for(c in listOf(f.config(installed="0.0.1"),f.config(windowsBuild=21999),f.config(workers=setOf(2)),
            f.config(migrations=setOf(1 to 2)),f.config(isWindows=false))) {
            val s=MemoryUpdateFreshnessStore();denied(UpdatePolicyError.INCOMPATIBLE,evaluate(c=c,s=s))
            assertEquals(2,s.record!!.sequence)
            denied(UpdatePolicyError.STALE_RELEASE,evaluate(f.manifest(1),c,s))
        }
    }
    @Test fun equalInstalledVersionReturnsUpToDateAfterDurableCommit() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore();assertEquals(UpdatePolicyResult.UpToDate,evaluate(c=f.config(installed="0.2.0"),s=s));assertEquals(1,s.commits)
    }
    @Test fun failedCommitCannotExposeOfferOrUpToDateOrIncompatibleResult() = runBlocking<Unit> {
        for(c in listOf(f.config(),f.config(installed="0.2.0"),f.config(workers=setOf(2)))) {
            val s=MemoryUpdateFreshnessStore().apply{failCommit=true}
            denied(UpdatePolicyError.STATE_UNAVAILABLE,evaluate(c=c,s=s));assertNull(s.record)
        }
    }
    @Test fun rejectedResponsesDoNotPersistNormalization() = runBlocking<Unit> {
        val initial=f.record(1,"0.1.0",null);val c=f.config(bootstrap=UpdateBootstrap(2,"0.2.0"))
        for(m in listOf(f.manifest(expires=UpdatePolicyFixture.NOW),f.manifest(sequence=1),f.manifest(version="0.1.0"))) {
            val s=MemoryUpdateFreshnessStore(initial);assertFalse(evaluate(m,c,s) is EligibleUpdate);assertSame(initial,s.record);assertEquals(0,s.commits)
        }
    }
    @Test fun concurrentChecksAndOldOfferRevalidationCannotRegressKnowledge() = runBlocking<Unit> {
        val s=MemoryUpdateFreshnessStore();val old=assertIs<EligibleUpdate>(evaluate(f.manifest(2),s=s))
        coroutineScope { (3L..30L).map { seq -> async(Dispatchers.Default) { evaluate(f.manifest(seq),s=s) } }.awaitAll() }
        assertEquals(30,s.record!!.sequence)
        denied(UpdatePolicyError.STALE_RELEASE,UpdateEligibilityPolicy.revalidate(old,f.config(),s,f.clock()))
    }
    @Test fun cancellationRemainsCancellation() = runBlocking<Unit> {
        val store=object: UpdateFreshnessStore {
            override suspend fun <T> transaction(scope: UpdateScope, action: suspend (UpdateFreshnessRecord?,(UpdateFreshnessRecord)->Unit)->T):T { throw CancellationException("test cancellation") }
        }
        assertFailsWith<CancellationException> { evaluate(s=store) }
    }
    @Test fun signerRotationSharesFreshnessScopeAndPermissionsStillApply() = runBlocking<Unit> {
        fun signed(id: String, targets: Set<String> = setOf("windows-x64"), apps: Set<String> = setOf(f.scope.appId)): ValidatedUpdateManifest {
            val bytes=SignedUpdatePayloadFixture.validJson().toByteArray()
            val publicKey=javaClass.getResourceAsStream("/update-v1/public-key.der")!!.use { it.readBytes() }
            val registry=UpdateTrustRegistry(listOf(UpdateTrustKey(id,publicKey,apps,setOf("preview"),targets)),allowTestKeys=true)
            val verified=assertIs<UpdateVerificationResult.Verified>(VerifiedUpdatePayload.verify(SignedUpdatePayloadFixture.envelope(bytes,id),registry))
            return assertIs<UpdatePayloadParseResult.Valid>(ValidatedUpdateManifest.parse(verified.payload)).manifest
        }
        val store=MemoryUpdateFreshnessStore()
        assertIs<EligibleUpdate>(evaluate(signed("test-old"),s=store))
        assertIs<EligibleUpdate>(evaluate(signed("test-new"),s=store))
        denied(UpdatePolicyError.WRONG_TARGET,evaluate(signed("test-wrong-scope",setOf("linux-x64")),s=store))
        denied(UpdatePolicyError.WRONG_TARGET,evaluate(signed("test-other-product",apps=setOf("other.product")),s=store))
        assertEquals(2L,store.record!!.sequence)
    }
}
