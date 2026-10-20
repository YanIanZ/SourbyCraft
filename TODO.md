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
roadmap). Security/observability ronde 1 sudah di-push (2026-10-20: `4a830168` updater SEC-2 sebagian/3/9/10, `1b33fe1e` SEC-8 + OBS-1/2/8/11/12
+ OBS-9 sebagian, `65dba8fb` SEC-12 + OBS-7/15/16 + SEC-11/OBS-14 dokumentasi). Sisa di papan OmniMemo: SEC-1/5/6/7 + OBS-3 (codex),
SEC-4 + OBS-4/5/6/13 (deepseek) — lihat `AGENT-COORDINATION.md` ROUND 2.

Laporan: [`docs/audits/2026-10-14-system-audit.md`](docs/audits/2026-10-14-system-audit.md)
(read-only, `d97fe247`; nomor baris di laporan per commit itu). Temuan HIGH di sini; MEDIUM di P1,
LOW di P2, proposal di P3. Keputusan owner 2026-10-14: SEC-1 bangun ulang guard; SEC-2/3 updater
aman + rilis bertanda tangan; SEC-5 hapus geo-IP SourbyClip; SEC-13 (lisensi) tetap terbuka
(*Keputusan owner*). Status `[ ]` = belum diperbaiki.
- [x] **AWF-1** Nama dunia managed dicocokkan ke setiap elemen path (dunia bernama seperti direktori
  induk server menempelkan semua dunia ke AWF) — cocokkan path relatif ke direktori server (`storageId()`) (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): nama dunia managed dicocokkan ke folder world milik storage itu sendiri
- [x] **AWF-2** `TicketHolder.pop()` membaca di luar monitor dan race dengan `propagate()`: ticket
  save hilang, `save-all`/`AuroraWorlds.save` tidak pernah selesai — pop atomik, gabungkan ticket kedua (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): `TicketHolder.pop` baca+kosongkan di bawah satu lock; propagate kedua di-coalesce
- [x] **AWF-3** Commit periodik AWF menumpang collector metrik dan terlewat saat koleksi telat, janji
  "hilang maksimal satu interval" tidak berlaku di bawah beban — driver maintenance AWF sendiri (audit §1)
  — sebagian `ebb9c1fb`: maintenance storage jalan pertama di tiap koleksi dalam try/catch sendiri (`PerformanceCollectorTest.lateSlowOrFailingCollectionsStillRunStorageMaintenance`); `46cbbefd` (OBS-4, 2026-10-20): housekeeping pasca-publish diisolasi try/catch sendiri sehingga tidak menurunkan snapshot segar ke STALE; MASIH TERBUKA: driver maintenance AWF yang sepenuhnya terpisah dari thread metrik (commit periodik tetap bisa terlewat saat koleksi telat)
  — selesai 2026-10-20: `awf/AwfMaintenanceDriver` (thread daemon `SourbyCraft-AWF-Maintenance`, fixed delay 1 s, tiap langkah try/catch sendiri, log ter-rate-limit, close idempoten dan menunggu pass berjalan) dimulai/dihentikan `MetricsRuntime`; collector tidak lagi memanggil AWF (`AwfMaintenanceIndependenceTest`, `AwfMaintenanceDriverTest` 14); `commit-interval-seconds` RESTART_REQUIRED; batas kehilangan = interval + ≤1 s + waktu commit; unit test saja, belum ada bukti server berbeban
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
- [x] **SEC-1** `sourbycraft-security.yml` tanpa consumer sejak `6b774810`: tidak ada guard
  packet/NBT/book/sign/payload, padahal docs bilang ditegakkan — **keputusan owner: bangun ulang**
  sebagai `dev.iyanz.sourbycraft.security` + satu feature patch, mode LOG dulu, tes per limit, cek
  policy bahwa setiap key punya consumer (audit §3)
  — selesai `41a444f5` (2026-10-20): guard di `dev.iyanz.sourbycraft.security`, MC 0034/0038/0040, mode LOG default; perbaikan review Opus (klik drag, NBT di bawah kuota vanilla, test hook decoder). Belum ada soak keamanan/boot proxy
- [ ] **SEC-2** Auto-updater hanya mempercayai respons API GitHub (tanpa tanda tangan), default
  `apply_mode=auto` — **keputusan owner:** rilis bertanda tangan (Ed25519, public key di jar), default `notify` (audit §3)
  — sebagian `4a830168` (2026-10-20): default `apply_mode=notify` (seed + docs `AUTO-UPDATER.md`), verifier Ed25519 aktif hanya bila `META-INF/sourbycraft-release.pub` ada di jar (tidak ada key yang dikirim), gate versi MC sama; BELUM: key pair + secret CI + langkah signing (keputusan owner); generator `scripts/release_signing_key.py` (2026-10-20, kunci dari skrip diverifikasi oleh `ReleaseSignatureVerifier` Java end-to-end) siap dijalankan owner
- [x] **SEC-3** Swap in-place tidak menyimpan jar sebelumnya dan otomatis melintasi versi Minecraft —
  **keputusan owner:** simpan `.prev` + rollback setelah boot gagal, gate versi MC yang sama (audit §3)
  — selesai `4a830168` (2026-10-20): `<jar>.prev` sebelum swap (tanpa .prev tidak ada swap), `UpdateRollbackGuard` APPLIED→BOOTING→OK, restore di boot berikutnya bila tidak mencapai ready (`ServerLoadEvent STARTUP`), `/update resume`; `MC_VERSION_MISMATCH`; 34 test unit, belum ada boot nyata jalur apply/rollback
- [x] **SEC-4** `SourbyBootstrap` kode mati (entry point = SourbyClip); grep guard download offline
  di CI tidak mungkin cocok — hapus atau sambungkan, tolak staging jar dengan versi clip berbeda, perbaiki grep CI dan docs (audit §3)
  — sebagian `db1be477` (2026-10-20): `SourbyBootstrap`/`CdsEnvironment`/`BootstrapManifest` + jalur `ensure()` mati di `LibDownloader` dihapus (transport seam untuk provisioning tetap), docs `CDS.md` + AGENTS.md "Boot-time network" dikoreksi ke SourbyClip; compileJava/compileTestJava hijau, LibDownloaderTest 2/2, test_plugin_provisioning.py 12 OK; BELUM: staging gate versi clip (snippet di AGENT-COORDINATION.md, area `update/**`) dan perbaikan grep CI offline (snippet; `build.yml` milik CI)
  — sisa selesai 2026-10-20: gate launcher `update/LauncherCompatibility` (`Outcome.CLIP_INCOMPATIBLE`, dipanggil `SourbyUpdater.verifyLauncherOrThrow` setelah SHA-256/tanda tangan): `META-INF/MANIFEST.MF` (`Clip-Version`, `SourbyClip-Protocol`) dan `sourbyclip.properties` jar staged harus cocok, protocol 1 dan versi >= 3.0.23; metadata hilang/kosong/tak terbaca/tidak sepakat = ditolak (fail-closed), unduhan dihapus; `LauncherCompatibilityTest` (21 `@Test`) + 155 tes paket `update` (SourbyUpdaterCheckTest, UpdateCommandReplyTest). Grep offline di `build.yml` (langkah "Bootstrap failure recovery and offline boot") diganti ke string log SourbyClip nyata (`[Sourbyclip] Failed to download original jar`, `Classpath prepared: N entries`, `Done (`) dan diputar ulang terhadap log CI run 39398396573; belum teramati di run CI baru (jar `release/SourbyCraft-26.2-REL.jar` lama berisi `Clip-Version: 3.0.21` tanpa protocol dan kini akan ditolak)
