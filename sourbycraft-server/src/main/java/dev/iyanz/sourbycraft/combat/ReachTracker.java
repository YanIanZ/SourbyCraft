package dev.iyanz.sourbycraft.combat;

/**
 * SourbyCraft v12 PVP — last-hit reach tracker.
 *
 * <p>Holds a single most-recent attacker/target/distance/latency tuple recorded
 * from NMS attack hook. Read by /reach debug command.
 */
public final class ReachTracker {

    public record Hit(String attacker, String target, double distance, int latencyMs, int windowMs) {}

    private static volatile Hit last;

    private ReachTracker() {}

    public static void record(String attacker, String target, double distance, int latencyMs, int windowMs) {
        last = new Hit(attacker, target, distance, latencyMs, windowMs);
    }

    public static Hit last() {
        return last;
    }
}
