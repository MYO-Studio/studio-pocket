#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Builds debugfs, e2fsck and dumpe2fs (e2fsprogs 1.47.4, GPL-2.0, unmodified upstream) so the
# installer engine can read, patch and check the player's ext4 system image without Homebrew.
#
# Output: build/e2fsprogs/<macos|linux>/ (apps/desktop/src-tauri/resources/e2fsprogs/<macos|linux>/ when that app folder exists beside this script)
#   debugfs e2fsck dumpe2fs   the executables (universal arm64 + x86_64 on macOS)
#   licences/                 upstream's NOTICE (GPL-2.0 + LGPL-2.0), libuuid's COPYING, libss/libet notices
#   BUNDLE-INFO.json          version, upstream URL, tarball SHA-256, configure flags, build date
#
# Usage: scripts/build-e2fsprogs.sh             (needs a C toolchain: Xcode command line tools on macOS,
#                                                build-essential on Linux; downloads ~10 MB from kernel.org)
#        OUT_DIR=... scripts/build-e2fsprogs.sh
#        MACOS_ARCHS="arm64" scripts/build-e2fsprogs.sh   (one arch only; default "arm64 x86_64")
#        APPLE_SIGNING_IDENTITY="Developer ID Application: ..." signs each binary (hardened runtime)
#        KEEP_WORK=1 scripts/build-e2fsprogs.sh   (keep the build trees; by default only the tarball stays)
# A signed release also needs scripts/check-e2fsprogs-bundle.mjs to pass (tauri build runs it).
#
# The tarball's SHA-256 is checked before it is unpacked and no patches are applied, so the
# upstream URL plus the hash is the corresponding source. Everything that could pull in a library
# from outside the OS (NLS, FUSE, libarchive, libmagic, the system libuuid/libblkid) is configured
# out, and the binaries are checked with otool/ldd: only /usr/lib and /System/Library (macOS) or
# libc's own libraries (Linux) may be linked. Windows: scripts/build-e2fsprogs-windows.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="1.47.4"
URL="https://mirrors.edge.kernel.org/pub/linux/kernel/people/tytso/e2fsprogs/v$VERSION/e2fsprogs-$VERSION.tar.gz"
SHA256="da274408bebbfd13a5a2fc3cfc66e3ffff17c48534673aa67f88d49b99123b96"
TOOLS="debugfs e2fsck dumpe2fs"
case "$(uname -s)" in
  Darwin) OS=macos ;;
  Linux) OS=linux ;;
  *) echo "scripts/build-e2fsprogs.sh builds macOS and Linux bundles only; on Windows use scripts/build-e2fsprogs-windows.sh"; exit 1 ;;
esac
if [ -d "$ROOT/apps/desktop/src-tauri" ]; then DEFAULT_OUT="$ROOT/apps/desktop/src-tauri/resources/e2fsprogs/$OS"; else DEFAULT_OUT="$ROOT/build/e2fsprogs/$OS"; fi
OUT="${OUT_DIR:-$DEFAULT_OUT}"
# OUT is replaced wholesale: never let a mistyped OUT_DIR point at /, $HOME or the repo.
case "$(cd "$OUT" 2>/dev/null && pwd -P)" in
  /|"$(cd "$HOME" && pwd -P)"|"$(cd "$ROOT" && pwd -P)") echo "refusing to replace OUT_DIR=$OUT"; exit 1 ;;
esac
# … nor at any other folder with something in it that an earlier run of this script didn't put there.
if [ -e "$OUT" ] && [ ! -d "$OUT" ]; then echo "refusing to replace OUT_DIR=$OUT: it is a file, not a folder"; exit 1; fi
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT")" ] && ! grep -q '"tool": "e2fsprogs"' "$OUT/BUNDLE-INFO.json" 2>/dev/null; then
  echo "refusing to replace OUT_DIR=$OUT: it is not empty and holds no e2fsprogs BUNDLE-INFO.json from an earlier build"; exit 1
fi
WORK="${TMPDIR:-/tmp}/myo-e2fsprogs-build"
TARBALL="$WORK/e2fsprogs-$VERSION.tar.gz"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
CONFIGURE_FLAGS="--disable-nls --disable-fuse2fs --disable-defrag --disable-imager --disable-resizer --disable-uuidd --disable-e2initrd-helper --disable-elf-shlibs --disable-bsd-shlibs --disable-rpath --enable-libuuid --enable-libblkid --without-libarchive"

