#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).
#
# Builds libusb (LGPL-2.1) for the macOS mtkclient bundle from the upstream release tarball that
# scripts/libusb-pin.json pins, for this Mac's architecture and for macOS 11 and later.
#
# Homebrew's libusb is built for the macOS it was bottled on (the runner's, or a new user's), so a
# bundle carrying it refuses to load on anything older; the pinned build runs back to Big Sur. It is
# also exactly the source the release attaches (libusb-<version>-source.tar.bz2).
#
# Output (OUT_DIR, default build/libusb):
#   libusb-1.0.0.dylib   the library, install name @rpath/libusb-1.0.0.dylib
#   COPYING              its LGPL-2.1 text
#   VERSION              the pinned version
# scripts/build-mtkclient.sh bundles it when LIBUSB_DIR names this folder.
#
# Usage: scripts/build-libusb-macos.sh
#        OUT_DIR=... ARCH=x86_64|arm64 scripts/build-libusb-macos.sh   (ARCH: default this Mac's)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT_DIR:-$ROOT/build/libusb}"
ARCH="${ARCH:-$(uname -m)}"
MIN_MACOS=11.0
[ "$(uname -s)" = Darwin ] || { echo "build-libusb-macos.sh builds the macOS library; on Linux and Windows the bundles do not carry libusb"; exit 1; }
case "$ARCH" in x86_64|arm64) ;; *) echo "ARCH=$ARCH: expected x86_64 or arm64"; exit 1 ;; esac
VERSION="$(node -p "require(process.argv[1]).version" "$ROOT/scripts/libusb-pin.json")"
SHA256="$(node -p "require(process.argv[1]).sha256" "$ROOT/scripts/libusb-pin.json")"
WORK="${TMPDIR:-/tmp}/myo-libusb-build"
rm -rf "$WORK"; mkdir -p "$WORK"

TARBALL="$WORK/libusb-$VERSION.tar.bz2"
curl -fsSL --retry 2 -o "$TARBALL" "https://github.com/libusb/libusb/releases/download/v$VERSION/libusb-$VERSION.tar.bz2"
GOT="$(shasum -a 256 "$TARBALL" | cut -d' ' -f1)"
[ "$GOT" = "$SHA256" ] || { echo "libusb $VERSION tarball checksum $GOT does not match scripts/libusb-pin.json ($SHA256)"; exit 1; }
tar -xjf "$TARBALL" -C "$WORK"

echo "== building libusb $VERSION for $ARCH, macOS $MIN_MACOS+"
(
  cd "$WORK/libusb-$VERSION"
  # --host names the target, so an x86_64 build on an arm64 Mac is a cross build and runs no test programs.
  ./configure -q --prefix="$WORK/prefix" --disable-static --host="$ARCH-apple-darwin" \
    CC="clang -arch $ARCH" CFLAGS="-O2 -mmacosx-version-min=$MIN_MACOS" LDFLAGS="-mmacosx-version-min=$MIN_MACOS"
  make -s -j"$(sysctl -n hw.ncpu)"
  make -s install
) > "$WORK/build.log" 2>&1 || { tail -30 "$WORK/build.log"; exit 1; }

LIB="$WORK/prefix/lib/libusb-1.0.0.dylib"
install_name_tool -id @rpath/libusb-1.0.0.dylib "$LIB"
GOT_ARCH="$(lipo -archs "$LIB")"
[ "$GOT_ARCH" = "$ARCH" ] || { echo "built libusb is $GOT_ARCH, expected $ARCH"; exit 1; }
GOT_MIN="$(otool -l "$LIB" | awk '/LC_BUILD_VERSION/{b=1} b&&/minos/{print $2; exit}')"
[ "$GOT_MIN" = "$MIN_MACOS" ] || { echo "built libusb needs macOS $GOT_MIN, expected $MIN_MACOS"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
cp "$LIB" "$OUT/libusb-1.0.0.dylib"
cp "$WORK/libusb-$VERSION/COPYING" "$OUT/COPYING"
echo "$VERSION" > "$OUT/VERSION"
echo "libusb $VERSION ($ARCH, macOS $MIN_MACOS+): $OUT/libusb-1.0.0.dylib"
