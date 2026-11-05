#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Maps every native library PyInstaller collected into an mtkclient bundle to its project, version,
# licence and notice file, copies the notice texts the bundle does not have yet into its licences/,
# and prints the mapping as JSON for BUNDLE-INFO.json's "nativeLibraries". Fails on a native file no
# rule covers, so a new library cannot ship without a decision about its licence.
#
# Run by scripts/build-mtkclient.sh with the venv's interpreter (the bundled CPython is that one's base,
# so its version answers are the bundle's): <python> -I mtkclient-notices.py <bundle> <scripts/licences>
#
# A file can carry more than one project (a CPython module with a library linked in statically), so a
# file can have more than one entry. scripts/check-mtkclient-bundle.mjs checks the result.
import json
import os
import re
import shutil
import sys
from importlib import metadata

bundle, vendored = sys.argv[1], sys.argv[2]
internal = os.path.join(bundle, '_internal')
windows = os.path.exists(os.path.join(bundle, 'mtk.exe'))
problems = []
entries = []


def version_of(pattern, path, default='unknown'):
    """The first match of a bytes pattern's group 1 in a binary, for libraries with no runtime query."""
    with open(path, 'rb') as f:
        m = re.search(pattern, f.read())
    return m.group(1).decode('ascii') if m else default


def notice(family, name):
    """licences/<family>/<name> in the bundle, copied from scripts/licences/<family>/ if not there yet."""
    rel = f'licences/{family}/{name}'
    dest = os.path.join(bundle, rel)
    if not os.path.exists(dest):
        src = os.path.join(vendored, family, name)
        if not os.path.isfile(src):
            problems.append(f'no licence text for {family}: {src} is missing')
            return rel
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copyfile(src, dest)
    return rel


def add(file, project, version, licence, notice_file):
    entries.append({'file': file, 'project': project, 'version': version, 'licence': licence, 'notice': notice_file})


import ssl  # noqa: E402  (after the helpers: these imports are the versions being recorded)
import pyexpat  # noqa: E402
import decimal  # noqa: E402
import zlib  # noqa: E402

PYTHON = sys.version.split()[0]
PSF = 'licences/python-LICENSE.txt'
OPENSSL = (re.search(r'OpenSSL (\S+)', ssl.OPENSSL_VERSION) or [None, 'unknown'])[1]
MS_RUNTIME = re.compile(r'^(vcruntime140(_\d)?|msvcp140(_\w+)?|concrt140|ucrtbase|api-ms-win-[\w-]+)\.dll$', re.I)

native = []
for root, dirs, files in os.walk(internal):
    dirs.sort()
    for name in sorted(files):
        path = os.path.join(root, name)
        rel = os.path.relpath(path, bundle).replace(os.sep, '/')
        if os.path.islink(path):
            continue
        if re.search(r'\.(dylib|so|dll|pyd)$|\.so\.\d', name) or re.search(r'Python\.framework/Versions/[^/]+/Python$', rel):
            native.append((rel, name, path))

