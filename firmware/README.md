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

A 3.2.0 player without FM Radio has no stock image on the archive and is not supported until one exists.
