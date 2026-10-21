package com.vipin.fitness.core.data.achievements

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.database.FitnessDatabase
import com.vipin.fitness.core.database.session.CurrentAccountEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The Room-backed seen-badge cache behind the "Badge earned" sheet. */
@RunWith(AndroidJUnit4::class)
class EarnedBadgeTrackerTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FitnessDatabase::class.java)
        .allowMainThreadQueries().build()
    private val tracker = EarnedBadgeTracker(
        RoomSeenBadgeStore(db.seenBadgeDao(), db.workoutSessionDao(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
    )

    private fun list(vararg earned: String) = BadgeList(
        listOf("A", "B", "C").map { Badge(it, it, "", BadgeFamily.WORKOUT, 5, if (it in earned) Instant.EPOCH else null, null) },
        earned.size,
    )

    private suspend fun signIn(user: String) {
        db.workoutSessionDao().setCurrentAccount(CurrentAccountEntity(userId = user))
        tracker.onSignedIn(user)
    }

    @After fun tearDown() = db.close()

    @Test fun firstListIsTheBaselineThenEachBadgeShowsOnce() = runTest {
        signIn("u1")
        assertTrue(tracker.newlyEarned(list("A")).isEmpty())
        assertEquals(listOf("B"), tracker.newlyEarned(list("A", "B")).map { it.code })
        assertTrue(tracker.newlyEarned(list("A", "B")).isEmpty())
    }

    @Test fun seenStateSurvivesANewTrackerInstance() = runTest {
        signIn("u1")
        tracker.newlyEarned(list("A"))
        tracker.newlyEarned(list("A", "B"))
        val again = EarnedBadgeTracker(RoomSeenBadgeStore(db.seenBadgeDao(), db.workoutSessionDao(), Clock.systemUTC()))
        assertTrue(again.newlyEarned(list("A", "B")).isEmpty()) // app restart: not shown twice
        assertEquals(listOf("C"), again.newlyEarned(list("A", "B", "C")).map { it.code })
    }

    @Test fun cacheIsPerUserAndClearedOnSignInAndSignOut() = runTest {
        signIn("u1")
        tracker.newlyEarned(list())
        tracker.newlyEarned(list("A"))
        signIn("u2") // another user: u1's rows go, u2 starts with a fresh baseline
        assertTrue(tracker.newlyEarned(list("A", "B")).isEmpty())
        assertEquals(listOf("u2"), db.query("SELECT DISTINCT owner_user_id FROM seen_badges", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
        tracker.clearUserState()
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_badge_baselines", null).use { c -> c.moveToFirst(); c.getInt(0) })
        assertTrue(tracker.newlyEarned(list("A", "B", "C")).isEmpty()) // baseline again after sign-out
    }

    @Test fun signedOutRecordsNothing() = runTest {
        assertTrue(tracker.newlyEarned(list("A")).isEmpty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM seen_badges", null).use { c -> c.moveToFirst(); c.getInt(0) })
    }
}
