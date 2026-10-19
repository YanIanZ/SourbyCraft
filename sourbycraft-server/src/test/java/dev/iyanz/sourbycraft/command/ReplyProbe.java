package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * Stands in for {@link SourbyReply#run} in a command test and records, for every message a
 * sender received, whether it arrived through the hop. A command block's {@code sendMessage}
 * writes block-entity state and throws off its region thread, so every message a command sends
 * after {@code execute} returned must take the hop; the synchronous acknowledgement need not.
 */
final class ReplyProbe implements BiConsumer<CommandSender, Runnable> {

    /** One message as the sender saw it. */
    record Received(String text, boolean throughHop) {}

    private final AtomicBoolean inside = new AtomicBoolean();
    private final AtomicInteger hops = new AtomicInteger();
    private final LinkedBlockingQueue<Received> inbox = new LinkedBlockingQueue<>();

    @Override
    public void accept(final CommandSender sender, final Runnable reply) {
        this.hops.incrementAndGet();
        this.inside.set(true);
        try {
            reply.run();
        } finally {
            this.inside.set(false);
        }
    }

    /** How many replies took the hop. */
    int hops() {
        return this.hops.get();
    }

    /** A sender of the given type whose messages land in this probe's inbox. */
    <T extends CommandSender> T sender(final Class<T> type, final boolean permitted) {
        final T sender = mock(type);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        when(sender.getName()).thenReturn(type.getSimpleName());
        doAnswer(invocation -> {
            final Component message = invocation.getArgument(0);
            this.inbox.add(new Received(PlainTextComponentSerializer.plainText().serialize(message), this.inside.get()));
            return null;
        }).when(sender).sendMessage(any(Component.class));
        return sender;
    }

    /**
     * Opt-in: also record the legacy {@code sendMessage(String)} overload on {@code sender}, for a
     * command that replies with plain strings. Not done by {@link #sender} so existing tests that
     * count messages keep seeing exactly what they saw before.
     */
    <T extends CommandSender> T captureStrings(final T sender) {
        doAnswer(invocation -> {
            this.inbox.add(new Received((String) invocation.getArgument(0), this.inside.get()));
            return null;
        }).when(sender).sendMessage(anyString());
        return sender;
    }

    /** The next message, waiting up to 20 s for it. */
    Received next() throws InterruptedException {
        final Received next = this.inbox.poll(20, TimeUnit.SECONDS);
        if (next == null) fail("no message within 20 s");
        return next;
    }

    /** The next message containing {@code fragment}, discarding the ones before it; waits up to 20 s. */
    Received await(final String fragment) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            final Received next = this.inbox.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (next == null) fail("no message containing '" + fragment + "' within 20 s");
            if (next.text().contains(fragment)) return next;
        }
    }

    /** Every message received so far, without waiting. */
    List<Received> received() {
        final List<Received> all = new ArrayList<>();
        this.inbox.drainTo(all);
        return all;
    }
}
