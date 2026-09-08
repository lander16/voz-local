# Application audit: issues and general improvements

Audit baseline: `921f413f0be25537b87092daf90b8617cd542133` (2026-09-06).
Status: P1 implementation revised after the September 7 review. Device-only validation
scenarios remain release gates; see each item's validation section.
Companion: [Performance implementation plan](Performance-Implementation-Plan.md).
Historical measurements: [Performance.md](Performance.md).

## September 7 review follow-up

Validation: 199 host tests passed and Android debug lint completed with zero
errors (62 warnings). Follow-up [Pixel validation](Pixel-Validation.md) used a
separate package with no accessibility service. It exposed the upstream scheduled
CPU graph's missing abort forwarding: the app-owned native build patch reduced
observed Small q5_1 cancellation from 7.510 seconds to 45–467 ms and passed repeated
cancel/reuse checks. Signed production UI/accessibility validation remains open.

- G01: native abort is now registered on job cancellation, not final completion.
  A blocking-worker regression test checks that abort fires before the worker
  exits. Context destruction is non-cancellable and serialized on its worker.
  Real Small q5_1 JNI cancellation and repeated cancel/restart now pass on the
  Pixel; other models/backends and production UI cancellation remain gates.
- G06: selection acquires the target model-operation lock and rechecks downloaded
  state transactionally. Deletion rereads current selection inside its final
  transaction so it cannot overwrite a newer selection using a stale snapshot.
- Model replacement releases the old allocation before creating the replacement;
  missing/empty replacement files preserve the old context, while allocation
  failure leaves an unloaded engine that can retry. Two models are not retained
  merely to provide rollback.
- Benchmark honesty, calibration persistence/UI, filter rejection, and strict
  accuracy coverage are tracked in the companion performance plan. These host
  checks do not establish device performance or bank-app compatibility.

## Scope, evidence, and priorities

Reviewed project-owned Kotlin, JNI, build configuration, permissions, backup
rules, persistence, model downloads, recording, decoding, accessibility insertion,
UI orchestration, and existing tests. Inspected relevant vendored inference code
to check context behavior. No subagents were used. No new phone timings, native
stress tests, or device UI tests were performed.

`testDebugUnitTest lintDebug` completed successfully. Gradle reused up-to-date
unit-test and analysis outputs; this was not a fresh execution of the tests.
The lint report contains **0 errors and 59 warnings**. Existing coverage does not
establish native cancellation, codec failure cleanup, or concurrency correctness.

Evidence labels below: **confirmed** means a source-level defect with an explicit
trigger; **risk** means a code-supported failure scenario requiring a runtime
reproducer; **improvement** means missing behavior or maintainability work.
Line numbers refer to the audit baseline and will drift during implementation.

- **P0:** emergency release blocker. None established by this audit.
- **P1:** fix before the next broad release or performance promotion; data loss,
  incorrect transcription, crash/resource leak, or broken cancellation.
- **P2:** next iteration; consistency, usability, diagnostics, or test coverage.
- **P3:** optional product expansion after correctness and performance gates.

Effort sizes are planning estimates: S = roughly half to one engineering day,
M = one to three, L = three to seven; device access and investigation can extend
these. Each item includes a proposed commit boundary, not authorization to push.

## P1 implementation record

- G01–G03: `ee1a57c` — native cancellation/status propagation, atomic model
  lease, and per-request decoder history reset.
- G04: `09ddd1d` — decoder resource safety, cancellation, and bounded decode.
- G05: `8b3fe58` — verified-only model loading.
- G06: `bafc78d` — transactional download, replacement, selection, and deletion.
- G07: `9df06cd` — immutable transcription sessions and fail-closed insertion.
- G08: `b1b6b45`, `ae571d3`, `d295947` — atomic literal dictionary replacement
  plus JVM-safe regression coverage.
- G09: `c039d99` plus the final cancellation correction in `09ddd1d` — safe
  asynchronous recorder lifecycle.
- G10: `824308d` — framework-hint-only placeholder replacement.

`testDebugUnitTest` passed after integration. Native cancellation latency,
malformed-codec cleanup, model replacement races, and accessibility behavior
still require the device validation specified below before a broad release.

