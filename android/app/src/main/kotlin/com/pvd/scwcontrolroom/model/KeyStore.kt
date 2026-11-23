package com.pvd.scwcontrolroom.model

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pvd.scwcontrolroom.kit.ApiKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/** Where the API keys are kept. */
interface KeyStorage {
    fun load(): List<ApiKey>
    fun save(keys: List<ApiKey>)
}

/**
 * Keeps the API keys encrypted with an AES key in the Android Keystore,
 * which never leaves the device (the iPhone app's keychain, on Android).
 * The encrypted list lives in the app's private preferences.
 */
class EncryptedKeyStore(context: Context) : KeyStorage {
    private val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val alias = "controlroom-keys"

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    override fun load(): List<ApiKey> {
        val stored = prefs.getString("keys", null) ?: return emptyList()
        val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return Json.decodeFromString(String(cipher.doFinal(data)))
    }

    override fun save(keys: List<ApiKey>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(Json.encodeToString(keys).toByteArray())
        val text = listOf(cipher.iv, data).joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
        // commit, not apply: a key the user just saved must not be lost.
        check(prefs.edit().putString("keys", text).commit()) { "The keys could not be written." }
    }
}

/** For tests and screenshots, where there is no Android Keystore. */
class MemoryKeyStore(private var keys: List<ApiKey> = emptyList()) : KeyStorage {
    override fun load() = keys
    override fun save(keys: List<ApiKey>) {
        this.keys = keys
    }
}
