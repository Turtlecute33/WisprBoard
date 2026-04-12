// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.TextInputDialog
import androidx.core.content.edit

@Composable
fun TextInputPreference(
    setting: Setting,
    default: String,
    info: String? = null,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    neutralButtonText: String? = null,
    onNeutral: (() -> Unit)? = null,
    checkTextValid: (String) -> Boolean = { true }
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    val currentValue by rememberStringPreferenceState(setting.key, default)
    val displayValue = currentValue.takeIf { it.isNotEmpty() }
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (isPassword && displayValue != null) "••••••••" else displayValue
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                prefs.edit { putString(setting.key, it) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = prefs.getString(setting.key, default) ?: "",
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            neutralButtonText = neutralButtonText,
            onNeutral = {
                onNeutral?.invoke()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            singleLine = singleLine,
            checkTextValid = checkTextValid
        )
    }
}