## Ranked backlog

| ID | Priority | Evidence | Issue | Effort |
|---|---|---|---|---|
| G01 | P1 | Confirmed | Native abort hook runs too late; failures lack typed propagation | L |
| G02 | P1 | Confirmed race | Requested model is not bound atomically to inference | M |
| G03 | P1 | Confirmed | Decoder context persists across unrelated requests | M |
| G04 | P1 | Confirmed | Decoder cancellation, resource cleanup, and allocation limits missing | L |
| G05 | P1 | Confirmed | Startup preload bypasses model verification | M |
| G06 | P1 | Confirmed | Model replacement/deletion can lose files and corrupt selection | M |
| G07 | P1 | Confirmed race | Overlapping requests can publish or paste stale results | L |
| G08 | P1 | Confirmed | Dictionary replacement treats user strings as regex substitutions | M |
| G09 | P1 | Confirmed/risk | Recorder start/error/stop lifecycle is incomplete | L |
| G10 | P1 | Confirmed | Placeholder heuristics can erase real user text | S |
| G11 | P2 | Confirmed | Overlay ignores cleanup settings and misrecords duration | S |
| G12 | P2 | Confirmed | Export only includes the latest 200 history rows | M |
| G13 | P2 | Confirmed | Cleanup can remove legitimate speech and paragraph breaks | M |
| G14 | P2 | Confirmed | Download UI confuses transfer completion with verification | M |
| G15 | P2 | Risk/improvement | Database singleton and migration validation need hardening | M |
| G16 | P2 | Confirmed | Shared URI metadata and permission UX need defensive handling | S–M |
| G17 | P2 | Improvement | Complete localization and operational state visibility | M |
| G18 | P2 | Improvement | Add behavior-focused CI and align documentation with actual features | M |
| G19 | P3 | Improvement | Define explicit background-recording lifecycle | L |

## G01 — cancellation and native result ownership

**Evidence:** `app/src/main/java/com/whispercpp/whisper/LibWhisper.kt:25` installs
`Job.invokeOnCompletion` around a blocking JNI call. The default handler runs on
completion; it does not interrupt that call when cancellation first begins. The
handler is also disposed in `finally`. JNI's `fullTranscribeWithParams` is `void`
and merely logs a nonzero `whisper_full` result. Kotlin subsequently reads segment
text regardless of success. `WhisperEngine.kt:134` catches all `Exception`s and
turns failures into empty text. Empty speech and execution failure become
indistinguishable. Warmup has no abort callback.

**Implementation:** introduce a request-scoped cancellation token registered
before native execution, with cancellation delivered independently of the blocked
worker. Eliminate the race where a cancellation request is cleared at native
entry. Return native success/error/abort status and read text only after success.
Rethrow coroutine cancellation at every layer. Add a typed result carrying
segments, timings, and status; localize errors in the UI. Serialize native release
on the context owner and wait for work to end before freeing abort state/context.
Use non-cancellable cleanup where required; do not free native memory while it
is executing. Preserve the existing engine mutex until ownership tests pass.

**Validation:** cancel before dispatch, while queued, during encode, during
decode, and during warmup. On a device, require bounded cancellation latency
(proposed target under one second, measured by stage), no further CPU work after
acknowledgement, no stale segment read, and a successful next transcription.
Use injected native failures to verify errors never become “no speech.” Race
release/cancel/complete under a stress harness. Unit tests alone cannot certify it.
**Commit:** `Fix native transcription cancellation and result propagation`.

## G02 — make model selection and inference atomic

**Evidence:** repository `transcribeAudio:617` and `transcribeSharedFile:644`
call `preloadModel`, then call `WhisperEngine.transcribe` separately. Engine
`loadModel:38` and `transcribe:100` lock individually. Between those operations,
selection, a completed download, warmup, or memory eviction can replace/free the
context. The request may run model B while reporting model A, or return empty.

**Implementation:** provide one engine operation accepting the expected model
identity/checksum plus request parameters. Verify/load and execute under one
context lease. Model selection updates preferences immediately but queues native
replacement behind active work. Eviction rechecks idleness inside the same owner.
Replace the free-standing `modelLoaded` Boolean with actual engine state containing
model identity, loading/ready/busy/error state, and generation.

