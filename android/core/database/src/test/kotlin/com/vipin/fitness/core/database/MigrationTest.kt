package com.vipin.fitness.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FitnessDatabase::class.java)

    @Test fun v1ToV2KeepsQueuedWritesAndAddsSessionTables() {
        helper.createDatabase("m.db", 1).use { db ->
            db.execSQL("INSERT INTO pending_writes (id, idempotency_key, method, path, body, created_at, attempts) VALUES ('a', 'k', 'POST', 'food-logs', '{}', 1, 0)")
        }
        helper.runMigrationsAndValidate("m.db", 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM pending_writes").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT owner_user_id FROM pending_writes").use { c -> c.moveToFirst(); assertTrue(c.isNull(0)) }
            db.query("SELECT COUNT(*) FROM current_account").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM workout_sessions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun v2ToV3KeepsSessionsAndAddsSeenBadgeTables() {
        helper.createDatabase("m3.db", 2).use { db ->
            db.execSQL("INSERT INTO current_account (id, user_id) VALUES (1, 'u1')")
        }
        helper.runMigrationsAndValidate("m3.db", 3, true).use { db ->
            db.query("SELECT user_id FROM current_account").use { c -> c.moveToFirst(); assertEquals("u1", c.getString(0)) }
            db.execSQL("INSERT INTO seen_badges (owner_user_id, code, seen_at) VALUES ('u1', 'FIRST_WORKOUT', 1)")
            db.query("SELECT COUNT(*) FROM seen_badges").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM seen_badge_baselines").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun v1MigratesAllTheWayToV3() {
        helper.createDatabase("m13.db", 1).close()
        helper.runMigrationsAndValidate("m13.db", 3, true).close()
    }
}
