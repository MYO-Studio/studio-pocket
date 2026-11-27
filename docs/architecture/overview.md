# How Studio Pocket fits together

Studio Pocket has four parts: a firmware engine that puts the player on a Y2 and takes it off again,
a library format that says what the player reads from its storage, a sync engine that writes that
library safely, and the player itself. The two commands in the [README](../../README.md),
`studio-pocket-firmware` and `studio-pocket-sync`, are thin front ends to the engines. Beside them is
one small program, `studio-pocket-transcode`, which converts audio the player cannot decode.

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

**Unknown firmware.** When the read works but the build is not one of the manifests, or the system
partition is not where they put it, `read-device` throws `UnsupportedFirmwareError` and keeps
nothing. `capture-device` (`capture.ts`) reads the same window, read-only, and keeps the system image
(or the raw window when it cannot be measured) with a `capture.json` report in a folder of its own,
outside `units/`, whatever the build. A new manifest is written from that capture, as 3.2.4's was.

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

## Transcoder (`tools/transcode`)

The player decodes MP3 and AAC; Android 4.4 has no Opus decoder. `studio-pocket-transcode` converts
one Ogg Opus file, mono or stereo, to an MP3 at a constant 128 kbps and 48 kHz with the same number
of channels. It is a program of its own, written in Rust, for two reasons: whatever is sending audio
to a player can run several at once, one process per file, and LAME, the MP3 encoder, is LGPL, so
it sits in a GPL program whose whole source is here, which anyone can rebuild or replace.

```
studio-pocket-transcode --version          prints "studio-pocket-transcode <version>"
studio-pocket-transcode <input> <output>   converts one file
```

- **Streaming.** It reads an Ogg page, decodes a packet with libopus and encodes it with LAME, and
  writes as it goes. Memory use is a few megabytes whatever the length of the audio.
- **The same audio.** The stream's pre-skip, its output gain and the position on its last page
  (where the audio really ends) are honoured. The MP3 starts with LAME's Info frame, which records
  the encoder's delay and padding, so a decoder that reads it plays exactly the samples that were
  in the Opus file; one that does not is out by under three frames (72 ms).
- **Standard output** carries only `progress <input bytes read> <input bytes total>`, once at the
  start and then at most about four times a second, and a final `done <output bytes>`. Each line is
  flushed as it is written. Errors are one line on standard error.
- **Exit codes.** `0` done. `2` wrong arguments. `3` the input is not something it reads (not Ogg,
  not Opus, or a channel mapping other than mono or stereo): nothing is written, and the caller can
  convert the file another way. `4` anything else (a file that cannot be read or written, a corrupt
  or truncated stream, an encoder error): the partial output is removed. A file left at `<output>`
  is always a complete MP3.
- **Nothing else.** No network, no configuration file, no environment variables. To stop it, kill
  it and delete the output.

A file that ends between two Ogg pages without its last page being marked is converted as far as
it goes, because nothing tells it from a file whose writer left the mark out; one that ends inside
a page is an error. Other streams in the same file, and anything after the Opus stream, are ignored.

`scripts/build-transcode.sh` runs the crate's tests and builds the bundle. libopus and LAME are
compiled from the source that their Rust crates carry (`opusic-sys`, `mp3lame-sys`), pinned by
checksum in `tools/transcode/Cargo.lock`, and linked statically, as is the `ogg` crate that reads
the container; the script checks that the finished binary depends only on the operating system's
libraries. On macOS the binary is universal (arm64 and x86_64) and runs on macOS 11 and later. The
licence texts of everything linked in and a `BUNDLE-INFO.json` naming each component, its version
and its source are installed next to it (see [../licensing/third-party.md](../licensing/third-party.md)).
The tests make their own Ogg Opus input with libopus's encoder, so no audio is checked in.

On an Apple M2, one process converts stereo audio at about 120 times real time.

## Player (`apps/player`)

An Android 4.4 (API 19) app in `/system/priv-app` that is the Y2's home screen. It has no network
permission. It reads the library when storage is mounted, hides incomplete playlists, shows groups
and playlists as a cover flow driven by the ring and buttons, plays MP3 and AAC with Android's
`MediaPlayer`, and saves the resume position. Settings (Bluetooth, volume cap, sleep timer,
brightness) are reached by holding Back; they are kept on the player itself, and nothing in the
library or on the computer sets them.

## Built on top

A graphical desktop app is built on these packages in a separate, proprietary project.
