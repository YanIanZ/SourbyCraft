# SWM Plugin Fix, Paperclip Cleanup, NMS Optimization — Design

**Date:** 2026-05-16
**Status:** Draft

---

## §G Goal

Perbaiki penggunaan SWM plugin, hapus paperclip.conf yang statis, dan optimalkan NMS bridge dengan mengganti reflection access transformer.

## §C Context

- SourbyCraft adalah Paper/Pufferfish fork untuk Minecraft 1.21.11 (Java 25)
- SWM (SlimeWorldManager) punya dua implementasi: server-internal (`dev.iyanz.sourbycraft.swm.plugin`) dan plugin eksternal (`dev.iyanz.sourbycraft.swmplugin.hook`)
- `paperclip.conf` berisi JVM flags statis yang duplikat dengan `scripts/gc-tuner.sh`
- `NMSSlimeChunk.getSections()` menggunakan reflection (`getDeclaredField("sections")` + `setAccessible(true)`) untuk akses field yang bisa langsung diakses via Access Transformer

## §I Implements

1. Perbaiki duplikasi SWM plugin (internal vs eksternal), dokumentasi lengkap di README
2. Hapus `paperclip.conf`, update referensi di egg script dan README
3. Ganti reflection di `NMSSlimeChunk` dengan Access Transformer

## §V Invariants

- V1: Plugin eksternal (`swm-plugin/`) harus tetap bisa compile dan deploy terpisah dari server JAR
- V2: Server-internal SWM (`dev.iyanz.sourbycraft.swm.plugin`) tetap berfungsi sebagai built-in bootstrap
- V3: `gc-tuner.sh` adalah satu-satunya cara generate JVM startup flags
- V4: Tidak ada `setAccessible(true)` atau `getDeclaredField()` di SWM NMS code
- V5: Semua perubahan harus lolos `./gradlew sourbycraft-server:build -x test` dan `./gradlew :swm-plugin:build`

## §T Tasks

### T.1: Perbaiki SWM Plugin Duplikasi & Dokumentasi

**Status:** keduanya dipertahankan

**Perubahan:**

1. **Plugin eksternal** (`swm-plugin/`):
   - Update `build.gradle.kts`: ganti Paper API version dari `1.21.3-R0.1-SNAPSHOT` ke `1.21.4-R0.1-SNAPSHOT` (match mcVersion)
   - Pastikan `SWPlugin` di plugin eksternal memanggil API yang sama dengan server-internal
   - Verifikasi `plugin.yml` main class benar: `dev.iyanz.sourbycraft.swmplugin.hook.SWPlugin`

2. **Plugin server-internal** (`sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/plugin/`):
   - Tidak dihapus, tetap sebagai built-in bootstrap
   - Verifikasi bahwa `SWPlugin` internal dan eksternal menggunakan API yang sama

3. **README.md** — tambah section SWM lengkap:
   - Arsitektur: internal vs eksternal plugin, kapan pakai yang mana
   - Cara setup: build plugin, deploy, konfigurasi `config.yml`
   - Perintah `/swm`: list, load, save, info
   - Konfigurasi: `swm.enabled`, `swm.file-dir`, `swm.load-worlds`
   - Contoh: load world saat startup, save saat shutdown
   - Link ke gc-tuner.sh untuk startup flags

### T.2: Hapus paperclip.conf

**Perubahan:**

1. Hapus file `paperclip.conf`
2. Update `scripts/egg-sourbycraft.yaml` — ganti referensi `paperclip.conf` dengan `gc-tuner.sh`
3. Update `README.md` — ganti instruksi startup dari `java @paperclip.conf` ke `./scripts/gc-tuner.sh --start`
4. Update `gc-tuner.sh` — pastikan output flags kompatibel dengan `java @file` format (sudah, tapi verifikasi)

### T.3: Ganti Reflection dengan Access Transformer

**File yang diubah:**
- `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/server/NMSSlimeChunk.java` (line 59-60)
- `build-data/sourbycraft.at` — tambah AT entry

**Perubahan di NMSSlimeChunk.java:**

Before (reflection):
```java
java.lang.reflect.Field f = ChunkAccess.class.getDeclaredField("sections");
f.setAccessible(true);
LevelChunkSection[] arr = (LevelChunkSection[]) f.get(chunk);
```

After (AT direct access):
```java
LevelChunkSection[] arr = chunk.sections;
```

**AT entry di `build-data/sourbycraft.at`:**
```
public-f net.minecraft.world.level.chunk.ChunkAccess sections
```

Note: Jika field `sections` merupakan private di ChunkAccess, AT membuatnya public sehingga bisa diakses langsung. Jika `sections` sudah punya public getter, gunakan getter tersebut.

**Verifikasi:**
- Build: `./gradlew sourbycraft-server:build -x test`
- SWM plugin: `cd swm-plugin && ./gradlew build`
- Pastikan tidak ada `getDeclaredField` atau `setAccessible` lagi di seluruh SWM codebase

## §B Buglog

(Tidak ada bug yang dicatat sebelumnya untuk area ini)