# Spark configuration reporting

SourbyCraft runs the spark that Paper bundles (`me.lucko:spark-paper`) on a region-threading spark
platform that is SourbyCraft-owned engine source (`dev.iyanz.aurora.engine.spark`; it came from
Canvas and has been owned source since the 2026-10-05 migration). Two classes of the bundled spark
are also kept in this tree as edited copies (*Updating Spark* below); the rest of the profiler is
the bundled spark, unchanged. `FoliaSparkPlugin.createServerConfigProvider()` delegates to the
Sourby-owned `SourbyServerConfigProvider`.

The `sourbycraft/` report group contains `global.yml` from
`sourbycraft_config/sourbycraft_global_config.yml`, `aurora.yml` from
`sourbycraft_config/aurora.yml` (every Aurora setting, including the bridge, scheduler and network
keys), and `security.yml` from `sourbycraft-security.yml`. The first two are read with the YAML
parser; on a server whose old `*.toml` has not been converted yet, that file is reported under the
same entry name with the TOML parser. The `aurora/` group holds the engine files
`aurora-server.yml` and `aurora-worlds.yml` and, per world, the `canvas-patch.yml` the engine
merges, read from the world's dimension storage folder (`<world>.yml` entry). Missing files are
omitted. Collection reads files and builds report JSON; it does not seed defaults, migrate files,
or save configuration.

## Aurora runtime metadata

`FoliaSparkPlugin.createExtraMetadataProvider()` returns `SourbyMetadataProvider` (combined with the
thread census below), which adds one
`sourbycraft` object to each report's extra platform metadata:

- `build` — identity, build, codename, Minecraft version, engine;
- `runtime` — Aurora state and processor count;
- `bridge` — mode, bridged plugins, quarantined plugins, fatal violations;
- `governor` — each used lane's threads, queue and rejections;
- `switches` — async pathfinding, lane sampling, network counters, `aurora.cpu.cores`;
- `startup` — total time to ready and start class;
- `slowestRegion` — the metrics collector's last pick over its 5-second window, the row
  `/perf region` shows: `window` (`5s`), `worldId`, `regionId`, `generationId` (Aurora registry ids;
  no world name or coordinates, which that record does not track), `averageMspt` and `maximumMspt`
  (only when finite) and `samples`; `state: none-measured` before the first measurement.

Every value is an in-memory read. No configuration strings are included, so there is nothing here
for secret filtering to catch. A section that throws is reported as `"unavailable"` instead of
failing the report. Whether spark.lucko.me renders extra platform metadata, and how, is part of
the open web-viewer verification.

## World and tick statistics

The platform's providers decide what spark's health report and viewer show for worlds and ticks.
The behaviour below changed in PR #62: code written and statically reviewed; not built; not
tested by the owner ([PM matrix](releases/BUILD-50-PM-MATRIX.md) PM-245, PM-275, PM-276, PM-306).

- **Tile entities.** Outside a region profile, the count is the sum of the *ticking* block entities
  that each region publishes at the end of its tick (`World#getTickableTileEntityCount`), read with
  no thread hop, so it is as old as each region's last tick and lower than a count of every block
  entity. Inside a region profile it is every block entity in the profiled region's ticking chunks.
  Upstream spark on Paper counts all block entities; before PR #62 this read a constant 0.
- **TPS.** Spark shows the worst region's TPS, like `/tps` and `/perf`. Only when there is no
  active region at all (an empty server, or before the first metrics collection) does it fall back
  to the global tick's TPS, and to the target TPS while that has no sample either. With active
  regions that completed no tick in the window (a stalled region) the value stays NaN, which spark
  shows as 0.0.
- **MSPT percentiles.** p95 and p99 are histogram estimates bounded by the measured maximum.
  Spark's `percentile()` answers 0 (minimum), 0.5, 0.95, 0.99 and 1 (maximum); any other
  percentile is NaN (not measured).
- **World statistics poll.** Outside a region profile, one poll queues one task per region (not
  one per loaded chunk) and has one 5-second deadline for the whole poll. While the previous poll's
  tasks have not run and its worlds are still loaded, a new poll returns that previous result
  instead of queueing more, so counts can be up to one poll old. A completed poll is not retained.
  During a region profile, a second read of the profiled region is refused while the first has not
  run yet ("Couldn't fetch localized world data").
- **Class lookups.** A lookup that fails with anything other than `ClassNotFoundException` is
  logged once, at WARNING, with its cause; spark then treats the class as not found. Errors other
  than linkage errors are not caught.

