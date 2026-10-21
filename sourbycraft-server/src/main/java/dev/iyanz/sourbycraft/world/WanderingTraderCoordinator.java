package dev.iyanz.sourbycraft.world;

import dev.iyanz.sourbycraft.execution.Admission;
import dev.iyanz.sourbycraft.execution.OwnerHandoff;
import dev.iyanz.sourbycraft.execution.RegionOwnerHandoff;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WanderingTraderData;

/** One global-owned timer and at most one outstanding player-owner attempt per world. */
public final class WanderingTraderCoordinator {
    private long lastTick = Long.MIN_VALUE;
    private boolean pending;
    private boolean initializeDefaults;

    /** Only newly created saved data uses Paper defaults; decoded files keep their values. */
    public void initializeNewData() { initializeDefaults = true; }

    record Settings(int minute, int day, int minimum, int maximum, int increment) {}

    /** The timer's environment; production uses the world clock and entity-owner handoff. */
    interface Backend<P> {
        long tick();
        boolean enabled();
        List<P> players();
        int random(int bound);
        OwnerHandoff<P> handoff(P player);
        boolean eligible(P player);
        boolean ownerEnabled(P player);
        boolean spawn(P player);
        void onGlobal(Runnable task);
    }

    /**
     * Called once per world after global game time advances by {@code elapsedTicks} (more than
     * one when the global tick catches up); never from a region tick.
     */
    public static void tick(final ServerLevel level, final long elapsedTicks) {
        RegionOwnership.current().ensureGlobalTickThread("Wandering trader timers belong to the global tick");
        if (!level.sourby$hasWanderingTraderSpawner()) return;
        final var config = level.paperConfig().entities.spawning.wanderingTrader;
        final WanderingTraderData data = level.getDataStorage().computeIfAbsent(WanderingTraderData.TYPE);
        data.coordinator.tick(data, new Settings(config.spawnMinuteLength, config.spawnDayLength,
            config.spawnChanceMin, config.spawnChanceMax, config.spawnChanceFailureIncrement), elapsedTicks, new Backend<Entity>() {
                public long tick() { return level.getGameTime(); }
                public boolean enabled() {
                    return !level.isDebug() && level.getGameRules().get(GameRules.SPAWN_MOBS)
                        && level.getGameRules().get(GameRules.SPAWN_WANDERING_TRADERS);
                }
                public List<Entity> players() {
                    // ServerLevel.players() is CopyOnWriteArrayList. Only copy references here;
                    // level, position, alive and connection state are read on the owner's thread.
                    return List.copyOf(level.players());
                }
                public int random(final int bound) { return ThreadLocalRandom.current().nextInt(bound); }
                public OwnerHandoff<Entity> handoff(final Entity player) { return RegionOwnerHandoff.forEntity(player); }
                public boolean eligible(final Entity entity) {
                    RegionOwnership.current().ensureTickThread(entity, "Wandering trader target must be region-owned");
                    return entity instanceof ServerPlayer player && player.level() == level
                        && !player.isRemoved() && player.isAlive() && !player.connection.isDisconnected()
                        && level.players().contains(player);
                }
                public boolean ownerEnabled(final Entity player) { return enabled(); }
                public boolean spawn(final Entity player) {
                    // POI radius 48 + spawn radius 48 + llama radius 4 + block footprint.
                    // Refuse a boundary attempt rather than reading/loading another region.
                    if (!ownsSearch(level, player.blockPosition(), 104)) return false;
                    return new WanderingTraderSpawner(level.getDataStorage()).spawnForPlayer(level, (ServerPlayer) player);
                }
                public void onGlobal(final Runnable task) { RegionOwnership.current().executeOnGlobalTick(task); }
            });
    }