for rel, name, path in native:
    lower = name.lower()
    stem = lower.split('.')[0]
    if lower.startswith('winfsp'):
        problems.append(f'{rel}: WinFsp must not be in the bundle (build-mtkclient.sh prunes it)')
    elif rel.startswith('_internal/Crypto/'):
        add(rel, 'pycryptodome', metadata.version('pycryptodome'), 'BSD-2-Clause AND LicenseRef-public-domain', 'licences/pycryptodome-LICENSE.rst')
    elif rel.startswith('_internal/Cryptodome/'):
        add(rel, 'pycryptodomex', metadata.version('pycryptodomex'), 'BSD-2-Clause AND LicenseRef-public-domain', 'licences/pycryptodomex-LICENSE.rst')
    elif lower.startswith(('libssl', 'libcrypto')):
        add(rel, 'OpenSSL', OPENSSL, 'Apache-2.0', notice('openssl', 'LICENSE.txt'))
    elif lower.startswith('libusb'):
        add(rel, 'libusb', os.environ.get('LIBUSB_VERSION') or 'unknown', 'LGPL-2.1-or-later', 'licences/libusb/COPYING')
    elif lower.startswith('libffi'):
        add(rel, 'libffi', f'as shipped with CPython {PYTHON}', 'MIT', notice('libffi', 'LICENSE'))
    elif MS_RUNTIME.match(name):
        # Microsoft's Visual C++ and Universal C runtimes, redistributed with the Windows Python build;
        # its LICENSE.txt carries the terms ("Additional Conditions for this Windows binary build").
        add(rel, 'Microsoft Visual C++ / Universal C Runtime', 'as shipped with CPython ' + PYTHON, 'LicenseRef-Microsoft-Distributable-Code', PSF)
    elif re.match(r'^python3\d*\.dll$', lower) or rel.endswith('/Python') or '/lib-dynload/' in rel or (rel.count('/') == 1 and lower.endswith('.pyd')):
        add(rel, 'CPython', PYTHON, 'PSF-2.0', PSF)
        # Libraries the Python build links in statically (macOS python.org and Windows builds alike,
        # unless noted); the rest (macOS: bzip2, zlib, libffi) are the operating system's, not bundled.
        if stem == '_lzma':
            xz = version_of(rb'\x00(5\.\d+\.\d+)\x00', path)
            if xz != 'unknown' and tuple(int(x) for x in xz.split('.')) >= (5, 6, 0):
                problems.append(f'{rel}: xz {xz} is 0BSD; add its COPYING.0BSD to scripts/licences/xz/ and a rule here')
            # liblzma before 5.6 is in the public domain: no notice is required.
            add(rel, 'xz (liblzma)', xz, 'LicenseRef-public-domain', None)
        elif stem == 'pyexpat':
            add(rel, 'expat', pyexpat.EXPAT_VERSION.replace('expat_', ''), 'MIT', notice('expat', 'COPYING'))
        elif stem == '_decimal':
            add(rel, 'mpdecimal (libmpdec)', decimal.__libmpdec_version__, 'BSD-2-Clause', notice('mpdecimal', 'LICENSE.txt'))
        elif stem == '_bz2' and windows:
            add(rel, 'bzip2', version_of(rb'(1\.0\.\d+), \d+-\w+-\d+', path), 'bzip2-1.0.6', notice('bzip2', 'LICENSE'))
        elif stem == '_sqlite3':
            import sqlite3
            add(rel, 'SQLite', sqlite3.sqlite_version, 'LicenseRef-public-domain', None)
        elif re.match(r'^python3\d*\.dll$', lower):
            add(rel, 'zlib', zlib.ZLIB_VERSION, 'Zlib', notice('zlib', 'LICENSE'))
    else:
        problems.append(f'{rel}: native library with no licence rule in scripts/mtkclient-notices.py')

# The executable itself is PyInstaller's bootloader (GPL-2.0-or-later with the bootloader exception).
exe = 'mtk.exe' if windows else 'mtk'
dist = metadata.distribution('pyinstaller')
copying = next((f for f in dist.files or [] if str(f).endswith('COPYING.txt')), None)
if copying is None:
    problems.append('the PyInstaller distribution has no COPYING.txt')
else:
    dest = os.path.join(bundle, 'licences', 'pyinstaller-COPYING.txt')
    shutil.copyfile(dist.locate_file(copying), dest)
    add(exe, 'PyInstaller bootloader', dist.version, 'GPL-2.0-or-later WITH Bootloader-exception', 'licences/pyinstaller-COPYING.txt')

if problems:
    sys.stderr.write('native library notices:\n  - ' + '\n  - '.join(problems) + '\n')
    sys.exit(1)
json.dump(entries, sys.stdout, indent=2)
sys.stdout.write('\n')
