# SourbyCraft 26.2 — To-do List Proyek

Satu daftar terurut berdasarkan urgensi, dikumpulkan dari `docs/DEVELOPMENT-TASKS.md`,
`docs/AURORA-TASKS.md`, `docs/AURORA-ROADMAP.md`, `docs/AURORA-FULL-TRANSITION.md` dan
`docs/architecture/independence.md` (per 2026-10-05, branch `26.2`).

Dokumen sumber tetap menjadi rincian dan bukti per tugas. Ketika satu tugas selesai, centang di
sini **dan** di dokumen sumbernya, sertakan bukti (run CI, commit, laporan) sesuai
`DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy`.

Plan lama di `docs/superpowers/plans/` (era v6/v12/Folia, Mei–Juli 2026) tidak dimasukkan; isinya
sudah digantikan dokumen di atas.

Status: `[ ]` belum · `[-]` sebagian / perlu validasi · `[!]` terblokir keputusan owner

---

## P0 — Mendesak (memblokir rilis stabil, legal, atau keamanan data)

### Rilis Build 47 — gate yang masih terbuka (audit 2026-10-07)
Hal yang memblokir `release=true`. Status per gate dan buktinya:
`docs/releases/26.2-build-47-aurora-nexus.md` *Release readiness (audited 2026-10-07)*.
Head ter-commit `1fe984ed` lulus CI run 485 (2026-10-06); tree kandidat rilis (Paper 0005–0011,
Minecraft 0023–0027, perbaikan bridge/AWF) belum di-commit dan belum pernah jalan di CI.
- [!] Keputusan lisensi owner (PolyForm Noncommercial vs GPLv3 Paper; lihat *Keputusan owner*).
- [x] Commit tree kandidat rilis, lalu satu run CI hijau: compile, suite Java + Python, boot,
  shutdown, termasuk marker fixture yang belum pernah jalan (`LEGACY_GLOBAL_*`,
  `LEGACY_REGION_TASK_OK`, `LEGACY_ENTITY_TASK_OK`, `LEGACY_ENTITY_RETIRED_OK`,
  `LEGACY_DISABLE_*`) dan langkah baru `patch_surface.py --check`. Boot CI ini juga yang pertama
  dengan feature patch 0024/0025 (Intave privat, auto-provision plugin sebelum plugin scan).
  SELESAI: push `beb7f40e` 2026-10-07, CI run 37515867230 success 33/33 langkah, 20 jenis marker OK.
- [x] Regenerasi patch bersih: urutan commit materialized = urutan file (0024/0025 ↔ 0026/0027
  menurut `patch_surface.py --check-rebuild`, 2026-10-07), `rebuild*Patches` tanpa diff isi,
  8 file feature patch yang masih untracked di-commit. SELESAI 2026-10-07: reorder tanpa diff isi,
  rebuild tanpa perubahan konten, `applyAllPatches` dari file lulus lokal dan di CI 37515867230.
- [!] Patch 0028 (`AuroraEdfScheduler`) DITAHAN dan diparkir (keputusan owner 2026-10-07): rilis
  tanpa 0028 kecuali owner memutuskan lain.
- [ ] Pasangan referensi tersertifikasi (`idle` + `chunk-stress`) di mesin tenang; tanpa itu gate
  regresi >3% terblokir (`docs/architecture/qualification-readiness.md` §1, §6).
- [ ] Soak multi-jam di tree kandidat rilis dengan set plugin `plugins-10`; soak tersertifikasi
  2026-09-15 (commit `1c883a55`) sebelum Build 47 dan tidak berlaku.
- [ ] Bug kepemilikan region yang diketahui: NPE `/say` di global tick dan NPE perintah konsol
  setelah `Done` (keduanya belum direproduksi ulang).
- [ ] Persistensi dan crash diulang di tree kandidat rilis (`verify_persistence*.py`,
  `verify_crash.py`); XFAIL metadata game time/spawn diperbaiki atau diterima owner.
- [ ] Redis terputus / lease hilang pada server yang berjalan (backend failure).
- [ ] Alur pemain nyata dengan plugin bridged (EssentialsX/Vault) di Sourby Demo.

### Keputusan owner
- [!] **Lisensi:** repo memakai PolyForm Noncommercial, sementara jar yang didistribusikan berisi kode
  server Paper berlisensi GPLv3. Perlu keputusan apakah kombinasi ini memenuhi GPLv3
  (`docs/releases/26.2-build-47-aurora-nexus.md`). Ini juga menentukan bentuk migrasi penuh di P2.
- [x] **Definisi "migrasi 100% ke Aurora":** owner memilih "lepas Canvas + Weaver", Paper tetap
  upstream (2026-10-05). Lihat bagian *Migrasi penuh* di P2.
