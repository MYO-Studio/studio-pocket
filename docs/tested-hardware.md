# Tested hardware

What has been run on a real player, and what has not. Everything below was run on **one** player.
Anything not listed under "Results" has not been tested on hardware.

## Setup

| | |
|---|---|
| Player | Innioasis Y2 (one unit): Samsung eMMC, 7.82 GB user area, 1 GiB RAM |
| Stock firmware | 3.2.0 with the FM Radio app: `3.2.0-fm`, build `1786599642`. Settings → About shows 3.2.0 but no build number; the FM Radio app is what tells this build apart |
| Computer | macOS 15 (Darwin 24.6), USB data cable |
| mtkclient | 2.1.4 at commit `7cb40e7` with the patches in `scripts/patch-mtkclient.py`, over the preloader's serial port (`--serialport DETECT`); both the patched venv and the standalone bundle |
| e2fsprogs | 1.47 (`scripts/build-e2fsprogs.sh` builds 1.47.4) |
| Studio Pocket player | 0.4.x for the October runs; 0.6.0 for the November runs |

## Results

All passed unless the row says otherwise.

| Date | Test | Outcome |
|---|---|---|
| 2026-10-19 | Talking to the player over mtkclient from macOS | Works only with the mtkclient patches (stock mtkclient lost the preloader handshake). The preloader port appears about 3 s after the reset pinhole is pressed with the cable in |
| 2026-10-19 | Finding the system partition | The partition table cannot be read (no PMT, no MBR) and the published scatter file's offsets are wrong for this player, so the partition is found by its two ext4 superblocks. The installer has found it this way ever since |
| 2026-10-19 | First write of the system partition | **Failed** at 15 % ("Data ack failed"): the eMMC took longer than mtkclient waited to commit a chunk. Fixed by mtkclient patch 8; the next write passed. Nothing else was affected |
| 2026-10-19 | Restore drill (the stock image written back), then install (early tooling) | Both writes verified by reading back the first and last MiB; stock, then Studio Pocket 0.4.0, booted |
| 2026-10-19 | Keys, playback, library pick-up | Ring, centre, back, play/pause and the two arrow buttons mapped; tones from the test library played; changes copied over USB storage appear after unplugging, without a restart |
| 2026-10-19 | Reading from the player without the stock preloader file | Works in preloader mode, so nothing from a stock ROM download is needed |
| 2026-10-20 | Restore (early tooling); then install and restore with the installer engine, from the player's own firmware | Every write verified; stock, Studio Pocket and stock again booted |
| 2026-10-21 | Bluetooth and screen settings, with a test build | Headphones that need no PIN paired, played and reconnected by themselves; each output keeps its own volume (15 steps); turning Bluetooth off while playing pauses; brightness and screen timeout persist across a restart |
| 2026-10-21 | Battery | About 5 % per hour playing over wired headphones with the screen off; no drop overnight while paused |
| 2026-11-03 | `read-device`, `prepare-device`, `write --action install` with the public commands, player 0.6.0 | `MATCH` for 3.2.0-fm, `WRITE VERIFIED`, Studio Pocket 0.6.0 booted |
| 2026-11-03 | `studio-pocket-sync … --create` | Playlists copied; they appeared and played after unplugging |
| 2026-11-03 | Sync refusals | A folder with an `.opus` file and the Mac's own disk were both refused, nothing written |
| 2026-11-03 | `write --action restore` | `WRITE VERIFIED`, stock launcher booted: a full stock → Studio Pocket → stock cycle with the public commands |
| 2026-11-04 | `read-device` into a fresh firmware dir | `units/<id>/` created with the raw read and its `e2fsck -fn` result |
| 2026-11-04 | `write --action install --verify full` | Whole image read back identical; Studio Pocket booted |
| 2026-11-04 | Identity check: a write aimed at another player's unit folder | Refused before anything was written; the player was unchanged |
| 2026-11-04 | mtkclient's own identity check | The patched `mtk` answers the probe; a wrong player id is refused before any read (exit 102); the right one is confirmed and the read runs |
| 2026-11-04 | Restore from an older single-player firmware dir | Moved to `units/legacy/`, restored with the "identity cannot be checked" warning, then renamed to the player's id |
| 2026-11-04 | Sync interrupted: cable pulled while an update was copying | The previous version of the playlist played; the next run finished the update, and a track removed from the folder was gone from the player after a full run |
| 2026-11-05 | The download agent fetched at first use | `mtk-check` downloaded it from upstream mtkclient, the SHA-256 matched the pin, and it was kept in the firmware dir; every later session passed it to mtkclient by path (`Using custom loader`) |
| 2026-11-05 | `read-device`, `prepare-device`, `write --action install`, then `write --action restore`, with an mtkclient bundle holding no download agent, preloader or WinFsp file | No preloader was asked for (no "DRAM config needed"); both writes verified; Studio Pocket, then stock, booted |
| 2026-11-05 | `studio-pocket-sync` into an existing library | The new playlist appeared and played after unplugging; the playlists already on the player were untouched |

## Not tested

- **Windows**, every part: the installer, restore, mtkclient and its USB driver on Windows, the
  Cygwin e2fsprogs bundle, and `studio-pocket-sync` to a drive letter.
- **Linux**, every part: the commands have only been built and unit-tested there, not run against a
  player.
- **Upgrade** (`prepare-upgrade`, then `write --action upgrade`), and restoring a player after an
  upgrade.
- An APK signed with the official release key: every install so far used a development-signed
  build.
- Stock firmware **3.2.1** (build `1786709305`): the installer knows it, but no tested player
  carried it.
- Stock firmware **3.2.4** (build `1787108138`): known from a read-only read a user shared from their
  own player. Offline, that read is identified as 3.2.4 with no problems, and `patch` plus `verify` on it
  pass (`e2fsck -fn` clean, APK read back, SELinux label present). Nothing has been written to a 3.2.4
  player, and no 3.2.4 player has booted Studio Pocket.
- More than one player, or a player whose partition layout differs from this one.
- Interrupting a write to the player. Only the sync copy has been interrupted on hardware.
- The player 0.6.0 settings screens on hardware: the Bluetooth page, volume cap, sleep timer,
  brightness and screen timeout, About and Diagnostics, settings surviving an in-place upgrade,
  the low-battery band, and plugging in USB with settings open.
- Wired headset buttons, Bluetooth headsets that pair with a PIN, call auto-answer, the
  low-battery warning and shutdown.
- The SP Flash Tool route on Windows: never run, and not supported by this project.
