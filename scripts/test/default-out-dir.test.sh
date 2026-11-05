#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# The build and check scripts default to the desktop's resources folder when apps/desktop/src-tauri
# exists beside them, and to build/<tool> when it does not (the public repo). Explicit dirs always win.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
# A public-shaped tree: the scripts only, no apps/desktop.
mkdir -p "$T/pub/scripts" "$T/pub/build"
cp "$ROOT"/scripts/check-e2fsprogs-bundle.mjs "$ROOT"/scripts/check-mtkclient-bundle.mjs "$T/pub/scripts/"
# The checkers name the folder they looked in when it is missing; that folder must be build/<tool>.
out="$(cd "$T/pub" && node scripts/check-mtkclient-bundle.mjs 2>&1 || true)"
echo "$out" | grep -q "build/mtkclient" || { echo "FAIL: mtkclient checker default is not build/mtkclient in a public tree:"; echo "$out"; exit 1; }
out="$(cd "$T/pub" && node scripts/check-e2fsprogs-bundle.mjs 2>&1 || true)"
echo "$out" | grep -q "build/e2fsprogs/" || { echo "FAIL: e2fsprogs checker default is not build/e2fsprogs/<os> in a public tree:"; echo "$out"; exit 1; }
# In this repo (desktop present) the defaults are unchanged.
out="$(cd "$ROOT" && MTK_BUNDLE_DIR= node scripts/check-mtkclient-bundle.mjs 2>&1 || true)"
echo "$out" | grep -q "apps/desktop/src-tauri/resources/mtkclient\|mtkclient bundle ok" || { echo "FAIL: mtkclient checker default changed in the desktop tree:"; echo "$out"; exit 1; }
# The shell scripts: their default line, read without running a build.
for s in build-e2fsprogs.sh build-mtkclient.sh build-player.sh build-e2fsprogs-windows.sh; do
  grep -q 'apps/desktop/src-tauri' "$ROOT/scripts/$s" || { echo "FAIL: $s lost its desktop default"; exit 1; }
  grep -q 'build/' "$ROOT/scripts/$s" || { echo "FAIL: $s has no public default"; exit 1; }
  grep -qi 'desktop app' "$ROOT/scripts/$s" && { echo "FAIL: $s still describes the desktop app"; exit 1; }
done
echo "default out dir test ok"
