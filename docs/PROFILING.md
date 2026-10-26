# SourbyCraft profiling

For comparison baselines and the regression gate, see [BASELINE.md](BASELINE.md).
This document covers ad-hoc profiling and the single-run boot smoke test.

Use Java 25, an isolated test directory, and the same JVM, world, plugin hashes,
configuration, warmup, and workload for both builds. Boot and idle recordings are
smoke tests; they do not establish any heavy-workload target (the T10 workloads and
metrics are listed in [qualification-readiness.md](architecture/qualification-readiness.md)).

## IntelliJ IDEA

Import the Gradle project, select a Java 25 project SDK, and use language level 25.
Build with `./gradlew applyAllPatches` and `./gradlew slimServerJar`.

Create `build/profile-idea`, accept the Minecraft EULA in that test directory, and
place the intended test configuration there. Then create a **JAR Application** run
configuration named **SourbyCraft JFR** locally. It is not checked in: `.run/` is
git-ignored, and the file that used to be tracked was dropped in `0b37eed`. Use these
settings:

| Field | Value |
| --- | --- |
| Path to JAR | `$PROJECT_DIR$/build/libs/SourbyCraft-slim.jar` |
| VM options | `-Xmx2G -XX:+UseG1GC -XX:StartFlightRecording=name=SourbyCraft,settings=profile,delay=60s,duration=120s,filename=profile.jfr,dumponexit=true -Xlog:gc*:file=gc.log:time,uptime,level,tags` |
| Program arguments | `--nogui` |
| Working directory | `$PROJECT_DIR$/build/profile-idea` |

The 2 GiB heap and G1 are for this test only. Press **Run** to launch from IDEA.
The configuration records `profile.jfr` for 120 seconds after a 60-second startup
delay and writes `gc.log`. For a cold dependency download, wait for `Done (` and
start an additional warmed recording using the command below. Send `stop` through
the Run console and wait for clean shutdown.

Open the recording using **Run → Open Profiler Snapshot**. The JAR Application
and JFR workflows are documented by [JetBrains](https://www.jetbrains.com/help/idea/run-debug-configuration-jar.html)
and [the profiler configuration guide](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html).

## Reproducible terminal smoke test

```sh
python3 scripts/profile_server.py build/libs/SourbyCraft-slim.jar \
  --output build/profiles/candidate \
  --fixture test-plugin/build/libs/test-plugin-1.0.0.jar \
  --warmup 60 --duration 120 --expect-config-unchanged
```

The output directory must not already exist. The script writes only into this
new directory, binds to loopback, writes its test config as
`sourbycraft_config/sourbycraft_global_config.yml` in the Build 49 layout
(`plugins.viaversion.auto-provision`, `plugins.protocollib.auto-provision` and
`updater.enabled`, all `false`), waits for `Done (`, explicitly loads the spawn chunk,
warms up, records JFR, requests heap/thread diagnostics, saves, and sends `stop`. It
requires exit 0, persisted `world/level.dat`, API lifecycle markers (with `--fixture`), and a
readable JFR file. `--expect-config-unchanged` additionally requires that YAML file to be
byte-identical after the stop; a missing file is recorded as `null` and fails the check. The
server does not rewrite an existing Build 49-layout file at boot (it seeds and saves only a new or
migrated one), so the check holds on a normal run. Until PR #62 the script wrote a TOML fixture,
which the server converted and renamed at boot, so the hash could not be taken afterwards (code
written and statically reviewed; not run since). Python 3.9+ is sufficient. JDK tools must be
next to the selected `java` executable.

`--world /path/to/saved/world` copies a world for restart/persistence testing. It
does not simulate players or prove full player/inventory persistence. Use different
output directories for baseline and candidate. Never run comparisons concurrently.

## One JFR recording name

Every tool that starts a recording names it **`SourbyCraft`**: the IDE run configuration,
`profile_server.py` and `run_baseline.py`. They previously used three different names, so
`jfr summary` output and the IDE profiler disagreed about what a file was depending on
which tool wrote it. SourbyCraft contributes two JFR event types:
`dev.iyanz.sourbycraft.PerformanceSnapshot` (the tick and runtime snapshot) and
`dev.iyanz.sourbycraft.LaneLoad` (one event per execution lane per collection: threads and cores
used). Keep one IDE run configuration, **SourbyCraft JFR**, with the settings above; if a second
JFR run configuration appears in the IDE, delete it rather than keeping two.

## Manual diagnostics

```sh
jcmd PID JFR.start name=SourbyCraft settings=profile duration=120s filename=/absolute/path/profile.jfr
jcmd PID Thread.print
jcmd PID GC.heap_info
jfr summary /absolute/path/profile.jfr
```

Use the `jcmd`/`jfr` in the same Java installation as the target server. Heap dumps
are explicit, potentially expensive diagnostics: `jcmd PID GC.heap_dump /path/heap.hprof`.
Native Spark profiling remains available through `/spark profiler start` and
`/spark profiler stop`; viewer upload follows Spark's explicit command behavior.

Build 44 contributes `dev.iyanz.sourbycraft.PerformanceSnapshot` events to active
JFR recordings. Events include the same sequence, freshness, target TPS, worst
region averages, estimated percentiles, CPU, and heap used by commands. They do
not attribute sampled CPU to individual regions or plugins. MXBean collection
time is not equivalent to stop-the-world GC pause time; use JFR pause events.

## Required evidence

Keep baseline/candidate commit and jar SHA-256, the Paper pin (`paperRef` in
`gradle.properties`), Java distribution and version, hardware, JVM args, configuration/plugin hashes, world source, warmup,
duration, and workload. Record CPU, heap/RSS, allocation, GC pauses, TPS, and
MSPT distributions. Mark unsupported metrics unavailable. Evaluate repeated runs
and variance before applying the approximately 3% regression gate. A developer
desktop running builds alongside a profile is not a controlled performance lab.
