package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The Aurora runtime's lifecycle, and the one place that answers "may we still take work".
 *
 * <p>Before this, five services each answered that question their own way — a {@code stopped}
 * flag here, an {@code isShutdown()} there, a {@code running} boolean elsewhere. Five answers to
 * one question is five chances for one of them to be wrong during the few seconds when a shutdown
 * is in flight and work is still arriving, which is exactly when it matters.</p>
 *
 * <p>This does not replace a service's own state. A pool still knows whether it has started, and
 * a collector still knows whether it has been closed. It adds the fact none of them own: whether
 * the server as a whole is still running. {@link #acceptingWork()} is false from the moment
 * shutdown begins, so a service need only ask rather than infer.</p>
 *
 * <p>Not a service locator. It holds no services and resolves nothing; {@link SourbyCraftBootstrap}
 * still owns startup order and shutdown order explicitly, because an order that is written down is
 * one that can be reviewed.</p>
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
        /** Fully up. The only state in which new work is freely admitted. */
        RUNNING,
        /** Shutdown has begun. No new work is admitted; outstanding work drains. */
        STOPPING,
        /** Everything is down. */
        STOPPED,
        /** Startup failed in a way that left the runtime unusable. */
        FAILED
    }

    // A lifecycle that can go anywhere is not a lifecycle. Written out so an illegal move is a
    // reported bug rather than a state nobody expected arriving somewhere that cannot handle it.
    private static final Map<State, Set<State>> ALLOWED = Map.of(
        State.NEW, EnumSet.of(State.BOOTSTRAPPING, State.FAILED),
        State.BOOTSTRAPPING, EnumSet.of(State.STARTING, State.STOPPING, State.FAILED),
        State.STARTING, EnumSet.of(State.RUNNING, State.STOPPING, State.FAILED),
        State.RUNNING, EnumSet.of(State.STOPPING, State.FAILED),
        State.STOPPING, EnumSet.of(State.STOPPED, State.FAILED),
        State.STOPPED, EnumSet.noneOf(State.class),
        State.FAILED, EnumSet.of(State.STOPPING, State.STOPPED));

    private static volatile State state = State.NEW;

    public static State state() {
        return state;
    }

    /**
     * Whether a subsystem may admit new work.
     *
     * <p>True while starting as well as running: services come up in order, and one that is up
     * has to serve the ones still coming up behind it.</p>
     */
    public static boolean acceptingWork() {
        final State current = state;
        return current == State.RUNNING || current == State.STARTING
            || current == State.BOOTSTRAPPING;
    }

    /** Whether shutdown has begun, by any route including failure. */
    public static boolean stopping() {
        final State current = state;
        return current == State.STOPPING || current == State.STOPPED;
    }

    /**
     * Moves to {@code next}, or reports the move as a bug and makes it anyway.
     *
     * <p>Refusing an illegal transition would leave the runtime claiming to run while its services
     * are going down, which is worse than the inconsistency it was guarding. The move is made and
     * the fault is logged, so shutdown still completes and the ordering bug is visible.</p>
     *
     * @param next the state to enter
     * @return true when the transition was a legal one
     */
    public static synchronized boolean transition(final State next) {
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