- [!] **Workload produk yang representatif:** "satu pemain menjelajah medan baru" atau
  "10 pemain menetap". Tiga hasil chunk worker saling bertentangan karena mengukur workload berbeda
  (`docs/architecture/chunk-workers.md` *Three workloads, three answers*,
  `docs/architecture/compat-vs-performance.md` §4 dan §6 butir 3). Yang bergantung pada keputusan
  ini: rekomendasi jumlah worker thread chunk (default kode tetap 2 pada 8 core; Sourby Demo memakai
  `chunk-system.worker-threads: 6` atas keputusan operator 2026-10-06, TIDAK tersertifikasi),
  rekomendasi di `chunk-workers.md`, dan prioritas pre-generation dunia.

### Persistensi dan kepemilikan region
- [!] **Scheduler region EDF baru (patch 0028, `AuroraEdfScheduler`, turunan LeafPile) DITAHAN** (keputusan
  owner 2026-10-07) sampai lulus `AuroraEdfSchedulerTest`, tes independence policy, dan boot. Patch
  dan tesnya diparkir di `sourbycraft-server/minecraft-patches/parked/`; commit materialisasinya dilepas.
  Catatan: jar demo 2026-10-07 00:00–00:36 dan bench `plugins-10` sempat berjalan di atasnya.
- [-] Matikan watchdog diagnosis region/Folia secara default (permintaan pengguna,
  2026-10-06). Feature patch 0026 ditulis dan diterapkan ke source materialized;
  JVM `sourbycraft.region-watchdog.enabled` default false, RESTART_REQUIRED. Ownership
  guard serta penanganan failure/shutdown tetap aktif; build/boot/testing oleh Claude
  masih pending. Scope/kasus penerimaan: `docs/development/REGION-WATCHDOG.md`, SPEC §158.
- [x] Tes persistensi restart penuh setelah perubahan performa (DEV-A, AURORA-J), build
  pasca-migrasi, 2026-10-05:
  - lokal: `verify_persistence.py` 104/104 (4 restart berturut-turut, data pemain round-trip di
    disk dan lewat server, shutdown dengan 6 klien bergerak + pemain online);
  - deployment (Sourby Demo, panel.parama.cloud): `verify_persistence_panel.py` 20/20 (3 restart
    panel nyata; blok, entity, game time; shutdown bersih; probe dibersihkan). Data pemain tidak
    bisa diuji di sana karena `online-mode=true` menolak klien headless.
- [x] Tes restart/crash untuk setiap perubahan save async (DEV-G), 2026-10-05:
  `verify_crash.py` — region file 49/49, AWF 53/53; tiga `kill -9` (3/8/15 detik) saat penyimpanan
  berjalan dengan 4 klien bergerak; checkpoint yang sudah di-flush/commit selalu utuh, tidak ada
  korupsi. Satu keterbatasan diketahui (lihat item berikut)
- [ ] **Metadata game time/spawn dunia belum masuk save-all/autosave**: bukti crash historis
  `verify_crash.py` XFAIL. Review source 26.2 (2026-10-06): metadata aktif ada di
  `PaperLevelOverrides` (`paper:level_overrides`), yang tidak masuk whitelist
  `RegionizedServer.autosaveSafeWorldData`; judul lama yang hanya menyalahkan `level.dat`
  tidak menggambarkan jalur load sekarang. Perbaikan dan penerimaan crash/spawn masih pending;
  jangan memanggil bulk save SavedData dari global tick karena sebagian data dimiliki region.
- [ ] AWF create masih menunggu generasi spawn pada global tick: report Spark pengguna
  `https://spark.lucko.me/RyP3FlAYME` mencatat 4.650 ms sampel inclusive pada
  `/awf create` → `setInitialSpawn` → `syncLoadNonFull`; 4.570 ms di `parkNanos`.
  Ini akumulasi sampel, bukan bukti satu pause 4,65 s. Handoff Claude: tahap persiapan async
  saja belum menghilangkan wait di dalam `createWorld`; perlu desain spawn/lifecycle yang
  mempertahankan ownership dan kondisi terrain. Tidak membuktikan semua stutter eksplorasi.
  Bukti/lingkup: `docs/development/AURORA-EDF-UPGRADE.md`.
- [ ] Perintah konsol kadang gagal dengan `NullPointerException` (`CommandSourceStack.getLevel()`
  null) dalam ~2 detik setelah `Done`, teramati saat boot ulang dunia AWF (direproduksi sekali,
  lalu tidak muncul di run berikutnya). Akar masalah belum ditemukan; harness sekarang menunggu
  perintah konsol pertama yang berhasil
