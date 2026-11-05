#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

// Sets the version of every public package to the release tag's version, before packing.
// Usage: node scripts/release/set-version.mjs <version> [root]   (root defaults to .)
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const version = process.argv[2];
const root = process.argv[3] || '.';
if (!version || !/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
    console.error('usage: set-version.mjs <version, e.g. 0.5.0> [root]');
    process.exit(2);
}
const packages = path.join(root, 'packages');
let count = 0;
for (const name of readdirSync(packages).sort()) {
    const file = path.join(packages, name, 'package.json');
    if (!existsSync(file)) continue;
    const pkg = JSON.parse(readFileSync(file, 'utf8'));
    pkg.version = version;
    writeFileSync(file, JSON.stringify(pkg, null, 2) + '\n');
    console.log(`${pkg.name}: ${version}`);
    count++;
}
if (count === 0) {
    console.error(`no packages/*/package.json under ${root}`);
    process.exit(1);
}
