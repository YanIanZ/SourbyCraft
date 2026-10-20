package dev.iyanz.sourbycraft.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.iyanz.sourbycraft.api.metrics.MetricState;
import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.perf.RegionLocator;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

/**
 * Golden output of {@code /perf} and {@code /ram}: usage text, the view list and its order, tab
 * completion, the argument handling of {@code player} and {@code region}, and every view's lines.
 *
 * <p>Captured from the single-class {@code PerfCommand} before it was split into one class per
 * view, so the split is checked against what operators saw. Views built only from the metrics
 * snapshot or from test-owned inputs are compared exactly, styles included (Gson JSON). Views
 * that read process-wide counters (network, governor, storage, ...) are pinned by their frame
 * only (header, freshness, actions, footer): other tests in the same JVM move those counters,
 * and the thread ledger depends on the machine's processor count; their bodies are covered by
 * the per-view tests (PerfTelemetryRowsTest, PerfSchedulerViewTest, PerfLanesViewTest, ...).</p>
 *
 * <p>On a mismatch the actual output is written to {@code build/perf-golden.actual.txt}; a
 * deliberate output change replaces the golden file with it, as a reviewed diff.</p>
 */
class PerfCommandGoldenTest {

    /**
     * Rendering the plugins view asks {@code AuroraBridge.runtimeMode()}, which starts the process-wide lazy
     * bridge runtime. Put it back so tests that expect "not started" (SourbyMetadataProviderTest) do not
     * depend on running before this one.
     */
    @org.junit.jupiter.api.AfterEach
    void forgetTheBridgeRuntimeThisTestStarted() throws Exception {
        dev.iyanz.sourbycraft.bridge.AuroraBridge.resetRuntimeForTesting().close();
    }

    private static final String GOLDEN = "src/test/resources/dev/iyanz/sourbycraft/command/perf-golden.txt";

    /** Views whose lines come only from the metrics snapshot: compared exactly. */
    private static final List<String> SNAPSHOT_VIEWS =
        List.of("overview", "tick", "cpu", "memory", "gc", "region", "health", "no-such-view");

    /** Views that read process-wide state: only their frame is compared. */
    private static final List<String> GLOBAL_VIEWS = List.of(
        "lanes", "async", "network", "governor", "scheduler", "incidents", "storage", "awf",
        "plugins", "history", "chunks", "entities");

    private static final RegionTickMetrics.WindowSnapshot WINDOW = new RegionTickMetrics.WindowSnapshot(
        100L, 5_000_000_000L, 800_000_000L, 4_000_000L, 8.0, 16_000_000L, 0L,
        19.99, 8.0, 0.4, 12.0, 14.0, false, true);

    private static final RegionTickMetrics.Snapshot SNAPSHOT = new RegionTickMetrics.Snapshot(
        1L, 1_000_000_000L, RegionTickMetrics.INACTIVE,
        WINDOW, WINDOW, WINDOW, WINDOW, WINDOW, WINDOW);

    private final StringBuilder out = new StringBuilder();

    @Test
    void perfOutputMatchesTheGoldenFile() throws Exception {
        this.out.setLength(0);
        identity();
        tabCompletion();
        dispatch();
        snapshotViews();
        locatedRegions();
        playerView();
        awfRows();
        globalViews();
        compare(this.out.toString());
    }

    // ----------------------------------------------------------------- identity and completion

    private void identity() {
        final PerfCommand perf = new PerfCommand("perf");
        final PerfCommand ram = new PerfCommand("ram");
        section("VIEWS");
        this.out.append(String.join(",", PerfCommand.VIEWS)).append('\n');
        section("perf usage / permission / description");
        this.out.append(perf.getUsage()).append('\n').append(perf.getPermission()).append('\n')
            .append(perf.getDescription()).append('\n');
        section("ram usage / permission / description");
        this.out.append(ram.getUsage()).append('\n').append(ram.getPermission()).append('\n')
            .append(ram.getDescription()).append('\n');
    }

