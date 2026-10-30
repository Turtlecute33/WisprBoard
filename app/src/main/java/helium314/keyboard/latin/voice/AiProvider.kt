// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

enum class AiProvider(val prefValue: String) {
    OPENROUTER("openrouter"),
    PAYPERQ("payperq");

    companion object {
        /** @JvmStatic so LatinIME (Java) can resolve the provider without a Companion hop. */
        @JvmStatic
        fun fromPref(value: String?): AiProvider =
            entries.firstOrNull { it.prefValue == value } ?: OPENROUTER
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
 * Slugs (other than [MODEL_CUSTOM]) that each picker offers, derived from [ModelCatalog].
 * Used to decide whether the user's saved selection is still valid after switching provider
 * — if it is, we leave it alone instead of silently overwriting a deliberate choice.
 *
 * Note: OpenRouter "latest" floating aliases are catalogued literally as `~author/model-latest`
 * (e.g. `~google/gemini-flash-latest`). The leading tilde is part of the slug — requests using
 * the bare form return 400 "not a valid model ID". Pinned slugs use the bare form.
 */
private val OPENROUTER_VOICE_SLUGS: Set<String> = ModelCatalog.OPENROUTER_VOICE.mapTo(LinkedHashSet()) { it.slug }
private val OPENROUTER_STT_SLUGS: Set<String> = ModelCatalog.OPENROUTER_STT.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_STT_SLUGS: Set<String> = ModelCatalog.PAYPERQ_STT.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_VOICE_SLUGS: Set<String> = ModelCatalog.PAYPERQ_VOICE.mapTo(LinkedHashSet()) { it.slug }
private val OPENROUTER_TEXT_FIX_SLUGS: Set<String> = ModelCatalog.OPENROUTER_TEXT_FIX.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_TEXT_FIX_SLUGS: Set<String> = ModelCatalog.PAYPERQ_TEXT_FIX.mapTo(LinkedHashSet()) { it.slug }

internal fun AiProvider.supportsVoiceSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_VOICE_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_VOICE_SLUGS
    }
}

internal fun AiProvider.supportsSttSlug(slug: String): Boolean {
    // PayPerQ's transcription endpoint routes by capability and ignores the `model` field, so
    // "Custom" there is a dead end: the picker offers it, no slug field is ever shown, and the
    // resolved model comes back null. Refusing it here makes both existing coercion sites in
    // VoiceScreen reset a slug carried over from OpenRouter automatically.
    if (slug == MODEL_CUSTOM) return this == AiProvider.OPENROUTER
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_STT_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_STT_SLUGS
    }
}

/**
 * The transcription model to fall back to when the saved one belongs to the other provider. The two
 * dedicated endpoints do not share a namespace, so there is no single default that suits both.
 */
internal fun AiProvider.defaultSttModel(): String = when (this) {
    AiProvider.OPENROUTER -> Defaults.PREF_VOICE_STT_MODEL
    AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_STT.first().slug
}

internal fun AiProvider.supportsTextFixSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_TEXT_FIX_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_TEXT_FIX_SLUGS
    }
}

/**
 * Resets each saved model that this provider cannot serve. VoiceScreen and the setup wizard call it
 * when the user selects a provider. A model that the provider can serve stays, so a re-pick of the
 * same provider, or a switch away and back, keeps a deliberate choice. The voice and Text Fix
 * defaults are slugs that both providers support, so one fallback works for each.
 */
internal fun AiProvider.resetUnsupportedModels(prefs: SharedPreferences) {
    val currentVoice = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
        ?: Defaults.PREF_VOICE_MODEL
    val currentStt = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
        ?: Defaults.PREF_VOICE_STT_MODEL
    val currentTextFix = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
        ?: Defaults.PREF_TEXT_FIX_MODEL
    val currentPolish = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
        ?: Defaults.PREF_VOICE_POLISH_MODEL
    prefs.edit {
        if (!supportsVoiceSlug(currentVoice)) {
            putString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
        }
        if (!supportsSttSlug(currentStt)) {
            putString(Settings.PREF_VOICE_STT_MODEL, defaultSttModel())
        }
        if (!supportsTextFixSlug(currentTextFix)) {
            putString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
        }
        if (!supportsTextFixSlug(currentPolish)) {
            putString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
        }
    }
}

// Accepts: author/slug, author/slug:variant, ~author/slug-latest. Rejects whitespace, bare names,
// missing slug. Empty input is allowed so the user can clear the field without a validation block.
private val CUSTOM_MODEL_SLUG_REGEX = Regex("""^~?[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?/[A-Za-z0-9](?:[A-Za-z0-9._:-]*[A-Za-z0-9])?$""")

internal fun isValidCustomModelSlug(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return true
    return CUSTOM_MODEL_SLUG_REGEX.matches(trimmed)
}
