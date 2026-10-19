# SourbyCraft 26.2 — To-do List Proyek

Satu daftar terurut berdasarkan urgensi, dikumpulkan dari `docs/DEVELOPMENT-TASKS.md`,
`docs/AURORA-TASKS.md`, `docs/AURORA-ROADMAP.md`, `docs/AURORA-FULL-TRANSITION.md` dan
`docs/architecture/independence.md` (per 2026-10-05, branch `26.2`).

Dokumen sumber tetap menjadi rincian dan bukti per tugas. Ketika satu tugas selesai, centang di
sini **dan** di dokumen sumbernya, sertakan bukti (run CI, commit, laporan) sesuai
`DEVELOPMENT.md#development-truthfulness-and-non-misleading-policy`.

Plan lama (era v6/v12/Folia, Mei–Juli 2026, dulu di `docs/superpowers/`, dihapus 2026-10-13) tidak dimasukkan; isinya
sudah digantikan dokumen di atas.

Status: `[ ]` belum · `[-]` sebagian / perlu validasi · `[!]` terblokir keputusan owner

---

## P0 — Mendesak (memblokir rilis stabil, legal, atau keamanan data)

### Rilis Build 47 — gate yang masih terbuka (audit 2026-10-07)
Hal yang memblokir `release=true`. Status per gate dan buktinya:
`docs/releases/26.2-build-47-aurora-nexus.md` *Release readiness (audited 2026-10-07)*.
CI hijau terbaru: run 38445246566 pada `d97fe247` (2026-10-14). Tree kandidat rilis (Paper
0005–0014, Minecraft 0023–0029, perbaikan bridge/AWF) sudah di-commit (`3da221d0`, `f188fe78`,
`e0c241e9`, `d97fe247`) dan jalan di CI.
- [!] Keputusan lisensi owner (PolyForm Noncommercial vs GPLv3 Paper; lihat *Keputusan owner*).
- [x] Commit tree kandidat rilis, lalu satu run CI hijau: compile, suite Java + Python, boot,
  shutdown, termasuk marker fixture yang belum pernah jalan (`LEGACY_GLOBAL_*`,
  `LEGACY_REGION_TASK_OK`, `LEGACY_ENTITY_TASK_OK`, `LEGACY_ENTITY_RETIRED_OK`,
  `LEGACY_DISABLE_*`) dan langkah baru `patch_surface.py --check`. Boot CI ini juga yang pertama
  dengan feature patch auto-provision plugin sebelum plugin scan (saat itu 0025, kini 0024).
  SELESAI: push `beb7f40e` 2026-10-07, CI run 37515867230 success 33/33 langkah, 20 jenis marker OK.
- [x] Regenerasi patch bersih: urutan commit materialized = urutan file (0024/0025 ↔ 0026/0027
  menurut `patch_surface.py --check-rebuild`, 2026-10-07), `rebuild*Patches` tanpa diff isi,
  8 file feature patch yang masih untracked di-commit. SELESAI 2026-10-07: reorder tanpa diff isi,
  rebuild tanpa perubahan konten, `applyAllPatches` dari file lulus lokal dan di CI 37515867230.
- [!] Patch EDF (`AuroraEdfScheduler`, file `minecraft-patches/parked/0028-…`) DITAHAN dan diparkir
  (keputusan owner 2026-10-07): rilis tanpa patch EDF kecuali owner memutuskan lain.
- [ ] Pasangan referensi tersertifikasi (`idle` + `chunk-stress`) di mesin tenang; tanpa itu gate
  regresi >3% terblokir (`docs/architecture/qualification-readiness.md` §1, §6).
- [ ] Soak multi-jam di tree kandidat rilis dengan set plugin `plugins-10`; soak tersertifikasi
  2026-09-15 (commit `1c883a55`) sebelum Build 47 dan tidak berlaku.
- [x] Bug kepemilikan region yang diketahui: NPE `/say` di global tick dan NPE perintah konsol
  setelah `Done`. Direproduksi dan diperbaiki 2026-10-13 (feature patch MC 0028
  `rebind-early-console-command-sources`, lihat item NPE konsol di bawah). SELESAI: CI run 38427215968 (head `8c70b53c`, run as `4275912f` before a message-only rewrite, 2026-10-13) success,
  probe konsol awal `SOURBY_EARLY_CONSOLE_PROBE` di langkah boot lulus.
- [x] Persistensi dan crash diulang di tree kandidat rilis (`verify_persistence*.py`,
  `verify_crash.py`); XFAIL metadata game time/spawn diperbaiki atau diterima owner.
  SELESAI 2026-10-13 (run lokal di laptop, bukan server deployment; Apple M2 `Mac14,2`, 8 core,
  16 GiB, OpenJDK 25.0.2; mesin tidak tenang, agen lain aktif): jar slim `975d3bda…` (40.681.193 B,
  tree head `cbef3fb9`; identitas di jar `26.2-DEV-26.2@1d9817a` = HEAD saat build sebelum commit AWF
  `e0c241e9`, kode AWF itu sudah ada di jar). `verify_persistence.py` (3 restart, 6 klien beban saat
  stop) **105/105** dalam 142 s; `verify_crash.py` region file **52/52** dalam 97 s; `verify_crash.py
  --awf` **56/56** dalam 101 s (tiga SIGKILL 3/8/15 s dengan 4 klien bergerak, tanpa baris korupsi,
  checkpoint utuh). Game time kini XPASS di ketiga pemulihan (mis. 500 ≥ 290, 1247 ≥ 830), jadi XFAIL
  diperbaiki oleh MC 0027; entri `KNOWN_LIMITATIONS` di `verify_crash.py` sudah dihapus di `3cd33b3f`
  (game time mundur kini menggagalkan run).
