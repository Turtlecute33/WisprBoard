# Voice-to-Text via OpenRouter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add voice-to-text to HeliBoard that records audio, sends it to OpenRouter, and inserts the transcription.

**Architecture:** Four new Kotlin files in a `voice/` package (AudioRecorder, OpenRouterClient, VoiceInputManager, RecordingOverlayView) plus modifications to Settings, LatinIME, SuggestionStripView, and AndroidManifest. No new dependencies — uses `AudioRecord`, `HttpURLConnection`, `Thread`/`Handler`.

**Tech Stack:** Kotlin, Android AudioRecord API, HttpURLConnection, Jetpack Compose (settings UI), Android ValueAnimator (recording animation)

**Spec:** `docs/superpowers/specs/2026-04-12-voice-to-text-openrouter-design.md`

---

### Task 1: Add permissions to AndroidManifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:11-15`

- [ ] **Step 1: Add INTERNET and RECORD_AUDIO permissions**

In `app/src/main/AndroidManifest.xml`, add after the existing permissions block (after line 15):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(voice): add INTERNET and RECORD_AUDIO permissions"
```

---

### Task 2: Add settings constants and defaults

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/latin/settings/Settings.java`
- Modify: `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt`

- [ ] **Step 1: Add preference key constants to Settings.java**

Add these constants alongside the existing `public static final String PREF_*` constants in `Settings.java` (around line 82, after the existing constants):

```java
public static final String PREF_VOICE_INPUT_ENABLED = "voice_input_enabled";
public static final String PREF_OPENROUTER_API_KEY = "openrouter_api_key";
public static final String PREF_VOICE_MODEL = "voice_model";
public static final String PREF_VOICE_MODEL_CUSTOM = "voice_model_custom";
```

- [ ] **Step 2: Add default values to Defaults.kt**

Add these constants in `Defaults.kt` alongside existing defaults:

```kotlin
const val PREF_VOICE_INPUT_ENABLED = false
const val PREF_OPENROUTER_API_KEY = ""
const val PREF_VOICE_MODEL = "google/gemini-3-flash-preview"
const val PREF_VOICE_MODEL_CUSTOM = ""
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/settings/Settings.java app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt
git commit -m "feat(voice): add settings constants and defaults for voice input"
```

---

### Task 3: Add string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add string resources for voice settings UI**

Add these strings in `app/src/main/res/values/strings.xml` (in the settings section):

```xml
<string name="voice_input_title">Voice Input</string>
<string name="voice_input_enabled">Enable Voice Input</string>
<string name="voice_input_enabled_summary">Use OpenRouter for voice-to-text transcription</string>
<string name="openrouter_api_key">OpenRouter API Key</string>
<string name="openrouter_api_key_summary">Enter your OpenRouter API key</string>
<string name="voice_model">Voice Model</string>
<string name="voice_model_custom">Custom Model ID</string>
<string name="voice_model_custom_summary">Enter a custom OpenRouter model ID</string>
<string name="voice_recording">Recording…</string>
<string name="voice_transcribing">Transcribing…</string>
<string name="voice_error_no_api_key">Set OpenRouter API key in settings</string>
<string name="voice_error_not_enabled">Enable Voice Input in settings</string>
<string name="voice_error_no_permission">Grant microphone permission in settings</string>
<string name="voice_error_no_network">No internet connection</string>
<string name="voice_error_transcription_failed">Transcription failed</string>
<string name="voice_custom_model">Custom</string>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(voice): add string resources for voice input UI"
```

---

### Task 4: Create AudioRecorder

**Files:**
- Create: `app/src/main/java/helium314/keyboard/latin/voice/AudioRecorder.kt`

- [ ] **Step 1: Create the voice package directory**

```bash
mkdir -p app/src/main/java/helium314/keyboard/latin/voice
```

