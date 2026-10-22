# Spark configuration reporting

SourbyCraft uses the upstream Spark engine through its existing Canvas integration.
`FoliaSparkPlugin.createServerConfigProvider()` delegates to the Sourby-owned
`SourbyServerConfigProvider`. This is an adapter, not a separate profiler fork.

The `sourbycraft/` report group contains `global.toml` from
`sourbycraft_config/sourbycraft_global_config.toml`, `aurora.toml` from
`sourbycraft_config/aurora.toml` (every Aurora setting, including the Build 47 bridge, scheduler
and network keys), and `security.yml` from `sourbycraft-security.yml`. Missing files are omitted. Collection reads files and
builds report JSON; it does not seed defaults, migrate files, or save configuration.

## Aurora runtime metadata

`FoliaSparkPlugin.createExtraMetadataProvider()` returns `SourbyMetadataProvider`, which adds one
`sourbycraft` object to each report's extra platform metadata:

- `build` — identity, build, codename, Minecraft version, engine;
- `runtime` — Aurora state and processor count;
- `bridge` — mode, bridged plugins, quarantined plugins, fatal violations;
- `governor` — each used lane's threads, queue and rejections;
- `switches` — async pathfinding, lane sampling, network counters, `aurora.cpu.cores`;
- `startup` — total time to ready and start class.

Every value is an in-memory read. No configuration strings are included, so there is nothing here
for secret filtering to catch. Whether spark.lucko.me renders extra platform metadata, and how,
is part of the open web-viewer verification.

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
`FoliaSparkPluginTest` 1 (0 failures, 2026-10-20). No live profile was taken.

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

## Verification and remaining work

`SourbyServerConfigProviderTest`, selected by `SparkConfigTestSuite`, exercises
synthetic management/RCON secrets, nested TOML array tables, YAML lists, operator
exclusions, grouped filenames, absent files, and byte-preserving collection.
Run the complete server test task: existing forced suites can fail discovery when
an incompatible Gradle `--tests` filter excludes their children.

The parser/provider contract is tested locally without uploading any server
configuration. Verification of rendering in an actual Spark web report is still
pending; local serialization tests do not establish viewer behavior.

## Updating Spark

Spark is not vendored. It arrives with the upstream server (`me.lucko:spark-paper`, a Paper
dependency), so its version moves when `paperRef` moves. SourbyCraft's coupling is three classes in
the region-threading Spark platform (owned source since the migration off Canvas) plus one Sourby
class:

| File | What it does |
| --- | --- |
| `src/main/java/dev/iyanz/aurora/engine/spark/FoliaSparkPlugin.java` | `createServerConfigProvider()` returns `SourbyServerConfigProvider` |
| `src/main/java/dev/iyanz/aurora/engine/spark/FoliaPlatformInfo.java` | Platform version reported as `BuildN (MC:26.2)` |
| `src/main/java/dev/iyanz/aurora/engine/spark/plugin/FoliaTickStatistics.java` | Tick statistics come from Sourby metrics |
| `src/main/java/dev/iyanz/sourbycraft/spark/SourbyServerConfigProvider.java` | Config groups and secret filtering; extends Spark's `ServerConfigProvider` |

Procedure, after bumping `paperRef`:

1. `./gradlew applyAllPatches`, then compile. The three Spark platform classes are owned source now,
   so a Spark API change shows up as a compile error in them rather than a failed patch.
2. Check that `ServerConfigProvider`, `ConfigParser` and `BASE_HIDDEN_PATHS` still have the
   shapes `SourbyServerConfigProvider` uses. Spark has changed them before. A compile error here
   is the expected signal.
3. Run the full `:sourbycraft-server:test` task (not a `--tests` filter; see above). At minimum,
   `SparkConfigTestSuite` and `FoliaTickStatisticsTest` must pass.
4. If the Spark jar coordinates moved, update the `me/lucko/spark-paper` prefix in
   `slimServerJar`'s externalised directories (`build.gradle.kts`). A task that strips 0
   libraries fails on purpose.
5. Boot, run `/spark profiler start` and then `stop`, and open the report. Confirm the platform
   version, the `sourbycraft/` config group (with secrets absent) and the tick statistics. Step 5
   is the web-viewer verification that is still open; record the result here when done.
