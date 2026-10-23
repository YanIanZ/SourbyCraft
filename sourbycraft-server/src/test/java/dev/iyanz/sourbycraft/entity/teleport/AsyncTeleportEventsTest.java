package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/** When the async teleport routes fire the legacy Bukkit teleport events (adapted from LightingLuminol). */
class AsyncTeleportEventsTest {

    @Test
    void noOpPortalListenerPreservesEffectiveRotationAndTransitionMetadata() {
        final PositionMoveRotation current = new PositionMoveRotation(new Vec3(10, 64, 10), new Vec3(0.5, 0, 1), 125, 35);
        final TeleportTransition found = new TeleportTransition(null, new Vec3(0, 80, 0), Vec3.ZERO, 0, 0,
            true, true, Relative.ROTATION, TeleportTransition.DO_NOTHING,
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL, TeleportTransition.PassengerTeleportationMode.POSITION_RIDER);
        final PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(current, PositionMoveRotation.of(found), found.relatives());
        final Location unchanged = new Location(null, absolute.position().x, absolute.position().y, absolute.position().z,
            absolute.yRot(), absolute.xRot());
        final TeleportTransition result = AsyncTeleportEvents.redirectedPortalTransition(found, absolute, null, unchanged);
        assertEquals(125, result.yRot());
        assertEquals(35, result.xRot());
        assertEquals(absolute.position(), result.position());
        assertEquals(absolute.deltaMovement(), result.deltaMovement());
        assertEquals(Set.of(), result.relatives(), "relative movement has already been resolved");
        assertTrue(result.missingRespawnBlock());
        assertTrue(result.asPassenger());
        assertSame(found.postTeleportTransition(), result.postTeleportTransition());
        assertSame(found.cause(), result.cause());
        assertSame(found.passengerTeleportationMode(), result.passengerTeleportationMode());
    }

    @Test
    void portalListenerCanChangePositionAndRotationWithoutChangingExitVelocity() {
        final TeleportTransition found = new TeleportTransition(null, Vec3.ZERO, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
        final PositionMoveRotation absolute = new PositionMoveRotation(Vec3.ZERO, new Vec3(0.25, 0, 0.5), 90, 10);
        final Location changed = new Location(null, 100, 90, -100, -45, 25);
        final TeleportTransition result = AsyncTeleportEvents.redirectedPortalTransition(found, absolute, null, changed);
        assertEquals(new Vec3(100, 90, -100), result.position());
        assertEquals(-45, result.yRot());
        assertEquals(25, result.xRot());
        assertEquals(absolute.deltaMovement(), result.deltaMovement());
    }

    @Test
    void firesOnlyWithListenersAndWhenAllowed() {
        assertTrue(AsyncTeleportEvents.fires(true, true, false, true));
        assertFalse(AsyncTeleportEvents.fires(true, true, false, false), "no listener: no event object");
        assertFalse(AsyncTeleportEvents.fires(true, false, false, true), "the caller asked for no events (a re-run after events)");
        assertFalse(AsyncTeleportEvents.fires(true, true, true, true), "Paper's synchronous body already fired it for this move");
        assertFalse(AsyncTeleportEvents.fires(false, true, false, true), "-Daurora.compat.async-teleport-events=false");
    }

    @Test
    void onlyAnExplicitFalseDisables() {
        assertTrue(AsyncTeleportEvents.enabledFrom(null));
        assertTrue(AsyncTeleportEvents.enabledFrom("true"));
        assertTrue(AsyncTeleportEvents.enabledFrom("yes"));
        assertFalse(AsyncTeleportEvents.enabledFrom("false"));
        assertFalse(AsyncTeleportEvents.enabledFrom(" FALSE "));
    }

    @Test
    void suppressionIsScopedToTheCall() {
        final boolean[] inside = new boolean[1];
        AsyncTeleportEvents.suppressPlayerTeleportEvent(() -> inside[0] = AsyncTeleportEvents.fires(true, true, suppressedNow(), true));
        assertFalse(inside[0]);
        assertFalse(suppressedNow());
    }

    private static boolean suppressedNow() {
        // firesPlayerTeleport reads the thread-local; with no Bukkit server there are no listeners, so the
        // decision is visible only through fires(): read the flag the same way the production method does.
        try {
            final java.lang.reflect.Field field = AsyncTeleportEvents.class.getDeclaredField("SUPPRESSED");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final ThreadLocal<Boolean> suppressed = (ThreadLocal<Boolean>) field.get(null);
            return suppressed.get();
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
