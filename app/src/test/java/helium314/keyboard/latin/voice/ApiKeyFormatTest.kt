// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** R-01: only a key that can go into an HTTP header is accepted. Made-up keys only. */
class ApiKeyFormatTest {
    @Test
    fun printableAsciiKeyIsValid() {
        assertTrue(SecretStore.isValidApiKey("sk-or-v1-0123456789abcdef"))
    }

    @Test
    fun controlSpaceAndNonAsciiCharactersAreRejected() {
        for (bad in listOf("", "sk-or\nv1", "sk-or\rv1", "sk-or v1", "sk-or\tv1", "sk-or\u200Bv1", "sk-or\u00A0v1", "sk-or-\u00E9")) {
            assertFalse(SecretStore.isValidApiKey(bad), "accepted: ${bad.map { it.code }}")
        }
    }
}
