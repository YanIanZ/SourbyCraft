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

### Keputusan owner
- [!] **Lisensi:** repo memakai PolyForm Noncommercial, sementara jar yang didistribusikan berisi kode
  server Paper berlisensi GPLv3. Perlu keputusan apakah kombinasi ini memenuhi GPLv3
  (`docs/releases/26.2-build-47-aurora-nexus.md`). Ini juga menentukan bentuk migrasi penuh di P2.
- [x] **Definisi "migrasi 100% ke Aurora":** owner memilih "lepas Canvas + Weaver", Paper tetap
  upstream (2026-10-05). Lihat bagian *Migrasi penuh* di P2.

### Persistensi dan kepemilikan region
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
- [ ] **`level.dat` tidak disimpan oleh `save-all` maupun autosave** (warisan Folia/Canvas): game time
  dan perubahan spawn dunia mundur ke boot terakhir setelah crash. Tidak ada korupsi; berkas lain
  (chunk, entity, cuaca, gamerule, jam siang/malam) tersimpan. Bukti: `verify_crash.py` XFAIL.
  Perbaikan perlu menyimpan level data dari global tick tanpa melanggar kepemilikan region
- [ ] Perintah konsol kadang gagal dengan `NullPointerException` (`CommandSourceStack.getLevel()`
  null) dalam ~2 detik setelah `Done`, teramati saat boot ulang dunia AWF (direproduksi sekali,
  lalu tidak muncul di run berikutnya). Akar masalah belum ditemukan; harness sekarang menunggu
  perintah konsol pertama yang berhasil
- [-] Review konkurensi penulisan region-file (DEV-G), dimulai 2026-10-06. Diperbaiki: dua commit AWF
  bisa mendarat di store dalam urutan terbalik dari snapshot-nya, sehingga store menyimpan isi chunk
  lama (`AwfCommitOrderTest`). Sisa: `AwfRegionStorage.write` vs `close` (tulisan yang lolos cek
  `closed` sesaat sebelum close bisa hilang), flush bersamaan dari banyak region, dan dokumen review
- [ ] Bukti "tidak ada regresi kepemilikan region" dan "tidak ada regresi persistensi" (gate rilis)
- [-] AWF: crash di level server dengan klien sudah lulus (53/53, `verify_crash.py --awf`). Sisa:
  backend terputus (belum ada backend jaringan), beban berkelanjutan, dan investigasi
  commit awal 529 chunk yang butuh 105,5 detik di panel demo (`architecture/aurora-world-fabric.md`)

### Konkurensi
- [ ] Soak konkurensi multi-jam (2 jam+) (DEV-A, AURORA-C/J)
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
- [ ] Profil chunk holder lookup, ticket, region-file I/O, player chunk tracking, konstruksi packet chunk
- [ ] Profil encode/decode, kompresi, chunk-send, dan jumlah alokasi/copy packet
- [ ] Optimasi Aurora pertama yang terukur untuk masing-masing domain: entity, chunk, network

### Bridge plugin legacy
- [-] Rute entity-owner (belum diimplementasikan karena pemanggilan scheduler tidak menyebut entity)
- [-] Plugin legacy representatif dengan alur pemain dan soak

### Rilis
- [ ] Tetap `release=pre` sampai semua gate di atas lulus
- [-] Audit ulang klaim terukur di README/release notes sebelum tagging

---

## P2 — Menengah (independensi dan arsitektur)

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
- [ ] Nomor feature patch punya celah (0018) dan akan dinomori ulang pada rebuild berikutnya;
  perbarui rujukan nomor patch di docs/test saat itu terjadi
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
- [ ] AWF template + klon copy-on-write (dunia instance berbagi chunk template sampai diubah)
- [ ] Konverter Slime SRF v13 → AWF + `/awf import`
- [ ] Backend database AWF (MongoDB/MySQL/Redis)
- [ ] Executor yang sudah ada dipindahkan ke bawah Resource Governor (ditunda; masing-masing sudah
  punya batas sendiri)
- [ ] Namespace config `aurora.performance`, `aurora.chunk`, `aurora.memory`, `aurora.ai`,
  `aurora.world` (hanya ditambahkan bila sudah ada key yang benar-benar dipakai)
