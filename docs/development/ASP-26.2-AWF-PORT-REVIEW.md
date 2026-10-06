# Review port AdvancedSlimePaper 26.2 → AWF

Tanggal: 2026-10-06. Review statis terhadap working tree yang sedang diedit bersama
Claude. Tidak menjalankan Gradle, unit test, boot, atau benchmark. Temuan di bawah
belum merupakan reproduksi runtime; perbaikan dan pengujian regresi masih terbuka.
Hasil tes sebelumnya di SPEC/TODO tidak memvalidasi temuan atau perubahan ini.

## Status temuan 2026-10-07

Sumber: [AGENT-COORDINATION.md](../../AGENT-COORDINATION.md) ("Aurora deepening round", agent "awf"),
[aurora-world-fabric.md](../architecture/aurora-world-fabric.md) bagian "Robustness fixes 2026-10-07" dan SPEC B76-B80/B85.
Teks setelah tabel ini adalah review 2026-10-06 dan tidak diubah; label "OPEN" di bagian "Status handoff" digantikan oleh tabel ini.
Semua perbaikan **hanya diuji di tingkat unit** (kelas tes AWF: 151 dijalankan, 0 gagal, 1 benchmark dilewati); belum ada boot server,
soak, atau uji lease Redis yang mencakupnya, dan tidak ada klaim performa.

| Temuan | Status 2026-10-07 | Bukti (tes) |
| --- | --- | --- |
| A1 import Slime membuang properties dan world PDC (B76) | **SEBAGIAN.** Properties bertipe ASP (spawn x/y/z+yaw, difficulty, pvp, allowMonsters/Animals, defaultBiome) dipetakan saat `importSlime` dan ditulis ke `.awf` oleh `convertSlime`; kunci lain dicatat sebagai dibuang. **PDC world (`BukkitValues`) belum dipulihkan.** | `SlimeImporterTest.worldPropertiesMapFromTypedValuesAndTheRestIsReportedDropped`, `theHeaderOnlyReaderSeesWhatAFullParseSees`, `slimeToAwfToImportKeepsTheProperties`; wiring `AuroraWorldsService.importSlimeInternal` tidak punya tes unit karena butuh server berjalan |
| A2 pruning menghapus chunk dengan PDC/biome/ticks (B77) | **DIPERBAIKI.** `ChunkPruning` mempertahankan PDC tidak kosong, block/fluid ticks, PostProcessing, structure start/reference, UpgradeData, kunci top-level tak dikenal, dan palette biome selain satu `the_void`/`plains`. Akibatnya dunia void tanpa defaultBiome tunggal the_void/plains kini tidak dipruning. | `ChunkPruningTest.nonRegenerableDataKeepsAnOtherwiseEmptyChunk`, `aVoidChunkWithOnlyPluginDataSurvivesUnloadAndReload` |
| A3 commit antre menulis setelah `unload(false)` (B78) | **DIPERBAIKI untuk backend FILE.** `discard()` memagari `AwfWorld`; commit yang antre tetapi belum mulai gagal dengan `FencedException`; commit yang sudah berjalan selesai atomik dan `closeStore()` menunggunya; `AwfWorldStore` tertutup menolak commit; write dan close saling eksklusif. **Pelepasan lease Redis setelah close tidak diuji ulang.** | `AwfLifecycleFenceTest.aCommitQueuedBeforeADiscardCommitsNothingWhenItRunsAfterIt`, `aFencedWorldRefusesQueuedCommitsWithoutTouchingTheStore`, `aRunningCommitCompletesAndTheStoreClosesOnlyAfterIt`, `aClosedFileStoreRefusesCommits`, `aWriteAdmittedJustBeforeCloseIsCommittedByIt` |
| A4 export/import `.awf` tidak membawa WorldProperties (B79) | **DIPERBAIKI.** Metadata `.awf` menulis kunci `properties` (JSON) saat export; import memulihkan dan memvalidasi; file lama tanpa kunci diimpor tanpa properties. | `AuroraWorldFilesTest` (4 tes: round trip dengan semua field non-default, file legacy, penolakan malformed) |
| A5 `createAfter` join I/O di global tick (B80, B85) | **TERBUKA, tidak diubah.** Kontrak `AuroraWorlds` menjanjikan future yang sudah selesai kepada pemanggil di global region thread agar bisa di-join di sana; handoff asinkron akan merusak janji itu dan membuat pemanggil tersebut deadlock. Perlu perubahan kontrak lebih dulu. Spawn-generation wait dari Spark RyP3FlAYME (B85) juga terbuka. | Tidak ada |

