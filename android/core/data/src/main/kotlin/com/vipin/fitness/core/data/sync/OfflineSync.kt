package com.vipin.fitness.core.data.sync

import javax.inject.Inject
import javax.inject.Singleton

/** What sign-out needs to know before it deletes anything unsynced. */
interface OfflineSync {
    /** Tries to send everything now. Returns how many workouts are still unsynced (0 = safe to sign out). */
    suspend fun syncNow(): Int
}

@Singleton
class DefaultOfflineSync @Inject constructor(private val outbox: OutboxProcessor) : OfflineSync {
    override suspend fun syncNow(): Int {
        outbox.drain()
        return outbox.unsyncedWorkouts()
    }
}
