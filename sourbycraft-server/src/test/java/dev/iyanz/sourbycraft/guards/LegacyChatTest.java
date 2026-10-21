package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LegacyChatTest {

    private static final long LONG = TimeUnit.SECONDS.toNanos(30L);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(20L);

    private final List<String> warnings = new ArrayList<>();
    private final List<String> firedOn = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private long now;

    @BeforeEach
    @AfterEach
    void resetWarnings() {
        LegacyChat.resetWarningsForTests();
    }

    private final Runnable fire = () -> this.firedOn.add(Thread.currentThread().getName());

    /** The player's region: runs the work on its own named thread, like the entity scheduler's next tick. */
    private final BoundedHandoff.Target region = (body, retired) -> {
        final Thread t = Thread.ofPlatform().name("Fake Region Tick").start(body);
        try {
            t.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    };

    private boolean call(final boolean async, final boolean owns, final BoundedHandoff.Target target, final long start) {
        return LegacyChat.onSenderRegion("alice", async, () -> owns, target, this.fire, start, LONG, () -> this.now, this.warnings::add);
    }

    @Test
    void theOwningThreadFiresInlineWithoutAHandoff() {
        assertTrue(this.call(false, true, (body, retired) -> {
            throw new AssertionError("no handoff when the caller owns the player");
        }, LONG));
        assertEquals(List.of(Thread.currentThread().getName()), this.firedOn);
    }

    @Test
    void theChatThreadHandsTheEventToThePlayersRegionAndWaitsForIt() {
        assertTrue(this.call(true, false, this.region, LONG));
        assertEquals(List.of("Fake Region Tick"), this.firedOn);
        assertTrue(this.warnings.isEmpty());
    }

    @Test
    void aTickThreadThatDoesNotOwnThePlayerNeverWaitsAndTheMessageIsDropped() {
        assertFalse(this.call(false, false, (body, retired) -> {
            throw new AssertionError("a tick thread must not wait on another region");
        }, LONG));
        assertTrue(this.firedOn.isEmpty());
        assertEquals(1, this.warnings.size());
    }

    @Test
    void aPlayerWhoLeftDropsTheMessage() {
        assertFalse(this.call(true, false, (body, retired) -> {
            retired.run();
            return true;
        }, LONG));
        assertTrue(this.firedOn.isEmpty());
        assertTrue(this.warnings.get(0).contains("RETIRED"), this.warnings.get(0));
    }

    @Test
    void aStalledRegionDropsTheMessageAndTheEventNeverFiresLate() {
        assertFalse(this.call(true, false, (body, retired) -> this.queued.add(body), SHORT));
        this.queued.get(0).run();
        assertTrue(this.firedOn.isEmpty(), "abandoned: the listener must not see a message that was already dropped");
        assertTrue(this.warnings.get(0).contains("NOT_STARTED"), this.warnings.get(0));
    }

    @Test
    void aListenerFailureSurfacesAsPapersChatException() {
        final IllegalStateException boom = new IllegalStateException("listener broke");
        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> LegacyChat.onSenderRegion("alice", true, () -> false,
            this.region, () -> {
                throw boom;
            }, LONG, LONG, () -> this.now, this.warnings::add));
        assertEquals("Exception processing chat", thrown.getMessage());
        assertSame(boom, thrown.getCause());
    }

    @Test
    void dropWarningsAreRateLimited() {
        final BoundedHandoff.Target gone = (body, retired) -> false;
        assertFalse(this.call(true, false, gone, LONG));
        assertFalse(this.call(true, false, gone, LONG));
        assertEquals(1, this.warnings.size(), "one warning per interval");
        this.now += TimeUnit.SECONDS.toNanos(10L);
        assertFalse(this.call(true, false, gone, LONG));
        assertEquals(2, this.warnings.size());
    }

    @Test
    void theBoundsAreTheDocumentedOnes() {
        assertEquals(TimeUnit.SECONDS.toNanos(5L), LegacyChat.START_TIMEOUT_NANOS);
        assertEquals(TimeUnit.SECONDS.toNanos(5L), LegacyChat.RUN_TIMEOUT_NANOS);
    }
}
