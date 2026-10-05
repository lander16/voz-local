#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
asset_dir="$root_dir/fastconformer-validation/build/replay-inputs"
revision="d7694ab533e189621361a30deb701f189abc7568"
model_file="$asset_dir/model.int8.onnx"
tokens_file="$asset_dir/tokens.txt"
model_url="https://huggingface.co/krut42/voice-fastconformer-es-ctc-int8/resolve/$revision/model.int8.onnx"
tokens_url="https://huggingface.co/krut42/voice-fastconformer-es-ctc-int8/resolve/$revision/tokens.txt"

verify_file() {
  local file="$1" expected_size="$2" expected_sha="$3"
  local actual_size actual_sha
  actual_size="$(wc -c < "$file" | tr -d '[:space:]')"
  actual_sha="$(shasum -a 256 "$file" | awk '{print $1}')"
  [[ "$actual_size" == "$expected_size" && "$actual_sha" == "$expected_sha" ]] || {
    echo "Pinned artifact failed size/SHA-256 check: $(basename "$file")" >&2
    return 1
  }
}

mkdir -p "$asset_dir"
if [[ ! -f "$model_file" ]]; then
  curl --fail --location --retry 2 --connect-timeout 20 --max-time 600 --output "$model_file.part" "$model_url"
  verify_file "$model_file.part" 173888284 592d3342057253a342aa19cec9937f46645d35d30f52ac427a4ddc72c395a769
  mv "$model_file.part" "$model_file"
else
  verify_file "$model_file" 173888284 592d3342057253a342aa19cec9937f46645d35d30f52ac427a4ddc72c395a769
fi

if [[ ! -f "$tokens_file" ]]; then
  curl --fail --location --retry 2 --connect-timeout 20 --max-time 120 --output "$tokens_file.part" "$tokens_url"
  verify_file "$tokens_file.part" 10776 556118db7946e6636018ef59c9e0537b05660b0398e995d60825081e77625e6a
  mv "$tokens_file.part" "$tokens_file"
else
  verify_file "$tokens_file" 10776 556118db7946e6636018ef59c9e0537b05660b0398e995d60825081e77625e6a
fi

source_audio="$root_dir/app/src/test/resources/test_audio_2min.ogg"
pcm_file="$asset_dir/speech.f32"
source_sha="19d015c3c78fe806134ddffbe144d216263b4d195b27cd7799ea667a3c575ba1"
expected_pcm_sha="23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a"
[[ "$(shasum -a 256 "$source_audio" | awk '{print $1}')" == "$source_sha" ]] || {
  echo "Existing source audio does not match the recorded fixture hash" >&2
  exit 1
}
ffmpeg -hide_banner -loglevel error -y -i "$source_audio" -t 10 -ac 1 -ar 16000 -f f32le "$pcm_file"
[[ "$(shasum -a 256 "$pcm_file" | awk '{print $1}')" == "$expected_pcm_sha" ]] || {
  echo "Prepared 10-second PCM does not match the retained fixture hash" >&2
  exit 1
}

echo "Verified pinned model, tokens and existing 10-second PCM fixture in ignored build/replay-inputs. No files were added to source assets."
