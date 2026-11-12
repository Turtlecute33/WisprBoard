package helium314.keyboard.settings.preferences

import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTooLargeException
import helium314.keyboard.latin.utils.readTextCapped
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    fun restoreSettingsRejectsSensitiveKeysFromLegacyOrCraftedBackup() {
        val snapshot = parseSettingsSnapshot(
            listOf(
                "string settings",
                """{"${Settings.PREF_OPENROUTER_API_KEY}":"secret","${Settings.PREF_PAYPERQ_API_KEY}":"secret-too","safe_string":"value"}""",
                "string set settings",
                """{"pinned_clips":["private clip"],"safe_set":["entry"]}""",
            )
        )

        assertFalse(snapshot.strings.containsKey(Settings.PREF_OPENROUTER_API_KEY))
        assertFalse(snapshot.strings.containsKey(Settings.PREF_PAYPERQ_API_KEY))
        assertFalse(snapshot.stringSets.containsKey("pinned_clips"))
        assertEquals("value", snapshot.strings["safe_string"])
        assertEquals(setOf("entry"), snapshot.stringSets["safe_set"])
    }

    @Test
    fun backupFileAllowlistIncludesLearnedWordsButNotAddedDictionaries() {
        assertTrue(isAllowedBackupFile("blacklists/example.txt"))
        assertTrue(isAllowedBackupFile("custom_background_image.jpg"))
        assertTrue(isAllowedBackupFile("UserHistoryDictionary.en-US.dict/UserHistoryDictionary.en-US.dict.body"))
        assertTrue(isAllowedBackupFile("UserHistoryDictionary.en-US.dict/UserHistoryDictionary.en-US.dict.header"))
        assertFalse(isAllowedBackupFile("dicts/en/example.user.dict"))
    }

    @Test
    fun apiKeysRestoreOnlyKnownNonBlankKeys() {
        val keys = parseApiKeys(
            """{"${Settings.PREF_OPENROUTER_API_KEY}":"secret","${Settings.PREF_PAYPERQ_API_KEY}":" ","other_pref":"value"}"""
        )

        assertEquals(mapOf(Settings.PREF_OPENROUTER_API_KEY to "secret"), keys)
    }

    @Test
    fun apiKeysThatCannotGoIntoAHeaderAreDropped() {
        val keys = parseApiKeys(
            """{"${Settings.PREF_OPENROUTER_API_KEY}":"sk-a\nb","${Settings.PREF_PAYPERQ_API_KEY}":"  sk-valid\n"}"""
        )
        assertEquals(mapOf(Settings.PREF_PAYPERQ_API_KEY to "sk-valid"), keys)
        // The JSON escape gives U+200B (zero width space), which trim() does not remove.
        val zeroWidth = parseApiKeys("""{"${Settings.PREF_OPENROUTER_API_KEY}":"sk-a\u200Bb"}""")
        assertEquals(emptyMap(), zeroWidth)
    }

    @Test
    fun apiKeysFromBackupAreUsedOnlyWhenTheUserAgrees() {
        val backupKeys = mapOf(Settings.PREF_OPENROUTER_API_KEY to "key-from-file")

        assertEquals(emptyMap(), apiKeysToRestore(backupKeys, replaceApiKeys = false))
        assertEquals(backupKeys, apiKeysToRestore(backupKeys, replaceApiKeys = true))
    }

    @Test
    fun restoreKeepsTheCurrentZdrChoice() {
        val zdrOff = SettingsSnapshot(booleans = mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to false, "safe_boolean" to true))
        val zdrOn = SettingsSnapshot(booleans = mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to true))

        // A file with ZDR off cannot turn off the user's ZDR.
        assertEquals(
            mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to true, "safe_boolean" to true),
            zdrOff.keepingCurrent(mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to true)).booleans,
        )
        assertEquals(
            false,
            zdrOn.keepingCurrent(mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to false)).booleans[Settings.PREF_OPENROUTER_ZDR_ENABLED],
        )
        // Not set now: stays not set, so the default (on) applies.
        assertEquals(mapOf("safe_boolean" to true), zdrOff.keepingCurrent(emptyMap<String, Any>()).booleans)
        // A current value that is not a boolean counts as not set.
        assertEquals(mapOf("safe_boolean" to true), zdrOff.keepingCurrent(mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to "x")).booleans)
        // A ZDR key with a different type cannot replace the kept choice.
        val zdrAsString = SettingsSnapshot(strings = mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to "x", "safe_string" to "value"))
        val kept = zdrAsString.keepingCurrent(mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to true))
        assertEquals(mapOf("safe_string" to "value"), kept.strings)
        assertEquals(mapOf(Settings.PREF_OPENROUTER_ZDR_ENABLED to true), kept.booleans)
    }

    @Test
    fun restoreKeepsTheCurrentProviderAndSpeechEngine() {
        val fromFile = SettingsSnapshot(
            strings = mapOf(
                Settings.PREF_AI_PROVIDER to "payperq",
                Settings.PREF_VOICE_SPEECH_ENGINE to "on_device",
                Settings.PREF_TEXT_FIX_PROMPT to "prompt",
            ),
            booleans = mapOf(Settings.PREF_AI_PROVIDER to true),
        )

        val kept = fromFile.keepingCurrent(
            mapOf(Settings.PREF_AI_PROVIDER to "openrouter", Settings.PREF_VOICE_SPEECH_ENGINE to "cloud")
        )
        assertEquals(
            mapOf(
                Settings.PREF_AI_PROVIDER to "openrouter",
                Settings.PREF_VOICE_SPEECH_ENGINE to "cloud",
                Settings.PREF_TEXT_FIX_PROMPT to "prompt",
            ),
            kept.strings,
        )
        // A kept key with a different type in the file is removed.
        assertEquals(emptyMap(), kept.booleans)
        // Not set now: the file cannot set it, so the default applies.
        assertEquals(mapOf(Settings.PREF_TEXT_FIX_PROMPT to "prompt"), fromFile.keepingCurrent(emptyMap<String, Any>()).strings)
    }

    @Test
    fun aiSettingsAreFoundInAnySettingType() {
        assertTrue(SettingsSnapshot(strings = mapOf(Settings.PREF_TEXT_FIX_PROMPT to "prompt")).hasAiSettings())
        assertTrue(SettingsSnapshot(ints = mapOf(Settings.PREF_VOICE_MAX_DURATION_SECONDS to 60)).hasAiSettings())
        assertTrue(SettingsSnapshot(strings = mapOf(Settings.PREF_AI_PROVIDER to "openrouter")).hasAiSettings())
        assertFalse(SettingsSnapshot(booleans = mapOf(Settings.PREF_AUTO_CAP to true)).hasAiSettings())
    }

    @Test
    fun malformedBackupEntriesDoNotQuoteTheirContent() {
        val keysError = assertFailsWith<IllegalArgumentException> { parseApiKeys("""{"key":"secret-value"""") }
        val clipsError = assertFailsWith<IllegalArgumentException> { parsePinnedClips("""[{"text":"private clip"""") }

        assertFalse(keysError.message.orEmpty().contains("secret-value"))
        assertFalse(clipsError.message.orEmpty().contains("private clip"))
        assertEquals(null, keysError.cause)
        assertEquals(null, clipsError.cause)
    }

    @Test
    fun malformedSettingsDoNotQuoteTheirContent() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseSettingsSnapshot(listOf("string settings", """{"key":"private value""""))
        }

        assertFalse(error.message.orEmpty().contains("private value"))
        assertEquals(null, error.cause)
    }

    @Test
    fun tooManyPinnedClipsAreRejected() {
        val atLimit = List(MAX_RESTORE_PINNED_CLIPS) { BackupClip(it.toLong(), "clip") }

        assertEquals(atLimit, parsePinnedClips(Json.encodeToString(atLimit)))
        assertFailsWith<IllegalArgumentException> {
            parsePinnedClips(Json.encodeToString(atLimit + BackupClip(0L, "one more")))
        }
    }

    @Test
    fun cappedReaderStopsAfterTheLimit() {
        assertEquals("abc", readTextCapped(ByteArrayInputStream("abc".toByteArray()), 3))
        assertFailsWith<InputTooLargeException> { readTextCapped(ByteArrayInputStream("abcd".toByteArray()), 3) }
    }

    @Test
    fun pinnedClipsRoundTrip() {
        val clips = listOf(BackupClip(1L, "first"), BackupClip(2L, "second\nline"))

        assertEquals(clips, parsePinnedClips(Json.encodeToString(clips)))
    }

    @Test
    fun backupEntryNormalizationRejectsTraversal() {
        assertEquals("safe/path.txt", normalizeBackupEntryName("/safe/path.txt"))
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("../unsafe.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("safe/../unsafe.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeBackupEntryName("..")
        }
    }

    @Test
    fun restoreEntryCopyDoesNotCloseZipStream() {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("blacklists/first.txt"))
                zip.write("first".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("blacklists/second.txt"))
                zip.write("second".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val parentDir = Files.createTempDirectory("restore-test-").toFile()

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                assertEquals("blacklists/first.txt", zip.nextEntry.name)
                copyRestoreEntryToNewFile(zip, parentDir.resolve("first.txt"))
                zip.closeEntry()

                assertEquals("blacklists/second.txt", zip.nextEntry.name)
                copyRestoreEntryToNewFile(zip, parentDir.resolve("second.txt"))
                zip.closeEntry()
            }
            assertEquals("first", parentDir.resolve("first.txt").readText())
            assertEquals("second", parentDir.resolve("second.txt").readText())
        } finally {
            parentDir.deleteRecursively()
        }
    }
}
