package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.commands.FunctionCommand;
import net.minecraft.server.commands.ReturnCommand;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Unit;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads a real datapack directory through vanilla's {@link ServerFunctionLibrary} reload, the
 * listener feature patch 0066 puts back into {@code ReloadableServerResources.listeners()}.
 */
@AllFeatures
class DatapackFunctionLibraryTest {
    @TempDir
    Path pack;

    private final List<String> probes = new ArrayList<>();

    private void write(final String path, final String... lines) throws IOException {
        final Path file = this.pack.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines));
    }

    private void writePack() throws IOException {
        write("data/test/function/a.mcfunction", "probe a1", "function test:b", "probe a2");
        write("data/test/function/b.mcfunction", "# nested, with an early return", "probe b1", "return 1", "probe never");
        write("data/test/function/t1.mcfunction", "probe t1");
        write("data/test/function/bad.mcfunction", "probe ok", "this_is_not_a_command at all");
        write("data/minecraft/tags/function/load.json", "{\"values\":[\"test:a\"]}");
        write("data/minecraft/tags/function/tick.json", "{\"values\":[\"test:t1\",\"#test:more\"]}");
        write("data/test/tags/function/more.json", "{\"values\":[\"test:b\"]}");
        write("data/test/tags/function/broken.json", "{\"values\":[\"test:missing\"]}");
    }

    /** The commands the functions use; {@code function}/{@code return} exactly as {@code Commands} registers them. */
    private CommandDispatcher<CommandSourceStack> dispatcher(final boolean withFunctionCommands) {
        final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("probe").then(Commands.argument("word", StringArgumentType.word())
            .executes(context -> {
                this.probes.add(StringArgumentType.getString(context, "word"));
                return 1;
            })));
        if (withFunctionCommands) {
            FunctionCommand.register(dispatcher);
            ReturnCommand.register(dispatcher);
        }
        return dispatcher;
    }

    private MultiPackResourceManager resources() {
        final PackLocationInfo info = new PackLocationInfo("test", Component.literal("test"), PackSource.BUILT_IN, Optional.empty());
        return new MultiPackResourceManager(PackType.SERVER_DATA, List.of(new PathPackResources(info, this.pack)));
    }

    private static CompletableFuture<?> reload(final ResourceManager manager, final PreparableReloadListener library) {
        // Same call ReloadableServerResources.loadResources makes, with direct executors.
        return SimpleReloadInstance.create(manager, List.of(library), Runnable::run, Runnable::run,
            CompletableFuture.completedFuture(Unit.INSTANCE), false).done();
    }

    private ServerFunctionLibrary load(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final ServerFunctionLibrary library = new ServerFunctionLibrary(LevelBasedPermissionSet.GAMEMASTER, dispatcher);
        try (MultiPackResourceManager manager = resources()) {
            reload(manager, library).join();
        }
        return library;
    }

    private static List<String> ids(final List<CommandFunction<CommandSourceStack>> functions) {
        return functions.stream().map(function -> function.id().toString()).toList();
    }

    @Test
    void functionsAndFunctionTagsLoadAndAMalformedFileIsSkippedAlone() throws IOException {
        writePack();
        final ServerFunctionLibrary library = load(dispatcher(true));
        assertEquals(List.of("test:a", "test:b", "test:t1"),
            library.getFunctions().keySet().stream().map(Identifier::toString).sorted().toList(),
            "bad.mcfunction fails to parse and is dropped; the others load");
        assertEquals(List.of("test:a"), ids(library.getTag(Identifier.parse("minecraft:load"))));
        assertEquals(List.of("test:t1", "test:b"), ids(library.getTag(Identifier.parse("minecraft:tick"))),
            "nested tag reference resolved");
        assertTrue(library.getTag(Identifier.parse("test:broken")).isEmpty(), "tag with a missing function is not built");
    }

    @Test
    void withoutTheFunctionCommandAFunctionCallingAnotherCannotLoad() throws IOException {
        writePack();
        final ServerFunctionLibrary library = load(dispatcher(false));
        assertFalse(library.getFunction(Identifier.parse("test:a")).isPresent(),
            "why /function and /return must be registered for datapacks, not only for players");
        assertFalse(library.getFunction(Identifier.parse("test:b")).isPresent());
        assertTrue(library.getFunction(Identifier.parse("test:t1")).isPresent());
    }

    @Test
    void nestedFunctionsRunInOrderAndReturnEndsTheCallee() throws IOException {
        writePack();
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(true);
        final ServerFunctionLibrary library = load(dispatcher);
        final MinecraftServer server = mock(MinecraftServer.class);
        final Commands commands = mock(Commands.class);
        when(commands.getDispatcher()).thenReturn(dispatcher);
        when(server.getCommands()).thenReturn(commands);
        final ServerFunctionManager functions = new ServerFunctionManager(server, library);
        when(server.getFunctions()).thenReturn(functions);
        final CommandSourceStack source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null,
            LevelBasedPermissionSet.GAMEMASTER, "test", Component.literal("test"), server, null).withSuppressedOutput();

        final CommandFunction<CommandSourceStack> a = functions.get(Identifier.parse("test:a")).orElseThrow();
        // The body of Commands.executeCommandInContext, minus the gamerule lookup that needs a level.
        try (ExecutionContext<CommandSourceStack> context = new ExecutionContext<>(65536, 65536)) {
            ExecutionContext.queueInitialFunctionCall(context, a.instantiate(null, dispatcher), source, CommandResultCallback.EMPTY);
            context.runCommandQueue();
        } catch (final Exception failure) {
            fail(failure);
        }
        assertEquals(List.of("a1", "b1", "a2"), this.probes);
    }

    @Test
    void everyLibraryPublishStartsAGenerationAndAFailedReloadKeepsTheOldLibrary() throws IOException {
        writePack();
        final CommandDispatcher<CommandSourceStack> dispatcher = dispatcher(true);
        final MinecraftServer server = mock(MinecraftServer.class);
        final long before = DatapackFunctions.LIFECYCLE.generation();

        final ServerFunctionLibrary first = load(dispatcher);
        final ServerFunctionManager functions = new ServerFunctionManager(server, first); // boot
        assertEquals(before + 1, DatapackFunctions.LIFECYCLE.generation(), "boot publish");

        write("data/test/function/t1.mcfunction", "probe t1v2");
        final ServerFunctionLibrary second = load(dispatcher);
        functions.replaceLibrary(second); // what a successful reload does
        assertEquals(before + 2, DatapackFunctions.LIFECYCLE.generation());
        final CommandFunction<CommandSourceStack> current = functions.get(Identifier.parse("test:t1")).orElseThrow();

        // A reload whose preparation fails: MinecraftServer.reloadResources swaps libraries only in a
        // completion stage after the reload future, so a failed future never reaches replaceLibrary.
        final ServerFunctionLibrary third = new ServerFunctionLibrary(LevelBasedPermissionSet.GAMEMASTER, dispatcher);
        final MultiPackResourceManager good = resources();
        final ResourceManager failing = mock(ResourceManager.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("list")) {
                throw new IllegalStateException("simulated broken pack");
            }
            return invocation.getMethod().invoke(good, invocation.getArguments());
        });
        final CompletableFuture<?> failed = reload(failing, third).thenRun(() -> functions.replaceLibrary(third));
        assertThrows(CompletionException.class, failed::join);
        good.close();
        assertEquals(before + 2, DatapackFunctions.LIFECYCLE.generation(), "no publish, so #minecraft:load does not run again");
        assertSame(current, functions.get(Identifier.parse("test:t1")).orElseThrow(), "old library still answers");
        assertEquals(List.of("test:a"), ids(functions.getTag(Identifier.parse("minecraft:load"))));
    }
}
