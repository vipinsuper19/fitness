package com.vipin.fitness.core.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.network.auth.AuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedTokenStoreTest {
    /** XOR stand-in: Robolectric has no AndroidKeyStore. Enough to prove nothing is stored in clear. */
    private class FakeCipher(var broken: Boolean = false) : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray {
            check(!broken) { "key gone" }
            return encrypt(blob)
        }
    }

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_session", Context.MODE_PRIVATE)
    private val tokens = AuthTokens("access-123", "refresh-456", 42L)

    @Test fun roundTripsAcrossInstances() {
        EncryptedTokenStore(prefs, FakeCipher()).save(tokens)
        assertEquals(tokens, EncryptedTokenStore(prefs, FakeCipher()).get())
    }

    @Test fun neverStoresTokensInClear() {
        EncryptedTokenStore(prefs, FakeCipher()).save(tokens)
        val raw = prefs.all.values.joinToString()
        assertFalse(raw.contains("access-123"))
        assertFalse(raw.contains("refresh-456"))
    }

    @Test fun clearRemovesTokens() {
        val store = EncryptedTokenStore(prefs, FakeCipher())
        store.save(tokens)
        store.clear()
        assertNull(store.get())
        assertNull(EncryptedTokenStore(prefs, FakeCipher()).get())
    }

    @Test fun undecryptableBlobIsDroppedNotThrown() {
        EncryptedTokenStore(prefs, FakeCipher()).save(tokens)
        assertNull(EncryptedTokenStore(prefs, FakeCipher(broken = true)).get())
        assertNull(EncryptedTokenStore(prefs, FakeCipher()).get()) // removed from disk too
    }

    @Test fun toStringRedactsSecrets() {
        assertFalse(tokens.toString().contains("access-123"))
    }
}
