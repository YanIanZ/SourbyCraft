# S2 entity+item Enforcement — Implementation Plan

Spec: docs/superpowers/specs/2026-07-05-s2-entity-item-enforcement-design.md
Repo: /Users/rheninxy/Sourby/SourbyCraft, branch release/26.1.2.
Task 1 = outer only. Task 2 = nested git. Task 3 = driver (rebuild+commit+review).

Verified facts:
- SpigotWorldConfig (paper-server/src/main/java/org/spigotmc/SpigotWorldConfig.java):
  `public double itemMerge` (161), `public int itemDespawnRate` (206),
  `public int hopperCheck` (288), activation ranges lines 212-218
  (`animal/monster/raider/misc/flyingMonster/water/villagerActivationRange`).
- WorldConfiguration.java:586 `public RedstoneImplementation redstoneImplementation` (mutable field).
- MinecraftServer.setPlayerIdleTimeout(int) exists (line ~2256).
- Live per-chunk cap idiom: FallingBlockEntity.java:150-175 (chunk AABB + getEntitiesOfClass).
- Knobs.ENTITY_TICK_RATE: dev/iyanz/sourbycraft/perf/knob/Knobs.java:15.
- PathNavigation.tick() line 270, recomputePath() line 101.
- ItemStack.processDurabilityChange(int, ServerLevel, LivingEntity, boolean) line 665.
- MsgCommand.register executes lambda at line 20-27; TeamMsgCommand + EmoteCommands same package.
- ActivationRange: sourcycraft nested tree at io/papermc/paper/entity/activation/ActivationRange.java.
- SWPlugin.java ~line 114-115: EntityStacker.register(this); OreReveal.register(this);
- EntityStacker cap expression appears 3×: `getMaxStackSize() * MAX_STACK_MULTIPLIER`
  (onItemSpawn, periodicMergeSweep, onItemMerge).

## Task 1: bridges + lag limits + owner protection + stacker cap (outer)

**Files:**
- Create `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/ConfigBridge.java`
- Create `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/LagLimits.java`
- Create `sourbycraft-server/src/main/java/dev/iyanz/sourbycraft/perf/OwnerProtection.java`
- Modify `wildstacker/EntityStacker.java` (cap source)
- Modify `SourbyCraftConfig.java` (pool-enabled boot WARN)
- Modify `swm/plugin/SWPlugin.java` (registrations)

- [ ] **Step 1: ConfigBridge** — new class:

```java
package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Pushes SourbyCraft entity.* master values into the per-world Spigot/Paper
 * config engines. A value is applied ONLY when the operator changed it from
 * the SourbyCraft compiled default — otherwise Spigot/Paper settings win.
 * Zero hot-path cost: runs once per world load.
 */
public final class ConfigBridge implements Listener {

    // Compiled defaults (mirror SourbyCraftConfig initializers).
    private static final int DEF_ITEM_DESPAWN = 6000;
    private static final int DEF_MERGE_RADIUS = 3;
    private static final int DEF_MOB_TICK_DISTANCE = 32;

    private ConfigBridge() {}

    public static void register(Plugin plugin) {
        ConfigBridge bridge = new ConfigBridge();
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        for (org.bukkit.World w : Bukkit.getWorlds()) bridge.apply(w, plugin);
        if (SourbyCraftConfig.idleTimeout > 0) {
            net.minecraft.server.MinecraftServer.getServer().setPlayerIdleTimeout(SourbyCraftConfig.idleTimeout);
            plugin.getLogger().info("[bridge] server.idle-timeout -> " + SourbyCraftConfig.idleTimeout + " min");
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        apply(e.getWorld(), null);
    }

    private void apply(org.bukkit.World world, Plugin plugin) {
        net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        org.spigotmc.SpigotWorldConfig spigot = level.spigotConfig;
        StringBuilder applied = new StringBuilder();

        if (SourbyCraftConfig.itemDespawnRate != DEF_ITEM_DESPAWN) {
            spigot.itemDespawnRate = Math.max(20, SourbyCraftConfig.itemDespawnRate);
            applied.append(" itemDespawnRate=").append(spigot.itemDespawnRate);
        }
        if (!SourbyCraftConfig.itemMergeOptimize) {
            spigot.itemMerge = 0.0;
            applied.append(" itemMerge=off");
        } else if (SourbyCraftConfig.itemMergeRadius != DEF_MERGE_RADIUS) {
            spigot.itemMerge = Math.max(0, SourbyCraftConfig.itemMergeRadius);
            applied.append(" itemMerge=").append(spigot.itemMerge);
        }
        if (SourbyCraftConfig.hopperBatch && spigot.hopperCheck < 4) {
            spigot.hopperCheck = 4;
            applied.append(" hopperCheck=4");
        }
        if (SourbyCraftConfig.mobTickDistance != DEF_MOB_TICK_DISTANCE && SourbyCraftConfig.mobTickDistance > 0) {
            int cap = SourbyCraftConfig.mobTickDistance;
            spigot.animalActivationRange = Math.min(spigot.animalActivationRange, cap);
            spigot.monsterActivationRange = Math.min(spigot.monsterActivationRange, cap);
            spigot.raiderActivationRange = Math.min(spigot.raiderActivationRange, cap);
            spigot.miscActivationRange = Math.min(spigot.miscActivationRange, cap);
            spigot.flyingMonsterActivationRange = Math.min(spigot.flyingMonsterActivationRange, cap);
            spigot.waterActivationRange = Math.min(spigot.waterActivationRange, cap);
            spigot.villagerActivationRange = Math.min(spigot.villagerActivationRange, cap);
            applied.append(" activationRanges<=").append(cap);
        }
        io.papermc.paper.configuration.WorldConfiguration paper = level.paperConfig();
        if (SourbyCraftConfig.redstoneOptimize) {
            if (paper.misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.VANILLA) {
                paper.misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
                applied.append(" redstone=alternate-current");
            }
        } else {
            paper.misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.VANILLA;
            applied.append(" redstone=vanilla");
        }
        if (applied.length() > 0) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("[bridge] " + world.getName() + ":" + applied);
        }
    }
}
```

- [ ] **Step 2: LagLimits** — new class:

```java
package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-gated per-chunk / per-world entity caps (entity.max-per-chunk,
 * entity.max-specials-per-chunk, item.max-per-chunk,
 * entity.max-arrows-per-world). Costs run on spawn events only; caps <= 0
 * disable a gate entirely. Natural/spawner reasons only for creatures —
 * breeding, plugins and commands are never blocked.
 */
public final class LagLimits implements Listener {

    /** Arrow counts per world, refreshed by the 1 Hz sweeper. */
    private static final Map<String, Integer> ARROW_COUNT = new HashMap<>();

    private LagLimits() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new LagLimits(), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, LagLimits::sweepArrows, 100L, 20L);
        plugin.getLogger().info("[lag-limits] active: entity/chunk=" + SourbyCraftConfig.maxEntityPerChunk
            + " specials/chunk=" + SourbyCraftConfig.maxSpecialsPerChunk
            + " items/chunk=" + SourbyCraftConfig.itemMaxPerChunk
            + " arrows/world=" + SourbyCraftConfig.maxArrowsPerWorld);
    }

    private static int chunkCount(Chunk chunk, Class<? extends Entity> type) {
        int n = 0;
        for (Entity e : chunk.getEntities()) {
            if (type.isInstance(e)) n++;
        }
        return n;
    }

    private static boolean isSpecial(Entity e) {
        return e instanceof TNTPrimed || e instanceof ExperienceOrb
            || e instanceof AreaEffectCloud || e instanceof EvokerFangs;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        int cap = SourbyCraftConfig.maxEntityPerChunk;
        if (cap <= 0) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        if (chunkCount(e.getLocation().getChunk(), LivingEntity.class) >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent e) {
        int cap = SourbyCraftConfig.maxSpecialsPerChunk;
        if (cap <= 0 || !isSpecial(e.getEntity())) return;
        int n = 0;
        for (Entity other : e.getLocation().getChunk().getEntities()) {
            if (isSpecial(other)) n++;
        }
        if (n >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        int cap = SourbyCraftConfig.itemMaxPerChunk;
        if (cap <= 0) return;
        if (chunkCount(e.getLocation().getChunk(), Item.class) >= cap) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        if (cap <= 0 || !(e.getEntity() instanceof AbstractArrow)) return;
        Integer count = ARROW_COUNT.get(e.getEntity().getWorld().getName());
        if (count != null && count >= cap) e.setCancelled(true);
    }

    /** 1 Hz: refresh arrow counts; cull oldest grounded arrows beyond cap. */
    private static void sweepArrows() {
        int cap = SourbyCraftConfig.maxArrowsPerWorld;
        for (World world : Bukkit.getWorlds()) {
            List<AbstractArrow> arrows = new ArrayList<>(world.getEntitiesByClass(AbstractArrow.class));
            ARROW_COUNT.put(world.getName(), arrows.size());
            if (cap <= 0 || arrows.size() <= cap) continue;
            arrows.sort((a, b) -> Integer.compare(a.getEntityId(), b.getEntityId())); // oldest first
            int toRemove = arrows.size() - cap;
            for (AbstractArrow arrow : arrows) {
                if (toRemove <= 0) break;
                if (arrow.isInBlock() && !(arrow.getShooter() instanceof org.bukkit.entity.Player p && p.isOnline() && arrow.getTicksLived() < 100)) {
                    arrow.remove();
                    toRemove--;
                }
            }
        }
    }
}
```

