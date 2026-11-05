# Y2 recovery procedure

How to put a Y2 back on its original software after Studio Pocket, and what to do when an install
goes wrong. Where the restore below has been run, and on what:
[../tested-hardware.md](../tested-hardware.md).

## What Studio Pocket changes, and why recovery is usually simple

Studio Pocket writes **only the system (`ANDROID`) partition**. Preloader, LK (bootloader), boot, recovery, NVRAM, PRO_INFO, PROTECT_F/S, SECCFG, cache and userdata are never written. Because the preloader and bootloader are untouched, a failed or interrupted system write leaves the device able to enter MediaTek preloader mode over USB, which is all mtkclient needs.

## Getting the player into preloader mode

Power the player off, connect the USB cable, start the tool, then **press the reset pinhole**
(on the edge between the power button and the headphone jack; a paper clip or SIM tool) with the
cable still connected. The player restarts by itself. The preloader port (`MT65xx Preloader`, USB
`0E8D:2000`) appears about 3 s later and stays only briefly unless a tool is waiting for it, so
start the tool first. A power-button restart with the cable in is caught only sometimes; use the
pinhole. Cold-plugging a powered-off player and holding
buttons for BROM mode rarely produces a port on a Mac. After any download-agent session,
press the pinhole again before the next one. A port that answers nothing means a wedged handshake:
press the pinhole again.

## Failure classes

| Symptom | Likely cause | Recoverable by software? |
|---|---|---|
| Device boots to stock launcher after install | Image was not written, or wrong image | Yes – re-run install |
| Device boots, no launcher / black screen / "Unfortunately, launcher has stopped" | Our APK failed to load (bad signature, wrong path, crash on start) | Yes – restore the stock system image |
| Boot loop at Innioasis logo | Corrupt system image (interrupted write) | Yes – restore the stock system image |
| No logo, preloader port still appears when restarted with the cable in | System partition unusable, preloader fine | Yes – restore the stock system image; if that fails, contact the manufacturer's support (below) |
| Nothing on USB at all, no charge LED | Preloader damaged (should be impossible via Studio Pocket), hardware fault, or dead battery | **No** – requires manufacturer/ODM tools or test points. Not covered. |

The installer must present the last row honestly: software restore covers failures in the system partition, not every possible failure.

## Restore to stock (system partition only)

The stock image is the copy read from **this unit** before it was patched, written back at the
offsets **measured on this unit** (two ext4 superblocks: system at the start, CACHE exactly at the
end; `device-layout.json`). Never write by scatter offset (the scatter's physical address is
SEC_RO in mtkclient's linear addressing, and a player's layout can differ from the published scatter)
and never by partition name (the Y2's download agent has no partition table mtkclient can read).

### With `studio-pocket-firmware` (normal path)

```bash
node packages/firmware-tools/dist/cli.js write --firmware-dir <dir> --action restore [--unit <id>] [--verify full]
```

This writes back the copy of this unit's own system image that `read-device` kept when the player
was set up (`<dir>/units/<id>/stock/system.img`, the raw read, hash-checked before every write) at
the offsets in that unit's `device/device-layout.json`, through mtkclient, after you type `WRITE`.
The session starts with a read of a few MiB from the connected player and stops, with nothing
written, if that is not the player the chosen copy belongs to. It then reads the first and last MiB
of the partition back and compares them (the whole image too, with `--verify full`).
`studio-pocket-firmware units --firmware-dir <dir>` lists the players a firmware dir holds.

**Known gap:** a player can only be restored from the firmware dir that read it before it was
installed. Each player has its own folder, so two players in one firmware dir keep two restore
copies. In a fresh firmware dir a read of an installed player is accepted for an *upgrade*, but there is
no original software to restore from: the original launcher is gone from an installed image. Keep
the firmware dir (or at least its `units/` folder) when changing computers. A copy made before restore copies were kept per player
sits in `units/legacy/`; its identity cannot be checked (the installer warns and writes it without
the player check, so make sure the connected player is the one it was read from), and after a
successful write the folder is renamed to the id of the player that was written.

### Windows and SP Flash Tool

Restoring with SP Flash Tool on Windows is not validated or supported by this project.

## Full stock restore

**Warning:** a full stock flash ("Format All + Download" in the manufacturer's and community tools)
erases everything on the device, including its calibration and identity data, and rewrites the
parts that start it. It is not a troubleshooting step, and this project does not document or
support it. If the system-partition restore above does not bring the player back, contact the
manufacturer's support.

## What is recorded at install time

The installer keeps, per unit (the player's eMMC id), in the firmware dir under `units/<id>/`:

- `unit.json`: the player's eMMC id, filesystem UUID, firmware version, first and last seen, whether it runs Studio Pocket.
- `device/device-identity.json`: build (`ro.build.display.id`, build number), FM flag, matching manifest, launcher hash.
- `device/device-layout.json`: the measured partition start and size and both markers.
- `stock/system.img`: the unit's own system image as read (nothing writes into it; its filesystem is checked with `e2fsck -fn` only), with its SHA-256.
- The install record (`InstallRecordEntry` in `packages/firmware-tools/src/installer.ts`): date, action, firmware version, stock and written image hashes, Studio Pocket APK version and hash, outcome, and the unit's ext4 UUID and eMMC id.