## Thread classification

Status: IMPLEMENTED and unit-tested; the web-viewer rendering is NOT verified (see *Verification*).
It reads thread names only and needs no restart switch: it is part of the Spark platform adapter.

- `SparkThreadClass` (`aurora.engine.spark`) maps a thread name to one of: region and global tick
  (`Folia Region Scheduler Thread #n`), chunk workers, chunk I/O, Sourby lanes (storage, bridge I/O,
  startup), the async path pool, the AWF maintenance driver, network (Netty event loops) or other.
  An unknown name is `OTHER`, never folded into a neighbour. `SparkThreadClassTest` pins the names
  against the factories that create them and compares `poolOf` with Spark's own `BY_POOL` rule.
- `LabelledThreadDumper` wraps `RegionThreadDumper` as the default game-thread dumper
  (`FoliaSparkPlugin` constructor). Selection is unchanged; the report metadata says `REGEX` with the
  region-tick pattern, or `SPECIFIC` with the pinned thread's id during a region profile, instead of
  `SPECIFIC` with no ids.
- `ThreadCensusMetadataProvider` adds `sourbycraft-threads` to the extra metadata (combined with
  `SourbyMetadataProvider`): per class, the live platform-thread count and the pool labels a viewer
  shows. One walk of the thread groups, no stack traces.

Limits, stated by the code: Spark identifies a thread by id and name only, so the global tick and the
region ticks (same threads) cannot be told apart, and virtual threads (the Sourby I/O lane) are not
visible to Spark. Sampling itself is not changed: classification does not add threads to a profile.
Evidence: `SparkThreadClassTest` 9, `ThreadCensusMetadataProviderTest` 8, `RegionThreadDumperTest` 7,
`FoliaSparkPluginTest` 1 (0 failures, 2026-10-20, tree before PR #62). No live profile was taken.
PR #62 changed `FoliaSparkPlugin` (class-lookup warning), so that run needs repeating on a build
that contains it.

## Secret filtering

The provider inherits Spark's `BASE_HIDDEN_PATHS`, including management-server
secrets and RCON credentials, and retains the existing proxy/database exclusions.
The operator's `spark.serverconfigs.hiddenpaths` exclusions remain supported.

For parsed TOML/YAML, an additional pass removes credential keys recursively,
including objects inside arrays. Key comparison ignores case and separators.
Keys ending in `password`, `passwd`, `secret`, `token`, `apikey`, `accesskey`,
`privatekey`, `webhook`, or `webhookurl`, and exact `credentials`, `authorization`,
or `connectionstring` keys are omitted with their values. This also handles quoted
literal dotted keys in TOML. Harmless settings such as `token-bucket-size` remain.

Two rules close the gap key names leave (audit OBS-1). `aurora.awf.redis.uri` is a
hidden path: it is the one Aurora key whose value may carry a password
(`redis://user:password@host`) under a name that ends in `uri`, and it is omitted
outright while the other `[aurora.awf.redis]` keys stay. And in every string value,
under any key and inside lists, the user-info of a URL (`scheme://user:password@host`)
is replaced by `***`: a plugin's `url: postgres://svc:pw@db:5432/mc` is reported as
`postgres://***@db:5432/mc`; host, port and path stay because they tell deployments
apart. The start log's `Aurora World Fabric: stores on redis …` line prints the
endpoint without credentials for the same reason (`RedisClient.Endpoint.toString`).

Apart from the URL rule, this is key-based filtering, not inspection of arbitrary
string contents. A secret placed in a message or an unrecognized field name is not
automatically detectable. Use the operator hidden-path setting for those fields. Spark uses dots
for nested paths and `<dot>` for a literal dot in a key; its upstream path filter
does not itself traverse arrays. Recognized credential keys inside arrays are
handled by SourbyCraft's recursive pass.

The filtering covers the configuration files above. SourbyCraft adds no filter to what the bundled
spark itself records about the JVM and the host in its own report sections; review a report
before sharing its link. The JVM-flag redaction of PR #62 (each option keeps its name, its value
becomes `***`) applies to the `/version` hover and its click-to-copy text
(`AuroraVersionFetcher`), not to spark reports.

## Verification and remaining work

`SourbyServerConfigProviderTest`, selected by `SparkConfigTestSuite`, exercises
synthetic management/RCON secrets, nested TOML array tables, YAML lists, operator
exclusions, grouped filenames, absent files, and byte-preserving collection.
Run the complete server test task: existing forced suites can fail discovery when
an incompatible Gradle `--tests` filter excludes their children.