- [x] **OBS-1** Password Redis di `aurora.awf.redis.uri` bocor ke report spark — sembunyikan key,
  scrub userinfo URI di semua tempat (audit §4)
  — selesai `1b33fe1e` (2026-10-20): `aurora.awf.redis.uri` disembunyikan dan userinfo `scheme://user:pass@` di-scrub di seluruh nilai config spark; `SourbyServerConfigProviderTest` +3
- [x] **OBS-2** `/speedtest` yang menggantung mengunci command selamanya dan membocorkan proses —
  baca output paralel, tunggu dengan timeout (audit §4)
  — selesai `1b33fe1e` (2026-10-20): stdout/stderr dikuras di thread sendiri, `waitFor(timeout)` lalu `destroyForcibly()`, flag in-flight selalu direset; `SpeedtestCommandTest` 5
- [x] **OBS-3** Identitas build tidak terikat ke tree (tanpa flag dirty/tree hash; CI tidak cek
  `Git-Commit`) — `-dirty`, tree hash, cek `--commit $GITHUB_SHA` (audit §4)

### Keputusan owner
  — selesai `d4269dcb` (2026-10-20): tree hash + flag dirty (repo materialisasi termasuk minecraft/resources, file build tergenerasi), `--commit $GITHUB_SHA` di CI; CI run berikutnya adalah bukti pertama di runner
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
- [x] Review konkurensi penulisan region-file (DEV-G), dimulai 2026-10-06. Diperbaiki: dua commit AWF
  bisa mendarat di store dalam urutan terbalik dari snapshot-nya, sehingga store menyimpan isi chunk
  lama (`AwfCommitOrderTest`); `AwfRegionStorage.write` vs `close` kini saling eksklusif
  (`AwfLifecycleFenceTest.aWriteAdmittedJustBeforeCloseIsCommittedByIt`, unit test 2026-10-07, belum
  boot).
  — selesai 2026-10-20 (unit, tanpa boot/soak): flush bersamaan dari banyak region ditelaah dan dokumennya ada di `aurora-world-fabric.md` "Region-file write concurrency review (DEV-G, 2026-10-20)" (4 temuan); temuan diperbaiki: dua flush region yang berpacu memasukkan save kedua ke antrean sehingga store menerima map kosong = generasi FILE kosong + fsync (makan slot `retained-generations`) / round trip Redis; `AwfWorld.commitInOrder` kini mengembalikan -1 tanpa menyentuh store bila snapshot INCREMENTAL tidak punya apa pun; `AwfConcurrentFlushTest` 3 (mutasi guard dimatikan: tes deterministik gagal), `AwfCommitOrderTest`, `AwfLifecycleFenceTest` 5, `AwfRegionStorageTest` 24 hijau; belum: soak multi-world, jalur penolakan `MAX_PENDING_SAVES` hanya ditelaah
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
- [x] Audit lengkap kebenaran snapshot async pathfinding (DEV-E, ROADMAP M5)
  — selesai 2026-10-20 di tingkat audit sumber (flag `aurora.entity.async-pathfinding` tetap OFF): sisi mob di `async-pathfinding-spec.md` (ENG-8, `MobPathSnapshotTest`); sisi blok di `docs/architecture/async-path-snapshot-audit.md` (tabel anggota `PathNavigationRegion` vs `SnapshotPathRegion`, 8 temuan) + `SnapshotPathRegionAuditTest` (3) + `SnapshotPathRegionTest`; satu-satunya divergensi kebenaran (chunk tak termuat: snapshot menjawab AIR/OPEN, vanilla `*IfLoaded` null/BLOCKED) diperbaiki di patch MC 0048; belum: run flag-on / soak, perbedaan `getBlockEntity`
- [x] Pembatalan/shutdown async pathfinding: tes penghapusan entity, unload world, dan target
  bergerak saat solve sedang berjalan (ROADMAP M5)
  — selesai 2026-10-20: `AsyncPathCancellationTest` (17) atas model `AsyncPathNavigationModel` + `AsyncPathPatchShapeTest` (5) yang mengunci bentuk patch 0006; lambda patch sendiri dan `Mob` nyata belum dijalankan (butuh server)
- [x] Bukti reentrancy scratch milik entity (posisi skalar) (DEV-A)
  — selesai 2026-10-20 untuk scratch feature patch: `docs/architecture/scratch-reentrancy-proof.md` (inventaris 0009/0012/0006/0039/0042 + 0003, semua SAFE dengan bukti); satu UNSAFE-benign diperbaiki di MC 0044 (`lastKnownSpeedX/Y/Z` bisa robek lintas thread via Dolphin `FollowPlayerRiddenEntityGoal`; kini satu field turunan), `ScratchReentrancyTest` 7; belum: scratch baseline engine, bukti runtime/soak
  — DIBUKA LAGI 2026-10-20 (review Codex): field turunan 0044 `double` non-volatile masih bisa robek (JLS §17.7); F3 token validitas null bisa menghidupkan lagi navigasi setelah `stop()` kedua; tes ASM punya celah setelah rekompilasi; 0009 mengubah ambang `1.0E-5F` → `1.0E-5D` (deviasi vanilla). Perbaikan di papan
  — ditutup 2026-10-20: temuan review ulang independen (Codex) diperbaiki di patch MC 0046 (predikat gerak horizontal dipublikasikan sebagai `volatile boolean` dengan ambang float `1.0E-5F`) dan 0047 (generasi navigasi membatalkan path async yang sedang berjalan, termasuk retarget dengan node yang sama); `ScratchReentrancyTest`, `AsyncPathPatchShapeTest`, `AsyncPathCancellationTest`, `AsyncPathValidityTest` diperbarui, mutasi R1-R3 tertangkap. Batas jujur: F3 di `scratch-reentrancy-proof.md` SAFE hanya untuk invalidasi yang terenumerasi (model tulisan tangan, bukan lambda patch nyata); TIDAK tercakup: scratch baseline engine (`minecraft-patches/sources`, 113 patch entity) dan bukti runtime/soak
- [-] Bukti runtime bahwa thread region tidak pernah blok pada I/O eksternal (saat ini baru audit statis)

