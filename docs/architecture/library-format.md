# On-device library format (version 1)

A computer writes it (through the sync engine, for example with `studio-pocket-sync`), the player
reads it, and the sync engine keeps it consistent. The
TypeScript source of truth is `packages/library-format/src/{types,validate,complete}.ts`; the
Kotlin mirror is `apps/player/.../library/{LibraryParser,LibraryLoader}.kt`. Both are tested
against the same fixtures in `packages/library-format/fixtures/`. Any change here is a
`formatVersion` bump, and the player refuses versions it does not understand.

## Layout on the USB volume

```
STUDIOPOCKET/
  device.json              identity marker (the computer or the player creates it; only the player's playerVersion is ever updated)
  library.json             index of playlists that are complete; replaced atomically, last
  sync.lock                present only while a computer is writing (hint, not a gate)
  playlists/<id>/
    update.lock            present only while this playlist's replaced files are being switched in (a gate)
    manifest.json          playlist + track list; written after every track file
    cover.jpg              optional, any size; player scales on load
    icon.png               optional 16x16 pixel icon
    tracks/<trackId>.mp3   or .m4a; MP3 and AAC only (KitKat cannot decode Opus; convert it to MP3 first)
  state/<id>.json          resume position, written by the player only; the sync engine preserves it
  groups.json              optional: playlist groups, written by the computer only (see below)
  groups/<name>.png        optional 16x16 group icons
  recent.json              optional: last played playlists, written by the player only
```

Every file that is being written has a `.part` suffix until it is complete, then is renamed.
Readers ignore `.part` names; the sync engine deletes them.

## Files

### `library.json`

```json
{
  "format": "studiopocket-library", "formatVersion": 1,
  "generatedAt": "2026-10-18T12:00:00Z", "generator": "studio-pocket-sync",
  "playlists": [
    { "id": "bedtime", "title": "Bedtime stories", "dir": "playlists/bedtime",
      "trackCount": 3, "totalBytes": 73872,
      "manifestSha256": "<sha256 of playlists/bedtime/manifest.json>" }
  ]
}
```

`dir` must equal `playlists/<id>`. Order is display order.

### `playlists/<id>/manifest.json`

```json
{
  "format": "studiopocket-playlist", "formatVersion": 1,
  "id": "bedtime", "title": "Bedtime stories", "author": "Mum",
  "source": { "kind": "yoto-myo", "cardId": "…", "updatedAt": "…" },
  "cover": "cover.jpg", "icon": "icon.png",
  "tracks": [
    { "id": "ch1", "title": "Chapter one", "file": "tracks/ch1.mp3",
      "durationMs": 3056, "sizeBytes": 18564, "sha256": "…", "mimeType": "audio/mpeg" }
  ]
}
```

`author`, `source`, `cover` and `icon` are optional (`cover`/`icon` may be `null`). `source.kind`
is `local` (what `studio-pocket-sync` writes) or `yoto-myo`. `file`
must be `tracks/<name>`. `mimeType` is one of `audio/mpeg`, `audio/mp4`, `audio/aac`.

### `state/<id>.json`

```json
{ "format": "studiopocket-state", "formatVersion": 1,
  "playlistId": "bedtime", "trackIndex": 1, "positionMs": 1500, "updatedAt": "…" }
```

### `device.json`

```json
{ "format": "studiopocket-device", "formatVersion": 1, "deviceId": "<uuid>",
  "createdAt": "…", "createdBy": "studio-pocket-sync", "playerVersion": "0.2.0" }
```

From 0.5.0 the player keeps `playerVersion` current on every start, rewriting only that field
(by `.part` + rename over the old file). Before 0.5.0 it was written only when the player created
the marker, so an absent or older value means "older than 0.5.0".

## Rules

| Rule | Why |
|---|---|
| ids: `[a-z0-9][a-z0-9-]{0,39}`; file names: `[A-Za-z0-9][A-Za-z0-9._-]{0,99}` | FAT is case-insensitive and has odd rules for long names; keep everything ASCII and short. |
| Titles ≤ 200 chars, ≤ 500 tracks per playlist | Bounded memory on a 1 GB device. |
| A playlist is **complete** iff `manifest.json` parses, every track exists with exactly `sizeBytes` | The size check is what hides interrupted copies (and `update.lock` hides an interrupted switch-over); the player does not hash (too slow). The writer can hash after a sync. |
| `library.json` lists only complete playlists and is written last via `.part` + rename | A reader never sees a half-written index. A sync interrupted anywhere leaves the previous index valid. |
| `update.lock` is a gate | Written in a playlist folder before the first replacement `.part` is renamed live (only when the update replaces existing files), removed after the manifest is written. The library checks (`checkLibrary`) and the player hide a playlist while it exists, because an interruption between two same-size renames is invisible to the size checks. The next sync sees the old manifest, recopies the replaced tracks, and clears the marker for every added or updated playlist. |
| `sync.lock` is a hint | The player keeps working if a sync was interrupted; it just shows a "sync in progress" note. |
| `state/` is player-owned | The sync engine never writes it and only deletes entries for playlists it removes. |
| Missing artwork is non-fatal | Playlists still play. |

## Sync algorithm (packages/sync-engine)

