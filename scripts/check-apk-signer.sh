#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Passes only when the APK's first signing certificate has the SHA-256 digest in the digest file.
# Reads both apksigner output styles ("Signer #1 ..." and "V2 Signer: ..."). A debug-signed or any other APK fails. Usage: scripts/check-apk-signer.sh <apk> [digest-file]
set -euo pipefail
APK="${1:?apk}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DIGEST_FILE="${2:-$HERE/../release/signing-certificate.sha256}"
APKSIGNER="${APKSIGNER:-$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/*/apksigner | sort -V | tail -1)}"
expected="$(tr -d '[:space:]' < "$DIGEST_FILE" | tr 'A-F' 'a-f')"
actual="$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/^(Signer #1|V[0-9.]+ Signer).* certificate SHA-256 digest/ && !seen {print $NF; seen=1}' | tr -d '[:space:]' | tr 'A-F' 'a-f')"
if [ "$actual" != "$expected" ]; then
  echo "signer mismatch for $APK: expected $expected, got ${actual:-none}" >&2
  exit 1
fi
echo "signer ok: $actual"
