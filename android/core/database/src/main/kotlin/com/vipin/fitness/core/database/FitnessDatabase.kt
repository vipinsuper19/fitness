package com.vipin.fitness.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vipin.fitness.core.database.badges.SeenBadgeBaselineEntity
import com.vipin.fitness.core.database.badges.SeenBadgeDao
import com.vipin.fitness.core.database.badges.SeenBadgeEntity
import com.vipin.fitness.core.database.outbox.PendingWriteDao
import com.vipin.fitness.core.database.outbox.PendingWriteEntity
import com.vipin.fitness.core.database.session.CurrentAccountEntity
import com.vipin.fitness.core.database.session.SessionExerciseEntity
import com.vipin.fitness.core.database.session.SessionSetEntity
import com.vipin.fitness.core.database.session.WorkoutSessionDao
import com.vipin.fitness.core.database.session.WorkoutSessionEntity

/**
 * Local cache + offline drafts. Schemas are exported to core/database/schemas and must be
 * committed; every version bump needs a Migration (no destructive fallback once testers install).
 *
 * v2 (T13): workout session tables, current_account, and pending_writes.owner_user_id. New
 * tables and a nullable column only, so an auto-migration covers it.
 *
 * v3: seen_badges and seen_badge_baselines (per-user "Badge earned" cache). New tables only: auto-migration.
 */
@Database(
    entities = [PendingWriteEntity::class, WorkoutSessionEntity::class, CurrentAccountEntity::class, SessionExerciseEntity::class, SessionSetEntity::class, SeenBadgeEntity::class, SeenBadgeBaselineEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class FitnessDatabase : RoomDatabase() {
    abstract fun pendingWriteDao(): PendingWriteDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun seenBadgeDao(): SeenBadgeDao

    companion object {
        const val NAME = "fitness.db"
    }
}
