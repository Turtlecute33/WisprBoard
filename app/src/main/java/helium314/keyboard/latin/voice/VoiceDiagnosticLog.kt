// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only diagnostic log for the voice pipeline, written to app-private external files
 * so the user can share it from settings without adb. Ring-capped to avoid growing unbounded.
 */
object VoiceDiagnosticLog {

    private const val LOG_SUBDIR = "voice-diag"
    private const val LOG_FILE_NAME = "voice-diag.log"
    private const val MAX_SIZE_BYTES = 200_000L
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun logFile(context: Context): File? {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, LOG_SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, LOG_FILE_NAME)
    }

    fun log(context: Context, tag: String, message: String) {
        val file = logFile(context) ?: return
        val line = "${timestampFormat.format(Date())} [$tag] $message\n"
        synchronized(lock) {
            try {
                if (file.length() > MAX_SIZE_BYTES) {
                    // Keep the most recent ~half; drop the rest.
                    val keep = file.readBytes().takeLast((MAX_SIZE_BYTES / 2).toInt()).toByteArray()
                    file.writeBytes(keep)
                }
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write(line.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
                // Diagnostic logging must never crash callers.
            }
        }
    }

    /** Formats a condensed stack trace showing the caller chain up to [depth] frames. */
    fun callerTrace(depth: Int = 6): String {
        val trace = Throwable().stackTrace
        // Skip the first couple of frames (this method + its caller's logging line).
        return trace.drop(2).take(depth).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }

    fun read(context: Context): String {
        val file = logFile(context) ?: return ""
        return try {
            if (file.exists()) file.readText() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear(context: Context) {
        val file = logFile(context) ?: return
        synchronized(lock) {
            runCatching { if (file.exists()) file.delete() }
        }
    }
}
