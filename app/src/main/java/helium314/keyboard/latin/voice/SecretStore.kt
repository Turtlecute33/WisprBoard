// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import helium314.keyboard.compat.isUserLocked
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores sensitive values (currently: AI provider API keys) encrypted with [Vault] when the
 * device supports it (API 23+). Each value is Base64(IV + AES-GCM ciphertext) in a normal
 * SharedPreferences file, in the same storage as the old encrypted file.
 *
 * Migrates once from the old EncryptedSharedPreferences file (report TURTLEBOARD-SEC-008), and
 * migrates any plaintext value in the normal prefs file on first use.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val VAULT_FILE = "wisprboard_vault"
    /** The old security-crypto file. Read once for the migration, then deleted. */
    private const val LEGACY_ENCRYPTED_FILE = "wisprboard_secrets"
    @Volatile private var cachedVault: Vault? = null
    @Volatile private var legacyMigrated = false
    /** Decrypted values, so that a key press does not wait for a StrongBox decrypt each time. */
    private val plainCache = ConcurrentHashMap<String, String>()

    fun isSecureStorageAvailable(context: Context): Boolean = vault(context) != null

    /**
     * Loads the vault key and runs the one-time migration from background code. Called at app
     * start when an AI feature is on, so the IME's first request does not pay this cost on the
     * input thread.
     * Best-effort: if the keystore is transiently unhappy at boot, the next real read/write will
     * simply retry.
     */
    fun warmUp(context: Context) {
        vault(context)
    }

    /**
     * True while the old security-crypto file still holds data for the one-time migration.
     * False before the first unlock: then the file is in locked storage, and a read throws.
     */
    fun hasLegacyFile(context: Context): Boolean = !legacyMigrated && !isUserLocked(context) &&
        context.applicationContext.getSharedPreferences(LEGACY_ENCRYPTED_FILE, Context.MODE_PRIVATE).all.isNotEmpty()

    fun getApiKey(context: Context, prefKey: String, default: String): String {
        val vault = vault(context) ?: return default
        plainCache[prefKey]?.let { return it }
        // Reads and writes use one lock, so a read during setApiKey cannot cache the old value.
        return synchronized(this) { plainCache[prefKey] ?: readApiKey(context, vault, prefKey, default) }
    }

    private fun readApiKey(context: Context, vault: Vault, prefKey: String, default: String): String {
        val values = vaultPrefs(context)
        if (!values.contains(prefKey)) migrateLegacyFile(context, vault) // no-op once done
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
            migrateLegacyFile(context, vault)
            cachedVault = vault
            vault
        }
    }

    /**
     * One-time move from the old EncryptedSharedPreferences file: copies each API key that the
     * vault does not have yet, then deletes the old file. If the old file does not open (for
     * example a locked keystore), it stays, and the next read that finds no key tries again.
     * A value that does not decrypt is lost, as before: the user enters the key again.
     */
    private fun migrateLegacyFile(context: Context, vault: Vault) {
        if (legacyMigrated) return
        synchronized(this) {
            if (legacyMigrated) return
            try {
                val app = context.applicationContext
                val raw = app.getSharedPreferences(LEGACY_ENCRYPTED_FILE, Context.MODE_PRIVATE)
                // Nothing to move (new install or done before): do not load Tink at all.
                if (raw.all.isNotEmpty()) {
                    val legacy = EncryptedPrefsFactory.create(app, LEGACY_ENCRYPTED_FILE)
                    val values = vaultPrefs(context)
                    for (prefKey in AiProvider.entries.map { it.apiKeyPrefKey() }) {
                        if (values.contains(prefKey)) continue // a key set in this version wins
                        val value = runCatching { legacy.getString(prefKey, null) }.getOrNull() ?: continue
                        if (!put(context, vault, prefKey, value)) return // keep the old file for a retry
                    }
                    // If this write fails, the next start finds the keys in the vault and clears again.
                    raw.edit(commit = true) { clear() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.deleteSharedPreferences(LEGACY_ENCRYPTED_FILE)
                }
                legacyMigrated = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate the old encrypted prefs: ${e.javaClass.simpleName}")
            }
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

/**
 * Opens the old security-crypto file, only for the one-time migration in [SecretStore]. Remove it
 * with the security-crypto dependency one release after the migration.
 */
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
