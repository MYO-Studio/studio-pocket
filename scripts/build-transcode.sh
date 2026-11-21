#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Builds studio-pocket-transcode (tools/transcode): the program that converts one Ogg Opus file to a
# 128 kbps MP3, which the Studio Pocket player can decode (Android 4.4 has no Opus decoder). The
# desktop app runs it as a separate program, one process per file. It is GPL-3.0 like the rest of
# this repository, with LAME (LGPL) and libopus (BSD) linked in statically from the source their
# Rust crates carry, so the file needs nothing but the operating system.
#
# Output: build/transcode/<macos|linux|windows>/ (apps/desktop/src-tauri/resources/transcode/<os>/ when that app folder exists beside this script)
#   studio-pocket-transcode   the executable (universal arm64 + x86_64 on macOS, .exe on Windows)
#   licences/                 GPL-3.0 (the program), LAME's LGPL texts, libopus's BSD notice, and the notices of
#                             the Rust crates and standard library linked in (scripts/transcode-notices.mjs)
#   BUNDLE-INFO.json          version, every linked component with its version, licence and source, build date
#
# Usage: scripts/build-transcode.sh             (needs Rust 1.85 or later with cargo, CMake, a C toolchain and
#                                                Node; cargo downloads the crates in tools/transcode/Cargo.lock)
#        OUT_DIR=... scripts/build-transcode.sh
#        MACOS_ARCHS="arm64" scripts/build-transcode.sh   (one arch only; default "arm64 x86_64", which needs
#                                                          both Rust targets: rustup target add x86_64-apple-darwin)
#        APPLE_SIGNING_IDENTITY="Developer ID Application: ..." signs the binary (hardened runtime)
# On Windows run it from Git Bash, with the MSVC toolchain installed (the Rust target is x86_64-pc-windows-msvc).
#
# The crate's tests are run first, on this machine's own architecture; a failing test stops the
# build. Cargo.lock pins every crate by checksum and cargo is run with --locked, so every build
# compiles the same source: LAME from the mp3lame-sys crate file and libopus from opusic-sys's.
# The release attaches the mp3lame-sys crate file as LAME's corresponding source. The finished
# binary is checked with otool, ldd or its import table: only the operating system's own libraries
# may be linked (on macOS /usr/lib and /System/Library; on Linux glibc's and libgcc_s; on Windows
# no C runtime DLL). macOS builds are for macOS 11 and later.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRATE="$ROOT/tools/transcode"
NAME="studio-pocket-transcode"
MIN_MACOS=11.0
case "$(uname -s)" in
  Darwin) OS=macos; EXE="" ;;
  Linux) OS=linux; EXE="" ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows; EXE=".exe" ;;
  *) echo "scripts/build-transcode.sh builds macOS, Linux and Windows bundles; this is $(uname -s)"; exit 1 ;;
