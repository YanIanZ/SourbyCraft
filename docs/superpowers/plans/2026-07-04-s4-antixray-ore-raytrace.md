# S4 Antixray Ore Raytrace — Implementation Plan

Spec: docs/superpowers/specs/2026-07-04-s4-antixray-ore-raytrace-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2.
Task 1 = outer repo only. Task 2 = nested git (sourbycraft-server/src/minecraft/java).
Task 3 = rebuild patches + outer commit (driver-run).

## Task 1: Reveal pipeline + config + per-world wiring (outer)

**Files:**
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftConfig.java`
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/SourbyCraftWorldConfig.java`
- Create `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/antixray/OreReveal.java`
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/antixray/OcclusionUtil.java`
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/antixray/RayTraceWorker.java`
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/antixray/EntityVisibilityCheck.java`
- Modify `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/swm/plugin/SWPlugin.java`

- [ ] **Step 1: config fields** — in `SourbyCraftConfig.java`, directly under
  `public static boolean fluidObscures = true;` (~line 240) add:

```java
    public static int raytraceIntervalTicks = 10;
    public static int raytraceDistance = 48;
    public static int raytraceMaxChecksPerCycle = 192;
    public static int raytraceMaxPendingPerPlayer = 8192;
```

- [ ] **Step 2: config load** — inside the existing
  `// SourbyCraft - RayTraceAntiXray opt-in toggle` try-block (~line 315),
  after the three `ENABLED.set(...)` calls and BEFORE the `} catch`, add:

```java
            raytraceIntervalTicks = Math.max(1, getInt("antixray.raytrace.interval-ticks", raytraceIntervalTicks));
            raytraceDistance = Math.max(8, Math.min(128, getInt("antixray.raytrace.distance", raytraceDistance)));
            raytraceMaxChecksPerCycle = Math.max(16, Math.min(2048, getInt("antixray.raytrace.max-checks-per-cycle", raytraceMaxChecksPerCycle)));
            raytraceMaxPendingPerPlayer = Math.max(512, Math.min(65536, getInt("antixray.raytrace.max-pending-per-player", raytraceMaxPendingPerPlayer)));
```

- [ ] **Step 3: world-config holder** — in `SourbyCraftWorldConfig.java`, right
  after the constructor (before `init()`), add:

```java
    // SourbyCraft S4 - lazy per-world holder. Main-thread call sites only
    // (chunk send, entity tracker, scheduler); ConcurrentHashMap is defensive.
    private static final java.util.concurrent.ConcurrentHashMap<String, SourbyCraftWorldConfig> BY_WORLD =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static SourbyCraftWorldConfig get(net.minecraft.server.level.ServerLevel level) {
        org.bukkit.World w = level.getWorld();
        return BY_WORLD.computeIfAbsent(w.getName(), n -> new SourbyCraftWorldConfig(n, w.getEnvironment()));
    }
```

- [ ] **Step 4: OcclusionUtil fluid overload** — replace the single method with:

```java
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to) {
        return isVisible(level, from, to, false);
    }

    /** @param fluidObscures when true the ray also collides with fluids (water hides ores). */
    public static boolean isVisible(final Level level, final Vec3 from, final Vec3 to, final boolean fluidObscures) {
        if (level == null) return false;
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER,
            fluidObscures ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
            (net.minecraft.world.entity.Entity) null);
        HitResult hit = level.clip(ctx);
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }
```

- [ ] **Step 5: RayTraceWorker fluid flag** — in `submit(...)`, after
  `final Vec3 ore = Vec3.atCenterOf(orePos);` insert:

```java
        final boolean fluidObscures = dev.iyanz.sourbycraft.SourbyCraftConfig.fluidObscures
            && dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get(level).fluidObscures;
```

  and change the lambda's check to
  `if (OcclusionUtil.isVisible(level, eye, ore, fluidObscures)) {`.

- [ ] **Step 6: EntityVisibilityCheck per-world gate** — after the
  `if (player.level() != entity.level()) return true;` line insert:

```java
        // SourbyCraft S4 - per-world gate + range (world-settings.<world>.anticheat.anti-xray)
        final dev.iyanz.sourbycraft.SourbyCraftWorldConfig wc =
            dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get((net.minecraft.server.level.ServerLevel) player.level());
        if (!wc.entityObfuscation) return true;
```

  and after the NEAR_DISTANCE_SQUARED bypass insert:

```java
        // Beyond the configured range the tracker's own range governs; skip the clip cost.
        final double range = wc.entityObfuscationRange;
        if (eye.distanceToSqr(centre) > range * range) return true;
```

- [ ] **Step 7: OreReveal (new file)** — create
  `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/antixray/OreReveal.java`:

