package dev.iyanz.sourbycraft.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.logging.LogUtils;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Where each half of a {@code /data} command runs.
 *
 * <p>Command storage belongs to the global tick. Block and entity data belong to the region that
 * owns them. A command that touches only storage runs on the global tick; a command that mixes
 * storage with a block or entity reads the source on its owner, copies it, and completes the
 * write on the target's owner (see {@link #modifyFrom}).</p>
 *
 * <p>Result semantics: a command that completes on the calling thread returns its real result
 * and fails with its real {@link CommandSyntaxException}. A command that has to be handed to
 * another thread cannot return the deferred value, so it returns {@code 0} (never a fake success
 * for {@code execute store} or success counts) and reports its outcome to the sender when it
 * runs.</p>
 */
public final class DataCommandThreading {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SimpleCommandExceptionType BLOCK_NOT_OWNED = new SimpleCommandExceptionType(
        Component.literal("Cannot access block data outside the owning region; run from a player or command block in that region"));
    private static final SimpleCommandExceptionType STORAGE_OFF_GLOBAL = new SimpleCommandExceptionType(
        Component.literal("Command storage is only available on the global tick (console, RCON, datapack functions or /data storage); this command ran inside a region"));

    /** Which kind of data a side of the command names. */
    enum Side { NONE, STORAGE, OTHER }

    private DataCommandThreading() {}