- [-] Redis terputus / lease hilang pada server yang berjalan (backend failure). Run pertama 2026-10-14
  (jar `rc-cbef3fb9`) menemukan D1–D6; perbaikan di-commit `d97fe247`, CI run 38445246566 hijau
  (suite + boot; skenario gangguan Redis sendiri tidak dijalankan di CI), run
  ulang lokal 2026-10-14 (jar final `d55469a0…`, Redis 8.10.2 loopback AOF, M2 16 GiB; detail di
  `docs/architecture/aurora-world-fabric.md` § *Backend failure*, SPEC B89–B93). Semua skenario
  sesuai desain: (D1) baca gagal dicoba ulang lalu posisi di-fence — chunk hasil regenerasi tidak
  pernah menimpa chunk tersimpan (AOF + marker tetap ada setelah reload dan reboot); (D2) token
  penulis terakhir (`:writer`) — tidak ada AWFLEASE setelah freeze/resume/kill/restart; (D3) lease
  tetap diperpanjang setelah diambil kembali; (D5) gagal lepas lease tidak dilaporkan sebagai chunk
  hilang; (D7, baru) tulis socket Redis tanpa timeout membuat `stop` menggantung >10 menit saat Redis
  beku — kini watchdog, `stop` 115 s. Masih terbuka (bukan defek data): D6 tanpa batas waktu total
  shutdown AWF, D4 hanya WARN; belum ada run Redis lewat jaringan, dan skenario gangguan hanya
  dijalankan lokal. Bukti lokal laptop, bukan kualifikasi; tetap `[-]` selama D4/D6 terbuka.
- [ ] Alur pemain nyata dengan plugin bridged (EssentialsX/Vault) di Sourby Demo.

### Audit sistem 2026-10-14
**Lanjutkan dari sini:** [`docs/development/HANDOFF-2026-10-14.md`](docs/development/HANDOFF-2026-10-14.md) —
status per gelombang. Gelombang AWF sudah di-push (`ebb9c1fb`: AWF-1..6, 8..10). Gelombang engine/bridge
sudah di-push (`1c57a736`, 2026-10-20: ENG-1..7, 9 (ledger saja), 10, 12..14; ENG-11 didokumentasikan; ENG-8 tetap
roadmap); gelombang security dan observability belum dimulai.

Laporan: [`docs/audits/2026-10-14-system-audit.md`](docs/audits/2026-10-14-system-audit.md)
(read-only, `d97fe247`; nomor baris di laporan per commit itu). Temuan HIGH di sini; MEDIUM di P1,
LOW di P2, proposal di P3. Keputusan owner 2026-10-14: SEC-1 bangun ulang guard; SEC-2/3 updater
aman + rilis bertanda tangan; SEC-5 hapus geo-IP SourbyClip; SEC-13 (lisensi) tetap terbuka
(*Keputusan owner*). Status `[ ]` = belum diperbaiki.
- [ ] **AWF-1** Nama dunia managed dicocokkan ke setiap elemen path (dunia bernama seperti direktori
  induk server menempelkan semua dunia ke AWF) — cocokkan path relatif ke direktori server (`storageId()`) (audit §1)
- [ ] **AWF-2** `TicketHolder.pop()` membaca di luar monitor dan race dengan `propagate()`: ticket
  save hilang, `save-all`/`AuroraWorlds.save` tidak pernah selesai — pop atomik, gabungkan ticket kedua (audit §1)
- [ ] **AWF-3** Commit periodik AWF menumpang collector metrik dan terlewat saat koleksi telat, janji
  "hilang maksimal satu interval" tidak berlaku di bawah beban — driver maintenance AWF sendiri (audit §1)
- [x] **ENG-1** `guard-severity: LOG` (default) membiarkan akses lintas region; pelanggaran plugin
  bridged tidak pernah sampai ke quarantine — sink guard yang mengatribusi ke plugin + rate limit log (audit §2)
  — selesai `1c57a736` (2026-10-20): `GuardViolations` + `BridgeRuntime` (satu pelanggaran per run body, log per (plugin, alasan)/60 s); unit test, belum ada fixture boot
- [x] **ENG-2** Async pathfinding mengganti `nodeEvaluator` hidup milik mob dan kehilangan flag
  pintu/float (fitur default off) — simpan/pulihkan evaluator, salin flag (amend MC 0006) (audit §2)
  — selesai `1c57a736` (2026-10-20): `PathNavigation.sourbyIsolatedPathFinder` (amend MC 0006), `AsyncPathEvaluatorIsolationTest`
- [x] **ENG-3** Task region/entity yang antre di dunia yang di-unload tidak pernah dijalankan,
  dibatalkan atau dipensiunkan — kuras antrean (cancel-only) dan pensiunkan scheduler saat unload (audit §2)
  — selesai `1c57a736` (2026-10-20): MC 0033 + `WorldUnloadDrain`; gate boot lokal `awf create/unload t1` bersih (tidak ada task antre saat itu, jalur kuras hanya teruji unit)
