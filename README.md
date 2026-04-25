<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="WisprBoard" width="140" />

# WisprBoard

### A keyboard with two superpowers, built for people who care about privacy.

An open-source Android keyboard with **AI Voice-to-Text** and **AI Text Fix** — both powered by your own [OpenRouter](https://openrouter.ai/) key, with **zero data retention on by default**.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="64">](https://github.com/Turtlecute33/WisprBoard/releases/latest)

</div>

<br>

## Why WisprBoard?

I love open source. I don't love AI keyboards that quietly ship every word I type to someone else's server. So I forked the best open-source Android keyboard and added the two AI features I actually wanted — done the way I'd want them done: my key, my model, no middleman, nothing logged by default.

No subscription. No account. No telemetry. Just a keyboard.

<br>

## 🎙️ Feature 1 — Voice-to-Text

**For when you'd rather talk than type — but still want to send text.**

You're in a meeting and a co-worker pings you. You're walking down the street. You're somewhere a voice note would feel rude or out of place. Long-press Return, tap the mic, speak — your words land in the chat as polished text. The recipient gets a normal message; you didn't have to thumb-type a paragraph.

- Pick any speech model OpenRouter offers (Whisper, Gemini, etc.)
- Add a custom prompt or vocabulary so it nails names and jargon
- Audio is deleted from the device the moment it's sent

<br>

## ✍️ Feature 2 — AI Text Fix

**For when you're writing in a language that isn't yours — or just typed too fast.**

You're chatting with someone in English, Italian, German, whatever isn't your strongest. You hammered out a rough draft. Select it, long-press Return, hit Fix — it comes back as a clean, well-formatted message in the same language. Typos gone. Grammar tidy. Tone intact.

- Works in every language your model supports
- Use it for typos, clumsy phrasing, or shifting tone (formal ↔ casual)
- Blocked in password fields; flashes a confirmation before anything leaves your device

<br>

## 🔒 Both features, one promise: Zero Data Retention

Voice and Text Fix both run through OpenRouter — and WisprBoard asks OpenRouter for **zero-data-retention endpoints by default**. When your model offers a [ZDR route](https://openrouter.ai/docs/use-cases/zero-data-retention), your audio and text aren't logged, stored, or used for training. If a model doesn't have one, WisprBoard tells you and falls back so things still work — and you can turn the strict setting off if you'd rather.

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

If you don't want AI features, stay on HeliBoard — it's wonderful as-is.

<br>

## 🛡️ The honest privacy footnote

WisprBoard has no backend, no analytics, no tracking. But once your audio or selected text reaches OpenRouter and the model provider, their policies apply. [Read OpenRouter's policy](https://openrouter.ai/privacy) before pointing this at anything sensitive.

Plus, of course: **everything HeliBoard already does** — multilingual layouts, glide typing, suggestions, themes — built on the excellent [HeliBoard](https://github.com/Helium314/HeliBoard). If you don't want AI features, stay there; it's wonderful as-is.

<br>

## 🚀 Get started

1. **Download** the APK from [Releases](https://github.com/Turtlecute33/WisprBoard/releases/latest).
2. **Enable** WisprBoard in *Settings → System → Keyboards*.
3. **Add a key** from [openrouter.ai/keys](https://openrouter.ai/keys) into *WisprBoard Settings → Voice Input*.
4. **Long-press Return** → tap the mic → start talking.

That's it. WisprBoard installs side-by-side with HeliBoard, so you can keep both.

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

- [**HeliBoard**](https://github.com/Helium314/HeliBoard) — the keyboard this fork is built on.
- [**OpenBoard**](https://github.com/openboard-team/openboard) and [**AOSP LatinIME**](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/) — the foundation of both.
- Original icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) and [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic).

Issues and PRs are welcome. A few rules inherited from upstream:

- The input path is fragile and perf-sensitive. New behavior should be opt-in.
- One purpose per PR.
- No new internet permissions, no proprietary blobs, no telemetry.
- Translations live on Weblate upstream — please don't edit them here.

<br>

## 📜 License

[GPL v3](/LICENSE). AOSP-derived portions are also available under [Apache 2.0](LICENSE-Apache-2.0).

<div align="center">
<br>
<sub>Made with care for everyone who writes in more than one language.</sub>
</div>
