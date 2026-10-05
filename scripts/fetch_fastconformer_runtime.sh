#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
runtime_dir="$root_dir/fastconformer-validation/libs"
runtime_file="$runtime_dir/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
runtime_part="$runtime_file.part"
runtime_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
expected_size=38691998
expected_sha256="b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

mkdir -p "$runtime_dir"
if [[ -f "$runtime_file" ]]; then
  actual_sha256="$(shasum -a 256 "$runtime_file" | awk '{print $1}')"
  actual_size="$(wc -c < "$runtime_file" | tr -d '[:space:]')"
  if [[ "$actual_sha256" == "$expected_sha256" && "$actual_size" == "$expected_size" ]]; then
    echo "Verified cached sherpa-onnx AAR: $actual_sha256 ($actual_size bytes)"
    exit 0
  fi
  echo "Existing sherpa-onnx AAR failed size/SHA-256 verification" >&2
  exit 1
fi

curl --fail --location --retry 2 --connect-timeout 20 --max-time 300 --output "$runtime_part" "$runtime_url"
actual_sha256="$(shasum -a 256 "$runtime_part" | awk '{print $1}')"
actual_size="$(wc -c < "$runtime_part" | tr -d '[:space:]')"
if [[ "$actual_sha256" != "$expected_sha256" || "$actual_size" != "$expected_size" ]]; then
  echo "Downloaded sherpa-onnx AAR failed size/SHA-256 verification" >&2
  exit 1
fi
mv "$runtime_part" "$runtime_file"
echo "Verified sherpa-onnx AAR: $actual_sha256 ($actual_size bytes)"
