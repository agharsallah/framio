# Veidoo F10 — full backup log (2026-10-15)

Read-only backup before risky experiments. Data lives **outside the repo** at
`~/framio-backup/2026-10-15/` (personal photos, never commit). Restore notes: `RESTORE.md` in that dir.
`adb` = `~/Downloads/platform-tools/adb`. Repeat with `scripts/backup.sh <out-dir>`.

## Preflight

| Command | Output | Note |
|---|---|---|
| `df -h ~` | `40Gi` free | backup needs ~1.7 GB |
| `adb devices -l` | `68384696 device product:F10 model:Frame` | one device |
| `adb shell su -c id` | `uid=0(root) context=u:r:toolbox:s0` | root OK |

## Partition layout

| Command | Output | Note |
|---|---|---|
| `adb shell cat /proc/partitions` | `mmcblk0 30310400` KiB, `mmcblk0p1`…`p13`, `mmcblk0rpmb` | eMMC, 13 partitions |
| `adb shell 'ls -l /dev/block/platform/*/by-name/'` | `uboot→p1 misc→p2 resource→p3 kernel→p4 boot→p5 recovery→p6 backup→p7 cache→p8 metadata→p9 kpanic→p10 system→p11 radical_update→p12 userdata→p13` | platform `1021c000.rksdmmc`; no `/dev/block/by-name` |
| `adb shell cat /proc/mtd` | `No such file or directory` | eMMC, not NAND |
| `adb shell su -c "cat /proc/cmdline"` | `mtdparts=rk29xxnand:0x00002000@0x00002000(uboot),…,-@0x002B4000(userdata) storagemedia=emmc` | Rockchip parameter layout |
| `adb shell 'for p in /sys/block/mmcblk0/mmcblk0p*; do … cat $p/start $p/size; done'` | `p1 start=16384 size=8192` … `p13 start=2842624 size=57778176`; disk `60620800` sectors | **physical = mtdparts offset + 0x2000**; contiguous, userdata to end |
| `dd` loader-area.img, check sectors 0/64/8192 | sector 0 zero, 64 = RC4 idb header, `PARM` at 8192, 10240, 12288, 14336 | no GPT; idbloader + 4 parameter copies in first 8 MiB |

| Name | Dev | Phys start | Size | mtdparts | Content |
|---|---|---|---|---|---|
| loader area | mmcblk0 | 0 | 8 MiB | — | idbloader + parameter |
| uboot | p1 | 0x4000 | 4 MiB | 0x2000 | `LOADER` |
| misc | p2 | 0x6000 | 4 MiB | 0x4000 | zeros |
| resource | p3 | 0x8000 | 16 MiB | 0x6000 | `RSCE` |
| kernel | p4 | 0x10000 | 12 MiB | 0xE000 | `KRNL` |
| boot | p5 | 0x16000 | 12 MiB | 0x14000 | bootimg |
| recovery | p6 | 0x1C000 | 32 MiB | 0x1A000 | bootimg |
| backup | p7 | 0x2C000 | 64 MiB | 0x2A000 | zeros |
| cache | p8 | 0x4C000 | 128 MiB | 0x4A000 | ext4, empty |
| metadata | p9 | 0x8C000 | 16 MiB | 0x8A000 | ext4 |
| kpanic | p10 | 0x94000 | 4 MiB | 0x92000 | zeros |
| system | p11 | 0x96000 | 1 GiB | 0x94000 | ext4 |
| radical_update | p12 | 0x296000 | 64 MiB | 0x294000 | zeros |
| userdata | p13 | 0x2B6000 | 27.55 GiB | 0x2B4000 | file-level only |

## Tools on the device

| Command | Output | Note |
|---|---|---|
| `adb shell su -c 'which md5sum sha1sum sha256sum tar busybox dd'` | `md5sum sha1sum tar` → toybox, `dd` → toolbox | no sha256sum, no busybox |
| `adb shell tar --help` | `usage: tar -[cxtzhmvO]` | toybox tar |
| `adb shell cpio --help` | `cpio -{o\|t\|i\|p DEST}`, newc | toybox cpio |

## Binary-safety test

| Command | Output | Note |
|---|---|---|
| `adb exec-out su -c "dd if=/dev/block/mmcblk0p2 bs=1048576 2>/dev/null" > misc.img; md5 -q misc.img` | `b5cfa9d6…0a1c` | 4194304 B |
| `adb shell su -c "md5sum /dev/block/mmcblk0p2"` | `b5cfa9d6…0a1c` | **match**: exec-out is binary-safe, no temp files needed |

## Mistakes worth remembering