    private void tabCompletion() throws Exception {
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final World nether = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(nether.getName()).thenReturn("World_Nether");
        when(server.getWorlds()).thenReturn(List.of(world, nether));
        final PerfCommand perf = CommandTestSupport.quietDenial(new PerfCommand("perf"));
        final PerfCommand ram = CommandTestSupport.quietDenial(new PerfCommand("ram"));
        final CommandSender allowed = new ReplyProbe().sender(CommandSender.class, true);
        final CommandSender denied = new ReplyProbe().sender(CommandSender.class, false);
        CommandTestSupport.withServer(server, () -> {
            for (final String[] args : List.of(
                    new String[]{""}, new String[]{"p"}, new String[]{"REG"}, new String[]{"s"},
                    new String[]{"overview"}, new String[]{"x"},
                    new String[]{"region", ""}, new String[]{"region", "WOR"}, new String[]{"REGION", "world_n"},
                    new String[]{"player", ""}, new String[]{"tick", ""}, new String[]{"region", "world", ""},
                    new String[]{})) {
                section("tab perf [" + String.join(" ", args) + "]");
                this.out.append(perf.tabComplete(allowed, "perf", args)).append('\n');
            }
            section("tab perf denied");
            this.out.append(perf.tabComplete(denied, "perf", new String[]{""})).append('\n');
            section("tab ram");
            this.out.append(ram.tabComplete(allowed, "ram", new String[]{""})).append('\n');
        });
    }

    // ----------------------------------------------------------------- execute dispatch

