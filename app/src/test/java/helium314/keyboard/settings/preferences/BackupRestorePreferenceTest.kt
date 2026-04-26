package helium314.keyboard.settings.preferences

import helium314.keyboard.latin.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupRestorePreferenceTest {
    @Test
    fun backupSettingsExcludeSensitiveKeys() {
        val snapshot = parseSettingsSnapshot(
            settingsToJsonString(
                mapOf(
                    Settings.PREF_OPENROUTER_API_KEY to "secret",
                    Settings.PREF_PAYPERQ_API_KEY to "secret-too",
                    "pinned_clips" to """[{"content":"should not persist"}]""",
                    "safe_string" to "value",
                    "safe_boolean" to true,
                )
            ).split("\n")
        )

        assertFalse(snapshot.strings.containsKey(Settings.PREF_OPENROUTER_API_KEY))
        assertFalse(snapshot.strings.containsKey(Settings.PREF_PAYPERQ_API_KEY))
        assertFalse(snapshot.strings.containsKey("pinned_clips"))
        assertEquals("value", snapshot.strings["safe_string"])
        assertEquals(true, snapshot.booleans["safe_boolean"])
    }

    @Test
    fun backupFileAllowlistExcludesLearnedAndHistoryData() {
        assertTrue(isAllowedBackupFile("blacklists/example.txt"))
        assertTrue(isAllowedBackupFile("custom_background_image.jpg"))
        assertFalse(isAllowedBackupFile("dicts/en/example.user.dict"))
        assertFalse(isAllowedBackupFile("UserHistoryDictionary.en/UserHistoryDictionary.en.body"))
    }

    @Test
    fun backupEntryNormalizationRejectsTraversal() {
        assertEquals("safe/path.txt", normalizeBackupEntryName("/safe/path.txt"))
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("../unsafe.txt")
        }
    }
}
