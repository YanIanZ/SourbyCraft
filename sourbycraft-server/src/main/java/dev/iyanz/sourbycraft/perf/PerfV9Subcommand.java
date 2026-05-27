package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import dev.iyanz.sourbycraft.async.PoolMetrics;
import dev.iyanz.sourbycraft.io.ChunkSaveBatcher;
import dev.iyanz.sourbycraft.tick.BatchPhysicsTicker;
import dev.iyanz.sourbycraft.tick.BatchPhysicsTickers;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;

/**
 * Renders the runtime state of {@code performance.v9.*} flags.
 * Toggle / pool resize / report variants land in Ph2 once worker pools exist.
 */
public final class PerfV9Subcommand {

    private PerfV9Subcommand() {}

    public static void status(CommandSourceStack src) {
        src.sendSystemMessage(Component.literal("=== SourbyCraft v9 perf state ===").withStyle(ChatFormatting.GOLD));
        line(src, "enabled",                 SourbyCraftConfig.v9Enabled);
        line(src, "async-lighting",          SourbyCraftConfig.v9AsyncLighting);
        line(src, "async-pathfind",          SourbyCraftConfig.v9AsyncPathfind);
        line(src, "async-chunk-save",        SourbyCraftConfig.v9AsyncChunkSave);
        line(src, "parallel-mob-ai",         SourbyCraftConfig.v9ParallelMobAi);
        line(src, "parallel-item-tick",      SourbyCraftConfig.v9ParallelItemTick);
        line(src, "parallel-orb-arrow-tick", SourbyCraftConfig.v9ParallelOrbArrowTick);
        line(src, "region-io-pool",          SourbyCraftConfig.v9RegionIoPool);
        line(src, "object-pools",            SourbyCraftConfig.v9ObjectPools);
        src.sendSystemMessage(Component.literal(
            String.format("  Pool sizes: light=%d path=%d chunk=%d item=%d ai=%d region=%d",
                SourbyCraftConfig.v9PoolSizeLighting,
                SourbyCraftConfig.v9PoolSizePathfind,
                SourbyCraftConfig.v9PoolSizeChunkSave,
                SourbyCraftConfig.v9PoolSizeItemTick,
                SourbyCraftConfig.v9PoolSizeMobAi,
                SourbyCraftConfig.v9PoolSizeRegionIo
            )).withStyle(ChatFormatting.GRAY));
    }

    public static boolean toggle(CommandSourceStack src, String featureName) {
        boolean newValue;
        switch (featureName.toLowerCase()) {
            case "async-lighting":           newValue = SourbyCraftConfig.v9AsyncLighting        = !SourbyCraftConfig.v9AsyncLighting;        break;
            case "async-pathfind":           newValue = SourbyCraftConfig.v9AsyncPathfind        = !SourbyCraftConfig.v9AsyncPathfind;        break;
            case "async-chunk-save":         newValue = SourbyCraftConfig.v9AsyncChunkSave       = !SourbyCraftConfig.v9AsyncChunkSave;       break;
            case "parallel-mob-ai":          newValue = SourbyCraftConfig.v9ParallelMobAi        = !SourbyCraftConfig.v9ParallelMobAi;        break;
            case "parallel-item-tick":       newValue = SourbyCraftConfig.v9ParallelItemTick     = !SourbyCraftConfig.v9ParallelItemTick;     break;
            case "parallel-orb-arrow-tick":  newValue = SourbyCraftConfig.v9ParallelOrbArrowTick = !SourbyCraftConfig.v9ParallelOrbArrowTick; break;
            case "region-io-pool":           newValue = SourbyCraftConfig.v9RegionIoPool         = !SourbyCraftConfig.v9RegionIoPool;         break;
            case "object-pools":             newValue = SourbyCraftConfig.v9ObjectPools          = !SourbyCraftConfig.v9ObjectPools;          break;
            case "enabled":                  newValue = SourbyCraftConfig.v9Enabled              = !SourbyCraftConfig.v9Enabled;              break;
            default:
                src.sendSystemMessage(Component.literal("Unknown feature: " + featureName).withStyle(ChatFormatting.RED));
                return false;
        }
        src.sendSystemMessage(
            Component.literal(featureName + " now ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(newValue ? "on" : "off")
                    .withStyle(newValue ? ChatFormatting.GREEN : ChatFormatting.RED)));
        return true;
    }

    public static void metrics(CommandSourceStack src) {
        src.sendSystemMessage(Component.literal("=== v9 pool metrics ===").withStyle(ChatFormatting.GOLD));
        ChunkSaveBatcher batcher = ChunkSaveBatcher.peek();
        if (batcher == null) {
            src.sendSystemMessage(Component.literal("  chunk-save: not started").withStyle(ChatFormatting.GRAY));
        } else {
            PoolMetrics.Snapshot s = batcher.pool().metrics().snapshot();
            src.sendSystemMessage(Component.literal(String.format(
                "  chunk-save: submitted=%d completed=%d timeouts=%d queueHigh=%d avgMs=%.2f tripped=%b",
                s.submitted, s.completed, s.timedOut, s.queueDepthHigh, s.avgLatencyMs,
                batcher.pool().breakerTripped()
            )).withStyle(ChatFormatting.GRAY));
        }
        BatchPhysicsTicker bpt = BatchPhysicsTickers.peek();
        if (bpt == null) {
            src.sendSystemMessage(Component.literal("  parallel-tick: not started").withStyle(ChatFormatting.GRAY));
        } else {
            PoolMetrics.Snapshot s = bpt.pool().metrics().snapshot();
            src.sendSystemMessage(Component.literal(String.format(
                "  parallel-tick: submitted=%d completed=%d timeouts=%d queueHigh=%d avgMs=%.2f tripped=%b",
                s.submitted, s.completed, s.timedOut, s.queueDepthHigh, s.avgLatencyMs,
                bpt.pool().breakerTripped()
            )).withStyle(ChatFormatting.GRAY));
        }
        wildstackerMetrics(src);
    }

    /** Wildstacker counters appended at the end of metrics output. SourbyCraft v9.18 */
    public static void wildstackerMetrics(CommandSourceStack src) {
        int totalItems = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            totalItems += w.getEntitiesByClass(org.bukkit.entity.Item.class).size();
        }
        int holograms = 0;
        try {
            holograms = WildstackerManager.hologramCount();
        } catch (Throwable ignored) {}
        src.sendSystemMessage(Component.literal(String.format(
            "  wildstacker: items=%d holograms=%d enabled=%b",
            totalItems, holograms, SourbyCraftConfig.wildstackerEnabled
        )).withStyle(ChatFormatting.GRAY));
    }

    private static void line(CommandSourceStack src, String key, boolean on) {
        src.sendSystemMessage(
            Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(on ? "on" : "off")
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }
}
