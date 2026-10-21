# Bootstrap download audit

AURORA-TASKS I: downloader timeouts, SHA/cache validation, bounded concurrency, retry and
failure handling, first-boot recovery and offline-after-success. Audited 2026-09-27.

In scope: the two downloads in this repository that run before the server starts, `bootstrap/LibDownloader`
(libraries the slim jar omits) and `bootstrap/PluginProvisioner` (the pinned ViaVersion and ViaBackwards jars).
**Historical scope (2026-09-27):** SourbyClip was excluded from that utility audit. The
2026-10-05 source audit of private local upgrade candidates is recorded separately below.
The historic CI probes do not qualify these new candidates.

## Findings and fixes

| Concern | Before | Now | Evidence |
| --- | --- | --- | --- |
| Connect timeout | 15 s | 15 s | `HttpClient.connectTimeout` |
| Stalled body | `HttpRequest.timeout` bounds only the response headers, so a body stalling after them could hang boot indefinitely | Whole transfer bounded by `TRANSFER_DEADLINE` (10 min): `sendAsync` + `get(deadline)`, cancelled on expiry | `LibDownloader.httpsFetch` |
| Oversized body | Written in full, then compared with the pinned size, so it could fill the disk | Cut off as soon as it passes the pinned size (`LimitedSubscriber`) | `LibDownloaderTest.theHttpsBodyIsCutOffOnceItExceedsThePinnedSize` |
| Retry | None: one transient failure meant `exit 3` | 3 attempts per library, 2 s then 4 s apart. Refusals (non-https, path escape) are not retried | `aTransientFailureIsRetriedWithBackoff`, `nonHttpsAndEscapingPathsAreRefusedWithoutRetry` |
| Partial files | A failure during the transfer left `.tmp` behind | Every attempt deletes its `.tmp`; the destination is replaced only by a verified file | `everyAttemptFailingKeepsNothingAndReportsTheLastError` |
| Hash / size | Checked before install | Unchanged; wrong bytes are never installed | `wrongBytesAreNeverInstalled` |
| Cache | Existing file used only if its SHA-256 matches | Unchanged; a corrupt cached file is re-downloaded | `aCorruptCacheIsReplaced` |
| Offline after success | Cache hit touches no network | Unchanged, now tested | `aVerifiedCacheIsReusedWithoutTheNetwork` |
| Concurrency | One download at a time, manifest order | Unchanged | the former `SourbyBootstrap` loop (deleted in SEC-4); library downloads now run in SourbyClip and its concurrency was not re-audited here |
| Plugin jars | Same stall and size issues as above | Use the same bounded transport. Failures still never block boot (the provisioner is wrapped) | `PluginProvisioner` |

## Still open

- CI step "Bootstrap failure recovery and offline boot" (added 2026-09-27) runs a fresh
  server three times: with no network (a private network namespace with only loopback) it
  must fail within 2 minutes, name what it could not download and leave no `.tmp`; online it
  must reach `Done`; with no network again it must reach `Done` without downloading.
  On a fresh directory the first network use is SourbyClip fetching the Mojang server jar.
  Current SourbyClip (3.1.2) logs `INFO]: [Sourbyclip] Downloading mojang_26.2.jar` and, when the
  network is down, `ERROR]: [Sourbyclip] Failed to download original jar` followed by
  `java.io.IOException: Cannot prepare original JAR mojang_26.2.jar at cache/mojang_26.2.jar; source: https://piston-data.mojang.com/...`.
  The step asserts these strings (CI run 39398396573 logs). A cached offline boot prints
  `[4/4] Classpath prepared: N entries`.
- **CI run 450 (`52568fb0`, 2026-09-27), green.** Fresh directory without network: failed with
  SourbyClip's download error and no `.tmp` left. Online: `Done`. Without network again:
  `Done (9.282s)` with nothing downloaded. The only network error in that boot was Mojang's
  `api.minecraftservices.com/publickeys` lookup (logged, not fatal, `online-mode=false`).
- SourbyClip whole-transfer deadline/size cap: **implemented in SourbyClip 3.1.3** (`e5de0f4b`, 2026-10-22, see the section below); pin rolled in the working change, private push pending. Remote cold-download qualification remains open.
- The utility manifest in the historical scope has one URL per library. SourbyClip
  has a separate Maven fallback list; see the current launcher audit below.


