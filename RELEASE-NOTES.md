# Studio Pocket 0.7.0

A new player look, card stacks for playlist groups, track browsing on Now Playing, and the screen waking
on any button, plus an Intel Mac build of mtkclient. This is still a **pre-release**: it has been run on
one player from one Mac, and a lot is untested (below). Read [`docs/tested-hardware.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/docs/tested-hardware.md) before you
write to a player, and keep the restore copy the installer makes.

## What's new

**Player**

- A purple brand palette and a new Now Playing layout.
- Groups show as a stack of cards. A group can show its icon, a picture of its own, up to three chosen
  playlist covers, or its most recently played playlists' covers. Rest on a group and its stack fans
  out. Recent and All show their own playlists' covers.
- On Now Playing, the ring browses the playlist's tracks ("Track n of m") and the arrows skip. Skipping
  stops at the first and last track instead of wrapping.
- The play/pause button wakes the screen.

**Installing and upgrading** (`studio-pocket-firmware`)

- Install and upgrade mark the arrow, centre and back buttons as wake keys in the player's key layout,
  so any button wakes the screen. A player installed with an earlier release gets this at its next
  upgrade.
- An Intel Mac mtkclient bundle (`mtkclient-macos-x64.tar.gz`). Both Mac bundles now carry libusb built
  from the pinned upstream source for macOS 11 and later; the release fails any Mac program in them
  that needs a later macOS.

**Library format and sync**

- `groups.json` gains three optional fields per group: `display`, `image` and `covers`. The format stays
  version 1: older players ignore the new fields and show the icon. See
  [`docs/architecture/library-format.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/docs/architecture/library-format.md).
- `studio-pocket-sync` reads the MYO Studio browser extension's full-archive downloads, and leaves out
  tracks a download has no audio for.

## What was tested

On **one** Y2, from **macOS**, through the Studio Pocket desktop app: a release-key-signed test build of
this release's player (0.6.3, the same code as 0.7.0), with the group stacks set up in the desktop and
sent to the player, the Now Playing look and track browsing, and the wake changes. Every package's
tests pass on the tagged commit.

## What was not tested

- **The Intel Mac mtkclient bundle on an Intel Mac**: it is built and gated in CI only.
- **Windows** and **Linux**, every part. They are built and unit-tested in CI only.
- **The 0.7.0 APK itself on a player**: the test build above had the same code and signer but a
  different version.
- Stock firmware 3.2.1, more than one player, a player with a different partition layout, and
  interrupting a write to the player.

## The files

| File | What it is |
|---|---|
| `StudioPocket-0.7.0.apk`, `player.json` | The player, signed with the release key, and its version, size and SHA-256 |
| `studio-pocket-firmware-tools-0.7.0.tgz`, `studio-pocket-library-format-0.7.0.tgz`, `studio-pocket-sync-engine-0.7.0.tgz` | The packages, as `pnpm pack` builds them |
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

([`release/signing-certificate.sha256`](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/release/signing-certificate.sha256) in the repository). `scripts/check-apk-signer.sh
StudioPocket-0.7.0.apk` compares it for you; do not install an APK whose digest differs.

## The first install downloads one file

The first time it reads or writes a player, `studio-pocket-firmware` downloads MediaTek's download
agent (`MTK_AllInOne_DA_mt6590.bin`, the program mtkclient loads into the player) from upstream
mtkclient at the commit the bundles are built from, checks it against the SHA-256 in
[`packages/firmware-tools/src/da-pin.json`](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/packages/firmware-tools/src/da-pin.json) and keeps it in
the firmware dir. This project does not distribute it. Offline, point `STUDIO_POCKET_DA_FILE` at a copy
you fetched yourself; the [README](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/README.md#the-first-flash-downloads-one-file) says how.

## Licences

Studio Pocket's own code is licensed under **GPL-3.0-only** (see `LICENSE`), or under a commercial
licence from the copyright holder (`COPYING.commercial`). The player APK and the tool bundles also
contain third-party components under their own licences: they are listed in
[`docs/licensing/third-party.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.7.0/docs/licensing/third-party.md), the APK carries the GPL-3.0 and their notices (shown on
the player under Settings > About > Licences), and each bundle carries them in its `licences/` folder
and `BUNDLE-INFO.json`. The corresponding source of the GPL and LGPL code in the bundles is attached to
this release. No file in this release contains MediaTek's download agent or any device maker's
preloader image: the bundles and the attached mtkclient source leave mtkclient's `Loader/` folder out,
the download agent is fetched on first use as above, and the Y2 needs no preloader.
