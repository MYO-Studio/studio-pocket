#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

// Adds the GPL-3.0-only file header to every source file in a tree, or checks that it is there.
// Usage: node add-headers.mjs <dir> [--check]
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const HOLDER = process.env.STUDIO_POCKET_COPYRIGHT_HOLDER || 'Ashworth Digital Ltd';
const YEAR = '2026';
// fixtures/ is walked: its data files have no source extension, and its scripts get the header too.
const SKIP_DIRS = new Set(['node_modules', 'dist', 'build', '.git', 'screenshots']);
const COMMENT = { '.ts': '//', '.mjs': '//', '.js': '//', '.rs': '//', '.kt': '//', '.kts': '//', '.sh': '#', '.py': '#' };
const SKIP_FILES = new Set(['gradlew', 'gradlew.bat']);

const headerLines = [
    'SPDX-License-Identifier: GPL-3.0-only',
    `Copyright (C) ${YEAR} ${HOLDER}`,
    'This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a',
    `commercial licence from ${HOLDER} (see COPYING.commercial).`,
];

function header(prefix) { return headerLines.map((l) => `${prefix} ${l}`.trimEnd()).join('\n') + '\n'; }

function walk(dir, out = []) {
    for (const name of readdirSync(dir)) {
        const p = path.join(dir, name);
        if (statSync(p).isDirectory()) { if (!SKIP_DIRS.has(name)) walk(p, out); }
        else out.push(p);
    }
    return out;
}

const [dir, flag] = process.argv.slice(2);
const check = flag === '--check';
const missing = [];
for (const file of walk(dir)) {
    const ext = path.extname(file);
    const base = path.basename(file);
    if (!COMMENT[ext] || SKIP_FILES.has(base)) continue;
    const text = readFileSync(file, 'utf8');
    // A file is headed when the identifier is in its first 10 lines (after a shebang, or a file's own
    // header such as scripts/patch-mtkclient.py's); a mention further down does not count.
    if (text.split('\n', 10).some((l) => l.includes('SPDX-License-Identifier'))) continue;
    if (check) { missing.push(path.relative(dir, file)); continue; }
    const prefix = COMMENT[ext];
    const shebang = text.startsWith('#!') ? text.slice(0, text.indexOf('\n') + 1) : '';
    const body = text.slice(shebang.length);
    writeFileSync(file, shebang + header(prefix) + (body.startsWith('\n') ? '' : '\n') + body);
}
if (check && missing.length) {
    console.error(`files without the licence header:\n  ${missing.join('\n  ')}`);
    process.exit(1);
}
