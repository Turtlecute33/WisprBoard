// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDestinationGuardTest {
    @Test
    fun unchangedDestinationIsAccepted() {
        assertTrue(VoiceDestinationGuard.isUnchanged("app/-1/1", "app/-1/1", 4, 4, false))
    }

    @Test
    fun aDifferentEditorIsRejected() {
        assertFalse(VoiceDestinationGuard.isUnchanged("app/7/1", "app/7/2", 4, 4, false))
    }

    @Test
    fun anUnknownTargetEditorIsRejected() {
        assertFalse(VoiceDestinationGuard.isUnchanged(null, "app/7/1", 4, 4, false))
    }

    @Test
    fun reusedFieldIdentityInANewInputSessionIsRejected() {
        assertFalse(VoiceDestinationGuard.isUnchanged("app/-1/1", "app/-1/1", 4, 5, false))
    }

    @Test
    fun aCaretMoveTheImeDidNotCauseIsRejectedEvenAfterTheCursorReturns() {
        assertFalse(VoiceDestinationGuard.isUnchanged("app/7/1", "app/7/1", 4, 4, true))
    }

    @Test
    fun typingWhileTheUploadRunsNoLongerDiscardsTheDictation() {
        // Regression guard. The caret positions used to be compared for equality here, so every
        // character the user typed during the upload — which the design explicitly permits —
        // destroyed their own dictation. The caller now arms selectionChanged only for moves the
        // IME did not make, and refuses at commit time on hasSelection() instead.
        assertTrue(VoiceDestinationGuard.isUnchanged("app/7/1", "app/7/1", 4, 4, false))
    }
}
