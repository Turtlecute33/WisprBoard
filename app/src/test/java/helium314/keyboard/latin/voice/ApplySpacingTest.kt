// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.VoiceInputManager.SpacingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ApplySpacingTest {
    @Test
    fun emptyTextIsReturnedAsIsAndNotAllocated() {
        val empty = ""
        val result = applySpacing(empty, SpacingContext(charBefore = 'a'.code, charAfter = 'b'.code))
        assertSame("empty path should not allocate a new string", empty, result)
    }

    @Test
    fun nullSpacingContextLeavesTextUnchanged() {
        val input = "hello"
        assertSame(input, applySpacing(input, null))
    }

    @Test
    fun letterOnBothSidesGetsLeadingAndTrailingSpace() {
        val result = applySpacing("hello", SpacingContext(charBefore = 'a'.code, charAfter = 'b'.code))
        assertEquals(" hello ", result)
    }

    @Test
    fun noSpaceNextToCjkOrKana() {
        // 今日は + 元気です: Japanese does not use spaces between words (review R-17)
        val result = applySpacing("\u5143\u6C17\u3067\u3059", SpacingContext(charBefore = 0x306F, charAfter = 0x3002))
        assertEquals("\u5143\u6C17\u3067\u3059", result)
        // Latin text after a CJK character also gets no space
        assertEquals("OK", applySpacing("OK", SpacingContext(charBefore = 0x5929, charAfter = null)))
    }

    @Test
    fun spaceOnBothSidesLeavesTextUntouched() {
        val result = applySpacing("hello", SpacingContext(charBefore = ' '.code, charAfter = ' '.code))
        assertEquals("hello", result)
    }

    @Test
    fun emojiCharBeforeDoesNotGetLeadingSpace() {
        // U+1F600 GRINNING FACE — not letter-or-digit, so no leading space should be added.
        val result = applySpacing("hello", SpacingContext(charBefore = 0x1F600, charAfter = null))
        assertEquals("hello", result)
    }

    @Test
    fun digitBeforeGetsLeadingSpace() {
        val result = applySpacing("hello", SpacingContext(charBefore = 0x30, charAfter = null))
        assertEquals(" hello", result)
    }

    @Test
    fun astralLetterBeforeGetsLeadingSpace() {
        // U+10400 DESERET CAPITAL LETTER LONG I — astral letter, isLetterOrDigit = true.
        val result = applySpacing("hello", SpacingContext(charBefore = 0x10400, charAfter = null))
        assertEquals(" hello", result)
    }

    @Test
    fun textStartingWithSurrogatePairEmojiHasCorrectSpacingDecision() {
        // Leading char is emoji (not whitespace, not letter-or-digit). Heuristic only checks
        // whether the inserted text already begins with whitespace; since the emoji isn't
        // whitespace, a leading space should still be added when charBefore is a letter.
        val emoji = "\uD83D\uDE00 world"
        val resultAfterLetter = applySpacing(emoji, SpacingContext(charBefore = 'a'.code, charAfter = null))
        assertEquals(" $emoji", resultAfterLetter)

        // When the text already ends with whitespace and there's a letter after the cursor,
        // no trailing space should be added.
        val endsWithSpace = "hello "
        val resultBeforeLetter = applySpacing(endsWithSpace, SpacingContext(charBefore = null, charAfter = 'b'.code))
        assertEquals(endsWithSpace, resultBeforeLetter)
    }
}