- [x] **ENG-4** Task batal yang antre di section ter-unload bisa membuat split melempar di bawah
  write lock regioniser dan menghentikan server (PLAUSIBLE) — lewati task batal saat split (audit §2)
  — selesai `1c57a736` (2026-10-20): MC 0032, `RegionTaskQueueDrainTest`
- [ ] **SEC-1** `sourbycraft-security.yml` tanpa consumer sejak `6b774810`: tidak ada guard
  packet/NBT/book/sign/payload, padahal docs bilang ditegakkan — **keputusan owner: bangun ulang**
  sebagai `dev.iyanz.sourbycraft.security` + satu feature patch, mode LOG dulu, tes per limit, cek
  policy bahwa setiap key punya consumer (audit §3)
- [ ] **SEC-2** Auto-updater hanya mempercayai respons API GitHub (tanpa tanda tangan), default
  `apply_mode=auto` — **keputusan owner:** rilis bertanda tangan (Ed25519, public key di jar), default `notify` (audit §3)
- [ ] **SEC-3** Swap in-place tidak menyimpan jar sebelumnya dan otomatis melintasi versi Minecraft —
  **keputusan owner:** simpan `.prev` + rollback setelah boot gagal, gate versi MC yang sama (audit §3)
- [ ] **SEC-4** `SourbyBootstrap` kode mati (entry point = SourbyClip); grep guard download offline
  di CI tidak mungkin cocok — hapus atau sambungkan, tolak staging jar dengan versi clip berbeda, perbaiki grep CI dan docs (audit §3)
- [ ] **OBS-1** Password Redis di `aurora.awf.redis.uri` bocor ke report spark — sembunyikan key,
  scrub userinfo URI di semua tempat (audit §4)
- [ ] **OBS-2** `/speedtest` yang menggantung mengunci command selamanya dan membocorkan proses —
  baca output paralel, tunggu dengan timeout (audit §4)
- [ ] **OBS-3** Identitas build tidak terikat ke tree (tanpa flag dirty/tree hash; CI tidak cek
  `Git-Commit`) — `-dirty`, tree hash, cek `--commit $GITHUB_SHA` (audit §4)

### Keputusan owner
- [!] **Lisensi:** repo memakai PolyForm Noncommercial, sementara jar yang didistribusikan berisi kode
  server Paper berlisensi GPLv3. Perlu keputusan apakah kombinasi ini memenuhi GPLv3
  (`docs/releases/26.2-build-47-aurora-nexus.md`). Ini juga menentukan bentuk migrasi penuh di P2.
  Audit 2026-10-14 **SEC-13**: jar tidak memuat teks GPL maupun tawaran source; label lisensi
  Docker (SEC-12) menunggu keputusan ini.
- [x] **Integrasi anticheat privat dihapus** (keputusan owner 2026-10-13): feature patch, tooling
  dan docs-nya dihapus dari repo; feature patch MC sesudahnya dinomori ulang turun satu
  (0025–0029 → 0024–0028).
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
- [!] **Scheduler region EDF baru (patch EDF yang diparkir, `AuroraEdfScheduler`, turunan LeafPile) DITAHAN** (keputusan
  owner 2026-10-07) sampai lulus `AuroraEdfSchedulerTest`, tes independence policy, dan boot. Patch
  dan tesnya diparkir di `sourbycraft-server/minecraft-patches/parked/`; commit materialisasinya dilepas.
  Catatan: jar demo 2026-10-07 00:00–00:36 dan bench `plugins-10` sempat berjalan di atasnya.
- [-] Matikan watchdog diagnosis region/Folia secara default (permintaan pengguna,
  2026-10-06). Feature patch 0025 (dulu 0026) ditulis dan diterapkan ke source materialized;
  JVM `sourbycraft.region-watchdog.enabled` default false, RESTART_REQUIRED. Ownership
  guard serta penanganan failure/shutdown tetap aktif. Patch di-commit `49d4e640` dan sudah
  di-build serta di-boot (watchdog mati) di setiap run CI sejak 37515867230. Sisa: penerimaan
  khusus watchdog (`docs/development/REGION-WATCHDOG.md` kasus 1–4, SPEC §158): bukti tidak ada
  `Folia Watchdog Thread` saat flag mati, run dengan flag nyala dan tick macet terkendali, dan
  akses wrong-owner terkendali.
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
  korupsi. Satu keterbatasan diketahui (lihat item berikut). Run terbaru tree kandidat (lokal
  2026-10-13): region file 52/52, AWF 56/56, game time XPASS (lihat gate P0 di atas)
- [x] **Metadata game time/spawn dunia belum masuk save-all/autosave** (DIPERBAIKI 2026-10-07, commit
  `44593a50`; demo: game time 1.792.900 ≥ 1.792.548 setelah `save-all flush` → SIGKILL → boot): bukti crash historis
  `verify_crash.py` XFAIL. Review source 26.2 (2026-10-06): metadata aktif ada di
  `PaperLevelOverrides` (`paper:level_overrides`), yang tidak masuk whitelist
  `RegionizedServer.autosaveSafeWorldData`; judul lama yang hanya menyalahkan `level.dat`
  tidak menggambarkan jalur load sekarang. Jangan memanggil bulk save SavedData dari global
  tick karena sebagian data dimiliki region.
  Diperbaiki 2026-10-07 (feature patch MC 0028, kini 0027, commit `44593a50`): whitelist itu kini
  menambah `WorldMetadataAutosave.GLOBAL_OWNED_TYPES` (hanya `PaperLevelOverrides`) untuk
  autosave global tick dan `save-all`; encode di global tick, tulis di
  `DIMENSION_DATA_IO_POOL`; SavedData milik region tetap di luar. Bukti: `WorldMetadataAutosaveTest`
  2/2; slim jar lokal (flat, tanpa pemain): `save-all flush` di game time 651, SIGKILL di 942,
  restart 681; autosave saja (span 20 s): SIGKILL di 1709, restart 1479; stop bersih exit 0, tanpa
  baris korupsi; `verify_crash_panel.py` di demo 2026-10-07: game time PASS, 64/64 blok, entitas utuh.
  Sisa: run dengan pemain nyata, world border,
  world PDC dan `level.dat` belum masuk. Detail: `docs/architecture/engine-storage.md` §6.
