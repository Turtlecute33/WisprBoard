// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState
import android.widget.Toast

@Composable
fun TextFixScreen(
    onClickBack: () -> Unit,
) {
    val enabled by rememberBooleanPreferenceState(
        Settings.PREF_TEXT_FIX_ENABLED,
        Defaults.PREF_TEXT_FIX_ENABLED
    )
    val model by rememberStringPreferenceState(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
    val provider = AiProvider.fromPref(providerPref)

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_text_fix),
        settings = listOf(
            Settings.PREF_TEXT_FIX_ENABLED,
            if (enabled) Settings.PREF_AI_PROVIDER else null,
            if (enabled) provider.apiKeyPrefKey() else null,
            if (enabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
            if (enabled) Settings.PREF_TEXT_FIX_MODEL else null,
            if (enabled && model == "custom") Settings.PREF_TEXT_FIX_MODEL_CUSTOM else null,
            if (enabled) Settings.PREF_TEXT_FIX_PROMPT else null,
        )
    )
}

fun createTextFixSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TEXT_FIX_ENABLED, R.string.text_fix_enabled, R.string.text_fix_enabled_summary) {
        val ctx = LocalContext.current
        val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
        SwitchPreference(
            it,
            Defaults.PREF_TEXT_FIX_ENABLED,
            allowCheckedChange = { enabling ->
                if (enabling && !SecretStore.isSecureStorageAvailable(ctx)) {
                    Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                    false
                } else true
            }
        )
    },
    Setting(context, Settings.PREF_TEXT_FIX_MODEL, R.string.text_fix_model) { setting ->
        val ctx = LocalContext.current
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val items = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> listOf(
                "GPT-5.4 Nano (Default, Cheap, ZDR + Cache)" to "openai/gpt-5.4-nano",
                "GPT-5.4 Mini (Medium, ZDR + Cache)" to "openai/gpt-5.4-mini",
                "Gemini 3 Flash Preview (Medium, ZDR + Cache)" to "google/gemini-3-flash-preview",
                "DeepSeek V4 Pro (Medium, ZDR)" to "deepseek/deepseek-v4-pro",
                "Claude Haiku 4.5 (Cheap, ZDR + Cache)" to "anthropic/claude-haiku-4.5",
                ctx.getString(R.string.voice_custom_model) to "custom",
            )
            AiProvider.PAYPERQ -> listOf(
                "Private Gemma4 31B (Default, Cheap, TEE)" to "private/gemma4-31b",
                "Private GLM 5.1 (Medium, TEE)" to "private/glm-5-1",
                "Private Kimi K2 6 (Expensive, TEE)" to "private/kimi-k2-6",
                "Gemini 3.1 Flash-Lite Preview (Cheap, no-store)" to "google/gemini-3.1-flash-lite-preview",
                "Mistral Small 3 (Cheap, EU no-store)" to "mistralai/mistral-small-3",
                ctx.getString(R.string.voice_custom_model) to "custom",
            )
        }
        ListPreference(setting, items, Defaults.PREF_TEXT_FIX_MODEL)
    },
    Setting(context, Settings.PREF_TEXT_FIX_MODEL_CUSTOM, R.string.text_fix_model_custom, R.string.text_fix_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_TEXT_FIX_MODEL_CUSTOM)
    },
    Setting(context, Settings.PREF_TEXT_FIX_PROMPT, R.string.text_fix_prompt, R.string.text_fix_prompt_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TEXT_FIX_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TEXT_FIX_PROMPT) } },
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
            TextFixScreen { }
        }
    }
}
