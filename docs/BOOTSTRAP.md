# SourbyClip bootstrap — 3.0.24

The official 26.2 build uses SourbyClip 3.0.24 from Maven Local. Its source lives in the
private `YanIanZ/SourbyClip` repository; the approved revision is recorded in
`build-data/private-toolchain.lock.json`, and `gradle.properties` pins the JAR hash.
See [maintainer setup](development/PRIVATE-TOOLCHAIN.md).

## Integrity and cache behavior

- Launcher and ServerMain failures retain their causes and exit nonzero.
- `--sourbyclip-info` reports identity without starting Minecraft.
- `-Dsourbyclip.offline=true` refuses bootstrap downloads and IP lookup. Verified cached
  or embedded libraries remain usable; this does not disable server/plugin networking.
- Library hash verification occurs inside the repository retry loop. HTML with HTTP 200
  is a failed attempt when its hash differs, so the next repository is tried.
- Library extraction and downloads use unique sibling temporary files. Only complete,
  verified bytes replace the final file. Failed attempts preserve existing destinations
  and remove their temporary files.

## Verification

Private SourbyClip tests cover offline cache acceptance/rejection, corrupt embedded libraries,
HTTP-200 HTML followed by a valid mirror, all mirrors failing, and destination preservation.
The public process probes cover the packaged launcher and large transfers:

```sh
java --class-path "$HOME/.m2/repository/dev/iyanz/sourbyclip/3.0.24/sourbyclip-3.0.24.jar" \
  scripts/fixtures/ClipDownloadProbe.java build/clip-download-probe
python3.12 scripts/verify_bootstrap.py build/libs/SourbyCraft-slim.jar \
  --output build/bootstrap-new
```

The second command requires a new output directory. It records a cold boot, a clean stop,
and an offline cached restart, with JSON results, separate logs and JFR files. A successful
local test is not a performance benchmark, a long soak, or proof of every network-failure mode.

## Official CI

Trusted pushes/manual runs retrieve pinned private tools using read-only deploy keys and
publish them only to Maven Local. Public pull requests do not receive private build access.
Build, server tests, cold boot/JFR and Docker validation run on one runner. Intermediate JARs
and private Gradle caches are not uploaded publicly. Only a successful release-branch run
publishes a server JAR and checksum to Releases. The retired NMS harness is not a release gate.

The 3.0.22 launcher exit-code/transfer fixes remain included. The 3.0.23 integrity check
exposed the HTTP-200 mirror failure documented in
[the incident report](architecture/slim-jar-bootstrap-failure.md); 3.0.24 moves that check
inside retry and stages files before publication.
