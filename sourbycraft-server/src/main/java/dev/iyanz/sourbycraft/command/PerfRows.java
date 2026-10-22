package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Row and value formatting shared by the {@code /perf} views. */
final class PerfRows {

    private PerfRows() {}

    static void add(final List<Component> lines, final String label, final String value) {
        lines.add(Component.text("  " + label + ": ", SourbyCraftColors.LABEL)
            .append(Component.text(value, SourbyCraftColors.VALUE)));
    }

    static String bytes(final long value) { return value < 0 ? "unavailable" : ContainerMemory.fmt(value); }

    static String count(final long value) { return value < 0 ? "unavailable" : Long.toString(value); }

    static String percent(final double value) {
        return Double.isFinite(value) ? TpsCommand.value(value, 1) + "%" : "unavailable";
    }
}
