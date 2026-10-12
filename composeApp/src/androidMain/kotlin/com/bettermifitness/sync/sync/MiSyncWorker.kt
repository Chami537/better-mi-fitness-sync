package com.bettermifitness.sync.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bettermifitness.sync.data.MiSessionManager
import com.bettermifitness.sync.di.initKoin
import org.koin.core.context.GlobalContext
import org.koin.mp.KoinPlatform

/**
 * Opportunistic background sync (WorkManager).
 * Mirrors iOS BGAppRefresh: last **1 day**, requires Auto-sync ON, no permission UI.
 *
 * Retry policy:
 * - Success / partial success / skipped / not logged in / health unavailable → [Result.success]
 * - Transient [SyncOutcome.Failed] → [Result.retry] (WorkManager exponential backoff)
 * - Non-retryable failures (auth without refresh, permanent) → [Result.failure]
 */
class MiSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_REFRESH_ONLY, false)) {
            return doSessionRefresh()
        }
        return try {
            ensureKoin()
            val coordinator = KoinPlatform.getKoin().get<SyncCoordinator>()
            val outcome = coordinator.run(
                rangeDaysOverride = 1,
                requireAutoSync = true,
                requestHealthPermissions = false,
                recordAsBackground = true,
                resetProgress = false,
                userInitiated = false,
            )
            Log.i(TAG, "background sync finished: $outcome")
            when (outcome) {
                SyncOutcome.Success,
                is SyncOutcome.PartialSuccess,
                SyncOutcome.Skipped,
                SyncOutcome.HealthUnavailable,
                // Another path owns the run; do not queue retries that stack more work.
                SyncOutcome.AlreadyRunning,
                -> Result.success()
                // A dead session is a real failure, not a healthy no-op: report it so
                // WorkManager bookkeeping (and any observers) see it instead of success.
                // The next periodic tick still runs, so a later sign-in recovers on its own.
                SyncOutcome.NotLoggedIn,
                is SyncOutcome.Failed,
                -> if (outcome is SyncOutcome.Failed && outcome.shouldRetryBackground()) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "background sync error", e)
            Result.retry()
        }
    }

    /**
     * Session keepalive tick: rolls the passport session forward when stale and does
     * nothing else. Scheduled independently of the auto-sync toggle so the passToken
     * rotation chain survives weeks without a data sync. Opportunistic by design —
     * any failure just waits for the next tick, hence always success.
     */
    private suspend fun doSessionRefresh(): Result {
        return try {
            ensureKoin()
            val session = KoinPlatform.getKoin().get<MiSessionManager>()
            if (session.ensureActive()) {
                session.refreshSessionIfStale()
            }
            Log.i(TAG, "background session refresh finished")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "background session refresh error", e)
            Result.success()
        }
    }

    private fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) {
            // Application should have started Koin; defensive for edge process starts.
            com.bettermifitness.sync.di.provideAndroidContext(applicationContext)
            initKoin()
        }
    }

    companion object {
        private const val TAG = "MiSyncWorker"
        const val UNIQUE_WORK_NAME = "mi_fitness_auto_sync"
        const val UNIQUE_REFRESH_NAME = "mi_fitness_session_refresh"
        const val KEY_REFRESH_ONLY = "refresh_only"
    }
}