### Jaringan dan keamanan
- [x] Verifikasi event loop Netty tidak melakukan I/O blocking yang tidak terkait (DEV-H)
  — audit sumber `41a444f5` (2026-10-20) di `region-io-audit.md`; H1 (MC 0035) dan H4/H5 (MC 0037) diperbaiki; H2/H3/H6 = panduan plugin `PLUGIN-NETTY-GUIDANCE.md`; tanpa bukti runtime
- [x] Pertahankan limit packet/security (`sourbycraft-security.yml`) di setiap perubahan network.
  Catatan audit 2026-10-14: file itu tidak punya consumer sejak `6b774810`, jadi saat ini tidak ada
  limit yang ditegakkan — lihat **SEC-1** di bawah
  — selesai 2026-10-20 (catatan audit di atas sudah usang): SEC-1 `41a444f5` membangun ulang consumer (`security/SecurityGuards`, 20 `Limit`, MC 0034/0036/0038/0040/0041); aturan "setiap key punya consumer" dikunci `SecurityGuardsTest.everyShippedYamlLeafHasAConsumerAndEveryGuardDefaultsToLog` (28 tes) dan `SecurityLimitPinTest` (3) mengunci 19 limit + `ipv6-prefix` 64 (default kode == tabel == YAML terkirim), jadi perubahan yang melonggarkan limit gagal di suite; belum: soak keamanan / boot proxy

### SourbyClip (sekarang bisa dikerjakan di repo `YanIanZ/SourbyClip` dengan Codex)
- [-] Audit timeout downloader, retry/failure, validasi SHA/cache, dan konkurensi di dalam
  SourbyClip: kandidat lokal 3.1.1 sudah diperiksa dan diuji (29 tes Java; timeout connect/read,
  staging SHA, mirror fallback, worker bounded). Pin rilis sudah masuk (SourbyClip 3.1.2 @ `85b4dd70`,
  `5a3806f1`, SEC-5); masih terbuka: whole-transfer deadline dan size cap launcher (repo privat
  SourbyClip: soket hanya dibatasi 30 s connect/read; butuh perubahan di repo itu, rilis, dan re-pin —
  keputusan owner); bukti di `docs/architecture/bootstrap-download-audit.md` (DEV-L, AURORA-I)
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
- [x] Pindahkan pool async path ke belakang AuroraExecution dengan callback owner-context eksplisit (M5)
  — selesai 2026-10-20 (menunggu commit): pool kini lane `ResourceGovernor.Lane.PATHFINDING` (`GovernedLane`, antrean 1024, menolak bukan caller-runs, thread `max(1, cores/4)`; "AuroraExecution" dibaca sebagai Resource Governor, satu-satunya execution fabric Aurora di kode — tidak ada kelas `AuroraExecution`); callback owner-context eksplisit sudah ada (`execution/OwnerHandoff`, `RegionOwnerHandoff`, `perf/AsyncPathCompletion`, dipakai MC 0006); `ResourceGovernorTest` 10, `LiveIncidentSourcesTest` 1, `UnboundedQueueAuditTest` 3, `AsyncPath*` hijau; default fitur tetap OFF; `/perf governor` dan metadata spark kini memuat lane "Pathfinding" saat async pathfinding menyala; belum: benchmark sync vs async, soak mob (butuh server)

### Memori
- [ ] Verifikasi alokasi MB/s terhadap JFR
- [ ] Verifikasi RSS/cgroup di Linux/container
- [x] Workflow laporan kelas yang paling banyak dialokasikan
  — selesai (alat + prosedur; terverifikasi 2026-10-20): `scripts/profile_server.py` merekam JFR `settings=profile` (termasuk `jdk.ObjectAllocationSample`); `scripts/rank_hotspots.py` `allocation_hotspots` meranking byte teralokasi per kelas / site / thread dan mencocokkan dengan `jdk.ThreadAllocationStatistics` (faktor overstatement dicetak); alur di `docs/BASELINE.md` "Ranking hot spots"; `test_rank_hotspots.py` 37 OK; belum: laporan dari run tersertifikasi (butuh server) — hasil nyata baru berupa bukti ranking di `hotspots-entity-ai.md` (run tak tersertifikasi)
- [x] Tes retensi: player disconnect, chunk unload, world unload
  — selesai 2026-10-20: 46 tes retensi (bridge, anchor region, RegionMetricsRegistry, speedtest, AwfEngine, lease Redis, unload AuroraWorlds, HudBars) via `ReferenceQueue`; bocor diperbaiki: `FoliaRegionBackend.anchors` memegang World kuat → `AnchorCache` weak; belum: GeoUtil cache, pearls engine

### Observability
- [-] `/tps`, `/mspt`, `/perf`, `/perfbar`, `/tpsbar`, `/rambar` disatukan dengan Sourby metrics
  (sudah diimplementasikan, perlu validasi)
- [x] `/ram` lengkap; `/perf region <world> <x> <z>`
  — selesai 2026-10-20: `/ram` sesuai PLAN.md (alokasi/GC young-old/last pause `n/a` jujur, belum diinstrumentasi); `/perf region <world> <x> <z>` via `RegionLocator` (lookup regioniser approximate, tanpa refresh generasi lain, nama world case-insensitive); belum diamati di server hidup
- [x] View antrean region scheduler (sebagian 2026-10-20: `/perf scheduler` ada — jenis scheduler, thread configured/pool/alive, region per state READY/TRANSIENT/TICKING/DEAD, region terlambat, tick terlama; antrean task per region dan histori late-tick `n/a`, butuh feature patch — kini nyata lewat patch 0045, lihat catatan di bawah. Teks lama: `/perf scheduler` tidak ada — view yang ada di
  `PerfCommand.VIEWS`: `lanes` beban CPU per lane, `governor` antrean lane governed, `async`
  pool path; antrean milik region scheduler sendiri belum ditampilkan di mana pun)
  — selesai 2026-10-20: patch MC 0045 menambah `LongAdder` lock-free untuk kedalaman antrean task region (tick/chunk) dan hitungan tick terlambat; `/perf scheduler` (`PerfSchedulerView`) menampilkan jumlah task antre per region dan hitungan late-tick-start sejak boot; `RegionTaskQueueDepthTest`, `TickLateStartCounterTest`, `SchedulerQueueCollectorTest`, `PerfSchedulerViewTest`. Tetap `n/a` (dengan alasan, tidak diperkirakan): run queue scheduler dan thread idle, antrean task global (antrean privat tanpa accessor ukuran), dan antrean chunk-task seluruh dunia (global); belum diamati di server hidup
- [ ] Verifikasi config SourbyCraft/Aurora tampil benar di Spark web viewer
- [x] Klasifikasi thread region yang lebih baik di Spark
  — selesai 2026-10-20: `SparkThreadClass` + metadata `sourbycraft-threads` di tiap laporan spark (region/global tick, chunk worker/IO, lane Sourby, async path, AWF maintenance, netty dapat dibedakan saat di-sample); tidak bisa: global vs region tick (thread sama) dan lane `SourbyCraft-IO-n` (virtual thread, tak terlihat spark); default dumper tidak berubah
