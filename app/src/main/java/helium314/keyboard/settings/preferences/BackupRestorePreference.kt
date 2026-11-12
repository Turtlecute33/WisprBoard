// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.personalization.PersonalizationHelper
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.readTextCapped
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.resetUnsupportedModels
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.core.content.edit
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.transferOldPinnedClips

@Composable
fun BackupRestorePreference(setting: Setting) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showKeysDialog by rememberSaveable { mutableStateOf(false) }
    val includeApiKeys = rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    var error: String? by rememberSaveable { mutableStateOf(null) }
    // A read backup waits here until the user confirms it (SEC-002). Its files are not saveable,
    // so a recreated screen deletes them and the user picks the file again.
    var stagedRestore: StagedBackup? by remember { mutableStateOf(null) }
    // A backup staged after the screen is gone is deleted at once.
    val disposed = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) { onDispose { disposed[0] = true; stagedRestore?.let(::discardStagedBackup) } }
    // The text of the modal dialog while background work runs, or null (R-30). Not saveable: the
    // work does not continue in a recreated screen.
    val busy = remember { mutableStateOf<Int?>(null) }
    val backupLauncher = backupLauncher(includeApiKeys, busy) { error = it }
    val restoreLauncher = restoreLauncher(busy, onStaged = { if (disposed[0]) discardStagedBackup(it) else { stagedRestore = it } }) { error = it }
    fun launchBackup(withKeys: Boolean) {
        includeApiKeys.value = withKeys
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(
                Intent.EXTRA_TITLE,
                ctx.getString(R.string.english_ime_name)
                    .replace(" ", "_") + "_backup_$currentDate.zip"
            )
            .setType("application/zip")
        backupLauncher.launch(intent)
    }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            content = { Text(stringResource(R.string.backup_restore_message)) },
            confirmButtonText = stringResource(R.string.button_backup),
            neutralButtonText = stringResource(R.string.button_restore),
            onNeutral = {
                showDialog = false
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                restoreLauncher.launch(intent)
            },
            onConfirmed = { showKeysDialog = true }
        )
    }
    if (showKeysDialog) {
        ConfirmationDialog(
            onDismissRequest = { showKeysDialog = false },
            title = { Text(stringResource(R.string.backup_include_keys_title)) },
            content = { Text(stringResource(R.string.backup_include_keys_message)) },
            // The safe choice is the primary button; plain-text keys need a deliberate tap.
            confirmButtonText = stringResource(R.string.button_exclude_keys),
            neutralButtonText = stringResource(R.string.button_include_keys),
            onNeutral = {
                showKeysDialog = false
                launchBackup(withKeys = true)
            },
            onConfirmed = { launchBackup(withKeys = false) }
        )
    }
    stagedRestore?.let { staged ->
        RestoreConfirmationDialog(
            staged = staged,
            // The dialog also calls this after a confirm; the restore then owns the staged files.
            onDismissRequest = {
                if (stagedRestore === staged) {
                    stagedRestore = null
                    discardStagedBackup(staged)
                }
            },
            onRestore = { replaceApiKeys ->
                stagedRestore = null
                applyRestore(ctx, staged, replaceApiKeys, busy) { error = it }
            },
        )
    }
    busy.value?.let { BusyDialog(stringResource(it)) }
    if (error != null) {
        InfoDialog(
            if (error!!.startsWith("b"))
                stringResource(R.string.backup_error, error!!.drop(1))
            else stringResource(R.string.restore_error, error!!.drop(1))
        ) { error = null }
    }
}

/** A modal dialog with no buttons. Back and a tap outside do not close it (R-30). */
@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(text, Modifier.padding(start = 16.dp))
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    )
}

/**
 * Runs [work] on the background executor, and shows [text] in a modal dialog until it ends (R-30).
 * The dialog stops a scroll, so this row stays composed and can show the result.
 */
private fun runBusy(busy: MutableState<Int?>, @StringRes text: Int, work: () -> Unit) {
    busy.value = text
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
        // Posted after the posts of work, so the result shows when the dialog closes.
        try { work() } finally { postToMain { busy.value = null } }
    }
}

