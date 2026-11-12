// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A backup entry can be 32 MB at most, so a larger font could not be restored. */
private const val MAX_FONT_BYTES = 32L * 1024 * 1024

@Composable
fun CustomFontPreference(setting: Setting, fontFile: File, title: Int) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        scope.launch {
            // Off the main thread with a size limit (R-31). A failed read shows the error and
            // does not crash the process, which the keyboard shares (R-07).
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    FileUtils.copyContentUriToNewFile(uri, ctx, tempFile, MAX_FONT_BYTES)
                    Typeface.createFromFile(tempFile)
                    fontFile.delete()
                    tempFile.renameTo(fontFile)
                }.onFailure { tempFile.delete() }.isSuccess
            }
            if (ok) {
                Settings.clearCachedTypeface()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            } else {
                showErrorDialog = true
            }
        }
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    Preference(
        name = setting.title,
        onClick = {
            if (fontFile.exists())
                showDialog = true
            else launcher.launch(intent)
        },
    )
    if (showDialog)
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { launcher.launch(intent) },
            onNeutral = {
                showDialog = false
                fontFile.delete()
                Settings.clearCachedTypeface()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            neutralButtonText = stringResource(R.string.delete),
            confirmButtonText = stringResource(R.string.load),
            title = { Text(stringResource(title)) }
        )
    if (showErrorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { showErrorDialog = false }
}
