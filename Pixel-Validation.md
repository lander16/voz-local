# Pixel native validation

## Isolation and scope

Use `validation`, never replace/uninstall the user's signed production package.
The package is `dev.sebastian.vozlocal.validation`, labelled **VozLocal Validation**,
debug-signed and debuggable, with the accessibility service removed. Production
data, permissions, model downloads, and accessibility settings are not touched.
The native library still uses the project's Release/O3 CMake configuration.
This is **not** a minified, production-signed release acceptance test.

The fixture-driven test checks Small q5_1 checksum verification, three native
cancel/restart cycles, successful transcription after cancellation, and repeated
3/4/5-thread batch measurements. It refuses battery saver and moderate/higher
thermal status. It does not establish multilingual accuracy, GPU/NPU performance,
bank compatibility, floating-button insertion, or end-to-end UI latency.

## Reproduce

Android Studio's SDK includes ADB even when it is not on PATH. On macOS:

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
./gradlew :app:assembleValidation :app:assembleValidationAndroidTest -PdeviceTestBuildType=validation
"$ADB" install -r app/build/outputs/apk/validation/app-validation.apk
"$ADB" install -r app/build/outputs/apk/androidTest/validation/app-validation-androidTest.apk
```

Obtain `ggml-small-q5_1.bin` from the model URL already recorded in `ModelUrls`.
Its SHA-256 must be
`ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`.
Name the local fixture `small-q5_1.bin`. Convert the vendored public JFK excerpt:

```sh
ffmpeg -i app/src/main/jni/whisper/samples/jfk.wav -f f32le -ac 1 -ar 16000 speech.f32
```

The resulting PCM SHA-256 is
`ebd52851100536db02d12c49fddd010372dcdc70243562e057553d476b706ae0`.
Use the app's **private** fixture folder; Android may deny instrumentation access
to files pushed by the shell under `Android/data`:

```sh
"$ADB" push small-q5_1.bin speech.f32 /data/local/tmp/
"$ADB" shell run-as dev.sebastian.vozlocal.validation mkdir -p files/validation
"$ADB" shell run-as dev.sebastian.vozlocal.validation cp /data/local/tmp/small-q5_1.bin /data/local/tmp/speech.f32 files/validation/
"$ADB" shell am instrument -w -r -e class dev.sebastian.vozlocal.PixelNativeValidationTest#smallQ51CancellationAndThreadMeasurements dev.sebastian.vozlocal.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (1 test)`, not merely a successful ADB process exit code. Android's
instrumentation command can exit zero while reporting a failed test.

To compare Automatic kernels, set the preference in the **validation** app and
restart that app before rerunning the measurement test:

```sh
"$ADB" shell am instrument -w -r -e backend AUTOMATIC -e class dev.sebastian.vozlocal.PixelNativeValidationTest#configureBackendForNextProcess dev.sebastian.vozlocal.validation.test/androidx.test.runner.AndroidJUnitRunner
"$ADB" shell am force-stop dev.sebastian.vozlocal.validation
```

Use `COMPATIBILITY` instead of `AUTOMATIC` to restore the baseline. Let the phone
cool between groups. Do not change production-app settings. Results are stored in
the validation app's `files/validation/native-validation-<mode>-cancel.txt` and
`benchmark-results-<mode>.json`. Read them with `adb exec-out run-as` and archive
them alongside the tested commit and actual effective backend diagnostics.

The `threadMeasurementsOnly` method runs the timing checks without claiming to
validate cancellation. For the complete calibration/persistence check, also stage
the official `ggml-tiny-q8_0.bin` as `tiny-q8_0.bin` in the private fixture folder
and run `PixelNativeValidationTest#calibrationPersistsAcrossReload`. Tiny's expected
SHA-256 is `c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca`.
That test explicitly selects Tiny only in the validation app, runs 18 trials,
and verifies the saved profile survives reloading from disk. Using Tiny bounds
the battery cost; it is not a Small-model calibration result.

## September 7, 2026 results

