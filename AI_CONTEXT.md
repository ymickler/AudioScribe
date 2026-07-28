# AudioScribe: AI Developer & Architecture Context

This document provides a highly structured and detailed architectural overview of AudioScribe to help downstream AI coding assistants understand the codebase instantly and perform safe, modular enhancements.

---

## 🏗️ 1. Architecture Overview

AudioScribe is designed with a highly modular, clean **MVVM (Model-View-ViewModel)** architectural pattern. It is **local-first by default**, with an optional self-hosted server transcription path (see section 2.B) that always falls back to the on-device engine.

```
┌────────────────────────────────────────────────────────┐
│                      MainActivity                      │
│                  (Compose View Layer)                  │
└───────────┬────────────────────────────────┬───────────┘
            │ Observes                       │ Controls
            ▼                                ▼
┌────────────────────────────────────────────────────────┐
│                     MainViewModel                      │
│                 (State Management/Flows)               │
└───────────────────────────┬────────────────────────────┘
                            │ Interfaces
                            ▼
┌────────────────────────────────────────────────────────┐
│               TranscriptionRepository                  │
│                (Data Access Abstraction)               │
└───────────┬────────────────────────────────┬───────────┘
            │ Reads/Writes                   │ Reads/Writes
            ▼                                ▼
┌───────────────────────────────┐┌───────────────────────┐
│         AppDatabase           ││    SettingsManager    │
│       (Room / SQLite)         ││  (SharedPreferences)  │
└───────────────────────────────┘└───────────────────────┘
```

---

## 🗄️ 2. Core Modules & Data Handling

### A. Data Persistence & Cryptography
* **Room Database:** Holds local transcription records in the `transcriptions` table.
  * File paths: `data/AppDatabase.kt`, `data/TranscriptionDao.kt`, `data/TranscriptionEntity.kt`, `data/TranscriptionRepository.kt`.
  * **On-Device AES-256 GCM Encryption:** If `SettingsManager.isEncryptionEnabled` is true, transcribed texts are encrypted on the fly before being committed to the database. It uses the `CryptoHelper` (`data/CryptoHelper.kt`) bound to the hardware-backed **Android Keystore System** to secure data seamlessly.