    /** All state writes (including completion) are global-owned. No task backlog per world. */
    <P> void tick(final WanderingTraderData data, final Settings settings, final long elapsedTicks, final Backend<P> backend) {
        final long now = backend.tick();
        if (lastTick == now) return;
        lastTick = now;
        if (pending || !backend.enabled()) return;
        final List<P> players = backend.players();
        // Custom spawners previously only ticked with mob spawning enabled and players present.
        if (players.isEmpty()) return;
        if (initializeDefaults) {
            data.setSpawnDelay(settings.day());
            data.setSpawnChance(settings.minimum());
            initializeDefaults = false;
        }
        if (data.tickDelay == Integer.MIN_VALUE) {
            // Older saved files have delay/chance but no minute countdown. Preserve both.
            data.setTickDelay(settings.minute());
        }
        // Count down by the game ticks that actually elapsed (vanilla: one per tick, fires at <= 1).
        final long elapsed = Math.max(1L, elapsedTicks);
        if (data.tickDelay > elapsed) {
            data.setTickDelay((int) (data.tickDelay - elapsed));
            return;
        }
        data.setTickDelay(settings.minute());
        data.setSpawnDelay(data.spawnDelay() - settings.minute());
        if (data.spawnDelay() > 0) return;
        data.setSpawnDelay(settings.day());
        final int chance = data.spawnChance();
        data.setSpawnChance((int) Math.clamp((long) chance + settings.increment(), settings.minimum(), settings.maximum()));
        final P chosen = players.get(backend.random(players.size()));
        pending = true;
        final Runnable absent = () -> backend.onGlobal(() -> finish(data, settings, backend.random(100) <= chance));
        final Admission admission = backend.handoff(chosen).submit(current -> {
            boolean resetChance = false;
            try {
                if (!backend.eligible(current)) {
                    // Vanilla spawn(null) succeeds without spawning. The due interval stays consumed.
                    resetChance = backend.random(100) <= chance;
                } else if (backend.ownerEnabled(current) && backend.random(100) <= chance) {
                    resetChance = backend.spawn(current);
                }
            } finally {
                final boolean reset = resetChance;
                backend.onGlobal(() -> finish(data, settings, reset));
            }
        }, absent);
        if (admission == Admission.REJECTED) {
            // No callback is coming; consume the interval, retain the increased failure chance.
            pending = false;
        }
    }

    private void finish(final WanderingTraderData data, final Settings settings, final boolean resetChance) {
        if (resetChance) data.setSpawnChance(settings.minimum());
        pending = false;
    }

    private static boolean ownsSearch(final ServerLevel level, final BlockPos center, final int radius) {
        return RegionOwnership.current().isTickThreadFor(level,
            (center.getX() - radius) >> 4, (center.getZ() - radius) >> 4,
            (center.getX() + radius) >> 4, (center.getZ() + radius) >> 4);
    }

    /**
     * Finishes a trader that is already in the world. It always counts as the spawn (so the chance
     * resets), whatever plugins did to it in its spawn event: home and wander target are set first,
     * then up to two llamas, each step only while this region still owns the trader. A trader a
     * plugin moved out of the region keeps no home (writing it would cross regions).
     */
    public static boolean completeSpawn(final ServerLevel level, final Entity trader, final Runnable setHome,
                                        final Runnable spawnLlama) {
        return completeSpawn(() -> ownsSpawnedTrader(level, trader), setHome, spawnLlama);
    }

    static boolean completeSpawn(final java.util.function.BooleanSupplier owned, final Runnable setHome,
                                 final Runnable spawnLlama) {
        if (owned.getAsBoolean()) {
            setHome.run();
            for (int i = 0; i < 2 && owned.getAsBoolean(); i++) {
                spawnLlama.run();
            }
        }
        return true;
    }

    /** Recheck after spawn-event plugins may have teleported or removed the trader. */
    public static boolean ownsSpawnedTrader(final ServerLevel level, final Entity trader) {
        return RegionOwnership.current().isTickThreadFor(trader) && !trader.isRemoved()
            && trader.level() == level && ownsSearch(level, trader.blockPosition(), 6);
    }
}
