package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The scope that lets an admitted API teleport pass the inherited NMS refusal. It must admit only
 * the entities the route decided for, only on the deciding thread and only while the call runs.
 */
class SyncTeleportScopeTest {

    @Test
    void onlyMembersAreAdmittedAndOnlyWhileTheBodyRuns() {
        final Object root = new Object();
        final Object passenger = new Object();
        final Object stranger = new Object();
        assertFalse(SyncTeleportScope.admits(root));
        final boolean result = SyncTeleportScope.run(List.of(root, passenger), () -> {
            assertTrue(SyncTeleportScope.admits(root));
            assertTrue(SyncTeleportScope.admits(passenger));
            assertFalse(SyncTeleportScope.admits(stranger));
            return true;
        });
        assertTrue(result);
        assertFalse(SyncTeleportScope.admits(root));
        assertFalse(SyncTeleportScope.admits(passenger));
    }

    @Test
    void membershipIsIdentityNotEquals() {
        final String a = new String("same");
        final String b = new String("same");
        SyncTeleportScope.run(List.of(a), () -> {
            assertTrue(SyncTeleportScope.admits(a));
            assertFalse(SyncTeleportScope.admits(b));
            return true;
        });
    }

    @Test
    void aNestedTeleportFromAnEventListenerRestoresTheOuterScope() {
        final Object outer = new Object();
        final Object inner = new Object();
        SyncTeleportScope.run(List.of(outer), () -> {
            SyncTeleportScope.run(List.of(inner), () -> {
                assertTrue(SyncTeleportScope.admits(inner));
                assertFalse(SyncTeleportScope.admits(outer), "the inner call admits only its own tree");
                return true;
            });
            assertTrue(SyncTeleportScope.admits(outer));
            assertFalse(SyncTeleportScope.admits(inner));
            return true;
        });
    }

    @Test
    void aThrowingBodyStillClosesTheScope() {
        final Object root = new Object();
        assertThrows(IllegalStateException.class, () -> SyncTeleportScope.run(List.of(root), () -> {
            throw new IllegalStateException("listener blew up");
        }));
        assertFalse(SyncTeleportScope.admits(root));
    }

    @Test
    void anotherThreadIsNeverAdmitted() throws InterruptedException {
        final Object root = new Object();
        final AtomicBoolean seen = new AtomicBoolean(true);
        SyncTeleportScope.run(List.of(root), () -> {
            final Thread other = new Thread(() -> seen.set(SyncTeleportScope.admits(root)));
            other.start();
            try {
                other.join();
            } catch (final InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return true;
        });
        assertFalse(seen.get());
    }

    @Test
    void aPassengerAddedByAListenerIsAdmittedThroughItsAdmittedRootVehicle() {
        final Object root = new Object();
        final Object addedLater = new Object();
        final Object elsewhere = new Object();
        final java.util.Map<Object, Object> rootOf = new java.util.IdentityHashMap<>();
        rootOf.put(root, root);
        rootOf.put(addedLater, root);
        rootOf.put(elsewhere, elsewhere);
        SyncTeleportScope.run(List.of(root), () -> {
            assertTrue(SyncTeleportScope.admits(addedLater, rootOf::get), "joined the tree after the snapshot");
            assertFalse(SyncTeleportScope.admits(elsewhere, rootOf::get));
            return true;
        });
        assertFalse(SyncTeleportScope.admits(addedLater, rootOf::get));
    }
}
