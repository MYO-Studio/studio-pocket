#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Public CI hygiene check: fails on a forbidden file name, a file over 5 MB, or a secret (gitleaks).
# Usage: scripts/check-public-hygiene.sh <dir>
set -uo pipefail
DIR="${1:?dir}"
HERE="$(cd "$(dirname "$0")" && pwd)"
fail=0

while IFS= read -r pattern; do
  [ -z "$pattern" ] && continue
  if found="$(find "$DIR" -name "$pattern" -not -path '*/node_modules/*' -not -path '*/.github/workflows' 2>/dev/null)" && [ -n "$found" ]; then
    echo "forbidden name ($pattern):"; echo "$found" | sed 's/^/  /'; fail=1
  fi
done < "$HERE/forbidden-paths.txt"

big="$(find "$DIR" -type f -size +5M -not -path '*/node_modules/*')"
if [ -n "$big" ]; then echo "files over 5 MB:"; echo "$big" | sed 's/^/  /'; fail=1; fi

cfg=()
[ -f "$DIR/.gitleaks.toml" ] && cfg=(--config "$DIR/.gitleaks.toml")
if ! gitleaks detect --no-git --source "$DIR" ${cfg[@]+"${cfg[@]}"} --redact --exit-code 1 >/dev/null 2>&1; then
  echo "gitleaks found a secret in $DIR (or gitleaks is missing); run gitleaks detect --no-git --source <dir> to see the rule"; fail=1
fi

exit $fail
