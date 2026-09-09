package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.graphics.Paint
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object SupportedEmojis {
    /**
     * Published as one immutable snapshot rather than mutated in place.
     *
     * [load] runs on a background dispatcher from `App.onCreate` while the main thread is already
     * building the first keyboard and reading this set, so `clear()`-then-refill exposed readers to
     * a half-empty set — and one of those readers, [helium314.keyboard.keyboard.internal
     * .keyboard_parser.loadEmojiDefaultVersionsAndPopupSpecs], caches what it saw for the life of
     * the process.
     */
    @Volatile private var unsupportedEmojis: Set<String> = emptySet()

    /**
     * Bumped on every [load]. Consumers that cache anything derived from this set compare their
     * own copy of this and rebuild when it moves — without it, changing the "Emoji max SDK"
     * setting or restoring a backup silently left a stale derived cache in place.
     */
    @Volatile var loadGeneration: Int = 0
        private set

    fun load(context: Context) {
        determineMaxSdk(context)
        val maxSdk = context.prefs().getInt(Settings.PREF_EMOJI_MAX_SDK, 0)
        val loaded = HashSet<String>()
        context.assets.open("emoji/minApi.txt").reader().readLines().forEach {
            val s = it.split(" ")
            val minApi = s.first().toInt()
            if (minApi > maxSdk)
                loaded.addAll(s.drop(1))
        }
        // Single volatile write: a reader sees either the previous set or the complete new one.
        unsupportedEmojis = loaded
        loadGeneration++
    }

    private fun determineMaxSdk(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (context.prefs().contains(Settings.PREF_EMOJI_MAX_SDK)) return
        val paint = Paint()
        (Settings.getInstance().customEmojiTypeface ?: Settings.getInstance().customTypeface)
            ?.let { paint.setTypeface(it) }
        val maxApi = context.assets.open("emoji/minApi.txt").reader().readLines().maxOf {
            val s = it.split(" ")
            val supported = paint.hasGlyph(s[1])
            if (supported) s.first().toInt() else 0
        }
        val newMax = maxApi.coerceAtLeast(Build.VERSION.SDK_INT)
        context.prefs().edit { putInt(Settings.PREF_EMOJI_MAX_SDK, newMax) }
    }

    fun isUnsupported(emoji: String) = emoji in unsupportedEmojis
}