Jalur format di tabel "Perbandingan jalur format" (zstd, v12/v13, entities compound) kini punya tes `SlimeImporterTest.version12FilesAreReadToo`
dan `advancedSlimePapersOwnVersion13IsRead` (file tes ditulis Claude, log "Owned" 2026-10-06); hasil per metode tidak dicatat di log.

Referensi upstream: [AdvancedSlimePaper dev/26.2 pada f8b0bc3](https://github.com/InfernalSuite/AdvancedSlimePaper/tree/f8b0bc312fba1e017b67738d96e45bf033225f88).
Commit penuh: `f8b0bc312fba1e017b67738d96e45bf033225f88`. Branch dapat bergerak;
review ini mengacu pada commit tersebut. Source diambil melalui GitHub API/raw.
AdvancedSlimePaper / InfernalSuite adalah sumber referensi; pemakaian langsung
source upstream harus mempertahankan atribusi dan memenuhi lisensi GPL-3.0-nya.

## Perbandingan jalur format

| Bagian | Hasil pembacaan source AWF dan ASP |
| --- | --- |
| v12/v13 dan blob zstd | AWF sekarang mengenali zstd serta layout zlib lama; belum diuji oleh Codex |
| Entitas v13 | Reader AWF menerima wrapper compound `entities` seperti serializer ASP; juga menerima list legacy |
| POI dan scheduled ticks | Bit flag serta key `block_ticks`/`fluid_ticks` sesuai serializer ASP yang diperiksa |
| Light arrays | AWF membuang dua array 2048 byte; urutan skip terbalik tidak mengubah posisi stream karena keduanya dibuang |
| Unknown world flags | Payload dilewati; ASP memberi warning, AWF tidak memberikan diagnosis setara |
| Properties dan world extra data | Dibaca sebagian tetapi tidak diteruskan ke dunia hasil import; A1 |
| Pruning | Pemeriksaan air mirip aggressive pruning ASP; itu tidak membuktikan aman untuk metadata plugin; A2 |
| Lifecycle region-threaded | AWF memiliki gate lokal; fence commit pada discard masih kurang; A3 |

Kesamaan serializer/reader bukan bukti kompatibilitas runtime penuh. Entitas hidup,
POI, scheduled ticks, lighting ulang, lintas data-version, ownership region, dan
shutdown masih memerlukan pengujian oleh Claude.

## A1 — P1: import Slime membuang properties dan world PDC

Lokasi: `SlimeImporter.java:74–81,113–127,276–293` dan
`AuroraWorldsService.java:276–308`.

`read()` mengekstrak `properties`, tetapi `Converted` dan `Result` hanya membawa
data chunk dan data version. `convert()` tidak meneruskan `parsed.properties()`;
entry import dibuat dari `WorldCreator` dengan properties null. Compound extra
data lainnya dibuang oleh `properties()`, termasuk `BukkitValues` untuk world PDC.
Akibatnya file ASP dengan spawn/difficulty/PvP atau world PDC dapat berhasil
diimpor sambil kehilangan pengaturan dan data plugin tersebut. `convertSlime()`
juga menggunakan hasil konversi tanpa metadata ini.

ASP mempertahankan seluruh extra data dan menggabungkan properties file dengan
override pemanggil di [reader v13](https://github.com/InfernalSuite/AdvancedSlimePaper/blob/f8b0bc312fba1e017b67738d96e45bf033225f88/core/src/main/java/com/infernalsuite/asp/serialization/slime/reader/impl/v13/v13SlimeWorldDeSerializer.java#L46).
[SlimeLevelInstance](https://github.com/InfernalSuite/AdvancedSlimePaper/blob/f8b0bc312fba1e017b67738d96e45bf033225f88/aspaper-server/src/main/java/com/infernalsuite/asp/level/SlimeLevelInstance.java#L110)
memulihkan `BukkitValues` dan spawn/difficulty saat membangun dunia.

Arah perbaikan: teruskan metadata bertipe dari parsing sampai registry/file AWF
dan jalur pemulihan world PDC. Tentukan prioritas override yang eksplisit; jangan
menganggap default WorldCreator sebagai override semua properties file. Nilai
NBT byte/float sekarang menjadi teks SNBT (`1b`, `90.0f`); mapping harus membaca
nilai bertipe, bukan sekadar `Boolean.parseBoolean`/`Float.parseFloat` atas SNBT.
Extra data yang belum dapat dipulihkan perlu dipertahankan atau dilaporkan jelas.

Kasus untuk Claude: file dari serializer ASP dengan spawn/yaw, difficulty,
PvP=false, allow flags, dan world PDC sentinel; periksa setelah import, reload,
serta Slime→AWF→import. Uji override eksplisit terpisah dari default pemanggil.

## A2 — P1: pruning menghapus chunk kosong dengan data plugin/biome

Lokasi: `ChunkPruning.java:49–66`, `AwfRegionStorage.java:121–127`, dan
`AuroraWorldRegistry.java:83–86`.

Untuk chunk baru tanpa base/owned entry, pemeriksaan hanya melihat status,
block entities, entities, serta palette air. `ChunkBukkitValues`, biome yang
diubah, scheduled ticks, dan metadata lain tidak diperiksa sebelum write dibuang.
Pruning default aktif untuk generator void. Plugin yang menulis PDC pada chunk
baru tanpa menaruh blok/entitas dapat kehilangan data pada unload/reload.
SerializableChunkData pada branch ini memang menyimpan PDC dengan key
`ChunkBukkitValues`; data tersebut bukan bagian dari `block_entities`.

Arah perbaikan: hanya prune data yang benar-benar dapat diregenerasi sama dengan
generator dan properties sekarang. Pertahankan chunk dengan metadata plugin,
biome khusus, ticks, atau payload yang belum dipahami. Aggressive pruning upstream
juga mengabaikan sebagian metadata; menyalinnya tidak membuat pruning lossless.

Kasus untuk Claude: chunk void baru dengan hanya PDC, hanya biome berbeda, serta
scheduled payload; harus tetap tersimpan. Chunk air tanpa metadata boleh dipruning;
chunk lama/base yang dikosongkan harus tetap menutupi data sebelumnya.

## A3 — P1: queued commit dapat menulis sesudah unload(false)

Lokasi: `AwfRegionStorage.java:150–155,183–194,231–233`,
`AwfWorld.java:200–224,255–277`, dan `AwfStore.java:48`.

`maintain()` mengantrekan `world.save()`. `discard()` hanya mengubah flag pada
storage; runnable save tidak memeriksa flag tersebut. Close melewati saveNow saat
discarding, tetapi tidak membatalkan/memagari save yang sudah antre. Backend FILE
mewarisi close no-op. Karena dirty map belum dibuang, runnable yang mulai setelah
discard dan close tetap dapat mengambil snapshot lalu commit ke disk. Ini
bertentangan dengan janji mempertahankan last committed state pada unload tanpa save.

Arah perbaikan: fence lifecycle pada level pemilik commit, tersinkronisasi dengan
commitLock. Tolak commit yang belum dimulai setelah discard/close; tetapkan
perilaku commit yang sudah berjalan dan tunggu pelepasannya sebelum storage atau
lease penulis dilepas. Menambahkan flag hanya pada write/flush tidak cukup.

Kasus untuk Claude: executor manual menahan runnable save; tulis dirty chunk,
maintain, discard, close, lalu jalankan runnable. Generation dan bytes FILE harus
tetap pada commit sebelum discard. Uji juga commit yang sudah berjalan, reload
langsung, dan Redis lease release agar instance lama tidak menulis sesudah close.

## A4 — P1: export/import AWF tidak membawa WorldProperties

Lokasi: `AuroraWorldFiles.java:38–48,75–92,118–121`.

Registry sekarang memiliki WorldProperties, tetapi metadata export hanya berisi
environment, seed, generator, type dan provenance. `describe()` membuat entry
dengan constructor lama tanpa properties. Dunia dengan spawn/difficulty/PvP,
save bounds, pruning override atau defaultBiome kehilangan konfigurasi tersebut
ketika diekspor lalu diimpor. Clone/template yang menyimpan Entry lengkap tidak
memiliki omission yang sama; bug ini khusus jalur file portable yang diperiksa.

Arah perbaikan: serialisasikan properties dalam metadata berversi, pulihkan dan
validasi sebelum membuat dunia. File lama tanpa field tersebut harus tetap bisa
dibaca dengan defaults yang terdokumentasi.

Kasus untuk Claude: round trip Entry dengan semua field properties non-default,
termasuk false booleans, biome void khusus dan bounds; periksa registry serta
perilaku sesudah reload. Uji file legacy yang tidak mempunyai properties.

## A5 — P2: async create/load menunggu I/O pada global tick

Lokasi: `AuroraWorldsService.java:362–367,405,716–733`.

Jika dipanggil dari global tick, `createAfter()` melakukan `ready.join()` sebelum
mengembalikan future. `ready` mencakup persiapan backend, dan pada import mencakup
pembacaan/dekompresi/penulisan file. Thread global menunggu operasi tersebut;
pekerjaan pada I/O lane tidak membuat join di thread tick menjadi non-blocking.
Backend lambat atau file besar dapat menahan global tick dan menyebabkan stall.
Review ini tidak mengukur lama stall atau mereproduksi watchdog.

Arah perbaikan: pertahankan API future yang benar-benar asynchronous dan jadwalkan
pembuatan dunia ke owner setelah persiapan selesai. Pemanggil di tick thread harus
melanjutkan melalui callback; jangan memberi jaminan boleh join sebagai bagian
kontrak async. Bila startup memerlukan jalur sinkron khusus, batasi lifecycle itu
secara eksplisit.

Kasus untuk Claude: future persiapan tertahan; pemanggilan dari global tick harus
mengembalikan future tanpa menunggu. Setelah persiapan dilepas, action harus berjalan
pada global owner. Periksa plugin enable/startup dan propagasi failure.

## Dokumentasi yang perlu diperbarui bersama fix

`SlimeImporter` Javadoc dan `docs/architecture/aurora-world-fabric.md:129–139`
masih mendeskripsikan v13 sebagai zlib/entitas bare list serta pembatasan ASP lama.
Implementation sekarang menerima zstd dan compound entities. Dokumentasikan
layout yang didukung, metadata yang dipertahankan, serta batas pengujian aktual.
Jangan tandai port lengkap berdasarkan fixture legacy atau keberhasilan compile.

## Status handoff

Per 2026-10-06 semua A1–A5: OPEN; runtime fix belum dibuat oleh Codex dalam review ini (status terkini: tabel 2026-10-07 di atas). Kode AWF
yang sedang dimiliki Claude tidak diubah. Kasus di atas adalah usulan pengujian,
bukan tes yang sudah dijalankan. SPEC §B dan TODO mencatat pekerjaan yang terbuka.
Snapshot hash source lokal ada di bagian berikut; cek ulang jika working tree bergerak (per 2026-10-07 hash `SlimeImporter`, `ChunkPruning` dan `AwfWorld` sudah tidak cocok karena file diubah oleh perbaikan di atas).

## SHA-256 snapshot source lokal

```text
53380042a2cd7a4a22571c596ac93cb3f8b464863169644eaee5a39ea3310ae4  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/SlimeImporter.java
37d40c9f5ddb08b2ef29311a95ff1c37c07d879283f0d51dee3bbda55e4b7253  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/AuroraWorldsService.java
69026579926a436a7ef5569bf362af92f3c5f6a5dc3d3a9857e3bb8d36b85975  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/AuroraWorldFiles.java
03dc83f949b96d2130962a16d236ebb77dbbb5f45c63b806ed1b205b52ab7ff2  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/world/AuroraWorldRegistry.java
f99180d304b7b6eb6be490f104b13a5af6daac987090d84e7133cf39a52f7cfb  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/ChunkPruning.java
0f00426fc7860b6adae5a7cabb4c21a80d33de3f290f0a89c7715ca3958bd093  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/AwfRegionStorage.java
f27f0dd9e1dd2ea1b471e2530d193fb8931acf695564ad0f7df17cc6044098fa  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/AwfWorld.java
dd40a4f5a6438155c61fa6310020a10199e2c8527a65404a01b0f93603b33de4  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/AwfWorldStore.java
a5e5bd2af27a55c4a239889dc2e162946a09ab6b22d121eb375e4c41e3667f0f  sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/awf/AwfStore.java
```

## Profile follow-up, 2026-10-06 — A5 is not the entire creation wait

The user supplied [Spark RyP3FlAYME](https://spark.lucko.me/RyP3FlAYME). Its selected
region-thread group includes `/awf create` → `Bukkit.createWorld` → `setInitialSpawn` →
`ChunkTaskScheduler.syncLoadNonFull`, with 4,650 ms of recorded inclusive stack time,
4,570 ms under its park child. This is accumulated sampling evidence, not a single-pause
measurement or proof of all exploration stutter. Making `ready.join()` asynchronous alone
would leave this later spawn-generation wait. Preserve spawn/terrain fidelity and ownership
in any phased initialization design. Details and Claude acceptance:
[AURORA-EDF-UPGRADE.md](AURORA-EDF-UPGRADE.md). No AWF source fix is claimed here.