## Private launcher source audit — 2026-10-05 local candidates

**IMPLEMENTED; pins rolled since.** At audit time SourbyClip 3.1.1 at
`5b6249c2d85e5f52a7c2687ef44eb92bde47d065` and SourbyPatcher 3.1.0 at
`34e2161686d1f599a7d4efe9a5168d7a91192c75` were local private commits. The official pins have
moved on: `5a3806f1` (2026-10-20, SEC-5) integrates SourbyClip 3.1.2 at `85b4dd70` (`clipVersion`,
`clipSha256`, `build-data/private-toolchain.lock.json`). The findings below were re-checked
against 3.1.2 on 2026-10-20: the launcher still bounds each socket with 30 s connect/read
timeouts only (`DownloadContext.java:61-62`, `MavenDependencyResolver.java:25,885-886,900-901`),
with no whole-transfer deadline and no size cap. See
[PRIVATE-TOOLCHAIN.md](../development/PRIVATE-TOOLCHAIN.md) for artifacts and the diagnostic override command.

Launcher-owned source paths inspected: `DownloadContext`, `VerifiedFile`, `FileEntry`,
`Downloader`, `MavenDependencyResolver`, `Sourbyclip`, `IPUtil` (3.1.1 only; deleted in 3.1.2), `BootstrapWorkers` and `SimpleLogger`.
The audit found no other source-owned executor or common-pool async submission in `java25/src/main/java`.
Re-checked against 3.1.2 (`85b4dd70`, 2026-10-20) by grepping all of `java25/src/main/java` for `Executors.`, `commonPool`, `parallelStream`, `supplyAsync`, `runAsync`, `new Thread` and `.shutdown`: the only thread creators are `BootstrapWorkers.create` (fixed pool, named `SourbyClip-download-N`), `Downloader.downloadOrLoad` (`supplyAsync(..., worker)`, always the explicit pool, never the common pool) and the single `ServerMain` thread in `Sourbyclip.generateThread`.
Third-party internals and server/plugin lifecycle qualification are outside this source audit.

| Surface | Candidate behavior | Evidence |
| --- | --- | --- |
| Original server JAR | 30 s connect/read timeouts; unique staging, complete copy, expected SHA-256 before replacement; prior file preserved on failed HTTP/body/hash attempt; IOException reaches existing source fallback | `DownloadContextTest`: valid stream larger than 8 MiB, HTTP-200 HTML rejection, existing-cache preservation, no partial publish, connection failure |
| Library mirror/extraction | VerifiedFile stages and verifies each response; an invalid hash is a mirror miss; embedded bytes verified too; cache hit requires expected hash | Existing `VerifiedDownloadTest`, `OfflineBootstrapTest`, `ConcurrentHashTest` |
| Download worker ownership | Fixed 4 workers per manifest batch by default, configurable 1–16; finite manifest batch queues; pool drained/joined before original-JAR filesystem closure and before `ServerMain` | `BootstrapWorkersTest`: 32 submitted tasks with two workers, success/failure cleanup and post-close rejection; packaged `LauncherProcessTest` rejects a live download worker at server invocation |
| Location lookup | **Removed in SourbyClip 3.1.2** (`85b4dd70`, SEC-5): no geo-IP lookup, no `IPUtil`, no lookup threads or `HttpClient`; the original-JAR context is `META-INF/download-context` unless `-Dsourbyclip.downloadContext` names another (pure function `DownloadContext.resourceName`). The 3.1.1 behavior (three provider callables, first answer wins) is historical | `DownloadContextSelectionTest` (3.1.2 repo); `IPUtil` and `IPUtilTest` no longer exist |
| Server handoff | One `ServerMain` thread owns reflective invocation; bootstrap workers finish first; arguments preserved and failure cause exits nonzero | Packaged `LauncherProcessTest` plus SourbyCraft `test_clip_launcher.py` using candidate artifact overrides |
| Logging/UI | Immutable DateTimeFormatter, append-only summary/progress, plain ASCII on redirected output; help/identity need no bootstrap; prepare never starts server and rejects a bare launcher | `BootstrapConsoleTest`, `LauncherProcessTest` |
| Toolchain identity | Patcher embeds its expected paperweight version and checks the loaded implementation manifest before applying it; read-only doctor validates SHA/launcher/protocol with configuration-cache reuse | `ToolchainIdentityTest`, `ToolchainDoctorTest`, real SourbyCraft doctor invocation with candidate version/hash overrides |

