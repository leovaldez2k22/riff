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
) {
    companion object {
        fun android(context: Context): RuntimeDependencies {
            val app = context.applicationContext
            return RuntimeDependencies(
                networkConditions = {
                    val manager = app.getSystemService(ConnectivityManager::class.java)
                    val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                    NetworkConditions(
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    )
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
    private val stateScope = CoroutineScope(SupervisorJob() + runtime.dispatchers.io)

    /**
     * Opens the state database and moves pre-owner data, once per process and never on the caller's
     * thread. Readers of saved queues await it; recording new plays does not need to.
     */
    val stateReady: Deferred<Unit> by lazy {
        stateScope.async {
            runCatching { epochs.warm() }.onFailure { eventLog.append("state_open_failed kind=${it::class.simpleName}") }
            runCatching { legacyState.run() }.onFailure { eventLog.append("legacy_migration_failed kind=${it::class.simpleName}") }
            Unit
        }
    }

    companion object {
        @Volatile private var process: AppContainer? = null
        fun existing():AppContainer?=process
        fun get(context: Context): AppContainer = process ?: synchronized(this) {
            process ?: AppContainer(context.applicationContext).also { process = it }
        }
    }
}