**Validation:** controlled barriers reproduce A-load → B-selection → A-inference;
assert A uses A. Repeat with download completion and memory-pressure eviction.
Check UI labels against returned actual model identity. **Depends on:** G01 result
contract. **Commit:** `Bind model lifecycle to transcription requests`.

## G03 — isolate context between sessions

**Evidence:** `WhisperParams.kt` defaults `noContext=false`; `forLiveAudio` and
shared-file parameters preserve it. The same native context survives recordings.
Vendored `whisper.cpp:6918` clears `prompt_past0/1` only when `no_context` is true.
Unrelated dictation or a different shared file can inherit previous transcript
tokens, including between applications. The extent of observable text carryover
needs a device test; the retained state itself is confirmed.

**Implementation:** explicitly reset decoder history at every independent request
boundary while preserving continuity between windows of that request. Inspect
the pinned upstream semantics before choosing `no_context=true` versus a fresh
Whisper state; do not disable useful within-file continuity indiscriminately.
Treat warmup, language/model change, abort, and failed inference as reset boundaries.

**Validation:** transcribe distinctive A then unrelated B; compare B against B on
a fresh context. Include Spanish/English switching and a >60-second file to prove
within-file coherence still works. **Depends on:** G02. **Commit:**
`Reset decoder history between independent transcription sessions`.

## G04 — robust shared-audio decoding

**Evidence:** `AudioDecoder.kt:80` releases codec/extractor only on the success
path; even `setDataSource` failure returns without releasing the extractor.
The blocking loop at line 140 has no cancellation checks or no-progress deadline.
Duration metadata controls a potentially enormous `FloatArray` at line 125.
Unsupported PCM encoding only logs and drops audio. A rate change after output
starts updates `sampleRate` but leaves the original resampler ratio in place.

**Implementation:** own extractor, codec, and dequeued output buffers with
`try/finally`; handle configure/start/stop failures separately. Check cancellation
between buffer operations, add a no-progress watchdog, and propagate typed errors.
Clamp initial allocation and enforce an explicit decoded-duration/memory budget.
For midstream rate changes, either transition a stateful resampler correctly or
reject with a clear unsupported-format error. Reject unsupported PCM instead of
returning a silently shortened transcript. Follow P04 for bounded storage.

**Validation:** corrupt/truncated audio, revoked URI, unsupported codec/PCM,
untrusted duration, missing EOS, cancellation, and format changes. Assert resources
release on every exit and decoded length/rate are correct. Native MediaCodec
instrumentation is required; existing converter tests are not enough.
**Commit:** `Make shared audio decoding cancellable and resource safe`.

## G05 — one verified model entry point

**Evidence:** no-argument `DictationRepository.preloadModel:331`, invoked from
`VozLocalApp.onCreate`, loads directly into the engine; the overload at line 346
verifies first. Startup seeding uses size checks, so a large unverified/corrupt
legacy model can reach the native parser before the verified path. Startup
initialization and preload are launched independently.

**Implementation:** centralize verification before every context creation; await
catalog initialization once. Return a verified file identity from the loader,
not an unchecked path. Tie verification records to file replacement generations;
invalidate sidecars on replace/delete. Keep exact pinned digests and use immutable
download revisions. Do not hash hundreds of MB on every warm request: use an
identity-aware cache under the same file lifecycle ownership (P05).

**Validation:** legacy valid model, minimum-size corrupt file, same-size replaced
file, absent/stale sidecar, interrupted verification, and startup races. Assert
the native factory is never called until verification succeeds. **Commit:**
`Enforce verification for startup and on-demand model loading`.

## G06 — transactional model management

**Evidence:** `deleteDownloadedModel:596` selects Tiny, then writes the original
model copy with its original `isSelected`; deleting a selected non-Tiny model can
restore its selection and leave two selected rows. File deletion success is
ignored. `redownloadModel:375` deletes before downloading. `ModelDownloader`
also deletes the existing output when verification of a new partial file fails.
`startModelDownload:543` performs a non-atomic check/set of download state and
later writes an old entity snapshot; concurrent selection/downloads can overwrite
new state. VAD stale-part cleanup can remove another active attempt.