The parser/provider contract is tested locally without uploading any server
configuration. Verification of rendering in an actual Spark web report is still
pending; local serialization tests do not establish viewer behavior.

Open owner checks for the PR #62 changes (none run; no new unit tests were written for them):
`/spark health` with 50 furnaces or hoppers loaded shows at least 50 tile entities; `/spark tps`
on a server with no active region shows about 20, and 0.0 while the only region is frozen; with
one region blocked for 30 s, repeated `/spark health` calls return within about 5 s each and queue
at most one spark task for that region; when a timed profile completes, its upload runs on the
spark async thread, not `ForkJoinPool.commonPool-worker-*`; an uploaded report's
`sourbycraft.slowestRegion` matches `/perf region`.

## Updating Spark

Spark arrives with the upstream server (`me.lucko:spark-paper`, a Paper dependency), so its
version moves when `paperRef` moves. SourbyCraft's coupling is the region-threading spark platform
(owned source since the migration off Canvas), two Sourby classes, and two edited copies of
bundled spark classes kept under their spark names (`sourbycraft-server/src/main/java/me/lucko/spark`;
`scripts/concurrency_policy.py` scans them like owned code):

| File | What it does |
| --- | --- |
| `src/main/java/dev/iyanz/aurora/engine/spark/FoliaSparkPlugin.java` | `createServerConfigProvider()` returns `SourbyServerConfigProvider`; `createExtraMetadataProvider()` adds `SourbyMetadataProvider` and the thread census; class-lookup warning |
| `src/main/java/dev/iyanz/aurora/engine/spark/FoliaPlatformInfo.java` | Platform version reported as the build's display version plus `(MC:26.2)` (`BuildInfo.displayVersion()`) |
| `src/main/java/dev/iyanz/aurora/engine/spark/plugin/FoliaTickStatistics.java` | Tick statistics come from Sourby metrics (*World and tick statistics*) |
| `src/main/java/dev/iyanz/aurora/engine/spark/provider/FoliaWorldInfoProvider.java` | World, chunk, entity and tile-entity counts (*World and tick statistics*) |
| `src/main/java/dev/iyanz/sourbycraft/spark/SourbyServerConfigProvider.java` | Config groups and secret filtering; extends Spark's `ServerConfigProvider` |
| `src/main/java/dev/iyanz/sourbycraft/spark/SourbyMetadataProvider.java` | The `sourbycraft` metadata object |
| `src/main/java/me/lucko/spark/paper/common/command/modules/SamplerModule.java` | Copy of spark's sampler command: region profiler (`// Canvas` hunks); its three completion, failure and upload continuations run on the spark platform's async executor (`FoliaSparkPlugin.executeAsync`) instead of `ForkJoinPool.commonPool` (`// SourbyCraft` hunks, PR #62) |
| `src/main/java/me/lucko/spark/paper/common/platform/world/WorldStatisticsProvider.java` | Copy of spark's world statistics: chunks grouped by region id instead of by distance (`// Canvas` hunks) |

Procedure, after bumping `paperRef`:

1. `./gradlew applyAllPatches`, then compile. The spark platform classes are owned source now,
   so a Spark API change shows up as a compile error in them rather than a failed patch.
2. Check that `ServerConfigProvider`, `ConfigParser` and `BASE_HIDDEN_PATHS` still have the
   shapes `SourbyServerConfigProvider` uses. Spark has changed them before. A compile error here
   is the expected signal.
3. Diff the two copied classes against the same classes of the new spark-paper release. Carry the
   upstream changes into the copies and keep only the marked `// Canvas` and `// SourbyCraft` hunks;
   a copy that silently stays on the old spark version is the risk here, and the compiler does not
   catch a behaviour change.
4. Run the full `:sourbycraft-server:test` task (not a `--tests` filter; see above). At minimum,
   `SparkConfigTestSuite`, `FoliaTickStatisticsTest` and `FoliaWorldInfoProviderTest` must pass.
5. If the Spark jar coordinates moved, update the `me/lucko/spark-paper` prefix in
   `slimServerJar`'s externalised directories (`build.gradle.kts`). A task that strips 0
   libraries fails on purpose.
6. Boot, run `/spark profiler start` and then `stop`, and open the report. Confirm the platform
   version, the `sourbycraft/` config group (with secrets absent), the `sourbycraft` metadata
   (including `slowestRegion`) and the tick statistics. Step 6 is the web-viewer verification that
   is still open; record the result here when done.
