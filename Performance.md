# VozLocal performance record and roadmap

This document is the engineering record for local transcription performance. It
separates **measurements** from code inspection, user observations, and future
hypotheses. A result is not a general model ranking unless it identifies the
device, software configuration, audio, and test conditions used to obtain it.

Last updated: 2026-09-06.

## Current inference path

VozLocal transcribes locally with `whisper.cpp` and quantized GGML/`bin`
Whisper models. On Android ARM64, the current production path is CPU inference:

- A baseline ARMv8-A CPU module is always available.
- Automatic mode can select capability-checked dot-product, FP16/dot-product,
  or I8MM CPU modules. Unsupported code is not executed; Compatibility mode
  remains available.
- Backend selection is process-wide. It is initialized before the first
  `WhisperContext`, so changing the setting takes effect after a process restart.
- The engine uses adaptive thread selection and passes the model identity to
  that policy. Small and Base are capped at five threads; Tiny at three; Medium
  and Large may use one more thread, within the device cap.
- Dictation uses the full Whisper context with timestamp tokens enabled. Shared
  audio uses timestamp-guided, multi-window decoding and decoder context.
- The active model may be loaded and warmed after a permitted editable field is
  focused. This reduces avoidable load/page-fault work, but its latency and
  energy benefit has not yet been measured as a separate result.

There is no GPU, NPU, cloud, or third-party inference route in the shipped
path. `INTERNET` is used only for user-directed model downloads.

## Measurement vocabulary

- **Inference time** is the time spent in transcription inference for an audio
  excerpt. It is not APK startup, model download, recording, audio decode, or
  post-processing time.
- **Real-time factor (RTF)** is inference time divided by audio duration. Lower
  is faster; below `1.0` is faster than real time.
- **WER/CER** are word/character error rates against a hand-verified reference.
  No WER/CER was reported for the initial Pixel experiment because its fixture
  did not have a hand-verified transcript.
- **Cold start**, **model load**, and **warmup** must remain distinct from warm
  inference in future reports.

The `benchmark` package already models the configuration needed for comparable
runs: model and quantization, thread count, language, beam size, temperature
increment, VAD state, audio source, backend mode/tier, native build identifier,
streaming state, and cold-start state. Result records support audio duration,
model load, optional warmup, inference, optional peak resident memory, thermal
status before/after, reference/hypothesis, and deterministic Unicode-aware
WER/CER. It is a data model and scorer today, not yet a complete user-facing
benchmark runner or exporter.

## Measured Pixel 8 Pro result

### Configuration

The following is the only published device measurement set:

| Field | Value |
|---|---|
| Device | Google Pixel 8 Pro, Tensor G3 |
| Backend | Automatic ARM CPU backend |
| Thread count | Five threads for Small; six tested for Large v3 Turbo |
| Language | Spanish decoding |
| Decode behavior | Current full-context live parameters |
| Audio | Fixed 10-second excerpt from `app/src/test/resources/test_audio_2min.ogg` |
| Power / thermal state | Battery Saver off; Android thermal status `NONE` |
| Accuracy reference | None; outputs were inspected but not scored with WER/CER |

| Model / quantization | Runs | Inference time | RTF | Observation |
|---|---:|---:|---:|---|
| Whisper Small q5_1 | 2 | 8.45 s, 8.64 s | 0.85–0.86 | Stable on both runs; omitted a short word relative to q8_0 on this excerpt. |
| Whisper Small q8_0 | 2 | 4.47 s, 4.92 s | 0.45–0.49 | Minor whitespace variation; retained the more grammatically complete phrase on this excerpt. |
| Whisper Large v3 Turbo q5_0 | 1 per thread count | 37.38 s at 6 threads | 3.74 | Much slower than either Small variant on this CPU; five and four threads were slower. |

### What this result supports

- On this exact workload, Small q8_0 had a median inference time about 45% lower
  than Small q5_1, even though q8_0 is the larger download.
- Small q8_0 was faster than real time in both measured runs.
- Large v3 Turbo q5_0 was substantially slower than real time on the Pixel CPU;
  the name “Turbo” is not a mobile-CPU performance guarantee.
- A smaller quantized file did not imply faster inference. Quantization changes
  memory traffic and the ARM kernels exercised, so q5_1 versus q8_0 must be
  benchmarked rather than inferred from file size.

### What this result does *not* support

- It does not establish WER, CER, or a general accuracy ranking.
- It does not establish p50/p95 latency, sustained performance, battery use,
  memory use, thermals over time, or streaming latency.
