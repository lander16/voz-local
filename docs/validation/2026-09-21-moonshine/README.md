# Pixel Moonshine feasibility and complete-clip screening

Decision: **promising; continue validation, not eligible for production selection yet**.
Scope and remaining work: [#11](https://github.com/lander16/voz-local/issues/11)
and [the implementation plan](../../moonshine-experiment-plan.md).

## Provenance

- Source: `859d8c8` (instrumentation and staging implementation). Documentation
  changes were uncommitted during these runs; they do not change the tested code.
- Device: Pixel 8 Pro / Tensor G3, Android 17 (SDK 37), build `CP2A.260805.005`.
- Validation app APK SHA-256:
  `b770df044d3ff9ad5bf2605c8175227d51b93d8413437febf675494c1d09d501`.
- Instrumentation APK SHA-256:
  `fbd331ce7d9ae2378f1ae3b4d91167e218e8dd67c18349726835c0db0df78686`.
- Moonshine SDK/AAR 0.1.5, SHA-256:
  `ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571`.
  AAR native binaries are prebuilt; local test code is not a release-shrunk app.
  `native_header_version` identifies the compiled header, not a runtime version query.
- Model manifests beside this file preserve all eight asset sizes, CRC32C and
  SHA-256 values and official dated URLs. Their file hashes match the reports.
  Whisper's official q8_0 digest is recorded in each control report.
- Fixture: first 10 seconds of repository `test_audio_2min.ogg`; source SHA-256
  `19d015c3c78fe806134ddffbe144d216263b4d195b27cd7799ea667a3c575ba1`.
  Converted using ffmpeg to mono 16 kHz float32 little-endian, 160,000 samples.
  PCM SHA-256 `23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a`.
  No verified reference or redistribution permission is established for this
  fixture. Audio and transcripts are deliberately absent from these artifacts.
- All retained runs: Battery Saver off, not charging, Android thermal status
  `NONE` before/after. Checks before/after iterations reject moderate-or-worse
  thermal state; individual thermal readings are not retained per iteration.
  Earlier preflight battery snapshot: 52%, 31.8°C (not a per-run measurement).
- Host finished downloads/staging before inference. Device tests were serial.
  User/background phone activity was not independently controlled. No energy or
  peak-memory measurement, airplane-mode verification, or bank-app interaction.

## Run order and result

First: Tiny (3 warm runs), Small (3), Whisper Compatibility (3). Each test process
loads once, runs a separate full-clip warmup, then measured calls. Initial ADB
transport interruption yielded no valid result and is excluded, not counted as
an engine success or zero-time inference.

The fresh validation app defaulted to Whisper Compatibility. That is retained
explicitly in `round-1-whisper-small-q8-control.json`; it is **not** the optimized
baseline. After explicitly selecting Automatic for the next test process, the
second order was Whisper Automatic (10), Small (10), Tiny (10). Do not pool
Compatibility and Automatic results or call this a balanced randomized trial.

| Second-pass configuration | Warm runs | Median inference | Observed range | Empirical p95* |
|---|---:|---:|---:|---:|
| Whisper Small q8_0, Automatic/I8MM, 4 threads | 10 | 2,763.5 ms | 2,595–3,220 ms | 3,220 ms |
| Moonshine Spanish Small Streaming, SDK defaults | 10 | 1,880.5 ms | 1,799–1,925 ms | 1,925 ms |
| Moonshine Spanish Tiny Streaming, SDK defaults | 10 | 929 ms | 891–984 ms | 984 ms |

*Nearest-rank p95 of ten samples equals their maximum; this is not a stable
tail-latency estimate. Raw arrays are retained in the adjacent JSON reports.

On this fixture, Small's median inference was 31.95% lower and Tiny's 66.38% lower
than the optimized Whisper control. All completed runs returned nonempty text
and no inference/close error. Nonempty output is not correctness evidence.

Whisper uses Spanish, no context, prompt OFF, four threads and its default other
`WhisperParams`; this differs from some live dictation settings and the older
exploratory benchmark. Moonshine uses its complete-clip API with flags=0 and
SDK default thread/decoder settings. These settings are not assumed equivalent
to Whisper beams/prompts. There is no live capture, VAD preprocessing, cleanup,
UI insertion or streaming replay in these timings.

The fields named `cold_load_ms` measure first model load in the test process;
they do not prove a cold OS page cache or cold app launch. Warmup uses the same
full clip and is excluded from the warm medians. The older three-run Small
screen showed substantial variation (2,205–3,552 ms), so repeatability remains
an explicit gate, despite the tighter second pass.

## Inclusion decision and next work

Keep Moonshine in the instrumentation experiment only. Next: a consented/licensed
Spanish/English corpus with references (#2), real-time chunk replay, finalization
and cancellation behavior, memory and sustained thermal testing, thirty finalist
repetitions and a second-day repeat. Once the plan's latency/accuracy/lifecycle
gates pass, introduce the production engine adapter and an English/Spanish
experimental opt-in. Keep Whisper as default and fallback.

Checks completed: six host staging-integrity tests; validation app/test APK build;
debug unit-test task (up-to-date) and lint; six completed model/control test
invocations plus Automatic backend configuration. Test APK contains Moonshine;
the application APK has no Moonshine or ONNX Runtime native libraries. The signed
production app was not replaced by this experiment.
