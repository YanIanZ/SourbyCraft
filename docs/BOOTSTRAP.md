# SourbyClip bootstrap — 3.1.3

The official 26.2 build uses SourbyClip 3.1.3 from Maven Local. Its source lives in the
private `YanIanZ/SourbyClip` repository; the approved revision is recorded in
`build-data/private-toolchain.lock.json`, and `gradle.properties` pins the JAR hash.
See [maintainer setup](development/PRIVATE-TOOLCHAIN.md).

## Integrity and cache behavior

- Launcher and ServerMain failures retain their causes and exit nonzero.
- `--sourbyclip-info` reports identity without starting Minecraft.
- `-Dsourbyclip.offline=true` refuses bootstrap downloads. Verified cached or embedded libraries
  remain usable; this does not disable server/plugin networking. (There is no boot-time IP lookup
  to refuse since 3.1.2.)
- Each concurrent hash check owns its digest and uses bounded memory. Native classifiers
  are preserved when converting Gradle library coordinates to repository paths.
- Library hash verification occurs inside the repository retry loop. HTML with HTTP 200
  is a failed attempt when its hash differs, so the next repository is tried.
- Since 3.1.3 every launcher download (original JAR, Maven libraries, POM/metadata) is bounded by
  a **whole-transfer deadline** (600 s when the size is unknown; with a known size — the server's
  `Content-Length`, since `libraries.list` and `download-context` carry no sizes —
  `size / 64 KiB/s` clamped to 120–1800 s), a 30 s idle timeout and a **byte cap**
  (256 MiB by default; an oversized `Content-Length` is refused before the body is read).
  Transient failures (connection error, 30 s without data, truncated body, HTTP 5xx/408/429) are
  retried on the same source up to 2 times (1 s, then 2 s); deadline, cap, SHA-256 and other 4xx
  failures move straight to the next source. The Mojang original JAR has a single source, so it now
  gets up to 3 attempts. Each failed attempt is logged as
  `Transfer of <what> from <url> failed ...`; when every source fails the launcher still exits
  non-zero naming the library coordinate or the original-JAR source. RESTART_REQUIRED launcher
  properties (before `-jar`): `sourbyclip.transferDeadlineSeconds` (1–86400, fixed deadline),
  `sourbyclip.maxTransferBytes`, `sourbyclip.transferRetries` (0–5),
  `sourbyclip.transferBackoffMillis` (0–60000). Invalid values fail at the first transfer.
- Library extraction and downloads use unique sibling temporary files. Only complete,
  verified bytes replace the final file. Failed attempts preserve existing destinations
  and remove their temporary files.

## Verification

Private SourbyClip tests cover offline cache acceptance/rejection, corrupt embedded libraries,
HTTP-200 HTML followed by a valid mirror, all mirrors failing, and destination preservation;
3.1.3 adds `BoundedTransferTest` (trickling body and headers, stalled, endless chunked, oversized
`Content-Length`, truncated body, flaky 503, 404, mirror fallback from a trickling repository,
original-JAR retry and deadline). These are in-process loopback servers, not real networks.
The public process probes cover the packaged launcher and large transfers:

```sh
java --class-path "$HOME/.m2/repository/dev/iyanz/sourbyclip/3.1.3/sourbyclip-3.1.3.jar" \
  scripts/fixtures/ClipDownloadProbe.java build/clip-download-probe
python3.12 scripts/verify_bootstrap.py build/libs/SourbyCraft-slim.jar \
  --output build/bootstrap-new
```

The second command requires a new output directory. It records a cold boot, a clean stop,
and an offline cached restart, with JSON results, separate logs and JFR files. A successful
local test is not a performance benchmark, a long soak, or proof of every network-failure mode.

## Official CI

Trusted pushes/manual runs retrieve pinned private tools using read-only deploy keys and
publish them only to Maven Local. Public pull requests do not receive private build access and get
no Gradle build, test or boot; those run after merge. Build, server tests, the cold boot with JFR,
the bootstrap failure/offline boot step and the Docker image build run in one job. Intermediate
JARs and private Gradle caches are not uploaded publicly. A server JAR and checksum are published
to Releases only by a push or manual run on `26.2` (or a `hotfix/26.2-r<N>.<M>` branch) while
`release=true|pre` and that build's tag does not exist yet, after the build job succeeded. The
retired NMS harness is not a release gate.

The 3.0.22 launcher exit-code/transfer fixes remain included. The 3.0.23 integrity check
exposed the HTTP-200 mirror failure documented in
[the incident report](architecture/slim-jar-bootstrap-failure.md); 3.0.26 moves that check
inside retry and stages files before publication. 3.1.2 removes the boot-time geo-IP lookup
(no `IPUtil`; a default download context is used unless the operator names another) and
carries the 3.1.1 bootstrap terminal controls, worker lifecycle and `--sourbyclip-prepare`.
3.1.3 adds the whole-transfer deadline, byte cap and bounded single-source retry above.
