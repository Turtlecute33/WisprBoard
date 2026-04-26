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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.OpenRouterClient
import helium314.keyboard.latin.voice.parseVoiceDictionaryTerms
import helium314.keyboard.latin.voice.parseExpectedLanguages
import helium314.keyboard.latin.voice.resolveVoiceModel
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.defaultApiKey
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
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
import org.json.JSONObject

@Composable
fun VoiceScreen(
    onClickBack: () -> Unit,
) {
    val voiceInputEnabled by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_INPUT_ENABLED,
        Defaults.PREF_VOICE_INPUT_ENABLED
    )
    val voiceModel by rememberStringPreferenceState(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
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
            provider = AiProvider.fromPref(providerPref),
            voiceAutoStop = voiceAutoStop,
        )
    )
}

internal fun buildVoiceScreenItems(
    voiceInputEnabled: Boolean,
    voiceModel: String,
    provider: AiProvider = AiProvider.OPENROUTER,
    voiceAutoStop: Boolean = Defaults.PREF_VOICE_AUTO_STOP_SILENCE,
): List<Any?> = listOf(
    Settings.PREF_VOICE_INPUT_ENABLED,
    if (voiceInputEnabled) Settings.PREF_AI_PROVIDER else null,
    if (voiceInputEnabled) provider.apiKeyPrefKey() else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_ACTION_TEST_KEY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_MODEL else null,
    if (voiceInputEnabled && voiceModel == "custom") Settings.PREF_VOICE_MODEL_CUSTOM else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_ACTION_PROMPT_PRESET else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRANSCRIPTION_PROMPT else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_EXPECTED_LANGUAGES else null,
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
        val prefs = ctx.prefs()
        val permissionDeniedMessage = stringResource(R.string.voice_error_no_permission)
        val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
        val pendingPermissionResult = remember {
            mutableStateOf<((Boolean) -> Unit)?>(null)
        }
        var showRationale by remember { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> pendingPermissionResult.value?.invoke(granted) }

        if (showRationale) {
            ConfirmationDialog(
                onDismissRequest = { showRationale = false; pendingPermissionResult.value = null },
                onConfirmed = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                title = { Text(stringResource(R.string.voice_mic_rationale_title)) },
                content = { Text(stringResource(R.string.voice_mic_rationale_message)) },
                confirmButtonText = stringResource(R.string.voice_mic_rationale_confirm),
            )
        }

        SwitchPreference(
            setting,
            Defaults.PREF_VOICE_INPUT_ENABLED,
            allowCheckedChange = { enabled ->
                if (!enabled) {
                    pendingPermissionResult.value = null
                    return@SwitchPreference true
                }
                if (!SecretStore.isSecureStorageAvailable(ctx)) {
                    Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                    return@SwitchPreference false
                }
                if (PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)) {
                    return@SwitchPreference true
                }
                pendingPermissionResult.value = { granted ->
                    if (granted) {
                        prefs.edit { putBoolean(setting.key, true) }
                    } else {
                        Toast.makeText(ctx, permissionDeniedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                // Show a rationale BEFORE the system prompt so the user knows what the
                // microphone will be used for and where the audio travels.
                showRationale = true
                false
            }
        )
    },
    Setting(context, Settings.PREF_OPENROUTER_API_KEY, R.string.openrouter_api_key, R.string.openrouter_api_key_summary) {
        VoiceApiKeyPreference(it, AiProvider.OPENROUTER)
    },
    Setting(context, Settings.PREF_PAYPERQ_API_KEY, R.string.payperq_api_key, R.string.payperq_api_key_summary) {
        VoiceApiKeyPreference(it, AiProvider.PAYPERQ)
    },
    Setting(context, Settings.PREF_AI_PROVIDER, R.string.ai_provider) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = listOf(
            ctx.getString(R.string.ai_provider_openrouter) to AiProvider.OPENROUTER.prefValue,
            ctx.getString(R.string.ai_provider_payperq) to AiProvider.PAYPERQ.prefValue,
        )
        ListPreference(setting, items, Defaults.PREF_AI_PROVIDER) { value ->
            val provider = AiProvider.fromPref(value)
            prefs.edit {
                when (provider) {
                    AiProvider.OPENROUTER -> {
                        putString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
                        putString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
                    }
                    AiProvider.PAYPERQ -> {
                        putString(Settings.PREF_VOICE_MODEL, "nova-3")
                        putString(Settings.PREF_TEXT_FIX_MODEL, "gpt-5")
                    }
                }
            }
        }
    },
    Setting(context, Settings.PREF_OPENROUTER_ZDR_ENABLED, R.string.openrouter_zdr_enabled, R.string.openrouter_zdr_enabled_summary) {
        SwitchPreference(it, Defaults.PREF_OPENROUTER_ZDR_ENABLED)
    },
    Setting(context, Settings.PREF_VOICE_MODEL, R.string.voice_model) { setting ->
        val ctx = LocalContext.current
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val items = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> listOf(
                "Voxtral Small 24B (Recommended, Cheap)" to "mistralai/voxtral-small-24b-2507",
                "Gemini 2.5 Flash Lite (Cheap)" to "google/gemini-2.5-flash-lite",
                "Gemini 2.5 Flash (Medium)" to "google/gemini-2.5-flash",
                "GPT-4o Audio Preview (Expensive)" to "openai/gpt-4o-audio-preview",
                "GPT Audio (Expensive)" to "openai/gpt-audio",
                ctx.getString(R.string.voice_custom_model) to "custom",
            )
            AiProvider.PAYPERQ -> listOf(
                "Voxtral Small 24B (Recommended, Cheap)" to "mistralai/voxtral-small-24b-2507",
                "GPT Audio Mini (Medium)" to "openai/gpt-audio-mini",
                "MiMo V2 Omni (Medium)" to "xiaomi/mimo-v2-omni",
                "GPT-4o Audio Preview (Expensive)" to "openai/gpt-4o-audio-preview",
                "GPT Audio (Expensive)" to "openai/gpt-audio",
                ctx.getString(R.string.voice_custom_model) to "custom",
            )
        }
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
        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
        R.string.voice_expected_languages,
        R.string.voice_expected_languages_summary
    ) {
        VoiceExpectedLanguagesPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_PROMPT_PRESET, R.string.voice_prompt_preset) {
        VoicePromptPresetPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_TEST_KEY, R.string.voice_validate_key) {
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
private fun VoiceApiKeyPreference(setting: Setting, provider: AiProvider) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var stored by remember { mutableStateOf(SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey())) }
    Preference(
        name = setting.title,
        onClick = {
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            showDialog = true
        },
        description = if (stored.isNotEmpty()) "••••••••" else setting.description,
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                SecretStore.setApiKey(ctx, provider.apiKeyPrefKey(), it.trim())
                stored = it.trim()
            },
            initialText = stored,
            title = { Text(setting.title) },
            singleLine = true,
            isPassword = true,
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
    var pendingPresetTextRes by rememberSaveable { mutableStateOf<Int?>(null) }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        // Keep items as primitive Ints so LazyColumn's key is Saveable — wrapping them in a
        // local data class crashes the dialog on selection.
        val labelToText = mapOf(
            R.string.voice_prompt_preset_verbatim to R.string.voice_prompt_preset_verbatim_text,
            R.string.voice_prompt_preset_clean to R.string.voice_prompt_preset_clean_text,
            R.string.voice_prompt_preset_punctuated to R.string.voice_prompt_preset_punctuated_text,
            R.string.voice_prompt_preset_translate_en to R.string.voice_prompt_preset_translate_en_text,
        )
        ListPickerDialog(
            onDismissRequest = { showDialog = false },
            items = labelToText.keys.toList(),
            onItemSelected = { labelRes ->
                val textRes = labelToText[labelRes] ?: return@ListPickerDialog
                val newText = ctx.getString(textRes)
                val current = prefs.getString(
                    Settings.PREF_VOICE_TRANSCRIPTION_PROMPT,
                    Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
                ) ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
                if (current.isBlank() || current == Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT) {
                    prefs.edit {
                        putString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, newText)
                    }
                } else {
                    pendingPresetTextRes = textRes
                }
                showDialog = false
            },
            title = { Text(ctx.getString(R.string.voice_prompt_preset)) },
            getItemName = { ctx.getString(it) },
        )
    }
    pendingPresetTextRes?.let { textRes ->
        ConfirmationDialog(
            onDismissRequest = { pendingPresetTextRes = null },
            onConfirmed = {
                prefs.edit {
                    putString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, ctx.getString(textRes))
                }
                pendingPresetTextRes = null
            },
            title = { Text(stringResource(R.string.voice_prompt_preset_overwrite_title)) },
            content = { Text(stringResource(R.string.voice_prompt_preset_overwrite_message)) },
        )
    }
}

