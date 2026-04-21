// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults
import java.util.Locale

internal data class ResolvedVoicePrompt(
    val systemPrompt: String,
    val runtimeInstruction: String?,
)

internal fun resolveVoiceModel(selectedModel: String, customModel: String): String? {
    if (selectedModel != "custom") {
        return selectedModel.trim().takeIf { it.isNotEmpty() }
    }
    return customModel.trim().takeIf { it.isNotEmpty() }
}

internal fun resolveVoicePrompt(
    savedPrompt: String,
    localeHint: Locale? = null,
    transcriptionDictionaryRaw: String = "",
    expectedLanguagesRaw: String = "",
): ResolvedVoicePrompt {
    val base = savedPrompt.trim().takeIf { it.isNotEmpty() } ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
    val dictionaryTerms = parseVoiceDictionaryTerms(transcriptionDictionaryRaw)
    val expectedLanguages = parseExpectedLanguages(expectedLanguagesRaw)
    val systemPrompt = listOfNotNull(
        base,
        dictionaryInstruction(dictionaryTerms),
        expectedLanguagesInstruction(expectedLanguages),
    )
        .joinToString("\n")
        .trim()
    return ResolvedVoicePrompt(
        systemPrompt = systemPrompt,
        runtimeInstruction = localeHintInstruction(systemPrompt, localeHint),
    )
}

internal fun parseExpectedLanguages(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

internal fun parseVoiceDictionaryTerms(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

internal fun shouldAttachPromptCacheHint(model: String): Boolean {
    val normalized = model.lowercase(Locale.ROOT)
    return normalized.startsWith("google/gemini") || normalized.startsWith("anthropic/")
}

private fun expectedLanguagesInstruction(languages: List<String>): String? {
    if (languages.isEmpty()) return null
    val joined = languages.joinToString(", ")
    return if (languages.size == 1) {
        "The speaker is expected to speak $joined. Transcribe in the spoken language only; do not translate and do not output multiple versions."
    } else {
        "The speaker may use any of these languages: $joined. Detect which one is actually spoken and transcribe in that language only. Do not translate and do not output the transcript in more than one language."
    }
}

private fun dictionaryInstruction(terms: List<String>): String? {
    if (terms.isEmpty()) return null
    return buildString {
        append("Prefer these exact spellings for names, brands, acronyms, and technical terms whenever they match the audio context: ")
        append(terms.joinToString(", "))
        append(". Do not force them when the audio clearly says something else.")
    }
}

private fun localeHintInstruction(systemPrompt: String, localeHint: Locale?): String? {
    if (localeHint == null) return null
    val tag = localeHint.toLanguageTag()
    if (tag.isBlank() || tag.equals("und", ignoreCase = true)) return null
    if (systemPrompt.contains(tag, ignoreCase = true)) return null
    val display = localeHint.getDisplayName(Locale.ENGLISH).ifBlank { tag }
    return "Expected spoken language: $display [$tag]."
}