**Implementation:** per-model single-flight operations; database column updates
and selection changes in transactions. Stage and verify replacements while
retaining the old verified file, then atomically promote. Coordinate with G02
before deleting loaded files. Select an actually downloaded alternative or no
model; publish filesystem errors. Clean only the current operation's temporary
files and remove matching verification records.

**Validation:** two simultaneous downloads, selection during download, failed
replacement, failed delete, cancellation, deleting selected Tiny/non-Tiny,
VAD retry/delete overlap, and app restart mid-operation. Assert at most one
selected model and a usable old model after failed replacement. **Depends on:**
G02/G05 ownership. **Commit:** `Make model replacement and selection transactional`.

## G07 — session-scoped UI and accessibility insertion

**Evidence:** live recording has no inference-job guard; another recording can
start before previous inference finishes. Overlay `recordingTarget` and
`startTimestamp` are mutable service fields reused by later sessions. A previous
result can therefore use a newer target. Policy `matchesRecordingTarget:26`
compares only package/window, not the input field. `setSharedAudio:580` and
`clearSharedFile:674` do not cancel ongoing work; completion uses the current
filename even for the old URI. Shared orchestration catches cancellation only;
ordinary codec/post-processing exceptions can leave busy UI or escape the scope.

**Implementation:** immutable request ID, model/settings snapshot, source URI/name,
recording duration, and insertion target per session. Maintain explicit
Idle/Recording/Processing/Success/Error/Cancelled state. Choose a bounded queue
or reject a second request with visible feedback. Gate every callback by request
ID. Cancel/await old work on replace/clear. Match the original input node using
stable available identity, revalidate at paste, and fail closed if identity cannot
be established. Preserve the transcript in a recoverable result view if insertion
fails, including when history saving is off. Never redirect it to a new field.

**Validation:** rapid record/stop/restart, app/field switch during inference,
protected-app switch, same-window two-field form, share A then B, clear while
decoding, and destroyed UI/service. Old results cannot modify newer sessions.
**Depends on:** G01/G02. **Commit:** `Scope transcription and insertion to immutable sessions`.

## G08 — literal and thread-safe dictionary replacements

**Evidence:** `postProcessText:849` calls `Regex.replace(input, replacement)` with
user-supplied canonical text. A canonical `$5` references a nonexistent capture
group; backslashes are interpreted rather than preserved. Three separate mutable
cache fields are read/written on `Dispatchers.Default`, while edits invalidate
them independently. Concurrent requests can see mismatched lists. Cache hashing
omits `replacement`, and hashes are not a robust identity contract.

**Implementation:** replace using a lambda returning literal canonical text.
Publish an immutable list of compiled pattern/replacement pairs atomically,
versioned by the entire dictionary snapshot. Define ordered replacement semantics
to avoid unintended cascading substitutions. Build snapshots outside critical
paths and use the same snapshot throughout each request.

**Validation:** `$5`, backslashes, accents, emoji, regex metacharacters, replacement
edits, and concurrent dictionary edits/transcriptions. No exceptions, lost literal
characters, or mixed snapshots. **Commit:** `Preserve literal dictionary values and atomic caches`.

## G09 — recorder lifecycle and error recovery

**Evidence:** `AudioRecorder.startRecording:76` sets recording state before
`AudioRecord.startRecording`; exceptions leave partially initialized state.
The read loop ignores negative error codes and may spin. Cancellation of the
owning scope has no reader `finally` to reconcile state. `stopRecording:163` and
`release:215` use `runBlocking` to join under a monitor and are called from UI/
service main-thread handlers. Native stop and release share a try block, so stop
failure skips release. Overlay start does not catch permission/start exceptions.

**Implementation:** explicit session state and failure rollback; publish recording
only after the platform start succeeds. Handle terminal read errors with cleanup
and UI notification. Make stop/discard suspending, unblock read, await completion
off the main thread, and release in finally. Avoid lock-held joins. Add a discard
operation that does not allocate a full PCM copy. Bound recording storage (P04).

