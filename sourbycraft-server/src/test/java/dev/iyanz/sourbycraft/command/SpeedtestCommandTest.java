package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code /speedtest} against a process that is a shell script: hung, chatty, or well-behaved.
 *
 * <p>OBS-2: the command read the process's output before waiting for it, so a CLI that hung with
 * its pipe open blocked the worker in {@code readAllBytes()}, the 120 s timeout never ran, the
 * process stayed, and the in-flight flag refused every later {@code /speedtest} until restart.
 * OBS-8: every reply after {@code execute} returned must take the sender's hop. OBS-12: nothing
 * runs, not even the log line, for a sender without the permission.</p>
 */
class SpeedtestCommandTest {

    private static final String JSON = "{\"download\":{\"bandwidth\":12500000},\"upload\":{\"bandwidth\":2500000},"
        + "\"ping\":{\"latency\":12.3},\"server\":{\"name\":\"Test\",\"location\":\"Nowhere\"}}";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeAll
    static void needsAShell() {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows"),
            "the fake speedtest is a sh script");
    }

    @AfterEach
    void stop() {
        this.executor.shutdownNow();
    }

    /** A launcher around a shell script that records the processes it started. */
    private static final class ScriptLauncher implements SpeedtestCommand.Launcher {
        final String script;
        final List<Process> started = new ArrayList<>();

        ScriptLauncher(final String script) {
            this.script = script;
        }

        @Override
        public synchronized Process start() throws IOException {
            final Process process = new ProcessBuilder("sh", "-c", this.script).redirectErrorStream(true).start();
            this.started.add(process);
            return process;
        }
    }

    private SpeedtestCommand command(final SpeedtestCommand.Launcher launcher, final Duration timeout, final ReplyProbe hop) {
        final SpeedtestCommand command = new SpeedtestCommand("speedtest", launcher, timeout, this.executor, hop);
        command.permissionMessage(Component.text("no permission"));     // keeps Bukkit.permissionMessage() out of a unit test
        return command;
    }

    @Test
    void aHungProcessIsKilledAfterTheTimeoutAndTheCommandIsUsableAgain() throws Exception {
        final ReplyProbe hop = new ReplyProbe();
        final ScriptLauncher launcher = new ScriptLauncher("sleep 30");
        final SpeedtestCommand command = command(launcher, Duration.ofMillis(500), hop);
        final CommandSender console = hop.sender(ConsoleCommandSender.class, true);

        assertTrue(command.execute(console, "speedtest", new String[0]));
        // A second invocation while the first is hung is refused, not stacked. Both
        // acknowledgements are sent synchronously, so they are queued in this order before the
        // worker can time out.
        assertTrue(command.execute(console, "speedtest", new String[0]));
        assertEquals("Running...", hop.next().text());
        assertTrue(hop.next().text().contains("already running"));

        final ReplyProbe.Received timedOut = hop.await("timed out");
        assertTrue(timedOut.throughHop(), "the timeout reply must take the sender's hop");
        assertEquals(1, launcher.started.size(), "the refused invocation started nothing");
        final Process hung = launcher.started.getFirst();
        assertTrue(hung.waitFor(5, TimeUnit.SECONDS), "the hung process was not killed");
        assertFalse(hung.isAlive());

        // The command is usable again: a third invocation starts a new process.
        assertTrue(command.execute(console, "speedtest", new String[0]));
        assertEquals("Running...", hop.next().text());
        hop.await("timed out");
        assertEquals(2, launcher.started.size());
        assertTrue(launcher.started.get(1).waitFor(5, TimeUnit.SECONDS));
    }

    @Test
    void aProcessThatWritesMoreThanAPipeHoldsIsDrainedWhileItRuns() throws Exception {
        // 200 KiB of preamble before the JSON: far more than a pipe buffers. Waiting for the exit
        // before reading would deadlock (the process blocks on a full pipe, the command on the
        // process); reading before waiting is what hung on OBS-2. Both at once is the answer.
        final ReplyProbe hop = new ReplyProbe();
        final ScriptLauncher launcher = new ScriptLauncher(
            "head -c 204800 /dev/zero | tr '\\0' 'x'; printf '%s' '" + JSON + "'");
        final SpeedtestCommand command = command(launcher, Duration.ofSeconds(20), hop);
        final CommandSender console = hop.sender(ConsoleCommandSender.class, true);

        command.execute(console, "speedtest", new String[0]);
        assertEquals("Running...", hop.next().text());
        final ReplyProbe.Received panel = hop.await("Mbps");
        assertTrue(panel.text().contains("100.0 Mbps"), panel.text());    // 12_500_000 B/s * 8 / 1e6
        assertTrue(panel.text().contains("20.0 Mbps"), panel.text());
        assertTrue(panel.text().contains("12ms"), panel.text());
        assertTrue(panel.text().contains("Test, Nowhere"), panel.text());
        assertTrue(panel.throughHop());
        assertTrue(launcher.started.getFirst().waitFor(5, TimeUnit.SECONDS));
    }

    @Test
    void aFailingProcessIsReportedWithItsExitCodeNotSwallowed() throws Exception {
        final ReplyProbe hop = new ReplyProbe();
        final SpeedtestCommand command = command(new ScriptLauncher("echo 'no network' >&2; exit 3"),
            Duration.ofSeconds(20), hop);
        final CommandSender console = hop.sender(ConsoleCommandSender.class, true);

        command.execute(console, "speedtest", new String[0]);
        assertEquals("Running...", hop.next().text());
        final ReplyProbe.Received failed = hop.await("Speedtest failed");
        assertTrue(failed.text().contains("exit 3"), failed.text());
        assertTrue(failed.throughHop());
    }

    @Test
    void aCommandBlockGetsEveryAsyncReplyThroughItsRegionHop() throws Exception {
        // BaseCommandBlock.sendSystemMessage writes lastOutput on the block entity behind Folia's
        // thread check: a reply from the worker thread throws. SourbyReply.hopFor says a command
        // block needs the REGION hop; this proves the command hands every async reply to it.
        final ReplyProbe hop = new ReplyProbe();
        final ScriptLauncher launcher = new ScriptLauncher("printf '%s' '" + JSON + "'");
        final SpeedtestCommand command = command(launcher, Duration.ofSeconds(20), hop);
        final BlockCommandSender block = hop.sender(BlockCommandSender.class, true);
        assertEquals(SourbyReply.Hop.REGION, SourbyReply.hopFor(block));

        command.execute(block, "speedtest", new String[0]);
        final ReplyProbe.Received running = hop.next();
        assertEquals("Running...", running.text());
        assertFalse(running.throughHop(), "the synchronous acknowledgement is already on the command thread");
        final ReplyProbe.Received panel = hop.await("Mbps");
        assertTrue(panel.throughHop(), "the result came from the worker and must take the region hop");
        assertEquals(1, hop.hops(), "one hop: nothing else was sent off-thread");
    }

    @Test
    void aSenderWithoutThePermissionStartsNothing() throws Exception {
        final ReplyProbe hop = new ReplyProbe();
        final ScriptLauncher launcher = new ScriptLauncher("printf '%s' '" + JSON + "'");
        final AtomicReference<Runnable> submitted = new AtomicReference<>();
        final SpeedtestCommand command = new SpeedtestCommand("speedtest", launcher, Duration.ofSeconds(20),
            task -> submitted.set(task), hop);
        command.permissionMessage(Component.text("no permission"));
        final CommandSender console = hop.sender(ConsoleCommandSender.class, false);

        assertTrue(command.execute(console, "speedtest", new String[0]));
        assertEquals("no permission", hop.next().text());
        assertNull(submitted.get(), "nothing -- not the log line, not the process -- runs before the permission check");
        assertTrue(launcher.started.isEmpty());
        assertEquals(0, hop.hops());
    }
}
