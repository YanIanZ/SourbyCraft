package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

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

    private static void line(CommandSourceStack src, String key, boolean on) {
        src.sendSystemMessage(
            Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(on ? "on" : "off")
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }
}
