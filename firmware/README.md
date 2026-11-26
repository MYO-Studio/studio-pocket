# firmware/

- The firmware manifests, JSON identities of the firmware bases we know how to patch (version, hashes, partition map, stock launcher identity), live in `packages/firmware-tools/src/manifests/`. The installer refuses any firmware they do not describe.
- `patches/` – per-firmware-version record of exactly what `packages/firmware-tools` changes inside the system image read from the player (files, modes, labels, debugfs sequence). Only the `ANDROID` partition is ever produced or flashed.
- `stock/`, `work*/` and `tools/` – local-only, git-ignored directories for reference images you supply yourself, build output and the mtkclient venv (`scripts/setup-mtkclient-venv.sh` creates `tools/mtkclient-venv/`).

No firmware images, preloaders, download agents, or MediaTek tools are committed. See `docs/licensing/third-party.md`.

## Supported firmware versions

| Manifest | Archive tag | How to recognise it on the player |
|---|---|---|
| `y2-stock-3.2.1.json` | `3.2.1` | Settings → About says 3.2.1 |
| `y2-stock-3.2.0-fm.json` | `3.2.0-fm` | Settings → About says 3.2.0 **and** an FM Radio app is in the menu (same scatter and filesystem geometry as 3.2.1; launcher at the same path) |
| `y2-stock-3.2.4.json` | none | Settings → About says 3.2.4 (build `1787108138`; FM Radio present; the launcher APK itself reports 3.2.3) |
| `y2-stock-3.2.8.json` | none | Build `1792752654` (FM Radio present; the launcher APK itself reports 3.2.9) |

3.2.4 has no published ROM, so its manifest has no `source` package: it was taken from a read-only
`read-device` read of a user's player and identifies the build on the device path only (build number,
then the stock launcher's exact size and hash). The zip/download commands (`verify-stock`, `build`) refuse
it. That read showed the same layout as the tested 3.2.0-fm player (ANDROID at linear `0x5d00000`,
`0x33400000` bytes, CACHE right after it) and the same 209,920 × 4 KiB filesystem. The launcher is still
`/system/priv-app/MyLauncher.apk` with no `.odex`, signed by the same Innioasis key as 3.2.1, so the
patch in `patches/y2-3.2.1/` applies unchanged.

3.2.8 has no published ROM either, so its manifest is device-only in the same way. It was taken from
a stock `system.img` (no scatter file) supplied to the maintainers, not from a player read. Compared
file by file with the 3.2.4 read, it has the same file set and the same 209,920 × 4 KiB filesystem;
the stock launcher is the only real change (3.2.3 to 3.2.9, still at
`/system/priv-app/MyLauncher.apk` with no `.odex`). Everything else differs only by build stamps,
and the platform signer, key layouts, init scripts and permission files are identical. The 0.10.2
player APK patches into that image and passes `verify` (launcher replaced, wake keys added, SELinux
label set, clean `e2fsck`). It has not yet been read from or installed on a player; the partition map is
carried over from 3.2.4, and the device path measures the real layout on each player anyway.

A 3.2.0 player without FM Radio has no stock image on the archive and is not supported until one exists.