- [ ] **Step 3: OwnerProtection** — new class:

```java
package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.Plugin;

/**
 * item.owner-protection: dropped items are pickup-locked to the dropper for
 * owner-protection-time seconds via the vanilla pickup-target (Item#setOwner),
 * then unlocked. Death drops excluded deliberately (killers loot corpses).
 */
public final class OwnerProtection implements Listener {

    private static Plugin OWNER;

    private OwnerProtection() {}

    public static void register(Plugin plugin) {
        OWNER = plugin;
        Bukkit.getPluginManager().registerEvents(new OwnerProtection(), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!SourbyCraftConfig.ownerProtectionEnabled) return;
        int seconds = SourbyCraftConfig.ownerProtectionTime;
        if (seconds <= 0) return;
        Item item = e.getItemDrop();
        java.util.UUID owner = e.getPlayer().getUniqueId();
        item.setOwner(owner);
        Bukkit.getScheduler().runTaskLater(OWNER, () -> {
            if (item.isValid() && owner.equals(item.getOwner())) item.setOwner(null);
        }, seconds * 20L);
    }
}
```

- [ ] **Step 4: EntityStacker cap source** — in `EntityStacker.java` add helper
  below `reload()`:

```java
    /** Effective per-entity amount cap: stacker multiplier bounded by item.drop-stack-cap unless unlimited. */
    private static int effectiveCap(final ItemStack stack) {
        long cap = (long) stack.getMaxStackSize() * MAX_STACK_MULTIPLIER;
        if (!SourbyCraftConfig.unlimitedDropStack) {
            cap = Math.min(cap, Math.max(1, SourbyCraftConfig.dropStackCap));
        }
        return (int) Math.min(Integer.MAX_VALUE, cap);
    }
```

  and replace all 3 `... = <stack>.getMaxStackSize() * MAX_STACK_MULTIPLIER;`
  cap computations (`onItemSpawn`, `periodicMergeSweep`, `onItemMerge`) with
  `... = effectiveCap(<stack>);` keeping each local variable name.

- [ ] **Step 5: pool WARN** — in `SourbyCraftConfig.java`, right after
  `itemPoolShrinkThreshold = ...` read, add:

```java
        if (itemPoolEnabled) {
            Bukkit.getLogger().warning("[SourbyCraft] item.pool-enabled: true but the ItemEntityPool engine is offline "
                + "(removed for the levitation bug; keys reserved for pool v2). No pooling occurs.");
        }
```

- [ ] **Step 6: registrations** — in `SWPlugin.java` after `OreReveal.register(this);`:

```java
        dev.iyanz.sourbycraft.perf.ConfigBridge.register(this);
        dev.iyanz.sourbycraft.perf.LagLimits.register(this);
        dev.iyanz.sourbycraft.perf.OwnerProtection.register(this);
```