- [ ] **Step 2: Write AudioRecorder.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import helium314.keyboard.latin.utils.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio from the microphone into an in-memory WAV byte array.
 * Uses AudioRecord for low-level control without writing to disk.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 90_000L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmOutput = ByteArrayOutputStream()
    @Volatile private var isRecording = false

    fun start(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            pcmOutput.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                val startTime = System.currentTimeMillis()
                while (isRecording) {
                    if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                        isRecording = false
                        onMaxDurationReached?.invoke()
                        break
                    }
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        pcmOutput.write(buffer, 0, read)
                    }
                }
            }
            recordingThread?.start()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            false
        }
    }

    fun stop(): ByteArray {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = pcmOutput.toByteArray()
        pcmOutput.reset()
        return createWav(pcmData)
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        pcmOutput.reset()
    }

    var onMaxDurationReached: (() -> Unit)? = null

    private fun createWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8 // sampleRate * channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            // fmt chunk
            put("fmt ".toByteArray())
            putInt(16) // chunk size
            putShort(1) // PCM format
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2) // block align (channels * bitsPerSample / 8)
            putShort(16) // bits per sample
            // data chunk
            put("data".toByteArray())
            putInt(pcmData.size)
        }

        return header.array() + pcmData
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/voice/AudioRecorder.kt
git commit -m "feat(voice): add AudioRecorder with WAV encoding"
```

---

### Task 5: Create OpenRouterClient

**Files:**
- Create: `app/src/main/java/helium314/keyboard/latin/voice/OpenRouterClient.kt`

- [ ] **Step 1: Write OpenRouterClient.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends audio to OpenRouter's chat completions API for transcription.
 * Must be called from a background thread.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    fun transcribe(wavData: ByteArray): String {
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        val audioContent = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply {
                put("data", base64Audio)
                put("format", "wav")
            })
        }

        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", "Transcribe this audio exactly as spoken. Output only the transcription, nothing else.")
        }

        val message = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(audioContent)
                put(textContent)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { put(message) })
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }

        try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "API error $responseCode: $errorBody")
                throw OpenRouterException("API error: $responseCode", responseCode)
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(responseBody)
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            return content
        } finally {
            connection.disconnect()
        }
    }
}

class OpenRouterException(message: String, val statusCode: Int = -1) : Exception(message)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/voice/OpenRouterClient.kt
git commit -m "feat(voice): add OpenRouterClient for transcription API"
```

---

### Task 6: Create RecordingOverlayView

**Files:**
- Create: `app/src/main/java/helium314/keyboard/latin/voice/RecordingOverlayView.kt`

- [ ] **Step 1: Write RecordingOverlayView.kt**

This is the minimal dot animation view that displays in the suggestion strip during recording.

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Minimal recording indicator with pulsing dots animation.
 * Shows in the suggestion strip area during voice recording/transcription.
 */
class RecordingOverlayView(context: Context) : LinearLayout(context) {

    private val dotView: PulsingDotsView
    private val statusText: TextView
    private var animator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        dotView = PulsingDotsView(context)
        dotView.layoutParams = LayoutParams(dp(48), dp(24)).apply {
            marginEnd = dp(8)
        }

        statusText = TextView(context).apply {
            textSize = 14f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        addView(dotView)
        addView(statusText)
    }

    fun setColors(textColor: Int) {
        statusText.setTextColor(textColor)
        dotView.dotColor = textColor
    }

    fun showRecording() {
        statusText.text = context.getString(R.string.voice_recording)
        dotView.visibility = View.VISIBLE
        dotView.startAnimation()
    }

    fun showTranscribing() {
        statusText.text = context.getString(R.string.voice_transcribing)
        dotView.stopAnimation()
        dotView.visibility = View.GONE
    }

