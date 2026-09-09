// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun onlyTheProviderStatusesWithAClearActionAreTranslated() {
        // These three tell the user something they can fix, so they get a real message instead of
        // the raw untranslated literal "API error: 402".
        assertNotNull(actionableErrorResId(401))
        assertNotNull(actionableErrorResId(402))
        assertNotNull(actionableErrorResId(404))
        // 403 on OpenRouter means the request was refused by moderation, not that the key is bad.
        // Sending the user to check their API key would be confidently wrong, which is worse than
        // an opaque status code.
        assertNull(actionableErrorResId(403))
        // Handled by safeUserFacingError as rate limits before this is consulted.
        assertNull(actionableErrorResId(429))
        assertNull(actionableErrorResId(503))
        assertNull(actionableErrorResId(500))
        assertNull(actionableErrorResId(413))
        // And the three statuses must not collide with each other.
        assertEquals(
            3,
            setOf(actionableErrorResId(401), actionableErrorResId(402), actionableErrorResId(404)).size,
        )
    }
}