```java
package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftWorldConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exposed-ore hide/reveal layer above Paper's anti-xray engine.
 *
 * <p>Paper engine-mode 1 hides ores that are fully enclosed; ores exposed to a
 * cave surface still leak through walls. This layer hides those on chunk send
 * (per-player fake block updates) and reveals each one when the existing
 * {@link RayTraceWorker} confirms actual line of sight, or instantly within
 * {@value #NEAR_DISTANCE} blocks (mining UX).
 *
 * <p>All state main-thread except {@link VisibilityCache} (concurrent) which
 * is the async worker handoff. Disabled ({@code antixray.raytrace.enabled:
 * false}) → {@link #onChunkSent} is a single volatile read.</p>
 */
public final class OreReveal implements Listener {

    private static final double NEAR_DISTANCE = 8.0;
    private static final double NEAR_DISTANCE_SQUARED = NEAR_DISTANCE * NEAR_DISTANCE;

    /** Per-player pending hidden-ore positions (BlockPos.asLong). Main-thread only. */
    private static final Map<UUID, LongOpenHashSet> PENDING = new ConcurrentHashMap<>();

    private static Plugin OWNER;

    private OreReveal() {}

    public static void register(Plugin plugin) {
        OWNER = plugin;
        Bukkit.getPluginManager().registerEvents(new OreReveal(), plugin);
        long interval = Math.max(1, SourbyCraftConfig.raytraceIntervalTicks);
        Bukkit.getScheduler().runTaskTimer(plugin, OreReveal::tickCycle, interval, interval);
        plugin.getLogger().info("[antixray] ore reveal " + (RayTraceWorker.ENABLED.get() ? "ENABLED" : "disabled")
            + " (interval=" + interval + "t distance=" + SourbyCraftConfig.raytraceDistance
            + " checks/cycle=" + SourbyCraftConfig.raytraceMaxChecksPerCycle + ")");
    }

    /** NMS hook (PlayerChunkSender.sendChunk, main thread, after the chunk packet went out). */
    public static void onChunkSent(final ServerPlayer player, final LevelChunk chunk) {
        if (!RayTraceWorker.ENABLED.get() || player == null || chunk == null) return;
        final ServerLevel level = (ServerLevel) chunk.getLevel();
        final SourbyCraftWorldConfig wc = SourbyCraftWorldConfig.get(level);
        final boolean fluidObscures = SourbyCraftConfig.fluidObscures && wc.fluidObscures;
        final java.util.List<Block> extraHidden = wc.allBlocks
            ? level.paperConfig().anticheat.antiXray.hiddenBlocks : java.util.List.of();

        final LongOpenHashSet pending = PENDING.computeIfAbsent(player.getUUID(), id -> new LongOpenHashSet());
        final int maxPending = SourbyCraftConfig.raytraceMaxPendingPerPlayer;
        final int chunkX = chunk.getPos().x() << 4;
        final int chunkZ = chunk.getPos().z() << 4;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final LevelChunkSection[] sections = chunk.getSections();

        for (int idx = 0; idx < sections.length; idx++) {
            final LevelChunkSection section = sections[idx];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.getStates().maybeHas(state -> isCandidate(state, extraHidden))) continue;
            final int yBase = chunk.getSectionYFromSectionIndex(idx) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (!isCandidate(state, extraHidden)) continue;
                        final int wx = chunkX + x, wy = yBase + y, wz = chunkZ + z;
                        if (!isExposed(level, chunk, wx, wy, wz, fluidObscures, pos)) continue;
                        if (pending.size() >= maxPending) return; // budget full: remaining ores stay visible (fail-open)
                        pos.set(wx, wy, wz);
                        final long key = pos.asLong();
                        if (VisibilityCache.isVisible(player.getUUID(), key)) continue; // already confirmed visible
                        player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), fakeState(level, wy)));
                        pending.add(key);
                    }
                }
            }
        }
    }

    private static boolean isCandidate(final BlockState state, final java.util.List<Block> extraHidden) {
        if (state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.EMERALD_ORES)
            || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES)
            || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS)) {
            return true;
        }
        if (!extraHidden.isEmpty()) {
            final Block block = state.getBlock();
            for (int i = 0; i < extraHidden.size(); i++) {
                if (extraHidden.get(i) == block) return true;
            }
        }
        return false;
    }

    /** Exposed = any of the 6 neighbours is non-occluding (and, with fluid-obscures, not a fluid). */
    private static boolean isExposed(final ServerLevel level, final LevelChunk chunk,
                                     final int x, final int y, final int z,
                                     final boolean fluidObscures, final BlockPos.MutableBlockPos pos) {
        for (net.minecraft.core.Direction dir : DIRECTIONS) {
            final int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
            if (ny < level.getMinY() || ny > level.getMaxY()) continue;
            pos.set(nx, ny, nz);
            final BlockState neighbor;
            if ((nx >> 4) == chunk.getPos().x() && (nz >> 4) == chunk.getPos().z()) {
                neighbor = chunk.getBlockState(pos);
            } else {
                final LevelChunk nc = level.getChunkIfLoaded(nx >> 4, nz >> 4);
                if (nc == null) continue; // unloaded neighbour counts as obscuring
                neighbor = nc.getBlockState(pos);
            }
            if (neighbor.canOcclude()) continue;
            if (fluidObscures && !neighbor.getFluidState().isEmpty()) continue;
            return true;
        }
        return false;
    }

    private static final net.minecraft.core.Direction[] DIRECTIONS = net.minecraft.core.Direction.values();

    private static BlockState fakeState(final ServerLevel level, final int y) {
        return switch (level.getWorld().getEnvironment()) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case THE_END -> Blocks.END_STONE.defaultBlockState();
            default -> y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        };
    }

    /** Main-thread cycle: reveal confirmed positions, submit raytraces for near pending ones. */
    private static void tickCycle() {
        if (!RayTraceWorker.ENABLED.get() || PENDING.isEmpty()) return;
        final double distance = SourbyCraftConfig.raytraceDistance;
        final double distanceSq = distance * distance;
        for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
            final LongOpenHashSet pending = PENDING.get(player.getUUID());
            if (pending == null || pending.isEmpty()) continue;
            final ServerLevel level = player.level();
            final Vec3 eye = player.getEyePosition();
            int budget = SourbyCraftConfig.raytraceMaxChecksPerCycle;
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            final LongIterator it = pending.iterator();
            while (it.hasNext()) {
                final long key = it.nextLong();
                pos.set(key);
                final double dsq = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                final boolean confirmed = VisibilityCache.isVisible(player.getUUID(), key);
                if (confirmed || dsq <= NEAR_DISTANCE_SQUARED) {
                    if (level.isLoaded(pos)) {
                        player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), level.getBlockState(pos)));
                    }
                    it.remove();
                    continue;
                }
                if (dsq <= distanceSq && budget > 0) {
                    RayTraceWorker.submit(player, pos.immutable());
                    budget--;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        final UUID id = e.getPlayer().getUniqueId();
        PENDING.remove(id);
        VisibilityCache.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        // BlockPos long keys are world-agnostic — never leak across dimensions.
        final UUID id = e.getPlayer().getUniqueId();
        PENDING.remove(id);
        VisibilityCache.clear(id);
    }
}
```

