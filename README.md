<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="WisprBoard" width="140" />

# WisprBoard

### A keyboard with two superpowers, built for people who care about privacy.

An open-source Android keyboard with **AI Voice-to-Text** and **AI Text Fix**. Both run through your own [OpenRouter](https://openrouter.ai/) key, with **zero data retention on by default**.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="64">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

</div>

<br>

## Why I built this

I tried [Wispr Flow](https://wisprflow.ai/), the dictation app a lot of people rave about on desktop. The Android version is rough: slow, account-locked, opaque about where your audio goes. I wanted the same idea (talk, get clean text) without handing my voice to one company's pipeline.

So I forked the best open-source Android keyboard and added two AI buttons. You bring an OpenRouter key. You pick the speech model (Google's Gemini speech, Whisper, anything else OpenRouter routes to). WisprBoard asks for zero-data-retention endpoints by default, so nothing about you is logged or trained on.

No subscription. No account. No telemetry. Just a keyboard.

<br>

## 🎙️ Feature 1 — Voice-to-Text

**For when you'd rather talk than type, but still want to send text.**

Your co-worker pings you mid-meeting. You're walking down the street. You're somewhere a voice note would be rude or out of place. Long-press Return, tap the mic, speak. Your words land in the chat as polished text. The recipient gets a normal message. You didn't thumb-type a paragraph.

- Pick any speech model OpenRouter offers (Whisper, Gemini, etc.)
- Add a custom prompt or vocabulary so it nails names and jargon
- The local recording is deleted the moment it's sent

<br>

## ✍️ Feature 2 — AI Text Fix

**For when you're writing in a language that isn't yours, or just typed too fast.**

You're chatting in English, Italian, German, whatever isn't your strongest. You hammered out a rough draft. Select it, long-press Return, hit Fix. It comes back as a clean, well-formatted message in the same language. Typos gone. Grammar tidy. Tone intact.

- Works in every language your model supports
- Good for typos, clumsy phrasing, or shifting tone (formal ↔ casual)
- Blocked in password fields, with a confirmation tap before anything leaves your device

<br>

## 🔒 Both features, one promise: Zero Data Retention

Voice and Text Fix both go through OpenRouter, and WisprBoard asks OpenRouter for **zero-data-retention endpoints by default**. When your model offers a [ZDR route](https://openrouter.ai/docs/use-cases/zero-data-retention), your audio and text aren't logged, stored, or used for training. If a model doesn't offer one, WisprBoard tells you and falls back so things still work. You can turn the strict setting off if you'd rather.

Your OpenRouter key is encrypted with the Android Keystore, excluded from cloud backups, and never written to logs. AI is opt-in: both features stay off until you paste a key in.

<br>

## 🆚 WisprBoard vs HeliBoard

|                                         | HeliBoard | WisprBoard |
| --------------------------------------- | :-------: | :--------: |
| Everything HeliBoard does               |     ✅     |     ✅      |
| Installs alongside HeliBoard            |     —     |     ✅      |
| Voice-to-text via OpenRouter            |     —     |     ✅      |
| Text Fix (rewrite selected text)        |     —     |     ✅      |
| Zero Data Retention enforced by default |     —     |     ✅      |
| API key encrypted on-device             |     —     |     ✅      |

If you don't want AI features, stay on HeliBoard. It's wonderful as-is.

<br>

## 🛡️ The honest privacy footnote

WisprBoard has no backend, no analytics, no tracking. Once your audio or selected text reaches OpenRouter and the model provider, their policies apply. [Read OpenRouter's policy](https://openrouter.ai/privacy) before pointing this at anything sensitive.

You also get everything HeliBoard already does (multilingual layouts, glide typing, suggestions, themes), built on the excellent [HeliBoard](https://github.com/Helium314/HeliBoard).

<br>

## 🚀 Get started

1. **Download** the APK from [Releases](https://github.com/Turtlecute33/WisprBoard/releases/latest).
2. **Enable** WisprBoard in *Settings → System → Keyboards*.
3. **Add a key** from [openrouter.ai/keys](https://openrouter.ai/keys) into *WisprBoard Settings → Voice Input*.
4. **Long-press Return**, tap the mic, start talking.

WisprBoard installs side-by-side with HeliBoard, so you can keep both.

<br>

## 🛠️ Build from source

```bash
git clone https://github.com/Turtlecute33/WisprBoard.git
cd WisprBoard
./gradlew assembleDebug
```

Needs JDK 17, Android SDK 35, NDK `28.0.13004108`. APK lands in `app/build/outputs/apk/debug/`.

<br>

## 💛 Open source, top to bottom

WisprBoard stands on the shoulders of giants:

- [**HeliBoard**](https://github.com/Helium314/HeliBoard), the keyboard this fork is built on.
- [**OpenBoard**](https://github.com/openboard-team/openboard) and [**AOSP LatinIME**](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/), the foundation of both.
- Original icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) and [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic).

Issues and PRs welcome. A few rules inherited from upstream:

- The input path is fragile and perf-sensitive. New behavior should be opt-in.
- One purpose per PR.
- No new internet permissions, no proprietary blobs, no telemetry.
- Translations live on Weblate upstream. Please don't edit them here.

<br>

## 📜 License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

<div align="center">
<br>
<sub>Made for everyone who writes in more than one language.</sub>
</div>
