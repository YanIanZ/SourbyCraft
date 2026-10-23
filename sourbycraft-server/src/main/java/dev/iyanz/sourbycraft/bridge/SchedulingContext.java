package dev.iyanz.sourbycraft.bridge;

/**
 * The player a thread is currently acting for, as the Aurora Bridge infers the owner of a legacy
 * sync task that names none.
 *
 * <p>A legacy plugin schedules {@code runTask}/{@code runTaskLater} with no target. The bridge's
 * only generic knowledge of what the body will touch is <em>why</em> it was scheduled: while a
 * player's event is being dispatched or a player's command is executing, a task scheduled from that
 * frame almost always acts on that player (open a menu, teleport, give an item). The server-side
 * hooks (Paper feature patch 0026) publish that player here for the duration of the frame; the
 * bridge reads it in {@link BridgeRuntime#submit} as the <em>entity anchor</em>, after the explicit
 * {@code EntityTask}/{@code RegionTask} targets and before the caller-region/global fallback.</p>
 *
 * <p>The context follows bridged continuations: a bridged task body runs under the anchor its task
 * was scheduled with, so {@code runTaskAsynchronously(() -> ... runTask(...))} from a player's
 * command (DeluxeMenus) or {@code PlayerJoinEvent -> async -> callEvent -> runTaskLater}
 * (EssentialsX/EssentialsXSpawn) still ends on that player's owner. An explicit global request's body
 * publishes nothing. Nothing here touches the server: the entity is an opaque object.</p>
 *
 * <p>Thread-confined; nesting restores the outer value. {@link #enter(Object)} with {@code null}
 * keeps the outer context (an event that names no player, fired inside a player's frame, must not
 * erase that frame's player).</p>
 */
public final class SchedulingContext {

    /** A context frame; closing restores the value in effect before it. */
    public static final class Scope implements AutoCloseable {
        private final Object previous;
        private final boolean restore;

        private Scope(final Object previous, final boolean restore) {
            this.previous = previous;
            this.restore = restore;
        }

        @Override
        public void close() {
            if (!this.restore) return;
            if (this.previous == null) CURRENT.remove();
            else CURRENT.set(this.previous);
        }
    }

    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    private static final Scope KEEP = new Scope(null, false);

    private SchedulingContext() {}

    /** The entity this thread is acting for, or {@code null}. */
    public static Object current() {
        return CURRENT.get();
    }

    /**
     * Publishes {@code entity} as this thread's anchor until the scope closes. {@code null} publishes
     * nothing and keeps the outer value.
     */
    public static Scope enter(final Object entity) {
        if (entity == null) return KEEP;
        final Object previous = CURRENT.get();
        CURRENT.set(entity);
        return new Scope(previous, true);
    }

    /**
     * Publishes exactly {@code entity} ({@code null} = no anchor) for the body of a bridged task, so
     * a body on a shared tick thread neither inherits a frame it did not come from nor leaks its own.
     */
    static Scope body(final Object entity) {
        final Object previous = CURRENT.get();
        if (entity == null) CURRENT.remove();
        else CURRENT.set(entity);
        return new Scope(previous, true);
    }
}