- [x] AWF create masih menunggu generasi spawn pada global tick: report Spark pengguna
  `https://spark.lucko.me/RyP3FlAYME` mencatat 4.650 ms sampel inclusive pada
  `/awf create` → `setInitialSpawn` → `syncLoadNonFull`; 4.570 ms di `parkNanos`.
  Ini akumulasi sampel, bukan bukti satu pause 4,65 s. Tidak membuktikan semua stutter eksplorasi.
  DIPERBAIKI 2026-10-13 (commit `e0c241e9`, CI run 38434258632 hijau pada `cbef3fb9` yang
  memuatnya): pembuatan dunia bertahap
  (MC 0029, Paper 0014, `AuroraWorldsService`; SPEC B80/B85): chunk pertama pencarian spawn
  dimuat async ke SPAWN, lalu `setInitialSpawn`/`prepareLevel` yang tidak diubah berjalan di
  global tick berikutnya; `Bukkit.createWorld` tetap sinkron. Lokal (M2 8 core, satu run per
  spesifikasi, tanpa pemain, tidak tersertifikasi): jeda maksimum global tick saat
  `/awf create` normal 11/22/33 3,29/2,30/2,19 s → 0,10/0,14/0,06 s, nether 0,73 → 0,06 s,
  template 1,98 → 0,10 s, clone 1,98 → 0,06 s; spawn identik dengan jar lama (10 dunia).
  Sisa: kandidat spiral tambahan masih sinkron; jalur gagal-setelah-konstruksi dan generator
  plugin belum dijalankan di server; future `AuroraWorlds` kini bisa selesai di tick berikutnya
  (SSB-AuroraWorlds `world()` perlu dicek bila dipanggil di global tick).
  Bukti/lingkup: `docs/development/AURORA-EDF-UPGRADE.md`, `docs/architecture/aurora-world-fabric.md`.
- [x] Perintah konsol kadang gagal dengan `NullPointerException` (`CommandSourceStack.getLevel()`
  null) dalam ~2 detik setelah `Done`, teramati saat boot ulang dunia AWF. DIPERBAIKI 2026-10-13
  (feature patch MC 0028 `rebind-early-console-command-sources`; 0029 sebelum renumbering hari yang
  sama). Akar masalah: thread konsol
  membuat `CommandSourceStack` saat membaca baris (`PaperConsole.runCommand` →
  `createCommandSourceStack()` → `overworld()`); baris yang terbaca sebelum overworld terdaftar
  menyimpan level null, menunggu di antrean, lalu NPE di global tick pertama setelah `Done`.
  `DedicatedServer.handleConsoleInputs` kini membuat ulang source bila levelnya null. Bukti lokal
  (slim jar, flat, `/say` tiap 0,25 s selama boot): jar 2026-10-07 97/109 NPE dengan stack yang sama
  persis dengan sighting `/say` (`Commands.java:461` ← `RegionizedServer.globalTick:278`); jar dengan perbaikan
  ini 0/37 NPE, 37/37 dieksekusi di tick pertama setelah `Done`, stop bersih exit 0. Sighting `/say` saat
  reload di demo cocok dengan stack ini; waktunya tidak bisa dipastikan ulang dari log.
- [-] Review konkurensi penulisan region-file (DEV-G), dimulai 2026-10-06. Diperbaiki: dua commit AWF
  bisa mendarat di store dalam urutan terbalik dari snapshot-nya, sehingga store menyimpan isi chunk
  lama (`AwfCommitOrderTest`); `AwfRegionStorage.write` vs `close` kini saling eksklusif
  (`AwfLifecycleFenceTest.aWriteAdmittedJustBeforeCloseIsCommittedByIt`, unit test 2026-10-07, belum
  boot). Sisa: flush bersamaan dari banyak region, dan dokumen review
- [-] Bukti "tidak ada regresi kepemilikan region" dan "tidak ada regresi persistensi" (gate rilis):
  persistensi tree kandidat 20/20 di demo 2026-10-07 (`verify_persistence_panel.py`, 3 restart);
  kepemilikan region: fixture bridge 20 marker OK di CI 37515867230, soak 100 menit tanpa pelanggaran.
  Crash: `verify_crash_panel.py` 14/15 di demo 2026-10-07 (2 SIGKILL, data chunk/entitas utuh; game time
  mundur = keterbatasan P0 yang sudah tercatat). Tree kandidat `cbef3fb9` lokal 2026-10-13 (laptop,
  jar `975d3bda…`): persistensi 105/105, crash region 52/52, crash AWF 56/56, game time tidak lagi
  mundur (XPASS). Dua NPE konsol diperbaiki (patch MC 0028, CI run 38427215968 (head `8c70b53c`, run as `4275912f` before a message-only rewrite, 2026-10-13) success)