- It does not generalize to another language, recording environment, audio
  duration, phone, backend tier, or future native build.
- It must not be used to make universal claims that q8_0 is always faster or
  more accurate than q5_1.

## Observations and open risks

### Battery and thermal state

Battery Saver was noticed to be enabled during an earlier subjective comparison
on the Pixel and was turned off before controlled testing. No retained
before/after measurement isolates its effect, so this is a test-control warning,
not evidence for a specific slowdown. Future runs must record Battery Saver,
charge level, battery temperature, Android thermal status, ambient conditions,
and whether the device was charging.

### CPU module dispatch

Automatic mode dynamically loads a capability-scored ggml CPU module. Its native
diagnostics include the selected tier, feature list, HWCAP values, and native
build identifier. A persisted probe sentinel forces Compatibility mode after an
interrupted automatic-module load or first warmup, preventing a crash loop.

A compatibility-backend pass produced a slow decoder-fallback outlier for Small
q8_0. It is a reason to measure tail latency and native decoder fallback events;
it is not enough evidence to reject Automatic mode or q8_0.

### Thread selection

The current `WhisperCpuConfig` derives a high-performance-core count from CPU
frequency groups, reserves CPU capacity for recording/UI work, and caps common
mobile devices to avoid oversubscription and thermal throttling. This was
helpful on Tensor G3's 1+4+4 layout: the Pixel result used the prime plus four
larger Cortex cores for Small.

Code inspection identifies a portability risk, not a device measurement: a SoC
with two highest-clocked prime cores and several lower-clocked but still powerful
performance cores can be reduced to a two-core “high-performance” count by the
current frequency heuristic. That would underuse a Snapdragon-style 2+6 Oryon
layout. Any profile for a Galaxy S26-class device must measure two through six
threads and should classify performance clusters explicitly rather than equating
“not maximum frequency” with “efficiency core.”

### Progress estimates

Shared-audio progress currently uses model-family timing estimates in the UI.
They are estimates for smooth progress reporting, not benchmark results, and are
not calibrated by device, quantization, backend, or thermal state. Replace them
with local calibration data or clearly preserve them as approximate progress
only; they must never appear as a performance promise.

### Model warmup

Warmup touches the active model and GGML graph before the user dictates. It can
improve perceived first-use latency, but it can increase background CPU/RAM
pressure and energy consumption. Measure it separately from model load and
inference, including memory-pressure behavior and the cost of warming a model
that the user changes before dictating.

## Acceleration assessment

### GPU: highest-priority experiment after CPU calibration

The app currently builds CPU backends only. The vendored ggml/whisper.cpp source
has accelerator-capable build options, but an Android GPU integration would add
runtime initialization, driver-compatibility, model-placement, error handling,
and fallback work.

GPU acceleration is most promising for Medium and Large v3 Turbo, where CPU
latency is high enough to amortize backend setup. It may not help short Small
q8_0 dictations: launch/transfer overhead and highly optimized integer CPU
kernels can erase a GPU advantage. A GPU experiment should retain CPU as the
unconditional fallback and verify transcript/timestamp parity before presenting
the option to users.

Required acceptance gates:

1. Build only behind an experimental flag; do not replace CPU as the default.
2. Detect a compatible GPU/backend at runtime and fall back before recording if
   initialization, allocation, or inference fails.
3. Benchmark cold and warm short dictation, 30-second windows, shared files, and
   a 10–20-minute sustained load against the same CPU build.
4. Report p50/p95 latency, WER/CER, transcript/timestamp differences, RAM,
   battery temperature, thermal status, and energy where available.
5. Promote a device/model/backend combination only when it is faster, reliable,
   and does not materially regress Spanish accuracy.

### Tensor TPU/NPU: valuable feasibility study, not a drop-in backend

Tensor G3 contains a dedicated ML accelerator. VozLocal's current GGML Whisper
weights cannot be sent to it directly. A TPU path needs a LiteRT/TFLite-compatible
model graph and a separate runtime. Delegation is operation- and device-specific:
unsupported subgraphs can run on CPU or GPU, which may remove the expected
benefit.

The viable product shape is therefore a parallel, opt-in engine rather than a
rewrite of the CPU engine:

- Obtain or convert a multilingual ASR model to LiteRT/TFLite.
- Request NPU with GPU/CPU fallback, while recording which accelerator actually
  ran each relevant graph partition.
- Keep the existing whisper.cpp CPU engine as the reference and fallback.
- Validate Spanish accuracy, punctuation, timestamps, long-form coherence,
  startup, model size, memory, battery, and thermal behavior.

Available TFLite-style candidates need different treatment:

| Candidate | Status for VozLocal |
|---|---|
| Whisper TFLite conversion | Closest to current Whisper behavior and supports multilingual Small in community Android projects; NPU coverage and output parity remain unproven. |
| Moonshine TFLite Tiny/Base | Readily packaged LiteRT models optimized for live ASR, but the official TFLite release is English-only, so it is not a Spanish replacement. |
| Other multilingual ASR models | Potential research candidates, but none is currently accepted as a drop-in, TPU-validated Spanish engine for VozLocal. |

NPU work should begin only with a concrete model license, conversion pipeline,
device coverage policy, and an accuracy corpus. It has the strongest potential
for sustained efficiency, but the highest model/runtime maintenance cost.

## Future benchmark plan

### Phase 1 — make measurements reproducible

1. Add a benchmark runner that can disable normal preload and accept explicit
   model, backend, thread count, clip, language, VAD, streaming, and iteration
   inputs.
2. Export structured local results containing native load/warmup/encode/decode
   timings, decoder fallback count and temperature steps, RTF, memory, thermal
   state, and device/build metadata. Never export audio or transcript text by
   default.
3. Create a versioned, hand-verified corpus: clean/noisy Spanish, names and
   numbers, code-switching, short utterances, 30-second boundaries, and long
   recordings. Keep consent and licensing documentation with every clip.
4. Alternate model/backend order, randomize within temperature constraints, and
   run enough repetitions to report median, p95, and outliers.

### Phase 2 — complete Pixel 8 Pro CPU calibration

1. Repeat Small q8_0 and q5_1 across the corpus and report WER/CER.
2. Test threads 1–6 per model, separately for short dictation, 30-second
   windows, and shared audio.
3. Compare Compatibility and Automatic backend tiers; record selected module and
   native build identifier.
4. Repeat from cold state, warmed state, low/normal/high charge, and controlled
   thermal conditions. Add a 10–20-minute sustained workload.
5. Use a full-context retry only where a duration-binned/short-context path
   produces empty, repetitive, low-confidence, or visibly truncated output.

### Phase 3 — adaptive CPU policy

1. Replace the frequency-only core heuristic with a topology-aware policy that
   distinguishes efficiency, performance, and prime clusters without discarding
   useful performance cores.
2. Add opt-in per-device/model calibration. Save profiles keyed by device,
   backend tier, model checksum, and native build identifier; invalidate stale
   profiles automatically.
3. Preserve a user-selectable Compatibility mode and a safe thread cap. Never
   allow automatic calibration to make the app unusable.
4. Test Snapdragon, Tensor, Exynos, and MediaTek devices independently. A Galaxy
   S26-class Snapdragon device is a priority test target, but no S26 VozLocal
   timing has been measured yet.

### Phase 4 — GPU experiment

Implement the guarded GPU prototype and acceptance gates above, prioritizing
Large v3 Turbo and Medium. Small q8_0 is a control workload, not the first
promotion target.

### Phase 5 — TPU feasibility prototype

Build a minimal LiteRT prototype for one multilingual candidate, first measuring
what graph partitions actually delegate on the Pixel 8 Pro. Compare it against
the CPU Whisper reference using the same corpus and acceptance gates. Stop the
project if fallback-heavy execution, accuracy loss, model size, startup, or
maintenance cost outweighs sustained-latency/energy benefits.

## Reporting rules

- State the exact device and SoC/SKU. “Galaxy S26” is insufficient because
  regional chipset variants can differ.
- Record app version/commit, native build identifier, model checksum,
  quantization, selected backend/tier, and effective thread count.
- Never compare cold and warmed runs as if they are equivalent.
- Never equate model download size with accuracy, RAM use, or speed.
- Publish raw per-run values alongside summaries; do not hide slow fallback or
  thermal-throttling outliers.
- Do not change the default model/backend solely on latency. Accuracy, failure
  behavior, sustained performance, battery, and privacy-preserving fallback are
  co-equal release criteria.

## References for future accelerator work

- [LiteRT NPU deployment and fallback](https://ai.google.dev/edge/litert/next/npu)
- [LiteRT delegate behavior](https://developers.google.com/edge/litert/performance/delegates)
- [Moonshine TFLite models](https://github.com/moonshine-ai/moonshine-tflite)
- [Community Whisper TFLite Android implementation](https://github.com/nyadla-sys/whisper.tflite/blob/main/whisper_android/README.md)

These links describe external technology options. They are not evidence that a
specific model/backend is faster, accurate, or supported by VozLocal until it
passes the measurement plan in this document.