**Concurrency bound and its limit (3.1.2 source, not a measurement).** At most
`sourbyclip.downloadThreads` (default 4, validated 1-16 before any network or cache activity,
`Sourbyclip.java:55`) downloads run at once, capped at the batch size (`Sourbyclip.java:412`). The
pool is closed by try-with-resources (`Sourbyclip.java:416`), which waits for every already-queued
task; it does not `shutdownNow`. So after one library fails, the remaining queued libraries
still run to completion before the failure surfaces, and the worst-case wall time of a batch is
bounded only by the per-socket 30 s timeouts multiplied by mirrors and entries divided by
workers, not by a whole-transfer deadline. 3.1.3 adds the per-attempt deadline and byte cap
(section at the end), so each entry is now bounded; the pool still drains queued work after a
failure instead of `shutdownNow`.

Settings introduced here (`sourbyclip.console`, `sourbyclip.downloadThreads`) are
**RESTART_REQUIRED**, read during launcher startup/batch setup. They do not tune region or server executors.
`--sourbyclip-prepare` is an explicit one-shot cache/patch operation; the classpath summary does not claim `Done (`.

### Cache, sources and fallback order

- A valid original-JAR cache entry avoids download. Otherwise launcher metadata selects a
  `META-INF/download-context` variant (optional `sourbyclip.downloadContext`, or regional `-cn`).
  On IOException, the existing launcher path tries its default `download-context`.
  `sourbyclip.useMojangSource=true` selects that default directly. URLs and original-JAR filenames
  come from the packaged server metadata; they are not inferred from an artifact name.
- For a library: expected-hash cache hit, then embedded `META-INF/libraries/...`, then the original-JAR
  filesystem, then Maven mirrors. Entries produced by server patches are handled by the patch stage.
- Maven mirrors are tried in this current source order:
  `https://maven.aliyun.com/repository/central`,
  `https://repo.papermc.io/repository/maven-public`,
  `https://repo.menthamc.org/repository/maven-public`,
  `https://repo.spongepowered.org/maven`.
  HTTP failure or SHA-256 mismatch advances to the next mirror. `libraries.list` uses
  `group:artifact:version[:classifier]` and the pinned bootstrap resolver chooses JAR bytes directly.
- `sourbyclip.offline=true` prevents launcher downloads and IP lookup. Valid cache/embedded bytes
  remain usable. Missing/corrupt requirements fail with a path or coordinate; this does not govern
  server/plugin traffic. Identity/help use only the launcher identity; prepare honors existing
  auto-update target resource selection.

### Verification and remaining limits

Temurin 25.0.4.1+1, Gradle 9.8.0, macOS arm64: 29 launcher and 11 active Patcher Java tests pass,
plus 8 SourbyCraft launcher/private-toolchain Python tests with candidate artifact overrides.
Both private JAR hashes match across two local clean builds. The real SourbyCraft diagnostic task
stores and reuses configuration cache. These results are controlled tests/local reproducibility,
not cross-host reproducibility, a remote cold boot, a performance claim or server/plugin qualification.

Still open (re-checked against SourbyClip 3.1.3 / `e5de0f4b`, 2026-10-22): full
POM/dependency content pins; remote cold-download and cached/offline Minecraft boot
qualification. Done since: the private pushes and the official revision/hash rollout (`5a3806f1`).
The previously pinned artifacts still validate unchanged.

## SourbyClip 3.1.3 bounded transfers — 2026-10-22

**IMPLEMENTED in the private repo; pinned in this change; not yet pushed or CI-built.**
SourbyClip `e5de0f4ba2d10fc81e95b0d23069b3b9d59d73bf` (local branch `feat/transfer-deadline`),
jar SHA-256 `56d317b81044d8d618901ef46d5b418de59ffdc9bb8940b6745aa1a732c4435e`. New
`downloader/BoundedTransfer` is the only network path of the launcher (`DownloadContext.download`,
`MavenDependencyResolver.downloadFile`/`downloadString`); HTTP(S) uses `java.net.http.HttpClient`
(HTTP/1.1, normal redirects, default proxy selector), other URL schemes a `URLConnection` with the
same cap and a per-read deadline check.

