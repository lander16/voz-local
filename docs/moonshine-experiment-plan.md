# Moonshine Spanish streaming experiment

Architecture and acceptance plan, updated 2026-09-22. Execution, decisions and retained
evidence belong to [P11 / issue #11](https://github.com/lander16/voz-local/issues/11).
Representative accuracy and promotion depend on [P02 / issue #2](https://github.com/lander16/voz-local/issues/2).

## Authorized app inclusion amendment

After the successful Pixel screening, the user explicitly requested app inclusion.
Implement a clearly labeled experimental option now; the earlier requirement to
keep all code test-only is superseded for this opt-in release. The accuracy and
long-term performance gates below still govern recommendation/default promotion.
Whisper remains default and the available fallback, with no automatic selection
of Moonshine on download, deletion of another model, or missing-model recovery.

Implementation ownership: Luna medium agents handle pinned transactional bundles,
the serialized runtime adapter, and EN/ES model cards. Primary handles repository
routing, safety/error handling, tests, release validation and documentation.

Initial contract: Spanish-only `moonshine_tiny_es` / `moonshine_small_es`, explicit
user download and model selection, complete-clip live dictation up to 30 seconds,
no shared-file transcription or incremental preview. Unsupported language,
duration and Whisper-only calibration are rejected with localized messages.
Do not silently change language or switch engines when a request fails.

Use pinned v0.1.5 native binaries and the retained SHA-256 manifests. Load only
verified complete bundles; serialize native inference and destruction, discard
cancelled results, keep at most one engine active, and reject additional requests
while a cancelled native operation is still draining. Do not claim immediate native
abort where the SDK has none. Test cancellation/recovery and model switching,
then run build/unit/lint/release packaging checks and device smoke checks. Keep
permissions and accessibility eligibility unchanged. Track completed work in #11.

## Decision and scope

Evaluate Spanish Moonshine Small Streaming and Tiny Streaming on the Pixel 8 Pro
against Whisper Small q8_0. Start with CPU execution in the separate validation
package. This is an alternative ASR engine experiment, not Tensor TPU support.
Keep Whisper as the default engine until the gates below pass. The authorized
amendment above permits experimental opt-in availability, not default promotion.

Prioritize Small for accuracy and Tiny for latency/memory. Evaluate corresponding
English models only after Spanish feasibility, and explicitly test language
switching and Spanish/English mixed speech. A language-specific Spanish model must
not be labeled multilingual or silently selected for English/automatic language.
Parakeet TDT v3 and Qwen3-ASR remain secondary candidates if this route fails.

## P0: artifact and runtime feasibility

1. Pin the Android SDK version, source revision, dependency graph, AAR digest and
   native ABI inventory. Check minimum SDK, native page alignment, release/R8
   loading, conflicting native libraries and notices for redistributed code.
2. Obtain models from the catalog in that exact runtime revision. Pin every
   frontend/encoder/adapter/cross-KV/decoder-KV/config/tokenizer asset by SHA-256;
   record sizes, source URLs, model language, quantization and license. A dated
   URL is not immutable: verify the bytes on every download and activation.
3. Inspect model metadata and instantiate on ARM64. Reject missing/corrupt files,
   unsupported shapes, incompatible tokenizer/config pairs and unsupported ABIs
   before activating. Do not interpret HTTP availability as execution success.
4. Load from local files with the low-level PCM API. Avoid convenience microphone
   capture and automatic model fetching; VozLocal owns recording and explicit
   user-directed downloads. Audit merged manifest and dependency networking.
5. Confirm offline operation after staging. No private speech or transcripts are
   sent to external services. Do not inspect or automate banking applications.

Sources checked: [upstream models](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[license](https://moonshine-voice.readthedocs.io/en/latest/license/),
[Android quickstart](https://moonshine-voice.readthedocs.io/en/latest/quickstart/),
and [v0.1.5 catalog](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/core/moonshine-model-catalog.cpp).
The v0.1.5 tag resolves to `234f60faa0eb388b01cdf7e60aca232af37aefda`.
The Maven AAR is published. The catalog points Spanish streaming models at
`https://download.moonshine.ai/model/{tiny,small}-streaming-es/quantized_26_08_24/`.
Tiny encoder and config requests succeeded during feasibility checking. The older
Hugging Face asset mirror's `FILES.tsv` omits these streaming models, so absence
from that mirror must not be reported as absence from the official CDN.

The docs report 123M/34M parameters, while the Spanish float checkpoint cards
report different counts. Treat exact asset identity and measured runtime memory,
not headline parameter counts, as the experiment inputs. Publisher WER figures
are not VozLocal accuracy measurements.

## P1: isolated validation implementation

- Initial screening kept the pinned runtime only in the instrumentation APK.
  The authorized app integration now includes it in the application; keep benchmark
  runners and fixture staging test-only. Use the side-by-side validation package
  rather than replacing the user's signed app for screening.
- Use `Transcriber.loadFromFiles`, supplied mono 16 kHz float PCM and serialized
  native operations. Start with complete-clip decoding for a smoke test, then
  replay PCM in bounded chunks at recording cadence for streaming.
- Retain runtime/model/audio identity, language, inference settings, run order,
  elapsed times, failures, device/build, power and thermal state. Store private
  hypotheses locally; publish only consented text or non-sensitive aggregates.
- Share the existing WER/CER scorer, but do not force non-Whisper models through
  Whisper-specific beam, prompt, quantization or native-timing fields. Unknown or
  unsupported metrics are null/unavailable, never zero or invented equivalents.
- Add tests for manifest/hash rejection, stream event assembly and final-only
  results, empty/silent audio, partial buffers, teardown, cancellation and recovery.

Relevant integration seams: `WhisperEngine`, `DictationRepository`,
`TranscriptionBenchmark.kt`, and `PixelNativeValidationTest`. Begin with a parallel
validation runner; the opt-in integration adds a narrow parallel adapter rather
than a broad rewrite of the existing Whisper engine.

## P1: experiment protocol

Use the same consented/licensed PCM and hand-verified reference for each model.
The existing text-only corpus and recording script are test designs, not recorded
accuracy evidence. An unreferenced Spanish fixture can screen runtime behavior,
but cannot establish WER, superiority or eligibility for promotion.

1. Record app commit and dirty diff identity, APK hash, OS/build, runtime and model
   hashes, PCM hash, language, chunk size, update cadence, thread controls actually
   available, VAD, decoder options and text-cleanup settings.
2. Disable Battery Saver, fix and record charging state, capture battery/thermal
   state before and after each trial. Stop/cool on throttling. No simultaneous
   downloads, benchmarks or model prewarming from another app instance.
3. Separate model-load, warmup, cold first inference and warm inference. Alternate
   candidate order. Run at least 10 warm screening trials, 30 finalist trials,
   independent cold trials, and repeat finalists on a second day.
4. Measure two workloads separately: unrestricted complete-clip throughput and
   real-time streaming. For streaming, preserve audio cadence with bounded queues,
   record backlog/overruns, time to first text, revision frequency, finalization
   after the last sample, and end-of-speech/Stop-to-final-result latency.
5. Do not compare Moonshine's last decoder call to Whisper's whole inference as a
   compute speedup. Report total inference work and user-visible latency separately;
   include endpoint detection, cleanup and UI delivery in end-to-end comparisons.
6. Use at least 30 Spanish clips plus an English regression set, with multiple
   lengths, accents, noise, immediate onset/quiet endings, names, numbers,
   punctuation, code-switching, silence and repetitions. Report raw and cleaned
   corpus WER/CER plus critical semantic errors; do not average per-clip WER as
   a substitute for corpus word-count weighting.
7. Run 10–20 minutes continuously to observe memory growth, backlog, temperature,
   throttling and battery behavior. Record peak process memory consistently for both
   engines. Do not claim energy efficiency from latency or battery percentage alone.

## P1: inclusion decision

Use the existing P02 thresholds, applied to matched workload/corpus:

| Gate | Required evidence |
|---|---|
| Useful latency | At least 15% lower median Stop-to-final-result latency |
| Tail behavior | No more than 10% p95 regression |
| Accuracy | At most 0.5 percentage-point absolute corpus WER regression |
| Reliability | No increased failure rate; silence and long sessions remain bounded |
| Semantic quality | Names, numbers, omissions, repetitions and meaning manually reviewed |
| Resource behavior | Measured memory/thermal behavior fits the phone; energy unavailable unless actually measured |
| Lifecycle | Cancel/stop/restart/model switch and process recovery verified on device |

Classify the result as rejected, further investigation, or eligible for opt-in.
Missing corpus, lifecycle or resource evidence means further investigation even
if timing looks excellent. The current narrow Whisper measurements are context,
not a substitute for a fresh paired baseline.

## P2: app integration and broader promotion

The amendment permits the bounded experimental subset now. Streaming and broader
recommendation remain gated; the items below describe the full target, not a claim
that every capability is implemented.

1. Introduce engine-neutral session/result/capability types with explicit language,
   streaming, timestamp and prompt support. Adapt Whisper without behavior changes.
   Let `DictationRepository` own exactly one model/session lease across engines.
2. Add verified multi-file downloads using temporary directories and atomic
   activation. Preserve known-good models on failure; validate all hashes before
   loading. Include licenses, total download sizes and deletion of complete bundles.
3. Use a serialized worker and bounded audio queue. Never race release against
   inference. The upstream `stopStream` finalizes transcription; it is not a native
   abort API. Prove bounded cancellation or isolate execution in a disposable app
   process. Discard cancelled generation IDs and stale callbacks immediately.
4. Clear preview text on cancel/focus loss; insert only the final result after the
   existing target/eligibility checks. Preserve protected-field restrictions and
   the floating button's keyboard/focus policy. No additional accessibility access.
5. Expose an Experimental engine choice with English/Spanish labels and clear
   supported languages. Keep Whisper selected by default. If a candidate fails,
   offer explicit retry with Whisper; never silently duplicate insertion or use an
   incompatible language model. Keep file transcription on Whisper until tested.
6. Validate model switches, app restart, memory pressure, corrupt downloads, offline
   use, Bluetooth/audio interruptions, silence, cancellation and release shrinking.
   Install a correctly signed production build only for the final acceptance pass.

## Delegation and completion

Luna medium reviews artifact/runtime feasibility and then the benchmark/design
gates. The primary agent owns integration decisions and serial device execution.
Additional parallel agents are conditional on session capacity; never run two
performance workloads on the Pixel concurrently. Review each implementation diff,
run relevant tests and project checks, commit focused changes with `Refs #11`,
and retain progress/evidence in the issue. Keep #11 open while NPU feasibility or
alternative-engine acceptance criteria remain unresolved.

## Running the initial smoke harness

The committed runners cover complete-clip inference only. Streaming replay,
end-to-end Stop latency, cancellation, peak memory, energy and corpus accuracy
remain follow-up work; these runners do not satisfy the inclusion gates above.

1. Run `python3 -B scripts/test_stage_moonshine_experiment.py`.
2. Stage each candidate with
   `python3 -B scripts/stage-moonshine-experiment.py --output /tmp/vozlocal-moonshine-experiment --model tiny-es`
   and repeat for `small-es`. This verifies upstream size/CRC32C pins and writes
   SHA-256 manifests. CRC32C is an integrity checksum, not a cryptographic
   authenticity guarantee; preserve the resulting SHA-256 manifest for each run.
3. Supply local `speech.f32` (mono 16 kHz little-endian float32, 0–30 seconds) and
   `speech.sha256` (hex digest). Keep source/license/consent, source hash, conversion
   command and human-verified reference in the experiment record. Do not publish
   unlicensed/private audio or hypotheses. Stage `whisper-small-q8.bin` with its
   existing pinned official digest for the control runner.
4. Build `assembleValidation assembleValidationAndroidTest` with
   `-PdeviceTestBuildType=validation`. Run `testDebugUnitTest lintDebug` separately
   without that property: AGP selects different test components with it enabled.
5. Install only the `.validation` app and test APK; copy the experiment directory
   into that app's `files/validation/moonshine` using `adb run-as`. Do not copy
   into or replace the signed production installation.
6. Run `am instrument -w` with class `dev.sebastian.vozlocal.MoonshineValidationTest`,
   arguments `moonshineModel=tiny-es|small-es`, `moonshineIterations=3` and
   `appCommit=<tested commit>`. Use `dev.sebastian.vozlocal.MoonshineControlValidationTest`
   for Whisper. Target runner:
   `dev.sebastian.vozlocal.validation.test/androidx.test.runner.AndroidJUnitRunner`.
   Select the device explicitly when ADB lists both mDNS and IP connections.
7. Read the local `report-<model>.json` after each run, archive it before another
   run of the same model overwrites it, and retain APK hashes plus model manifests.
   Reports contain hypotheses: redact them before publishing if redistribution is
   not authorized. A missing report or interrupted transport is unavailable
   evidence, never a successful zero-time run.

For the initial local smoke only, the intended fixture is the first 10 seconds of
`app/src/test/resources/test_audio_2min.ogg`, converted with
`ffmpeg -i INPUT -t 10 -ar 16000 -ac 1 -f f32le OUTPUT`.
The resulting PCM SHA-256 is
`23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a`.
Its reference/provenance is insufficient for the P02 accuracy corpus. Do not
redistribute the audio/transcript or calculate promotion WER from this fixture.
