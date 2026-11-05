package com.musicglass.personal

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * runCatching for work that can suspend. A caller that was cancelled stays cancelled: the cancellation is thrown
 * again and never becomes a result. A cancellation that came from inside while the caller is still wanted (a
 * timeout, a request the provider's library cancelled to refresh its session, a value whose producer was stopped)
 * is an ordinary failure, to be shown or retried like any other.
 */
internal suspend inline fun <T> suspendCatching(block: () -> T): Result<T> =
    try { Result.success(block()) } catch (failure: Throwable) { currentCoroutineContext().ensureActive(); Result.failure(failure) }

/** The library ends a request when the account changes, without cancelling its caller: unless the caller was cancelled, a failure. */
internal suspend fun accountChangedFailure(message: String): Result<Nothing> {
    currentCoroutineContext().ensureActive()
    return Result.failure(IllegalStateException(message))
}