| Concern | 3.1.2 | 3.1.3 | Evidence (private `BoundedTransferTest`, loopback) |
| --- | --- | --- | --- |
| Whole transfer | 30 s connect/read per socket only; a trickle never ends | Deadline per attempt: 600 s unknown size; known size (`Content-Length`; manifests carry none) `size / 64 KiB/s` clamped 120–1800 s; `sourbyclip.transferDeadlineSeconds` fixes it. Covers headers and body; the exchange is cancelled on expiry | `tricklingSourceHitsTheWholeTransferDeadlineAndIsNotRetried`, `tricklingHeadersAreBoundedToo`, `originalJarTrickleFailsNamingTheSource` |
| Idle | 30 s read timeout | 30 s without data (retryable) | `stalledSourceIsRetriedBoundedTimesThenFails` |
| Size | none | Expected size + 1% (≥ 64 KiB) when known, else `sourbyclip.maxTransferBytes` (256 MiB); POM/metadata ≤ 4 MiB; an oversized `Content-Length` is refused before the body | `endlessStreamIsCutAtTheByteCap`, `declaredLengthAboveTheCapIsRejectedBeforeTheBody`, `expectedSizeCapsTheBodyWithSmallSlack` |
| Single-source retry | none (mirror fallback only; the Mojang jar had one attempt per context) | Transient failures (connect error, idle, truncated body, HTTP 5xx/408/429) retried `sourbyclip.transferRetries` (default 2) times, backoff 1 s doubling; deadline/cap/SHA-256/other 4xx not retried on that source | `transientHttpStatusIsRetriedUntilTheSourceRecovers`, `truncatedBodyIsRetriedAndNeverPublished`, `notFoundAndHashMismatchAreNotRetried`, `originalJarSingleSourceIsRetriedThenDownloaded`, `mavenBootstrapFallsBackFromATricklingRepositoryToAGoodOne` |
| Partial files | staged + SHA-256 | unchanged; aborted transfers remove their temporary file and keep the previous destination | `previousDestinationSurvivesAnAbortedTransfer` and the existing `VerifiedDownloadTest`/`DownloadContextTest` |
| Final failure | non-zero exit naming coordinate / source | unchanged, plus one `Transfer of <what> from <url> failed ...` line per failed attempt | `mavenBootstrapFailureNamesTheCoordinate` |
| Offline | no network | unchanged | `OfflineBootstrapTest` |

All 48 SourbyClip Java tests pass (Temurin 25, Gradle 9.8.0, macOS arm64); `BoundedTransferTest`
passed 3 reruns. Worst case is bounded but not short: per library, 4 mirrors × (one deadline, or
3 attempts × 30 s idle plus backoff); per batch divided by `sourbyclip.downloadThreads`.
Not covered: real-network/remote Linux cold download, proxies, HTTP/2, a tampered cache in a
booted server.

Local server evidence (2026-10-22, agent test server, macOS arm64, not certified): 2026-10-22 local run (claude-clip worktree, PARTIAL, slim jar `c730ebf4…06ed052` with SourbyClip 3.1.3 `e5de0f4b`, built from HEAD `5851c8e7` plus the uncommitted re-pin, not certified): `clip313-cold-2 --no-seed-cache` downloaded the Mojang jar (60.9 MB) + 112 SHA-256-verified entries through the bounded transfer path from the real mirrors (one `Transfer of me.lucko:spark-paper:1.10.180:jar ... HTTP 404; not retrying this source` then the next mirror), classpath 15.9 s, `Done` 17.686 s, stop exit 0, no `.part` left; offline cold boot (`clip313-cold-offline-1`, empty cache) exits 1 naming `cache/mojang_26.2.jar` and its source URL. The
first run (`clip313-cold-1`, an earlier build of the same change) also reached `Done` with stop
exit 0. This exercises only the success path and a 404 mirror miss on a real network; the deadline,
cap and retry paths are covered by the loopback tests above.

