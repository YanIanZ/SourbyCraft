# ProtocolLib provisioning

SourbyCraft memasang ProtocolLib sebelum Paper memindai plugin. Jalur aktif adalah SourbyClip
→ server → feature patch `0024` yang diterapkan SourbyPatcher → `PluginProvisioner` → pemindaian
plugin. Ini memakai entrypoint toolchain yang sudah dipin; perubahan JAR/revision SourbyClip
atau SourbyPatcher tidak diperlukan untuk hook server ini. Entry `SourbyBootstrap` lama bukan
main slim JAR saat ini. Hook yang sama kini memasang ViaVersion/ViaBackwards dari jalur aktif.

Status 2026-10-07 01:35 (sumber: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md), tree ter-materialisasi): patch 0025 (kini 0024) sudah
**diterapkan** ke tree Minecraft ter-materialisasi dengan `git am --3way` sebagai commit `392a8e5` ("SourbyCraft provision packet plugins
before the plugin scan", mengubah `net/minecraft/server/Main.java`, 3 baris; HEAD tree itu pada 2026-10-07), di atas commit `049aabd`. Sebelumnya patch
ini belum pernah diterapkan, sehingga **setiap jar yang dibangun sebelum 01:35 tidak memiliki hook provisioning** (log). Berkas
`features/0025-SourbyCraft-provision-plugins-before-plugin-scan.patch` masih untracked di repo root. Tidak tercatat di log: boot
dengan jar yang dibangun sesudah 01:35 maupun provisioning ProtocolLib yang benar-benar berjalan; klaim "jalur aktif" di atas
berarti hook ada di source ter-materialisasi, bukan bukti unduhan atau load.

Konfigurasi di `sourbycraft_config/sourbycraft_global_config.toml`:

```toml
[protocollib]
auto-provision = true

[viaversion]
auto-provision = true
```

Kedua setting **RESTART_REQUIRED**, independen, default `true`. `--plugins` menentukan folder
tujuan JAR serta config Via. ProtocolLib menghasilkan config miliknya saat plugin dimuat;
installer tidak menimpa config operator. JAR ProtocolLib yang sudah ada, termasuk JAR dengan
nama lain yang mendeklarasikan `name: ProtocolLib`, dipertahankan dan mencegah instalasi duplikat.

Pin memakai tagged release resmi ProtocolLib `5.4.0`, asset `280088448` (2025-08-07),
10.251.186 byte, SHA-256 `ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf`.
Provenance dan field lain tercatat dalam [`protocollib-pin.json`](../../build-data/protocollib-pin.json).
Descriptor upstream menyatakan `version: 5.4.0`, `api-version: 1.13` (Bukkit API floor),
`folia-supported: true`, `load: STARTUP`. Changelog resmi mengklaim dukungan MC 1.21.4–1.21.8;
diuji 2026-10-20 di build slim 26.2 ini: ProtocolLib termuat, aktif, dan dimatikan
bersih (`Done (15.719s)`, `Done (16.023s)`, exit code 0). Satu peringatan upstream
`Version (MC: 26.2.0) has not yet been tested!` adalah soft caveat karena 26.2 belum
terdaftar di peta `MCVERSIONS` upstream; plugin tetap enable.
Sumber: [official tagged release](https://github.com/dmulloy2/ProtocolLib/releases/tag/5.4.0),
[asset metadata](https://api.github.com/repos/dmulloy2/ProtocolLib/releases/assets/280088448).

Boot pertama mengunduh melalui HTTPS, memeriksa ukuran/SHA-256 lalu memindahkan file temporer
terverifikasi. Boot berikutnya memakai cache. `-Dsourbyclip.offline=true` tidak melakukan
unduhan plugin: JAR yang sudah terverifikasi atau dipasang operator dapat digunakan. Kegagalan
unduhan menyebut URL, tujuan dan hash, lalu server dapat lanjut tanpa ProtocolLib; plugin yang
membutuhkannya mungkin tidak dimuat. Pin adalah tagged release; perubahan upstream ditolak
sampai pin diperbarui, bukan otomatis dianggap versi terbaru yang dipercaya. Artifact resmi
telah diunduh dan diverifikasi pada 2026-10-20 (SHA-256 dihitung sendiri dari bytes
resmi GitHub).

`auto-provision=false` memindahkan hanya JAR dengan nama dan hash pin milik installer ke
`.jar.disabled`; mengaktifkannya kembali memulihkan JAR terverifikasi tanpa unduhan. JAR operator
tetap dapat dimuat. File karantina yang berbeda tidak ditimpa.

Verifikasi:

```bash
python3 -m unittest discover -s scripts -p test_plugin_provisioning.py
```

Tes JDK-only memeriksa install/hash/ukuran, cache, HTTPS-only, offline, setting independen,
pelestarian JAR/config, karantina, folder CLI, pin metadata dan hook sebelum scan. Hook Main
dikompilasi terpisah terhadap artifact lokal 26.2. Ini belum membuktikan unduhan HTTPS artifact
resmi, packaging slim JAR baru, load ProtocolLib, kompatibilitas plugin lain atau boot/region soak.
Full Gradle build masih ditolak oleh pembatasan socket lingkungan ini (batasan sesi Codex saat itu; agen Claude menjalankan Gradle dari 2026-10-06, lihat log, tetapi tidak ada hasil Gradle yang dicatat khusus untuk hook 0025, kini 0024).

## SEC-7 review — 2026-10-20

Live GitHub release metadata was checked again. The newest non-development tagged
release is [5.4.0](https://github.com/dmulloy2/ProtocolLib/releases/tag/5.4.0), asset
`280088448`, 10,251,186 bytes. Its downloaded SHA-256 matches the official digest:
`ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf`.
Its descriptor says `version: 5.4.0`, `api-version: 1.13`, `folia-supported: true`;
the release notes claim MC 1.21.4–1.21.8 support. Neither the descriptor nor that
changelog establishes 26.2 compatibility. Replacing the existing pin with this older
release would need a separate compatibility validation; SEC-7 remains pending a
compatible tagged asset. No pin downgrade was made.

The current `dev-build` asset has changed to ID `616780410`, SHA-256
`ca3ec620315ff92bee2d6d09af297d77362e99b4791b163135b745262df75ecc`,
10,567,022 bytes. This differs from the repository's reviewed `608298510` pin.
The exact-size/hash check will refuse changed upstream bytes on a cold installation;
a previously verified cache remains usable. The historical metadata above describes
the old pin, not the current mutable release. No new development asset is trusted by
this review. This is an upstream availability/compatibility blocker, not a successful
ProtocolLib load test. The isolated SEC-5 launcher boot disables plugin provisioning
and therefore does not validate ProtocolLib compatibility.

The integrator's scheduled CI probe should query the pinned release tag and asset
metadata daily, assert asset name/ID/size/digest still match the reviewed JSON, and
report newer tagged releases for compatibility review. It must never auto-update the
pin or accept `dev-build` as an immutable release. Proposed workflow details and
verified evidence are in the current Codex block of `AGENT-COORDINATION.md`.

## SEC-7 fix — 2026-10-20

The tagged 5.4.0 release was pinned instead and LOAD-TESTED on a fresh slim jar
(40,772,112 B, built 2026-10-20 03:03 via the Gradle lock wrapper with
`AGENT_NAME=deepseek-sec7`). Cold install with `[protocollib] auto-provision=true`,
`[viaversion] auto-provision=false`, `misc.auto_update.enabled=false`:

- Downloaded the official asset from
  `https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar`
  (HTTP 200, 10,251,186 bytes) and independently recomputed the SHA-256:
  `ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf` — matches
  `build-data/protocollib-pin.json#sha256` byte-for-byte. No alternative mirror
  consulted; the upstream redirect target is the same artifact.
- `python3 -m unittest discover -s scripts -p 'test_plugin_provisioning.py'` on
  /usr/bin/python3 3.9.6 and python3 3.12.13: 12 tests, 0 failures. The
  `test_runtime_pin_matches_official_asset_provenance` test recompiles the
  bootstrap classes against the JSON metadata and confirms the runtime pin
  carries the new file name, URL, digest and size.
- Cold boot from `/Volumes/Ext/DevCache/agent-tmp/sourbycraft/sec7-boot/boot/`
  (fresh dir, no plugins): `PluginProvisioner` provisioned
  `plugins/ProtocolLib-5.4.0.jar` (10,251,186 B) before Paper's plugin scan.
  Server log (timestamps local):
  - `[03:04:12 INFO]: [ProtocolLib] Loading server plugin ProtocolLib v5.4.0`
  - `[03:04:12 WARN]: [ProtocolLib] Version (MC: 26.2.0) has not yet been tested! Proceed with caution.`
  - `[03:04:12 INFO]: [ProtocolLib] Enabling ProtocolLib v5.4.0`
  - `[03:04:18 INFO]: Done (15.719s)! For help, type "help"`
  - `[03:06:45 INFO]: [ProtocolLib] Disabling ProtocolLib v5.4.0` (clean stop)
- Second boot (cached install) from `boot2/`: `/plugins` printed
  `- ProtocolLib (5.4.0)`; `/version ProtocolLib` replied with `ProtocolLib v5.4.0`
  + issues link + Native marker; `Done (16.023s)`; Aurora boot summary
  `COMPLETE | 14/14 stages completed without error | 0 failed`; exit code 0.

What the upstream "not yet been tested" warning means here: ProtocolLib compares the
running Minecraft version against the `MCVERSIONS` map in its `pom.xml` and logs the
warning when there is no match. The plugin still ENABLES and serves requests
(success path observed: `Done (`, `/version ProtocolLib` reply, clean disable on
`stop`). This is a soft compatibility caveat, not a fatal error — the warning is
the expected behaviour for a tagged release shipped before the upstream added 26.2
to its tested list. The SEC-7 audit row's "never booted" finding is now closed;
full plugin/region compatibility is still out of scope for SEC-7 (that's an OBS
question, not a pin question).

Pin file delta vs the reviewed prior JSON:
- `release`: `…/tag/dev-build` → `…/tag/5.4.0`
- `release_id`: `238871921` → `238142563`
- `asset_id`: `608298510` → `280088448`
- `version`: `5.5.0-SNAPSHOT` → `5.4.0`
- `file_name`: `ProtocolLib-5.5.0-dev-608298510.jar` → `ProtocolLib-5.4.0.jar`
- `download_url`: `…/download/dev-build/ProtocolLib.jar` → `…/download/5.4.0/ProtocolLib.jar`
- `sha256`: `3242a696…2a768a` → `ee2e7ab9…caccf`
- `size_bytes`: `10567071` → `10251186`
- `note`: now flags the 26.2 untested warning as a known soft caveat

`ProtocolLibProvisioner.PIN` updated to match (same 7-arg record constructor). No
public API change. The CI probe in the section below stays pinned to the tagged
release tag/asset and refuses to follow dev-build.
