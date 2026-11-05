#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
# commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

"""Compare a system partition read back from a Y2 with another image of the same build at the file level.

A byte-for-byte match is not realistic: the factory writes its own ext4 generation of the same
build (different UUID, different block placement) and the partition gets mounted. What matters
for the installer is that the *content* is the same build: build.prop identical and every
regular file identical. Needs e2fsprogs' debugfs (keg-only on Homebrew).

Usage: scripts/compare-system-images.py <device.img> <stock.img> [--fs-offset BYTES]
Exit 0 = same build, 1 = differs, 2 = could not compare.
"""
import argparse
import glob
import hashlib
import os
import re
import struct
import subprocess
import sys

LS_RX = re.compile(r"\s*(\d+)\s+(\d+)\s+\(\d+\)\s+\d+\s+\d+\s+(\d+)\s+\S+\s+\S+\s+(.+)$")
IGNORE_DIRS = {".", "..", "lost+found"}


def find_debugfs():
    for cand in ["/opt/homebrew/opt/e2fsprogs/sbin/debugfs", "/usr/local/opt/e2fsprogs/sbin/debugfs",
                 "/usr/sbin/debugfs", "/sbin/debugfs"] + glob.glob("/opt/homebrew/Cellar/e2fsprogs/*/sbin/debugfs"):
        if os.path.exists(cand):
            return cand
    found = subprocess.run(["which", "debugfs"], capture_output=True, text=True).stdout.strip()
    return found or None


def superblock(path, offset):
    with open(path, "rb") as fh:
        fh.seek(offset + 0x400)
        s = fh.read(1024)
    if len(s) < 1024 or struct.unpack_from("<H", s, 0x38)[0] != 0xEF53:
        return None
    blocks = struct.unpack_from("<I", s, 4)[0]
    log_bs = struct.unpack_from("<I", s, 0x18)[0]
    mnt_count = struct.unpack_from("<H", s, 0x34)[0]
    return {"blocks": blocks, "block_size": 1024 << log_bs, "uuid": s[0x68:0x78].hex(),
            "mount_count": mnt_count, "kb_written": struct.unpack_from("<Q", s, 0x178)[0]}


def find_fs_offset(path):
    """First ext4 superblock at 64 KiB alignment (a raw-offset read may start before the partition)."""
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        for off in range(0, min(size, 64 << 20), 1 << 16):
            fh.seek(off + 0x400 + 0x38)
            if fh.read(2) == b"\x53\xef":
                return off
    return None


def extract(path, offset, out):
    with open(path, "rb") as src, open(out, "wb") as dst:
        src.seek(offset)
        while True:
            chunk = src.read(1 << 24)
            if not chunk:
                break
            dst.write(chunk)


def listing(debugfs, img):
    files = {}
    stack = ["/"]
    while stack:
        d = stack.pop()
        out = subprocess.run([debugfs, "-R", f"ls -l {d}", img], capture_output=True, text=True).stdout
        for line in out.splitlines():
            m = LS_RX.match(line)
            if not m:
                continue
            _ino, mode, size, name = m.groups()
            name = name.strip()
            if name in IGNORE_DIRS:
                continue
            path = d.rstrip("/") + "/" + name
            kind = int(mode, 8) & 0o170000
            if kind == 0o040000:
                stack.append(path)
            elif kind == 0o100000:
                files[path] = int(size)
    return files


