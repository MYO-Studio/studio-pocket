#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Passes only when the player APK carries its own licence (assets/licenses/GPL-3.0.txt, the canonical
# GPL-3.0) and the notices its bundled libraries need (Apache-2.0 §4):
# assets/licenses/APACHE-2.0.txt starting with the licence's canonical first line, and
# assets/licenses/THIRD-PARTY.txt naming every runtime dependency below at its version, plus the
# font licence. Usage: scripts/check-apk-notices.sh <apk>   (or --list: the dependency names)
set -euo pipefail

# The player's runtime dependencies (releaseRuntimeClasspath of apps/player/app), "group:name version".
# This list and the dependencies block of apps/player/app/build.gradle.kts change together: the Kotlin
# standard library comes from the Kotlin Gradle plugin's version in apps/player/build.gradle.kts, and
# brings org.jetbrains:annotations with it. Check with:
#   (cd apps/player && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath)
# release.yml does, and fails on a dependency this list does not name.
RUNTIME_DEPENDENCIES=(
  "org.jetbrains.kotlin:kotlin-stdlib 2.0.21"
  "org.jetbrains:annotations 13.0"
)

# --list: print the listed dependencies' names (group:name), one per line, for that comparison.
if [ "${1:-}" = "--list" ]; then
  for dep in "${RUNTIME_DEPENDENCIES[@]}"; do echo "${dep% *}"; done
  exit 0
fi

APK="${1:?apk}"
[ -f "$APK" ] || { echo "no such APK: $APK" >&2; exit 1; }

T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
unzip -q -o "$APK" 'assets/licenses/*' 'assets/fonts/LICENSE-Nunito.txt' -d "$T" 2>/dev/null || true
L="$T/assets/licenses"
fail=0
problem() { echo "$APK: $1" >&2; fail=1; }

[ -f "$L/APACHE-2.0.txt" ] || problem "assets/licenses/APACHE-2.0.txt is missing"
if [ -f "$L/APACHE-2.0.txt" ]; then
  first="$(grep -m1 -v '^[[:space:]]*$' "$L/APACHE-2.0.txt" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  [ "$first" = "Apache License" ] || problem "APACHE-2.0.txt does not start with \"Apache License\" (got \"$first\")"
  grep -q 'Version 2.0, January 2004' "$L/APACHE-2.0.txt" || problem "APACHE-2.0.txt is not version 2.0"
fi
# The player is GPL-3.0-only code: a copy of the licence goes with every copy of it (About > Licences shows it).
[ -f "$L/GPL-3.0.txt" ] || problem "assets/licenses/GPL-3.0.txt is missing"
if [ -f "$L/GPL-3.0.txt" ]; then
  first="$(grep -m1 -v '^[[:space:]]*$' "$L/GPL-3.0.txt" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
  [ "$first" = "GNU GENERAL PUBLIC LICENSE" ] || problem "GPL-3.0.txt does not start with \"GNU GENERAL PUBLIC LICENSE\" (got \"$first\")"
  grep -q 'Version 3, 29 June 2007' "$L/GPL-3.0.txt" || problem "GPL-3.0.txt is not version 3"
  grep -q 'END OF TERMS AND CONDITIONS' "$L/GPL-3.0.txt" || problem "GPL-3.0.txt is not the whole licence"
fi
[ -f "$T/assets/fonts/LICENSE-Nunito.txt" ] || problem "assets/fonts/LICENSE-Nunito.txt (the font's OFL-1.1) is missing"

if [ ! -f "$L/THIRD-PARTY.txt" ]; then
  problem "assets/licenses/THIRD-PARTY.txt is missing"
else
  for dep in "${RUNTIME_DEPENDENCIES[@]}"; do
    coord="${dep% *}"; version="${dep#* }"
    # "Package: <coord>" with "Version: <version>" on the next line.
    awk -v p="Package: $coord" -v v="Version: $version" '$0 == p { getline n; if (n == v) found = 1 } END { exit !found }' "$L/THIRD-PARTY.txt" \
      || problem "THIRD-PARTY.txt does not list $coord at version $version"
  done
  grep -q 'SIL Open Font License 1.1' "$L/THIRD-PARTY.txt" || problem "THIRD-PARTY.txt does not list the font's licence"
fi
[ "$fail" -eq 0 ] || exit 1
echo "apk notices ok: GPL-3.0, ${#RUNTIME_DEPENDENCIES[@]} runtime dependencies, Apache-2.0 and OFL-1.1 texts present"
