package dev.iyanz.sourbycraft.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.command.UpdateCommand;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code updater.check-interval-minutes} is RESTART_REQUIRED: the interval schedule is built once
 * in {@code SourbyUpdater.start()}. {@code /update status} must show the interval the running schedule
 * uses, and the configured one too when a reload changed it, instead of the reloaded value alone.
 */
class UpdateIntervalStatusTest {

    @TempDir Path dir;
    private int savedInterval;

    @BeforeEach
    void save() { savedInterval = AutoUpdateSettings.checkIntervalMinutes; }

    @AfterEach
    void restore() { AutoUpdateSettings.checkIntervalMinutes = savedInterval; }

    private SourbyUpdater updater() {
        final HttpTransport transport = (url, headers) -> new HttpTransport.Response(200, Map.of(),
            new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));
        return new SourbyUpdater(dir.resolve("auto_update"), transport,
            () -> new BuildInfo("26.2-REL", "47", "47", "26.2", "", "", ""), Clock.systemUTC(),
            ReleaseSignatureVerifier.inactive(), new UpdateRollbackGuard(dir.resolve("auto_update")));
    }

    private static AsyncScheduler scheduler() {
        final AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(scheduler.runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class)))
            .thenReturn(mock(ScheduledTask.class));
        return scheduler;
    }

    @Test
    void summaryShowsTheRunningIntervalAndThePendingOneOnlyWhenTheyDiffer() {
        assertEquals("30m", AutoUpdateSettings.intervalSummary(30, 30));
        assertEquals("30m (pending restart: 15m)", AutoUpdateSettings.intervalSummary(30, 15));
        assertEquals("0m (pending restart: 15m)", AutoUpdateSettings.intervalSummary(0, 15));
        assertEquals("30m (pending restart: 0m)", AutoUpdateSettings.intervalSummary(30, 0));
        // No schedule built in this process (disabled, mode off): nothing is running, show the configured value.
        assertEquals("15m", AutoUpdateSettings.intervalSummary(-1, 15));
    }

    @Test
    void theUpdaterRemembersTheIntervalItActuallyScheduled() {
        final SourbyUpdater updater = updater();
        final AsyncScheduler scheduler = scheduler();
        assertEquals(-1, updater.runningIntervalMinutes(), "nothing scheduled before start");

        updater.scheduleIntervalCheck(mock(Plugin.class), scheduler, 30);

        assertEquals(30, updater.runningIntervalMinutes());
        verify(scheduler).runAtFixedRate(any(Plugin.class), any(), eq(TimeUnit.MINUTES.toMillis(30)),
            eq(TimeUnit.MINUTES.toMillis(30)), eq(TimeUnit.MILLISECONDS));

        updater.stop();
        assertEquals(-1, updater.runningIntervalMinutes(), "stop() cancels the schedule, so nothing is running");
    }

    @Test
    void zeroMeansDailyChecksOnlyAndSchedulesNoIntervalTask() {
        final SourbyUpdater updater = updater();
        final AsyncScheduler scheduler = scheduler();

        updater.scheduleIntervalCheck(mock(Plugin.class), scheduler, 0);

        assertEquals(0, updater.runningIntervalMinutes());
        verify(scheduler, never()).runAtFixedRate(any(Plugin.class), any(), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void statusLineShowsTheRunningIntervalAfterAReloadChangedTheConfig() {
        final SourbyUpdater updater = updater();
        updater.scheduleIntervalCheck(mock(Plugin.class), scheduler(), 30);
        AutoUpdateSettings.checkIntervalMinutes = 15; // what loadFromToml leaves after /sourbycraft reload

        final String line = updaterLine(updater);

        assertTrue(line.contains("interval=30m (pending restart: 15m)"), line);
        assertTrue(!line.contains("interval=15m"), line);
    }

    @Test
    void statusLineIsPlainWhenNothingChanged() {
        final SourbyUpdater updater = updater();
        updater.scheduleIntervalCheck(mock(Plugin.class), scheduler(), 30);
        AutoUpdateSettings.checkIntervalMinutes = 30;

        final String line = updaterLine(updater);

        assertTrue(line.contains("interval=30m "), line);
        assertTrue(!line.contains("pending"), line);
    }

    private String updaterLine(final SourbyUpdater updater) {
        final CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(any(String.class))).thenReturn(true);
        final List<String> lines = new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
            lines.add(PlainTextComponentSerializer.plainText().serialize(call.getArgument(0, Component.class)));
            return null;
        }).when(sender).sendMessage(any(Component.class));
        final UpdateCommand cmd = new UpdateCommand("update", () -> updater, Runnable::run,
            (target, reply) -> reply.run(), new UpdateRollbackGuard(dir.resolve("auto_update")));

        cmd.execute(sender, "update", new String[] {"status"});

        return lines.stream().filter(l -> l.startsWith("Updater:")).findFirst().orElseThrow(
            () -> new AssertionError("no Updater line in " + lines));
    }
}
