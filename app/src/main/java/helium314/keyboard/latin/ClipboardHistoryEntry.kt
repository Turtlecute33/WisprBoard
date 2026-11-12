// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

class ClipboardHistoryEntry(
    val id: Long,
    var timeStamp: Long,
    var isPinned: Boolean,
    val text: String
) : Comparable<ClipboardHistoryEntry> {
    /** Pinned clips first, then the newest first */
    override fun compareTo(other: ClipboardHistoryEntry): Int {
        val result = other.isPinned.compareTo(isPinned)
        if (result == 0) return other.timeStamp.compareTo(timeStamp)
        return result
    }
}
