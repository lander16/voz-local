# VozLocal 🎙️⚡

**100% On-Device Local Speech Dictation & Audio Transcription for Android**

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat&logo=android)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Engine](https://img.shields.io/badge/Engine-whisper.cpp-orange.svg?style=flat)](https://github.com/ggerganov/whisper.cpp)
[![LLM](https://img.shields.io/badge/AI%20Polisher-Qwen%202.5%200.5B-red.svg?style=flat)](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-brightgreen.svg?style=flat)](#privacy--security)

**VozLocal** is a privacy-first, ultra-fast Android application designed for real-time speech-to-text dictation and audio file transcription. Running entirely on-device using quantized OpenAI Whisper GGUF/bin models (`whisper.cpp`) and local LLM post-processing (Qwen 2.5 0.5B), **VozLocal** requires zero internet connection, guarantees 100% data privacy, and works across any Android app via a global floating accessibility assistant.

---

## ✨ Key Features

- 🎙️ **On-Device Speech Recognition**: Run quantized OpenAI Whisper models locally on Android hardware via JNI C++ bindings (`whisper.cpp`). No cloud APIs or subscriptions required.
- 🎈 **Global Floating Dictation Overlay**: Accessibility Service integration allows a floating microphone button to appear over **any application** (WhatsApp, Gmail, Chrome, Twitter, Notes). Automatically types recognized text directly into the focused text input field.
- 📁 **Shared Audio File Transcription**: Receives shared audio files via Android `SEND` intents (e.g. WhatsApp voice notes, audio recordings, podcast snippets) and transcribes them offline.
- 🧠 **Local AI Text Polisher**: Optional offline LLM integration (Qwen 2.5 0.5B Instruct GGUF) that removes filler words ("um", "eh", "este"), corrects grammar, and formats dictated text on-device.
- 📚 **Personal Dictation Dictionary**: Vocabulary biasing and custom phonetic replacement rules (e.g., mapping misheard sounds like "bos local" -> "VozLocal") for accurate recognition of jargon, names, and technical terms.
- ⚡ **Local Post-Processing Filters**:
  - **Smart Pause Correction**: Merges long speech pauses into logical sentence structures and punctuation.
  - **Auto-Capitalization**: Automatically capitalizes sentence beginnings.
  - **Smart Input Field Visibility**: Shows floating dictation button only when an active text field is focused.
- 📊 **Performance Stats & Analytics**: Real-time metrics tracking dictation speed (WPM), total words dictated, estimated time saved vs. typing, and accuracy breakdown per model.
- 💾 **Persistent Transcription History**: Built-in Room database storing past dictations and shared audio transcriptions with instant one-tap copy to clipboard.
- 🎨 **Modern Material 3 Jetpack Compose UI**: Dynamic dark aesthetic with Material 3 Hamburger Navigation Drawer (`ModalNavigationDrawer`), Modal Settings Sheet (`SettingsSheet`), fluid micro-animations, canvas audio waveform visualizer, and custom press scaling.
- 🇲🇽 🇧🇷 **Multi-Language Support**: Explicit language target selection (Spanish 🇲🇽, English 🇺🇸, French 🇫🇷, German 🇩🇪, Portuguese 🇧🇷, Italian 🇮🇹, Auto-detect 🌐) eliminating model language auto-detection latency.

---

## 🛠️ Architecture & Tech Stack

```
VozLocal System Architecture
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI (MVVM)                       │
│ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌──────────────┐ │
│ │  DictateTab   │ │  ModelsTab    │ │   SharedTab   │ │  HistoryTab  │ │
│ └───────────────┘ └───────────────┘ └───────────────┘ └──────────────┘ │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │   ModalNavigationDrawer (Custom Dictionary, Stats & Analytics)     │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │     SettingsSheet (ModalBottomSheet for Language & System AI)      │ │
│ └────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Flow / State
┌───────────────────────────────────▼────────────────────────────────────┐
│                             MainViewModel                              │
└──────┬────────────────────────────┬────────────────────────────┬───────┘
       │                            │                            │
┌──────▼────────────────┐  ┌────────▼────────────────┐  ┌───────▼────────────────┐
│  AudioRecorder        │  │ DictationRepository     │  │ DictationAccessibility │
│  (AudioRecord / PCM)  │  │ (Model management & DB) │  │ Service (Global Overlay│
└──────┬────────────────┘  └────────┬────────────────┘  └───────┬────────────────┘
       │                            │                            │
┌──────▼────────────────────────────▼────────────────────────────▼───────┐
│                            WhisperEngine JNI                           │
│                     (LibWhisper / C++ whisper.cpp)                     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                    On-Device Local GGUF/bin Models                     │
│    Whisper Tiny / Base / Small / Medium / Large-v3-Turbo + Qwen 0.5B    │
└────────────────────────────────────────────────────────────────────────┘
```

| Layer | Technology |
|---|---|
| **Language** | Kotlin 2.0+ & C++ (JNI) |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Architecture** | MVVM with Kotlin Coroutines & `StateFlow` |
| **STT Engine** | `whisper.cpp` (q8_0 / q5_0 GGML quantized models) |
| **AI Polisher** | Qwen2.5-0.5B-Instruct (q4_k_m GGUF) |
| **Persistence** | Room Database |
| **Audio Processing** | AudioRecord API & MediaCodec / FFmpeg PCM decoder |
| **Networking** | OkHttp 4.x (Used exclusively for downloading models on user request) |
| **Global Overlay** | Android `AccessibilityService` & `WindowManager` |

---

## 📦 Supported Local Speech Models

Models are downloaded on-demand from Hugging Face directly to internal app storage (`context.filesDir/models`) and run offline:

| Model ID | Weight File | Size (MB) | Spanish Accuracy | Decoding Speed | Recommended For |
|---|---|---|---|---|---|
| `whisper_tiny` | `ggml-tiny-q8_0.bin` | ~75 MB | 78% | **8.5x** | Ultra-fast dictation on low-end hardware |
| `whisper_base` *(Default)* | `ggml-base-q8_0.bin` | ~142 MB | 90% | **5.5x** | Best balance of speed & accuracy for dictation |
| `whisper_small` | `ggml-small-q8_0.bin` | ~466 MB | 95% | **3.2x** | High accuracy dictation & clean audio notes |
| `whisper_medium` | `ggml-medium-q8_0.bin` | ~1.5 GB | 98% | **1.1x** | Complex vocab, accents & technical dictation |
| `whisper_large_v3_turbo` | `ggml-large-v3-turbo-q5_0.bin` | ~1.6 GB | 99% | **1.4x** | Maximum accuracy audio file transcription |
| `qwen2.5_0.5b` *(LLM)* | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~398 MB | N/A | **7.8x** | On-device text polishing & grammar cleanup |

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Ladybug (2024.2.1+) or newer.
- **JDK**: Version 17+.
- **Android SDK**: Minimum SDK 26 (Android 8.0 Oreo), Target SDK 34 (Android 14).
- **NDK**: Android NDK (r25c or higher) for compiling `whisper.cpp` C++ JNI source code.

### Installation & Build

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/voz-local.git
   cd voz-local
   ```

2. **Environment Configuration**:
   Copy `.env.example` to `.env` if custom build configurations are needed:
   ```bash
   cp .env.example .env
   ```

3. **Build the Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Install on Device or Emulator**:
   ```bash
   ./gradlew installDebug
   ```

---

## 📱 How to Use

### 1. Initial Permissions Setup
Upon launching **VozLocal**, complete the 2-step setup wizard:
1. **Microphone Permission**: Required for capturing live audio.
2. **Accessibility Service**: Required for the global floating overlay button and automatic text injection into other apps.

### 2. Live Voice Dictation
1. Select your target language (e.g. Spanish, English, Auto) on the **Dictate** tab.
2. Tap the large circular microphone button to begin speaking.
3. Observe the live audio waveform visualizer and real-time text output.
4. Tap Stop to finish and automatically copy the dictation to your clipboard.

### 3. Using the Global Floating Assistant
1. Enable **VozLocal Floating Dictation** in Android's *Accessibility Settings*.
2. Open any app (e.g. WhatsApp, Gmail, Chrome).
3. Tap on a text field: the floating mic icon will appear.
4. Tap the floating mic to dictate; text is injected directly into your active input field!

### 4. Transcribing Shared Audio Files
1. In WhatsApp, Voice Memos, or Files, select any audio file (`.wav`, `.mp3`, `.m4a`, `.ogg`).
2. Tap **Share** and select **VozLocal**.
3. VozLocal opens the **Shared Audio** tab and transcribes the file locally using your active Whisper model.

### 5. Managing Models & Custom Dictionary
- **Models Tab**: Download and activate different Whisper models or the Qwen 0.5B AI text polisher.
- **Dictionary Tab**: Add custom brand names (e.g. "VozLocal") or technical terms. Add common phonetic misspellings so the model automatically corrects them.

---

## 🔒 Privacy & Security

- 🚫 **Zero Telemetry / No Tracking**: VozLocal does not track, store, or transmit your audio or text logs.
- ✈️ **Air-Gapped Operation**: Once model weights are downloaded, turn off Wi-Fi/Cellular data—the app continues to function at 100% capacity offline.
- 🔐 **Local Storage**: All dictionary entries and dictation history logs remain strictly inside your device's private Room database (`app_database.db`).

---

## 📄 License & Acknowledgments

- **whisper.cpp**: Built upon Georgi Gerganov's incredible [`whisper.cpp`](https://github.com/ggerganov/whisper.cpp) C++ library.
- **Qwen2.5**: Powered by Alibaba Cloud's [`Qwen2.5-0.5B-Instruct`](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) GGUF quantized model.
- **License**: MIT License. See [LICENSE](LICENSE) for details.
