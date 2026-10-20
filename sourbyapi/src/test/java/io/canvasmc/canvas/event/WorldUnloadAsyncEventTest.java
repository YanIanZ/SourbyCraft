package io.canvasmc.canvas.event;

import static io.canvasmc.canvas.event.CanvasEventTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

class WorldUnloadAsyncEventTest {
    @Test
    void exposesTheWorldAndStartsUncancelled() {
        final World world = world();
        final var event = new WorldUnloadAsyncEvent(world);
        assertSame(world, event.getWorld());
        assertInstanceOf(Cancellable.class, event);
        assertFalse(event.isCancelled());
        assertEquals("WorldUnloadAsyncEvent", event.getEventName());
    }

    @Test
    void cancellationTogglesBothWays() {
        final var event = new WorldUnloadAsyncEvent(world());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
    }

    @Test
    void independentInstancesDoNotShareCancellation() {
        final var a = new WorldUnloadAsyncEvent(world());
        final var b = new WorldUnloadAsyncEvent(world());
        a.setCancelled(true);
        assertFalse(b.isCancelled());
    }
}
