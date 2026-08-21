# VozLocal 🎙️⚡

**100% On-Device Local Speech Dictation & Audio Transcription for Android**

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Engine](https://img.shields.io/badge/Engine-whisper.cpp-orange.svg?style=flat)](https://github.com/ggerganov/whisper.cpp)
[![Cleanup](https://img.shields.io/badge/Text%20Cleanup-Local%20Rule--Based-red.svg?style=flat)](#architecture--tech-stack)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-brightgreen.svg?style=flat)](#privacy--security)

**VozLocal** is a privacy-first, ultra-fast Android application for local speech-to-text dictation and audio file transcription. It runs on-device using quantized OpenAI Whisper GGUF/bin models via `whisper.cpp` and local rule-based text cleanup — **no cloud APIs, no telemetry, no third-party SDKs that phone home**. Network access is used only to download model files. A global floating accessibility overlay lets you dictate into any app on the device.

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

- 🎙️ **On-Device Speech Recognition** — Quantized OpenAI Whisper models run locally on Android hardware via JNI C++ bindings (`whisper.cpp`). No cloud APIs or subscriptions.
- 🎈 **Protected Floating Dictation Overlay** — Accessibility Service + `WindowManager` show a floating microphone button in permitted apps. A local sensitive-app denylist hides it in banks, password managers, authenticators, and payment apps; only direct, validated text insertion is used.
- 📁 **Shared Audio File Transcription** — Receives shared audio files via Android `SEND` intents (WhatsApp voice notes, Voice Memos, podcast snippets) and transcribes them offline with `MediaCodec` + PCM.
- 🧹 **Local Text Cleanup Modes** — A pure-Kotlin rule-based engine strips safe filler vocalizations ("um", "uh", "euh", "ähm"…), collapses repeated tokens, and applies capitalization/punctuation. It has **Minimal**, **Balanced** (default), and **Aggressive** modes, all persisted in settings. No LLM or extra model file is used; the historical `QwenEngine` class name now refers to this local cleanup implementation.
- 🎯 **Optimized On-Device STT** — The project-owned JNI shim (`app/src/main/jni/vozlocal-jni/vozlocal-jni.c`) exposes high-value `whisper_full_params` controls to Kotlin: explicit language, an optional initial prompt (Spanish gets a default priming prompt), low-latency single-window dictation, confidence thresholds, temperature fallback, optional beam search, decoder context, and native **Silero VAD** (~0.9 MB, downloaded from `ggml-org/whisper-vad` and user-toggleable). Short dictations use the low-latency path; recordings over 25 seconds switch to multi-window decoding so Whisper processes the complete recording. A post-transcription `HallucinationFilter` strips known outro phrases only at the tail and collapses verbatim repeated sentences. If the selected model is already downloaded, it is preloaded in the background at process start to reduce first-dictation latency.
- 🧪 **Opt-in Streaming Dictation** — Settings → Advanced engine can enable an experimental in-app preview. It transcribes an overlapping 8-second rolling audio window every 2.5 seconds, keeps confirmed text stable, and permits only the newest words to be revised. Stopping always runs the existing complete-recording pass, which remains authoritative for saved, copied, and overlay-pasted text.
- 📚 **Personal Dictation Dictionary** — Vocabulary biasing and phonetic-replacement rules. Compiled regexes are cached and invalidated on insert/delete, so post-processing stays O(words) per transcription.
- ⚡ **Local Post-Processing Pipeline** — Smart pause correction, auto-capitalization, dictionary replacement, spoken punctuation commands, hallucination filtering, and cleanup modes — all stitched together in `DictationRepository.postProcessText`.
- 📊 **Performance Stats & Analytics** — Per-day WPM, total speak time, and accuracy breakdown per model, backed by Room.
- 💾 **Persistent Transcription History** — Room database with a paged query (`LIMIT 200`) so the History tab never re-emits the entire table on insert.
- 🎨 **Modern Material 3 Jetpack Compose UI** — `ModalNavigationDrawer` + `ModalBottomSheet` settings + light/dark/system theming + canvas audio waveform visualizer + press-scale micro-interactions (now via `pointerInput`, not the previous clickable-swallowing bug).
- 🇲🇽 🇧🇷 **Multi-Language Support** — Explicit language target (Spanish, English, French, German, Portuguese, Italian, Auto-detect) skips Whisper's expensive auto-detect.

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
│  synchronized(this)│  │  - QwenEngine (polish)    │  │  - Text injection         │
└─────────┬──────────┘  │  - Room (DAO) + Prefs     │  │  - Same singleton         │
          │             │  - postProcessText()      │  │    recorder               │
          │             └────────────┬─────────────┘  └────────────┬──────────────┘
          │                          │                              │
┌─────────▼──────────────────────────▼──────────────────────────────▼─────────┐
│                   WhisperEngine (com.whispercpp.whisper.WhisperContext)      │
│                  - Single-thread executor (JNI constraint)                   │
│                  - WhisperLib.fullTranscribeWithLang() — language hint skips │
│                    ~200-500ms auto-detect overhead                            │
│                  - adaptive thread count with optional JVM override           │
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
| `repository` | `DictationRepository` | Lazy-initialized; owns the `WhisperEngine`, `ModelDownloader`, `AudioDecoder`, `QwenEngine`, and Room database. |
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
                 ├─ QwenEngine        (rule-based cleanup)
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
| **STT Engine** | `whisper.cpp` (q8_0 / q5_0 GGML quantized) |
| **Text Cleanup** | Local rule-based Kotlin engine (`QwenEngine`) — Minimal / Balanced / Aggressive modes, pure Kotlin, no model file |
| **Persistence** | Room 2.7.0 (version 2, with stub `MIGRATION_1_2`, no destructive fallback) |
| **Audio Capture** | `AudioRecord` API (16 kHz mono PCM, `VOICE_RECOGNITION` source) |
| **Audio Decoding** | `MediaCodec` + `MediaExtractor`; drains output EOS, handles decoder PCM format changes, downmixes channels, and resamples to 16 kHz |
| **Networking** | OkHttp 4.x (model downloads only; offline thereafter) |
| **Global Overlay** | Android `AccessibilityService` + `WindowManager` (display-cutout aware) |
| **Build** | AGP 9.3.1, R8 minify + resource shrink **enabled** for release |

---

## 📦 Supported Local Speech Models

Models are downloaded on-demand from Hugging Face directly to `context.filesDir/models` and run offline:

| Model ID | Weight File | Size (MB) | Spanish Accuracy | Decoding Speed | Recommended For |
|---|---|---|---|---|---|
| `whisper_tiny` *(Initial default)* | `ggml-tiny-q8_0.bin` | ~42 MB | 72% | **8.5x** | Ultra-fast dictation on low-end hardware |
| `whisper_base` | `ggml-base-q8_0.bin` | ~78 MB | 83% | **5.0x** | Better balance of speed & accuracy for dictation |
| `whisper_small` | `ggml-small-q8_0.bin` | ~252 MB | 92% | **2.5x** | High accuracy dictation & clean audio notes |
| `whisper_medium` | `ggml-medium-q8_0.bin` | ~823 MB | 97% | **1.0x** | Complex vocab, accents & technical dictation |
| `whisper_large_v3_turbo` | `ggml-large-v3-turbo-q5_0.bin` | ~547 MB | 99% | **3.5x** | High-accuracy file transcription with better speed than medium |


> **Model integrity**: downloads are written to unique `.part` files, checked for complete transport length and model-specific minimum size, and then atomically moved into place. SHA-256 verification is supported when a real hash is pinned, but the current Hugging Face URLs do not yet have pinned immutable hashes in this repo, so downloads are explicitly marked unverified instead of pretending placeholder hashes are real. Pin immutable model revisions and real SHA-256 values before shipping.

---

## ⚖️ Accuracy & Performance

VozLocal optimizes for complete, accurate local transcription before micro-latency:

- **Capture integrity** — stopping the microphone stops and joins the PCM reader before samples are copied, so a final in-flight audio block cannot be lost.
- **Long dictation safety** — dictations up to 25 seconds retain Whisper’s fast single-window path. Longer recordings automatically enable multi-window decoding, avoiding 30-second-window truncation.
- **Accuracy-oriented decoding** — the default no-speech and log-probability thresholds are `0.6` and `-1.0`; a `0.2` temperature increment lets Whisper retry uncertain passages. Advanced thresholds remain configurable in Settings.
- **Long-file coherence** — shared audio uses timestamps, multi-window decoding, and previous decoder context rather than treating every Whisper window as unrelated speech.
- **Streaming preview (opt-in)** — the Dictate screen can display rolling transcription while recording. It uses raw PCM snapshots, overlapping windows, and token reconciliation; the final full-recording pass preserves the established accuracy and post-processing pipeline.
- **Measurable comparisons** — the `benchmark` package provides deterministic Unicode-aware WER/CER scoring and records model, quantization, threads, VAD, timing, real-time factor, optional memory, and thermal status. Model labels in the UI and table are guidance, not device-specific measurements.

### Pixel 8 Pro baseline

For accuracy-first use, benchmark `large-v3-turbo q5_0` against `small q8_0` on representative recordings before choosing a default. Test 3–6 threads, cold and warm starts, and sustained 10-minute sessions; select the configuration with the best WER/CER that remains comfortably faster than real time without thermal throttling. The app supplies benchmark foundations, but does not yet ship a benchmark UI or hard-code unverified Pixel measurements.

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1+) or newer
- **JDK**: Version 17+
- **Android SDK**: Minimum SDK 26 (Android 8.0 Oreo), Target SDK 36
- **NDK**: Android NDK r25c or higher for compiling the vendored `whisper.cpp` C++ JNI

### Installation & Build

```bash
git clone https://github.com/your-username/voz-local.git
cd voz-local
cp .env.example .env          # optional, only if you override build config
./gradlew assembleDebug
./gradlew installDebug
```

Release builds enable R8 minification + resource shrinking. The `proguard-rules.pro` keeps the JNI bridge, Room entities/DAOs, the `AccessibilityService`, and the Compose runtime intact.

---

## 📱 How to Use

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
- **History** — opt-out of saving transcripts, or cap history at 5 / 10 / 20 / 50 / unlimited.
- **Done** — closes the sheet (settings are saved instantly on toggle).

---

## 🔒 Privacy & Security

- 🚫 **Zero telemetry, no tracking.** No Firebase, no analytics SDK, no third-party network call after model download.
- ✈️ **Air-gapped operation.** Once model weights are downloaded, turn off Wi-Fi and cellular — the app continues to work at 100% capacity. Downloads are user-initiated and are the only network call.
- 🔐 **Local storage only.** All dictionary entries and dictation history live in the device's private Room database (`vozlocal_database`). The DB is **excluded** from cloud backup and device-transfer rules (`backup_rules.xml` and `data_extraction_rules.xml`) so transcript text is not silently uploaded or migrated. The downloaded `models/` directory is also excluded.
- 🪟 **Hardened accessibility scope.** The service listens only for focus/window-state changes, does not retrieve interactive-window lists, does not filter keys or request gestures/screenshots, and checks the local sensitive-app denylist before retrieving an event source. Password and Android-marked sensitive nodes are never targets.
- 🏦 **Banking-app limitation.** Android and bank apps can still detect that an accessibility service is enabled system-wide. Adding a bank to the denylist prevents VozLocal from showing, recording, reading a source node, or inserting text there; disable the accessibility service entirely if a bank requires it.
- 📋 **No accessibility clipboard fallback.** If direct text insertion fails, VozLocal saves the local transcript but never copies it to the system clipboard on behalf of the overlay.
- 🔒 **No `FOREGROUND_SERVICE_MICROPHONE` permission declared.** The permission is removed from the manifest because no foreground service with `foregroundServiceType="microphone"` is currently registered. A future release will add a real foreground service for background recording; the permission will be re-added at the same time.
- 🪵 **No raw transcripts in logs.** `WhisperEngine` only logs the raw transcription text under `BuildConfig.DEBUG` — release builds log nothing user-identifiable.

---

## 🧱 Recent Architectural Changes (v2)

| Area | Before | After |
|---|---|---|
| `AudioRecorder` | Two instances (ViewModel + Service), raced for the mic | Process-wide singleton in `VozLocalApp`; state transitions guarded by `synchronized(this)` and the IO reader thread reads `@Volatile` fields |
| `WhisperContext` cleanup | `runBlocking` on the GC finalizer thread | `Cleaner`-based backstop + lifecycle mutex that serializes load / transcribe / release |
| CPU thread count | Re-read `/proc/cpuinfo` on every transcription | Adaptive lazy selection with `-Dvozlocal.whisper.threads=N` override |
| Smart-punctuation | Two implementations, one dead | Kotlin `postProcessText` is the single source of truth; the JNI pause-joiner is removed |
| Dictionary regexes | Re-compiled every transcription | Hash-keyed cache, invalidated on insert/delete |
| Download progress | Wrote Room on every 1% (100 writes for an 800 MB model) | In-memory `MutableStateFlow<Map<String,Float>>`; DB written only on completion/failure |
| Model downloads | Direct writes to final path + placeholder SHA strings | Unique `.part` files, complete-length/size validation, atomic move, explicit unverified state until real SHA-256 hashes are pinned |
| Silero VAD download | Broken upstream URL / oversized validation floor | Downloads from `ggml-org/whisper-vad`, accepts the current ~0.9 MB asset, and keeps VAD optional/user-toggleable |
| History list | Full table re-emit on every insert | `LIMIT 200` paged flow for the UI; full table retained for stats |
| Filter toggles | Reset on every app restart | Persisted to SharedPreferences via proper setters |
| Cleanup modes | One optional cleanup toggle | Persisted Minimal / Balanced / Aggressive modes exposed in ViewModel and Settings; Dictate tab shows the current mode |
| Spoken punctuation commands | Could replace punctuation words inside normal prose | Phrase-isolated commands, so dictation like “question mark is useful” is preserved while standalone commands still insert punctuation |
| `postProcessText` signature | Fixed booleans | Cleanup mode and spoken punctuation settings flow through the shared repository pipeline used by the app and overlay |
| Database schema | `fallbackToDestructiveMigration(dropAllTables = true)` | `addMigrations(MIGRATION_1_2)`, no destructive fallback; version bumped to 2 |
| Accessibility service | `typeAllMask` + key-filter | Focus/window-state events only; no interactive-window retrieval; local sensitive-app denylist is checked before source access; password/sensitive nodes and stale targets are rejected |
| Backup rules | Empty TODOs → default behavior leaked 1.5 GB models to cloud | `models/` + database explicitly excluded |
| Build | `isMinifyEnabled = false`, no ProGuard rules | `isMinifyEnabled = true`, `isShrinkResources = true`, explicit keep rules for JNI/Room/AccessibilityService |
| Dependencies | Firebase BOM, Retrofit, Moshi, logging-interceptor (all unused) | Removed; only Compose, Lifecycle, Room, Coroutines, OkHttp, Accompanist, Robolectric/Roborazzi |
| `minSdk` / `targetSdk` | 24 / 36 (unreleased) | 26 / 36 (with a comment to pin to 35 once AGP 9.3.1 is verified on 35) |
| `compileSdk` | `release(36) { minorApiLevel = 1 }` | Same (SDK 36 still tracked) |
| `WhisperEngine` | Hardcoded `language="en"`, no VAD, no initial_prompt, no threshold tuning, single_segment=false | Project-owned JNI shim exposes language, initial_prompt, single_segment, no_speech_thold / logprob_thold / entropy_thold, beam_size, temperature fallback, decoder context, and optional Silero VAD. Accuracy-first defaults use `0.6` / `-1.0` rejection thresholds and a `0.2` fallback increment. Live dictation stays single-window only through 25 seconds; longer recordings and shared files use multi-window decoding. Hallucination post-filter strips known loop phrases only at the tail. |
| Verification coverage | Sparse post-processing coverage | Unit tests now cover cleanup modes, cleanup-mode persistence, punctuation command isolation, multilingual capitalization, and VAD download integrity behavior |

---

## 🛣️ Roadmap

**Deferred from the current STT correctness and measurement pass:**

- [ ] **[J] GPU / Vulkan / OpenCL delegate on Android** — vendored whisper.cpp is CPU-only. Switching to GPU would require forking the build, adding `GGML_VULKAN=ON` / `GGML_OPENCL=ON` to the project-owned CMakeLists, and shipping per-driver fallbacks. Vulkan on Android is immature, the community has reports of bad WER regressions on some Adreno/Mali GPUs, and it adds ~15 MB to the APK. Net benefit is unclear.
- [ ] **Streaming refinements** — the experimental Dictate-screen preview currently uses fixed overlapping windows. Add VAD-aligned windows, device-calibrated intervals, bounded decoder context, and equivalent live feedback for the accessibility overlay without pasting provisional text.
- [ ] **Audio resampling quality** — replace the current linear resampler with a streaming band-limited/windowed-sinc resampler before 16 kHz Whisper inference; select the cleanest stereo channel rather than always averaging channels.
- [ ] **Pixel calibration UI** — run the benchmark matrix for model, quantization, thread count, VAD, audio source, and decoding profile; persist the best sustainable configuration per device.

**Open items:**

- [ ] **Foreground service for background recording** — add a `<service android:foregroundServiceType="microphone">` so the mic can stay open when the user navigates away mid-dictation. Re-add the `FOREGROUND_SERVICE_MICROPHONE` permission at the same time.
- [ ] **Real SHA-256 hashes and immutable model revisions** for the 5 Whisper model download map entries.
- [ ] **Window-size-class adaptive UI** — `NavigationRail` for width ≥ 600 dp, foldable support.
- [ ] **Room migrations for v2** — the v1 → v2 migration is a no-op stub; the next schema change will add a real `Migration(2, 3)`.
- [ ] **`MainActivity` reads `themeMode` from `VozLocalApp.repository`** and passes it to the top-level `MyApplicationTheme` (currently the theme is re-applied inside `MainScreen` as a workaround).
- [ ] **Detekt + ktlint + CI** for static analysis.
- [ ] **Instrumented UI tests** for the dictation flow, dictionary CRUD, and model download.

---

## 📄 License & Acknowledgments

- **whisper.cpp** — Georgi Gerganov's [`whisper.cpp`](https://github.com/ggerganov/whisper.cpp) C++ library (MIT).
- **OpenAI Whisper** — the original Whisper model weights (MIT).
- **License** — MIT. See [LICENSE](LICENSE).
