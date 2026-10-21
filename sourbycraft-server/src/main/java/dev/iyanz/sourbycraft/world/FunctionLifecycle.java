package dev.iyanz.sourbycraft.world;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The vanilla {@code #minecraft:load} / {@code #minecraft:tick} lifecycle, with no Minecraft types.
 *
 * <p>Every successful function-library publish (boot included) starts a new generation. The first
 * tick that runs game elements after a publish runs the load tag exactly once for that generation,
 * before the tick tag. A publish that never happens (a failed reload keeps the old library) starts
 * no generation, so the load tag does not run again. The tick tag runs only when enabled.</p>
 *
 * <p>{@link #published()} may be called from any thread; {@link #tick} is single-threaded (the
 * global tick).</p>
 */
public final class FunctionLifecycle {

    /** Where the functions come from and how one runs. */
    public interface Target<F> {
        /** The functions of a tag in the currently published library, empty when absent. */
        Collection<F> tag(String tag);

        /** Runs one function with the lifecycle source. */
        void execute(F function);
    }

    /** What one tick did, for tests and diagnostics. */
    public record Ran(int load, int tick) {
        public static final Ran NOTHING = new Ran(0, 0);
    }

    public static final String LOAD_TAG = "minecraft:load";
    public static final String TICK_TAG = "minecraft:tick";

    private final AtomicLong published = new AtomicLong();
    private long loaded; // generation whose load tag already ran; global tick only

    /** A new function library is now the server's library. */
    public void published() {
        this.published.incrementAndGet();
    }

    /** Number of library publishes so far. */
    public long generation() {
        return this.published.get();
    }

    /**
     * One server tick of the lifecycle.
     *
     * @param runsGameElements false while the game is frozen; vanilla defers both tags then
     * @param tickFunctions    whether {@code #minecraft:tick} runs ({@code aurora.world.tick-functions})
     * @param failure          receives a function that threw, with its exception; the others still run
     */
    public <F> Ran tick(final boolean runsGameElements, final boolean tickFunctions, final Target<F> target,
                        final BiConsumer<F, RuntimeException> failure) {
        if (!runsGameElements) {
            return Ran.NOTHING;
        }
        int load = 0;
        final long generation = this.published.get();
        if (generation != this.loaded) {
            // Marked before running, as vanilla clears postReload first: a throwing load function
            // must not make the load tag run again next tick.
            this.loaded = generation;
            load = runAll(target.tag(LOAD_TAG), target::execute, failure);
        }
        final int tick = tickFunctions ? runAll(target.tag(TICK_TAG), target::execute, failure) : 0;
        return load == 0 && tick == 0 ? Ran.NOTHING : new Ran(load, tick);
    }

    private static <F> int runAll(final Collection<F> functions, final Consumer<F> execute,
                                  final BiConsumer<F, RuntimeException> failure) {
        int ran = 0;
        for (final F function : functions) {
            try {
                execute.accept(function);
            } catch (final RuntimeException exception) {
                failure.accept(function, exception);
            }
            ran++;
        }
        return ran;
    }
}
