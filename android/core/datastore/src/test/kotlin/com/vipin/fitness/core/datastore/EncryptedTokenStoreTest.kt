package com.vipin.fitness.core.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.SessionEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedTokenStoreTest {
    /** XOR stand-in: Robolectric has no AndroidKeyStore. Enough to prove nothing is stored in clear. */
    private class FakeCipher(var broken: Boolean = false) : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray {
            if (broken) throw TokenDecryptException(AEADBadTagException("key gone"))
            return encrypt(blob)
        }
    }

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_session", Context.MODE_PRIVATE)
    private val tokens = AuthTokens("access-123", "refresh-456", 42L)
    private val events = SessionEvents()
    private val expiredSignals = mutableListOf<Unit>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before fun collect() {
        scope.launch { events.expired.collect { expiredSignals += it } }
    }

    @After fun stop() = scope.cancel()

    @Test fun roundTripsAcrossInstances() {
        EncryptedTokenStore(prefs, FakeCipher(), events).save(tokens)
        assertEquals(tokens, EncryptedTokenStore(prefs, FakeCipher(), events).get())
    }

    @Test fun neverStoresTokensInClear() {
        EncryptedTokenStore(prefs, FakeCipher(), events).save(tokens)
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("access-123"))
        assertFalse(raw.contains("refresh-456"))
    }

    @Test fun compareAndSetOnlyReplacesMatchingSession() {
        val store = EncryptedTokenStore(prefs, FakeCipher(), events)
        val next = AuthTokens("a2", "r2", 7L)
        assertFalse(store.compareAndSet("refresh-456", next)) // signed out: nothing to replace
        assertNull(store.get())

        store.save(tokens)
        assertFalse(store.compareAndSet("other", next))
        assertEquals(tokens, store.get())
        assertTrue(store.compareAndSet("refresh-456", next))
        assertEquals(next, EncryptedTokenStore(prefs, FakeCipher(), events).get())

        assertFalse(store.clearIf("refresh-456"))
        assertEquals(next, store.get())
        assertTrue(store.clearIf("r2"))
        assertNull(EncryptedTokenStore(prefs, FakeCipher(), events).get())
    }

    @Test fun clearRemovesTokens() {
        val store = EncryptedTokenStore(prefs, FakeCipher(), events)
        store.save(tokens)
        store.clear()
        assertNull(store.get())
        assertNull(EncryptedTokenStore(prefs, FakeCipher(), events).get())
    }

    @Test fun undecryptableBlobClearsSessionAndSignalsExpiry() {
        EncryptedTokenStore(prefs, FakeCipher(), events).save(tokens)
        assertEquals(0, expiredSignals.size)

        assertNull(EncryptedTokenStore(prefs, FakeCipher(broken = true), events).get())
        assertEquals(1, expiredSignals.size)
        assertNull(EncryptedTokenStore(prefs, FakeCipher(), events).get()) // removed from disk too
    }

    @Test fun encryptFailureEndsSessionInsteadOfThrowing() {
        val failing = object : TokenCipher {
            override fun encrypt(plain: ByteArray): ByteArray = throw KeyStoreException("keystore broken")
            override fun decrypt(blob: ByteArray): ByteArray = blob
        }
        val store = EncryptedTokenStore(prefs, failing, events)
        store.save(tokens)
        assertNull(store.get())
        assertEquals(1, expiredSignals.size)
    }

    @Test fun toStringRedactsSecrets() {
        assertFalse(tokens.toString().contains("access-123"))
    }
}
