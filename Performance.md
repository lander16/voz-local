# VozLocal performance record and roadmap

This document is the engineering record for local transcription performance. It
separates **measurements** from code inspection, user observations, and future
hypotheses. A result is not a general model ranking unless it identifies the
device, software configuration, audio, and test conditions used to obtain it.

Last updated: 2026-10-02.

## Stop-to-text latency milestone in progress

Moonshine Spanish Small is the primary candidate for the current user goal of
reducing wait after Stop; it remains explicitly selected and experimental, with
no default or model-promotion change. The former 30-second complete-clip cap was
removed in `40f12c7`. Its current app path verifies the selected bundle and
creates, loads, runs, and closes a native transcriber for each request.

The opt-in `stopToTextTrace` build property records monotonic offset spans for
recorder release and drain, PCM snapshot/reset, trimming, bundle verification,
engine handoff, adapter creation/load/inference/teardown, postprocessing, and
delivery. In-app result state publication and overlay accessibility-action
acceptance are separate endpoints. Action acceptance is not evidence that the
target visibly rendered the text. Nested and overlapping spans retain their
offsets and must not be summed. Trace lines contain no audio or transcript text;
they are disabled by default. One consented in-app microphone trace is retained
at [`docs/validation/2026-10-02-stop-to-text/single-live-mic-trace.json`](docs/validation/2026-10-02-stop-to-text/single-live-mic-trace.json).
It measured 1,238 ms from Stop receipt to ViewModel result-state publication for
a 6.299-second take. Asset verification, model load, and native teardown occupied
566 ms (45.7%) of that one sample's measured critical path. This is enough to
prioritize repeated screening, not to classify the run as cold or warm, claim a
general gain, or justify resident model reuse. Two earlier user takes had no
recoverable trace records; the reason is unknown. Nine further complete traces
are retained at
[`docs/validation/2026-10-02-stop-to-text/repeated-mic-traces.json`](docs/validation/2026-10-02-stop-to-text/repeated-mic-traces.json).
For these distinct microphone takes (5.819–13.059 seconds; different PCM hashes),
Stop-to-state median was 1,681 ms, range 1,098–1,973 ms, with descriptive
nearest-rank p95 1,973 ms (n=9). Median verification/load/teardown was 478 ms
(29% median per-run share); conservatively, median load plus teardown alone was
343 ms (20.4% of the endpoint median). This makes a verified-instance experiment
plausible under the 15% screening gate while retaining full verification before
instance creation, but it is an attribution-based hypothesis, not measured
resident improvement. The takes are not matched PCM, durations vary, warm-cache
state is unproven, and this is not a paired control comparison. The two retained
artifacts contain no raw audio or transcripts. Twenty-to-thirty-second and
sixty-to-ninety-second checks, matched-PCM paired comparisons, p95 regression
gate, and second-day finalist repeat remain outstanding. A subsequent signed
candidate build (`1e95bc5`, APK SHA-256
`534fce7382ee97a2cc00405581bfff13b9fa8fb346978f5d54e6da8e69d47d79`) produced
11 in-app traces: one first-observed create/load and ten subsequent
`moonshine_resident_reused` requests, all for `moonshine_small_es` and all
publishing in-app result state. Complete rows were not durably retained:
terminal output truncation left only IDs 1–3 and 9–11 recoverable; a later
UID/tag-filtered buffer read returned no rows. Partial visible values are
retained without an aggregate in
[`docs/validation/2026-10-02-stop-to-text/resident-candidate-partial.json`](docs/validation/2026-10-02-stop-to-text/resident-candidate-partial.json).
No candidate median, p95, paired gain, or pass of the 15%/10% gate is claimed.
Post-series package memory was 299,110 KB PSS / 411,412 KB RSS, including
188,053 KB native-heap PSS; this is a single post-use sample, not a leak or
retention-stability test. Next capture must write the filtered stream to a
private local file, verify all expected IDs and timing fields, then stop capture
and retain only sanitized metadata.
Build and install the opt-in signed diagnostic release with
`scripts/install-signed-release.sh --skip-tests --stop-to-text-trace`. The script
checks that its certificate matches the installed app before updating it. Capture
only VozLocal's UID and the trace tag, directing output to a private local file
rather than a terminal buffer. Before stopping capture, verify all expected
unique IDs have complete timing rows. Retain the PCM SHA-256 and timings, never
the samples or transcript. Recheck the device state before each run.

## September 7 device-validation follow-up

