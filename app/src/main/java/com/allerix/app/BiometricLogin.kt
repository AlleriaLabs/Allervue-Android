package com.allerix.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Jellyfin username/password encrypted by an Android Keystore key
 * that requires a fresh strong biometric authentication for every operation.
 */
object BiometricLogin {
    private const val PREFS = "biometric_login"
    private const val KEY_ALIAS = "allervue_biometric_login_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    data class Credentials(val username: String, val password: String)

    fun hasSavedCredentials(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString("ciphertext", null).isNullOrBlank() &&
            !p.getString("iv", null).isNullOrBlank()
    }

    fun newEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher
    }

    fun newDecryptCipher(context: Context): Cipher {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = p.getString("iv", null) ?: error("No saved biometric login")
        val bytes = Base64.decode(iv, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, bytes))
        return cipher
    }

    fun save(context: Context, cipher: Cipher, username: String, password: String) {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(username)
                out.writeUTF(password)
            }
            buffer.toByteArray()
        }
        val encrypted = cipher.doFinal(bytes)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun decrypt(context: Context, cipher: Cipher): Credentials {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("ciphertext", null) ?: error("No saved biometric login")
        val clear = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        DataInputStream(ByteArrayInputStream(clear)).use { input ->
            return Credentials(input.readUTF(), input.readUTF())
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