- [-] Review konkurensi penulisan region-file (DEV-G), dimulai 2026-10-06. Diperbaiki: dua commit AWF
  bisa mendarat di store dalam urutan terbalik dari snapshot-nya, sehingga store menyimpan isi chunk
  lama (`AwfCommitOrderTest`); `AwfRegionStorage.write` vs `close` kini saling eksklusif
  (`AwfLifecycleFenceTest.aWriteAdmittedJustBeforeCloseIsCommittedByIt`, unit test 2026-10-07, belum
  boot). Sisa: flush bersamaan dari banyak region, dan dokumen review
- [-] Bukti "tidak ada regresi kepemilikan region" dan "tidak ada regresi persistensi" (gate rilis):
  persistensi tree kandidat 20/20 di demo 2026-10-07 (`verify_persistence_panel.py`, 3 restart);
  kepemilikan region: fixture bridge 20 marker OK di CI 37515867230, soak 100 menit tanpa pelanggaran.
  Sisa: dua NPE konsol yang belum tereproduksi, rerun `verify_crash.py` pada tree ini
- [-] AWF: crash di level server dengan klien sudah lulus (53/53, `verify_crash.py --awf`). Sisa:
  backend terputus (backend Redis sudah ada; penerimaan gangguan jaringan belum tercatat), beban berkelanjutan, dan investigasi
  commit awal 529 chunk yang butuh 105,5 detik di panel demo (`architecture/aurora-world-fabric.md`)

### Konkurensi
- [-] Soak konkurensi multi-jam (2 jam+) (DEV-A, AURORA-C/J): soak tersertifikasi 2 jam 10 pemain
  ada (2026-09-15, `docs/BASELINE.md` "Second soak", commit `1c883a55`), tetapi sebelum Build 47 dan
  tanpa plugin. Build 47: run `plugins-10` 2026-10-07 di demo ~100 menit dengan 10 bot (terputus
  oleh jaringan lokal; TIDAK tersertifikasi): TPS median 20, 2 dip, merge region 10 → 3–4
  (`docs/BASELINE.md`). Sisa: 2 jam penuh dari host yang stabil, seri heap
- [ ] Audit lengkap kebenaran snapshot async pathfinding (DEV-E, ROADMAP M5)
- [-] Pembatalan/shutdown async pathfinding: tes penghapusan entity, unload world, dan target
  bergerak saat solve sedang berjalan (ROADMAP M5)
- [-] Bukti reentrancy scratch milik entity (posisi skalar) (DEV-A)
- [-] Bukti runtime bahwa thread region tidak pernah blok pada I/O eksternal (saat ini baru audit statis)

### Jaringan dan keamanan
- [ ] Verifikasi event loop Netty tidak melakukan I/O blocking yang tidak terkait (DEV-H)
- [ ] Pertahankan limit packet/security (`sourbycraft-security.yml`) di setiap perubahan network

### SourbyClip (sekarang bisa dikerjakan di repo `YanIanZ/SourbyClip` dengan Codex)
- [-] Audit timeout downloader, retry/failure, validasi SHA/cache, dan konkurensi di dalam
  SourbyClip: kandidat lokal 3.1.1 sudah diperiksa dan diuji (29 tes Java; timeout connect/read,
  staging SHA, mirror fallback, worker bounded). Whole-transfer deadline/size cap dan pin rilis
  masih terbuka; bukti di `docs/architecture/bootstrap-download-audit.md` (DEV-L, AURORA-I)
- [-] Kualifikasi cold-download dari remote (cold bootstrap pernah gagal cek hash Mojang dengan exit 0)

---

## P1 — Tinggi (bukti performa dan kualifikasi rilis)

### Pengujian panel (panel.parama.cloud)
- [-] Jalankan build terbaru di panel: build pasca-migrasi (`2554782`) terpasang di Sourby Demo sejak
  2026-10-05, boot tanpa ERROR, persistensi 20/20; jar sebelumnya disimpan sebagai
  `SourbyCraft-slim.pre-migration-20261005.jar`. Vault/EssentialsX sudah enable dengan 0 pelanggaran
  bridge pada `9a15f51`. Sisa: alur pemain nyata dengan plugin legacy lewat bridge (butuh pemain
  sungguhan karena `online-mode=true`)

### Baseline tersertifikasi (DEV-B, AURORA-C, ROADMAP M8)
- [-] Bench panel (Sourby Demo) dengan klien sungguhan: `scripts/bench_panel.py` siap (mode bench
  dengan dunia terpisah, whitelist bot, pemulihan otomatis). Run pertama players-10: TPS 20, MSPT
  median 5,1 ms, p99 10,6 ms (TIDAK tersertifikasi; lihat `docs/BASELINE.md`). Sisa: dunia bench
  yang di-pre-generate atau klien ber-leash agar generate chunk tidak masuk jendela ukur, lalu
  players-50 dan entity-stress
