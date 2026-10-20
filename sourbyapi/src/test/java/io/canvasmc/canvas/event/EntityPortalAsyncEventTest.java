package io.canvasmc.canvas.event;

import static io.canvasmc.canvas.event.CanvasEventTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

class EntityPortalAsyncEventTest {
    private final Entity entity = entity();
    private final World overworld = world();
    private final World nether = world();

    @Test
    void constructionExposesWhatWasPassedIn() {
        final var event = new EntityPortalAsyncEvent(entity, overworld, nether, PortalType.NETHER);
        assertSame(entity, event.getEntity());
        assertSame(overworld, event.getFrom());
        assertSame(nether, event.getTo());
        assertEquals(PortalType.NETHER, event.getPortalType());
        assertEquals("EntityPortalAsyncEvent", event.getEventName());
    }

    @Test
    void destinationIsReplaceableButSourceAndTypeAreNot() {
        final var event = new EntityPortalAsyncEvent(entity, overworld, nether, PortalType.ENDER);
        final World end = world();
        event.setTo(end);
        assertSame(end, event.getTo());
        assertSame(overworld, event.getFrom());
        assertEquals(PortalType.ENDER, event.getPortalType());
    }

    @Test
    void cancellableStartsUncancelledAndTogglesBothWays() {
        final var event = new EntityPortalAsyncEvent(entity, overworld, nether, PortalType.NETHER);
        assertInstanceOf(Cancellable.class, event);
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
    }

    @Test
    void postEventIsReadOnlyAndNotCancellable() {
        final var post = new EntityPostPortalAsyncEvent(entity, overworld, nether, PortalType.CUSTOM);
        assertSame(entity, post.getEntity());
        assertSame(overworld, post.getFrom());
        assertSame(nether, post.getTo());
        assertEquals(PortalType.CUSTOM, post.getPortalType());
        assertFalse(post instanceof Cancellable);
        assertTrue(java.util.Arrays.stream(EntityPostPortalAsyncEvent.class.getMethods())
            .noneMatch(m -> m.getName().startsWith("set") && m.getDeclaringClass() == EntityPostPortalAsyncEvent.class),
            "the post event declares no setters");
    }
}
