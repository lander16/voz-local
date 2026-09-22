# Moonshine app integration validation — 2026-09-22

Scope: experimental Spanish Tiny/Small complete-clip dictation, tracked in
[issue #11](https://github.com/lander16/voz-local/issues/11). This is functional
integration evidence, not a new speed, accuracy, energy or bank-compatibility result.

## Device checks

`MoonshineAppIntegrationTest` ran against `dev.sebastian.vozlocal.validation` only.
The signed production package was not replaced and no bank app was inspected.
Both test methods passed: `OK (2 tests)`, instrumentation elapsed 61.936 seconds
(whole-test duration, **not** inference latency).

For each model, the test downloaded its official pinned bundle through the app's
downloader, checked that download did not select it, selected it explicitly,
obtained nonempty transcription, cancelled a 30-second request, waited for native
drain, retried, reused the engine after shutdown, switched to Whisper Small q8_0
and back, then deleted the candidate and verified Whisper fallback selection.
Cancelled text was not saved or inserted. No transcripts or audio are retained here.

Runtime/model identities are pinned in `MoonshineModels.kt` and the
[screening manifests](../2026-09-21-moonshine/). The same privately staged 10-second
mono 16 kHz float PCM was used; the 30-second input repeats that fixture. Accuracy
references are unavailable. The test explicitly sets Spanish and otherwise uses
the validation app's settings; no timing comparison is made.

PCM SHA-256: `23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a`.

- Device: Pixel 8 Pro, ARM64, Android 17.
- Build: `google/husky/husky:17/CP2A.260805.005/15828068:user/release-keys`.
- Post-run state: Battery Saver off, unplugged, 94%, battery 33.6 °C,
  system thermal status 0/NONE. Pre-run state was not retained; do not use this
  smoke test for thermal or performance comparisons.
- Source base: `6eee20167a3ab4b95669bde8e142cc993cbe3b58` plus the integration
  changes committed alongside this evidence. A later test-only move-injection
  seam and regression tests do not alter the normal device path.
- Validation APK SHA-256:
  `38d77bd1a361a3f9a8b7aa8924c72ea8afd879cc7e1361fc41c907df216180df`.
- Instrumentation APK SHA-256:
  `de497548732289f52ff8137739cebfc8af5f5d1c131284ad4e5812f248ad2065`.

Reproduction (Android Studio JDK and SDK installed):

```sh
./gradlew -PdeviceTestBuildType=validation assembleValidation assembleValidationAndroidTest
adb install -r app/build/outputs/apk/validation/app-validation.apk
adb install -r app/build/outputs/apk/androidTest/validation/app-validation-androidTest.apk
adb shell am instrument -w -e class dev.sebastian.vozlocal.MoonshineAppIntegrationTest dev.sebastian.vozlocal.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Stage the consented private PCM and verified Whisper control using the existing
screening procedure first. The test needs network access only to download bundles;
it does not establish network-disabled operation by itself.

## Remaining acceptance work

- Representative Spanish accuracy and semantic-error review (#2), mixed-language
  behavior and the existing English Whisper regression corpus.
- Real-time streaming, longer clips and shared-file support are not implemented.
- Native abort is unavailable. Cancellation stops result delivery, not computation.
- Signed/minified production on-device acceptance, microphone/Bluetooth interruption,
  offline network-disabled execution, process-death/memory-pressure and sustained
  resource testing remain separate follow-ups.
- No NPU implementation or energy-efficiency claim is introduced.

## Host and release checks

- `testDebugUnitTest`: 240 tests, 0 failures/errors, including downloader
  corruption/oversize, blocked-body cancellation, transactional rollback and
  recovery, model routing, localized labels and native ownership tests.
- `lintDebug`: passed.
- `assembleRelease`: passed with R8 minification and resource shrinking. The
  output was an **unsigned** release APK and was not installed on the phone.
- `zipalign -c -P 16 4` on that APK: passed. ARM64 and x86_64 packages contain
  `libmoonshine-jni.so`, `libmoonshine.so`, `libonnxruntime.so` and existing
  `libwhisper.so`. The three Moonshine ARM64 libraries have `0x4000` ELF LOAD
  alignment (16 KiB).
- The merged release manifest has no `ACCESS_NETWORK_STATE` or Moonshine SDK
  convenience microphone activity. The accessibility service remains
  `exported=false`. Existing permissions include microphone, internet, vibrate,
  and AndroidX work scheduling permissions; the SDK adds no new permission.
- Unsigned release APK SHA-256:
  `488314c7aeb41144afb7a61ab764a11e946bbc1b8c857fc4a3f1d50c6a34f062`.
