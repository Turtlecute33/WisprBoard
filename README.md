# TurtleBoard

TurtleBoard is a privacy-conscious and customizable open-source keyboard, forked from [HeliBoard](https://github.com/HeliBorg/HeliBoard) (which is based on AOSP / OpenBoard).

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="80">](https://github.com/Turtlecute33/TurtleBoard/releases/latest)

## What's different from HeliBoard?

TurtleBoard adds the following features on top of HeliBoard:

- **AI Voice-to-Text** — Audio is recorded on-device and sent to [OpenRouter](https://openrouter.ai/) for transcription using **your own API key**. No third party (including TurtleBoard) proxies or sees the audio; the only server involved is OpenRouter's, reached directly over HTTPS. See [Voice privacy](#voice-privacy) below.
- **Settings performance improvements** — Optimized settings screens for smoother navigation and reduced recomposition overhead.
- **Separate application ID** — Can be installed side-by-side with HeliBoard without conflicts.

## Voice privacy

Voice input is opt-in and disabled by default. When you enable it:

- Your OpenRouter API key is stored in `EncryptedSharedPreferences` (AES-256-GCM, key material backed by the Android Keystore on API 23+). It is excluded from both Android cloud backup and device-to-device transfer and is never written to logs.
- Audio is recorded to your app-private cache directory as a temporary WAV file, streamed over HTTPS to `openrouter.ai`, and the local file is deleted once the request completes (success or failure).
- No other network destination is contacted by the voice feature. The app has no analytics, crash reporters, or telemetry.
- OpenRouter's own data handling is governed by their [privacy policy](https://openrouter.ai/privacy) and the settings on the model you pick. Some models log prompts by default; check the model page before enabling voice for sensitive use.
- Translations of voice-related UI strings are not yet available in all locales; non-English users currently see these strings in English.

## Setup

1. Install from [Releases](https://github.com/Turtlecute33/TurtleBoard/releases/latest)
2. Enable TurtleBoard in **Settings > System > Keyboard**
3. For voice input: open **TurtleBoard Settings > Voice Input**, enter your OpenRouter API key, and enable **Voice Input**
4. In the keyboard, long-press the **Return** key and tap the voice button to start recording

## Building from source

```bash
git clone https://github.com/Turtlecute33/TurtleBoard.git
cd TurtleBoard
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

### Requirements

- JDK 17
- Android SDK with API level 35
- Android NDK 28.0.13004108

## Features (inherited from HeliBoard)

- Add dictionaries for suggestions and spell check
- Customize keyboard themes (style, colors, background image)
- Emoji search (inline and separate)
- Customize keyboard [layouts](layouts.md)
- Multilingual typing
- Glide typing (*only with closed source library*)
- Clipboard history
- One-handed mode
- Split keyboard
- Number pad
- Backup and restore settings

For more details, see the [HeliBoard wiki](https://github.com/HeliBorg/HeliBoard/wiki).

## Contributing

Contributions are welcome! Please open an issue or pull request.

## License

TurtleBoard (as a fork of HeliBoard/OpenBoard) is licensed under [GNU General Public License v3.0](/LICENSE).

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is also provided.

## Credits

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) and its contributors
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
