package com.musicglass.personal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import com.metrolist.innertubex.extraction.AudioQuality

internal interface AppClocks {
    fun wallTimeMillis(): Long
    fun monotonicMillis(): Long
}

internal object AndroidAppClocks : AppClocks {
    override fun wallTimeMillis() = System.currentTimeMillis()
    override fun monotonicMillis() = SystemClock.elapsedRealtime()
}

internal data class AppDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
)

internal data class NetworkConditions(val wifi: Boolean, val unmetered: Boolean, val validated: Boolean)

/** One network as Android describes it: what is needed to tell whether the device can really reach the internet. */
internal data class NetworkLink(val vpn: Boolean, val wifi: Boolean, val unmetered: Boolean, val validated: Boolean)

/**
 * A VPN can stay validated after the networks under it are gone (Q18-017, NordVPN with Wi-Fi and mobile data
 * off), so when the active network is a VPN the device counts as online only while a network other than a VPN
 * is validated too; Wi-Fi and unmetered are read from that network.
 */
internal fun networkConditionsOf(active: NetworkLink?, others: List<NetworkLink>): NetworkConditions {
    if (active == null) return NetworkConditions(false, false, false)
    if (!active.vpn) return NetworkConditions(active.wifi, active.unmetered, active.validated)
    val under = others.filter { !it.vpn && it.validated }
    val carrier = under.firstOrNull { it.wifi } ?: under.firstOrNull()
    return NetworkConditions(carrier?.wifi == true, carrier?.unmetered == true && active.unmetered, active.validated && carrier != null)
}
internal data class StorageCapacity(val availableBytes: Long, val totalBytes: Long)

internal data class StreamRequest(
    val videoId: String,
    val live: Boolean = false,
    val excludedProfiles: Set<String> = emptySet(),
    val video: Boolean = false,
    val download: Boolean = false,
    val quality: AudioQuality = streamingAudioQuality(false, false),
)

internal fun interface StreamResolver {
    suspend fun resolve(request: StreamRequest): Result<ProviderStream>
}

/** Constructor seams, never an exported test-control endpoint. Production defaults stay Android-owned. */
internal data class RuntimeDependencies(
    val networkConditions: () -> NetworkConditions,
    val storageCapacity: (File) -> StorageCapacity,
    val batteryPercent: () -> Int,
    val httpCalls: Call.Factory,
    val clocks: AppClocks = AndroidAppClocks,
    val dispatchers: AppDispatchers = AppDispatchers(),
    val wait: suspend (Long) -> Unit = { delay(it) },
    val providerTransport: () -> HttpClientEngine = { OriginalAudioEngine.create {} },
    val resolver: StreamResolver? = null,
    val storedScopeKeys: (Context) -> Set<String> = ::storedAccountScopeKeys,
    val startProviderBackgroundWork: Boolean = true,
    /** Points the extractor at the solver process. A failure here leaves online resolution refused, never in-process. */
    val installSolverBridge: (com.metrolist.innertubex.extraction.InnerTubeExtractor, CipherBackend) -> Unit =
        { extractor, backend -> CipherBridgeInstaller.install(extractor, backend) },
    /** Rebuilds and checks downloaded audio. Android's extractor and muxer in the app. */
    val assembler: AudioAssembler = AndroidAudioAssembler,
    /** Where a download's lyrics come from. Null means the app's lyrics sources. */
    val downloadLyrics: ((LyricsTrack) -> kotlinx.coroutines.flow.Flow<LyricsDocument>)? = null,
) {
    companion object {
        fun android(context: Context): RuntimeDependencies {
            val app = context.applicationContext
            return RuntimeDependencies(
                networkConditions = {
                    val manager = app.getSystemService(ConnectivityManager::class.java)
                    fun link(caps: NetworkCapabilities?) = caps?.let {
                        NetworkLink(it.hasTransport(NetworkCapabilities.TRANSPORT_VPN), it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    }
                    val active = link(manager.getNetworkCapabilities(manager.activeNetwork))
                    @Suppress("DEPRECATION") // getAllNetworks: the networks under a VPN, read only when the active one is a VPN.
                    val others = if (active?.vpn == true) manager.allNetworks.mapNotNull { link(manager.getNetworkCapabilities(it)) } else emptyList()
                    networkConditionsOf(active, others)
                },
                storageCapacity = { directory ->
                    StatFs(directory.absolutePath).let { StorageCapacity(it.availableBytes, it.totalBytes) }
                },
                batteryPercent = {
                    app.getSystemService(BatteryManager::class.java)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                },
                httpCalls = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS).build(),
            )
        }
    }
}

