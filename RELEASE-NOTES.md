# Studio Pocket 0.6.0

The first public build of Studio Pocket: the player app for the Innioasis Y2, the commands that install
it on a player and restore the stock firmware (`studio-pocket-firmware`), and the command that copies
playlists to it (`studio-pocket-sync`). This is a **pre-release**: it has been run on one player from one
Mac, and a lot is untested (below). Read [`docs/tested-hardware.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.6.0/docs/tested-hardware.md) before you
write to a player, and keep the restore copy the installer makes.

## What was tested

On **one** Y2 (stock firmware 3.2.0 with the FM Radio app), from **macOS**, with the public commands:

- install of the 0.6.0 player from the player's own firmware, every write read back and verified, and the
  player booting into Studio Pocket;
- restore of the stock firmware the same way: a full stock → Studio Pocket → stock cycle;
- the identity checks: a write aimed at another player's folder, and mtkclient run against the wrong
  player, were both refused before anything was written or read;
- playlist sync, including a sync interrupted by pulling the cable: the previous version of the playlist
  played, and the next run finished the update.

The mtkclient bundle in this release is the one tested on hardware: the install and the restore above
ran with it, with no download agent or preloader inside it and the download agent fetched on first use
(below).

## What was not tested

- **Windows** and **Linux**, every part: the installer, restore, mtkclient (and its USB driver on
  Windows), the Cygwin e2fsprogs bundle and sync. They are built and unit-tested in CI only.
- **The APK in this release on a player.** Every install so far used a development-signed build; this
  one is signed with the official release key, which no player has run yet. A player running a
  development-signed build must be restored to stock before it can take this one.
- **Upgrades** (`prepare-upgrade`, `write --action upgrade`) and restoring after an upgrade.
- Stock firmware 3.2.1, more than one player, a player with a different partition layout, and
  interrupting a write to the player.
- The player's settings and licence screens on hardware.

## The files

| File | What it is |
|---|---|
| `StudioPocket-0.6.0.apk`, `player.json` | The player, signed with the release key, and its version, size and SHA-256 |
| `studio-pocket-firmware-tools-0.6.0.tgz`, `studio-pocket-library-format-0.6.0.tgz`, `studio-pocket-sync-engine-0.6.0.tgz` | The packages, as `pnpm pack` builds them |
| `mtkclient-macos-arm64.tar.gz` (Apple silicon), `mtkclient-macos-x64.tar.gz` (Intel), `mtkclient-windows-x64.tar.gz` | mtkclient with the Studio Pocket patches, frozen into a standalone program, without MediaTek's download agent (fetched on first use, below) |
| `e2fsprogs-macos-universal.tar.gz`, `e2fsprogs-linux-x64.tar.gz`, `e2fsprogs-windows-x64.tar.gz` | `debugfs`, `e2fsck` and `dumpe2fs` |
| `mtkclient-source.tar.gz`, `e2fsprogs-1.47.4.tar.gz`, `e2fsprogs-cygwin-src.tar.gz`, `libusb-<version>-source.tar.bz2` | The corresponding source of the GPL and LGPL code in the bundles |
| `sbom-*.spdx.json` | An SPDX software bill of materials for each bundle |
| `SHA256SUMS` | The SHA-256 of every file above |

Check what you downloaded, in the folder you downloaded it to:

```bash
shasum -a 256 -c --ignore-missing SHA256SUMS     # Linux: sha256sum -c --ignore-missing SHA256SUMS
```

Check that the APK was signed with the release key: its signing certificate's SHA-256 digest must be

```
c1a8d7e7f71a3fc07e2fb830c79338aa927ca2092ca9305734db5b60fa0de8fe
```

([`release/signing-certificate.sha256`](https://github.com/MYO-Studio/studio-pocket/blob/v0.6.0/release/signing-certificate.sha256) in the repository). `scripts/check-apk-signer.sh
StudioPocket-0.6.0.apk` compares it for you; do not install an APK whose digest differs.

## The first install downloads one file

The first time it reads or writes a player, `studio-pocket-firmware` downloads MediaTek's download
agent (`MTK_AllInOne_DA_mt6590.bin`, the program mtkclient loads into the player) from upstream
mtkclient at the commit the bundles are built from, checks it against the SHA-256 in
[`packages/firmware-tools/src/da-pin.json`](https://github.com/MYO-Studio/studio-pocket/blob/v0.6.0/packages/firmware-tools/src/da-pin.json) and keeps it in
the firmware dir. This project does not distribute it. Offline, point `STUDIO_POCKET_DA_FILE` at a copy
you fetched yourself; the [README](https://github.com/MYO-Studio/studio-pocket/blob/v0.6.0/README.md#the-first-flash-downloads-one-file) says how.

## Licences

Studio Pocket's own code is licensed under **GPL-3.0-only** (see `LICENSE`), or under a commercial
licence from the copyright holder (`COPYING.commercial`). The player APK and the tool bundles also
contain third-party components under their own licences: they are listed in
[`docs/licensing/third-party.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.6.0/docs/licensing/third-party.md), the APK carries the GPL-3.0 and their notices (shown on
the player under Settings > About > Licences), and each bundle carries them in its `licences/` folder
and `BUNDLE-INFO.json`. The corresponding source of the GPL and LGPL code in the bundles is attached to
this release. No file in this release contains MediaTek's download agent or any device maker's
preloader image: the bundles and the attached mtkclient source leave mtkclient's `Loader/` folder out,
the download agent is fetched on first use as above, and the Y2 needs no preloader.