@Composable
private fun backupLauncher(
    includeApiKeys: State<Boolean>,
    busy: MutableState<Int?>,
    onError: (String) -> Unit,
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        val withKeys = includeApiKeys.value
        // read on the main thread, like all other users of ClipboardDao
        val pinnedClips = ClipboardDao.getInstance(ctx)?.getAll().orEmpty()
            .filter { it.isPinned }
            .map { BackupClip(it.timeStamp, it.text) }
        runBusy(busy, R.string.backup_in_progress) {
            try {
                val backupPayload = collectBackupFiles(ctx)
                val apiKeys = if (withKeys) {
                    API_KEY_PREFS.associateWith { SecretStore.getApiKey(ctx, it, "") }.filterValues { it.isNotBlank() }
                } else emptyMap()
                val outputStream = requireNotNull(ctx.getActivity()?.contentResolver?.openOutputStream(uri)) {
                    "Unable to open backup destination"
                }
                outputStream.use { os ->
                    // write files to zip
                    val zipStream = ZipOutputStream(os)
                    backupPayload.files.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(backupPayload.filesPrefix, "")))
                        fileStream.copyTo(zipStream, 8 * 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    backupPayload.protectedFiles.forEach {
                        val fileStream = FileInputStream(it).buffered()
                        zipStream.putNextEntry(ZipEntry(it.path.replace(backupPayload.protectedFilesPrefix, "unprotected/")))
                        fileStream.copyTo(zipStream, 8 * 1024)
                        fileStream.close()
                        zipStream.closeEntry()
                    }
                    zipStream.putNextEntry(ZipEntry(PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.prefs().all, zipStream)
                    zipStream.closeEntry()
                    zipStream.putNextEntry(ZipEntry(PROTECTED_PREFS_FILE_NAME))
                    settingsToJsonStream(ctx.protectedPrefs().all, zipStream)
                    zipStream.closeEntry()
                    if (pinnedClips.isNotEmpty()) {
                        zipStream.putNextEntry(ZipEntry(PINNED_CLIPS_FILE_NAME))
                        zipStream.write(Json.encodeToString(pinnedClips).toByteArray())
                        zipStream.closeEntry()
                    }
                    if (apiKeys.isNotEmpty()) {
                        zipStream.putNextEntry(ZipEntry(API_KEYS_FILE_NAME))
                        zipStream.write(Json.encodeToString(apiKeys).toByteArray())
                        zipStream.closeEntry()
                    }
                    zipStream.close()
                }
                postToMain { Toast.makeText(ctx, ctx.getString(R.string.backup_saved), Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                postToMain { onError("b" + (t.message ?: "Unknown error")) }
                Log.w("AdvancedScreen", "error during backup", t)
            }
        }
    }
}

/** Reads the picked file into a staged backup. Nothing changes until the user confirms it. */
@Composable
private fun restoreLauncher(
    busy: MutableState<Int?>,
    onStaged: (StagedBackup) -> Unit,
    onError: (String) -> Unit,
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val ctx = LocalContext.current
    return filePicker { uri ->
        runBusy(busy, R.string.restore_in_progress) {
            try {
                val restoreInputStream = requireNotNull(ctx.getActivity()?.contentResolver?.openInputStream(uri)) {
                    "Unable to open backup archive"
                }
                val staged = restoreInputStream.use { inputStream -> stageBackup(ctx, inputStream) }
                postToMain { onStaged(staged) }
            } catch (t: Throwable) {
                postToMain { onError("r" + (t.message ?: "Unknown error")) }
                Log.w("AdvancedScreen", "error during restore", t)
            }
        }
    }
}

/**
 * Lists what the staged backup holds before anything changes (SEC-002). The API keys in the file
 * replace the current keys only if the user selects the option, which is off by default.
 */
@Composable
private fun RestoreConfirmationDialog(
    staged: StagedBackup,
    onDismissRequest: () -> Unit,
    onRestore: (replaceApiKeys: Boolean) -> Unit,
) {
    var replaceApiKeys by rememberSaveable { mutableStateOf(false) }
    val items = remember(staged) {
        buildList {
            if (staged.prefs != null || staged.protectedPrefs != null || staged.hasOtherFiles) add(R.string.restore_item_settings)
            if (listOfNotNull(staged.prefs, staged.protectedPrefs).any { it.hasAiSettings() }) add(R.string.restore_item_ai_settings)
            if (staged.hasLearnedWords) add(R.string.restore_item_learned_words)
            if (staged.pinnedClips.isNotEmpty()) add(R.string.restore_item_pinned_clips)
            if (staged.apiKeys.isNotEmpty()) add(R.string.restore_item_api_keys)
        }
    }
    ConfirmationDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { onRestore(replaceApiKeys) },
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        content = {
            Column {
                Text(stringResource(R.string.restore_confirm_message))
                items.forEach { Text("\u2022 " + stringResource(it), Modifier.padding(start = 8.dp, top = 4.dp)) }
                if (staged.apiKeys.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
                            .toggleable(value = replaceApiKeys, role = Role.Checkbox) { replaceApiKeys = it }
                    ) {
                        Checkbox(checked = replaceApiKeys, onCheckedChange = null)
                        Text(stringResource(R.string.restore_replace_api_keys), Modifier.padding(start = 8.dp))
                    }
                }
                Text(stringResource(R.string.restore_keeps_zdr), Modifier.padding(top = 8.dp))
            }
        },
        confirmButtonText = stringResource(R.string.button_restore),
    )
}

