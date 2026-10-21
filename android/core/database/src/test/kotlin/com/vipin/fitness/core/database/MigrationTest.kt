package com.vipin.fitness.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
            db.query("SELECT COUNT(*) FROM workout_sessions").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
}
