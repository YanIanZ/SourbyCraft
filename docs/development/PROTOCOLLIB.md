# ProtocolLib provisioning

SourbyCraft memasang ProtocolLib sebelum Paper memindai plugin. Jalur aktif adalah SourbyClip
→ server → feature patch `0025` yang diterapkan SourbyPatcher → `PluginProvisioner` → pemindaian
plugin. Ini memakai entrypoint toolchain yang sudah dipin; perubahan JAR/revision SourbyClip
atau SourbyPatcher tidak diperlukan untuk hook server ini. Entry `SourbyBootstrap` lama bukan
main slim JAR saat ini. Hook yang sama kini memasang ViaVersion/ViaBackwards dari jalur aktif.

Status 2026-10-07 01:35 (sumber: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md), tree ter-materialisasi): patch 0025 sudah
**diterapkan** ke tree Minecraft ter-materialisasi dengan `git am --3way` sebagai commit `392a8e5` ("SourbyCraft provision packet plugins
before the plugin scan", mengubah `net/minecraft/server/Main.java`, 3 baris; HEAD tree itu pada 2026-10-07), di atas 0024 (`049aabd`). Sebelumnya patch
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

Pin memakai asset Paper resmi ProtocolLib `5.5.0-SNAPSHOT`, ID `608298510` (2026-10-03),
10.567.071 byte. SHA-256 dan provenance tercatat dalam
[`protocollib-pin.json`](../../build-data/protocollib-pin.json). Changelog resmi mencantumkan
dukungan 26.2; descriptor upstream menyatakan `api-version: 26.2` dan `folia-supported: true`.
Sumber: [official development release](https://github.com/dmulloy2/ProtocolLib/releases/tag/dev-build),
[asset metadata](https://api.github.com/repos/dmulloy2/ProtocolLib/releases/assets/608298510),
[Paper descriptor](https://github.com/dmulloy2/ProtocolLib/blob/master/paper/src/main/resources/paper-plugin.yml).
Deklarasi upstream ini bukan bukti qualification pada region engine SourbyCraft.

Boot pertama mengunduh melalui HTTPS, memeriksa ukuran/SHA-256 lalu memindahkan file temporer
terverifikasi. Boot berikutnya memakai cache. `-Dsourbyclip.offline=true` tidak melakukan
unduhan plugin: JAR yang sudah terverifikasi atau dipasang operator dapat digunakan. Kegagalan
unduhan menyebut URL, tujuan dan hash, lalu server dapat lanjut tanpa ProtocolLib; plugin yang
membutuhkannya mungkin tidak dimuat. Release `dev-build` bersifat mutable: perubahan bytes
upstream ditolak sampai pin diperbarui, bukan otomatis dianggap versi terbaru yang dipercaya.
Artifact resmi belum diunduh dalam sesi implementasi ini; digest berasal dari metadata GitHub.

`auto-provision=false` memindahkan hanya JAR dengan nama dan hash pin milik installer ke
`.jar.disabled`; mengaktifkannya kembali memulihkan JAR terverifikasi tanpa unduhan. JAR operator
tetap dapat dimuat. File karantina yang berbeda tidak ditimpa.

**Intave native:** build `includePrivateIntave` atau `-Dsourbycraft.intave.enabled=true` melewati pemasangan ProtocolLib eksternal
dan mengarantina JAR managed dengan pin tersebut. Intave native masih memakai API/adapter paket
internalnya; coexistence dengan plugin ProtocolLib belum diimplementasikan/divalidasi. Instalasi
eksternal juga dilewati ketika engine native disabled tetapi build masih membundel API tersebut.
JAR manual tetap dipertahankan, tetapi provider Intave native menolak backend yang bertabrakan.
Status build dan efektivitas Intave tetap mengikuti [dokumen native](../architecture/intave-native-integration.md).

Verifikasi:

```bash
python3 -m unittest discover -s scripts -p test_plugin_provisioning.py
```

12 tes JDK-only memeriksa install/hash/ukuran, cache, HTTPS-only, offline, setting independen,
pelestarian JAR/config, karantina, folder CLI, pin metadata dan hook sebelum scan. Hook Main
dikompilasi terpisah terhadap artifact lokal 26.2. Ini belum membuktikan unduhan HTTPS artifact
resmi, packaging slim JAR baru, load ProtocolLib, kompatibilitas plugin lain atau boot/region soak.
Full Gradle build masih ditolak oleh pembatasan socket lingkungan ini (batasan sesi Codex saat itu; agen Claude menjalankan Gradle dari 2026-10-06, lihat log, tetapi tidak ada hasil Gradle yang dicatat khusus untuk hook 0025).
