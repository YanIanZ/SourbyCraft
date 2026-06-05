package dev.iyanz.sourbycraft.perf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("toggle")
                    .then(Commands.argument("feature", StringArgumentType.word())
                        .executes(ctx -> {
                            PerfV9Subcommand.toggle(ctx.getSource(), StringArgumentType.getString(ctx, "feature"));
                            return 1;
                        })))
                .then(Commands.literal("metrics")
                    .executes(ctx -> { PerfV9Subcommand.metrics(ctx.getSource()); return 1; }))
            )
            .then(Commands.literal("scale")
                .then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource(), false)))
            )
            .then(Commands.literal("rate")
                .then(Commands.argument("rate", IntegerArgumentType.integer(1, 20))
                    .executes(ctx -> setRate(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rate"))))
            )
            .then(Commands.literal("tier")
                .executes(ctx -> showTier(ctx.getSource())))
            .then(Commands.literal("sensors")
                .executes(ctx -> showSensors(ctx.getSource())))
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
        src.sendSystemMessage(Component.literal("  Scale: " + scaling + " | Rate: 1/" + SourbyCraftConfig.entityTickRate())
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
        dev.iyanz.sourbycraft.perf.knob.Knobs.ENTITY_TICK_RATE.set(rate);
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

    private static int showTier(CommandSourceStack src) {
        if (!dev.iyanz.sourbycraft.perf.sensor.PerfSensor.isEnabled()) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Perf] Sensor disabled (perf.sensor.enabled=false)"));
            return 0;
        }
        dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot snap =
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot();
        long timeInTier = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.timeInTierNanos();
        long timeInTierSec = timeInTier / 1_000_000_000L;
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Tier: " + snap.tier()));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Candidate: " + snap.candidateTier() + " (" + snap.dwellSamples() + " samples in candidate)"));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "[Perf] Time in tier: " + timeInTierSec + "s"
                + (snap.timestampNanos() == 0L ? " (no samples yet)" : "")));
        return 1;
    }

    private static int showSensors(CommandSourceStack src) {
        if (!dev.iyanz.sourbycraft.perf.sensor.PerfSensor.isEnabled()) {
            src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Perf] Sensor disabled (perf.sensor.enabled=false)"));
            return 0;
        }
        dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot snap =
            dev.iyanz.sourbycraft.perf.sensor.PerfSensor.snapshot();
        double[] tpsT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("tps");
        double[] msptT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("mspt");
        double[] memT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("mem");
        double[] gcT = dev.iyanz.sourbycraft.perf.sensor.PerfSensor.thresholdsFor("gc-ms-per-min");
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] TPS:  %.2f (1s)  %.2f (1m)  %.2f (5m)  thresholds Y/O/R/E: %.1f/%.1f/%.1f/%.1f",
                snap.tps1s(), snap.tps30s(), snap.tps5m(), tpsT[1], tpsT[2], tpsT[3], tpsT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] MSPT: %.1f ms  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.msptAvg(), msptT[1], msptT[2], msptT[3], msptT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] Mem:  %.0f%% used  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.memPct(), memT[1], memT[2], memT[3], memT[4])));
        src.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            String.format("[Perf] GC:   %.0f ms/min  thresholds Y/O/R/E: %.0f/%.0f/%.0f/%.0f",
                snap.gcMsPerMin(), gcT[1], gcT[2], gcT[3], gcT[4])));
        return 1;
    }
}