- [x] Telemetri histori: `/perf history` (`PerformanceHistory`, ring 60 menit, satu sampel per menit:
  TPS/MSPT region terburuk, CPU proses, heap; `PerformanceHistoryTest`)
- [-] Telemetri sisa: latensi tunggu task, counter siklus hidup chunk, counter tick entity, log
  insiden (kejadian, bukan sampel per menit)
  — log insiden selesai 2026-10-20: `/perf incidents` (`IncidentLog` ring 20, merge per kind+subject, 9 sinyal; watchdog dan pause GC STW tidak bersumber); siklus hidup chunk dan tick entity masih terbuka
  — latensi tunggu task tercatat 2026-10-20 (patch MC 0049 + `perf/RegionTaskWaitLatency`, per jenis tick/chunk sejak boot; `RegionTaskWaitLatencyTest` 8, `RegionTaskWaitLatencyHookTest` 4), tetapi belum ditampilkan (butuh baris di `/perf scheduler`), tanpa angka server, overhead belum di-benchmark
- [x] Latensi save region-file (saat ini hanya ada latensi commit AWF)
  — selesai 2026-10-20: patch MC 0043 mengukur `moonrise$finishWrite` (tulis+hapus, sukses dan gagal) ke `perf/RegionFileSaveLatency` (ring lock-free 1024, p50/p99/max 60 s per world), tampil di `/perf storage`; overhead belum dibenchmark, belum ada angka server

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
  berlaku untuk jar 2026.3 upstream yang tidak di-port. Adapter dengan jar nyata diuji tingkat unit 2026-10-20
  (`SuperiorSpawnRealJarTest` 4/4 terhadap jar nyata, env-gated `SOURBY_SSB2_JAR`/`SOURBY_SSB2_FOLIA_JAR`, CI melewatinya; temuan: callback jar port-Folia memakai `BukkitExecutor.sync(Location, Runnable)` sehingga adapter mengembalikan null lewat cek bentuk, bukan penolakan fingerprint); belum ada run server;
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
  — audit ulang parsial 2026-10-20 (tree kerja setelah `12b1232c`): klaim README dicek (angka bernama workload+tanggal atau tertaut bukti); dikoreksi satu kalimat soal enable-ulang instance yang sama (hanya boot/unit, tanpa marker; TODO bridge di atas) dan baris Build 47 soal limit `sourbycraft-security.yml` (SEC-1); sel ENG-1/3/4 di release doc diperbarui; gate tetap OPEN; belum: audit pada commit yang akan di-tag, SPEC §161

### Audit sistem 2026-10-14 — MEDIUM
Laporan dan konteks: blok *Audit sistem 2026-10-14* di P0.
- [x] **AWF-4** Nama template tidak divalidasi di from-template/delete/read: path traversal termasuk
  delete rekursif — validasi nama, batasi resolusi ke root template (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): nama template divalidasi dan di-resolve di dalam root templates
- [x] **AWF-5** Lease Redis bocor lewat `unprepare` dan jalur "already open", lalu error palsu
  "lost its Redis lease" — tutup store di setiap removal/throw (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): unprepare/already-open/registrasi gagal/export menutup store-nya, lease Redis dilepas
- [x] **AWF-6** Perpanjangan lease serial: stores × `timeout-ms` bisa melebihi lease pada Redis
  lambat (PLAUSIBLE) — pipeline renewal, WARN bila satu pass > period/2 (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): perpanjangan lease satu round trip ter-pipeline per putaran
- [x] **AWF-7** (roadmap) Biaya commit FILE O(dunia): indeks penuh ditulis ulang, di-hash dan
  di-fsync per commit; satu fsync per objek — generasi delta, fsync batch (audit §1; lihat item
  "AWF FILE: tulis massal lambat" di P3)
  — separuh fsync selesai 2026-10-20: objek ditulis dulu lalu di-force satu pass sebelum index (`ObjectStore.sync`, `Durability`), direktori shard/`objects`/`N.tmp`/leluhur store kini di-fsync, kegagalan fsync direktori dipropagasi; A/B lokal tak tersertifikasi (M2, APFS USB SSD) bulk 3.364 chunk median 34,1 s → 7,9 s; jumlah force tetap O(objek).
  — selesai 2026-10-20: generasi delta-index diimplementasikan tetapi OPT-IN (`aurora.awf.delta-generations`, default false, RESTART_REQUIRED); flag mati menulis persis format pra-AWF-7 (manifest format 1, indeks penuh tiap commit, byte identik dengan penulis `12b1232c`, diuji); dengan delta menyala, downgrade ke build pra-AWF-7 butuh `/awf compact all` (atau flag dimatikan lalu restart: setiap store yang dibuka dikompaksi saat open) dan kehilangan riwayat tersimpan yang hanya ada di delta secara permanen (build lama menghapus objek yang hanya direferensikan generasi delta; backup dulu); tidak ada percepatan wall-time yang terukur lokal (A/B tak tersertifikasi, 3.364 chunk: commit 336 chunk median 1.649 ms penuh vs 1.688 ms delta; byte indeks per commit 238 KB → 23 KB, probe 100.000 chunk 7,2 MB → 0,7 MB; jumlah force tetap O(objek)). Alasan opt-in: manifest format 2 tidak terbaca build sebelumnya (merusak rollback SEC-3). Tes: `AwfDeltaGenerationTest` 15, `AwfCompactionLifecycleTest` 2, `AwfSettingsTest` 9, `AwfDurabilityTest` 14, `AwfCommandTest` 7, 263 tes `awf` hanya dengan 4 initializationError suite yang sudah dikenal; review Codex: default-off bersih, 2 temuan P2 jalur rollback diperbaiki; belum: boot dengan flag menyala, `/awf compact` di server hidup, jar pra-AWF-7 nyata (parser lama dimodelkan di `HeadBuildReader`)
- [x] **ENG-5** Ticket save dilaporkan selesai saat region merge/split di tengah save; bagiannya
  tanpa ticket (flush AWF melewatkan chunk) — teruskan ticket saat merge, sebar saat split (audit §2)
  — selesai `1c57a736` (2026-10-20): MC 0031, `SaveAllTicket.split`/`TicketHolder.forwardTo`, `SaveTicketRegionChangeTest`
- [x] **ENG-6** Satu lane `BRIDGE_IO` penuh membatalkan timer async berulang milik plugin lain
  selamanya — lewati, jangan batalkan, task berulang (audit §2)
  — selesai `1c57a736` (2026-10-20): `BridgeRuntime.rejectedIo` lewati run task berulang (`skippedRuns`), `BridgeRuntimeTest`
- [x] **ENG-7** Penolakan path async memakai slot recompute tanpa fallback sinkron — kembalikan
  null saat ditolak supaya vanilla jalan (audit §2)
  — selesai `1c57a736` (2026-10-20): `AsyncPathProcessor.trySubmit` null → recompute sinkron vanilla, `AsyncPathRefusalTest`
