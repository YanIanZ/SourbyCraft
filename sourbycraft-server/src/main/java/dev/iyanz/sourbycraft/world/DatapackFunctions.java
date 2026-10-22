package dev.iyanz.sourbycraft.world;

import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Datapack functions on the region-threaded server ({@code docs/architecture/datapack-functions.md}).
 *
 * <p>Minecraft feature patch 0066 loads the function library again, registers {@code /function}
 * and {@code /return}, calls {@link #published()} whenever {@code ServerFunctionManager} receives
 * a library (boot, and any future reload), and calls {@link #globalTick} once per server tick from
 * the global tick. The vanilla {@code ServerFunctionManager.tick()} stays uncalled: it would run
 * {@code #minecraft:tick} unconditionally.</p>
 *
 * <p>Both tags run with vanilla's game-loop source (server source, GAMEMASTER, output suppressed)
 * on the global tick, which is the console command path: each command keeps the engine's own
 * route or refusal for the region work it does.</p>
 */
public final class DatapackFunctions {
    private static final Logger LOGGER = LoggerFactory.getLogger("SourbyCraft/DatapackFunctions");

    static final FunctionLifecycle LIFECYCLE = new FunctionLifecycle();
    private static volatile boolean tickFunctions;

    private DatapackFunctions() {}

    /** {@code aurora.world.tick-functions}; read on every global tick (LIVE). */
    public static boolean tickFunctions() {
        return tickFunctions;
    }

    /** Published by the config loader at boot and at {@code /sourbycraft reload}. */
    public static void setTickFunctions(final boolean value) {
        tickFunctions = value;
    }

    /** {@code ServerFunctionManager} now holds a newly loaded library. Any thread. */
    public static void published() {
        LIFECYCLE.published();
    }

    /**
     * Once per server tick, on the global tick, after console input and before the worlds' global
     * ticks (vanilla runs functions before levels tick).
     *
     * @param runsGameElements false while the global tick is frozen
     */
    public static void globalTick(final MinecraftServer server, final boolean runsGameElements) {
        RegionOwnership.current().ensureGlobalTickThread("Datapack function tags run on the global tick");
        final ServerFunctionManager functions = server.getFunctions();
        LIFECYCLE.tick(runsGameElements, tickFunctions, new FunctionLifecycle.Target<CommandFunction<CommandSourceStack>>() {
            @Override
            public Collection<CommandFunction<CommandSourceStack>> tag(final String tag) {
                return functions.getTag(Identifier.parse(tag));
            }

            @Override
            public void execute(final CommandFunction<CommandSourceStack> function) {
                // Vanilla's own execution: logs and swallows a failing function, like the tag runner did.
                functions.execute(function, functions.getGameLoopSender());
            }
        }, (function, failure) -> LOGGER.error("Datapack function {} failed; continuing with the rest of the tag",
            function.id(), failure));
    }
}
