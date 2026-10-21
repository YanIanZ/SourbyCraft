package dev.iyanz.sourbycraft.api.event;

import static dev.iyanz.sourbycraft.api.event.EventTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.api.event.EntityTeleportAsyncEvent.TeleportType;
import java.util.Arrays;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

class EntityTeleportAsyncEventTest {
    private final World world = world();
    private final Entity entity = entity();

    @Test
    void constructionExposesWhatWasPassedIn() {
        final Location from = location(world, 1);
        final Location to = location(world, 2);
        final var event = new EntityTeleportAsyncEvent(entity, from, to, TeleportCause.COMMAND, TeleportType.CROSS_REGION);
        assertSame(entity, event.getEntity());
        assertSame(from, event.getFrom());
        assertSame(to, event.getTo());
        assertEquals(TeleportCause.COMMAND, event.getCause());
        assertEquals(TeleportType.CROSS_REGION, event.getType());
        assertEquals("EntityTeleportAsyncEvent", event.getEventName());
    }

    @Test
    void aNullDestinationFallsBackToTheSourceLocation() {
        final Location from = location(world, 1);
        final var event = new EntityTeleportAsyncEvent(entity, from, null, TeleportCause.PLUGIN, TeleportType.SAME_REGION);
        assertSame(from, event.getTo(), "null 'to' means 'stay where you are', not a null destination");
    }

    @Test
    void setToStoresADefensiveCopy() {
        final var event = new EntityTeleportAsyncEvent(entity, location(world, 1), location(world, 2), TeleportCause.PLUGIN, TeleportType.SAME_REGION);
        final Location mutable = location(world, 10);
        event.setTo(mutable);
        assertNotSame(mutable, event.getTo());
        assertEquals(mutable, event.getTo());
        mutable.setX(99);
        assertEquals(10, event.getTo().getX(), "later mutation of the caller's Location must not move the teleport");
    }

    @Test
    void setToRejectsNull() {
        final var event = new EntityTeleportAsyncEvent(entity, location(world, 1), location(world, 2), TeleportCause.PLUGIN, TeleportType.SAME_REGION);
        assertThrows(NullPointerException.class, () -> event.setTo(null));
        assertEquals(2, event.getTo().getX(), "a refused setTo leaves the destination unchanged");
    }

    @Test
    void cancellableStartsUncancelledAndTogglesBothWays() {
        final var event = new EntityTeleportAsyncEvent(entity, location(world, 1), null, TeleportCause.PLUGIN, TeleportType.CROSS_WORLD);
        assertInstanceOf(Cancellable.class, event);
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
    }

    @Test
    void teleportTypeNamesAndOrderArePinned() {
        assertEquals(java.util.List.of("SAME_REGION", "CROSS_REGION", "CROSS_WORLD"),
            Arrays.stream(TeleportType.values()).map(Enum::name).toList());
    }

    @Test
    void postEventHasTheSameNullFallbackAndIsNotCancellable() {
        final Location from = location(world, 1);
        final var post = new EntityPostTeleportAsyncEvent(entity, from, null, TeleportCause.ENDER_PEARL, TeleportType.CROSS_REGION);
        assertSame(from, post.getFrom());
        assertSame(from, post.getTo());
        assertEquals(TeleportCause.ENDER_PEARL, post.getCause());
        assertEquals(TeleportType.CROSS_REGION, post.getType());
        assertSame(entity, post.getEntity());
        assertFalse(post instanceof Cancellable, "a finished teleport cannot be cancelled");
        final Location to = location(world, 5);
        assertSame(to, new EntityPostTeleportAsyncEvent(entity, from, to, TeleportCause.PLUGIN, TeleportType.SAME_REGION).getTo(),
            "the post event keeps the destination as is (no copy)");
    }
}