1. `planTransfer(root, { send, remove })`: diff each sent playlist per file by size (optionally SHA-256); delete only the ids in `remove`; every other playlist on the player is a `keep` marked `untouched` and is never written. Result: add / update / keep / remove per playlist, bytes to copy, stray `.part` files.
2. `applySync(plan)`: remove strays (so their space counts), check free space (bytes to copy + 16 MB reserve), write `sync.lock`, delete removed playlists (+ their state), copy each file to `<name>.part`, then switch over per playlist: rename each `.part` to its live name and write `manifest.json` last; re-check each playlist from disk, write `library.json` from the complete ones, remove `sync.lock`, then remove files the new manifests no longer name. Replacement copies (a file whose size, recorded hash or content changed) stay `.part` until the switch; a file with no live predecessor (a new playlist or track) is renamed live right after its copy, so an interrupted first sync resumes. A cancelled or unplugged run can leave hidden `.part` files for replaced tracks until the next sync.
3. A sync interrupted while copying leaves the previous version of every playlist playable (except playlists being removed in that run). The switch-over for a playlist is a few renames; interrupted there, `update.lock` (written before the first rename of an update that replaces live files, removed after the manifest) is still present, so the player's loader and `checkLibrary` hide that playlist whatever the sizes. The next sync plans it as an update, removes stray `.part` files, recopies that playlist's replaced tracks and clears the marker.
4. Re-running after an interruption copies only what is missing.

Tests cover fresh sync, no-op resync, interruption mid-copy with the old index staying
valid, abort and resume, manifest-only updates, re-copy on size change, removal with state
cleanup, and insufficient storage.

### Track icons (added 2026-10-19, still format version 1)

A track may carry an optional `icon`: a relative path `icons/<name>.png` inside the playlist
folder, a 16×16 PNG. The field is optional and additive, so readers that predate it ignore it; a
missing or unreadable icon file is non-fatal (the completeness check reports it as `(non-fatal)`
and the player draws its built-in note glyph), so a writer should never fail a sync because an icon
could not be fetched. Validation: same file-name rules as covers, and the path must be under
`icons/`.

### Playlist groups (added 2026-10-21, still format version 1)

Two optional root files, additive like track icons, so `formatVersion` stays 1 and players older
than 0.5.0 ignore them. TypeScript: `GROUPS_FILE`, `GROUPS_DIR`, `RECENT_FILE`, `validateGroups`,
`validateRecent` in `packages/library-format`; Kotlin: `library/Groups.kt`, `library/RecentStore.kt`.
Fixtures: `fixtures/library/STUDIOPOCKET/{groups.json,groups/,recent.json}` and
`fixtures/invalid/{groups,recent}-*.json`.

`groups.json` is written by the computer only, atomically (`.part` + rename):

```json
{
  "format": "studiopocket-groups", "formatVersion": 1,
  "updatedAt": "2026-10-22T07:00:00Z", "generator": "<writer>/0.x",
  "groups": [
    { "id": "g-1a2b3c4d", "title": "Bedtime", "icon": "groups/g-1a2b3c4d.png",
      "iconRef": "yoto:<mediaId>", "playlists": ["yoto-abc", "local-3f9a2c1b7d"] }
  ]
}
```

| Rule | Why |
|---|---|
| ≤ 30 groups; `id` is an id (as above), unique, and not `recent` or `all` | Bounded on the device; the player's system groups need those names. |
| `title` 1–40 chars | A label under a square on a 2.4" screen. |
| `icon` optional (absent or `null` = the player's folder glyph); must be `groups/<file name>` | Same file-name rules as covers. A missing or unreadable file is non-fatal. |
| `iconRef` optional string ≤ 100, ignored by the player | Lets the writer record which icon was picked (for example `yoto:<mediaId>`). |
| `playlists`: ids, unique within the group, and a playlist is in at most one group | Folder semantics. The validator rejects a playlist in two groups; the player lets the first win. |
| Ids not in `library.json` are allowed | Removing a playlist never breaks groups; the writer prunes them when it writes. |
| Order of `groups` is order on the player; order of `playlists` is the group's cover-flow order | Explicit, set by the writer. |

`recent.json` is written by the player only (atomically) and preserved by the sync engine, like
`state/`:

```json
{ "format": "studiopocket-recent", "formatVersion": 1, "updatedAt": "…", "playlists": ["yoto-abc", "local-1"] }
```

The player moves a playlist to the front whenever it starts playing it and keeps 5. Readers
accept up to 20 unique ids and show the first 5 that are on the player. It is a list rather than
a sort of `state/*.json` timestamps because the Y2 has no network time and its clock can go
backwards after a flat battery.

Validation is strict on the computer and tolerant on the player. `checkLibrary` returns the parsed
`groups` and `recent` (null when absent or invalid) and reports a bad file or a missing group icon
as a `(non-fatal)` problem; a bad `groups.json` never hides a playlist. The player skips a
malformed group or id rather than the whole file, cuts a long title, treats a bad icon path as no
icon, and treats a file it cannot read, or a newer `formatVersion`, as no groups. The sync
engine's stray clean-up and `applySync` never touch `groups.json`, `groups/` or `recent.json`
(only root-level `.part` files, which are interrupted writes).

On the player: the home screen is Recent, the user's groups that have something on the player
(in file order), then All. With no such group it shows the flat cover flow as before.
