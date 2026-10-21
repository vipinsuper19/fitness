package com.vipin.fitness.core.database.badges

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** An earned badge the user has already been shown (spec §10), per signed-in user. */
@Entity(tableName = "seen_badges", primaryKeys = ["owner_user_id", "code"])
data class SeenBadgeEntity(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    val code: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)

/**
 * A user's first badge list has been recorded. Until then the list is the baseline and shows
 * nothing, so history (or the release backfill) never pops a "Badge earned" sheet.
 */
@Entity(tableName = "seen_badge_baselines", primaryKeys = ["owner_user_id"])
data class SeenBadgeBaselineEntity(
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
)

@Dao
abstract class SeenBadgeDao {
    @Query("SELECT EXISTS(SELECT 1 FROM seen_badge_baselines WHERE owner_user_id = :owner)")
    abstract suspend fun hasBaseline(owner: String): Boolean

    @Query("SELECT code FROM seen_badges WHERE owner_user_id = :owner")
    abstract suspend fun seenCodes(owner: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSeen(rows: List<SeenBadgeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBaseline(row: SeenBadgeBaselineEntity)

    /**
     * Records [earned] as seen for [owner] and returns the codes that weren't seen before, in
     * [earned]'s order. The owner's first call is the baseline and returns nothing. Atomic, so
     * two screens checking at once can't both show the same badge.
     */
    @Transaction
    open suspend fun recordEarned(owner: String, earned: List<String>, now: Long): List<String> {
        val first = !hasBaseline(owner)
        val seen = seenCodes(owner).toSet()
        val fresh = earned.distinct().filter { it !in seen }
        insertSeen(fresh.map { SeenBadgeEntity(owner, it, now) })
        if (first) insertBaseline(SeenBadgeBaselineEntity(owner, now))
        return if (first) emptyList() else fresh
    }

    @Query("DELETE FROM seen_badges")
    abstract suspend fun deleteAllSeen()

    @Query("DELETE FROM seen_badge_baselines")
    abstract suspend fun deleteAllBaselines()

    @Query("DELETE FROM seen_badges WHERE owner_user_id != :owner")
    abstract suspend fun deleteSeenNotOwnedBy(owner: String)

    @Query("DELETE FROM seen_badge_baselines WHERE owner_user_id != :owner")
    abstract suspend fun deleteBaselinesNotOwnedBy(owner: String)

    @Transaction
    open suspend fun clearAll() {
        deleteAllSeen()
        deleteAllBaselines()
    }

    @Transaction
    open suspend fun keepOnly(owner: String) {
        deleteSeenNotOwnedBy(owner)
        deleteBaselinesNotOwnedBy(owner)
    }
}
