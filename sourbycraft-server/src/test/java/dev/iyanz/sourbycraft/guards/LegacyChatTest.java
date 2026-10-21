package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LegacyChatTest {

    private static final long LONG = TimeUnit.SECONDS.toNanos(30L);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(20L);

    private final List<String> warnings = new ArrayList<>();
    private final List<String> firedOn = new ArrayList<>();
    private final List<String> deliveredOn = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private long now;

    @BeforeEach
    @AfterEach
    void resetWarnings() {
        LegacyChat.resetWarningsForTests();
    }

    private final Runnable fire = () -> this.firedOn.add(Thread.currentThread().getName());
    private final Runnable deliver = () -> this.deliveredOn.add(Thread.currentThread().getName());

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

    /** A caller that is not a tick thread (the asynchronous chat thread). */
    private LegacyChat.Delivery call(final boolean owns, final BoundedHandoff.Target target, final long start) {
        return this.call(owns, false, target, start);
    }

    private LegacyChat.Delivery call(final boolean owns, final boolean tickThread, final BoundedHandoff.Target target, final long start) {
        return LegacyChat.onSenderRegion("alice", () -> owns, () -> tickThread, target, this.fire, this.deliver, start, LONG,
            () -> this.now, this.warnings::add);
    }

    @Test
    void theOwningThreadFiresInlineWithoutAHandoff() {
        assertEquals(LegacyChat.Delivery.INLINE, this.call(true, true, (body, retired) -> {
            throw new AssertionError("no handoff when the caller owns the player");
        }, LONG));
        assertEquals(List.of(Thread.currentThread().getName()), this.firedOn);
        assertEquals(List.of(Thread.currentThread().getName()), this.deliveredOn);
    }

    @Test
    void theChatThreadHandsTheEventToThePlayersRegionAndContinuesAfterIt() {
        assertEquals(LegacyChat.Delivery.HANDED_OFF, this.call(false, this.region, LONG));
        assertEquals(List.of("Fake Region Tick"), this.firedOn);
        assertEquals(List.of(Thread.currentThread().getName()), this.deliveredOn, "the message continues on the waiting chat thread");
        assertTrue(this.warnings.isEmpty());
    }

    /**
     * Review finding 1: {@code CraftPlayer#chat} passes {@code async=false} from every thread, so a
     * plugin's {@code Player#chat} from the global region scheduler or another region used to be
     * dropped whenever a plugin listened to {@code PlayerChatEvent}. A tick thread that does not
     * own the player must not wait, but the message is continued on the owner, not dropped.
     */
    @Test
    void aTickThreadThatDoesNotOwnThePlayerNeverWaitsAndTheMessageContinuesOnTheOwner() {
        assertEquals(LegacyChat.Delivery.CONTINUED_ON_OWNER, this.call(false, true, (body, retired) -> this.queued.add(body), LONG));
        assertTrue(this.firedOn.isEmpty(), "the caller returned at once, nothing ran on it");
        assertEquals(1, this.queued.size());
        final Thread owner = Thread.ofPlatform().name("Owner Region Tick").start(this.queued.get(0));
        assertDoesNotThrow(() -> owner.join());
        assertEquals(List.of("Owner Region Tick"), this.firedOn);
        assertEquals(List.of("Owner Region Tick"), this.deliveredOn, "event and message both run on the owner, in order");
        assertTrue(this.warnings.isEmpty(), "not dropped: " + this.warnings);
    }

    /**
     * Re-review finding 7: a failure in the continuation (after the caller returned) was swallowed
     * silently for anything but a RuntimeException. It is logged once with the listening plugins,
     * counted, the message is not sent, and only a VirtualMachineError escapes.
     */
    @Test
    void aContinuationFailureIsLoggedOnceWithThePluginsAndCounted() {
        final List<String> errors = new ArrayList<>();
        final List<Throwable> causes = new ArrayList<>();
        final AssertionError boom = new AssertionError("not a RuntimeException");
        assertEquals(LegacyChat.Delivery.CONTINUED_ON_OWNER, LegacyChat.onSenderRegion("alice", () -> false, () -> true,
            (body, retired) -> this.queued.add(body), () -> {
                throw boom;
            }, this.deliver, LONG, LONG, () -> this.now, this.warnings::add, () -> "ChatFilter", (message, cause) -> {
                errors.add(message);
                causes.add(cause);
            }));
        assertDoesNotThrow(() -> this.queued.get(0).run(), "nobody to throw to on the owner");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("alice") && errors.get(0).contains("ChatFilter") && errors.get(0).contains("not sent"), errors.get(0));
        assertSame(boom, causes.get(0));
        assertEquals(1L, LegacyChat.continuedFailures());
        assertTrue(this.deliveredOn.isEmpty(), "the message is not sent");

        this.queued.clear();
        final StackOverflowError fatal = new StackOverflowError();
        LegacyChat.onSenderRegion("alice", () -> false, () -> true, (body, retired) -> this.queued.add(body), () -> {
            throw fatal;
        }, this.deliver, LONG, LONG, () -> this.now, this.warnings::add, () -> "ChatFilter", (message, cause) -> errors.add(message));
        assertSame(fatal, assertThrows(StackOverflowError.class, () -> this.queued.get(0).run()), "a VirtualMachineError is never swallowed");
        assertEquals(1, errors.size());
    }

    @Test
    void theListeningPluginsAreNamedOnceEach() {
        final org.bukkit.event.HandlerList chat = org.mockito.Mockito.mock(org.bukkit.event.HandlerList.class);
        final org.bukkit.event.HandlerList paper = org.mockito.Mockito.mock(org.bukkit.event.HandlerList.class);
        final org.bukkit.plugin.Plugin filter = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(filter.getName()).thenReturn("ChatFilter");
        final org.bukkit.plugin.RegisteredListener listener = org.mockito.Mockito.mock(org.bukkit.plugin.RegisteredListener.class);
        org.mockito.Mockito.when(listener.getPlugin()).thenReturn(filter);
        org.mockito.Mockito.when(chat.getRegisteredListeners()).thenReturn(new org.bukkit.plugin.RegisteredListener[] {listener});
        org.mockito.Mockito.when(paper.getRegisteredListeners()).thenReturn(new org.bukkit.plugin.RegisteredListener[] {listener});
        assertEquals("ChatFilter", LegacyChat.listeningPlugins(chat, paper));
        org.mockito.Mockito.when(chat.getRegisteredListeners()).thenReturn(new org.bukkit.plugin.RegisteredListener[0]);
        org.mockito.Mockito.when(paper.getRegisteredListeners()).thenReturn(new org.bukkit.plugin.RegisteredListener[0]);
        assertEquals("none", LegacyChat.listeningPlugins(chat, paper));
    }

    @Test
    void aContinuationForAPlayerWhoLeftIsDroppedWithAWarning() {
        assertEquals(LegacyChat.Delivery.CONTINUED_ON_OWNER, this.call(false, true, (body, retired) -> {
            retired.run();
            return true;
        }, LONG));
        assertTrue(this.firedOn.isEmpty());
        assertTrue(this.deliveredOn.isEmpty());
        assertTrue(this.warnings.get(0).contains("left"), this.warnings.get(0));
    }

    @Test
    void aContinuationTheRegionRefusesIsDropped() {
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, true, (body, retired) -> false, LONG));
        assertTrue(this.warnings.get(0).contains("REFUSED"), this.warnings.get(0));
    }

    @Test
    void aPlayerWhoLeftDropsTheMessage() {
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, (body, retired) -> {
            retired.run();
            return true;
        }, LONG));
        assertTrue(this.firedOn.isEmpty());
        assertTrue(this.deliveredOn.isEmpty());
        assertTrue(this.warnings.get(0).contains("RETIRED"), this.warnings.get(0));
    }

    @Test
    void aStalledRegionDropsTheMessageAndTheEventNeverFiresLate() {
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, (body, retired) -> this.queued.add(body), SHORT));
        this.queued.get(0).run();
        assertTrue(this.firedOn.isEmpty(), "abandoned: the listener must not see a message that was already dropped");
        assertTrue(this.deliveredOn.isEmpty());
        assertTrue(this.warnings.get(0).contains("NOT_STARTED"), this.warnings.get(0));
    }

    /**
     * Review finding 4: when the listeners overran the wait, the message was logged as dropped but
     * the region later sent it anyway, late and out of order. Sending is now the {@code deliver}
     * step, which only the waiting caller runs after a completed wait: an overrun is dropped for good.
     */
    @Test
    void anOverrunIsDroppedAndTheMessageIsNeverSentWhenTheListenersFinishLater() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final Thread[] regionThread = new Thread[1];
        final BoundedHandoff.Target slowRegion = (body, retired) -> {
            regionThread[0] = Thread.ofPlatform().name("Slow Region Tick").start(body);
            return true;
        };
        final Runnable slowFire = () -> {
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.firedOn.add(Thread.currentThread().getName());
            finished.countDown();
        };
        assertEquals(LegacyChat.Delivery.DROPPED, LegacyChat.onSenderRegion("alice", () -> false, () -> false, slowRegion, slowFire,
            this.deliver, LONG, SHORT, () -> this.now, this.warnings::add));
        assertTrue(this.warnings.get(0).contains("OVERRAN") && this.warnings.get(0).contains("will not be sent"), this.warnings.get(0));
        release.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS));
        regionThread[0].join();
        assertEquals(List.of("Slow Region Tick"), this.firedOn, "the listeners did finish on the region");
        assertTrue(this.deliveredOn.isEmpty(), "but the message was never sent");
    }

    @Test
    void aListenerFailureSurfacesAsPapersChatException() {
        final IllegalStateException boom = new IllegalStateException("listener broke");
        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> LegacyChat.onSenderRegion("alice", () -> false, () -> false,
            this.region, () -> {
                throw boom;
            }, this.deliver, LONG, LONG, () -> this.now, this.warnings::add));
        assertEquals("Exception processing chat", thrown.getMessage());
        assertSame(boom, thrown.getCause());
        assertTrue(this.deliveredOn.isEmpty());
    }

    @Test
    void dropWarningsAreRateLimited() {
        final BoundedHandoff.Target gone = (body, retired) -> false;
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, gone, LONG));
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, gone, LONG));
        assertEquals(1, this.warnings.size(), "one warning per interval");
        this.now += TimeUnit.SECONDS.toNanos(10L);
        assertEquals(LegacyChat.Delivery.DROPPED, this.call(false, gone, LONG));
        assertEquals(2, this.warnings.size());
    }

    @Test
    void theBoundsAreTheDocumentedOnes() {
        assertEquals(TimeUnit.SECONDS.toNanos(5L), LegacyChat.START_TIMEOUT_NANOS);
        assertEquals(TimeUnit.SECONDS.toNanos(5L), LegacyChat.RUN_TIMEOUT_NANOS);
    }
}
