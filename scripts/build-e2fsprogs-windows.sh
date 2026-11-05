#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Windows counterpart of scripts/build-e2fsprogs.sh: collects debugfs, e2fsck and dumpe2fs from
# Cygwin's e2fsprogs package, with the Cygwin DLLs they load, so the installer engine can read, patch
# and check the player's ext4 system image on Windows without the user installing Cygwin.
#
# Output: build/e2fsprogs/windows/ (apps/desktop/src-tauri/resources/e2fsprogs/windows/ when that app folder exists beside this script)
#   debugfs.exe e2fsck.exe dumpe2fs.exe   Cygwin's builds
#   cyg*.dll                              cygwin1.dll and any other Cygwin DLL they load
#   licences/                             LGPL-3.0.txt (cygwin1.dll's licence, vendored in scripts/licences/),
#                                         the package docs Cygwin ships (e2fsprogs, Cygwin), and the licence
#                                         text of every other DLL's package (vendored in scripts/licences/)
#   BUNDLE-INFO.json                      the Cygwin package and version behind every bundled file,
#                                         each DLL's licence and notice files ("dllLicences"),
#                                         where the sources are, build date
#
# Usage (Git Bash or Cygwin bash, with Cygwin installed and its e2fsprogs package added):
#        scripts/build-e2fsprogs-windows.sh
#        CYGWIN_ROOT=/c/cygwin64 OUT_DIR=... scripts/build-e2fsprogs-windows.sh
#
# Beta stopgap: the macOS/Linux bundles are built from the pinned upstream tarball; this one is
# Cygwin's package build of e2fsprogs, whose sources (and those of every bundled DLL) are Cygwin's
# source packages: release.yml mirrors each package that BUNDLE-INFO.json's "packages" names.
# See docs/licensing/third-party.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="debugfs e2fsck dumpe2fs"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) echo "scripts/build-e2fsprogs-windows.sh runs on Windows (Git Bash or Cygwin); use scripts/build-e2fsprogs.sh elsewhere"; exit 1 ;;
esac
if [ -z "${CYGWIN_ROOT:-}" ]; then
  for c in /c/cygwin64 /c/cygwin /cygdrive/c/cygwin64 /cygdrive/c/cygwin; do [ -d "$c/bin" ] && CYGWIN_ROOT="$c" && break; done
fi
[ -n "${CYGWIN_ROOT:-}" ] && [ -x "$CYGWIN_ROOT/bin/cygcheck.exe" ] || { echo "Cygwin not found (set CYGWIN_ROOT)"; exit 1; }
SRC="$CYGWIN_ROOT/usr/sbin"
[ -f "$SRC/debugfs.exe" ] || SRC="$CYGWIN_ROOT/bin"
for t in $TOOLS; do
  [ -f "$SRC/$t.exe" ] || { echo "$t.exe not found under $CYGWIN_ROOT (install Cygwin's e2fsprogs package)"; exit 1; }
done
if [ -d "$ROOT/apps/desktop/src-tauri" ]; then DEFAULT_OUT="$ROOT/apps/desktop/src-tauri/resources/e2fsprogs/windows"; else DEFAULT_OUT="$ROOT/build/e2fsprogs/windows"; fi
OUT="${OUT_DIR:-$DEFAULT_OUT}"
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT")" ] && ! grep -q '"tool": "e2fsprogs"' "$OUT/BUNDLE-INFO.json" 2>/dev/null; then
  echo "refusing to replace OUT_DIR=$OUT: it is not empty and holds no e2fsprogs BUNDLE-INFO.json from an earlier build"; exit 1
fi

echo "== collecting from $SRC"
rm -rf "$OUT"; mkdir -p "$OUT/licences"
for t in $TOOLS; do cp "$SRC/$t.exe" "$OUT/"; done
# Every DLL cygcheck resolves inside the Cygwin tree (Windows' own DLLs are left to Windows).
# cygcheck searches PATH for them, so Cygwin's bin goes first for this call only.
DLLS=$(for t in $TOOLS; do env PATH="$CYGWIN_ROOT/bin:$PATH" "$CYGWIN_ROOT/bin/cygcheck.exe" "$(cygpath -w "$SRC/$t.exe")"; done \
  | tr -d '\r' | grep -io '[^ ]*\\bin\\[^\\ ]*\.dll' | grep -iv '\\windows\\' | sed 's/.*\\//' | sort -u)
[ -n "$DLLS" ] || { echo "cygcheck found no Cygwin DLLs; expected at least cygwin1.dll"; exit 1; }
for d in $DLLS; do cp "$CYGWIN_ROOT/bin/$d" "$OUT/"; done
echo "   DLLs: $(echo $DLLS | tr '\n' ' ')"
# cygwin1.dll is LGPL-3.0: its text ships with every bundle (the build fails without it).
cp "$ROOT/scripts/licences/LGPL-3.0.txt" "$OUT/licences/LGPL-3.0.txt"
for doc in e2fsprogs Cygwin; do
  [ -d "$CYGWIN_ROOT/usr/share/doc/$doc" ] && cp -R "$CYGWIN_ROOT/usr/share/doc/$doc" "$OUT/licences/$doc" || true
done

