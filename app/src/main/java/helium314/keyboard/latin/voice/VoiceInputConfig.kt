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
    translationTargetsRaw: String = "",
): ResolvedVoicePrompt {
    val base = savedPrompt.trim().takeIf { it.isNotEmpty() } ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
    val dictionaryTerms = parseVoiceDictionaryTerms(transcriptionDictionaryRaw)
    val translationTargets = parseTranslationTargets(translationTargetsRaw)
    val systemPrompt = listOfNotNull(
        base,
        dictionaryInstruction(dictionaryTerms),
        translationInstruction(translationTargets),
    )
        .joinToString("\n")
        .trim()
    return ResolvedVoicePrompt(
        systemPrompt = systemPrompt,
        runtimeInstruction = localeHintInstruction(systemPrompt, localeHint),
    )
}

internal fun parseTranslationTargets(raw: String): List<String> =
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

private fun translationInstruction(targets: List<String>): String? {
    if (targets.isEmpty()) return null
    return if (targets.size == 1) {
        "Override any earlier output-format instruction. Translate the final result into natural ${targets.first()} and output only the ${targets.first()} translation."
    } else {
        "Override any earlier output-format instruction. Translate the final result into each of the following languages: ${targets.joinToString(", ")}. Output one block per language in the same order, prefix each block with the language name, and do not add extra commentary."
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
