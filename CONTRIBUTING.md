# Contributing to Studio Pocket

Thank you for helping. This page covers how to build and test, the rules every change must meet,
and the contributor licence agreement.

## Build and test

You need macOS or Linux, Node 22, pnpm, and for the player the Android SDK and a JDK (Android
Studio provides both). The [README](README.md) lists the requirements in full.

```bash
pnpm install
pnpm -r build
pnpm -r test

scripts/make-dev-keystore.sh      # once: a development signing key for the player (git-ignored)
scripts/build-player.sh   # player unit tests, lint and an APK signed with that key (build/player/StudioPocket.apk; OUT_DIR overrides)
```

A player you build yourself is signed with your development key. Never use it for anything you
publish, and keep the same key for every build you install on the same player: Android refuses an
update whose signature has changed.

Tests that touch firmware images need e2fsprogs (`debugfs`, `e2fsck`, `mke2fs`) on the machine. On
Linux, install your distribution's `e2fsprogs` package; on macOS, `brew install e2fsprogs` or build
the pinned copy with `scripts/build-e2fsprogs.sh`.

## Licence headers

Every source file starts with the GPL-3.0-only header that names the copyright holder and the
commercial alternative. CI checks this on every pull request:

```bash
node scripts/add-headers.mjs . --check      # lists files without the header; exit 1 if any
node scripts/add-headers.mjs .              # adds the header to new files
```

Run the second command before you commit a new source file.

## Contributor licence agreement (CLA)

Every outside contributor signs the CLA before a pull request can be merged. You do this once: the
CLA Assistant bot asks for it on your first pull request and records your signature.

The CLA lets Ashworth Digital Ltd offer your contribution under both licences. Its full text is
[CLA.md](CLA.md), and the bot links it too. That is why it is required: the project is offered under GPL-3.0-only and also under a
commercial licence ([COPYING.commercial](COPYING.commercial)), and both can only cover your code if
the holder may license it both ways.

## Pull requests

- Keep each pull request to one change, with tests where the change can be tested.
- `pnpm -r build`, `pnpm -r test` and the header check must pass.
- Anything that writes to a player must stay human-confirmed and verified by reading back, as the
  existing commands are. Never add a write by scatter offset or by partition name (see
  [docs/development/flashing-macos.md](docs/development/flashing-macos.md)).
- Never commit stock firmware, system images, keystores or anything else from a player.

## Security problems

Do not open an issue for a vulnerability. Follow [SECURITY.md](SECURITY.md).

## Names and logos

"Studio Pocket" and "MYO Studio", and their logos, are not licensed under GPL-3.0. The licence covers
the code, not the names. If you publish a modified version or a fork, give it a different name.
