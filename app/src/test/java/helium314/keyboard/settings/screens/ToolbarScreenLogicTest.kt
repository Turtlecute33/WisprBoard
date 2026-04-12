// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.resolveTranscriptionPrompt
import helium314.keyboard.latin.voice.resolveVoiceModel
import helium314.keyboard.latin.utils.ToolbarMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolbarScreenLogicTest {
    @Test
    fun toolbarItemsHideVoiceConfigurationWhenVoiceInputIsDisabled() {
        val items = buildToolbarScreenItems(
            toolbarMode = ToolbarMode.EXPANDABLE,
            toolbarHidingGlobal = true,
            voiceInputEnabled = false,
            voiceModel = "google/gemini-3-flash-preview",
        )

        assertTrue(Settings.PREF_VOICE_INPUT_ENABLED in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_VOICE_MODEL in items)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in items)
    }

    @Test
    fun toolbarItemsShowCustomModelOnlyWhenSelected() {
        val regularModelItems = buildToolbarScreenItems(
            toolbarMode = ToolbarMode.EXPANDABLE,
            toolbarHidingGlobal = true,
            voiceInputEnabled = true,
            voiceModel = "google/gemini-3-flash-preview",
        )
        val customModelItems = buildToolbarScreenItems(
            toolbarMode = ToolbarMode.EXPANDABLE,
            toolbarHidingGlobal = true,
            voiceInputEnabled = true,
            voiceModel = "custom",
        )

        assertTrue(Settings.PREF_OPENROUTER_API_KEY in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in regularModelItems)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL_CUSTOM in customModelItems)
    }

    @Test
    fun toolbarItemsKeepExistingToolbarConditionals() {
        val hiddenToolbarItems = buildToolbarScreenItems(
            toolbarMode = ToolbarMode.HIDDEN,
            toolbarHidingGlobal = false,
            voiceInputEnabled = false,
            voiceModel = "google/gemini-3-flash-preview",
        )

        assertTrue(Settings.PREF_TOOLBAR_HIDING_GLOBAL in hiddenToolbarItems)
        assertFalse(Settings.PREF_TOOLBAR_KEYS in hiddenToolbarItems)
        assertTrue(Settings.PREF_CLIPBOARD_TOOLBAR_KEYS in hiddenToolbarItems)
        assertFalse(R.string.settings_category_miscellaneous in hiddenToolbarItems)
    }

    @Test
    fun resolveVoiceModelTrimsCustomModel() {
        assertEquals(
            "openai/whisper-large",
            resolveVoiceModel("custom", "  openai/whisper-large  ")
        )
    }

    @Test
    fun resolveVoiceModelRejectsBlankCustomModel() {
        assertNull(resolveVoiceModel("custom", "   "))
    }

    @Test
    fun resolveTranscriptionPromptFallsBackToDefaultForBlankValue() {
        assertEquals(
            Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT,
            resolveTranscriptionPrompt("   ")
        )
    }

    @Test
    fun resolveTranscriptionPromptTrimsCustomPrompt() {
        assertEquals(
            "Return clean bullet points.",
            resolveTranscriptionPrompt("  Return clean bullet points.  ")
        )
    }
}
