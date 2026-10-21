package dev.iyanz.sourbycraft.entity.teleport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PearlTeleportFlowTest {
    private record Target(String world, double x, double y, double z, float yaw, float pitch) {}
    private static final Target IMPACT = new Target("overworld", 10, 64, 20, 30, 40);

    private static final class Attempt implements PearlTeleportFlow.Attempt<Target, Target> {
        final List<String> actions = new ArrayList<>();
        Target destination = IMPACT, transferred, completedAt;
        boolean cancelled, gone, disconnected, transferring, owned = true, accepted = true;
        Consumer<Target> completion;
        int consumed, damage, endermites, resets, sounds;
        @Override public Target eventDestination() { actions.add("event"); return cancelled ? null : destination; }
        @Override public boolean valid() {
            actions.add("revalidate");
            return owned && !gone && !disconnected && !transferring;
        }
        @Override public void transfer(Target target, Consumer<Target> done) {
            actions.add("transfer"); transferred = target;
            if (accepted) completion = done;
        }
        @Override public void finish(Target finalPosition) {
            completedAt = finalPosition;
            actions.add("finish:" + finalPosition.world());
            PearlTeleportFlow.finish(0.0F, true, false, () -> endermites++,
                () -> { resets++; damage++; }, () -> sounds++);
        }
        void hit() { PearlTeleportFlow.hit(() -> PearlTeleportFlow.attempt(this), () -> consumed++); }
        void noEffects() { assertEquals(0, damage + endermites + resets + sounds); }
    }

    @Test void cancellationConsumesPearlWithoutTransferOrEffects() {
        final Attempt a = new Attempt(); a.cancelled = true; a.hit();
        assertEquals(1, a.consumed); assertNull(a.transferred); a.noEffects();
        assertEquals(List.of("event"), a.actions);
    }
    @Test void sameWorldRedirectPassesEveryCoordinateAndRotationToTransfer() {
        final Attempt a = new Attempt();
        a.destination = new Target("overworld", 123, 80, -456, 91, -32); a.hit();
        assertEquals(new Target("overworld", 123, 80, -456, 91, -32), a.transferred);
        assertEquals(List.of("event", "revalidate", "transfer"), a.actions);
        assertEquals(1, a.consumed); a.noEffects();
    }
    @Test void otherWorldRedirectPassesWorldAndRotationToTransfer() {
        final Attempt a = new Attempt();
        a.destination = new Target("nether", -50, 100, 70, -179, 67); a.hit();
        assertEquals(new Target("nether", -50, 100, 70, -179, 67), a.transferred);
        assertEquals(1, a.consumed); a.noEffects();
    }
    @Test void removedDisconnectedTransferredOrUnownedAfterListenersAborts() {
        for (int state = 0; state < 4; state++) {
            final Attempt a = new Attempt();
            a.gone = state == 0; a.disconnected = state == 1;
            a.transferring = state == 2; a.owned = state != 3; a.hit();
            assertEquals(1, a.consumed); assertNull(a.transferred); a.noEffects();
            assertEquals(List.of("event", "revalidate"), a.actions);
        }
    }
    @Test void forcedEndermiteAndDamageWaitForSuccessfulTransferAtFinalWorld() {
        final Attempt a = new Attempt(); a.hit(); a.noEffects();
        // The engine may redirect again through EntityTeleportAsyncEvent: use its completion entity.
        a.completion.accept(new Target("end", 999, 90, -999, 170, -50));
        assertEquals(1, a.damage); assertEquals(1, a.endermites);
        assertEquals(1, a.resets); assertEquals(1, a.sounds);
        assertEquals(new Target("end", 999, 90, -999, 170, -50), a.completedAt);
        assertEquals("finish:end", a.actions.getLast());
    }
    @Test void rejectedTransferConsumesPearlWithoutEffects() {
        final Attempt a = new Attempt(); a.accepted = false; a.hit();
        assertEquals(1, a.consumed); assertNull(a.completion); a.noEffects();
    }
    @Test void retiredSchedulerAndSchedulingFailureStillConsumePearl() {
        final Attempt a = new Attempt();
        PearlTeleportFlow.hit(() -> {}, () -> a.consumed++);
        assertEquals(1, a.consumed); a.noEffects();
        assertThrows(IllegalStateException.class, () -> PearlTeleportFlow.hit(
            () -> { throw new IllegalStateException("schedule failed"); }, () -> a.consumed++));
        assertEquals(2, a.consumed);
    }
    @Test void endermiteChanceAndWorldRulesGateOnlySpawn() {
        for (int state = 0; state < 3; state++) {
            final Attempt a = new Attempt();
            PearlTeleportFlow.finish(state == 0 ? 0.05F : 0.0F, state != 1, state == 2,
                () -> a.endermites++, () -> a.damage++, () -> a.sounds++);
            assertEquals(0, a.endermites); assertEquals(1, a.damage); assertEquals(1, a.sounds);
        }
    }
}