- [ ] **Step 8: SWPlugin registration** — in `SWPlugin.java`, directly after
  `dev.iyanz.sourbycraft.wildstacker.EntityStacker.register(this);` (line ~114) add:

```java
        dev.iyanz.sourbycraft.antixray.OreReveal.register(this);
```

- [ ] **Step 9: Compile** — `./gradlew :sourbycraft-server:compileJava -q` → BUILD SUCCESSFUL.
  API-drift fixes allowed (report them): e.g. `ServerPlayer.level()` return type,
  `LevelChunkSection.getBlockState(x,y,z)` name, `level.isLoaded(pos)`,
  `chunk.getSectionYFromSectionIndex`, `paperConfig().anticheat.antiXray.hiddenBlocks`
  element type (must be `Block`; adapt the contains-check if it is a holder/tag type).

- [ ] **Step 10: Outer commit** —
  `git add` the seven files, commit:
  `antixray: ore raytrace reveal pipeline — hide exposed ores on chunk send, per-world anti-xray config wiring`

## Task 2: NMS hook (nested git)

**File:** `sourbycraft-server/src/minecraft/java/net/minecraft/server/network/PlayerChunkSender.java`

- [ ] In `public static void sendChunk(...)`, after the
  `// Paper end - PlayerChunkLoadEvent` block, insert:

```java
        // SourbyCraft - antixray ore raytrace: hide exposed ores post-send, reveal via raytrace (S4)
        dev.iyanz.sourbycraft.antixray.OreReveal.onChunkSent(connection.player, chunk);
```

- [ ] Compile: `./gradlew :sourbycraft-server:compileJava -q` → BUILD SUCCESSFUL.
- [ ] Nested commit:
  `git -C sourbycraft-server/src/minecraft/java add net/minecraft/server/network/PlayerChunkSender.java`
  `git -C sourbycraft-server/src/minecraft/java commit -m "SourbyCraft antixray: ore-reveal hook in PlayerChunkSender.sendChunk"`

## Task 3 (driver): rebuild + commit + review

- Preflight nested clean → `./gradlew rebuildMinecraftFeaturePatches`
- `git add patches/minecraft/` + outer commit
  `antixray: wire ore-reveal chunk-send hook (feature patch)`
- Combined review (Tasks 1+2), then artifact at S-run end.
