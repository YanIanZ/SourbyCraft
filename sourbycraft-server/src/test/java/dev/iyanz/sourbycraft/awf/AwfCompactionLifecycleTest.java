package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rollback preparation through the engine and its flush/maintenance/close lifecycle, not
 * through {@code AwfWorldStore.commit}: a world has separate region, entities and poi stores,
 * flush/maintenance/close skip a clean one, so turning the flag off and saving does not convert
 * every store — the writable open with the flag off and {@code /awf compact} do, clean stores
 * included, and each store reports whether its current generation is a full index.
 */
class AwfCompactionLifecycleTest {

    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private AwfEngine engine(final boolean deltaGenerations) {
        return new AwfEngine(new AwfSettings(Set.of("q"), Set.of(), "file", PersistenceMode.INCREMENTAL, 1, 8, 3, 3,
            deltaGenerations), Runnable::run, this.clock::get);
    }

    private Path folder(final String kind) throws IOException {
        final Path folder = this.dir.resolve("q").resolve("overworld").resolve(kind);
        Files.createDirectories(folder);
        return folder;
    }

    private static Path generationsRoot(final Path folder) {
        return AwfRegionStorage.storeFor(folder).resolve("generations-root");
    }

    private static void writeAndFlush(final AwfRegionStorage storage, final int x, final String value) throws IOException {
        storage.write(x, 0, b(value));
        storage.flush();
    }

    @Test
    void flushMaintenanceAndCloseSkipCleanStoresButTheFlagOffOpenAndCompactConvertEveryOne() throws Exception {
        // Deltas on: three stores of one dimension, with different histories.
        final AwfEngine on = engine(true);
        final AwfRegionStorage region = on.open(folder("region"));
        final AwfRegionStorage entities = on.open(folder("entities"));
        final AwfRegionStorage poi = on.open(folder("poi"));
        for (final AwfRegionStorage storage : List.of(region, entities, poi)) {
            for (int i = 0; i < 20; i++) storage.write(i, 0, b("base-" + i));   // enough chunks for deltas
            storage.flush();                                                      // generation 1: full
            assertTrue(storage.fullIndex(), storage.name());
        }
        writeAndFlush(region, 0, "r2");      // region 2: delta
        writeAndFlush(region, 1, "r3");      // region 3: delta
        writeAndFlush(entities, 0, "e2");    // entities 2: delta
        assertFalse(region.fullIndex());
        assertFalse(entities.fullIndex());
        assertTrue(poi.fullIndex(), "never chained");

        // The gap the review found: with nothing dirty, flush, maintenance and close commit nothing,
        // so a clean store keeps a delta CURRENT that a pre-AWF-7 build rejects.
        entities.flush();
        this.clock.addAndGet(5_000_000_000L);
        assertEquals(0, on.maintain(), "nothing dirty: no commit is started");
        assertFalse(entities.fullIndex(), "flush and maintenance left the chain alone");
        assertThrows(IOException.class, () -> HeadBuildReader.read(generationsRoot(folder("entities"))));

        // /awf compact <world>: every open store of the world, clean ones included, on the storage lane.
        final List<AwfEngine.CompactionReport> reports =
            on.compactStorages(this.dir.resolve("q")).get();
        assertEquals(3, reports.size());
        for (final AwfEngine.CompactionReport report : reports) {
            assertNull(report.error(), report.toString());
            assertTrue(report.fullAfter(), report.toString());
            // region and entities were chained (compacted now); poi was full already (left alone).
            assertEquals(report.storage().endsWith("poi"), report.fullBefore(), report.toString());
            assertTrue(report.generation() > 0, report.toString());
        }
        for (final AwfRegionStorage storage : List.of(region, entities, poi)) {
            assertTrue(storage.fullIndex(), storage.name());
            final String kind = Path.of(storage.name()).getFileName().toString();
            assertEquals(20, HeadBuildReader.decodeIndex(HeadBuildReader.read(generationsRoot(folder(kind)))).size(), storage.name());
        }
        assertEquals(List.of(), on.compactStorages(this.dir.resolve("elsewhere")).get(), "no storage under another folder");
        assertEquals(3, on.compactStorages((Path) null).get().size());
        // The dirty chunks are still the storage's: a compaction carries no writes, a flush does.
        region.write(5, 0, b("dirty"));
        assertEquals(1, on.compactStorages((Path) null).get().stream().filter(r -> r.storage().endsWith("region")).count());
        assertTrue(region.fullIndex());
        assertArrayEquals(b("dirty"), region.read(5, 0));
        region.flush();
        assertFalse(region.fullIndex(), "with the flag on, the flush chains again");
        assertEquals(0, on.closeEvery());
        assertThrows(IllegalStateException.class, region::compact, "closed storages do not compact");

        // Deltas off: the writable open converts region (chained) at once, nothing is written for
        // entities and poi (already full), and every store reads on the pre-AWF-7 build.
        final AwfEngine off = engine(false);
        final long entitiesBefore = GenerationStore.open(generationsRoot(folder("entities")), WorldRole.READ_ONLY, 3).currentGeneration();
        final AwfRegionStorage region2 = off.open(folder("region"));
        final AwfRegionStorage entities2 = off.open(folder("entities"));
        final AwfRegionStorage poi2 = off.open(folder("poi"));
        for (final AwfRegionStorage storage : List.of(region2, entities2, poi2)) {
            assertTrue(storage.fullIndex(), storage.name() + " is a full index right after the open, no save needed");
            HeadBuildReader.read(generationsRoot(Path.of(storage.name())));
        }
        assertEquals(entitiesBefore, entities2.storeGeneration(), "a full store is not rewritten by the open");
        assertArrayEquals(b("dirty"), region2.read(5, 0));
        assertArrayEquals(b("r3"), region2.read(1, 0));
        assertArrayEquals(b("e2"), entities2.read(0, 0));
        final List<AwfEngine.CompactionReport> already = off.compactStorages((Path) null).get();
        assertTrue(already.stream().allMatch(r -> r.fullBefore() && r.fullAfter() && r.error() == null), already.toString());
        // And a store that is never opened by the server keeps whatever it has: only opened stores convert.
        assertEquals(0, off.closeEvery());
    }

