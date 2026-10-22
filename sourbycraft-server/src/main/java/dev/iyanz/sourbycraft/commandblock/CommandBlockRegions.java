package dev.iyanz.sourbycraft.commandblock;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.iyanz.sourbycraft.execution.region.RegionChunkLoading;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The engine side of {@link CommandBlockPolicy}: the calls the Minecraft feature patch makes.
 * Every method is a no-op outside a command block's {@link CommandBlockPolicy.Scope}, so players,
 * console and plugins keep exactly the behaviour they had.
 */
public final class CommandBlockRegions {

    private static final DynamicCommandExceptionType CROSS_REGION = new DynamicCommandExceptionType(
        action -> Component.literal(CommandBlockPolicy.crossRegionMessage(String.valueOf(action))));
    private static final DynamicCommandExceptionType NOT_LOADED = new DynamicCommandExceptionType(
        action -> Component.literal(CommandBlockPolicy.notLoadedMessage(String.valueOf(action))));

    private CommandBlockRegions() {}

    /** {@code EntitySelector.getPredicate}: in a scope, only entities this region owns. */
    public static Predicate<Entity> selector(final Predicate<Entity> selector) {
        return CommandBlockPolicy.ownedFirst(CommandBlockPolicy.inScope(), entity -> RegionOwnership.current().isTickThreadFor(entity), selector);
    }

    /**
     * {@code EntitySelector.addEntities}: whether the entity may be read at all (its bounding box is
     * read there before the predicate). Outside a scope always, without an ownership lookup.
     */
    public static boolean mayRead(final Entity entity) {
        final boolean inScope = CommandBlockPolicy.inScope();
        return CommandBlockPolicy.mayRead(inScope, inScope && RegionOwnership.current().isTickThreadFor(entity));
    }

    /** {@code CommandBlockEntity.markConditionMet}: whether the predecessor block may be read. */
    public static boolean mayReadPredecessor(final Level level, final BlockPos predecessor) {
        final boolean enabled = CommandBlockPolicy.enabled();
        return CommandBlockPolicy.mayReadPredecessor(enabled, enabled && RegionOwnership.current().isTickThreadFor(level, predecessor));
    }

    /**
     * {@code fill}/{@code fillbiome}: the engine loads the footprint asynchronously and mutates in the
     * completion callback, which may run later and elsewhere. Outside a scope that is unchanged. In a
     * scope the whole footprint (physics buffer included) must be this region's and the mutated chunks
     * loaded; the callback then runs now, on this thread, inside the scope.
     * The upstream call site's priority is passed through opaquely to the chunk-loading adapter.
     *
     * @throws CommandSyntaxException a foreign or unloaded footprint, as a command error
     */
    public static void loadChunksThenRun(final net.minecraft.server.level.ServerLevel level,
                                         final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                         final int mutatedMinChunkX, final int mutatedMaxChunkX,
                                         final int mutatedMinChunkZ, final int mutatedMaxChunkZ,
                                         final net.minecraft.world.level.chunk.status.ChunkStatus status,
                                         final Object priority,
                                         final java.util.function.Consumer<List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad,
                                         final String action) throws CommandSyntaxException {
        final boolean inScope = CommandBlockPolicy.inScope();
        final boolean owns = inScope && RegionOwnership.current().isTickThreadFor(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        final boolean loaded = owns && level.moonrise$areChunksLoaded(mutatedMinChunkX, mutatedMinChunkZ, mutatedMaxChunkX, mutatedMaxChunkZ);
        switch (CommandBlockPolicy.deferred(inScope, owns, loaded)) {
            case ROUTE_ASYNC -> RegionChunkLoading.current().loadChunksAsync(level, minChunkX, maxChunkX, minChunkZ, maxChunkZ, status, priority, onLoad);
            case REFUSE_FOREIGN -> throw CROSS_REGION.create(action);
            case REFUSE_UNLOADED -> throw NOT_LOADED.create(action);
            case RUN_NOW -> onLoad.accept(List.of());
        }
    }

    /** {@code EntitySelector} name/UUID lookups: in a scope, a foreign result becomes none. */
    public static <T extends Entity> List<T> single(final @Nullable T found) {
        final T owned = CommandBlockPolicy.ownedOrNull(CommandBlockPolicy.inScope(), found, entity -> RegionOwnership.current().isTickThreadFor(entity));
        return owned == null ? List.of() : List.of(owned);
    }

    /**
     * Fails the command when, in a scope, any chunk of the box is owned by another region.
     *
     * @throws CommandSyntaxException the command error a player sees as a red line
     */
    public static void requireOwned(final Level level, final int minChunkX, final int minChunkZ,
                                    final int maxChunkX, final int maxChunkZ, final String action)
        throws CommandSyntaxException {
        if (!CommandBlockPolicy.inScope()) {
            return;
        }
        final boolean owned = RegionOwnership.current().isTickThreadFor(level, Math.min(minChunkX, maxChunkX), Math.min(minChunkZ, maxChunkZ),
            Math.max(minChunkX, maxChunkX), Math.max(minChunkZ, maxChunkZ));
        if (!CommandBlockPolicy.allowsTarget(true, owned)) {
            throw CROSS_REGION.create(action);
        }
    }

    /** Block-coordinate form of {@link #requireOwned(Level, int, int, int, int, String)}. */
    public static void requireOwned(final Level level, final BlockPos pos, final int radiusBlocks, final String action)
        throws CommandSyntaxException {
        requireOwned(level, (pos.getX() - radiusBlocks) >> 4, (pos.getZ() - radiusBlocks) >> 4,
            (pos.getX() + radiusBlocks) >> 4, (pos.getZ() + radiusBlocks) >> 4, action);
    }

    /**
     * {@link #requireOwned(Level, BlockPos, int, String)} for the engine helpers whose signatures do
     * not declare {@link CommandSyntaxException} ({@code AbstractCommandExecution.executeAtPos},
     * {@code BlockDataAccessor.scheduleOrExecute}). They are only called while a command executes,
     * and Brigadier's execution loop catches the exception and reports it as a normal command
     * failure, so it is rethrown unchecked rather than wrapped (a wrapper would surface as
     * "An unexpected error occurred" with a logged stack trace).
     */
    public static void requireOwnedUnchecked(final Level level, final BlockPos pos, final int radiusBlocks,
                                             final String action) {
        try {
            requireOwned(level, pos, radiusBlocks, action);
        } catch (final CommandSyntaxException refused) {
            throw CommandBlockRegions.<RuntimeException>sneaky(refused);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(final Throwable throwable) throws E {
        throw (E) throwable;
    }
}
