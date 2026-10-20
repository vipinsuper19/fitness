package com.vipin.fitness.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Encrypts small secrets. Swappable so tests (Robolectric has no AndroidKeyStore) can use a fake. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws TokenDecryptException when the blob can't be recovered (key lost or data tampered). */
    fun decrypt(blob: ByteArray): ByteArray
}

class TokenDecryptException(cause: Throwable) : GeneralSecurityException("token blob unrecoverable", cause)

/** Holds the AES key. Android: [KeystoreKeyProvider]. Tests: a software key. */
interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
    fun delete()
}

/**
 * AES-256-GCM. Output is `iv(12) || ciphertext+tag`.
 *
 * Keystore keys can disappear or become unusable (lock-screen change, OS upgrade, Keystore
 * corruption). On those errors the alias is deleted:
 * - encrypt regenerates the key and retries once;
 * - decrypt can't recover data sealed with the old key, so it throws [TokenDecryptException]
 *   and the caller drops the session.
 */
class AesGcmTokenCipher(private val keys: SecretKeyProvider) : TokenCipher {
    private val lock = Any()

    override fun encrypt(plain: ByteArray): ByteArray = synchronized(lock) {
        try {
            seal(plain)
        } catch (e: GeneralSecurityException) {
            if (!e.isKeyLoss()) throw e
            keys.delete()
            seal(plain) // fresh key; a second failure propagates
        }
    }

    override fun decrypt(blob: ByteArray): ByteArray = synchronized(lock) {
        if (blob.size <= IV_BYTES) throw TokenDecryptException(IllegalArgumentException("ciphertext too short"))
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.getOrCreate(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            if (e.isKeyLoss()) keys.delete() // next encrypt starts with a fresh key
            throw TokenDecryptException(e)
        }
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.getOrCreate()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun Throwable.isKeyLoss(): Boolean =
        this is KeyPermanentlyInvalidatedException ||
            this is UnrecoverableKeyException ||
            this is KeyStoreException ||
            this is AEADBadTagException

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Non-exportable AES-256 key in the Android Keystore. Calls are serialized. */
class KeystoreKeyProvider @Inject constructor() : SecretKeyProvider {
    private val lock = Any()

    override fun getOrCreate(): SecretKey = synchronized(lock) {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun delete() = synchronized(lock) {
        runCatching { keyStore().deleteEntry(ALIAS) }
        Unit
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "fitness_token_key"
    }
}
