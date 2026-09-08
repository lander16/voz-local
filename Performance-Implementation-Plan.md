# Transcription performance implementation plan

Last reconciled: September 8, 2026

Audit baseline: `921f413f0be25537b87092daf90b8617cd542133`

Current implementation baseline: `0dd1b9e`

This document is the architectural roadmap for transcription performance.
Historical measurements and rejected assumptions belong in [Performance.md](Performance.md).
Reproducible Pixel commands and raw result links belong in
[Pixel-Validation.md](Pixel-Validation.md). General correctness dependencies use
the `Gxx` identifiers in [Issues-Implementation-Plan.md](Issues-Implementation-Plan.md).
GitHub Issues are authoritative for execution status, checklists, discussion, and
new evidence. The table below is a point-in-time architectural status snapshot;
update its issue link or acceptance state only when the roadmap itself changes.

An item is not complete merely because its code exists:

- **Implemented** means the planned production or test code is present.
- **Device evidence** means the relevant path ran on the Pixel 8 Pro with results retained.
- **Acceptance complete** means its accuracy, latency, reliability, and regression gates passed.

## Progress and priority

| Tracker | Priority | Work | Implementation | Device evidence | Acceptance | Next action |
|---|---|---|---|---|---|---|
| [P01 · #1](https://github.com/lander16/voz-local/issues/1) | P1 | Benchmark runner and native metrics | Implemented | Partial | Partial | Add signed-release end-to-end Stop-to-result runs and complete environment capture |
| [P02 · #2](https://github.com/lander16/voz-local/issues/2) | P1 | Accuracy corpus and q8_0 comparison | Scoring/manifest implemented | Missing representative corpus run | Open | Curate licensed audio and compare Small q5_1 with q8_0 |
| [P03 · #3](https://github.com/lander16/voz-local/issues/3) | P1 | CPU topology and calibration | Implemented | Tiny calibration and Small q5_1 thread sweep | Partial | Run calibration with Small and prove persisted profile use after restart |
| [P04 · #4](https://github.com/lander16/voz-local/issues/4) | P1 | Bounded audio memory and copies | Initial safeguards implemented | Not measured | Partial | Measure peak Java/native memory, then design the long-file bounded pipeline |
| [P05 · #5](https://github.com/lander16/voz-local/issues/5) | P1 | Model residency, warmup, and eviction | Implemented | Basic reuse after cancellation | Partial | Exercise focus storms, model switches, warmup cancellation, and memory pressure on device |
| [P06 · #6](https://github.com/lander16/voz-local/issues/6) | P2 | UI, database, and progress overhead | Implemented | Not profiled | Partial | Measure recomposition/jobs, history pruning, frames, and progress behavior |
| [P07 · #7](https://github.com/lander16/voz-local/issues/7) | P2 | Resampling, trimming, and VAD | Implemented | Not compared end to end | Partial | Measure preprocessing cost and quiet-speech accuracy on the P02 corpus |
| [P08 · #8](https://github.com/lander16/voz-local/issues/8) | P2 | Decoder, prompt, and short-context tuning | Experiment controls implemented | No controlled matrix | Open | Run one-variable-at-a-time experiments after P02 baseline |
| [P09 · #9](https://github.com/lander16/voz-local/issues/9) | P2 | Opt-in incremental previews | Not implemented | None | Open | Design bounded scheduling only after final-pass latency is stable |
| [P10 · #10](https://github.com/lander16/voz-local/issues/10) | P2 | Vulkan GPU prototype | Not implemented | None | Open | Build an isolated backend experiment after CPU/model baselines close |
| [P11 · #11](https://github.com/lander16/voz-local/issues/11) | P3 | NPU and alternative ASR feasibility | Documentation only | None | Blocked for Tensor G3 route | Revisit only with a documented distributable G3 runtime or supported hardware |

### Recommended execution order

1. **P02** — establish whether Small q8_0 preserves accuracy while delivering the
   large exploratory speed improvement already observed.
2. **P03** — calibrate Small on the Pixel, verify restart persistence, and compare
   the selected profile against the safe default.
3. **P01** — add signed-release end-to-end measurements so native gains can be
   translated into user-visible Stop-to-result latency.
4. **P07 and P08** — use the accepted corpus and benchmark contract to tune audio
   preprocessing and decoder/context settings.
5. **P04, P05, and P06** — close memory, lifecycle, and responsiveness gates.
6. **P09**, then **P10** — keep streaming previews and GPU work opt-in until their
   accuracy, final latency, energy, cancellation, and fallback behavior pass.
7. **P11** — remain a feasibility track rather than part of the current Pixel G3 plan.

## Current measured baseline

Two result sets exist and must not be combined as though they used one protocol:

- The original exploratory Pixel run reported Small q5_1 at 8.45/8.64 seconds,
  Small q8_0 at 4.47/4.92 seconds, and Large v3 Turbo q5_0 at 37.38 seconds on an
  approximately ten-second Spanish excerpt. The q8_0 result was about 45% lower
  latency than q5_1, but the run lacks a hand-verified reference, a reliable
  latency distribution, and complete raw provenance.
- The September 7 reproducible native harness used an 11-second English JFK
  fixture with Small q5_1. Four threads had the best median in both tested CPU
  modes: 15.397 seconds in Compatibility and 12.830 seconds in Automatic I8MM.
  Automatic was about 16.7% faster in that narrow comparison. Cancellation was
  45–467 ms after the scheduled-graph abort fix. Tiny calibration completed 18/18
  trials, selected three threads, and reloaded its profile from disk in-process.

These results establish that model format, backend, and thread count matter. They
do not establish a default model, multilingual accuracy, sustained thermal behavior,
energy use, Small calibration benefit, or performance on another device.

## P01 — Benchmark runner and native metrics

**Status:** implemented; device and acceptance validation are partial.

Implemented:

- Repository/native leases prevent benchmarking a stale or concurrently replaced model.
- Per-request thread overrides, model/PCM hashes, quantization checks, backend tier,
  and native build identity are recorded.
- Cold and warm native-context modes are distinct; unsupported settings fail rather
  than silently producing misleading output.
- Native timing totals are exported through the project-owned accessor. Historical
  Compatibility rows using upstream averages are labelled as legacy.
- JSON/CSV schema and round-trip tests exist.

Remaining:

- Measure microphone Stop-to-result and imported-file end-to-end latency in a
  production-signed build; the current harness begins with decoded PCM.
- Record unavailable decode, verification, energy, and memory fields as null while
  adding the missing real measurements.
- Separate native-context cold from filesystem/page-cache cold and record both clearly.
- Preserve exact device/build, power, charge, thermal, model, audio, and parameter provenance.
- Add failure/abort rows and stage-sum consistency checks to the retained result bundle.

Acceptance:

- Repeated results are reproducible enough to compare candidates without order bias.
- The effective native module and requested model/settings match every recorded row.
- User audio and transcript text remain private unless explicitly exported.

## P02 — Accuracy corpus and controlled model comparison

**Status:** scoring and coverage validation are implemented; the representative
audio corpus and q5_1/q8_0 result set are not complete. This is the next priority.

Implemented:

- A 22-entry Spanish/English text-only planning manifest and WER/CER infrastructure.
- Separate raw-ASR and post-cleanup scoring.
- Rejection of empty manifests, duplicate IDs, missing hypotheses, and unexpected results.
- Explicitly empty hypotheses count as deletions instead of disappearing from the report.

Remaining protocol:

1. Replace the planning manifest with a real corpus. Its current `CC0` labels are
   not backed by audio files, source URLs, creator/consent records, or hashes, so
   they are not evidence that a licensed audio corpus exists.
2. Curate at least 30 consented or appropriately licensed Spanish clips plus a
   smaller English regression set. Cover 1–3, 5–15, 25–35, and 60–120 seconds;
   immediate onset, quiet endings, noise, names, numbers, accents, code-switching,
   silence, and intentional repetitions.
3. Record license/consent, reference text, decoded duration, normalization rules,
   and PCM hash for every clip. Keep private recordings and transcripts out of Git.
4. Compare Small q5_1 and q8_0 first under identical settings. Use Base/Tiny as
   controls; defer Turbo/Medium until failure and memory reporting are reliable.
5. Alternate candidate order, disable Battery Saver, document charging and thermal
   state, prevent downloads/background benchmarks, and allow cooling between groups.
6. Use at least 10 warm screening runs per configuration and 30 runs for finalists.
   Keep cold trials separate and repeat the winner on another day.
7. Run a 10–20-minute sustained workload to expose throttling and decoder outliers.

Promotion gate:

- At least 15% median Stop-to-result improvement.
- No more than 10% p95 regression and no increased failure rate.
- No more than 0.5 percentage-point absolute WER regression on the agreed corpus.
- Manual review of names, numbers, omissions, repetitions, and changed meaning.
- Energy and peak memory remain within the measured device budget.

Until all gates pass, q8_0 remains a promising candidate rather than the automatic default.

## P03 — CPU topology and opt-in calibration

**Status:** implemented; Pixel validation is partial.

Implemented:

- Testable CPU information provider and handling for homogeneous, two-cluster, and
  three-cluster topologies with conservative fallback.
- Instruction-capability detection remains separate from performance policy.
- Per-request thread override and bounded opt-in calibration across up to six counts.
- Three rotated rounds, transcript-consistency screening, cancellation, timeout,
  Battery Saver/thermal refusal, and durable keyed profiles.
- Keys include device/OS, native build/backend, model checksum, settings, and workload bucket.

Device evidence:

- Small q5_1 manual sweep found four threads fastest on the one 11-second fixture.
- Tiny q8_0 calibration selected three threads and reloaded its profile from disk
  within the same application process.

Remaining:

- Run the actual calibration flow with Small q5_1 and q8_0 using short and long samples.
- Restart the complete application and prove that ordinary transcription consumes
  the persisted matching profile; test invalidation after model/settings/runtime changes.
- Compare normal versus raised worker priority for throughput, UI frames, and recording stability.
- Repeat across thermal states with hysteresis before considering adaptive live changes.
- Do not claim Snapdragon behavior until tested on Snapdragon hardware.

## P04 — Bounded audio memory and copy reduction

**Status:** initial safeguards implemented; long-file memory work remains.

Implemented:

- A 30-minute recording limit and overflow-safe `FastFloatBuffer` growth.
- Oversized-buffer shrinking outside the recording hot path.
- Clamped decoder allocation instead of trusting container duration metadata.

Remaining:

- Measure retained Java heap, native RSS/PSS, and peak overlap among decode, trim,
  JNI, and model buffers for 1-, 10-, and 30-minute inputs.
- Add discard-without-snapshot where cancellation currently materializes PCM.
- Design bounded blocks or a private temporary PCM spool for long imports, including
  cleanup after cancellation/crash and an explicit privacy-policy update if microphone
  audio is ever written to disk.
- Preserve timestamps and words across chunk overlap before changing the default path.
- Consider direct/native buffers only if profiling shows JNI copies are material.

## P05 — Model residency, warmup, and eviction

**Status:** implemented; adversarial device validation remains.

Implemented:

- Only selected models preload; concurrent preload requests are deduplicated.
- Replacement verifies the new file and releases the old native allocation before
  loading, avoiding simultaneous large model residency.
- Model operations and native context ownership are serialized.
- Engine busy state protects active inference from memory-pressure eviction.
- Cancellation propagates to scheduled CPU graphs and context destruction is non-cancellable.

Remaining:

- Exercise launch/model-switch races, focus storms, cancelled warmup, missing/corrupt
  replacement, failed native allocation, successful retry, and memory pressure during inference.
- Compare no warmup, current bounded warmup, and representative graph warmup for
  first-request latency and total startup energy.
- Verify next-launch backend recovery after an interrupted capability probe.

## P06 — UI, database, and progress overhead

**Status:** implemented; performance validation remains.

Implemented:

- Stable memoized download progress/status flows instead of new `stateIn` jobs on recomposition.
- Direct state-map exposure and waveform emissions throttled to roughly 30 FPS with
  forced boundary snapshots.
- Atomic SQL history-retention pruning instead of row-by-row deletion.

Remaining:

- Measure collector/job counts after repeated Models visits and trace frames during inference.
- Benchmark 10,000-row history insertion/pruning and verify export remains complete.
- Replace heuristic progress/ETA claims with measured native progress or phase-only UI.
- Treat this as responsiveness work; do not report it as Whisper inference speedup.

## P07 — Resampling, trimming, and VAD

**Status:** implemented; end-to-end performance and accuracy gates remain.

Implemented:

- Stateful rate-scaled Hann FIR resampling with history across decoder buffers.
- Synthetic 32/44.1/48/96 kHz stop-band tests requiring more than 45 dB rejection
  near the target Nyquist boundary.
- Decimation avoids evaluating discarded frames.
- Partial tail preservation and a conservative all-silence inference shortcut.

Remaining:

- Benchmark total decode plus inference cost against the earlier path on device.
- Validate immediate speech, quiet endings, noise, stereo, buffer seams, short tails,
  and silence against the P02 corpus.
- Measure VAD cost and avoided inference together by duration/noise before changing defaults.
- Keep quiet-speech preservation more important than skipping marginal silence.

## P08 — Decoder, prompt, and short-context experiments

**Status:** configuration implemented; experiments and policy are open.

Implemented:

- Explicit `AUTOMATIC`, `OFF`, and `CUSTOM` prompt modes.
- `audioCtx` and prompt mode in transcription parameters and benchmark serialization.

Remaining:

- With P02 references, vary one setting at a time: full versus duration-binned
  context, Spanish prompt versus none, greedy/beam policy, and fallback parameters.
- Record encoder, decoder/sample, fallback, retry, and total latency separately.
- Test 8/15/30/60-second boundaries and apparently fluent truncation, not only empty
  or repetitive failures.
- Preserve full context as the reference and promote no tuning before P02 gates pass.

## P09 — Opt-in incremental previews

**Status:** not implemented. Recorder snapshots and live status text are not a
streaming transcription pipeline.

Design requirements:

- Capture immutable request/model/settings identity.
- Run at most one preview and retain only the newest pending window; adapt cadence
  to actual inference time so work cannot accumulate.
- Never overlap native calls on one context or preempt microphone capture.
- On Stop, cancel/await preview work and prioritize the authoritative final pass.
- Mark previews as provisional and never paste or store them as final history.
- Measure time-to-first-preview, update age, final latency, extra CPU/energy, stable
  prefix errors, cancellation, model changes, silence, and long-session boundaries.

Remain opt-in unless final accuracy is unchanged and latency/energy costs are acceptable.

## P10 — Vulkan GPU experiment

**Status:** not implemented. Backend source presence is not device support evidence.

Milestones:

1. Build an isolated benchmark variant and enumerate required Pixel Vulkan operations.
2. Run a Tiny smoke model and verify raw output against CPU.
3. Benchmark Small q8_0/q5_1 and Turbo with driver, shader/cold cost, transfers,
   encode/decode split, memory, thermal, cancellation, and accuracy recorded.
4. Add bounded CPU fallback for recoverable failures. Treat native driver crashes as
   process-level failures requiring isolation or next-start quarantine.
5. Integrate only as an experimental setting after P01/P02 and cancellation gates pass.

Stop on unsupported operations, instability, fallback-heavy execution, worse p95 or
energy, or accuracy regression. A Pixel result does not generalize to Adreno/Xclipse.

## P11 — NPU and alternative ASR feasibility

**Status:** no production implementation. The documented Google Tensor route does
not currently establish a distributable Tensor G3 solution for the Pixel 8 Pro.

The first deliverable is a compatibility decision: supported hardware, compiler
host, runtime redistribution, offline behavior, operations, numeric types, shapes,
decoder state, delegated partitions, and failure behavior. Only then should a minimal
model be converted and compared with the CPU reference. Community Whisper conversions
and alternative ASR families require independent provenance, licensing, multilingual,
tokenizer, timestamp, and accuracy validation. Keep the current GGML CPU engine and
models as the fallback on unsupported devices.

## Definition of done

Every optimization must have:

- A retained baseline and exact configuration.
- Release-representative ARM64 device evidence where relevant.
- Raw and cleaned accuracy, p50/p95, failures, and total Stop-to-result latency.
- Peak memory and sustained thermal behavior; energy claims require energy measurements.
- Correct cancellation, model/session ownership, protected-app behavior, and offline operation.
- A known-good rollback and profile invalidation when dependencies change.
- Updated [Performance.md](Performance.md) with raw-result links, rejected experiments,
  conclusions, and limitations.

Use one independently verifiable commit per implementation change and a separate
commit for measurement artifacts/conclusions. Never run competing native benchmarks
in parallel on the same phone.
