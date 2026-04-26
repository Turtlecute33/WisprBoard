// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.defaultApiKey
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var aiSetupSkipped by rememberSaveable { mutableStateOf(false) }
    fun isAiProviderReady(): Boolean {
        val prefs = ctx.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        return SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).isNotBlank()
                && prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)
                && PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)
    }
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        isAiProviderReady() || aiSetupSkipped -> 4
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(ctx, imm, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                step = determineStep()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val useWideLayout = isWideScreen()
    val stepBackgroundColor = Color(ContextCompat.getColor(ctx, R.color.setup_step_background))
    val textColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_action))
    val textColorDim = textColor.copy(alpha = 0.5f)
    val titleColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_title))
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        val resource = if (step == 0) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 36.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                color = titleColor,
            )
            if (JniUtils.sHaveGestureLib)
                Text(
                    stringResource(R.string.setup_welcome_additional_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.End,
                    color = titleColor,
                    modifier = Modifier.fillMaxWidth()
                )
        }
    }
    @Composable
    fun ColumnScope.Step(step: Int, title: String, instruction: String, actionText: String, icon: Painter, action: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..4).forEach {
                Text(it.toString(), color = if (step == it) titleColor else textColorDim)
            }
        }
        Column(Modifier
            .background(color = stepBackgroundColor)
            .padding(16.dp)
        ) {
            Text(title)
            Text(instruction, style = MaterialTheme.typography.bodyLarge.merge(color = textColor))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.clickable { action() }
                .background(color = stepBackgroundColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.padding(end = 6.dp).size(32.dp), tint = textColor)
            Text(actionText, Modifier.weight(1f))
        }
    }
    @Composable fun steps() {
        if (step == 0)
            Step0 { step = 1 }
        else
            Column {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    step = determineStep()
                }
                if (step == 1) {
                    Step(
                        step,
                        stringResource(R.string.setup_step1_title, appName),
                        stringResource(R.string.setup_step1_instruction, appName),
                        stringResource(R.string.setup_step1_action),
                        painterResource(R.drawable.ic_setup_key)
                    ) {
                        val intent = Intent()
                        intent.action = AndroidSettings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                    }
                } else if (step == 2) {
                    Step(
                        step,
                        stringResource(R.string.setup_step2_title, appName),
                        stringResource(R.string.setup_step2_instruction, appName),
                        stringResource(R.string.setup_step2_action),
                        painterResource(R.drawable.ic_setup_select),
                        imm::showInputMethodPicker
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { close() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.sym_keyboard_language_switch),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_step3_action), Modifier.weight(1f))
                    }
                } else if (step == 3) {
                    AiProviderSetupStep(
                        stepBackgroundColor = stepBackgroundColor,
                        textColor = textColor,
                        titleColor = titleColor,
                        onConfigured = { step = 4 },
                        onSkip = {
                            aiSetupSkipped = true
                            step = 4
                        },
                        onOpenVoiceSettings = {
                            close()
                            SettingsDestination.navigateTo(SettingsDestination.Voice)
                        },
                    )
                } else { // step 4
                    Step(
                        step,
                        stringResource(R.string.setup_step3_title),
                        stringResource(R.string.setup_step3_instruction, appName),
                        stringResource(R.string.setup_step3_action),
                        painterResource(R.drawable.sym_keyboard_language_switch),
                        close
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.clickable { finish() }
                            .background(color = stepBackgroundColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_setup_check),
                            null,
                            Modifier.padding(end = 6.dp).size(32.dp),
                            tint = textColor
                        )
                        Text(stringResource(R.string.setup_finish_action), Modifier.weight(1f))
                    }
                }
            }
    }
    Surface {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row(Modifier.verticalScroll(rememberScrollState())) {
                        Box(Modifier.weight(0.4f)) {
                            bigText()
                        }
                        Box(Modifier.weight(0.6f)) {
                            steps()
                        }
                    }
                else
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        bigText()
                        steps()
                    }
            }
        }
    }
}