# Only the OS's toolchain and libraries: Homebrew's pkg-config files, headers and dylibs must not
# leak into configure (otool would catch it, but failing early is clearer).
export PATH="/usr/bin:/bin:/usr/sbin:/sbin"
export PKG_CONFIG_LIBDIR=/nonexistent PKG_CONFIG_PATH=
unset CPPFLAGS CFLAGS LDFLAGS LIBS
# libmagic is linked when configure finds it (e.g. Intel Homebrew in /usr/local); debugfs only
# uses it to guess file types in `dump`, which the installer never needs.
export ac_cv_lib_magic_magic_file=no
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-10.13}"  # Tauri's default minimum; arm64 is 11.0 at least

echo "== fetching e2fsprogs $VERSION"
mkdir -p "$WORK"
[ -f "$TARBALL" ] || { curl -fsSL -o "$TARBALL.part" "$URL" && mv "$TARBALL.part" "$TARBALL"; }
GOT=$( (shasum -a 256 "$TARBALL" 2>/dev/null || sha256sum "$TARBALL") | awk '{print $1}')
[ "$GOT" = "$SHA256" ] || { echo "SHA-256 mismatch for $TARBALL: expected $SHA256, got $GOT"; rm -f "$TARBALL"; exit 1; }
echo "sha256 ok: $GOT"
rm -rf "$WORK/src"; mkdir -p "$WORK/src"; tar -xzf "$TARBALL" -C "$WORK/src"
SRC="$WORK/src/e2fsprogs-$VERSION"

# build <dir> <configure args...>: out-of-tree build of the libraries, the three tools and mke2fs
# (mke2fs is only for the smoke test below and is never installed).
build() {
  local dir="$1"; shift
  rm -rf "$dir"; mkdir -p "$dir"
  (cd "$dir" && "$SRC/configure" $CONFIGURE_FLAGS "$@" > configure.log 2>&1) || { tail -30 "$dir/configure.log"; exit 1; }
  (cd "$dir" && make -j"$JOBS" libs > make.log 2>&1 \
    && make -j"$JOBS" -C debugfs debugfs >> make.log 2>&1 \
    && make -j"$JOBS" -C e2fsck e2fsck >> make.log 2>&1 \
    && make -j"$JOBS" -C misc dumpe2fs mke2fs >> make.log 2>&1) || { tail -30 "$dir/make.log"; exit 1; }
}

STAGE="$WORK/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
if [ "$OS" = macos ]; then
  ARCHS="${MACOS_ARCHS:-arm64 x86_64}"
  NATIVE="$(uname -m)"
  triple() { [ "$1" = arm64 ] && echo aarch64-apple-darwin || echo "$1-apple-darwin"; }
  for arch in $ARCHS; do
    echo "== building $arch"
    host=(); [ "$arch" = "$NATIVE" ] || host=(--host="$(triple "$arch")" --build="$(triple "$NATIVE")")
    CC="cc -arch $arch" BUILD_CC=cc build "$WORK/build-$arch" ${host[@]+"${host[@]}"}
  done
  MKE2FS="$WORK/build-$NATIVE/misc/mke2fs"; [ -x "$MKE2FS" ] || MKE2FS="$WORK/build-${ARCHS%% *}/misc/mke2fs"
  for t in $TOOLS; do
    slices=(); for arch in $ARCHS; do slices+=("$WORK/build-$arch/$( [ "$t" = dumpe2fs ] && echo misc || echo "$t" )/$t"); done
    lipo -create "${slices[@]}" -output "$STAGE/$t"
    strip "$STAGE/$t"
    # strip invalidates the linker's ad-hoc signature, and arm64 refuses unsigned code.
    if [ -n "${APPLE_SIGNING_IDENTITY:-}" ]; then
      sign=(--options runtime --timestamp --sign "$APPLE_SIGNING_IDENTITY")
    else
      sign=(--sign -)
    fi
    # codesign always says "replacing existing signature"; only show its output when it fails.
    out=$(codesign --force "${sign[@]}" "$STAGE/$t" 2>&1) || { echo "$out"; echo "codesign failed for $t"; exit 1; }
  done
else
  echo "== building $(uname -m)"
  build "$WORK/build"
  MKE2FS="$WORK/build/misc/mke2fs"
  for t in $TOOLS; do
    cp "$WORK/build/$( [ "$t" = dumpe2fs ] && echo misc || echo "$t" )/$t" "$STAGE/$t"
    strip "$STAGE/$t"
  done
fi

echo "== checking linked libraries"
for t in $TOOLS; do
  if [ "$OS" = macos ]; then
    lipo -info "$STAGE/$t"
    libs=$(otool -L -arch all "$STAGE/$t" | grep -E '^[[:space:]]' | awk '{print $1}' | sort -u)
    bad=$(echo "$libs" | grep -Ev '^(/usr/lib/|/System/Library/)' || true)
  else
    libs=$(ldd "$STAGE/$t" | awk '{print ($3 ~ /^\//) ? $3 : $1}' | sort -u)
    bad=$(echo "$libs" | grep -Ev '^(linux-vdso\.so|linux-gate\.so|(/usr)?/lib.*/ld-linux|(/usr)?/lib.*/lib(c|m|pthread|dl|rt|resolv)\.so)' || true)
  fi
  echo "$t: $(echo $libs)"
  [ -z "$bad" ] || { echo "$t links libraries outside the OS: $bad"; exit 1; }