### B. Speech-to-Text Engine
* **LocalTranscriptionEngine (`engine/LocalTranscriptionEngine.kt`):**
  * Orchestrates every transcription: optionally attempts the server path first (below), then always falls back to on-device whisper.cpp/Vosk processing.
  * Simulates real-time transcription progress (`onProgress`) and streams word tokens (`onPartialResult`) to mimic Whisper or native STT offline models realistically in testing.
  * Detects simulation requests dynamically when receiving a URI formatted as `mock://audio/...` (this path never attempts server transcription - it's a pure UI/local-flow test fixture).

### B2. Optional Server Transcription (`engine/ServerTranscriptionEngine.kt`)
* Talks to a self-hosted companion server (see the separate `audioscribe-server` project) over `GET /health`, `GET /v1/models`, `POST /v1/transcribe`.
* Only attempted when `SettingsManager.preferServerTranscription` is true AND `LocalTranscriptionEngine.transcribeAudio`'s `modelOverride` is null (an explicit local model switch from the overlay always bypasses the server).
* Flow: health check (short timeout) -> if reachable, transcribe (long timeout, retried once on failure) -> on any failure (unreachable, or failed even after retry), falls back to the local engine and reports *why* via `TranscriptionCallback.onServerFallback(reason)` (`"unreachable"` or `"error"`), surfaced as a distinct badge/notification text rather than looking like an ordinary local run.
* All network calls run under `Dispatchers.IO` internally (not the caller's dispatcher) - `NetworkOnMainThreadException` was a real regression here once, worth being careful about if this file is touched again.

### C. SharedPreferences (`data/SettingsManager.kt`)
Holds user selections dynamically:
* `language` (Transcription target language: English, German, or System default).
* `uiLanguage` (Application interface language).
* `isEncryptionEnabled` (Enforces local storage encryption).
* `showAsNotification` (Boolean toggle: True = Display transcription as status notification; False = Floating overlay window).
* `serverUrl`, `preferServerTranscription`, `serverModel` (optional server transcription settings, see B2).

---

## 📱 3. Interaction Flows: Overlay vs. Notification Mode

When an audio file is shared to AudioScribe via WhatsApp:
1. `ShareActivity.kt` intercepts the shared file.
2. It fetches `SettingsManager.showAsNotification` to check the user's preferred layout.
3. If **Overlay Mode** is active, it checks for system overlay permissions (`Settings.canDrawOverlays`) and launches `TranscriptionOverlayService.kt`.
4. If **Notification Mode** is active, it bypasses the system overlay check entirely and launches `TranscriptionOverlayService.kt` in background mode.

### How `TranscriptionOverlayService` processes the modes:
* **Overlay Mode (Default):** Draws a floating interactive Card window nicely positioned below the status bar (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`). Allows instant scrolling, copying, sharing, or dismissing of results right on top of WhatsApp.
* **Notification Mode:**
  * Displays a small, low-priority foreground status notification: `"Transcription started..."` / `"Transkription gestartet..."`.
  * On success, registers a secure `CopyReceiver` (`service/CopyReceiver.kt`) and posts a rich, expandable status bar notification containing:
    1. The fully transcribed text.
    2. A **Copy** action button (broadcasts to `CopyReceiver` to copy to clipboard with a toast).
    3. A **Share** action button (launches the Android system share sheet chooser directly).
    4. A clickable body (opens `MainActivity` focused on the newly transcribed item).

---

## 🧪 4. Preview Sandbox (Testing inside AI Studio)

Since browser-based Android streaming emulators cannot naturally share media files from third-party apps, AudioScribe features a **Preview Sandbox** panel:
* **Trigger:** Visible only in debug builds (`BuildConfig.DEBUG`).
* **Implementation:** Standard item card injected gracefully at the top of the `History` screen's unified `LazyColumn` in `MainActivity.kt`.
* **Action:** Clicking `"Simulate Shared Audio" / "Geteilte Sprachnachricht simulieren"` dispatches an intent containing a `mock://` Uri. This starts the complete background lifecycle of `TranscriptionOverlayService`, allowing the developer to test either the **Overlay HUD** or the **Expandable System Notifications** dynamically in real time.

---

## 🌐 5. Localization (`data/Localization.kt`)

AudioScribe utilizes an on-the-fly custom translation helper to prevent screen recreation/flickering issues. Add any new translation keys directly to the `translations` map inside `Localization.kt` to ensure uniform German and English updates.

---

## 🛠️ 6. Toolchain & Build Automation
* **Toolchain Versions:**
  * **Gradle Wrapper:** `9.6.1`
  * **Kotlin:** `2.3.0` (integrated Compose Compiler plugin)
  * **Android Gradle Plugin (AGP):** `9.2.0`
  * **Kotlin Symbol Processing (KSP):** `2.3.9`
  * **JDK Version:** `21` (used in CI workflow / local execution)
* **Self-Healing debug.keystore:**
  * Since `debug.keystore` is gitignored, the app defines a custom Gradle task `generateDebugKeystore` of type `Exec` inside `app/build.gradle.kts`.
  * If the keystore file is missing locally or on the CI runner, Gradle automatically runs `keytool` to generate a valid debug keystore in the root directory before running the build. This avoids missing keystore compilation errors.

---

## 🚀 7. CI/CD & Auto-Versioning
* **GitHub Actions Release CI (`release.yml`):**
  * Triggered automatically on push to the `main` branch or manually via `workflow_dispatch`.
  * **Path Filtering (No Release for Docs/License):** Push events will *only* trigger a release if source code or build configuration files are modified. Changes restricted solely to documentation (`**/*.md`) or the `LICENSE` file are explicitly ignored via negative path patterns to prevent unnecessary release runs.
  * **Conventional Commits Auto-Versioning:** On automatic push to `main` (usually when a pull request is merged), the workflow parses the commit message of the merged commit to automatically determine the version increment:
    * **Major Bump (`vA.0.0`):** Triggered if the commit message contains `BREAKING CHANGE`, `breaking:`, or an exclamation mark suffix (e.g. `feat!:`).
    * **Minor Bump (`vX.B.0`):** Triggered if the commit message starts with `feat:` or `feat(`.
    * **Patch Bump (`vX.Y.C`):** Default fallback for fixes (`fix:`), refactoring (`refactor:`), chores (`chore:`), etc.
* **GitHub Actions Preview CI (`preview.yml`):**
  * Triggered automatically on pull requests (`opened`, `synchronize`, `labeled`).
  * **Label Filter:** Executes only if the pull request is labeled with **`build-preview`** (created via GitHub CLI or UI).
  * **Dynamic PR Versioning:** Injects a temporary version name (`[BaseVersion]-pr[PR_Number]-[Short_SHA]`) into Gradle via `-PversionName` so that the generated app binary contains metadata referencing the exact Pull Request and commit source.
  * **Artifact Upload:** Renames and uploads the preview APK as a workflow artifact, accessible directly from the Actions run summary page.
