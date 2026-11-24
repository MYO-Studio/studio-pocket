# Studio Pocket 0.10.0

This release adds **`studio-pocket-transcode`**, a program that converts Ogg Opus audio to MP3 so the
player can play it. The desktop app will use it in place of converting in its web view. This is still
a **pre-release**: the installer and player are unchanged since 0.9.0, and a lot is untested (below).
Read [`docs/tested-hardware.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/docs/tested-hardware.md) before you write to a player,
and keep the restore copy the installer makes.

## What's new

**Transcoder**

- `studio-pocket-transcode` (`tools/transcode`) converts one Ogg Opus file, mono or stereo, to a
  128 kbps, 48 kHz MP3, which the player's Android 4.4 can decode. It is a Rust program with libopus
  1.6.1 and LAME 4.0 linked in statically, run as a separate process, one per file, so several can
  run at once.
- It works a packet at a time, so memory use stays at a few megabytes whatever the length of the
  audio. The pre-skip, output gain and end position are honoured, and the MP3 starts with LAME's Info
  frame, so the duration is exact.
- Standard output carries `progress` and `done` lines. Exit code `3` means the input is not Ogg Opus
  in mono or stereo (nothing is written); `4` means anything else went wrong (the partial output is
  removed). The [architecture overview](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/docs/architecture/overview.md#transcoder-toolstranscode) has the details.
- Bundles for macOS (universal, macOS 11 and later), Linux x64 and Windows x64 are attached, each with
  its licences, a `BUNDLE-INFO.json` and an SBOM. LAME's corresponding source is attached too.

**Installer and player**

- No changes since 0.9.0. The player is 0.10.0 (versionCode 14) so the APK matches the release.

## What was tested

The transcoder's tests (which make their own Ogg Opus input with libopus's encoder) and the build
script's smoke test (`--version`, a stereo and a mono conversion, exit 3 for an input that is not Ogg
Opus) pass on Linux, macOS and Windows in CI, and the release workflow runs them again on the tagged
commit. Every package's tests pass on the tagged commit.

## What was not tested

- **The transcoder's MP3s on a player**, and the transcoder driven by the desktop app.
- **The 0.10.0 APK itself on a player**. It has the same code as the 0.8.0 and 0.9.0 APKs.
- **Restore on a 3.2.4 player**, and **upgrading** a 3.2.4 player.
- **Windows** and **Linux** for the installer, every part. They are built and unit-tested in CI only.
- Stock firmware 3.2.1, more than one player, a player with a different partition layout, and
  interrupting a write to the player.

## The files

| File | What it is |
|---|---|
| `StudioPocket-0.10.0.apk`, `player.json` | The player, signed with the release key, and its version, size and SHA-256 |
| `studio-pocket-firmware-tools-0.10.0.tgz`, `studio-pocket-library-format-0.10.0.tgz`, `studio-pocket-sync-engine-0.10.0.tgz` | The packages, as `pnpm pack` builds them |
| `mtkclient-macos-arm64.tar.gz` (Apple silicon), `mtkclient-macos-x64.tar.gz` (Intel), `mtkclient-windows-x64.tar.gz` | mtkclient with the Studio Pocket patches, frozen into a standalone program, without MediaTek's download agent (fetched on first use, below) |
| `e2fsprogs-macos-universal.tar.gz`, `e2fsprogs-linux-x64.tar.gz`, `e2fsprogs-windows-x64.tar.gz` | `debugfs`, `e2fsck` and `dumpe2fs` |
| `transcode-macos-universal.tar.gz`, `transcode-linux-x64.tar.gz`, `transcode-windows-x64.tar.gz` | `studio-pocket-transcode`, the Ogg Opus to MP3 converter |
| `mtkclient-source.tar.gz`, `e2fsprogs-1.47.4.tar.gz`, `e2fsprogs-cygwin-src.tar.gz`, `libusb-<version>-source.tar.bz2`, `mp3lame-sys-<version>-source.tar.gz` | The corresponding source of the GPL and LGPL code in the bundles (the last is the crate LAME is compiled from, checked against `tools/transcode/Cargo.lock`) |
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

([`release/signing-certificate.sha256`](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/release/signing-certificate.sha256) in the repository). `scripts/check-apk-signer.sh
StudioPocket-0.10.0.apk` compares it for you; do not install an APK whose digest differs.

## The first install downloads one file

The first time it reads or writes a player, `studio-pocket-firmware` downloads MediaTek's download
agent (`MTK_AllInOne_DA_mt6590.bin`, the program mtkclient loads into the player) from upstream
mtkclient at the commit the bundles are built from, checks it against the SHA-256 in
[`packages/firmware-tools/src/da-pin.json`](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/packages/firmware-tools/src/da-pin.json) and keeps it in
the firmware dir. This project does not distribute it. Offline, point `STUDIO_POCKET_DA_FILE` at a copy
you fetched yourself; the [README](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/README.md#the-first-flash-downloads-one-file) says how.

## Licences

Studio Pocket's own code is licensed under **GPL-3.0-only** (see `LICENSE`), or under a commercial
licence from the copyright holder (`COPYING.commercial`). The player APK and the tool bundles also
contain third-party components under their own licences: they are listed in
[`docs/licensing/third-party.md`](https://github.com/MYO-Studio/studio-pocket/blob/v0.10.0/docs/licensing/third-party.md), the APK carries the GPL-3.0 and their notices (shown on
the player under Settings > About > Licences), and each bundle carries them in its `licences/` folder
and `BUNDLE-INFO.json`. The corresponding source of the GPL and LGPL code in the bundles is attached to
this release. No file in this release contains MediaTek's download agent or any device maker's
preloader image: the bundles and the attached mtkclient source leave mtkclient's `Loader/` folder out,
the download agent is fetched on first use as above, and the Y2 needs no preloader.
