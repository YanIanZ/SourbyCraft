package dev.iyanz.sourbycraft.command;

import static net.kyori.adventure.text.Component.text;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * {@code /spec} — what this machine actually is.
 *
 * <p>Separate from {@code /sys}, which reports how the server is <em>doing</em>. This reports what
 * it is <em>running on</em>, and the distinction matters when a number looks wrong: a tick budget
 * blown on four cores is a different problem from the same budget blown on thirty-two.</p>
 *
 * <p>Hardware identity comes from OSHI and is resolved once on a virtual thread, never on a region
 * thread. OSHI's load calls sleep — {@code getSystemCpuLoad(1000)} sleeps a full second — and a
 * command that stalls the region it runs on to report how fast that region is would be its own
 * worst reading. Every field degrades to "unavailable" rather than to a zero.</p>
 */
public final class SpecCommand extends Command {

    private static volatile Hardware hardware;

    /** Hardware identity, resolved off-thread. Every field may be absent. */
    private record Hardware(String cpu, int physical, int logical, long maxHz, long baseHz,
                            long physicalMemory, String os) {}

    public SpecCommand(final String name) {
        super(name);
        this.description = "Full server specification: CPU, memory, allocation";
        this.usageMessage = "/" + name;
        this.setPermission("sourbycraft.command.spec");
        prewarm();
    }

    private static void prewarm() {
        if (hardware != null) {
            return;
        }
        dev.iyanz.sourbycraft.util.VirtualExecutor.run(() -> {
            try {
                final var info = new oshi.SystemInfo();
                final var cpu = info.getHardware().getProcessor();
                final var id = cpu.getProcessorIdentifier();
                hardware = new Hardware(
                    id.getName().trim(),
                    cpu.getPhysicalProcessorCount(),
                    cpu.getLogicalProcessorCount(),
                    cpu.getMaxFreq(),
                    id.getVendorFreq(),
                    info.getHardware().getMemory().getTotal(),
                    info.getOperatingSystem().toString());
            } catch (final Throwable absent) {
                // OSHI unavailable or unsupported here: the hardware lines say so rather than
                // inventing numbers the JVM cannot see.
            }
        });
    }

    @Override
    public boolean execute(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermission(sender)) return true;
        render().forEach(sender::sendMessage);
        return true;
    }

    public static List<Component> render() {
        final List<Component> lines = new ArrayList<>();
        lines.add(text("SourbyCraft Specification", SourbyCraftColors.HEADER));

        final Hardware hw = hardware;
        final Runtime runtime = Runtime.getRuntime();
        final int visible = runtime.availableProcessors();

        if (hw == null) {
            add(lines, "Hardware", "unavailable (still resolving, or OSHI unsupported here)");
        } else {
            add(lines, "Processor", hw.cpu());
            add(lines, "Cores / threads", hw.physical() + " physical, " + hw.logical() + " logical");
            add(lines, "Clock", frequency(hw.baseHz()) + " base, " + frequency(hw.maxHz()) + " max");
            add(lines, "Operating system", hw.os());
        }

        // Stated separately from the physical count on purpose. Inside a container these differ,
        // and every thread-count default on this server is computed from the JVM's view -- so a
        // tuning decision made against the host's core count is made against the wrong number.
        final String visibleNote = hw != null && hw.logical() > 0 && visible != hw.logical()
            ? visible + "  (host has " + hw.logical() + "; this process is limited)"
            : String.valueOf(visible);
        add(lines, "Processors visible to the JVM", visibleNote);

        final long limit = ContainerMemory.limitBytes();
        final long physical = hw == null ? -1L : hw.physicalMemory();
        if (physical > 0) {
            add(lines, "Physical memory", ContainerMemory.fmt(physical));
        }
        if (limit > 0 && (physical <= 0 || limit != physical)) {
            add(lines, "Container limit", ContainerMemory.fmt(limit));
        }
        final long used = ContainerMemory.currentBytes();
        if (used > 0) {
            add(lines, "Container in use", ContainerMemory.fmt(used));
        }

        add(lines, "Heap allocation", ContainerMemory.fmt(runtime.totalMemory()) + " committed of "
            + ContainerMemory.fmt(runtime.maxMemory()) + " maximum");
        add(lines, "Heap in use", ContainerMemory.fmt(runtime.totalMemory() - runtime.freeMemory()));
        add(lines, "Java", System.getProperty("java.version", "unknown") + " ("
            + System.getProperty("os.arch", "unknown") + ")");
        add(lines, "Uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000L + "s");
        return List.copyOf(lines);
    }

    /** Hertz as gigahertz, or "unavailable" — OSHI reports -1 where the platform will not say. */
    static String frequency(final long hz) {
        return hz <= 0 ? "unavailable" : String.format(Locale.ROOT, "%.2f GHz", hz / 1.0e9);
    }

    private static void add(final List<Component> lines, final String label, final String value) {
        lines.add(text("  " + label + ": ", SourbyCraftColors.LABEL)
            .append(text(value, SourbyCraftColors.VALUE)));
    }
}