- [-] AWF: crash di level server dengan klien sudah lulus (terbaru lokal 56/56, `verify_crash.py
  --awf`, 2026-10-13; sebelumnya 53/53, 2026-10-05). Sisa: beban berkelanjutan dan investigasi
  commit awal 529 chunk yang butuh 105,5 detik di panel demo (`docs/architecture/aurora-world-fabric.md`);
  backend terputus dilacak di item Redis P0 di atas (tidak diulang di sini)

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
- [ ] Pertahankan limit packet/security (`sourbycraft-security.yml`) di setiap perubahan network.
  Catatan audit 2026-10-14: file itu tidak punya consumer sejak `6b774810`, jadi saat ini tidak ada
  limit yang ditegakkan — lihat **SEC-1** di bawah

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
- [x] Waktu onLoad/onEnable per plugin (Paper feature 0013, `PluginTimings`, `PluginTimingsTest`):
  di-commit `f188fe78`/`49d4e640` 2026-10-07, ikut CI sejak 37515867230
- [-] Waktu eksekusi task bridge per plugin (count, total, max, jendela terbaru) di `/plugins <name>`
  dan `/perf plugins`, metrik penghubung kompatibilitas dan performa
  (`compat-vs-performance.md` §6 butir 2). Selesai di kode, 48 tes bridge lulus, terlihat di demo
  (`/perf plugins` menampilkan body total/p99 per plugin bridged, 2026-10-06). Di-commit `f188fe78`
  (2026-10-07; CI hijau sejak 37515867230); dipakai di run `plugins-10` (Essentials 84,8 ms total,
  Vault 7,69 ms). Sisa: validasi dengan alur pemain nyata

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
- [ ] View antrean region scheduler (DIRENCANAKAN; `/perf scheduler` tidak ada — view yang ada di
  `PerfCommand.VIEWS`: `lanes` beban CPU per lane, `governor` antrean lane governed, `async`
  pool path; antrean milik region scheduler sendiri belum ditampilkan di mana pun)
- [ ] Verifikasi config SourbyCraft/Aurora tampil benar di Spark web viewer
- [ ] Klasifikasi thread region yang lebih baik di Spark
- [x] Telemetri histori: `/perf history` (`PerformanceHistory`, ring 60 menit, satu sampel per menit:
  TPS/MSPT region terburuk, CPU proses, heap; `PerformanceHistoryTest`)
- [ ] Telemetri sisa: latensi tunggu task, counter siklus hidup chunk, counter tick entity, log
  insiden (kejadian, bukan sampel per menit)
- [-] Latensi save region-file (saat ini hanya ada latensi commit AWF)

### Optimasi NMS terukur (hanya setelah baseline ada)
- [ ] Profil Entity/LivingEntity/Mob tick, GoalSelector, Brain/Sensor, navigasi, merge/pickup item
- [-] Stutter generasi area baru (laporan pengguna 2026-10-06): source pengukuran opsional
  antrean/eksekusi per tahap generic generation di `/perf chunks`, feature patch 0026 (dulu 0027).
  RESTART_REQUIRED: `-Dsourbycraft.chunk-generation-metrics.enabled=true` ATAU TOML
  `aurora.diagnostics.chunk-generation-metrics = true` (2026-10-07, untuk panel yang tidak bisa
  mengirim flag -D; `ChunkGenerationMetricsEarlyConfigTest` 7/7); default off. Patch itu sudah
  di-build dan boot di demo dengan metrik mati. Sisa: run dengan metrik nyala di demo, profil
  worker/region + CPU/GC, lalu A/B kandidat; akar stutter dan keuntungan belum terbukti.
  Pengukuran delivery chunk 2026-10-06 (worker 2 vs 6) ada di `chunk-workers.md`.
  Lingkup/sisa: `docs/development/CHUNK-GENERATION-STUTTER.md`.
- [!] EDF LeafPile/Spottedleaf (DITAHAN 2026-10-07, patch EDF diparkir; lihat P0): source backend turunan `AuroraEdfScheduler` memperbaiki
  cancel saat tick berjalan, link replacement, rearm deadline, pelepasan blocker task batal,
  publikasi state, dan admission setelah halt. Patch EDF itu memasang backend pada opsi EDF;
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
  Marker fixture `LEGACY_GLOBAL_*` lulus di CI sejak run 37515867230. Rincian dan kasus penerimaan
  di `docs/development/LIGHTINGLUMINOL-BRIDGE-PORT.md`.
- [-] Upgrade kapasitas bridge per plugin: limit pending/running async LIVE (default 0),
  worker Bukkit + status body yang masih draining setelah cancel, dan statistik `/plugins`.
  Source + feature patch 0008 ditulis dan materialized; tes batas `BridgeReviewTest` lulus
  (2026-10-07, unit saja); disable lalu reload kini bisa menjadwalkan lagi (B86). Enable ulang
  instance yang sama tanpa reload kini dibuka lagi oleh hook di Paper feature 0012
  (`reopen-bridge-admission-on-enable`, di-commit `49d4e640`, ikut boot CI sejak 37515867230).
  Sisa: bukti runtime untuk enable ulang instance yang sama (belum ada marker/boot yang
  menjalankannya).
