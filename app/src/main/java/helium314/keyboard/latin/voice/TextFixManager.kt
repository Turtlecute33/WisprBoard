// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Orchestrates text-fix requests to OpenRouter. Reads the user's selected text via a callback,
 * validates preconditions, runs the HTTP call on a background thread, and posts the proposed
 * replacement back on the main thread. Stateless across requests — a new fix starts fresh.
 */
class TextFixManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    companion object {
        private const val TAG = "TextFixManager"
        private const val MAX_INPUT_LENGTH = 10_000
        private const val MAX_OUTPUT_LENGTH = 10_000

        @JvmStatic
        @StringRes
        fun getBlockedErrorResId(
            inputType: Int,
            isPasswordField: Boolean,
            noLearning: Boolean,
            incognitoModeEnabled: Boolean,
        ): Int? {
            if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
                return R.string.text_fix_error_unsupported_field
            }
            if (isPasswordField || noLearning || incognitoModeEnabled) {
                return R.string.text_fix_error_sensitive_field
            }
            if (InputTypeUtils.isUriOrEmailType(inputType)) {
                return R.string.text_fix_error_unsupported_field
            }
            return null
        }
    }

    enum class State { IDLE, WORKING }

    interface Callbacks {
        @StringRes
        fun getBlockedErrorResId(): Int?
        /** Return the currently selected text, or null/empty if nothing is selected. */
        fun getSelectedText(): CharSequence?
        fun onWorking()
        fun onFinished()
        fun onResult(originalText: String, proposedText: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeClient: OpenRouterClient? = null
    @Volatile private var activeThread: Thread? = null
    @Volatile private var activeToken = 0L
    private var state = State.IDLE

    fun getState() = state

    @Synchronized
    fun startTextFix() {
        if (state != State.IDLE) return
        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED)) {
            Toast.makeText(context, R.string.text_fix_error_not_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        callbacks.getBlockedErrorResId()?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            return
        }
        if (!SecretStore.isSecureStorageAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = SecretStore.getApiKey(context, Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY)
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isNetworkAvailable()) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        val selected = callbacks.getSelectedText()?.toString()?.trim().orEmpty()
        if (selected.isEmpty()) {
            Toast.makeText(context, R.string.text_fix_error_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val input = if (selected.length > MAX_INPUT_LENGTH) selected.substring(0, MAX_INPUT_LENGTH) else selected

        val selectedModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL) ?: Defaults.PREF_TEXT_FIX_MODEL
        val customModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL_CUSTOM, Defaults.PREF_TEXT_FIX_MODEL_CUSTOM) ?: ""
        val model = resolveVoiceModel(selectedModel, customModel)
        if (model == null) {
            Toast.makeText(context, R.string.voice_error_no_model, Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = (prefs.getString(Settings.PREF_TEXT_FIX_PROMPT, Defaults.PREF_TEXT_FIX_PROMPT) ?: Defaults.PREF_TEXT_FIX_PROMPT)
            .trim().ifEmpty { Defaults.PREF_TEXT_FIX_PROMPT }

        state = State.WORKING
        callbacks.onWorking()

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt,
            runtimeInstruction = null,
        )
        val token = activeToken + 1
        activeToken = token
        activeClient = client

        val thread = Thread {
            try {
                val proposed = sanitize(client.fixText(input))
                if (proposed.isBlank()) {
                    finish(token, error = context.getString(R.string.text_fix_error_empty))
                    return@Thread
                }
                finish(token, original = input, result = proposed)
            } catch (e: InterruptedException) {
                finish(token)
            } catch (e: Exception) {
                Log.e(TAG, "Text fix failed", e)
                finish(token, error = safeUserFacingError(e))
            }
        }.apply {
            name = "TextFixRequest"
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                Log.e(TAG, "Text fix thread crashed", e)
                finish(token, error = context.getString(R.string.text_fix_error_failed))
            }
        }
        activeThread = thread
        thread.start()
    }

    @Synchronized
    fun cancel() {
        if (state != State.WORKING) return
        activeToken += 1
        activeClient?.cancel()
        activeThread?.interrupt()
        activeThread = null
        activeClient = null
        state = State.IDLE
        callbacks.onFinished()
    }

    private fun finish(
        token: Long,
        original: String? = null,
        result: String? = null,
        error: String? = null,
    ) {
        mainHandler.post {
            if (activeToken != token) return@post
            activeThread = null
            activeClient = null
            state = State.IDLE
            callbacks.onFinished()
            if (original != null && !result.isNullOrEmpty()) {
                callbacks.onResult(original, result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

    private fun sanitize(raw: String): String {
        // Strip control characters (Cc) and format marks (Cf) to protect the host editor.
        val cleaned = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").trim()
        return if (cleaned.length > MAX_OUTPUT_LENGTH) cleaned.substring(0, MAX_OUTPUT_LENGTH) else cleaned
    }

    private fun safeUserFacingError(e: Throwable): String {
        if (e is OpenRouterException) {
            val raw = e.message
            if (!raw.isNullOrBlank()) {
                return raw
                    .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer ***")
                    .replace(Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+"), "$1***")
            }
        }
        return context.getString(R.string.text_fix_error_failed)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        val activeNetwork = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return activeNetwork.isConnected
    }
}
