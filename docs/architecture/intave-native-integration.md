# Intave native — build pribadi SourbyCraft

Audit 2026-10-06. **NATIVE_CODE_INTEGRATED_UNVERIFIED**: port sumber dan hook server tersedia;
compile engine lengkap, build Gradle, boot native dan efektivitas deteksi belum terverifikasi.
JAR SourbyCraft yang sudah ada belum dibangun ulang dengan engine ini. Tidak ada klaim
anticheat sudah aktif, qualified, atau memiliki tingkat deteksi tertentu.

Owner memilih penggunaan server pribadi tanpa distribusi. Source Intave berada di direktori
Git-ignored `.private-intave/`; profile tidak masuk build publik/CI secara default.

## Provenance

Upstream dipin ke [`c67af7f4fca5d09f68e59f7996c6b15b2a560455`](https://github.com/intave/intave/commit/c67af7f4fca5d09f68e59f7996c6b15b2a560455).
Snapshot lengkap berisi 1.403 blob terverifikasi: main/resources/lisensi/build script,
147 kelas tes dan 26 fixture/resource biner. Salinan asli plus inventory Git hash tetap di
`verified-upstream/`. Perubahan checkout lokal `~/Sourby/intave` tidak dicampurkan.

Source API pendukung kini tersedia dari tag resmi
[`samples 0.0.12`](https://github.com/intave/samples/tree/c45161073429f5e46f5f7787e7b5370e26e65e2a)
dan [`cloud-protocol 0.0.11`](https://github.com/intave/cloud-protocol/tree/1a7560fbb6907a2b58920c734eff96070db3e490).
Commit dan annotated-tag object dipin di `dependency_sources` pada build plan; 151 file
main/build terverifikasi terhadap Git blob disimpan di `.private-intave/dependencies/snapshots/`.
Source asli dan notice tetap dipertahankan. Compile source API bukan verifikasi byte artifact
Maven terbitan atau pelaksanaan test API upstream.

Lisensi [PolyForm Perimeter](https://github.com/intave/intave/blob/c67af7f4fca5d09f68e59f7996c6b15b2a560455/LICENSE.md),
copyright dan attribution Intave tetap dipertahankan. [README upstream](https://github.com/intave/intave/tree/c67af7f4fca5d09f68e59f7996c6b15b2a560455#license)
menjelaskan cakupan pemakaian/adaptasi pribadi. Pemindahan package bukan pengalihan kepemilikan
atau izin distribusi publik. Build pribadi harus tetap digunakan sesuai cakupan tersebut.

Package engine adalah `dev.yanianz.intave`. Dependency JNI `de.jpx3.classloader` tetap memakai
nama asli; mengubahnya memerlukan perubahan kontrak native tersendiri. Relokasi hanya mengubah
format teks yang dikenali, tidak mengubah byte fixture biner meskipun terbaca sebagai UTF-8.

## Implementasi awal native

`scripts/integrate_private_intave.py` mem-port workspace terverifikasi. Entrypoint menjadi
`IntaveEngine extends NativeService`, bukan `JavaPlugin`. `NativeService` menyediakan facade
owner untuk kontrak lama Bukkit (scheduler/event/permission), tetapi tidak dimasukkan ke plugin
manager, plugin scan, atau plugin loader. Permission default upstream diregistrasikan langsung;
cleanup menghapus hanya permission/command/listener milik facade ini.

Server memiliki controller `dev.yanianz.intave.NativeIntave`. Native engine dimuat sebagai class
server lewat provider privat, tanpa plugin artifact. State `ACTIVE` hanya diterbitkan setelah
inisialisasi tahap akhir selesai, engine melaporkan ready dan packet subscriptions tersedia.
`ACTIVE` berarti hook diinisialisasi, bukan sertifikasi efektivitas. Error callback membuat state
`FAILED`, menolak callback baru dan menampilkan bahwa perlindungan dinonaktifkan. Admission hook
nonblocking juga menonaktifkan facade owner; command/sends native menolak state yang tidak aktif.

Feature patch `0024-SourbyCraft-private-native-Intave-lifecycle.patch` menambahkan:

- Start setelah world/plugin initialization, sebelum menerima koneksi pemain; command map
  disinkronkan agar diagnosis tersedia di console dan client.
- Tick callback di akhir `ServerGamePacketListenerImpl.tick`, dengan pemeriksaan owner region
  di provider. Tidak memasang reflected global tickable lama. Subscriber harus menyebut pemain;
  quit menghapus subscriber pemain itu.
- Cleanup pada shutdown thread setelah proses halt Aurora. Controller menunggu callback owner
  yang sedang berjalan dengan batas 5 detik; timeout/interruption menyimpan resource dan
  melaporkan kegagalan, tanpa mengklaim cleanup selesai.
- `Connection.sendNative` mempertahankan queue, readiness, extra packets dan dispatch/finish
  path server. Flag filtering mengikuti queued send sampai event-loop write; konteks thread
  dipulihkan dalam finally. Paket native tidak ditulis langsung melewati queue Connection.
  Paket dengan finish subscriber memakai promise Netty nyata, sehingga callback menerima
  hasil kirim tanpa exception `void future`; jalur tanpa subscriber tetap memakai void promise.

`NativeProtocol` menggunakan ProtocolLib sebagai **library packet access** embedded, bukan
ProtocolLib JavaPlugin atau `PacketFilterManager`. Library lokal dipin ke
`5.5.0-SNAPSHOT-9404ce3`, SHA-256
`4b991e5b390649b54b5b3f7613caa3bf97b25646ea6a0d0207f965c4d0741a94`.
Profile menyalin class/resource library ke server JAR, tanpa plugin descriptor, plugin config
atau signed-JAR metadata. Setting internal library berada di memory, terpisah dari YAML Intave.

Adapter memasang handler pada ChannelInitializeListener, mempertahankan subscription priority,
cancellation dan jalur INTERNAL outbound sebelum callback ProtocolLib. Native sends dengan
filter=false tetap menjalankan INTERNAL subscriptions seperti jalur TinyProtocol upstream.
Incoming injection dimulai setelah native handler, tidak mengulang decoder wire.
API manager yang belum disediakan melempar error eksplisit. Diagnostic lama `platrace` yang
bergantung pada injector ProtocolLib diganti dengan pemberitahuan status native.

Callback deteksi upstream tetap berjalan dalam topology Netty upstream. Ini **tidak membuktikan**
seluruh akses world mutable sudah benar pada Aurora; audit serta replay/soak region tetap gate.
Adapter tidak menambah worker pool sendiri; pool engine upstream masih harus diukur bersama
kapasitas CPU scheduler Aurora. Provider menunggu barrier Netty sebelum disposal module. Timeout barrier melaporkan cleanup
gagal dan mempertahankan resource module; tidak melakukan disposal ketika callback masih berjalan.

Native lifecycle tidak memanggil provisioner dependency plugin, boot library injection,
plugin telemetry atau remote version index. Master cloud connection default off; cloud ML tetap
memerlukan layanan upstream dan kredensialnya, bukan otomatis tersedia dari source lokal.

## Config dan diagnosis

| Surface | Consumer/status | Perubahan |
| --- | --- | --- |
| `-PincludePrivateIntave=true` | Gradle profile server; tipe DSL diperiksa, eksekusi Gradle belum terverifikasi | BUILD_REQUIRED |
| `-Dsourbycraft.intave.enabled=true` | Controller saat boot; default false | RESTART_REQUIRED |
| `-Dsourbycraft.intave.cloud=true` | Engine boot; default false; tetap mengikuti konfigurasi cloud upstream | RESTART_REQUIRED |
| `sourbycraft_config/intave/` | ConfigurationService engine upstream | Native reload belum terverifikasi; validasi lewat restart |
| `/intave-native status` | Diagnosis server, termasuk disabled/unavailable/failed | LIVE, permission `sourbycraft.command.intave` default OP |
| `/intave native` | Diagnosis ketika facade command privat telah diregistrasikan | LIVE, permission `intave.command` default OP |
| `candidate-config.toml` | Rancangan; tidak punya runtime consumer | PLANNED |

`candidate-config.toml` tidak mengaktifkan mode observasi dan tidak mengendalikan setback/kick/ban
runtime. Config engine upstream tetap menentukan perilakunya. Mode observasi/enforcement native
tersendiri masih perlu implementasi dan pengujian; jangan menyimpulkan mitigasi otomatis sudah
aman dari file rancangan. Tidak ada klaim tuning limit yang terukur.

Build pribadi dapat tergantikan oleh updater publik. Pada server pribadi, gunakan
`misc.auto_update.apply_mode = "notify"` di global TOML bila ingin mempertahankan JAR custom.
File konfigurasi operator tidak diubah otomatis oleh tooling integrasi ini.

## Build privat

Workspace:
`.private-intave/workspaces/c67af7f4fca5d09f68e59f7996c6b15b2a560455/`.
`native-port.json` mencatat 177 file hasil port/generated beserta SHA-256 dan penghapusan entrypoint
lama. Import/port ulang mempertahankan edit lokal. `doctor` memeriksa ledger aktif dan local
library hashes, serta menolak file Java/resource/JAR tambahan yang tidak tercatat dan symlink
escape. Resource yang dipulihkan harus termasuk inventory Git pin; test/JAR gate tetap
memeriksa hash-nya. Arsip asli diverifikasi saat persiapan dan saat probe reproduksi.

Port merender seluruh perubahan sebelum menulis source. Kegagalan transformasi tidak merename
entrypoint atau meninggalkan perubahan separuh jadi; error tulis biasa memulihkan file yang
sudah disentuh. Ledger baru diterbitkan setelah semua perubahan source selesai dan setiap hash
hasil tulis cocok dengan hasil render. Matcher method
mengabaikan brace dalam literal/comment Java dan menolak signature ganda/body tidak seimbang.
Ini bukan jaminan transaksi terhadap penghentian paksa proses atau kegagalan rollback pada
filesystem yang tetap tidak dapat ditulis.

`--refresh-port` secara eksplisit memperbarui hasil port dari arsip asli yang terverifikasi dan
template saat ini. Hash/closed inventory workspace aktif harus cocok sebelum render; edit
operator ditolak dan dipertahankan. Refresh menolak perubahan kontrak penghapusan/drop input,
memeriksa seluruh hash baru sebelum ledger, dan rollback juga memulihkan ledger yang sempat
ditulis separuh. Refresh kedua tanpa perubahan tidak menulis ulang source/ledger.

Prasyarat: toolchain SourbyCraft terpin, JDK 25, jaringan/cache artifact lengkap. Profile memakai
API/server SourbyCraft 26.2; JAR NMS historis hanya compile compatibility. Dependency runtime
managed berasal dari plan versi upstream; versi efektif/transitif wajib dicatat dari resolve
Gradle, karena dependency shared dapat memilih versi server yang lebih baru.
Group runtime `ac.intave` memakai Maven Central sesuai build/publishing upstream, terpisah dari
Maven Local yang diwajibkan untuk SourbyClip/SourbyPatcher. Probe source API tidak memasang JAR
lokal dengan nama artifact resmi atau mengubah dependency runtime menjadi source hasil probe.

```bash
python3 scripts/integrate_private_intave.py
# Untuk workspace yang sudah di-port, setelah template integrasi diperbaiki:
python3 scripts/integrate_private_intave.py --refresh-port
python3 scripts/integrate_private_intave.py --verify-reproducibility
# Opsional: pulihkan resource dari arsip lokal hanya jika setiap byte cocok dengan Git pin.
python3 scripts/private_intave_workspace.py recover-fixtures --archive /path/to/Intave.jar
python3 scripts/private_intave_workspace.py fetch-fixtures
python3 scripts/private_intave_workspace.py resolve
python3 scripts/private_intave_workspace.py doctor --gradle-cache .private-intave/gradle-cache
./gradlew --gradle-user-home .private-intave/gradle-cache applyAllPatches
./gradlew --gradle-user-home .private-intave/gradle-cache -PincludePrivateIntave=true \
  --write-verification-metadata sha256 :sourbycraft-server:compileJava
./gradlew --gradle-user-home .private-intave/gradle-cache -PincludePrivateIntave=true \
  :sourbyapi:test :sourbycraft-server:test
./gradlew --gradle-user-home .private-intave/gradle-cache -PincludePrivateIntave=true slimServerJar
```

`resolve` menggunakan project baseline privat untuk dependency/fixture preparation. Setelah port,
compile/test standalone ditolak: source native perlu source set server. Profile memasukkan source
test privat dan paket test secara eksplisit, karena include server biasa hanya TestSuite. Task
test/JAR memverifikasi seluruh 22 blob resource/replay inventory awal, termasuk JNI, sebelum berjalan.
Kini dua JNI Linux telah dipulihkan dengan hash yang sama; 20 blob masih belum tersedia.
Tes upstream asli tetap disimpan di `verified-upstream/`; baseline terpisah dan adaptasi native
masih harus diverifikasi dengan jumlah test aktual, tanpa menganggap source test berarti tes lulus.
Jangan membuat fixture kosong atau memakai API JAR lama sebagai
substitusi dependency terbaru untuk melewati gate.

Profile memverifikasi source/library hashes sebelum compile/resource/JAR tasks, mempertahankan
lisensi di `META-INF/licenses/intave`, dan menambah manifest `SourbyCraft-Private-Intave` dengan
commit penuh. Output build berada di `build/libs/` lokal, bukan artifact rilis publik. `release=pre`
dan identitas Build 47 tidak diubah. Tidak ada build pribadi yang sudah dihasilkan pada audit ini.

Setelah semua gate build dan correctness tersedia, boot di server uji pribadi tanpa JAR
Intave/ProtocolLib di `plugins/`:

```bash
java -Dsourbycraft.intave.enabled=true -Xmx2G -jar build/libs/SourbyCraft-slim.jar --nogui
```

## Bukti verifikasi dan batasnya

Auto-provision ProtocolLib eksternal mengikuti [jalur launcher aktif](../development/PROTOCOLLIB.md).
Build privat yang membundel API native atau flag enable native melewati installer eksternal
agar tidak membuat backend paket kedua. JAR operator tetap dipertahankan; coexistence masih
belum tersedia dan provider native menolak ProtocolLib plugin yang bertabrakan.

- **12 tes lifecycle controller lulus**: default disabled, missing provider, deferred readiness,
  gagal startup/late initialization, cleanup sekali, kegagalan callback, terminal stop,
  drain callback owner dan thread-local filter restoration.
- **22 tes workspace/tooling lulus**: provenance/pin, path escape, preservation, overlay hashes,
  entrypoint lama, license, binary replay fidelity, input tambahan, source symlink, recovery arsip
  dengan hash pin, unduhan hash mismatch dan cache offline.
- **12 tes migrasi source lulus**: validasi lengkap sebelum perubahan, preservation saat target
  salah, revision/legacy-entrypoint checks, publikasi dan rerun tanpa perubahan, rollback error
  tulis biasa/hash hasil tulis berbeda serta matcher brace/comment/literal Java. Refresh diuji
  untuk preservation edit lokal, idempotensi dan rollback source/ledger parsial; pemetaan API
  hanya menyentuh token kode, bukan literal atau notice.
- Reproduksi dari arsip asli menghasilkan **177 hash port yang sama**, tanpa menulis workspace
  aktif. Fix import eksplisit `Input` juga tercakup agar tidak bentrok dengan Bukkit modern.
- Probe API 26.2 **inventory, fallback AIR dan pemetaan fluid lulus** dengan item/block state
  Minecraft nyata, tanpa pengganti interface NMS. Inventory mencakup 43 slot, mapping equipment,
  storage-only/all-slot removal dan remainder, iterator, array terpisah serta invalid-input
  preservation. Metode mock legacy lain belum diaudit oleh probe ini. Fallback AIR memakai
  block data/collision/sound/piston state nyata dan menolak operasi dunia yang tidak tersedia;
  subclass dengan tipe non-AIR harus menyediakan adapter state sendiri. Probe fluid memeriksa
  water/lava level 0–15, falling/height/source, waterlogged dan state null. Inisialisasi komponen
  memakai vanilla registries; proses berjalan di direktori temporer. Ini bukan replay movement,
  akses dunia/region, indeks seluruh material saat boot, atau efektivitas anticheat.
- Probe mapped state tambahan memeriksa seluruh state AIR/STONE/WATER/OAK_STAIRS/OAK_FENCE,
  ID unik/repeatable, zero = default asli, boolean/integer/enum property, level water 0–15,
  semua collision boxes stairs dan offset (-31,70,33). `NativeBlockView` dicompile terhadap
  BlockGetter/ServerLevel 26.2 asli, tetapi tidak dijalankan dengan world/region sungguhan.
- `BlockAccess`, `DrillResolver`, `VariantIndex` dan `ConversionBridges` kini memasang adapter
  mapped langsung, tanpa Patchy untuk jalur ini. `v14BlockAccessor`/`v17b1ShapeDrill` hanya
  mempertahankan nama internal dan mendelegasikan native 26.2. Lookup variant tak dikenal
  melempar error, bukan memilih zero. Properti gerak/solid memakai default BlockState native;
  ini bukan bukti semua simulasi fluid/blok sudah benar.
- `NativeBlockView` memeriksa owner pada setiap lookup, termasuk neighbor yang diminta shape,
  dan mengambil full chunk yang sudah loaded tanpa load/join. Chunk yang tidak tersedia ditolak;
  hanya posisi sah di luar build height mengembalikan AIR. Block-entity query ditolak bila
  membutuhkan snapshot karena bahkan LevelChunk CHECK bisa mendeserialisasi/membuat entity.
  `VolatileBlockAccess` mempertahankan hasil cache yang tersedia, tetapi cache miss/world reads
  memakai guard; emergency map/fallback AIR/shape kosong/variant zero dihapus. **Callback Netty
  yang membutuhkan world langsung akan ditolak bila tidak memiliki region**. Routing/snapshot
  dan audit ownership/cache/subclass non-AIR tetap pekerjaan yang belum selesai, sehingga
  perubahan guard ini tidak membuat anticheat siap produksi.
- `Fluids.setup()` native memakai resolver mapped langsung, tanpa Patchy classloader rewrite
  untuk resolver ini, dan kegagalan indeks kini melempar exception. `v18b2FluidResolver` hanya
  mempertahankan nama internal dengan delegasi native 26.2; build ini tidak mendukung server
  versi lama. Adapter lain yang memakai Patchy masih perlu diaudit. Propagasi startup penuh
  belum dijalankan karena build engine belum lulus.
- **147 source Java API resmi berhasil dicompile** dengan JDK 25 terhadap classpath server
  lokal. Source tag exact, tetapi belum membuktikan resolve artifact/transitif Maven, test
  upstream, engine compile atau boot.
- **4 probe paket lulus** terhadap `Connection` hasil feature patch dan Netty EmbeddedChannel:
  ready-head blocking/urutan nested extras, pembatalan queue, native filter=true, serta failed
  write. Finish callback menerima success/failure future atau null untuk pembatalan, sekali;
  paket reguler berikutnya kembali memakai filter. Probe mereproduksi `void future` sebelum
  perbaikan. Ini tidak menjalankan engine/check Intave atau server Minecraft penuh.
- Wiring build privat **lolos static typecheck** dengan compiler Kotlin/API Gradle 9.8.0 lokal.
  Probe membungkus konfigurasi dalam receiver `Project` dan memakai compiler SAM Gradle;
  helper inventory filesystem dari script asli juga diuji terhadap input tercatat, input
  tambahan, nested JAR dengan nama sama, dan symlink. Probe tidak mengevaluasi konfigurasi
  project, resolve dependency atau memverifikasi configuration-cache reuse.
- Compiler JDK 25 terhadap JAR lokal SourbyCraft/API 26.2 + runtime libraries + ProtocolLib pin
  berhasil untuk controller, command diagnosis, NativeService, NativeProtocol, TickEnd dan Connection hasil patch.
  Ini **bukan** compile IntaveEngine, NativeEngineProvider, seluruh server atau boot.
- Patch apply-check serta kebijakan patch/concurrency/toolchain/engine/independence lulus.
  Policy toolchain mengabaikan arsip provenance privat, tetapi tetap memeriksa target Java
  project workspace yang dapat dieksekusi.
- Doctor menemukan **0 perubahan source/library yang tidak tercatat**, tetapi 16 dependency
  baseline langsung belum ada dalam cache yang diperiksa dan **20 resource biner belum tersedia**
  (15 recording, 4 JNI library, 1 marker packaging). Dua JNI Linux dipulihkan dari JAR lokal
  dengan Git blob exact dan SHA-256 arsip tercatat di `fixture-recovery.json`.
  Kehadiran source API atau cache pun bukan resolve artifact/transitif.
- Diagnostic reproducible `probe_private_intave_engine.py` untuk 1.191 source engine/generated
  plus 147 source API tag terverifikasi masih **gagal**. Diagnostic terbaru mencatat 98 error
  pada Certificate/FakePlayerFactory/FakeWorldFactory/GeyserTrustFactorResolver akibat dependency
  Bouncy Castle, Byte Buddy dan Floodgate yang belum tersedia. Tidak ada diagnostic adapter
  blok/shape/fluid atau nama Bukkit lama yang terlihat pada percobaan ini.
  Angka ini bergantung pada classpath dan urutan javac, bukan persentase progres build.
  Probe terlebih dahulu menyalin/menerapkan Connection patch nyata dan memasang class hasil
  compile sebelum JAR server, sehingga tidak memakai Connection lama yang belum punya sendNative.
  Classpath probe berisi
  artifact server/API 26.2 dan runtime dari cache Gradle lokal, ditambah legacy
  NMS compile compatibility; ini bukan dependency resolution Gradle. Tidak ada klaim bahwa
  menghilangkan pembatasan socket/jaringan saja akan langsung menghasilkan engine yang lulus.
- Probe root `-PincludePrivateIntave=true :sourbycraft-server:compileJava` dengan Gradle 9.8/JDK 25
  berhenti sebelum konfigurasi: FileLockContentionHandler tidak dapat
  membuka socket (`SocketException: Operation not permitted`). Terminal gagal DNS GitHub/Maven.
  Eksekusi root private profile/configuration cache, engine compile, artifact packaging dan
  seluruh tes upstream tetap **belum terverifikasi**; static typecheck terpisah di atas bukan
  bukti build tersebut berhasil.
  Retry wrapper juga ditolak saat menulis lock cache global yang read-only; memakai distribusi
  Gradle terpasang dengan cache workspace tetap gagal pada socket sebelum konfigurasi project.

Probe tambahan dapat diulang setelah materialized sources dan classpath 26.2 tersedia:

```bash
python3 scripts/probe_private_intave_gradle.py --gradle-home /path/to/gradle-9.8.0
python3 scripts/probe_native_intave_packets.py --classpath-file /path/to/server-runtime-classpath.txt
python3 scripts/probe_private_intave_apis.py --classpath-file /path/to/server-runtime-classpath.txt
python3 scripts/probe_private_intave_inventory.py --classpath-file /path/to/server-runtime-classpath.txt --java-home /path/to/jdk-25
python3 scripts/probe_private_intave_engine.py --classpath-file /path/to/server-runtime-classpath.txt --java-home /path/to/jdk-25
```

File classpath berisi satu baris path JAR server/API SourbyCraft beserta runtime libraries,
dipisahkan separator classpath OS. Probe menyalin `Connection` ke direktori sementara dan
menerapkan hanya bagiannya dari patch bila sumber belum mempunyai hook native. JVM terpisah
menjalankan setiap skenario; repo materialized dan config server tidak diubah. Output JSON
menyertakan hash patch/source dan secara eksplisit menandai engine/boot belum diverifikasi.

`verification-matrix.json` masih menandai 10 gate end-to-end PENDING. Source dan unit test terpisah
bukan bukti gate native runtime/check selesai. Gate tersisa: exact dependency/transitive provenance,
full engine/server compile, nonempty fixture suite, boot tanpa plugin, legal/illegal replays,
ViaVersion translation, velocity/vehicle/teleport/respawn, jitter/burst, region merge/split,
join/quit/migration, clean shutdown/restart serta CPU/allocation/queue measurement dengan workload,
hardware dan config tercatat. Tidak ada hasil false-positive atau tingkat efektivitas saat ini.
