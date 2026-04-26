// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults

enum class AiProvider(val prefValue: String) {
    OPENROUTER("openrouter"),
    PAYPERQ("payperq");

    companion object {
        fun fromPref(value: String?): AiProvider =
            values().firstOrNull { it.prefValue == value } ?: OPENROUTER
    }
}

internal fun AiProvider.apiKeyPrefKey(): String = when (this) {
    AiProvider.OPENROUTER -> helium314.keyboard.latin.settings.Settings.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> helium314.keyboard.latin.settings.Settings.PREF_PAYPERQ_API_KEY
}

internal fun AiProvider.defaultApiKey(): String = when (this) {
    AiProvider.OPENROUTER -> Defaults.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> Defaults.PREF_PAYPERQ_API_KEY
}

internal fun resolveProviderModel(provider: AiProvider, selectedModel: String, customModel: String, fallback: String): String? {
    val resolved = resolveVoiceModel(selectedModel, customModel) ?: return null
    if (provider == AiProvider.PAYPERQ && selectedModel != "custom" && "/" in resolved) {
        return fallback
    }
    return resolved
}
