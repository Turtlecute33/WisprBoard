# WisprBoard

An Android keyboard that transcribes your voice and fixes your text. Pick any model on [OpenRouter](https://openrouter.ai/) and point the keyboard at it.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="70">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

## Why this exists

I used Wispr Flow. It was slow, clunky, locked behind an account, and the transcription quality didn't match the price. I wanted a plain Android keyboard with a mic button and a "fix this" button, both wired to OpenRouter with my own key. Pick a better model, get better results. Swap it whenever something new ships. No Wispr-branded cloud. No subscription. No desktop app to babysit.

It isn't local. It isn't private in the offline sense. Your audio and selected text go to OpenRouter, because that's where transcription and rewriting happen. The difference from Wispr Flow: you pick the model, you own the key, and no WisprBoard server sits in between.

WisprBoard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard), which is a great open-source keyboard without voice or text fix. WisprBoard adds those two things.

## WisprBoard vs HeliBoard

|                                         | HeliBoard | WisprBoard |
| --------------------------------------- | :-------: | :--------: |
| Everything HeliBoard does               |     ✅     |     ✅      |
| Installs alongside HeliBoard            |     —     |     ✅      |
| Voice-to-text via OpenRouter            |     —     |     ✅      |
| Text Fix (rewrite selected text)        |     —     |     ✅      |
| API key stored encrypted on-device      |     —     |     ✅      |

If you don't want AI features, stay on HeliBoard.

## Voice

Long-press **Return**, tap the mic, speak, release. Audio uploads over HTTPS to [OpenRouter](https://openrouter.ai/) with your API key. The transcript lands in the field. The local recording gets deleted.

Pick any speech model OpenRouter offers. Set a custom prompt or dictionary. Swap models whenever something better ships.

## Text Fix

Select the text, long-press **Return**, the model rewrites it in place. Same setup as voice: your key, your model, your prompt. The feature is blocked in password fields, and a one-tap confirmation flashes before anything leaves the device.

Use it for typos, clumsy phrasing, wrong tone.

## About privacy

Voice and Text Fix send data to OpenRouter. That's how they work. WisprBoard has no backend, no analytics, no telemetry, but OpenRouter and the model you pick will see your audio and selected text. Their logging depends on the model. [Check their policy](https://openrouter.ai/privacy) before pointing this at sensitive content.

Your API key is encrypted with the Android Keystore, excluded from cloud backup and device transfer, and never written to logs. Both AI features are opt-in and off until you add a key.

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
- No new internet permissions, no proprietary blobs, no telemetry.
- Translations live on Weblate upstream. Don't edit them here. New English strings are fine.

## License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

## Credits

Built on [HeliBoard](https://github.com/HeliBorg/HeliBoard), [OpenBoard](https://github.com/openboard-team/openboard), and [AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/). Original icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) and [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic).
