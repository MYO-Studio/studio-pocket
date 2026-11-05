#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

# Copies an uncompressed tar archive without the members named in a list, block for block: every other
# header (the pax global header included) and every other member's data are copied unchanged, so
# what is left is byte for byte the original's. A member's pax extended header or GNU long-name
# header goes with it. Fails if a listed name is not in the archive.
#
# Usage: tar-remove-members.py <in.tar> <names.txt> <out.tar>     (one member name per line)
# release.yml uses it to leave out of mtkclient's source archive the files no bundle ships.
import sys

src, names_file, dst = sys.argv[1:4]
with open(names_file, encoding='utf-8') as f:
    remove = {line.rstrip('\n') for line in f if line.strip()}
data = open(src, 'rb').read()
out = bytearray()
removed = set()
off = 0
pending = bytearray()   # pax 'x' / GNU 'L' 'K' headers that belong to the next member
long_name = None


def field(h, start, length):
    return h[start:start + length].split(b'\0', 1)[0].decode('utf-8')


while off + 512 <= len(data):
    header = data[off:off + 512]
    if header == b'\0' * 512:
        break
    size = int(header[124:136].split(b'\0', 1)[0].strip() or b'0', 8)
    end = off + 512 + (size + 511) // 512 * 512
    typeflag = header[156:157]
    block = data[off:end]
    off = end
    if typeflag == b'g':                       # pax global header: archive-wide, always kept
        out += block
        continue
    if typeflag in (b'x', b'L', b'K'):
        pending += block
        payload = block[512:512 + size]
        if typeflag == b'x':
            for record in payload.decode('utf-8').splitlines():
                key, _, value = record.partition(' ')[2].partition('=')
                if key == 'path':
                    long_name = value
        elif typeflag == b'L':
            long_name = payload.split(b'\0', 1)[0].decode('utf-8')
        continue
    name = long_name
    if name is None:
        prefix = field(header, 345, 155) if header[257:262] == b'ustar' else ''
        name = (prefix + '/' if prefix else '') + field(header, 0, 100)
    if name in remove:
        removed.add(name)
    else:
        out += pending + block
    pending = bytearray()
    long_name = None

out += data[off:]                              # the end-of-archive blocks, as they were
missing = remove - removed
if missing:
    sys.exit('not in the archive: ' + ', '.join(sorted(missing)))
open(dst, 'wb').write(out)
print(f'{len(removed)} members removed')