- [ ] Baseline idle
- [ ] 50 pemain dan 100 pemain (10 pemain sudah tersertifikasi)
- [ ] Beban gameplay dengan klien nyata yang terhubung
- [ ] Entity stress; mob AI; entity tracking; combat
- [ ] Generate/load/unload chunk; chunk traversal
- [ ] Baseline network gameplay
- [ ] Pemulihan heap setelah beban; stabilisasi heap/thread/queue
- [ ] Review JFR, termasuk hot spot contention dan I/O
- [ ] Laporan benchmark representatif (gate rilis)
- [-] Workload `plugins-10`: `players-10` dengan plugin nyata (EssentialsX dan Vault lewat bridge,
  SuperiorSkyblock2 port native, spark) di harness yang sama (`scripts/baseline_workloads.py`,
  `run_baseline.py`; `docs/architecture/compat-vs-performance.md` §6 butir 1). Workload dan tes
  selesai (2026-10-07). Satu run TIDAK tersertifikasi di Sourby Demo lewat `bench_panel.py`
  (2026-10-06): TPS median 20, MSPT 4,93 ms, p99 12,7 ms, 10 region; biaya body bridge total
  Essentials 84,8 ms / Vault 7,69 ms sepanjang run (`docs/BASELINE.md` "The plugin workload").
  Sisa: run di harness lokal tidak mungkin di mesin ini; sertifikasi butuh host lain
- [-] Waktu eksekusi task bridge per plugin (count, total, max, jendela terbaru) di `/plugins <name>`
  dan `/perf plugins`, metrik penghubung kompatibilitas dan performa
  (`compat-vs-performance.md` §6 butir 2). Selesai di kode, 48 tes bridge lulus, terlihat di demo
  (`/perf plugins` menampilkan body total/p99 per plugin bridged, 2026-10-06). Belum di-commit
  per 2026-10-07; dipakai di run `plugins-10` (Essentials 84,8 ms total, Vault 7,69 ms)

### Scheduler dan pathfinding
- [ ] Benchmark pathfinding sync vs async
- [ ] Soak kompatibilitas perilaku mob
- [ ] Pindahkan pool async path ke belakang AuroraExecution dengan callback owner-context eksplisit (M5)

### Memori
- [ ] Verifikasi alokasi MB/s terhadap JFR
- [ ] Verifikasi RSS/cgroup di Linux/container
- [ ] Workflow laporan kelas yang paling banyak dialokasikan
- [ ] Tes retensi: player disconnect, chunk unload, world unload

### Observability
- [-] `/tps`, `/mspt`, `/perf`, `/perfbar`, `/tpsbar`, `/rambar` disatukan dengan Sourby metrics
  (sudah diimplementasikan, perlu validasi)
- [ ] `/ram` lengkap; `/perf region <world> <x> <z>`
- [-] `/perf scheduler`: antrean milik region scheduler sendiri belum ditampilkan
- [ ] Verifikasi config SourbyCraft/Aurora tampil benar di Spark web viewer
- [ ] Klasifikasi thread region yang lebih baik di Spark
- [ ] Telemetri: latensi tunggu task, counter siklus hidup chunk, counter tick entity, histori insiden
- [-] Latensi save region-file (saat ini hanya ada latensi commit AWF)

### Optimasi NMS terukur (hanya setelah baseline ada)
- [ ] Profil Entity/LivingEntity/Mob tick, GoalSelector, Brain/Sensor, navigasi, merge/pickup item
- [-] Stutter generasi area baru (laporan pengguna 2026-10-06): source pengukuran opsional
  antrean/eksekusi per tahap generic generation di `/perf chunks`, feature patch 0027.
  RESTART_REQUIRED: `-Dsourbycraft.chunk-generation-metrics.enabled=true` ATAU TOML
  `aurora.diagnostics.chunk-generation-metrics = true` (2026-10-07, untuk panel yang tidak bisa
  mengirim flag -D; `ChunkGenerationMetricsEarlyConfigTest` 7/7); default off. 0027 sudah
  di-build dan boot di demo dengan metrik mati. Sisa: run dengan metrik nyala di demo, profil
  worker/region + CPU/GC, lalu A/B kandidat; akar stutter dan keuntungan belum terbukti.
  Pengukuran delivery chunk 2026-10-06 (worker 2 vs 6) ada di `chunk-workers.md`.
  Lingkup/sisa: `docs/development/CHUNK-GENERATION-STUTTER.md`.