**Validation:** denied/revoked mic permission, constructor/start/read/stop failures,
scope cancellation, rapid ownership transfer, and last-block preservation. Device
test with UI frame tracing; no hang, busy spin, or stuck recording state.
**Commit:** `Make recorder shutdown asynchronous and error safe`.

## G10 — preserve real text that resembles a hint

**Evidence:** `AccessibilityTargetPolicy.isPlaceholderText:53` treats words such
as “message” and “search” as placeholders, even if `isShowingHintText=false`.
`computeInsertionText` then discards the existing content. Equal content
descriptions are also insufficient proof that text is a placeholder.

**Implementation:** use framework hint state and narrowly tested fallback rules
only where the text is truly absent. Preserve ambiguous nonempty text and respect
selection. Update tests that currently encode the destructive heuristic.
**Validation:** literal “message”, “buscar”, text equal to a hint, actual hints,
selected ranges, and empty controls. **Commit:** `Preserve ambiguous editable text during dictation`.

## G11 — consistent overlay settings and statistics

**Evidence:** service `processAndPaste:687` hardcodes punctuation, capitalization,
and dictionary to true. Duration is calculated after inference using a shared
start timestamp and therefore includes waiting/compute time. Overlay never calls
`insertStat`, unlike in-app dictation.

**Implementation:** capture repository settings and sample-derived recording
duration per G07 session; apply the same pipeline from both entry points. Define
whether failed insertion counts as completed dictation and apply that policy
consistently. **Validation:** every toggle off/on, matching app/overlay audio,
slow inference, and insertion failure. **Commit:** `Unify overlay settings and dictation accounting`.

## G12 — full history export and bounded retention

**Evidence:** ViewModel history uses `pagedHistory()` with fixed limit 200.
Settings export reads that same flow, so “complete history” omits older records.
`pruneHistory:367` loads everything and deletes rows individually; stats remain
unbounded. Large `ACTION_SEND` text also needs a file-based path.

**Implementation:** separate paged display from complete snapshot export; stream
export through a private temporary file and a scoped content URI. Batch retention
deletion in SQL within an insert/prune transaction, add timestamp/ID tie-breaking,
and define stats retention independently of transcript preferences.
**Validation:** >200 records, multi-MB output, identical timestamps, limited history,
history disabled, concurrent insert/export, and receiving-app access. **Commit:**
`Export complete history independently of UI pagination`.

## G13 — preserve accuracy through cleanup

**Evidence:** unconditional `HallucinationFilter` deletes a legitimate trailing
“gracias por escuchar” or “thanks for watching” and collapses intentional repeated
phrases. `postProcessText` first replaces all `\s+` with one space, removing
existing paragraphs. Default question heuristics can turn declarative Spanish
sentences beginning with “cuando” or “como” into questions.

**Implementation:** preserve raw ASR text separately from cleaned output; make
aggressive removal explicit and conservative by default. Preserve newline
structure using horizontal whitespace normalization. Gate heuristic questions
by language and stronger evidence or an optional cleanup setting. P02 measures
raw and processed accuracy separately. **Validation:** intentional outros,
“muy bien, muy bien”, paragraphs, quoted text, and declarative/question minimal
pairs. **Commit:** `Make transcription cleanup conservative and reversible`.

## G14 — accurate download states and cancellation

**Evidence:** body progress emits 1.0 before hashing/promotion. ViewModel assigns
a “Verified” label at download start and retains “Downloading” on failure callbacks.
Blocking OkHttp execution/read has no cancellation hook; broad catches swallow
cancellation and cleanup depends on another suspend call. Unknown content length
does not produce those progress callbacks at all.

**Implementation:** typed Connecting/Downloading/Verifying/Ready/Failed/Cancelled
states; only Ready is verified. Cancel the OkHttp call when the job is cancelled,
check cancellation in copy/hash loops, and clean owned partials in finally.
Reserve terminal success until digest, promotion, and catalog update succeed.
**Validation:** slow/stalled response, unknown length, checksum failure, cancel
during read/hash, and retry. **Depends on:** G06. **Commit:**
`Report verified download completion and support prompt cancellation`.