    fun stopAnimation() {
        dotView.stopAnimation()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Custom view that draws 3 dots with staggered pulsing alpha animation.
     */
    private class PulsingDotsView(context: Context) : View(context) {

        var dotColor: Int = 0xFF888888.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun startAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    phase = animation.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dotRadius = height / 6f
            val spacing = width / 4f
            val centerY = height / 2f

            for (i in 0..2) {
                val dotPhase = (phase + i * 0.33f) % 1f
                val alpha = (kotlin.math.sin(dotPhase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
                    .coerceIn(0.2f, 1f)
                paint.color = dotColor
                paint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(spacing * (i + 0.5f), centerY, dotRadius, paint)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopAnimation()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/voice/RecordingOverlayView.kt
git commit -m "feat(voice): add RecordingOverlayView with pulsing dots animation"
```

---

### Task 7: Create VoiceInputManager

**Files:**
- Create: `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`

- [ ] **Step 1: Write VoiceInputManager.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Orchestrates voice recording, transcription via OpenRouter, and text insertion.
 * All state transitions happen on the main thread.
 */
class VoiceInputManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "VoiceInputManager"
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    interface Callbacks {
        fun onRecordingStarted()
        fun onTranscribing()
        fun onFinished()
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onMaxDurationReached()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder()
    private var state = State.IDLE

    fun getState() = state

    fun startRecording() {
        if (state != State.IDLE) return

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            Toast.makeText(context, R.string.voice_error_not_enabled, Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = prefs.getString(Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        audioRecorder.onMaxDurationReached = {
            mainHandler.post {
                callbacks.onMaxDurationReached()
                stopRecording()
            }
        }

        if (!audioRecorder.start()) {
            Toast.makeText(context, R.string.voice_error_transcription_failed, Toast.LENGTH_SHORT).show()
            return
        }

        state = State.RECORDING
        callbacks.onRecordingStarted()
    }

    fun stopRecording() {
        if (state != State.RECORDING) return

        val wavData = audioRecorder.stop()
        state = State.TRANSCRIBING
        callbacks.onTranscribing()

        val prefs = context.prefs()
        val apiKey = prefs.getString(Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY) ?: ""
        var model = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
        if (model == "custom") {
            model = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
        }

        val client = OpenRouterClient(apiKey, model)

        Thread {
            try {
                val transcription = client.transcribe(wavData)
                mainHandler.post {
                    state = State.IDLE
                    callbacks.onFinished()
                    if (transcription.isNotBlank()) {
                        callbacks.onTranscriptionResult(transcription)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post {
                    state = State.IDLE
                    callbacks.onFinished()
                    callbacks.onError(e.message ?: context.getString(R.string.voice_error_transcription_failed))
                }
            }
        }.start()
    }

    fun cancelRecording() {
        if (state == State.RECORDING) {
            audioRecorder.cancel()
            state = State.IDLE
            callbacks.onFinished()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt
git commit -m "feat(voice): add VoiceInputManager orchestrating record-transcribe flow"
```

---

### Task 8: Add voice settings UI to ToolbarScreen

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/settings/screens/ToolbarScreen.kt`

- [ ] **Step 1: Add voice settings to ToolbarScreen**

Add these imports at the top of `ToolbarScreen.kt`:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.settings.preferences.TextInputPreference
```

In the `ToolbarScreen` composable function, add voice settings keys to the `items` list (before the closing `)`):

```kotlin
Settings.PREF_VOICE_INPUT_ENABLED,
Settings.PREF_OPENROUTER_API_KEY,
Settings.PREF_VOICE_MODEL,
Settings.PREF_VOICE_MODEL_CUSTOM,
```

In the `createToolbarSettings()` function, add these `Setting` entries at the end of the list (before the closing `)`):

```kotlin
Setting(context, Settings.PREF_VOICE_INPUT_ENABLED, R.string.voice_input_enabled, R.string.voice_input_enabled_summary) { setting ->
    val ctx = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    SwitchPreference(setting, Defaults.PREF_VOICE_INPUT_ENABLED) { enabled ->
        if (enabled && !PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
},
Setting(context, Settings.PREF_OPENROUTER_API_KEY, R.string.openrouter_api_key, R.string.openrouter_api_key_summary) {
    TextInputPreference(it, Defaults.PREF_OPENROUTER_API_KEY, isPassword = true)
},
Setting(context, Settings.PREF_VOICE_MODEL, R.string.voice_model) { setting ->
    val ctx = LocalContext.current
    val items = listOf(
        "google/gemini-3-flash-preview" to "google/gemini-3-flash-preview",
        "google/gemini-2.0-flash-001" to "google/gemini-2.0-flash-001",
        ctx.getString(R.string.voice_custom_model) to "custom",
    )
    ListPreference(setting, items, Defaults.PREF_VOICE_MODEL)
},
Setting(context, Settings.PREF_VOICE_MODEL_CUSTOM, R.string.voice_model_custom, R.string.voice_model_custom_summary) {
    TextInputPreference(it, Defaults.PREF_VOICE_MODEL_CUSTOM)
},
```

**Important:** `TextInputPreference` already exists at `app/src/main/java/helium314/keyboard/settings/preferences/TextInputPreference.kt` with signature `fun TextInputPreference(setting: Setting, default: String, info: String? = null, checkTextValid: (String) -> Boolean = { true })`. It does NOT have an `isPassword` parameter, so we need to add one.

- [ ] **Step 2: Add `isPassword` parameter to existing TextInputPreference**

Modify `app/src/main/java/helium314/keyboard/settings/preferences/TextInputPreference.kt`:

1. Add import: `import androidx.compose.ui.text.input.PasswordVisualTransformation` and `import androidx.compose.ui.text.input.VisualTransformation`
2. Add `isPassword: Boolean = false` parameter to the function signature
3. Change the description in `Preference()` to mask the value when `isPassword` is true:

```kotlin
@Composable
fun TextInputPreference(setting: Setting, default: String, info: String? = null, isPassword: Boolean = false, checkTextValid: (String) -> Boolean = { true }) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    val currentValue = prefs.getString(setting.key, default)?.takeIf { it.isNotEmpty() }
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = if (isPassword && currentValue != null) "••••••••" else currentValue
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                prefs.edit { putString(setting.key, it) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = prefs.getString(setting.key, default) ?: "",
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            checkTextValid = checkTextValid
        )
    }
}
```

Note: The `TextInputDialog` already handles text input; for the password masking we only need to mask the *description* shown on the preference row (not the dialog itself — the user needs to see what they type). The existing `TextInputDialog` does not need changes.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/helium314/keyboard/settings/screens/ToolbarScreen.kt
git add app/src/main/java/helium314/keyboard/settings/preferences/TextInputPreference.kt
git commit -m "feat(voice): add voice input settings UI and password masking for API key"
```

---

### Task 9: Wire VoiceInputManager into LatinIME

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/latin/LatinIME.java:1396-1399`
- Modify: `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt`

- [ ] **Step 1: Add VoiceInputManager field and initialization in LatinIME.java**

Add import at the top of `LatinIME.java`:

```java
import helium314.keyboard.latin.voice.VoiceInputManager;
import helium314.keyboard.latin.voice.RecordingOverlayView;
```

Add field declaration near other member variables (around line 100):

```java
private VoiceInputManager mVoiceInputManager;
```

In `onCreate()` method (find the end of the existing initialization), add:

Note: `mSuggestionStripView` is a direct field on `LatinIME` (line 137). Do NOT use `mKeyboardSwitcher.getSuggestionStripView()` — that method doesn't exist.

```java
mVoiceInputManager = new VoiceInputManager(this, new VoiceInputManager.Callbacks() {
    @Override
    public void onRecordingStarted() {
        if (mSuggestionStripView != null) mSuggestionStripView.showRecordingOverlay();
    }

    @Override
    public void onTranscribing() {
        if (mSuggestionStripView != null) mSuggestionStripView.showTranscribingOverlay();
    }

    @Override
    public void onFinished() {
        if (mSuggestionStripView != null) mSuggestionStripView.hideRecordingOverlay();
    }

    @Override
    public void onTranscriptionResult(@NonNull final String text) {
        mInputLogic.mConnection.commitText(text, 1);
    }

    @Override
    public void onError(@NonNull final String message) {
        // Toast is already shown by VoiceInputManager
    }

    @Override
    public void onMaxDurationReached() {
        final AudioAndHapticFeedbackManager haptics = AudioAndHapticFeedbackManager.getInstance();
        haptics.vibrate(50L);
    }
});
```

- [ ] **Step 2: Replace VOICE_INPUT handling in onEvent()**

In `LatinIME.java`, replace lines 1397-1398:

```java
// OLD:
if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
    mRichImm.switchToShortcutIme(this);
}

// NEW:
if (KeyCode.VOICE_INPUT == event.getKeyCode()) {
    if (mVoiceInputManager != null) {
        if (mVoiceInputManager.getState() == VoiceInputManager.State.RECORDING) {
            mVoiceInputManager.stopRecording();
        } else {
            mVoiceInputManager.startRecording();
        }
    }
    return;
}
```

Note the `return;` — we don't want VOICE_INPUT to be processed as a normal key event.

- [ ] **Step 3: Add recording overlay methods to SuggestionStripView.kt**

Add these methods to `SuggestionStripView.kt`:

```kotlin
private var recordingOverlay: RecordingOverlayView? = null

fun showRecordingOverlay() {
    val overlay = RecordingOverlayView(context)
    overlay.setColors(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
    overlay.showRecording()
    setExternalSuggestionView(overlay, false)
    recordingOverlay = overlay
}

fun showTranscribingOverlay() {
    recordingOverlay?.showTranscribing()
}

fun hideRecordingOverlay() {
    recordingOverlay?.stopAnimation()
    recordingOverlay = null
    // clear() removes all views from suggestionsStrip and resets state
    clear()
    isExternalSuggestionVisible = false
}
```

Add the required import at the top of `SuggestionStripView.kt`:

```kotlin
import helium314.keyboard.latin.voice.RecordingOverlayView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
```

Also add the import for `ColorType` if not already present:

```kotlin
import helium314.keyboard.latin.common.ColorType
```

- [ ] **Step 4: Add tap-to-stop touch handling**

In `SuggestionStripView.kt`, add a touch interceptor. Override `dispatchTouchEvent` or add a method that the keyboard view can call:

```kotlin
fun isRecordingActive(): Boolean = recordingOverlay != null
```

In `LatinIME.java`, in the `onEvent()` method, the VOICE_INPUT toggle already handles start/stop. For tapping anywhere on the keyboard to stop, add in `KeyboardActionListenerImpl.kt` at the start of `onCodeInput()`:

```kotlin
// In KeyboardActionListenerImpl.onCodeInput(), add at the beginning:
// Any key press during recording stops and transcribes (except VOICE_INPUT itself,
// which is handled via onEvent() toggle logic)
if (primaryCode != KeyCode.VOICE_INPUT && latinIME.isVoiceRecording()) {
    latinIME.stopVoiceRecording()
    return
}
```

Add the import at the top of `KeyboardActionListenerImpl.kt`:
```kotlin
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
```

And add these helper methods to `LatinIME.java`:

```java
public boolean isVoiceRecording() {
    return mVoiceInputManager != null
        && mVoiceInputManager.getState() == VoiceInputManager.State.RECORDING;
}

public void stopVoiceRecording() {
    if (mVoiceInputManager != null) {
        mVoiceInputManager.stopRecording();
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/helium314/keyboard/latin/LatinIME.java
git add app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt
git add app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt
git commit -m "feat(voice): wire VoiceInputManager into LatinIME and keyboard"
```

---

### Task 10: Verify the VOICE toolbar key is accessible

**Files:**
- Read: `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`

- [ ] **Step 1: Verify VOICE is in the default toolbar and clipboard toolbar**

Check `ToolbarUtils.kt` for `defaultToolbarPref` and `defaultClipboardToolbarPref`. The `VOICE` key should already be listed. If not, add it. Typically `VOICE` is in the default toolbar keys (`defaultToolbarPref`).

Also verify that the voice icon drawable exists. Search for `sym_keyboard_voice` in the drawables directory. HeliBoard already has voice icons — confirm they're properly referenced in `KeyboardIconsSet`.

- [ ] **Step 2: Commit if changes needed**

```bash
git add -A && git commit -m "feat(voice): ensure VOICE toolbar key is accessible"
```

Only commit if changes were made.

---

### Task 11: Build and verify

- [ ] **Step 1: Build the project**

```bash
cd /Users/user/Documents/Github/HeliBoard
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

Address any import issues, missing methods, type mismatches, etc. This is expected — the plan provides the structure but exact API signatures may need adjustment based on the actual codebase.

- [ ] **Step 3: Commit any build fixes**

```bash
git add -A && git commit -m "fix(voice): resolve build errors"
```

---

### Task 12: Manual testing checklist

- [ ] **Step 1: Install on device/emulator**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Test settings**

1. Open HeliBoard settings > Toolbar
2. Verify "Enable Voice Input" toggle appears
3. Enable it → should prompt for microphone permission
4. Verify API key field appears and masks input
5. Verify model dropdown works with Gemini 3 Flash as default
6. Verify custom model field appears when "Custom" is selected

- [ ] **Step 3: Test voice recording flow**

1. Open any text field
2. Long-press return → swipe to voice button → release
3. Verify pulsing dots animation appears in suggestion strip
4. Tap to stop → verify "Transcribing..." appears
5. Verify transcribed text is inserted

- [ ] **Step 4: Test error cases**

1. Try voice input with no API key set → expect toast
2. Try voice input with voice disabled → expect toast
3. Try with airplane mode → expect "No internet" toast

- [ ] **Step 5: Final commit**

```bash
git add -A && git commit -m "feat(voice): complete voice-to-text via OpenRouter implementation"
```
