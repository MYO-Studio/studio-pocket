#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

// Release gate for the bundled mtkclient (scripts/build-mtkclient.sh). Fails when the folder is
// not a usable bundle: a missing executable, unpatched sources, missing licence texts, or a
// BUNDLE-INFO.json that does not match the locked requirements. It also fails when a WinFsp file is
// in the bundle, when any file is under _internal/mtkclient/Loader/ (MediaTek's download agents and
// the OEM preloader images: the bundles carry none; the installer fetches the one download agent at
// first use), and when a native library in _internal/ has no "nativeLibraries" entry or an entry's
// notice file is missing or does not start the way its licence's text does, and on macOS when a Mach-O
// file needs a later macOS than 11.0 (MTK_BUNDLE_MAX_MACOS), as Homebrew's libusb does. After those static checks it
// runs the bundle's mtk (mtk.exe on Windows) on this host: with STUDIO_POCKET_PROBE=1 and no arguments
// it must exit 0 and print exactly the identity-gate line (the capability probe the runners use),
// and with neither it must exit 2 (usage). So the bundle must be checked on the OS it was built for.
//
// Usage: node scripts/check-mtkclient-bundle.mjs          (build/mtkclient; apps/desktop/src-tauri/resources/mtkclient when that app folder exists)
//        MTK_BUNDLE_DIR=... node scripts/check-mtkclient-bundle.mjs
//        MTK_BUNDLE_SKIP_PROBE=1 ...                        (static checks only; for fixture bundles in tests)
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, lstatSync, openSync, readdirSync, readFileSync, readSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const desktop = path.join(root, 'apps/desktop/src-tauri');
const dir = process.env.MTK_BUNDLE_DIR || (existsSync(desktop) ? path.join(root, 'apps/desktop/src-tauri/resources/mtkclient') : path.join(root, 'build/mtkclient'));
const pinPath = process.env.MTK_PIN_FILE || path.join(root, 'scripts/mtkclient-pin.json');
const lockPath = path.join(root, 'scripts/mtkclient-requirements.txt');
const problems = [];

if (!existsSync(path.join(dir, 'mtk')) && !existsSync(path.join(dir, 'mtk.exe'))) problems.push('mtk executable (mtk or mtk.exe) missing');

const internal = path.join(dir, '_internal', 'mtkclient');

// Port.py carries the serial patches; mtk_main.py the identity check; the package-root mtk.py the
// capability probe (a bundle built before either lacks that file's marker).
for (const file of ['Library/Port.py', 'Library/mtk_main.py', 'mtk.py']) {
    const source = path.join(internal, file);
    if (!existsSync(source) || !readFileSync(source, 'utf8').includes('Studio Pocket patch')) problems.push(`Studio Pocket patch marker missing from ${path.basename(file)}: the bundle is not patched`);
}
if (existsSync(path.join(dir, '_internal', 'PySide6')) || existsSync(path.join(dir, '_internal', 'shiboken6'))) problems.push('GUI modules (PySide6, shiboken6) are in the bundle');

const licences = path.join(dir, 'licences');
const licenceTexts = existsSync(licences)
    ? readdirSync(licences, { recursive: true }).map((f) => path.join(licences, String(f))).filter((f) => statSync(f).isFile()).map((f) => readFileSync(f, 'utf8'))
    : [];
const hasGpl = licenceTexts.some((t) => t.includes('GNU GENERAL PUBLIC LICENSE'));
if (!hasGpl) problems.push('licences/ has no GPL-3.0 text');

if (process.platform !== 'win32') {
    const hasLibusb = existsSync(path.join(dir, '_internal')) && readdirSync(path.join(dir, '_internal')).some((f) => f.startsWith('libusb'));
    if (!hasLibusb) problems.push('libusb missing: _internal/libusb* is not in the bundle');
    const hasLgpl = licenceTexts.some((t) => t.includes('GNU LESSER GENERAL PUBLIC LICENSE'));
    if (!hasLgpl) problems.push('licences/ has no LGPL text for libusb');
}

