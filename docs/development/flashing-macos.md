# Flashing with mtkclient

The installer engine (`packages/firmware-tools`) reads the player's own system partition, measures
where it is, keeps the image as that player's restore copy, patches it and writes it back, on
every OS through **mtkclient** (GPL-3.0, run as a separate program) over the MediaTek preloader's
USB serial port. mtkclient comes either from the patched venv that
`scripts/setup-mtkclient-venv.sh` sets up or from the standalone bundle that
`scripts/build-mtkclient.sh` builds. From a terminal the steps are
`studio-pocket-firmware read-device`, `prepare-device` and `write`
(`node packages/firmware-tools/dist/cli.js`); the [README](../../README.md) walks through them.
This page explains how the write works, why it is built the way it is, and the patches mtkclient
needs. What has been run on a real player: [../tested-hardware.md](../tested-hardware.md).

Innioasis' own macOS updater uses the same library underneath, so the mechanism is the one the
manufacturer ships.

**Why the write is narrow.** A community updater disabled its Y2 mtkclient writes after users
reported bricks. Reading its source, those come from writing sparse images raw and from MBR/EBR
placeholder writes, not from the partition-write primitive itself. Studio Pocket writes one raw
ext4 image to one partition (`ANDROID`, the system partition), which is as narrow as a write can
be. The boot loader and preloader are never touched, so the player always re-enters preloader mode
when it is restarted with the cable in.

## Why offsets are measured, never taken from the scatter file

1. The published scatter file carries two addresses per partition. mtkclient's read and write
   addresses are *linear* (user-area) offsets; ANDROID's `physical_start_addr` (`0x5180000`) is,
   in linear terms, the `SEC_RO` partition. A write there would destroy SEC_RO, MISC, LOGO, EXPDB
   and the first part of ANDROID.
2. A real player's layout need not match the scatter. On the tested player ANDROID starts
   `0x880000` bytes before the scatter's linear address (`0x6580000`): the partitions in between
   are smaller on the device than in the scatter.

Partition *names* are not available either: the Y2's download agent does not implement MediaTek's
PMT command, and mtkclient finds no MBR it can parse (the region at `0x1400000` holds no boot
sector), so `mtk printgpt` and `mtk r ANDROID` fail.

