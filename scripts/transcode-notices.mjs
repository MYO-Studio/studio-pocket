#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

// Lists everything that is linked into studio-pocket-transcode (tools/transcode), copies each
// part's licence texts into a bundle's licences/ folder, and prints the list as JSON for
// BUNDLE-INFO.json's "components". Fails on a linked crate that is not in REVIEWED below, or whose
// declared licence has changed, so a new or relicensed dependency cannot ship without a decision.
//
// Run by scripts/build-transcode.sh: node scripts/transcode-notices.mjs <bundle dir> <target triple>
//
// "Linked" is what cargo resolves as the program's normal dependencies for that target, to any
// depth; build-time crates (cc, cmake) and the tests' are not in the program. Two of the crates
// carry C source that is compiled in: mp3lame-sys has LAME and opusic-sys has libopus, each with
// its own licence and version, read here from the source that cargo unpacked.
//
// licences/ afterwards:
//   GPL-3.0.txt       the program itself (and the licence that LGPL-3.0 adds permissions to)
//   LGPL-3.0.txt      mp3lame-sys, the bindings to LAME
//   lame/             LAME's COPYING (the GNU Library General Public License 2) and its LICENSE note
//   libopus/          libopus's COPYING (BSD-3-Clause, with the patent licences it names) and AUTHORS
//   <crate>/          the licence files each other crate ships
//   rust/             the Rust standard library's (scripts/licences/rust/)
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Every crate that may be linked in, with the licence it declared when it was reviewed.
const REVIEWED = {
    'ogg': 'BSD-3-Clause',
    'byteorder': 'Unlicense OR MIT',
    'opusic-sys': 'BSD-3-Clause',
    'mp3lame-sys': 'LGPL-3.0',
};
const LICENCE_FILE = /^(LICEN[CS]E|COPYING|UNLICENSE|NOTICE|COPYRIGHT)/i;

const [bundle, triple] = process.argv.slice(2);
if (!bundle || !triple) {
    console.error('usage: node scripts/transcode-notices.mjs <bundle dir> <target triple>');
    process.exit(2);
}
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const crate = path.join(root, 'tools/transcode');
const vendored = path.join(root, 'scripts/licences');
const licences = path.join(bundle, 'licences');
const problems = [];

/** Copies one licence text to licences/<to>; `mustContain` guards against a file that is not that text. */
function copy(from, to, mustContain) {
    if (!existsSync(from)) return problems.push(`${from} is missing`);
    if (mustContain && !readFileSync(from, 'utf8').includes(mustContain)) return problems.push(`${from} is not the text expected ("${mustContain}")`);
    mkdirSync(path.dirname(path.join(licences, to)), { recursive: true });
    copyFileSync(from, path.join(licences, to));
    return to;
}