- [x] Indeks task per plugin + disable/admission/rejection cleanup; regresi 100 plugin simulasi,
  overlap async dan race scheduler; BridgeRuntimeTest 24/24, suite server 10.147 tes tanpa
  kegagalan (23 skipped), 2026-10-06; SPEC §155 dan `docs/architecture/aurora-plugin-bridge.md`.
- [x] Rute entity-owner lewat `EntityTask` (sourbyapi) + Paper 0011: IMPLEMENTED, 6 tes
  `BridgeTargetRoutingTest` v39 lulus (2026-10-07), SPEC V39; callback tanpa `EntityTask` tetap tidak
  dirutekan ke entity. Marker CI `LEGACY_ENTITY_*` lulus sejak run 37515867230
- [-] Plugin legacy representatif dengan alur pemain dan soak

### Rilis
- [ ] Tetap `release=pre` sampai semua gate di atas lulus
- [-] Audit ulang klaim terukur di README/release notes sebelum tagging: audit 2026-10-07 dilakukan
  (release doc, README, SPEC §161); ulangi pada commit yang akan di-tag

### Audit sistem 2026-10-14 — MEDIUM
Laporan dan konteks: blok *Audit sistem 2026-10-14* di P0.
- [ ] **AWF-4** Nama template tidak divalidasi di from-template/delete/read: path traversal termasuk
  delete rekursif — validasi nama, batasi resolusi ke root template (audit §1)
- [ ] **AWF-5** Lease Redis bocor lewat `unprepare` dan jalur "already open", lalu error palsu
  "lost its Redis lease" — tutup store di setiap removal/throw (audit §1)
- [ ] **AWF-6** Perpanjangan lease serial: stores × `timeout-ms` bisa melebihi lease pada Redis
  lambat (PLAUSIBLE) — pipeline renewal, WARN bila satu pass > period/2 (audit §1)
- [ ] **AWF-7** (roadmap) Biaya commit FILE O(dunia): indeks penuh ditulis ulang, di-hash dan
  di-fsync per commit; satu fsync per objek — generasi delta, fsync batch (audit §1; lihat item
  "AWF FILE: tulis massal lambat" di P3)
- [x] **ENG-5** Ticket save dilaporkan selesai saat region merge/split di tengah save; bagiannya
  tanpa ticket (flush AWF melewatkan chunk) — teruskan ticket saat merge, sebar saat split (audit §2)
  — selesai `1c57a736` (2026-10-20): MC 0031, `SaveAllTicket.split`/`TicketHolder.forwardTo`, `SaveTicketRegionChangeTest`
- [x] **ENG-6** Satu lane `BRIDGE_IO` penuh membatalkan timer async berulang milik plugin lain
  selamanya — lewati, jangan batalkan, task berulang (audit §2)
  — selesai `1c57a736` (2026-10-20): `BridgeRuntime.rejectedIo` lewati run task berulang (`skippedRuns`), `BridgeRuntimeTest`
- [x] **ENG-7** Penolakan path async memakai slot recompute tanpa fallback sinkron — kembalikan
  null saat ditolak supaya vanilla jalan (audit §2)
  — selesai `1c57a736` (2026-10-20): `AsyncPathProcessor.trySubmit` null → recompute sinkron vanilla, `AsyncPathRefusalTest`
- [ ] **ENG-8** Worker path membaca state mob hidup di luar owner — snapshot immutable yang diambil
  di owner (audit §2; terkait audit snapshot M5 di P0 *Konkurensi*)
- [x] **ENG-9** Oversubscription CPU secara default (thread region = semua prosesor ditambah pool
  chunk, async, Netty, path, bridge) — ledger thread saat boot; ubah default hanya dengan bukti A/B (audit §2)
  — ledger saja, selesai `1c57a736` (2026-10-20): `ThreadLedger` di log boot dan `/perf governor` (boot lokal: 22 thread / 8 prosesor, 2.8x); default TIDAK diubah
- [x] **ENG-10** Future `propagateSaveAllTicket` diselesaikan di bawah write lock regioniser;
  dependent sinkron yang masuk lagi bisa deadlock (PLAUSIBLE) — selesaikan secara async (audit §2)
  — selesai `1c57a736` (2026-10-20): `SaveAllTicket.completing` menyelesaikan future di luar thread pemanggil (MC 0031)
- [ ] **SEC-5** SourbyClip memanggil tiga layanan geo-IP setiap boot, dua lewat HTTP biasa —
  **keputusan owner: hapus lookup** di repo SourbyClip (audit §3)
- [ ] **SEC-6** `ViaAutoUpdate` menimpa pin Via, bisa memasang versi yang tidak mencantumkan MC
  yang berjalan, mempercayai hash dari API, menghapus jar operator — hanya versi yang mencantumkan
  versi MC, jangan hapus jar yang bukan milik kita, batasi ukuran (audit §3)
- [ ] **SEC-7** Pin ProtocolLib menunjuk asset `dev-build` yang bisa berubah, belum pernah di-boot —
  pin rilis bertag, probe CI terjadwal (audit §3)
- [ ] **SEC-8** CI: action di-pin per tag bukan SHA; `contents: write` untuk seluruh job — pin
  per SHA, persempit permission (audit §3)
- [ ] **OBS-4** Maintenance AWF di thread metrik; kegagalan setelah publish menurunkan snapshot
  segar jadi STALE — perbaikan sama dengan AWF-3, try/catch terpisah (audit §4)
