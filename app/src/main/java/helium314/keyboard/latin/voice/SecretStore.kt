// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Stores sensitive values (currently: the OpenRouter API key) in an EncryptedSharedPreferences
 * bucket when the device supports it (API 23+).
 *
 * Migrates any plaintext value in the normal prefs file into the encrypted store on first use.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val ENCRYPTED_FILE = "turtleboard_secrets"

    fun isSecureStorageAvailable(context: Context): Boolean = securePrefs(context) != null

    fun getApiKey(context: Context, prefKey: String, default: String): String {
        val secure = securePrefs(context)
        if (secure != null) {
            val encrypted = secure.getString(prefKey, null)
            if (encrypted != null) return encrypted
            // First-run migration: if a plaintext value exists in normal prefs, move it here
            // and scrub the original.
            val legacy = context.prefs().getString(prefKey, null)
            if (!legacy.isNullOrBlank()) {
                secure.edit { putString(prefKey, legacy) }
                context.prefs().edit { remove(prefKey) }
                return legacy
            }
            return default
        }
        return default
    }

    fun setApiKey(context: Context, prefKey: String, value: String) {
        val secure = securePrefs(context)
        if (secure != null) {
            secure.edit { putString(prefKey, value) }
            // Ensure no stale plaintext copy remains.
            context.prefs().edit { remove(prefKey) }
        } else {
            throw IllegalStateException("Secure storage unavailable")
        }
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        // EncryptedSharedPreferences relies on KeyGenParameterSpec (API 23+). Keep the reference
        // inside this method so the class isn't loaded on older devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            EncryptedPrefsFactory.create(context, ENCRYPTED_FILE)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open encrypted prefs", e)
            null
        }
    }
}

private object EncryptedPrefsFactory {
    fun create(context: Context, name: String): SharedPreferences {
        val spec = androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(spec)
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            name,
            masterKeyAlias,
            context.applicationContext,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