- [x] **ENG-8** Worker path membaca state mob hidup di luar owner — snapshot immutable yang diambil
  di owner (audit §2; terkait audit snapshot M5 di P0 *Konkurensi*)
  — selesai `41a444f5` (2026-10-20): MC 0039 `MobPathSnapshot` diambil di owner; worker tidak menyentuh Mob hidup (`MobPathSnapshotTest`); fitur tetap default off, soak flag-on masih terbuka (papan)
  — lanjutan 2026-10-20: MC 0042 Creaking — home position di-snapshot di owner saat solver isolasi dibuat, evaluator tahu mode snapshot lewat flag eksplisit (bukan `mob == null`), sehingga query owner tetap membaca home hidup; offload Creaking kini diizinkan (`AsyncPathEvaluatorIsolationTest` 8)
- [x] **ENG-9** Oversubscription CPU secara default (thread region = semua prosesor ditambah pool
  chunk, async, Netty, path, bridge) — ledger thread saat boot; ubah default hanya dengan bukti A/B (audit §2)
  — ledger saja, selesai `1c57a736` (2026-10-20): `ThreadLedger` di log boot dan `/perf governor` (boot lokal: 22 thread / 8 prosesor, 2.8x); default TIDAK diubah
- [x] **ENG-10** Future `propagateSaveAllTicket` diselesaikan di bawah write lock regioniser;
  dependent sinkron yang masuk lagi bisa deadlock (PLAUSIBLE) — selesaikan secara async (audit §2)
  — selesai `1c57a736` (2026-10-20): `SaveAllTicket.completing` menyelesaikan future di luar thread pemanggil (MC 0031)
- [x] **SEC-5** SourbyClip memanggil tiga layanan geo-IP setiap boot, dua lewat HTTP biasa —
  **keputusan owner: hapus lookup** di repo SourbyClip (audit §3)
  — selesai `5a3806f1` (2026-10-20): SourbyClip 3.1.2 (`85b4dd70`, tanpa `IPUtil`/geo-IP) di-pin di `gradle.properties` + lock file; artifact Maven Local hash = pin; slimServerJar 40.756.217 B; test_clip_launcher 4/4; ClipDownloadProbe/ClipLaunchProbe hijau (probe hash-rejection disesuaikan ke chained IOException 3.1.2); boot lokal: banner `=== SourbyClip 3.1.2 ===`, `Done (` 21.3s, `stop` exit 0; bawa 3.1.1: terminal controls, worker lifecycle, `--sourbyclip-prepare`
- [x] **SEC-6** `ViaAutoUpdate` menimpa pin Via, bisa memasang versi yang tidak mencantumkan MC
  yang berjalan, mempercayai hash dari API, menghapus jar operator — hanya versi yang mencantumkan
  versi MC, jangan hapus jar yang bukan milik kita, batasi ukuran (audit §3)
  — selesai `d93794eb` (2026-10-20): Via hanya memulihkan pin yang direview (metadata harus mencantumkan versi MC, hash pin, ukuran dibatasi, jar operator tidak dihapus, tanpa request bila jar ada)
- [x] **SEC-7** Pin ProtocolLib menunjuk asset `dev-build` yang bisa berubah, belum pernah di-boot —
  pin rilis bertag, probe CI terjadwal (audit §3)
  — selesai `6e50b735` (2026-10-20): pin tagged 5.4.0 (asset 280088448, 10.251.186 B, SHA-256 dihitung dari artifact resmi; dev-build sudah ganti bytes lagi: 608298510→616780410); load test 2 boot di slim jar 26.2: enable + disable bersih (`Done (` 15,7s/16,0s, exit 0); caveat soft upstream `MC 26.2.0 has not yet been tested` permanen sampai 26.2 masuk MCVERSIONS upstream (bukan regresi SourbyCraft); probe CI terjadwal = snippet workflow read-only di AGENT-COORDINATION.md (milik CI owner)
- [x] **SEC-8** CI: action di-pin per tag bukan SHA; `contents: write` untuk seluruh job — pin
  per SHA, persempit permission (audit §3)
  — selesai `1b33fe1e` (2026-10-20): semua `uses:` di-pin SHA 40-hex dengan tag di komentar; `permissions: contents: read` di atas dan job build, `write` hanya di job `release` baru (`needs: build`)
- [x] **OBS-4** Maintenance AWF di thread metrik; kegagalan setelah publish menurunkan snapshot
  segar jadi STALE — perbaikan sama dengan AWF-3, try/catch terpisah (audit §4)
  — selesai `46cbbefd` (2026-10-20): housekeeping pasca-publish diisolasi di try/catch sendiri dengan log rate-limited; snapshot segar tidak lagi turun ke STALE; tes regresi `aPostPublishFailureKeepsTheFreshSnapshotPublished`
- [x] **OBS-5** Ring sampel mentah 384 merusak jendela 10/15 s di atas 25,6 Hz atau saat sprint —
  ukuran dari tick rate (audit §4)
  — selesai `46cbbefd` (2026-10-20): kapasitas ring dari interval tick teramati (default 384, headroom 2×, cap 4096); tes `rawRingSizesFromTickRateSoFastTicksDoNotTruncateTheFifteenSecondWindow`
- [x] **OBS-6** Generasi telemetri region hanya disapu collector; tak terbatas bila metrik gagal
  start — sapuan teramortisasi, batas (audit §4)
  — selesai `46cbbefd` (2026-10-20): `sweepExpired` amortisasi dari `activate()` (SWEEP_BATCH 64, kursor id generasi dengan wrap); tes `activationSweepsExpiredGenerationsWithoutTheCollector`
- [x] **OBS-7** Policy script melewatkan patch Paper dan package engine; root yang hilang dilewati
  diam-diam — tuple root termasuk fitur Paper dan engine; assert root ada (audit §4)
  — selesai `65dba8fb` (2026-10-20): root eksplisit (minecraft + paper `features`, `aurora/engine`), root hilang = error; pemindaian yang diperluas menemukan 4 simbol di patch Paper 0005/0014 yang dicatat dengan alasan di `test_independence_policy.py` (`PATCH_APPROVED` + set scheduler) dan `independence.md`; Python 302 OK di 3.9 dan 3.12
- [x] **OBS-8** Balasan command async melewati `SourbyReply` (command block mendapat exception) —
  lewatkan via `SourbyReply.run` (audit §4)
  — selesai `1b33fe1e` + `4a830168` (2026-10-20): balasan async `AwfCommand`, `SpeedtestCommand`, `PerfCommand` (`/perf player`) dan `UpdateCommand` lewat `SourbyReply.run`; test dengan sender command-block
