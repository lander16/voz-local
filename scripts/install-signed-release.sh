#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
PACKAGE_NAME="dev.sebastian.vozlocal"
KEYCHAIN_ACCOUNT="${USER:-vozlocal}"
STORE_SERVICE="dev.sebastian.vozlocal.release-store-password"
KEY_SERVICE="dev.sebastian.vozlocal.release-key-password"
ALLOW_FIRST_INSTALL=false
SKIP_TESTS=false
TEMP_DIR=""
STORE_SECRET=""
KEY_SECRET=""

usage() {
  cat <<'EOF'
Usage:
  scripts/install-signed-release.sh --setup-keychain
  scripts/install-signed-release.sh [--skip-tests] [--allow-first-install]

Options:
  --setup-keychain       Save both signing passwords through hidden macOS Keychain prompts.
  --skip-tests           Skip unit tests; the signed release is still built and verified.
  --allow-first-install  Permit installation when VozLocal is not already installed.
  --help                 Show this help.

Environment overrides:
  KEYSTORE_PATH          Keystore path (default: <repository>/my-upload-key.jks)
  KEY_ALIAS              Key alias (default: upload)
  ANDROID_SDK_ROOT       Android SDK root (default: ~/Library/Android/sdk on macOS)
  JAVA_HOME              JDK location (Android Studio's bundled JDK is detected on macOS)
  ANDROID_SERIAL         Target one device when more than one is connected
EOF
}

cleanup() {
  STORE_SECRET=""
  KEY_SECRET=""
  unset STORE_PASSWORD KEY_PASSWORD
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf -- "$TEMP_DIR"
  fi
}
trap cleanup EXIT INT TERM

setup_keychain() {
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "Keychain setup is supported only on macOS." >&2
    exit 1
  fi
  echo "Enter the keystore password when prompted. Input is hidden."
  /usr/bin/security add-generic-password -U \
    -a "$KEYCHAIN_ACCOUNT" -s "$STORE_SERVICE" \
    -l "VozLocal release keystore password" -w
  echo "Enter the key password when prompted. Input is hidden."
  /usr/bin/security add-generic-password -U \
    -a "$KEYCHAIN_ACCOUNT" -s "$KEY_SERVICE" \
    -l "VozLocal release key password" -w
  echo "VozLocal release passwords are stored in macOS Keychain."
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --setup-keychain)
      setup_keychain
      exit 0
      ;;
    --allow-first-install)
      ALLOW_FIRST_INSTALL=true
      ;;
    --skip-tests)
      SKIP_TESTS=true
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This workflow currently requires macOS Keychain." >&2
  exit 1
fi

KEYSTORE_PATH="${KEYSTORE_PATH:-$REPO_ROOT/my-upload-key.jks}"
KEY_ALIAS="${KEY_ALIAS:-upload}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$ANDROID_SDK_ROOT/platform-tools/adb}"
APKSIGNER="${APKSIGNER:-$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name apksigner 2>/dev/null | sort | tail -n 1)}"

if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

[[ -f "$KEYSTORE_PATH" ]] || { echo "Keystore not found: $KEYSTORE_PATH" >&2; exit 1; }
[[ -x "$ADB" ]] || { echo "adb not found below Android SDK: $ADB" >&2; exit 1; }
[[ -n "$APKSIGNER" && -x "$APKSIGNER" ]] || { echo "apksigner not found below Android SDK build-tools." >&2; exit 1; }
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || { echo "Set JAVA_HOME to a compatible JDK." >&2; exit 1; }
export JAVA_HOME

STORE_SECRET="$(/usr/bin/security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$STORE_SERVICE" -w)" || {
  echo "Keystore password is missing. Run this first:" >&2
  echo "  scripts/install-signed-release.sh --setup-keychain" >&2
  exit 1
}
KEY_SECRET="$(/usr/bin/security find-generic-password -a "$KEYCHAIN_ACCOUNT" -s "$KEY_SERVICE" -w)" || {
  echo "Key password is missing. Run this first:" >&2
  echo "  scripts/install-signed-release.sh --setup-keychain" >&2
  exit 1
}

cd "$REPO_ROOT"
GRADLE_TASKS=(assembleRelease)
if [[ "$SKIP_TESTS" == false ]]; then
  GRADLE_TASKS=(testDebugUnitTest assembleRelease)
fi

echo "Building a production release without retaining signing credentials in Gradle."
KEYSTORE_PATH="$KEYSTORE_PATH" \
STORE_PASSWORD="$STORE_SECRET" \
KEY_ALIAS="$KEY_ALIAS" \
KEY_PASSWORD="$KEY_SECRET" \
JAVA_HOME="$JAVA_HOME" \
  ./gradlew --no-daemon --no-configuration-cache "${GRADLE_TASKS[@]}"

APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "Signed release APK was not produced: $APK" >&2; exit 1; }

VERIFY_OUTPUT="$($APKSIGNER verify --verbose --print-certs "$APK")"
printf '%s\n' "$VERIFY_OUTPUT"
CANDIDATE_CERT="$(printf '%s\n' "$VERIFY_OUTPUT" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}')"
[[ -n "$CANDIDATE_CERT" ]] || { echo "Could not read the candidate signer fingerprint." >&2; exit 1; }

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_command() {
    "$ADB" -s "$ANDROID_SERIAL" "$@"
  }
else
  DEVICE_SERIALS="$($ADB devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  DEVICE_COUNT="$(printf '%s\n' "$DEVICE_SERIALS" | awk 'NF { count++ } END { print count + 0 }')"
  if [[ "$DEVICE_COUNT" -ne 1 ]]; then
    echo "Connect exactly one authorized device, or set ANDROID_SERIAL." >&2
    exit 1
  fi
  adb_command() {
    "$ADB" "$@"
  }
fi

INSTALLED_PATH="$(adb_command shell pm path "$PACKAGE_NAME" 2>/dev/null | sed -n '1s/^package://p' | tr -d '\r')"
if [[ -n "$INSTALLED_PATH" ]]; then
  TEMP_DIR="$(mktemp -d -t vozlocal-release-check.XXXXXX)"
  INSTALLED_APK="$TEMP_DIR/installed-base.apk"
  adb_command pull "$INSTALLED_PATH" "$INSTALLED_APK" >/dev/null
  INSTALLED_CERT="$($APKSIGNER verify --print-certs "$INSTALLED_APK" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print $2; exit}')"
  [[ -n "$INSTALLED_CERT" ]] || { echo "Could not read the installed signer fingerprint." >&2; exit 1; }
  if [[ "$CANDIDATE_CERT" != "$INSTALLED_CERT" ]]; then
    echo "Certificate mismatch: refusing to replace the trusted installed VozLocal app." >&2
    exit 1
  fi
  echo "Candidate certificate matches the installed VozLocal certificate."
elif [[ "$ALLOW_FIRST_INSTALL" != true ]]; then
  echo "VozLocal is not installed, so there is no trusted certificate to compare." >&2
  echo "Review the printed certificate and rerun with --allow-first-install if expected." >&2
  exit 1
fi

echo "Installing the verified production release on the selected device."
adb_command install -r "$APK"
echo "Installed $PACKAGE_NAME successfully."
