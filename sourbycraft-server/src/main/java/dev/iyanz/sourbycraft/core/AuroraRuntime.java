package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Process-wide lifecycle state published by {@link SourbyCraftBootstrap}.
 *
 * <p>This does not replace a service's own state. A pool still knows whether it has started, and
 * a collector still knows whether it has been closed. It adds the fact none of them own: whether
 * the server as a whole is still running. {@link #acceptingWork()} is false from the moment
 * shutdown begins or startup fails. Services must explicitly consult this state alongside their
 * own admission controls; changing it does not stop workers or drain their queues.</p>
 *
 * <p>Not a service locator. It holds no services and resolves nothing; {@link SourbyCraftBootstrap}
 * still owns startup order and shutdown order explicitly, because an order that is written down is
 * one that can be reviewed.</p>
 *
 * <p>Transitions are serialized on the class monitor. Readers observe a volatile snapshot;
 * checking admission does not atomically reserve work against a concurrent shutdown.</p>
 */
public final class AuroraRuntime {

    private AuroraRuntime() {}

    /** Where the runtime is in its life. */
    public enum State {
        /** Nothing has begun. */
        NEW,
        /** Engine hooks are installed; services are not up. */
        BOOTSTRAPPING,
        /** Services are coming up, in order. */
        STARTING,
        /** Startup stages have completed; optional services may have failed. Work is admitted. */
        RUNNING,
        /** Shutdown has begun. No new work is admitted; outstanding work drains. */
        STOPPING,
        /** The bootstrap shutdown sequence has completed its cleanup attempts. */
        STOPPED,
        /** Startup failed in a way that left the runtime unusable. */
        FAILED
    }

    // A lifecycle that can go anywhere is not a lifecycle. Written out so an illegal move is a
    // reported bug rather than a state nobody expected arriving somewhere that cannot handle it.
    private static final Map<State, Set<State>> ALLOWED = Map.of(
        // STOPPING from NEW: a JVM that dies before bootstrap runs — a port already bound, a
        // config that will not parse, a Ctrl-C during early init — still shuts down, and a
        // "not a legal transition" error logged there is noise at the worst possible moment.
        State.NEW, EnumSet.of(State.BOOTSTRAPPING, State.STOPPING, State.FAILED),
        State.BOOTSTRAPPING, EnumSet.of(State.STARTING, State.STOPPING, State.FAILED),
        State.STARTING, EnumSet.of(State.RUNNING, State.STOPPING, State.FAILED),
        State.RUNNING, EnumSet.of(State.STOPPING, State.FAILED),
        State.STOPPING, EnumSet.of(State.STOPPED, State.FAILED),
        State.STOPPED, EnumSet.noneOf(State.class),
        State.FAILED, EnumSet.of(State.STOPPING, State.STOPPED));

    private static volatile State state = State.NEW;

    /** @return the current non-null lifecycle state */
    public static State state() {
        return state;
    }

    /**
     * Whether a subsystem may admit new work.
     *
     * <p>True while starting as well as running: services come up in order, and one that is up
     * has to serve the ones still coming up behind it.</p>
     *
     * @return true in BOOTSTRAPPING, STARTING or RUNNING; false otherwise
     */
    public static boolean acceptingWork() {
        final State current = state;
        return current == State.RUNNING || current == State.STARTING
            || current == State.BOOTSTRAPPING;
    }

    /**
     * Whether shutdown-sensitive work must be refused, including after startup failure.
     *
     * @return true in FAILED, STOPPING or STOPPED; false before startup or while starting/running
     */
    public static boolean stopping() {
        final State current = state;
        return current == State.FAILED || current == State.STOPPING || current == State.STOPPED;
    }

    /**
     * Moves to {@code next}, or reports the move as a bug and makes it anyway.
     *
     * <p>Refusing an illegal transition would leave the runtime claiming to run while its services
     * are going down, which is worse than the inconsistency it was guarding. The move is made and
     * the fault is logged, so shutdown still completes and the ordering bug is visible.</p>
     *
     * <p>The transition table is diagnostic: even an illegal non-null transition changes state.
     * Callers remain responsible for startup/shutdown ordering and preventing restarts.</p>
     *
     * @param next the non-null state to enter
     * @return true for an allowed transition or the current state; false for a logged illegal move
     * @throws NullPointerException if {@code next} is null; the current state is preserved
     */
    public static synchronized boolean transition(final State next) {
        Objects.requireNonNull(next, "next");
        final State current = state;
        if (current == next) {
            return true;                      // Idempotent: close() may be reached twice.
        }
        final boolean legal = ALLOWED.getOrDefault(current, Set.of()).contains(next);
        if (!legal) {
            SourbyLogger.error("Aurora runtime moved from " + current + " to " + next
                + ", which is not a legal transition; continuing so shutdown is not blocked", null);
        }
        state = next;
        return legal;
    }

    /** Test seam: restores the initial state. Not for runtime use. */
    static void resetForTest() {
        state = State.NEW;
    }
}
