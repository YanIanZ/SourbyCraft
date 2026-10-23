# Blocking I/O and region threads — audit

AURORA-TASKS E: "verify no external I/O blocks region execution". This is a source audit of
`sourbycraft-server/src/main/java` as of 2026-09-26. It covers SourbyCraft's own code, not
upstream. `BlockingIoBoundaryTest` pins the network list below, so a new file that opens network
connections fails the build until it is added here with its thread context.

## Network I/O

| File | What | Runs on |
| --- | --- | --- |
| `update/HttpTransport.java` | GitHub release checks and downloads (the one HTTP seam of `SourbyUpdater`, which holds no network call itself since 2026-10-20) | Folia async scheduler (`runAtFixedRate`/`runDelayed`) via `SourbyUpdater`; never the start/stop monitor |
| `update/HttpTransport.java` (Via caller) | Pinned Via metadata/download check; bounds + SHA-256 checked by `ViaAutoUpdate` | Called from `SourbyUpdater`'s async check |
| `command/SpeedtestCommand.java` | Speed test | `VirtualExecutor` (bounded virtual threads); the reply hops back to the sender's owner |
| `util/GeoUtil.java` | GeoIP lookup for `/ping` (local `.mmdb`) | `VirtualExecutor`, from `PingCommand` |
| `bootstrap/LibDownloader.java`, `bootstrap/PluginProvisioner.java` | Library and plugin provisioning, including ProtocolLib | Plugin JAR/config work runs on the startup path before Paper's plugin scan / plugin enable, before region threads start; library fetch is bootstrap-only |
| `net/HandshakeAddresses.java` | Parses IP literals for the handshake address (Guava `InetAddresses`); opens no connection and never resolves a name. Listed only because it matches the scan pattern | Netty event loop (`ServerHandshakePacketListenerImpl.handleIntention`) |
| `security/SecurityGuards.java` | Reads the numeric bytes of a peer/forwarded `InetSocketAddress` for connection-guard admission limits (SEC-11); opens no connection and never resolves a name (unresolved addresses bypass). Listed only because it matches the scan pattern | Netty event loop (`ServerConnectionListener` `initChannel`, `ServerLoginPacketListenerImpl.handleHello` / Velocity forwarding) |
| `update/UpdateOverrides.java` | Validates the test-only `sourbycraft.update.api-base` JVM property (loopback `http(s)` URL only) with `java.net.URI`; opens no connection and never resolves a name. Listed only because it matches the scan pattern | Async scheduler, inside `SourbyUpdater.check` |
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

