// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Stores sensitive values (currently: AI provider API keys) in an EncryptedSharedPreferences
 * bucket when the device supports it (API 23+).
 *
 * Migrates any plaintext value in the normal prefs file into the encrypted store on first use.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val ENCRYPTED_FILE = "wisprboard_secrets"

    fun isSecureStorageAvailable(context: Context): Boolean = securePrefs(context) != null

    /**
     * Force the EncryptedSharedPreferences and AndroidKeyStore master key to be loaded from
     * background code. Called once at app start so the IME's first transcription does not pay
     * the cold-decrypt cost on the input thread.
     *
     * Crucially, warm-up does NOT trigger the recovery path: if the keystore is transiently
     * unhappy at boot, we do not want to wipe the user's stored API key. Recovery still runs
     * on a real read/write attempt, where the user is actively using the feature.
     */
    fun warmUp(context: Context) {
        securePrefs(context, allowRecovery = false)
    }

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

    @Volatile
    private var recoveryAttempted = false

    private fun securePrefs(context: Context, allowRecovery: Boolean = true): SharedPreferences? {
        // EncryptedSharedPreferences relies on KeyGenParameterSpec (API 23+). Keep the reference
        // inside this method so the class isn't loaded on older devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            EncryptedPrefsFactory.create(context, ENCRYPTED_FILE)
        } catch (e: Exception) {
            // Warm-up (allowRecovery=false) is best-effort: the first real read/write will run
            // through the same path with allowRecovery=true and log + recover then. Skipping the
            // warning here keeps test stderr (Robolectric has no AndroidKeyStore) and the
            // never-used-voice install case quiet.
            if (!allowRecovery) return null
            Log.w(TAG, "Failed to open encrypted prefs", e)
            // Recovery wipes only the encrypted prefs storage. The AndroidKeyStore master key is
            // shared by EncryptedSharedPreferences users and must not be deleted on a transient
            // open failure, because that would make otherwise recoverable data unreadable.
            if (recoveryAttempted) return null
            recoveryAttempted = true
            Log.w(TAG, "Attempting one-shot recovery of encrypted prefs storage")
            attemptRecovery(context)
            try {
                EncryptedPrefsFactory.create(context, ENCRYPTED_FILE)
            } catch (e2: Exception) {
                Log.w(TAG, "Encrypted prefs still unavailable after recovery", e2)
                null
            }
        }
    }

    private fun attemptRecovery(context: Context) {
        try {
            context.getSharedPreferences(ENCRYPTED_FILE, Context.MODE_PRIVATE).edit(commit = true) {
                clear()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(ENCRYPTED_FILE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear encrypted prefs file during recovery", e)
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