private fun discardStagedBackup(staged: StagedBackup) {
    ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { staged.root.deleteRecursively() }
}

/** Writes a confirmed staged backup, then deletes the staged files. */
private fun applyRestore(
    ctx: android.content.Context,
    staged: StagedBackup,
    replaceApiKeys: Boolean,
    busy: MutableState<Int?>,
    onError: (String) -> Unit,
) {
    runBusy(busy, R.string.restore_in_progress) {
        var listenerStopped = false
        try {
            LayoutUtilsCustom.onLayoutFileChanged()
            Settings.getInstance().stopListener()
            listenerStopped = true
            applyStagedBackup(ctx, staged, replaceApiKeys)
            val pinnedClips = staged.pinnedClips
            postToMain {
                // on the main thread, like all other users of ClipboardDao
                try {
                    restorePinnedClips(ctx, pinnedClips)
                    Toast.makeText(ctx, ctx.getString(R.string.backup_restored), Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    onError("r" + (t.message ?: "Unknown error"))
                    Log.w("AdvancedScreen", "error restoring pinned clips", t)
                }
                refreshAfterRestore(ctx)
            }
        } catch (t: Throwable) {
            postToMain {
                if (listenerStopped) {
                    Settings.getInstance().startListener()
                }
                onError("r" + (t.message ?: "Unknown error"))
            }
            Log.w("AdvancedScreen", "error during restore", t)
        } finally {
            staged.root.deleteRecursively()
        }
    }
}

private val API_KEY_PREFS = AiProvider.entries.map { it.apiKeyPrefKey() }.toSet()

// Keys that must never be written to the preference files of a backup archive. Provider API
// keys live in an encrypted shared-prefs bucket and are scrubbed from plain prefs by SecretStore,
// but we filter here as defense-in-depth against pre-migration or future pref leaks. API keys go
// to the backup only in API_KEYS_FILE_NAME, and only if the user says so.
private val SENSITIVE_BACKUP_KEYS = API_KEY_PREFS + LEGACY_PINNED_CLIPS_KEY

@Suppress("UNCHECKED_CAST") // it is checked... but whatever (except string set, because can't check for that))
internal fun settingsToJsonString(settings: Map<String?, Any?>): String {
    val filtered = settings.filterKeys { it !in SENSITIVE_BACKUP_KEYS }
    val booleans = filtered.filter { it.key is String && it.value is Boolean } as Map<String, Boolean>
    val ints = filtered.filter { it.key is String && it.value is Int } as Map<String, Int>
    val longs = filtered.filter { it.key is String && it.value is Long } as Map<String, Long>
    val floats = filtered.filter { it.key is String && it.value is Float } as Map<String, Float>
    val strings = filtered.filter { it.key is String && it.value is String } as Map<String, String>
    val stringSets = filtered.filter { it.key is String && it.value is Set<*> } as Map<String, Set<String>>
    return buildString {
        append("boolean settings\n")
        append(Json.encodeToString(booleans))
        append("\nint settings\n")
        append(Json.encodeToString(ints))
        append("\nlong settings\n")
        append(Json.encodeToString(longs))
        append("\nfloat settings\n")
        append(Json.encodeToString(floats))
        append("\nstring settings\n")
        append(Json.encodeToString(strings))
        append("\nstring set settings\n")
        append(Json.encodeToString(stringSets))
    }
}

private fun settingsToJsonStream(settings: Map<String?, Any?>, out: OutputStream) {
    out.write(settingsToJsonString(settings).toByteArray())
}

internal fun parseSettingsSnapshot(list: List<String>): SettingsSnapshot {
    val i = list.iterator()
    try {
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        val strings = mutableMapOf<String, String>()
        val stringSets = mutableMapOf<String, Set<String>>()
        while (i.hasNext()) {
            when (i.next()) {
                "boolean settings" -> booleans.putAll(Json.decodeFromString<Map<String, Boolean>>(i.next()))
                "int settings" -> ints.putAll(Json.decodeFromString<Map<String, Int>>(i.next()))
                "long settings" -> longs.putAll(Json.decodeFromString<Map<String, Long>>(i.next()))
                "float settings" -> floats.putAll(Json.decodeFromString<Map<String, Float>>(i.next()))
                "string settings" -> strings.putAll(Json.decodeFromString<Map<String, String>>(i.next()))
                "string set settings" -> stringSets.putAll(Json.decodeFromString<Map<String, Set<String>>>(i.next()))
            }
        }
        return SettingsSnapshot(booleans, ints, longs, floats, strings, stringSets)
            .withoutSensitiveKeys()
    } catch (_: Exception) {
        // No cause: the serialization message quotes the input, and the log gets it (R-29).
        throw IllegalArgumentException("Malformed settings backup")
    }
}

internal data class SettingsSnapshot(
    val booleans: Map<String, Boolean> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
)

private fun SharedPreferences.Editor.putAll(snapshot: SettingsSnapshot) {
    snapshot.withoutSensitiveKeys().run {
        booleans.forEach { putBoolean(it.key, it.value) }
        ints.forEach { putInt(it.key, it.value) }
        longs.forEach { putLong(it.key, it.value) }
        floats.forEach { putFloat(it.key, it.value) }
        strings.forEach { putString(it.key, it.value) }
        stringSets.forEach { putStringSet(it.key, it.value) }
    }
}

private fun SettingsSnapshot.withoutSensitiveKeys() = SettingsSnapshot(
    booleans = booleans.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
    ints = ints.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
    longs = longs.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
    floats = floats.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
    strings = strings.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
    stringSets = stringSets.filterKeys { it !in SENSITIVE_BACKUP_KEYS },
)

@Serializable
internal data class BackupClip(val timestamp: Long, val text: String)

// Parse errors are rethrown without cause: serialization messages quote the input, which here
// is clip text or API keys, and the message ends up in the error dialog and the log.
internal fun parsePinnedClips(json: String): List<BackupClip> {
    val clips = try {
        Json.decodeFromString<List<BackupClip>>(json)
    } catch (_: Exception) {
        throw IllegalArgumentException("Malformed pinned clips in backup")
    }
    require(clips.size <= MAX_RESTORE_PINNED_CLIPS) { "Too many pinned clips in backup" }
    return clips
}

/** A value that cannot go into an HTTP header is dropped (R-01); the rest of the restore continues. */
internal fun parseApiKeys(json: String): Map<String, String> = try {
    Json.decodeFromString<Map<String, String>>(json)
        .mapValues { it.value.trim() }
        .filter { it.key in API_KEY_PREFS && SecretStore.isValidApiKey(it.value) }
} catch (_: Exception) {
    throw IllegalArgumentException("Malformed API keys in backup")
}

/** The keys in a backup replace the current keys only if the user selected that option (SEC-002). */
internal fun apiKeysToRestore(backupKeys: Map<String, String>, replaceApiKeys: Boolean): Map<String, String> =
    if (replaceApiKeys) backupKeys else emptyMap()

/** Settings that a restore does not change: a file from another person cannot turn off ZDR (SEC-002) or change where the audio and text go (D-2). */
private val KEPT_BOOLEANS = setOf(Settings.PREF_OPENROUTER_ZDR_ENABLED)
private val KEPT_STRINGS = setOf(Settings.PREF_AI_PROVIDER, Settings.PREF_VOICE_SPEECH_ENGINE)

/**
 * Keeps the values of the kept settings in [current] (the prefs file before the restore). A kept
 * setting that is not in [current] stays not set, so the default applies. A value of the wrong
 * type counts as not set, and the restore removes each kept key from all other types.
 */
internal fun SettingsSnapshot.keepingCurrent(current: Map<String, *>): SettingsSnapshot {
    val keys = KEPT_BOOLEANS + KEPT_STRINGS
    return SettingsSnapshot(
        booleans = booleans - keys + KEPT_BOOLEANS.mapNotNull { k -> (current[k] as? Boolean)?.let { k to it } },
        ints = ints - keys,
        longs = longs - keys,
        floats = floats - keys,
        strings = strings - keys + KEPT_STRINGS.mapNotNull { k -> (current[k] as? String)?.let { k to it } },
        stringSets = stringSets - keys,
    )
}

private val AI_SETTING_PREFIXES = listOf("ai_", "voice_", "text_fix_", "translate_", "openrouter_")

/** True if the snapshot has AI provider, model or prompt settings; the restore dialog lists them. */
internal fun SettingsSnapshot.hasAiSettings(): Boolean =
    listOf(booleans.keys, ints.keys, longs.keys, floats.keys, strings.keys, stringSets.keys)
        .any { keys -> keys.any { key -> AI_SETTING_PREFIXES.any { key.startsWith(it) } } }

private fun isLearnedWordsFile(path: String) = path.startsWith("UserHistoryDictionary")

private fun restorePinnedClips(ctx: android.content.Context, clips: List<BackupClip>) {
    if (clips.isEmpty()) return
    val dao = ClipboardDao.getInstance(ctx) ?: error("Clipboard unavailable")
    clips.forEach { dao.addPinnedClip(it.timestamp, it.text) }
}

private data class BackupFiles(
    val files: List<File>,
    val protectedFiles: List<File>,
    val filesPrefix: String,
    val protectedFilesPrefix: String,
)

private data class StagedBackup(
    val root: File,
    val filesDir: File,
    val protectedFilesDir: File,
    val prefs: SettingsSnapshot?,
    val protectedPrefs: SettingsSnapshot?,
    val pinnedClips: List<BackupClip>,
    val apiKeys: Map<String, String>,
    val hasLearnedWords: Boolean,
    val hasOtherFiles: Boolean,
)

private fun collectBackupFiles(ctx: android.content.Context): BackupFiles {
    val filesDir = ctx.filesDir ?: error("Files directory unavailable")
    val filesPrefix = filesDir.path + File.separator
    val files = filesDir.walk().filter { file ->
        val path = file.path.removePrefix(filesPrefix)
        file.isFile && isAllowedBackupFile(path)
    }.toList()

    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val protectedFilesPrefix = protectedFilesDir.path + File.separator
    val protectedFiles = protectedFilesDir.walk().filter { file ->
        val path = file.path.removePrefix(protectedFilesPrefix)
        file.isFile && isAllowedBackupFile(path)
    }.toList()
    return BackupFiles(files, protectedFiles, filesPrefix, protectedFilesPrefix)
}

// Once for each process: delete stage and rollback folders that a killed process left.
private val staleRestoreSwept = java.util.concurrent.atomic.AtomicBoolean(false)

private fun stageBackup(ctx: android.content.Context, inputStream: InputStream): StagedBackup {
    if (staleRestoreSwept.compareAndSet(false, true))
        ctx.cacheDir.listFiles { f -> f.name.startsWith("backup_restore_") }?.forEach { it.deleteRecursively() }
    val stageRoot = File(ctx.cacheDir, "backup_restore_stage_${System.currentTimeMillis()}")
    val stageFilesDir = File(stageRoot, "files")
    val stageProtectedFilesDir = File(stageRoot, "device_protected")
    var prefsSnapshot: SettingsSnapshot? = null
    var protectedPrefsSnapshot: SettingsSnapshot? = null
    var pinnedClips = emptyList<BackupClip>()
    var apiKeys = emptyMap<String, String>()
    var hasLearnedWords = false
    var hasOtherFiles = false
    var restoredFileBytes = 0L
    try {
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val entryName = normalizeBackupEntryName(entry.name)
                when {
                    entry.isDirectory -> Unit
                    entryName.startsWith("unprotected/") -> {
                        val adjustedName = entryName.removePrefix("unprotected/")
                        if (isAllowedBackupFile(adjustedName)) {
                            restoredFileBytes += copyRestoreEntryToNewFile(zip, File(stageProtectedFilesDir, adjustedName))
                            require(restoredFileBytes <= MAX_RESTORE_TOTAL_BYTES) { "Backup file entries exceed total size limit" }
                            if (isLearnedWordsFile(adjustedName)) hasLearnedWords = true else hasOtherFiles = true
                        }
                    }
                    isAllowedBackupFile(entryName) -> {
                        restoredFileBytes += copyRestoreEntryToNewFile(zip, File(stageFilesDir, entryName))
                        require(restoredFileBytes <= MAX_RESTORE_TOTAL_BYTES) { "Backup file entries exceed total size limit" }
                        if (isLearnedWordsFile(entryName)) hasLearnedWords = true else hasOtherFiles = true
                    }
                    entryName == PREFS_FILE_NAME -> {
                        prefsSnapshot = parseSettingsSnapshot(readTextCapped(zip, MAX_SNAPSHOT_BYTES).split("\n"))
                    }
                    entryName == PROTECTED_PREFS_FILE_NAME -> {
                        protectedPrefsSnapshot = parseSettingsSnapshot(readTextCapped(zip, MAX_SNAPSHOT_BYTES).split("\n"))
                    }
                    entryName == PINNED_CLIPS_FILE_NAME -> {
                        pinnedClips = parsePinnedClips(readTextCapped(zip, MAX_RESTORE_ENTRY_BYTES.toInt()))
                    }
                    entryName == API_KEYS_FILE_NAME -> {
                        apiKeys = parseApiKeys(readTextCapped(zip, MAX_SNAPSHOT_BYTES))
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (t: Throwable) {
        stageRoot.deleteRecursively()
        throw t
    }
    return StagedBackup(
        root = stageRoot,
        filesDir = stageFilesDir,
        protectedFilesDir = stageProtectedFilesDir,
        prefs = prefsSnapshot,
        protectedPrefs = protectedPrefsSnapshot,
        pinnedClips = pinnedClips,
        apiKeys = apiKeys,
        hasLearnedWords = hasLearnedWords,
        hasOtherFiles = hasOtherFiles,
    )
}

private fun applyStagedBackup(ctx: android.content.Context, backup: StagedBackup, replaceApiKeys: Boolean) {
    val filesDir = ctx.filesDir ?: error("Files directory unavailable")
    val protectedFilesDir = DeviceProtectedUtils.getFilesDir(ctx)
    val rollbackRoot = File(ctx.cacheDir, "backup_restore_rollback_${System.currentTimeMillis()}")
    val rollbackFilesDir = File(rollbackRoot, "files")
    val rollbackProtectedFilesDir = File(rollbackRoot, "device_protected")
    filesDir.mkdirs()
    protectedFilesDir.mkdirs()
    // Write the keys first: if this fails, no file or pref has changed.
    // Without secure storage (API < 23) the app can't store keys at all, so skip them.
    val apiKeys = apiKeysToRestore(backup.apiKeys, replaceApiKeys)
        .takeIf { it.isNotEmpty() && SecretStore.isSecureStorageAvailable(ctx) }.orEmpty()
    // Keep the old keys, so that a failed restore writes them back.
    val oldKeys = apiKeys.keys.associateWith { SecretStore.getApiKey(ctx, it, "") }
    fun restoreOldKeys() = oldKeys.forEach { (k, v) -> runCatching { SecretStore.setApiKey(ctx, k, v) } }
    try {
        apiKeys.forEach { (prefKey, value) -> SecretStore.setApiKey(ctx, prefKey, value) }
    } catch (t: Throwable) {
        restoreOldKeys()
        throw t
    }
    // Roll back only from a complete snapshot. A failed snapshot has changed no live file.
    try {
        snapshotAllowedFiles(filesDir, rollbackFilesDir)
        snapshotAllowedFiles(protectedFilesDir, rollbackProtectedFilesDir)
    } catch (t: Throwable) {
        restoreOldKeys()
        rollbackRoot.deleteRecursively()
        throw t
    }
    try {
        // A full backup replaces the files too, so a file that the backup does not have goes
        // (R-14). The rollback snapshot above puts the files back if a later step fails. A zip
        // without the settings file is not a full backup (for example a wrong file): keep the files.
        if (backup.prefs != null) {
            deleteAllowedBackupFiles(filesDir)
            deleteAllowedBackupFiles(protectedFilesDir)
        }
        copyDirectoryContents(backup.filesDir, filesDir)
        copyDirectoryContents(backup.protectedFilesDir, protectedFilesDir)
        // loaded learned words still use the old files, make them load the restored ones
        PersonalizationHelper.closeAllUserHistoryDictionaries()
        // Read both files before either is cleared: the two can be the same file.
        val current = ctx.prefs().all
        val protectedCurrent = ctx.protectedPrefs().all
        backup.prefs?.let { snapshot ->
            ctx.prefs().edit {
                clear()
                putAll(snapshot.keepingCurrent(current))
            }
        }
        backup.protectedPrefs?.let { snapshot ->
            ctx.protectedPrefs().edit {
                clear()
                putAll(snapshot.keepingCurrent(protectedCurrent))
            }
        }
        // The provider stays the same, so a restored model of the other provider goes back to the
        // default (D-2). Never throw here: the rollback does not cover the prefs that are now written.
        runCatching {
            val prefs = ctx.prefs()
            AiProvider.fromPref(prefs.all[Settings.PREF_AI_PROVIDER] as? String).resetUnsupportedModels(prefs)
        }
        // Old or hand-crafted backups may contain provider keys in either plaintext prefs file.
        // Scrub them even when an encrypted key already exists, since SecretStore then has no
        // reason to run its one-time plaintext migration path.
        ctx.prefs().edit(commit = true) {
            SENSITIVE_BACKUP_KEYS.forEach { remove(it) }
        }
        ctx.protectedPrefs().edit(commit = true) {
            SENSITIVE_BACKUP_KEYS.forEach { remove(it) }
        }
    } catch (t: Throwable) {
        restoreOldKeys()
        restoreAllowedFiles(rollbackFilesDir, filesDir)
        restoreAllowedFiles(rollbackProtectedFilesDir, protectedFilesDir)
        throw t
    } finally {
        rollbackRoot.deleteRecursively()
    }
}

private fun snapshotAllowedFiles(source: File, target: File) {
    if (!source.exists()) return
    source.walkTopDown()
        .filter { it.isFile && isAllowedBackupFile(it.relativeTo(source).path) }
        .forEach { file ->
            file.inputStream().buffered().use { input ->
                FileUtils.copyStreamToNewFile(input, File(target, file.relativeTo(source).path))
            }
        }
}

private fun restoreAllowedFiles(snapshot: File, target: File) {
    deleteAllowedBackupFiles(target)
    copyDirectoryContents(snapshot, target)
}

private fun deleteAllowedBackupFiles(root: File) {
    if (!root.exists()) return
    root.walkBottomUp()
        .filter { it.isFile && isAllowedBackupFile(it.relativeTo(root).path) }
        .forEach { it.delete() }
}

private fun copyDirectoryContents(source: File, target: File) {
    if (!source.exists()) return
    source.walkTopDown().forEach { file ->
        if (file == source) return@forEach
        val relativePath = file.relativeTo(source).path
        val targetFile = File(target, relativePath)
        if (file.isDirectory) {
            if (targetFile.isFile) {
                targetFile.delete()
            }
            targetFile.mkdirs()
        } else {
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            }
            // Write a temp file and rename it over the target. Native code memory-maps loaded
            // learned-words files, and truncating a mapped file in place crashes the keyboard.
            val tempFile = File(targetFile.path + ".restore_tmp")
            file.inputStream().buffered().use { input ->
                FileUtils.copyStreamToNewFile(input, tempFile)
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                throw java.io.IOException("Could not write ${targetFile.name}")
            }
        }
    }
}

internal fun normalizeBackupEntryName(name: String): String {
    val normalized = name.replace('\\', '/').trimStart('/')
    require(normalized.split('/').none { it == ".." }) { "Unsafe backup entry: $name" }
    return normalized
}

internal fun copyRestoreEntryToNewFile(input: InputStream, target: File): Long {
    val parent = target.parentFile ?: throw IllegalArgumentException("Restore target has no parent")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IllegalArgumentException("Could not create restore target folder")
    }
    try {
        java.io.FileOutputStream(target).use { output ->
            return copyRestoreEntry(input, output)
        }
    } catch (t: Throwable) {
        target.delete()
        throw t
    }
}

private fun copyRestoreEntry(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > MAX_RESTORE_ENTRY_BYTES) {
            throw IllegalArgumentException("Backup file entry exceeds size limit")
        }
        output.write(buffer, 0, read)
    }
    output.flush()
    return total
}

