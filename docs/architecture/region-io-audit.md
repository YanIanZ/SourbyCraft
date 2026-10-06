# Blocking I/O and region threads — audit

AURORA-TASKS E: "verify no external I/O blocks region execution". This is a source audit of
`sourbycraft-server/src/main/java` as of 2026-09-26. It covers SourbyCraft's own code, not
upstream. `BlockingIoBoundaryTest` pins the network list below, so a new file that opens network
connections fails the build until it is added here with its thread context.

## Network I/O

| File | What | Runs on |
| --- | --- | --- |
| `update/SourbyUpdater.java` | GitHub release checks and downloads | Folia async scheduler (`runAtFixedRate`/`runDelayed`) |
| `update/ViaAutoUpdate.java` | ViaVersion release check | Called from `SourbyUpdater`'s async check |
| `command/SpeedtestCommand.java` | Speed test | `VirtualExecutor` (bounded virtual threads); the reply hops back to the sender's owner |
| `util/GeoUtil.java` | GeoIP lookup for `/ping` (local `.mmdb`) | `VirtualExecutor`, from `PingCommand` |
| `bootstrap/LibDownloader.java`, `bootstrap/PluginProvisioner.java` | Library and plugin provisioning | Before the server starts (bootstrap), not on any region thread |
| `awf/redis/RedisClient.java` | The `redis` AWF backend (`aurora.awf.backend = "redis"`) | Store opening: the I/O lane (`AwfEngine.prepare`) for runtime worlds, the main thread at boot. Commits: the storage lane, refused on a region thread like FILE commits. Chunk reads: wherever the engine reads a region file for that chunk — the same exposure the FILE backend's reads have. Lease renewal: its own daemon thread. Every socket read is bounded by `timeout-ms`. |

## File I/O

| File | What | Runs on |
| --- | --- | --- |
| `update/UpdateApplier.java`, `command/UpdateCommand.java` | Staging and swapping the jar | Async scheduler / `VirtualExecutor` |
| `startup/*` (`StartupCache`, `SourceFingerprint`, `StartupProfile`) | Startup index and profile | Boot stage (before plugins load), and once at `ServerLoadEvent(STARTUP)` |
| `bridge/AuroraBridge.java` | Reads two TOML keys once | Plugin loading during bootstrap |
| `spark/SourbyServerConfigProvider.java` | Config files for a Spark report | Spark's report thread |
| `perf/RuntimeSampler.java`, `util/ContainerMemory.java` | `/proc`/cgroup pseudo-files | Metrics collector thread; `/spec` reads `memory.current` on the command thread (a kernel pseudo-file, no disk or network) |
| `bootstrap/*` | CDS, hashes, libraries | Before server start |
| `awf/*` | World storage | Commits: `STORAGE` lane, or synchronously in `flush`/`close` off region threads (on a region thread `flush` only starts an async commit, and `GenerationStore` throws). `AwfRegionStorage.read` of a chunk no longer in memory reads its object on whatever thread the engine asks, like the region-file read it replaces |
| `awf/AwfSettings.java` | Reads the `aurora.awf` TOML keys once | The first `RegionFileStorage` construction (world load) |

## Result

No SourbyCraft code performs network I/O on a region tick thread. The only disk I/O that can
happen on one is an AWF chunk read standing in for the engine's own region-file read (worlds in
`aurora.awf.worlds` only). The one command-thread read is `/spec`'s cgroup pseudo-file. This is a static
audit: it does not prove that upstream code, or plugins, stay off region threads.
