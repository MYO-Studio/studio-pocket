#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

// Release gate for the bundled e2fsprogs, run by `pnpm tauri build` before bundling
// (tauri.conf.json `beforeBundleCommand`) and by CI after scripts/build-e2fsprogs.sh.
//
// Fails when build/e2fsprogs/<os>/ (apps/desktop/src-tauri/resources/e2fsprogs/<os>/ when that app folder exists) is not a usable bundle:
//   - debugfs, e2fsck, dumpe2fs missing or not executable, or no BUNDLE-INFO.json;
//   - macOS: a binary whose signature does not verify;
//   - macOS with APPLE_SIGNING_IDENTITY set (a signed release): a binary that is ad-hoc signed or
//     lacks the hardened runtime. Tauri signs the app but not the executables under Resources, so
//     a bundle built without the identity would break notarisation;
//   - a DLL (the Windows bundle's Cygwin set) with no "dllLicences" entry in BUNDLE-INFO.json, or whose
//     licence texts are missing from licences/ or are not that licence's text.
// Without it a release still builds, ships only `.keep`, and tells users to reinstall.
//
// Usage: node scripts/check-e2fsprogs-bundle.mjs
//        E2FS_BUNDLE_DIR=... node scripts/check-e2fsprogs-bundle.mjs   (check another folder)
// Debug bundles (`tauri build --debug`, TAURI_ENV_DEBUG=true) and STUDIO_POCKET_ALLOW_MISSING_E2FSPROGS=1
// only warn. On Windows the tools are `.exe` files with their Cygwin DLLs beside them
// (scripts/build-e2fsprogs-windows.sh), and nothing is signed yet.
import { spawnSync } from 'node:child_process';
import { accessSync, constants, existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS = ['debugfs', 'e2fsck', 'dumpe2fs'];
const os = { darwin: 'macos', linux: 'linux', win32: 'windows' }[process.platform];
if (!os) {
    console.log(`e2fsprogs bundle check skipped: no bundle is built for ${process.platform} yet`);
    process.exit(0);
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const desktop = path.join(root, 'apps/desktop/src-tauri');
const dir = process.env.E2FS_BUNDLE_DIR || (existsSync(desktop) ? path.join(root, 'apps/desktop/src-tauri/resources/e2fsprogs', os) : path.join(root, 'build/e2fsprogs', os));
const identity = process.env.APPLE_SIGNING_IDENTITY || '';
const problems = [];

/** `codesign` writes its report to stderr; returns [ok, stdout + stderr]. */
function codesign(args) {
    const r = spawnSync('codesign', args, { encoding: 'utf8' });
    return [r.status === 0, `${r.stdout ?? ''}${r.stderr ?? ''}${r.error ? r.error.message : ''}`];
}
function describe(file) {
    const [, out] = codesign(['-dv', '--verbose=2', file]);
    return out;
}

let info = null;
try {
    info = JSON.parse(readFileSync(path.join(dir, 'BUNDLE-INFO.json'), 'utf8'));
    if (info.tool !== 'e2fsprogs') problems.push(`BUNDLE-INFO.json is not an e2fsprogs bundle (tool: ${info.tool})`);
} catch {
    problems.push('BUNDLE-INFO.json is missing or unreadable');
}
for (const t of TOOLS) {
    const file = path.join(dir, os === 'windows' ? `${t}.exe` : t);
    try {
        if (!statSync(file).isFile()) throw new Error();
        accessSync(file, constants.X_OK);
    } catch {
        problems.push(`${t} is missing or not executable`);
        continue;
    }
    if (os !== 'macos') continue;
    const [verified, why] = codesign(['--verify', '--strict', file]);
    if (!verified) {
        problems.push(`${t}: signature does not verify (${why.trim().split('\n').pop()})`);
        continue;
    }
    if (identity) {
        const d = describe(file);
        if (/Signature=adhoc/.test(d)) problems.push(`${t} is only ad-hoc signed, but APPLE_SIGNING_IDENTITY is set`);
        else if (!/flags=0x[0-9a-f]*\(.*runtime.*\)/.test(d)) problems.push(`${t} is signed without the hardened runtime`);
    }
}
if (os === 'windows' && !existsSync(path.join(dir, 'cygwin1.dll'))) problems.push('cygwin1.dll is missing (the tools cannot start without it)');

// Every DLL ships with its licence: the texts BUNDLE-INFO.json's "dllLicences" names
// (scripts/build-e2fsprogs-windows.sh), each starting the way that licence's text does. For each
// licence, every text in its list below must be among the DLL's notices.
const firstLine = (text) => (text.split(/\r?\n/).find((l) => /[A-Za-z0-9]/.test(l)) ?? '').replace(/^[\s/*#=-]+/, '').trim();
const GPL3 = { first: 'GNU GENERAL PUBLIC LICENSE', contains: 'Version 3, 29 June 2007' };
const DLL_LICENCE_TEXTS = {
    'LGPL-3.0-or-later': [{ first: 'GNU LESSER GENERAL PUBLIC LICENSE', contains: 'Version 3, 29 June 2007' }, GPL3],
    'LGPL-2.1-or-later': [{ first: 'GNU LESSER GENERAL PUBLIC LICENSE', contains: 'Version 2.1, February 1999' }],
    'LGPL-2.0-only': [{ first: 'This package, the EXT2 filesystem utilities, are made available under', contains: 'GNU LIBRARY GENERAL PUBLIC LICENSE' }],
    'LicenseRef-MIT-SIPB': [{ first: /^Copyright 198\d/, contains: 'Permission to use, copy, modify, and distribute this software' }],
    'BSD-3-Clause': [{ first: /^Copyright /, contains: 'Redistribution and use in source and binary forms' }],
    'GPL-3.0-or-later WITH GCC-exception-3.1': [GPL3, { first: 'GCC RUNTIME LIBRARY EXCEPTION', contains: 'Version 3.1' }],
};
const dlls = existsSync(dir) ? readdirSync(dir).filter((f) => /\.dll$/i.test(f)) : [];
for (const dll of dlls) {
    const entry = info?.dllLicences?.[dll];
    if (!entry) { problems.push(`${dll} has no dllLicences entry in BUNDLE-INFO.json`); continue; }
    const wanted = DLL_LICENCE_TEXTS[entry.licence];
    if (!wanted) { problems.push(`${dll}: licence ${JSON.stringify(entry.licence)} is not in the gate's table`); continue; }
    const texts = [];
    for (const n of Array.isArray(entry.notices) ? entry.notices : []) {
        if (existsSync(path.join(dir, n))) texts.push(readFileSync(path.join(dir, n), 'utf8'));
        else problems.push(`${dll}: licence text ${n} is missing`);
    }
    for (const w of wanted) {
        const ok = texts.some((t) => {
            const first = firstLine(t);
            return (w.first instanceof RegExp ? w.first.test(first) : first === w.first) && (!w.contains || t.includes(w.contains));
        });
        if (!ok) problems.push(`${dll} (${entry.licence}): no licence text starting "${w.first}"${w.contains ? ` with "${w.contains}"` : ''}`);
    }
}
if (identity && info && info.signed !== true) problems.push('BUNDLE-INFO.json says the bundle was built without APPLE_SIGNING_IDENTITY');

if (problems.length === 0) {
    const authority = os === 'macos' ? (describe(path.join(dir, 'debugfs')).match(/^Authority=(.*)$/m)?.[1] ?? 'ad-hoc') : 'n/a';
    if (dlls.length) console.log(`DLL licences ok: ${dlls.length} DLLs, each with its licence texts`);
    console.log(`e2fsprogs bundle ok: ${info.version} in ${dir} (signature: ${authority})`);
    process.exit(0);
}
const fix = `run scripts/build-e2fsprogs${os === 'windows' ? '-windows' : ''}.sh${identity ? ' with APPLE_SIGNING_IDENTITY set' : ''}`;
const lenient = process.env.TAURI_ENV_DEBUG === 'true' || process.env.STUDIO_POCKET_ALLOW_MISSING_E2FSPROGS === '1';
console.error(`${lenient ? 'warning' : 'error'}: the e2fsprogs bundle in ${dir} is not releasable:`);
for (const p of problems) console.error(`  - ${p}`);
console.error(`  ${fix}${lenient ? ' (continuing: debug bundle or STUDIO_POCKET_ALLOW_MISSING_E2FSPROGS=1)' : ''}`);
if (!lenient) console.error('  for a local, non-release bundle: pnpm tauri build --debug, or STUDIO_POCKET_ALLOW_MISSING_E2FSPROGS=1 pnpm tauri build');
process.exit(lenient ? 0 : 1);
