// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the AI provider API keys with one AES-256-GCM key (report TURTLEBOARD-SEC-008).
 * [SecretStore] gives the AndroidKeyStore key from [androidKeyStoreKey] (`wisprboard.vault.v1`,
 * StrongBox when available, else TEE); unit tests give a software key. No user-auth or unlock
 * binding: dictation also works on the lock screen. Blob = 12-byte random IV + GCM output.
 */
internal class Vault(private val key: SecretKey) {

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) // the provider picks a fresh random IV
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected IV length")
        return iv + cipher.doFinal(plain)
    }

    /** Throws [GeneralSecurityException] when [blob] is damaged or the key cannot decrypt it (e.g. a new key). */
    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("Ciphertext too short")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: ProviderException) {
            // Keystore failures surface as this unchecked exception.
            throw GeneralSecurityException(e)
        }
    }

    companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "wisprboard.vault.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Loads or makes the key. Throws when the keystore is not available (for example during an OS update). */
        @RequiresApi(Build.VERSION_CODES.M)
        fun androidKeyStoreKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) strongBoxKeyOrNull()?.let { return it }
            return generateKey(spec().build())
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun strongBoxKeyOrNull(): SecretKey? = try {
            generateKey(spec().setIsStrongBoxBacked(true).build())
        } catch (_: StrongBoxUnavailableException) {
            null
        } catch (_: ProviderException) {
            null // some devices report a StrongBox failure like this; the TEE key is the fallback
        }

        @RequiresApi(Build.VERSION_CODES.M)
        private fun spec() = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        @RequiresApi(Build.VERSION_CODES.M)
        private fun generateKey(spec: KeyGenParameterSpec): SecretKey =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }
    }
}
