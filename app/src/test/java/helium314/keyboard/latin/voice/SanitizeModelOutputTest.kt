// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeModelOutputTest {
    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", sanitizeModelOutput("", 100))
    }

    @Test
    fun pureAsciiPassesThroughUnchanged() {
        val input = "Hello, world! 123."
        assertEquals(input, sanitizeModelOutput(input, 100))
    }

    @Test
    fun controlCharsAreStripped() {
        val input = "abc\u0000def\u0001ghi\u0007end"
        assertEquals("abcdefghiend", sanitizeModelOutput(input, 100))
    }

    @Test
    fun zwjIsPreservedSoEmojiFamilySurvives() {
        // U+1F468 U+200D U+1F469 U+200D U+1F466 -> man + woman + boy family emoji
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66"
        val result = sanitizeModelOutput(family, 100)
        assertEquals(family, result)
        assertTrue("ZWJ must be preserved", result.contains('\u200D'))
    }

    @Test
    fun lrmAndRlmAreStripped() {
        val input = "a\u200Eb\u200Fc"
        assertEquals("abc", sanitizeModelOutput(input, 100))
    }

    @Test
    fun bomIsStripped() {
        val input = "\uFEFFhello"
        assertEquals("hello", sanitizeModelOutput(input, 100))
    }

    @Test
    fun bidiEmbeddingsAreStripped() {
        // U+202A..U+202E
        val input = "a\u202Ab\u202Bc\u202Cd\u202De\u202Ef"
        assertEquals("abcdef", sanitizeModelOutput(input, 100))
    }

    @Test
    fun maxLengthTruncationDoesNotSplitSurrogatePair() {
        // Build a string that ends with a surrogate pair right at the max-length boundary.
        // Prefix of ASCII 'a's, then an astral emoji whose high surrogate would land at index
        // maxLength - 1 with a naive substring truncation.
        val prefix = "a".repeat(9)
        // U+1F600 GRINNING FACE = high D83D + low DE00
        val emoji = "\uD83D\uDE00"
        // Full length = 9 + 2 = 11 chars. Truncating to 10 would split the pair.
        val input = prefix + emoji + "TAIL"
        val result = sanitizeModelOutput(input, 10)
        assertTrue("result char count should be <= maxLength", result.length <= 10)
        assertFalse(
            "truncated result must not end on a high surrogate",
            result.isNotEmpty() && Character.isHighSurrogate(result.last())
        )
    }

    @Test
    fun maxLengthTruncationTrimsToLimit() {
        val input = "abcdefghij_ignored_tail"
        val result = sanitizeModelOutput(input, 10)
        assertEquals("abcdefghij", result)
    }
}
