package com.vipin.fitness.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Drains pending_writes in order. Retries with backoff while the network or server is down. */
@HiltWorker
class OutboxSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outbox: OutboxProcessor,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (outbox.drain()) {
        DrainResult.Done -> Result.success()
        DrainResult.Retry -> {
            val wait = outbox.rateLimitDelayMs()
            if (wait != null) {
                // 429: honour Retry-After exactly (WorkManager backoff can't), and don't spend an
                // attempt: the queue is intact and will be sent once the window passes.
                scheduler.requestSync(wait)
                Result.success()
            } else if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        // Gives up after ~a day of backoff; the next write or app start schedules a new run.
        const val MAX_ATTEMPTS = 12
    }
}
