#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Builds a standalone mtkclient (GPL-3.0, bkerler/mtkclient at the commit in scripts/mtkclient-pin.json
# + the Studio Pocket patches) with PyInstaller so the installer engine can run it as a separate program
# without a Python install.
#
# Output: build/mtkclient/ (apps/desktop/src-tauri/resources/mtkclient/ when that app folder exists beside this script)
#   mtk            the executable (mtk.exe on Windows)
#   _internal/     Python runtime, mtkclient (without its Loader/ folder), libusb
#   licences/      the licence texts of mtkclient, every other bundled distribution and every native
#                  library (scripts/mtkclient-notices.py; vendored texts from scripts/licences/)
#   BUNDLE-INFO.json  includes "nativeLibraries" (file -> project, version, licence, notice)
#
# Usage: scripts/build-mtkclient.sh            (first runs scripts/setup-mtkclient-venv.sh, which
#                                               rebuilds firmware/tools/mtkclient-venv from the lock)
#        OUT_DIR=... scripts/build-mtkclient.sh
#        LIBUSB_DIR=build/libusb scripts/build-mtkclient.sh   (macOS: bundle the libusb that
#                                               scripts/build-libusb-macos.sh built for macOS 11 and later,
#                                               instead of the one PyInstaller finds, such as Homebrew's,
#                                               which needs the macOS it was bottled for; the release does this)
#
# What is left out on purpose: the PySide6 GUI (79 MB), BROM exploit payloads, the Windows driver
# installer, WinFsp (mtkclient's optional filesystem mount) and mtkclient's whole Loader/ folder:
# MediaTek's download agents and the OEM preloader images. Studio Pocket distributes none of them. The
# installer fetches the one download agent the Y2 needs (MTK_AllInOne_DA_mt6590.bin) from upstream
# mtkclient at the pinned commit on first use, checks it against packages/firmware-tools/src/da-pin.json
# and hands it to mtkclient with --loader; no preloader is needed in preloader mode.
# The bundle is platform-specific: build it on each OS that ships.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$ROOT/firmware/tools/mtkclient-venv"
if [ -d "$ROOT/apps/desktop/src-tauri" ]; then DEFAULT_OUT="$ROOT/apps/desktop/src-tauri/resources/mtkclient"; else DEFAULT_OUT="$ROOT/build/mtkclient"; fi
OUT="${OUT_DIR:-$DEFAULT_OUT}"
WORK="${TMPDIR:-/tmp}/myo-mtkclient-build"
# OUT is replaced wholesale: never let a mistyped OUT_DIR point at /, $HOME or the repo.
case "$(cd "$OUT" 2>/dev/null && pwd -P)" in
  /|"$(cd "$HOME" && pwd -P)"|"$(cd "$ROOT" && pwd -P)") echo "refusing to replace OUT_DIR=$OUT"; exit 1 ;;
esac
# … nor at any other folder with something in it that an earlier run of this script didn't put there.
if [ -e "$OUT" ] && [ ! -d "$OUT" ]; then echo "refusing to replace OUT_DIR=$OUT: it is a file, not a folder"; exit 1; fi
# (.keep is committed in the default folder, so a folder holding only that counts as empty.)
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT" | grep -vx '.keep')" ] && ! grep -q '"tool": "mtkclient"' "$OUT/BUNDLE-INFO.json" 2>/dev/null; then
  echo "refusing to replace OUT_DIR=$OUT: it is not empty and holds no mtkclient BUNDLE-INFO.json from an earlier build"; exit 1
fi
echo "== creating the clean, locked venv"
"$ROOT/scripts/setup-mtkclient-venv.sh"
# A Windows venv has Scripts/ and Lib/site-packages; executables end in .exe.
if [ -d "$VENV/Scripts" ]; then BIN="$VENV/Scripts"; SITE="$VENV/Lib/site-packages"; EXE=".exe"
else BIN="$VENV/bin"; SITE="$(echo "$VENV"/lib/python3.*/site-packages)"; EXE=""; fi
[ -f "$BIN/mtk$EXE" ] || { echo "mtkclient venv at $VENV has no mtk launcher"; exit 1; }