- [ ] **OBS-5** Ring sampel mentah 384 merusak jendela 10/15 s di atas 25,6 Hz atau saat sprint —
  ukuran dari tick rate (audit §4)
- [ ] **OBS-6** Generasi telemetri region hanya disapu collector; tak terbatas bila metrik gagal
  start — sapuan teramortisasi, batas (audit §4)
- [ ] **OBS-7** Policy script melewatkan patch Paper dan package engine; root yang hilang dilewati
  diam-diam — tuple root termasuk fitur Paper dan engine; assert root ada (audit §4)
- [ ] **OBS-8** Balasan command async melewati `SourbyReply` (command block mendapat exception) —
  lewatkan via `SourbyReply.run` (audit §4)
- [ ] **OBS-9** Gate CI yang lulus tanpa banyak membuktikan: CI PR tidak pernah jalan, grep smoke
  cocok dengan header panel apa pun, marker metrik hanya cek non-null, tes zstd skip diam-diam —
  perbaiki trigger, assert per view, `SOURBY_REQUIRE_ZSTD` (audit §4)
- [-] **OBS-10** Release doc / SPEC / README saling bertentangan soal status gate — pass konsistensi
  2026-10-14 (release doc, SPEC §161, README, `qualification-readiness.md`); ulangi saat tagging (audit §4)
- [ ] **OBS-11** `/awf export|import|convert` menerima path filesystem sembarang — batasi ke
  `awf/exports` / `awf/imports` (audit §4)

---

## P2 — Menengah (independensi dan arsitektur)

### Provisioning plugin (ProtocolLib)
- [x] Implementasi auto-provision ProtocolLib melalui jalur aktif SourbyClip/SourbyPatcher
  (2026-10-06): pin asset resmi, HTTPS + SHA-256/ukuran, setting independen, hook sebelum
  plugin scan, folder CLI, cache/offline dan pelestarian operator; 12 tes installer lulus.
  Bukti: `docs/development/PROTOCOLLIB.md`.
- [-] Qualification ProtocolLib: download artifact resmi, packaging/boot baru, dependency-plugin
  compatibility dan region soak belum diverifikasi.

### Migrasi penuh ke SourbyCraft (Aurora Engine) tanpa Canvas/Folia
Sejak 2026-10-05 build berjalan: vanilla → Paper (`paperRef` 9240f586) → SourbyCraft, lewat
paperweight resmi (SourbyPatcher `paper-toolchain` 3.0.0). Seluruh perubahan yang dulu datang dari
Canvas/Folia sekarang milik SourbyCraft: baseline engine di `minecraft-patches/sources` (476 file),
`paper-patches/files` (234), `sourbyapi/paper-patches/files` (16), dan source `io.canvasmc` di
`src/main/java`. Perubahan buatan Sourby ada di feature patch (`minecraft-patches/features` 29,
`paper-patches/features` 14, dihitung 2026-10-14; 22/4 pada 2026-10-05). Batasan yang tidak bisa dihindari:

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
  kontradiksi 21); renumbering 0024–0027 berikutnya masih diprediksi `patch_surface.py`. 2026-10-13: 0024 lama dihapus
  dan 0025–0029 dinomori ulang menjadi 0024–0028; rujukan nomor di docs current-state diperbarui
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

### Audit sistem 2026-10-14 — LOW
Laporan dan konteks: blok *Audit sistem 2026-10-14* di P0.
- [ ] **AWF-8** `WorldShutdownThread` bisa melempar ulang tanpa memanggil callback unload: future
  unload tidak selesai, lease tertahan — callback di `finally`; watchdog lease (audit §1)
- [ ] **AWF-9** `.slime` tak tepercaya: batas blob 1 GiB, accounter NBT tanpa batas, seluruh file di
  memori — batas yang bisa dikonfigurasi (256 MiB), accounter terbatas (audit §1)
- [ ] **AWF-10** Drift docs: nomor patch 0020 (seharusnya 0019), lokasi hook vs patch policy, javadoc
  `WorldRole` usang — perbaiki docs (audit §1; `aurora-world-fabric.md` sedang dipegang agen lain)
- [x] **ENG-11** Penolakan patch 0016 di fork `execute if/unless block … run` membatalkan kedua
  cabang diam-diam (PLAUSIBLE) — dokumentasikan atau rutekan ke owner (audit §2)
  — didokumentasikan `1c57a736` (2026-10-20) di `patch-classification.md` baris 0016; perubahan perilaku menunggu keputusan owner
- [x] **ENG-12** Plugin ter-quarantine yang di-reload dengan nama sama diterima dan semua task-nya
  ditolak diam-diam — log sekali saat admit (audit §2)
  — selesai `1c57a736` (2026-10-20): peringatan sekali per admit saat plugin ter-quarantine dimuat ulang, `BridgeRuntimeTest`
- [x] **ENG-13** Drift docs: javadoc `CompatibilityClassifier`, default region-parallelism §4,
  lokasi config scheduler di AGENTS.md — perbaiki docs (audit §2)
  — selesai `1c57a736` (2026-10-20): javadoc `CompatibilityClassifier`, `region-parallelism.md` §4, AGENTS.md
