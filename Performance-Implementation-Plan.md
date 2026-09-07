# Transcription performance: audit and implementation plan

Baseline: `921f413f0be25537b87092daf90b8617cd542133`, audited 2026-09-06.
Status: P01–P08 have implementation work, but are **not all acceptance-complete**.
The September 7 follow-up fixes below have host regression coverage. Real native
cancellation, calibration benefit, corpus accuracy, and device latency remain
release gates; the package-name instrumentation test does not validate these.
See [general issues plan](Issues-Implementation-Plan.md) for G01–G19 dependencies
and [historical measurements](Performance.md) for the original experiment record.

## Implementation record (P01–P08)

### September 7 review corrections (working tree)

Host validation: `:app:testDebugUnitTest :app:lintDebug --offline` completed
successfully after these fixes: 199 tests, zero failures/errors/skips; lint zero
errors and 62 warnings. No new device timing or release-validation claim is made.

- P01: real repository/native leases are mandatory. Thread overrides are per
  request; backend/build/tier, PCM/model hashes, and quantization are checked.
  Cold runs allocate once and warm once; warm runs reject a missing resident
  model. Native stage timings are collected inside the same lease. Unmeasured
  verification, decoding, and trimming stages are null, not fabricated zeros.
  This is a batch-PCM runner, not an end-to-end UI benchmark. Streaming, native
  VAD, custom prompts, seed control, and implicit repetitions fail explicitly.
  Cold denotes native-context cold, not OS-page-cache cold.
- P02: missing, unexpected, duplicate, and empty corpus coverage is rejected.
  An explicitly empty hypothesis is scored as deletions, not skipped. The
  manifest/scoring infrastructure is not a recorded, licensed audio corpus or
  proof of q8_0 superiority. Capture audio and publish complete raw results before
  closing the accuracy gate.
- P03: Settings now offers explicit local calibration using a 3–30 second speech
  file. It tests up to six thread counts in three rotated rounds, selecting the
  lowest median among candidates matching the initial transcript. It refuses
  battery saver or moderate/higher thermal state and has a 10-minute inference
  budget plus cancellation. Profiles persist with device/OS, model checksum,
  native build/backend, decoder settings, and short/long workload identity.
  No startup calibration occurs. A matching transcript on one clip is only a
  safeguard, not corpus accuracy proof; Pixel speed/thermal validation is pending.
- P05: replacement verifies before loading and releases the old native allocation
  before allocating the new model. Failed allocation leaves a retryable unloaded
  engine, rather than retaining two potentially large models in RAM.
- P07: the original 31-tap claim was incorrect near Nyquist. The replacement uses
  a rate-scaled Hann FIR with an explicit transition width; synthetic tests cover
  8.05–10 kHz at 32/44.1/48/96 kHz input and require >45 dB rejection. Decimation
  avoids evaluating discarded input frames. Measure total decode+inference cost
  and corpus accuracy on hardware before claiming a performance win.
- P08: parameter support is not completion of the planned prompt/context
  experiments. Duration-binned device experiments and reference transcripts remain.

- **P01:** `b801d72` — Add reproducible transcription benchmarks and native stage metrics.
  Upstream `whisper.cpp` timing counters exposed via JNI (`whisper_get_timings`),
  10-stage monotonic nanosecond timings, cold/warm trials, device/build environment
  metadata, and lossless RFC 4180 CSV / JSON benchmark runner.
- **P02:** `47ec432` — Add Spanish and English accuracy corpus and evaluation manifests.
  Curated 22-clip manifest across duration tiers (short, medium, long, extended)
  and speech categories, plus `AccuracyEvaluationRunner` scoring raw vs cleaned
  WER/CER preserving Spanish diacritics and numeric tokens.
- **P03:** `5634b8c` — Implement topology-aware CPU calibration and thread profiles.
  Extracted pluggable `CpuInfoProvider`, core cluster detection for 1, 2 (e.g. 2+6),
  and 3 clusters (e.g. Tensor G3 1+4+4, Snapdragon 1+3+4), configurable thread priority,
  and persistent `ThreadProfileManager` with model/build invalidation.
