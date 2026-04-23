package helium314.keyboard.latin.utils

import android.os.Build
import java.time.LocalDateTime
import java.util.Date

/**
 * Logger that does the android logging, but also allows reading the log in the app.
 * It's only a little slower than the android logger, but since both are used we end up at
 * half performance (still fast enough to not be noticeable, unless spamming thousands of log lines)
 */
object Log {
    private const val MAX_LOG_LINES = 12000
    private const val TRIMMED_LOG_LINES = 2000
    private const val MAX_EXPORT_LINES = 2000

    @JvmStatic
    fun wtf(tag: String?, message: String) {
        log(LogLine('F', tag, message))
        android.util.Log.wtf(tag, message)
    }

    @JvmStatic
    fun e(tag: String?, message: String, e: Throwable?) {
        log(LogLine('E', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.e(tag, message, e)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        log(LogLine('E', tag, message))
        android.util.Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String, e: Throwable?) {
        log(LogLine('W', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.w(tag, message, e)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        log(LogLine('W', tag, message))
        android.util.Log.w(tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String, e: Throwable?) {
        log(LogLine('I', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.i(tag, message, e)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        log(LogLine('I', tag, message))
        android.util.Log.i(tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String, e: Throwable?) {
        log(LogLine('D', tag, "$message\n${e?.stackTraceToString()}"))
        android.util.Log.d(tag, message, e)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        log(LogLine('D', tag, message))
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        log(LogLine('V', tag, message))
        android.util.Log.v(tag, message)
    }

    private fun log(line: LogLine) {
        synchronized(logLines) {
            if (logLines.size > MAX_LOG_LINES) // clear oldest entries if list gets too long
                logLines.subList(0, TRIMMED_LOG_LINES).clear()
            logLines.add(line)
        }
    }

    private val logLines: MutableList<LogLine> = ArrayList(2000)

    /** returns a copy of [logLines] */
    fun getLog(maxLines: Int = logLines.size) = synchronized(logLines) { logLines.takeLast(maxLines) }

    /**
     * Returns a safer snapshot for user-facing export. We omit verbose/debug noise and collapse
     * multi-line entries so stack traces and accidental payloads are not dumped wholesale.
     */
    fun getLogForExport(maxLines: Int = MAX_EXPORT_LINES) = synchronized(logLines) {
        logLines
            .asSequence()
            .filter { it.level != 'V' && it.level != 'D' }
            .toList()
            .takeLast(maxLines)
            .map { it.toExportString() }
    }

    internal fun clearForTests() = synchronized(logLines) { logLines.clear() }
}

data class LogLine(val level: Char, val tag: String?, val message: String) {
    companion object {
        private const val MAX_EXPORT_MESSAGE_LENGTH = 500
    }

    // time can be Date or LocalDateTime, doesn't matter because but it's used for toString only
    private val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LocalDateTime.now()
    } else {
        Date(System.currentTimeMillis())
    }

    override fun toString(): String = // should look like a normal android log line, at least for api26+
        "${time.toString().replace('T', ' ')} $level $tag: $message"

    fun toExportString(): String {
        val compactMessage = message
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .let {
                if (it.length > MAX_EXPORT_MESSAGE_LENGTH) {
                    it.take(MAX_EXPORT_MESSAGE_LENGTH) + "..."
                } else {
                    it
                }
            }
        val suffix = if (message.contains('\n')) " [details omitted]" else ""
        return "${time.toString().replace('T', ' ')} $level $tag: $compactMessage$suffix"
    }
}