// Real preference snapshots are kilobytes; this cap is ~3 orders of magnitude of headroom and
// guards against a malformed archive that would otherwise OOM on the IO thread.
private const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
private const val MAX_RESTORE_ENTRY_BYTES = 32L * 1024L * 1024L
private const val MAX_RESTORE_TOTAL_BYTES = 64L * 1024L * 1024L
/** Each clip is added on the main thread (R-32). */
internal const val MAX_RESTORE_PINNED_CLIPS = 1000

private fun refreshAfterRestore(ctx: android.content.Context) {
    checkVersionUpgrade(ctx)
    transferOldPinnedClips(ctx)
    Settings.getInstance().startListener()
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
        .setPackage(ctx.packageName)
    ctx.getActivity()?.sendBroadcast(newDictBroadcast)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    SupportedEmojis.load(ctx)
    // The restore can replace or remove the image and font files (R-14).
    Settings.clearCachedBackgroundImages()
    Settings.clearCachedTypeface()
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
}

private fun postToMain(action: () -> Unit) {
    Handler(Looper.getMainLooper()).post(action)
}

private const val PREFS_FILE_NAME = "preferences.json"
private const val PROTECTED_PREFS_FILE_NAME = "protected_preferences.json"
private const val PINNED_CLIPS_FILE_NAME = "pinned_clips.json"
private const val API_KEYS_FILE_NAME = "api_keys.json"
private const val LEGACY_PINNED_CLIPS_KEY = "pinned_clips"

internal val backupFilePatterns by lazy { listOf(
    "blacklists${File.separator}.*\\.txt".toRegex(),
    "layouts${File.separator}.*${LayoutUtilsCustom.CUSTOM_LAYOUT_PREFIX}+\\..{0,4}".toRegex(), // can't expect a period at the end, as this would break restoring older backups
    "UserHistoryDictionary.*${File.separator}UserHistoryDictionary.*\\.(body|header)".toRegex(),
    "custom_background_image.*".toRegex(),
    "custom_font".toRegex(),
    "custom_emoji_font".toRegex(),
) }

internal fun isAllowedBackupFile(path: String) = backupFilePatterns.any { path.matches(it) }
