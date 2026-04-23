# WisprBoard

An Android keyboard that transcribes your voice and fixes your text — powered by whatever model you point it at on [OpenRouter](https://openrouter.ai/).

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="70">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

## Why this exists

I was a Wispr Flow user. It was slow, clunky, locked behind an account, and I didn't like the transcription quality for what they charged. So I built the thing I actually wanted: a plain Android keyboard with a mic button and a "fix this" button, both wired straight to OpenRouter with my own key. Pick a better model, get better results. Swap it whenever something new ships. No Wispr-branded cloud, no subscription, no desktop app to babysit.

It's not local. It's not hyper-private. Your audio and selected text go to OpenRouter — that's how transcription and rewriting happen. What's different from Wispr Flow is that **you** pick the model, **you** own the key, and there's no WisprBoard server sitting in the middle skimming anything.

WisprBoard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) — a great open-source keyboard that doesn't do voice or text fix. This adds both. That's it.

## WisprBoard vs HeliBoard

|                                      | HeliBoard | WisprBoard |
| ------------------------------------ | :-------: | :--------: |
| Everything HeliBoard does            |     ✅     |     ✅      |
| Installs alongside HeliBoard         |     —     |     ✅      |
| Voice-to-text via OpenRouter         |     —     |     ✅      |
| Text Fix — rewrite selected text via OpenRouter | — |   ✅      |
| API key stored encrypted on-device   |     —     |     ✅      |

If you don't want AI features, stay on HeliBoard. It's excellent.

## Voice

Long-press **Return**, tap the mic, speak, release. Audio uploads over HTTPS to [OpenRouter](https://openrouter.ai/) with your API key, the transcript lands in the field, the local recording is deleted.

Pick any speech model OpenRouter offers. Set a custom prompt or dictionary. Swap models whenever something better ships.

## Text Fix

Typos, clumsy phrasing, wrong tone — select the text, long-press **Return**, the model rewrites it in place. Same setup: your key, your model, your prompt. Blocked in password fields, and a one-tap confirmation flashes before anything is sent.

## About privacy

Voice and Text Fix send data to OpenRouter. That's how they work. WisprBoard itself has no backend, no analytics, no telemetry — but OpenRouter (and the model you pick) will see your audio and selected text, and their logging depends on the model. [Check their policy](https://openrouter.ai/privacy) before pointing this at sensitive content.

Your API key is encrypted with the Android Keystore, excluded from cloud backup and device transfer, and never written to logs. Both features are opt-in and off until you add a key.

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