- [!] EDF LeafPile/Spottedleaf (DITAHAN 2026-10-07, patch 0028 diparkir; lihat P0): source backend turunan `AuroraEdfScheduler` memperbaiki
  cancel saat tick berjalan, link replacement, rearm deadline, pelepasan blocker task batal,
  publikasi state, dan admission setelah halt. Feature 0028 memasang backend pada opsi EDF;
  perlu build + restart. Enam regresi disiapkan, belum dijalankan; semua testing oleh Claude.
  Worker/tick rate tetap. Kredit + GPL LeafPile disertakan. Belum qualified/performa terukur;
  `docs/development/AURORA-EDF-UPGRADE.md`, SPEC V38/B84.
- [ ] Profil chunk holder lookup, ticket, region-file I/O, player chunk tracking, konstruksi packet chunk
- [ ] Profil encode/decode, kompresi, chunk-send, dan jumlah alokasi/copy packet
- [ ] Optimasi Aurora pertama yang terukur untuk masing-masing domain: entity, chunk, network

### Bridge plugin legacy
- [-] SuperiorSkyblock2 2026.3: callback biome spawn berjalan di global tick dan melanggar
  ownership (log Build 47 dari pengguna). Source routing target eksplisit + adapter dengan
  fingerprint kelas dan feature patch 0010 ditulis; regresi dispatch kini lulus (`BridgeTargetRoutingTest`
  9/9, paket bridge 61/61, 2026-10-07), NPE meta null diperbaiki (B87). Plugin itu sendiri sudah di-port ke Folia
  (repo eksternal `~/Sourby/ssb/SSB2`, diverifikasi di Sourby Demo 2026-10-06), jadi adapter hanya
  berlaku untuk jar 2026.3 upstream yang tidak di-port. Adapter dengan jar nyata belum diuji;
  `docs/development/SUPERIORSKYBLOCK-BRIDGE.md`, SPEC B81/V36. Belum qualified seluruh plugin.
- [-] Port scheduler global + disable cleanup dari LightingLuminol/Luminol (Bacteriawa);
  source adapter dan feature patch 0007 disiapkan 2026-10-06, credits/lisensi dipertahankan.
  Source materialized di Paper commit `425610c83`; suite Gradle penuh 10.152/10.152 lulus
  sesudahnya (2026-10-06), regresi native-disable ditemukan saat boot dan diperbaiki di 0009.
  Marker fixture `LEGACY_GLOBAL_*` belum pernah jalan di CI. Rincian dan kasus penerimaan
  di `docs/development/LIGHTINGLUMINOL-BRIDGE-PORT.md`.
- [-] Upgrade kapasitas bridge per plugin: limit pending/running async LIVE (default 0),
  worker Bukkit + status body yang masih draining setelah cancel, dan statistik `/plugins`.
  Source + feature patch 0008 ditulis dan materialized; tes batas `BridgeReviewTest` lulus
  (2026-10-07, unit saja); disable lalu reload kini bisa menjadwalkan lagi (B86). Sisa: enable ulang
  instance yang sama tanpa reload (butuh hook enable), boot/CI.
- [x] Indeks task per plugin + disable/admission/rejection cleanup; regresi 100 plugin simulasi,
  overlap async dan race scheduler; BridgeRuntimeTest 24/24, suite server 10.147 tes tanpa
  kegagalan (23 skipped), 2026-10-06; SPEC §155 dan `docs/architecture/aurora-plugin-bridge.md`.
- [x] Rute entity-owner lewat `EntityTask` (sourbyapi) + Paper 0011: IMPLEMENTED, 6 tes
  `BridgeTargetRoutingTest` v39 lulus (2026-10-07), SPEC V39; callback tanpa `EntityTask` tetap tidak
  dirutekan ke entity. Marker CI `LEGACY_ENTITY_*` belum jalan (blok rilis P0)
- [-] Plugin legacy representatif dengan alur pemain dan soak

### Rilis
- [ ] Tetap `release=pre` sampai semua gate di atas lulus
- [-] Audit ulang klaim terukur di README/release notes sebelum tagging: audit 2026-10-07 dilakukan
  (release doc, README, SPEC §161); ulangi pada commit yang akan di-tag

---

## P2 — Menengah (independensi dan arsitektur)

### Anticheat native untuk build pribadi
- [x] Implementasi auto-provision ProtocolLib melalui jalur aktif SourbyClip/SourbyPatcher
  (2026-10-06): pin asset resmi, HTTPS + SHA-256/ukuran, setting independen, hook sebelum
  plugin scan, folder CLI, cache/offline dan pelestarian operator; 12 tes installer lulus.
  Mode Intave native tetap mengisolasi backend paket internal. Bukti: `docs/development/PROTOCOLLIB.md`.
- [-] Qualification ProtocolLib: download artifact resmi, packaging/boot baru, dependency-plugin
  compatibility dan region soak belum diverifikasi; coexistence dengan Intave native belum tersedia.
