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

internal fun resolveProviderModel(selectedModel: String, customModel: String): String? =
    resolveVoiceModel(selectedModel, customModel)

internal const val MODEL_CUSTOM = "custom"

/**
 * Slugs (other than [MODEL_CUSTOM]) that the voice screen offers for each provider. Used to
 * decide whether the user's saved selection is still valid after switching provider — if it
 * is, we leave it alone instead of silently overwriting a deliberate choice.
 */
private val OPENROUTER_VOICE_SLUGS = setOf(
    "mistralai/voxtral-small-24b-2507",
    "google/gemini-2.5-flash-lite",
    "google/gemini-2.5-flash",
    "openai/gpt-4o-audio-preview",
    "openai/gpt-audio",
)
private val OPENROUTER_STT_SLUGS = setOf(
    "openai/gpt-4o-mini-transcribe",
    "openai/whisper-large-v3-turbo",
    "openai/whisper-large-v3",
    "openai/whisper-1",
    "openai/gpt-4o-transcribe",
)
private val PAYPERQ_VOICE_SLUGS = setOf(
    "mistralai/voxtral-small-24b-2507",
    "openai/gpt-audio-mini",
    "xiaomi/mimo-v2-omni",
    "openai/gpt-4o-audio-preview",
    "openai/gpt-audio",
)
// Text-fix list happens to be identical across providers today; kept per-provider so
// updates to either side don't require a refactor here.
private val OPENROUTER_TEXT_FIX_SLUGS = setOf(
    "openai/gpt-5.4-mini",
    "openai/gpt-5.4-nano",
    "google/gemini-3-flash-preview",
    "deepseek/deepseek-v4-pro",
    "anthropic/claude-haiku-4.5",
)
private val PAYPERQ_TEXT_FIX_SLUGS = OPENROUTER_TEXT_FIX_SLUGS

internal fun AiProvider.supportsVoiceSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_VOICE_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_VOICE_SLUGS
    }
}

internal fun supportsOpenRouterSttSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in OPENROUTER_STT_SLUGS
}

internal fun AiProvider.supportsTextFixSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_TEXT_FIX_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_TEXT_FIX_SLUGS
    }
}
