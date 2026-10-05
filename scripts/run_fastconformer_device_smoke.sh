#!/usr/bin/env bash
set -euo pipefail
umask 077

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
adb_bin="${ADB:-/Users/sebastian/Library/Android/sdk/platform-tools/adb}"
serial="${ANDROID_SERIAL:-}"
[[ -n "$serial" ]] || { echo "Set ANDROID_SERIAL to the intended Pixel serial" >&2; exit 2; }
app_id="dev.sebastian.vozlocal.fastconformer.validation"
test_id="$app_id.test"
runner="$test_id/androidx.test.runner.AndroidJUnitRunner"
staging="/sdcard/Android/data/$app_id/files/fastconformer-validation"
asset_dir="$root_dir/fastconformer-validation/build/replay-inputs"
result_dir="$root_dir/fastconformer-validation/build/replay-results"
app_apk="$root_dir/fastconformer-validation/build/outputs/apk/debug/fastconformer-validation-debug.apk"
test_apk="$root_dir/fastconformer-validation/build/outputs/apk/androidTest/debug/fastconformer-validation-debug-androidTest.apk"

bash "$root_dir/scripts/fetch_fastconformer_runtime.sh"
bash "$root_dir/scripts/fetch_fastconformer_artifacts.sh"
env JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}" \
  "$root_dir/gradlew" -PfastConformerValidation=true :fastconformer-validation:assembleDebug :fastconformer-validation:assembleDebugAndroidTest

"$adb_bin" -s "$serial" install -r "$root_dir/fastconformer-validation/build/outputs/apk/debug/fastconformer-validation-debug.apk"
"$adb_bin" -s "$serial" install -r "$root_dir/fastconformer-validation/build/outputs/apk/androidTest/debug/fastconformer-validation-debug-androidTest.apk"
source_commit="$(git -C "$root_dir" rev-parse HEAD)"
source_tree_dirty="$(if [[ -n "$(git -C "$root_dir" status --short --untracked-files=all)" ]]; then echo true; else echo false; fi)"
app_sha="$(shasum -a 256 "$app_apk" | awk '{print $1}')"
test_sha="$(shasum -a 256 "$test_apk" | awk '{print $1}')"
requested_run_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
"$adb_bin" -s "$serial" shell am instrument -w -e class \
  dev.sebastian.vozlocal.fastconformervalidation.FastConformerSmokeTest#prepareStagingDirectory "$runner"
"$adb_bin" -s "$serial" push "$asset_dir/model.int8.onnx" "$staging/model.int8.onnx"
"$adb_bin" -s "$serial" push "$asset_dir/tokens.txt" "$staging/tokens.txt"
"$adb_bin" -s "$serial" push "$asset_dir/speech.f32" "$staging/speech.f32"
instrument_output="$("$adb_bin" -s "$serial" shell am instrument -w \
  -e requestedRunId "$requested_run_id" \
  -e sourceCommit "$source_commit" \
  -e sourceTreeDirty "$source_tree_dirty" \
  -e appApkSha256 "$app_sha" \
  -e testApkSha256 "$test_sha" \
  -e class dev.sebastian.vozlocal.fastconformervalidation.FastConformerSmokeTest#offlineCtcDecodesPinnedFixtureWithoutPersistingText "$runner")"
printf '%s\n' "$instrument_output"
grep -Fq 'OK (1 test)' <<<"$instrument_output" || { echo "Instrumentation did not report exactly one passing test" >&2; exit 1; }
mkdir -p "$result_dir"
"$adb_bin" -s "$serial" pull "$staging/fastconformer-smoke-result.json" "$result_dir/fastconformer-smoke-result.json"
jq -e --arg run "$requested_run_id" --arg app "$app_sha" --arg test "$test_sha" \
  '.schema == "fastconformer-device-smoke-v1" and .transcript_persisted == false and .run_id == $run and .app_apk_sha256 == $app and .test_apk_sha256 == $test and .pcm_sha256 == "23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a" and (.inference | length) == 4' \
  "$result_dir/fastconformer-smoke-result.json" >/dev/null
jq '{schema,run_id,source_commit,source_tree_dirty,app_apk_sha256,test_apk_sha256,device,android_release,build_fingerprint,hf_revision,sherpa_version,runtime_aar_sha256,model_sha256,tokens_sha256,source_fixture_sha256,pcm_sha256,sample_rate_hz,sample_count,num_threads,bundle_verify_and_native_init_ms,inference,punctuation_counts,system_before,system_after,transcript_persisted}' \
  "$result_dir/fastconformer-smoke-result.json"
