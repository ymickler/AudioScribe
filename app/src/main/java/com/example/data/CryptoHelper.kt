package com.example.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoHelper {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "AudioScribeKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG = "CryptoHelper"

    init {
        initKey()
    }

    private fun initKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not create or load the encryption key", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return plainText
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            "ENC:$ivBase64:$encryptedBase64"
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed - storing text unencrypted", e)
            plainText
        }
    }

    // The history Flow re-maps (and therefore re-decrypts) the *entire* list on every emission,
    // and while a transcription is running that happens about once a second. Ciphertext is
    // immutable once stored, so the result can simply be memoized: an LRU keyed by the stored
    // value, bounded so a long history can't grow the plaintext copy without limit.
    private const val DECRYPT_CACHE_SIZE = 200
    private val decryptCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > DECRYPT_CACHE_SIZE
    }

    fun decrypt(encryptedText: String): String {
        if (!encryptedText.startsWith("ENC:")) return encryptedText
        val parts = encryptedText.split(":")
        if (parts.size < 3) return encryptedText

        synchronized(decryptCache) { decryptCache[encryptedText] }?.let { return it }

        return try {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8).also {
                synchronized(decryptCache) { decryptCache[encryptedText] = it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed - showing raw stored value", e)
            encryptedText
        }
    }
}
