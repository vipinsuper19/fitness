package com.vipin.fitness.core.datastore

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Runs on Robolectric only for the android.security exception class; the crypto is plain JCE. */
@RunWith(AndroidJUnit4::class)
class AesGcmTokenCipherTest {
    /** Software AES key standing in for the Keystore; can be told to throw on the next N lookups. */
    private class FakeKeys(private val failures: MutableList<GeneralSecurityException> = mutableListOf()) : SecretKeyProvider {
        var key: SecretKey = newKey()
        var deletes = 0
        var lookups = 0

        override fun getOrCreate(): SecretKey {
            lookups++
            failures.removeFirstOrNull()?.let { throw it }
            return key
        }

        override fun delete() {
            deletes++
            key = newKey()
        }

        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val plain = "secret-token".toByteArray()

    @Test fun roundTripsWithFreshIvEachTime() {
        val cipher = AesGcmTokenCipher(FakeKeys())
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertArrayEquals(plain, cipher.decrypt(a))
        assertTrue(!a.contentEquals(b))
    }

    @Test fun encryptRegeneratesKeyAndRetriesOnceForEachKeyLossError() {
        listOf(
            KeyPermanentlyInvalidatedException("lock screen changed"),
            UnrecoverableKeyException("gone"),
            KeyStoreException("corrupt"),
            AEADBadTagException("bad tag"),
        ).forEach { error ->
            val keys = FakeKeys(mutableListOf(error))
            val cipher = AesGcmTokenCipher(keys)

            val sealed = cipher.encrypt(plain)

            assertEquals(error::class.simpleName, 1, keys.deletes)
            assertArrayEquals(plain, cipher.decrypt(sealed))
        }
    }

    @Test fun encryptGivesUpAfterOneRetry() {
        val keys = FakeKeys(mutableListOf(KeyStoreException("1"), KeyStoreException("2")))
        try {
            AesGcmTokenCipher(keys).encrypt(plain)
            fail("expected KeyStoreException")
        } catch (e: KeyStoreException) {
            assertEquals(1, keys.deletes)
            assertEquals(2, keys.lookups)
        }
    }

    @Test fun decryptWithLostKeyThrowsTokenDecryptAndDropsAlias() {
        val keys = FakeKeys()
        val cipher = AesGcmTokenCipher(keys)
        val sealed = cipher.encrypt(plain)
        keys.key = FakeKeys.newKey() // Keystore silently replaced the key -> AEADBadTagException

        try {
            cipher.decrypt(sealed)
            fail("expected TokenDecryptException")
        } catch (e: TokenDecryptException) {
            assertTrue(e.cause is AEADBadTagException)
            assertEquals(1, keys.deletes)
        }
    }

    @Test fun decryptWhenKeyInvalidatedThrowsTokenDecrypt() {
        val keys = FakeKeys()
        val cipher = AesGcmTokenCipher(keys)
        val sealed = cipher.encrypt(plain)
        val broken = AesGcmTokenCipher(FakeKeys(mutableListOf(KeyPermanentlyInvalidatedException("x"))))
        try {
            broken.decrypt(sealed)
            fail("expected TokenDecryptException")
        } catch (e: TokenDecryptException) {
            assertTrue(e.cause is KeyPermanentlyInvalidatedException)
        }
    }

    @Test fun truncatedBlobIsTokenDecrypt() {
        try {
            AesGcmTokenCipher(FakeKeys()).decrypt(ByteArray(5))
            fail("expected TokenDecryptException")
        } catch (_: TokenDecryptException) {
        }
    }

    @Test fun concurrentCallersAreSafe() {
        val cipher = AesGcmTokenCipher(FakeKeys())
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..64).map { i ->
            pool.submit<Boolean> {
                val msg = "token-$i".toByteArray()
                cipher.decrypt(cipher.encrypt(msg)).contentEquals(msg)
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(results.all { it })
    }
}
