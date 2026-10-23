package dev.iyanz.sourbycraft.legacytest;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

/**
 * The five live failure patterns of the Sourby Demo plugin set, reduced to what a headless fixture can do, run
 * once from {@code onEnable}. Each probe logs {@code LEGACY_RELAXED_*_OK} when the access went through and
 * {@code LEGACY_RELAXED_*_REFUSED} when the engine refused it; which of the two is right depends on
 * {@code aurora.bridge.thread-checks}: relaxed (the owner's default while {@code mode = safe}) expects every
 * {@code _OK}, enforced expects every {@code _REFUSED}. {@code _WRONG} is a probe that ended in neither state.
 * None of this qualifies anything: a probe that passes while relaxed is an access that ran on the wrong thread.
 *
 * <ul>
 *   <li>sweep: a ClearLag-style world sweep from an untargeted Bukkit sync task (the global tick) removing an
 *       entity another region owns;</li>
 *   <li>block write: a global-task block change in a region-owned chunk;</li>
 *   <li>teleport join: {@code Entity#teleport} from a Bukkit async task (Essentials-style off-thread teleport);
 *       relaxed waits for the move and reports {@code true}, enforced schedules and reports {@code false};</li>
 *   <li>global chunk: {@code World#getChunkAt} of an unloaded chunk from the global tick (SuperiorSkyblock2-style
 *       chunk preparation); relaxed loads it synchronously, enforced refuses;</li>
 *   <li>current tick: {@code Bukkit.getCurrentTick()} from the global tick and an async thread (all modes).</li>
 * </ul>
 */
final class RelaxedProbes {

    private final LegacyBridgePlugin plugin;

    RelaxedProbes(final LegacyBridgePlugin plugin) {
        this.plugin = plugin;
    }

    void run() {
        final World world = this.plugin.getServer().getWorlds().get(0);
        this.plugin.getLogger().info("LEGACY_RELAXED_MODE effective=" + effectiveMode());
        currentTick();
        // The subject entity lives in a chunk a region owns; the probes reach it from elsewhere.
        final Location home = new Location(world, 9 * 16 + 8.5, 80, 9 * 16 + 8.5);
        world.getChunkAtAsync(9, 9).thenAccept(chunk -> {
            world.addPluginChunkTicket(9, 9, this.plugin);
            Bukkit.getRegionScheduler().run(this.plugin, home, task -> {
                final ArmorStand stand = (ArmorStand) world.spawnEntity(home, EntityType.ARMOR_STAND);
                final ArmorStand mover = (ArmorStand) world.spawnEntity(home.clone().add(1, 0, 0), EntityType.ARMOR_STAND);
                // Untargeted sync tasks from here run on the global tick (no player context).
                Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, ignored -> {
                    sweep(world, stand);
                    blockWrite(world, home);
                    globalChunk(world);
                }, 5L);
                this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> teleportJoin(mover, home));
            });
        });
    }

    private static String effectiveMode() {
        try {
            return String.valueOf(Class.forName("dev.iyanz.sourbycraft.guards.ThreadChecks").getMethod("effective").invoke(null));
        } catch (final ReflectiveOperationException unavailable) {
            return "unknown";
        }
    }

    private void currentTick() {
        try {
            final int fromEnable = Bukkit.getCurrentTick();
            Bukkit.getGlobalRegionScheduler().run(this.plugin, ignored -> {
                try {
                    final int global = Bukkit.getCurrentTick();
                    this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
                        try {
                            final int async = Bukkit.getCurrentTick();
                            this.plugin.check(global >= fromEnable && async >= global, "LEGACY_CURRENT_TICK",
                                "enable=" + fromEnable + " global=" + global + " async=" + async);
                        } catch (final RuntimeException refused) {
                            this.plugin.getLogger().severe("LEGACY_CURRENT_TICK_WRONG async " + refused);
                        }
                    });
                } catch (final RuntimeException refused) {
                    this.plugin.getLogger().severe("LEGACY_CURRENT_TICK_WRONG global " + refused);
                }
            });
        } catch (final RuntimeException refused) {
            this.plugin.getLogger().severe("LEGACY_CURRENT_TICK_WRONG enable " + refused);
        }
    }

    private void sweep(final World world, final Entity target) {
        try {
            int seen = 0;
            boolean removed = false;
            for (final Entity entity : world.getEntities()) {
                seen++;
                if (entity.getUniqueId().equals(target.getUniqueId())) {
                    entity.remove();
                    removed = entity.isDead() || !entity.isValid();
                }
            }
            this.plugin.check(removed, "LEGACY_RELAXED_SWEEP", "thread=" + Thread.currentThread().getName()
                + " seen=" + seen + " removed=" + removed);
        } catch (final RuntimeException refused) {
            this.plugin.getLogger().info("LEGACY_RELAXED_SWEEP_REFUSED " + refused.getClass().getSimpleName()
                + " thread=" + Thread.currentThread().getName());
        }
    }

    private void blockWrite(final World world, final Location home) {
        try {
            final Location at = home.clone().add(0, -2, 0);
            world.getBlockAt(at).setType(Material.GLOWSTONE, false);
            this.plugin.check(world.getBlockAt(at).getType() == Material.GLOWSTONE, "LEGACY_RELAXED_BLOCK_WRITE",
                "thread=" + Thread.currentThread().getName());
        } catch (final RuntimeException refused) {
            this.plugin.getLogger().info("LEGACY_RELAXED_BLOCK_WRITE_REFUSED " + refused.getClass().getSimpleName()
                + " thread=" + Thread.currentThread().getName());
        }
    }

    private void globalChunk(final World world) {
        final int chunkX = 40;
        final int chunkZ = -40;
        try {
            final long start = System.nanoTime();
            final boolean loaded = world.getChunkAt(chunkX, chunkZ).isLoaded();
            this.plugin.check(loaded, "LEGACY_RELAXED_GLOBAL_CHUNK", "thread=" + Thread.currentThread().getName()
                + " loaded=" + loaded + " ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (final RuntimeException refused) {
            this.plugin.getLogger().info("LEGACY_RELAXED_GLOBAL_CHUNK_REFUSED " + refused.getClass().getSimpleName()
                + " thread=" + Thread.currentThread().getName());
        }
    }

    private void teleportJoin(final Entity mover, final Location home) {
        final AtomicReference<String> outcome = new AtomicReference<>();
        try {
            final Location to = home.clone().add(40, 0, 0); // same region when loaded, else the async route
            final long start = System.nanoTime();
            final boolean result = mover.teleport(to);
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (result) {
                outcome.set("LEGACY_RELAXED_TELEPORT_JOIN_OK result=true ms=" + ms + " thread=" + Thread.currentThread().getName());
            } else {
                outcome.set("LEGACY_RELAXED_TELEPORT_JOIN_REFUSED result=false (scheduled, not waited for) ms=" + ms
                    + " thread=" + Thread.currentThread().getName());
            }
        } catch (final RuntimeException refused) {
            outcome.set("LEGACY_RELAXED_TELEPORT_JOIN_WRONG " + refused);
        }
        final String line = outcome.get();
        if (line.endsWith("_WRONG") || line.contains("_WRONG ")) this.plugin.getLogger().severe(line);
        else this.plugin.getLogger().info(line);
    }
}