- [x] Persiapan workspace Intave privat (2026-10-06): 1.403 file upstream terverifikasi,
  relokasi main/test, 147 kelas tes, inventaris library lokal, pin 18 dependency, tooling
  resolve/doctor/pemulihan fixture, project baseline Gradle, contoh config dan 10 gate port.
  22 tes tooling lulus. Belum siap build/runtime: 20 binary belum tersedia, DNS terminal
  gagal, Gradle socket-lock ditolak lingkungan. Bukti: `docs/architecture/intave-native-integration.md`.
- [x] Implementasi awal port native Intave (2026-10-06): private `IntaveEngine` bukan JavaPlugin,
  lifecycle server, adapter packet dengan antrean Connection, hook owner tick, status operator,
  profil build opt-in dan pemeriksaan provenance. 12 tes controller + 22 tes tooling + 8 tes migrasi lulus;
  compiler adapter/Connection terhadap artifact lokal 26.2 lulus; 4 probe antrean paket nyata
  lulus setelah perbaikan finish future; static typecheck wiring Gradle lulus. Bukti dan cakupan:
  `docs/architecture/intave-native-integration.md`.
- [-] Verifikasi engine Intave lengkap: `NATIVE_ACTIVE_SELFTEST_PASSED_DETECTION_UNVERIFIED` (2026-10-07).
  Profile privat dicompile (1.189 source engine), `slimServerJar` privat dibangun, boot lokal
  mencapai `ACTIVE`, self-test Intave selesai (59 metode, 7 no-op karena gate versi) dan shutdown
  bersih (`Native engine cleanup completed`, exit 0). Keputusan owner: pembacaan blok off-region
  hanya racy read chunk yang sudah loaded (tanpa load/ticket; unloaded = AIR/0/shape kosong);
  write, block entity dan akses entity tetap ditolak. Perbaikan port: urutan classpath NMS lama,
  filter Floodgate, ASM vendored ke Java 25, atribusi caller native, self-test legacy material dan
  scoreboard via global scheduler, tes potion; 183 hash direproduksi, 0 fixture hilang. Tes:
  server-owned 23/23 (privat), 19/19 (publik); upstream 721: 694 lulus, 27 gagal (registry tes,
  server null, path cwd, material 26.3), 4 skipped. Sisa gate: efektivitas deteksi (butuh client
  nyata + cheat diketahui), false positive, join/quit, race pembacaan racy, ViaVersion, region soak,
  CPU/alokasi. Tidak ada klaim efektivitas. Bukti/batasan:
  `docs/architecture/intave-native-integration.md#kebijakan-pembacaan-blok-2026-10-07`.

### Migrasi penuh ke SourbyCraft (Aurora Engine) tanpa Canvas/Folia
Sejak 2026-10-05 build berjalan: vanilla → Paper (`paperRef` 9240f586) → SourbyCraft, lewat
paperweight resmi (SourbyPatcher `paper-toolchain` 3.0.0). Seluruh perubahan yang dulu datang dari
Canvas/Folia sekarang milik SourbyCraft: baseline engine di `minecraft-patches/sources` (476 file),
`paper-patches/files` (234), `sourbyapi/paper-patches/files` (16), dan source `io.canvasmc` di
`src/main/java`. Perubahan buatan Sourby ada di feature patch (`minecraft-patches/features` 22,
`paper-patches/features` 4). Batasan yang tidak bisa dihindari:

- Source Minecraft hasil decompile tidak boleh di-commit (EULA Mojang), jadi pipeline
  "decompile + apply patch" tetap dibutuhkan, dengan SourbyPatcher sebagai pemiliknya.
- Package API yang dipakai plugin (`org.bukkit.*`, `io.papermc.paper.*`, termasuk scheduler Folia
  `io.papermc.paper.threadedregions.scheduler`) harus tetap sama agar plugin Paper/Folia tetap jalan.
- Kewajiban atribusi dan lisensi upstream tetap berlaku (lihat keputusan lisensi di P0).

Tahapan yang diusulkan, dari yang paling kecil risikonya:
- [x] M-0: owner memilih target: lepas Canvas + Weaver, Paper tetap upstream
- [-] M-1 Identitas: nama publik SourbyCraft/Aurora sudah ada; sisa: laporan crash memakai
  BuildInfo, profiler identity
- [ ] M-2 Isolasi (T9 di `AURORA-FULL-TRANSITION.md`): pindahkan body service Sourby keluar dari
  class upstream; pecah patch campuran 0006/0013 dan pindahkan class dari 0002/0005/0016; isolasi
  bridge Spark Canvas; dokumentasikan setiap dependensi keras Canvas di `independence.md`
