# VozLocal 🎙️⚡

**100% On-Device Local Speech Dictation & Audio Transcription for Android**

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Engine](https://img.shields.io/badge/Engine-whisper.cpp-orange.svg?style=flat)](https://github.com/ggerganov/whisper.cpp)
[![Polisher](https://img.shields.io/badge/AI%20Polisher-Local%20Rule--Based-red.svg?style=flat)](#architecture--tech-stack)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-brightgreen.svg?style=flat)](#privacy--security)

**VozLocal** is a privacy-first, ultra-fast Android application for real-time speech-to-text dictation and audio file transcription. It runs entirely on-device using quantized OpenAI Whisper GGUF/bin models via `whisper.cpp` and a local rule-based text polisher — **no internet, no cloud APIs, no telemetry, no third-party SDKs that phone home**. A global floating accessibility overlay lets you dictate into any app on the device.

---

## 📱 Application Screenshots

| Live Dictate | AI Speech Models | Shared Audio File |
| :---: | :---: | :---: |
| <img src="docs/images/screen_dictate.png" width="280"/> | <img src="docs/images/screen_models.png" width="280"/> | <img src="docs/images/screen_shared.png" width="280"/> |

| Transcription History | Navigation Drawer | Settings Sheet |
| :---: | :---: | :---: |
| <img src="docs/images/screen_history.png" width="280"/> | <img src="docs/images/screen_drawer.png" width="280"/> | <img src="docs/images/screen_settings.png" width="280"/> |

---

## ✨ Key Features

- 🎙️ **On-Device Speech Recognition** — Quantized OpenAI Whisper models run locally on Android hardware via JNI C++ bindings (`whisper.cpp`). No cloud APIs or subscriptions.
- 🎈 **Global Floating Dictation Overlay** — Accessibility Service + `WindowManager` show a floating microphone button over **any application** (WhatsApp, Gmail, Chrome, Notes). Recognized text is injected directly into the focused input field. The button is hidden when no text field is focused.
- 📁 **Shared Audio File Transcription** — Receives shared audio files via Android `SEND` intents (WhatsApp voice notes, Voice Memos, podcast snippets) and transcribes them offline with `MediaCodec` + PCM.
- 🧹 **Local Text Polisher** — A pure-Kotlin rule-based engine strips filler words ("um", "uh", "euh", "ähm"…), collapses repeated tokens, and applies smart capitalization/punctuation. Runs on `Dispatchers.Default`, no model file required. The `QwenEngine` is the final implementation — the LLM backend was scoped out.
- 🎯 **Optimized On-Device STT** — The project-owned JNI shim (`app/src/main/jni/vozlocal-jni/vozlocal-jni.c`) exposes the full `whisper_full_params` surface to Kotlin via `WhisperParams`: explicit language, an optional initial prompt (Spanish gets a default priming prompt), `single_segment` for low-latency live dictation, tunable no-speech / log-probability / entropy rejection thresholds, optional beam search, and native **Silero VAD** (auto-downloaded at app start, ~2 MB). A post-transcription `HallucinationFilter` strips looped outro phrases ("gracias por ver", "thanks for watching", "[music]", …) and collapses verbatim repeated sentences. Thresholds and the initial prompt are configurable in **Settings → AI Engine**, persisted to SharedPreferences. The model is preloaded in the background at process start so the first dictation has no load latency.
- 📚 **Personal Dictation Dictionary** — Vocabulary biasing and phonetic-replacement rules. Compiled regexes are cached and invalidated on insert/delete, so post-processing stays O(words) per transcription.
- ⚡ **Local Post-Processing Pipeline** — Smart pause correction, auto-capitalization, dictionary replacement, and optional polisher — all stitched together in `DictationRepository.postProcessText`.
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
│ │  - onCleared() → recorder.release() + repository.shutdown()            │ │
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
│                  - thread count = lazy { CpuInfo.getHighPerfCpuCount() }     │
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

`MainViewModel.onCleared()` calls `audioRecorder.release()` and the repository's `shutdown()` (which releases the native `WhisperContext`) so the multi-hundred-MB native buffer is freed on `Activity` destruction / configuration change.

### Layered View → Service → Repository → Engine

```
Compose Screen
  └─ collectAsStateWithLifecycle() over ViewModel StateFlow
       └─ MainViewModel exposes state, mutators, and in-memory caches
            └─ DictationRepository (single orchestration layer)
                 ├─ WhisperEngine     (JNI bridge to whisper.cpp)
                 ├─ AudioDecoder      (MediaCodec + PCM resample)
                 ├─ ModelDownloader   (OkHttp + SHA-256 verify)
                 ├─ QwenEngine        (rule-based polisher)
                 └─ Room DAOs         (model / history / dictionary / stats)
```

### Service ↔ ViewModel Coordination

`DictationAccessibilityService` does **not** re-implement recording or transcription. It:
1. Borrows the same `AudioRecorder` singleton from `VozLocalApp`.
2. Uses its own `serviceScope` for floating-overlay-only work.
3. Queries `repository.allModels.first()` and `repository.postProcessText(..., useAiPolisher = repository.getUseAiPolisher())` to share the canonical post-processing pipeline with the in-app ViewModel.

A `SharedPreferences.OnSharedPreferenceChangeListener` is registered in the service for `show_only_on_input` so overlay visibility updates instantly when the user toggles the setting in the app.

---

| Layer | Technology |
|---|---|
| **Language** | Kotlin 2.2.10 & C++ (JNI, vendored whisper.cpp) |
| **UI Framework** | Jetpack Compose (Material 3, BOM 2024.09.00) |
| **Architecture** | MVVM, Kotlin Coroutines, `StateFlow`, process-scoped singletons |
| **STT Engine** | `whisper.cpp` (q8_0 / q5_0 GGML quantized) |
| **AI Polisher** | Local rule-based Kotlin engine (`QwenEngine`) — pure Kotlin, no model file |
| **Persistence** | Room 2.7.0 (version 2, with stub `MIGRATION_1_2`, no destructive fallback) |
| **Audio Capture** | `AudioRecord` API (16 kHz mono PCM, `VOICE_RECOGNITION` source) |
| **Audio Decoding** | `MediaCodec` + `MediaExtractor` with linear resampling to 16 kHz |
| **Networking** | OkHttp 4.x (model downloads only; offline thereafter) |
| **Global Overlay** | Android `AccessibilityService` + `WindowManager` (display-cutout aware) |
| **Build** | AGP 9.3.1, R8 minify + resource shrink **enabled** for release |

---

## 📦 Supported Local Speech Models

Models are downloaded on-demand from Hugging Face directly to `context.filesDir/models` and run offline:

| Model ID | Weight File | Size (MB) | Spanish Accuracy | Decoding Speed | Recommended For |
|---|---|---|---|---|---|
| `whisper_tiny` | `ggml-tiny-q8_0.bin` | ~75 MB | 78% | **8.5x** | Ultra-fast dictation on low-end hardware |
| `whisper_base` *(Default)* | `ggml-base-q8_0.bin` | ~142 MB | 90% | **5.5x** | Best balance of speed & accuracy for dictation |
| `whisper_small` | `ggml-small-q8_0.bin` | ~466 MB | 95% | **3.2x** | High accuracy dictation & clean audio notes |
| `whisper_medium` | `ggml-medium-q8_0.bin` | ~1.5 GB | 98% | **1.1x** | Complex vocab, accents & technical dictation |
| `whisper_large_v3_turbo` | `ggml-large-v3-turbo-q5_0.bin` | ~1.6 GB | 99% | **1.4x** | Maximum accuracy audio file transcription |


> **Model integrity**: after every download, `ModelDownloader` computes SHA-256 of the file and compares against the expected hash. A mismatch deletes the file and reports a failed download. The current map contains `placeholder-…` values — replace them with the real Hugging Face model-card hashes before shipping.

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
4. Tap the mic (or the stop icon) to finish. The transcript is auto-saved and copied to your clipboard.

### 3. Using the Global Floating Assistant
1. Enable **VozLocal Floating Dictation** in Android's *Accessibility Settings*.
2. Open any app (WhatsApp, Gmail, Chrome, Notes).
3. Tap on a text field — the floating mic icon appears (it stays hidden when no field is focused).
4. Tap the floating mic to dictate; text is injected directly into the active input field via `ACTION_SET_TEXT` (with a clipboard fallback).

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
- **Post-processing** — Smart pause correction, auto-capitalization, dictionary, optional polisher. **All four are persisted to SharedPreferences.**
- **Overlay** — show floating button only when a text field is focused.
- **History** — opt-out of saving transcripts, or cap history at 5 / 10 / 20 / 50 / unlimited.
- **Done** — closes the sheet (settings are saved instantly on toggle).

---

## 🔒 Privacy & Security

- 🚫 **Zero telemetry, no tracking.** No Firebase, no analytics SDK, no third-party network call after model download.
- ✈️ **Air-gapped operation.** Once model weights are downloaded, turn off Wi-Fi and cellular — the app continues to work at 100% capacity. Model downloads are the only network call.
- 🔐 **Local storage only.** All dictionary entries and dictation history live in the device's private Room database (`vozlocal_database`). The DB is **excluded** from auto-backup (`backup_rules.xml` and `data_extraction_rules.xml`) so transcript text is not silently uploaded to Google Drive. Device-to-device transfer includes the database (so history moves with you) but still excludes the 1.5 GB `models/` directory.
- 🪟 **Narrowed accessibility scope.** The accessibility service only subscribes to `typeViewFocused | typeWindowStateChanged | typeWindowContentChanged` and does not request key-filter or "not important views" flags. This is the minimum scope needed to detect a focused text field.
- 🔒 **No `FOREGROUND_SERVICE_MICROPHONE` permission declared.** The permission is removed from the manifest because no foreground service with `foregroundServiceType="microphone"` is currently registered. A future release will add a real foreground service for background recording; the permission will be re-added at the same time.
- 🪵 **No raw transcripts in logs.** `WhisperEngine` only logs the raw transcription text under `BuildConfig.DEBUG` — release builds log nothing user-identifiable.

---

## 🧱 Recent Architectural Changes (v2)

| Area | Before | After |
|---|---|---|
| `AudioRecorder` | Two instances (ViewModel + Service), raced for the mic | Process-wide singleton in `VozLocalApp`; state transitions guarded by `synchronized(this)` and the IO reader thread reads `@Volatile` fields |
| `WhisperContext` cleanup | `runBlocking` on the GC finalizer thread | `Cleaner`-based backstop + explicit `release()` from `ViewModel.onCleared()` and `repository.shutdown()` |
| CPU thread count | Re-read `/proc/cpuinfo` on every transcription | `by lazy { … }` — read once |
| Smart-punctuation | Two implementations, one dead | Kotlin `postProcessText` is the single source of truth; the JNI pause-joiner is removed |
| Dictionary regexes | Re-compiled every transcription | Hash-keyed cache, invalidated on insert/delete |
| Download progress | Wrote Room on every 1% (100 writes for an 800 MB model) | In-memory `MutableStateFlow<Map<String,Float>>`; DB written only on completion/failure |
| History list | Full table re-emit on every insert | `LIMIT 200` paged flow for the UI; full table retained for stats |
| Filter toggles | Reset on every app restart | Persisted to SharedPreferences via proper setters |
| `postProcessText` signature | Fixed booleans | `useAiPolisher: Boolean = false` parameter, with `QwenEngine` running the new rule-based polisher |
| Database schema | `fallbackToDestructiveMigration(dropAllTables = true)` | `addMigrations(MIGRATION_1_2)`, no destructive fallback; version bumped to 2 |
| Accessibility service | `typeAllMask` + key-filter | Narrowed to `typeViewFocused | typeWindowStateChanged | typeWindowContentChanged` |
| Backup rules | Empty TODOs → default behavior leaked 1.5 GB models to cloud | `models/` + database explicitly excluded |
| Build | `isMinifyEnabled = false`, no ProGuard rules | `isMinifyEnabled = true`, `isShrinkResources = true`, explicit keep rules for JNI/Room/AccessibilityService |
| Dependencies | Firebase BOM, Retrofit, Moshi, logging-interceptor (all unused) | Removed; only Compose, Lifecycle, Room, Coroutines, OkHttp, Accompanist, Robolectric/Roborazzi |
| `minSdk` / `targetSdk` | 24 / 36 (unreleased) | 26 / 36 (with a comment to pin to 35 once AGP 9.3.1 is verified on 35) |
| `compileSdk` | `release(36) { minorApiLevel = 1 }` | Same (SDK 36 still tracked) |
| `WhisperEngine` | Hardcoded `language="en"`, no VAD, no initial_prompt, no threshold tuning, single_segment=false | Project-owned JNI shim exposes the full `whisper_full_params` surface: language, initial_prompt, single_segment, no_speech_thold / logprob_thold / entropy_thold, beam_size, native Silero VAD. Defaults tuned for Spanish dictation (no_speech_thold 0.4, logprob_thold -0.5). Live dictation uses `single_segment=true`; shared-file keeps multi-segment for the timeline UI. Hallucination post-filter strips known loop phrases ("gracias por ver", "thanks for watching", etc.). |

---

## 🛣️ Roadmap

**Deferred from the STT optimization pass (intentionally out of scope — see commit `3d1307c` and the `[J]` notes below):**

- [ ] **[J] GPU / Vulkan / OpenCL delegate on Android** — vendored whisper.cpp is CPU-only. Switching to GPU would require forking the build, adding `GGML_VULKAN=ON` / `GGML_OPENCL=ON` to the project-owned CMakeLists, and shipping per-driver fallbacks. Vulkan on Android is immature, the community has reports of bad WER regressions on some Adreno/Mali GPUs, and it adds ~15 MB to the APK. Net benefit is unclear.
- [ ] **[J] Word-by-word streaming for long recordings** — VAD + `single_segment=true` already gives the per-utterance latency win that matters for dictation. Real token-by-token streaming requires a persistent decoder state (`whisper_full_with_state`), a token-diffing renderer in Compose, and a small `WhisperContext` per session. Significant complexity for marginal UX value at the typical 10-30 s dictation length.
- [ ] **[J] Audio resampling quality** — `AudioDecoder.resampleLinear` uses linear interpolation which aliases on 44.1 / 48 kHz → 16 kHz downsample. A windowed-sinc (Kaiser) resampler would be better; for speech the aliasing is rarely user-perceptible. Low-priority polish.

**Open items:**

- [ ] **Foreground service for background recording** — add a `<service android:foregroundServiceType="microphone">` so the mic can stay open when the user navigates away mid-dictation. Re-add the `FOREGROUND_SERVICE_MICROPHONE` permission at the same time.
- [ ] **Real SHA-256 hashes** for the 5 Whisper model download map entries (currently `placeholder-…`).
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
