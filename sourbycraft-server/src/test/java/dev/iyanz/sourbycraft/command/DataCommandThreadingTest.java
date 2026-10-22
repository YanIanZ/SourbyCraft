package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataCommandThreadingTest {
    private RegionOwnership ownership;
    private RegionOwnership previous;
    /** Tasks handed to the global tick from a region thread; run later by the test. */
    private final List<Runnable> queued = new ArrayList<>();
    private boolean global;

    @BeforeEach
    void install() {
        ownership = mock(RegionOwnership.class);
        when(ownership.isGlobalTickThread()).thenAnswer(call -> global);
        doAnswer(call -> {
            final Runnable task = call.getArgument(0);
            if (global) task.run(); else queued.add(() -> { global = true; try { task.run(); } finally { global = false; } });
            return null;
        }).when(ownership).executeOnGlobalTick(any());
        previous = RegionOwnership.replace(ownership);
    }

    @AfterEach
    void restore() {
        RegionOwnership.replace(previous);
    }

    private static CommandSyntaxException failure(final String text) {
        return new SimpleCommandExceptionType(Component.literal(text)).create();
    }

    private static CommandContext<CommandSourceStack> context(final CommandSourceStack source, final String... literals) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        LiteralArgumentBuilder<CommandSourceStack> node = LiteralArgumentBuilder.<CommandSourceStack>literal(literals[literals.length - 1])
            .executes(c -> 0);
        for (int i = literals.length - 2; i >= 0; i--) {
            node = LiteralArgumentBuilder.<CommandSourceStack>literal(literals[i]).then(node);
        }
        dispatcher.register(node);
        final String input = String.join(" ", literals);
        return dispatcher.parse(input, source).getContext().build(input);
    }

    @Test
    void consoleStorageCommandReturnsItsRealResultAndFailsAsACommandError() throws Exception {
        global = true;
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final var ctx = context(source, "data", "get", "storage");
        assertEquals(7, DataCommandThreading.execute(ctx, () -> 7), "inline on the global tick: real value, not a fake 1");
        final CommandSyntaxException thrown = assertThrows(CommandSyntaxException.class,
            () -> DataCommandThreading.execute(ctx, () -> { throw failure("no such path"); }));
        assertEquals("no such path", thrown.getMessage());
        verifyNoInteractions(source);
    }

    @Test
    void regionStorageCommandReturnsZeroAndReportsWhenItRuns() throws Exception {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final var ctx = context(source, "data", "get", "storage");
        final AtomicBoolean ran = new AtomicBoolean();
        assertEquals(0, DataCommandThreading.execute(ctx, () -> { ran.set(true); throw failure("storage failure"); }),
            "deferred: execute store / success counts must not see a fake success");
        assertFalse(ran.get());
        verifyNoInteractions(source);
        queued.forEach(Runnable::run);
        assertTrue(ran.get());
        verify(source).sendFailure(argThat(c -> c.getString().equals("storage failure")));
    }

    @Test
    void deferredRuntimeFailureIsCaughtAndReportedNotThrownIntoTheGlobalTick() {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final AtomicReference<Runnable> later = new AtomicReference<>();
        assertDoesNotThrow(() -> assertEquals(0,
            DataCommandThreading.runOrDefer(later::set, () -> { throw new IllegalStateException("boom"); }, source)));
        assertDoesNotThrow(() -> later.get().run());
        verify(source).sendFailure(any());
    }

    @Test
    void inlineRuntimeFailurePropagatesToTheCommandDispatcher() {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        assertThrows(IllegalStateException.class,
            () -> DataCommandThreading.runOrDefer(Runnable::run, () -> { throw new IllegalStateException("boom"); }, source));
        verifyNoInteractions(source);
    }

    @Test
    void nonStorageCommandRunsInlineWithItsResult() throws Exception {
        final var ctx = context(mock(CommandSourceStack.class), "data", "get", "entity");
        assertEquals(3, DataCommandThreading.execute(ctx, () -> 3));
        verify(ownership, never()).executeOnGlobalTick(any());
    }

    @Test
    void storageOffTheGlobalTickIsACommandErrorNotARuntimeException() throws Exception {
        // /execute if data storage, /execute store ... storage, /function ... with storage from a region.
        final CommandSyntaxException error = assertThrows(CommandSyntaxException.class,
            DataCommandThreading::requireGlobalTickForStorage);
        assertTrue(error.getMessage().contains("global tick"));
        verify(ownership, never()).ensureGlobalTickThread(anyString());
        global = true;
        DataCommandThreading.requireGlobalTickForStorage();
    }

    @Test
    void storageTargetFromEntitySourceReadsTheSourceOnItsOwnerThenWritesOnTheGlobalTick() throws Exception {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final var ctx = context(source, "data", "modify", "storage", "from", "entity");
        final CompoundTag entityData = new CompoundTag();
        entityData.putInt("Health", 20);
        final List<String> order = new ArrayList<>();
        final AtomicReference<List<Tag>> applied = new AtomicReference<>();
        assertEquals(0, DataCommandThreading.modifyFrom(ctx,
            () -> { order.add("read:" + (global ? "global" : "owner")); return List.of(entityData); },
            () -> { order.add("resolve:" + (global ? "global" : "owner")); return "storage"; },
            (target, tags) -> { order.add("apply:" + (global ? "global" : "owner")); applied.set(tags); return 1; }));
        assertEquals(List.of("read:owner"), order);
        entityData.putInt("Health", 1); // the owner keeps mutating its entity
        queued.forEach(Runnable::run);
        assertEquals(List.of("read:owner", "resolve:global", "apply:global"), order);
        assertEquals(20, ((CompoundTag) applied.get().getFirst()).getIntOr("Health", -1), "storage got a copy");
    }

    @Test
    void entityTargetFromStorageSourceResolvesTheTargetFirstAndReadsStorageOnTheGlobalTick() throws Exception {
        final CommandSourceStack source = mock(CommandSourceStack.class);
        final var ctx = context(source, "data", "modify", "entity", "from", "storage");
        final List<String> order = new ArrayList<>();
        final CompoundTag stored = new CompoundTag();
        final AtomicReference<List<Tag>> applied = new AtomicReference<>();
        DataCommandThreading.modifyFrom(ctx,
            () -> { order.add("read:" + (global ? "global" : "owner")); return List.of(stored); },
            () -> { order.add("resolve:" + (global ? "global" : "owner")); return "entity"; },
            (target, tags) -> { order.add("apply:" + target + ":" + (global ? "global" : "owner")); applied.set(tags); return 1; });
        assertEquals(List.of("resolve:owner"), order);
        queued.forEach(Runnable::run);
        assertEquals(List.of("resolve:owner", "read:global", "apply:entity:global"), order);
        assertNotSame(stored, applied.get().getFirst());
    }

    @Test
    void storageToStorageOnTheConsoleRunsInlineWithItsResult() throws Exception {
        global = true;
        final var ctx = context(mock(CommandSourceStack.class), "data", "modify", "storage", "from", "storage");
        assertEquals(5, DataCommandThreading.modifyFrom(ctx, List::of, () -> "s", (t, tags) -> 5));
        assertTrue(queued.isEmpty());
    }

    @Test
    void blockAndEntityCommandsKeepTheirInlinePath() throws Exception {
        final var ctx = context(mock(CommandSourceStack.class), "data", "modify", "block", "from", "entity");
        assertEquals(4, DataCommandThreading.modifyFrom(ctx, List::of, () -> "b", (t, tags) -> 4));
        verify(ownership, never()).executeOnGlobalTick(any());
    }

    @Test
    void sidesAreTargetThenSource() {
        final CommandSourceStack s = mock(CommandSourceStack.class);
        assertEquals(DataCommandThreading.Side.STORAGE, DataCommandThreading.side(context(s, "data", "modify", "storage", "from", "block"), 0));
        assertEquals(DataCommandThreading.Side.OTHER, DataCommandThreading.side(context(s, "data", "modify", "storage", "from", "block"), 1));
        assertEquals(DataCommandThreading.Side.NONE, DataCommandThreading.side(context(s, "data", "get", "entity"), 1));
    }

    @Test
    void failingStorageSuggestionsCompleteEmptyInsteadOfThrowing() throws Exception {
        global = true;
        final SuggestionsBuilder builder = new SuggestionsBuilder("data get storage ", 17);
        final var result = DataCommandThreading.suggestOnGlobalTick(builder, () -> { throw new IllegalStateException("boom"); });
        final Suggestions suggestions = result.get();
        assertTrue(suggestions.isEmpty());
        final var failed = DataCommandThreading.suggestOnGlobalTick(builder,
            () -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("async boom")));
        assertTrue(failed.get().isEmpty());
    }

    @Test
    void blockLookupRefusesAnUnownedPositionBeforeReadingWorldData() throws Exception {
        final Level level = mock(Level.class);
        final BlockPos pos = new BlockPos(1, 2, 3);
        final CommandSyntaxException error = assertThrows(CommandSyntaxException.class,
            () -> DataCommandThreading.ensureBlockOwned(level, pos));
        assertTrue(error.getMessage().contains("owning region"));
        verifyNoInteractions(level);
        when(ownership.isTickThreadFor(level, pos)).thenReturn(true);
        DataCommandThreading.ensureBlockOwned(level, pos);
    }
}
