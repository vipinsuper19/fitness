package com.vipin.fitness.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for the offline queue (pending_writes) to be sent when there is a network. */
interface SyncScheduler {
    fun requestSync()

    /** Same, but not before [delayMs] (a server Retry-After). */
    fun requestSync(delayMs: Long) = requestSync()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {
    override fun requestSync() = requestSync(0)

    override fun requestSync(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a write queued while a sync runs still gets a run after it.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "outbox-sync"
    }
}