This inventory does not prove that region execution is free of network or disk waits.
A cold AWF read uses the engine caller's thread; with Redis that operation can include network
I/O. The caller context must be checked at each active engine path; the Redis read paths are traced
in [Redis chunk-read paths](#redis-chunk-read-paths-static-trace-2026-10-20) below. The
`createAfter` global-thread `ready.join()` (B80/B85) is **fixed** since 2026-10-13 (staged
creation, SPEC.md B80/B85, [aurora-world-fabric.md](aurora-world-fabric.md) "Staged
creation"); the [ASP port review](../development/ASP-26.2-AWF-PORT-REVIEW.md) text that calls it
OPEN is the 2026-10-06 snapshot. `Bukkit.createWorld` itself is unchanged and still synchronous, and
global tick waiting is a separate blocking boundary from a region tick. `/spec` reads a cgroup
pseudo-file on its command thread.
`BlockingIoBoundaryTest` pins a source inventory, not a proof of safe runtime scheduling.
Runtime qualification remains pending; plugins and generated engine paths are not covered by
that source test.

## Netty event loop audit (DEV-H, 2026-10-20)

Question (TODO DEV-H): do Netty event-loop threads do blocking work that is unrelated to the
connection they serve — file, database, DNS, HTTP, Redis, synchronous chunk loads, or plugin
event handlers? This is a **static source audit** of the materialized tree at
`sourbycraft-server/src/minecraft/java` (HEAD `1fc895f SourbyCraft - security guard hooks`),
`paper-server/src/main/java` and `sourbycraft-server/src/main/java` on 2026-10-20. No server was
started; nothing here is a runtime measurement and no latency figure is claimed.

Method: a packet handler runs on the channel's event loop unless it calls
`PacketUtils.ensureRunningOnSameThread` (`network/protocol/PacketUtils.java:21-33`), which
re-queues it to the player's region (`schedulePacket`) or to the global tick thread
(`RegionizedServer.schedulePacket`) for login/configuration listeners. Every `handle*` method of
the handshake, status, login, configuration, common and game listeners was checked for that call;
those without it were read to the end of their call chain. CONFIRMED = the path from the event
loop to the blocking call was traced in source. PLAUSIBLE = the event loop runs code (usually a
plugin listener) whose blocking behaviour this repository cannot see.

### Findings

| # | Status | Where (file:line) | What blocks | How the event loop reaches it | Severity | Proposed fix |
| --- | --- | --- | --- | --- | --- | --- |
| H1 | **FIXED** in materialized commit `214fff5` (SourbyCraft - hand code-of-conduct acceptance to the global thread); was CONFIRMED (conditional) | `server/network/ServerConfigurationPacketListenerImpl.java:199-201` → `:294` `finishCurrentTask` → `:274-285` `startNextTask` → `io/papermc/paper/connection/PaperConfigurationTask.java:30-34` → `server/network/config/PrepareSpawnTask.java:54-58,105,121` → `world/level/storage/PlayerDataStorage.java:64-104` | Player-data file read (`NbtIo.readCompressed` of `playerdata/<uuid>.dat`, `.dat_old` fallback, backup copy on a failed read), DataConverter upgrade of old data, a fair `ReentrantLock` on the spawn world (`ServerLevel.java:741,754`, world stage lock also taken by world load/unload), and world/joining-player state mutation. | `handleAcceptCodeOfConduct` has no `ensureRunningOnSameThread` (inherited unchanged from the Vanilla commit `eb888cf`). It finishes the code-of-conduct task on the event loop; `startNextTask` then starts the next tasks inline. With no `ServerResourcePackConfigurationTask` and no `AsyncPlayerConnectionConfigureEvent` listener, `PaperConfigurationTask` finishes immediately and `PrepareSpawnTask.start` runs on the event loop. Only reached when a code-of-conduct task exists: `enable-code-of-conduct` with files, or any `PlayerCodeOfConductSendEvent` listener returning non-null (`:143-166`). Default config does not add the task. | Medium — one slow disk read or a contended world stage lock stalls every channel on that loop; it is also a correctness issue (configuration state and world joining counters touched off the global tick thread, racing `tick()` at `:255`), which the policy ranks above throughput. | SourbyCraft feature patch: add `PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor())` as the first line of `handleAcceptCodeOfConduct`, as `handleSelectKnownPacks` (`:189`) already does, with a test that the handler throws `RunningOnDifferentThreadException` off the global thread. Note: the read then lands on the global tick thread like the default path (see "Related, not DEV-H"). |
| H2 | CONFIRMED (plugin code), blocking PLAUSIBLE | `server/network/ServerGamePacketListenerImpl.java:3861-3872` | `PlayerConnectionReconfigureEvent` listeners run on the event loop. | `handleConfigurationAcknowledged` has no thread hop (Paper line, Vanilla `eb888cf` also has none). | Low (plugin-dependent) | Document it as an event-loop event in the plugin guidance; if routed later, use the global thread through `ensureRunningOnSameThread`, which changes Paper event semantics and needs its own review. |
| H3 | CONFIRMED (plugin code), blocking PLAUSIBLE | `server/network/ServerStatusPacketListenerImpl.java:34-40` → `com/destroystokyo/paper/network/StandardPaperServerListPingEventImpl.java:66-68`; `server/network/LegacyQueryHandler.java:52,82,221` → `PaperLegacyStatusClient.java:46-51` | `PaperServerListPingEvent` listeners (MOTD/player-count plugins often do DB/HTTP/Redis lookups) run on the event loop that serves the ping. The server's own work there is in-memory (cached icon, player list copy). | Status and legacy ping handlers never hop threads (Paper design; the event is declared async). | Low–Medium (plugin-dependent; status pings are unauthenticated, so a blocking listener is reachable by anyone who can open a socket) | No server change; state in plugin guidance that ping listeners must not block, and consider counting ping-listener wall time in bridge telemetry. |
| H4 | **FIXED** in materialized commit `822d4d7` (SourbyCraft - no DNS lookup for handshake addresses): a non-literal `getSocketAddressHostname()` is ignored (warning logged once), the real remote address stays; was CONFIRMED (code path), DNS conditional | `server/network/ServerHandshakePacketListenerImpl.java:122-148` (`:145`) | `new InetSocketAddress(event.getSocketAddressHostname(), port)` resolves its argument; a non-literal hostname from a plugin is a synchronous DNS lookup. `PlayerHandshakeEvent` listeners themselves also run here. | `handleIntention` runs on the event loop and fires `PlayerHandshakeEvent` when any listener is registered. | Low (needs a plugin returning a hostname rather than an IP literal) | Feature patch: parse with `com.google.common.net.InetAddresses.forString` (as `VelocityProxy.readAddress` does) or `InetSocketAddress.createUnresolved` + reject, so no lookup can happen on the loop. |
| H5 | **FIXED** in materialized commit `822d4d7`: the forwarded address must be an IP literal (`net/HandshakeAddresses`, also with `Paper.bypassHostCheck`), otherwise the existing BungeeCord-forwarding disconnect applies; was CONFIRMED (code path), DNS conditional | `server/network/ServerHandshakePacketListenerImpl.java:19,157-160` | BungeeCord forwarding: `new InetSocketAddress(split[1], port)`. `HOST_PATTERN` is `[0-9a-f\.:]{0,45}`, which admits non-literals such as `cafe.dead`; those are resolved by DNS on the event loop. `-DPaper.bypassHostCheck=true` (`:26`) skips the pattern entirely. | Same handler, only with `spigot.yml settings.bungeecord: true`. A client can supply the string only when the backend port is reachable without the proxy (already a security misconfiguration) or the proxy forwards it. | Low | Same fix as H4 (literal-only parse, disconnect on failure). |
| H6 | PLAUSIBLE | `io/papermc/paper/connection/ReadablePlayerCookieConnectionImpl.java:39-42`, reached from `ServerLoginPacketListenerImpl.java:456` and `ServerCommonPacketListenerImpl.java:263` | Cookie futures are completed on the event loop; a plugin's non-`Async` dependent stage (`thenAccept`, …) runs inline there. | `handleCookieResponse` has no thread hop. | Info (plugin-dependent) | Plugin guidance: attach cookie continuations with an explicit executor. |
| H7 | PLAUSIBLE | `paper-server/src/main/resources/log4j2.xml` (`<Async name="Async">`, default `blocking`/`bufferSize`); `security/SecurityGuards.java:50,158-160`; e.g. `ServerLoginPacketListenerImpl.java:134,158`, `ServerCommonPacketListenerImpl.java` keep-alive disconnect lines | Log calls made on the event loop enqueue into Log4j's Async appender, which (per Log4j defaults, not re-verified here) blocks the caller when its queue is full, i.e. when file/console output stalls. SourbyCraft's own decode guard logs at most once per 30 s per limit (`LOG_INTERVAL`), so it adds no per-packet logging. | Disconnect, login and security log lines are emitted from handlers on the loop. | Info | None now; if a disk-stall test ever shows it, set `blocking="false"` with a discard policy for the event-loop path. |
| H8 | PLAUSIBLE, not inspected | Third-party pipeline handlers installed through `ChannelInitializeListenerHolder` / channel injection: ProtocolLib (auto-provisioned by default, `[plugins.protocollib] auto-provision = true`), ViaVersion/ViaBackwards (auto-provisioned) | Their packet listeners run on the event loop for inbound packets; whether a given plugin listener blocks is outside this repository. | Every inbound packet passes their handlers. | Info (deployment-dependent) | None in the server; note in plugin guidance and keep ProtocolLib/Via versions pinned as today. |

### Checked, no unrelated blocking found

- **SourbyCraft code on the loop.** `PacketDecoder.java:24-50` + `security/SecurityGuards.java`:
  a channel attribute, a thread-local scope, `AtomicLongArray` counters and arithmetic; the YAML
  file is read only by `SecurityGuards.load`, called from `SourbyCraftConfig.java:261` (boot and
  `/sourbycraft reload`, not the loop). The scope closes when `decode` returns, before
  `ByteToMessageDecoder` fires the packet onward, so NBT checks in storage or in packet handlers
  are outside it (`check` returns early with no scope). `DiscardedPayload.java:15` and
  `ServerboundSetCreativeModeSlotPacket.java:19` run inside decode. `perf/NetworkMetrics.java`
  handlers only add to `LongAdder`s (`NetworkCounters.java:34-38`; `sample` is synchronized but
  runs on the metrics thread).
- **No SourbyCraft listener on a loop-fired event.** The only SourbyCraft `@EventHandler`s are
  `PlayerLoginEvent` (`maxplayers/MaxPlayersBypass.java:69`, fired from the login tick on the
  global thread), `PlayerJoinEvent`/`PlayerQuitEvent`, `ServerLoadEvent` and
  `PluginDisableEvent`. `bridge/` does not wrap event dispatch, so legacy plugins' listeners for
  H2/H3/H6 events run on the loop unwrapped.
- **Login.** Online-mode session lookups (`ServerLoginPacketListenerImpl.java:274-316`), offline
  profile creation and `AsyncPlayerPreLoginEvent` (`:200-212`, `:330-376`), and the Velocity path
  (`:420-430`) run on `authenticatorPool` virtual threads (`:58`). Velocity forwarding parses the
  address with `InetAddresses.forString` (`VelocityProxy.java:61-62`, no DNS). The login tick
  (`canPlayerLogin`, `PlayerLoginEvent`) runs from `Connection.tick` on the global thread.
- **Game.** Tab completion and `AsyncTabCompleteEvent` → `TAB_COMPLETE_EXECUTOR`
  (`ServerGamePacketListenerImpl.java:917-944`); chat decoration and `AsyncChatEvent` →
  `chatExecutor` (`ImprovedChatDecorator` `supplyAsync`, `:349`); text-filter HTTP →
  `ServerTextFilter` worker pool (`ServerTextFilter.java:87-99`); book and sign results →
  `player::queuePacketTask` (`:1486`, `:3732`); custom payload / plugin messages, resource-pack
  responses, click actions → `ensureRunningOnSameThread` (`ServerCommonPacketListenerImpl.java:163,225,237`).
- **Disconnect.** `channelInactive` only calls `disconnect`; `handleDisconnection` and
  `PlayerConnectionCloseEvent` run from tick paths (`Connection.java:675,687`,
  `ServerConnectionListener.java:314,339`, `RegionizedServer.java:524`,
  `RegionizedWorldData.java:619`).
- **Other SourbyCraft I/O seams** (`util/GeoUtil.java` via `PingCommand` `VirtualExecutor`,
  `bootstrap/LibDownloader`, `bootstrap/PluginProvisioner`, updater HTTP, `awf/redis/RedisClient`)
  have no caller on a Netty thread.
- **Connection-related CPU on the loop (in scope of the connection, not findings):** RSA decrypt in
  `handleKey`, chat signature checks in `handleChat`, Velocity HMAC, compression/encryption.
  Contention, not I/O: Paper's packet limiter takes the global `PACKET_LIMIT_LOCK`
  (`Connection.java:264`) per limited packet across all loops, and the connection throttle
  synchronizes a static `HashMap` (`ServerHandshakePacketListenerImpl.java:82`).
  `Connection.disconnect` calls `close().awaitUninterruptibly()` (`:734`); on the loop Netty closes
  inline, so that wait is on the channel's own close.

### Related, not DEV-H (region/global I/O, for the sections above)

- `PrepareSpawnTask.start` reads player data on whichever thread starts it; on the default path
  that is the global tick thread (after `handleSelectKnownPacks`), and on a configuration with an
  `AsyncPlayerConnectionConfigureEvent` listener it is Paper's `CONFIGURATION_POOL`.
- `PrepareSpawnTask.java:359-361` reads the same player-data file again inside `spawn`, which is
  queued to the spawn chunk's region (`ServerConfigurationPacketListenerImpl.java:240-244`): a
  synchronous file read on a region thread per join. Not measured.

### Not verified

- No runtime evidence: no thread dump, no blocked-loop profile, no disk-stall test. H1's stall
  size and H7's queue behaviour are unmeasured.
- Third-party plugins (ProtocolLib, ViaVersion, Spark, any MOTD/handshake plugin) were not read.
- Log4j Async appender defaults and Netty's HAProxy address validation are quoted from library
  behaviour, not re-read from the bundled jars.
- Generated/obfuscated paths outside the listed files and the `aurora.engine` sources were not
  traced for loop entry.

## Redis chunk-read paths (static trace, 2026-10-20)

Question left open by the Result section: which threads reach `AwfRegionStorage.read` (and so the
Redis backend, `aurora.awf.backend = "redis"`) and what bounds the wait. Static read of the
materialized tree and `sourbycraft-server/src/main`; no server was started, no Redis latency was
measured, and nothing here says a stall happens, only where one would land.

Bounds that apply to every path: each blocking Redis step (free-connection wait, connect, socket
read, stalled write) is capped by `aurora.awf.redis.timeout-ms` (default 5000, accepted range
100-600000; `RedisSettings.DEFAULT`, `RedisClient` lines 157/205/206/168-174). How many such steps
one command can take was not enumerated, so "one timeout" below means "per step".
`AwfRegionStorage.readStored` retries only off region threads: up to `READ_ATTEMPTS` = 3 with
100 ms and 400 ms pauses, no new attempt after `READ_RETRY_BUDGET_NANOS` = 5 s, and for
`FAIL_FAST_NANOS` = 5 s after any read gave up every read gets a single attempt. A failed read is
fenced, never "no data".

| # | Path | Thread that blocks | Reaches AWF read as | Wait on a Redis stall |
|---|---|---|---|---|
| R1 | Chunk load: `ChunkIOTask` -> `RegionDataController.readData` -> `RegionFileStorage.moonrise$readData` (MoonriseRegionFileIO.java:1100, RegionFileStorage.java:224) | The chunk system's I/O pool (`... I/O Worker #n`, lane `WORLD_IO`), never a region thread; retries and sleeps are allowed here | `AwfRegionStorage.read` | The I/O worker is held for up to the retry budget plus one attempt. **Transitively** a tick thread parked in `ServerChunkCache.syncLoad` (`getChunk(x, z, status, true)` for a chunk it owns; `managedBlock(completable::isDone)`) waits for that task, so a region can stall on a stalled backend without ever calling Redis itself. Other tick-thread waits on the same I/O: `ChunkTaskScheduler.syncLoadNonFull` (`managedBlock`, used by `PlayerSpawnFinder`, `MinecraftServer` world creation) |
| R2 | Structure scan: `StructureCheck.tryLoadFromStorage` -> `storageAccess.scanChunk(...).join()` (StructureCheck.java:155) -> `SimpleRegionStorage.chunkScanner()` (a lambda that scans **inline on the caller**, "TODO ChunkMap implementation?") -> `RegionFileStorage.scanChunk` | The **calling thread**. Region-thread callers exist: `Dolphin` treasure search, `EnderEyeItem`, `ExplorationMapFunction` (loot), `LocateCommand` via `ServerLevel.findNearestMapStructure` -> `ChunkGenerator.findNearestMapStructure` -> `StructureManager.checkStructurePresence` -> `StructureCheck.checkStart` | `AwfRegionStorage.read` (RegionFileStorage.java:440-449) | On a region thread: **one attempt, no sleep**, so the region tick is blocked for the steps of one command (up to the timeout per step) once per uncached chunk. `StructureCheck` caches the parsed result per chunk (`loadedChunksSafe`), so only the first check of a chunk reads. The FILE backend has the same shape with a disk read instead of a network read |
| R3 | Synchronous `RegionFileStorage.read(ChunkPos)` (RegionFileStorage.java:~395) via `SimpleRegionStorage.read` and `IOWorker` (IOWorker.java:146) | Whoever calls it; in the Moonrise chunk system the chunk/POI/entity loads use R1 instead. Callers of `SimpleRegionStorage.read` outside R1 (`RegionStorageUpgrader`, `ChunkMap.upgradeChunkTag` path) were not traced | `AwfRegionStorage.read` | Not established for region threads |
| R4 | Commits (`write`/`flush`) | `STORAGE` lane; refused on a region thread (`GenerationStore` throws, `flush` only starts an async commit) | n/a | Not a read path; unchanged from the File I/O table |

Conclusions, all static: (1) the Redis backend adds network latency to R1 and R2 and nothing else
in this repository reaches it; (2) R2 is the one path where a region tick can block **directly** on
Redis, bounded per step by `timeout-ms` and cached after the first check of a chunk; R1 can stall a
region only through an existing synchronous chunk wait; (3) the item is still open as a runtime
question: a Redis-down or Redis-slow test with a structure search on a region thread and a sync
chunk load on a region thread has not been run (`needs-server`), and plugin-driven
`World#getChunkAt` on a region thread is the same R1 wait and was not separately traced.

## Runtime sampling under plugins and a disk stall (2026-10-22)

Evidence: [region-io-blocking-2026-10-22.md](../development/region-io-blocking-2026-10-22.md) (agent test
server, macOS, no players). Across 1218 `jcmd Thread.print` dumps (9744 region-scheduler thread stacks), with
the world directory on a disk image whose serving process was SIGSTOPped for 60 s:

- **R2 confirmed at runtime**: `/locate` from a command block (region thread) read region files inline
  (`StructureCheck.tryLoadFromStorage` -> `RegionFileStorage.scanChunk` -> `FileChannelImpl.read`) and held
  its region for 51-60 s of the stall (203 stacks per stall run). Not fixed; proposal in the evidence doc.
- **Global tick (0056)**: the periodic `level.dat` write held the global tick in `write0` for the stall
  (92-113 stacks). Fixed by feature patch 0077 / `WorldMetadataAutosave.scheduleLevelDat`: after the fix the
  global tick shows 0 I/O stacks during the stall and the write waits on its own thread.
- Chunk loads/saves ran on the `Paper I/O Worker` pool. The one region-thread wait on that pool was
  `/locate`'s `ChunkTaskScheduler.syncLoadNonFull` (R1 shape, 9 stacks, unstalled run only).
- Not exercised (no players): per-player saves on the owning region (`PlayerList.saveAll` ->
  `PlayerDataStorage.save`, stats, advancements) and the join-time read in `PrepareSpawnTask.spawn`; both are
  synchronous file I/O on a region thread by source reading.

## AbyssLink control connection (2026-10-24)

`abysslink/AbyssLinkConnectionManager` resolves DNS, loads PEM trust/client keys and generates the cryptographic nonce inside the existing bounded `VirtualExecutor`. Its dedicated single Netty event loop performs asynchronous connect, verified TLS and bounded frame I/O only. Startup never waits for the endpoint. Shutdown cancels retries/preparation and closes the channel/group without a synchronous network wait. `AbyssLinkSettings` reads YAML once at bootstrap, outside event loops. `PingCommand` captures `InetSocketAddress` on the target entity owner before invoking `GeoUtil.lookup(address)` on bounded I/O; no production GeoIP worker reads a Player. Control counters remain separate from gameplay wire counters.
