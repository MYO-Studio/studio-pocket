#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Ashworth Digital Ltd
# This file quotes and modifies code from mtkclient (https://github.com/bkerler/mtkclient), Copyright (C) B. Kerler, GPL-3.0-only.
# It is licensed under GPL-3.0-only alone; the commercial licence offered for the rest of Studio Pocket does not cover it.
"""Apply the Studio Pocket patches to an installed mtkclient (bkerler/mtkclient at commit 7cb40e7, the
pin in scripts/mtkclient-pin.json) so it works with the Innioasis Y2 over the CDC serial port
(proven on macOS; the same serial path is what the desktop app uses on Windows).

Usage: python3 scripts/patch-mtkclient.py [path/to/site-packages/mtkclient]
Default target: firmware/tools/mtkclient-venv/lib/python3.*/site-packages/mtkclient

Each hunk is an exact-string replacement and is idempotent; the script fails loudly if upstream
has drifted so that a hunk no longer matches. Why each hunk exists is in
docs/development/flashing-macos.md ("mtkclient patches").
"""
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

HUNKS = {
    "Library/Connection/seriallib.py": [
        # 1. Idle detect loop: comports() costs hundreds of ms through IOKit on macOS, which made the
        #    loop miss the preloader's USB window. Cheap glob first; skip the scan when nothing is there.
        (
            '''    def detectdevices(self):
        ids = []
        for port in serial.tools.list_ports.comports():
''',
            '''    def detectdevices(self):
        ids = []
        # Studio Pocket patch (macOS): comports() takes hundreds of ms via IOKit, which made the
        # detect loop miss the preloader's short USB window. Cheap glob first; skip when empty.
        if sys.platform == "darwin":
            import glob as _glob
            if not _glob.glob("/dev/cu.usbmodem*") and not _glob.glob("/dev/cu.usbserial*"):
                return ids
        for port in serial.tools.list_ports.comports():
''',
        ),
        # 2. Single-byte echo reads (Port.echo -> usbread(1, maxtimeout=0)) returned empty on macOS CDC
        #    because the 20 ms minimum timeout is shorter than the preloader's reply latency. Give the
        #    echo reads a 0.5 s deadline; the handshake passes an explicit timeout and stays fast.
        (
            '''        retry_empty_reads = resplen is not None and resplen > 1
        read_deadline = (
            time.monotonic() + max(timeout, 0.25)
''',
            '''        # Studio Pocket patch (macOS CDC serial): the preloader's single-byte echo after the handshake
        # arrives later than the 20 ms minimum timeout, so one-byte reads were returning empty and
        # the sync step failed ("Sync error") although the device answered. Treat every read like a
        # multi-byte read: tolerate short empty gaps up to a 0.5 s deadline.
        # Only the echo reads (maxtimeout == 0) get the longer wait; the handshake passes an
        # explicit 20 ms and must stay fast because the preloader's window is ~0.3 s.
        slow_single = resplen == 1 and maxtimeout == 0
        retry_empty_reads = resplen is not None and (resplen > 1 or slow_single)
        read_deadline = (
            time.monotonic() + max(timeout, 0.5 if slow_single else 0.25)
''',
        ),
    ],
    "Library/Port.py": [
        # 3. Serial handshake: bail out on a dead port (upstream spun at 100 % CPU forever when the
        #    device rebooted under it), wait 100 ms per reply instead of 20 ms (a late reply used to
        #    be discarded and the start byte re-sent, which wedged the preloader's handshake state),
        #    drain stray bytes so the sync step is not misaligned, recognise an already-synced
        #    preloader (verbatim echo) and log the exchange.
        (
            '''    def run_serial_handshake(self):
''',
            '''    def _serial_drain(self, settle=0.02):
        """Studio Pocket patch: discard any bytes already waiting on the serial port."""
        try:
            dev = self.cdc.device
            time.sleep(settle)
            while dev.in_waiting:
                dev.read(dev.in_waiting)
                time.sleep(0.005)
        except Exception as err:
            self.debug(f"drain: {err}")

    def run_serial_handshake(self):
''',
        ),
        (
            '''        i = 0
        startcmd = b"\\xa0\\x0a\\x50\\x05"  # 5F F5 AF FA
        length = len(startcmd)
        try:
            while i < length:
                if ep_out(int.to_bytes(startcmd[i], 1, 'little')):
                    v = ep_in(1, timeout=20)  # Do not wait 1 sec, bootloader is only active for 0.3 sec.
                    if len(v) == 1 and v[0] == ~(startcmd[i]) & 0xFF:
                        i += 1
                    else:
                        i = 0
            self.info("Device detected :)")
            return True
''',
            '''        i = 0
        startcmd = b"\\xa0\\x0a\\x50\\x05"  # 5F F5 AF FA
        length = len(startcmd)
        # Studio Pocket patch (macOS CDC serial): bound the loop (it spun forever at 100% CPU with no
        # output) and recognise a preloader that already completed the handshake, which echoes the
        # command byte verbatim instead of its complement.
        misses = 0
        try:
            while i < length:
                if not ep_out(int.to_bytes(startcmd[i], 1, 'little')):
                    # Studio Pocket patch: a failed write means the port is gone (device rebooted);
                    # upstream looped here forever at 100% CPU without re-detecting the port.
                    return False
                else:
                    # Studio Pocket patch: 100 ms instead of 20 ms. A late reply used to be discarded and
                    # the start byte re-sent, which desynchronised the preloader's handshake state.
                    v = ep_in(1, timeout=100)
                    if len(v):
                        self.info(f"hs tx {startcmd[i]:02x} rx {v.hex()}")
                    if len(v) == 1 and v[0] == ~(startcmd[i]) & 0xFF:
                        i += 1
                    elif len(v) == 1 and v[0] == startcmd[i]:
                        self._serial_drain()
                        self.info("Device already in sync (verbatim echo), skipping handshake")
                        return True
                    else:
                        # A late or stray byte would otherwise be read as the next echo and leave
                        # the stream misaligned for the sync step that follows.
                        self._serial_drain()
                        i = 0
                        misses += 1
                        if misses > 150:
                            return False
            self._serial_drain()
            self.info("Device detected :)")
            return True
''',
        ),
        # 4. Poll for the port every 20 ms instead of 100 ms while waiting.
        (
            '''                    if loop >= 20:
                        sys.stdout.write('\\n')
                        loop = 0
                    loop += 1
                    time.sleep(0.1)
                    sys.stdout.flush()
''',
            '''                    if loop >= 20:
                        sys.stdout.write('\\n')
                        loop = 0
                    loop += 1
                    time.sleep(0.02)  # Studio Pocket patch: poll faster for the short preloader window
                    sys.stdout.flush()
''',
        ),
    ],
    "Library/DA/legacy/dalegacy_lib.py": [
        # 5. The legacy DA (mt6590 loader on the MT6582) takes well over 250 ms after the stage 2 ack
        #    before it sends the flash info, and pauses again before the PassInfo block. Upstream's
        #    default read deadline returned empty buffers, which shifted every later field (and for
        #    this eMMC-only device ended in a struct.error crash or "Failed to upload da").
        (
            '''    def read_flash_info(self):
        self.daconfig.legacy_storage.nor = Legacy_NorInfo(self.usbread(0x1C))
        data = self.usbread(0x11)
''',
            '''    def read_flash_info(self):
        # Studio Pocket patch: the legacy DA (mt6590 loader on the Innioasis Y2 / MT6582) needs well
        # over 250 ms after the stage 2 ack before it sends the flash info, and pauses again before
        # the PassInfo block. The default read deadline returned empty buffers and shifted every
        # later field. Captured layout on hardware 2026-10-19: NorInfo, NandInfo32 with 5 dev
        # codes, NandInfo2, EmmcInfo, SdcInfo, ConfigInfo, pause, PassInfo.
        norraw = self.usbread(0x1C, timeout=5)
        self.daconfig.legacy_storage.nor = Legacy_NorInfo(norraw)
        data = self.usbread(0x11, timeout=2)
        self.info(f"Flash info raw: nor={norraw.hex()} nand={data.hex()}")
''',
        ),
        (
            '''        pi = PassInfo(self.usbread(0xA))
        if pi.ack == 0x5A:
            return True
        elif pi.m_download_status & 0xFF == 0x5A:
            self.usbread(1)
            return True
        return False

    def upload_da1(self):''',
            '''        e = self.daconfig.legacy_storage.emmc
        self.info(f"Flash info: nand ids={self.daconfig.legacy_storage.nand.m_nand_flash_dev_code} "
                  f"emmc boot1={hex(e.m_emmc_boot1_size)} boot2={hex(e.m_emmc_boot2_size)} "
                  f"rpmb={hex(e.m_emmc_rpmb_size)} ua={hex(e.m_emmc_ua_size)} "
                  f"ram={hex(self.daconfig.legacy_storage.flashconfig.m_ext_ram_size)}")
        praw = self.usbread(0xA, timeout=8)
        self.info(f"Flash info: pass raw={praw.hex() if praw else '(none)'}")
        if len(praw) < 0xA:
            self.error("Flash info: no PassInfo from the DA within 8 s")
            return False
        pi = PassInfo(praw)
        if pi.ack == 0x5A:
            return True
        elif pi.m_download_status & 0xFF == 0x5A:
            self.usbread(1)
            return True
        return False

    def upload_da1(self):''',
        ),
        # 6. read_pmt (DA command 0xA5): upstream unpacks "<Q" from 4-byte slices (struct.error) in
        #    the non-0xFF layouts and leaves 32-bit names undecoded; also log the raw table. When the
        #    DA does not support the command (the Y2's mt6590 DA answers something else), drain what
        #    it sent, or the next read command sees a misaligned stream ("status: 0x5a015a0d").
        (
            '''        if self.usbwrite(self.Cmd.SDMMC_READ_PMT_CMD):
            ack = unpack(">B", self.usbread(1))[0]
            if ack == 0x5a:
''',
            '''        if self.usbwrite(self.Cmd.SDMMC_READ_PMT_CMD):
            ackraw = self.usbread(1)
            ack = ackraw[0] if ackraw else -1
            if ack != 0x5a:  # Studio Pocket patch: log and drain, the DA answered something else
                time.sleep(0.3)
                rest = self.usbread()
                self.warning(f"PMT command not acked: {ackraw.hex() if ackraw else '(none)'} {rest.hex() if rest else ''}")
            if ack == 0x5a:
''',
        ),
        (
            '''                    if self.usbwrite(self.Rsp.ACK):
                        if partdata[0x48] == 0xFF:
''',
            '''                    if self.usbwrite(self.Rsp.ACK):
                        self.info(f"PMT raw ({datalength} bytes): {partdata[:0x200].hex()}")  # Studio Pocket patch
                        if partdata[0x48] == 0xFF:
''',
        ),
        (
            '''                            mask_flags = unpack("<Q", partdata[0x48:0x4C])[0]
                            if 0xA > mask_flags > 0:
''',
            '''                            mask_flags = unpack("<I", partdata[0x48:0x4C])[0]  # Studio Pocket patch: 4 bytes
                            if 0xA > mask_flags > 0:
''',
        ),
        (
            '''                                for pos in range(0, datalength, 0x4C):
                                    partname = partdata[pos:pos + 0x40]
                                    size = unpack("<Q", partdata[pos + 0x40:pos + 0x44])[0]
                                    offset = unpack("<Q", partdata[pos + 0x44:pos + 0x48])[0]
                                    mask_flags = unpack("<Q", partdata[pos + 0x48:pos + 0x4C])[0]
''',
            '''                                for pos in range(0, datalength, 0x4C):
                                    # Studio Pocket patch: 32-bit fields, decoded names
                                    partname = partdata[pos:pos + 0x40].rstrip(b"\\x00").decode('utf-8', 'replace')
                                    size = unpack("<I", partdata[pos + 0x40:pos + 0x44])[0]
                                    offset = unpack("<I", partdata[pos + 0x44:pos + 0x48])[0]
                                    mask_flags = unpack("<I", partdata[pos + 0x48:pos + 0x4C])[0]
''',
        ),
        # 8. sdmmc_write_data: the per-MiB data ack is read with the default 0.5 s deadline; the Y2's
        #    eMMC occasionally takes longer to commit a chunk, which failed a write 15 % in
        #    ("Data ack failed for sdmmc_write_data") and left the partition half-written. Wait up
        #    to 60 s for the header ack and each data ack, and log what arrived on failure.
        (
            '''        self.usbwrite(pack(">I", 0x100000))
        if self.usbread(1) != self.Rsp.ACK:
            self.error("Couldn't send sdmmc_write_data header")
            return False
''',
            '''        self.usbwrite(pack(">I", 0x100000))
        if self.usbread(1, timeout=60) != self.Rsp.ACK:  # Studio Pocket patch: patient ack
            self.error("Couldn't send sdmmc_write_data header")
            return False
''',
        ),
        (
            '''            self.usbwrite(data)
            chksum = sum(data) & 0xFFFF
            self.usbwrite(pack(">H", chksum))
            if self.usbread(1) != self.Rsp.CONT_CHAR:
                self.error("Data ack failed for sdmmc_write_data")
                return False
''',
            '''            self.usbwrite(data)
            chksum = sum(data) & 0xFFFF
            self.usbwrite(pack(">H", chksum))
            ack = self.usbread(1, timeout=60)  # Studio Pocket patch: patient ack, logged on failure
            if ack != self.Rsp.CONT_CHAR:
                time.sleep(0.5)
                rest = self.usbread()
                self.error(f"Data ack failed for sdmmc_write_data at offset {offset:#x}: got "
                           f"{ack.hex() if ack else '(nothing in 60 s)'} {rest.hex() if rest else ''}")
                return False
''',
        ),
    ],
    "Library/partition.py": [
        # 7. get_gpt only asks the DA for the MTK PMT when the user area starts with "EMMC_BOOT". On
        #    the Y2 (MT6582, KitKat layout) the user area starts with a preloader-sized gap and the
        #    MBR sits at 0x1400000 without names, so printgpt failed ("Error reading gpt"). Ask the
        #    DA for the PMT first (names + offsets), then try the KitKat MBR location.
        (
            '''        elif data[0x1FE:0x200] == b"\\x55\\xAA" and self.read_pmt is not None:
            return self.parse_mbr(fh, gpt_settings, offset=0)
        else:
            return self.parse_mbr(fh, gpt_settings, offset=0x380000)
        return data, None
''',
            '''        elif data[0x1FE:0x200] == b"\\x55\\xAA" and self.read_pmt is not None:
            return self.parse_mbr(fh, gpt_settings, offset=0)
        else:
            # Studio Pocket patch (Innioasis Y2, MT6582 KitKat layout): the user area starts with a
            # preloader-sized gap and the MBR at 0x1400000 carries no names. Ask the DA for the MTK
            # PMT first (names + offsets), then fall back to the MBR locations.
            if self.read_pmt is not None:
                try:
                    partdata, partentries = self.read_pmt()
                    entries = getattr(partentries, "partentries", None)
                    if partdata != b"" and entries:
                        self.info(f"Partition table from the DA's PMT ({len(entries)} entries)")
                        return partdata, partentries
                    self.warning("DA returned no PMT")
                except Exception as err:
                    self.warning(f"PMT read failed: {err}")
            for off in (0x1400000, 0x380000):
                data, parts = self.parse_mbr(fh, gpt_settings, offset=off)
                if parts is not None:
                    self.info(f"Partition table from the MBR at {off:#x} (no names)")
                    return data, parts
            return data, None
''',
        ),
    ],
    "Library/mtk_main.py": [
        # 9. script mode reads the whole plan up front and runs it straight after configure_da, so
        #    a parent process that watches the log for the "m_emmc_cid = ..." line and kills mtk on
        #    a mismatch is racing the first write. When STUDIO_POCKET_EXPECT_CID is set, compare the
        #    connected eMMC CID with it before the first command and refuse the plan (exit 102 for
        #    another player, 103 when the CID cannot be read); a match logs a confirmation line.
        (
            '''            commands = open(self.args.script, "r").read().splitlines()
            da_handler = DaHandler(mtk, loglevel)
            mtk = da_handler.connect(mtk, directory)
            if mtk is None:
                return
            mtk = da_handler.configure_da(mtk)
            if mtk is not None:
                for rcmd in commands:
''',
            '''            commands = open(self.args.script, "r").read().splitlines()
            da_handler = DaHandler(mtk, loglevel)
            mtk = da_handler.connect(mtk, directory)
            if mtk is None:
                return
            mtk = da_handler.configure_da(mtk)
            if mtk is not None:
                # Studio Pocket patch: refuse to run the plan on a player other than STUDIO_POCKET_EXPECT_CID.
                # The plan is already read and runs back to back, so a parent watching the log for the
                # eMMC CID cannot stop the first write in time; the check has to happen here.
                expect_cid = os.environ.get("STUDIO_POCKET_EXPECT_CID", "").strip().lower()
                if expect_cid:
                    try:
                        emmc = getattr(getattr(getattr(getattr(mtk, "daloader", None), "daconfig", None),
                                               "legacy_storage", None), "emmc", None)
                        c = getattr(emmc, "m_emmc_cid", None)
                        connected_cid = hexlify(pack("<QQ", c[0], c[1])).decode().lower()
                    except Exception:
                        connected_cid = None
                    if connected_cid is None:
                        self.error("Studio Pocket - could not confirm which player is connected; "
                                   "refusing to run the plan")
                        sys.stdout.flush()
                        sys.stderr.flush()
                        sys.exit(103)
                    if connected_cid != expect_cid:
                        self.error(f"Studio Pocket - wrong player: expected \\u2026{expect_cid[-8:]}, "
                                   f"connected \\u2026{connected_cid[-8:]}; refusing to run the plan")
                        sys.stdout.flush()
                        sys.stderr.flush()
                        sys.exit(102)
                    self.info(f"Studio Pocket - player identity confirmed \\u2026{connected_cid[-8:]}")
                    sys.stdout.flush()
                for rcmd in commands:
''',
        ),
    ],
    "mtk.py": [
        # 10. the app had no way to tell a patched mtkclient from an unpatched one before handing
        #     it a write plan. With STUDIO_POCKET_PROBE=1 (and no arguments) main() answers with the
        #     identity-gate version and returns before any argument is parsed or any port opened;
        #     an unpatched mtk prints its usage and exits 2, and the app refuses to write with it.
        (
            '''def main():
    base = create_base_parser()
''',
            '''def main():
    # Studio Pocket patch: capability probe. Before it hands over a write plan the app runs `mtk`
    # with STUDIO_POCKET_PROBE=1 and no arguments; this answers and returns before any argument
    # is parsed or any port is opened. An mtkclient without this patch prints its usage and
    # exits 2 instead, and the app refuses to write with it.
    if os.environ.get("STUDIO_POCKET_PROBE") == "1":
        print("Studio Pocket - identity gate 1")
        sys.stdout.flush()
        return 0
    base = create_base_parser()
''',
        ),
    ],
}


def find_target():
    if len(sys.argv) > 1:
        return sys.argv[1]
    venv = os.path.join(HERE, "..", "firmware", "tools", "mtkclient-venv")
    hits = glob.glob(os.path.join(venv, "lib", "python3.*", "site-packages", "mtkclient"))
    hits += glob.glob(os.path.join(venv, "Lib", "site-packages", "mtkclient"))  # Windows venv
    if not hits:
        sys.exit("mtkclient not found; pass the mtkclient package directory as the first argument")
    return hits[0]


def main():
    target = find_target()
    applied = already = 0
    for rel, hunks in HUNKS.items():
        path = os.path.join(target, rel)
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for old, new in hunks:
            if new in src:
                already += 1
                continue
            if src.count(old) != 1:
                sys.exit(f"{rel}: hunk not found exactly once; upstream changed, patch by hand:\n{old}")
            src = src.replace(old, new)
            applied += 1
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(src)
    print(f"mtkclient at {target}: {applied} hunk(s) applied, {already} already present")


if __name__ == "__main__":
    main()
