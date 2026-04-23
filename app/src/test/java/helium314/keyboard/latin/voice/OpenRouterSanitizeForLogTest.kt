// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterSanitizeForLogTest {
    private val client = OpenRouterClient(
        apiKey = "unused",
        model = "unused/unused",
        systemPrompt = "unused",
        runtimeInstruction = null,
    )

    @Test
    fun bearerTokenIsMasked() {
        val out = client.sanitizeForLog("Bearer xyz")
        assertEquals("Bearer ***", out)
    }

    @Test
    fun authorizationHeaderIsMasked() {
        val out = client.sanitizeForLog("Authorization: Bearer xyz")
        // Both the Bearer and Authorization patterns apply; the important contract is that
        // the secret ("xyz") is gone and the Authorization label is masked.
        assertFalse("sensitive content must not leak", out.contains("xyz"))
        assertTrue("output should still contain a mask marker", out.contains("***"))
        assertTrue("Authorization label should be preserved with a mask", out.contains("Authorization: ***"))
    }

    @Test
    fun xApiKeyHeaderIsMasked() {
        val out = client.sanitizeForLog("X-Api-Key: abc")
        assertFalse(out.contains("abc"))
        assertEquals("X-Api-Key: ***", out)
    }

    @Test
    fun apiKeyAssignmentIsMasked() {
        val out = client.sanitizeForLog("api_key=secret")
        assertFalse(out.contains("secret"))
    }

    @Test
    fun apiKeyJsonPairIsMasked() {
        val out = client.sanitizeForLog("\"api-key\": \"secret\"")
        assertFalse("the secret value must not leak", out.contains("secret"))
    }

    @Test
    fun tokenAssignmentIsMasked() {
        val out = client.sanitizeForLog("token=abc123")
        assertFalse(out.contains("abc123"))
    }

    @Test
    fun skPrefixedSecretIsMasked() {
        val out = client.sanitizeForLog("sk-abcdef0123456789")
        assertEquals("sk-***", out)
    }

    @Test
    fun cleanTextPassesThroughUnchanged() {
        val clean = "Request timed out after retries"
        assertEquals(clean, client.sanitizeForLog(clean))
    }
}