esac
if [ -d "$ROOT/apps/desktop/src-tauri" ]; then DEFAULT_OUT="$ROOT/apps/desktop/src-tauri/resources/transcode/$OS"; else DEFAULT_OUT="$ROOT/build/transcode/$OS"; fi
OUT="${OUT_DIR:-$DEFAULT_OUT}"
# A relative OUT_DIR is relative to where the script was called from, not to the crate's folder
# that the build runs in. And cargo must not see the name: it has its own meaning there.
case "$OUT" in /*|[A-Za-z]:[/\\]*) ;; *) OUT="$PWD/$OUT" ;; esac
unset OUT_DIR
# OUT is replaced wholesale: never let a mistyped OUT_DIR point at /, $HOME or the repo.
case "$(cd "$OUT" 2>/dev/null && pwd -P)" in
  /|"$(cd "$HOME" && pwd -P)"|"$(cd "$ROOT" && pwd -P)") echo "refusing to replace OUT_DIR=$OUT"; exit 1 ;;
esac
# … nor at any other folder with something in it that an earlier run of this script didn't put there.
if [ -e "$OUT" ] && [ ! -d "$OUT" ]; then echo "refusing to replace OUT_DIR=$OUT: it is a file, not a folder"; exit 1; fi
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT")" ] && ! grep -q '"tool": "transcode"' "$OUT/BUNDLE-INFO.json" 2>/dev/null; then
  echo "refusing to replace OUT_DIR=$OUT: it is not empty and holds no transcode BUNDLE-INFO.json from an earlier build"; exit 1
fi

# Nothing from outside the crates may reach the C builds: no pkg-config lookups (neither build
# script does any, so this only makes a leak fail early), and no compiler or Rust flags from the
# caller's environment, which would also replace the ones in tools/transcode/.cargo/config.toml.
export PKG_CONFIG_LIBDIR=/nonexistent PKG_CONFIG_PATH=
unset CPPFLAGS CFLAGS CXXFLAGS LDFLAGS LIBS RUSTFLAGS CARGO_ENCODED_RUSTFLAGS CARGO_BUILD_RUSTFLAGS CARGO_BUILD_TARGET
export MACOSX_DEPLOYMENT_TARGET="$MIN_MACOS"

for tool in cargo rustc cmake node; do
  command -v "$tool" > /dev/null || { echo "$tool not found: scripts/build-transcode.sh needs Rust (cargo, rustc), CMake and Node"; exit 1; }
done

# The Rust targets to build: both Mac architectures, or this machine's own.
host_triple() { rustc -vV | sed -n 's/^host: //p'; }
mac_triple() { [ "$1" = arm64 ] && echo aarch64-apple-darwin || echo "$1-apple-darwin"; }
if [ "$OS" = macos ]; then
  ARCHS="${MACOS_ARCHS:-arm64 x86_64}"
  TRIPLES=""; for arch in $ARCHS; do TRIPLES="$TRIPLES $(mac_triple "$arch")"; done
  NATIVE="$(mac_triple "$(uname -m)")"
  # Tests run on this Mac's own architecture when it is one of those built, else on the first.
  case " $TRIPLES " in *" $NATIVE "*) ;; *) NATIVE="${TRIPLES# }"; NATIVE="${NATIVE%% *}" ;; esac
else
  ARCHS="$(uname -m)"
  NATIVE="$(host_triple)"
  TRIPLES="$NATIVE"
  if [ "$OS" = windows ]; then
    case "$NATIVE" in *-pc-windows-msvc) ;; *) echo "the Rust toolchain targets $NATIVE; the Windows bundle is built with the MSVC one (rustup default stable-msvc)"; exit 1 ;; esac
  fi
fi
# A Rust installed without rustup (Homebrew's) has only its own architecture's standard library.
# When a target is missing there and rustup has a toolchain, use that one instead.
has_targets() { local t; for t in $TRIPLES; do [ -d "$(rustc --print sysroot)/lib/rustlib/$t/lib" ] || return 1; done; }
if ! has_targets && command -v rustup > /dev/null && RUSTUP_RUSTC="$(rustup which rustc 2>/dev/null)"; then
  PATH="$(dirname "$RUSTUP_RUSTC"):$PATH"
fi
has_targets || { echo "the Rust toolchain ($(rustc --version)) lacks a standard library for one of:$TRIPLES (rustup target add <target>)"; exit 1; }
echo "== $(rustc --version), $(cargo --version), $(cmake --version | head -1)"

cd "$CRATE"   # cargo reads .cargo/config.toml from the folder it is run in
VERSION="$(cargo metadata --format-version 1 --locked --no-deps | node -e 'const m = JSON.parse(require("fs").readFileSync(0, "utf8")); console.log(m.packages[0].version)')"
TARGET_DIR="$(cargo metadata --format-version 1 --locked --no-deps | node -e 'const m = JSON.parse(require("fs").readFileSync(0, "utf8")); console.log(m.target_directory.replace(/\\/g, "/"))')"
[ -n "$VERSION" ] && [ -n "$TARGET_DIR" ] || { echo "cargo metadata gave no version or target folder"; exit 1; }
WORK="$TARGET_DIR/bundle"
STAGE="$WORK/stage"
rm -rf "$WORK"; mkdir -p "$STAGE"
BIN="$NAME$EXE"

echo "== testing $NAME $VERSION ($NATIVE)"
cargo test --release --locked --target "$NATIVE"
for triple in $TRIPLES; do
  echo "== building $triple"
  cargo build --release --locked --target "$triple" --bin "$NAME"
done
# The smoke test's input: an Ogg Opus file from the tests' own writer.
cargo build --release --locked --target "$NATIVE" --example make-ogg-opus
MAKE_OPUS="$TARGET_DIR/$NATIVE/release/examples/make-ogg-opus$EXE"

if [ "$OS" = macos ]; then
  slices=(); for triple in $TRIPLES; do slices+=("$TARGET_DIR/$triple/release/$BIN"); done
  lipo -create "${slices[@]}" -output "$STAGE/$BIN"
  strip "$STAGE/$BIN"
  # strip invalidates the linker's ad-hoc signature, and arm64 refuses unsigned code.
  if [ -n "${APPLE_SIGNING_IDENTITY:-}" ]; then
    sign=(--options runtime --timestamp --sign "$APPLE_SIGNING_IDENTITY")
  else
    sign=(--sign -)
  fi
  # codesign always says "replacing existing signature"; only show its output when it fails.
  out=$(codesign --force "${sign[@]}" "$STAGE/$BIN" 2>&1) || { echo "$out"; echo "codesign failed for $BIN"; exit 1; }
else
  cp "$TARGET_DIR/$NATIVE/release/$BIN" "$STAGE/$BIN"
  [ "$OS" != linux ] || strip "$STAGE/$BIN"   # an MSVC build keeps its symbols in a separate file already
fi

echo "== checking linked libraries"
if [ "$OS" = macos ]; then
  lipo -info "$STAGE/$BIN"
  [ "$(lipo -archs "$STAGE/$BIN" | tr ' ' '\n' | sort | xargs)" = "$(echo $ARCHS | tr ' ' '\n' | sort | xargs)" ] || { echo "$BIN holds $(lipo -archs "$STAGE/$BIN"), expected $ARCHS"; exit 1; }
  for arch in $ARCHS; do
    min="$(otool -arch "$arch" -l "$STAGE/$BIN" | awk '/LC_BUILD_VERSION/{b=1} b&&/minos/{print $2; exit}')"
    [ "$min" = "$MIN_MACOS" ] || { echo "the $arch slice needs macOS $min, expected $MIN_MACOS"; exit 1; }
  done
  libs=$(otool -L -arch all "$STAGE/$BIN" | grep -E '^[[:space:]]' | awk '{print $1}' | sort -u)
  bad=$(echo "$libs" | grep -Ev '^(/usr/lib/|/System/Library/)' || true)
elif [ "$OS" = linux ]; then
  libs=$(ldd "$STAGE/$BIN" | awk '{print ($3 ~ /^\//) ? $3 : $1}' | sort -u)
  # libgcc_s is the compiler's runtime that every Rust program on glibc links; it is part of the base system.
  bad=$(echo "$libs" | grep -Ev '^(linux-vdso\.so|linux-gate\.so|(/usr)?/lib.*/ld-linux|(/usr)?/lib.*/lib(c|m|pthread|dl|rt|resolv|gcc_s)\.so)' || true)
