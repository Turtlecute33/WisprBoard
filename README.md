# WisprBoard

WisprBoard is a privacy-conscious and customizable open-source keyboard, forked from [HeliBoard](https://github.com/HeliBorg/HeliBoard) (which is based on AOSP / OpenBoard).

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="80">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

## Why WisprBoard?

WisprBoard keeps everything HeliBoard does well and layers on a voice-input pipeline, tighter security defaults, and smoother settings. In short:

|                                | HeliBoard | WisprBoard |
| ------------------------------ | :-------: | :---------: |
| AOSP / OpenBoard core          |     ✅     |      ✅      |
| Multilingual typing, glide, themes, dictionaries |     ✅     |      ✅      |
| Installable side-by-side       |     —     |      ✅      |
| AI voice-to-text (BYO key)     |     —     |      ✅      |
| Encrypted API key storage      |    n/a    |      ✅      |
| Explicit backup / D2D exclusion for secrets |    n/a    |      ✅      |
| Streaming audio upload (bounded heap) |    n/a    |      ✅      |
| Permission rationale before mic prompt |    n/a    |      ✅      |
| Live amplitude meter during recording |    n/a    |      ✅      |
| Haptic feedback on record / stop |    n/a    |      ✅      |
| Settings search filter memoized |     —     |      ✅      |
| Preference schema-drift recovery |     —     |      ✅      |
| Signed releases with SHA-256 sums |     —     |      ✅      |

### Headline changes

- **AI Voice-to-Text you control.** Long-press Return → tap mic. Audio records to an app-private cache file, streams over HTTPS to [OpenRouter](https://openrouter.ai/) using **your** API key, and is deleted the moment the request ends. No WisprBoard server sits in the middle, and there is no fallback endpoint. Model, prompt, and optional dictionary are all yours to configure.
- **Security-first voice plumbing.** Your OpenRouter key lives in `EncryptedSharedPreferences` (AES-256-GCM, Keystore-backed on API 23+), is excluded from cloud backup and device-to-device transfer via `dataExtractionRules`, is filtered out of the in-app backup ZIP as defense-in-depth, and is never written to logs. API error surfaces are scrubbed of bearer-token / api-key fragments before they reach a Toast.
- **Bounded memory.** Audio is streamed to disk as it's recorded and sent to the server chunk-by-chunk via chunked-transfer base64. Peak heap usage no longer scales with recording length — a 5-minute recording holds the same memory as a 5-second one. Responses are capped at 1 MB before JSON parsing so a pathological reply can't exhaust the heap.
- **Smoother settings.** The search screen memoizes its filter by query string instead of re-scanning every frame. Preference reads are wrapped so a type mismatch after a schema change repairs the stored value and keeps the UI alive instead of crashing the settings activity.
- **Hardened release pipeline.** Workflow is idempotent (re-runs on the same tag don't create duplicate releases), least-privileged (`contents: read` at job level, `write` only for the release step), and publishes a `SHA256SUMS.txt` alongside each APK for integrity verification.
- **Polished voice UX.** Live amplitude meter driven by mic input, elapsed-time counter, cancel + stop as separate buttons, haptic ticks on start / stop, theme-aware overlay colors, and an in-app microphone rationale that explains the data flow before the system permission prompt fires.
- **Side-by-side install.** WisprBoard uses its own application ID (`helium314.keyboard.wisprboard`) so it sits next to HeliBoard instead of replacing it — easy to try, easy to back out.

## Voice privacy

Voice input is opt-in and disabled by default. When you enable it:

- Your OpenRouter API key is stored in `EncryptedSharedPreferences` (AES-256-GCM, key material backed by the Android Keystore on API 23+). It is excluded from both Android cloud backup and device-to-device transfer, is filtered out of WisprBoard's own backup/restore ZIP, and is never written to logs.
- Audio is recorded to your app-private cache directory as a temporary WAV file, streamed over HTTPS to `openrouter.ai`, and the local file is deleted once the request completes (success or failure).
- No other network destination is contacted by the voice feature. The app has no analytics, crash reporters, or telemetry.
- OpenRouter's own data handling is governed by their [privacy policy](https://openrouter.ai/privacy) and the settings on the model you pick. Some models log prompts by default; check the model page before enabling voice for sensitive use.
- Translations of voice-related UI strings are not yet available in all locales; non-English users currently see these strings in English.

## Setup

1. Install from [Releases](https://github.com/Turtlecute33/WisprBoard/releases/latest). Each release ships `SHA256SUMS.txt` — run `sha256sum -c SHA256SUMS.txt` to verify the APK before installing.
2. Enable WisprBoard in **Settings > System > Keyboard**.
3. (Optional) For voice input:
   1. Create an API key at [openrouter.ai/keys](https://openrouter.ai/keys).
   2. Open **WisprBoard Settings > Voice Input**, paste the key, and enable **Voice Input**. You'll get a microphone-access rationale dialog before the system permission prompt.
   3. In the keyboard, long-press the **Return** key and tap the voice button to start recording.

## Building from source

```bash
git clone https://github.com/Turtlecute33/WisprBoard.git
cd WisprBoard
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

### Requirements

- JDK 17
- Android SDK with API level 35
- Android NDK 28.0.13004108

## Features (inherited from HeliBoard)

All of HeliBoard's functionality is preserved unchanged — WisprBoard does not rip anything out:

- Add dictionaries for suggestions and spell check
- Customize keyboard themes (style, colors, background image)
- Emoji search (inline and separate)
- Customize keyboard [layouts](layouts.md)
- Multilingual typing
- Glide typing (*only with closed source library*)
- Clipboard history
- One-handed mode, split keyboard, number pad
- Backup and restore settings (with sensitive keys filtered out on WisprBoard)

For details on the underlying keyboard, see the [HeliBoard wiki](https://github.com/HeliBorg/HeliBoard/wiki).

## Contributing

Contributions are welcome — please open an issue or PR. A few ground rules borrowed from upstream:

- The core input path (`InputLogic`, `PointerTracker`, `RichInputConnection`, dictionary facilitator) is fragile and performance-sensitive. New behavior here should be optional and gated.
- Keep PRs single-purpose; avoid sprinkling unrelated changes across many files.
- No new internet permissions or proprietary blobs. No analytics, crash reporters, or telemetry.
- Translations are handled upstream via Weblate — don't edit translated strings in-tree. Adding new English strings in `values/strings.xml` is fine.

## License

WisprBoard (as a fork of HeliBoard/OpenBoard) is licensed under [GNU General Public License v3.0](/LICENSE).

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is also provided.

## Credits

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) and its contributors
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- Original HeliBoard icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