So no scatter offset is ever used. What works is a **measured offset with two independent
markers**: the system ext4 superblock at the start of the partition and the CACHE ext4 superblock
exactly at its end. `read-device` reads a window that certainly contains the partition, finds both
markers and records the result in the player's `device/device-layout.json`; a write refuses to run
without a layout whose two markers were found. On the tested player: ANDROID at `0x5D00000`, size
`0x33400000` (ending at `0x39100000`, where CACHE's superblock sits, `0x7E00000` bytes); the
scatter says ANDROID `0x6580000`, CACHE `0x39980000`.

A byte-for-byte match between a read and a stock ROM's `system.img` is not expected: the factory
writes its own ext4 generation of the same build (different UUID and block placement), and the
partition has been mounted. The engine identifies a read by its `build.prop` and the stock
launcher's hash instead.

## Setup (once)

```bash
scripts/setup-mtkclient-venv.sh      # venv, pinned mtkclient commit, FUSE stub, patches; idempotent
# libusb: brew install libusb   (not used on the serial path, but mtkclient imports it)
firmware/tools/mtkclient-venv/bin/mtk --help      # "MTK Flash/Exploit Client V2.1.4"
```

The script pins mtkclient to commit `7cb40e7` (`scripts/mtkclient-pin.json`) and replaces
`mfusepy.py` with a stub, because mtkclient imports a FUSE helper at start-up that fails without
macFUSE. The same script works in Git Bash on Windows. Neither the venv nor the bundle is where the
engine takes MediaTek's download agent from: on first use it downloads `MTK_AllInOne_DA_mt6590.bin`
from upstream mtkclient at that commit into the firmware dir's `tools/da/`, checks it against
`packages/firmware-tools/src/da-pin.json` and passes it with `--loader` (`STUDIO_POCKET_DA_FILE` overrides it).

e2fsprogs is needed too: run `scripts/build-e2fsprogs.sh` once (it builds 1.47.4 into
`build/e2fsprogs/<os>/`, which the CLI searches first), or point `STUDIO_POCKET_E2FS_DIR` at a
build made elsewhere. `brew install e2fsprogs` (keg-only, `/opt/homebrew/opt/e2fsprogs/sbin`)
still works as a fallback.

## Getting the player into preloader mode

Power the player off, connect the cable and start the command; when it says it is waiting, press
the reset pinhole (between the power button and the headphone jack, with a paper clip) with the
cable connected. The player restarts by itself and the preloader port (`MT65xx Preloader`, USB
`0E8D:2000`) appears about 3 s later. Use a USB **data** cable. A power-button restart with the
cable in is caught only sometimes, and plugging in a powered-off player, or holding buttons for
BROM mode, rarely produces a port on a Mac. If mtkclient prints `Handshake failed, retrying`, the
port was seen and lost: press the pinhole again. The download agent lives in the player's RAM and
is gone at the next restart, so press the pinhole again before each session.

Reading the partition takes about 6 minutes at 2.5 MB/s; a write about 4 minutes at 4 MB/s, plus
the read-back. Do not unplug during a write. A failed or interrupted write leaves the preloader
intact: restart with the cable in and run the restore.

### mtkclient patches (`scripts/patch-mtkclient.py`)

Stock mtkclient 2.1.4 could not talk to the Y2 from a Mac. All of these were found on hardware and
are needed on the `--serialport DETECT` path (1–7 for reading, 8 for writing, 9–10 for the
identity check):

| # | File | Problem | Fix |
|---|---|---|---|
| 1 | `seriallib.detectdevices` | `comports()` costs hundreds of ms on macOS, so the detect loop missed the preloader window | cheap `/dev/cu.usbmodem*` glob first |
| 2 | `seriallib.usbread` | one-byte echo reads used a 20 ms timeout; the preloader answers later → "Sync error" | 0.5 s deadline for echo reads only |
| 3 | `Port.run_serial_handshake` | spun at 100 % CPU forever when the port died or the preloader was already synced; a reply later than 20 ms was discarded and the start byte re-sent, which wedged the preloader (mute port) | bail out on dead port, 100 ms reply wait, drain stray bytes, accept verbatim echo, log `hs tx/rx` |
| 4 | `Port.serial_handshake` | 100 ms poll interval | 20 ms |
| 5 | `dalegacy_lib.read_flash_info` | the legacy DA needs > 250 ms before it sends the flash info and pauses again before PassInfo; empty reads shifted every field (crash in `unpack`, then "Failed to upload da") | 5 s / 8 s waits, logged |
| 6 | `dalegacy_lib.read_pmt` | `unpack("<Q")` on 4-byte slices; an unsupported PMT command (this DA) left bytes in the stream that broke the next read ("status: 0x5a015a0d") | 4-byte unpacks, drain + log on a missing ack |
| 7 | `partition.get_gpt` | only asked the DA for the PMT on "EMMC_BOOT" layouts; tried the MBR at 0 and 0x380000 only | PMT first, then MBR at 0x1400000 (still nothing on the Y2, see above) |
| 8 | `dalegacy_lib.sdmmc_write_data` | the per-MiB data ack was read with the default 0.5 s deadline; the eMMC sometimes takes longer to commit a chunk, so a write stopped at 15 % with "Data ack failed" | wait up to 60 s for the header ack and each data ack, log what arrived on failure |
| 9 | `mtk_main` `script` branch | the plan is read up front and runs straight after `configure_da`, so a parent killing `mtk` when the `m_emmc_cid` line names another player races the first write | with `STUDIO_POCKET_EXPECT_CID` set, compare the CID before the first command: confirm (`Studio Pocket - player identity confirmed …<last 8>`) or refuse the plan (exit 102 wrong player, 103 CID unreadable) |
| 10 | `mtk.main` | the installer had no way to tell a patched tool from an unpatched one before handing it a plan | answers `STUDIO_POCKET_PROBE=1` (no arguments) with `Studio Pocket - identity gate 1`, exit 0, and returns before argparse; an unpatched `mtk` prints its usage and exits 2 |

Patches 9 and 10 only take effect in a venv re-patched with `scripts/setup-mtkclient-venv.sh` and a bundle rebuilt with `scripts/build-mtkclient.sh` (the bundle check requires the markers in its `mtk_main.py` and `mtk.py`). Before handing over a write plan the installer's runners probe the tool (`STUDIO_POCKET_PROBE=1`, no arguments) and refuse one that does not answer `Studio Pocket - identity gate 1` (result code -4, nothing written); as a second net they still refuse a tool that never prints the confirmation line (-4) and stop a session whose `m_emmc_cid` line names another player (-2) or is missing (-3).

Upstream is unaware of these; if mtkclient is upgraded, re-run the patch script and read its
errors. The venv is git-ignored, so the script is the record.

## If it goes wrong

1. **No preloader port.** Check the cable carries data (the player's storage should appear on the
   computer when it is switched on and plugged in), press the pinhole with the cable already
   connected and the command already waiting, and make sure no other `mtk` is running.
   `scripts/preloader-probe.py` is a 40-line pyserial probe (handshake + `GET_HW_CODE`) that tells
   a device problem from an mtkclient problem; `mtk` must not be running when it is used.
2. **The player does not boot but still shows up as `MT65xx Preloader`** when restarted with the
   cable in: run `studio-pocket-firmware write --action restore` from the firmware dir that read
   it. More in [../recovery/y2-recovery.md](../recovery/y2-recovery.md).
3. **The player never shows up at all:** nothing here can help; see
   [../recovery/y2-recovery.md](../recovery/y2-recovery.md).

`scripts/compare-system-images.py` compares two system images of the same build at the file level
(`build.prop` and every regular file), for checking a read against another copy by hand.
