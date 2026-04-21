// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.OpenRouterClient
import helium314.keyboard.latin.voice.parseVoiceDictionaryTerms
import helium314.keyboard.latin.voice.parseTranslationTargets
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceScreen(
    onClickBack: () -> Unit,
) {
    val voiceInputEnabled by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_INPUT_ENABLED,
        Defaults.PREF_VOICE_INPUT_ENABLED
    )
    val voiceModel by rememberStringPreferenceState(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
    val voiceAutoStop by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_AUTO_STOP_SILENCE,
        Defaults.PREF_VOICE_AUTO_STOP_SILENCE
    )

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice),
        settings = buildVoiceScreenItems(
            voiceInputEnabled = voiceInputEnabled,
            voiceModel = voiceModel,
            voiceAutoStop = voiceAutoStop,
        )
    )
}

internal fun buildVoiceScreenItems(
    voiceInputEnabled: Boolean,
    voiceModel: String,
    voiceAutoStop: Boolean = Defaults.PREF_VOICE_AUTO_STOP_SILENCE,
): List<Any?> = listOf(
    Settings.PREF_VOICE_INPUT_ENABLED,
    if (voiceInputEnabled) Settings.PREF_OPENROUTER_API_KEY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_ACTION_TEST_KEY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_MODEL else null,
    if (voiceInputEnabled && voiceModel == "custom") Settings.PREF_VOICE_MODEL_CUSTOM else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_ACTION_PROMPT_PRESET else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRANSCRIPTION_PROMPT else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRANSLATION_LANGUAGES else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_LANGUAGE_HINT else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_SPACE_HEURISTIC else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_HAPTIC_FEEDBACK else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_MAX_DURATION_SECONDS else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_AUTO_STOP_SILENCE else null,
    if (voiceInputEnabled && voiceAutoStop) Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS else null,
)

fun createVoiceSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_VOICE_INPUT_ENABLED, R.string.voice_input_enabled, R.string.voice_input_enabled_summary) { setting ->
        val ctx = LocalContext.current
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ -> }
        SwitchPreference(setting, Defaults.PREF_VOICE_INPUT_ENABLED) { enabled ->
            if (enabled && !PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    },
    Setting(context, Settings.PREF_OPENROUTER_API_KEY, R.string.openrouter_api_key, R.string.openrouter_api_key_summary) {
        VoiceApiKeyPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_MODEL, R.string.voice_model) { setting ->
        val ctx = LocalContext.current
        val items = listOf(
            "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
            "google/gemini-2.0-flash-001" to "google/gemini-2.0-flash-001",
            ctx.getString(R.string.voice_custom_model) to "custom",
        )
        ListPreference(setting, items, Defaults.PREF_VOICE_MODEL)
    },
    Setting(context, Settings.PREF_VOICE_MODEL_CUSTOM, R.string.voice_model_custom, R.string.voice_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_VOICE_MODEL_CUSTOM)
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRANSCRIPTION_PROMPT,
        R.string.voice_transcription_prompt,
        R.string.voice_transcription_prompt_summary
    ) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
        R.string.voice_transcription_dictionary,
        R.string.voice_transcription_dictionary_summary
    ) {
        VoiceDictionaryPreference(it)
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRANSLATION_LANGUAGES,
        R.string.voice_translation_languages,
        R.string.voice_translation_languages_summary
    ) {
        VoiceTranslationLanguagesPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_PROMPT_PRESET, R.string.voice_prompt_preset) {
        VoicePromptPresetPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_TEST_KEY, R.string.voice_test_key) {
        VoiceTestKeyPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_LANGUAGE_HINT, R.string.voice_language_hint, R.string.voice_language_hint_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_LANGUAGE_HINT)
    },
    Setting(context, Settings.PREF_VOICE_SPACE_HEURISTIC, R.string.voice_space_heuristic, R.string.voice_space_heuristic_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_SPACE_HEURISTIC)
    },
    Setting(context, Settings.PREF_VOICE_HAPTIC_FEEDBACK, R.string.voice_haptic_feedback, R.string.voice_haptic_feedback_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_HAPTIC_FEEDBACK)
    },
    Setting(context, Settings.PREF_VOICE_MAX_DURATION_SECONDS, R.string.voice_max_duration, R.string.voice_max_duration_summary) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VOICE_MAX_DURATION_SECONDS,
            description = { stringResource(R.string.voice_max_duration_seconds, it) },
            range = 15f..300f,
        )
    },
    Setting(context, Settings.PREF_VOICE_AUTO_STOP_SILENCE, R.string.voice_auto_stop_silence, R.string.voice_auto_stop_silence_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_AUTO_STOP_SILENCE)
    },
    Setting(context, Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, R.string.voice_auto_stop_silence_seconds) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            description = { stringResource(R.string.voice_max_duration_seconds, it) },
            range = 1f..10f,
        )
    },
)