- [ ] **Step 7: compile** `./gradlew :sourbycraft-server:compileJava -q` → BUILD SUCCESSFUL.
  API drift latitude: CraftWorld package (`org.bukkit.craftbukkit.CraftWorld`),
  `Item#setOwner/getOwner` availability, `AbstractArrow#isInBlock`,
  `RedstoneImplementation` enum location, `getEntitiesByClass` name. Record all.

- [ ] **Step 8: outer commit** — message:
  `perf: wire entity/item config keys — spigot-paper bridges, per-chunk caps, arrow cap, owner protection, drop-stack cap`

## Task 2: NMS gates (nested git)

**Files (all under sourbycraft-server/src/minecraft/java):**
- `io/papermc/paper/entity/activation/ActivationRange.java`
- `net/minecraft/world/entity/ai/navigation/PathNavigation.java`
- `net/minecraft/world/item/ItemStack.java`
- `net/minecraft/server/commands/MsgCommand.java`
- `net/minecraft/server/commands/TeamMsgCommand.java`
- `net/minecraft/server/commands/EmoteCommands.java`

- [ ] **Step 1: entity tick-rate limit** — in `ActivationRange`, find the method
  that decides whether an inactive entity still ticks this tick (`checkIfActive`
  or equivalent — the branch where an entity outside activation range gets its
  reduced cadence). At the point where an inactive entity is granted a tick,
  add the SourbyCraft gate:

```java
        // SourbyCraft S2 - entity.tick-rate-limit: stretch inactive-entity cadence to entity.tick-rate
        if (dev.iyanz.sourbycraft.SourbyCraftConfig.entityTickRateLimit) {
            final int rate = dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.get();
            if (rate > 0 && rate < 20 && (entity.tickCount % Math.max(1, 20 / rate)) != 0) {
                return false; // skip this inactive tick grant
            }
        }
```

  Adapt `entity`/return-shape to the real method (report the adaptation).
  Active (in-range) entities MUST remain untouched.

- [ ] **Step 2: pathfind interval** — in `PathNavigation`, add field
  `private int sourbyLastRecompute = Integer.MIN_VALUE;` and at the very top of
  `recomputePath()` (line ~101):

```java
        // SourbyCraft S2 - entity.mob-pathfind-interval: floor between path recomputes
        final int sourbyInterval = dev.iyanz.sourbycraft.SourbyCraftConfig.mobPathfindInterval;
        if (sourbyInterval > 1 && this.tick - this.sourbyLastRecompute < sourbyInterval) {
            return;
        }
        this.sourbyLastRecompute = this.tick;
```

- [ ] **Step 3: no durability** — in `ItemStack.processDurabilityChange(final int
  amount, final ServerLevel level, final @Nullable LivingEntity player, final
  boolean force)` (line ~665), after the `isDamageableItem()` early-return add:

```java
        // SourbyCraft S2 - item.no-durability-except: server-wide durability freeze
        else if (dev.iyanz.sourbycraft.SourbyCraftConfig.noDurabilityExcept && !force) {
            return 0;
        }
```

  (keep else-if chain valid — adapt braces to the real structure).

- [ ] **Step 4: communication command gates** — in each of `MsgCommand`
  (executes lambda, line ~20), `TeamMsgCommand`, `EmoteCommands` (their
  `.executes(...)` lambdas), insert as first statement:

```java
                    // SourbyCraft S2 - settings.disable-communication-commands
                    if (dev.iyanz.sourbycraft.SourbyCraftConfig.disableCommunicationCommands) {
                        c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("Communication commands are disabled on this server."));
                        return 0;
                    }
```

  (lambda parameter name may differ; adapt).

- [ ] **Step 5: compile** → BUILD SUCCESSFUL.
- [ ] **Step 6: nested commit** — add the 6 files, message:
  `SourbyCraft S2: entity tick-rate limit, pathfind interval, durability freeze, communication-command gates`

## Task 3 (driver): rebuild + commit + review

- Nested preflight → `./gradlew rebuildMinecraftFeaturePatches`
- `git add patches/minecraft/` + outer commit
  `perf: S2 NMS gates — tick-rate limit, pathfind interval, durability, comm-commands (feature patch)`
- Combined S2 review (sonnet), fixes, ledger.
