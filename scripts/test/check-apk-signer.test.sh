#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Signs a copy of a debug APK with a throwaway key and checks the signer check on both sides.
# Needs ANDROID_HOME (build-tools) and a debug APK: DEBUG_APK=apps/player/app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${DEBUG_APK:?set DEBUG_APK}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)"
cp "$DEBUG_APK" "$T/app.apk"
keytool -genkeypair -keystore "$T/test.jks" -storepass testtest -keypass testtest -alias test -keyalg RSA -keysize 2048 -validity 1 -dname "CN=test" >/dev/null
"$APKSIGNER" sign --ks "$T/test.jks" --ks-pass pass:testtest --key-pass pass:testtest --ks-key-alias test "$T/app.apk"
keytool -exportcert -keystore "$T/test.jks" -storepass testtest -alias test | shasum -a 256 | cut -d' ' -f1 > "$T/expected.sha256"

# Signed by the expected key: passes.
"$ROOT/scripts/check-apk-signer.sh" "$T/app.apk" "$T/expected.sha256"

# Signed by a debug key (what a release build without keystore.properties produces): refused.
cp "$DEBUG_APK" "$T/debug.apk"
"$APKSIGNER" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey "$T/debug.apk"
if "$ROOT/scripts/check-apk-signer.sh" "$T/debug.apk" "$T/expected.sha256" 2>"$T/err.txt"; then
  echo "FAIL: a debug-signed APK passed the signer check"; exit 1
fi
# The refusal must be the signer mismatch, not a crash of apksigner or the script.
grep -q "signer mismatch" "$T/err.txt" || { echo "FAIL: refused for the wrong reason:"; cat "$T/err.txt"; exit 1; }
echo "signer check test ok"