echo "== patching mtkclient (idempotent)"
"$BIN/python" "$ROOT/scripts/patch-mtkclient.py" "$SITE/mtkclient"
PATCHES=$(grep -c "Studio Pocket patch" "$SITE"/mtkclient/Library/Port.py "$SITE"/mtkclient/Library/Connection/seriallib.py "$SITE"/mtkclient/Library/DA/legacy/dalegacy_lib.py "$SITE"/mtkclient/Library/partition.py "$SITE"/mtkclient/Library/mtk_main.py "$SITE"/mtkclient/mtk.py | awk -F: '{s+=$2} END {print s}')
[ "$PATCHES" -ge 9 ] || { echo "expected the Studio Pocket patches in the venv, found $PATCHES markers"; exit 1; }

LIBUSB_ARGS=()
if [ -n "${LIBUSB_DIR:-}" ]; then
  # Absolute: PyInstaller resolves --add-binary paths from its work folder, not from here.
  LIBUSB_DIR="$(cd "$LIBUSB_DIR" && pwd)" || { echo "LIBUSB_DIR=$LIBUSB_DIR is not a folder"; exit 1; }
  for f in libusb-1.0.0.dylib COPYING VERSION; do
    [ -f "$LIBUSB_DIR/$f" ] || { echo "LIBUSB_DIR=$LIBUSB_DIR has no $f (build it with scripts/build-libusb-macos.sh)"; exit 1; }
  done
  # PyInstaller's own usb hook would also add whichever libusb it finds on this Mac: with pyusb
  # discovery skipped it still falls back to ctypes.util.find_library, which finds Homebrew's in
  # /usr/local/lib on Intel. Our hook (written below) replaces it and adds nothing.
  LIBUSB_ARGS=(--add-binary "$LIBUSB_DIR/libusb-1.0.0.dylib:." --additional-hooks-dir "$WORK/hooks")
fi

echo "== building"
rm -rf "$WORK"; mkdir -p "$WORK"
if [ -n "${LIBUSB_DIR:-}" ]; then
  mkdir -p "$WORK/hooks"
  printf '# The libusb comes from LIBUSB_DIR (--add-binary); collect no other.\nhiddenimports = ["glob"]\nbinaries = []\n' > "$WORK/hooks/hook-usb.py"
fi
# The venv's own mtk launcher is an .exe on Windows, so PyInstaller gets this equivalent script.
printf 'import sys\nfrom mtkclient.mtk import main\nif __name__ == "__main__":\n    sys.exit(main())\n' > "$WORK/mtk.py"
"$BIN/pyinstaller" --noconfirm --clean --onedir --name mtk \
  --collect-all mtkclient \
  --hidden-import mfusepy --hidden-import serial.tools.list_ports --hidden-import usb.backend.libusb1 \
  --exclude-module PySide6 --exclude-module shiboken6 --exclude-module PyQt5 --exclude-module PyQt6 \
  --exclude-module tkinter --exclude-module capstone --exclude-module keystone --exclude-module unicorn \
  ${LIBUSB_ARGS[@]+"${LIBUSB_ARGS[@]}"} \
  --distpath "$WORK/dist" --workpath "$WORK/build" --specpath "$WORK" "$WORK/mtk.py" > "$WORK/pyinstaller.log" 2>&1 || { tail -30 "$WORK/pyinstaller.log"; exit 1; }
BUNDLE="$WORK/dist/mtk"

echo "== pruning"
INTERNAL="$BUNDLE/_internal/mtkclient"
# mtkclient's Loader/: MediaTek's download agents and the OEM preloader images, none of them ours to
# distribute. The installer fetches the DA at first use (packages/firmware-tools/src/da.ts) and passes it
# with --loader; the Y2 is reached in preloader mode, whose own preloader has set up DRAM, so no
# preloader image is looked for (the engine stops with a clear message if one ever is). The gate fails
# on any file left under Loader/.
# (An install from the release's pruned source archive, MTKCLIENT_ARCHIVE, has no Loader/ files to begin with.)
rm -rf "$INTERNAL/Loader"
rm -rf "$INTERNAL/gui" "$INTERNAL/Windows" "$INTERNAL/payloads" "$INTERNAL/mtk_gui.py" "$BUNDLE/_internal/PySide6" "$BUNDLE/_internal/shiboken6"
# WinFsp: mtkclient ships two WinFsp DLLs for its optional `fs` (FUSE mount) command. Only that command
# would load them, through a real mfusepy; the bundle has the stub (setup-mtkclient-venv.sh), whose FUSE
# refuses to mount, and Library/Filesystem/mtkdafs.py only puts the DLL's path in FUSE_LIBRARY_PATH. So
# no bundle needs them, on any OS, and none carries them (the gate fails if one does).
rm -f "$INTERNAL/Library/Filesystem/bin"/winfsp-*.dll
rmdir "$INTERNAL/Library/Filesystem/bin" 2>/dev/null || true
for f in Library/Port.py Library/mtk_main.py mtk.py; do
  grep -q "Studio Pocket patch" "$INTERNAL/$f" || { echo "patch marker missing from the bundle's mtkclient sources ($f)"; exit 1; }