    @FunctionalInterface
    public interface Action {
        int run() throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface Read {
        List<Tag> read() throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface Resolve<T> {
        T resolve() throws CommandSyntaxException;
    }

    @FunctionalInterface
    public interface Apply<T> {
        int apply(T target, List<Tag> source) throws CommandSyntaxException;
    }

    public static boolean isGlobalTickThread() {
        return RegionOwnership.current().isGlobalTickThread();
    }

    /** Thread assertion for work that is already scheduled on the global tick. */
    public static void ensureGlobalTickThread(final String reason) {
        RegionOwnership.current().ensureGlobalTickThread(reason);
    }

    /** Command-level refusal: storage touched from a region becomes a command error, never an exception. */
    public static void requireGlobalTickForStorage() throws CommandSyntaxException {
        if (!isGlobalTickThread()) {
            throw STORAGE_OFF_GLOBAL.create();
        }
    }

    public static void scheduleOrExecute(final Runnable task) {
        RegionOwnership.current().executeOnGlobalTick(() -> {
            ensureGlobalTickThread("Command storage belongs to the global tick");
            task.run();
        });
    }

    /** {@code get}, {@code merge}, {@code remove} and {@code modify ... value}: one target, no source. */
    public static int execute(final CommandContext<CommandSourceStack> context, final Action action)
        throws CommandSyntaxException {
        if (side(context, 0) != Side.STORAGE) {
            return action.run();
        }
        return runOrDefer(DataCommandThreading::scheduleOrExecute, action, context.getSource());
    }

    /**
     * {@code modify ... from|string}: read the source where it is owned, apply to the target where it
     * is owned. Tags crossing threads are copied, so neither side shares a mutable tag.
     */
    public static <T> int modifyFrom(final CommandContext<CommandSourceStack> context, final Read read,
                                     final Resolve<T> resolve, final Apply<T> apply) throws CommandSyntaxException {
        final Side target = side(context, 0);
        final Side source = side(context, 1);
        final CommandSourceStack sender = context.getSource();
        if (target == Side.STORAGE && source == Side.STORAGE) {
            return runOrDefer(DataCommandThreading::scheduleOrExecute, () -> {
                final List<Tag> tags = read.read();
                return apply.apply(resolve.resolve(), tags);
            }, sender);
        }
        if (target == Side.STORAGE) {
            // Source block/entity is read here, on its owner (or refused with a command error).
            final List<Tag> tags = copy(read.read());
            return runOrDefer(DataCommandThreading::scheduleOrExecute, () -> apply.apply(resolve.resolve(), tags), sender);
        }
        if (source == Side.STORAGE) {
            // Target is resolved here, then storage is read on the global tick; the target's
            // accessor hands the write to its owner from there.
            final T resolved = resolve.resolve();
            return runOrDefer(DataCommandThreading::scheduleOrExecute, () -> apply.apply(resolved, copy(read.read())), sender);
        }
        final List<Tag> tags = read.read();
        return apply.apply(resolve.resolve(), tags);
    }

    /**
     * Runs {@code action} through {@code scheduler}. When the scheduler runs it inline on the
     * calling thread, the real result is returned and its failure propagates. When it is deferred,
     * {@code 0} is returned and the outcome is reported to {@code sender} when it runs.
     */
    public static int runOrDefer(final Consumer<Runnable> scheduler, final Action action, final CommandSourceStack sender)
        throws CommandSyntaxException {
        final Thread caller = Thread.currentThread();
        final Inline inline = new Inline();
        scheduler.accept(() -> {
            if (inline.open && Thread.currentThread() == caller) {
                inline.ran = true;
                try {
                    inline.result = action.run();
                } catch (final CommandSyntaxException error) {
                    inline.syntax = error;
                } catch (final RuntimeException error) {
                    inline.runtime = error;
                }
            } else {
                runDeferred(action, sender);
            }
        });
        inline.open = false;
        if (!inline.ran) {
            return 0;
        }
        if (inline.syntax != null) throw inline.syntax;
        if (inline.runtime != null) throw inline.runtime;
        return inline.result;
    }

    private static final class Inline {
        volatile boolean open = true;
        boolean ran;
        int result;
        CommandSyntaxException syntax;
        RuntimeException runtime;
    }

    /** A handed-off action: every failure reaches the sender, and a runtime failure is logged once here. */
    static void runDeferred(final Action action, final CommandSourceStack sender) {
        try {
            action.run();
        } catch (final CommandSyntaxException error) {
            fail(sender, ComponentUtils.fromMessage(error.getRawMessage()));
        } catch (final RuntimeException error) {
            LOGGER.error("Deferred /data command failed", error);
            fail(sender, Component.literal("An unexpected error occurred trying to execute that command"));
        }
    }

    private static void fail(final CommandSourceStack sender, final Component message) {
        try {
            sender.sendFailure(message);
        } catch (final RuntimeException error) {
            LOGGER.warn("Could not report a deferred /data failure to its sender", error);
        }
    }

    /** Storage id suggestions read storage on the global tick; any failure yields no suggestions. */
    public static CompletableFuture<Suggestions> suggestOnGlobalTick(final SuggestionsBuilder builder,
                                                                     final Supplier<CompletableFuture<Suggestions>> supplier) {
        final CompletableFuture<Suggestions> result = new CompletableFuture<>();
        try {
            scheduleOrExecute(() -> {
                try {
                    supplier.get().whenComplete((value, error) -> {
                        if (error == null) {
                            result.complete(value);
                        } else {
                            suggestionFailed(result, builder, error);
                        }
                    });
                } catch (final RuntimeException error) {
                    suggestionFailed(result, builder, error);
                }
            });
        } catch (final RuntimeException error) {
            suggestionFailed(result, builder, error);
        }
        return result;
    }

    private static void suggestionFailed(final CompletableFuture<Suggestions> result, final SuggestionsBuilder builder,
                                         final Throwable error) {
        LOGGER.warn("Command storage suggestions failed", error);
        result.complete(builder.build());
    }

    public static void ensureBlockOwned(final Level level, final BlockPos pos) throws CommandSyntaxException {
        if (!RegionOwnership.current().isTickThreadFor(level, pos)) {
            throw BLOCK_NOT_OWNED.create();
        }
    }

    /** The data kind of the n-th {@code block|entity|storage} literal: 0 = target, 1 = source. */
    static Side side(final CommandContext<CommandSourceStack> context, final int index) {
        int seen = 0;
        for (final ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (!(node.getNode() instanceof LiteralCommandNode<?> literal)) continue;
            final String name = literal.getLiteral();
            if (!name.equals("block") && !name.equals("entity") && !name.equals("storage")) continue;
            if (seen++ == index) {
                return name.equals("storage") ? Side.STORAGE : Side.OTHER;
            }
        }
        return Side.NONE;
    }

    private static List<Tag> copy(final List<Tag> tags) {
        final List<Tag> copies = new ArrayList<>(tags.size());
        for (final Tag tag : tags) {
            copies.add(tag.copy());
        }
        return copies;
    }
}
