// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import helium314.keyboard.latin.settings.Defaults
import java.util.Locale

/**
 * Strip control characters (Cc) and a narrow set of bidi/format marks that corrupt host editors,
 * but intentionally preserve U+200D (ZWJ) so emoji sequences survive.
 */
internal fun sanitizeModelOutput(raw: String, maxLength: Int): String {
    val cleaned = raw.replace(
        Regex("[\\p{Cc}\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"),
        ""
    ).trim()
    if (cleaned.length <= maxLength) return cleaned
    // Avoid splitting a surrogate pair at the truncation boundary.
    var end = maxLength
    if (end > 0 && Character.isHighSurrogate(cleaned[end - 1])) end -= 1
    return cleaned.substring(0, end)
}

/**
 * Prepends a space when the cursor sits immediately after a letter/digit, and appends one
 * when the next char is a letter/digit. Keeps `"hello".world` from becoming `"hello"world`.
 * Accepts a [VoiceInputManager.SpacingContext] whose fields are Unicode code points (surrogate-pair safe),
 * or null to leave the text unchanged.
 */
internal fun applySpacing(text: String, ctx: VoiceInputManager.SpacingContext?): String {
    if (text.isEmpty() || ctx == null) return text
    val leadingCp = Character.codePointAt(text, 0)
    val trailingCp = Character.codePointBefore(text, text.length)
    val leadingIsWhitespace = Character.isWhitespace(leadingCp)
    val trailingIsWhitespace = Character.isWhitespace(trailingCp)
    val needsLeading = ctx.charBefore?.let { Character.isLetterOrDigit(it) && !leadingIsWhitespace } == true
    val needsTrailing = ctx.charAfter?.let { Character.isLetterOrDigit(it) && !trailingIsWhitespace } == true
    val prefix = if (needsLeading) " " else ""
    val suffix = if (needsTrailing) " " else ""
    return prefix + text + suffix
}

internal fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Suppress("DEPRECATION")
    val activeNetwork = cm.activeNetworkInfo ?: return false
    @Suppress("DEPRECATION")
    return activeNetwork.isConnected
}

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
