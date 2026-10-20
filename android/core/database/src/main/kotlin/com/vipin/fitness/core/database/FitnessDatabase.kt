package com.vipin.fitness.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vipin.fitness.core.database.outbox.PendingWriteDao
import com.vipin.fitness.core.database.outbox.PendingWriteEntity

/**
 * Local cache + offline drafts. Schemas are exported to core/database/schemas and must be
 * committed; every version bump needs a Migration (no destructive fallback once testers install).
 */
@Database(entities = [PendingWriteEntity::class], version = 1, exportSchema = true)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun pendingWriteDao(): PendingWriteDao

    companion object {
        const val NAME = "fitness.db"
    }
}
