# Tester Owner — SourbyCraft 26.2 Build 50

Pemilik pengujian: **YanIanZ**. Kandidat: branch `fix/build-50-readiness`, target PR `26.2`.
Status handoff: **NEEDS OWNER TEST**. Agent tidak menjalankan build, test suite, server,
benchmark, atau failure injection. Review source/diff bukan bukti compile atau stabilitas.
Hasil AWF/MultiProfile 24 Oktober berlaku untuk tree lama; ulangi bagian yang berubah.

## 1. Siapkan kandidat yang tepat

1. Backup dunia, folder profil, konfigurasi, Redis/AOF, dan jar sebelum pengujian.
2. Gunakan salinan server terisolasi; catat SHA commit, SHA-256 jar, Java 25, OS,
   CPU/RAM/limit container, JVM flags, plugin beserta versi dan hash konfigurasi.
3. Ikuti [private toolchain](development/PRIVATE-TOOLCHAIN.md). Pada checkout kandidat:

```bash
git rev-parse HEAD
bash scripts/setup_metal.sh
./gradlew applyAllPatches
./gradlew :sourbycraft-server:compileJava
./gradlew slimServerJar -PsourbyBuild=50
```

4. Bila owner ingin menjalankan suite regresi sebelum tes manual:

```bash
./gradlew -PincludeTestPlugin=true :sourbyapi:test :sourbycraft-server:test :test-plugin:test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/patch_policy.py
python3 scripts/baseline_policy.py
python3 scripts/patch_surface.py --check
```

5. Jangan menafsirkan CI PR hijau sebagai build Java berhasil. Pipeline PR tidak membangun jar;
   pipeline push memakai toolchain privat. Branch PR menghasilkan DEV/EXP, bukan identitas REL.
6. Jalankan server uji, tunggu `Done (` dan ringkasan boot; cek `/ver`, `/perf`, `/awf`.
   Catat semua stage DEGRADED/FAILED. Jangan promosikan `release=pre` sebelum gate selesai.

## 2. Regresi wajib pada perubahan ini

Semua baris di bawah **NOT RUN**. Crash/injeksi kegagalan hanya pada salinan disposable.

| ID | Langkah owner | Hasil wajib |
| --- | --- | --- |
| B50-01 patch/build | Apply patch bersih, compile, paketkan. Fokus MC feature 0033 dan `WorldUnloadDrain`. | Tidak ada reject, simbol hilang, atau patch yang terhapus saat rebuild. |
| B50-02 save barrier | Dunia AWF FILE lalu Redis: ubah blok/container/entity/POI pada beberapa region, `/awf save <world>`, tunggu completion, kill JVM, restart. | Semua perubahan sebelum save tersimpan; kegagalan backend tidak menghasilkan completion sukses palsu. Ulangi sambil generate/unload chunk. |
| B50-03 unload gagal | Dunia bukan overworld, tanpa pemain; buat dirty chunk lalu putus Redis sebelum `/awf unload <world>`. | Hasil FAILED/exception, bukan SUCCESS, bila final commit tidak terkonfirmasi. Log storage penyebab ada. Restore backend lalu restart untuk memeriksa checkpoint. Failure bukan jaminan dunia masih loaded. |
| B50-04 unload normal/nosave | Ulangi unload sehat, dunia tanpa region, dan `nosave`; muat ulang. | Save mempertahankan marker; nosave mempertahankan checkpoint terakhir saja; callback selalu selesai. Tidak ada handle/lease tertahan di luar TTL. |
| B50-05 slot race | Melalui plugin uji, kirim 20 `createProfile` serentak untuk satu akun yang punya 3 slot. Ulangi saat switch/delete. | Maksimal 3 berhasil, semua profil sukses ada dalam daftar akun, tidak ada lost update. Konflik transition ditolak. |
| B50-06 stale capture | Plugin uji menunda completion `PlayerStatePort.capture` pada fixture, atau fixture Java `ProfileEngineTest`; mulai save A, switch A→B→A, baru lepaskan capture. | Save lama gagal; revision akun berbeda meski UUID profil aktif kembali A. Item/state B tidak masuk snapshot A. Manual tanpa seam tidak menjamin race ini terpicu. |
| B50-07 lease expiry | Dengan mode standalone dan provider Redis, tunda capture melewati TTL lalu ambil claim baru; lanjutkan save lama. | Claim lama ditolak sebelum snapshot ditulis. Ini bukan pembuktian fencing lintas node. |
| B50-08 recovery race | Siapkan journal incomplete, jalankan recover bersamaan dengan switch, save, delete, dan recover kedua pada akun sama. | Hanya satu transition diterima; penolakan jelas, tidak ada perubahan akun/snapshot silang. Setelah selesai/gagal, reservation dilepas. |
| B50-09 bind | Buat dua profil. Pada profil inactive: `/awf multiprofile bind <uuid> island_b50 <template>`, inspect, restart, switch ke profil itu. | Binding bertahan, dunia dibuat dari template/loaded ketika switch; binding profil aktif/claimed/deleted atau saat transition ditolak. |
| B50-10 izin/input | Jalankan bind/unbind tanpa permission admin; coba `../world`, nama kapital, UUID invalid; API map >16 entries. | Ditolak tanpa menulis binding atau mengakses folder di luar AWF. API hanya untuk plugin terpercaya, bukan batas sandbox. |
| B50-11 unbind | Switch away, tunggu claim release, `/awf multiprofile unbind <uuid>`. | Binding kosong; dunia dan template tidak dihapus. Bind CLI mengganti seluruh binding dengan satu PRIMARY; multiworld memakai API. |
| B50-12 idle retry | Set idle timeout rendah, buat unload pertama gagal (misalnya pemain masih di dunia), lalu keluarkan pemain dan tunggu interval berikutnya. | Retry terjadi; count hanya unload sukses, tidak menghitung kandidat yang gagal/ditouch ulang. |
| B50-13 distributed refusal | Set enabled=true, mode=distributed dengan fabric valid, provider Redis dan fencing=true. Restart. | MultiProfile gagal start dengan alasan FILE tidak mendukung atomic fencing, service tidak terdaftar. Kembalikan standalone dengan direktori eksklusif per server. |
| B50-14 bounded record | Pada salinan folder profil: record terlalu besar/truncated/checksum salah; ulangi pembacaan file yang tumbuh melewati batas. | IO_FAILURE, tidak mengalokasikan seluruh file tak terbatas, data lama tidak ditimpa otomatis. |
| B50-15 shutdown/contention | Banyak akun create/save/switch bersamaan, AWF load, kemudian stop. | Drain selesai, tidak deadlock/starvation STORAGE, snapshot terakhir terkonfirmasi. Bandingkan queue wait dan p95/p99; serialisasi metadata dapat menambah latensi. |

