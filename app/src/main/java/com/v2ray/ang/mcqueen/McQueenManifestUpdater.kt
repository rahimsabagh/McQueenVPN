package com.v2ray.ang.mcqueen

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Schedules and executes periodic background refreshes of McQueen VPN's remote `servers.json`
 * via WorkManager's `:bg` multiprocess worker.
 *
 * Scheduling & battery optimization contract:
 * - Uses [RemoteWorkManager.enqueueUniquePeriodicWork] with [McQueenServerConfig.MANIFEST_REFRESH_TASK_NAME]
 *   and [ExistingPeriodicWorkPolicy.KEEP] on normal app launch/boot so repeated starts never create
 *   duplicate periodic workers. [ExistingPeriodicWorkPolicy.UPDATE] is used when the endpoint changes.
 * - Requests [NetworkType.CONNECTED] only and never requests battery-optimization exemptions.
 *   Because Android Doze mode, App Standby Buckets, and OEM power management defer background jobs,
 *   the 30-minute period is a minimum target interval and is not guaranteed to fire on an exact
 *   30-minute cadence.
 */
object McQueenManifestUpdater {

    private const val MAX_RETRY_ATTEMPTS = 3

    internal fun periodicWorkPolicy(forceReschedule: Boolean): ExistingPeriodicWorkPolicy =
        if (forceReschedule) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP

    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false,
    ) {
        val policy = periodicWorkPolicy(forceReschedule)

        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            McQueenServerConfig.MANIFEST_REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(McQueenServerConfig.MANIFEST_REFRESH_TASK_NAME)
            .build()

        runCatching {
            RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(
                McQueenServerConfig.MANIFEST_REFRESH_TASK_NAME,
                policy,
                request,
            )
            LogUtil.i(
                AppConfig.TAG,
                "McQueenManifestUpdater: scheduled interval=${McQueenServerConfig.MANIFEST_REFRESH_INTERVAL_MINUTES}m policy=$policy"
            )
        }.onFailure { e ->
            LogUtil.e(AppConfig.TAG, "McQueenManifestUpdater: failed to schedule manifest refresh", e)
        }
    }

    class RefreshWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            return when (val outcome = McQueenServerRepository().syncFromGitHub(applicationContext)) {
                is ManifestSyncOutcome.Success -> {
                    LogUtil.i(
                        AppConfig.TAG,
                        "McQueenManifestUpdater: refreshed ${outcome.manifest.countries.size} countries"
                    )
                    Result.success()
                }

                is ManifestSyncOutcome.Failure -> {
                    LogUtil.w(
                        AppConfig.TAG,
                        "McQueenManifestUpdater: background sync failed (attempt=$runAttemptCount): ${outcome.reason}"
                    )
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        }
    }
}
