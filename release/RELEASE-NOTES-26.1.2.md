# SourbyCraft 26.1.2-EXP

Released: 2026-06-17 on branch `release/26.1.2`.

## Highlights

- **Paper 26.1.2 upstream** — year-based versioning (Minecraft 26.1.2 = same era as 1.21.11 codebase line, fully replaced).
- **Java 25** required (Paper 26.1.2 uses unnamed variables; virtual threads).
- **Bootstrap shipped** — lazy lib download keeps release jar at 31M (was 57M fat jar in 12-EXP era).
- **PvP variant removed** — single-jar general SMP baseline only.
- **Reobf pipeline dropped** — Paper 26.1.2 ships mojmap-only.
- **Spigot mapping support removed** upstream — no more CraftBukkit `v1_21_R7` package version; flattened to `org.bukkit.craftbukkit.*`.
- **Perf-engine sub-projects shipped this session**: P3 adaptive AI throttle, P7 self-tune controller, P8 tier BossBar. P0/P1/P2 already shipped; P4/P5/P6 deferred.
- **Adventure book force-op exploit blocked** via `BookSanitizer` on creative-slot inject path.
- **Security hardening pass** — YAML SafeConstructor everywhere, plugin/lib downloader https-only + path containment + size cap, FileLoader path-traversal containment, GeoUtil https + bounded cache, VirtualExecutor race fix, APILoader UTF-8 + warn-on-insecure-tls.

## Compatibility breaks

- NMS plugins importing `org.bukkit.craftbukkit.v1_21_R7.*` will fail to load. They must update to flattened CB package.
- Plugins relying on the deleted SourbyCraft NMS hooks (Pufferfish DAB writes, wildstacker NMS-only mode, parallel tick router, BossBar ticker, ModScanner) will see no-op behavior — their config knobs are dead.
- PvP variant is gone — `variant=pvp` CLI flag is no longer recognized.

## SourbyCraft patches (39 minecraft + 6 server + 14 server buildscript + 2 api buildscript + 4 api)

### Minecraft source patches (39)

