// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

/** Pure destination check shared by the IME callback and local regression tests. */
public final class VoiceDestinationGuard {
    private VoiceDestinationGuard() {}

    /**
     * Whether a finished transcription may still be written into the editor it was dictated
     * against.
     *
     * The editor fingerprint plus the input-session generation establish "same field"; the
     * {@code selectionChanged} flag establishes "nothing outside the IME moved the caret". Caret
     * <em>positions</em> are deliberately not compared: typing while the upload is in flight is
     * allowed by design, and every character typed moves the caret, so an equality test on the
     * position discarded the user's own dictation the moment they kept typing. The caller checks
     * {@code hasSelection()} at commit time instead, which is the case that actually matters —
     * a transcription must never silently replace a selection the user made while waiting.
     */
    public static boolean isUnchanged(
            final String targetEditor,
            final String currentEditor,
            final long targetSession,
            final long currentSession,
            final boolean selectionChanged) {
        return targetEditor != null
                && targetEditor.equals(currentEditor)
                && targetSession == currentSession
                && !selectionChanged;
    }
}