## G15–G19 — general hardening and product work

### G15: database construction and migration tests (P2, M)

`AppDatabase.getDatabase` checks INSTANCE outside the synchronized block but not
inside it; simultaneous first callers can construct multiple instances. Add the
inner check. Only schema v2 is checked in; the empty 1→2 migration is not proven
wrong, but needs a historical v1 fixture and MigrationTestHelper verification.
Validate preservation of all user tables. Commit singleton fix separately from
migration infrastructure; do not invent a schema migration without evidence.

### G16: shared metadata and microphone permission (P2, S–M)

`setSharedAudio` performs a provider query on the main thread, assumes columns
exist, and can leave old name/size when a cursor is empty. Move metadata to IO,
reset fields, validate optional/null columns, and bind the result to the request.
Request microphone permission when recording is requested, since file import
does not require it. Handle transient URI grants and recreation explicitly.
Test missing metadata, slow providers, repeated shares, and denied microphone.

### G17: localized errors and honest operational state (P2, M)

Many strings in MainActivity, service, ViewModel, VAD repository state, and
Settings remain English literals. `isModelLoading` is permanently false and
`modelLoaded` does not identify the loaded model. Use typed domain events and
English/Spanish resources; expose actual loading, processing, and recoverable
failure from G01/G02. Test both locales, large fonts, denied permissions, and
unavailable model/backend. Do not couple control flow to translated strings.

### G18: coverage, CI, and documentation truth (P2, M)

Add CI for JVM tests, lint, native build, release ABI packaging, and migration
checks, then device jobs for G01/G04/G09. Existing recorder tests use reflection
and tolerate exceptions; add deterministic failure-injection behavior tests.
No `.github` workflow was found. Triage the 59 lint warnings rather than blindly
applying them: synchronous probe-sentinel persistence is intentional.

Correct documentation claims: code chooses thread counts, not core affinity;
the Kotlin wrapper has no Cleaner backstop despite a README claim; streaming
has buffer snapshot support but no live preview orchestration at this baseline;
“full history export” is limited by G12. `Performance.md` overstates the readiness
of a Pixel 8 TPU prototype; see P11. Preserve historical observations and mark
corrections explicitly. Keep these plans as a backlog until work ships.

### G19: background recording lifecycle (P3, L)

No recording foreground service is declared. Define intended screen-off,
background, and accessibility behavior before promising long background capture.
If implemented, use a user-started microphone foreground service, visible stop
control, and version-appropriate permission/lifecycle handling. Test app switching,
screen lock, process death, microphone revocation, and protected applications on
supported Android versions. This is product work, not a claim of a new permission
vulnerability in the current manifest.

## Implementation order and future delegation boundaries

1. Establish G01 typed results/cancellation and G02 engine ownership together;
   add G03 session reset before recording new performance baselines.
2. G04 decoder and G09 recorder can be separate owners after agreeing the result
   and session interfaces. G05/G06/G14 form one model-management workstream.
3. G07 UI/session owner integrates those contracts; G10/G11 stay with that owner
   to avoid conflicting edits in the accessibility service.
4. G08/G13 post-processing and G12/G15 persistence can be independent owners.
5. G16/G17 UI work follows stable result types; G18 validates every merge.
6. P01–P11 in the performance plan consume corrected behavior and tests.

Do not split concurrent edits to `DictationRepository.kt`, `MainViewModel.kt`, or
the JNI shim between agents. Agree interfaces first and merge small, testable
commits sequentially. Each future task should carry its ID, allowed files,
dependencies, reproducer, acceptance criteria, and benchmark impact.

## Release and rollback criteria

- No regression in protected-app/password filtering or offline inference.
- Native cancel/release stress and model switching pass on real ARM64 hardware.
- Failure never silently becomes successful empty output or an incorrect paste.
- Existing files survive failed replacements; history export completeness passes.
- CPU Compatibility mode and the last known-good model remain usable.
- Each change reports checks actually run; cached checks and untested device paths
  are explicitly identified. A passing JVM suite does not close native risks.

Reference: [Kotlin Job completion semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-job/invoke-on-completion.html).
