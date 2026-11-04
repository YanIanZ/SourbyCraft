# Tester Owner — SourbyCraft 26.2 Build 50 (kandidat PR #62)

Pemilik pengujian: **YanIanZ**. Diperbarui 2026-10-25. Buku pegangan ini adalah sumber utama untuk
tes manual kandidat Build 50 dan untuk setiap perubahan di PR #62. Prosedur rinci yang sudah ada di
[`testing/owner/`](testing/owner/00-TESTING-INDEX.md) ditautkan, tidak disalin.

> **Status handoff:** kode sudah ditulis dan direview statis; belum dibangun; belum diuji owner.
> Agent **tidak** menjalankan build, Gradle, Java, test suite, skrip Python (termasuk skrip policy
> dan generator), server, Docker, Redis, benchmark, atau failure injection. Review source/diff bukan
> bukti compile, durabilitas, keamanan, atau stabilitas. Tidak ada klaim performa baru. Aturan:
> [kebijakan truthfulness](DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy).

## 0. Kandidat yang diuji

| Hal | Fakta (diverifikasi dari repo) |
| --- | --- |
| Branch kandidat | `build50/pm-completion`, draft PR #62 ke `26.2`. Basisnya `26.2` di `85e66a2` (merge PR #61). Commit kode terakhir `823c09b`; commit setelahnya hanya dokumen. Uji SHA HEAD yang benar-benar dibangun dan catat SHA penuh. SHA di kolom *Commit / PR* [matrix PM](releases/BUILD-50-PM-MATRIX.md) (mis. `823c09b`, `73dd07b`) berasal dari history sebelum branch ditulis ulang: tree-nya sama dengan commit di branch (mis. `823c09b` = `823c09b`), tetapi SHA itu tidak ada di branch. Pakai `git log --oneline origin/26.2..HEAD`. |
| Rilis yang sudah terbit | Tag `v26.2-r50` (prerelease) = `5f5a167`, **sebelum** PR #61 dan PR #62. Jar rilis itu tidak berisi perbaikan B50-02…15 maupun isi PR #62. `/ver` jar itu menampilkan `Build 50@5f5a167PR!`. Jangan memakai jar itu untuk tes di dokumen ini. (`v26.2-r49.1` adalah hotfix tes AbyssLink di atas r49, bukan Build 50.) |
| Versi | `gradle.properties`: `sourbyBuild=50`, `release=pre`, `releaseVersion=26.2`; PR #62 tidak mengubahnya. Jalur publikasi (r50.1, Build 51, atau prosedur lain) adalah keputusan owner **PM-011**. Merge PR tidak menerbitkan apa pun: CI melewati tag yang sudah ada. |
| Tes otomatis | Tidak ada tes baru ditulis di PR #62 (aturan owner). Beberapa assertion lama diedit karena perilaku sengaja berubah (daftar di B50-01). Spesifikasi unit test yang belum ditulis ada di kolom *Owner verification* [matrix PM](releases/BUILD-50-PM-MATRIX.md). |
| Fitur eksperimen | Semua tetap opt-in: MultiProfile (`enabled: false`), backend Redis AWF dan Redis Fabric (off), async pathfinding (`aurora.entity.async-pathfinding: false`), bridge (`aurora.bridge.mode: off`). Default `aurora.bridge.thread-checks: relaxed` (berlaku saat `mode: safe`) adalah keputusan owner dan **tidak qualified**. |
| MultiProfile terdistribusi | Hanya diizinkan dengan `aurora.awf.multiprofile.repository: redis` (commit Lua ber-fence di `RedisProfileRepository`): **EXPERIMENTAL, unqualified**. State live pemain tidak ikut berpindah antar-server; handoff proxy/portal dan snapshot quit saat shutdown (urutan patch 0081) masih terbuka. |
| Nama lama yang dihapus | Build 50 berhenti membaca nama non-persisten: flag `-DCanvas.*`, izin `canvas.broadcasting.receiver`, nama utility pra-49, `perf.ai.async-pathfinding`. Rewrite sekali di disk saat boot tetap ada. Pembaca data persisten (`Canvas.Distance`, `data/canvas/pearls.dat`, migrasi `canvas-*.yml`) dipertahankan menunggu keputusan owner **PM-179**. |

Hasil owner lama tetap terlihat tetapi tidak berlaku untuk kandidat ini: laporan owner 2026-10-24
(AWF-001…011 dan MP-001…009 kecuali MP-007 dilaporkan PASSED; MP-007 dan MP-010 tetap BLOCKED saat
itu; lembar hasil belum dilampirkan) dibuat pada tree
sekitar `v26.2-r50` (`5f5a167`), sebelum PR #61/#62. Statusnya sekarang **NEEDS RETEST**. Semua hasil
PASS historis di `testing/owner/*.md` untuk area AWF, MultiProfile, Redis, bridge, updater,
konfigurasi, async pathfinding dan telemetri juga NEEDS RETEST pada SHA kandidat.

## 1. Membangun kandidat yang tepat

Ikuti [private toolchain](development/PRIVATE-TOOLCHAIN.md) (SourbyPatcher/SourbyClip di Maven Local;
tanpa itu Gradle gagal saat konfigurasi settings). Java 25 (Temurin). Pada checkout kandidat:

```bash
git fetch origin && git checkout build50/pm-completion
git rev-parse HEAD                        # catat SHA penuh
git status --short                        # harus bersih sebelum build
bash scripts/setup_metal.sh
./gradlew applyAllPatches
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar -PsourbyBuild=50  # -> build/libs/SourbyCraft-slim.jar
sha256sum build/libs/SourbyCraft-slim.jar
./gradlew -PincludeTestPlugin=true :test-plugin:jar :legacy-test-plugin:jar
#   -> test-plugin/build/libs/test-plugin-1.0.0.jar, legacy-test-plugin/build/libs/legacy-test-plugin-1.0.0.jar
```

Regresi otomatis opsional (dijalankan owner, bukan agent; hasilnya tetap harus dicatat):

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/patch_policy.py
python3 scripts/baseline_policy.py
python3 scripts/patch_surface.py --check
python3 scripts/inherited_guard_policy.py
python3 scripts/concurrency_policy.py
```

Identitas yang benar di `/ver` dan `/sourbycraft version` (dari `BuildInfo.formatReleaseIdentity` dan
`build.gradle.kts`): build lokal di branch ini adalah kanal DEV dan menampilkan
`Build 50 — Aurora Nexus (DEV, build50/pm-completion@<7 karakter SHA>)` dengan warna amber, walaupun
`release=pre`. Hanya build CI dengan `-PsourbyChannel=REL` yang menampilkan `Build 50@<sha>PR!` (merah).
Jika SHA di baris Release tidak sama dengan `git rev-parse --short HEAD`, hentikan dan bangun ulang.

Pipeline PR tidak menjalankan Gradle, test Java, boot test, atau jar; CI hijau pada PR **bukan** bukti
build. Build, test dan boot berjalan setelah merge pada push (job `Build 26.2 jar`).

## 2. Kosakata status dan aturan bukti

| Status | Arti |
| --- | --- |
| NOT RUN | Belum dijalankan (sama dengan "NOT STARTED" di dokumen lama). |
| READY FOR OWNER TEST | Kode ada di kandidat dan prosedurnya bisa dijalankan dengan alat yang tersedia. |
| IN PROGRESS | Owner sudah mulai. |
| PASSED | Owner melihat setiap hasil yang diharapkan pada SHA kandidat dan menyimpan bukti. |
| FAILED | Owner melihat kegagalan; laporkan dengan [laporan bug](testing/owner/07-BUG-REPORT-TEMPLATE.md). |
| BLOCKED (`alasan`) | Tidak dapat dijalankan: alat, plugin uji, node kedua, peer, atau keputusan owner belum ada. Alasannya wajib ditulis. |
| NEEDS RETEST | Hasil lama dibuat pada tree yang kemudian diubah; hasil lama tetap ditulis dengan commit/build-nya. |

Aturan bukti:

1. Satu hasil = satu ID. Isi [lembar hasil](testing/owner/06-RESULTS-TEMPLATE.md): ID, nama owner,
   tanggal/jam/zona, SHA commit penuh, SHA-256 jar, `java -version`, OS, CPU/RAM/limit container,
   flag JVM, plugin + versi, file konfigurasi (atau hash-nya), langkah yang benar-benar dijalankan,
   hasil diharapkan vs aktual, potongan log dengan timestamp, status.
2. Status PASSED hanya untuk SHA yang diuji. Commit baru pada area yang sama membuat hasil itu NEEDS RETEST.
3. Skenario race yang tidak bisa dipicu andal lewat gameplay **tidak** boleh ditandai PASSED hanya
   karena server boot atau tidak terjadi apa-apa. Tulis `BLOCKED (race tidak terpicu)` kecuali ada seam,
   fixture, atau bukti log bahwa jalur race benar-benar dilewati.
4. Simpan: `logs/latest.log` (dan log rotasi), output perintah (salin teks, bukan hanya screenshot),
   `/perf <view>` terkait, thread dump (`jcmd <pid> Thread.dump_to_file -format=text <file>`; thread
   virtual tidak tampil di `jstack`), heap histogram/dump bila diminta, `sha256sum` file data sebelum/
   sesudah, output `redis-cli` (`INFO persistence`, `--scan`, `PTTL`), exit code proses.
5. Rahasiakan: token, password/URI Redis, kunci privat, IP/data pemain. Redaksi sebelum dibagikan.
6. Tes destruktif (kill -9, korupsi file, pause Redis) hanya pada salinan disposable.

## 3. Hubungan dengan prosedur rinci

| Dokumen | Isi | Status hasil historis |
| --- | --- | --- |
| [00 Index](testing/owner/00-TESTING-INDEX.md) | Dasbor ID lama (AWF/MP/RF/APF/BR/…) | Lihat index; AWF/MP NEEDS RETEST |
| [01 AWF](testing/owner/01-AWF-TESTING.md) | AWF-001…011 | NEEDS RETEST |
| [02 Async pathfinding](testing/owner/02-ASYNC-PATHFINDING-TESTING.md) | APF-001…005 | NOT RUN |
| [03 Experimental](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) | MP-001…010, RF-001…006, BR, RG, SC, CH, NW, AB, UP, SM | MP-001…009 kecuali MP-007 NEEDS RETEST; MP-007, MP-010 dan lainnya belum punya hasil owner |
| [04 Performa](testing/owner/04-PERFORMANCE-BENCHMARK-GUIDE.md) | A/B tersertifikasi, JFR | Tidak ada klaim performa |
| [05 Failure recovery](testing/owner/05-FAILURE-RECOVERY-TESTING.md) | FR-001…011 | NOT RUN |
| [06](testing/owner/06-RESULTS-TEMPLATE.md) / [07](testing/owner/07-BUG-REPORT-TEMPLATE.md) | Lembar hasil / laporan bug | — |

Satu kasus, satu hasil yang diharapkan: tes `B50-*` dan `TO-*` di sini adalah sumber untuk perubahan
Build 50/PR #62; kasus lama (AWF-006, FR-005, MP-007, MP-009, MP-010, RF-006, BR-001, …) merujuk ke ID
di sini bila perilakunya sama. Bila dua dokumen berbeda, kode menang; laporkan selisihnya sebagai bug
dokumentasi. Detail status per item PM: [matrix PM](releases/BUILD-50-PM-MATRIX.md).

## 4. Lingkungan, backup dan rollback standar

### 4.1 Lingkungan terisolasi

- Satu direktori server disposable per tes (atau per kelompok), port sendiri, `online-mode=false`
  hanya di jaringan uji. Jangan memakai data produksi.
- Redis uji khusus (Redis **7.2+** untuk `WAITAOF`; 7.0 minimum untuk repository profil redis),
  hanya listen di loopback/jaringan uji, `appendonly yes` untuk tes durabilitas. Gunakan prefix uji,
  misalnya `aurora.awf.redis.key-prefix: "sbtest:awf:"` (tanpa `{` atau `}`), agar mudah dibersihkan.
- Semua kunci `aurora.awf.*` (termasuk `redis.*`, `redis.fabric.*`, `multiprofile.*`) adalah
  **RESTART_REQUIRED**: ubah file, lalu restart. Kunci `aurora.awf.redis.uri/key-prefix/pool-size/
  timeout-ms/lease-seconds/wait-for-aof` tidak di-seed; tulis sendiri di `sourbycraft_config/aurora.yml`.
- Blok YAML di dokumen ini adalah potongan. Gabungkan ke pohon `aurora:` yang sudah ada dan ubah nilai
  yang sudah di-seed; jangan menempel blok `aurora:`/`awf:`/`multiprofile:` kedua. Kunci duplikat kini
  ditolak: file itu gagal dibaca (SEVERE/ERROR) dan nilainya tidak berlaku, misalnya MultiProfile tetap
  disabled (TO-CFG-13).
- Plugin uji: `test-plugin` (probe aktif hanya dengan `-Dsourby.testplugin.probes=true`) dan
  `legacy-test-plugin` (butuh `aurora.bridge.mode: safe`). Keduanya hanya untuk server uji.

### 4.2 Backup sebelum setiap tes destruktif

1. Hentikan server dengan `stop` (atau catat bahwa server sengaja di-kill).
2. Salin seluruh direktori server (`world/`, `sourbycraft_config/`, `config/`, `awf/`, `awf-templates/`,
   `plugins/`, `auto_update/`, jar). Catat `sha256sum` jar dan file data penting.
3. Redis: `redis-cli BGSAVE` lalu salin direktori data (RDB + `appendonlydir/`), atau pakai instance
   Redis terpisah yang boleh dibuang. Catat `redis-cli INFO persistence`.

### 4.3 Rollback standar dan batas kompatibilitas data

Rollback umum: hentikan kandidat, simpan bukti dan salinan state kandidat, lalu pulihkan **jar + data +
config** yang cocok dari backup 4.2. Jangan membuka data yang ditulis kandidat dengan jar lama tanpa
membaca tabel ini.

| Data yang ditulis kandidat | Akibat bagi jar lama (Build 49 / r50) | Tindakan |
| --- | --- | --- |
| `sourbycraft_config/aurora-worlds.json` mendapat `knownStores` | Gson mengabaikan field tak dikenal; tetap terbaca. | Tidak perlu. Entri bernama `overworld`, `the_nether`, `the_end` atau level-name diabaikan dengan WARN dan hilang pada tulis berikutnya; spawn di luar dunia dibuang dengan WARN. |
| `.awf` metadata `plugin-data` | `VERSION` tetap 2; jar lama mengabaikan key (PDC tidak dipulihkan). | Aman. |
| Store AWF FILE (`*.awf/`) | Format tidak berubah. Kandidat menolak index yang menyebut nama objek bukan hex 64 karakter. | Aman. |
| `awf/node-id`, `awf/incarnation`, `awf/node.lock` (dibuat bila backend Redis, fabric, atau MultiProfile butuh identitas) | Diabaikan jar lama. Owner lease Redis berubah sekali saat upgrade/downgrade, jadi lease lama ditunggu sampai kedaluwarsa (`lease-seconds`, default 60 s; klaim sesi `sessions.ttl-seconds`, default 45 s). | Jangan hapus `awf/node-id` kecuali memang ingin identitas baru. Upgrade/downgrade dengan stop bersih melepas lease lebih dulu. |
| Token lease Redis `owner/incarnation/uuid` | Owner berbeda; diperkirakan jar lama menunggu lease kedaluwarsa (belum diverifikasi oleh agent). | Stop bersih sebelum ganti jar. |
| Journal MultiProfile (`transitions/<player>.<transition>.awfp`, trailer fence/incarnation) | Jar lama mendekode isi, tetapi `recover <id>` tidak menemukan nama file baru. | Selesaikan/recover semua journal sebelum downgrade. |
| `accounts/<player>.legacy*.awfp` (arsip state pra-profil) | Diabaikan. | Berisi inventaris: perlakukan seperti playerdata; tidak pernah dihapus otomatis. |
| Snapshot dengan efek durasi tak terbatas (-1) atau > 1.000.000 tick | Build 49 gagal mendekode; switch ke profil itu gagal. | Simpan ulang profil di kandidat tanpa efek itu sebelum downgrade. |
| Repository profil Redis (`<prefix>fabric:mp:*`) | Tidak dibaca jar lama. Migrasi `migrate-repository` satu arah. | Kembali ke `repository: file` memakai direktori lama yang tidak disentuh; perubahan setelah migrasi hilang di jalur itu. |
| `repository.lock` | Ditulis ulang setiap kali direktori dibuka. | Kecualikan dari perbandingan checksum. |
| `auto_update/apply-failed.tag` | Marker baru (jar lama tidak memakainya). | Hapus manual atau `/update apply` sukses untuk mengizinkan apply lagi. |
| `<launch jar>.update-*.part` sisa kill -9 saat apply | — | Hapus manual. |
| `libraries/speedtest/**/speedtest.sha256` | Install lama tanpa record diunduh ulang sekali. | — |
| YAML dengan kunci duplikat | Kini ditolak (SEVERE) bukan "nilai terakhir menang". | Perbaiki file. |
| Nama config pra-49 di direktori read-only | Kini diabaikan (WARN per kunci), default berlaku. | Ganti nama kunci atau jalankan boot dengan direktori writable. |

### 4.4 Alat yang tidak tersedia (alasan BLOCKED yang umum)

- **Plugin probe MultiProfile tidak ada** (PM-005, alat uji di luar cakupan PR #62): `saveProfile`,
  `deleteProfile`, `createProfile` paralel, `bindProfileWorlds` > 16 entri, adapter state,
  `setProfileLocked`/`setProfileSlotLimit` dari plugin. CLI tidak punya `save` atau `delete` profil.
- **Seam deterministik tidak ada** (PM-004): menunda `PlayerStatePort.capture`, executor yang menahan
  atau me-retire dari thread lain, budget yang sudah kedaluwarsa.
- **Plugin bridged uji untuk kasus khusus tidak ada**: timer `EntityTask` yang menghapus entitasnya
  sendiri, kick di dalam command pemain lain, timer async berulang yang memanggil `runTask`,
  exception khusus untuk classifier, `world.setTime` dari async sebelum region tick.
- **Server rilis loopback yang bisa diatur tidak ada**: [`scripts/updater_rollback_fixture.py`](../scripts/updater_rollback_fixture.py)
  hanya punya skenario `rollback`, `healthy` dan `dead-launcher`.
- **File rusak buatan tidak ada**: `.awf` dengan offset index meluap, `.slime` v13 dengan bit flag
  tak dikenal atau PDC besar.
- **Peer Leviathan** untuk AbyssLink (AB-002).
- Butuh Linux root: `chattr`, `strace`, `iptables`, cgroup `memory.swap.max`. Butuh debugger JDWP
  untuk menangguhkan satu thread.
- Dua node + satu Redis bisa dibuat di satu host (dua direktori, dua port, identitas node berbeda);
  itu kebutuhan lingkungan, bukan alat yang hilang.

## 5. Format setiap tes

Setiap tes memuat: ID dan PM, prioritas/risiko, konfigurasi, persiapan data, langkah bernomor, hasil
yang diharapkan, indikator gagal, bukti, rollback/kompatibilitas, dan ketergantungan. Prasyarat,
backup dan rollback yang sama untuk satu area ditulis sekali di awal area ("standar area"). Kolom
hasil owner ada di tabel akhir tiap area (Status / Tanggal / Commit / Bukti). Untuk tes yang ditulis
ringkas (baris tabel), prioritas ada di kolom Prio dan arti risikonya: **P0** kehilangan/duplikasi data,
keselamatan region atau server berhenti; **P1** perilaku keamanan/durabilitas atau penolakan yang salah;
**P2** perilaku atau diagnosis yang menyesatkan; **P3** teks, telemetri atau kenyamanan operator.

---

## 6. Build dan patch (B50-01, TO-PATCH)

Standar area: checkout bersih; toolchain privat; tidak ada data server. Rollback: buang direktori
materialized (`paper-server/`, `paper-api/`, `sourbycraft-server/src/minecraft/`) dan jalankan
`applyAllPatches` lagi. Jangan commit direktori materialized.

### B50-01 — Patch bersih, compile dan paket kandidat

- **PM:** PM-017, PM-016 (MC 0033 + `WorldUnloadDrain` dari PR #61) dan seluruh kode PR #62.
- **Prioritas / risiko:** P0 / tinggi — tanpa jar tidak ada tes lain yang sah.
- **Langkah:** jalankan blok perintah §1 sampai `slimServerJar`, dengan `--console=plain` dan simpan log.
- **Diharapkan:** tidak ada reject/`Patch failed`; `compileJava` sukses; `slimServerJar` mencetak
  `slimServerJar: stripped N lib jar(s) …` dengan N > 0; `build/libs/SourbyCraft-slim.jar` ada.
- **Titik rawan compile yang hanya direview statis:** `HumanEntity#getPotentialRespawnLocation()` dan
  `Player#setRespawnLocation(Location, boolean)` (PM-093; fallback yang disarankan implementer:
  `getRespawnLocation(false)`), `World#getTickableTileEntityCount` (PM-276), klausa `throws`
  `PaperClassLookup.lookup` (PM-306), `AtomicReference<@Nullable ChunkPoll>` (PM-245),
  `TickThread.isTickThreadFor(Level,int,int)` (PM-245), skrip Lua dan pattern matching di
  `RedisProfileRepository` (PM-037).