| Command | Output | Note |
|---|---|---|
| zsh loop with `adb shell readlink /dev/block/platform/*/by-name/$n` | `no matches found` | zsh expanded `*` on the Mac; dev empty → `md5sum` with no arg hung reading stdin. Killed, nothing written to device. Quote device globs: `adb shell '…*…'` |
| `adb shell su -c "du …; ls /sdcard; find /data \| wc -l"` | `find: /data: Permission denied` | adb joins args, device sh splits on `;` → only first cmd ran as root. Use `adb shell "su -c '…; …'"` |
| `while read …; do adb shell …; done < by-name.txt` | only first partition dumped | `adb shell` eats the loop's stdin. Fix: `</dev/null` on every adb call |
| `adb exec-out "su -c 'tar -cf - -C / data'"` | `tar: '…/the-real-index' > 100 bytes`, archive stops at 24 MB | toybox tar can't do long names → switched to cpio |
| `shasum -a 256 -c SHA256SUMS` | `./.DS_Store: FAILED` | Finder wrote `.DS_Store` mid-run; excluded from SHA256SUMS |

## Backup run (`scripts/backup.sh ~/framio-backup/2026-10-15`)

| Command | Output | Note |
|---|---|---|
| `adb shell getprop`, `pm list packages -f`, `dumpsys package net.frameo.frame` | 225 / 35 / 231 lines | saved in `info/` |
| `adb shell pm path net.frameo.frame` | `/data/app/net.frameo.frame-1/base.apk` | v1.32.12 (78070); stock v1.29.14 in `/system/priv-app/Frameo.apk` |
| `adb exec-out "su -c 'dd if=/dev/block/mmcblk0 bs=1048576 count=8 2>/dev/null'"` | 8388608 B, md5 `529aa410…` device = Mac | loader area, sectors 0–16383 |
| `adb exec-out "su -c 'dd if=/dev/block/mmcblk0pN bs=1048576 2>/dev/null'"` ×12 | all 12 md5 device = Mac | see table below |
| `adb exec-out "su -c 'cd / && find data > …/list.txt; cpio -o -H newc < …/list.txt'" > data.cpio` | 275807480 B, 690 listed / 682 archived | 8 missing = other agent's app reinstalled mid-copy + 2 recent-task files |
| `tar -tf data.cpio` | exit 0, 682 entries | lists cleanly on Mac, uid/gid/mode kept |
| `md5sum` vs `tar -xOf data.cpio <f> \| md5 -q` | packages.xml, wpa_supplicant.conf, Frameo base.apk: match | content spot-check |
| `adb exec-out "su -c 'cat <apk>'"` | 74126981 B, md5 `ae9e58bc…` device = Mac | Frameo APK |
| `su -c 'find <dir> -type f \| wc -l; du -s <dir>'` | `/data/data/net.frameo.frame` 111 files / 21.9 MB; `/sdcard/frameo` 3 / 32 KB; `/sdcard/frameo_files` 17 / 4.5 MB | listings in `frameo/` |
| `shasum -a 256 -c SHA256SUMS` | 33 files OK | after excluding `.DS_Store` |
| `adb shell ls -d /data/local/tmp/framio-tmp /sdcard/framio-tmp` | `No such file or directory` | device temp cleaned |
| `df -h ~` | `37Gi` free | backup dir 1.7 GB |

| Image | Bytes | md5 (device = Mac) |
|---|---|---|
| loader-area | 8388608 | `529aa4101230d67156b628d5a3284950` |
| uboot | 4194304 | `6d7d0de790376ef199d339b5988a5dfe` |
| misc | 4194304 | `b5cfa9d6c8febd618f91ac2843d50a1c` (zeros) |
| resource | 16777216 | `980d331d050d506383245aafb41d4da7` |
| kernel | 12582912 | `aceceb606a8a79e2f61d3f7ff5a580c3` |
| boot | 12582912 | `09276e5365dbba2f34901f6bec10f28f` |
| recovery | 33554432 | `b5353fdbb94d0f1f00be88278d487553` |
| backup | 67108864 | `7f614da9329cd3aebf59b91aadc30bf0` (zeros) |
| cache | 134217728 | `4d2bc2f9827683ee489f8f367c951041` |
| metadata | 16777216 | `901d9eacf0174877075be78b80f44d47` |
| kpanic | 4194304 | `b5cfa9d6c8febd618f91ac2843d50a1c` (zeros) |
| system | 1073741824 | `bf661305d04065c05a399d7d8dfe69b7` |
| radical_update | 67108864 | `7f614da9329cd3aebf59b91aadc30bf0` (zeros) |

## Where Frameo keeps things

| Path | Files | Note |
|---|---|---|
| `/sdcard/frameo_files/media` | 8 `.webp`, 2.1 MB | received photos |
| `/sdcard/frameo_files/cache/galleryThumbnails` | 8 `.jpeg`, 95 KB | thumbnails |
| `/sdcard/frameo` | `.key`, `.info`, `.conf` | frame identity/pairing keys, keep private |
| `/data/data/net.frameo.frame/files` | `default.realm`, `calendar.realm`, json | app database (Realm), likely friends + photo metadata |
| `/data/data/net.frameo.frame/databases`, `shared_prefs` | 4 db, 10 xml | settings |
| `/data/data/net.frameo.frame/cache` | 30 glide cache, 8 thumbs | rebuildable |

Skipped: `userdata` raw image (27.5 GB, mostly empty; covered by `data.cpio`), `mmcblk0rpmb` (not a readable block device).