else
  # The DLLs named in the import table. The C runtime is linked statically (.cargo/config.toml), so
  # none of the Visual C++ redistributable's may be there, nor a codec's.
  libs=$(node -e '
    const b = require("fs").readFileSync(process.argv[1]);
    const pe = b.readUInt32LE(0x3c), opt = pe + 24, plus = b.readUInt16LE(opt) === 0x20b;
    const sections = [];
    for (let i = 0, at = opt + b.readUInt16LE(pe + 20); i < b.readUInt16LE(pe + 6); i++, at += 40) {
      sections.push({ size: Math.max(b.readUInt32LE(at + 8), b.readUInt32LE(at + 16)), rva: b.readUInt32LE(at + 12), raw: b.readUInt32LE(at + 20) });
    }
    const offset = (rva) => { const s = sections.find((s) => rva >= s.rva && rva < s.rva + s.size); return rva - s.rva + s.raw; };
    const imports = b.readUInt32LE(opt + (plus ? 112 : 96) + 8);
    for (let at = imports ? offset(imports) : 0; imports && b.readUInt32LE(at + 12); at += 20) {
      const name = offset(b.readUInt32LE(at + 12));
      console.log(b.toString("latin1", name, b.indexOf(0, name)).toLowerCase());
    }' "$STAGE/$BIN" | sort -u)
  [ -n "$libs" ] || { echo "could not read the import table of $BIN"; exit 1; }
  bad=$(echo "$libs" | grep -Ei '^(vcruntime|msvcp|msvcr|ucrtbase|concrt|vcomp|libgcc|libwinpthread|libstdc|opus|libopus|libmp3lame|lame)' || true)
fi
echo "$BIN: $(echo $libs)"
[ -z "$bad" ] || { echo "$BIN links libraries that are not the operating system's: $bad"; exit 1; }

echo "== smoke test"
# smoke [prefix...]: --version, a stereo and a mono conversion with the output protocol checked, and
# the exit code for an input that is not Ogg Opus.
smoke() {
  local channels src out lines status
  [ "$("$@" "$STAGE/$BIN" --version)" = "$NAME $VERSION" ] || { echo "--version does not print \"$NAME $VERSION\""; exit 1; }
  for channels in 2 1; do
    src="$WORK/smoke-$channels.opus"; out="$WORK/smoke-$channels.mp3.part"
    [ -f "$src" ] || "$MAKE_OPUS" "$src" 3 "$channels"
    rm -f "$out"
    lines="$("$@" "$STAGE/$BIN" "$src" "$out")" || { echo "converting $src failed"; exit 1; }
    [ "$(echo "$lines" | head -1 | cut -d' ' -f1,3)" = "progress $(wc -c < "$src" | tr -d ' ')" ] || { echo "the first line is not a progress line with the input size: $(echo "$lines" | head -1)"; exit 1; }
    [ "$(echo "$lines" | tail -1)" = "done $(wc -c < "$out" | tr -d ' ')" ] || { echo "the last line is not done with the output size: $(echo "$lines" | tail -1)"; exit 1; }
    # 3 s of audio at 128 kbps is 48 000 bytes, plus the Info frame and the encoder's padding; the
    # file starts with an MPEG-1 Layer III frame header (ff fb).
    [ "$(wc -c < "$out")" -gt 48000 ] && [ "$(wc -c < "$out")" -lt 50000 ] || { echo "$out is $(wc -c < "$out") bytes, expected about 48 800"; exit 1; }
    [ "$(head -c 2 "$out" | od -An -tx1 | tr -d ' \n')" = fffb ] || { echo "$out does not start with an MP3 frame"; exit 1; }
  done
  echo "this is not audio" > "$WORK/smoke.txt"; rm -f "$WORK/smoke.txt.part"
  status=0; "$@" "$STAGE/$BIN" "$WORK/smoke.txt" "$WORK/smoke.txt.part" > /dev/null 2>&1 || status=$?
  [ "$status" = 3 ] && [ ! -e "$WORK/smoke.txt.part" ] || { echo "an input that is not Ogg Opus exited $status (expected 3) or left an output file"; exit 1; }
  echo "ok: --version $VERSION, stereo and mono conversions, exit 3 for an input that is not Ogg Opus${1:+ (via $*)}"
}
smoke
if [ "$OS" = macos ] && [ "$(uname -m)" = arm64 ] && [[ " ${ARCHS} " == *" x86_64 "* ]]; then
  if arch -x86_64 /usr/bin/true 2>/dev/null; then smoke arch -x86_64; else echo "Rosetta not installed: x86_64 slice not smoke-tested"; fi
fi

echo "== collecting licence texts"
COMPONENTS="$(node "$ROOT/scripts/transcode-notices.mjs" "$STAGE" "$NATIVE")"

echo "== installing to $OUT"
if [ "$OS" = linux ]; then LIBC="$(ldd --version 2>&1 | head -1)"; elif [ "$OS" = macos ]; then LIBC="macOS libSystem"; else LIBC="MSVC C runtime, linked statically"; fi
rm -rf "$OUT"; mkdir -p "$OUT"
cp -p "$STAGE/$BIN" "$OUT/$BIN"
cp -R "$STAGE/licences" "$OUT/licences"
cat > "$OUT/BUNDLE-INFO.json" <<EOF
{
  "tool": "transcode",
  "version": "$VERSION",
  "programs": ["$NAME"],
  "licence": "GPL-3.0-only (the program: tools/transcode in the Studio Pocket repository, at the commit this was built from). Linked in statically: LAME (LGPL-2.0-or-later), libopus (BSD-3-Clause) and the Rust crates and standard library listed in components. Invoked as a separate program",
  "source": "tools/transcode with its Cargo.lock, built by scripts/build-transcode.sh; each component's source is the crate file at its \"source\" URL, whose SHA-256 is the checksum in Cargo.lock",
  "components": $COMPONENTS,
  "targets": "$(echo $TRIPLES)",
  "archs": "$ARCHS",
  "macosDeploymentTarget": "$( [ "$OS" = macos ] && echo "$MIN_MACOS" || echo n/a )",
  "signed": $( [ -n "${APPLE_SIGNING_IDENTITY:-}" ] && echo true || echo false ),
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "host": "$(uname -sm)",
  "rustc": "$(rustc --version)",
  "libc": "$LIBC"
}
EOF
node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$OUT/BUNDLE-INFO.json" || { echo "BUNDLE-INFO.json is not valid JSON"; exit 1; }
du -sh "$OUT" | awk '{print "bundle size: " $1}'
echo "done: $OUT"
