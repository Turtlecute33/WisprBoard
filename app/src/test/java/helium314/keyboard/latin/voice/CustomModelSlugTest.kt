// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomModelSlugTest {

    @Test
    fun emptyIsAllowed() {
        // Lets the user clear the field without a validation block.
        assertTrue(isValidCustomModelSlug(""))
        assertTrue(isValidCustomModelSlug("   "))
    }

    @Test
    fun standardSlugsAccepted() {
        assertTrue(isValidCustomModelSlug("openai/whisper-1"))
        assertTrue(isValidCustomModelSlug("google/chirp-3"))
        assertTrue(isValidCustomModelSlug("anthropic/claude-3-5-sonnet"))
    }

    @Test
    fun variantSuffixesAccepted() {
        assertTrue(isValidCustomModelSlug("nvidia/nemotron:free"))
        assertTrue(isValidCustomModelSlug("openai/gpt-4o:beta"))
    }

    @Test
    fun openRouterTildeAliasAccepted() {
        assertTrue(isValidCustomModelSlug("~google/gemini-flash-latest"))
        assertTrue(isValidCustomModelSlug("~anthropic/claude-haiku-latest"))
    }

    @Test
    fun whitespaceIsTrimmedNotRejected() {
        assertTrue(isValidCustomModelSlug("  openai/whisper-1  "))
    }

    @Test
    fun bareNameRejected() {
        assertFalse(isValidCustomModelSlug("whisper-1"))
        assertFalse(isValidCustomModelSlug("openai"))
    }

    @Test
    fun internalWhitespaceRejected() {
        assertFalse(isValidCustomModelSlug("openai / whisper-1"))
        assertFalse(isValidCustomModelSlug("openai/whisper 1"))
    }

    @Test
    fun missingSlugRejected() {
        assertFalse(isValidCustomModelSlug("openai/"))
        assertFalse(isValidCustomModelSlug("/whisper-1"))
    }

    @Test
    fun leadingTrailingSeparatorsRejected() {
        assertFalse(isValidCustomModelSlug("-openai/whisper-1"))
        assertFalse(isValidCustomModelSlug("openai/whisper-1-"))
    }
}