- **Regresi opsional:** suite di §1. Assertion lama yang diedit di PR #62 (perilaku sengaja berubah):
  `AwfRegionStorageTest`, `ProfileEngineTest`, `BridgeReviewTest`, `BootstrapPluginSettingsTest`,
  `ProtocolLibProvisionerTest`, `UpdateApplierRestartPlanTest`, `UpdateApplierSwapTest`,
  `ReleaseIdentityTest`, `FoliaWorldInfoProviderTest`, `SysCommandTest`, `FoliaTickStatisticsTest`,
  `TickDataBinaryCompatibilityTest`, `perf-golden.txt`, `AsyncPathPatchShapeTest`,
  `BroadcastPermissionTest`, `LegacyNamesTest`, `AuroraConfigTest`, `ConfigMigrationTest`,
  `scripts/test_inherited_guard_policy.py`, `legacy-test-plugin` `LimitProbes`,
  `scripts/fixtures/PluginProvisioningProbe.java`. `ConfigExamplesTest` membandingkan setiap kunci
  Aurora dengan `docs/config-examples/aurora.yml`. Pada commit kode `823c09b` contoh itu belum memuat
  `aurora.awf.multiprofile.repository`, `redis.require-aof` dan `redis.wait-for-aof`; pembaruan dokumen
  PR #62 menambahkannya. Bila test ini masih gagal karena contoh, catat sebagai bug dokumentasi, bukan
  bug runtime.
- **Gagal bila:** reject, simbol hilang, `slimServerJar stripped 0 libraries …`, test merah selain yang dijelaskan di atas.
- **Bukti:** log Gradle lengkap, `git rev-parse HEAD`, `sha256sum` jar, laporan test (`build/reports/tests`).
- **Catatan:** test backend Redis butuh `redis-server` lokal; tanpa itu dilewati (skip, bukan lulus) kecuali
  `SOURBY_REQUIRE_REDIS=1` diset (seperti CI), yang membuatnya gagal.

### TO-PATCH-01 — Rebuild feature patch yang diedit tangan (0006, 0039, 0084, 0087)

- **PM:** PM-304 (0006), PM-238 (0039), PM-101 (0084), PM-271 (0087).
- **Prioritas / risiko:** P1 / sedang — hash `index` basi hanya berpengaruh bila `git am` jatuh ke 3-way merge, tetapi patch harus dibuat ulang dari repo materialized.
- **Langkah:**
  1. Setelah `applyAllPatches` sukses (B50-01), jalankan `./gradlew rebuildMinecraftFeaturePatches`.
  2. `git diff --stat sourbycraft-server/minecraft-patches/features/` dan `git diff` file yang berubah.
  3. Opsional PM-304: di repo materialized, ubah baris pesan commit patch 0006 yang masih menyebut
     `perf.ai.async-pathfinding` menjadi `aurora.entity.async-pathfinding`, amend commit itu, lalu
     rebuild lagi.
  4. `python3 scripts/patch_policy.py` dan `python3 scripts/patch_surface.py --check`.
- **Diharapkan:** perubahan hanya pada baris `index <hash>..<hash>` (dan pesan commit 0006 bila langkah 3
  dijalankan) di 0006, 0039, 0047, 0084, 0085, 0087; tidak ada hunk isi yang berubah; policy lulus.
- **Gagal bila:** hunk isi berubah, patch lain ikut berubah, atau policy gagal.
- **Bukti:** output `git diff`, log policy.
- **Rollback:** `git checkout -- sourbycraft-server/minecraft-patches/features/`. Commit hasil rebuild
  sebagai perubahan terpisah setelah direview.

### TO-PATCH-02 — MC 0084: guard Moonrise kembali untuk thread pemilik entitas di mode relaxed

- **PM:** PM-101. **Prioritas / risiko:** P1 / tinggi (ChunkEntitySlices diubah saat iterasi, entitas hilang).
- **Konfigurasi:** `aurora.bridge.mode: safe`, `aurora.bridge.thread-checks: relaxed` (RESTART_REQUIRED); `legacy-test-plugin` terpasang.
- **Langkah:**
  1. (a) Thread pemilik: plugin yang, di listener `EntityAddToWorldEvent` pada region pemilik saat chunk
     dimuat, memanggil `remove()` pada entitas lain di chunk yang sama — **BLOCKED (plugin uji tidak ada)**.
  2. (b) Thread asing: boot dengan `legacy-test-plugin`; `RelaxedProbes` berjalan sekali di `onEnable`.
     Periksa log `LEGACY_RELAXED_*_OK` dan `/perf plugins` (relaxed hits naik).
  3. (c) Ulangi dengan `aurora.bridge.thread-checks: enforced`: probe harus `LEGACY_RELAXED_*_REFUSED`.
- **Diharapkan:** (a) penolakan upstream Moonrise (WARN `… is currently prevented from being added/removed to
  world since it is processing section status updates` atau `IllegalStateException` `Attempting to remove
  entity … that is receiving status updates`), tanpa NPE chunk system; (b) akses
  asing tetap berjalan dan dihitung; (c) perilaku enforced sama seperti sebelumnya. Tidak ada `_WRONG`.
- **Gagal bila:** `LEGACY_RELAXED_*_WRONG`, NPE di chunk system, entitas hilang setelah restart.
- **Bukti:** log boot, `/perf plugins`, daftar entitas sebelum/sesudah restart.
- **Rollback:** standar area; mode relaxed tetap **unqualified** apa pun hasilnya.

### TO-PATCH-03 — MC 0039: `SwimNodeEvaluator.getPathType` di luar solve

- **PM:** PM-238. **Prioritas / risiko:** P2 / sedang (NPE `prepare was not called`).
- **Langkah:**
  1. Akuarium tertutup berisi lumba-lumba dan cumi; satu kali dengan `aurora.entity.async-pathfinding: true`
     (LIVE, `/sourbycraft reload`), satu kali `false`. Amati 10 menit.
  2. Panggilan API `dolphin.getNavigation().getNodeEvaluator().getPathType(dolphin, pos)` di region
     pemilik di luar solve — **BLOCKED (plugin uji tidak ada)**.
- **Diharapkan:** mob air tetap bernavigasi; tidak ada `NullPointerException`/`prepare was not called`
  di log; langkah 2 mengembalikan `WATER`, `BREACH` atau `BLOCKED`.
- **Bukti:** log, `/perf async` (bila on). **Catatan:** tes otomatis terkait: `AsyncPathPatchShapeTest`,
  `AsyncPathEvaluatorIsolationTest`, `MobPathSnapshotTest`.

### TO-PATCH-04 — MC 0087: teks penolakan sync chunk load di thread shutdown

- **PM:** PM-271. **Prioritas / risiko:** P3 / rendah (hanya teks; penolakan tidak berubah).
- **Langkah:** (a) `/stop` dengan plugin yang menulis blok di chunk tak dimuat dari `onDisable`;
  (b) unload dunia lewat API async sementara handler `ChunkUnloadEvent` membaca chunk tak dimuat
  secara sinkron — keduanya **BLOCKED (plugin uji tidak ada)**. Yang bisa sekarang: grep log semua tes
  lain untuk teks lama `the region scheduler has halted`.
- **Diharapkan:** pesan baru sekali: `Chunk load refused on a region shutdown thread (server stop or world unload): ...`; shutdown/unload tetap selesai. Teks lama tidak muncul.

### TO-PATCH-05 — Lisensi POM `sourbyapi`

- **PM:** PM-269. **Prioritas / risiko:** P3 / rendah.
- **Langkah:** setelah `applyAllPatches`: `./gradlew :sourbyapi:generatePomFileForMavenPublication`;
  periksa POM hasil (lokasi standar Gradle `sourbyapi/build/publications/maven/pom-default.xml`).
- **Diharapkan:** `<license><name>GPL-3.0-only</name>`. **Bukti:** potongan POM.

### Hasil area 6

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B50-01 | Patch, compile, paket | PM-016/017 | P0 | READY FOR OWNER TEST | | | |
| TO-PATCH-01 | Rebuild patch tangan | PM-101/238/271/304 | P1 | READY FOR OWNER TEST | | | |
| TO-PATCH-02 | 0084 guard owner-thread | PM-101 | P1 | READY (b, c); BLOCKED (a: plugin uji tidak ada) | | | |
| TO-PATCH-03 | 0039 swim path type | PM-238 | P2 | READY (1); BLOCKED (2: plugin uji tidak ada) | | | |
| TO-PATCH-04 | 0087 teks shutdown load | PM-271 | P3 | BLOCKED (plugin uji tidak ada) | | | |
| TO-PATCH-05 | Lisensi POM | PM-269 | P3 | READY FOR OWNER TEST | | | |

---

## 7. Aurora World Fabric (B50-02…04, TO-AWF)

Standar area:

- **Konfigurasi FILE (default):** dunia dibuat dengan `/awf create <nama> normal`; dunia itu selalu AWF.
  Pindah ke dunia: `/execute in minecraft:<nama> run tp @s 0 100 0`.
- **Konfigurasi Redis:** di `sourbycraft_config/aurora.yml` (RESTART_REQUIRED):

  ```yaml
  aurora:
    awf:
      backend: redis
      shutdown-deadline-ms: 30000        # default; beberapa tes memakai 2000/3000
      redis:
        uri: "redis://127.0.0.1:6379/0"
        key-prefix: "sbtest:awf:"
        lease-seconds: 60
        timeout-ms: 5000
        wait-for-aof: true
  ```
- **Folder dunia** (layout 26.2): `<level>/dimensions/minecraft/<nama>/` dengan store `region.awf/`,
  `entities.awf/`, `poi.awf/` (masing-masing `generations-root/CURRENT`, `generations-root/generations/<N>/`,
  `objects/`). Registry: `sourbycraft_config/aurora-worlds.json`. Template: `awf-templates/<t>/`.
- **Izin:** `sourbycraft.command.awf`; delete/import/export/convert/clone/set/template delete/unload
  `nosave` juga memeriksa `sourbycraft.command.awf.admin` (tersirat oleh node dasar).
- **Bukti standar:** output `/awf …`, `/perf awf`, `/awf info <w>`, `ls -laR` folder store, log, exit
  code, penanda shutdown `AWF-SHUTDOWN-OK` / `AWF-SHUTDOWN-INCOMPLETE`, dan untuk Redis
  `redis-cli --scan --pattern 'sbtest:awf:*'` serta `PTTL` kunci `…:lock`.
- **Rollback standar:** pulihkan salinan direktori + data Redis (§4.2). Lihat §4.3 untuk `knownStores`,
  `plugin-data` dan format store.
- Prosedur rinci terkait: AWF-001…011 di [01](testing/owner/01-AWF-TESTING.md) (NEEDS RETEST) dan
  FR-001…011 di [05](testing/owner/05-FAILURE-RECOVERY-TESTING.md) (NOT RUN).

### B50-02 — Batas save `/awf save`: drain I/O, commit terkonfirmasi, kill -9

- **PM:** PM-089, PM-211, PM-007 (AWF-006 adalah prosedur satu dunia yang sama; hasil yang diharapkan
  harus identik: 20/20). **Prioritas / risiko:** P0 / tinggi (kehilangan data setelah "Saved").
- **Konfigurasi:** putaran 1 FILE default; putaran 2 Redis (blok standar); putaran 3
  `aurora.awf.shutdown-deadline-ms: 3000`.
- **Data:** dunia baru `b2`; 20 penanda di beberapa region (blok, chest berisi item bernama, armor stand
  bernama, villager + workstation untuk POI); catat koordinat.
- **Langkah:**
  1. Buat 20 perubahan, lalu `/awf save b2`; tunggu balasan `Saved b2 (committed)`.
  2. Segera `kill -9 <pid>` (dalam 1 detik).
  3. Start ulang, `/awf load b2` bila tidak autoload, periksa 20/20 penanda.
  4. Putaran 3: terbang dengan elytra menembus terrain baru di `b2` sambil `/awf save b2` setiap beberapa
     detik selama 5 menit; ambil thread dump di tengah.
  5. Putaran Redis: `redis-cli CLIENT PAUSE 10000 WRITE`, lalu `/awf save b2`.
- **Diharapkan:** setelah `Saved b2 (committed)` + kill -9, 20/20 ada (satu yang hilang = FAIL). Pada
  putaran 3 tiap balasan adalah `Saved b2 (committed)` atau `Failed: the chunk I/O of b2 did not drain
  within 3000 ms (aurora.awf.shutdown-deadline-ms); nothing was committed and this save is not
  durable`; dump menunjukkan paling banyak satu stack per dunia di `MoonriseRegionFileIO.flush`. Pada
  langkah 5 balasannya `Failed: …`, tidak pernah `Saved …`.
- **Gagal bila:** penanda hilang setelah `Saved`; `Saved` saat Redis di-pause; lebih dari satu drain per dunia.
- **Bukti:** daftar koordinat + screenshot sebelum/sesudah, log, thread dump, output `redis-cli`.
- **Catatan:** tiket region bawaan sebelum drain masih tidak dibatasi waktu (PM-089 remaining); drain
  yang timeout tetap memegang satu dari 64 permit I/O sampai selesai.

### B50-03 — Unload gagal tidak melaporkan sukses dan tidak menghentikan server

- **PM:** PM-001, PM-259, PM-009 (FR-005 merujuk ke sini). **Prioritas / risiko:** P0 / tinggi.
- **Konfigurasi:** backend Redis (blok standar).
- **Data:** dunia non-overworld `b3`, tanpa pemain, dengan chunk kotor (ubah blok lalu teleport keluar).
- **Langkah:**
  1. `redis-cli CLIENT PAUSE 60000`, lalu `/awf unload b3`.
  2. `/awf info b3` dan cek server masih berjalan.
  3. `redis-cli CLIENT UNPAUSE`, lalu `/awf unload b3` lagi.
  4. Jalur plugin `Bukkit.unloadWorld(b3, true)` saat Redis di-pause (jalur ini tidak menjalankan
     pre-save) — **BLOCKED (plugin uji tidak ada)**.
  5. Race: pause Redis setelah pre-save sukses tetapi sebelum commit penutupan — **tidak dapat dipicu
     andal**; catat bila kebetulan terjadi.
  6. Restart dengan Redis sehat, muat `b3`, bandingkan dengan state commit terakhir yang terkonfirmasi.
- **Diharapkan:** langkah 1: `Failed: AWF world b3 was not unloaded: its save before the unload did not
  complete durably (…); it stays loaded and the unload can be retried`; langkah 2: State `loaded`,
  server hidup; langkah 3: `Unloaded b3`. Langkah 4: ERROR `World b3 was unloaded, but Aurora World
  Fabric did not confirm the final commit of […] … The unload reports FAIL_UNKNOWN; the server keeps
  running`. Langkah 5: pengirim melihat `Unloaded b3, but AWF could not confirm its last commit (see the
  server log)` dan log berisi `Aurora World Fabric could not confirm the final commit of … on close;
  the chunks written since its last confirmed commit may or may not have been applied`.
- **Gagal bila:** `Unloaded b3` saat pre-save gagal; server berhenti; log `Error shutting down world … alerting global tick to shutdown`.
- **Bukti:** output perintah, log, `redis-cli` timeline.
- **Catatan:** nilai API terpisah `UNLOADED_NOT_DURABLE` adalah keputusan owner (belum ada).

### B50-04 — Unload sehat, `nosave`, dan dunia kosong