done

echo "== smoke test"
# smoke [prefix...]: -V on each tool and a write/read/check round trip on a throwaway image.
smoke() {
  local t img="$WORK/smoke.img" in="$WORK/smoke.in" out="$WORK/smoke.out"
  for t in $TOOLS; do "$@" "$STAGE/$t" -V 2>&1 | head -1 | grep -q "$VERSION" || { echo "$t -V does not report $VERSION"; exit 1; }; done
  rm -f "$img" "$out"; head -c 100000 /dev/urandom > "$in"
  MKE2FS_CONFIG="$SRC/misc/mke2fs.conf.in" "$MKE2FS" -q -F -t ext4 "$img" 8M
  "$@" "$STAGE/debugfs" -w -R "write $in smoke.bin" "$img" > /dev/null 2>&1
  "$@" "$STAGE/debugfs" -R "cat /smoke.bin" "$img" > "$out" 2>/dev/null
  cmp -s "$in" "$out" || { echo "debugfs did not read back what it wrote"; exit 1; }
  "$@" "$STAGE/e2fsck" -fn "$img" > "$WORK/smoke.fsck" 2>&1 || { cat "$WORK/smoke.fsck"; echo "e2fsck -fn reported problems"; exit 1; }
  "$@" "$STAGE/dumpe2fs" -h "$img" 2>/dev/null | grep -q "Filesystem magic number:  0xEF53" || { echo "dumpe2fs -h shows no superblock"; exit 1; }
  echo "ok: -V $VERSION, debugfs write + cat, e2fsck -fn clean, dumpe2fs -h${1:+ (via $*)}"
}
smoke
if [ "$OS" = macos ] && [ "$(uname -m)" = arm64 ] && [[ " ${ARCHS} " == *" x86_64 "* ]]; then
  if arch -x86_64 /usr/bin/true 2>/dev/null; then smoke arch -x86_64; else echo "Rosetta not installed: x86_64 slice not smoke-tested"; fi
fi

echo "== installing to $OUT"
rm -rf "$OUT"; mkdir -p "$OUT/licences"
for t in $TOOLS; do cp -p "$STAGE/$t" "$OUT/$t"; done
cp "$SRC/NOTICE" "$OUT/licences/NOTICE"
cp "$SRC/lib/uuid/COPYING" "$OUT/licences/COPYING.libuuid"
for h in lib/et/com_err.h lib/ss/ss.h; do echo "== $h"; sed -n '1,/\*\//p' "$SRC/$h"; done > "$OUT/licences/NOTICE.libet-libss"
[ -n "${OUT_DIR:-}" ] || [ ! -d "$ROOT/apps/desktop/src-tauri" ] || touch "$(dirname "$OUT")/.keep"  # .keep is committed so the tauri.conf.json resource glob always matches
cat > "$OUT/BUNDLE-INFO.json" <<EOF
{
  "tool": "e2fsprogs",
  "version": "$VERSION",
  "upstream": "$URL",
  "sha256": "$SHA256",
  "patches": 0,
  "programs": ["debugfs", "e2fsck", "dumpe2fs"],
  "licence": "GPL-2.0 (programs; lib/ext2fs, lib/e2p and lib/blkid LGPL-2.0, lib/uuid BSD-3-Clause, lib/et and lib/ss MIT-style). Invoked as separate programs; source: the upstream tarball above, unmodified",
  "configure": "$CONFIGURE_FLAGS",
  "archs": "$( [ "$OS" = macos ] && echo "$ARCHS" || uname -m )",
  "macosDeploymentTarget": "$( [ "$OS" = macos ] && echo "$MACOSX_DEPLOYMENT_TARGET" || echo n/a )",
  "signed": $( [ -n "${APPLE_SIGNING_IDENTITY:-}" ] && echo true || echo false ),
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "host": "$(uname -sm)",
  "libc": "$( [ "$OS" = linux ] && ldd --version 2>&1 | head -1 || echo "macOS libSystem" )"
}
EOF
du -sh "$OUT" | awk '{print "bundle size: " $1}'
# The build trees are ~100 MB; keep only the verified tarball for the next run.
[ -n "${KEEP_WORK:-}" ] || find "$WORK" -mindepth 1 -maxdepth 1 ! -name "$(basename "$TARBALL")" -exec rm -rf {} +
echo "done: $OUT"
