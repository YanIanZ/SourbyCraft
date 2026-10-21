package dev.iyanz.sourbycraft.api.event;

import static dev.iyanz.sourbycraft.api.event.EventTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnFlag;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.junit.jupiter.api.Test;

class PlayerEventsTest {
    private final Player player = player();
    private final World world = world();

    @Test
    void saveEventReportsWhetherThePlayerIsLeaving() {
        final var quit = new PlayerSaveEvent(player, true);
        final var auto = new PlayerSaveEvent(player, false);
        assertSame(player, quit.getPlayer());
        assertTrue(quit.isQuit());
        assertFalse(auto.isQuit());
        assertEquals("PlayerSaveEvent", quit.getEventName());
    }

    @Test
    void endCreditsFollowVanillaUnlessAResultOverridesIt() {
        for (final boolean vanilla : new boolean[]{true, false}) {
            final var event = new PlayerViewEndCreditsEvent(player, vanilla);
            assertEquals(vanilla, event.willShowCredits(), "DEFAULT result defers to vanilla");
            assertEquals(vanilla, event.willVanillaShowCredits());

            event.setResult(Result.ALLOW);
            assertTrue(event.willShowCredits(), "ALLOW forces the credits");
            assertEquals(vanilla, event.willVanillaShowCredits(), "the vanilla verdict itself never changes");

            event.setResult(Result.DENY);
            assertFalse(event.willShowCredits(), "DENY suppresses the credits");
            assertEquals(vanilla, event.willVanillaShowCredits());

            event.setResult(Result.DEFAULT);
            assertEquals(vanilla, event.willShowCredits(), "back to DEFAULT defers to vanilla again");
        }
    }

    @Test
    void respawnEventExposesTheDecisionFlagsAndLocation() {
        final Location spawn = location(world, 3);
        final var event = new PlayerRespawnAsyncEvent(player, spawn, true, false, true, RespawnReason.DEATH);
        assertSame(player, event.getPlayer());
        assertEquals(spawn, event.getRespawnLocation());
        assertNotSame(spawn, event.getRespawnLocation(), "the getter returns a copy");
        assertTrue(event.isBedSpawn());
        assertFalse(event.isAnchorSpawn());
        assertTrue(event.isMissingRespawnBlock());
        assertEquals(RespawnReason.DEATH, event.getRespawnReason());
    }

    @SuppressWarnings("deprecation")
    @Test
    void respawnFlagsDeriveFromReasonAndSpawnKind() {
        assertEquals(Set.of(RespawnFlag.END_PORTAL),
            new PlayerRespawnAsyncEvent(player, location(world, 0), false, false, false, RespawnReason.END_PORTAL).getRespawnFlags());
        assertEquals(Set.of(RespawnFlag.BED_SPAWN, RespawnFlag.ANCHOR_SPAWN),
            new PlayerRespawnAsyncEvent(player, location(world, 0), true, true, false, RespawnReason.PLUGIN).getRespawnFlags());
        assertEquals(Set.of(),
            new PlayerPostRespawnAsyncEvent(player, location(world, 0), false, false, false, RespawnReason.DEATH).getRespawnFlags());
    }

    @Test
    void setRespawnLocationStoresACopy() {
        final var event = new PlayerRespawnAsyncEvent(player, location(world, 3), false, false, false, RespawnReason.DEATH);
        final Location moved = location(world, 50);
        event.setRespawnLocation(moved);
        assertEquals(moved, event.getRespawnLocation());
        moved.setX(-1);
        assertEquals(50, event.getRespawnLocation().getX(), "mutating the caller's Location must not move the respawn");
    }

    @Test
    void setRespawnLocationRefusesNullAndWorldlessLocationsAndKeepsTheOldOne() {
        final Location spawn = location(world, 3);
        final var event = new PlayerRespawnAsyncEvent(player, spawn, false, false, false, RespawnReason.DEATH);
        final var nullLocation = assertThrows(IllegalArgumentException.class, () -> event.setRespawnLocation(null));
        assertTrue(nullLocation.getMessage().toLowerCase().contains("location"), nullLocation.getMessage());
        final var noWorld = assertThrows(IllegalArgumentException.class,
            () -> event.setRespawnLocation(new Location(null, 1, 2, 3)));
        assertTrue(noWorld.getMessage().toLowerCase().contains("world"), noWorld.getMessage());
        assertEquals(spawn, event.getRespawnLocation(), "a refused set leaves the respawn location unchanged");
    }

    @Test
    void postRespawnEventDoesNotAllowChangingTheLocation() {
        final var post = new PlayerPostRespawnAsyncEvent(player, location(world, 3), false, true, false, RespawnReason.PLUGIN);
        assertTrue(post.isAnchorSpawn());
        assertTrue(java.util.Arrays.stream(PlayerPostRespawnAsyncEvent.class.getMethods())
            .noneMatch(m -> m.getName().equals("setRespawnLocation")),
            "documented: modifying the respawn location from the post event is not allowed");
    }
}
