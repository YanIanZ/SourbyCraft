package dev.iyanz.sourbycraft.commandblock;

import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * SourbyCraft's own rules for command blocks and command-block minecarts on a region-threaded
 * server, replacing the base's unconditional "never run" guard ({@code aurora.world.command-blocks},
 * {@code docs/architecture/command-blocks.md}).
 *
 * <p>The rules, all decided here so the Minecraft feature patch stays a set of one-line hooks:</p>
 * <ol>
 *   <li>Off (the default) keeps the inherited behaviour exactly: blocks never execute, the GUI does
 *       not open, edit packets are refused.</li>
 *   <li>On, a command runs only on the thread of the region that owns the block (or minecart). A
 *       block tick already runs there; the check makes it a rule instead of an assumption.</li>
 *   <li>While a command block's command runs, this thread is in a {@link Scope}. Entity selectors
 *       then only consider entities this region owns, checked before the selector reads anything
 *       else of the entity: vanilla behaviour within the region, nothing from other regions.</li>
 *   <li>In a scope, block targets (setblock, fill, clone, place, summon, particle, data block, ...)
 *       outside the owning region fail with a command error instead of being queued to the other
 *       region. Players and console keep the engine's routing; only command blocks are narrowed,
 *       because a command block has no one watching the delayed result.</li>
 *   <li>Global state (time, gamerules, objectives, world border, difficulty) keeps the engine's
 *       existing route to the global region, the same path console commands take.</li>
 *   <li>Output that reaches a command block after its command finished, or from another region's
 *       thread, goes to {@link CommandBlockDiagnostics} (logged once per block and command) instead of
 *       the block or the inherited thread check.</li>
 *   <li>Commands that load chunks and mutate in a later callback (fill, fillbiome, clone) never defer
 *       for a command block: the whole footprint, physics buffer included, must be this region's and
 *       loaded, and the mutation then runs now, inside the scope ({@link #deferred}).</li>
 *   <li>Selectors check ownership before reading anything of an entity, bounding box included; a
 *       conditional block treats a predecessor another region owns as "condition not met".</li>
 * </ol>
 *
 * <p>Pure Java with no Minecraft types; {@link CommandBlockRegions} adapts it to the engine.</p>
 */
public final class CommandBlockPolicy {

    /** What a command block may do right now. */
    public enum Decision {
        /** Execute the command on this thread. */
        RUN,
        /** {@code aurora.world.command-blocks} is off: the inherited refusal stands. */
        DISABLED,
        /** On, but this thread does not own the block or minecart: skip, never touch it. */
        NOT_OWNER
    }

    private static volatile boolean enabled;

    // Depth, not a flag, so a nested enter/close pair cannot end the outer scope early.
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private CommandBlockPolicy() {}

    /** Whether {@code aurora.world.command-blocks} is on. Read per tick, interaction and packet. */
    public static boolean enabled() {
        return enabled;
    }

    /** Published by the config loader at boot and at {@code /sourbycraft reload}. */
    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    public static Decision decide(final boolean enabled, final boolean ownsSource) {
        if (!enabled) {
            return Decision.DISABLED;
        }
        return ownsSource ? Decision.RUN : Decision.NOT_OWNER;
    }

    public static Decision decide(final boolean ownsSource) {
        return decide(enabled, ownsSource);
    }

    /** Marks this thread as running a command block's command until closed. */
    public static Scope enter() {
        DEPTH.get()[0]++;
        return new Scope();
    }

    /** Whether this thread is running a command block's command right now. */
    public static boolean inScope() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * The selector predicate to use: unchanged outside a scope; inside one, ownership first, so a
     * foreign entity's position, tags or NBT are never read from this thread.
     */
    public static <T> Predicate<T> ownedFirst(final boolean inScope, final Predicate<? super T> owner,
                                              final Predicate<T> selector) {
        if (!inScope) {
            return selector;
        }
        return entity -> owner.test(entity) && selector.test(entity);
    }

    /** A name or UUID lookup result, or {@code null} when it is foreign and this is a scope. */
    public static <T> @Nullable T ownedOrNull(final boolean inScope, final @Nullable T value,
                                              final Predicate<? super T> owner) {
        if (value == null || !inScope) {
            return value;
        }
        return owner.test(value) ? value : null;
    }

    /** Whether a block-position target may be used: always outside a scope, else only if owned. */
    public static boolean allowsTarget(final boolean inScope, final boolean ownsTarget) {
        return !inScope || ownsTarget;
    }

    /**
     * Whether a selector may read anything of an entity (its bounding box included) before its
     * predicate runs: always outside a scope, else only an entity this region owns.
     */
    public static boolean mayRead(final boolean inScope, final boolean ownsEntity) {
        return !inScope || ownsEntity;
    }

    /**
     * Whether a conditional command block may read its predecessor (block, block entity, success
     * count). Off keeps the inherited read; on, a predecessor another region owns reads as "condition
     * not met" instead of being touched from the wrong thread.
     */
    public static boolean mayReadPredecessor(final boolean enabled, final boolean ownsPredecessor) {
        return !enabled || ownsPredecessor;
    }

    /** How a command that loads chunks and mutates them in a completion callback may proceed. */
    public enum Deferred {
        /** Not a command block: the engine's asynchronous load-then-run, unchanged. */
        ROUTE_ASYNC,
        /** Command block, footprint owned and loaded: run the mutation now, on this thread. */
        RUN_NOW,
        /** Command block, some chunk of the footprint (physics buffer included) is another region's. */
        REFUSE_FOREIGN,
        /** Command block, owned but the mutated chunks are not loaded: never defer, so refuse. */
        REFUSE_UNLOADED
    }

    /**
     * A command block never hands a mutation to a later callback: the callback could run on another
     * thread after the scope has ended, where none of these rules would hold. It runs the mutation
     * synchronously on its own region, or not at all.
     */
    public static Deferred deferred(final boolean inScope, final boolean ownsFootprint, final boolean mutatedLoaded) {
        if (!inScope) {
            return Deferred.ROUTE_ASYNC;
        }
        if (!ownsFootprint) {
            return Deferred.REFUSE_FOREIGN;
        }
        return mutatedLoaded ? Deferred.RUN_NOW : Deferred.REFUSE_UNLOADED;
    }

    /**
     * Whether output reaching a command block goes to the diagnostic log instead of its last-output
     * field: on, and the source is already closed or this thread does not own the block. Such output
     * is a deferred result (usually a failure) nobody would otherwise see.
     */
    public static boolean toDiagnostics(final boolean enabled, final boolean closed, final boolean ownsSource) {
        return enabled && (closed || !ownsSource);
    }

    public static String notLoadedMessage(final String action) {
        return "Command block: cannot " + action + " in chunks that are not loaded";
    }

    public static String crossRegionMessage(final String action) {
        return "Command block: cannot " + action + " outside the region that owns the command block";
    }

    /** One level of {@link #enter()}; closing twice is harmless. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                DEPTH.get()[0]--;
            }
        }
    }
}