B50-06/07 membutuhkan seam/fixture agar deterministik. Jika owner hanya menjalankan gameplay,
tandai **BLOCKED (race tidak terpicu)**, bukan PASSED.

## 3. Matriks seluruh Build 50

Lanjutkan prosedur lengkap di [owner index](testing/owner/00-TESTING-INDEX.md):
AWF-001…011, MP-001…009, RF-001…006, APF-001…005, BR-001/002, RG-001,
SC-001, CH-001/002, NW-001, SM-001, UP-001, dan failure recovery.
MP-010 menjadi **BLOCKED BY IMPLEMENTATION**, bukan sekadar kekurangan dua node.
AB-002 memerlukan peer Leviathan; tanpa peer hasilnya BLOCKED.

Uji bridge dengan `aurora.bridge.thread-checks: enforced` untuk gate keselamatan.
Default `relaxed` yang merupakan keputusan owner sebelumnya tetap berisiko dan tidak qualified.
Async pathfinding, MultiProfile dan Redis Fabric tetap opt-in. Tidak ada klaim performa baru.

## 4. Bukti dan keputusan rilis

Gunakan [lembar hasil](testing/owner/06-RESULTS-TEMPLATE.md) dan
[laporan bug](testing/owner/07-BUG-REPORT-TEMPLATE.md). Satu hasil memuat:
ID, commit, jar hash, konfigurasi, langkah, expected/actual, timestamp, log, dump/JFR bila relevan,
status PASS/FAIL/BLOCKED dan nama owner. Rahasiakan token, Redis URI/password, data pemain.

Hentikan promosi jika ada duplikasi/kehilangan item, corruption, unconfirmed save, crash,
callback menggantung, queue/heap terus tumbuh, atau guard ownership baru.
Ulangi soak minimal 2 jam dengan klien nyata dan plugin representatif, restart, crash recovery,
dan A/B tersertifikasi sesuai [panduan performa](testing/owner/04-PERFORMANCE-BENCHMARK-GUIDE.md).

Rollback: hentikan kandidat, simpan bukti dan backup kandidat, pulihkan **jar + data + config**
yang cocok dari backup. Jangan membuka data format baru dengan jar lama tanpa runbook migrasi.
Daftar penutupan pekerjaan parsial: [Build 50 readiness](releases/BUILD-50-READINESS.md).
