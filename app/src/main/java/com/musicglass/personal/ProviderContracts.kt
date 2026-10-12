package com.musicglass.personal

import com.metrolist.innertubex.InnerTubeHttpException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * What the rest of the app may ask of a music provider (Q10), in four parts: who is signed in, what can be
 * read, what can be changed, and where audio comes from (StreamResolver, declared with the resolver).
 * YouTubeMusicProvider implements all four on the pinned library. The split is by responsibility, so a
 * caller that only reads cannot change anything through the type it holds, and a test can stand in for one part.
 */

/** Who is signed in. Read-only; sign-in and sign-out go through the session coordinator. */
internal interface ProviderSession {
    val signedIn: Boolean
    val sessionState: SessionState
    /** Scope, generation and process nonce. A result computed for an older value must be dropped. */
    val session: StateFlow<SessionToken>
    fun accountScope(): AccountScope
    fun storageScopeKey(): String
    fun checkSessionIfDue(forced: Boolean)
}

/** Everything that only reads. None of these changes anything at the provider. */
internal interface ProviderCatalogReader {
    suspend fun discover(query: String, token: String?, filter: String): Result<CatalogPage>
    suspend fun browseCatalog(id: String, token: String?): Result<CatalogPage>
    /** Every page of an album or playlist, up to [COLLECTION_TRACK_LIMIT] tracks; see [CatalogPage.truncation]. */
    suspend fun completeCollection(id: String): Result<CatalogPage>
    fun partialCollection(id: String): CatalogPage?
    suspend fun libraryCatalog(kind: String, token: String?): Result<CatalogPage>
    suspend fun homeSections(mood: String): Result<List<ProviderHomeSection>>
    suspend fun recommendations(videoId: String, token: String?): Result<Pair<List<ProviderTrack>, String?>>
    suspend fun videoFor(track: ProviderTrack): Result<String?>
    suspend fun lyricsFor(videoId: String, durationMs: Long): LyricsDocument?
    suspend fun profile(): Result<ProviderProfile?>
    suspend fun loadLike(videoId: String): Result<Boolean?>
}

/**
 * Everything that changes the account at the provider. Each call succeeds only when the provider
 * acknowledged it for the account that was signed in when it was sent. An outcome that is not known is
 * settled by reading, never by sending again; see [MutationOutcome].
 */
internal interface ProviderMutations {
    suspend fun setSongLike(videoId: String, liked: Boolean): Result<Unit>
    suspend fun saveAlbum(browseId: String): Result<Unit>
    suspend fun addPlaylistSong(id: String, videoId: String): Result<Unit>
    suspend fun removePlaylistSong(id: String, card: ProviderHomeCard): Result<Unit>
    /** May write a sort preference, when the provider's own sort menu supplied one. */
    suspend fun sortedCollection(id: String, endpoint: CollectionSortEndpoint): Result<CatalogPage>
    suspend fun sendNotInterested(videoId: String?, token: String?): Result<Boolean>
}

// ---- pagination

/** A queue, a saved queue and a collection are never longer than this. */
internal const val COLLECTION_TRACK_LIMIT = 2000
/** No collection needs more requests than this; a provider that keeps answering is stopped here. */
internal const val COLLECTION_PAGE_LIMIT = 64

/** Why a collection's track list is not the whole collection. */
internal enum class CollectionTruncation(val label: String) {
    Limit("Showing the first 2,000 songs. This collection has more than Riff can load."),
    /** The provider sent a page token it had already sent, or pages that added nothing. */
    RepeatedPage("YouTube Music stopped sending new songs. This list may be incomplete.")
}

/** Page tokens already used by one listing. A token is followed once; a provider that sends it again has no further page. */
internal class PageTokens {
    private val seen = HashSet<String>()
    /** [candidate] if it has not been offered before, else null. */
    fun next(candidate: String?): String? = candidate?.takeIf { it.isNotBlank() && seen.add(it) }
}

// ---- failures

/** HTTP status at or above 400 on a catalog request. No address, header or body is kept. */
internal class ProviderHttpException(val status: Int) : IllegalStateException("YouTube Music is unavailable (HTTP $status)")
/** HTTP 200 whose body is an error envelope: {"error":{"code":403,"status":"PERMISSION_DENIED"}}. */
internal class ProviderEnvelopeException(val code: Int, val status: String) : IllegalStateException("YouTube Music returned an error ($code)")

