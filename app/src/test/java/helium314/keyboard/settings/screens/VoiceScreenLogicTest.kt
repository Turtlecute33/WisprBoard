// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.settings.Settings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceScreenLogicTest {
    @Test
    fun voiceItemsHideConfigurationWhenVoiceInputIsDisabled() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = false,
            voiceModel = "google/gemini-3-flash-preview",
        )

        assertTrue(Settings.PREF_VOICE_INPUT_ENABLED in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_VOICE_MODEL in items)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in items)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in items)
        assertFalse(Settings.PREF_VOICE_EXPECTED_LANGUAGES in items)
    }

    @Test
    fun voiceItemsShowCustomModelOnlyWhenSelected() {
        val regularModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "google/gemini-3-flash-preview",
        )
        val customModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "custom",
        )

        assertTrue(Settings.PREF_OPENROUTER_API_KEY in regularModelItems)
        assertTrue(Settings.PREF_OPENROUTER_ZDR_ENABLED in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in regularModelItems)
        assertTrue(Settings.PREF_VOICE_EXPECTED_LANGUAGES in regularModelItems)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL_CUSTOM in customModelItems)
    }

    @Test
    fun voiceItemsShowAutoStopDurationOnlyWhenEnabled() {
        val autoStopOffItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "google/gemini-3-flash-preview",
            voiceAutoStop = false,
        )
        val autoStopOnItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "google/gemini-3-flash-preview",
            voiceAutoStop = true,
        )

        assertFalse(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOffItems)
        assertTrue(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOnItems)
    }
}
