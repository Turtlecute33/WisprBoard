// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputConfigTest {
    @Test
    fun parseVoiceDictionaryTermsSplitsAndDeduplicates() {
        assertEquals(
            listOf("OpenRouter", "TurtleBoard", "gRPC"),
            parseVoiceDictionaryTerms("OpenRouter, TurtleBoard\nopenrouter; gRPC"),
        )
    }

    @Test
    fun parseTranslationTargetsSplitsAndDeduplicates() {
        assertEquals(
            listOf("English", "Italian", "Deutsch"),
            parseTranslationTargets("English, Italian\nenglish; Deutsch"),
        )
    }

    @Test
    fun resolveVoicePromptAppendsSingleTranslationLanguage() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            translationTargetsRaw = "English",
        )

        assertTrue(prompt.systemPrompt.contains("Translate the final result into natural English"))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptAppendsDictionaryToCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            transcriptionDictionaryRaw = "OpenRouter, TurtleBoard, gRPC",
        )

        assertTrue(prompt.systemPrompt.contains("Prefer these exact spellings"))
        assertTrue(prompt.systemPrompt.contains("OpenRouter, TurtleBoard, gRPC"))
        assertNull(prompt.runtimeInstruction)
    }

    @Test
    fun resolveVoicePromptKeepsLocaleHintOutOfCachedSystemPrompt() {
        val prompt = resolveVoicePrompt(
            savedPrompt = "Transcribe this audio exactly as spoken.",
            localeHint = Locale.forLanguageTag("it-IT"),
            transcriptionDictionaryRaw = "OpenRouter",
            translationTargetsRaw = "English, Italian",
        )

        assertTrue(prompt.systemPrompt.contains("OpenRouter"))
        assertTrue(prompt.systemPrompt.contains("English, Italian"))
        assertFalse(prompt.systemPrompt.contains("it-IT", ignoreCase = true))
        assertEquals("Expected spoken language: Italian (Italy) [it-IT].", prompt.runtimeInstruction)
    }

    @Test
    fun shouldAttachPromptCacheHintMatchesGeminiAndClaudeModels() {
        assertTrue(shouldAttachPromptCacheHint("google/gemini-3-flash-preview"))
        assertTrue(shouldAttachPromptCacheHint("anthropic/claude-sonnet-4"))
        assertFalse(shouldAttachPromptCacheHint("openai/gpt-4o-mini"))
    }
}
