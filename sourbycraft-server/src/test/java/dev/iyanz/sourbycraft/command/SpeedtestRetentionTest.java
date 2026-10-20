package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What {@code /speedtest} remembers about whoever ran it. The command is shared by every sender, so its
 * only mutable state is the one-at-a-time flag; the sender itself must not outlive the run on any exit
 * path (success, timeout, a launcher that fails, an I/O executor that is stopped), or a player who ran it
 * and disconnected would be pinned, and the flag must be clear so the next sender is not locked out.
 *
 * <p>Complements {@link SpeedtestCommandTest}, which proves the hung-process recovery; this proves what is
 * still held afterwards. The "player disconnects mid-run" hop (reply to an offline player) is
 * {@code SourbyReply}'s and is covered by {@code SourbyReplyTest}.</p>
 */
class SpeedtestRetentionTest {

    private static final String JSON = "{\"download\":{\"bandwidth\":12500000},\"upload\":{\"bandwidth\":2500000},"
        + "\"ping\":{\"latency\":12.3},\"server\":{\"name\":\"Test\",\"location\":\"Nowhere\"}}";

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeAll
    static void needsAShell() {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows"),
            "the fake speedtest is a sh script");
    }

    @AfterEach
    void stop() {
        this.pool.shutdownNow();
    }


    /**
     * Messages sent to senders of this test, in order. A plain {@link Proxy} is the sender, not a Mockito
     * mock: Mockito's inline mock maker keeps every mock reachable through its own invocation records, which
     * would make "the command released the sender" unprovable.
     */
    private final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();

    /** Runs a reply inline; holds no sender. */
    private final BiConsumer<CommandSender, Runnable> hop = (sender, reply) -> reply.run();

    private CommandSender sender() {
        final BlockingQueue<String> messages = this.inbox;
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ConsoleCommandSender.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendMessage") && args != null && args.length == 1 && args[0] instanceof Component c) {
                    messages.add(PlainTextComponentSerializer.plainText().serialize(c));
                    return null;
                }
                if (method.getName().equals("hasPermission") || method.getName().equals("isPermissionSet")) return true;
                if (method.getName().equals("getName")) return "CONSOLE";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("toString")) return "retention sender";
                final Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }

    private String next() throws InterruptedException {
        final String message = this.inbox.poll(20, TimeUnit.SECONDS);
        if (message == null) fail("no message within 20 s");
        return message;
    }

    private String await(final String fragment) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            final String message = this.inbox.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (message == null) fail("no message containing '" + fragment + "' within 20 s");
            if (message.contains(fragment)) return message;
        }
    }

    /** Signals when the first task handed to it (the command's own run) has returned. */
    private final class FirstTaskExecutor implements Executor {
        final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        final AtomicBoolean rejectFirst = new AtomicBoolean();

        @Override
        public void execute(final Runnable task) {
            if (this.first.compareAndSet(true, false)) {
                if (this.rejectFirst.get()) {
                    this.first.set(true);
                    this.rejectFirst.set(false);
                    throw new RejectedExecutionException("the I/O executor is stopped");
                }
                pool.execute(() -> {
                    try {
                        task.run();
                    } finally {
                        this.finished.countDown();
                    }
                });
            } else {
                pool.execute(task);
            }
        }
    }

    private SpeedtestCommand command(final SpeedtestCommand.Launcher launcher, final Duration timeout,
                                     final BiConsumer<CommandSender, Runnable> hop, final Executor executor) {
        final SpeedtestCommand command = new SpeedtestCommand("speedtest", launcher, timeout, executor, hop);
        command.permissionMessage(Component.text("no permission"));
        return command;
    }

    private static SpeedtestCommand.Launcher script(final String script) {
        return () -> new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
    }

    /** Runs the command once for a fresh sender and returns a tracker for that sender only. */
    private Tracker runOnceForASenderWhoThenLeaves(final SpeedtestCommand command, final BiConsumer<CommandSender, Runnable> hop,
                                                   final FirstTaskExecutor executor, final String finalMessage)
        throws Exception {
        final CommandSender sender = sender();
        assertTrue(command.execute(sender, "speedtest", new String[0]));
        assertEquals("Running...", next());
        await(finalMessage);
        assertTrue(executor.finished.await(20, TimeUnit.SECONDS), "the run never returned");
        return Reachability.track("sender of /speedtest", sender);
    }

    private void assertNextSenderIsNotLockedOut(final SpeedtestCommand command, final BiConsumer<CommandSender, Runnable> hop)
        throws Exception {
        final AtomicReference<String> first = new AtomicReference<>();
        final CommandSender next = sender();
        assertTrue(command.execute(next, "speedtest", new String[0]));
        first.set(next());
        assertEquals("Running...", first.get(), "the one-at-a-time flag was released");
    }

    @Test
    void aFinishedRunReleasesTheSenderAndTheFlag() throws Exception {
        final BiConsumer<CommandSender, Runnable> hop = this.hop;
        final FirstTaskExecutor executor = new FirstTaskExecutor();
        final SpeedtestCommand command = command(script("printf '%s' '" + JSON + "'"), Duration.ofSeconds(20), hop, executor);

        final Tracker sender = runOnceForASenderWhoThenLeaves(command, hop, executor, "DL: ");

        sender.assertCollected();
        assertNextSenderIsNotLockedOut(command, hop);
    }

    @Test
    void aTimedOutRunReleasesTheSenderAndTheFlag() throws Exception {
        final BiConsumer<CommandSender, Runnable> hop = this.hop;
        final FirstTaskExecutor executor = new FirstTaskExecutor();
        final SpeedtestCommand command = command(script("sleep 30"), Duration.ofMillis(300), hop, executor);

        final Tracker sender = runOnceForASenderWhoThenLeaves(command, hop, executor, "timed out");

        sender.assertCollected();
        assertNextSenderIsNotLockedOut(command, hop);
    }

    @Test
    void aLauncherThatFailsReleasesTheSenderAndTheFlag() throws Exception {
        final BiConsumer<CommandSender, Runnable> hop = this.hop;
        final FirstTaskExecutor executor = new FirstTaskExecutor();
        final SpeedtestCommand command = command(() -> {
            throw new IOException("binary missing");
        }, Duration.ofSeconds(20), hop, executor);

        final Tracker sender = runOnceForASenderWhoThenLeaves(command, hop, executor, "binary missing");

        sender.assertCollected();
        assertNextSenderIsNotLockedOut(command, hop);
    }

    @Test
    void aStoppedIoExecutorReleasesTheFlagAtOnceAndKeepsNoSender() throws Exception {
        final BiConsumer<CommandSender, Runnable> hop = this.hop;
        final FirstTaskExecutor executor = new FirstTaskExecutor();
        executor.rejectFirst.set(true);
        final SpeedtestCommand command = command(script("printf '%s' '" + JSON + "'"), Duration.ofSeconds(20), hop, executor);

        final Tracker sender = trackedExecute(command, hop);

        sender.assertCollected();
        assertNextSenderIsNotLockedOut(command, hop);
    }

    private Tracker trackedExecute(final SpeedtestCommand command, final BiConsumer<CommandSender, Runnable> hop) throws Exception {
        final CommandSender sender = sender();
        assertTrue(command.execute(sender, "speedtest", new String[0]));
        assertEquals("Running...", next());
        assertTrue(next().contains("unavailable"), "the sender is told the executor is stopped");
        return Reachability.track("sender of /speedtest", sender);
    }
}