@Composable
private fun VoiceExpectedLanguagesPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
        Defaults.PREF_VOICE_EXPECTED_LANGUAGES
    )
    val displayValue = parseExpectedLanguages(rawValue).joinToString(", ")
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
                        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
                        parseExpectedLanguages(value).joinToString(", ")
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
    // Deliberately not rememberSaveable: the probe can't survive process death, so restoring
    // busy=true would leave the UI stuck. rememberCoroutineScope() cancels on dispose, which
    // is enough to abandon the in-flight request on navigation.
    var busy by remember { mutableStateOf(false) }
    Preference(
        name = setting.title,
        description = if (busy) stringResource(R.string.voice_test_key_testing) else null,
        onClick = {
            if (busy) return@Preference
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
            val apiKey = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey())
            if (apiKey.isBlank()) {
                Toast.makeText(ctx, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
            val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
            val model = resolveVoiceModel(selectedModel, customModel)
            if (model == null) {
                Toast.makeText(ctx, R.string.voice_error_no_model, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val useZdr = provider == AiProvider.OPENROUTER &&
                prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { probeApiKey(provider, apiKey, model, useZdr) }
                val msgRes = when (result) {
                    TestResult.OK -> R.string.voice_test_key_success
                    TestResult.OK_ZDR_UNAVAILABLE -> R.string.voice_test_key_success_zdr_unavailable
                    TestResult.INVALID -> R.string.voice_test_key_invalid
                    TestResult.INVALID_MODEL -> R.string.voice_test_key_invalid_model
                    TestResult.NETWORK -> R.string.voice_test_key_network_error
                }
                Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                busy = false
            }
        }
    )
}

private enum class TestResult { OK, OK_ZDR_UNAVAILABLE, INVALID, INVALID_MODEL, NETWORK }

private fun probeApiKey(provider: AiProvider, apiKey: String, model: String, useZdr: Boolean): TestResult {
    if (provider == AiProvider.PAYPERQ) return probePayPerQApiKey(apiKey, model)
    val keyConn = (java.net.URL(OpenRouterClient.KEY_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (keyConn.responseCode) {
            200 -> probeModel(apiKey, model, useZdr)
            401, 403 -> TestResult.INVALID
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        keyConn.disconnect()
    }
}

private fun probePayPerQApiKey(apiKey: String, model: String): TestResult {
    if (model.isBlank()) return TestResult.INVALID_MODEL
    val conn = (java.net.URL(OpenRouterClient.PAYPERQ_AUDIO_MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
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

private fun probeModel(apiKey: String, model: String, useZdr: Boolean): TestResult {
    val parts = model.trim().split("/", limit = 2)
    if (parts.size != 2 || parts.any { it.isBlank() }) {
        return TestResult.INVALID_MODEL
    }
    val author = URLEncoder.encode(parts[0], StandardCharsets.UTF_8.name())
    val slug = URLEncoder.encode(parts[1], StandardCharsets.UTF_8.name())
    val conn = (java.net.URL(OpenRouterClient.modelEndpointUrl(author, slug)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (conn.responseCode) {
            200 -> if (useZdr && !probeZdrModelSupport(apiKey, model)) TestResult.OK_ZDR_UNAVAILABLE else TestResult.OK
            401, 403 -> TestResult.INVALID
            404 -> TestResult.INVALID_MODEL
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        conn.disconnect()
    }
}

private fun probeZdrModelSupport(apiKey: String, model: String): Boolean {
    val conn = (java.net.URL(OpenRouterClient.ZDR_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        if (conn.responseCode != 200) return true
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val endpoints = JSONObject(body).optJSONArray("data") ?: return true
        for (i in 0 until endpoints.length()) {
            if (endpoints.optJSONObject(i)?.optString("model_id") == model) return true
        }
        false
    } catch (_: Exception) {
        true
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