@Composable
private fun VoiceApiKeyPreference(setting: Setting) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val stored = SecretStore.getApiKey(ctx, Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY)
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (stored.isNotEmpty()) "••••••••" else setting.description,
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { SecretStore.setApiKey(ctx, Settings.PREF_OPENROUTER_API_KEY, it.trim()) },
            initialText = stored,
            title = { Text(setting.title) },
            singleLine = true,
        )
    }
}

@Composable
private fun VoiceDictionaryPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
        Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
    )
    val displayValue = parseVoiceDictionaryTerms(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
                        parseVoiceDictionaryTerms(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoicePromptPresetPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        data class Preset(val labelRes: Int, val textRes: Int)
        val presets = listOf(
            Preset(R.string.voice_prompt_preset_verbatim, R.string.voice_prompt_preset_verbatim_text),
            Preset(R.string.voice_prompt_preset_clean, R.string.voice_prompt_preset_clean_text),
            Preset(R.string.voice_prompt_preset_punctuated, R.string.voice_prompt_preset_punctuated_text),
            Preset(R.string.voice_prompt_preset_translate_en, R.string.voice_prompt_preset_translate_en_text),
        )
        ListPickerDialog(
            onDismissRequest = { showDialog = false },
            items = presets,
            onItemSelected = { preset ->
                prefs.edit {
                    putString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, ctx.getString(preset.textRes))
                }
                showDialog = false
            },
            title = { Text(ctx.getString(R.string.voice_prompt_preset)) },
            getItemName = { ctx.getString(it.labelRes) },
        )
    }
}

@Composable
private fun VoiceTranslationLanguagesPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_TRANSLATION_LANGUAGES,
        Defaults.PREF_VOICE_TRANSLATION_LANGUAGES
    )
    val displayValue = parseTranslationTargets(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_TRANSLATION_LANGUAGES,
                        parseTranslationTargets(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoiceTestKeyPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }
    Preference(
        name = setting.title,
        description = if (busy) stringResource(R.string.voice_test_key_testing) else null,
        onClick = {
            if (busy) return@Preference
            val apiKey = SecretStore.getApiKey(ctx, Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY)
            if (apiKey.isBlank()) {
                Toast.makeText(ctx, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
            val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
            val model = if (selectedModel == "custom") customModel.ifBlank { Defaults.PREF_VOICE_MODEL } else selectedModel
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { probeApiKey(apiKey, model) }
                val msgRes = when (result) {
                    TestResult.OK -> R.string.voice_test_key_success
                    TestResult.INVALID -> R.string.voice_test_key_invalid
                    TestResult.NETWORK -> R.string.voice_test_key_network_error
                }
                Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                busy = false
            }
        }
    )
}

private enum class TestResult { OK, INVALID, NETWORK }

private fun probeApiKey(apiKey: String, model: String): TestResult {
    val conn = (java.net.URL("https://openrouter.ai/api/v1/auth/key").openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (conn.responseCode) {
            200 -> TestResult.OK
            401, 403 -> TestResult.INVALID
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        conn.disconnect()
    }
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceScreen { }
        }
    }
}