See [Pixel validation procedure and raw results](Pixel-Validation.md). The isolated
Pixel 8 Pro tests found a missing CPU-graph abort propagation path: cancellation
initially took 7.510 seconds, then measured 45–467 ms over six attempts after the
native fix, with successful subsequent transcription. This is measured Small q5_1
behavior, not a guarantee for every model/backend.

For one 11-second English fixture, four threads had the best median: 15.397 s in
Compatibility and 12.830 s in Automatic/I8MM. These are preliminary batch-native
measurements on Android 17, not new global defaults or a Spanish accuracy result.
The report distinguishes legacy averaged timing fields from corrected native
stage totals, and records the successful 18-trial Tiny calibration/reload check.

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

The following is the original exploratory Whisper measurement set. The later
[Moonshine screening](docs/validation/2026-09-21-moonshine/README.md) uses different
settings and a newer OS build; compare candidates within each set, not across them.

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
device coverage policy, and an accuracy corpus. It may improve sustained
efficiency, but requires a separate runtime integration and validation effort.

### Moonshine Spanish streaming CPU experiment (2026-09-21)

The older TFLite row above describes that particular release, not all current
Moonshine models. Moonshine Voice v0.1.5 now publishes MIT-licensed Spanish Small
and Tiny Streaming bundles. The pinned native catalog resolves them to the
official `download.moonshine.ai` CDN; the older Hugging Face asset mirror omits
them. Upstream bundle metadata totals approximately 121.8 MB for Small and
32.3 MB for Tiny. These are download sizes, not peak runtime memory.

The published `ai.moonshine:moonshine-voice:0.1.5` Android AAR was inspected and
contains arm64-v8a, armeabi-v7a and x86_64 native libraries. Its SHA-256 is
`ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571`.
This establishes an Android CPU experiment route, not Tensor TPU compatibility
or a measured advantage over Whisper. The PCM API permits reusing VozLocal's
recording path. `stopStream` flushes final transcription; cancellation latency
and safe native ownership still require explicit validation.

The [experiment plan](docs/moonshine-experiment-plan.md) defines artifact checks,
an isolated validation runner, paired Pixel measurements and opt-in promotion
gates. Track execution in [#11](https://github.com/lander16/voz-local/issues/11),
with representative accuracy dependent on [#2](https://github.com/lander16/voz-local/issues/2).
Publisher WER and model-size claims must not be substituted for local accuracy,
end-to-end latency or energy measurements. Whisper remains the default engine;
Spanish Tiny/Small are explicitly selected alternative models (see README).
Their app path uses complete clips without streaming previews.
Per-request verification/loading/teardown adds costs outside the screening inference
timings below, so these figures are not promises of app Stop-to-result latency.
For the active latency milestone, Small Spanish is the primary candidate; Tiny is
retained as an optional comparison. The former 30-second complete-clip limit was
removed in `40f12c7`. Do not infer a user-visible advantage or implement residency
from the complete-clip inference-only figures above.

Initial Pixel execution is now verified. On the same ten-second Spanish fixture,
ten warm complete-clip calls measured medians of **2.764 s** for Whisper Small q8_0
(Automatic/I8MM, four threads), **1.881 s** for Moonshine Spanish Small and
**0.929 s** for Tiny (SDK defaults). This is approximately 32% and 66% less inference
time, respectively, on this workload. These are promising screening results, not
streaming/Stop-to-result timings or verified accuracy improvements. Retained
[reports and limitations](docs/validation/2026-09-21-moonshine/README.md) include
model hashes, run order, settings, APK identities and the initial Compatibility
control, which must not be confused with the optimized baseline. Corpus accuracy,
sustained memory/energy and representative accuracy remain unvalidated. Cancellation
discards results but cannot abort Moonshine's native computation; engine switching and
deletion must drain that work before releasing its files. Track integration validation
separately in #11 rather than treating the screening run as app lifecycle evidence.
The [Pixel integration checks](docs/validation/2026-09-22-moonshine-integration/README.md)
exercise both models through the app repository without adding speed or accuracy
measurements.
Moonshine Small may omit a final period in ordinary dictation. The app's Smart
Punctuation setting now supplies a conservative terminal period when the output
ends in a letter or digit; it does not estimate pauses, interior commas or
sentence boundaries. This formatting change is not evidence of model accuracy,
and representative punctuation quality still needs the #2 corpus.

Sources: [pinned model catalog](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/core/moonshine-model-catalog.cpp),
[model metadata](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/core/moonshine-model-file-metadata.generated.cpp),
[models and licenses](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/).

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
