// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.ModelCatalog
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.isValidCustomModelSlug
import helium314.keyboard.latin.voice.parseTranslateLanguages
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ModelListPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState

@Composable
fun TranslateScreen(
    onClickBack: () -> Unit,
) {
    val enabled by rememberBooleanPreferenceState(
        Settings.PREF_TRANSLATE_ENABLED,
        Defaults.PREF_TRANSLATE_ENABLED
    )
    val model by rememberStringPreferenceState(Settings.PREF_TRANSLATE_MODEL, Defaults.PREF_TRANSLATE_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
    val provider = AiProvider.fromPref(providerPref)

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_translate),
        settings = listOf(
            Settings.PREF_TRANSLATE_ENABLED,
            if (enabled) Settings.PREF_TRANSLATE_LANGUAGES else null,
            if (enabled) Settings.PREF_AI_PROVIDER else null,
            if (enabled) provider.apiKeyPrefKey() else null,
            if (enabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
            if (enabled) Settings.PREF_TRANSLATE_MODEL else null,
            if (enabled && model == "custom") Settings.PREF_TRANSLATE_MODEL_CUSTOM else null,
            if (enabled) Settings.PREF_TRANSLATE_PROMPT else null,
        )
    )
}

fun createTranslateSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TRANSLATE_ENABLED, R.string.translate_enabled, R.string.translate_enabled_summary) {
        // No skip: the Translate disclosure names the clipboard, so it shows also when Text Fix is on.
        AiFeatureEnableSwitch(
            it,
            Defaults.PREF_TRANSLATE_ENABLED,
            R.string.translate_enable_privacy_title,
            R.string.translate_enable_privacy_message,
            R.string.translate_enable_privacy_confirm,
        )
    },
    Setting(context, Settings.PREF_TRANSLATE_LANGUAGES, R.string.translate_languages, R.string.translate_languages_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TRANSLATE_LANGUAGES,
            info = stringResource(R.string.translate_languages_hint),
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TRANSLATE_LANGUAGES) } },
            // At least one usable language, or the middle menu would open empty.
            checkTextValid = { text -> parseTranslateLanguages(text).isNotEmpty() }
        )
    },
    Setting(context, Settings.PREF_TRANSLATE_MODEL, R.string.translate_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val entries = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_TEXT_FIX
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_TEXT_FIX
        }
        ModelListPreference(setting, entries, Defaults.PREF_TRANSLATE_MODEL)
    },
    Setting(context, Settings.PREF_TRANSLATE_MODEL_CUSTOM, R.string.translate_model_custom, R.string.translate_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_TRANSLATE_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(context, Settings.PREF_TRANSLATE_PROMPT, R.string.translate_prompt, R.string.translate_prompt_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TRANSLATE_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TRANSLATE_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TranslateScreen { }
        }
    }
}
