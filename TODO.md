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
- [!] **Definisi "migrasi 100% ke Aurora":** pilih target (lihat bagian *Migrasi penuh* di P2)
  sebelum pekerjaan migrasi dimulai.

### Persistensi dan kepemilikan region
- [ ] Tes persistensi restart penuh setelah perubahan performa (DEV-A, AURORA-J)
- [ ] Tes restart/crash untuk setiap perubahan save async (DEV-G)
- [ ] Review konkurensi penulisan region-file (DEV-G)
- [ ] Bukti "tidak ada regresi kepemilikan region" dan "tidak ada regresi persistensi" (gate rilis)
- [-] AWF: crash di level server dengan pemain, backend terputus, beban berkelanjutan, dan investigasi
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
  SourbyClip itu sendiri (bootstrap repo sudah diaudit, SourbyClip belum) (DEV-L, AURORA-I)
- [-] Kualifikasi cold-download dari remote (cold bootstrap pernah gagal cek hash Mojang dengan exit 0)

---

## P1 — Tinggi (bukti performa dan kualifikasi rilis)

### Pengujian panel (panel.parama.cloud)
- [ ] Jalankan build terbaru di panel setelah akses diberikan: boot, plugin legacy lewat bridge,
  alur pemain nyata (Vault/EssentialsX sudah enable dengan 0 pelanggaran bridge pada `9a15f51`)

### Baseline tersertifikasi (DEV-B, AURORA-C, ROADMAP M8)
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
Saat ini build berjalan: vanilla → Paper (974 file patch, ~142 ribu baris) → Canvas (162 file
patch, ~52 ribu baris) → SourbyCraft (34 patch), lewat Weaver. Batasan yang tidak bisa dihindari:

- Source Minecraft hasil decompile tidak boleh di-commit (EULA Mojang), jadi pipeline
  "decompile + apply patch" tetap dibutuhkan, dengan SourbyPatcher sebagai pemiliknya.
- Package API yang dipakai plugin (`org.bukkit.*`, `io.papermc.paper.*`, termasuk scheduler Folia
  `io.papermc.paper.threadedregions.scheduler`) harus tetap sama agar plugin Paper/Folia tetap jalan.
- Kewajiban atribusi dan lisensi upstream tetap berlaku (lihat keputusan lisensi di P0).

Tahapan yang diusulkan, dari yang paling kecil risikonya:
- [ ] M-0: owner memilih target (lihat P0)
- [-] M-1 Identitas: nama publik SourbyCraft/Aurora sudah ada; sisa: laporan crash memakai
  BuildInfo, profiler identity
- [ ] M-2 Isolasi (T9 di `AURORA-FULL-TRANSITION.md`): pindahkan body service Sourby keluar dari
  class upstream; pecah patch campuran 0006/0013 dan pindahkan class dari 0002/0005/0016; isolasi
  bridge Spark Canvas; dokumentasikan setiap dependensi keras Canvas di `independence.md`
- [ ] M-3 Lepas Canvas sebagai upstream: serap patch Canvas yang dibutuhkan ke patch set
  SourbyCraft, lalu ambil Paper langsung. Rename internal `io.canvasmc.*` yang bukan API plugin.
  Mulai saat ini setiap update Canvas/Folia harus di-port sendiri.
- [ ] M-4 Lepas Weaver: SourbyPatcher menjalankan pipeline sendiri (Weaver tidak lagi dibutuhkan)
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
- [ ] Update dependency di kedua repo (Patcher: JUnit 6.0.3, Gradle 9.4.1; Clip: Gradle 9.5.1),
  lalu perbarui pin di SourbyCraft sesuai `AGENTS.md` masing-masing repo
- [ ] Audit kepemilikan thread/executor di SourbyClip

---

## P3 — Rendah (polish dan fitur tambahan)

- [ ] Mode HUD actionbar
- [!] Label `Aurora Engine` di Spark viewer (butuh viewer sendiri karena enum engine dirender oleh
  spark.lucko.me) dan desain presentasi viewer Aurora
- [ ] Backend database AWF (MongoDB/MySQL/Redis)
- [ ] Adapter SlimeLoader (butuh converter format Slime)
- [ ] Executor yang sudah ada dipindahkan ke bawah Resource Governor (ditunda; masing-masing sudah
  punya batas sendiri)
- [ ] Namespace config `aurora.performance`, `aurora.chunk`, `aurora.memory`, `aurora.ai`,
  `aurora.world` (hanya ditambahkan bila sudah ada key yang benar-benar dipakai)