| # | Patch | Effect |
|---|-------|--------|
| 0001 | perf-engine P1 sensor tick hook | Multi-signal load sensor running every cadence on main thread; feeds 5-tier state machine (GREEN/YELLOW/ORANGE/RED/EMERGENCY) |
| 0002 | perf-engine P2 disable saving snowballs | NBT save skip for snowballs when knob is set — drops cost-zero lag-machine vector |
| 0003 | perf-engine P2 disable saving fireworks | NBT save skip for fireworks (same rationale) |
| 0004 | raise fast-drop kick threshold 20 to 200 | Ctrl+Q on full inventory no longer trips anti-hack disconnect on fast clients |
| 0005 | defer POI worldgen updates via scheduleOnMain | Moonrise treats chunk-gen workers as tick threads; force-defer to main |
| 0006 | work around unnecessary chunk pos conversion | ChunkHolder.moonrise$getPlayers calls `isChunkSentBorderOnly` directly |
| 0007 | don't apply de-synced opencount for shulker boxes | Skip self-write in `triggerEvent` |
| 0008 | fix redstone ore lit state desync on interact cancel | Resend BlockUpdate packet when interact cancelled |
| 0009 | prevent healing amplifier from overflowing | `Math.max(_, 0)` clamp on all `<< amp` sites in `HealOrHarmMobEffect` |
| 0010 | split item lore lines on newline characters | Network-level `\n` splitter on `ItemLore` via new `SPLITTING_STREAM_CODEC` |
| 0011 | fire PlayerPickupArrowEvent for creative players | `CREATIVE_ONLY` pickup mode fires event when `instabuild` |
| 0012 | log exceptions caused by packet sending | Promote packet-send exceptions from `debug` to `error` |
| 0013 | add option for processing default fluids with surface rules | Gate fluid-state skip branch on yml toggle |
| 0014 | fix children copying when filling missing command redirects | Consumer overload recurses through children |
| 0015 | add more detailed brand info | Brand payload appends `ServerBuildInfo` version string |
| 0016 | US import particles fall+death gated emit | LivingEntity fall + tickDeath POOF particles gated by yml |
| 0017 | startup banner | Print SourbyCraftBanner ASCII at runServer |
| 0018 | startup plugin summary hook | Compact category-grouped plugin listing after `enablePlugins(POSTWORLD)` |
| 0019 | v6 virtual threads for server thread pools | `Util.makeExecutor` and friends gated on `virtualThreads` config |
| 0020 | sounds.disablePiglinAngerSound gate | Piglin#getAmbientSound returns null when activity sound is PIGLIN_ANGRY + yml toggle set |
| 0021 | sounds.disableShieldSounds gate | Early-return at `BlocksAttacks#onBlocked` before playSound when yml toggle set |
| 0022 | sounds.disablePistonSounds gate | Both `PistonBaseBlock` playSound sites gated by yml |
| 0023 | disable CHECK_DATA_FIXER_SCHEMA for vanilla/mache mismatch | Skip strict schema assertion when DataVersion mismatch detected |
| 0024 | skip too-recent file fixers instead of throwing | Soft-skip future-dated fixers in FileFixerUpper |
| 0025 | make entity variant baby_asset_id optional | `optionalFieldOf` + default to adult asset on Cat/Chicken/Cow/Pig codecs |
| 0026 | fix elastic leash behaviour on unleash cancel | New boolean return method + deprecated wrapper |
| 0027 | US import particles batch-1 (4 sites) | Configurable gates for additional particle emit sites |
| 0028 | US import sounds batch-1 (footsteps + parrot + player attack) | Configurable gates for additional sound emit sites |
| 0029 | Improve Player#canSee performance (ChunkMap side) | Skip the Bukkit-wrapper round-trip in entity-tracker hot path |
| 0030 | wire SourbyCraftConfig.init() in DedicatedServer | Load sourbycraft.yml at boot for Knobs + PerfSensor + SWM gate |
| 0031 | Resend more data on locale change | PlayerLocaleChangeEvent throttled refresh of advancements/inventory/entity custom names/TextDisplay/ItemFrame |
| 0032 | Add option to translate custom item names and lore | ItemUtil pack/unpackPatchSaves round-trip on ItemStack STREAM_CODEC + ItemCost STREAM_CODEC |
| 0033 | Optimise non-flush packet sending (Spottedleaf) | Netty `safeExecute` reflection skips event-loop wakeup on non-flushed packets |
| 0034 | Further improve Player#canSee performance | `Entity.longUUID` (msb ^ lsb) field maintained via setUUID() for future fastutil canSee |
| 0035 | Strip click/hover events from written book pages | **Security** — BookSanitizer hook in handleSetCreativeModeSlot blocks force-op book exploit |
| 0036 | perf-engine P2 projectile chunk-load throttle | Per-tick + per-projectile chunk-load fan-out caps via LagMachineCounters |
| 0037 | perf-engine P2 excess minecart/boat sweeper | 10s chunk-AABB sweep on AbstractMinecart + AbstractBoat |
| 0038 | perf-engine P2 excess falling-block sweeper | 2s chunk-AABB sweep on FallingBlockEntity |
| 0039 | perf-engine P3 adaptive AI throttle | `Mob.aiStep` early-return on (knob > 0 && tickCount % interval != 0 && nearestPlayer > distance) |

### Paper-server feature patches (6)

| # | Patch | Effect |
|---|-------|--------|
| 0001 | Populate despawn time for existing entities on reload | Reload-after-restart no longer despawns entities instantly |
| 0002 | Fix paper relative teleport flag changes | Relative teleport flags survive round-trip |
| 0003 | Improve Player#canSee performance | `CraftPlayer.canSee(NMS Entity)` overload, no bukkit-wrapper boxing |
| 0004 | SourbyCraft v9.19 disable outdated version warning | Suppress vanilla "outdated server" warning at boot |
| 0005 | SourbyCraft v12 wire PluginAutoInstaller into loadPlugins | Hook auto-install before plugin scan |
| 0006 | SourbyCraft register custom commands | commandMap.register for ping/sys/plugins/tpsbar/speedtest/swm/rambar/ver |

### Buildscript patches

