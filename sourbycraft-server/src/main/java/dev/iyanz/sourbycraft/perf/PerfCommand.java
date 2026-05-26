package dev.iyanz.sourbycraft.perf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public class PerfCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("perf")
            .executes(ctx -> status(ctx.getSource()))
            .then(Commands.literal("v9")
                .executes(ctx -> { PerfV9Subcommand.status(ctx.getSource()); return 1; })
            )
            .then(Commands.literal("scale")
                .then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource(), false)))
            )
            .then(Commands.literal("rate")
                .then(Commands.argument("rate", IntegerArgumentType.integer(1, 20))
                    .executes(ctx -> setRate(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rate"))))
            )
        );
    }

    private static int status(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        Runtime rt = Runtime.getRuntime();

        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();

        src.sendSystemMessage(Component.literal(""));

        double tps = getTPS(server);
        src.sendSystemMessage(Component.literal("[SourbyCraft Performance]").withStyle(ChatFormatting.GOLD));
        src.sendSystemMessage(Component.literal("  TPS: " + String.format("%.1f", tps) + "  " + BarUtil.bar(tps * 5, 20))
            .withStyle(tps > 18 ? ChatFormatting.GREEN : tps > 15 ? ChatFormatting.YELLOW : ChatFormatting.RED));

        src.sendSystemMessage(Component.literal("  RAM: " + BarUtil.formatBytes(used) + "/" + BarUtil.formatBytes(max))
            .withStyle(ChatFormatting.WHITE));

        long pct = max > 0 ? used * 100 / max : 0;
        src.sendSystemMessage(Component.literal("  " + BarUtil.bar(pct, 30) + " " + pct + "%")
            .withStyle(pct < 50 ? ChatFormatting.GREEN : pct < 80 ? ChatFormatting.YELLOW : ChatFormatting.RED));

        double cpu = getCPU();
        src.sendSystemMessage(Component.literal("  CPU: " + String.format("%.1f", cpu) + "%")
            .withStyle(ChatFormatting.WHITE));

        int entities = 0, chunks = 0;
        for (ServerLevel w : server.getAllLevels()) {
            chunks += w.getChunkSource().getLoadedChunksCount();
            entities += w.getEntityCount();
        }
        src.sendSystemMessage(Component.literal("  Entities: " + entities + " | Chunks: " + chunks + " | Players: " + server.getPlayerCount())
            .withStyle(ChatFormatting.WHITE));

        String scaling = DynamicPerformanceScaler.isEnabled() ? "ON" : "OFF";
        ChatFormatting sc = DynamicPerformanceScaler.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED;
        src.sendSystemMessage(Component.literal("  Scale: " + scaling + " | Rate: 1/" + SourbyCraftConfig.entityTickRate)
            .withStyle(sc));

        return 1;
    }

    private static int toggle(CommandSourceStack src, boolean on) {
        DynamicPerformanceScaler.setEnabled(on);
        src.sendSystemMessage(Component.literal("[Perf] Dynamic scaler: " + (on ? "ON" : "OFF"))
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return 1;
    }

    private static int setRate(CommandSourceStack src, int rate) {
        SourbyCraftConfig.entityTickRate = rate;
        src.sendSystemMessage(Component.literal("[Perf] Entity tick rate set to 1/" + rate).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static double getTPS(MinecraftServer server) {
        long nanos = server.getAverageTickTimeNanos();
        return nanos > 0 ? Math.min(20.0, 1_000_000_000.0 / nanos) : 20.0;
    }

    private static double getCPU() {
        try {
            return ((com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getProcessCpuLoad() * 100;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft:PerfCommand").warn("Failed to get CPU load", e);
            return 0;
        }
    }
}
