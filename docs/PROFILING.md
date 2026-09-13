# SourbyCraft profiling

Use Java 25, an isolated test directory, and the same JVM, world, plugin hashes,
configuration, warmup, and workload for both builds. Boot and idle recordings are
smoke tests; they do not establish the heavy-workload targets in SPEC.md.

## IntelliJ IDEA

Import the Gradle project, select a Java 25 project SDK, and use language level 25.
Build with `./gradlew applyAllPatches` and `./gradlew slimServerJar`.

Create `build/profile-idea`, accept the Minecraft EULA in that test directory, and
place the intended test configuration there. The checked-in **SourbyCraft JFR**
JAR Application run configuration uses that directory and the slim jar. It sets
an explicit 2 GiB heap and G1 for this test only. Press **Run** to launch from IDEA.
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
new directory, binds to loopback, disables update/plugin provisioning in its test
config, waits for `Done (`, explicitly loads the spawn chunk, warms up, records
JFR, requests heap/thread diagnostics, saves, and sends `stop`. It requires exit 0,
persisted `world/level.dat`, API lifecycle markers, and a readable JFR file.
`--expect-config-unchanged` additionally checks the operator TOML hash. Python 3.9+
is sufficient. JDK tools must be next to the selected `java` executable.

`--world /path/to/saved/world` copies a world for restart/persistence testing. It
does not simulate players or prove full player/inventory persistence. Use different
output directories for baseline and candidate. Never run comparisons concurrently.

## Manual diagnostics

```sh
jcmd PID JFR.start name=SourbyProfile settings=profile duration=120s filename=/absolute/path/profile.jfr
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

Keep baseline/candidate commit and jar SHA-256, Canvas pin, Java distribution and
version, hardware, JVM args, configuration/plugin hashes, world source, warmup,
duration, and workload. Record CPU, heap/RSS, allocation, GC pauses, TPS, and
MSPT distributions. Mark unsupported metrics unavailable. Evaluate repeated runs
and variance before applying the approximately 3% regression gate. A developer
desktop running builds alongside a profile is not a controlled performance lab.
