# How Studio Pocket fits together

Studio Pocket has four parts: a firmware engine that puts the player on a Y2 and takes it off again,
a library format that says what the player reads from its storage, a sync engine that writes that
library safely, and the player itself. The two commands in the [README](../../README.md),
`studio-pocket-firmware` and `studio-pocket-sync`, are thin front ends to the engines.

```
  computer                                          Innioasis Y2
  ┌───────────────────────────┐   mtkclient, USB    ┌─────────────────────────────┐
  │ firmware engine           │ ──────────────────> │ system partition            │
  │ (studio-pocket-firmware)  │   (preloader mode)  │   Studio Pocket player APK  │
  ├───────────────────────────┤                     ├─────────────────────────────┤
  │ sync engine               │   USB mass storage  │ storage                     │
  │ (studio-pocket-sync)      │ ──────────────────> │   STUDIOPOCKET/  (library)  │
  └───────────────────────────┘                     └─────────────────────────────┘
```

## Firmware engine (`packages/firmware-tools`)

The engine installs the player by changing one file in the Y2's own system image: the stock home
screen app is removed and the Studio Pocket APK put in its place. It never downloads firmware. Its
steps:

1. **Read.** Over mtkclient, with the player restarted into MediaTek preloader mode, read the
   region that holds the system partition. mtkclient and e2fsprogs run as separate programs.
   MediaTek's download agent, which mtkclient loads into the player, is not distributed: the
   engine downloads it from upstream mtkclient on first use, checks it against a committed
   SHA-256 and hands it to mtkclient by path.
2. **Measure.** Find the partition by two markers: the system ext4 superblock at its start and the
   next partition's superblock exactly at its end. Partition names and the published scatter
   file's offsets are both unusable on the Y2, so the measured offsets are the only ones ever used.
3. **Identify.** Match the build (`build.prop`) and the stock launcher's hash against the known
   firmware manifests in `src/manifests/`. Anything unknown is refused. The read is kept, per
   player, as that player's restore copy.
4. **Patch.** With `debugfs`, offline, on a copy: remove the stock launcher, write the APK with the
   right owner, mode and SELinux label. The APK must carry a valid v1 signature that Android 4.4 can
   check.
5. **Verify.** Re-open the patched image independently: `e2fsck` clean, the APK reads back
   identical, the stock launcher is gone, the image fits the measured partition.
6. **Write.** After the person types `WRITE`, in one mtkclient session: confirm the connected
   player is the one the image belongs to, write the system partition only, then read the first
   and last MiB (or the whole image) back and compare.
7. **Restore** is the same write with the kept original. **Upgrade** reads an installed player and
   swaps only the APK.

**Upgrades.** `read-device` on a player that already runs Studio Pocket recognises it (known
build, `/priv-app/StudioPocket.apk` present, stock launcher absent) and keeps that read apart, under
the player's `upgrade/` folder, so it never replaces the player's kept stock copy. `prepare-upgrade`
re-checks that read, stops with "already runs this version" when the new APK is the installed one,
then removes the installed `StudioPocket.apk` instead of the stock launcher and writes the new one,
and verifies the image as for an install. `write --action upgrade` writes it like any other image.
The new APK must be signed with the same key as the installed one: Android refuses to update a
system app whose signature has changed. `prepare-upgrade` reads the installed APK's signer from the
read image and refuses a different one, or one it cannot read; `--allow-new-signer` skips the
check, but the reliable route for a player with another key is restore, then install.

**Bundled tools.** The engine runs e2fsprogs (`debugfs`, `e2fsck`, `dumpe2fs`) as separate
programs, and a release ships them ready-built so that installing needs nothing else on the
computer. `scripts/build-e2fsprogs.sh` builds them, unmodified, from upstream's 1.47.4 tarball
rather than copying a package manager's binaries, which link libraries that are not on other
machines and are not ours to redistribute as they are. The tarball's SHA-256 is pinned in the
script and checked before it is unpacked, so every build starts from the same source, and that
tarball is then the complete corresponding source of what ships. The programs are linked
statically against e2fsprogs' own libraries and depend only on the operating system's (the script
checks this with `otool` or `ldd`); on macOS the build is universal (arm64 and x86_64). Upstream's
licence texts and a `BUNDLE-INFO.json` (version, tarball URL and hash, configure flags) are
installed next to them. `scripts/check-e2fsprogs-bundle.mjs` is the gate CI and the release
workflow run: it fails when a tool is missing or not executable or `BUNDLE-INFO.json` is missing,
and on macOS when a signature does not verify or, for a signed release, when a binary is ad-hoc
signed or lacks the hardened runtime. The engine looks for each tool first in
`STUDIO_POCKET_E2FS_DIR` (or, when that is not set, `build/e2fsprogs/<os>/`), then on `PATH` and
in Homebrew's and the system's usual folders. On Windows, `scripts/build-e2fsprogs-windows.sh`
packages Cygwin's e2fsprogs instead (see [../licensing/third-party.md](../licensing/third-party.md)).

Flashing details and the reasons behind them:
[../development/flashing-macos.md](../development/flashing-macos.md). Recovery:
[../recovery/y2-recovery.md](../recovery/y2-recovery.md). What has been run on a real player:
[../tested-hardware.md](../tested-hardware.md).

## Library format (`packages/library-format`)

A versioned folder layout, `STUDIOPOCKET/` at the top of the player's storage: an index
(`library.json`), one folder per playlist with its manifest, tracks and artwork, and files the
player owns (resume positions, recently played). The TypeScript validators and the player's Kotlin
parser are tested against the same fixtures. Any incompatible change bumps `formatVersion`, and the
player refuses versions it does not understand. Full description:
[library-format.md](library-format.md).

## Sync engine (`packages/sync-engine`)

Two steps, so nothing is written until everything that can fail on the computer has been checked:

- **Plan** compares what should be on the player with what is there, per file, and lists what to
  add, update, keep and remove. Playlists not named in the request are never touched.
- **Apply** checks free space, copies each file under a `.part` name, then switches each playlist
  over with a few renames and its manifest last. The index `library.json` is replaced atomically,
  after the playlists, and lists only complete ones. An interrupted copy leaves the previous
  version of every playlist playable. A playlist interrupted during its switch-over is hidden by
  the player until the next sync finishes it: an `update.lock` marker covers the renames, and the
  player also hides a playlist whose manifest no longer matches the index's track count.

`studio-pocket-sync` builds the request from a folder of playlist folders and refuses, before writing
anything, any file the player could not play.

## Player (`apps/player`)

An Android 4.4 (API 19) app in `/system/priv-app` that is the Y2's home screen. It has no network
permission. It reads the library when storage is mounted, hides incomplete playlists, shows groups
and playlists as a cover flow driven by the ring and buttons, plays MP3 and AAC with Android's
`MediaPlayer`, and saves the resume position. Settings (Bluetooth, volume cap, sleep timer,
brightness) are reached by holding Back; they are kept on the player itself, and nothing in the
library or on the computer sets them.

## Built on top

A graphical desktop app is built on these packages in a separate, proprietary project.
