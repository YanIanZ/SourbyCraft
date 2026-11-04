package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Build 55: the cooldown counts committed switches only and is judged on an injected clock; guards are evaluated in
 * registration order off the caller's thread, the first refusal wins, an exception is a refusal, a plugin's guards go
 * with the plugin, and the registration count is bounded. Review P2-5: the guards run on their own named lane, a guard
 * that hangs is interrupted when the budget runs out, and a full lane refuses instead of queueing elsewhere.
 */
class ProfileSwitchGuardsTest {

    @Test
    void cooldownStartsOnlyWithACommittedSwitch() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(90, clock::get);
        final UUID player = UUID.randomUUID();
        assertEquals(90_000L, guards.cooldownMillis());
        assertEquals(0L, guards.cooldownRemainingMillis(player), "never switched: no cooldown");
        guards.committed(player);
        assertEquals(90_000L, guards.cooldownRemainingMillis(player));
        clock.addAndGet(30_000L);
        assertEquals(60_000L, guards.cooldownRemainingMillis(player));
        clock.addAndGet(60_000L);
        assertEquals(0L, guards.cooldownRemainingMillis(player));
        guards.committed(player);
        assertTrue(guards.clearCooldown(player));
        assertEquals(0L, guards.cooldownRemainingMillis(player), "an operator reset forgets it");
        assertFalse(guards.clearCooldown(player));
        final ProfileSwitchGuards off = new ProfileSwitchGuards(0, clock::get);
        off.committed(player);
        assertEquals(0L, off.cooldownRemainingMillis(player), "cooldown-seconds 0 is off: the Build 54 behaviour");
        assertThrows(IllegalArgumentException.class, () -> new ProfileSwitchGuards(-1, clock::get));
    }

    @Test
    void guardsAreEvaluatedInOrderAndTheFirstRefusalWins() throws Exception {
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(0);
        final UUID player = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        assertTrue(guards.evaluate(player, null, target).get(5, TimeUnit.SECONDS).allowed(), "no guard: allowed without a thread");
        final StringBuilder order = new StringBuilder();
        final ProfileSwitchGuard allow = (p, from, to) -> {
            order.append("a");
            return null;
        };
        final ProfileSwitchGuard combat = (p, from, to) -> {
            order.append("b");
            return "You are in combat";
        };
        final ProfileSwitchGuard never = (p, from, to) -> {
            order.append("c");
            return "never reached";
        };
        guards.register("Allow", allow);
        guards.register("Combat", combat);
        guards.register("Never", never);
        guards.register("Combat", combat);
        assertEquals(3, guards.guardCount(), "registering the same guard twice is one registration");
        final ProfileSwitchGuards.Verdict verdict = guards.evaluate(player, null, target).get(5, TimeUnit.SECONDS);
        assertFalse(verdict.allowed());
        assertEquals("You are in combat", verdict.reason());
        assertEquals("Combat", verdict.plugin());
        assertFalse(verdict.timedOut());
        assertEquals("ab", order.toString(), "the guard after the refusal never ran");
        assertTrue(guards.unregister("Combat", combat));
        assertFalse(guards.unregister("Combat", combat));
        assertFalse(guards.unregister("Allow", never), "a guard is removed by the plugin that registered it");
        assertEquals(1, guards.unregisterAll("Never"));
        assertTrue(guards.evaluate(player, null, target).get(5, TimeUnit.SECONDS).allowed());
        assertEquals(java.util.List.of("Allow"), guards.guardOwners());
    }

    @Test
    void aThrowingGuardRefusesAndALongReasonIsCut() throws Exception {
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(0);
        guards.register("Broken", (p, from, to) -> {
            throw new IllegalStateException("boom");
        });
        final ProfileSwitchGuards.Verdict broken = guards.evaluate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
            .get(5, TimeUnit.SECONDS);
        assertFalse(broken.allowed());
        assertEquals("Broken", broken.plugin());
        assertTrue(broken.reason().contains("Broken"), broken.reason());
        guards.unregisterAll("Broken");
        guards.register("Verbose", (p, from, to) -> "x".repeat(500));
        final ProfileSwitchGuards.Verdict verbose = guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()).get(5, TimeUnit.SECONDS);
        assertEquals(ProfileSwitchGuards.MAX_REASON, verbose.reason().length());
        guards.unregisterAll("Verbose");
        guards.register("Blank", (p, from, to) -> "   ");
        assertEquals("refused by Blank", guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()).get(5, TimeUnit.SECONDS).reason());
    }

    @Test
    void registrationIsBounded() {
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(0);
        for (int i = 0; i < AuroraProfiles.MAX_SWITCH_GUARDS; i++) {
            // Each lambda captures its index: a non-capturing lambda is one shared instance, which the identity
            // de-duplication would count once (review T3).
            final int index = i;
            guards.register("Plugin" + i, (p, from, to) -> index < 0 ? "never" : null);
        }
        assertThrows(IllegalStateException.class, () -> guards.register("OneTooMany", (p, from, to) -> "one too many"));
        assertEquals(AuroraProfiles.MAX_SWITCH_GUARDS, guards.guardCount());
        assertNull(ProfileSwitchGuards.Verdict.ALLOWED.reason());
        guards.close();
    }

    /**
     * Review P2-5: guards run on the class's own lane (named threads, never the shared virtual executor), and a guard
     * that does not answer within the budget is interrupted so it frees its lane thread.
     */
    @Test
    void aHungGuardIsInterruptedWhenTheBudgetRunsOut() throws Exception {
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(0, System::currentTimeMillis, 100L);
        try {
            final AtomicReference<String> thread = new AtomicReference<>();
            final CountDownLatch never = new CountDownLatch(1);
            final CountDownLatch interrupted = new CountDownLatch(1);
            guards.register("Hung", (p, from, to) -> {
                thread.set(Thread.currentThread().getName());
                try {
                    never.await();
                } catch (final InterruptedException freed) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    return null;
                }
                return "unreachable";
            });
            final ProfileSwitchGuards.Verdict verdict = guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()).get(5, TimeUnit.SECONDS);
            assertFalse(verdict.allowed());
            assertTrue(verdict.timedOut(), verdict.toString());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the hung guard was not interrupted at the budget");
            assertTrue(thread.get().startsWith("SourbyCraft-MP-switch-guard-"), thread.get());
            // The lane thread is free again: a fast guard answers at once.
            guards.unregisterAll("Hung");
            guards.register("Fast", (p, from, to) -> null);
            assertTrue(guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()).get(5, TimeUnit.SECONDS).allowed());
        } finally {
            guards.close();
        }
    }

    /**
     * Review P2-5: the lane is bounded ({@code GUARD_THREADS} running, {@code GUARD_QUEUE} waiting); an evaluation beyond
     * that is refused at once, never handed to another executor, and the waiting ones complete when the guards return.
     */
    @Test
    void aFullLaneRefusesInsteadOfQueueingElsewhere() throws Exception {
        final ProfileSwitchGuards guards = new ProfileSwitchGuards(0, System::currentTimeMillis, 30_000L);
        try {
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch running = new CountDownLatch(ProfileSwitchGuards.GUARD_THREADS);
            guards.register("Slow", (p, from, to) -> {
                running.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            final List<CompletableFuture<ProfileSwitchGuards.Verdict>> pending = new ArrayList<>();
            for (int i = 0; i < ProfileSwitchGuards.GUARD_THREADS; i++) {
                pending.add(guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()));
            }
            assertTrue(running.await(5, TimeUnit.SECONDS), "the lane threads did not start");
            for (int i = 0; i < ProfileSwitchGuards.GUARD_QUEUE; i++) {
                pending.add(guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()));
            }
            for (final CompletableFuture<ProfileSwitchGuards.Verdict> evaluation : pending) {
                assertFalse(evaluation.isDone(), "an evaluation completed while its guard still blocks");
            }
            final ProfileSwitchGuards.Verdict refused = guards.evaluate(UUID.randomUUID(), null, UUID.randomUUID()).get(1, TimeUnit.SECONDS);
            assertFalse(refused.allowed(), "the lane took more than its threads and queue");
            assertFalse(refused.timedOut());
            assertTrue(refused.reason().contains("busy"), refused.reason());
            release.countDown();
            for (final CompletableFuture<ProfileSwitchGuards.Verdict> evaluation : pending) {
                assertTrue(evaluation.get(10, TimeUnit.SECONDS).allowed(), "a queued evaluation did not run after the guards returned");
            }
        } finally {
            guards.close();
        }
    }
}
