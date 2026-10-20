package com.vipin.fitness.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.database.outbox.PendingWriteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingWriteDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        FitnessDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val dao = db.pendingWriteDao()

    @After fun close() = db.close()

    private fun write(id: String, key: String, at: Long) =
        PendingWriteEntity(id, key, "POST", "food-logs", "{}", at)

    @Test fun enqueueIsIdempotentByKeyAndOrderedByAge() = runTest {
        dao.enqueue(write("b", "k2", 20))
        dao.enqueue(write("a", "k1", 10))
        dao.enqueue(write("c", "k1", 30)) // same idempotency key: ignored

        assertEquals(listOf("a", "b"), dao.oldest(10).map { it.id })
        assertEquals(2, dao.count().first())
    }

    @Test fun markFailedAndDelete() = runTest {
        dao.enqueue(write("a", "k1", 10))
        dao.markFailed("a", "HTTP_503")
        dao.markFailed("a", "NETWORK")
        val row = dao.oldest(1).single()
        assertEquals(2, row.attempts)
        assertEquals("NETWORK", row.lastError)

        dao.delete("a")
        assertEquals(0, dao.count().first())
    }
}