def sha(debugfs, img, path):
    data = subprocess.run([debugfs, "-R", f"cat {path}", img], capture_output=True).stdout
    return hashlib.sha256(data).hexdigest(), len(data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("device")
    ap.add_argument("stock")
    ap.add_argument("--fs-offset", type=int, default=None, help="byte offset of the ext4 filesystem inside device.img")
    ap.add_argument("--keep", action="store_true", help="keep the extracted filesystem next to device.img")
    ap.add_argument("--window-start", type=lambda v: int(v, 0), default=None,
                    help="device (linear) address where device.img starts; enables the layout output")
    ap.add_argument("--layout-out", default=None, help="write the measured ANDROID/CACHE layout as JSON here")
    args = ap.parse_args()

    debugfs = find_debugfs()
    if not debugfs:
        print("debugfs not found (brew install e2fsprogs)"); return 2

    off = args.fs_offset if args.fs_offset is not None else find_fs_offset(args.device)
    if off is None:
        print("no ext4 superblock found in the first 64 MiB of the device image"); return 2
    dev_sb = superblock(args.device, off)
    stock_sb = superblock(args.stock, 0)
    if not dev_sb or not stock_sb:
        print("could not parse a superblock"); return 2
    print(f"device filesystem starts at offset {off:#x} in {os.path.basename(args.device)}")
    print(f"  device: blocks={dev_sb['blocks']} uuid={dev_sb['uuid']} mounts={dev_sb['mount_count']} kb_written={dev_sb['kb_written']}")
    print(f"  stock : blocks={stock_sb['blocks']} uuid={stock_sb['uuid']}")
    if dev_sb["blocks"] != stock_sb["blocks"] or dev_sb["block_size"] != stock_sb["block_size"]:
        print("DIFFERENT: filesystem geometry differs"); return 1
    captured = os.path.getsize(args.device) - off
    expected = dev_sb["blocks"] * dev_sb["block_size"]
    if captured < expected:
        print(f"  note: read-back holds {captured} of {expected} filesystem bytes (read started before the partition)")

    # Layout: the partition is the filesystem's extent, confirmed by the NEXT partition's
    # superblock (CACHE is ext4 on the Y2) sitting exactly at the end. Both markers are needed
    # before any offset is trusted for a write.
    if args.window_start is not None:
        start = args.window_start + off
        end = start + expected
        nxt = superblock(args.device, off + expected) if captured >= expected + 0x800 else None
        if nxt:
            print(f"  layout: ANDROID {start:#x} + {expected:#x} = {end:#x}; next partition (CACHE) superblock at {end:#x}, "
                  f"{nxt['blocks'] * nxt['block_size']:#x} bytes: BOTH MARKERS PRESENT")
        else:
            print(f"  layout: ANDROID starts at {start:#x}; the end marker at {end:#x} is NOT in this read "
                  f"(read {captured:#x} of {expected:#x}+ bytes): not enough to trust the size for a write")
        if args.layout_out:
            import json
            layout = {
                "measured": __import__("datetime").date.today().isoformat(),
                "method": "read-only mtkclient read; addresses are mtkclient linear user-area offsets",
                "window_start": f"{args.window_start:#x}",
                "partitions": {"ANDROID": {"start": f"{start:#x}", "size": f"{expected:#x}", "end": f"{end:#x}"}},
                "end_marker": ({"CACHE": {"start": f"{end:#x}", "size": f"{nxt['blocks'] * nxt['block_size']:#x}"}}
                               if nxt else None),
                "trusted_for_write": bool(nxt),
            }
            with open(args.layout_out, "w") as fh:
                json.dump(layout, fh, indent=2)
            print(f"  layout written to {args.layout_out} (trusted_for_write={bool(nxt)})")

    dev_img = args.device if off == 0 else os.path.splitext(args.device)[0] + ".fs.img"
    if off != 0:
        extract(args.device, off, dev_img)

    try:
        dev_prop = subprocess.run([debugfs, "-R", "cat build.prop", dev_img], capture_output=True).stdout
        stock_prop = subprocess.run([debugfs, "-R", "cat build.prop", args.stock], capture_output=True).stdout
        if dev_prop != stock_prop or not dev_prop:
            print("DIFFERENT: build.prop differs"); return 1
        build = re.search(rb"ro.build.version.incremental=(\S+)", dev_prop)
        print(f"  build.prop identical (incremental {build.group(1).decode() if build else '?'})")

        dev_files = listing(debugfs, dev_img)
        stock_files = listing(debugfs, args.stock)
        only_dev = sorted(set(dev_files) - set(stock_files))
        only_stock = sorted(set(stock_files) - set(dev_files))
        changed = []
        truncated = []
        for p in sorted(set(dev_files) & set(stock_files)):
            hd, ld = sha(debugfs, dev_img, p)
            hs, ls_ = sha(debugfs, args.stock, p)
            if hd != hs:
                if ld < dev_files[p] or ls_ < stock_files[p]:
                    truncated.append(p)  # blocks beyond what either image holds
                else:
                    changed.append(p)
        print(f"  files: {len(stock_files)} stock, {len(dev_files)} device, {len(changed)} differ, "
              f"{len(only_dev)} only on device, {len(only_stock)} only in stock, {len(truncated)} unreadable in one image")
        for p in changed[:20]:
            print(f"    differs: {p}")
        for p in only_dev[:20]:
            print(f"    only on device: {p}")
        for p in only_stock[:20]:
            print(f"    only in stock: {p}")
        for p in truncated[:10]:
            print(f"    unreadable (beyond image end): {p}")
        important = [p for p in changed + only_dev + only_stock if not p.startswith("/media/")]
        if important:
            print("DIFFERENT: system content is not the archive build"); return 1
        print("SAME BUILD: the device's system content matches the archive image"
              + (" (media files differ only)" if changed or only_dev or only_stock else ""))
        return 0
    finally:
        if off != 0 and not args.keep and os.path.exists(dev_img):
            os.remove(dev_img)


if __name__ == "__main__":
    sys.exit(main())
