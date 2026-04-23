// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.dialogs.NewDictionaryDialog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val layoutIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream", "application/json"))
    .setType("*/*")

@Composable
fun filePicker(onUri: (Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        onUri(uri)
    }

@Composable
fun layoutFilePicker(
    onSuccess: (content: String, name: String?) -> Unit
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var errorDialog by remember { mutableStateOf(false) }
    val loadFilePicker = filePicker { uri ->
        val cr = ctx.getActivity()?.contentResolver ?: return@filePicker
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = cr.query(uri, null, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index < 0) null
                        else c.getString(index)
                    }
                    val content = cr.openInputStream(uri)?.use { it.reader().readText() }
                        ?: throw IllegalStateException("could not open layout file")
                    name to content
                }.getOrNull()
            }
            if (result == null) {
                errorDialog = true
                return@launch
            }
            val (name, content) = result
            errorDialog = !LayoutUtilsCustom.checkLayout(content, ctx)
            if (!errorDialog) {
                onSuccess(content, name)
            }
        }
    }
    if (errorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { errorDialog = false }
    return loadFilePicker
}

@Composable
fun dictionaryFilePicker(mainLocale: Locale?): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cachedDictionaryFile = File(ctx.cacheDir?.path + File.separator + "temp_dict")
    var done by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf(false) }
    val picker = filePicker { uri ->
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    cachedDictionaryFile.delete()
                    FileUtils.copyDictionaryContentUriToNewFile(uri, ctx, cachedDictionaryFile)
                }.isSuccess
            }
            if (copied) done = true
            else errorDialog = true
        }
    }
    if (done)
        NewDictionaryDialog(
            onDismissRequest = { done = false },
            cachedDictionaryFile,
            mainLocale
        )
    if (errorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { errorDialog = false }

    return picker
}
