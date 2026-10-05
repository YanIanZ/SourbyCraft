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

This is key-based filtering, not inspection of arbitrary string contents. A secret
placed in a message, custom URL, or an unrecognized field name is not automatically
detectable. Use the operator hidden-path setting for those fields. Spark uses dots
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
| `src/main/java/io/canvasmc/canvas/spark/FoliaSparkPlugin.java` | `createServerConfigProvider()` returns `SourbyServerConfigProvider` |
| `src/main/java/io/canvasmc/canvas/spark/FoliaPlatformInfo.java` | Platform version reported as `BuildN (MC:26.2)` |
| `src/main/java/io/canvasmc/canvas/spark/plugin/FoliaTickStatistics.java` | Tick statistics come from Sourby metrics |
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