- [x] M-3 Lepas Canvas sebagai upstream — selesai 2026-10-05. Bukti: tree hasil pipeline baru
  identik dengan pipeline lama (Minecraft 5.359 file, paper-server 1.549, paper-api 1.975; selisih
  hanya newline akhir file di 48 file), 10.096 tes Java + 222 tes Python lulus, boot test CI lulus
  lokal dan di CI (run 37282530676, hijau), dan tes persistensi restart lulus lokal (104/104) serta
  di server deployment (20/20). Mulai sekarang setiap
  perbaikan dari Canvas/Folia harus di-port sendiri.
- [x] M-4 Lepas Weaver — selesai 2026-10-05: SourbyPatcher `paper-toolchain` 3.0.0 menerapkan
  paperweight 2.0.0-beta.24; jar reproducible dari clone bersih; gate sama dengan M-3.
- [x] Rename package internal `io.canvasmc.canvas.*` menjadi `dev.iyanz.aurora.engine.*` (2026-10-05).
  API plugin tetap `io.canvasmc.canvas` (`event`, `region`, `simd`, `Unsupported`,
  `WorldUnloadResult`, di `sourbyapi`). Plugin yang memakai class internal Canvas (bukan API)
  harus diperbarui.
- [x] Hapus dependency `io.canvasmc.httpclient` beserta repo Maven `maven.canvasmc.io` (2026-10-05).
  Ternyata dipakai: `ClientV2` memanggil `canvasmc.io/api/v2` dari `/version` dan setelah config
  dimuat jika `Build-Number` terisi (tidak pernah di build CI). Panggilan itu ikut dihapus.
- [ ] Putuskan nama branch rilis `release/26.2-canvas` (dipakai workflow publikasi dan auto-updater)
- [ ] Rencana migrasi nama file konfigurasi `config/canvas-server.yml` / `canvas-worlds.yml`
  (sekarang sengaja tetap, agar konfigurasi server yang sudah ada tetap terbaca)
- [ ] Pengaman agar baseline engine (`minecraft-patches/sources`, `paper-patches/files`) tidak
  diedit diam-diam: perubahan Sourby wajib lewat feature patch supaya terlihat oleh policy
- [ ] Pin SHA-256 SourbyPatcher hanya mencakup jar, tidak POM tempat versi paperweight ditentukan
  — kandidat lokal 3.1.0 memeriksa versi paperweight yang dimuat terhadap identitas di JAR;
  hash POM/dependency penuh tetap belum diimplementasikan
- [-] Nomor feature patch punya celah (0018) dan akan dinomori ulang pada rebuild berikutnya;
  perbarui rujukan nomor patch di docs/test saat itu terjadi. Celah 0018 tertutup oleh rebuild
  2026-10-06 (0019–0023 → 0018–0022, isi sama); rujukan lama baru dianotasi sebagian (AURORA.md
  kontradiksi 21); renumbering 0024–0027 berikutnya masih diprediksi `patch_surface.py`
- [ ] M-5 Opsional, paling berat: lepas Paper sebagai upstream (vanilla → SourbyCraft langsung).
  Artinya ~974 patch Paper menjadi milik SourbyCraft, dan setiap rilis Minecraft serta perbaikan
  keamanan Paper harus di-port sendiri.
- Gate tiap tahap: build dari clean checkout reproducible, semua tes Java + Python lulus, boot test
  dan tes persistensi lulus, jumlah konflik rebase tercatat di `rebase-log.md`.

### Build dan reproduktibilitas
- [ ] Tes reproduktibilitas dari clean checkout
- [ ] Validasi boot cached/offline sebagai gate independensi
- [ ] Hapus dependensi build legacy hanya setelah ada bukti clean build
- [ ] Dokumentasikan repository remote yang dibutuhkan dan urutan fallback-nya

### Arsitektur patch
- [ ] Identifikasi optimasi yang duplikat dengan upstream
- [ ] Simpan implementasi di source milik Sourby bila memungkinkan; tambah tes regresi sebelum
  sebuah patch dihapus
- [ ] Runtime lifecycle authority, execution contract, scheduler contract, compatibility layer (ROADMAP)

### Startup
- [ ] Benchmark startup cold vs warm
- [ ] Caching output transform

### Spark
- [ ] Putuskan: adapter atau fork SourbySpark (hanya setelah kebutuhannya terbukti; review GPL jika fork)

### Toolchain privat (repo `YanIanZ/SourbyPatcher` dan `YanIanZ/SourbyClip`)
- [-] Update dependency di kedua repo: kandidat lokal Clip 3.1.1 dan Patcher 3.1.0 memakai
  Gradle 9.8.0 + JUnit 6.1.3. Tes/build Temurin 25 dan integrasi diagnostik dengan override lulus;
  push revisi privat dan pembaruan pin resmi masih terbuka (`docs/development/PRIVATE-TOOLCHAIN.md`)
