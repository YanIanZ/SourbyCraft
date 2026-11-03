package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.AwfBackendKind;
import dev.iyanz.sourbycraft.api.world.AwfPersistenceMode;
import dev.iyanz.sourbycraft.api.world.AwfWorldInfo;
import dev.iyanz.sourbycraft.api.world.AwfWorldSummary;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 54 developer API of Aurora World Fabric that runs without a server: disposable instances
 * ({@link AuroraWorlds#createInstance}) as registry entries (persisted flag, never autoloaded, not inherited by copies,
 * read as an ordinary entry by a build that does not know the flag), the service refusing to autoload one, the sweep
 * doing nothing before startup finished, {@code info} of an unknown world, {@code listTemplates}, the summary and info
 * shapes, the persistence-mode mapping {@code listWorlds} relies on, and the API's defaults for providers built before
 * Build 54.
 *
 * <p>Not covered here (they need a running server and are left to the owner's server tests): creating, unloading and
 * deleting a live instance, the sweep after a crash deleting it once the server runs, and {@code listWorlds}/{@code info}
 * of a known world (both read the live world list and the started AWF engine).</p>
 */
class AuroraWorldsDisposableInstanceTest {

    @TempDir
    Path dir;

    private AuroraWorldsService service(final AuroraWorldRegistry registry) throws Exception {
        return new AuroraWorldsService(registry, new AuroraTemplates(this.dir.resolve("templates")), name -> this.dir.resolve(name));
    }

    @Test
    void aDisposableInstanceIsAPersistedFlagThatIsNeverAutoloadedOrInherited() throws Exception {
        final AuroraWorldRegistry.Entry template = new AuroraWorldRegistry.Entry("lobby", "normal", 7L, "void", null, null, false);
        final AuroraWorldRegistry.Entry ordinary = template.named("arena-1", "lobby", true);
        assertNull(ordinary.deleteOnUnload());
        assertFalse(ordinary.deletedOnUnload());
        final AuroraWorldRegistry.Entry instance = template.named("arena-2", "lobby", true).asDeletedOnUnload();
        assertTrue(instance.deletedOnUnload());
        assertFalse(instance.autoload(), "a disposable instance is never loaded at startup");
        assertEquals("lobby", instance.template());
        assertTrue(instance.withProperties(dev.iyanz.sourbycraft.api.world.WorldProperties.NONE).deletedOnUnload());
        assertTrue(instance.withKnownStores(List.of("region")).deletedOnUnload());
        assertTrue(instance.withAutoload(false).deletedOnUnload());
        assertFalse(instance.named("copy", "lobby", false).deletedOnUnload(), "a copy inherited the disposable flag");

        final Path file = this.dir.resolve("aurora-worlds.json");
        final AuroraWorldRegistry registry = new AuroraWorldRegistry(file);
        registry.put(ordinary);
        registry.put(instance);
        final String json = Files.readString(file);
        assertEquals(1, json.split("deleteOnUnload", -1).length - 1, "an ordinary entry was written with the new field: " + json);

        // After a restart (or a crash) the instance is still known as disposable, so the sweep can delete it.
        final AuroraWorldRegistry reopened = new AuroraWorldRegistry(file);
        assertTrue(reopened.get("arena-2").orElseThrow().deletedOnUnload());
        assertFalse(reopened.get("arena-2").orElseThrow().autoload());
        assertFalse(reopened.get("arena-1").orElseThrow().deletedOnUnload());
        assertEquals(instance, reopened.get("arena-2").orElseThrow());

        // An entry written before Build 54 has no such field and reads as an ordinary one.
        Files.writeString(file, "[{\"name\":\"old\",\"environment\":\"normal\",\"autoload\":true}]");
        assertFalse(new AuroraWorldRegistry(file).get("old").orElseThrow().deletedOnUnload());
    }

    @Test
    void theServiceRefusesToAutoloadAnInstanceAndTheSweepWaitsForStartup() throws Exception {
        final AuroraWorldRegistry registry = new AuroraWorldRegistry(this.dir.resolve("aurora-worlds.json"));
        final AuroraWorldRegistry.Entry instance = new AuroraWorldRegistry.Entry("arena", "normal", null, "void", null, "lobby", false)
            .asDeletedOnUnload();
        registry.put(instance);
        Files.createDirectories(this.dir.resolve("arena"));
        final AuroraWorldsService service = service(registry);

        assertThrows(IllegalStateException.class, () -> service.setAutoload("arena", true));
        assertFalse(registry.get("arena").orElseThrow().autoload());
        service.setAutoload("arena", false);
        assertTrue(registry.get("arena").orElseThrow().deletedOnUnload());

        // Before startup finished (scheduleAutoload) the sweep deletes nothing.
        service.sweepDisposableInstances();
        assertTrue(registry.contains("arena"));
        assertTrue(Files.isDirectory(this.dir.resolve("arena")));
        assertTrue(service.exists("arena"));
        assertEquals(java.util.Set.of("arena"), service.list());

        assertEquals(Optional.empty(), service.info("nowhere").get(10, TimeUnit.SECONDS), "an unknown world has no info");
        assertTrue(service.listTemplates().get(10, TimeUnit.SECONDS).isEmpty());
    }

    /**
     * Review P2-6: a deletion marks the world name in the engine for its duration (one deletion at a time per name), so
     * an attach of the same name in that window is refused; the mark ends with the deletion. The live attach refusal
     * needs a running server and is not exercised here.
     */
    @Test
    void aDeletionMarksTheWorldNameUntilItEnds() {
        final String name = "b54-deletion-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        assertFalse(dev.iyanz.sourbycraft.awf.AwfEngine.deleting(name));
        assertTrue(dev.iyanz.sourbycraft.awf.AwfEngine.beginDeletion(name));
        try {
            assertTrue(dev.iyanz.sourbycraft.awf.AwfEngine.deleting(name));
            assertFalse(dev.iyanz.sourbycraft.awf.AwfEngine.beginDeletion(name), "two deletions of one world ran at once");
            assertFalse(dev.iyanz.sourbycraft.awf.AwfEngine.deleting(name + "-other"));
        } finally {
            dev.iyanz.sourbycraft.awf.AwfEngine.endDeletion(name);
        }
        assertFalse(dev.iyanz.sourbycraft.awf.AwfEngine.deleting(name));
    }

    @Test
    void summaryAndInfoShapes() {
        final AwfWorldSummary summary = new AwfWorldSummary("arena", true, false, Optional.of("lobby"), AwfBackendKind.FILE, "file",
            AwfPersistenceMode.INCREMENTAL, true);
        final AwfWorldInfo info = new AwfWorldInfo(summary, "awf-generations", OptionalLong.of(4096L), 3, 3,
            Optional.of(Instant.ofEpochSecond(1)));
        assertEquals("arena", info.name());
        assertTrue(info.loaded());
        assertEquals(AwfBackendKind.FILE, info.backend());
        assertTrue(info.world().deleteOnUnload());
        assertThrows(NullPointerException.class, () -> new AwfWorldSummary("arena", true, false, null, AwfBackendKind.FILE, "file",
            AwfPersistenceMode.FULL, false));
        assertThrows(NullPointerException.class, () -> new AwfWorldInfo(summary, "x", null, 0, 0, Optional.empty()));
        // listWorlds maps the engine's persistence mode by name: every mode must have its API counterpart.
        for (final PersistenceMode mode : PersistenceMode.values()) assertEquals(mode.name(), AwfPersistenceMode.valueOf(mode.name()).name());
        assertEquals(PersistenceMode.values().length, AwfPersistenceMode.values().length);
    }

    @Test
    void providersBuiltBeforeBuild54KeepLinking() throws Exception {
        final AuroraWorlds old = mock(AuroraWorlds.class, CALLS_REAL_METHODS);
        for (final String name : List.of("listWorlds", "info", "listTemplates", "isAwfWorld", "createInstance")) {
            for (final java.lang.reflect.Method method : AuroraWorlds.class.getMethods()) {
                if (method.getName().equals(name)) assertTrue(method.isDefault(), method + " is not a default method");
            }
        }
        assertThrows(UnsupportedOperationException.class, old::listWorlds);
        final ExecutionException failed = assertThrows(ExecutionException.class, () -> old.createInstance("lobby", "arena")
            .get(10, TimeUnit.SECONDS));
        assertTrue(failed.getCause() instanceof UnsupportedOperationException, String.valueOf(failed.getCause()));
        assertTrue(assertThrows(ExecutionException.class, () -> old.info("arena").get(10, TimeUnit.SECONDS)).getCause()
            instanceof UnsupportedOperationException);
    }
}