Hardware: Pixel 8 Pro / Tensor G3, Android 17 (API 37), battery saver off.
Native graphs compiled Release/O3; app variant debuggable, not production-signed.
Fixture: 11 seconds, English JFK speech, float32 16 kHz mono; Small q5_1,
language `en`, prompt OFF, greedy, temperature increment 0.2, VAD off, full audio
context. Source baseline `7898751` plus the app-owned native patch archived with
this report. Exact native source fingerprints are embedded in the JSON records.

### Native cancellation

- Initial run after Kotlin-only fix **failed**: cancellation took 7,510 ms.
- Cause: upstream's scheduled graph helper did not propagate the abort callback
  into the CPU backend, unlike its non-scheduled helper.
- App-owned CMake patch generates a corrected translation unit without modifying
  the submodule. Its four encoder/decoder call sites and helper replacements are
  counted; unexpected upstream changes fail configuration rather than silently
  losing cancellation. ARM64 and x86_64 builds succeeded.
- Compatibility after patch: **121, 108, 45 ms**. Automatic/I8MM: **151, 467,
  150 ms**. Both runs then transcribed the reference clip correctly using the
  context that had been cancelled repeatedly. Both instrumentation runs passed.

### Small q5_1 measurements

Median native inference wall time, three observations per thread count:

| Threads | Compatibility | Automatic / I8MM |
| --- | ---: | ---: |
| 3 | 21.339 s | 15.361 s |
| 4 | 15.397 s | 12.830 s |
| 5 | 16.616 s | 14.913 s |

Four threads were best in this sample. Automatic's four-thread median was 16.7%
lower latency than Compatibility's four-thread median. Do **not** generalize
this to all clips or change global defaults from this single experiment. Thermal
status ranged from none to light; there were outliers, modes ran sequentially,
and one first trial per mode included a fresh context/warmup before its timed
inference. These are not end-to-end microphone, Spanish, or production-release
results. The measured wall times are slower than real time for this fixture.

Raw records:

- [Compatibility](docs/validation/2026-09-07/compatibility-legacy-average-timings.json):
  wall-clock inference measurements are usable, but **native timing fields and
  derived stage fields used upstream per-call averages**, not totals. Do not sum
  or compare those fields as stage totals. Native ID: `1.9.2-vozlocal-de792b1a0d92`.
- [Automatic](docs/validation/2026-09-07/automatic-stage-totals.json): corrected
  stage totals, native ID `1.9.2-vozlocal-bf0afa647cbf`. The app-owned C++ accessor
  reads resettable totals without allocating/deallocating the upstream timing
  object across the C/C++ boundary. Encoder time dominates these runs.
- [Compatibility cancellation](docs/validation/2026-09-07/native-validation-compatibility-cancel.txt)
  and [Automatic cancellation](docs/validation/2026-09-07/native-validation-automatic-cancel.txt).
- [Calibration](docs/validation/2026-09-07/calibration-automatic.txt): Tiny q8_0,
  18/18 trials, 20.150 seconds, selected three threads, persistence/reload passed.

Host checks after the native changes: 199 unit tests passed; debug lint reported
zero errors and 62 warnings. The native fix and harness do not change the
production application's permissions or installation. No production uninstall
or replacement command was issued during this session.

## Remaining release gates

- Repeat with Spanish reference recordings, quiet endings, noise and long files.
- Measure battery/thermal effects and repeat cold/warm groups in alternating order.
- Repeat calibration with Small, test runtime/model/settings invalidation, and
  verify profile use after a full application restart (the Tiny test reloads disk
  state in-process, not through a reboot).
- Check model selection/deletion/replacement while native work is active.
- Validate Settings, microphone recording, shared audio, recovery, and English/
  Spanish labels manually on a signed release built from the same commit.
- Test accessibility separately with explicit user approval; this isolated package
  deliberately cannot validate it. Never open or operate the bank app as part of
  automated performance tests.

Leave the validation app installed for follow-up only with the user's knowledge.
Removing it discards only its disposable test data, not production VozLocal data.