/** The error envelope of a response, if it is one. Only the number and the status word are read. */
internal fun providerEnvelopeError(root: JsonElement?): ProviderEnvelopeException? {
    val error = (root as? JsonObject)?.get("error") as? JsonObject ?: return null
    val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: 0
    val status = (error["status"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(40).filter { it.isLetterOrDigit() || it == '_' }
    return ProviderEnvelopeException(code, status)
}

/** What kind of failure a provider request ended in. Only what the evidence supports; the rest is [Unknown]. */
internal enum class ProviderFault(val message: String) {
    Offline(OFFLINE_MESSAGE),
    Timeout(TIMEOUT_MESSAGE),
    Auth(SESSION_EXPIRED_MESSAGE),
    RateLimited("YouTube Music is limiting requests. Try again shortly."),
    /** The provider said the thing asked for does not exist (404). It does not say why. */
    NotFound("This is not available on YouTube Music."),
    Refused("YouTube Music refused this request."),
    Provider("YouTube Music is having trouble. Try again later."),
    Unknown("YouTube Music could not load this content")
}

/**
 * Classifies by what was observed: the exception type for the network, the status number for the provider.
 * A region restriction is not among the classes: no response Riff reads for the catalog states one, and a
 * 404 or an empty page is not evidence of it.
 */
internal fun classifyProviderFailure(e: Throwable): ProviderFault {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    if (chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException }) return ProviderFault.Offline
    if (chain.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") }) return ProviderFault.Timeout
    val status = chain.firstNotNullOfOrNull {
        when (it) {
            is ProviderHttpException -> it.status
            is ProviderEnvelopeException -> it.code.takeIf { code -> code > 0 } ?: when (it.status) {
                "UNAUTHENTICATED" -> 401; "PERMISSION_DENIED" -> 403; "NOT_FOUND" -> 404; "RESOURCE_EXHAUSTED" -> 429; else -> null }
            is InnerTubeHttpException -> it.status.value
            else -> null
        }
    } ?: return ProviderFault.Unknown
    return when {
        status == 401 -> ProviderFault.Auth
        status == 403 -> ProviderFault.Refused
        status == 404 || status == 410 -> ProviderFault.NotFound
        status == 429 -> ProviderFault.RateLimited
        status >= 500 -> ProviderFault.Provider
        else -> ProviderFault.Unknown
    }
}

// ---- mutations

/** What is known about a change after it was sent. */
internal enum class MutationOutcome {
    /** The provider said it was applied. */
    Acknowledged,
    /** The provider said it was not applied, or the request never left the device. Sending again is safe. */
    Rejected,
    /** Sent, with no usable answer: it may or may not have been applied. Must be read back before any retry. */
    Unknown
}

/** A change whose outcome could not be read back. Not a failure of the change itself. */
internal class MutationUnconfirmed(message: String = MUTATION_UNCONFIRMED_MESSAGE) : IllegalStateException(message)
internal const val MUTATION_UNCONFIRMED_MESSAGE = "YouTube Music did not confirm this change. Riff will check before trying again."

private fun statusOutcome(status: Int): MutationOutcome? = when {
    status == 408 || status >= 500 -> MutationOutcome.Unknown
    status >= 400 -> MutationOutcome.Rejected
    else -> null
}
private fun parsedBody(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

/** A playlist edit is applied only when the answer says STATUS_SUCCEEDED. HTTP 200 alone says nothing. */
internal fun playlistEditOutcome(status: Int, body: String): MutationOutcome {
    statusOutcome(status)?.let { return it }
    val root = parsedBody(body) ?: return MutationOutcome.Unknown
    if (root["error"] != null) return MutationOutcome.Rejected
    return when ((root["status"] as? JsonPrimitive)?.contentOrNull) {
        "STATUS_SUCCEEDED" -> MutationOutcome.Acknowledged
        null -> MutationOutcome.Unknown
        else -> MutationOutcome.Rejected
    }
}

/**
 * Like, unlike and save answer with an ordinary response object and no status word. It counts as an
 * acknowledgment only if it is an object that carries the provider's response context and no error.
 * An empty body or a bare {} is not an answer.
 */
internal fun likeOutcome(status: Int, body: String): MutationOutcome {
    statusOutcome(status)?.let { return it }
    val root = parsedBody(body) ?: return MutationOutcome.Unknown
    if (root["error"] != null) return MutationOutcome.Rejected
    return if (root["responseContext"] is JsonObject) MutationOutcome.Acknowledged else MutationOutcome.Unknown
}

/** A request that failed before a connection existed was never sent. Anything else may have arrived. */
internal fun transportOutcome(e: Throwable): MutationOutcome {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    return if (chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException })
        MutationOutcome.Rejected else MutationOutcome.Unknown
}

// ---- capabilities

/** Shown when an action is asked for while the session is still being checked. */
internal const val CHECKING_ACCOUNT_NOTICE = "Checking your account. Try again in a moment."

/** Whether an action can be offered now. The reason is shown to the user as written. */
internal sealed interface Capability {
    data object Available : Capability
    /** Not known yet; the control is shown disabled without a reason. */
    data object Loading : Capability
    data class Unavailable(val why: String) : Capability
    val available get() = this is Available
    /** Why it cannot be offered, or null when it can or is not known yet. */
    val reason get() = (this as? Unavailable)?.why
}

/**
 * What the provider can do for this session. Each feature stands alone: an account that cannot be edited
 * still browses and plays, and a resolver that is not ready takes nothing from the catalog, downloads or
 * audio stored on the phone.
 */
internal data class ProviderCapabilities(
    val browse: Capability, val stream: Capability, val library: Capability,
    val like: Capability, val saveCollection: Capability, val editPlaylists: Capability,
    val feedback: Capability,
) {
    companion object {
        const val SIGN_IN_LIBRARY = "Sign in to view your YouTube Music library"
        const val SIGN_IN_LIKE = "Sign in to like songs on YouTube Music"
        const val SIGN_IN_SAVE = "Sign in to save to YouTube Music"
        const val SIGN_IN_EDIT = "Sign in to edit YouTube Music playlists"
        const val SIGN_IN_FEEDBACK = "Sign in to send feedback to YouTube Music"
        const val RESOLVER_UNAVAILABLE = "Online playback resolver unavailable"
    }
}

internal fun providerCapabilities(state: SessionState, scope: AccountScope, resolverReady: Boolean): ProviderCapabilities {
    fun account(signedOut: String): Capability = when {
        state == SessionState.SignedOut || scope == AccountScope.Guest -> Capability.Unavailable(signedOut)
        state == SessionState.Expired -> Capability.Unavailable(SESSION_EXPIRED_MESSAGE)
        // Signed in, but which account is not established: nothing is written for an owner that is not known.
        scope == AccountScope.Unverified -> Capability.Unavailable(VERIFY_TO_SAVE_NOTICE)
        state == SessionState.Checking -> Capability.Loading
        else -> Capability.Available
    }
    // Reading the library needs a session but not a verified owner: nothing is stored under it.
    val library = when {
        state == SessionState.SignedOut || scope == AccountScope.Guest -> Capability.Unavailable(ProviderCapabilities.SIGN_IN_LIBRARY)
        state == SessionState.Expired -> Capability.Unavailable(SESSION_EXPIRED_MESSAGE)
        else -> Capability.Available
    }
    return ProviderCapabilities(
        browse = Capability.Available,
        stream = if (resolverReady) Capability.Available else Capability.Unavailable(ProviderCapabilities.RESOLVER_UNAVAILABLE),
        library = library,
        like = account(ProviderCapabilities.SIGN_IN_LIKE),
        saveCollection = account(ProviderCapabilities.SIGN_IN_SAVE),
        editPlaylists = account(ProviderCapabilities.SIGN_IN_EDIT),
        feedback = account(ProviderCapabilities.SIGN_IN_FEEDBACK),
    )
}

/** What can be done with one album or playlist as it is loaded now. */
internal data class CollectionCapabilities(
    val playAll: Capability, val downloadAll: Capability, val sort: Capability,
    val save: Capability, val removeEntries: Capability, val checkMembership: Capability,
) {
    companion object {
        const val READ_ONLY = "This playlist is read-only"
        const val NOT_A_PLAYLIST = "Only playlists can be edited"
    }
}

/**
 * [complete] is false while later pages are still arriving. A truncated list is never complete: actions
 * that claim the whole collection are off, with the reason, and stay off however long one waits.
 */
internal fun collectionCapabilities(page: CatalogPage, complete: Boolean, playlist: Boolean, provider: ProviderCapabilities): CollectionCapabilities {
    val whole: Capability = page.truncation?.let { Capability.Unavailable(it.label) } ?: if (complete) Capability.Available else Capability.Loading
    val edit: Capability = when {
        !playlist -> Capability.Unavailable(CollectionCapabilities.NOT_A_PLAYLIST)
        !provider.editPlaylists.available -> provider.editPlaylists
        !page.editable -> Capability.Unavailable(CollectionCapabilities.READ_ONLY)
        else -> Capability.Available
    }
    return CollectionCapabilities(playAll = whole, downloadAll = whole, sort = whole, save = provider.saveCollection,
        removeEntries = edit, checkMembership = whole)
}