- **P04:** `c1b6edf` — Bound audio storage and eliminate avoidable PCM copies.
  Enforced 30-minute recording budget in `FastFloatBuffer`, buffer shrinking to
  reclaim heap outside the recording hot path, clamped initial decoder allocation,
  and overflow-safe doubling math.
- **P05:** `10b26a1` — Coordinate demand-aware model residency and warmup.
  Unselected downloads no longer preload or evict active models, deduplicated
  concurrent preloads, model verification before releasing active contexts,
  engine busy tracking (`isBusy()`) to guard active inferences from memory pressure
  eviction, and model-specific thread count hints during warmup.
- **P06:** `83fbef9` — Eliminate UI recomposition allocations and optimize history retention.
  Memoized per-model `downloadProgressFor` and `downloadStatusFor` StateFlows in
  `MainViewModel`, exposed underlying maps directly, throttled live waveform
  emissions to ~30 FPS with forced boundary emissions, and implemented atomic Room
  SQL history retention pruning in `HistoryDao` and `DictationRepository`.
- **P07:** `cc7b541` — Add anti-aliasing resampling and fix silence trimmer boundary preservation.
  Initially added a 31-tap Hann windowed-sinc filter; the follow-up above replaces
  its insufficient near-Nyquist rejection with a rate-scaled filter preserving
  inter-chunk filter history, preserved the final sub-frame audio tail in
  `AudioSilenceTrimmer`, and implemented a conservative silence shortcut skipping
  Whisper inference on dead audio without dropping quiet speech.
- **P08:** `0fa0b9a` — Add explicit prompt mode configuration and short-context tuning support.
  Introduced `PromptMode` enum (`AUTOMATIC`, `OFF`, `CUSTOM`) in `WhisperParams` and
  `WhisperEngine` to allow disabling the default Spanish priming prompt cleanly,
  plumbed prompt modes through `DictationRepository`, and added duration-binned
  `audioCtx` and `promptMode` configuration with CSV/JSON roundtrip in
  `TranscriptionBenchmarkConfig`.

## Objective and audit corrections

Reduce time from Stop to final usable text, energy per minute of speech, and
peak memory while preserving Spanish recognition, independent-session privacy,
long-file completeness, cancellation, and UI responsiveness. Speed alone does
not justify changing the default model or backend.

Four corrections materially affect implementation:

1. **CPU count is not CPU affinity.** `WhisperCpuConfig` requests a worker count;
   Android schedules those workers. Previous documentation saying the measured
   run “used the prime plus four” cores is stronger than the evidence. No affinity
   or scheduler trace was retained. The heuristic drops the minimum-frequency
   group, rather than selecting only the maximum group on every topology.
