# AudioScribe 🎙️✨

AudioScribe is a completely free, local-first, and secure audio transcriber for WhatsApp voice messages (and other audio files), with an optional self-hosted server for faster or higher-quality results.

Designed to combine ultimate privacy with seamless integration, AudioScribe lets you read voice messages instantly without playing them out loud.

---

## 🌟 Key Features

* **Local-First Transcription:** Voice message transcription runs fully locally on your Android device by default. Your private messages never leave your phone unless you explicitly opt in below.
* **Optional Self-Hosted Server Transcription:** Point the app at your own server (e.g. over Tailscale/LAN) for faster or more accurate results, with automatic fallback to the local offline engine if it's unreachable. Off by default; since it's your own server, not a third-party service, your data stays private either way.
* **Flexible Display Modes (NEW!):** 
  * *Elegant Floating Overlay:* Display transcription results instantly in a sleek card over your active app (e.g., WhatsApp).
  * *Rich System Notification:* Receive a standard system notification containing the complete text, featuring quick actions to **Copy** or **Share** with a single tap.
* **Preview Sandbox (NEW!):** A built-in simulator card visible exclusively in debug/preview builds to simulate shared WhatsApp audio messages, allowing you to test both display modes and copy flows directly in the AI Studio emulator.
* **Seamless WhatsApp Integration:** Share any received voice message or audio file directly from WhatsApp to AudioScribe and view the transcribed text instantly in an elegant floating overlay.
* **Secure Local History:** Keep track of your past transcriptions in a central, searchable history page.
* **AES-256 GCM Database Encryption:** Optionally secure your locally stored transcription history. Transcribed texts are encrypted using secure cryptographic keys handled by the hardware-backed Android Keystore system.
* **Fully Dynamic UI Language Support:** Toggle between English, German, or your device's System Language dynamically without needing to restart the app.
* **Modern Material 3 Design:** A gorgeous, dark-themed user interface utilizing generous negative spacing, custom visual elements, and fully responsive layouts.

---

## 🛠️ Tech Stack & Architecture

* **Framework:** Jetpack Compose (Kotlin) with strict Material Design 3 guidelines.
* **Database:** Room SQLite database for structured storage.
* **Security:** AES-256 GCM encryption via the Android Keystore.
* **Lifecycle:** Architecture-aware Compose services (`TranscriptionOverlayService`) with manual state-driven overlays.
* **Build System:** Gradle (Kotlin DSL).
* **AI-Optimized Context:** Includes a comprehensive English [AI_CONTEXT.md](./AI_CONTEXT.md) documentation file detailing internal data flows, service endpoints, and modular simulation layers to facilitate instant context-loading for downstream AI agents.

---

## ⚠️ Requirements & Permissions

* **System Overlay Permission (Draw over other apps):** Essential to show the transcribed text instantly on top of WhatsApp when sharing an audio file. The app features a dynamic permission banner that instantly disappears once granted.
* **Internet Permission:** Requested solely to support the optional self-hosted server transcription feature above. If you never enter a server URL and enable it, no network request is ever made and behavior is identical to a fully offline app.

---

## 📜 Licensing

This project is licensed under the **Creative Commons Attribution-NonCommercial 4.0 International License (CC BY-NC 4.0)**.

### Under this license:
* **Share:** You are free to copy and redistribute the material in any medium or format.
* **Adapt:** You are free to remix, transform, and build upon the material.
* **Attribution:** You must give appropriate credit and provide a link to the license.
* **Non-Commercial:** You **MAY NOT** use the material for commercial purposes of any kind.

To view a copy of this license, visit [Creative Commons CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/).

---

*Note: This project is an independent utility and is not affiliated with, authorized, or endorsed by WhatsApp Inc. or Meta Platforms, Inc.*
