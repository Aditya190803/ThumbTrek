package com.thumbtrek.app.update

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer

/**
 * Registers the periodic update check on process start.
 *
 * The owner's ask was "you don't need to open anything to update it", and that has to
 * include not needing to open Settings once to arm the checker. App Startup gives the
 * updater a foot in the door without a line in `ThumbTrekApp` — the package stays
 * self-contained, and deleting the `update` package plus its manifest entries removes the
 * whole feature cleanly.
 *
 * Cheap enough for the startup path: creating the notification channel and enqueueing
 * unique periodic work with `KEEP` are both no-ops after the first run.
 */
class UpdateStartupInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        UpdateNotifier.ensureChannel(context)
        if (UpdatePrefs.get(context).autoCheck.value) UpdateWorker.schedule(context)
    }

    /** WorkManager must be ready before [create] schedules the periodic update job. */
    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(WorkManagerInitializer::class.java)
}
