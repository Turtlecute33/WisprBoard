# Voice-to-Text via OpenRouter

## Summary

Add voice-to-text functionality to HeliBoard using OpenRouter's chat completions API with audio-capable models. Users configure an API key and select a model in settings. A voice button in the clipboard toolbar strip starts audio recording; tapping the screen stops recording and sends audio to OpenRouter for transcription, which is then inserted into the active text field.

**Note on INTERNET permission:** HeliBoard is historically an offline-only keyboard. This feature adds the `INTERNET` permission, which is a significant philosophical change. The permission is only used when voice input is explicitly enabled by the user. This should be prominently noted in release notes.

## User Flow

1. **Setup:** User goes to Settings > Toolbar, enables Voice Input, enters OpenRouter API key, optionally selects a model (default: `google/gemini-3-flash-preview`). When enabling voice input, the settings screen prompts for `RECORD_AUDIO` permission (since the IME service cannot request runtime permissions).
2. **Activation:** While typing, user long-presses the return/enter key, swipes to the voice button, and releases. The VOICE toolbar key can also be tapped directly if visible in the toolbar strip. Both trigger `KeyCode.VOICE_INPUT`.
3. **Recording:** The suggestion strip is replaced with a minimal looping dot animation. Audio recording begins via Android's `AudioRecord` API. Maximum recording duration: 60 seconds (auto-stops with a brief vibration).
4. **Stop:** User taps anywhere on the keyboard. Recording stops.
5. **Cancel:** User can swipe down on the recording overlay to cancel without transcribing.
6. **Transcription:** Audio is encoded as base64, sent to OpenRouter's `/api/v1/chat/completions` endpoint. A "Transcribing..." indicator replaces the dot animation.
7. **Insertion:** The transcribed text is committed to the text field via `RichInputConnection.commitText()`. The suggestion strip is restored.

## Components

### 1. Settings (modify existing Toolbar screen)

Add three preferences to the existing Toolbar settings screen (`ToolbarScreen.kt`):

- **Enable Voice Input** (`pref_voice_input_enabled`): Boolean toggle, default `false`. When toggled on, immediately request `RECORD_AUDIO` runtime permission via the settings Activity context.
- **OpenRouter API Key** (`pref_openrouter_api_key`): String, password-masked text field. Stored in SharedPreferences.
- **Voice Model** (`pref_voice_model`): Dropdown with these options:
  - `google/gemini-3-flash-preview` (default)
  - `google/gemini-2.0-flash-001`
  - Custom (free-text field that appears when selected)

Settings keys defined as constants in `Settings.java`.

### 2. Audio Recorder (`AudioRecorder.kt`)

New file: `helium314.keyboard.latin.voice.AudioRecorder`

- Uses Android `AudioRecord` API (not `MediaRecorder` — more control, works without file I/O).
- Records PCM 16-bit mono at 16kHz.
- Encodes to WAV format in-memory (WAV header + PCM data).
- Returns `ByteArray` of the WAV file on stop.
- Lifecycle: `start()` / `stop(): ByteArray` / `cancel()`.
- Runs recording on a background thread via `Thread` (no coroutines dependency needed).
- Maximum duration: 60 seconds (~1.9MB WAV, ~2.5MB base64). Auto-stops when limit reached.

### 3. OpenRouter Client (`OpenRouterClient.kt`)

New file: `helium314.keyboard.latin.voice.OpenRouterClient`

- Uses `java.net.HttpURLConnection` (no external HTTP library — keeps HeliBoard dependency-free).
- Endpoint: `https://openrouter.ai/api/v1/chat/completions`
- Request format:
  ```json
  {
    "model": "<selected_model>",
    "messages": [{
      "role": "user",
      "content": [
        {
          "type": "input_audio",
          "input_audio": {
            "data": "<base64_wav>",
            "format": "wav"
          }
        },
        {
          "type": "text",
          "text": "Transcribe this audio exactly as spoken. Output only the transcription, nothing else."
        }
      ]
    }]
  }
  ```
- Headers: `Authorization: Bearer <api_key>`, `Content-Type: application/json`
- Parses response JSON to extract `choices[0].message.content`.
- Returns transcription text or throws with error message.
- Runs on a background `Thread` (no coroutines — HeliBoard does not include kotlinx-coroutines). Uses `Handler(Looper.getMainLooper())` to post results back to the UI thread.

### 4. Voice Input Manager (`VoiceInputManager.kt`)

New file: `helium314.keyboard.latin.voice.VoiceInputManager`

Orchestrates the full flow:

- Checks prerequisites (voice enabled, API key set, mic permission granted).
- Manages state: `IDLE` / `RECORDING` / `TRANSCRIBING`. All state transitions happen on the main thread via `Handler`.
- On `startRecording()`: if not `IDLE`, ignore (debounce). Checks network connectivity before starting. Starts `AudioRecorder`, notifies UI to show recording animation.
- On `stopRecording()`: stops `AudioRecorder`, transitions to `TRANSCRIBING`, calls `OpenRouterClient`, commits result text.
- On `cancelRecording()`: stops `AudioRecorder`, discards audio, returns to `IDLE`.
- Communicates with `LatinIME` via a callback interface for UI updates and text insertion.

### 5. Recording UI (`RecordingOverlayView.kt`)

New file: `helium314.keyboard.latin.voice.RecordingOverlayView`

A custom `View` displayed over the suggestion strip area:

- **Recording state:** Minimal looping dot animation (3 dots that pulse/fade in sequence). Uses theme colors from `SettingsValues.mColors` for consistency.
- **Transcribing state:** "Transcribing..." text in the same style.
- **Implementation:** Dots animated with standard Android `ValueAnimator`. The view is added/removed from the suggestion strip's parent layout.
- **Stop trigger:** An `OnTouchListener` set on the `KeyboardView` during recording state — any tap calls `VoiceInputManager.stopRecording()`. This avoids modifying `PointerTracker.java` (which is complex and performance-sensitive).
- **Cancel trigger:** Swipe down gesture on the overlay cancels recording.

### 6. Wiring into Existing Code

**`LatinIME.java` — `onEvent()` method (line ~1397):**
- `VOICE_INPUT` is already intercepted in `LatinIME.onEvent()` where it currently calls `mRichImm.switchToShortcutIme(this)`. Replace this with a call to `VoiceInputManager.startRecording()`.
- Hold a `VoiceInputManager` instance, initialized in `onCreate()`.
- Provide callback for text insertion (`mInputLogic.mConnection.commitText()`).
- Provide callback for UI state changes (show/hide recording overlay on the suggestion strip).

**`SuggestionStripView.kt`:**
- Add methods `showRecordingOverlay()` and `hideRecordingOverlay()` that add/remove `RecordingOverlayView` as a child view.

**`ToolbarKey` enum / `ToolbarUtils.kt`:**
- `VOICE` toolbar key already exists and maps to `KeyCode.VOICE_INPUT`. No changes needed.
- Verify VOICE is available in clipboard toolbar key selection (it is in the default list).

## Permissions

Add to `AndroidManifest.xml`:
- `<uses-permission android:name="android.permission.INTERNET" />`
- `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

**RECORD_AUDIO runtime permission:** Requested in the Settings Activity when the user enables voice input. The IME service (`LatinIME`) cannot call `requestPermissions()` since it is not an Activity. If the permission is not granted when voice input is triggered from the keyboard, a toast directs the user to settings.

## Error Handling

| Condition | Behavior |
|-----------|----------|
| Voice disabled in settings | Toast: "Enable Voice Input in settings" |
| No API key | Toast: "Set OpenRouter API key in settings" |
| Mic permission denied | Toast: "Grant microphone permission in settings" |
| No network connectivity | Toast: "No internet connection", don't start recording |
| Network error | Toast with error message, restore suggestion strip |
| API error (4xx/5xx) | Toast with status code, restore suggestion strip |
| Empty transcription | No text inserted, restore suggestion strip silently |
| Max duration reached | Auto-stop, vibrate briefly, proceed to transcription |
| Already recording/transcribing | Ignore new voice input trigger (debounce) |

## File Inventory

| File | Action |
|------|--------|
| `app/src/main/java/helium314/keyboard/latin/voice/AudioRecorder.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/OpenRouterClient.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/voice/RecordingOverlayView.kt` | New |
| `app/src/main/java/helium314/keyboard/latin/settings/Settings.java` | Modify — add pref key constants |
| `app/src/main/java/helium314/keyboard/settings/screens/ToolbarScreen.kt` | Modify — add voice settings UI + permission request |
| `app/src/main/java/helium314/keyboard/latin/LatinIME.java` | Modify — replace switchToShortcutIme with VoiceInputManager call in onEvent() |
| `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt` | Modify — add show/hide recording overlay methods |
| `app/src/main/AndroidManifest.xml` | Modify — add INTERNET and RECORD_AUDIO permissions |

## Security Considerations

- API key stored in SharedPreferences on device-protected storage (same as all other HeliBoard prefs). Not exported or logged. Plaintext storage is a known limitation consistent with the rest of HeliBoard's settings.
- Audio data is transient — held in memory only during recording/sending, never written to disk.
- HTTPS only for API communication.
- No telemetry or analytics added.
- Network calls only happen when the user explicitly triggers voice input with a configured API key.
