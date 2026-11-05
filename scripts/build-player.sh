#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Builds, unit-tests and lints the API 19 player, producing a signed release APK.
# Usage: scripts/build-player.sh [extra gradle args]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLAYER="$ROOT/apps/player"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# A relative OUT_DIR is relative to the caller's directory, not to apps/player (the cd below).
if [ -n "${OUT_DIR:-}" ]; then case "$OUT_DIR" in /*) ;; *) OUT_DIR="$PWD/$OUT_DIR" ;; esac; fi
cd "$PLAYER"
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease "$@"
APK="$PLAYER/app/build/outputs/apk/release/app-release.apk"
echo
echo "APK: $APK"
shasum -a 256 "$APK"

# Stage the APK where the guided installer can patch it into firmware.
if [ -d "$ROOT/apps/desktop/src-tauri" ]; then DEFAULT_RES="$ROOT/apps/desktop/src-tauri/resources/player"; else DEFAULT_RES="$ROOT/build/player"; fi
RES="${OUT_DIR:-$DEFAULT_RES}"
mkdir -p "$RES"
cp "$APK" "$RES/StudioPocket.apk"
VERSION="$(python3 -c "import json,sys; d=json.load(open('$PLAYER/app/build/outputs/apk/release/output-metadata.json')); print(d['elements'][0]['versionName'])")"
SHA="$(shasum -a 256 "$RES/StudioPocket.apk" | cut -d' ' -f1)"
printf '{\n  "file": "StudioPocket.apk",\n  "versionName": "%s",\n  "sha256": "%s",\n  "sizeBytes": %s,\n  "builtAt": "%s"\n}\n' "$VERSION" "$SHA" "$(wc -c < "$RES/StudioPocket.apk" | tr -d " ")" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$RES/player.json"
echo "staged $RES/StudioPocket.apk ($VERSION, $SHA)"
