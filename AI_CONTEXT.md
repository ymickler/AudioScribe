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

### A2. Live history rows for running transcriptions
* A history row is created **when a transcription starts**, not only when it finishes (`TranscriptionOverlayService.createRunningHistoryEntry`), so opening the app mid-run shows the item as a live card - independent of overlay vs. notification mode and of local vs. server transcription.
* `TranscriptionEntity.status` is one of `running` / `completed` / `error`, plus `statusText` (the same human-readable label the overlay shows) and `progress`.
* `updateQueueItem` is the single funnel: every status/progress change mirrors into the row via `syncItemToDatabase`, throttled to max one write per second unless the status label itself changed.
* Completion re-inserts the row with the same id (`OnConflictStrategy.REPLACE`), so the running card turns into a normal entry instead of producing a second card. Cancel deletes the row (`QueueItem.cancelled` also covers a cancel that lands before the insert has finished); a killed service is cleaned up by `failStaleRunning` (30 min) on app start.
* DB schema is **version 3** (`MIGRATION_1_2` status columns, `MIGRATION_2_3` timestamp index). There is deliberately **no** `fallbackToDestructiveMigration()` - a forgotten migration must crash in development rather than silently wipe the user's history. Every schema change needs a real `Migration`.

### A3. Cross-cutting conventions (see `OPTIMIZATIONS.md` for the full write-up)
* **All user-visible strings go through `Localization`** - including the ones set inside the service. `Localization.getString(key, lang, vararg args)` fills `{1}`/`{2}` placeholders so counters stay inside the translated sentence.
* **One status derivation**: `statusLabel(item)` and `progressLabel(...)` in the service feed overlay, notification and history card alike. Don't add a fourth private formatting branch.
* **One coroutine scope**: everything the service launches uses `serviceScope`, cancelled in `onDestroy()`. Only writes that must survive shutdown (persisting a finished transcription, telling the server to cancel) are wrapped in `withContext(NonCancellable)`.
* `CryptoHelper.decrypt` memoizes into a bounded LRU - the history Flow re-decrypts the whole list on every emission.
* `ServerTranscriptionEngine` defaults to a process-wide `sharedClient`; per-call timeouts come from `newBuilder()`, which keeps sharing the pool.

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

### B2a. Server progress phases (`ServerTranscriptionEngine.Phase`)
* A server transcription has **two fundamentally different halves**, and the UI must present them differently:
  * `UPLOADING` - known total size, so a **real** percentage. `CountingRequestBody` wraps the multipart body and reports written bytes, mapped onto the `UPLOAD_PROGRESS_START..UPLOAD_PROGRESS_END` band (0.05-0.35). Those two constants are public because the service maps a raw progress value back onto "x% of the upload"; don't duplicate the numbers.
  * `PROCESSING` - a single opaque wait. The ASR webservice reports **nothing** until it is completely done, so there is no progress value that can move. Showing a static percentage here was the original user-visible bug ("stuck at 30% for minutes, looks crashed").
* `transcribe(..., onPhase = ...)` reports the transition. The phase switch fires when the last byte is written, with a fallback right after the request completes (OkHttp may buffer a small body and never stream it, and can call `writeTo` more than once).
* In the service: `QueueItem.serverPhase` + `phaseStartedAtMs`, set only via `setServerPhase()` so the elapsed timer always restarts with the phase it measures. `isIndeterminate(item)` is the single predicate for "no movable progress value"; it drives the elapsed-time label, the notification's indeterminate bar, the overlay bar, **and** (by syncing `progress = 0f`, which `HistoryItemCard` already renders as indeterminate) the history card - deliberately avoiding a Room migration for a pure rendering hint.
* `elapsedTickerJob` is self-terminating: it exits as soon as no item is in an indeterminate phase, which covers completion, error, cancellation and fallback-to-local in one place. Reset `serverPhase = null` whenever falling back to local, or the local run inherits a stale indeterminate display.
* `DurationFormat.formatElapsed` (always `m:ss`) is for **running** transcriptions; `DurationFormat.format` is for finished ones. Don't mix them - a live clock must not change shape at the 60s mark.

### B3. Fixes spanning both this app and `audioscribe-server`
* When a fix requires changes on **both** sides of the `POST /v1/transcribe` / `/v1/cancel` / `/v1/models` API (this repo + the companion `audioscribe-server` repo), **implement and ship the app change first**, then the server change.
* Why: this repo builds via GitHub Actions Release CI (several minutes: Gradle build, APK packaging, release creation) and the user installs the resulting APK - that's the slow, high-latency side. `audioscribe-server` just needs a container rebuild/restart on the homelab host - seconds to a couple of minutes. Sequencing app-first means the slow CI pipeline starts as early as possible instead of waiting behind trivial server work.
* Concretely: commit + push the app fix (kicking off Release CI) before touching `audioscribe-server`'s compose/Dockerfile/source, even if the server-side change is trivial and could be "gotten out of the way" first.

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