/** Process-owned runtime and lazy provider; shared() remains the compatibility facade. */
internal class AppContainer(context: Context, val runtime: RuntimeDependencies = RuntimeDependencies.android(context)) {
    private val app = context.applicationContext
    private val providerDelegate=lazy {YouTubeMusicProvider(app,runtime)}
    val provider:YouTubeMusicProvider get()=providerDelegate.value
    fun existingProvider():YouTubeMusicProvider?=if(providerDelegate.isInitialized()) providerDelegate.value else null

    /** Orders account switches and sign-out. Screens register playback and collections; downloads live here. */
    val sessions: SessionCoordinator by lazy {
        SessionCoordinator({ provider.session.value }) { PlaybackEventLog(app).append(it) }.also { coordinator ->
            coordinator.register(object : SessionParticipant {
                // Outgoing transfers re-queue themselves when the active scope changes; completed files stay.
                override suspend fun bind(current: SessionToken) {
                    if (current.scope == AccountScope.Unverified) return
                    OfflineDownloadScheduler.schedule(app)
                    OfflineDownloadScheduler.resumeManualLater(app)
                }
            })
        }
    }

    private val eventLog by lazy { PlaybackEventLog(app) }
    val libraryState: LibraryStateDb by lazy { LibraryStateDb(app) }
    private val epochs by lazy { ScopeEpochs(libraryState) }
    val history: HistoryRepository by lazy {
        HistoryRepository(libraryState, epochs, runtime.clocks::wallTimeMillis) { historyRetentionDays(app) }
    }
    val snapshots: PlaybackSnapshotRepository by lazy {
        PlaybackSnapshotRepository(libraryState, epochs, runtime.clocks::wallTimeMillis,
            File(app.filesDir, "unassigned/playback-snapshot-rejected.json"), eventLog::append)
    }
    val aliasJournal: ScopeAliasJournal by lazy { ScopeAliasJournal(libraryState) }
    val legacyState: LegacyStateMigration by lazy {
        LegacyStateMigration(app, libraryState, history, snapshots, runtime.clocks::wallTimeMillis, eventLog::append)
    }
    val localData: LocalDataController by lazy {
        LocalDataController(app, history, snapshots, { OfflineMusicStore.get(app) }, { SearchHistory.get(app) },
            runtime.dispatchers.io, runtime.clocks::wallTimeMillis, eventLog::append)
    }
    val localTransfer: LocalDataTransfer by lazy {
        LocalDataTransfer(app, libraryState, history, { OfflineMusicStore.get(app) }, { SearchHistory.get(app) }, { NotInterestedStore.get(app) },
            { CurrentOwner(provider.storageScopeKey(), provider.accountScopeDigests()) }, runtime.dispatchers.io,
            runtime.clocks::wallTimeMillis, localData.gate, eventLog::append)
    }
    /** The once-a-day question to the release feed (Q12A). Asks nothing in a build without a feed. */
    val updates: UpdateChecker by lazy { UpdateChecker.android(app, runtime, eventLog::append) }
    private val stateScope = CoroutineScope(SupervisorJob() + runtime.dispatchers.io)

    /** Hand-chosen downloads left waiting start again when the app comes to the front (Q18-021). Off the caller's thread. */
    fun resumeWaitingDownloads() {
        stateScope.launch { suspendCatching { DownloadCoordinator.resumeWaiting(app) } }
    }

    /** The automatic update check, when the app comes to the front. Never on the caller's thread. */
    fun checkForUpdatesIfDue() {
        stateScope.launch { suspendCatching { updates.checkIfDue() } }
    }

    /**
     * Opens the state database and moves pre-owner data, once per process and never on the caller's
     * thread. Readers of saved queues await it; recording new plays does not need to.
     */
    val stateReady: Deferred<Unit> by lazy {
        stateScope.async {
            runCatching { epochs.warm() }.onFailure { eventLog.append("state_open_failed kind=${it::class.simpleName}") }
            runCatching { legacyState.run() }.onFailure { eventLog.append("legacy_migration_failed kind=${it::class.simpleName}") }
            // Retention is applied when the app runs, at most once a day. Nothing waits for it.
            stateScope.launch { suspendCatching { localData.pruneIfDue() } }
            Unit
        }
    }

    companion object {
        // Lint (StaticFieldLeak): the context held is the application, which lives as long as the process (Q15 review).
        @Volatile private var process: AppContainer? = null
        /**
         * What the process builds its runtime from. Only the benchmark build types replace it (src/benchmarkFeed),
         * from a content provider that runs before anything asks for the container; a release has no such code.
         */
        @Volatile var runtimeFor: (Context) -> RuntimeDependencies = { RuntimeDependencies.android(it) }
        fun existing():AppContainer?=process
        fun get(context: Context): AppContainer = process ?: synchronized(this) {
            process ?: context.applicationContext.let { AppContainer(it, runtimeFor(it)) }.also { process = it }
        }
    }
}
