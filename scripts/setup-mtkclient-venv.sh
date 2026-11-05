#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Creates firmware/tools/mtkclient-venv with the pinned mtkclient, the FUSE stub and the Studio Pocket
# patches: the input scripts/build-mtkclient.sh bundles. Same steps on macOS, Linux and Windows
# (Git Bash); docs/development/flashing-macos.md "Setup" explains each one.
#
# Usage: scripts/setup-mtkclient-venv.sh           (recreates the venv each time; PYTHON=... picks the
#                                                    interpreter, 3.12)
#        MTKCLIENT_ARCHIVE=<local .tar.gz> ...      (install mtkclient from that archive instead of
#                                                    downloading upstream's: either upstream's archive
#                                                    of the pinned commit, or the release's
#                                                    mtkclient-<commit>-pruned.tar.gz, whose SHA-256 the
#                                                    SOURCE-INFO.txt beside it records)
#
# The venv is rebuilt from scratch so nothing stale reaches the bundle: dependencies come from the
# hash-locked scripts/mtkclient-requirements.txt, and mtkclient from the archive of one commit,
# checked against the SHA-256 below (or, for the pruned archive, against SOURCE-INFO.txt). The pruned
# archive lacks only files no bundle ships (WinFsp's DLLs and mtkclient/Loader/); build-mtkclient.sh
# removes the same files from an upstream install.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$ROOT/firmware/tools/mtkclient-venv"
# The commit tested on hardware (docs/tested-hardware.md); the patches match it exactly.
# build-mtkclient.sh reads it back from $VENV/MTKCLIENT-COMMIT for BUNDLE-INFO.json.
# scripts/mtkclient-pin.json is the single source of truth (the release gate reads it too).
MTKCLIENT_COMMIT="$(node -p "require(process.argv[1]).commit" "$ROOT/scripts/mtkclient-pin.json")"
MTKCLIENT_SHA256="$(node -p "require(process.argv[1]).sha256" "$ROOT/scripts/mtkclient-pin.json")"
MTKCLIENT_URL="https://github.com/bkerler/mtkclient/archive/$MTKCLIENT_COMMIT.tar.gz"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"

rm -rf "$VENV"
"$PYTHON" -m venv "$VENV"
if [ -d "$VENV/Scripts" ]; then BIN="$VENV/Scripts"; SITE="$VENV/Lib/site-packages"
else BIN="$VENV/bin"; SITE="$(echo "$VENV"/lib/python3.*/site-packages)"; fi

# Dependencies at the locked versions and hashes (PySide6 is not among them: the bundle has no GUI).
"$BIN/python" -m pip install -q --require-hashes -r "$ROOT/scripts/mtkclient-requirements.txt"

# mtkclient from the commit's archive: pip's hash-checking mode refuses VCS requirements.
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
ARCHIVE="$(mktemp "${TMPDIR:-/tmp}/mtkclient-XXXXXX")"
trap 'rm -f "$ARCHIVE"' EXIT
if [ -n "${MTKCLIENT_ARCHIVE:-}" ]; then
  # A local archive: upstream's (the pinned SHA-256), or the release's pruned one, whose SHA-256 the
  # SOURCE-INFO.txt next to it records on its "This copy: mtkclient-<commit>-pruned.tar.gz, SHA-256 <hex>:" line.
  [ -f "$MTKCLIENT_ARCHIVE" ] || { echo "MTKCLIENT_ARCHIVE=$MTKCLIENT_ARCHIVE is not a file"; exit 1; }
  cp "$MTKCLIENT_ARCHIVE" "$ARCHIVE"
  GOT="$(sha256_of "$ARCHIVE")"
  INFO="$(dirname "$MTKCLIENT_ARCHIVE")/SOURCE-INFO.txt"
  PRUNED_SHA256=""
  [ ! -f "$INFO" ] || PRUNED_SHA256="$(sed -n "s/^This copy: mtkclient-$MTKCLIENT_COMMIT-pruned\.tar\.gz, SHA-256 \([0-9a-f]\{64\}\):\$/\1/p" "$INFO" | head -1)"
  if [ "$GOT" = "$MTKCLIENT_SHA256" ]; then echo "mtkclient from $MTKCLIENT_ARCHIVE (upstream's archive of $MTKCLIENT_COMMIT)"
  elif [ -n "$PRUNED_SHA256" ] && [ "$GOT" = "$PRUNED_SHA256" ]; then echo "mtkclient from $MTKCLIENT_ARCHIVE (the release's pruned archive of $MTKCLIENT_COMMIT, as $INFO records)"
  else echo "MTKCLIENT_ARCHIVE checksum $GOT is neither upstream's ($MTKCLIENT_SHA256) nor the pruned archive's recorded in $INFO (${PRUNED_SHA256:-none found for commit $MTKCLIENT_COMMIT})"; exit 1; fi
else
  curl -fsSL --retry 2 -o "$ARCHIVE" "$MTKCLIENT_URL"
  GOT="$(sha256_of "$ARCHIVE")"
  [ "$GOT" = "$MTKCLIENT_SHA256" ] || { echo "mtkclient archive checksum mismatch: expected $MTKCLIENT_SHA256, got $GOT"; exit 1; }
fi
# pip needs a recognisable archive name; --no-deps because the dependencies are already locked.
mv "$ARCHIVE" "$ARCHIVE.tar.gz"; ARCHIVE="$ARCHIVE.tar.gz"
"$BIN/python" -m pip install -q --no-deps "$ARCHIVE"
echo "$MTKCLIENT_COMMIT" > "$VENV/MTKCLIENT-COMMIT"

# mtkclient imports a FUSE helper at start-up; without macFUSE/WinFsp it fails, so replace it.
cat > "$SITE/mfusepy.py" <<'EOF'
# Stub for mtkclient's optional FUSE mount feature (macFUSE is not installed). Only the
# names mtkclient imports at module load; any attempt to actually mount raises.
class Operations:  # noqa: D101
    pass


class LoggingMixIn:  # noqa: D101
    pass


class FUSE:  # noqa: D101
    def __init__(self, *args, **kwargs):
        raise OSError("FUSE mounting is not available in this environment")
EOF
"$BIN/python" "$ROOT/scripts/patch-mtkclient.py" "$SITE/mtkclient"
"$BIN/python" -c "import mtkclient.mtk" && echo "mtkclient venv ready: $VENV"