done

echo "== licences"
# Each bundled distribution's licence file from its dist-info (pyserial's wheel carries none, so
# its BSD text is kept in scripts/licences/pyserial/). The mfusepy stub is our own file, not a distribution.
LICENCES="$BUNDLE/licences"; mkdir -p "$LICENCES"
add_licence() {  # add_licence <name> <file>...: copies the first file that exists
    local name="$1"; shift
    for f in "$@"; do
        if [ -f "$f" ]; then cp "$f" "$LICENCES/$name-$(basename "$f")"; return 0; fi
    done
    echo "licence missing for $name (looked in: $*)"; exit 1
}
dist_info() { echo "$SITE"/"$1"-*.dist-info; }
add_licence mtkclient "$(dist_info mtkclient)/licenses/LICENSE"
add_licence pyusb "$(dist_info pyusb)/LICENSE"
add_licence pyserial "$ROOT/scripts/licences/pyserial/LICENSE.txt"
add_licence colorama "$(dist_info colorama)/licenses/LICENSE.txt"
add_licence pycryptodome "$(dist_info pycryptodome)/licenses/LICENSE.rst"
add_licence pycryptodomex "$(dist_info pycryptodomex)/LICENSE.rst"
add_licence python "$("$BIN/python" -c 'import sys; print(sys.base_prefix)')/LICENSE.txt" \
    "$("$BIN/python" -c 'import sys; print(sys.base_prefix)')/lib/python3.12/LICENSE.txt"
# The gate looks for the GPL-3.0 text; give it the canonical name too.
cp "$LICENCES/mtkclient-LICENSE" "$LICENCES/LICENSE-GPL-3.0.txt"
LIBUSB_VERSION=""
if ls "$BUNDLE/_internal"/libusb* >/dev/null 2>&1; then
    # The version goes into BUNDLE-INFO.json ("libusb"); the release attaches that version's upstream
    # source tarball (libusb-<version>-source.tar.bz2, pinned in scripts/libusb-pin.json).
    if [ -n "${LIBUSB_DIR:-}" ]; then
        # Exactly the library build-libusb-macos.sh made, and nothing else PyInstaller found.
        BUNDLED_LIBUSB="$(ls "$BUNDLE/_internal"/libusb*)"
        [ "$(echo "$BUNDLED_LIBUSB" | wc -l | tr -d ' ')" = 1 ] || { echo "expected one libusb in _internal/, found: $BUNDLED_LIBUSB"; exit 1; }
        # PyInstaller rewrites and re-signs what it collects, so the bytes differ; the Mach-O UUID does not.
        macho_uuid() { otool -l "$1" | awk '/cmd LC_UUID/{u=1} u&&/uuid/{print $2; exit}'; }
        [ -n "$(macho_uuid "$BUNDLED_LIBUSB")" ] && [ "$(macho_uuid "$BUNDLED_LIBUSB")" = "$(macho_uuid "$LIBUSB_DIR/libusb-1.0.0.dylib")" ] \
            || { echo "$BUNDLED_LIBUSB is not the library in LIBUSB_DIR=$LIBUSB_DIR"; exit 1; }
        LIBUSB_VERSION="$(cat "$LIBUSB_DIR/VERSION")"
    elif command -v brew >/dev/null 2>&1 && brew --prefix libusb >/dev/null 2>&1; then
        # The linked keg (opt/libusb -> Cellar/libusb/<version>[_<revision>]), not every installed version.
        LIBUSB_VERSION="$(basename "$(cd "$(brew --prefix libusb)" && pwd -P)" | sed 's/_[0-9]*$//')"
    elif command -v pkg-config >/dev/null 2>&1; then
        LIBUSB_VERSION="$(pkg-config --modversion libusb-1.0 2>/dev/null || true)"
    fi
    echo "$LIBUSB_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "libusb is bundled but its version could not be read (got '$LIBUSB_VERSION')"; exit 1; }
    printf '%s\n%s\n' \
        "The bundled libusb shared library (_internal/libusb*) is libusb $LIBUSB_VERSION, LGPL-2.1 (text in libusb/COPYING): https://libusb.info" \
        "Its source is attached to the same release as libusb-$LIBUSB_VERSION-source.tar.bz2 (upstream https://github.com/libusb/libusb/releases/tag/v$LIBUSB_VERSION)." \
        > "$LICENCES/README.txt"
    # The LGPL-2.1 text itself ships too; the build fails when the library is bundled without it.
    LIBUSB_COPYING=""
    for c in "${LIBUSB_DIR:+$LIBUSB_DIR/COPYING}" "$(command -v brew >/dev/null 2>&1 && brew --prefix libusb 2>/dev/null)/COPYING" /usr/share/doc/libusb-1.0-0/copyright; do
        if [ -f "$c" ]; then LIBUSB_COPYING="$c"; break; fi
    done
    [ -n "$LIBUSB_COPYING" ] || { echo "libusb is bundled but its LGPL text was not found (brew install libusb, or libusb-1.0-0 on Linux)"; exit 1; }
    mkdir -p "$LICENCES/libusb"; cp "$LIBUSB_COPYING" "$LICENCES/libusb/COPYING"