- [ ] **OBS-9** Gate CI yang lulus tanpa banyak membuktikan: CI PR tidak pernah jalan, grep smoke
  cocok dengan header panel apa pun, marker metrik hanya cek non-null, tes zstd skip diam-diam —
  perbaiki trigger, assert per view, `SOURBY_REQUIRE_ZSTD` (audit §4)
  — sebagian `1b33fe1e` (2026-10-20): trigger `pull_request` (build+test, tanpa publikasi), grep smoke per-view pada log tanpa ANSI, `SOURBY_METRICS_SAMPLED_OK` dengan nilai nyata; BELUM: `SOURBY_REQUIRE_ZSTD` (`SlimeImporterTest` tidak membaca env), bukti boot dari run CI berikutnya
  — `SOURBY_REQUIRE_ZSTD` sisi tes (2026-10-20): `awf/ZstdTestSupport` (pola `RedisTestServer`) dipakai `SlimeImporterTest` + `AwfWorldFileTest`; BELUM: baris env `SOURBY_REQUIRE_ZSTD: "1"` di langkah tes `build.yml` (workflow sedang dipegang SEC-7)
  — selesai `52cce4b8` (2026-10-20): env `SOURBY_REQUIRE_ZSTD: "1"` di langkah tes `build.yml` (zstd-dependent gagal bukan skip); sisanya hanya bukti boot dari run CI berikutnya
- [-] **OBS-10** Release doc / SPEC / README saling bertentangan soal status gate — pass konsistensi
  2026-10-14 (release doc, SPEC §161, README, `qualification-readiness.md`); ulangi saat tagging (audit §4)
- [x] **OBS-11** `/awf export|import|convert` menerima path filesystem sembarang — batasi ke
  `awf/exports` / `awf/imports` (audit §4)

---

## P2 — Menengah (independensi dan arsitektur)

### Provisioning plugin (ProtocolLib)
  — selesai `1b33fe1e` (2026-10-20): `AuroraWorldFiles.confine` → `awf/exports` (tulis) / `awf/imports` (baca); path absolut, `..`, symlink keluar ditolak; API plugin tetap tidak dibatasi (didokumentasikan); `AwfCommandTest` 6, `AuroraWorldFilesTest` +2
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
- [-] M-2 Isolasi (T9 di `AURORA-FULL-TRANSITION.md`): pindahkan body service Sourby keluar dari
  class upstream; pecah patch campuran 0006/0013 dan pindahkan class dari 0002/0005/0016; isolasi
  bridge Spark Canvas; dokumentasikan setiap dependensi keras Canvas di `independence.md`
  — sebagian 2026-10-20: `AuroraCpu` keluar dari patch 0002, `AuroraCommandErrors` dari 0016, `AwfChunkNbt` dari 0021, `GliderSlots` dari 0007 (plus tes regresi dan perbaikan bug undian acak); dependensi keras Canvas terdokumentasi (`independence.md` §2.2); BELUM: pindah `SnapshotPathRegion` (0005), pecah 0006/0013, cabang AWF di 0021, isolasi bridge Spark Canvas (menunggu keputusan owner adapter vs fork)
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
- [x] Rencana migrasi nama file konfigurasi `config/canvas-server.yml` / `canvas-worlds.yml`
  (sekarang sengaja tetap, agar konfigurasi server yang sudah ada tetap terbaca)
  — rencana PROPOSED 2026-10-20: `docs/development/CONFIG-FILE-RENAME-PLAN.md` (direview Codex, 11 temuan diterapkan); belum diimplementasikan
- [x] Pengaman agar baseline engine (`minecraft-patches/sources`, `paper-patches/files`) tidak
  diedit diam-diam: perubahan Sourby wajib lewat feature patch supaya terlihat oleh policy
  — sudah ada (diverifikasi 2026-10-20): `scripts/baseline_policy.py` + `build-data/engine-baseline.sha256` (726 file, 3 root) dan langkah CI "Engine baseline guard"; edit baseline tanpa marker yang juga menulis ulang manifest hanya tertangkap di review
- [x] Pin SHA-256 SourbyPatcher hanya mencakup jar, tidak POM tempat versi paperweight ditentukan
  — kandidat lokal 3.1.0 memeriksa versi paperweight yang dimuat terhadap identitas di JAR;
  hash POM/dependency penuh tetap belum diimplementasikan
  — selesai `9147ce3b` (2026-10-20): `settings.gradle.kts` kini memverifikasi SHA-256 POM **dan** `.module` paper-toolchain (hilang/mismatch = gagal konfigurasi) + `paperweightVersion` dibaca dari properti; `scripts/private_toolchain.py` menuntut metadata ter-pin mendeklarasikan persis `paperweightVersion` (equality check, jalan di CI sebelum Gradle); 9 tes; BELUM: hash graph dependency paperweight transitif (artifact dari repo.papermc.io tetap tidak diverifikasi — di luar cakupan pin ini)
- [x] Nomor feature patch punya celah (0018) dan akan dinomori ulang pada rebuild berikutnya;
  perbarui rujukan nomor patch di docs/test saat itu terjadi. Celah 0018 tertutup oleh rebuild
  2026-10-06 (0019–0023 → 0018–0022, isi sama); rujukan lama baru dianotasi sebagian (AURORA.md
  kontradiksi 21); renumbering 0024–0027 berikutnya masih diprediksi `patch_surface.py`. 2026-10-13: 0024 lama dihapus
  dan 0025–0029 dinomori ulang menjadi 0024–0028; rujukan nomor di docs current-state diperbarui
  — diverifikasi 2026-10-20: `patch_surface.py --check-rebuild` melaporkan MC 0001-0044 (kini 0001-0048 tanpa celah, `ls`), Paper 0001-0014, tanpa duplikat/celah/untracked; catatan penomoran lama→kini ditambahkan di bawah H1 `threading`/`reuse-audit`/`patches`/`hotspots-entity-ai`/`region-safety-review-0017-0019`/`slim-jar-bootstrap-failure`; komentar `IsInWallBoundsTest`/`BlockCollisionBoundsTest` dikoreksi; aturan tetap berlaku bila renumbering terjadi lagi; baris SPEC B30 dan `docs/releases/*.md` sengaja memakai nomor bertanggal
- [ ] M-5 Opsional, paling berat: lepas Paper sebagai upstream (vanilla → SourbyCraft langsung).
  Artinya ~974 patch Paper menjadi milik SourbyCraft, dan setiap rilis Minecraft serta perbaikan
  keamanan Paper harus di-port sendiri.
- Gate tiap tahap: build dari clean checkout reproducible, semua tes Java + Python lulus, boot test
  dan tes persistensi lulus, jumlah konflik rebase tercatat di `rebase-log.md`.

### Build dan reproduktibilitas
- [ ] Tes reproduktibilitas dari clean checkout
- [ ] Validasi boot cached/offline sebagai gate independensi
- [ ] Hapus dependensi build legacy hanya setelah ada bukti clean build
- [x] Dokumentasikan repository remote yang dibutuhkan dan urutan fallback-nya
  — `docs/development/REMOTE-REPOSITORIES.md` (diperbarui 2026-10-20 dengan bukti file:line dan tabel verifikasi hash)

