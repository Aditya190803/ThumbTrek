package com.thumbtrek.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The background half of "you don't need to open anything to update it": every six hours,
 * ask GitHub whether there is a newer release, and if there is, download and verify it so
 * the only thing left for the user is one tap.
 *
 * Deliberately lives in the `update` package rather than `work` — the updater is a
 * self-contained unit that could be deleted wholesale without touching anything else.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // A forced pass (the user asked) runs even when automatic checking is switched off.
        val forced = inputData.getBoolean(KEY_FORCE, false)
        if (!forced && !UpdatePrefs.get(applicationContext).autoCheck.value) return Result.success()

        // autoDownload: fetch the APK now, but only over unmetered data (the repository
        // makes that call). The user finds it already downloaded and verified.
        UpdateRepository.get(applicationContext).check(autoDownload = true)

        // Always success: a failed check is recorded in the repository's state for the UI
        // to show, and retrying aggressively would just burn battery. The next periodic
        // pass is at most six hours away.
        return Result.success()
    }

    companion object {

        private const val KEY_FORCE = "force"

        /**
         * Idempotent — [ExistingPeriodicWorkPolicy.KEEP] means calling this on every app
         * start (or every time the settings card appears) re-uses the existing schedule
         * rather than resetting its timer.
         *
         * WorkManager persists the schedule across reboots and process death, so one call
         * ever is technically enough; calling it often is just cheap insurance.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UpdateConfig.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(
                    UpdateConfig.CHECK_INTERVAL_HOURS, TimeUnit.HOURS,
                )
                    .setConstraints(
                        Constraints.Builder()
                            // CONNECTED, not UNMETERED: the check itself is a couple of KB
                            // and should happen for someone who lives on mobile data. The
                            // repository is what holds the multi-megabyte download back
                            // until wifi.
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UpdateConfig.UNIQUE_WORK_NAME)
        }

        /**
         * A one-shot pass, for "check for updates" tapped somewhere that has no coroutine
         * scope of its own. The settings card calls the repository directly instead, so it
         * can show progress — this exists for completeness and for debugging via adb.
         */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UpdateConfig.ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateWorker>()
                    .setInputData(workDataOf(KEY_FORCE to true))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
        }
    }
}