- **PM:** PM-001 (PR #61 B50-AWF-CLOSE), PM-289. **Prioritas / risiko:** P0 / sedang.
- **Langkah:**
  1. FILE dan Redis: `/awf unload <w>` sehat → `Unloaded <w>`; muat lagi, perubahan sebelum unload ada.
  2. `/awf unload <w> nosave` (butuh izin admin, §15 TO-CORE-06): perubahan setelah commit terakhir
     hilang, checkpoint terakhir tetap.
  3. Dunia yang baru dibuat dan belum pernah dikunjungi: unload lalu load.
  4. Redis: setelah unload, `redis-cli PTTL 'sbtest:awf:{world/dimensions/minecraft/<w>/region}:lock'` → `-2`
     (sama untuk `entities` dan `poi`).
  5. Regresi opsional: `test-plugin` dengan `-Dsourby.testplugin.probes=true`, perintah `/unloadprobe`.
- **Diharapkan:** callback selalu selesai; tidak ada lease/handle tertahan; tidak ada pesan `Could not unload` pada kasus sehat.
- **Gagal bila:** perubahan hilang setelah unload dengan save; lease masih ada; unload menggantung.

### TO-AWF-01 — Race eviction resident vs tulis chunk

- **PM:** PM-023. **Prioritas / risiko:** P1 / tinggi (edit hilang diam-diam).
- **Konfigurasi:** `aurora.awf.resident-chunks: 1`, `aurora.awf.commit-interval-seconds: 5`.
- **Langkah:** 1. Terbang lurus di dunia AWF sambil memasang/menghancurkan blok di batas chunk selama 5
  menit (catat 30 koordinat). 2. `save-all flush`, `stop`. 3. Start, periksa 30 koordinat.
- **Diharapkan:** semua edit ada; `/perf awf` tidak menunjukkan failures.
- **Catatan:** race ini **tidak dapat dipicu andal** lewat gameplay; PASSED di sini hanya regresi, bukan
  bukti race tertutup. Spesifikasi unit stress test ada di matrix (PM-023).

### TO-AWF-02 — Gagal hapus generasi/objek setelah commit tidak menggagalkan commit

- **PM:** PM-070. **Prioritas / risiko:** P2 / sedang. **Butuh:** Linux root.
- **Konfigurasi:** `aurora.awf.retained-generations: 1`.
- **Langkah:**
  1. Buat edit + `save-all flush` beberapa kali; catat N terbaru di `region.awf/generations-root/generations/`.
  2. `sudo chattr +i <store>/generations-root/generations/<N>`; edit lagi + `save-all flush`.
  3. Ulangi beberapa commit dalam 1 menit; lalu `sudo chattr -i …` dan commit lagi.
  4. Varian objek: `sudo chattr +i <store>/objects/<xx>/<nama>` untuk objek yang akan menjadi yatim.
- **Diharapkan:** flush sukses; satu WARN per menit per store dan jenis:
  `AWF store <root> committed generation <N+1>, but deleting the generations it no longer retains
  failed; … (<k> failure(s) since the last such warning)`; varian objek: `… object(s) no retained
  generation references could not be deleted; the commit stands …`. Setelah `chattr -i`, commit
  berikutnya menghapus sisa itu.
- **Gagal bila:** save/flush gagal, shutdown exit 70 karena cleanup, WARN per commit (bukan per menit).
- **Rollback:** `chattr -i` semua file. **Catatan:** kegagalan permanen membuat disk tumbuh tanpa metrik (remaining).

### TO-AWF-03 — Index store dengan nama objek bermusuhan ditolak

- **PM:** PM-071. **Prioritas / risiko:** P2 / sedang (keamanan path).
- **Data:** salinan store `region.awf` dari dunia uji (bukan aslinya).
- **Langkah:** 1. Di salinan, ubah satu baris `<x> <z> <nama>` di `generations-root/generations/<N>/index`
  agar nama berisi `../` dan panjangnya 64 karakter. 2. Perbarui sha/panjang di manifest generasi dan
  sha di `CURRENT` agar cocok (kalau tidak, pembukaan gagal lebih awal karena checksum — itu bukan tes
  ini). 3. Pasang salinan sebagai store dunia uji dan muat dunia itu.
- **Diharapkan:** pembukaan gagal dengan `malformed index line: …`; tidak ada file di luar store yang dihapus.
- **Ketergantungan:** hex editor/skrip untuk menghitung sha; prosedur rumit.

### TO-AWF-04 — Offset index `.awf` yang meluap ditolak saat dibuka

- **PM:** PM-206. **Prioritas / risiko:** P3 / sedang (alokasi ~2 GiB).
- **Langkah/Diharapkan:** file `.awf` dengan offset `Long.MAX_VALUE-8` dan panjang `Integer.MAX_VALUE`
  (CRC32C index diperbarui) harus ditolak `CorruptAwfException` (`… points outside the chunk data` /
  `… points outside the file`).
- **Status:** BLOCKED (tidak ada alat pembuat file `.awf` rusak; spesifikasi unit test di matrix PM-206).

### TO-AWF-05 — Temp objek yang hilang hanya menggagalkan satu percobaan commit

- **PM:** PM-207. **Prioritas / risiko:** P3 / rendah.
- **Langkah/Diharapkan:** jeda antara `put` dan `sync` (debugger), hapus `objects/<xx>/*.tmp`; flush
  berikutnya sukses (mungkin setelah retry internal) dengan satu kegagalan `a pending AWF object's
  temporary file … disappeared …`.
- **Status:** BLOCKED (butuh debugger/seam untuk berhenti di antara `put` dan `sync`).

### TO-AWF-06 — Open READ_ONLY/TEMPLATE tidak menghapus sisa crash

- **PM:** PM-208. **Prioritas / risiko:** P3 / rendah.
- **Langkah:**
  1. `/awf create src normal`, edit, `/awf save src`, `/awf unload src`, `/awf template save src t`.
  2. Server berhenti: di salah satu store template di `awf-templates/t/` buat
     `generations-root/CURRENT.tmp` dan `objects/ab/x.tmp`.
  3. Start; `/awf create i1 from t`; muat dan kunjungi `i1`.
  4. Periksa file sisa di store template masih ada; dunia `i1` membaca data template.
- **Diharapkan:** sisa di store template tidak dihapus (open TEMPLATE tidak membersihkan); tidak ada
  error. Open writable berikutnya pada store itu (bila ada) yang membersihkan.
- **Rollback:** hapus file sisa manual.

### TO-AWF-07 — `plugin-data` saat convert dan import `.awf`

- **PM:** PM-252 (+ follow-up wave 2). **Prioritas / risiko:** P2 / sedang (PDC dunia hilang).
- **Langkah:**
  1. Taruh file Slime berisi `BukkitValues` (< 48 KiB terkompresi) di `awf/imports/`;
     `/awf convert <file>.slime <file>.awf`.
  2. Salin `<file>.awf` dari `awf/exports/` ke `awf/imports/`, lalu `/awf import <file>.awf iw`.
  3. Rusak nilai metadata `plugin-data` di salinan `.awf` lain lalu import.
- **Diharapkan:** langkah 1: WARN `did not keep […]` tidak lagi menyebut `extra.BukkitValues`; langkah 2:
  setelah future selesai, PDC dunia memuat kunci asli dan tetap ada setelah restart bersih; langkah 3:
  import gagal dan tidak ada folder/entri registry yang dibuat.
- **Ketergantungan:** file `.slime` dengan BukkitValues dan plugin/inspeksi NBT untuk membaca PDC —
  **BLOCKED (tidak ada fixture file atau plugin pembaca PDC)**. `/awf export` belum menulis PDC dunia
  yang sudah ada (remaining).

### TO-AWF-08 — Bit flag chunk Slime v13 tak dikenal disebut di WARN

- **PM:** PM-258. **Prioritas / risiko:** P3 / rendah.
- **Diharapkan:** `/awf convert` file v13 dengan flag tak dikenal memberi satu WARN yang menyebut
  `chunk-flags.0x<bit>`.
- **Status:** BLOCKED (tidak ada file `.slime` v13 dengan bit flag tak dikenal di repo; tes unit
  `SlimeImporterTest.aspV13File` membuatnya di memori).

### TO-AWF-09 — Baris `/perf awf` "oldest dirty age"

- **PM:** PM-453. **Prioritas / risiko:** P3 / rendah. **LIVE.**
- **Konfigurasi:** `aurora.awf.commit-interval-seconds: 30`.
- **Langkah:** hancurkan satu blok di dunia AWF, jalankan `/perf awf` beberapa kali sampai commit.
- **Diharapkan:** baris `<storage> oldest dirty age: <n> ms` naik ±1000 ms per detik dan kembali ke
  `no dirty chunk` tepat setelah commit periodik atau `save-all flush`.

### TO-AWF-10 — Store yang tercatat (`knownStores`) hilang → load ditolak

- **PM:** PM-024 (FR-011 merujuk ke sini). **Prioritas / risiko:** P1 / tinggi (dunia kosong menimpa data).
- **Langkah:**
  1. `/awf create t1 normal`, `/awf save t1`, `/awf unload t1`.
  2. Periksa `sourbycraft_config/aurora-worlds.json`: entri `t1` memuat `knownStores`.
  3. Ganti nama `<level>/dimensions/minecraft/t1/region.awf` menjadi `region.awf.bak`; `/awf load t1`.
  4. Kembalikan nama; `/awf load t1`.
- **Diharapkan:** langkah 3: `Failed: AWF world t1 had a store … on file when it was last loaded or
  saved (recorded in sourbycraft_config/aurora-worlds.json), and that store is missing; refusing to
  load the world fresh in its place …`; tidak ada `region.awf` baru; langkah 4 sukses.
- **Batas kompatibilitas:** entri pra-Build-50 tanpa `knownStores` masih membuka store kosong sekali,
  sampai load/save pertama di kandidat mencatatnya.

### TO-AWF-11 — Instance tanpa template ditolak di semua jalur load

- **PM:** PM-072 (AWF-009 merujuk ke sini). **Prioritas / risiko:** P1 / tinggi.
- **Langkah:** template `t` + instance `i`; stop; pindahkan `awf-templates/t` keluar; start; `/awf load i`.
- **Diharapkan:** gagal dengan `AWF world folder … is an instance of template 't', which is missing (no
  awf-templates/t/template.json); refusing to open it without its template …`; tidak ada commit ke
  store `i`. Kembalikan template → dunia termuat.
- **Catatan:** jalur `Bukkit.createWorld(new WorldCreator("i"))` dari plugin — BLOCKED (plugin uji tidak ada).

### TO-AWF-12 — Nama dunia terlarang di registry

- **PM:** PM-073 (AWF-010 merujuk ke sini). **Prioritas / risiko:** P2 / sedang.
- **Langkah:** stop; tambahkan `{"name":"world","template":"t"}` dan `{"name":"overworld"}` ke
  `sourbycraft_config/aurora-worlds.json` (sesuaikan bila level-name bukan `world`); start;
  `/awf create overworld`.
- **Diharapkan:** dua WARN `Aurora World Fabric: ignoring the registry entry 'world': … is the server's
  level-name …` dan `… 'overworld' is a vanilla dimension name …`; tidak ada
  `world/dimensions/minecraft/overworld/region.awf`; dunia utama tetap memakai region file;
  `/awf create overworld` ditolak.

### TO-AWF-13 — Registry kosong/terpotong dan tulis registry yang durable

- **PM:** PM-074. **Prioritas / risiko:** P2 / tinggi (semua dunia terkelola hilang diam-diam).
- **Langkah:** 1. Stop; `truncate -s 0 sourbycraft_config/aurora-worlds.json`; start.
  2. Opsional (root): `strace -f -e trace=fsync,fdatasync -p <pid>` selama `/awf create x normal`.
  3. Opsional: disk lambat (`dm-delay`), `/awf list` selama `/awf create`.
- **Diharapkan:** 1: WARN AWF berisi `… is empty or null, not a world list (damaged by a crash?); restore
  it or remove it`; API AuroraWorlds tidak start; tidak ada entri yang dibuang diam-diam. 2: file temp
  dan direktori `sourbycraft_config` di-fsync. 3: `/awf list` langsung menjawab.
- **Catatan:** `/awf autoload` dari konsol masih menulis + fsync di thread global tick (API sinkron;
  keputusan owner).

### TO-AWF-14 — Pembuatan dunia yang gagal dibersihkan dengan aman

- **PM:** PM-209. **Prioritas / risiko:** P3 / sedang.
- **Diharapkan:** setelah create gagal (generator plugin melempar exception di chunk pertama), create
  ulang dengan nama sama tidak gagal `AWF storage … is already open`; bila cleanup bertabrakan dengan
  close, WARN `… stays registered after its failed creation …` dan `/awf delete <nama> confirm` kemudian sukses.
- **Status:** BLOCKED (butuh plugin generator yang melempar).

### TO-AWF-15 — Fence baca region juga menahan tulis POI chunk yang sama

- **PM:** PM-210. **Prioritas / risiko:** P2 / tinggi (POI villager tertimpa).
- **Konfigurasi:** backend Redis.
- **Langkah:** chunk C berisi villager + workstation; unload C (menjauh); `redis-cli CLIENT PAUSE 15000`;
  dekati C (baca gagal, C diregenerasi); unpause; `/perf awf`; menjauh agar C unload; kembali.
- **Diharapkan:** `/perf awf` menunjukkan refused writes > 0 untuk storage poi; POI workstation asli utuh;
  log `AWF read chunk … of … again; its stored bytes were loaded and writes of it are accepted again` muncul.
- **Catatan:** timing bergantung pada kapan chunk dibaca; bila baca tidak gagal, tulis `BLOCKED (race tidak terpicu)`. Entitas sengaja tidak di-fence.

### TO-AWF-16 — WARN macet maintenance AWF

- **PM:** PM-223. **Prioritas / risiko:** P3 / rendah. **Butuh:** debugger JDWP.
- **Langkah:** tangguhkan thread `SourbyCraft-AWF-Maintenance` > 30 s; lanjutkan.
- **Diharapkan:** satu WARN `Aurora World Fabric maintenance (SourbyCraft-AWF-Maintenance) has not finished
  a pass for 30 s …`; setelah lanjut satu INFO `… periodic AWF commits resumed`.

### TO-AWF-17 — Laporan shutdown dengan Redis terhenti

- **PM:** PM-226. **Prioritas / risiko:** P1 / tinggi.
- **Langkah:** dunia Redis dengan chunk kotor; `redis-cli CLIENT PAUSE 120000`; `stop`; catat exit code;
  unpause; `redis-cli --scan --pattern 'sbtest:awf:*lock'`.
- **Diharapkan:** exit 70, `AWF-SHUTDOWN-INCOMPLETE`, setiap storage disebut sekali; setelah stop tidak ada
  lease dari server ini (termasuk store yang hanya disiapkan oleh create yang terputus).
- **Gagal bila:** exit 0 dengan commit tidak terkonfirmasi; storage dilaporkan dua kali; lease tertinggal.
- **Prosedur terkait:** RF-004 di [03](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) (urutan shutdown sehat).

### TO-AWF-18 — Open kedua serentak pada folder storage yang sama

- **PM:** PM-225. **Diharapkan:** `IOException` `AWF storage … is already being opened` tanpa menyentuh store.
- **Status:** BLOCKED (butuh tes konkurensi dua thread; tidak dapat dipicu andal lewat perintah).

### TO-AWF-19 — Baris "AWF loaded worlds"

- **PM:** PM-260. **Langkah:** dua dunia AWF dimuat → `/perf awf`; unload satu → `/perf awf`.
- **Diharapkan:** `AWF loaded worlds` = 2, lalu 1.

### TO-AWF-20 — Validasi spawn dan toleransi spawn pra-Build-50

- **PM:** PM-290 (+ follow-up wave 2/3). **Prioritas / risiko:** P1 / tinggi (stage `aurora worlds` gagal).
- **Langkah:**
  1. `/awf set <w> spawn NaN 64 0`, `… Infinity 64 0`, `… 1e300 64 0`, `… 0 5000 0`, lalu
     `/awf set <w> spawn 0.5 64 0.5 90` dan restart.
  2. Stop; edit `aurora-worlds.json` agar dunia `isle` punya `properties.spawn` `{x:40000000,y:64,z:0,yaw:0}`
     di samping dunia valid `hub`; start.
  3. `/awf set isle spawn 0 64 0` (atau load `isle`); restart.
  4. Stop; set `properties.saveBounds` dengan min > max; start.
  5. Template: `spawn` y=5000 di `awf-templates/<t>/template.json`; `/awf create j from <t>`.
  6. Import Slime dengan `spawnY=9999` — BLOCKED (tidak ada file uji).
- **Diharapkan:** 1: setiap nilai buruk `Failed: spawn x must be a finite number from -29999984 to
  29999984, not NaN` (atau pesan y/z/yaw yang setara); `/awf info` tetap spawn lama; nilai valid bertahan
  setelah restart. 2: WARN `Aurora World Fabric: ignoring the stored spawn of world 'isle' in … ` (dua kali
  saat boot: start engine dan world service); kedua dunia terdaftar dan bisa dimuat. 3: file tidak lagi
  memuat spawn buruk; boot berikutnya tanpa WARN. 4: world service gagal dengan
  `… holds a world entry with invalid properties (bounds min must not exceed max); fix or remove it`.
  5: WARN per create; instance tanpa spawn. 6: import sukses tanpa spawn, WARN menyebut
  `properties.spawnX/spawnY/spawnZ`.
- **Catatan:** `.awf` hasil export pra-Build-50 dengan spawn di luar dunia masih menggagalkan import
  seluruhnya (remaining). Load lama mungkin sudah menulis spawn buruk ke level data; set spawn baru.

### Hasil area 7

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B50-02 | Batas save + kill -9 | PM-089/211/007 | P0 | READY FOR OWNER TEST | | | |
| B50-03 | Unload gagal | PM-001/259/009 | P0 | READY (1–3, 6); BLOCKED (4: plugin); race 5 tidak andal | | | |
| B50-04 | Unload sehat/nosave/kosong | PM-001/289 | P0 | READY FOR OWNER TEST | | | |
| TO-AWF-01 | Race eviction | PM-023 | P1 | READY (regresi saja; race tidak dapat dipicu andal) | | | |
| TO-AWF-02 | Cleanup pasca-commit | PM-070 | P2 | READY FOR OWNER TEST | | | |
| TO-AWF-03 | Nama objek bermusuhan | PM-071 | P2 | READY FOR OWNER TEST | | | |
| TO-AWF-04 | Offset `.awf` meluap | PM-206 | P3 | BLOCKED (alat file rusak) | | | |
| TO-AWF-05 | Temp objek hilang | PM-207 | P3 | BLOCKED (debugger/seam) | | | |
| TO-AWF-06 | Sisa crash di store template | PM-208 | P3 | READY FOR OWNER TEST | | | |
| TO-AWF-07 | `plugin-data` convert/import | PM-252 | P2 | BLOCKED (fixture Slime + pembaca PDC) | | | |
| TO-AWF-08 | Flag Slime tak dikenal | PM-258 | P3 | BLOCKED (file Slime v13) | | | |
| TO-AWF-09 | Oldest dirty age | PM-453 | P3 | READY FOR OWNER TEST | | | |
| TO-AWF-10 | `knownStores` | PM-024 | P1 | READY FOR OWNER TEST | | | |
| TO-AWF-11 | Template hilang | PM-072 | P1 | READY FOR OWNER TEST | | | |
| TO-AWF-12 | Nama terlarang | PM-073 | P2 | READY FOR OWNER TEST | | | |
| TO-AWF-13 | Registry kosong + fsync | PM-074 | P2 | READY FOR OWNER TEST | | | |
| TO-AWF-14 | Create gagal | PM-209 | P3 | BLOCKED (plugin generator) | | | |
| TO-AWF-15 | Fence POI | PM-210 | P2 | READY (bisa BLOCKED: race tidak terpicu) | | | |
| TO-AWF-16 | Maintenance macet | PM-223 | P3 | READY (butuh JDWP) | | | |
| TO-AWF-17 | Shutdown Redis terhenti | PM-226 | P1 | READY FOR OWNER TEST | | | |
| TO-AWF-18 | Open serentak | PM-225 | P3 | BLOCKED (tes konkurensi) | | | |
| TO-AWF-19 | AWF loaded worlds | PM-260 | P3 | READY FOR OWNER TEST | | | |
| TO-AWF-20 | Validasi spawn | PM-290 | P1 | READY (1–5); BLOCKED (6: file Slime) | | | |

---

## 8. Backend Redis AWF dan Redis Fabric (TO-RF)

Standar area: Redis 7.2+ khusus uji, `appendonly yes`; konfigurasi Redis di §7. Fabric:

```yaml
aurora:
  awf:
    redis:
      uri: "redis://127.0.0.1:6379/0"
      fabric:
        enabled: true
```

Rollback: hentikan server, `redis-cli --scan --pattern 'sbtest:awf:*' | xargs -r redis-cli DEL` pada
Redis uji (bukan produksi), pulihkan direktori. File identitas `awf/node-id`, `awf/incarnation`,
`awf/node.lock` di direktori server: lihat §4.3. Prosedur RF-001…006 di
[03](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) (RF-006 merujuk ke TO-RF-01).

### TO-RF-01 — URI Redis tidak valid ditolak tanpa fallback

- **PM:** PM-027, PM-213. **Prioritas / risiko:** P1 / tinggi (menulis ke Redis yang salah).
- **Konfigurasi:** Redis lokal berjalan di 6379; `aurora.awf.redis.uri: "redis://:bad-port"`,
  `aurora.awf.redis.fabric.enabled: true`, `aurora.awf.backend: redis`.
- **Langkah:**
  1. Start; catat log; `redis-cli CLIENT LIST`; `redis-cli --scan --pattern 'sourbycraft:awf:*'`.
  2. Hapus kunci uri; `export SOURBYCRAFT_AWF_REDIS_URI='redis://u:p@ss@word@'`; start;
     `grep -c 'ss@word' logs/latest.log`.
  3. File global YAML rusak (indentasi tab) dengan uri hanya di file global → start; lalu uri di `aurora.yml`.
- **Diharapkan:** 1: satu ERROR `Aurora Redis Fabric could not start: the Redis connection is
  misconfigured: aurora.awf.redis.uri is not a usable Redis URI: … The fabric is off (no default server
  is used) …` dan ERROR AWF `the Redis backend is misconfigured: …`; tidak ada klien server di
  `CLIENT LIST`; tidak ada kunci baru; dunia Redis gagal dimuat; server mencapai `Done (`. 2: ERROR
  menyebut variabel lingkungan; grep = 0. 3: uri tidak diketahui (`… is unknown because <file> could not
  be read`) kecuali `aurora.yml` mengatur uri.
- **Gagal bila:** koneksi ke 127.0.0.1:6379 terjadi; nilai URI/password muncul di log.

### TO-RF-02 — Redaksi URI di log

- **PM:** PM-213. **Prioritas / risiko:** P2 / tinggi (kebocoran password).
- **Langkah:** `redis-cli ACL SETUSER awf on '>s3cr@t' '~*' '+@all'`; uri
  `"redis://awf:s3cr%40t@127.0.0.1:6379/0"`; start; lalu uri tidak valid `"redis://u:p@ss@h"` dan env
  `SOURBYCRAFT_AWF_REDIS_URI='redis://u:x%zzy@h'` (kunci dihapus).
- **Diharapkan:** baris boot menampilkan `redis://***@…`; `grep -E 'p@ss|ss@h|x%zz|zzy|s3cr' logs/latest.log` tanpa hasil.

### TO-RF-03 — Identitas node, incarnation dan takeover lease

- **PM:** PM-025. **Prioritas / risiko:** P1 / tinggi (dua proses menulis dunia yang sama).
- **Konfigurasi:** backend Redis, satu dunia Redis `rw`; `HOSTNAME` dan path direktori sama untuk
  skenario salinan (container atau bind mount).
- **Langkah dan hasil yang diharapkan:**
  1. (a) Salinan saat berjalan: start A; salin direktori A (termasuk `awf/`) saat A hidup; start B dari
     salinan dengan hostname/path sama. Log B: `Aurora node identity: node-…, incarnation … (no previous
     run proven stopped in this directory)`; open `rw` di B gagal `… is held by another process with this
     server's identity …`; `redis-cli GET 'sbtest:awf:{world/dimensions/minecraft/rw/region}:lock'` masih
     token A (`<owner>/<incarnation A>/<uuid>`). Dengan MultiProfile provider
     redis, klaim B atas profil yang dipegang A ditolak sebagai duplikat.
  2. (b) Crash restart: `kill -9` A, start lagi di direktori dan container/volume yang sama. Log:
     `… (previous run <uuid>)` lalu `Aurora World Fabric: took over this server's Redis lease on … from its
     previous run …`; dunia langsung termuat.
  3. (c) Proses kedua di direktori yang sama saat A hidup: WARN `Aurora node identity: another running
     process holds …/node.lock …`; open lease proses kedua ditolak.
  4. (d) Restart bersih: tidak ada baris takeover; lease `OK`.
  5. (e) Setelah reboot host: boot id berbeda → `no previous run proven stopped in this directory`; lease
     lama ditunggu sampai kedaluwarsa.
- **Gagal bila:** B mengambil lease A yang masih hidup; crash restart menunggu padahal lock/boot sama.
- **Batas:** NFS tanpa lock dan clone VM berikut memorinya tidak didukung. Overlay container yang device
  id-nya berubah membuat restart menunggu kedaluwarsa (aman, lebih lambat).

### TO-RF-04 — Framing RESP buruk menjadi IOException

- **PM:** PM-026. **Prioritas / risiko:** P2 / sedang. **Butuh:** `socat` atau `nc`.
- **Langkah:** server palsu tanpa password, misalnya
  `socat TCP-LISTEN:6390,reuseaddr,fork SYSTEM:"printf ':abc\r\n'"`, lalu
  `aurora.awf.redis.uri: "redis://127.0.0.1:6390/0"`; ulangi dengan balasan `*9999999999\r\n`.
- **Diharapkan:** kegagalan berisi `Redis reply framing: expected a number` atau `Redis array reply of …
  values`; tidak ada `NumberFormatException` atau `OutOfMemoryError`; panggilan berikutnya membuka koneksi baru.

### TO-RF-05 — Delete/retire ditolak saat store terbuka di server lain

- **PM:** PM-212. **Prioritas / risiko:** P1 / tinggi.
- **Konfigurasi:** dua server, **direktori berbeda** (identitas berbeda), `level-name`, Redis dan `key-prefix`
  sama. ID store relatif terhadap direktori server (`world/dimensions/minecraft/<w>/region`), jadi nama dunia
  yang sama memakai kunci Redis yang sama.
- **Langkah:** 1. A: `/awf create rw normal`, edit, `/awf save rw`. 2. B berhenti: salin entri `rw` dari
  `aurora-worlds.json` A ke registry B (tanpa autoload); start B. 3. `redis-cli EXISTS
  'sbtest:awf:{world/dimensions/minecraft/rw/region}:chunks'` (juga `:gen`, `:writer`). 4. B:
  `/awf delete rw confirm` (atau jalur export yang me-retire). 5. Ulangi langkah 3. 6. A: `/awf unload rw`;
  ulangi langkah 4 di B.
- **Diharapkan:** langkah 4 `Failed: AWF store … is open on a server (or a server stopped without releasing its
  lease, which then expires after lease-seconds); unload it there first`; kunci A tidak berubah; setelah A
  unload, operasi B sukses.

### TO-RF-06 — WARN celah lease dan baris `AWF Redis leases`

- **PM:** PM-262. **Prioritas / risiko:** P2 / sedang.
- **Langkah:** 1. Buka dunia Redis; `kill -STOP <pid>` > 60 s (tanpa server lain); `kill -CONT <pid>`.
  2. `redis-cli DEBUG SLEEP 50` (atau `docker pause` Redis) sambil `/perf awf` berulang.
- **Diharapkan:** 1: tepat satu WARN: `… more than 60 s (the lease) after it was last extended …` atau
  `… had lapsed …`. 2: setelah > 40 s tanpa perpanjangan (dua periode renewal), baris `AWF Redis leases`
  berbunyi `0 held; <id> not renewed for N s, … (lease 60 s)` untuk setiap store yang terbuka (satu dunia
  biasanya punya tiga: `region`, `entities`, `poi`); setelah pulih semua store kembali `held`; bila server
  lain memegang lease, baris menunjukkan `<id> lost`.

### TO-RF-07 — Event stream tetap menerima publish selama consumer mengikuti

- **PM:** PM-253. **Diharapkan:** dengan `services.events: true`, `events.retention: 100`, consumer group
  yang membaca dan ACK, 300 publish tidak memicu `FabricBackpressureException` dan `XLEN` sekitar 100;
  tanpa consumer, publish ditolak setelah lag + pending mencapai 100.
- **Status:** BLOCKED (tidak ada perintah atau plugin untuk menerbitkan 300 event; tes unit `EventFabricTest` hanya kasus tanpa group).

### TO-RF-08 — Commit AWF Redis dikonfirmasi `WAITAOF` pada koneksi commit

- **PM:** follow-up wave 3 (WP10 redis-commit-waitaof). **Prioritas / risiko:** P1 / tinggi (commit dianggap durable padahal belum).
- **Langkah:** 1. Redis `appendonly yes`, `appendfsync always`; dunia Redis; edit + `/awf save <w>`.
  2. `CONFIG SET appendonly no`; edit + save. 3. Regresi otomatis (owner):
  `./gradlew :sourbycraft-server:test --tests '*RedisBackendTest'` dengan `redis-server` terpasang.
- **Diharapkan:** 1: `Saved <w> (committed)`; 2: satu WARN bahwa Redis tidak mengonfirmasi AOF dan
  wait-for-aof dimatikan, save tetap sukses; commit yang diterapkan tapi tidak dikonfirmasi tepat waktu
  melempar `did not write the commit to its append-only file within N ms`. Batas waktu WAITAOF sekarang
  setengah sisa client timeout (paling lama ±2500 ms dengan default 5000; lebih pendek dekat deadline shutdown).
- **Catatan:** `commitsWaitForTheAppendOnlyFileWhenRedisHasOne` dan `withoutAnAppendOnlyFileCommitsStillWorkAndSaySo` adalah test lama yang melewati jalur ini.

### TO-RF-09 — `/awf redis` memakai executor administratif dan menjawab `Busy`

- **PM:** PM-248. **Prioritas / risiko:** P2 / sedang (probe menyumbat lane STORAGE).
- **Langkah:** fabric aktif; matikan `redis-server`; `/awf redis status` ±20 kali cepat sambil `/awf save <w>` (dunia FILE) dan `/perf lanes`.
- **Diharapkan:** save tidak menunggu di belakang probe; antrean STORAGE tidak terisi probe; tiap probe
  `UNREACHABLE` atau `Failed: …` setelah timeout; saat 64 slot habis: `Busy: … ; try again shortly`, bukan error internal.

### TO-RF-10 — Label `/awf redis ownership`

- **PM:** follow-up wave 3 (awfcommand-ownership). **Langkah:** `/awf redis ownership <uuid>` dan `/awf redis sessions <uuid>`.
- **Diharapkan:** baris ownership diawali `coordination claim (API-only; profiles: /awf multiprofile owner): `; sessions tanpa label.

### Hasil area 8

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-RF-01 | URI tidak valid | PM-027/213 | P1 | READY FOR OWNER TEST | | | |
| TO-RF-02 | Redaksi URI | PM-213 | P2 | READY FOR OWNER TEST | | | |
| TO-RF-03 | Identitas node | PM-025 | P1 | READY FOR OWNER TEST | | | |
| TO-RF-04 | Framing RESP | PM-026 | P2 | READY (butuh socat/nc) | | | |
| TO-RF-05 | Delete/retire saat terbuka | PM-212 | P1 | READY (dua direktori server) | | | |
| TO-RF-06 | Celah lease | PM-262 | P2 | READY FOR OWNER TEST | | | |
| TO-RF-07 | Event retention | PM-253 | P3 | BLOCKED (penerbit event) | | | |
| TO-RF-08 | WAITAOF per commit | wave 3 | P1 | READY FOR OWNER TEST | | | |
| TO-RF-09 | `/awf redis` Busy | PM-248 | P2 | READY FOR OWNER TEST | | | |
| TO-RF-10 | Label ownership | wave 3 | P3 | READY FOR OWNER TEST | | | |

---

## 9. MultiProfile (B50-05…15, TO-MP, MP-010)

Standar area:

- **Konfigurasi dasar** (`sourbycraft_config/aurora.yml`, semua RESTART_REQUIRED; default dari
  `ProfileSettings.DEFAULT`):

  ```yaml
  aurora:
    awf:
      multiprofile:
        enabled: true
        mode: standalone            # distributed hanya dengan repository: redis (EXPERIMENTAL)
        repository: file            # file | redis
        default-slots: 3
        max-slots: 8
        switching: { enabled: true, timeout-ms: 15000, require-durable-save: true }  # timeout 1000–600000
        worlds: { idle-unload-seconds: 300 }   # 0–86400; sweep tiap 30 s
        coordination: { provider: local, fencing-enabled: true, single-active-session: true }
        directory: awf/profiles
        redis: { require-aof: true, wait-for-aof: true }   # hanya untuk repository: redis
  ```
- **Perintah** (`sourbycraft.command.awf.multiprofile`; yang bertanda * juga butuh
  `sourbycraft.command.awf.multiprofile.admin`): `status`, `list <player>`, `create* <player> [type]`,
  `switch* <player> <profile>`, `inspect <profile>`, `bind* <profile> <world> [template]`,
  `unbind* <profile>`, `recover* <transition>`, `lock*|unlock* <profile>`, `slots* <player> <n>`,
  `owner <profile>`, `reclaim* <profile> <epoch>`, `migrate-repository* confirm`. **Tidak ada** `save`
  atau `delete` di CLI; keduanya hanya lewat API `AuroraProfiles` (plugin probe tidak ada, §4.4).
- **Data:** akun uji dengan inventaris bertanda (item bernama, shulker bernama), XP, efek, ender chest.
  Folder repository: `awf/profiles/{accounts,profiles,bindings,snapshots,transitions}`, `repository.lock`.
- **Bukti standar:** `/awf multiprofile status`, `list`, `inspect`, `ls -la awf/profiles/*`,
  `sha256sum` snapshot, screenshot inventaris sebelum/sesudah, log `AWF MultiProfile: …`.
- **Rollback standar:** stop; pulihkan `awf/profiles/` + playerdata (`world/playerdata/`) dari backup;
  untuk Redis repository lihat §4.3. Jangan downgrade dengan journal pending.
- Prosedur MP-001…010 di [03](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md) — NEEDS RETEST.

### B50-05 — Batas slot di bawah create bersamaan

- **PM:** PR #61 B50-MP-ACCOUNT, PM-091, PM-005. **Prioritas / risiko:** P0 / tinggi (lost update akun).
- **Langkah:** 1. Varian API: 20 `createProfile` serentak dari plugin untuk akun dengan 3 slot, juga saat
  switch/delete — **BLOCKED (plugin probe tidak ada)**. 2. Varian CLI (bukan race sejati; semua langkah
  profil diserialkan `ProfileIoQueue`): kirim 20 `/awf multiprofile create <player>` secepat mungkin
  lewat RCON (mis. loop `mcrcon` di background).
- **Diharapkan:** paling banyak 3 sukses (`Created profile …`), sisanya `Failed: the account already owns 3 of
  3 profiles` (kode `SLOT_LIMIT`; CLI hanya mencetak pesan); setiap profil yang sukses ada di `list`;
  revisi akun konsisten.
- **Gagal bila:** > 3 profil; profil sukses tidak ada di akun.

### B50-06 — Capture basi A→B→A tidak menulis snapshot

- **PM:** PR #61 B50-MP-SAVE, PM-004. **Prioritas / risiko:** P0 / tinggi (item B masuk A).
- **Diharapkan:** save lama gagal (`SWITCH_IN_PROGRESS` atau `active profile changed`); tidak ada snapshot.
- **Status:** BLOCKED (butuh seam untuk menunda `PlayerStatePort.capture` atau plugin yang memanggil
  `saveProfile` saat switch). Gameplay saja tidak menjamin race terpicu: jangan tandai PASSED.

### B50-07 — Lease kedaluwarsa saat capture/tulis

- **PM:** PR #61 B50-MP-SAVE, PM-004. **Prioritas / risiko:** P0 / tinggi.
- **Konfigurasi varian manual:** provider redis + fabric (`sessions.heartbeat-seconds: 1`,
  `sessions.ttl-seconds: 5` — minimum 5, harus > heartbeat).
- **Langkah varian manual:** mulai switch, segera `redis-cli CLIENT PAUSE 8000`.
- **Diharapkan:** klaim lama ditolak sebelum snapshot ditulis (`OWNERSHIP_CONFLICT`) atau I/O gagal;
  tidak ada snapshot setengah jadi. Ini bukan bukti fencing lintas node (lihat MP-010).
- **Status:** BLOCKED untuk varian deterministik (seam); varian manual timing-dependent → bila tidak terpicu, `BLOCKED (race tidak terpicu)`.

### B50-08 — Recovery bersaing dengan operasi lain

- **PM:** PR #61 B50-MP-RECOVER, PM-077, PM-004. **Prioritas / risiko:** P0 / tinggi.
- **Langkah:** 1. Buat journal RECOVERING (TO-MP-02). 2. Saat pending: `switch`, `bind`, `lock` lewat CLI.
  3. Jalankan `/awf multiprofile recover <id>` dua kali cepat. 4. save/delete API — **BLOCKED (plugin)**.
- **Diharapkan:** langkah 2: switch `Refused: RECOVERY_REQUIRED — …`, bind/lock `Failed: …` (kode
  `RECOVERY_REQUIRED`); langkah 3: satu diterima, yang lain `Recovery result: SWITCH_IN_PROGRESS — …`;
  setelah selesai/gagal reservasi dilepas (switch berikutnya bisa jalan).

### B50-09 — Bind dunia profil, restart, switch masuk

- **PM:** PR #61 B50-MP-BIND, PM-107, PM-288. **Prioritas / risiko:** P1 / sedang.
- **Langkah:** 1. Dua profil; pada profil inactive: `/awf multiprofile bind <uuid> Island_B50 <template>`.
  2. `inspect`; restart; `switch` ke profil itu. 3. Coba bind profil aktif/terklaim/terhapus atau saat transisi.
- **Diharapkan:** binding tersimpan sebagai `island_b50` / template huruf kecil; bertahan restart; saat switch
  dunia dibuat dari template dan dimuat; dengan `isolation.location: true` profil baru mendarat di spawn
  dunia PRIMARY (tengah blok spawn). Langkah 3 ditolak: `Failed: only an inactive, non-deleted profile can be
  rebound` (`PROFILE_LOCKED`), `Failed: release the profile ownership before rebinding` (`OWNERSHIP_CONFLICT`)
  atau `Failed: account transition in progress` (`SWITCH_IN_PROGRESS`). Bila spawn tak diketahui, pemain
  tetap di tempat dengan WARN dan switch sukses.

### B50-10 — Izin dan input binding

- **PM:** PR #61 B50-MP-BIND, PM-288. **Prioritas / risiko:** P1 / sedang.
- **Langkah:** bind/unbind tanpa izin admin; `bind <uuid> ../world`; UUID tidak valid; nama kapital; API map > 16 entri (BLOCKED: plugin).
- **Diharapkan:** tanpa izin: `You do not have sourbycraft.command.awf.multiprofile.admin`; `../world` →
  `invalid AWF world name: ../world`; UUID buruk → `Not a profile uuid: …`; nama kapital diubah ke huruf
  kecil (tidak lagi ditolak); > 16 → `at most 16 worlds may be bound`. Tidak ada binding tertulis atau
  akses folder di luar AWF. API hanya untuk plugin tepercaya, bukan batas sandbox.

### B50-11 — Unbind

- **PM:** PR #61 B50-MP-BIND. **Langkah:** switch keluar, tunggu klaim lepas, `/awf multiprofile unbind <uuid>`.
- **Diharapkan:** `Replaced world binding of <uuid>; existing worlds were not deleted`; binding kosong; dunia
  dan template tidak dihapus. Bind CLI mengganti seluruh binding dengan satu PRIMARY; multiworld lewat API.

### B50-12 — Retry idle unload dan hitungan sukses

- **PM:** PR #61 B50-MP-IDLE, PM-263, PM-092, PM-348, XREQ AwfProfileWorldPort. **Prioritas / risiko:** P2 / sedang.
- **Konfigurasi:** `worlds.idle-unload-seconds: 60`; dunia binding di backend Redis (untuk memaksa gagal).
- **Langkah:**
  1. Biarkan dunia binding kosong > 60 s. 2. Berdiri di dunia itu > 60 s. 3. Paksa gagal: saat dunia
     kosong dan hampir idle, `redis-cli CLIENT PAUSE 30000` agar save sebelum unload gagal; unpause.
  4. Tunggu `idle-unload-seconds` penuh lagi plus satu sweep (30 s).
- **Diharapkan:** 1: dunia di-unload, INFO `AWF MultiProfile: idle unload sweep unloaded 1 world(s)`, baris
  status `last idle sweep: 1 unloaded, 0 failed, …`. 2: tidak ada save/unload/WARN
  (dunia berpenghuni hanya disentuh ulang; bukan kegagalan). 3: WARN `idle unload of '<w>' failed: …`,
  INFO `… 0 world(s); 1 failed and stay eligible`, dunia tetap loaded. 4: retry terjadi **setelah timeout
  idle penuh berikutnya**, bukan pada sweep berikutnya. Hitungan `unloaded` hanya unload sukses.
- **Catatan:** pemain yang masuk di antara cek dan unload memberi satu WARN `PLAYERS_PRESENT` (remaining).

### B50-13 — Mode distributed: penolakan dan syaratnya

- **PM:** PR #61 B50-MP-FENCE, PM-084, PM-037. MP-009 merujuk ke sini. **Prioritas / risiko:** P1 / tinggi.
- **Langkah dan hasil:**
  1. `mode: distributed`, `repository: file`, fabric valid, provider redis, fencing true → boot gagal
     stage MultiProfile: `AWF MultiProfile could not start: mode: distributed requires repository: redis:
     the file repository cannot atomically fence its writes with Redis ownership …`; service tidak terdaftar.
  2. `mode: distributed` tanpa fabric → `… requires aurora.awf.redis.fabric.enabled: true and a reachable
     Redis …`; provider local → `… requires coordination.provider: redis`; `fencing-enabled: false` →
     `… requires coordination.fencing-enabled: true …`.
  3. `mode: distributed` + `repository: redis` + provider redis + fencing + fabric → start dengan WARN
     `profiles in the shared Redis repository are EXPERIMENTAL and not qualified on two nodes (owner test
     MP-010) …` (lanjut ke TO-MP-31…40).
  4. Kembalikan `standalone` + `repository: file` dengan direktori eksklusif per server.

### B50-14 — Record terbatas dan rusak

- **PM:** PR #61 B50-MP-RECORD, PM-004. **Prioritas / risiko:** P1 / tinggi.
- **Data:** salinan `awf/profiles/` (bukan asli).
- **Langkah:** di salinan: record terlalu besar (> 8 MiB + 64 KiB), terpotong, checksum salah; ulangi saat
  file tumbuh melewati batas selama dibaca.
- **Diharapkan:** `IO_FAILURE`; tidak ada alokasi seluruh file; data lama tidak ditimpa otomatis.

### B50-15 — Kontensi dan shutdown

- **PM:** PR #61 B50-MP-ACCOUNT, PM-091, PM-076. **Prioritas / risiko:** P1 / tinggi.
- **Butuh:** beberapa klien/bot (mis. SoulFire/mineflayer) — kebutuhan lingkungan.
- **Langkah:** banyak akun create/switch bersamaan + load dunia AWF; lalu `stop`.
- **Diharapkan:** drain selesai; tidak ada deadlock/starvation lane STORAGE; tidak ada `INTERNAL_ERROR`
  karena antrean penuh (kapasitas 4096 langkah); saat stop dengan pemain online satu INFO
  `AWF MultiProfile: closed with N player(s) online; they get no quit snapshot …` (bukan WARN per pemain).
  Catat queue wait dan p95/p99 dari `/perf governor`; serialisasi metadata dapat menambah latensi (tidak ada klaim skalabilitas).

### TO-MP-01 — Urutan fase switch dan freeze

- **PM:** PM-033. **Prioritas / risiko:** P0 / tinggi (duplikasi item).
- **Langkah:** bind profil ke template yang dunianya belum ada; `switch`. Selama `Switching...`: drop item,
  ambil item/XP, pindah item di chest, pasang blok, makan. Switch kembali. Ulangi dengan template `nope`
  (tidak ada → `WORLD_UNAVAILABLE`) dan dengan `switching.timeout-ms: 1000` pada dunia lambat.
- **Diharapkan:** aksi ditolak sejak capture (setelah dunia siap) sampai switch selesai; tidak ada item
  ganda; pada kegagalan sebelum apply inventaris tidak berubah, journal FAILED, pemain tidak lagi beku.
- **Catatan:** damage, drop kematian, lapar, `/give`, break blok dan plugin yang mengedit inventaris tidak ditahan.

### TO-MP-02 — Kompensasi dan journal RECOVERING

- **PM:** PM-029. **Langkah:** putus klien saat teleport switch (target jauh) atau kill klien setelah
  `Switching...`; `status`; join lagi. Ulangi kegagalan sebelum apply (template `nope`).
- **Diharapkan:** status menampilkan journal `RECOVERING` dengan alasan; saat join akun dan inventaris kembali
  ke profil sebelumnya dan journal hilang; kegagalan sebelum apply meninggalkan record FAILED dan inventaris utuh.

### TO-MP-03 — Recovery fence/incarnation setelah kill -9

- **PM:** PM-032 (MP-006). **Langkah:** switch A→B→A→B dalam satu boot; kill -9 saat switch terakhir
  `TELEPORTING` (dunia target besar); restart, join sekali. Ulangi dengan kill setelah commit.
- **Diharapkan:** join pertama me-rollback tanpa `OWNERSHIP_CONFLICT`; kasus kedua menyelesaikan switch maju.
- **Catatan:** waktu kill bergantung keberuntungan; catat fase journal yang benar-benar tertinggal.

### TO-MP-04 — Rollback ke snapshot tepat dan blok saat journal pending

- **PM:** PM-077. **Langkah:** tinggalkan journal RECOVERING; `switch`/`bind`/`lock` (CLI) dan `saveProfile`
  (API, BLOCKED); join lagi; bandingkan revisi/id dengan file di `awf/profiles/snapshots/`.
- **Diharapkan:** operasi ditolak `RECOVERY_REQUIRED`; rollback menerapkan `fromSnapshotId` journal; bila
  snapshot itu hilang journal menjadi FAILED dan hasilnya `RECOVERY_REQUIRED` (tidak ada fallback state kosong).

### TO-MP-05 — Journal rusak dan pemangkasan journal terminal

- **PM:** PM-031. **Langkah:** stop; balik satu byte di `transitions/<X>.<id>.awfp` (atau tulis sampah ke
  file baru dengan pola itu); start; switch X dan Y; hapus file `.corrupt`; switch X. Lalu buat 7 switch
  X gagal (template `nope`).
- **Diharapkan:** satu ERROR menyebut file; status `unreadable journals …`; switch X gagal `IO_FAILURE`
  menyebut `.corrupt`, Y normal; setelah dihapus X bisa switch; hanya 5 journal FAILED yang tersisa untuk X.
- **Batas:** journal rusak bernama lama (`<transition>.awfp`) tidak memblokir siapa pun (hanya dicatat).

### TO-MP-06 — Arsip state pra-profil pada switch pertama

- **PM:** PM-030. **Langkah:** pemain berinventaris tanpa profil; create A; switch ke A; ulangi dengan
  switch pertama yang gagal setelah apply (putus saat teleport) lalu join.
- **Diharapkan:** INFO `AWF MultiProfile: archived the pre-profile state of <uuid> (…)`;
  `awf/profiles/accounts/<uuid>.legacy.awfp` ada dan berisi item itu; arsip lama dipindah ke
  `<uuid>.legacy.<millis>.awfp`, tidak ditimpa; setelah kegagalan item asli kembali.
- **Catatan:** keputusan adopt-vs-archive masih owner; tidak ada restore in-game; arsip tidak pernah dihapus otomatis.

### TO-MP-07 — Snapshot saat quit dan tidak ada snapshot saat shutdown

- **PM:** PM-076 (MP-004). **Langkah:** ubah inventaris, quit; `list`/`inspect` revisi; join. Lalu `stop` dengan pemain online.
- **Diharapkan:** revisi naik tepat 1, tanpa WARN `on quit failed`; join menampilkan inventaris yang sama;
  saat stop satu INFO `closed with N player(s) online; they get no quit snapshot …`.
- **Batas yang terbuka:** tidak ada snapshot quit saat shutdown sampai urutan patch 0081 diubah.

### TO-MP-08 — Quit selama switch dan klaim sesi

- **PM:** PM-228. **Langkah:** quit saat switch, join lagi sebelum switch selesai; lalu switch berikutnya.
- **Diharapkan:** setelah switch pemain memegang tepat satu klaim hidup (`/awf multiprofile owner <profil>`
  menyebut node ini); switch berikutnya tidak menjatuhkannya.

### TO-MP-09 — Switch dibatasi waktu

- **PM:** PM-090 (MP-005). **Konfigurasi:** `switching.timeout-ms: 1000`.
- **Langkah:** switch ke profil yang titik kembalinya di area jauh yang belum dimuat.
- **Diharapkan:** `TIMEOUT` dalam ±1 s; inventaris di-rollback; journal FAILED; pemain tidak beku.
- **Catatan:** `teleportAsync` tidak bisa dibatalkan; teleport yang selesai setelah timeout tetap memindahkan pemain (state di-rollback, lokasi tidak).

### TO-MP-10 — Antrean I/O profil di bawah latensi Redis

- **PM:** PM-091. **Butuh:** injector latensi (mis. `tc netem` 200 ms atau toxiproxy) dan banyak klien.
- **Langkah:** provider redis, 50 join serentak sambil autosave AWF.
- **Diharapkan:** `/perf governor` tanpa penolakan lane STORAGE akibat profil; operasi profil selesai lambat,
  bukan `INTERNAL_ERROR`. Tidak ada paralelisme per akun (semua langkah profil diserialkan).

### TO-MP-11 — Dua snapshot dengan revisi sama

- **PM:** PM-214. **Langkah:** salin snapshot ke `<rev>-<uuid-baru>.awfp` dengan mtime lebih baru; save berikutnya.
- **Diharapkan:** snapshot terbaru dipakai; duplikat lama yang dipangkas. Di FS dengan mtime kasar (FAT), urutan nama file.

### TO-MP-12 — Publish Fabric di dalam langkah I/O

- **PM:** PM-227. **Diharapkan:** `createProfile` dari command region-thread dengan `services.events: true` tidak menjalankan pekerjaan `SourbyCraft-Storage` di thread region.
- **Status:** BLOCKED (plugin probe tidak ada).

### TO-MP-13 — Permintaan create tidak valid dan state terlalu besar

- **PM:** PM-264, PM-265, XREQ STATE_TOO_LARGE. **Langkah:** 1. `/awf multiprofile create <player> bad.type`
  (CLI mengubah ke huruf kecil, jadi `Bad_Type` justru valid). 2. Inventaris dengan shulker ber-NBT berat
  sampai state > 8 MiB; switch (dan quit). 3. Ulangi 2 dengan `switching.require-durable-save: false`.
- **Diharapkan:** 1: `Failed: profile type: bad.type` (kode `INVALID_REQUEST`). 2: `STATE_TOO_LARGE` dengan
  `the profile state cannot be stored: …` dan satu WARN berisi ukuran. 3: switch lanjut tanpa titik rollback, dengan WARN.

### TO-MP-14 — Perpanjangan klaim sesi dan klaim ulang setelah lapse

- **PM:** PM-295, PM-098. **Langkah:** 1. Provider local: online 5 menit tanpa switch, lalu quit
  (snapshot quit = tulis yang memvalidasi klaim sendiri). 2. Provider redis + fabric: pemain online,
  `redis-cli DEL sourbycraft:awf:fabric:session:<profil>` (dengan `key-prefix` default); tunggu heartbeat
  (default 10 s); quit.
  3. `/awf multiprofile recover <id>` untuk pemain online yang kompensasinya RECOVERING.
- **Diharapkan:** 1: snapshot quit sukses; tidak ada WARN `session claim … no longer valid`. 2: log
  `… lapsed and was claimed again (fence e -> e+1)`; quit tetap menulis snapshot. 3: klaim sesi tetap valid
  (epoch `owner` sama); paling banyak satu WARN `… was dropped`, bukan tiap 15 s.
- **Catatan:** `saveProfile` API (varian asli) BLOCKED (plugin).

### TO-MP-15 — Lock/unlock dan slot

- **PM:** PM-455, XREQ MP-SLOTS-API. **Langkah:** `lock <inactive>` lalu switch; `unlock` lalu switch;
  `lock <aktif>`; dengan `max-slots: 8`: `slots <player> 8`, `slots <player> 9`.
- **Diharapkan:** `Locked …; switching to it is refused until it is unlocked`; switch `Refused: PROFILE_LOCKED — …`;
  setelah unlock switch sukses (tanpa `STALE_STATE`); lock profil aktif `Failed: …` (kode `PROFILE_LOCKED`);
  slots 8 → `Slot limit of … is now 8; …`, 9 → `Failed: the slot limit must be 1-8 (max-slots)` (kode
  `INVALID_REQUEST`). API `setProfileLocked`/`setProfileSlotLimit`: BLOCKED (plugin).

### TO-MP-16 — Registri adapter state

- **PM:** PM-078. **Diharapkan:** adapter ke-33 ditolak; id milik plugin lain yang aktif ditolak; key `id:version` > 64 ditolak; adapter 3 MiB dilewati dengan WARN; data adapter yang tidak terpasang dibawa maju.
- **Status:** BLOCKED (plugin adapter uji tidak ada).

### TO-MP-17 — Efek potion tak terbatas dan amplifier besar

- **PM:** PM-079. **Langkah:** `/effect give @s minecraft:speed infinite 1`, `/effect give @s minecraft:haste 2000000 3`; switch keluar dan kembali.
- **Diharapkan:** kedua efek kembali, yang pertama tetap infinite. Efek amplifier > 255 (dari plugin, BLOCKED) dilewati dengan satu WARN.
- **Batas downgrade:** lihat §4.3 (Build 49 gagal mendekode snapshot ini).

### TO-MP-18 — Cakupan inventaris/armor dan titik respawn per profil

- **PM:** PM-080. **Langkah:** (1) `isolation.armor: false`, `inventory: true`: pakai armor, switch A→B.
  (2) `isolation.location: true`: tidur di bed di A, switch ke B baru, mati; kembali ke A, mati.
  (3) Tinggalkan A di lokasi L (bukan bed), kembali. (4) Switch ke snapshot Build 49 dengan `armor:false`;
  lalu dengan storage penuh. (5) Restore paksa: respawn anchor 1 charge di A, switch ke B dan kembali,
  mati dua kali; hancurkan bed A saat di B, kembali, mati.
- **Diharapkan:** (1) armor tetap terpakai, hotbar/storage bertukar; (2) respawn dibersihkan di B (spawn
  dunia), di A kembali ke bed; (3) tiba di L; (4) armor lama masuk slot storage kosong; storage penuh →
  WARN jumlah; (5) dua respawn memakai anchor dan charge tetap 1; setelah bed hancur respawn di titik bed
  bila kosong (deviasi gameplay yang terdokumentasi).

### TO-MP-19 — Sweep idle tidak menyentuh dunia berpenghuni atau dunia utama

- **PM:** PM-092. **Konfigurasi:** `idle-unload-seconds: 60`.
- **Langkah:** berdiri di pulau binding > 60 s; tinggalkan; tidur di bed dunia utama lalu switch.
- **Diharapkan:** tidak ada churn save/unload/WARN saat berpenghuni; setelah ditinggal unload dalam idle + 30 s
  dengan satu INFO sweep; dunia utama tidak pernah disentuh.

### TO-MP-20 — Capture respawn tanpa membaca blok

- **PM:** PM-093. **Konfigurasi:** `region-scheduler.guard-severity: LOG` (`config/aurora-server.yml`).
- **Langkah:** bed di overworld, pemain di dunia profil (region lain); switch.
- **Diharapkan:** tidak ada pelanggaran guard region; tidak ada sync chunk load posisi bed.

### TO-MP-21 — Kembali ke dunia profil saat join

- **PM:** PM-108. **Langkah:** bind pulau, switch masuk, jalan, quit; restart; join. Ulangi dengan stop
  saat pemain online. `/awf multiprofile switch` segera setelah join.
- **Diharapkan:** kembali di pulau pada lokasi quit dengan satu INFO `AWF MultiProfile: returned …`; tanpa
  snapshot quit kembali ke lokasi tersimpan terakhir atau spawn pulau; switch tepat setelah join menang atau
  ditolak `SWITCH_IN_PROGRESS` sampai teleport join selesai.

### TO-MP-22 — Cakupan isolasi yang tidak didukung

- **PM:** PM-456. **Langkah:** dapatkan advancement dan resep di A; switch ke B.
- **Diharapkan:** keduanya tetap ada (dibagi semua profil; statistik dan recipe book juga). Tidak ada config/doc yang mengklaim isolasi.

### TO-MP-23 — Provider redis + standalone + file, dan lock direktori

- **PM:** PM-084. **Langkah:** provider redis, standalone, repository file → start; server kedua pada direktori sama.
- **Diharapkan:** satu WARN `coordination.provider: redis with mode: standalone and the file repository … This is a
  single-node setup, not distributed mode.`; server kedua gagal `the profile directory … is in use by …;
  MultiProfile needs a directory exclusive to one server`.

### TO-MP-24 — WARN timeout switch ≥ lease klaim

- **PM:** PM-230. **Langkah:** `switching.timeout-ms: 60000` dengan provider local; start.
- **Diharapkan:** satu WARN `switching.timeout-ms (60000 ms) is not below the claim lease (60000 ms) …`.
  Varian dunia lambat > lease yang tetap commit: BLOCKED (butuh dunia/seam lambat yang terkontrol).

### TO-MP-25 — Node keluar dari direktori routing saat stop

- **PM:** PM-457. **Konfigurasi:** fabric + `services.routing: true`.
- **Langkah:** `redis-cli EXISTS sourbycraft:awf:fabric:node:<nodeId>` sebelum stop; `stop`; ulangi segera.
- **Diharapkan:** kunci hilang segera setelah shutdown, biasanya sebelum TTL. Alamat yang diiklankan masih `''` (remaining).

### TO-MP-26 — `owner` dan `reclaim`

- **PM:** PM-113. **Langkah:** `owner <profil aktif>`; `reclaim` profil yang dipegang server ini (epoch apa pun);
  `reclaim <p> <epoch salah>` untuk profil yang dipegang proses lain (node kedua, persiapan TO-MP-37).
- **Diharapkan:** `node <id>, epoch <E>, expires in N s (session fabric)` (provider redis) atau `(this process)`;
  milik sendiri `Failed: profile … is not held by another process; there is nothing to reclaim` (kode
  `INVALID_REQUEST`; dengan provider local selalu begini); epoch salah pada pemegang lain `Failed: the claim
  on … is no longer at fence …` (kode `STALE_STATE`). Skenario reclaim yang sah: TO-MP-37.

### TO-MP-27 — Idle unload hanya menyimpan sekali

- **PM:** XREQ AwfProfileWorldPort. **Langkah:** `idle-unload-seconds: 60`, dunia profil idle; ulangi dengan
  save yang gagal (B50-12 langkah 3).
- **Diharapkan:** unload sukses; save yang gagal membuat dunia tetap loaded dan eligible lagi. Bahwa hanya ada
  satu save durable (bukan save lalu unload yang menyimpan lagi) tidak terlihat di log — AWF tidak mencatat
  tiap save — jadi bagian itu tetap review statis (`AwfProfileWorldPort.saveAndUnload` → `unload(world, true)`).

### MP-010 — Dua node, repository Redis ber-fence (TO-MP-31…40)

Status keseluruhan: kode ada, **EXPERIMENTAL dan unqualified**. Gap yang tetap terbuka apa pun hasilnya:
state live pemain tidak ikut pindah server (join memakai playerdata vanilla server itu; save/quit
berikutnya menyimpannya), tidak ada snapshot quit saat shutdown, tidak ada handoff proxy/portal.

Persiapan semua skenario: Redis 7.2 `appendonly yes`; dua server A dan B di **direktori berbeda**
(identitas node berbeda), port berbeda, Redis dan `key-prefix` sama. Di kedua `aurora.yml`:

```yaml
aurora:
  awf:
    redis:
      uri: "redis://127.0.0.1:6379/0"
      fabric:
        enabled: true
        sessions: { heartbeat-seconds: 10, ttl-seconds: 45 }
    multiprofile:
      enabled: true
      mode: distributed
      repository: redis
      redis: { require-aof: true, wait-for-aof: true }
      coordination: { provider: redis, fencing-enabled: true }
```

Kunci: profil `sourbycraft:awf:fabric:mp:profile:<id>` (field `rev`), staging
`…:fabric:mp:stage:<snapshot>`, sesi `sourbycraft:awf:fabric:session:<profil>`. Rollback: hentikan kedua
server, hapus kunci `sourbycraft:awf:fabric:*` di Redis uji, kembalikan `repository: file` +
`mode: standalone` (direktori file tidak disentuh oleh repository redis; perubahan setelah migrasi hilang).

- **TO-MP-31 (S1) — Owner basi:** pemain switch di A; `kill -STOP <A>` > 45 s; pemain join B (klaim
  diberikan setelah kedaluwarsa, epoch+1); `kill -CONT <A>`. Varian tanpa plugin: A memproses quit pemain
  yang lama → tulis snapshot quit di bawah klaim basi. Varian API `saveProfile` di A: BLOCKED (plugin).
  **Diharapkan:** tidak ada tulis dari A. Salah satu jalur: tulis A ditolak `OWNERSHIP_CONFLICT` (`… the claim
  on profile … no longer holds it (Redis has epoch …)` atau `… has expired`), **atau** heartbeat A menjatuhkan
  klaim lebih dulu (WARN `… was taken over by … and was dropped; saves for this player are refused …`)
  sehingga quit tidak menulis. `redis-cli HGET sourbycraft:awf:fabric:mp:profile:<id> rev` tidak berubah oleh A.
- **TO-MP-32 (S2) — Redis mati di tengah switch:** hentikan Redis saat journal di `APPLYING_TARGET`.
  **Diharapkan:** switch gagal; setelah Redis kembali dan pemain join, recovery menyelesaikan; akun menyebut
  profil lama *xor* baru, konsisten dengan state pemain. Timing: bila fase tidak tercapai, `BLOCKED (race tidak terpicu)`.
- **TO-MP-33 (S3) — Balasan commit hilang:** turunkan `aurora.awf.redis.timeout-ms`; `redis-cli CLIENT
  PAUSE 20000 WRITE` tepat sebelum `COMMITTING`. **Diharapkan:** log `switch … lost the reply of its commit`
  (switch selesai) atau `… whether the commit was applied is unknown` (journal RECOVERING, pemain tidak di-rollback). Timing-dependent.
- **TO-MP-34 (S4) — Kunci staging yatim:** kill -9 saat save; `redis-cli --scan --pattern
  'sourbycraft:awf:fabric:mp:stage:*'` dan `PTTL`. **Diharapkan:** kunci hilang dalam 10 menit (TTL staging
  600000 ms). Timing-dependent.
- **TO-MP-35 (S5) — Durabilitas Redis:** `appendonly no` → start. Lalu `redis.require-aof: false`.
  Lalu `appendonly yes`, `appendfsync everysec`. **Diharapkan:** stage MultiProfile gagal start (server tetap
  berjalan tanpa MultiProfile) dengan `AWF MultiProfile could not start: Redis has appendonly off, so a
  Redis restart would lose every profile … set aurora.awf.multiprofile.redis.require-aof: false …`; dengan
  `false`: WARN dan baris status repository memuat `AOF OFF`; dengan everysec status tanpa akhiran
  `… write(s) not confirmed on disk in time` pada beban normal.
- **TO-MP-36 (S6) — Migrasi satu arah:** direktori file berisi data + `repository: redis` (Redis kosong).
  `sha256sum` semua file di `awf/profiles/` kecuali `repository.lock`; status; `/awf multiprofile
  migrate-repository confirm` tanpa pemain online; status; checksum lagi; jalankan migrate kedua kali.
  **Diharapkan:** sebelum: status `… WRITES REFUSED: the file repository … holds profiles that are not in
  Redis yet; run /awf multiprofile migrate-repository confirm …`; ringkasan `Migrated: …` sama dengan jumlah
  file; refusal hilang; checksum identik (kecuali `repository.lock`, yang ditulis ulang dengan holder dan
  pid); run kedua ditolak. Dengan pemain online: `Refused: players are online …`.
- **TO-MP-37 (S7) — Reclaim:** `kill -9` A saat memegang profil; di B sebelum TTL habis `/awf multiprofile
  owner <p>` (menunjukkan A, epoch E) lalu `/awf multiprofile reclaim <p> E`. **Diharapkan:** `Took over and
  released the claim on <p> at epoch E; writes under the old claim are refused`; `owner` lalu `unowned`.
- **TO-MP-38 (S8) — Topologi ditolak:** uri ke replica atau Redis Cluster. **Diharapkan:** `repository: redis
  needs the Redis primary, but this Redis reports role …` atau `repository: redis does not support a Redis
  Cluster …`; Redis < 7.0 → `… needs Redis 7.0 or newer …`.
- **TO-MP-39 (S9) — Default tidak berubah:** `repository: file`, `mode: standalone`: switch, quit dan restart berperilaku seperti B50-09/TO-MP-07.
- **TO-MP-40 (S10) — Delete profil aktif dengan provider redis:** API `deleteProfile` atas profil aktif
  pemain online di A → `PROFILE_LOCKED`, dan tulis berikutnya (snapshot quit) tetap sukses. **BLOCKED (plugin probe; CLI tidak punya delete).**

### Hasil area 9

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B50-05 | Batas slot create | B50-MP-ACCOUNT, PM-091 | P0 | READY (CLI, bukan race sejati); BLOCKED (API: plugin) | | | |
| B50-06 | Capture basi A→B→A | PM-004 | P0 | BLOCKED (seam/plugin) | | | |
| B50-07 | Lease kedaluwarsa | PM-004 | P0 | BLOCKED (seam); varian manual timing-dependent | | | |
| B50-08 | Recovery bersaing | PM-077/004 | P0 | READY (CLI); BLOCKED (save/delete API) | | | |
| B50-09 | Bind + switch | PM-107/288 | P1 | READY FOR OWNER TEST | | | |
| B50-10 | Izin/input bind | PM-288 | P1 | READY (CLI); BLOCKED (>16: plugin) | | | |
| B50-11 | Unbind | B50-MP-BIND | P2 | READY FOR OWNER TEST | | | |
| B50-12 | Retry idle | PM-263/092/348 | P2 | READY FOR OWNER TEST | | | |
| B50-13 | Distributed refusal/syarat | PM-084/037 | P1 | READY FOR OWNER TEST | | | |
| B50-14 | Record terbatas | PM-004 | P1 | READY FOR OWNER TEST | | | |
| B50-15 | Kontensi + shutdown | PM-091/076 | P1 | READY (butuh bot klien) | | | |
| TO-MP-01 | Freeze + fase | PM-033 | P0 | READY FOR OWNER TEST | | | |
| TO-MP-02 | Kompensasi RECOVERING | PM-029 | P0 | READY FOR OWNER TEST | | | |
| TO-MP-03 | Fence setelah kill -9 | PM-032 | P1 | READY (timing) | | | |
| TO-MP-04 | Rollback snapshot tepat | PM-077 | P0 | READY (CLI); BLOCKED (save API) | | | |
| TO-MP-05 | Journal rusak + prune 5 | PM-031 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-06 | Arsip pra-profil | PM-030 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-07 | Snapshot quit / shutdown | PM-076 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-08 | Quit selama switch | PM-228 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-09 | Timeout switch | PM-090 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-10 | Antrean I/O + latensi | PM-091 | P2 | READY (injector latensi + klien) | | | |
| TO-MP-11 | Revisi snapshot ganda | PM-214 | P3 | READY FOR OWNER TEST | | | |
| TO-MP-12 | Publish di langkah I/O | PM-227 | P3 | BLOCKED (plugin) | | | |
| TO-MP-13 | Create invalid / too large | PM-264/265 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-14 | Renew/klaim ulang sesi | PM-295/098 | P1 | READY (CLI/quit); BLOCKED (save API) | | | |
| TO-MP-15 | Lock/slots | PM-455 | P2 | READY (CLI); BLOCKED (API) | | | |
| TO-MP-16 | Registri adapter | PM-078 | P2 | BLOCKED (plugin adapter) | | | |
| TO-MP-17 | Efek infinite | PM-079 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-18 | Armor/respawn per profil | PM-080 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-19 | Sweep idle berpenghuni | PM-092 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-20 | Respawn tanpa baca blok | PM-093 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-21 | Kembali saat join | PM-108 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-22 | Cakupan tidak didukung | PM-456 | P3 | READY FOR OWNER TEST | | | |
| TO-MP-23 | Provider redis + file + lock | PM-084 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-24 | WARN timeout ≥ lease | PM-230 | P3 | READY (WARN); BLOCKED (dunia lambat) | | | |
| TO-MP-25 | Unregister routing | PM-457 | P3 | READY FOR OWNER TEST | | | |
| TO-MP-26 | owner/reclaim | PM-113 | P2 | READY FOR OWNER TEST | | | |
| TO-MP-27 | Idle unload sekali save | XREQ | P3 | READY FOR OWNER TEST | | | |
| TO-MP-31 | MP-010 S1 owner basi | PM-037 | P0 | READY (varian quit); BLOCKED (API) | | | |
| TO-MP-32 | MP-010 S2 Redis mati | PM-037 | P0 | READY (timing; bisa BLOCKED race) | | | |
| TO-MP-33 | MP-010 S3 balasan hilang | PM-037 | P0 | READY (timing; bisa BLOCKED race) | | | |
| TO-MP-34 | MP-010 S4 staging yatim | PM-037 | P2 | READY (timing) | | | |
| TO-MP-35 | MP-010 S5 durabilitas | PM-037 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-36 | MP-010 S6 migrasi | PM-037 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-37 | MP-010 S7 reclaim | PM-037 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-38 | MP-010 S8 topologi | PM-037 | P2 | READY (butuh replica/cluster uji) | | | |
| TO-MP-39 | MP-010 S9 default | PM-037 | P1 | READY FOR OWNER TEST | | | |
| TO-MP-40 | MP-010 S10 delete aktif | PM-037 | P2 | BLOCKED (plugin) | | | |

---

## 10. Updater dan provisioning (TO-UP)

Standar area: konfigurasi `sourbycraft_config/sourbycraft_global_config.yml` blok `updater` (default
`apply-mode: notify`, LIVE; `enabled` RESTART_REQUIRED) dan `plugins.viaversion/protocollib.auto-provision`
(RESTART_REQUIRED). Server rilis loopback hanya lewat properti uji `-Dsourbycraft.update.api-base`
(loopback saja). Backup: jar peluncur, `auto_update/`, config. Rollback: kembalikan jar dan hapus
`auto_update/apply-failed.tag`, `pending.tag`, `<jar>.update-*.part`, `<jar>.prev` sesuai kebutuhan.
Prosedur UP-001 di [03](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md); detail updater di
[AUTO-UPDATER](development/AUTO-UPDATER.md). Banyak kasus di bawah butuh daftar rilis yang bisa diatur
(rilis lebih baru, MC lain, nama aset aneh, ukuran besar) yang tidak disediakan fixture: statusnya BLOCKED (alat).

### TO-UP-01 — Regresi fixture rollback (apply → boot gagal → restore)

- **PM:** PM-035, PM-095, PM-083 (sebagian). **Prioritas / risiko:** P1 / tinggi.
- **Langkah:** `python3 scripts/updater_rollback_fixture.py run --scenario all --name b50` (OpenSSL 3; jar
  dan test-plugin dari §1).
- **Diharapkan:** skenario `rollback`, `healthy`, `dead-launcher` lulus sesuai docstring fixture; pada boot
  pertama setelah apply log `auto-updater: first boot after applying <tag>` muncul sebelum section Engine.
- **Bukti:** direktori bukti fixture, ringkasan JSON.

### TO-UP-02 — Preflight apply fail-closed dan `apply-failed.tag`

- **PM:** PM-034, PM-094. **Diharapkan:** jar peluncur tak diketahui/direktori read-only → balasan `Update
  downloaded but it cannot be installed here — nothing restarted …`; satu ERROR berisi langkah manual; tidak
  ada broadcast countdown/restart; `auto_update/apply-failed.tag` = tag; cek terjadwal berikutnya hanya
  mengumumkan (`auto-updater: an earlier apply of <tag> failed on this server …`), tanpa permintaan jar.
- **Status:** BLOCKED (butuh server rilis loopback yang menawarkan rilis lebih baru; fixture tidak punya skenario ini).

### TO-UP-03 — Swap atomik dan kill saat apply

- **PM:** PM-035. **Diharapkan:** `<jar>.prev` muncul, `<jar>.update-*.part` sesaat; kill -9 saat apply →
  jar peluncur identik (sha256) dengan lama atau baru, tidak pernah setengah.
- **Status:** BLOCKED (alat rilis loopback); jalur swap sehat tercakup TO-UP-01 `healthy`.

### TO-UP-04 — Cek bersamaan dan staged jar dirusak saat countdown

- **PM:** PM-081. **Diharapkan:** `/update apply` dua kali dalam 1 s → `An update check is already running …`;
  satu `*.part`; staged jar dirusak saat countdown → ERROR `the copy's SHA-256 … differs from the release
  digest …`, `NOT restarting`, jar tidak berubah.
- **Status:** BLOCKED (alat rilis loopback lambat).

### TO-UP-05 — Beberapa kunci rilis (rotasi)

- **PM:** PM-082, CROSS-WP06/07-PEM. **Langkah yang bisa sekarang:** pada salinan repo,
  `python3 scripts/release_signing_key.py --out-dir <tmp> --add-pub` → resource berisi dua blok PEM.
- **Diharapkan (bagian BLOCKED):** rilis ditandatangani kunci mana pun di-stage; jar/manifest yang diubah
  ditolak; satu blok rusak menolak semua; job rilis CI log `<jar>.sig verifies under key-<n>.pem`.
- **Status:** READY (skrip); BLOCKED (fixture jar dua kunci + keputusan rotasi owner).

### TO-UP-06 — Restore config pra-49 saat rollback ke jar < 49

- **PM:** PM-083. **Diharapkan:** setelah restore ke r48, `sourbycraft_global_config.toml`, `aurora.toml`,
  `canvas-*.yml` kembali dari `*.migrated`; catatan di `rollback.state`.
- **Status:** BLOCKED (butuh apply Build 50 di atas Build 48 lewat server rilis loopback). Baca build number
  jar bersarang belum diuji pada slim jar nyata.

### TO-UP-07 … TO-UP-10 — Pemilihan rilis dan unduhan aset

| ID | PM | Diharapkan | Status |
| --- | --- | --- | --- |
| TO-UP-07 | PM-096 | `restart-mode: spigot` → WARN tidak bisa menjalankan restart-script; stop exit 0 | BLOCKED (alat rilis loopback) |
| TO-UP-08 | PM-109 | `v26.3-r100` + `v26.2-r51` → WARN `… it is announced, never staged …`, r51 STAGED | BLOCKED (alat rilis loopback) |
| TO-UP-09 | PM-110 | Stage mode dua cek → `… is already staged with the release's SHA-256; …`, tanpa unduhan; pesan "restart tidak memasang" | BLOCKED (alat rilis loopback) |
| TO-UP-10 | PM-215 | Aset `../x.jar` → `refusing asset name …`; ukuran 600 MiB ditolak sebelum unduh; `http://` ditolak tanpa override | BLOCKED (alat rilis loopback) |

### TO-UP-11 — Stop selama startup tidak mengonfirmasi update dan tidak menulis profil startup

- **PM:** PM-229 (+ StartupTimeline/ReadySummary wave 2). **Diharapkan:** plugin yang memanggil
  `Bukkit.shutdown()` di `onEnable` → WARN `auto-updater: a stop was requested while <tag> was starting; …`,
  `rollback.state` phase=BOOTING; tidak ada baris ready summary; `sourbycraft_config/cache/startup-profile.properties` tidak berubah.
- **Status:** BLOCKED (plugin uji yang memanggil shutdown di onEnable tidak ada).

### TO-UP-12 — `applied.tag` setelah container dibuat ulang

- **PM:** PM-254. **Diharapkan:** apply r50 (guard OK) lalu container dari image r49 dengan `/data` sama →
  WARN `… Offering <tag> again`, NOTIFIED. **Status:** BLOCKED (alat rilis loopback + Docker).

### TO-UP-13 — `/update` menolak saat runtime stopping/failed

- **PM:** PM-247 (wave 2). **Diharapkan:** `Update check not started: the server is stopping or failed to start.`
- **Status:** BLOCKED (state STOPPING/FAILED tidak dapat dicapai andal secara manual; konsol berhenti memproses perintah saat shutdown).

### TO-UP-14 — Provisioning dilewati untuk kunci pra-49 saja

- **PM:** PM-036. **Prioritas / risiko:** P1 / sedang.
- **Langkah:** direktori Build 48 dengan TOML `[viaversion] auto-provision=false` (tanpa jar Via); boot kandidat; boot lagi.
- **Diharapkan:** konsol `… sets only the pre-Build-49 key viaversion.auto-provision; … provisioning skipped this
  boot (nothing downloaded, installed or quarantined) …`, WARN `ViaVersion auto-provision skipped this boot …`, tanpa
  unduhan; setelah ConfigMigration menulis kunci baru, boot berikutnya `ViaVersion auto-provision disabled …`.

### TO-UP-15 — Nilai provisioning YAML: flow style, huruf besar, tanda kutip

- **PM:** PM-216, PM-217, PM-283 (pembacaan ketat fase 2). **Langkah:** (a) `plugins: {viaversion: {auto-provision:
  false}}`; (b) `auto-provision: True`; (c) `auto-provision: "true"` dengan jar Via terpasang.
- **Diharapkan:** (a) `Invalid plugins; provisioning skipped this boot`, tanpa unduhan/karantina; (b)
  diperlakukan true; (c) Invalid + skipped, jar tetap (tidak di-rename `.disabled`), via-updater runtime tidak mengunduh, config Via tidak ditulis.

### TO-UP-16 — Jar plugin bertanda tangan rusak tidak menggagalkan boot

- **PM:** PM-097. **Langkah:** ubah satu entri di jar plugin bertanda tangan (digest `META-INF/*.SF` tidak cocok),
  taruh di `plugins/`, `plugins.viaversion.auto-provision: false`, boot.
- **Diharapkan:** tidak ada exception sebelum scan plugin; server mencapai `Done (`.

### TO-UP-17 — Timeout koneksi

- **PM:** PM-297. **Butuh:** Linux root. **Langkah:** `-Dsourbycraft.update.api-base=http://127.0.0.1:<port>`
  dengan aturan `iptables` DROP; `/update check`.
- **Diharapkan:** `request to … failed` dalam ±10 s.

### Hasil area 10

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-UP-01 | Fixture rollback | PM-035/095 | P1 | READY FOR OWNER TEST | | | |
| TO-UP-02 | Preflight fail-closed | PM-034/094 | P1 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-03 | Swap atomik + kill | PM-035 | P1 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-04 | Cek bersamaan | PM-081 | P2 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-05 | Rotasi kunci | PM-082 | P2 | READY (skrip); BLOCKED (fixture + keputusan) | | | |
| TO-UP-06 | Restore config pra-49 | PM-083 | P2 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-07 | restart-mode spigot | PM-096 | P3 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-08 | Rilis MC lain | PM-109 | P2 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-09 | Stage reuse | PM-110 | P3 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-10 | Aturan aset | PM-215 | P2 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-11 | Stop saat startup | PM-229 | P2 | BLOCKED (plugin) | | | |
| TO-UP-12 | applied.tag Docker | PM-254 | P3 | BLOCKED (alat rilis loopback) | | | |
| TO-UP-13 | /update saat stopping | PM-247 | P3 | BLOCKED (state tidak tercapai) | | | |
| TO-UP-14 | Kunci pra-49 SKIP | PM-036 | P1 | READY FOR OWNER TEST | | | |
| TO-UP-15 | Nilai YAML provisioning | PM-216/217/283 | P2 | READY FOR OWNER TEST | | | |
| TO-UP-16 | Jar bertanda rusak | PM-097 | P3 | READY FOR OWNER TEST | | | |
| TO-UP-17 | Timeout koneksi | PM-297 | P3 | READY (butuh root) | | | |

---

## 11. Bridge dan guard (TO-BR)

Standar area: `aurora.bridge.mode: safe` (RESTART_REQUIRED) dan `legacy-test-plugin`. Mode
`aurora.bridge.thread-checks`: **enforced** untuk gate keselamatan; **relaxed** (default owner saat `safe`)
hanya untuk mengamati perilaku default — relaxed dan bridged **tidak aman dan tidak qualified** apa pun
hasilnya (PM-012 adalah keputusan owner). `region-scheduler.guard-severity` ada di
`config/aurora-server.yml` (default `LOG`). Ekspektasi `THROW` hanya berlaku dengan `enforced` (PM-008;
BR-001 merujuk ke sini). Rollback: kembalikan `aurora.yml` dan restart. Prosedur BR-001/002 di
[03](testing/owner/03-EXPERIMENTAL-FEATURES-TESTING.md).

### TO-BR-01 — Mode thread-checks dan penanda probe

- **PM:** PM-008, PM-049, PM-103. **Prioritas / risiko:** P0 / tinggi.
- **Langkah:** boot `relaxed`, lalu `enforced`; tiap kali catat `/ver`, `/sourbycraft config`, `/perf plugins`
  dan penanda `LEGACY_RELAXED_*`. Dengan `enforced`: konsol `legacybridge unload` (probe membuat dan
  meng-unload dunia AWF sementara sendiri).
- **Diharapkan:** relaxed → semua `LEGACY_RELAXED_*_OK` dan relaxed hits naik; enforced → semua `_REFUSED`;
  `LEGACY_UNLOAD_RELEASED_OK` dan `LEGACY_UNLOAD_LATE_REFUSED_OK`; tidak ada `_WRONG`.

### TO-BR-02 — Jendela log relaxed terbatas

- **PM:** PM-002. **Prioritas / risiko:** P0 / tinggi (banjir WARN, heap tumbuh).
- **Langkah:** safe + relaxed; 30 menit dengan plugin bridged penyapu dunia (ClearLag atau probe) dan plugin
  native yang menyentuh blok region tetangga dari luar thread; heap histogram awal dan akhir.
- **Diharapkan:** paling banyak satu WARN `Bridged plugin <X> accessed state owned by another thread …` per
  (plugin, alasan dasar) per 60 s, diakhiri `(N more suppressed in the last 60 s)`; paling banyak satu
  `Region-ownership check relaxed …` per alasan dasar per 60 s; heap datar; objek `LogWindows$Window`
  ≤ 1024 + 256 + 256 + 3. `/plugins <nama>` "Last failure" = exception terakhir plugin, atau hit relaxed pertama bila tidak pernah melempar.

### TO-BR-03 — Timer async berulang tidak menumpuk di BRIDGE_IO

- **PM:** PM-100, wave 2 LimitProbes. **Langkah:** (a) `aurora.bridge.max-running-async-tasks-per-plugin: 2`
  (LIVE), `aurora.scheduler.bridge-io-threads` ≥ 5; konsol `legacybridge async-limit 2`. (b) Plugin bridged dengan
  `runTaskTimerAsynchronously` (tidur 2 s, periode 1 tick) — gunakan plugin nyata atau BLOCKED.
- **Diharapkan:** (a) `LEGACY_LIMIT_ASYNC_OK`, `LEGACY_LIMIT_ASYNC_RELEASED_OK`, `LEGACY_LIMIT_ASYNC_TIMER_OK`
  (timer tetap terjadwal, runs naik, peak ≤ 2, pending 0). (b) antrean BRIDGE_IO di `/perf` sekitar 1 per
  timer; skipped runs naik; WARN `… its previous run is still queued on the BRIDGE_IO lane` paling banyak
  sekali per 60 s; one-shot plugin lain tetap jalan.

### TO-BR-04 — Pelepasan kerja yang dibuang dan anchor

| ID | PM | Diharapkan | Status |
| --- | --- | --- | --- |
| TO-BR-04a | PM-099 | `runTaskTimer(plugin, EntityTask.of(stand, stand::remove), 1, 20)` → Pending turun, Rejected +1, Last failure `… removed before its next run (retired)`, tanpa violation | BLOCKED (plugin bridged uji) |
| TO-BR-04b | PM-231 | Listener `PlayerKickEvent` menjadwalkan `runTaskLater(40)`; A meng-kick B lalu A keluar → baris cleanup tetap tercetak | BLOCKED (plugin) |
| TO-BR-04c | PM-232 | Timer async berulang yang memanggil `runTask` → sync run di thread global region; tidak dibatalkan setelah op logout; tidak menahan `CraftPlayer` | BLOCKED (plugin) |
| TO-BR-04d | PM-233 | `IllegalStateException("feature X needs region threading off")` tidak mengarantina; `Bukkit.reload()` mengarantina (`quarantine-after: 1`) | BLOCKED (plugin) |
| TO-BR-04e | PM-237 | Async `world.setTime(1000)` sebelum region tick: relaxed → berubah + relaxed hit; enforced → ERROR `Thread failed startup thread check` + `IllegalStateException` | BLOCKED (plugin) |

### TO-BR-05 — Race retire anchor inferensi

- **PM:** PM-235. **Langkah:** spam perintah plugin bridged dengan continuation async (mis. DeluxeMenus open) sambil pemain disconnect.
- **Diharapkan:** tidak ada menu/body sync yang berjalan di global region setelah quit; Last failure `left before it ran`.
- **Catatan:** **tidak dapat dipicu andal**; tanpa bukti log jalur race, tulis `BLOCKED (race tidak terpicu)`.

### TO-BR-06 — Rate limit log TickGuard (guard-severity LOG)

- **PM:** PM-236 (wave 2). **Langkah:** (a) plugin native yang membaca blok region tetangga tiap tick dari
  task region — BLOCKED (plugin); (b) relaxed, body async bridged memanggil metode yang dijaga (RelaxedProbes).
- **Diharapkan:** (a) satu WARN `Thread failed main thread check: …` lalu satu per 60 s dengan `(N more suppressed …)`;
  (b) relaxed hits naik 1 per akses; tidak ada WARN TickGuard tambahan.

### Hasil area 11

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-BR-01 | Mode thread-checks | PM-008/049/103 | P0 | READY FOR OWNER TEST | | | |
| TO-BR-02 | Jendela log terbatas | PM-002 | P0 | READY FOR OWNER TEST | | | |
| TO-BR-03 | Timer async | PM-100 | P1 | READY (a); BLOCKED (b tanpa plugin nyata) | | | |
| TO-BR-04a…e | Pelepasan/anchor/classifier | PM-099/231/232/233/237 | P2 | BLOCKED (plugin bridged uji) | | | |
| TO-BR-05 | Race retire | PM-235 | P3 | READY (tidak andal) | | | |
| TO-BR-06 | Rate limit TickGuard | PM-236 | P2 | READY (b); BLOCKED (a: plugin) | | | |

---

## 12. Konfigurasi, penghapusan nama lama, dan CI (TO-CFG)

Standar area: file `sourbycraft_config/sourbycraft_global_config.yml` dan `sourbycraft_config/aurora.yml`;
engine `config/aurora-server.yml`/`aurora-worlds.yml`. Backup seluruh `sourbycraft_config/` dan `config/`.
Rollback: pulihkan folder itu; salinan `*.migrated` dari migrasi tetap ada. LIVE vs RESTART_REQUIRED
mengikuti `UtilityKeys`/`AuroraKeys`.

### TO-CFG-01 — Upgrade dari layout Build 48 (direktori writable)

- **PM:** PM-041, PM-133, PM-198, PM-313. **Langkah:** salinan writable server Build 48 (TOML pra-49); boot
  kandidat; lalu boot ulang salinan Build 49 yang sudah punya `aurora.yml`.
- **Diharapkan:** satu INFO `Migrated the configuration files to the Build 49 layout: …`; tidak ada WARN
  `ignored since Build 50`; semua nilai pindah ke nama baru; `*.migrated` tersimpan (`*.toml.migrated` =
  file pra-49 asli dari konversi format, `*.yml.migrated` = salinan sebelum rewrite layout; ringkasan
  migrasi menyebut `Previous Aurora file kept as <nama>`). `aurora.yml` yang sudah ada tidak mendapat kunci
  `aurora.abysslink.*`/`aurora.awf.multiprofile.*` baru (seeding hanya untuk file baru); default berlaku.

### TO-CFG-02 — Nama lama di direktori read-only

- **PM:** PM-041. **Langkah:** salinan read-only (`chmod -R a-w sourbycraft_config`); boot. Lalu `aurora.yml` yang
  hanya berisi `perf.ai.async-pathfinding: true` di direktori read-only. Direktori read-only dapat memicu WARN
  lain (cache, registry); nilai tes ini hanya WARN nama lama dan nilai efektif.
- **Diharapkan:** satu WARN per kunci lama `Config key '<lama>' in <file> is ignored since Build 50; only its current
  name '<baru>' is read …`; default berlaku. Kasus kedua: satu WARN `Config key 'perf.ai.async-pathfinding' is ignored
  since Build 50; set 'aurora.entity.async-pathfinding' …`; async pathfinding tetap off.

### TO-CFG-03 — Flag `-DCanvas.*` diabaikan

- **PM:** PM-042. **Langkah:** boot dengan `-DCanvas.AggressiveChunkSystem=true -DCanvas.DisableRegionProfiler=true` tanpa `-DAurora.*`.
- **Diharapkan:** satu WARN per flag `JVM flag -DCanvas.<X> is ignored since Build 50; …`; aggressive chunk system off; region profiler tetap aktif.

### TO-CFG-04 — Izin `canvas.broadcasting.receiver` diabaikan

- **PM:** PM-042. **Butuh:** LuckPerms. **Langkah:** pemain non-op hanya punya `canvas.broadcasting.receiver`; `/sourbycraft reload`.
- **Diharapkan:** tidak ada broadcast engine untuk pemain itu; satu WARN `Permission canvas.broadcasting.receiver (held by <pemain>) is ignored since Build 50; grant aurora.broadcasting.receiver instead.`

### TO-CFG-05 — File global rusak saat boot tidak mematikan konfigurasi lain

- **PM:** PM-087. **Langkah:** baris berindentasi tab di file global, `aurora.world.command-blocks: true` di
  `aurora.yml`, satu batas non-default di `sourbycraft-security.yml`; boot; perbaiki file; `/sourbycraft reload`.
- **Diharapkan:** satu SEVERE menyebut file dan baris; command block berjalan; batas security berlaku; setelah
  perbaikan reload `COMPLETE` dan nilai utility berlaku (nama pra-49 di file yang dibuka ulang baru dimigrasi boot berikutnya).

### TO-CFG-06 — `/maxp` menyimpan tanpa menimpa edit operator

- **PM:** PM-088. **Langkah:** ubah `messages.join` tanpa reload; `/maxp 50` di game; `/sourbycraft config`;
  lalu `/maxp 40` dan `/maxp 60` cepat dari dua pemain di region berbeda; restart.
- **Diharapkan:** file tetap berisi edit join dan `players.max-players: 50`; tanpa stall tick; balasan tiba
  (asinkron); "Pending tracked changes" tidak memuat `players.max-players`; jumlah live = nilai terakhir; setelah restart sama.

### TO-CFG-07 — `/sourbycraft reload` asinkron

- **PM:** PM-105 (wave 3). **Langkah:** reload saat pemain tersebar di beberapa region; reload dari RCON.
- **Diharapkan:** `Reloading configuration...` langsung, panel laporan menyusul; tanpa `IllegalStateException`;
  nilai `aurora-server.yml` baru terlihat di semua region. RCON dapat kehilangan panel laporan (perilaku yang
  terdokumentasi). Penolakan saat stopping/failed: BLOCKED (state tidak tercapai andal).

### TO-CFG-08 — File engine tidak ditulis ulang bila tidak ada kunci berubah

- **PM:** PM-115. **Langkah:** `stat -c %Y config/aurora-server.yml config/aurora-worlds.yml`; reload dua kali; hapus satu opsi; reload.
- **Diharapkan:** mtime tidak berubah; setelah opsi dihapus INFO `Added new … option` dan file ditulis ulang.

### TO-CFG-09 — `*.toml` di samping `*.yml`

- **PM:** PM-116. **Langkah:** taruh `aurora.toml` (`mode = "safe"`) di samping `aurora.yml`; boot; reload.
- **Diharapkan:** satu WARN `<path>/aurora.toml is ignored: <path>/aurora.yml exists and always wins …` saat boot dan per reload; bridge tetap off.

### TO-CFG-10 — Tidak ada klaim penghapusan di log

- **PM:** PM-117, PM-179. **Langkah:** grep log boot + reload untuk `until Build 50` dan `stops working in Build 50`.
- **Diharapkan:** tidak ada hasil (pesan `PerWorldDistanceConfig` kini menyebut kunci masih dibaca menunggu keputusan owner).

### TO-CFG-11 — Kunci Redis tak di-seed dikenal dan dilacak restart

- **PM:** PM-279, PM-282. **Langkah:** install baru: periksa komentar `aurora.awf.backend` dan tidak adanya
  `aurora.awf.redis.*`; set `aurora.awf.redis.pool-size: 4`; ubah lalu reload; ubah juga
  `aurora.awf.commit-interval-seconds` dan `aurora.abysslink.port`; reload dua kali.
- **Diharapkan:** tidak ada WARN kunci tak dikenal untuk `pool-size`; semua kunci yang diubah tercantum di
  `RESTART_REQUIRED (changes since boot)` sampai restart, tetap setelah reload kedua.

### TO-CFG-12 — WARN kunci tak dikenal

- **PM:** PM-284. **Langkah:** `updater.apply_mode: auto` di file global; `aurora.world.command-block: true` di `aurora.yml`.
- **Diharapkan:** satu WARN per kunci per load: `Config key '<k>' in <file> is not a setting SourbyCraft reads (misspelled or misplaced?) and is ignored`; kunci valid tanpa WARN. Kunci pensiun (`perf.smart-swap.*`) di direktori read-only juga dilaporkan.

### TO-CFG-13 — Kunci duplikat ditolak; section yang dihapus kembali default

- **PM:** PM-285. **Langkah:** duplikasikan kunci top-level di file global; boot; reload. Lalu hapus section `plugins:` dari server berjalan; reload.
- **Diharapkan:** SEVERE saat boot (`Duplicate config key '<k>'`), reload `Could not read …`; section yang dihapus kembali ke default di memori.

### TO-CFG-14 — `aurora.cpu.cores` berlapis

- **PM:** PM-286. **Langkah:** set hanya di file global; lalu di keduanya (beda nilai); lalu `aurora.yml` tidak terbaca.
- **Diharapkan:** ledger thread saat boot / `/perf governor` memakai nilai global bila `aurora.yml` tidak
  mengaturnya; `aurora.yml` menang bila keduanya; file tak terbaca hanya kehilangan kuncinya sendiri.

### TO-CFG-15 — `console.style` dibaca awal

- **PM:** PM-280. **Langkah:** `console.style: plain` di file global tanpa `-Dsourbycraft.console`; boot.
- **Diharapkan:** baris peluncuran dan rule Engine sudah plain sebelum stage konfigurasi.

### TO-CFG-16 — Laporan config Spark membaca `canvas-patch.yml` dimensi

- **PM:** PM-281. **Langkah:** `canvas-patch.yml` di path dimensi nether; `/spark` config report.
- **Diharapkan:** entri per dunia menampilkan file yang diterapkan engine.

### TO-CFG-17 — Pembaca data persisten lama tetap bekerja (menunggu PM-179)

- **PM:** PM-179. **Langkah:** dari salinan Build 48/49: `canvas-server.yml`/`canvas-worlds.yml`, kunci
  `Canvas.Distance`, `data/canvas/pearls.dat` dengan pearl tersimpan; boot kandidat.
- **Diharapkan:** migrasi file engine sekali (INFO, file lama `*.migrated`); `Canvas.Distance` masih dibaca
  dengan satu WARN; pearl termigrasi. Hasil ini mendukung keputusan owner, bukan menutupnya.

### TO-CFG-18 — Layout CI dan tooling

- **PM:** PM-040, PM-221, PM-219, PM-121, PM-220, PM-246, PM-301. **Langkah (owner/CI):** push run pertama
  setelah merge; `workflow_dispatch` `protocollib-pin-probe.yml`; `./gradlew --version` (wrapper memverifikasi
  `distributionSha256Sum`); build tanpa repository `sourby-maven`; `python3 scripts/profile_server.py …`;
  `python3 scripts/concurrency_policy.py`; `python3 scripts/inherited_guard_policy.py`.
- **Diharapkan:** boot main/bridge/AWF tanpa `ignored since Build 50` dan tanpa `provisioning skipped`; langkah
  bootstrap/offline (sengaja memakai TOML pra-49) menunjukkan INFO migrasi + baris provisioning-skipped dan
  boot offline kedua tanpa unduhan; probe menampilkan `release_id=238142563` pada expected dan actual; wrapper
  menerima distribusi; profil server menulis YAML dan hash config hanya bila file ada; policy tanpa temuan;
  ledger inherited guards cocok (ThrownEnderpearl#1 KEEP-OWNED).

### TO-CFG-19 — Cache library rusak pada boot offline

- **PM:** PM-218 (perilaku launcher yang ada; skenario CI-nya belum dibuat). **Prioritas / risiko:** P2 / sedang.
- **Langkah:** setelah satu boot online sukses dan satu boot offline (`-Dsourbyclip.offline=true`) sukses,
  timpa beberapa byte awal satu file `libraries/**/*.jar`; boot offline; lalu boot online.
- **Diharapkan:** boot offline keluar non-zero dan menyebut koordinat library; boot online memulihkan dan mencapai `Done (`.
- **Catatan:** sufiks file staging SourbyClip 3.1.3 untuk pemeriksaan `*.tmp` perlu dikonfirmasi owner dari repo privat (PM-278).

### TO-CFG-20 — Pesan server penuh mengikuti `players.full-server-bypass`

- **PM:** PM-287 (komentar seeded diperbaiki; perilaku tidak berubah). **Prioritas / risiko:** P3 / rendah.
- **Langkah:** `players.max-players: 1`; `players.full-server-bypass: false` (RESTART_REQUIRED); dua klien
  join. Lalu `true`, restart, ulangi.
- **Diharapkan:** dengan `false` pesan penuh vanilla; dengan `true` dipakai `messages.server-full` (LIVE).

### Hasil area 12

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-CFG-01 | Upgrade Build 48 | PM-041/133/198 | P1 | READY FOR OWNER TEST | | | |
| TO-CFG-02 | Read-only nama lama | PM-041 | P1 | READY FOR OWNER TEST | | | |
| TO-CFG-03 | `-DCanvas.*` | PM-042 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-04 | Izin canvas | PM-042 | P2 | READY (butuh LuckPerms) | | | |
| TO-CFG-05 | File global rusak | PM-087 | P1 | READY FOR OWNER TEST | | | |
| TO-CFG-06 | `/maxp` | PM-088 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-07 | Reload asinkron | PM-105 | P1 | READY (stopping: BLOCKED) | | | |
| TO-CFG-08 | File engine stabil | PM-115 | P3 | READY FOR OWNER TEST | | | |
| TO-CFG-09 | TOML di samping YAML | PM-116 | P3 | READY FOR OWNER TEST | | | |
| TO-CFG-10 | Tanpa klaim penghapusan | PM-117/179 | P3 | READY FOR OWNER TEST | | | |
| TO-CFG-11 | Kunci Redis tak di-seed | PM-279/282 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-12 | Kunci tak dikenal | PM-284 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-13 | Kunci duplikat | PM-285 | P1 | READY FOR OWNER TEST | | | |
| TO-CFG-14 | cpu.cores berlapis | PM-286 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-15 | console.style awal | PM-280 | P3 | READY FOR OWNER TEST | | | |
| TO-CFG-16 | Spark canvas-patch | PM-281 | P3 | READY FOR OWNER TEST | | | |
| TO-CFG-17 | Pembaca persisten lama | PM-179 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-18 | CI/tooling | PM-040/221/219/121/220/246/301 | P1 | READY (butuh push run / dispatch) | | | |
| TO-CFG-19 | Cache library rusak | PM-218 | P2 | READY FOR OWNER TEST | | | |
| TO-CFG-20 | Pesan server penuh | PM-287 | P3 | READY (butuh dua klien) | | | |

---

## 13. Async pathfinding (TO-APF)

Standar area: `aurora.entity.async-pathfinding: true` (LIVE, default false; `/sourbycraft reload`). Lane
pathfinding `max(1, availableProcessors/4)` thread, antrean 1024, tidak dapat dikonfigurasi. Rollback:
set `false` + reload. Prosedur APF-001…005 di [02](testing/owner/02-ASYNC-PATHFINDING-TESTING.md);
tidak ada klaim performa, gate benchmark/soak di PM-061.

### TO-APF-01 — Penolakan kapasitas dihitung sebagai `declined`

- **PM:** PM-039, wave 2 incident. **Prioritas / risiko:** P1 / sedang.
- **Langkah:** JVM `-XX:ActiveProcessorCount=4` (lane 1 thread); kandang 48×48 tertutup berisi 400–600 zombie
  mengejar pemain yang pindah 8–12 blok tiap 2 s; `/perf async` tiap 15 s selama 5 menit; `/perf incidents`;
  lalu `/stop` saat masih berbeban.
- **Diharapkan:** `Queue depth` naik ke 1024; setelah jenuh `Declined, pool full / stopping (ran sync)` naik dan
  baris `minecraft:zombie` menampilkan `/ declined N`; `Refused at submit (ran sync)` kecil; mob tetap bergerak
  (fallback sinkron; MSPT boleh naik); `/perf incidents` mencatat insiden `async path pool refused or declined
  work`; `/stop` tanpa exception.

### TO-APF-02 — Hasil delivery: applied/discarded/retired

- **PM:** PM-272, wave 3 RETIRED. **Langkah:** APF-001 dengan pemain bergerak tiap 2–5 s selama 5 menit; `/tp`
  mob yang sedang pathing melewati portal.
- **Diharapkan:** `Results applied` > 0; discard `path`/`generation` naik saat sering re-path; portal menambah
  discard `entity`; `Results retired before delivery (mob removed / unloaded, or handoff refused)` terisi saat
  mob dihapus/unload; secara kasar applied + discard + retired + outstanding ≤ admitted.

### TO-APF-03 — Handoff di thread pemilik dan atribusi per tipe

- **PM:** PM-241, PM-305. **Langkah:** APF-001 (20 zombie) 5 menit.
- **Diharapkan:** tidak ada baris thread-check/region-ownership dari AsyncPath; baris per tipe menampilkan
  tipe nyata (`minecraft:zombie`), tanpa baris `unknown`.

### TO-APF-04 — Offload ditolak di thread non-pemilik (relaxed)

- **PM:** PM-242. **Diharapkan:** plugin yang memanggil `world.setBlock` dari async tiap tick di dekat zombie →
  tidak ada exception/log dari AsyncPath; `Solves admitted` sama dengan loop on/off.
- **Status:** BLOCKED (plugin loop async setBlock tidak ada; `RelaxedProbes` hanya sekali saat enable).

### Hasil area 13

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-APF-01 | Declined + insiden | PM-039 | P1 | READY FOR OWNER TEST | | | |
| TO-APF-02 | Delivery outcomes | PM-272 | P2 | READY FOR OWNER TEST | | | |
| TO-APF-03 | Handoff + atribusi | PM-241/305 | P2 | READY FOR OWNER TEST | | | |
| TO-APF-04 | Guard non-pemilik | PM-242 | P2 | BLOCKED (plugin) | | | |

---

## 14. Telemetri, HUD, Spark (TO-TEL)

Standar area: server uji dengan beberapa pemain/bot; `test-plugin` dengan `-Dsourby.testplugin.probes=true`
untuk `/stallprobe <global|region> <sleep|lock|spin> <detik>` (jangan dengan pemain sungguhan). Rollback:
tidak ada data persisten selain `logs/stalls/` dan profil Spark. Prioritas: TO-TEL-01 P1 (kebocoran nilai
flag JVM), TO-TEL-02 P1 (region crash saat tick sprint), TO-TEL-03/05/07 P2, sisanya P3.

| ID | PM | Langkah | Diharapkan | Status |
| --- | --- | --- | --- | --- |
| TO-TEL-01 | PM-086 | JVM `-Dtest.secret=hunter2 -javaagent:/tmp/a.jar=tok -XX:HeapDumpPath=/srv/x -Xlog:gc*:file=/srv/gc.log` (agent harus ada); non-op `/bukkit:version`, `/paper:version`; klik hover dan tempel | Hover `FLAGS (values hidden):` dengan `-Dtest.secret=***`, `-javaagent:***`, `-XX:HeapDumpPath=***`, `-Xlog:***`; clipboard sama; tanpa nilai | READY |
| TO-TEL-02 | PM-102 | `region-bars.enable-tps-bar: true` di `config/aurora-worlds.yml` (nama kebab-case, cek file yang dibuat server); `/tick sprint 2000`, lalu `/tick sprint 20000` | Tanpa regionFailed/shutdown; TPS bar tetap render (`Waiting for region update...` bila belum ada data); `/tps`, `/mspt` tidak melempar; angka 5s/15s pulih dalam 15 s | READY |
| TO-TEL-03 | PM-243 | `aurora.diagnostics.stall-recorder.after-seconds: 5` (RESTART_REQUIRED; `/sourbycraft reload` tidak menghentikan recorder, hanya shutdown yang memanggil `StallRecorderRuntime.stop()`); `/stallprobe global sleep 10`; `stop` dan thread dump (`jcmd`) pada fase akhir shutdown (setelah recorder berhenti, saat dunia masih disimpan); ulangi dengan stall terbuka + SIGTERM | Dump fase akhir tanpa thread `SourbyCraft-StallRecorder-Probe` (timing-dependent; bila dump tidak sempat diambil, tulis BLOCKED dengan alasan); `logs/stalls/stall-<waktu UTC>.txt` diakhiri dump FINAL dengan counter region terisi | READY (bagian thread: timing) |
| TO-TEL-04 | PM-244 | Linux dengan swap, `/rambar`; `strace -f -e trace=openat` | Thread global tick tidak membuka `/proc/self/cgroup` atau `/sys/fs/cgroup`; thread collector sekali per detik; bar tetap menampilkan swap | READY (root) |
| TO-TEL-05 | PM-245 | ≥ 2 dunia; `/spark health --upload`; `/stallprobe region sleep 30` lalu `/spark health` 5×; heap histogram setelah poll (~20k chunk) | Statistik dunia per region; paling banyak satu task spark antre per region; tiap health ±5 s; tidak ada `FoliaWorldInfoProvider$FoliaChunkInfo` tersisa | READY |
| TO-TEL-06 | PM-246 | `/spark profiler start --timeout 30`; thread dump saat upload | Upload di thread async scheduler spark/Paper, bukan `ForkJoinPool.commonPool-worker-*` | READY |
| TO-TEL-07 | PM-275 | Server baru tanpa region aktif: `/spark tps`; lalu `/stallprobe region sleep 10` | 0 region: TPS global (~20), bukan 0.0; region beku: 5s menunjukkan 0.0, bukan 20; p95 tidak melebihi max | READY |
| TO-TEL-08 | PM-276 | 50 furnace/hopper di chunk termuat; `/spark health` | Tile entities ≥ 50 (block entity yang ticking; beda dengan spark-on-Paper) | READY |
| TO-TEL-09 | PM-277 | cgroup `memory.swap.max=0` (`docker --memory-swap` = `--memory`) | `/rambar` tanpa segmen Swap; laporan stall tanpa `swap: total` | READY (Docker/cgroup v2) |
| TO-TEL-10 | PM-274 | `/perf memory`, `/perf cpu`; `bench_panel.py` berikutnya | `Process RSS: x.yzG` (dua desimal, ±0,01 GiB dari VmRSS); label `Available processors / live Java threads`; bench_panel mengisi `available_processors` | READY |
| TO-TEL-11 | PM-308 | Buat & unload 50 dunia unik (AuroraWorlds), tunggu 16 menit, buat satu lagi; `/perf storage`, `/perf entities`; heap histogram | Dunia yang di-unload hilang; dunia termuat tetap dengan totalnya; jumlah `RegionFileSaveLatency$Recorder` ≈ dunia termuat × 3 × 2 + sisa < 15 menit | READY |
| TO-TEL-12 | PM-459 | Upload profil spark; tab metadata | `sourbycraft.slowestRegion` sama dengan `/perf region` saat itu; tanpa rahasia | READY |
| TO-TEL-13 | PM-306 | Profil spark dengan plugin yang kelasnya gagal link | Tepat satu WARNING `spark class lookup failed for …` dengan penyebab; profil tetap terunggah | BLOCKED (plugin dengan dependency hilang) |
| TO-TEL-14 | GUARD-115 | Format region bar MiniMessage tidak valid | Bila MiniMessage melempar `ParsingException`: teks literal + satu WARN `Region bar format '…' is not valid MiniMessage …` per format; MiniMessage non-strict biasanya tidak melempar (WARN dormant) | READY |
| TO-TEL-15 | PM-273 | Boot; `/perf governor` | Ledger `Async scheduler core`: `async scheduler (threaded-regions API): max(4, processors / 2) core threads, more on demand` (tanpa "Folia") | READY |

Hasil area 14: isi kolom berikut untuk setiap ID di tabel di atas.

| ID | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- |
| TO-TEL-01 … TO-TEL-15 | sesuai kolom Status di atas | | | |

---

## 15. Core: perintah, izin, SourbyMod, AbyssLink, identitas (TO-CORE)

Standar area: server uji; LuckPerms untuk tes izin. Rollback: kembalikan config/izin; hapus
`libraries/speedtest/` dan `cache/geoip` bila perlu (diunduh ulang).

### TO-CORE-01 — AbyssLink: konfigurasi tidak valid dan kegagalan koneksi berlabel

- **PM:** PM-118 (AB-003/AB-004 di 03). **Langkah:** (1) `aurora.abysslink.enabled: true`, `port: 70000`; restart.
  (2) Port benar, `client-certificate`/`client-key` ke path yang tidak ada. (3) Sertifikat valid, tidak ada yang
  listen. (4) Peer yang menyelesaikan TLS tetapi menolak autentikasi — BLOCKED (peer Leviathan).
- **Diharapkan:** (1) satu WARN `AbyssLink: unavailable (configuration): IllegalArgumentException: Invalid
  AbyssLink <kunci port>; fix sourbycraft_config/aurora.yml and restart; gameplay remains available`; `/ping`
  dan `/perf network` `AbyssLink: unavailable (configuration)`; gameplay normal. (2) WARN `AbyssLink: TLS/DNS
  setup failed (attempt 1): …; retrying with backoff, logged again every 10 failed attempts`, lalu hanya di
  percobaan 10, 20, …; `/ping` `disconnected (TLS/DNS setup failed)`. (3) WARN `AbyssLink: connect failed
  (attempt …)` dan status yang sesuai. Tidak ada isi sertifikat/kunci di WARN.

### TO-CORE-02 — Identitas build DEV/EXP saat `release=pre`

- **PM:** PM-119, PM-050. **Langkah:** jar kandidat lokal (§1): `/ver`, `/sourbycraft version`; opsional build di
  branch `feat/*` dengan `release=pre`.
- **Diharapkan:** `Build 50 — Aurora Nexus (DEV, build50/pm-completion@<sha>)` amber; `feat/*` → `(EXP, …)` amber;
  REL dengan `release=pre` (hanya CI) merah berakhiran `PR!`; REL dengan `release=true` hijau.

### TO-CORE-03 — Integritas binary speedtest

- **PM:** PM-222. **Langkah:** (1) `/speedtest` sekali; (2) `truncate -s 1000 libraries/speedtest/<os>/<arch>/speedtest`;
  `/speedtest`; (3) hapus hanya `speedtest.sha256`; `/speedtest`.
- **Diharapkan:** (1) ada `speedtest` + `speedtest.sha256`, tanpa `.part`/`.tgz.tmp`; (2) WARN `Speedtest binary … does
  not match its recorded SHA-256; downloading it again` lalu hasil normal; (3) unduh ulang. Tidak ada IP/MAC di log speedtest.

### TO-CORE-04 — GeoIP latar belakang dan back-off

- **PM:** PM-291, PM-249. **Langkah:** (1) hapus `cache/geoip`, start, `/ping` dari IP publik/proxy; (2) hapus lagi
  dan blokir `download.db-ip.com`; `/ping`; buka blokir, tunggu 60 menit, `/ping` dua kali.
- **Diharapkan:** (1) `/ping` pertama langsung `Location: unavailable`; log `Downloaded GeoIP DB …` lalu
  `GeoIP database ready (…)`; `/ping` berikutnya menampilkan lokasi. (2) satu WARN `GeoIP database download failed:
  …`; setelah 60 menit unduhan dimulai lagi tanpa restart. Batas cache 1024 LRU hanya bisa direview (tanpa seam).

### TO-CORE-05 — SourbyMod: urutan nama file dan `onUnload`

- **PM:** PM-292, PM-250 (SM-001 di 03). **Butuh:** mod uji yang dikompilasi (lihat SM-001).
- **Langkah:** `z-dup.jar` dan `a-dup.jar` dengan id `dup` di `mods/`; mod yang mencatat
  onLoad/onEnable/onDisable/onUnload; start + `stop`; lalu SIGTERM sebelum `Done (`.
- **Diharapkan:** WARN `mods/z-dup.jar: duplicate mod id 'dup' …` dan `dup` dimuat dari `a-dup.jar` di setiap host;
  normal: onLoad, onEnable, onDisable, onUnload berurutan; stop dini: onUnload tanpa onDisable.

### TO-CORE-06 — `/awf` admin permission

- **PM:** PM-289. **Butuh:** LuckPerms. **Langkah:** grup `viewer`: `sourbycraft.command.awf=true`,
  `sourbycraft.command.awf.admin=false`; coba `list`, `info`, `load`, `save`, `unload <w>` lalu `delete <w> confirm`,
  `import`, `export`, `convert`, `clone`, `set`, `template delete`, `unload <w> nosave`; ulangi sebagai op tanpa node eksplisit dan konsol.
- **Diharapkan:** viewer: perintah inspeksi/load/save/unload jalan; perintah admin `Refused: sourbycraft.command.awf.admin is
  denied to you; …`; op dan konsol menjalankan semuanya. Node `…awf.admin` (tersirat) berbeda dari
  `…awf.multiprofile.admin` (op-only, independen).

### TO-CORE-07 — Penolakan saat runtime stopping (`/awf`, `/speedtest`)

- **PM:** PM-247. **Diharapkan:** `The server is stopping or failed to start; /awf starts no new work`.
- **Status:** BLOCKED (state STOPPING/FAILED tidak dapat dicapai andal secara manual).

### Hasil area 15

| ID | Fitur | PM | Prio | Status | Tanggal | Commit | Bukti |
| --- | --- | --- | --- | --- | --- | --- | --- |
| TO-CORE-01 | AbyssLink berlabel | PM-118 | P2 | READY (1–3); BLOCKED (4: peer Leviathan) | | | |
| TO-CORE-02 | Identitas DEV/EXP | PM-119/050 | P1 | READY FOR OWNER TEST | | | |
| TO-CORE-03 | Speedtest | PM-222 | P3 | READY FOR OWNER TEST | | | |
| TO-CORE-04 | GeoIP | PM-291/249 | P3 | READY FOR OWNER TEST | | | |
| TO-CORE-05 | SourbyMod | PM-292/250 | P2 | READY (butuh mod uji) | | | |
| TO-CORE-06 | `/awf` admin | PM-289 | P1 | READY (butuh LuckPerms) | | | |
| TO-CORE-07 | Stopping refusal | PM-247 | P3 | BLOCKED (state tidak tercapai) | | | |

AB-002 (AbyssLink dengan peer) tetap BLOCKED sampai ada peer Leviathan uji.

---

## 16. Perubahan tanpa tes manual tersendiri, dan pekerjaan yang tidak ada di PR #62

Hanya direview statis (tidak ada perilaku yang bisa diamati owner secara andal, atau hanya teks):

| PM | Perubahan | Cara diperiksa |
| --- | --- | --- |
| PM-224 | `AwfRegionStorage.flush()` juga menangkap `FencedException` (bukan hanya `AwfDeadlineExceeded`) | Race fence vs lock tidak bisa dipicu manual; perilaku shutdown terlihat di TO-AWF-17. Spec unit test di matrix. |
| PM-261 | Javadoc budget shutdown: commit FILE yang sedang menulis (fsync) tidak dapat diputus | Dokumentasi; TO-AWF-17 dan AWF-007 mengamati batasnya. |
| PM-294, PM-307, PM-298, PM-112, PM-266, PM-267, PM-268, PM-316, PM-279 (bagian javadoc) | Javadoc dan komentar seeded (komentar seeded hanya masuk ke file baru) | Baca teks; `ConfigExamplesTest` tidak membandingkan komentar. |
| PM-296 | Kode mati MultiProfile dihapus (`ProfileSession`, metode repository yang tidak dipakai) | B50-01 (compile + test). |
| PM-260 (javadoc `AwfRegistry`), PM-074 (javadoc `Durability`) | Javadoc | Baca teks; baris `/perf awf` diuji di TO-AWF-19. |

Belum dikerjakan di PR #62, jadi tidak ada perilaku baru untuk diuji (status rinci di matrix):

- **Butuh patch dari repo materialized (BLOCKED toolchain):** PM-085 (race `pendingWriteFuture` di
  `SavedDataStorage`), PM-111 (`/restart`), PM-114 (`@Unsupported` pada teleport), PM-239 (guard
  `openInventory`), PM-240 (entry point scheduler Folia), PM-255 (bonus chest), PM-256 (score holder
  non-entitas), PM-257 (`/tp … facing`), PM-270 (nama thread region), sisa PM-099
  (`FoliaAsyncScheduler#cancelTasks`), urutan shutdown patch 0081 (snapshot quit saat shutdown),
  `Level.canvasConfig` volatile, null guard baseline tick report, `@Unsupported` pada `getTickableTileEntityCount`.
- **Butuh keputusan owner atau perubahan baseline:** PM-028 (commit vs playerdata vanilla), PM-075 (`/schedule`
  TimerQueue autosave), PM-234 (anchor EntityTask), PM-300, PM-303, PM-458 (biaya listener per plugin), PM-460
  (branch 26.3), PM-011, PM-012, PM-179.
- **Hanya tes (DEFERRED, owner melarang membuat tes di PR ini):** PM-003, PM-004, PM-005, PM-293, PM-299,
  PM-317, dan unit test yang disebut di laporan implementasi per item.
- **Tidak dikerjakan/di luar repo:** PM-454 (prefetch tetangga Redis), PM-278 (sufiks staging SourbyClip, repo
  privat), PM-302 (helper teleport + ledger), PM-312 (validasi skema SBOM).

## 17. Keputusan rilis dan rollback

**Tidak ada promosi** (perubahan `release=`, tag baru, atau label STABLE/qualified) sebelum:

1. B50-01 PASSED pada SHA kandidat, termasuk regresi otomatis atau alasan tertulis untuk setiap kegagalan.
2. Semua tes P0 di dokumen ini PASSED, atau BLOCKED dengan alasan dan keputusan owner yang tercatat.
   Race yang tidak terpicu tetap BLOCKED, bukan PASSED.
3. Kasus lama yang terdampak dijalankan ulang di SHA kandidat: AWF-001…011, MP-001…009, RF-001…006,
   FR-001…011, BR-001/002, UP-001, SM-001 (lihat §3).
4. Gate yang lebih luas di matrix (bukan perubahan PR #62) dinilai: soak ≥ 2 jam dengan klien nyata dan plugin
   representatif (PM-020), stability gate Build 49 diulang (PM-021), failure recovery (PM-018), A/B tersertifikasi
   bila ada klaim performa ([panduan performa](testing/owner/04-PERFORMANCE-BENCHMARK-GUIDE.md), PM-057/061),
   backend Redis lewat jaringan nyata (PM-063), updater end-to-end dengan kunci asli (PM-068), bootstrap
   cold/offline (PM-182), migrasi config dari Build 48 nyata (PM-198).
5. Keputusan owner yang memengaruhi hasil dicatat: jalur publikasi **PM-011**; mode thread-checks yang
   di-qualify **PM-012**; pembaca data persisten Canvas **PM-179**; commit vs playerdata vanilla **PM-028**
   (crash setelah commit sebelum flush playerdata masih dapat menduplikasi item); kebijakan anchor EntityTask
   **PM-234**; nilai API `UNLOADED_NOT_DURABLE`; `setAutoload` asinkron; adopt-vs-archive pra-profil.

**Hentikan pengujian dan laporkan** bila terjadi: duplikasi atau kehilangan item, korupsi data, save/unload
yang tidak terkonfirmasi dilaporkan sukses, crash, callback menggantung, antrean/heap terus tumbuh, guard
ownership baru, atau MultiProfile terdistribusi menulis dengan klaim basi.

**Rollback kandidat:** hentikan server; simpan log dan salinan state kandidat sebagai bukti; pulihkan jar +
data + config yang cocok dari backup §4.2; periksa §4.3 sebelum membuka data kandidat dengan jar lama
(selesaikan journal MultiProfile, simpan ulang profil dengan efek infinite, jangan pakai repository Redis
dengan jar lama). Karena tag `v26.2-r50` sudah ada, mengubah `release=true` tidak menerbitkan ulang tag itu
(CI hanya menerbitkan bila tag `v26.2-r<sourbyBuild>` belum ada); jalur publikasi adalah keputusan owner
**PM-011** (mis. r50.1 atau Build 51); jangan menimpa tag.

## 18. Tautan

- [Matrix PM Build 50](releases/BUILD-50-PM-MATRIX.md) — status per item dan kolom *Owner verification*.
- [Build 50 readiness](releases/BUILD-50-READINESS.md) — rencana penutupan pekerjaan parsial dan keputusan rilis.
- [Catatan rilis Build 50](releases/26.2-build-50-aurora-nexus.md) dan [TODO](TODO.md) (sumber status checklist).
- [Owner testing index](testing/owner/00-TESTING-INDEX.md), [lembar hasil](testing/owner/06-RESULTS-TEMPLATE.md),
  [laporan bug](testing/owner/07-BUG-REPORT-TEMPLATE.md).
- [MultiProfile distributed ownership](awf/multiprofile/DISTRIBUTED-OWNERSHIP.md), [AUTO-UPDATER](development/AUTO-UPDATER.md),
  [AURORA-CONFIG](AURORA-CONFIG.md), [runbook Build 49](releases/runbooks/build-49-real-test.md).