### Arsitektur patch
- [x] Identifikasi optimasi yang duplikat dengan upstream
  — selesai 2026-10-20 (hanya pada `paperRef` yang di-pin 9240f586): baris `-` tiap feature patch PERFORMANCE (0004, 0005, 0007, 0009, 0010, 0012, 0014, 0017, 0018) masih kode upstream asli; 0003/0006/0011 adalah tambahan tanpa padanan upstream; tidak ada duplikat (`docs/architecture/patch-classification.md` Findings). Belum: bandingkan dengan Paper/Moonrise lebih baru (ulangi tiap bump `paperRef`), tanpa benchmark
- [-] Simpan implementasi di source milik Sourby bila memungkinkan; tambah tes regresi sebelum
  sebuah patch dihapus
  — aturan berlaku terus; irisan 2026-10-20: 0007 kini punya `GliderSlotsTest` (menemukan dan memperbaiki penyimpangan undian acak dari vanilla bila ada 2+ slot glider), `AuroraCommandErrorsTest` (0016), `AwfChunkNbtTest` (0021); patch 0009-0012 belum punya tes khusus
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
- [x] **AWF-8** `WorldShutdownThread` bisa melempar ulang tanpa memanggil callback unload: future
  unload tidak selesai, lease tertahan — callback di `finally`; watchdog lease (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): MC 0030 selalu menyelesaikan callback unload (FAIL_UNKNOWN saat throw)
- [x] **AWF-9** `.slime` tak tepercaya: batas blob 1 GiB, accounter NBT tanpa batas, seluruh file di
  memori — batas yang bisa dikonfigurasi (256 MiB), accounter terbatas (audit §1)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): impor Slime dibatasi (default 256 MiB, dicek sebelum alokasi), baca NBT dibatasi
- [x] **AWF-10** Drift docs: nomor patch 0020 (seharusnya 0019), lokasi hook vs patch policy, javadoc
  `WorldRole` usang — perbaiki docs (audit §1; `aurora-world-fabric.md` sedang dipegang agen lain)
  — selesai `ebb9c1fb` (2026-10-14, CI 38450393598 hijau): `aurora-world-fabric.md` dan javadoc `WorldRole` dikoreksi
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
- [x] **SEC-9** Mode restart `exit` melakukan halt dari shutdown hook (PLAUSIBLE) — exit setelah
  shutdown selesai (audit §3)
  — selesai `4a830168` (2026-10-20): mode `exit` memakai `MinecraftServer.abnormalExit` → `onServerExit()` keluar 70 setelah shutdown bersih; tanpa shutdown hook/halt
- [x] **SEC-10** Download updater tanpa batas sebelum cek ukuran; polling tanpa autentikasi tiap
  30 menit (PLAUSIBLE) — copy terbatas; ETag / Retry-After (audit §3)
  — selesai `4a830168` (2026-10-20): `Content-Length` di atas batas ditolak sebelum dibaca, stream dipotong di batas; `ETag`/`If-None-Match` + 304; `Retry-After`/`X-RateLimit-*` backoff (`RATE_LIMITED`)
- [x] **SEC-11** Baseline Folia membuang guard login Paper (`max-joins-per-tick`, pre-login sinkron,
  profil duplikat) — warisan, belum didokumentasikan; dokumentasikan + limit laju koneksi (audit §3)
  — didokumentasikan `65dba8fb` (2026-10-20) di `docs/development/LOGIN-GUARDS.md` (buffer join per tick diganti `if (true)`, `PlayerPreLoginEvent` sinkron tidak pernah menyala, jalur duplicate-profile diganti `pushPendingJoin`); rate limit koneksi BELUM dibangun
  — rate limit selesai `41a444f5` (2026-10-20): MC 0036/0041, LRU + overflow hanya global, epoch monoton, loopback dikecualikan, kunci alamat forward di belakang proxy, IPv6 /64; tidak ada uji proxy/TCP nyata
- [x] **SEC-12** Base image Docker tidak di-pin per digest; label lisensi — pin digest; label
  setelah keputusan SEC-13 (audit §3)
  — selesai `65dba8fb` (2026-10-20): `FROM eclipse-temurin:25-jre@sha256:dbb43d81…` (digest dari `docker buildx imagetools inspect`, dicocokkan dengan header registry); label lisensi menunggu SEC-13; `docker build` belum dijalankan (daemon mati)
- [x] **OBS-12** `/speedtest` mencatat log sebelum cek permission — pindahkan log (audit §4)
  — selesai `1b33fe1e` (2026-10-20): log `/speedtest` dipindah setelah `testPermission`
- [x] **OBS-13** Jalur `getTPS()` mengalokasi dan mengurutkan di bawah monitor region — layani dari
  snapshot yang sudah dipublikasikan (audit §4)
  — selesai `46cbbefd` (2026-10-20): `tps()`/`mspt()` melayani rata-rata lewat helper `sumsFor` tanpa alokasi/sort (mirip resolusi targetless `report()`); tes bytecode `tpsAndMsptServeAveragesWithoutAllocatingOrSorting`; catatan: `MSPTData.rawData()` kini kosong (tidak ada konsumen produksi)
- [x] **OBS-14** Lubang cakupan tes (engine, hud, maxplayers, lang, update, bootstrap); tes sensitif
  waktu — tes berprioritas; clock yang diinjeksi (audit §4)
  — daftar prioritas `65dba8fb` (2026-10-20) di `docs/development/TEST-COVERAGE-GAPS.md` (P1–P3 + 10 test sensitif waktu dengan seam clock); test Java-nya belum ditulis
  — 86 test P1 `b5766689` (2026-10-20) untuk hud/maxplayers/lang/engine util; sisa seam (SourbyCraftConfig, HudBars scheduler, PearlsMigration) di papan
  — seam selesai (2026-10-20): `SourbyCraftConfig.installForTesting`, `HudBars.installForTesting` + `HudBarsSchedulingTest` (17), `PearlsMigration(Path)` + 6 tes legacy; refleksi di tes maxplayers/lang dihapus; `TimeSpanValidation` membuat `M` ambigu di `logs.cleanerTimeSpan`/`autosave.autosaveFrequency` gagal saat load dengan nama key. Full suite server 10561/0. Sisa OBS-14: baris terbuka di `TEST-COVERAGE-GAPS.md` + konversi clock
  — baris 4 (stage count bootstrap) selesai 2026-10-20: `SourbyCraftBootstrapTest` + seam `verdict()`/`publishBootStages()`
  — baris 1 (update/) dan 7 (command/) selesai 2026-10-20: 81 + 55 tes; bug diperbaiki: `HttpTransport` ClassCastException pada URL non-HTTP, `/ping` melempar RejectedExecutionException saat executor I/O penuh/berhenti, `AutoUpdateSettings` reload mempertahankan nilai lama (mis. `apply_mode=auto`) saat key dihapus — kini default terdokumentasi, `/ver` tidak lagi membuat runtime bridge sebagai efek samping. Full suite server 10707/0
  — baris 2 (bootstrap/, 71 tes) dan konversi clock (6 tes; RedisBackendTest 13,1 s → 4,5 s) selesai 2026-10-20; full suite server 10778/0. Sisa OBS-14: P3 baris 8 (engine) dan 9 (sourbyapi)
  — baris 8 dan 9 selesai 2026-10-20: engine 157 + spark 53 tes (bug diperbaiki: `BalancedChunkSystem` ArrayStoreException → ledger -1, codec `RegionResourceBar` melempar pada placement tak dikenal, YAML komentar saja crash di `NodeDiff`, versi spark basi 1.10.172, deskripsi datapack kosong di laporan spark); sourbyapi 58 tes `io.canvasmc`