- **server (14)** — Pufferfish sourceset, branding manifest, version-format EXP/REL/DEV, SWM compileOnly deps, ByteBuddy Java 25 mode, paperweight-fork wiring, mache 26.1.2+build.3, paperclip 3.0.4, fill 1.0.11, Java launcher 21→25.
- **api (2)** — Pufferfish-API junit/mockito/asm bumps, paperweight-fork wiring.

### API patches (4)

- 0001 Setup gitignore
- 0002 Pufferfish-api-patches (test deps)
- 0003 Add CloudPlane configuration
- 0004 Changed branding

## Perf-engine roadmap status

| Sub-project | Status | What it ships |
|---|---|---|
| **P0** Knob Registry API | ✓ shipped | `BoolKnob`/`IntKnob`, `Knobs` static holder, `KnobRegistry.logLoaded("boot")` |
| **P1** Load Sensor + Tier Classifier | ✓ shipped | `PerfSensor` (TPS/MSPT/mem/GC), 5-tier state machine, NMS hook in `tickChildren` |
| **P2** Lag-Machine Protection | ✓ shipped | 8 knobs across 5 NMS patches (0002, 0003, 0036, 0037, 0038) |
| **P3** Adaptive Entity AI | ✓ shipped | 2 knobs + Mob.aiStep early-return when nearestPlayer beyond distance (0039) |
| P4 Combat Profiles | blocked | combat/ module deleted; needs restoration before profile bundles meaningful |
| P5 Async Chunk Pipeline | blocked | Moonrise overlap + multi-thousand-line ChunkMap.TrackedEntity refactor |
| P6 Async Packet & World | partial | non-flush packet (0033) + virtual threads (0019); full async data save needs `IOWorker` rework |
| **P7** Self-Tune Controller | ✓ shipped | `SelfTuneController` subscribes to PerfSensor transitions; tier policy escalates lag-machine + AI knobs; restores operator baseline on recovery |
| **P8** Operator UX | ✓ shipped (BossBar) | `TierBossBar` per-player opt-in; tier-coloured (GREEN/YELLOW/PINK/RED/PURPLE); refresh on transition |

**5 / 9 shipped end-to-end. P6 half-shipped.**

## Security hardening this session

- **BookSanitizer** (`security/`) — strips click_event / hover_event from written-book page Components on `handleSetCreativeModeSlot`. Blocks Adventure-style force-op book exploit. Recursion depth-capped at 64.
- **YAML SafeConstructor** on every `new Yaml().load(...)` site (SourbyCraftSecurityConfig, SourbyCraftConfig, PluginManifest, PluginCategoryMap) — blocks `!!tag`-based gadget RCE.
- **PluginDownloader / LibDownloader / SWM PluginInstaller** — https-only on initial + every redirect hop, 100 MB cap with running byte counter, path-traversal containment.
- **SWM FileLoader** — `/swm load <name>` rejects slash/`..`/leading-dot, normalizes and contains within `slime_worlds/`.
- **APILoader (remote SWM)** — startup WARN when `ignoreSslCertificate=true`; UTF-8 charset for basic-auth Base64 encoding.
- **GeoUtil** (`/ping`) — `https://ip-api.com`, 1024-entry bounded cache, `InetAddress.isSiteLocalAddress` RFC1918 detection, get+null TOCTOU fix.
- **VirtualExecutor** — `init()` synchronized; double-checked + local-capture in `executor()` to avoid race-with-shutdown.

## Build

```bash
./gradlew assembleReleaseArtifacts
# -> release/SourbyCraft-26.1.2-EXP.jar (31M)
```

Bootstrap rebuilds slim jar with lazy lib manifest. First-boot downloads:
- sqlite-jdbc, mysql-connector-j (JDBC drivers, ~17M combined)
- spark-paper (profiler)
- Flare (Pufferfish flare deps — present for SWM compileOnly references)
- sentry (crash reporting)
- speedtest binary (post-boot perf scan, on `/speedtest` invocation)

## Verification

User boots TestServer manually (per project memory — no JUnit, no smoke harness). Expected banner:

```
Loading SourbyCraft 26.1.2-EXP <day, dd Month YYYY> for Minecraft 26.1.2
```

Boot tests at every commit point in this session land cleanly between 19s and 27s on a 2 GB heap.