- [x] **ENG-14** Snapshot path async diambil sebelum penolakan mungkin terjadi; kegagalan worker
  dilog tanpa throttle — cek kapasitas dulu; rate-limit log (audit §2)
  — selesai `1c57a736` (2026-10-20): `hasCapacity()` sebelum snapshot, log kegagalan solver 1/60 s (`AsyncPathRefusalTest`)
- [ ] **SEC-9** Mode restart `exit` melakukan halt dari shutdown hook (PLAUSIBLE) — exit setelah
  shutdown selesai (audit §3)
- [ ] **SEC-10** Download updater tanpa batas sebelum cek ukuran; polling tanpa autentikasi tiap
  30 menit (PLAUSIBLE) — copy terbatas; ETag / Retry-After (audit §3)
- [ ] **SEC-11** Baseline Folia membuang guard login Paper (`max-joins-per-tick`, pre-login sinkron,
  profil duplikat) — warisan, belum didokumentasikan; dokumentasikan + limit laju koneksi (audit §3)
- [ ] **SEC-12** Base image Docker tidak di-pin per digest; label lisensi — pin digest; label
  setelah keputusan SEC-13 (audit §3)
- [ ] **OBS-12** `/speedtest` mencatat log sebelum cek permission — pindahkan log (audit §4)
- [ ] **OBS-13** Jalur `getTPS()` mengalokasi dan mengurutkan di bawah monitor region — layani dari
  snapshot yang sudah dipublikasikan (audit §4)
- [ ] **OBS-14** Lubang cakupan tes (engine, hud, maxplayers, lang, update, bootstrap); tes sensitif
  waktu — tes berprioritas; clock yang diinjeksi (audit §4)
- [ ] **OBS-15** `averageGcPauseMs` adalah waktu koleksi, bukan pause STW — caveat di javadoc (audit §4)
- [ ] **OBS-16** Kesiapan baseline berpatokan `Done (`, yang kini bisa mendahului terrain spawn
  bertahap (PLAUSIBLE) — tunggu juga ringkasan boot (audit §4)

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
  B77, B78, B79 FIXED; B76 sebagian (properti ter-mapping, PDC world belum)
  (`docs/architecture/aurora-world-fabric.md` *Robustness fixes 2026-10-07*). B80 dan B85 diperbaiki
  2026-10-13 (commit `e0c241e9`, CI run 38434258632 hijau; lihat item AWF create di P0). Temuan awal: metadata Slime/world PDC,
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

### Inovasi (proposal, belum direncanakan)
Dari audit sistem 2026-10-14 §5 (`docs/audits/2026-10-14-system-audit.md`). Belum diterima dan
belum direncanakan, kecuali yang ditandai; tidak ada yang diimplementasikan.
- [ ] Storage: generasi FILE ber-indeks delta dengan kompaksi (menutup AWF-7) — byte commit dan fsync
  sebanding dengan chunk kotor
- [ ] Storage: driver maintenance AWF dengan commit yang dipicu byte — jendela kehilangan terbatas
  waktu dan byte, umur kotor maksimum di `/perf awf`
- [ ] Storage: Redis `EVALSHA` + renewal ber-pipeline — satu round trip per pass renewal
- [ ] Storage: API rollback point-in-time di atas generasi FILE yang disimpan, untuk pemulihan operator
- [ ] Storage: object store content-addressed dibagi satu keluarga template (dedup antar pulau; di
  belakang flag)
- [x] Engine/bridge: konteks kepemilikan task bridged yang mengirim setiap hit guard ke quarantine
  tanpa mode THROW — selesai `1c57a736` (ENG-1)
- [ ] Engine/bridge: lane I/O bridged yang adil — sub-antrean berbobot per plugin (belum); timer
  lewati-bukan-batal selesai `1c57a736` (ENG-6)
- [ ] Engine/bridge: antrean region yang aman saat unload — kuras cancel-only ada (`WorldUnloadDrain`,
  `1c57a736`, ENG-3); fixture CI yang mengantrekan task lalu unload belum ada
- [ ] Engine/bridge: `MobPathSnapshot` immutable untuk async pathfinding (menutup ENG-8 dan audit M5)
- [x] Engine/bridge: ledger CPU saat boot di `ResourceGovernor` dan `/perf governor` — selesai `1c57a736`
  (`ThreadLedger`, ENG-9; laporan saja)
- [ ] Distribusi: manifest rilis bertanda tangan dengan persentase staged rollout dan daftar yank
  (tanda tangan rilis sendiri sudah diterima owner sebagai SEC-2; rollout/yank belum)
- [ ] Distribusi: rollback auto-update yang digate kesehatan boot (rollback `.prev` diterima owner
  sebagai SEC-3; dilacak di P0)
- [ ] Distribusi: SBOM (CycloneDX) dan atestasi provenance build per rilis
- [ ] Distribusi: slim jar reproducible dengan job rebuild independen
- [ ] Keamanan: satu modul security milik sendiri yang teruji untuk semua limit packet (= SEC-1,
  **diterima owner** 2026-10-14; dilacak di P0)
- [ ] Observability: gate regresi performa kontinu di CI (advisory dulu, sadar noise)
- [ ] Observability: ringkasan flame per region untuk region lambat (JFR opt-in)
- [ ] Observability: atribusi biaya plugin per region
- [ ] Observability: lane housekeeping dengan marker kesegarannya sendiri (commit AWF, sampling, histori)
- [ ] Observability: manifest provenance build (tree hash, flag dirty, hash direktori patch) dicetak `/ver`