@Composable
private fun AiProviderSetupStep(
    stepBackgroundColor: Color,
    textColor: Color,
    titleColor: Color,
    onConfigured: () -> Unit,
    onSkip: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var providerPref by rememberSaveable {
        mutableStateOf(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER) ?: Defaults.PREF_AI_PROVIDER)
    }
    val selectedProvider = AiProvider.fromPref(providerPref)
    var apiKeySet by remember {
        mutableStateOf(SecretStore.getApiKey(ctx, selectedProvider.apiKeyPrefKey(), selectedProvider.defaultApiKey()).isNotBlank())
    }
    var micGranted by remember {
        mutableStateOf(PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO))
    }
    var voiceEnabled by remember {
        mutableStateOf(prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED))
    }
    val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
    val permissionDeniedMessage = stringResource(R.string.voice_error_no_permission)
    fun providerName(provider: AiProvider): String = when (provider) {
        AiProvider.OPENROUTER -> ctx.getString(R.string.ai_provider_openrouter)
        AiProvider.PAYPERQ -> ctx.getString(R.string.ai_provider_payperq)
    }
    fun refreshApiKeyState(provider: AiProvider = selectedProvider) {
        apiKeySet = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).isNotBlank()
    }
    fun selectProvider(provider: AiProvider) {
        providerPref = provider.prefValue
        prefs.edit {
            putString(Settings.PREF_AI_PROVIDER, provider.prefValue)
            when (provider) {
                AiProvider.OPENROUTER -> {
                    putString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
                    putString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
                }
                AiProvider.PAYPERQ -> {
                    putString(Settings.PREF_VOICE_MODEL, "nova-3")
                    putString(Settings.PREF_TEXT_FIX_MODEL, "private/gemma4-31b")
                }
            }
        }
        refreshApiKeyState(provider)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            prefs.edit { putBoolean(Settings.PREF_VOICE_INPUT_ENABLED, true) }
            voiceEnabled = true
            if (apiKeySet) onConfigured()
        } else {
            Toast.makeText(ctx, permissionDeniedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    if (showApiKeyDialog) {
        TextInputDialog(
            onDismissRequest = { showApiKeyDialog = false },
            onConfirmed = {
                val key = it.trim()
                SecretStore.setApiKey(ctx, selectedProvider.apiKeyPrefKey(), key)
                apiKeySet = key.isNotBlank()
                showApiKeyDialog = false
                if (key.isNotBlank() && micGranted && voiceEnabled) onConfigured()
            },
            initialText = SecretStore.getApiKey(ctx, selectedProvider.apiKeyPrefKey(), selectedProvider.defaultApiKey()),
            title = {
                Text(
                    if (selectedProvider == AiProvider.PAYPERQ) {
                        stringResource(R.string.payperq_api_key)
                    } else {
                        stringResource(R.string.openrouter_api_key)
                    }
                )
            },
            singleLine = true,
            isPassword = true,
        )
    }
    if (showProviderDialog) {
        ListPickerDialog(
            onDismissRequest = { showProviderDialog = false },
            items = listOf(AiProvider.OPENROUTER, AiProvider.PAYPERQ),
            onItemSelected = {
                selectProvider(it)
                showProviderDialog = false
            },
            selectedItem = selectedProvider,
            title = { Text(stringResource(R.string.ai_provider)) },
            getItemName = { providerName(it) },
        )
    }
    if (showMicRationale) {
        ConfirmationDialog(
            onDismissRequest = { showMicRationale = false },
            onConfirmed = {
                showMicRationale = false
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            title = { Text(stringResource(R.string.voice_mic_rationale_title)) },
            content = { Text(stringResource(R.string.voice_mic_rationale_message)) },
            confirmButtonText = stringResource(R.string.voice_mic_rationale_confirm),
        )
    }

    @Composable
    fun ActionRow(actionText: String, icon: Painter, action: () -> Unit) {
        Row(
            Modifier.clickable { action() }
                .background(color = stepBackgroundColor)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.padding(end = 6.dp).size(32.dp), tint = textColor)
            Text(actionText, Modifier.weight(1f))
        }
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..4).forEach {
                Text(it.toString(), color = if (it == 3) titleColor else textColor.copy(alpha = 0.5f))
            }
        }
        Column(
            Modifier
                .background(color = stepBackgroundColor)
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.setup_ai_provider_title))
            Text(
                stringResource(R.string.setup_ai_provider_instruction),
                style = MaterialTheme.typography.bodyLarge.merge(color = textColor)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    R.string.setup_ai_provider_status,
                    providerName(selectedProvider),
                    if (apiKeySet) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing),
                    if (micGranted) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing),
                    if (voiceEnabled) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing),
                ),
                style = MaterialTheme.typography.bodyMedium.merge(color = textColor)
            )
        }
        Spacer(Modifier.height(4.dp))
        ActionRow(
            stringResource(R.string.setup_ai_provider_select, providerName(selectedProvider)),
            painterResource(R.drawable.ic_settings_preferences)
        ) {
            showProviderDialog = true
        }
        Spacer(Modifier.height(4.dp))
        ActionRow(
            if (apiKeySet) {
                stringResource(R.string.setup_ai_provider_update_key, providerName(selectedProvider))
            } else {
                stringResource(R.string.setup_ai_provider_add_key, providerName(selectedProvider))
            },
            painterResource(R.drawable.ic_settings_preferences)
        ) {
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
            } else {
                showApiKeyDialog = true
            }
        }
        Spacer(Modifier.height(4.dp))
        ActionRow(
            if (micGranted && voiceEnabled) stringResource(R.string.setup_ai_provider_voice_ready) else stringResource(R.string.setup_ai_provider_enable_voice),
            painterResource(R.drawable.sym_keyboard_voice_rounded)
        ) {
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                return@ActionRow
            }
            if (micGranted) {
                prefs.edit { putBoolean(Settings.PREF_VOICE_INPUT_ENABLED, true) }
                voiceEnabled = true
                if (apiKeySet) onConfigured()
            } else {
                showMicRationale = true
            }
        }
        Spacer(Modifier.height(4.dp))
        ActionRow(
            stringResource(R.string.setup_ai_provider_voice_settings),
            painterResource(R.drawable.ic_settings_default),
            onOpenVoiceSettings
        )
        Spacer(Modifier.height(4.dp))
        ActionRow(
            stringResource(R.string.setup_ai_provider_skip),
            painterResource(R.drawable.ic_setup_check),
            onSkip
        )
    }
}

@Composable
fun Step0(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.setup_welcome_image), null)
        Row(Modifier.clickable { onClick() }
            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
            //.background(color = MaterialTheme.colorScheme.primary)
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.setup_start_action),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