# The Cygwin package behind each bundled file (cygcheck -f, e.g. "libintl8-0.22.5-1" for
# cygintl-8.dll) and its installed version (cygcheck -c): BUNDLE-INFO.json's "packages", whose
# source packages release.yml mirrors. MSYS2_ARG_CONV_EXCL stops Git Bash rewriting the /usr/bin/...
# path that cygcheck looks up in Cygwin's package lists.
cyg() { env PATH="$CYGWIN_ROOT/bin:$PATH" MSYS2_ARG_CONV_EXCL='*' "$CYGWIN_ROOT/bin/cygcheck.exe" "$@" 2>/dev/null | tr -d '\r'; }
NAMES="e2fsprogs"   # debugfs.exe, e2fsck.exe and dumpe2fs.exe
DLL_PKGS=""; DLL_LICENCES=""
for d in $DLLS; do
  owner="$(cyg -f "/usr/bin/$d" | head -1 || true)"   # a failure is reported below, not by set -e
  name="${owner%-*-*}"   # drop "-<version>-<release>"
  [ -n "$owner" ] && [ "$name" != "$owner" ] || { echo "cygcheck -f names no Cygwin package for $d (got '$owner')"; exit 1; }
  DLL_PKGS="$DLL_PKGS${DLL_PKGS:+, }\"$d\": \"$name\""
  NAMES="$NAMES $name"
  # The DLL's licence and the texts that carry it (scripts/check-e2fsprogs-bundle.mjs checks each one).
  # A package not listed here stops the build: its licence has to be looked up and its text added first.
  case "$name" in
    cygwin) lic="LGPL-3.0-or-later"; notices="LGPL-3.0.txt GPL-3.0/GPL-3.0.txt" ;;
    libcom_err2|libss2) lic="LicenseRef-MIT-SIPB"; notices="e2fsprogs-libet-libss/NOTICE" ;;
    libe2p2|libext2fs2) lic="LGPL-2.0-only"; notices="e2fsprogs/NOTICE" ;;
    libblkid1|libiconv2|libintl8) lic="LGPL-2.1-or-later"; notices="LGPL-2.1/LGPL-2.1.txt" ;;
    libuuid1) lic="BSD-3-Clause"; notices="util-linux-libuuid/NOTICE" ;;
    libgcc1) lic="GPL-3.0-or-later WITH GCC-exception-3.1"; notices="GPL-3.0/GPL-3.0.txt gcc-runtime-exception/COPYING.RUNTIME" ;;
    *) echo "no licence mapping for $d (Cygwin package $name): add it to build-e2fsprogs-windows.sh and its text to scripts/licences/"; exit 1 ;;
  esac
  list=""
  for n in $notices; do
    if [ ! -f "$OUT/licences/$n" ]; then
      [ -f "$ROOT/scripts/licences/$n" ] || { echo "licence text licences/$n for $d is neither in Cygwin's docs nor in scripts/licences/"; exit 1; }
      mkdir -p "$(dirname "$OUT/licences/$n")"; cp "$ROOT/scripts/licences/$n" "$OUT/licences/$n"
    fi
    list="$list${list:+, }\"licences/$n\""
  done
  DLL_LICENCES="$DLL_LICENCES${DLL_LICENCES:+, }\"$d\": { \"package\": \"$name\", \"licence\": \"$lic\", \"notices\": [$list] }"
done
PKGS=""
for p in $(echo $NAMES | tr ' ' '\n' | sort -u); do
  v="$(cyg -c "$p" | awk -v p="$p" '$1 == p { print $2; exit }' || true)"
  [ -n "$v" ] || { echo "cygcheck -c reports no installed version of the Cygwin package $p"; exit 1; }
  PKGS="$PKGS${PKGS:+, }\"$p\": \"$v\""
done
echo "   packages: $PKGS"

echo "== smoke test (bundle only: Windows' system folders on PATH)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
dd if=/dev/zero of="$WORK/t.img" bs=1048576 count=8 2>/dev/null
IMG="$(cygpath -m "$WORK/t.img")"   # C:/... : what the app passes, and what Cygwin reads as-is
env PATH="$CYGWIN_ROOT/bin:$PATH" "$SRC/mke2fs.exe" -q -t ext4 -F "$IMG"
bundled() { (cd "$OUT" && env PATH="/c/Windows/system32:/c/Windows" "./$1.exe" "${@:2}" 2>&1 | tr -d '\r'); }
bundled debugfs -R "ls -l /" "$IMG" | grep -q "lost+found" || { echo "bundled debugfs could not list the test image"; bundled debugfs -R "ls -l /" "$IMG"; exit 1; }
bundled e2fsck -fn "$IMG" > /dev/null || { echo "bundled e2fsck failed on a clean image"; exit 1; }
bundled dumpe2fs -h "$IMG" | grep -q "Filesystem magic number" || { echo "bundled dumpe2fs failed"; exit 1; }

VERSION="$("$OUT/debugfs.exe" -V 2>&1 | tr -d '\r' | awk '/^debugfs/ {print $2; exit}')"
cat > "$OUT/BUNDLE-INFO.json" <<EOF
{
  "tool": "e2fsprogs",
  "version": "$VERSION",
  "source": "Cygwin packages",
  "packages": { $PKGS },
  "sources": "the Cygwin source package of every entry in packages (https://cygwin.com/packages/), mirrored with each release as e2fsprogs-cygwin-src.tar.gz",
  "licence": "e2fsprogs: GPL-2.0 programs, LGPL-2.0/BSD/MIT libraries; cygwin1.dll: LGPL-3.0 (licences/LGPL-3.0.txt); other DLLs: their Cygwin packages' licences",
  "dlls": { $DLL_PKGS },
  "dllLicences": { $DLL_LICENCES },
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
echo "done: $OUT ($VERSION)"
