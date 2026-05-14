// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against a default slug falling out of the catalog without anyone noticing — fresh
 * installs would silently get a slug that no longer routes, and the picker would render a
 * stale-model description on first open.
 */
class ModelCatalogConsistencyTest {

    @Test
    fun voiceDefaultExistsInOpenRouterCatalog() {
        val slugs = ModelCatalog.OPENROUTER_VOICE.map { it.slug }
        assertTrue(
            "PREF_VOICE_MODEL=${Defaults.PREF_VOICE_MODEL} missing from OPENROUTER_VOICE: $slugs",
            Defaults.PREF_VOICE_MODEL in slugs,
        )
    }

    @Test
    fun sttDefaultExistsInOpenRouterCatalog() {
        val slugs = ModelCatalog.OPENROUTER_STT.map { it.slug }
        assertTrue(
            "PREF_VOICE_STT_MODEL=${Defaults.PREF_VOICE_STT_MODEL} missing from OPENROUTER_STT: $slugs",
            Defaults.PREF_VOICE_STT_MODEL in slugs,
        )
    }

    @Test
    fun textFixDefaultExistsInOpenRouterCatalog() {
        val slugs = ModelCatalog.OPENROUTER_TEXT_FIX.map { it.slug }
        assertTrue(
            "PREF_TEXT_FIX_MODEL=${Defaults.PREF_TEXT_FIX_MODEL} missing from OPENROUTER_TEXT_FIX: $slugs",
            Defaults.PREF_TEXT_FIX_MODEL in slugs,
        )
    }

    @Test
    fun voiceDefaultIsAlsoValidOnPayPerQ() {
        // The provider-switch fallback in VoiceScreen relies on the default being valid for
        // both providers — otherwise switching to PayPerQ from a previously-default OpenRouter
        // slug would not reset cleanly.
        val payperqSlugs = ModelCatalog.PAYPERQ_VOICE.map { it.slug }
        assertTrue(
            "PREF_VOICE_MODEL must resolve on PayPerQ too: $payperqSlugs",
            Defaults.PREF_VOICE_MODEL in payperqSlugs,
        )
    }

    @Test
    fun textFixDefaultIsAlsoValidOnPayPerQ() {
        val payperqSlugs = ModelCatalog.PAYPERQ_TEXT_FIX.map { it.slug }
        assertTrue(
            "PREF_TEXT_FIX_MODEL must resolve on PayPerQ too: $payperqSlugs",
            Defaults.PREF_TEXT_FIX_MODEL in payperqSlugs,
        )
    }
}
