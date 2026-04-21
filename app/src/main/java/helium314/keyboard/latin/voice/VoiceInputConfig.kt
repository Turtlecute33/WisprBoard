// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults
import java.util.Locale

internal fun resolveVoiceModel(selectedModel: String, customModel: String): String? {
    if (selectedModel != "custom") {
        return selectedModel.trim().takeIf { it.isNotEmpty() }
    }
    return customModel.trim().takeIf { it.isNotEmpty() }
}

internal fun resolveTranscriptionPrompt(savedPrompt: String, localeHint: Locale? = null): String {
    val base = savedPrompt.trim().takeIf { it.isNotEmpty() } ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
    if (localeHint == null) return base
    val tag = localeHint.toLanguageTag()
    if (tag.isBlank() || tag.equals("und", ignoreCase = true)) return base
    // Avoid duplicating if the user's prompt already contains the tag.
    if (base.contains(tag, ignoreCase = true)) return base
    val display = localeHint.getDisplayName(Locale.ENGLISH).ifBlank { tag }
    return "$base\n(Expected language: $display [$tag].)"
}
