// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores sensitive values (currently: AI provider API keys) encrypted with [Vault] when the
 * device supports it (API 23+). Each value is Base64(IV + AES-GCM ciphertext) in a normal
 * SharedPreferences file, in the same storage as the old encrypted file.
 *
 * Migrates any plaintext value in the normal prefs file on first use.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val VAULT_FILE = "wisprboard_vault"
    /** The security-crypto file of 7.12.2 and older (TURTLEBOARD-SEC-008). Only a reset deletes it. */
    private const val LEGACY_ENCRYPTED_FILE = "wisprboard_secrets"
    @Volatile private var cachedVault: Vault? = null
    /** Decrypted values, so that a key press does not wait for a StrongBox decrypt each time. */
    private val plainCache = ConcurrentHashMap<String, String>()

    fun isSecureStorageAvailable(context: Context): Boolean = vault(context) != null

    /**
     * Loads the vault key from background code. Called at app start when an AI feature is on, so
     * the IME's first request does not pay this cost on the input thread.
     * Best-effort: if the keystore is transiently unhappy at boot, the next real read/write will
     * simply retry.
     */
    fun warmUp(context: Context) {
        vault(context)
    }

    fun getApiKey(context: Context, prefKey: String, default: String): String {
        val vault = vault(context) ?: return default
        plainCache[prefKey]?.let { return it }
        // Reads and writes use one lock, so a read during setApiKey cannot cache the old value.
        return synchronized(this) { plainCache[prefKey] ?: readApiKey(context, vault, prefKey, default) }
    }

    private fun readApiKey(context: Context, vault: Vault, prefKey: String, default: String): String {
        val values = vaultPrefs(context)
        val stored = values.getString(prefKey, null)
        if (stored != null) {
            // A blob that does not decrypt (for example after a restore or an OS update changed
            // the keystore) reports "no key", because every caller is on the IME main thread and
            // an escaping exception kills the keyboard. Re-entering the key in Settings overwrites
            // the bad blob. Deliberately does NOT fall through to the plaintext-legacy branch.
            return runCatching { vault.decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8) }
                .onSuccess { plainCache[prefKey] = it }
                .getOrElse { e ->
                    Log.w(TAG, "Failed to read stored key: ${e.javaClass.simpleName}")
                    default
                }
        }
        // First-run migration: if a plaintext value exists in normal prefs, move it here
        // and scrub the original.
        val legacy = context.prefs().getString(prefKey, null)
        if (!legacy.isNullOrBlank()) {
            // Only scrub the plaintext copy once the encrypted one is actually on disk, or the
            // migration would destroy the user's key. If the process dies in between, the worst
            // case is a leftover plaintext key that gets re-scrubbed on the next read.
            val migrated = runCatching { put(context, vault, prefKey, legacy) }.getOrDefault(false)
            if (migrated) context.prefs().edit { remove(prefKey) }
            return legacy
        }
        return default
    }

    fun setApiKey(context: Context, prefKey: String, value: String) {
        val vault = vault(context) ?: throw IllegalStateException("Secure storage unavailable")
        // A false commit() means the key is not on disk: report it, like a missing store.
        check(synchronized(this) { put(context, vault, prefKey, value) }) { "Secure storage write failed" }
        // Ensure no stale plaintext copy remains (only scrub once the secure write is persisted).
        context.prefs().edit { remove(prefKey) }
    }

    /** Encrypts [value] and commits it. False when the disk write fails. Call it with the lock held. */
    private fun put(context: Context, vault: Vault, prefKey: String, value: String): Boolean {
        val blob = Base64.encodeToString(vault.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        plainCache.remove(prefKey)
        // commit(), not apply(): the androidx edit(commit = true) extension drops the result.
        val written = vaultPrefs(context).edit().putString(prefKey, blob).commit()
        if (written) plainCache[prefKey] = value
        return written
    }

    private fun vaultPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(VAULT_FILE, Context.MODE_PRIVATE)

    private fun vault(context: Context): Vault? {
        // KeyGenParameterSpec needs API 23. API 21-22 installs have no secure storage.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        cachedVault?.let { return it }
        return synchronized(this) {
            cachedVault?.let { return@synchronized it }
            val vault = try {
                Vault(Vault.androidKeyStoreKey())
            } catch (e: Exception) {
                // Do not cache failures: AndroidKeyStore can be transiently unavailable before
                // user unlock or during an OS update, and a later call should be allowed to retry.
                Log.w(TAG, "Failed to open the vault: ${e.javaClass.simpleName}")
                return@synchronized null
            }
            cachedVault = vault
            vault
        }
    }

    /**
     * Explicit user-initiated reset of the secret files. **This destroys all stored API keys.**
     * Wire from a settings screen, never from automatic recovery — the secret store is sometimes
     * unreadable for transient reasons (locked keystore, OS update mid-flight) that resolve on
     * their own.
     */
    fun clearSecureStorage(context: Context) {
        synchronized(this) {
            plainCache.clear()
            try {
                val app = context.applicationContext
                for (name in listOf(VAULT_FILE, LEGACY_ENCRYPTED_FILE)) {
                    app.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.deleteSharedPreferences(name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear the secret files: ${e.javaClass.simpleName}")
            }
        }
    }
}

