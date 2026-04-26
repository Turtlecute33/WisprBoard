// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Orchestrates text-fix requests to the selected AI provider. Reads the user's selected text via a callback,
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
            imeOptions: Int,
        ): Int? {
            // Sensitive signals first — caller-supplied flags and IME-level hints.
            if (isPasswordField || noLearning || incognitoModeEnabled) {
                return R.string.text_fix_error_sensitive_field
            }
            if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
                return R.string.text_fix_error_sensitive_field
            }
            if ((inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) {
                return R.string.text_fix_error_sensitive_field
            }
            when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> {
                    if (InputTypeUtils.isUriOrEmailType(inputType)) {
                        return R.string.text_fix_error_unsupported_field
                    }
                }
                InputType.TYPE_CLASS_NUMBER -> {
                    // Preventative: today we reject NUMBER as unsupported, but check password
                    // variation first so it registers as sensitive rather than unsupported.
                    if ((inputType and InputType.TYPE_MASK_VARIATION) ==
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                        return R.string.text_fix_error_sensitive_field
                    }
                    return R.string.text_fix_error_unsupported_field
                }
                else -> {
                    // DATETIME / PHONE / etc. — unsupported.
                    return R.string.text_fix_error_unsupported_field
                }
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
        /**
         * Called when the user must acknowledge the "data is sent to the AI provider" consent
         * before the first text-fix request.
         */
        fun requestConsent() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeClient: OpenRouterClient? = null
    @Volatile private var activeThread: Thread? = null
    @Volatile private var activeToken = 0L
    @Volatile private var state = State.IDLE
    @Volatile private var pendingConsentDeadline: Long = 0L
    private val consentWindowMs = 10_000L

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
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        val apiKey = SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }
        // First-use consent: same two-tap window as voice input.
        if (!prefs.getBoolean(Settings.PREF_TEXT_FIX_CONSENT_GIVEN, Defaults.PREF_TEXT_FIX_CONSENT_GIVEN)) {
            val now = System.currentTimeMillis()
            if (pendingConsentDeadline == 0L || now > pendingConsentDeadline) {
                pendingConsentDeadline = now + consentWindowMs
                callbacks.requestConsent()
                return
            }
            pendingConsentDeadline = 0L
            prefs.edit { putBoolean(Settings.PREF_TEXT_FIX_CONSENT_GIVEN, true) }
        }
        if (!isNetworkAvailable(context)) {
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
        val model = resolveProviderModel(selectedModel, customModel)
        if (model == null) {
            Toast.makeText(context, R.string.voice_error_no_model, Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = (prefs.getString(Settings.PREF_TEXT_FIX_PROMPT, Defaults.PREF_TEXT_FIX_PROMPT) ?: Defaults.PREF_TEXT_FIX_PROMPT)
            .trim().ifEmpty { Defaults.PREF_TEXT_FIX_PROMPT }
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)

        state = State.WORKING
        callbacks.onWorking()

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt,
            runtimeInstruction = null,
            provider = provider,
            useZeroDataRetention = useZdr,
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

    private fun sanitize(raw: String): String = sanitizeModelOutput(raw, MAX_OUTPUT_LENGTH)

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
}
