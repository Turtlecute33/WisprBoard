// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.PersistableBundle
import helium314.keyboard.latin.ClipboardHistoryManager.Companion.MAX_CLIP_TEXT_LENGTH
import helium314.keyboard.latin.ClipboardHistoryManager.Companion.cappedClipText
import helium314.keyboard.latin.ClipboardHistoryManager.Companion.clipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The one rule for clipboard reads (R-08) */
@RunWith(RobolectricTestRunner::class)
class ClipTextTest {
    @Test
    fun capKeepsTextUpToTheLimit() {
        val text = "a".repeat(MAX_CLIP_TEXT_LENGTH)
        assertEquals(text, cappedClipText(text))
    }

    @Test
    fun capSkipsLongerText() {
        assertNull(cappedClipText("a".repeat(MAX_CLIP_TEXT_LENGTH + 1)))
    }

    @Test
    fun capSkipsNullAndEmptyText() {
        assertNull(cappedClipText(null))
        assertNull(cappedClipText(""))
    }

    @Test
    fun plainTextClipGivesItsText() {
        assertEquals("sample", clipText(ClipData.newPlainText(null, "sample"), skipSensitive = true)?.toString())
    }

    @Test
    fun uriOnlyTextClipGivesNull() {
        // coerceToText() would open and read this stream; the rule reads the item text only
        val description = ClipDescription(null, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
        val clip = ClipData(description, ClipData.Item(Uri.parse("content://example.invalid/note")))
        assertNull(clipText(clip, skipSensitive = false))
    }

    @Test
    fun nonTextClipGivesNull() {
        val description = ClipDescription(null, arrayOf("image/png"))
        assertNull(clipText(ClipData(description, ClipData.Item("sample")), skipSensitive = false))
    }

    @Test
    fun sensitiveClipIsSkippedOnlyWhenAsked() {
        val clip = ClipData.newPlainText(null, "sample")
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        assertNull(clipText(clip, skipSensitive = true))
        assertEquals("sample", clipText(clip, skipSensitive = false)?.toString())
    }

    @Test
    fun nullClipGivesNull() {
        assertNull(clipText(null, skipSensitive = false))
    }
}
