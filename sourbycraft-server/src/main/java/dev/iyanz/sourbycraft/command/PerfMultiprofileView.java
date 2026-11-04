package dev.iyanz.sourbycraft.command;

import static dev.iyanz.sourbycraft.command.PerfRows.add;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileEngine;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileMetrics;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRuntime;
import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf multiprofile} (Build 55): AWF MultiProfile's in-memory metrics. Switch latency percentiles of the
 * switches this node committed since it started, the profile I/O queue (depth, partitions, wait percentiles), the
 * oldest outstanding salvage record and open kick vote this node has seen, switch refusals, the session handoff waits
 * of joins (Build 55.1, {@code coordination.handoff-wait-ms}), and the audit log's counters. Everything is read from memory on the command thread: no store I/O, nothing persisted, so the rows
 * reset at restart and the ages are judged on this node's wall clock.
 */
final class PerfMultiprofileView implements PerfView {

    @Override public String name() { return "multiprofile"; }

    @Override
    public void render(final PerformanceSnapshot snapshot, final List<Component> lines) {
        final ProfileEngine engine = ProfileRuntime.engineOrNull();
        if (engine == null) {
            add(lines, "AWF MultiProfile", "off (aurora.awf.multiprofile.enabled) or not started");
            return;
        }
        render(lines, engine.metricsSnapshot(), engine.switchGuards().guardCount(), engine.switchGuards().cooldownMillis(),
            engine.pendingIo(), engine.audit() == null ? null : engine.audit().directory().toString());
        // Build 55: the AWF world events (sourbyapi world.event) share the "dropped, store is truth" contract; counted by the service.
        final dev.iyanz.sourbycraft.awf.world.AuroraWorldsService worlds = dev.iyanz.sourbycraft.awf.world.AuroraWorldsRuntime.service();
        if (worlds != null) add(lines, "AWF world events dropped", Long.toString(worlds.droppedWorldEvents()));
    }

    /** The rows from a snapshot, so the layout can be checked without a running engine. */
    static void render(final List<Component> lines, final ProfileMetrics.Snapshot m, final int guards, final long cooldownMillis,
                       final int pendingIo, final String auditDirectory) {
        lines.add(UiPanel.section("Switches (committed on this node since start)"));
        add(lines, "Committed / failed", m.switchesCommitted() + " / " + m.switchesFailed());
        add(lines, "Latency p50 / p95 / p99", m.switchesCommitted() == 0 ? "no committed switch yet"
            : m.switchP50Millis() + " / " + m.switchP95Millis() + " / " + m.switchP99Millis() + " ms (last "
                + Math.min(m.switchesCommitted(), ProfileMetrics.WINDOW) + " switches)");
        add(lines, "Refused by cooldown / vetoed / guard timeouts", m.switchesRefusedCooldown() + " / " + m.switchesVetoed() + " / "
            + m.guardTimeouts());
        add(lines, "Switch policy", (cooldownMillis == 0L ? "no cooldown" : "cooldown " + cooldownMillis / 1000L + " s") + ", " + guards
            + " guard(s) registered");
        lines.add(UiPanel.section("Profile I/O queue"));
        add(lines, "Waiting / running / peak waiting", m.queueWaiting() + " / " + m.queueRunning() + " / " + m.queuePeakWaiting()
            + " (pending futures " + pendingIo + ", capacity " + ProfileEngine.IO_QUEUE_CAPACITY + ")");
        add(lines, "Partitions", m.queuePartitions() + (m.queuePartitions() == 1 ? " (one step at a time, FIFO over all accounts)"
            : " (FIFO per account; aurora.awf.multiprofile.io.partitions)"));
        add(lines, "Queue wait p50 / p95 / p99", m.queueWaitSamples() == 0 ? "no step yet"
            : micros(m.queueWaitP50Micros()) + " / " + micros(m.queueWaitP95Micros()) + " / " + micros(m.queueWaitP99Micros())
                + " (last " + Math.min(m.queueWaitSamples(), ProfileMetrics.WINDOW) + " steps)");
        lines.add(UiPanel.section("Session handoff (coordination.handoff-wait-ms)"));
        // One join can wait twice (session claim, then the active profile's node claim): these are claim waits, not joins.
        add(lines, "Claim waits / admitted / timed out / left / failed", m.handoffWaits() + " / " + m.handoffSucceeded() + " / "
            + m.handoffTimedOut() + " / " + m.handoffAbandoned() + " / " + m.handoffFailed());
        add(lines, "Wait p50 / p95", m.handoffSucceeded() == 0 ? "no admitted wait yet"
            : m.handoffWaitP50Millis() + " / " + m.handoffWaitP95Millis() + " ms (last "
                + Math.min(m.handoffSucceeded(), ProfileMetrics.WINDOW) + " admitted waits)");
        lines.add(UiPanel.section("Co-op (as seen by this node)"));
        add(lines, "Outstanding salvage / oldest age", m.outstandingSalvage() + " / " + age(m.oldestSalvageAgeMillis()));
        add(lines, "Open kick votes / oldest age", m.openVotes() + " / " + age(m.oldestVoteAgeMillis()));
        lines.add(UiPanel.section("Audit log"));
        if (auditDirectory == null) {
            add(lines, "Audit", "off (aurora.awf.multiprofile.audit.enabled)");
        } else {
            add(lines, "Recorded / dropped / write failures", m.auditRecorded() + " / " + m.auditDropped() + " / " + m.auditWriteFailures());
            add(lines, "Audit directory", auditDirectory);
        }
        add(lines, "Profile events dropped", Long.toString(m.profileEventsDropped()));
    }

    private static String micros(final long micros) {
        return micros >= 10_000L ? (micros / 1000L) + " ms" : micros + " us";
    }

    private static String age(final long millis) {
        if (millis < 0L) return "none";
        final long seconds = millis / 1000L;
        if (seconds < 120L) return seconds + " s";
        if (seconds < 7200L) return (seconds / 60L) + " min";
        return (seconds / 3600L) + " h";
    }
}
