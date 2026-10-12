package com.bettermifitness.sync.data.repository

import com.bettermifitness.sync.data.SessionRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight passport refresh shared by concurrent metric fetches.
 *
 * When a serviceToken dies mid-sync, every in-flight metric fails with auth errors at
 * once. Without sharing, only the first failure would refresh and retry while the rest
 * report auth failure even though the session is fresh again. Here the first caller
 * becomes the leader and performs [refresh]; every other caller awaits the same outcome
 * and retries on success, so one refresh heals the whole run with a single passport call.
 *
 * One instance serves one sync run: call [reset] at the start of each run. [reset] is
 * only called from the run owner before fan-out, so it needs no locking.
 */
class SharedAuthRefresh(
    private val refresh: suspend () -> SessionRefreshResult,
) {
    private val mutex = Mutex()
    private var flight: CompletableDeferred<SessionRefreshResult>? = null

    fun reset() {
        flight = null
    }

    /**
     * Runs [refresh] at most once per run and returns its outcome to every caller.
     * A cancelled leader completes the flight with a transient failure so followers
     * fail fast instead of hanging, then the cancellation is rethrown.
     */
    suspend fun refreshOnce(): SessionRefreshResult {
        val mine: CompletableDeferred<SessionRefreshResult>
        val isLeader: Boolean
        mutex.withLock {
            val existing = flight
            if (existing == null) {
                mine = CompletableDeferred()
                flight = mine
                isLeader = true
            } else {
                mine = existing
                isLeader = false
            }
        }
        if (!isLeader) {
            return mine.await()
        }
        try {
            val result = refresh()
            mine.complete(result)
            return result
        } catch (e: CancellationException) {
            mine.complete(SessionRefreshResult.TransientFailure("Session refresh interrupted"))
            throw e
        } catch (e: Exception) {
            // refresh() is not expected to throw (it returns a result), but a shared flight
            // must never be left incomplete — followers would hang until the run is torn down.
            val fallback = SessionRefreshResult.TransientFailure(
                e.message?.takeIf { it.isNotBlank() } ?: "Session refresh failed",
            )
            mine.complete(fallback)
            return fallback
        }
    }
}
