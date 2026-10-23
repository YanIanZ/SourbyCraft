package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;
import static dev.iyanz.sourbycraft.command.PerfRows.bytes;
import static dev.iyanz.sourbycraft.command.PerfRows.count;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import java.util.List;
import net.kyori.adventure.text.Component;

/** {@code /perf network}: wire and packet rates, totals since start, then the outbound queues. */
final class PerfNetworkView implements PerfView {

    @Override public String name() { return "network"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final var counters = dev.iyanz.sourbycraft.perf.NetworkCounters.GLOBAL;
        final var rates = counters.rates();
        final var totals = counters.totals(System.nanoTime());
        if (!rates.available()) {
            add(lines, "Network rates", "unavailable until two collector samples exist");
        } else {
            add(lines, "Bytes in / out per second", bytes((long)rates.bytesInPerSecond()) + " / "
                + bytes((long)rates.bytesOutPerSecond()) + " (wire, after compression)");
            add(lines, "Packets in / out per second", TpsCommand.value(rates.packetsInPerSecond(), 1) + " / "
                + TpsCommand.value(rates.packetsOutPerSecond(), 1));
        }
        add(lines, "Total bytes in / out", bytes(totals.bytesIn()) + " / " + bytes(totals.bytesOut()));
        add(lines, "Total packets in / out", count(totals.packetsIn()) + " / " + count(totals.packetsOut()));
        add(lines, "Connections opened", count(totals.connections()));
        final var control = dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.client();
        add(lines, "AbyssLink", dev.iyanz.sourbycraft.abysslink.AbyssLinkRuntime.status());
        if (control != null) {
            final var metrics = control.metrics.snapshot();
            add(lines, "Control frames in / out", count(metrics.framesIn()) + " / " + count(metrics.framesOut()));
            add(lines, "Control bytes in / out", bytes(metrics.bytesIn()) + " / " + bytes(metrics.bytesOut()) + " (frame bytes, excluding TLS)");
            add(lines, "Control pending / cap", control.pendingCount() + " / " + control.settingsForDiagnostics().maxPendingRequests());
            add(lines, "Control timeouts / rejected / backpressure", metrics.timeouts() + " / " + metrics.rejected() + " / " + metrics.backpressure());
            add(lines, "Control RTT", metrics.rttNanos() == 0 ? "unavailable" : TpsCommand.value(metrics.rttNanos() / 1_000_000.0, 2) + " ms (not player ping)");
        }
        PerfTelemetryRows.renderConnectionQueues(lines);
    }
}