    /** A sender recording every component it is sent. */
    private static CommandSender recorder(final List<Component> received, final boolean permitted) {
        final CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        when(sender.getName()).thenReturn("Console");
        doAnswer(invocation -> {
            received.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(any(Component.class));
        return sender;
    }

    private void execute(final String name, final String... args) {
        final List<Component> received = new ArrayList<>();
        final boolean result = CommandTestSupport.quietDenial(new PerfCommand(name))
            .execute(recorder(received, true), name, args);
        section("execute /" + name + " [" + String.join(" ", args) + "] -> " + result);
        for (final Component line : received) {
            // The live provider's freshness depends on what other tests in this JVM published.
            if (PlainTextComponentSerializer.plainText().serialize(line).startsWith("  Freshness:")) {
                this.out.append("<live provider freshness line>\n");
            } else {
                exact(List.of(line));
            }
        }
    }

    private void dispatch() throws Exception {
        // Usage and argument errors: no metrics, no server needed.
        execute("perf", "tick", "extra");
        execute("perf", "no-such-view");
        execute("perf", "overview", "extra");
        execute("perf", "player");
        execute("perf", "PLAYER", "a", "b");
        execute("perf", "region", "world");
        execute("perf", "region", "world", "16");
        execute("perf", "Region", "world", "16", "-32", "extra");
        execute("perf", "region", "world", "x", "-32");
        execute("perf", "region", "world", "16", "z");
        execute("ram", "memory");
        execute("ram", "player", "Steve");
        execute("ram", "region", "world", "1", "2");
        // A located region without a server: the locator reports it; the freshness line is the provider's.
        execute("perf", "region", "world", "16", "-32");
        // Denied: Command.testPermission sends the permission message.
        final List<Component> denied = new ArrayList<>();
        final boolean result = CommandTestSupport.quietDenial(new PerfCommand("perf"))
            .execute(recorder(denied, false), "perf", new String[]{"tick"});
        section("execute /perf [tick] denied -> " + result);
        exact(denied);
        // An offline player is named, on the command thread.
        final Server server = mock(Server.class);
        CommandTestSupport.withServer(server, () -> execute("perf", "player", "Steve"));
    }

    // ----------------------------------------------------------------- views

    private static List<Supplier<SourbyMetrics>> snapshots() {
        return List.of(
            () -> CommandTestSupport::nothingMeasured,
            () -> () -> CommandTestSupport.healthy(20.0, 8.0),
            () -> () -> CommandTestSupport.healthy(20.0, 12.0).target(20.0),
            () -> () -> CommandTestSupport.healthy(16.0, 60.0)
                .runtime(CommandTestSupport.runtime(
                    "processCpuPercent", 87.25, "systemCpuPercent", 93.0, "availableProcessors", 8,
                    "liveThreadCount", 97, "uptimeMillis", 3_725_000L,
                    "heapUsedBytes", 3L << 30, "heapCommittedBytes", 4L << 30, "heapMaxBytes", 6L << 30,
                    "nonHeapUsedBytes", 300L << 20, "processRssBytes", 5L << 30, "rssPercent", 71.5,
                    "gcCollectionCount", 1234L, "gcCollectionTimeMillis", 5678L, "gcCollectionsPerMinute", 3.5)),
            () -> () -> CommandTestSupport.healthy(20.0, 8.0)
                .fresh(CommandTestSupport.freshness(MetricState.STALE, 12_000L, "collector late")));
    }

    private void snapshotViews() {
        final List<Supplier<SourbyMetrics>> snapshots = snapshots();
        for (int s = 0; s < snapshots.size(); s++) {
            for (final String view : SNAPSHOT_VIEWS) {
                section("render snapshot#" + s + " " + view);
                exact(PerfCommand.render(snapshots.get(s).get(), view));
            }
            section("health snapshot#" + s);
            this.out.append(PerfCommand.health(snapshots.get(s).get().snapshot())).append('\n');
        }
        section("health without a positive target");
        this.out.append(PerfCommand.health(CommandTestSupport.healthy(20.0, 8.0).target(0.0))).append('\n');
        section("health warning window");
        this.out.append(PerfCommand.health(CommandTestSupport.healthy(20.0, 8.0)
            .window(MetricWindow.FIVE_SECONDS, CommandTestSupport.window(
                "worstTps", 18.5, "worstAverageMspt", 41.0)))).append('\n');
    }

    private void locatedRegions() {
        final SourbyMetrics metrics = () -> CommandTestSupport.healthy(20.0, 8.0);
        final List<RegionLocator.Result> results = List.of(
            new RegionLocator.Result(RegionLocator.Status.FOUND, 3L, 7L, 11L, true, SNAPSHOT, 1, -2),
            new RegionLocator.Result(RegionLocator.Status.FOUND, 3L, 7L, 11L, false, SNAPSHOT, 1, -2),
            new RegionLocator.Result(RegionLocator.Status.NO_REGION_AT_CHUNK, 3L, 0L, 0L, false, null, 1, -2),
            new RegionLocator.Result(RegionLocator.Status.UNKNOWN_WORLD, -1L, 0L, 0L, false, null, 1, -2),
            new RegionLocator.Result(RegionLocator.Status.NO_SERVER, -1L, 0L, 0L, false, null, 1, -2),
            new RegionLocator.Result(RegionLocator.Status.NO_TELEMETRY, 3L, 7L, 0L, true, null, 1, -2));
        for (final RegionLocator.Result result : results) {
            section("renderRegion " + result.status() + " active=" + result.active());
            exact(PerfCommand.renderRegion(metrics, "world", 16, -32, result));
        }
    }

    private void playerView() {
        // Every branch of the owner-thread hop, as PerfPlayerViewTest drives it.
        for (final boolean asSender : new boolean[]{true, false}) {
            for (final String mode : List.of("RUNS", "RETIRES", "REFUSES")) {
                final ReplyProbe probe = new ReplyProbe();
                final org.bukkit.entity.Player player = player(probe, asSender, mode);
                final CommandSender sender = asSender ? player
                    : probe.sender(org.bukkit.command.BlockCommandSender.class, true);
                PerfCommand.renderPlayer(sender, player, mock(Plugin.class), probe);
                section("renderPlayer asSender=" + asSender + " " + mode + " hops=" + probe.hops());
                for (final ReplyProbe.Received received : probe.received()) {
                    this.out.append(received.throughHop() ? "hop " : "direct ")
                        .append(received.text().replaceAll("Owning thread: .*", "Owning thread: <thread>")).append('\n');
                }
            }
        }
    }

    /** A player whose entity scheduler runs, retires or refuses the task at once. */
    private static org.bukkit.entity.Player player(final ReplyProbe probe, final boolean asSender, final String mode) {
        final org.bukkit.entity.Player player = asSender ? probe.sender(org.bukkit.entity.Player.class, true)
            : mock(org.bukkit.entity.Player.class);
        when(player.getName()).thenReturn("Steve");
        final World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 17.5, 64.0, -33.2));
        when(player.getPing()).thenReturn(42);
        when(player.getViewDistance()).thenReturn(10);
        when(player.getSimulationDistance()).thenReturn(8);
        final io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
            mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            final java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> task =
                invocation.getArgument(1);
            final Runnable retired = invocation.getArgument(2);
            switch (mode) {
                case "RUNS" -> task.accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
                case "RETIRES" -> retired.run();
                default -> {
                    return null;
                }
            }
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        });
        return player;
    }

    private void awfRows() {
        final List<Component> normal = new ArrayList<>();
        PerfCommand.renderAwf(normal, 0L, null);
        section("renderAwf 0 forces (no engine)");
        exact(normal);
        final List<Component> degraded = new ArrayList<>();
        PerfCommand.renderAwf(degraded, 7L, "UnsupportedOperationException: directories cannot be opened");
        section("renderAwf 7 forces (no engine)");
        exact(degraded);
        final List<Component> collapsed = new ArrayList<>();
        PerfCommand.renderAwf(collapsed, 0L, null, 1458L);
        section("renderAwf 1458 collapsed read errors (no engine)");
        exact(collapsed);
        section("shortStorageName");
        this.out.append(PerfCommand.shortStorageName("/srv/mc/world/DIM-1/region")).append(' ')
            .append(PerfCommand.shortStorageName("world/entities")).append(' ')
            .append(PerfCommand.shortStorageName("C:\\srv\\world\\poi")).append(' ')
            .append(PerfCommand.shortStorageName("region")).append('\n');
    }

    private void globalViews() throws Exception {
        final Server server = mock(Server.class);
        final PluginManager plugins = mock(PluginManager.class);
        when(plugins.getPlugins()).thenReturn(new Plugin[0]);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getWorlds()).thenReturn(List.of());
        final SourbyMetrics metrics = () -> CommandTestSupport.healthy(20.0, 8.0);
        CommandTestSupport.withServer(server, () -> {
            for (final String view : GLOBAL_VIEWS) {
                // The body reads process-wide counters that other tests in this JVM move: only the
                // frame (header, freshness, actions, footer) is pinned here.
                final List<Component> lines = PerfCommand.render(metrics, view);
                section("render frame " + view + " (body not compared)");
                exact(List.of(lines.get(0)));
                exact(lines.subList(lines.size() - 3, lines.size()));
            }
        });
    }

    // ----------------------------------------------------------------- output

    private void section(final String title) {
        this.out.append("## ").append(title).append('\n');
    }

    private void exact(final List<Component> lines) {
        for (final Component line : lines) {
            this.out.append(GsonComponentSerializer.gson().serialize(line)).append('\n');
        }
    }

    private static Path moduleDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("sourbycraft-server/src/test/java"))) return dir.resolve("sourbycraft-server");
            if (Files.isDirectory(dir.resolve("src/test/java/dev/iyanz/sourbycraft"))) return dir;
        }
        throw new IllegalStateException("could not locate sourbycraft-server from " + Path.of("").toAbsolutePath());
    }

    private static void compare(final String actual) throws IOException {
        final Path module = moduleDir();
        final Path golden = module.resolve(GOLDEN);
        final Path dump = module.resolve("build/perf-golden.actual.txt");
        Files.createDirectories(dump.getParent());
        Files.writeString(dump, actual, StandardCharsets.UTF_8);
        if (!Files.exists(golden)) {
            fail("no golden file at " + golden + "; the actual output is in " + dump);
        }
        final List<String> expected = Files.readAllLines(golden, StandardCharsets.UTF_8);
        final List<String> got = actual.lines().toList();
        for (int i = 0; i < Math.min(expected.size(), got.size()); i++) {
            assertEquals(expected.get(i), got.get(i), "line " + (i + 1) + " differs; actual output in " + dump);
        }
        assertEquals(expected.size(), got.size(), "line count differs; actual output in " + dump);
    }
}
