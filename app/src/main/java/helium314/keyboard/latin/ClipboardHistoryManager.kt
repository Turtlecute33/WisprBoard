// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.compat.isDeviceLocked
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.ToolbarKey

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            clipboardDao?.clearOldClips(true)
            fetchPrimaryClip()
        } else {
            clipboardDao?.clear()
        }
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        clipboardDao?.cancelScheduledClear()
    }

    override fun onPrimaryClipChanged() {
        // A new clip can show as a suggestion again, also when history is off (R-40).
        dontShowCurrentSuggestion = false
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
        }
    }

    private fun fetchPrimaryClip() {
        val clipData = clipboardManager.primaryClip ?: return
        val content = clipText(clipData, skipSensitive = true) ?: return
        clipboardDao?.addClip(ClipboardManagerCompat.getClipTimestamp(clipData), false, content.toString())
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun removeEntry(id: Long) {
        clearPrimaryClipIfSame(clipboardDao?.get(id)?.text)
        clipboardDao?.deleteClip(id)
    }

    fun updateEntryText(id: Long, text: String) {
        val oldText = clipboardDao?.get(id)?.text
        if (oldText != text) clearPrimaryClipIfSame(oldText)
        if (text.isEmpty()) clipboardDao?.deleteClip(id)
        else clipboardDao?.updateText(id, text)
    }

    /**
     * Clears the system clip when it has [text], as [clearHistory] does. Else [onCreate] reads the
     * clip again and adds the deleted or old text back to the history (R-11).
     */
    private fun clearPrimaryClipIfSame(text: String?) {
        if (text == null) return
        if (clipText(clipboardManager.primaryClip, skipSensitive = false)?.toString() != text) return
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
    }

    /** Puts [text] back into the system clipboard, so other apps can paste it */
    fun copyToSystemClipboard(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    /** Snapshot of the whole history, most recent (or pinned) first */
    fun getHistoryEntries(): List<ClipboardHistoryEntry> = clipboardDao?.getAll().orEmpty()

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    fun retrieveClipboardContent(): CharSequence =
        clipText(clipboardManager.primaryClip, skipSensitive = false) ?: ""

    /**
     * The clipboard text, or "" when the clip is not text or the app that wrote it marked it
     * sensitive. Pasting such a clip is fine because it stays on the device, but an AI action
     * picks the clipboard up implicitly and sends it to a provider, so it must skip these.
     * Also "" on a locked device: a person without the unlock code must not send the clipboard.
     */
    fun retrieveClipboardContentForAi(): CharSequence {
        if (isDeviceLocked(latinIME)) return ""
        return clipText(clipboardManager.primaryClip, skipSensitive = true) ?: ""
    }

    private fun isClipSensitive(description: ClipDescription?, inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        // No clipboard content on a locked device. Use the cached flag, not an IPC per keystroke.
        if (latinIME.mKeyboardSwitcher.keyboard?.mId?.mDeviceLocked == true) return null
        // Check the description before fetching the clip. This runs once per keystroke for anyone
        // with clipboard suggestions on, and getPrimaryClip() copies the entire clip across a
        // binder boundary, whereas getPrimaryClipDescription() does not — so for a clip older than
        // the recency window (the overwhelmingly common case) that copy was pure waste.
        val description = clipboardManager.primaryClipDescription ?: return null
        if (!description.hasMimeType("text/*")) return null
        if (System.currentTimeMillis() - ClipboardManagerCompat.getClipTimestamp(description) > RECENT_TIME_MILLIS) return null
        val clipData = clipboardManager.primaryClip ?: return null
        val content = clipText(clipData, skipSensitive = false) ?: return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        latinIME.mSettings.getCustomTypeface()?.let { textView.typeface = it }
        textView.text = (if (isClipSensitive(clipData.description, inputType)) "*".repeat(content.length) else content)
            .take(200) // truncate displayed text for performance reasons
        val clipIcon = latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(clipIcon, null, null, null)
        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            latinIME.onTextInput(content.toString())
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)
        /** The keyboard does not read a clip text that is longer than this, in chars (R-08). */
        const val MAX_CLIP_TEXT_LENGTH = 100_000

        /**
         * The one rule for all clipboard reads (R-08): the text of the first item of a text clip,
         * or null. Only ClipData.Item.getText() is read. coerceToText() opens and reads all of a
         * URI stream on the main thread, so a URI-only clip gives null. With [skipSensitive], a
         * clip that its app marked sensitive gives null before the text is read.
         */
        fun clipText(clipData: ClipData?, skipSensitive: Boolean): CharSequence? {
            if (clipData == null || clipData.itemCount == 0) return null
            val description = clipData.description
            if (description?.hasMimeType("text/*") == false) return null
            if (skipSensitive && ClipboardManagerCompat.getClipSensitivity(description) == true) return null
            return cappedClipText(clipData.getItemAt(0)?.text)
        }

        /** [text], or null when it is empty or longer than [MAX_CLIP_TEXT_LENGTH]. */
        fun cappedClipText(text: CharSequence?): CharSequence? =
            text?.takeIf { it.isNotEmpty() && it.length <= MAX_CLIP_TEXT_LENGTH }
    }
}