// Every regular file under a folder, as paths relative to it with forward slashes (symlinks skipped).
function files(base, sub = '') {
    const at = path.join(base, sub);
    if (!existsSync(at)) return [];
    return readdirSync(at).flatMap((name) => {
        const rel = sub ? `${sub}/${name}` : name;
        const st = lstatSync(path.join(base, rel));
        if (st.isSymbolicLink()) return [];
        return st.isDirectory() ? files(base, rel) : [rel];
    });
}
const bundleFiles = files(dir);

// WinFsp is only for mtkclient's FUSE mount, which the bundle cannot do; no bundle ships it.
for (const f of bundleFiles.filter((f) => /^winfsp/i.test(path.posix.basename(f)))) problems.push(`WinFsp file in the bundle: ${f}`);
// No download agent and no preloader image: the installer fetches the agent (packages/firmware-tools/src/da.ts).
const loaderFiles = bundleFiles.filter((f) => f.startsWith('_internal/mtkclient/Loader/'));
if (loaderFiles.length) problems.push(`${loaderFiles.length} file(s) under _internal/mtkclient/Loader/ (the bundle carries no download agent or preloader): ${loaderFiles.slice(0, 5).join(', ')}`);

// macOS: no native file may need a newer macOS than the oldest the desktop supports. A library
// copied from Homebrew needs the macOS it was bottled for (a macos-latest runner's), and a bundle
// carrying it fails to load on every older Mac; scripts/build-libusb-macos.sh builds libusb for 11.0.
const MAX_MACOS = process.env.MTK_BUNDLE_MAX_MACOS || '11.0';
let machOChecked = 0;
const MACHO_MAGIC = new Set(['feedface', 'feedfacf', 'cefaedfe', 'cffaedfe', 'cafebabe', 'bebafeca']);
function isMachO(file) {
    const fd = openSync(file, 'r');
    try {
        const head = Buffer.alloc(4);
        return readSync(fd, head, 0, 4, 0) === 4 && MACHO_MAGIC.has(head.toString('hex'));
    } finally { closeSync(fd); }
}
const newer = (a, b) => {  // true when version a is later than b (dotted numbers)
    const x = a.split('.').map(Number), y = b.split('.').map(Number);
    for (let i = 0; i < Math.max(x.length, y.length); i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (x[i] ?? 0) > (y[i] ?? 0);
    return false;
};
if (process.platform === 'darwin') {
    const tooNew = [];
    for (const f of bundleFiles) {
        const full = path.join(dir, f);
        if (!isMachO(full)) continue;
        const otool = spawnSync('otool', ['-arch', 'all', '-l', full], { encoding: 'utf8' });
        if (otool.error || otool.status !== 0) { problems.push(`otool could not read ${f}`); continue; }
        machOChecked++;
        // LC_BUILD_VERSION's "minos", or the older LC_VERSION_MIN_MACOSX's "version", per architecture.
        // (Only those two commands: LC_BUILD_VERSION's tool entries and LC_SOURCE_VERSION have "version" lines too.)
        const mins = otool.stdout.split(/^Load command \d+$/m).flatMap((block) => {
            const field = /^\s*cmd LC_BUILD_VERSION$/m.test(block) ? 'minos' : /^\s*cmd LC_VERSION_MIN_MACOSX$/m.test(block) ? 'version' : null;
            const m = field && block.match(new RegExp(`^\\s*${field} (\\d+(?:\\.\\d+)*)$`, 'm'));
            return m ? [m[1]] : [];
        });
        const worst = mins.reduce((w, v) => (newer(v, w) ? v : w), '0');
        if (newer(worst, MAX_MACOS)) tooNew.push(`${f} (macOS ${worst})`);
    }
    if (tooNew.length) problems.push(`${tooNew.length} native file(s) need a macOS later than ${MAX_MACOS}: ${tooNew.slice(0, 5).join(', ')}`);
}

// The first line of a licence text that has a letter or digit in it, without comment marks or indentation.
function firstLine(text) {
    const line = text.split(/\r?\n/).find((l) => /[A-Za-z0-9]/.test(l)) ?? '';
    return line.replace(/^[\s/*#=-]+/, '').trim();
}
// How each licence's notice file starts (and, where the first line is only a copyright line, a phrase
// it must contain). A licence not in this table is not releasable until it is added here.
const NOTICE_TEXTS = {
    'Apache-2.0': { first: 'Apache License', contains: 'Version 2.0, January 2004' },
    'LGPL-2.1-or-later': { first: 'GNU LESSER GENERAL PUBLIC LICENSE', contains: 'Version 2.1, February 1999' },
    'PSF-2.0': { first: 'A. HISTORY OF THE SOFTWARE' },
    'LicenseRef-Microsoft-Distributable-Code': { first: 'A. HISTORY OF THE SOFTWARE', contains: 'Microsoft Distributable Code' },
    MIT: { first: /^(libffi - )?Copyright /, contains: 'Permission is hereby granted, free of charge' },
    'BSD-2-Clause': { first: /^Copyright /, contains: 'Redistribution and use in source and binary forms' },
    'BSD-2-Clause AND LicenseRef-public-domain': { first: 'The source code in PyCryptodome is partially in the public domain' },
    'bzip2-1.0.6': { first: 'This program, "bzip2", the associated library "libbzip2", and all' },
    Zlib: { first: 'Copyright notice:' },
    'GPL-2.0-or-later WITH Bootloader-exception': { first: 'The PyInstaller licensing terms', contains: 'Bootloader Exception' },
    // Public domain needs no notice; such an entry may have none.
    'LicenseRef-public-domain': null,
};
// Native libraries: shared libraries and Python extension modules, and the macOS Python framework binary.
const isNative = (f) => /\.(dylib|so|dll|pyd)$|\.so\.\d/.test(f) || /Python\.framework\/Versions\/[^/]+\/Python$/.test(f);

let info = null;
try { info = JSON.parse(readFileSync(path.join(dir, 'BUNDLE-INFO.json'), 'utf8')); } catch { problems.push('BUNDLE-INFO.json missing or unreadable'); }
if (info) {
    if (typeof info.loaders !== 'string' || !info.loaders.startsWith('none')) problems.push(`BUNDLE-INFO.json "loaders" is ${JSON.stringify(info.loaders)}, not "none; …" (a bundle from before the download agent was fetched at first use)`);
    if ('preloaders' in info) problems.push('BUNDLE-INFO.json has a "preloaders" list: the bundle carries no preloader images');

    if (!Array.isArray(info.nativeLibraries)) problems.push('BUNDLE-INFO.json has no "nativeLibraries" mapping');
    else {
        const mapped = new Set(info.nativeLibraries.map((e) => e?.file));
        const unmapped = bundleFiles.filter((f) => f.startsWith('_internal/') && isNative(f) && !mapped.has(f));
        if (unmapped.length) problems.push(`${unmapped.length} native librar${unmapped.length === 1 ? 'y has' : 'ies have'} no nativeLibraries entry: ${unmapped.slice(0, 5).join(', ')}`);
        const noticeProblems = new Set();
        for (const e of info.nativeLibraries) {
            const label = `nativeLibraries entry ${JSON.stringify(e?.file)} (${e?.project})`;
            if (!e || typeof e.file !== 'string' || !bundleFiles.includes(e.file)) { problems.push(`${label}: no such file in the bundle`); continue; }
            if (typeof e.project !== 'string' || typeof e.version !== 'string' || !e.version) { problems.push(`${label}: project or version missing`); continue; }
            if (!(e.licence in NOTICE_TEXTS)) { problems.push(`${label}: licence ${JSON.stringify(e.licence)} is not in the gate's notice table`); continue; }
            const expected = NOTICE_TEXTS[e.licence];
            if (expected === null && e.notice == null) continue;
            const key = `${e.licence}|${e.notice}`;
            if (noticeProblems.has(key)) continue;
            const noticePath = typeof e.notice === 'string' ? path.join(dir, e.notice) : null;
            let why = null;
            if (!noticePath || !existsSync(noticePath)) why = `notice file ${JSON.stringify(e.notice)} is missing`;
            else {
                const text = readFileSync(noticePath, 'utf8');
                const first = firstLine(text);
                const want = expected?.first;
                const firstOk = want instanceof RegExp ? want.test(first) : first === want;
                if (want && !firstOk) why = `notice ${e.notice} starts "${first}", not the ${e.licence} text ("${want}")`;
                else if (expected?.contains && !text.includes(expected.contains)) why = `notice ${e.notice} does not contain "${expected.contains}"`;
            }
            if (why) { noticeProblems.add(key); problems.push(`${label}: ${why}`); }
        }
    }

    // scripts/mtkclient-pin.json is the single source of truth for which mtkclient sources go
    // into the bundle; the version must end with the first 7 characters of its commit.
    let commit = null;
    try {
        const pinned = JSON.parse(readFileSync(pinPath, 'utf8'))?.commit;
        if (typeof pinned === 'string' && /^[0-9a-f]{40}$/.test(pinned)) commit = pinned;
    } catch { /* named below */ }
    if (!commit) problems.push(`${pinPath} is missing, unreadable or has no 40-character commit`);
    else if (typeof info.version !== 'string' || !info.version.endsWith(`+${commit.slice(0, 7)}`)) problems.push(`BUNDLE-INFO.json version ${JSON.stringify(info.version)} is not built from mtkclient commit ${commit.slice(0, 7)} (scripts/mtkclient-pin.json)`);
    if (info.tool !== 'mtkclient') problems.push('BUNDLE-INFO.json does not name mtkclient');
    if (!(info.patches >= 9)) problems.push(`BUNDLE-INFO.json reports ${info.patches} patches; expected at least 9`);
    const lockSha = createHash('sha256').update(readFileSync(lockPath)).digest('hex');
    if (info.lockSha256 !== lockSha) problems.push('BUNDLE-INFO.json lockSha256 does not match scripts/mtkclient-requirements.txt');
}

// The capability probe, run on this host. The gate line must match the runners' IDENTITY_GATE_LINE.
const IDENTITY_GATE_LINE = 'Studio Pocket - identity gate 1';
let probed = false;
if (process.env.MTK_BUNDLE_SKIP_PROBE !== '1') {
    const exe = path.join(dir, process.platform === 'win32' ? 'mtk.exe' : 'mtk');
    const probeEnv = { ...process.env, STUDIO_POCKET_PROBE: '1' };
    const probe = spawnSync(exe, [], { env: probeEnv, encoding: 'utf8', timeout: 30000 });
    if (probe.error) problems.push(`the bundled mtk could not be run (${probe.error.message}): the bundle is not usable on this host`);
    else {
        const answer = (probe.stdout ?? '').trim();
        if (probe.status !== 0 || answer !== IDENTITY_GATE_LINE) {
            problems.push(`the bundled mtk did not answer the capability probe (exit ${probe.status}, stdout ${JSON.stringify(answer)}): the bundle is not patched`);
        }
        const plainEnv = { ...process.env };
        delete plainEnv.STUDIO_POCKET_PROBE;
        const usage = spawnSync(exe, [], { env: plainEnv, encoding: 'utf8', timeout: 30000 });
        if (usage.error) problems.push(`the bundled mtk could not be run (${usage.error.message}): the bundle is not usable on this host`);
        else if (usage.status !== 2) problems.push(`the bundled mtk with no arguments exited ${usage.status}, expected 2 (usage)`);
        else probed = problems.length === 0;
    }
}

if (problems.length) {
    console.error(`mtkclient bundle in ${dir} is not releasable:\n  - ${problems.join('\n  - ')}`);
    process.exit(1);
}
if (info) {
    const natives = bundleFiles.filter((f) => f.startsWith('_internal/') && isNative(f)).length;
    const notices = new Set(info.nativeLibraries.map((e) => e.notice).filter(Boolean)).size;
    console.log('no WinFsp files');
    console.log('no Loader/ files: no download agent or preloader in the bundle');
    console.log(`native libraries ok: ${natives} in _internal/ mapped, ${notices} notice files checked`);
}
if (machOChecked) console.log(`macOS ${MAX_MACOS} or later: all ${machOChecked} Mach-O files`);
if (probed) console.log('probe ok');
console.log(`mtkclient bundle ok: ${dir}`);
