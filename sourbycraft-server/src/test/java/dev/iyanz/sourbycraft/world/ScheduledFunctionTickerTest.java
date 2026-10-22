package dev.iyanz.sourbycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import net.minecraft.world.level.timers.TimerCallback;
import net.minecraft.world.level.timers.TimerQueue;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

@AllFeatures
class ScheduledFunctionTickerTest {
    private final TimerQueue<Object> queue = new TimerQueue<>();
    private final Object context = new Object();
    private final List<Exception> failures = new ArrayList<>();
    private final List<Long> fired = new ArrayList<>();

    private void tick(final long time) {
        ScheduledFunctionTicker.tick(queue, context, time, failures::add);
    }

    private TimerCallback<Object> callback(final LongConsumer action) {
        return new TimerCallback<>() {
            @Override
            public void handle(final Object actualContext, final TimerQueue<Object> actualQueue, final long time) {
                assertSame(context, actualContext);
                assertSame(queue, actualQueue);
                action.accept(time);
            }

            @Override
            public MapCodec<? extends TimerCallback<Object>> codec() {
                throw new UnsupportedOperationException("not serialized by this test");
            }
        };
    }

    @Test
    void dueEventFiresOnceAtItsGameTime() {
        queue.schedule("due", 20, callback(fired::add));
        tick(20);
        tick(21);
        assertEquals(List.of(20L), fired);
        assertTrue(queue.getEventsIds().isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test
    void futureEventWaits() {
        queue.schedule("future", 21, callback(fired::add));
        tick(20);
        assertTrue(fired.isEmpty());
        assertTrue(queue.getEventsIds().contains("future"));
        tick(21);
        assertEquals(List.of(21L), fired);
    }

    @Test
    void eachFailedEventIsLoggedAndLaterEventsStillRun() {
        queue.schedule("bad-first", 20, callback(time -> { throw new IllegalStateException("first"); }));
        queue.schedule("good", 20, callback(fired::add));
        queue.schedule("bad-second", 20, callback(time -> { throw new IllegalArgumentException("second"); }));
        queue.schedule("good-last", 20, callback(fired::add));
        tick(20);
        assertEquals(List.of(20L, 20L), fired);
        assertEquals(List.of("first", "second"), failures.stream().map(Exception::getMessage).toList());
        assertTrue(queue.getEventsIds().isEmpty());
        tick(21);
        assertEquals(2, failures.size(), "failed events are consumed, not retried");
    }

    @Test
    void repeatedTickAtTheSameGameTimeDoesNotExecuteAnEventTwice() {
        queue.schedule("once", 20, callback(time -> {
            fired.add(time);
            queue.schedule("next", time + 1, callback(fired::add));
        }));
        tick(20);
        tick(20);
        assertEquals(List.of(20L), fired);
        tick(21);
        assertEquals(List.of(20L, 21L), fired);
    }

    @Test
    void overdueEventsFireWhenGlobalTimeAdvancesPastTheirDeadline() {
        queue.schedule("overdue", 18, callback(fired::add));
        tick(20);
        tick(21);
        assertEquals(List.of(20L), fired);
    }
}
