# Patch: replace the stock launcher (Y2 stock 3.2.1)

The same patch applies unchanged to 3.2.4: checked on a read of a 3.2.4 player, the launcher is
at the same path (`MyLauncher.apk`, 61,822,056 B, label `u:object_r:system_file:s0`) with no `.odex`.

Applied by `packages/firmware-tools` (`studio-pocket-firmware prepare-device`, and with the
installed APK in place of the stock launcher by `prepare-upgrade`) to the copy of the system
partition read from the player itself. Recorded here so the change to the vendor image is
auditable without reading code.

**Reference identity:** the read is recognised by its `build.prop` and the stock launcher's hash,
both taken from the published `rom_y2.zip` 3.2.1, whose `system.img` has SHA-256
`d020da56f490bada6e2bdb183b1c5517cd7ee7100909f3066d9b712cc84bff36` (see `packages/firmware-tools/src/manifests/y2-stock-3.2.1.json`).
Nothing from the ROM itself is downloaded or used.

**Files changed inside the ext4 image** (nothing else is touched):

| Path | Change |
|---|---|
| `/system/priv-app/MyLauncher.apk` | removed (stock launcher, `com.innioasis.y2`) |
| `/system/priv-app/MyLauncher.odex` | removed if present (3.2.1 ships none) |
| `/system/priv-app/StudioPocket.apk` | added: our APK, mode `0644`, owner `root:root`, xattr `security.selinux = u:object_r:system_file:s0` |

**debugfs command sequence** (`replace-launcher.debugfs.tmpl`, `{{apk}}` and `{{ctx}}` substituted with absolute host paths):

```
rm /priv-app/MyLauncher.apk
cd /priv-app
write {{apk}} StudioPocket.apk
sif StudioPocket.apk mode 0100644
sif StudioPocket.apk uid 0
sif StudioPocket.apk gid 0
ea_set -f {{ctx}} StudioPocket.apk security.selinux
```

followed by `e2fsck -fy` (exit 0 or 1 accepted) and an independent verification pass
(`e2fsck -fn` must exit 0, APK readback must hash-match, label must be present).

Partitions written on the device: `ANDROID` (SYS16) only. That is enough because KitKat
grants an APK placed in `/system/priv-app` the `signatureOrSystem` permissions the stock
launcher relies on. The per-package `priv-app/<Name>/<Name>.apk` layout must not be used:
Android 4.4 scans `/system/priv-app/*.apk` directly and silently ignores subfolders (Y2Player
`integrate_launcher.py`).
