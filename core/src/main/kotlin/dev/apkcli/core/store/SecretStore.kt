package dev.apkcli.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted with a hardware-backed key.
 *
 * Hand-rolled on top of the Android Keystore rather than using
 * `androidx.security:security-crypto`: that library was deprecated and its
 * `EncryptedSharedPreferences` is no longer the recommended path, while the
 * Keystore API underneath it is stable and the amount of code needed is this
 * file.
 *
 * Properties that matter here:
 *   * The AES key never leaves the TEE / StrongBox. We hold a handle, not key
 *     material, so a filesystem dump of the app's data yields ciphertext only.
 *   * `setUserAuthenticationRequired(false)` — deliberate. Requiring a biometric
 *     to decrypt would mean the background agent service could not make a
 *     request while the screen is locked, which breaks long autonomous runs.
 *     The threat model is offline data extraction, not a running attacker with
 *     the device unlocked.
 *   * GCM with a random 12-byte IV per value, stored as a prefix. Never reuse an
 *     IV with the same key; the Keystore rejects a caller-supplied IV for
 *     encryption anyway, so we let it generate one and record what it chose.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0L)

    /**
     * Bumped whenever a credential is stored or removed.
     *
     * Keys live outside the settings blob, so a screen deriving "has a key"
     * from settings alone kept showing "add an API key" after one was saved,
     * until something unrelated happened to change settings.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** Store (or clear, when [value] is blank) the credential for an endpoint. */
    fun putApiKey(endpointId: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(entryKey(endpointId)).apply()
            changed()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            // iv || ciphertext, both base64'd together.
            val packed = cipher.iv + ciphertext
            prefs.edit()
                .putString(entryKey(endpointId), Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
            changed()
        } catch (t: Throwable) {
            // Losing a key is recoverable (the user retypes it); crashing the
            // settings screen is not.
            Log.e(TAG, "could not store credential for $endpointId", t)
        }
    }

    fun apiKey(endpointId: String): String? {
        val packed = prefs.getString(entryKey(endpointId), null) ?: return null
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            if (bytes.size <= GCM_IV_BYTES) return null
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Happens if the Keystore key was invalidated — a device restore, a
            // lock-screen change on some OEMs, or a factory reset of the TEE.
            // The stored blob is unrecoverable; drop it so the UI prompts again
            // instead of failing every request with a decrypt error.
            Log.w(TAG, "credential for $endpointId is undecryptable, discarding: ${t.message}")
            prefs.edit().remove(entryKey(endpointId)).apply()
            changed()
            null
        }
    }

    fun hasApiKey(endpointId: String): Boolean = prefs.contains(entryKey(endpointId))

    fun remove(endpointId: String) {
        prefs.edit().remove(entryKey(endpointId)).apply()
        changed()
    }

    /**
     * Masked form for display. Shows enough to tell two keys apart without
     * putting a usable credential on screen (or into a screenshot).
     */
    fun maskedApiKey(endpointId: String): String? {
        val key = apiKey(endpointId) ?: return null
        return when {
            key.length <= 8 -> "•".repeat(key.length)
            else -> key.take(4) + "•".repeat(8) + key.takeLast(4)
        }
    }

    private fun entryKey(endpointId: String) = "apikey.$endpointId"

    companion object {
        private const val TAG = "SecretStore"
        private const val PREFS = "apkcli_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "apkcli_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