const run = (command, args) => execFileSync(command, args, { cwd: crate, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const metadata = JSON.parse(run('cargo', ['metadata', '--format-version', '1', '--locked', '--filter-platform', triple]));
const packages = new Map(metadata.packages.map((p) => [p.id, p]));
const nodes = new Map(metadata.resolve.nodes.map((n) => [n.id, n]));

// Cargo.lock's checksum of each crate file: the SHA-256 of https://static.crates.io/crates/<name>/<name>-<version>.crate.
const checksums = new Map();
for (const block of readFileSync(path.join(crate, 'Cargo.lock'), 'utf8').split('[[package]]')) {
    const field = (name) => block.match(new RegExp(`^${name} = "(.*)"$`, 'm'))?.[1];
    if (field('checksum')) checksums.set(`${field('name')} ${field('version')}`, field('checksum'));
}

// The program's normal dependencies, to any depth. `kind` is null for a normal dependency.
const linked = new Set();
(function walk(id) {
    for (const dep of nodes.get(id).deps) {
        if (dep.dep_kinds.some((k) => k.kind === null) && !linked.has(dep.pkg)) {
            linked.add(dep.pkg);
            walk(dep.pkg);
        }
    }
})(metadata.resolve.root);

const program = packages.get(metadata.resolve.root);
const components = [];
copy(path.join(vendored, 'GPL-3.0/GPL-3.0.txt'), 'GPL-3.0.txt', 'GNU GENERAL PUBLIC LICENSE');

for (const pkg of [...linked].map((id) => packages.get(id)).sort((a, b) => a.name.localeCompare(b.name))) {
    const dir = path.dirname(pkg.manifest_path);
    const sha256 = checksums.get(`${pkg.name} ${pkg.version}`);
    if (!(pkg.name in REVIEWED)) {
        problems.push(`${pkg.name} ${pkg.version} (${pkg.license}) is linked in but not reviewed: add it to REVIEWED in scripts/transcode-notices.mjs and to docs/licensing/third-party.md`);
        continue;
    }
    if (pkg.license !== REVIEWED[pkg.name]) problems.push(`${pkg.name} ${pkg.version} declares "${pkg.license}", reviewed as "${REVIEWED[pkg.name]}"`);
    if (!sha256) problems.push(`${pkg.name} ${pkg.version} has no checksum in Cargo.lock: it must come from crates.io`);
    const source = `https://static.crates.io/crates/${pkg.name}/${pkg.name}-${pkg.version}.crate`;

    let notices;
    if (pkg.name === 'mp3lame-sys') {
        // The crate's own LICENSE is the LGPL-3.0 text; the bundle carries the vendored copy of it.
        const own = path.join(dir, 'LICENSE');
        // Compared as text with line endings set aside: on Windows the vendored copy is checked out with CRLF.
        const text = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
        if (!existsSync(own) || text(own) !== text(path.join(vendored, 'LGPL-3.0.txt'))) {
            problems.push(`${own} is not the LGPL-3.0 text in scripts/licences/LGPL-3.0.txt`);
        }
        notices = [copy(path.join(vendored, 'LGPL-3.0.txt'), 'LGPL-3.0.txt', 'GNU LESSER GENERAL PUBLIC LICENSE'), 'GPL-3.0.txt'];
    } else {
        const files = readdirSync(dir).filter((f) => LICENCE_FILE.test(f)).sort();
        if (!files.length) problems.push(`${pkg.name} ${pkg.version} ships no licence file`);
        notices = files.map((f) => copy(path.join(dir, f), `${pkg.name}/${f}`));
    }
    components.push({ name: pkg.name, version: pkg.version, kind: 'Rust crate', licence: pkg.license, source, sha256, notices });

    if (pkg.name === 'mp3lame-sys') {
        const lame = readdirSync(dir).filter((f) => /^lame-/.test(f));
        if (lame.length !== 1) {
            problems.push(`expected one lame-* folder in ${dir}, found ${lame.length}`);
            continue;
        }
        const header = readFileSync(path.join(dir, lame[0], 'libmp3lame/version.h'), 'utf8');
        const number = (name) => header.match(new RegExp(`#\\s*define\\s+LAME_${name}_VERSION\\s+(\\d+)`))?.[1];
        const version = [number('MAJOR'), number('MINOR')].join('.') + (number('PATCH') !== '0' ? `.${number('PATCH')}` : '');
        components.push({
            name: 'LAME (libmp3lame)',
            version,
            kind: `C library, compiled from the source in ${pkg.name} ${pkg.version} (${lame[0]}/) and linked statically`,
            licence: 'LGPL-2.0-or-later',
            source,
            sha256,
            notices: [
                copy(path.join(dir, lame[0], 'COPYING'), 'lame/COPYING', 'GNU LIBRARY GENERAL PUBLIC LICENSE'),
                copy(path.join(dir, lame[0], 'LICENSE'), 'lame/LICENSE', 'LGPL'),
            ],
        });
    }
    if (pkg.name === 'opusic-sys') {
        const version = readFileSync(path.join(dir, 'opus/package_version'), 'utf8').match(/PACKAGE_VERSION="(.+)"/)?.[1];
        if (!version) problems.push(`no version in ${dir}/opus/package_version`);
        components.push({
            name: 'libopus',
            version,
            kind: `C library, compiled from the source in ${pkg.name} ${pkg.version} (opus/, with the crate's opus.patch to its build file) and linked statically`,
            licence: 'BSD-3-Clause',
            source,
            sha256,
            notices: [
                copy(path.join(dir, 'opus/COPYING'), 'libopus/COPYING', 'Redistribution and use in source and binary forms'),
                copy(path.join(dir, 'opus/AUTHORS'), 'libopus/AUTHORS'),
            ],
        });
    }
}

for (const name of Object.keys(REVIEWED)) {
    if (![...linked].some((id) => packages.get(id).name === name)) problems.push(`${name} is in REVIEWED but no longer linked in: remove it there and from docs/licensing/third-party.md`);
}

components.push({
    name: 'Rust standard library',
    version: run('rustc', ['--version']).trim(),
    kind: 'linked statically by rustc',
    licence: 'MIT OR Apache-2.0',
    source: 'https://github.com/rust-lang/rust',
    notices: ['COPYRIGHT', 'LICENSE-MIT', 'LICENSE-APACHE'].map((f) => copy(path.join(vendored, 'rust', f), `rust/${f}`)),
});

if (problems.length) {
    console.error(`studio-pocket-transcode ${program.version}: licence notices are incomplete:\n  ${problems.join('\n  ')}`);
    process.exit(1);
}
console.log(JSON.stringify(components, null, 2));
