#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Creates a development signing key for apps/player (git-ignored).
# The key only has to be consistent across builds you install on the same device;
# priv-app placement does not require the Innioasis platform key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLAYER="$ROOT/apps/player"
KS="$PLAYER/keystore/myo-pocket-dev.jks"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME
mkdir -p "$PLAYER/keystore"
if [ -f "$KS" ]; then echo "exists: $KS"; else
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KS" -storepass myo-pocket-dev -keypass myo-pocket-dev \
    -alias myo-pocket-dev -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Studio Pocket dev, O=MYO Studio" >/dev/null
  echo "created: $KS"
fi
if [ ! -f "$PLAYER/keystore.properties" ]; then
  cp "$PLAYER/keystore.properties.example" "$PLAYER/keystore.properties"
  echo "created: $PLAYER/keystore.properties"
fi