2. **Tensor hardware is not confirmed Tensor G3 developer support.** Google's
   current Tensor SDK guide lists Tensor G5 as supported, requires beta access,
   and describes a Linux x86-64 toolchain. LiteRT's Google Tensor route currently
   supports ahead-of-time compilation, not on-device JIT. Pixel 8 Pro G3 is not
   listed. Gate G3 work on demonstrated runtime/compiler support before model
   conversion or integration. [Tensor SDK guide](https://developers.google.com/edge/litert/next/tensor-sdk),
   [LiteRT NPU guide](https://developers.google.com/edge/litert/next/npu).
3. **No measured Galaxy S26 speedup exists.** Prior conversation estimates were
   projections, not results. General CPU scores cannot establish Whisper latency
   or predict identical scaling for Small and Turbo. Do not publish those ranges
   as a purchase recommendation or a performance target.
4. **Streaming is not currently wired into dictation.** Search of production
   Kotlin finds recorder snapshots and a benchmark flag but no preview scheduler
   or streaming setting. A future streaming feature must be implemented and
   tested end to end; previous conversation intent is not evidence it ships.

## What has actually been measured

Historical Pixel 8 Pro, Automatic ARM backend, Spanish, full-context inference,
10-second excerpt from `app/src/test/resources/test_audio_2min.ogg`:

| Model | Threads | Recorded inference | RTF |
|---|---:|---|---|
| Small q5_1 | 5 | 8.45 s; 8.64 s | 0.845; 0.864 |
| Small q8_0 | 5 | 4.47 s; 4.92 s | 0.447; 0.492 |
| Large v3 Turbo q5_0 | 6 | 37.38 s | 3.738 |

Two-run Small medians are 8.545 s and 4.695 s: about 45% lower time for q8_0.
That is an arithmetic summary, not a reliable latency distribution. Turbo's
four/five-thread runs were reported slower, but their numbers are not preserved
in the checked-in record. Do not reconstruct them from memory.

Battery Saver was off and thermal status was NONE during the compact paired
experiment; the phone was at low charge. An earlier q8_0 Compatibility run had a
decoder-fallback outlier. Neither energy nor thermal trends were measured. There
is no hand-verified transcript, raw result bundle, exact excerpt offset/hash,
complete parameter snapshot, or full timing breakdown in the historical record.
Retain these as exploratory observations and create a new reproducible baseline.

## Priorities and expected value

P1 means foundational or directly evidenced waste; P2 means measured optimization
after correctness; P3 means exploratory product/runtime work. No speed multiplier
is promised for any item. Effort: S ≈ half–one day, M ≈ one–three days, L ≈
three–seven days, XL = multiple iterations including device validation.

| ID | Priority | Work | Evidence / expected value | Effort | Dependencies |
|---|---|---|---|---|---|
| P01 | P1 | Reproducible benchmark runner and native metrics | No runnable benchmark/export pipeline today | L | G01–G03 |
| P02 | P1 | Accuracy corpus and q8_0 validation | Strong narrow q8_0 result, inadequate coverage | L | P01, G13 |
| P03 | P1 | Correct topology detection and thread calibration | Frequency heuristic underuses two-cluster performance CPUs | M–L | P01 |
| P04 | P1 | Bound PCM memory and eliminate avoidable copies | Whole recordings/files retained; metadata-sized allocation | L | G04/G09 |
| P05 | P1 | Demand-aware load/warmup/eviction | Competing preloads, model-agnostic warmup, Boolean state | M | G02/G05/G06 |
| P06 | P2 | Stable UI flows, history queries, honest progress | Per-recomposition stateIn creation; row-by-row pruning | M | G12/G14 |
| P07 | P2 | Resampling and silence/VAD tuning | Linear resampling aliases; silent inputs still infer | M–L | P01/P02/G04 |
| P08 | P2 | Decoder/prompt and short-context experiments | Full-context cost/fallback stage unknown | L | G03/P01/P02 |
| P09 | P2 | Opt-in previews with bounded scheduling | Snapshot primitive exists; no streaming integration | L | G01/G07/P01 |
| P10 | P2 | Opt-in Vulkan GPU prototype | Source backend exists; Android result unknown | XL | P01/P02/G01/G02 |
| P11 | P3 | NPU and alternative ASR feasibility | G3 support not established; format/runtime change | XL | Compatibility gate, P01/P02 |

## P01 — benchmark runner and timing contract

**Evidence:** `benchmark/TranscriptionBenchmark.kt` and `TranscriptionScorer.kt`
provide data classes/scoring. `WhisperEngine.kt:82,125` measures wall-clock time;
JNI logs configuration but exports no encode/decode/fallback metrics. Default
startup load and warmup contaminate cold-start tests. Debug native compilation
is configured as Release, but Kotlin/R8 behavior still differs from release.

**Implementation steps:**

1. Introduce an internal/debug-only benchmark entry point, disabled in distributed
   releases. Input: corpus manifest, model/checksum, backend, thread override,
   decoding parameters, cold/warm mode, repetitions, seed, and output destination.
2. Define monotonic stage timing: request queue, verify, model load, warmup, audio
   decode/resample, trim/VAD, native encode, native decode/sample, cleanup, and
   Stop-to-result. Keep recording duration separate. `elapsedRealtimeNanos` or
   equivalent replaces `currentTimeMillis` for intervals.
3. Add JNI request metrics including effective backend/module, model identity,
   threads, processed audio duration, return status, segment count, and upstream
   timing counters. Reset counters per request. Inspect available upstream APIs;
   if fallback count requires instrumentation, isolate a small tracked patch and
   validate it against logs in development.
4. Disable speculative preload for benchmark cold mode and wait for catalog
   readiness. Restart the process between CPU module choices: the registry is
   process-global. Do not claim a live setting switch changed the native backend.
5. Export JSON/CSV plus a manifest with device/build fingerprint, SoC, OS, app git
   revision, native revision/configuration, model and PCM SHA-256, exact excerpt,
   power mode, charge, thermal status/temperature, and monotonic per-stage timings.
6. Store user-audio results privately and export only on request. Public test
   fixtures may include approved reference/hypothesis text; private transcript
   text must not appear in routine diagnostics. Mark unavailable energy/memory
   readings null rather than zero.

**Validation:** deterministic parsing/scoring, zero-duration inputs, stage-sum
consistency, failed/aborted runs, invalid backend/model, monotonic clock behavior,
and schema round-trip. Confirm model is unloaded before a declared cold run and
that the selected native module is the one recorded. Add an actual device smoke
run; label JVM-only results explicitly.
**Deliverable/commit:** runner, schema, sample manifest, README command examples;
`Add reproducible transcription benchmarks and native stage metrics`.

## P02 — controlled model and accuracy evaluation

**Implementation:** curate at least 30 hand-verified Spanish clips spanning
1–3 s, 5–15 s, 25–35 s, and 60–120 s; include immediate speech onset, quiet
endings, background noise, proper names, numbers, code-switching, accents, silence,
and intentional repetition/outros. Add a small English regression set. Record
consent/license, PCM hash, duration, and normalization rules. Preserve accents and
numbers in scoring. Report raw ASR and post-cleanup WER/CER separately.

Use the existing approximately 100.65-second fixture only after verifying its
actual decoded duration and providing a reference. Small q8_0 and q5_1 are the
first pair. Base/Tiny provide memory/speed controls; Turbo/Medium follow after
failure and memory limits are in place.

**Protocol:** screen brightness fixed, Battery Saver off, normal charge and a
documented charging state, cool start within a recorded temperature band, no
concurrent model downloads. Alternate/randomize order and allow cooling between
batches. Take at least 10 warm runs per configuration for screening; collect at
least 30 for shortlisted tail-latency evaluation. Run cold trials separately.
Run a 10–20-minute workload after burst tests. Repeat winners on another day.

**Proposed promotion gate:** at least 15% median Stop-to-result improvement to
justify a new automatic profile, no >10% p95 regression, no increased failure
rate, and no >0.5 percentage-point absolute WER regression on the agreed corpus.
Manually review changed names/numbers/omissions even if aggregate WER passes.
These are provisional engineering thresholds, not claimed statistical confidence;
expand the corpus if differences are within measurement noise. Energy and memory
must fit the agreed device budget. Present q8_0 as a candidate until this passes.
**Commit:** corpus/manifest first, result bundle and conclusion separately.

## P03 — topology-aware CPU calibration

**Evidence:** `WhisperCpuConfig.kt:106` excludes the minimum max-frequency group.
On two-group 2+6 all-performance topology that can leave only two workers. The
fallback ranks `CPU variant`, which is a revision field rather than a capacity
ranking. The model-specific maxCap can allow more threads than available on
small machines when adding one for Medium/Large. A process-lazy preference does
not respond to changing conditions. The wrapper also elevates its worker to
`THREAD_PRIORITY_DISPLAY` without benchmark evidence for that priority.

**Implementation:** extract CPU information behind a testable provider. Prefer
available capacity/topology information, retaining raw groups and uncertainty;
fall back conservatively when sysfs/proc data is denied. Do not infer performance
from revision numbers. Always clamp to accessible CPUs. Keep capability detection
for safe instructions independent of a topology heuristic for speed.

Enumerate 1–6 thread candidates where supported; benchmark per model,
quantization, and short/long workload with the same parameters. Compare normal
versus raised scheduling priority for throughput, UI frames, and recording
stability before retaining the elevation. Do not add hard affinity unless traces
show scheduler placement is a problem and the device experiment proves a gain.

Persist profiles by device/SoC, OS/driver, native build, model checksum, backend,
and workload. Invalidate after those change. User calibration is opt-in, visible,
cancellable, and bounded; it must not run silently on every launch. Keep a safe
default when measurements fail. Thermal policy should use hysteresis and measured
profiles, not oscillate thread counts every callback.

**Validation:** Tensor 1+4+4, two-group 2+6, mixed 1+3+4, homogeneous, 1/2/4-core,
missing/malformed files, and constrained CPU availability. Compare real Pixel
results before/after; no claim about Snapdragon is closed without that hardware.
**Commits:** topology fix; calibration runner integration; profile persistence.

## P04 — bounded audio storage and copies

**Evidence:** recorder `FastFloatBuffer` doubles and never shrinks after reset;
stop copies it in full. Decoder stores the whole output then copies it; trimming
can create another copy and JNI `GetFloatArrayElements` may copy again. Float
PCM at 16 kHz mono costs 64,000 bytes/s, about 230.4 MB/hour for one array,
excluding capacity slack and native copies. Duration metadata can request huge
allocations before actual PCM arrives.

**Implementation sequence:**

1. Enforce configurable recording/import duration and memory budgets with useful
   user feedback. Clamp initial allocation regardless of metadata and check size
   arithmetic for overflow. Add discard without snapshot and shrink retained
   oversized buffers after the session, outside the recording hot path.
2. Measure retained Java heap, native RSS/PSS, and peak overlap of buffers. Use
   bounded blocks or a private temporary PCM spool for large imports. Delete
   spools on completion/cancel and recover stale files at next startup. Any disk
   storage of microphone audio requires updated privacy behavior/documentation.
3. Feed bounded windows with overlap and explicit seek/context accounting;
   preserve timestamps and boundary words. This changes long-file orchestration,
   so compare it to the existing full-file path before enabling by default.
4. Consider direct/native buffers only after traces prove JNI copying matters.
   Define ownership and cancellation lifetime; avoid long critical-array pins.

**Validation:** underestimated/overestimated metadata, 1/10/60-minute inputs,
concurrent UI activity, constrained memory, cancelled imports, and seam accuracy.
Declare a peak-memory target per model/device after measuring model footprint;
do not invent a universal RAM limit. **Commits:** immediate limits/discard;
bounded long-file pipeline; optional buffer transport optimization.

## P05 — load, verification, warmup, and eviction

**Evidence:** startup launches initialization/preload independently; any completed
model download preloads that model even if not selected. `loadModel` releases
the old context before checking the replacement path. Warmup uses a small
`audio_ctx=256`, one token, and no model hint for threads; it does not establish
that the full-context graph is warmed. A warmup failure returns successful load
but can leave the crash-probe sentinel set. Input-focus caching uses a Boolean
loaded flag. Memory pressure checks recording rather than engine busy state.

**Implementation:** G02 owns context identity and busy state. Deduplicate requests
for the selected model; prefer user inference over queued speculative work.
Verify files before evicting an existing context. Cancel obsolete warmups and do
not load inactive downloads. Separate capability probe success, graph warmup,
and model readiness. Distinguish ordinary warmup failure from process interruption
so the next launch does not incorrectly quarantine a usable optimized backend.

Benchmark no warmup, current bounded warmup, and representative graph warmup.
Compare first Stop latency and total startup energy; skip warming on pressure or
rapid model changes. Eviction must recheck an idle lease when executed. Cache
verification by controlled file identity and invalidate on mutation. Preserve
synchronous persistence needed for crash recovery; do not obey lint's `apply()`
suggestion blindly.

**Validation:** app launch→model switch, focus storms, cancelled warmup, memory
pressure during decode, missing replacement, failed probe, successful retry,
and next-launch recovery. **Commit:** `Coordinate demand-aware model residency and warmup`.

## P06 — allocation and database/UI overhead

**Evidence:** `MainViewModel.downloadProgressFor:126` and `downloadStatusFor:131`
create new `stateIn(viewModelScope, …)` flows on each call. `ModelCard:98` calls
them in composition; recomposition creates new sharing jobs retained by the
ViewModel scope. History prune loads all rows then deletes one by one. Shared
progress uses fixed family multipliers and concurrent decode/load callbacks can
make it move backward. Waveform snapshots allocate a boxed list per emission.

**Implementation:** expose stable map state or memoize per-model flows once.
Verify collector counts stabilize after repeated Models visits. Use SQL retention
and paged history plus separate export (G12). Base progress on actual native
progress where available, otherwise phase plus elapsed time; show an ETA only
with matching calibration provenance. Profile waveform allocations and throttle
display emissions if material, keeping audio capture independent of UI cadence.

**Validation:** repeated recomposition/download cycles with job/heap counts,
10,000-history-row insert/prune timing, slow/unknown-duration imports, and frame
traces under inference. This improves responsiveness; no Whisper speed gain is
claimed. **Commits:** stable download flows; measured storage/UI tuning.

## P07 — resampling, trimming, and VAD

**Evidence:** `AudioDecoder.LinearResamplingSink` interpolates without an
anti-alias filter. At 48→16 kHz it effectively selects every third sample, allowing
frequencies above the target Nyquist limit to alias. `AudioSilenceTrimmer` returns
the original array for all-silence input, so expensive Whisper inference still
runs. Its whole-frame calculations can omit a partial final frame when leading
silence is trimmed. `vadPathFor` ignores its duration/workload arguments.

**Implementation:** add a stateful low-pass/polyphase resampler for common source
rates and retain channel alignment across buffers. Preserve the final partial
frame. Benchmark VAD on/off by duration/noise; measure VAD cost and skipped
inference together. Add a conservative silence shortcut only if the corpus proves
quiet speech is retained. Never infer silence from a single fixed threshold alone.

**Validation:** tone/sweep alias tests, 44.1/48/8/16-kHz lengths, buffer seams,
stereo input, immediate/quiet speech, sub-20-ms tails, pure silence, and background
noise. Compare WER/CER and total time, including preprocessing. **Commits:**
resampler; tail preservation; separately gated VAD/silence optimization.

## P08 — decoder and short-context tuning

After G03 removes cross-request contamination, quantify encoder/decode/fallback
cost for Small and Turbo. Vary one setting at a time: full versus duration-binned
context, Spanish priming prompt versus none, greedy/beam strategy, and fallback
parameters. The default Spanish prompt cannot currently be disabled through a
blank setting because blank resolves to null; expose an explicit automatic/off/
custom prompt mode for this experiment.

Use full context as reference. If a short-context candidate is tested, require a
validated retry policy and record retry time in p95; empty/repetitive output is
detectable but apparently fluent truncation may not be. Do not promote based only
on those heuristics. Preserve timestamp-guided window traversal and test 8/15/30/
60-second boundaries. Avoid reducing fallback merely to suppress slow outliers;
report whether the removed fallback was improving recognition.

**Validation:** complete reference transcripts, long-form boundary tests, unchanged
names/numbers, repeated runs, and worst-case retry cost. **Commit:** experiment
flags/results first; production policy only after P02 gates pass.

## P09 — opt-in incremental previews

**Implementation:** capture stable request/settings/model identity (G07); run at
most one preview and keep only the newest pending window. Adapt cadence to actual
inference time so work cannot accumulate when inference is slower than capture.
Do not preempt microphone reading or overlap native calls to the same context.
Stop cancels/awaits a preview and prioritizes the final full-audio pass. Keep
preview text visibly provisional; never paste partial text into another app.

Measure time-to-first-preview, update age, final Stop latency, total inference
work, battery, and stable-prefix errors. A preview feature may feel faster while
using more CPU; evaluate both. For long recordings, agree overlap/context and
bounded storage with P04. No preview result is authoritative history.

**Validation:** slow Turbo, rapid Stop, cancelled preview, model/language changes,
long silence, 30-second seams, UI destruction, and protected-app switching.
**Gate:** final accuracy unchanged and no material final-latency regression;
retain opt-in status if energy costs are significant. **Commit:**
`Add bounded opt-in transcription previews`.

## P10 — GPU experiment

Start with Vulkan because it can potentially reuse the current Whisper model
files. Inspect the pinned ggml backend, required extensions/operations, quantized
kernel support, Android loader, and shader compilation toolchain. Backend source
presence is not evidence of correct or faster execution on Mali/Adreno/Xclipse.
OpenCL on Snapdragon is a separate candidate, contingent on driver availability
and Whisper operator coverage; Llama backend benchmarks do not prove ASR support.

**Milestones:** build an isolated benchmark variant; enumerate the actual device
capabilities; load and run a tiny smoke model; verify raw output; benchmark Small
q8_0/q5_1 and Turbo separately; then integrate an experimental setting. Record GPU
driver and precision, upload/compile/cold time, encode/decode split, peak memory,
thermal trends, and cancellation latency. Test full versus supported partial
offload only when the backend exposes it; do not assume encoder-only control is
already available.

**Fallback design:** recoverable backend errors recreate a CPU request using the
same captured audio/settings. Bound retries. A native driver crash cannot be
caught as a Kotlin exception: consider an isolated experimental worker process
or persistent quarantine applied on next startup. Avoid UI crash loops. Re-run
P02 gates and G01 cancellation tests before any default changes.

**Stop conditions:** unsupported required operations, driver instability,
fallback-heavy execution, worse p95/energy, or accuracy regression. A negative
Pixel result still informs Snapdragon testing but does not generalize globally.
**Commits:** build flag/packaging; benchmark proof; runtime integration/rollout.

## P11 — NPU and alternative speech models

**First deliverable is a compatibility decision, not converted Whisper.** For
the user's Pixel 8 Pro, official Tensor SDK support currently lists G5 only.
Determine whether a documented, distributable G3 runtime/compiler exists and can
execute a minimal test on this phone. If not, mark G3 NPU work blocked and continue
CPU/GPU work. G5 investigation would require different hardware and SDK access.
The documented compiler workstation is Ubuntu/x86-64; this Mac environment does
not itself establish a supported toolchain. [Tensor SDK requirements](https://developers.google.com/edge/litert/next/tensor-sdk).

On supported hardware, confirm license/redistribution, offline installation and
runtime behavior, SDK/runtime/OS matrix, operation coverage, supported numeric
types, fixed/dynamic shapes, decoder KV state, model compilation, and actual
delegated partitions. Report both accelerated encoder and total request cost;
CPU decoder fallback alone is not sufficient reason to reject a net improvement.

Whisper TFLite community conversions are candidates, not validated TPU packages.
Review multilingual support, tokenizer/features, model provenance, timestamps,
and conversion accuracy. Moonshine's linked TFLite release supplies English Tiny/
Base; evaluate Spanish variants only with exact version, license, and model
evidence. Other ASR families need the same checks. Qualcomm Hexagon source also
exists in vendored ggml, but its existence does not establish Whisper support or
anything about Tensor G3. Treat each route as its own feasibility decision.

**Milestones:** compatibility matrix → minimal accelerator proof → one multilingual
model proof → corpus comparison → optional parallel-engine integration. Retain
current GGML downloads and CPU engine for unsupported devices. P02 gates apply;
do not promise preserved accuracy merely because the model originated as Whisper.

## Suggested future work packages and merge order

1. **Correctness foundation:** G01–G03, G04/G09, G05/G06. Resolve interfaces before
   profiling; otherwise timings can reflect the wrong model or stale context.
2. **Measurement owner:** P01/P02 owns manifests, metrics, corpus, and results.
3. **CPU owner:** P03/P05 owns policy/residency after G02. Do not edit engine/native
   ownership concurrently with the correctness owner.
4. **Audio owner:** P04/P07 owns PCM, cancellation, resampling, and seam tests.
5. **UI/data owner:** P06/P09 follows G07/G12/G14 and stable benchmark interfaces.
6. **Accelerator owner:** P10, then P11 only after compatibility approval gates
   based on evidence. Keep experiments separate from baseline result collection.

These are future delegation boundaries; no agents were dispatched for this audit.
Use one coherent commit per independently verifiable change, with result artifacts
in a separate follow-up commit. Rebase against corrected engine contracts before
integration; never run two native benchmark workloads concurrently on one phone.

## Definition of done for every optimization

- Reproducer/baseline and exact configuration are recorded.
- Release-representative ARM64 device validation is completed where relevant;
  emulator/JVM success is not substituted for device measurements.
- Raw and cleaned accuracy, p50/p95, failures, total Stop latency, memory, and
  sustained behavior are compared. Energy claims require measurements.
- Cancellation, input-target protections, and offline behavior remain correct.
- Rollback uses a known-good CPU policy/model; profiles invalidate correctly.
- Historical findings stay historical. Update Performance.md with actual results,
  rejected experiments, and remaining limitations; remove implemented items from
  the README roadmap only after their validation gates pass.
