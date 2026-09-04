# VozLocal 🎙️⚡

**100% On-Device Local Speech Dictation & Audio Transcription for Android**

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Engine](https://img.shields.io/badge/Engine-whisper.cpp-orange.svg?style=flat)](https://github.com/ggerganov/whisper.cpp)
[![Cleanup](https://img.shields.io/badge/Text%20Cleanup-Local%20Rule--Based-red.svg?style=flat)](#architecture--tech-stack)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-brightgreen.svg?style=flat)](#privacy--security)
[![Privacy Policy](https://img.shields.io/badge/Privacy%20Policy-Read%20Policy-blue.svg?style=flat)](PRIVACY_POLICY.md)

**VozLocal** is a privacy-first Android application for on-device speech-to-text dictation and audio file transcription. It runs using quantized OpenAI Whisper GGUF/bin models via `whisper.cpp` and local rule-based text cleanup — **no cloud APIs, no telemetry, no third-party SDKs that phone home**. Network access is used only to download model files. A global floating accessibility overlay lets you dictate into any app on the device.

---

## 📱 Application Screenshots

| Live Dictate | Speech Models | Shared Audio File |
| :---: | :---: | :---: |
| <img src="docs/images/screen_dictate.png" width="280"/> | <img src="docs/images/screen_models.png" width="280"/> | <img src="docs/images/screen_shared.png" width="280"/> |

| Transcription History | Navigation Drawer | Settings Sheet |
| :---: | :---: | :---: |
| <img src="docs/images/screen_history.png" width="280"/> | <img src="docs/images/screen_drawer.png" width="280"/> | <img src="docs/images/screen_settings.png" width="280"/> |

---

## ✨ Key Features

- 🎙️ **On-Device Speech Recognition** — Quantized OpenAI Whisper models run locally on Android hardware via JNI C++ bindings (`whisper.cpp`). ARM64 starts from an ARMv8-A compatibility module and can opt into capability-checked dot-product, FP16, and I8MM modules. Unsupported tiers fall back without executing their kernels. Zero cloud APIs or external servers.
- ⚡ **Focus Warmup & Full-Context Decoding** — Input-focused background pre-warming keeps locally stored models hot in RAM. Transcription currently uses Whisper's default full audio context, avoiding an unmeasured context-truncation tradeoff across short and long utterances.
- 🎈 **Global Floating Dictation Button** — Accessibility Service + `WindowManager` overlay places an elegant floating microphone button right alongside your traditional keyboard (Gboard, Samsung, SwiftKey). Features persistent screen positioning across reboots, automatic smooth edge-docking animation, and tactile haptic feedback.
- 🛡️ **Protected Floating Dictation** — The optional accessibility service is not declared as an accessibility tool. A user-managed protected-app list hides the floating button in banks, password managers, and authenticators; banks can still detect that any accessibility service is enabled. Includes an in-app privacy policy and offline guarantee dialog.
- 📁 **Shared Audio File & History Export** — Receives shared voice notes/recordings via Android `SEND` intents, and provides one-tap export of your complete transcription archive to Markdown/Text via the Android Sharesheet.
- 🗣️ **Intelligent Spoken Punctuation** — Converts vocalized punctuation commands in Spanish and English (e.g., *"punto", "coma", "abrir/cerrar interrogación", "nueva línea", "nuevo párrafo"*) with clean forward/backward symbol attachment.
- 🧹 **Local Text Cleanup Modes** — Pure-Kotlin rule-based engine strips safe filler vocalizations ("um", "uh", "euh", "ähm"…), collapses repeated tokens, and applies capitalization/punctuation. Minimal, Balanced (default), and Aggressive modes.
- 🎨 **Adaptive Material 3 UI & Semantic Contrast** — Full Light and Dark theme support adhering to WCAG AAA contrast guidelines, `AnimatedContent` tab transitions, dynamic audio waveform visualizer, spring-scale micro-interactions, and live transcription canvas.

---

## 🛠️ Architecture & Tech Stack

```
VozLocal System Architecture (v2)
┌──────────────────────────────────────────────────────────────────────────┐
│                  Jetpack Compose UI (MVVM, Material 3)                    │
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│ │  DictateTab  │ │  ModelsTab   │ │ SharedTab    │ │ HistoryTab /     │  │
│ │  (filters)   │ │  (paged)     │ │ (decode UI)  │ │ Dictionary/Stats │  │
│ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────────┘  │
│  ↑                                                                       │
│  │  StateFlow / collectAsStateWithLifecycle                              │
│  │                                                                       │
│ ┌─────────────────────▼────────────────────────────────────────────────┐ │
│ │                       MainViewModel                                    │ │
│ │  - StateFlow<Model/History/Dictionary/Stats>                           │ │
│ │  - In-memory ring buffer for live waveform                             │ │
│ │  - downloadProgressFor(modelId): StateFlow<Float>                      │ │
│ │  - onCleared() stops only a ViewModel-owned recording session           │ │
│ └───────┬──────────────────────────────┬──────────────────────┬─────────┘ │
└─────────┼──────────────────────────────┼──────────────────────┼───────────┘
          │                              │                      │
┌─────────▼──────────┐  ┌────────────────▼──────────┐  ┌───────▼──────────────────┐
│  AudioRecorder     │  │  DictationRepository      │  │  DictationAccessibility   │
│  (process-wide     │  │  - WhisperEngine (load)   │  │  Service                  │
│   singleton,       │  │  - AudioDecoder (decode)  │  │  - Floating overlay       │
│  synchronized(this)│  │  - TextPolishEngine       │  │  - Text injection         │
│  - Room (DAO) + Prefs     │  │  - Same singleton         │
└─────────┬──────────┘  │  - postProcessText()      │  │    recorder               │
          │             └────────────┬─────────────┘  └────────────┬──────────────┘
          │                          │                              │
┌─────────▼──────────────────────────▼──────────────────────────────▼─────────┐
│                   WhisperEngine (com.whispercpp.whisper.WhisperContext)      │
│                  - Single-thread executor (JNI constraint)                   │
│                  - WhisperLib.fullTranscribeWithParams() — language hint,    │
│                    full audio context, temperature fallback, and noTimestamps │
│                  - adaptive thread count with Tensor G3 & 8+ core optimization│
│                  - Cleaner-based backstop cleanup (no finalize()/runBlocking)│
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────────────┐
│                    On-Device Local Models                                    │
│   ggml-tiny / base / small / medium / large-v3-turbo.bin (q8_0 / q5_0)      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Application / Process Lifecycle

`VozLocalApp : Application` is the single source of truth for shared singletons:

| Field | Type | Notes |
|---|---|---|
| `repository` | `DictationRepository` | Lazy-initialized; owns the `WhisperEngine`, `ModelDownloader`, `AudioDecoder`, `TextPolishEngine`, and Room database. |
| `audioRecorder` | `AudioRecorder` | Process-wide singleton. Both `MainViewModel` and `DictationAccessibilityService` read this same instance; state transitions are guarded by `synchronized(this)` and the IO reader thread reads `@Volatile` fields, so the two can never open `AudioRecord` on the same mic. |
| `applicationScope` | `CoroutineScope` | `SupervisorJob() + Dispatchers.IO`. Owns the long-lived `repository.initializeModels()` call (seed default model rows, prune stale ones, refresh download state). |

`MainViewModel.onCleared()` stops only a recording session that the ViewModel actually owns. The process-wide recorder is not released by Activity destruction, so it cannot interrupt a simultaneous accessibility-overlay dictation. The repository still exposes `shutdown()` for app-level teardown of the native `WhisperContext`.

### Layered View → Service → Repository → Engine

```
Compose Screen
  └─ collectAsStateWithLifecycle() over ViewModel StateFlow
       └─ MainViewModel exposes state, mutators, and in-memory caches
            └─ DictationRepository (single orchestration layer)
                 ├─ WhisperEngine     (JNI bridge to whisper.cpp)
                 ├─ AudioDecoder      (MediaCodec + PCM resample)
                 ├─ ModelDownloader   (OkHttp + SHA-256 verify)
                 ├─ TextPolishEngine  (rule-based cleanup & Spanish accent normalization)
                 └─ Room DAOs         (model / history / dictionary / stats)
```

### Service ↔ ViewModel Coordination

`DictationAccessibilityService` does **not** re-implement recording or transcription. It:
1. Borrows the same `AudioRecorder` singleton from `VozLocalApp`.
2. Uses its own `serviceScope` for floating-overlay-only work.
3. Queries `repository.allModels.first()` and `repository.postProcessText(...)` with the persisted cleanup settings to share the canonical post-processing pipeline with the in-app ViewModel.

A `SharedPreferences.OnSharedPreferenceChangeListener` is registered in the service for `show_only_on_input` so overlay visibility updates instantly when the user toggles the setting in the app.

---

| Layer | Technology |
|---|---|
| **Language** | Kotlin 2.2.10 & C++ (JNI, vendored whisper.cpp) |
| **UI Framework** | Jetpack Compose (Material 3, BOM 2024.09.00) |
| **Architecture** | MVVM, Kotlin Coroutines, `StateFlow`, process-scoped singletons |
| **STT Engine** | `whisper.cpp` (q8_0 / q5_1 / q5_0 GGML quantization, runtime-selected ARM CPU modules, adaptive multi-core threading) |
| **Text Cleanup** | Local rule-based Kotlin engine (`TextPolishEngine`) — Minimal / Balanced / Aggressive modes, Spanish orthography normalization, pure Kotlin, zero latency |
| **Persistence** | Room 2.7.0 (version 2, with stub `MIGRATION_1_2`, no destructive fallback) |
| **Audio Capture** | `AudioRecord` API (16 kHz mono PCM, `VOICE_RECOGNITION` source) |
| **Audio Decoding** | `MediaCodec` + `MediaExtractor`; drains output EOS, handles decoder PCM format changes, downmixes channels, and resamples to 16 kHz |
| **Networking** | OkHttp 4.x (model downloads only; offline thereafter) |
| **Global Overlay** | Android `AccessibilityService` + `WindowManager` (edge docking, position persistence, haptic feedback, display-cutout aware) |
| **Build** | AGP 9.3.1, R8 minify + resource shrink **enabled** for release |

---

## 📦 Supported Local Speech Models

Models are downloaded on-demand from Hugging Face directly to `context.filesDir/models` and run offline:

| Model ID | Weight File | Approx. Download | Language | Quantization | Selection Notes |
|---|---|---:|---|---|---|
| `whisper_base` *(default)* | `ggml-base-q8_0.bin` | ~78 MB | Multilingual | q8_0 | Default starting point; compare against Tiny and Small on representative audio. |
| `whisper_tiny` | `ggml-tiny-q8_0.bin` | ~42 MB | Multilingual | q8_0 | Smallest download; useful when storage or memory is constrained. |
| `whisper_base_en` | `ggml-base.en-q8_0.bin` | ~78 MB | English only | q8_0 | Not intended for Spanish or multilingual dictation. |
| `whisper_small` | `ggml-small-q8_0.bin` | ~252 MB | Multilingual | q8_0 | Larger Small checkpoint; compare with the compressed Small variant. |
| `whisper_small_q5_1` | `ggml-small-q5_1.bin` | ~175 MB | Multilingual | q5_1 | Smaller download than Small q8_0; quantization can affect output and device performance. |
| `whisper_large_v3_turbo` | `ggml-large-v3-turbo-q5_0.bin` | ~547 MB | Multilingual | q5_0 | Turbo checkpoint with substantial storage and memory demand; benchmark locally. |
| `whisper_medium` | `ggml-medium-q8_0.bin` | ~823 MB | Multilingual | q8_0 | Largest download in the catalog; benchmark sustained performance before selecting. |


The sizes above are approximate download sizes, not RAM estimates. VozLocal does not publish universal accuracy percentages or speed multipliers: meaningful results require a named corpus, device, native backend, thread count, thermal state, and decoding configuration.

> **Model integrity**: downloads use unique `.part` files, transport-length and model-size checks, atomic replacement, and pinned SHA-256 digests. The current Hugging Face URLs still use mutable `/resolve/main/` paths; digest verification protects the expected bytes, but pinning each URL to an immutable repository revision remains future hardening work.

---

## ⚖️ Accuracy & Performance

VozLocal optimizes for complete, accurate local transcription before micro-latency:

- **Multi-core CPU threading optimization** — `WhisperCpuConfig` dynamically discovers high-performance CPU cores by parsing `/sys/devices/system/cpu/` and `/proc/cpuinfo`. It safely allocates up to 5 performance cores on modern 8+ core and 9-core mobile SoCs (such as the Pixel 8 Pro Tensor G3: 1x Cortex-X3 + 4x Cortex-A715) while reserving CPU budget for audio recording and UI threads. Thread allocation also scales by active model size, with configurable JVM override (`-Dvozlocal.whisper.threads=N`).
- **Capability-checked ARM modules** — ARM64 has an ARMv8-A compatibility module plus optional dot-product, FP16/dot-product, and I8MM modules. Native HWCAP checks select a supported tier before its kernels run; a persisted startup sentinel restores compatibility mode after an interrupted optimized probe.
- **Input-focus model warmup & GGML graph pre-warming** — `DictationAccessibilityService` warms the active model after an allowed text input gains focus. `WhisperEngine` also performs a bounded dry run to allocate graph memory and touch model/kernel pages. The improvement and thermal cost are device-dependent and must be measured rather than described as zero latency.
- **Smart `onTrimMemory` keep-alive & eviction policy** — `VozLocalApp` monitors Android system memory pressure (`ComponentCallbacks2.onTrimMemory`). Under moderate pressure, the native model remains permanently pinned in RAM; under critical system memory emergencies (`TRIM_MEMORY_COMPLETE`), idle models are gracefully unloaded to protect the accessibility service from being killed, automatically reloading and pre-warming as soon as an input field is focused.
- **Robust timestamp-guided segmentation** — Live dictation retains standard Whisper timestamp tokens (`noTimestamps = false`) across the full 30-second context window. This ensures long dictations (>8s) are seamlessly decoded across segment boundaries without cutting off speech, while multi-temperature fallback (`temperatureInc = 0.2f`) automatically breaks out of repetition loops.
- **Hallucination & loop collapse post-filtering** — `HallucinationFilter` automatically detects and collapses pathological word loops ("no no no...") and multi-word phrase loops ("ustedes como ven...") before text is committed.
- **Long-file coherence** — Shared audio uses timestamps, multi-window decoding, and previous decoder context rather than treating every Whisper window as unrelated speech.
- **Measurable comparisons** — The `benchmark` package provides deterministic Unicode-aware WER/CER scoring and records model, quantization, threads, VAD, timing, real-time factor, optional memory, and thermal status. Catalog labels expose factual model attributes; measured rankings require explicit benchmark provenance.

### Pixel 8 Pro measurements

These measurements are local engineering observations, not universal model rankings. They used a Pixel 8 Pro (Tensor G3), the app's Automatic ARM backend, five threads for Small, Spanish decoding, the current full-context live parameters, and a fixed 10-second excerpt from `app/src/test/resources/test_audio_2min.ogg`. Battery Saver was off and Android reported thermal status `NONE`.

| Model / quantization | Runs | Inference time | Real-time factor | Observed output |
|---|---:|---:|---:|---|
| Small q5_1 | 2 | 8.45 s, 8.64 s | 0.85–0.86 | Stable across both runs; omitted a short word relative to q8_0 on this excerpt. |
| Small q8_0 | 2 | 4.47 s, 4.92 s | 0.45–0.49 | Minor whitespace variation; retained the more grammatically complete phrase on this excerpt. |
| Large v3 Turbo q5_0 | 1 per thread count | 37.38 s at 6 threads | 3.74 | Much slower than either Small variant on this CPU; 5 and 4 threads were slower still. |

On this narrow workload, Small q8_0 reduced median inference latency by about **45%** relative to Small q5_1 despite its larger file. This is plausible because quantization formats exercise different ARM kernels and memory/compute tradeoffs; a smaller model file does not guarantee faster inference. The excerpt has no hand-verified reference transcript, so its output difference is evidence to expand accuracy testing, not a WER claim. The phone was at low charge during the compact paired experiment, and a preceding compatibility-backend pass showed q8_0 can trigger a slow decoder-fallback outlier. Promotion therefore requires the longer validation matrix below.

For the next controlled evaluation, use multiple clean/noisy Spanish clips and a hand-verified reference; alternate model order; record native encode/decode timing, transcript hashes, WER/CER, memory, battery temperature, and thermal state; then repeat the full ~100.65-second fixture and a 10–20-minute sustained workload. Small q8_0 is the leading Pixel candidate, while Small q5_1 remains valuable where storage/RAM are tighter or until broader accuracy and fallback-tail latency are measured.

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1+) or newer
- **JDK**: Version 17+
- **Android SDK**: Minimum SDK 26 (Android 8.0 Oreo), Target SDK 36
- **NDK**: Android NDK r25c or higher for compiling the vendored `whisper.cpp` C++ JNI

### Installation & Build

```bash
git clone https://github.com/lander16/voz-local.git
cd voz-local
cp .env.example .env          # optional, only if you override build config
./gradlew assembleDebug
./gradlew installDebug
```

Release builds enable R8 minification + resource shrinking. The `proguard-rules.pro` keeps the JNI bridge, Room entities/DAOs, the `AccessibilityService`, and the Compose runtime intact. Release credentials are never stored in the repository: provide `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS` (optional; defaults to `upload`), and `KEY_PASSWORD` through CI or your local environment. Without them, Gradle builds an unsigned release for validation only.

---

## 📱 How to Use

The primary Dictate, Shared Audio, History, and protected-app flows are localized in English and Spanish. Settings and less-frequent dialogs continue to be localized incrementally; untranslated text must not be presented as a complete localization.

### 1. Initial Permissions Setup
On first launch you'll see a 2-step setup wizard:
1. **Microphone Permission** — required for live dictation
2. **Accessibility Service** — required for the global floating overlay

A "Skip Setup & Explore App" option is provided; if you skip, a persistent banner offers to grant the missing permission later.

### 2. Live Voice Dictation
1. Open the **Dictate** tab.
2. (Optional) Pick a language (Spanish, English, etc.) — explicit language skips Whisper's auto-detect overhead.
3. Tap the large circular microphone to start. The pulse aura, live waveform, and timer will all light up.
4. Tap the mic (or the stop icon) to finish. The transcript is auto-saved locally and can be copied or shared from the app.

### 3. Using the Global Floating Assistant
1. Enable **VozLocal Floating Dictation** in Android's *Accessibility Settings*.
2. In **Settings → Floating assistant**, add banks, password managers, authenticators, and payment apps to **Protect sensitive apps**.
3. Open a permitted app (WhatsApp, Gmail, Chrome, Notes).
4. Tap on a text field — the floating mic icon appears (it stays hidden when no field is focused).
5. Tap the floating mic to dictate; text is inserted only with direct `ACTION_SET_TEXT` into the original permitted app/window. If the target changes or rejects insertion, the transcript stays local in history.

### 4. Transcribing Shared Audio Files
1. In WhatsApp, Voice Memos, or Files, tap **Share** on any audio file (`.wav`, `.mp3`, `.m4a`, `.ogg`).
2. Pick **VozLocal** — the **Shared** tab opens with the file loaded.
3. Tap **Start offline transcription**. Progress is reported through decode → model-load → inference → post-process stages.

### 5. Managing Models & Custom Dictionary
- **Models** tab — download or activate Whisper models. Download progress is now driven by an in-memory flow (not a per-1% Room write) so the list doesn't recompose 100 times during an 800 MB download.
- **Dictionary** tab — add custom brand names or technical terms. Compiled regexes are cached and invalidated on insert/delete.

### 6. Settings
- **Appearance** — Light, Dark, or System. ("System" follows the OS setting via `isSystemInDarkTheme()` inside `MyApplicationTheme`.)
- **Language** — the same set as in the Dictate tab.
- **Post-processing** — Smart pause correction, auto-capitalization, dictionary, local cleanup mode (Minimal / Balanced / Aggressive), spoken punctuation commands, and optional VAD. The VAD model downloads only when you explicitly request it. These settings are persisted to SharedPreferences.
- **Overlay** — show the floating button only on text fields and select sensitive apps where it must never appear.
- **History & Data Management** — opt-out of saving transcripts, cap history limit, or one-tap export your entire transcription history to Markdown/Text via the Android sharesheet.
- **Privacy & Security** — view the in-app "Privacy Policy & Offline Guarantee" modal dialog confirming zero cloud telemetry and local model execution.
- **Done** — closes the sheet (settings are saved instantly on toggle).

---

## 🎈 Floating Microphone Gestures

The floating accessibility overlay provides fluid, non-intrusive dictation alongside any Android keyboard:

- **Tap to start / stop** — Tap the microphone to immediately begin dictation with instant tactile haptic feedback. A second tap stops recording and inserts your transcription into the active text field.
- **Drag anywhere on screen to reposition** — Freely drag the floating button to any location on your screen with real-time responsive touch tracking.
- **Automatic edge-docking** — When released, a smooth spring deceleration animation snaps the button flush against the left or right screen bezel (whichever is closest), ensuring underlying reading material and keyboard layouts stay unobscured while respecting display cutouts and navigation bars.
- **Persistent positioning** — Remembers your customized placement across app restarts and system reboots via `FloatingButtonDockPolicy` and device-local preferences.

---

## 🗣️ Spoken Punctuation Commands Cheat Sheet

VozLocal features phrase-isolated spoken punctuation replacement. When **Spoken punctuation commands** is enabled in Settings, vocalized punctuation commands are converted into natural punctuation marks and line breaks. Because the replacement rules isolate punctuation commands, ordinary phrases (such as *"el paciente entró en coma"* or *"the word period is a noun"*) are preserved without unwanted substitution.

### 🇪🇸 Spanish Commands

| Spoken Command | Output Symbol | Behavior / Meaning |
|---|:---:|---|
| `punto` / `punto final` / `punto y seguido` / `punto y aparte` | `.` | Period followed by a space and automatic sentence capitalization |
| `coma` | `,` | Comma followed by a space |
| `punto y coma` | `;` | Semicolon followed by a space |
| `dos puntos` | `:` | Colon followed by a space |
| `puntos suspensivos` | `...` | Ellipsis |
| `abrir interrogación` / `abrir signo de interrogación` | `¿` | Inverted Spanish question mark attaching to following text |
| `cerrar interrogación` / `cerrar signo de interrogación` / `signo de interrogación` | `?` | Closing question mark with trailing space |
| `abrir exclamación` / `abrir signo de admiración` | `¡` | Inverted Spanish exclamation mark attaching to following text |
| `cerrar exclamación` / `cerrar signo de admiración` / `signo de exclamación` | `!` | Closing exclamation point with trailing space |
| `abrir comillas` | `"` | Opening quotation mark |
| `cerrar comillas` | `"` | Closing quotation mark |
| `abrir paréntesis` | `(` | Opening parenthesis attaching to following word |
| `cerrar paréntesis` | `)` | Closing parenthesis |
| `nueva línea` | `\n` | Single line break |
| `nuevo párrafo` | `\n\n` | Double line break starting a new paragraph |
| `guión` | `-` | Dash / hyphen |

### 🇬🇧 English Commands

| Spoken Command | Output Symbol | Behavior / Meaning |
|---|:---:|---|
| `period` / `full stop` | `.` | Period followed by space and capitalizes next word |
| `comma` | `,` | Comma followed by space |
| `semicolon` | `;` | Semicolon followed by space |
| `colon` | `:` | Colon followed by space |
| `ellipsis` / `dot dot dot` | `...` | Ellipsis |
| `question mark` | `?` | Question mark with trailing space |
| `exclamation mark` / `exclamation point` | `!` | Exclamation point with trailing space |
| `open quote` / `open quotation mark` | `"` | Opening quotation mark |
| `close quote` / `close quotation mark` | `"` | Closing quotation mark |
| `open parenthesis` / `open paren` | `(` | Opening parenthesis |
| `close parenthesis` / `close paren` | `)` | Closing parenthesis |
| `new line` | `\n` | Single line break |
| `new paragraph` | `\n\n` | Double line break starting a new paragraph |
| `hyphen` / `dash` | `-` | Dash / hyphen |

---

## 🔒 Privacy, Security & Google Play Compliance

> 📄 **Official Public Privacy Policy:** The complete legal privacy document complying with Google Play's User Data policy is published at [**PRIVACY_POLICY.md**](PRIVACY_POLICY.md) (provided in English and Spanish). You can submit this URL directly in Google Play Console: `https://github.com/lander16/voz-local/blob/main/PRIVACY_POLICY.md`

VozLocal is architected from inception for complete local sovereignty, zero cloud tracking, and strict adherence to Google Play Store policies:

### 1. 100% On-Device Offline Guarantee
- **Zero telemetry or external analytics**: No Firebase, telemetry trackers, crash-loggers, or third-party SDKs are embedded.
- **Air-gapped voice transcription**: All Whisper speech recognition models run purely on-device using local CPU inference (`whisper.cpp`). No audio recordings, audio samples, or generated transcripts are ever transmitted to remote servers.
- **Air-gapped operation**: Turn on Airplane Mode after downloading a speech model and VozLocal will operate indefinitely at 100% functionality. Network access is utilized exclusively for user-initiated model weight downloads.

### 2. Optional Accessibility Service
- **Purpose**: VozLocal offers user-triggered voice typing through an optional floating microphone. It is declared with `android:isAccessibilityTool="false"`; do not represent this app as an accessibility aid unless its purpose and Play Console declarations genuinely meet that policy category.
- **Banking-app limitation**: A protected-app list prevents overlay display, recording, source-node access, and insertion in selected apps. Android and bank security software can still detect an enabled accessibility service, so users must disable the service entirely if their bank requires it.
- **Strict Scope of Operation**:
  - The accessibility service only observes focus and window transitions (`TYPE_VIEW_FOCUSED`, `TYPE_WINDOW_STATE_CHANGED`) to position the floating microphone alongside the active keyboard.
  - It does not enumerate interactive windows, log keystrokes, capture screenshots, or persist screen contents. It reads only the focused editable node needed to validate and perform direct insertion.
  - Text insertion occurs solely via Android's `ACTION_SET_TEXT` API when the user explicitly triggers dictation.

### 3. Sensitive & Banking Application Protection
- **Protected-app Shielding**: VozLocal includes a local sensitive-application exclusion manager in **Settings → Floating assistant**.
- **Password & Financial Safety**: The floating button is hidden and all text operations are rejected in apps selected by the user, and for secure password/PIN fields.
- **Private Local Storage**: Transcripts and custom dictionaries reside exclusively in the device's private Room database (`vozlocal_database`), explicitly excluded from cloud backups and device migration (`backup_rules.xml`).

### 4. Android Permission Justifications

| Permission | Justification & Usage Scope |
|---|---|
| `android.permission.RECORD_AUDIO` | **Dictation capture**: Used exclusively while an active speech recording session is initiated by the user. The microphone is never accessed in the background or when dictation is stopped. Audio is processed directly in-memory into 16 kHz PCM frames without leaving device RAM. |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | **Assistive overlay & text entry**: Used solely to detect when an editable text view is focused so the floating microphone button can be presented, and to insert the generated transcription directly into the active field via `ACTION_SET_TEXT`. |
| `android.permission.VIBRATE` | **Haptic feedback**: Used only for optional tactile feedback from the floating microphone button. |
| `android.permission.INTERNET` | **Model downloads only**: Utilized exclusively for user-directed downloads of quantized Whisper model weights from Hugging Face. Never invoked during recording or transcription. |

---

## 🛣️ Roadmap

Only unfinished work belongs here. Implemented behavior is documented in the
feature, architecture, privacy, and performance sections above.

### Performance and accuracy

1. **Reproducible device benchmark mode** — Disable normal startup preload during
   measurement; accept model, backend, thread, clip, and iteration arguments; and
   export structured load, warmup, encode, decode, fallback, memory, energy, and
   thermal results.
2. **Representative transcription corpus** — Add hand-verified clean/noisy
   Spanish, names and numbers, code-switched speech, short utterances, 30-second
   boundary cases, and sustained recordings. Use normalized WER/CER plus manual
   review of substantive differences.
3. **Complete Small q8_0 validation** — Repeat the promising Pixel 8 Pro result
   across the full corpus, alternating q8_0/q5_1 order and measuring p50/p95,
   decoder-fallback outliers, memory, battery temperature, and sustained load
   before changing any default.
4. **Opt-in local calibration** — Select backend tier and thread count per device,
   model, quantization, and workload. Invalidate saved profiles after native-build
   or model-checksum changes and always retain Compatibility mode.
5. **Guarded short-dictation path** — Re-evaluate duration-binned audio contexts
   with a full-context retry for empty, repetitive, low-confidence, or apparently
   truncated output. Long and shared audio must keep timestamp-guided decoding.
6. **Native timing and fallback telemetry** — Expose whisper.cpp encode/decode and
   sampling timings locally, including fallback count and temperature steps,
   without storing transcript text in release diagnostics.
7. **Warmup and sustained-load tuning** — Separate load, warmup, and inference
   costs; avoid warming a model that will be replaced; and compare burst versus
   long-file thread policies under controlled thermal conditions.
8. **Larger-model acceleration experiments** — Evaluate GPU/Vulkan support, more
   compressed Turbo variants, and vetted Spanish-fine-tuned Small checkpoints as
   opt-in alternatives with explicit compatibility and WER/CER gates.

### Platform and project quality

- Pin model download URLs to immutable Hugging Face revisions in addition to the
  existing SHA-256 verification.
- Replace linear audio downsampling with a streaming band-limited/windowed-sinc
  resampler and measure its recognition effect.
- Add a foreground service for user-initiated recording with the screen off.
- Add window-size-class layouts for tablets and foldables.
- Add Detekt, ktlint, and continuous-integration checks.

---

## 📄 License & Acknowledgments

- **whisper.cpp** — Georgi Gerganov's [`whisper.cpp`](https://github.com/ggerganov/whisper.cpp) C++ library (MIT).
- **OpenAI Whisper** — the original Whisper model weights (MIT).
- **License** — MIT. See [LICENSE](LICENSE).
