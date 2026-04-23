# WisprBoard

An Android keyboard that types what you say.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="70">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

## Why this exists

I was a Wispr Flow user. I wanted dictation that felt like magic and got a bloated desktop app, a mandatory cloud account, and transcripts I didn't own. So I built the thing I actually wanted: a plain Android keyboard with a mic button, pointed at whatever model I pick, using my own key. No account, no middleman, no app to babysit.

WisprBoard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) — a great open-source keyboard that doesn't do voice. This adds voice. That's it.

## WisprBoard vs HeliBoard

|                                      | HeliBoard | WisprBoard |
| ------------------------------------ | :-------: | :--------: |
| Everything HeliBoard does            |     ✅     |     ✅      |
| Installs alongside HeliBoard         |     —     |     ✅      |
| Voice-to-text (bring your own key)   |     —     |     ✅      |
| API key stored encrypted, never logged |    —    |     ✅      |

If you don't want voice, stay on HeliBoard. It's excellent.

## How voice works

Long-press **Return**, tap the mic, speak, release. Audio records locally, uploads over HTTPS to [OpenRouter](https://openrouter.ai/) with your API key, the text lands in the field, and the recording is deleted.

- **Your key, your model.** Pick any OpenRouter speech model. Set a custom prompt or dictionary.
- **No server in between.** WisprBoard talks to OpenRouter directly. There is no WisprBoard backend, no analytics, no telemetry.
- **Key stays local.** Encrypted with the Android Keystore, excluded from cloud backup and device transfer, stripped from in-app backups, never written to logs.
- **Opt-in.** Disabled until you add a key. Mic permission is explained before Android asks for it.

OpenRouter's own logging depends on the model you pick — [check their policy](https://openrouter.ai/privacy) before using voice for anything sensitive.

## Install

1. Grab the APK from [Releases](https://github.com/Turtlecute33/WisprBoard/releases/latest). Each release ships `SHA256SUMS.txt` if you want to verify it.
2. Enable WisprBoard in **Settings → System → Keyboard**.
3. For voice: create a key at [openrouter.ai/keys](https://openrouter.ai/keys), paste it into **WisprBoard Settings → Voice Input**, long-press Return, tap the mic.

WisprBoard uses its own app ID, so it installs next to HeliBoard instead of replacing it.

## Build from source

```bash
git clone https://github.com/Turtlecute33/WisprBoard.git
cd WisprBoard
./gradlew assembleDebug
```

Needs JDK 17, Android SDK 35, NDK `28.0.13004108`. APK lands in `app/build/outputs/apk/debug/`.

## Contributing

Issues and PRs welcome. A few rules inherited from upstream:

- The input path (`InputLogic`, `PointerTracker`, `RichInputConnection`, dictionary facilitator) is fragile. New behavior should be opt-in.
- One purpose per PR.
- No new internet permissions, no proprietary blobs, no telemetry — ever.
- Translations live on Weblate upstream; don't edit them here. New English strings are fine.

## License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

## Credits

Built on the shoulders of [HeliBoard](https://github.com/HeliBorg/HeliBoard), [OpenBoard](https://github.com/openboard-team/openboard), and [AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/). Original icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) and [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic).