- [x] Audit kepemilikan thread/executor di sumber kandidat SourbyClip 3.1.1 (`5b6249c`):
  worker download per batch ditutup sebelum filesystem/server, lookup dibatalkan/join dan
  HttpClient ditutup. `BootstrapWorkersTest`, `IPUtilTest`, `LauncherProcessTest` lulus;
  cakupan/batas audit di `docs/architecture/bootstrap-download-audit.md`; belum aktif di pin resmi

---

## P3 — Rendah (polish dan fitur tambahan)

- [x] UI/UX terminal dan command SourbyCraft/Aurora (2026-10-06): progress per tahap dengan
  durasi/status, panel bersama, `/aurora`, `/pl` identik `/plugins`, pencarian/filter/halaman,
  diagnosis loader JUL + Log4j, header dan contoh config beranotasi. Compile/suite server penuh,
  243 tes Python, identitas server/API, boot command dan reload terisolasi lulus;
  detail/batas bukti di `docs/AURORA-UX.md#local-validation-2026-10-06`.

- [ ] Mode HUD actionbar
- [!] Label `Aurora Engine` di Spark viewer (butuh viewer sendiri karena enum engine dirender oleh
  spark.lucko.me) dan desain presentasi viewer Aurora
- [x] API dunia AWF runtime (`AuroraWorlds` di sourbyapi, `/awf`, registry
  `sourbycraft_config/aurora-worlds.json`, autoload) — backend FILE; E2E lokal 12/12 (2026-10-06).
  Belum diuji: unload saat ada pemain, `unload nosave` dengan chunk kotor.
- [x] AWF template + instance copy-on-write, generator `void`/plugin/tipe dunia bernama
  (`/awf template …`, `/awf create <n> from <t>`) — E2E lokal 17/17 (2026-10-06). Belum diukur:
  hemat disk/memori per instance.
- [x] Konverter Slime v12/v13 → AWF + `/awf import` — E2E lokal 17/17 dengan 3 file pulau asli
  (2026-10-06). Belum dicek: entitas hasil impor muncul di game; format AdvancedSlimePaper lain.
- [x] Backend AWF `redis` (klien RESP sendiri, commit atomik Lua, lease satu penulis per dunia,
  WAITAOF) + file dunia `.awf` v2 (zstd per chunk, CRC32C, indeks di akhir) untuk export/import/
  convert dari `.slime` — tes integrasi Redis sungguhan (CI memasang redis-server) dan E2E lokal
  27/27 (2026-10-06). Belum: uji beban pemain, Redis lewat jaringan nyata.
- [x] API AWF request persisten/template-clone + koordinasi lifecycle multi-plugin;
  reservation sampai pekerjaan selesai, shared template readers, konflik fail-fast dan drain
  region saat save gagal; WorldRequestTest 3/3, WorldOperationGateTest 7/7, WorldSaveBarrierTest
  3/3; suite API/server tanpa kegagalan, 2026-10-06; SPEC §155 dan `docs/guides/developing-awf.md`.
- [-] Tutup temuan review port ASP `dev/26.2` → AWF (2026-10-06). Per 2026-10-07 (unit test saja):
  B77, B78, B79 FIXED; B76 sebagian (properti ter-mapping, PDC world belum); B80 dan B85 terbuka
  (`docs/architecture/aurora-world-fabric.md` *Robustness fixes 2026-10-07*). Temuan awal: metadata Slime/world PDC,
  pruning PDC/biome, fence commit sesudah discard, WorldProperties pada export/import,
  dan join I/O pada global tick. Review statis saja; fix serta pengujian regresi masih terbuka,
  testing oleh Claude. Bukti source dan skenario: `docs/development/ASP-26.2-AWF-PORT-REVIEW.md`;
  SPEC B76–B80. Hasil tes sebelumnya tidak menutup temuan ini.
- [ ] AWF Redis lewat jaringan: tiap baca chunk = satu round trip; prefetch/pipelining tetangga
- [ ] AWF FILE: tulis massal lambat (fsync per objek chunk; 3.364 chunk = 14,9 s) — batch fsync
- [ ] Backend database AWF MongoDB/MySQL
- [ ] Executor yang sudah ada dipindahkan ke bawah Resource Governor (ditunda; masing-masing sudah
  punya batas sendiri)
- [ ] Namespace config `aurora.performance`, `aurora.chunk`, `aurora.memory`, `aurora.ai`,
  `aurora.world` (hanya ditambahkan bila sudah ada key yang benar-benar dipakai)
