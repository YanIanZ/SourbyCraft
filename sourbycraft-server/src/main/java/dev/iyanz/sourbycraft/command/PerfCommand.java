package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.perf.knob.KnobRegistry;
import dev.iyanz.sourbycraft.perf.sensor.PerfSensor;
import dev.iyanz.sourbycraft.perf.sensor.SensorSnapshot;
import dev.iyanz.sourbycraft.perf.sensor.Tier;
import dev.iyanz.sourbycraft.util.BarUtil;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Map;

import static net.kyori.adventure.text.Component.text;

/**
 * /perf — operator view of the SourbyCraft perf engine.
 *
 * <p>Single-line summary at the top (current tier + warmup state),
 * three signal lines (TPS / MSPT / Memory) coloured by their tier
 * threshold, then the full knob snapshot from {@link KnobRegistry}.
 * Same divider + header style as /sys / /tps / /ver so the panel
 * reads as one coherent suite.
 */
public class PerfCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    public PerfCommand(String name) {
        super(name);
        this.description = "SourbyCraft perf engine status (tier, signals, knobs)";
        this.usageMessage = "/perf";
        this.setPermission("sourbycraft.command.perf");
        this.setAliases(java.util.List.of("perfengine", "tuner"));
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;

        SensorSnapshot snap = PerfSensor.snapshot();
        Tier tier = snap == null ? Tier.GREEN : snap.tier();
        TextColor tierColor = tierColor(tier);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
                .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
                .append(text("SourbyCraft ", SourbyCraftColors.HEADER))
                .append(text("Perf Engine", SourbyCraftColors.LABEL))
                .build());

        int warmup = PerfSensor.warmupRemainingTicks();
        s.sendMessage(text()
                .append(text("  Tier: ", SourbyCraftColors.LABEL))
                .append(text(tier.name(), tierColor))
                .append(warmup > 0
                        ? text("  (warmup " + (int) Math.ceil(warmup / 20.0) + "s)", SourbyCraftColors.WARNING)
                        : text("  (sensor live)", SourbyCraftColors.DIM))
                .build());
        s.sendMessage(text()
                .append(text("  Sensor enabled: ", SourbyCraftColors.LABEL))
                .append(text(String.valueOf(PerfSensor.isEnabled()),
                        PerfSensor.isEnabled() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM))
                .build());

        if (snap != null) {
            s.sendMessage(text("  Signals:", SourbyCraftColors.LABEL));
            s.sendMessage(line("    TPS 1s ",  String.format(Locale.ROOT, "%.2f", snap.tps1s()),  tpsColor(snap.tps1s())));
            s.sendMessage(line("    TPS 30s",  String.format(Locale.ROOT, "%.2f", snap.tps30s()), tpsColor(snap.tps30s())));
            s.sendMessage(line("    TPS 5m ",  String.format(Locale.ROOT, "%.2f", snap.tps5m()),  tpsColor(snap.tps5m())));
            s.sendMessage(line("    MSPT   ", String.format(Locale.ROOT, "%.1fms", snap.msptAvg()),    msptColor(snap.msptAvg())));
            s.sendMessage(line("    Mem    ", String.format(Locale.ROOT, "%.1f%%", snap.memPct()),     memColor(snap.memPct())));
            s.sendMessage(line("    GC/min ", String.format(Locale.ROOT, "%.1fms", snap.gcMsPerMin()), gcColor(snap.gcMsPerMin())));
        }

        Map<String, Object> knobs = KnobRegistry.snapshot();
        if (!knobs.isEmpty()) {
            s.sendMessage(text("  Knobs (" + knobs.size() + "):", SourbyCraftColors.LABEL));
            for (Map.Entry<String, Object> entry : knobs.entrySet()) {
                s.sendMessage(text()
                        .append(text("    " + entry.getKey() + " = ", SourbyCraftColors.DIM))
                        .append(text(String.valueOf(entry.getValue()), SourbyCraftColors.VALUE))
                        .build());
            }
        }

        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static net.kyori.adventure.text.Component line(String label, String value, TextColor color) {
        return text()
                .append(text(label + ": ", SourbyCraftColors.DIM))
                .append(text(value, color))
                .build();
    }

    private static TextColor tierColor(Tier tier) {
        return switch (tier) {
            case GREEN     -> SourbyCraftColors.SUCCESS;
            case YELLOW    -> SourbyCraftColors.PRIMARY;
            case ORANGE    -> SourbyCraftColors.WARNING;
            case RED       -> SourbyCraftColors.DANGER;
            case EMERGENCY -> SourbyCraftColors.ACCENT;
        };
    }

    private static TextColor tpsColor(double t) {
        return t > 19.0 ? SourbyCraftColors.SUCCESS
                : t > 16.0 ? SourbyCraftColors.PRIMARY
                : t > 12.0 ? SourbyCraftColors.WARNING : SourbyCraftColors.DANGER;
    }

    private static TextColor msptColor(double m) {
        return m < 40.0 ? SourbyCraftColors.SUCCESS
                : m < 50.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor memColor(double p) {
        return p < 75.0 ? SourbyCraftColors.SUCCESS
                : p < 92.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }

    private static TextColor gcColor(double ms) {
        return ms < 50.0 ? SourbyCraftColors.SUCCESS
                : ms < 100.0 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
    }
}