- [x] **OBS-15** `averageGcPauseMs` adalah waktu koleksi, bukan pause STW — caveat di javadoc (audit §4)
  — selesai `65dba8fb` (2026-10-20): caveat javadoc `RuntimeMetrics.averageGcPauseMs()`/`gcTimePercent()` dan `GcTracker` (waktu koleksi, bukan pause STW)
- [x] **OBS-16** Kesiapan baseline berpatokan `Done (`, yang kini bisa mendahului terrain spawn
  bertahap (PLAUSIBLE) — tunggu juga ringkasan boot (audit §4)

---

## P3 — Rendah (polish dan fitur tambahan)
  — selesai `65dba8fb` (2026-10-20): `run_baseline.py` menunggu `Done (` DAN ringkasan boot Aurora `COMPLETE | 14/14 stages…`; DEGRADED/FAILED menggagalkan run. Catatan: premis audit tidak berlaku untuk dunia boot (spawn tidak di-stage), jadi ini gate kedua pada boot yang sama
- [x] UI/UX terminal dan command SourbyCraft/Aurora (2026-10-06): progress per tahap dengan
  durasi/status, panel bersama, `/aurora`, `/pl` identik `/plugins`, pencarian/filter/halaman,
  diagnosis loader JUL + Log4j, header dan contoh config beranotasi. Compile/suite server penuh,
  243 tes Python, identitas server/API, boot command dan reload terisolasi lulus;
  detail/batas bukti di `docs/AURORA-UX.md#local-validation-2026-10-06`.

- [x] Mode HUD actionbar
  — selesai 2026-10-20: `/tpsbar|/rambar|/perfbar actionbar|bossbar`, default bossbar, mode per pemain di PDC (LIVE); refresh basi dibuang lewat token sesi; belum diamati di klien nyata
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
  B77, B78, B79 FIXED; B76 sebagian (properti ter-mapping; PDC world dipulihkan saat `/awf import` Slime 2026-10-20 (unit `SlimeImporterTest`), tetapi konversi ke `.awf` masih menjatuhkannya karena formatnya tak punya key; wiring layanan belum diuji di server)
  (`docs/architecture/aurora-world-fabric.md` *Robustness fixes 2026-10-07*). B80 dan B85 diperbaiki
  2026-10-13 (commit `e0c241e9`, CI run 38434258632 hijau; lihat item AWF create di P0). Temuan awal: metadata Slime/world PDC,
  pruning PDC/biome, fence commit sesudah discard, WorldProperties pada export/import,
  dan join I/O pada global tick. Review statis saja; fix serta pengujian regresi masih terbuka,
  testing oleh Claude. Bukti source dan skenario: `docs/development/ASP-26.2-AWF-PORT-REVIEW.md`;
  SPEC B76–B80. Hasil tes sebelumnya tidak menutup temuan ini.
- [ ] AWF Redis lewat jaringan: tiap baca chunk = satu round trip; prefetch/pipelining tetangga
- [x] AWF FILE: tulis massal lambat (fsync per objek chunk; 3.364 chunk = 14,9 s) — batch fsync
  — selesai 2026-10-20 (lihat AWF-7): force satu pass setelah semua tulis; A/B lokal tak tersertifikasi
- [ ] Backend database AWF MongoDB/MySQL
- [ ] Executor yang sudah ada dipindahkan ke bawah Resource Governor (ditunda; masing-masing sudah
  punya batas sendiri)
- [x] Namespace config `aurora.performance`, `aurora.chunk`, `aurora.memory`, `aurora.ai`,
  `aurora.world` (hanya ditambahkan bila sudah ada key yang benar-benar dipakai)
  — n/a sampai ada key (2026-10-20: tidak ada key `aurora.{performance,chunk,memory,ai,world}` di `src/main`)

### Inovasi (proposal, belum direncanakan)
Dari audit sistem 2026-10-14 §5 (`docs/audits/2026-10-14-system-audit.md`). Belum diterima dan
belum direncanakan, kecuali yang ditandai; hanya butir yang bertanda [x] sudah diimplementasikan.
- [x] Storage: generasi FILE ber-indeks delta dengan kompaksi (menutup AWF-7) — byte commit dan fsync
  sebanding dengan chunk kotor
  — diimplementasikan 2026-10-20 tetapi OPT-IN (`aurora.awf.delta-generations`, default false, RESTART_REQUIRED; lihat AWF-7): byte indeks per commit sebanding dengan chunk yang berubah, jumlah fsync TIDAK (tetap O(objek) + 6) dan tidak ada percepatan wall-time terukur
- [-] Storage: driver maintenance AWF dengan commit yang dipicu byte — jendela kehilangan terbatas
  waktu dan byte, umur kotor maksimum di `/perf awf`
  — pemicu byte masuk opt-in 2026-10-20 (`aurora.awf.commit-dirty-bytes`=0 default, RESTART_REQUIRED; `AwfDirtyBytesCommitTest` 7); metrik umur kotor terbuka (butuh timestamp kotor per chunk + baris `/perf awf`); efek ke jendela kehilangan belum diukur
- [x] Storage: Redis `EVALSHA` + renewal ber-pipeline — satu round trip per pass renewal
  — selesai 2026-10-20: renewal ber-pipeline sudah ada (AWF-6); semua script kini lewat EVALSHA (`RedisClient.eval`/`evalPipeline`, NOSCRIPT dikirim ulang sekali sebagai EVAL); `RedisBackendTest` 21 (+2), butuh redis-server; byte/latensi yang dihemat belum diukur, Redis Cluster tidak dipertimbangkan
- [-] Storage: API rollback point-in-time di atas generasi FILE yang disimpan, untuk pemulihan operator
  — API tingkat store saja 2026-10-20 (`AwfWorldStore.rollbackPoints()`/`rollbackTo(long)`, `AwfRollbackTest` 6); BELUM: perintah operator (generasi region/entities/poi bernomor terpisah, butuh pemetaan, keputusan owner), backend Redis tak punya generasi
- [ ] Storage: object store content-addressed dibagi satu keluarga template (dedup antar pulau; di
  belakang flag)
- [x] Engine/bridge: konteks kepemilikan task bridged yang mengirim setiap hit guard ke quarantine
  tanpa mode THROW — selesai `1c57a736` (ENG-1)
- [-] Engine/bridge: lane I/O bridged yang adil — sub-antrean berbobot per plugin (komponen `execution/FairLane` + `FairLaneTest` 8 ada 2026-10-20 tetapi BELUM dipasang ke `BRIDGE_IO`; setting dan bobot keputusan owner); timer
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