    /** A dimension key without touching Level, whose class init needs a bootstrapped registry. */
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key(final String path) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.Identifier.withDefaultNamespace(path));
    }

    /** A 26.2 dimension folder, built by the function the server's storage source uses. */
    private Path dimension(final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        return AwfWorldFolders.dimensionFolder(this.dir.resolve("world"), key);
    }

    @Test
    void compactingANamedWorldTakesOnlyThatWorldsDimensionStoresInThe262Layout() throws Exception {
        // server-w2 awf4: every dimension lives under world/dimensions/<ns>/<name>/; "/awf compact w2a"
        // found nothing and "/awf compact world" compacted all 12 stores.
        final AwfEngine engine = new AwfEngine(new AwfSettings(Set.of("world", "w2a"), Set.of(), "file",
            PersistenceMode.INCREMENTAL, 1, 8, 3, 3, false), Runnable::run, this.clock::get);
        final var w2a = key("w2a");
        final List<Path> dimensions = List.of(dimension(key("overworld")),
            dimension(key("the_nether")), dimension(key("the_end")), dimension(w2a));
        for (final Path dimension : dimensions) {
            for (final String kind : List.of("region", "entities", "poi")) {
                Files.createDirectories(dimension.resolve(kind));
                engine.open(dimension.resolve(kind)).write(0, 0, b(kind));
            }
        }
        assertTrue(dimensions.get(3).endsWith(Path.of("world", "dimensions", "minecraft", "w2a")), dimensions.get(3).toString());
        // Bukkit world name -> that loaded world's dimension folder, as AwfWorldFolders resolves it on a server.
        final Path level = this.dir.resolve("world");
        final Map<String, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> loaded = Map.of(
            "world", key("overworld"), "world_nether", key("the_nether"),
            "world_the_end", key("the_end"), "w2a", w2a);
        final java.util.function.Function<String, Path> folders = name -> loaded.containsKey(name)
            ? AwfWorldFolders.dimensionFolder(level, loaded.get(name)) : null;
        final List<AwfEngine.CompactionReport> overworld = engine.compactStorages("world", folders).get();
        assertEquals(3, overworld.size(), "\"world\" is the overworld's stores only: " + overworld);
        assertTrue(overworld.stream().allMatch(r -> Path.of(r.storage()).getParent().equals(dimensions.get(0))), overworld.toString());
        final List<AwfEngine.CompactionReport> named = engine.compactStorages("w2a", folders).get();
        assertEquals(3, named.size(), "w2a's stores: " + named);
        assertTrue(named.stream().allMatch(r -> Path.of(r.storage()).getParent().equals(dimensions.get(3))), named.toString());
        assertEquals(3, engine.compactStorages("world_nether", folders).get().size());
        assertEquals(12, engine.compactStorages("all", folders).get().size());
        assertEquals(List.of(), engine.compactStorages("w2", folders).get(), "not loaded: nothing, never a prefix match");
        assertEquals(List.of(), engine.compactStorages("w2", name -> AwfWorldFolders.dimensionFolder(level, key("w2"))).get(), "a sibling whose name is a prefix is another world");
        assertEquals(0, engine.closeEvery());
    }

    @Test
    void theAutomaticCompactionAtOpenIsAnOrdinaryCommitThatACrashLeavesRecoverable() throws Exception {
        // A chain written with deltas on.
        final AwfWorldStore on = AwfWorldStore.open(this.dir.resolve("s"), WorldRole.VANILLA, 2, true);
        final Map<ChunkKey, byte[]> world = new java.util.HashMap<>();
        for (int i = 0; i < 20; i++) world.put(new ChunkKey(i, 0), b("w" + i));
        on.commit(world, Set.of(), PersistenceMode.INCREMENTAL);
        on.commit(Map.of(new ChunkKey(0, 0), b("changed")), Set.of(), PersistenceMode.INCREMENTAL);
        // A crash in the middle of the compaction at open: the open fails, the delta generation
        // stays current and intact, and the next open completes the compaction.
        final boolean[] crash = {true};
        assertThrows(IOException.class, () -> AwfWorldStore.open(this.dir.resolve("s"), WorldRole.VANILLA, 2, stage -> {
            if (crash[0] && stage == GenerationStore.Stage.VERIFIED) throw new IOException("crash during the compaction at open");
        }, Durability.DISK, AwfWorldStore.Compaction.FULL_ONLY));
        final AwfWorldStore readOnly = AwfWorldStore.open(this.dir.resolve("s"), WorldRole.READ_ONLY, 2, false);
        assertEquals(2L, readOnly.generation());
        assertEquals(1, readOnly.chainLength());
        assertArrayEquals(b("changed"), readOnly.read(new ChunkKey(0, 0)).orElseThrow());
        crash[0] = false;
        final AwfWorldStore off = AwfWorldStore.open(this.dir.resolve("s"), WorldRole.VANILLA, 2, false);
        assertEquals(3L, off.generation());
        assertTrue(off.fullIndex());
        assertArrayEquals(b("changed"), off.read(new ChunkKey(0, 0)).orElseThrow());
        assertEquals(20, HeadBuildReader.decodeIndex(HeadBuildReader.read(this.dir.resolve("s").resolve("generations-root"))).size());
    }
}
