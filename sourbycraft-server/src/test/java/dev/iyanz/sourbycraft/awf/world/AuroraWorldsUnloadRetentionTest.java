package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent;
import java.nio.file.Path;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The "unload without saving" marker is per world name and must not outlive the unload it was set for.
 * Its release paths: the engine firing {@link WorldUnloadAsyncEvent} (this handler removes it), and the
 * unload outcome callback (removes it when the unload is refused or finishes; that lambda needs a running
 * server and is covered by the code path review, not here). A marker left behind would make a later,
 * unrelated unload of a world with the same name silently discard its writes.
 */
class AuroraWorldsUnloadRetentionTest {

    @TempDir Path dir;

    private AuroraWorldsService service() throws Exception {
        return new AuroraWorldsService(new AuroraWorldRegistry(this.dir.resolve("aurora-worlds.json")),
            new AuroraTemplates(this.dir.resolve("templates")), name -> this.dir.resolve(name));
    }

    private static WorldUnloadAsyncEvent unloadOf(final String name) {
        final World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return new WorldUnloadAsyncEvent(world);
    }

    @Test
    void theUnloadEventConsumesTheMarkerOfThatWorldOnly() throws Exception {
        final AuroraWorldsService service = service();
        service.markUnloadWithoutSaving("arena");
        service.markUnloadWithoutSaving("lobby");

        service.discardOnUnload(unloadOf("arena"));

        assertFalse(service.unloadWithoutSavingPending("arena"), "consumed by the unload it was set for");
        assertTrue(service.unloadWithoutSavingPending("lobby"), "another world's marker is untouched");
    }

    @Test
    void anUnloadThatWasNeverMarkedLeavesNothingBehind() throws Exception {
        final AuroraWorldsService service = service();
        service.discardOnUnload(unloadOf("plain"));
        assertFalse(service.unloadWithoutSavingPending("plain"));
    }

    @Test
    void theSecondUnloadOfTheSameNameIsNotAffectedByTheFirst() throws Exception {
        final AuroraWorldsService service = service();
        service.markUnloadWithoutSaving("arena");
        service.discardOnUnload(unloadOf("arena"));

        service.discardOnUnload(unloadOf("arena")); // a later unload that asked to save

        assertFalse(service.unloadWithoutSavingPending("arena"));
    }
}