fi
# Every native library (OpenSSL, libffi, the CPython modules' static libraries, the Microsoft runtime on
# Windows, the bootloader): its notice into licences/ and its mapping into BUNDLE-INFO.json.
NATIVE_JSON="$WORK/native-libraries.json"
LIBUSB_VERSION="$LIBUSB_VERSION" "$BIN/python" -I "$ROOT/scripts/mtkclient-notices.py" "$BUNDLE" "$ROOT/scripts/licences" > "$NATIVE_JSON"

echo "== smoke test"
"$BUNDLE/mtk$EXE" --help > /dev/null
"$BUNDLE/mtk$EXE" --serialport DETECT script "$WORK/none.mtk" 2>&1 | grep -q "Couldn't find script" || { echo "bundled mtk does not accept the script invocation"; exit 1; }

echo "== staging and checking the bundle"
# The finished bundle is assembled and gated in $WORK/bundle; $OUT is only touched once it passes.
STAGE="$WORK/bundle"
rm -rf "$STAGE"; cp -R "$BUNDLE" "$STAGE"; touch "$STAGE/.keep"  # .keep is committed so the tauri.conf.json resource glob always matches
# One source for the commit: setup-mtkclient-venv.sh records it in the venv.
MTK_VERSION="$("$BIN/python" -m pip show mtkclient 2>/dev/null | awk '/^Version:/ {print $2}')+$(cut -c1-7 "$VENV/MTKCLIENT-COMMIT")"
LOCK_SHA256="$( (sha256sum "$ROOT/scripts/mtkclient-requirements.txt" 2>/dev/null || shasum -a 256 "$ROOT/scripts/mtkclient-requirements.txt") | cut -d' ' -f1)"
# "libusb": the bundled libusb's version, or null when the bundle has none (Windows).
LIBUSB_JSON=null; [ -z "$LIBUSB_VERSION" ] || LIBUSB_JSON="\"$LIBUSB_VERSION\""
cat > "$STAGE/BUNDLE-INFO.json" <<EOF
{
  "tool": "mtkclient",
  "upstream": "https://github.com/bkerler/mtkclient",
  "version": "$MTK_VERSION",
  "licence": "GPL-3.0 (invoked as a separate program; sources: upstream + scripts/patch-mtkclient.py)",
  "patches": $PATCHES,
  "lockSha256": "$LOCK_SHA256",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "host": "$(uname -sm)",
  "loaders": "none; the installer fetches the download agent at first use (see da-pin.json)",
  "libusb": $LIBUSB_JSON,
  "nativeLibraries": $(cat "$NATIVE_JSON"),
  "note": "No MediaTek download agent or OEM preloader is in this bundle (mtkclient's Loader/ folder is removed). The installer fetches MTK_AllInOne_DA_mt6590.bin from upstream mtkclient at the pinned commit on the user's machine at first use, checked against the SHA-256 in packages/firmware-tools/src/da-pin.json, and passes it to mtkclient with --loader."
}
EOF
MTK_BUNDLE_DIR="$STAGE" node "$ROOT/scripts/check-mtkclient-bundle.mjs"   # the same gate CI runs, so a bad build fails here, before $OUT changes
echo "== installing to $OUT"
rm -rf "$OUT"; mkdir -p "$(dirname "$OUT")"; cp -R "$STAGE" "$OUT"
du -sh "$OUT" | awk '{print "bundle size: " $1}'
echo "done: $OUT/mtk$EXE"
