package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCommandPatchShapeTest {
    private static String patch() throws Exception {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String base : List.of("minecraft-patches/features", "sourbycraft-server/minecraft-patches/features")) {
                if (!Files.isDirectory(dir.resolve(base))) continue;
                try (var files = Files.list(dir.resolve(base))) {
                    final var found = files.filter(p -> p.getFileName().toString()
                        .contains("-SourbyCraft-data-command-threading.patch")).findFirst();
                    if (found.isPresent()) return Files.readString(found.get());
                }
            }
        }
        return fail("data command feature patch missing");
    }

    @Test
    void storageIsRegisteredAndAllReadsRouteThroughTheOwnedHelper() throws Exception {
        final String patch = patch();
        assertTrue(patch.contains("+        EntityDataAccessor.PROVIDER, BlockDataAccessor.PROVIDER, StorageDataAccessor.PROVIDER"));
        assertTrue(patch.contains("DataCommandThreading.execute(c, () -> getData(c.getSource(), targetProvider.access(c))"));
        assertTrue(patch.contains("DataCommandThreading.modifyFrom(c, () -> getSingletonSource(c, sourceProvider), () -> targetProvider.access(c)"));
        assertFalse(patch.contains("DataCommandThreading.execute(c, () -> manipulateData(c, targetProvider, manipulator, getSingletonSource"),
            "mixed storage + block/entity commands must not move whole to the global tick");
        assertTrue(patch.contains("DataCommandThreading.suggestOnGlobalTick(p,"));
        assertTrue(patch.contains("DataCommandThreading.scheduleOrExecute(() -> consumer.accept(this))"));
        assertTrue(patch.contains("+        ensureTickThread(\"Read command storage\")"));
        assertTrue(patch.contains("+        ensureTickThread(\"Write command storage\")"));
    }

    @Test
    void storageOffTheGlobalTickIsACommandErrorForExecuteAndFunctionToo() throws Exception {
        // ExecuteCommand (store/if data) and FunctionCommand (with storage) call access()/getData()
        // directly from region threads; they must see a CommandSyntaxException, never an ISE.
        final String patch = patch();
        final int access = patch.indexOf("public DataAccessor access(final CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {\n+            dev.iyanz.sourbycraft.command.DataCommandThreading.requireGlobalTickForStorage();");
        assertTrue(access >= 0);
        assertFalse(patch.contains("DataCommandThreading.ensureGlobalTickThread(reason)"));
    }

    @Test
    void handleActionReturnsTheRealResultOrZeroWhenDeferred() throws Exception {
        final String patch = patch();
        assertTrue(patch.contains("return dev.iyanz.sourbycraft.command.DataCommandThreading.runOrDefer("));
        assertTrue(patch.contains("+            return iAction.act(accessor); // SourbyCraft - real result"));
    }

    @Test
    void consoleBlockDataIsHandedToTheOwningRegionWithoutTouchingTheWorld() throws Exception {
        final String patch = patch();
        final int global = patch.indexOf("+            if (dev.iyanz.sourbycraft.command.DataCommandThreading.isGlobalTickThread()) {");
        final int lazy = patch.indexOf("+                return new BlockDataAccessor(level, requested);", global);
        final int guard = patch.indexOf("+            dev.iyanz.sourbycraft.command.DataCommandThreading.ensureBlockOwned(level, requested);", lazy);
        assertTrue(global >= 0 && lazy > global && guard > lazy);
        final int loaded = patch.indexOf("BlockPosArgument.getLoadedBlockPos", guard);
        final int entity = patch.indexOf("getBlockEntity(pos)", guard);
        assertTrue(loaded > guard && entity > loaded, "regions still refuse before any world lookup");
        // The console accessor looks its block entity up only after an ownership check on the owner.
        final int resolve = patch.indexOf("+    private BlockEntity entity() throws");
        final int check = patch.indexOf("+            this.ensureTickThread(", resolve);
        final int lookup = patch.indexOf("+            final BlockEntity found = this.level.getBlockEntity(this.pos);", check);
        assertTrue(resolve >= 0 && check > resolve && lookup > check);
    }
}
