// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.R
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputConfigTest {
    @Test
    fun parseVoiceDictionaryTermsSplitsAndDeduplicates() {
        assertEquals(
            listOf("OpenRouter", "WisprBoard", "gRPC"),
            parseVoiceDictionaryTerms("OpenRouter, WisprBoard\nopenrouter; gRPC"),
        )
    }

    @Test
    fun parseExpectedLanguagesSplitsAndDeduplicates() {
        assertEquals(
            listOf("English", "Italian", "Deutsch"),
            parseExpectedLanguages("English, Italian\nenglish; Deutsch"),
        )
    }

    @Test
    fun resolveVoicePromptAppendsSingleExpectedLanguage() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            expectedLanguagesRaw = "English",
        )

        assertTrue(prompt.systemPrompt.contains("The speaker is expected to speak English"))
        assertTrue(prompt.systemPrompt.contains("do not translate", ignoreCase = true))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptAppendsDictionaryToCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            transcriptionDictionaryRaw = "OpenRouter, WisprBoard, gRPC",
        )

        assertTrue(prompt.systemPrompt.contains("Strict dictionary"))
        assertTrue(prompt.systemPrompt.contains("MUST output the exact spelling"))
        assertTrue(prompt.systemPrompt.contains("OpenRouter, WisprBoard, gRPC"))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptKeepsLocaleHintOutOfCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            localeHint = Locale.forLanguageTag("it-IT"),
            transcriptionDictionaryRaw = "OpenRouter",
            expectedLanguagesRaw = "English, Italian",
        )

        assertTrue(prompt.systemPrompt.contains("OpenRouter"))
        assertTrue(prompt.systemPrompt.contains("English, Italian"))
        assertTrue(prompt.systemPrompt.contains("translate", ignoreCase = true))
        assertFalse(prompt.systemPrompt.contains("it-IT", ignoreCase = true))
        assertEquals("Expected spoken language: Italian (Italy) [it-IT].", prompt.runtimeInstruction)
    }


    @Test
    fun providerStatusesWithAClearActionAreTranslated() {
        // These tell the user something they can fix, so they get a real message instead of the
        // raw untranslated literal "API error: 402".
        assertEquals(R.string.ai_error_rejected_request, actionableErrorResId(400))
        assertEquals(R.string.ai_error_bad_key, actionableErrorResId(401))
        assertEquals(R.string.ai_error_out_of_credit, actionableErrorResId(402))
        assertEquals(R.string.ai_error_model_not_found, actionableErrorResId(404))
        assertEquals(R.string.ai_error_too_large, actionableErrorResId(413))
        assertEquals(R.string.voice_error_rate_limited, actionableErrorResId(429))
        // And the statuses with different actions must not collide with each other.
        assertEquals(
            6,
            setOf(400, 401, 402, 404, 413, 429).map { actionableErrorResId(it) }.toSet().size,
        )
    }

    @Test
    fun forbiddenIsAModerationRefusalNotABadKey() {
        // 403 on OpenRouter means the request was refused by moderation, not that the key is bad.
        // Sending the user to check their API key would be confidently wrong.
        assertEquals(R.string.ai_error_refused_content, actionableErrorResId(403))
        assertNotEquals(R.string.ai_error_bad_key, actionableErrorResId(403))
    }

    @Test
    fun serverErrorsReadAsProviderDownNotAsRateLimit() {
        for (status in listOf(500, 502, 503, 504)) {
            assertEquals(R.string.ai_error_provider_down, actionableErrorResId(status), "status $status")
        }
        assertNotEquals(R.string.voice_error_rate_limited, actionableErrorResId(503))
    }

    @Test
    fun timeoutAndNetworkFailuresHaveTheirOwnText() {
        assertEquals(R.string.ai_error_timeout, actionableErrorResId(OpenRouterClient.STATUS_TIMEOUT))
        assertEquals(R.string.ai_error_timeout, actionableErrorResId(408))
        assertEquals(R.string.voice_error_no_network, actionableErrorResId(OpenRouterClient.STATUS_NETWORK))
        // Unknown statuses fall back to the caller's generic text.
        assertNull(actionableErrorResId(-1))
        assertNull(actionableErrorResId(418))
    }
}
