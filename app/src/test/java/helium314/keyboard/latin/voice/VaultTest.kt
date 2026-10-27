// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test

/** Checks the blob format of [Vault] with a software AES key (the AndroidKeyStore key is device-only). */
class VaultTest {
    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val vault = Vault(softwareKey())
    private val plain = "sk-test-0000".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTrip() {
        assertContentEquals(plain, vault.decrypt(vault.encrypt(plain)))
    }

    @Test
    fun emptyValueRoundTrip() {
        assertContentEquals(ByteArray(0), vault.decrypt(vault.encrypt(ByteArray(0))))
    }

    @Test
    fun blobIsIvThenCiphertextWithTag() {
        val blob = vault.encrypt(plain)
        assertEquals(Vault.IV_BYTES + plain.size + Vault.TAG_BITS / 8, blob.size)
    }

    @Test
    fun eachEncryptionUsesAFreshIv() {
        val a = vault.encrypt(plain)
        val b = vault.encrypt(plain)
        assertFalse(a.copyOfRange(0, Vault.IV_BYTES).contentEquals(b.copyOfRange(0, Vault.IV_BYTES)))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun changedByteIsRefused() {
        val blob = vault.encrypt(plain)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { vault.decrypt(blob) }
    }

    @Test
    fun shortBlobIsRefused() {
        assertFailsWith<GeneralSecurityException> { vault.decrypt(ByteArray(Vault.IV_BYTES + Vault.TAG_BITS / 8 - 1)) }
    }

    @Test
    fun otherKeyIsRefused() {
        val blob = vault.encrypt(plain)
        assertFailsWith<GeneralSecurityException> { Vault(softwareKey()).decrypt(blob) }
    }
}
