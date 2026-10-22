package dev.iyanz.sourbycraft.hud;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.retention.Reachability;
import dev.iyanz.sourbycraft.retention.Reachability.Tracker;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@link HudBars} keeps per player, and that every way a player leaves the HUD lets go of it.
 *
 * <p>HudBars holds, in process-wide statics keyed by player UUID: three viewer sets (TPS, RAM, perf),
 * three actionbar session maps (UUID to token), and the {@code ANY} index. The tests read them by
 * reflection (they are private) after driving the real transitions: the public toggle/mode methods, the
 * registered {@code QuitListener}, the perf bar's retired callback and {@code close()}.</p>
 *
 * <p>Player retention: HudBars stores UUIDs only, but its scheduled closures capture the {@link Player}.
 * Those live in the server's entity scheduler, modelled here by a queue that is <em>outside</em> the
 * player. The control shows the player is held while a task is queued; after the scheduler ran or
 * retired the tasks and the player quit, the player must be collectable. The player is a plain
 * {@link Proxy}, never a Mockito mock (its inline mock maker keeps mocks reachable).</p>
 */
class HudBarsRetentionTest {

    private static final List<String> SETS = List.of("TPS_VIEWERS", "RAM_VIEWERS", "PERF_VIEWERS");
    private static final List<String> MAPS = List.of("TPS_ACTIONBAR", "RAM_ACTIONBAR", "PERF_ACTIONBAR", "ANY");

    @BeforeAll
    static void initialiseThePluginOwner() throws Exception {
        // MinecraftInternalPlugin.INSTANCE builds a PluginLogger from Bukkit.getServer().getLogger().
        final Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        final Object previous = field.get(null);
        final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("HudBarsRetentionTest");
        field.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getLogger")) return logger;
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return zero(method.getReturnType());
            }));
        try {
            assertNotNull(MinecraftInternalPlugin.INSTANCE);
        } finally {
            field.set(null, previous);
        }
    }

    private static Object zero(final Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }

    /** The server's entity schedulers, for every player: tasks and retired callbacks live here, not in a player. */
    private static final class SchedulerQueue {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Runnable> retired = new ArrayList<>();

        void runAll() {
            final List<Runnable> now = new ArrayList<>(this.tasks);
            this.tasks.clear();
            this.retired.clear();
            now.forEach(Runnable::run);
        }

        /** Entities removed: tasks never run, retired callbacks do, and everything is dropped. */
        void retireAll() {
            final List<Runnable> callbacks = new ArrayList<>(this.retired);
            this.tasks.clear();
            this.retired.clear();
            callbacks.forEach(Runnable::run);
        }

        int queued() {
            return this.tasks.size();
        }
    }

    private static final class UpdaterStub implements HudBars.UpdaterScheduler {
        int scheduled;
        int cancelled;

        @Override
        public ScheduledTask schedule(final Runnable update) {
            this.scheduled++;
            return (ScheduledTask) Proxy.newProxyInstance(ScheduledTask.class.getClassLoader(),
                new Class<?>[] {ScheduledTask.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) {
                        this.cancelled++;
                        return ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    return zero(method.getReturnType());
                });
        }
    }

    private static final class Metrics implements SourbyMetrics {
        @Override
        public PerformanceSnapshot snapshot() {
            throw new AssertionError("the updater is never run by these tests");
        }
    }

    /** Every call made on any player proxy, so a test can prove a code path never touched the Player. */
    private static final java.util.concurrent.atomic.AtomicInteger PLAYER_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    private final SchedulerQueue server = new SchedulerQueue();
    /** What {@code Bukkit::getPlayer} would return for the updater's refresh. */
    private final Map<UUID, Player> online = new java.util.concurrent.ConcurrentHashMap<>();
    private UpdaterStub updater;
    private AutoCloseable restore;

    @BeforeEach
    void install() {
        HudBars.close();
        this.updater = new UpdaterStub();
        this.online.clear();
        this.restore = HudBars.installForTesting(this.updater, new Metrics(), this.online::get);
    }

    @AfterEach
    void uninstall() throws Exception {
        HudBars.close();
        this.restore.close();
    }

    /** A player proxy; its scheduler hands tasks to {@link #server}. */
    private Player player(final UUID id) {
        return playerOn(id, this.server);
    }

    /** A player proxy whose entity scheduler is {@code queue}. */
    private static Player playerOn(final UUID id, final SchedulerQueue queue) {
        final Map<NamespacedKey, Object> pdc = new HashMap<>();
        final PersistentDataContainer container = (PersistentDataContainer) Proxy.newProxyInstance(
            PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
            (p, method, args) -> switch (method.getName()) {
                case "get" -> pdc.get((NamespacedKey) args[0]);
                case "set" -> {
                    pdc.put((NamespacedKey) args[0], args[2]);
                    yield null;
                }
                case "remove" -> {
                    pdc.remove((NamespacedKey) args[0]);
                    yield null;
                }
                case "has" -> pdc.containsKey((NamespacedKey) args[0]);
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == args[0];
                default -> throw new AssertionError("unexpected PDC call " + method.getName());
            });
        final EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
            EntityScheduler.class.getClassLoader(), new Class<?>[] {EntityScheduler.class},
            (p, method, args) -> {
                if (method.getName().equals("run")) {
                    @SuppressWarnings("unchecked") final Consumer<ScheduledTask> body = (Consumer<ScheduledTask>) args[1];
                    final Runnable onRetired = (Runnable) args[2];
                    queue.tasks.add(() -> body.accept(null));
                    if (onRetired != null) queue.retired.add(onRetired);
                    return null;
                }
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                if (method.getName().equals("equals")) return p == args[0];
                throw new AssertionError("unexpected EntityScheduler call " + method.getName());
            });
        final Player[] self = new Player[1];
        self[0] = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (p, method, args) -> {
                if (!method.getName().equals("hashCode") && !method.getName().equals("equals")
                    && !method.getName().equals("toString")) PLAYER_CALLS.incrementAndGet();
                switch (method.getName()) {
                    case "getUniqueId": return id;
                    case "getPersistentDataContainer": return container;
                    case "getScheduler": return scheduler;
                    case "hasPermission": return false;
                    case "showBossBar":
                    case "hideBossBar":
                    case "sendActionBar":
                        return null;
                    case "hashCode": return id.hashCode();
                    case "equals": return p == args[0];
                    case "toString": return "RetentionPlayer[" + id + "]";
                    default: throw new AssertionError("unexpected Player call " + method.getName());
                }
            });
        return self[0];
    }

    private static Object field(final String name) {
        try {
            final Field f = HudBars.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The names of every HudBars structure that currently holds this player's UUID. */
    private static List<String> holding(final UUID id) {
        final List<String> found = new ArrayList<>();
        for (final String name : SETS) if (((Set<?>) field(name)).contains(id)) found.add(name);
        for (final String name : MAPS) if (((Map<?, ?>) field(name)).containsKey(id)) found.add(name);
        return found;
    }

    private static int entries() {
        int total = 0;
        for (final String name : SETS) total += ((Set<?>) field(name)).size();
        for (final String name : MAPS) total += ((Map<?, ?>) field(name)).size();
        return total;
    }

    private static void quit(final Player player) {
        new HudBars.QuitListener().onQuit(new PlayerQuitEvent(player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    /** Turns on the bars in {@code mask} (1 = TPS, 2 = RAM, 4 = perf) in the given mode. */
    private static List<String> enable(final Player player, final int mask, final HudBars.DisplayMode mode) {
        final boolean action = mode == HudBars.DisplayMode.ACTIONBAR;
        final List<String> expected = new ArrayList<>();
        if ((mask & 1) != 0) {
            HudBars.setMode(player, true, mode);
            expected.add("TPS_VIEWERS");
            if (action) expected.add("TPS_ACTIONBAR");
        }
        if ((mask & 2) != 0) {
            HudBars.setMode(player, false, mode);
            expected.add("RAM_VIEWERS");
            if (action) expected.add("RAM_ACTIONBAR");
        }
        if ((mask & 4) != 0) {
            if (action) HudBars.setPerfMode(player, mode);
            else HudBars.setPerf(player, true);
            expected.add("PERF_VIEWERS");
            if (action) expected.add("PERF_ACTIONBAR");
        }
        if ((mask & 3) != 0) expected.add("ANY");
        return expected;
    }

    @Test
    void quitRemovesThePlayerFromEverySetAndMapForEveryCombinationOfBarsAndModes() {
        final Player bystander = player(UUID.randomUUID());
        enable(bystander, 7, HudBars.DisplayMode.ACTIONBAR);
        final List<String> bystanderHolds = holding(bystander.getUniqueId());
        assertEquals(7, bystanderHolds.size(), "the bystander is in all seven structures");

        for (int mask = 1; mask <= 7; mask++) {
            for (final HudBars.DisplayMode mode : HudBars.DisplayMode.values()) {
                final UUID id = UUID.randomUUID();
                final Player player = player(id);
                final List<String> expected = enable(player, mask, mode);
                assertEquals(Set.copyOf(expected), Set.copyOf(holding(id)), "mask " + mask + " " + mode + " before quit");

                quit(player);

                assertEquals(List.of(), holding(id), "mask " + mask + " " + mode + ": nothing may survive the quit");
            }
        }
        assertEquals(bystanderHolds, holding(bystander.getUniqueId()), "another player's entries are untouched");
        quit(bystander);
        assertEquals(0, entries(), "after everyone left, no structure holds anything");
    }

    @Test
    void quittingAPlayerWhoNeverUsedTheHudChangesNothing() {
        final Player user = player(UUID.randomUUID());
        enable(user, 3, HudBars.DisplayMode.BOSSBAR);
        final int before = entries();
        quit(player(UUID.randomUUID()));
        assertEquals(before, entries());
    }

    @Test
    void aQuitTwiceIsHarmless() {
        final Player player = player(UUID.randomUUID());
        enable(player, 7, HudBars.DisplayMode.ACTIONBAR);
        quit(player);
        quit(player);
        assertEquals(0, entries());
    }

    @Test
    void togglingABarOffRemovesTheViewerTheActionbarEntryAndTheIndex() {
        final UUID id = UUID.randomUUID();
        final Player player = player(id);

        HudBars.setMode(player, true, HudBars.DisplayMode.ACTIONBAR);
        assertTrue(holding(id).contains("TPS_ACTIONBAR"));
        assertFalse(HudBars.toggle(player, true), "toggling a shown bar hides it");
        assertEquals(List.of(), holding(id), "TPS off: viewer, actionbar session and ANY are gone");

        HudBars.setMode(player, false, HudBars.DisplayMode.ACTIONBAR);
        assertTrue(holding(id).contains("RAM_ACTIONBAR"));
        assertFalse(HudBars.setPreference(player, false, false));
        assertEquals(List.of(), holding(id), "RAM off");

        HudBars.setPerfMode(player, HudBars.DisplayMode.ACTIONBAR);
        assertEquals(Set.of("PERF_VIEWERS", "PERF_ACTIONBAR"), Set.copyOf(holding(id)));
        assertFalse(HudBars.setPerf(player, false));
        assertEquals(List.of(), holding(id), "perf off");
    }

    @Test
    void turningOffOneBarKeepsTheOthersAndTheIndexUntilTheLastIsOff() {
        final UUID id = UUID.randomUUID();
        final Player player = player(id);
        enable(player, 3, HudBars.DisplayMode.ACTIONBAR);

        HudBars.toggle(player, true);
        assertEquals(Set.of("RAM_VIEWERS", "RAM_ACTIONBAR", "ANY"), Set.copyOf(holding(id)));
        HudBars.toggle(player, false);
        assertEquals(List.of(), holding(id));
    }

    @Test
    void switchingBackToBossbarDropsOnlyTheActionbarSession() {
        final UUID id = UUID.randomUUID();
        final Player player = player(id);

        HudBars.setMode(player, true, HudBars.DisplayMode.ACTIONBAR);
        HudBars.setMode(player, true, HudBars.DisplayMode.BOSSBAR);
        assertEquals(Set.of("TPS_VIEWERS", "ANY"), Set.copyOf(holding(id)));

        HudBars.setPerfMode(player, HudBars.DisplayMode.ACTIONBAR);
        HudBars.setPerfMode(player, HudBars.DisplayMode.BOSSBAR);
        assertEquals(Set.of("TPS_VIEWERS", "ANY", "PERF_VIEWERS"), Set.copyOf(holding(id)));
    }

    @Test
    void aRetiredPerfTaskReleasesThePerfEntries() {
        final UUID id = UUID.randomUUID();
        final Player player = player(id);
        HudBars.setPerfMode(player, HudBars.DisplayMode.ACTIONBAR);
        assertEquals(Set.of("PERF_VIEWERS", "PERF_ACTIONBAR"), Set.copyOf(holding(id)));

        this.server.retireAll(); // the entity was removed before the task ran

        assertEquals(List.of(), holding(id));
    }

    @Test
    void aRetiredTpsOrRamTaskReleasesEveryStructureForEveryCombinationOfBarsAndModes() {
        // The entity scheduler can be retired (entity removed) with the show/hide tasks still queued and
        // without the quit handler having run: the viewer UUIDs must not stay behind until a quit.
        final Player bystander = player(UUID.randomUUID());
        enable(bystander, 7, HudBars.DisplayMode.ACTIONBAR);
        final List<String> bystanderHolds = holding(bystander.getUniqueId());
        for (int mask = 1; mask <= 7; mask++) {
            for (final HudBars.DisplayMode mode : HudBars.DisplayMode.values()) {
                final UUID id = UUID.randomUUID();
                final SchedulerQueue own = new SchedulerQueue();
                final Player scheduled = playerOn(id, own);
                final List<String> expected = enable(scheduled, mask, mode);
                assertEquals(Set.copyOf(expected), Set.copyOf(holding(id)), "mask " + mask + " " + mode + " before retire");

                own.retireAll();

                assertEquals(List.of(), holding(id), "mask " + mask + " " + mode + ": nothing may survive the retire");
            }
        }
        assertEquals(bystanderHolds, holding(bystander.getUniqueId()), "another player's entries are untouched");
    }

    @Test
    void aRetireAfterTheQuitIsHarmlessAndAQuitAfterTheRetireIsToo() {
        final UUID id = UUID.randomUUID();
        final Player player = player(id);
        enable(player, 7, HudBars.DisplayMode.ACTIONBAR);
        quit(player); // PlayerList.remove fires the quit event first and retires the scheduler after it
        this.server.retireAll();
        assertEquals(List.of(), holding(id));

        final UUID other = UUID.randomUUID();
        final Player second = player(other);
        enable(second, 7, HudBars.DisplayMode.BOSSBAR);
        this.server.retireAll();
        quit(second);
        assertEquals(List.of(), holding(other));
        assertEquals(0, entries());
    }

    @Test
    void aRetiredCallbackIsSafeFromAnyThreadAndNeverTouchesThePlayer() throws Exception {
        final UUID id = UUID.randomUUID();
        // the Player is only ever referenced inside this helper's frame, so the tracker is a real control
        final Tracker tracker = enableTpsAndRamAndTrack(id);
        tracker.assertStillReachable();
        final int callsBefore = PLAYER_CALLS.get();
        final Throwable[] failure = new Throwable[1];
        final Thread other = new Thread(() -> {
            try {
                this.server.retireAll();
            } catch (final Throwable t) {
                failure[0] = t;
            }
        }, "not-a-tick-thread");
        other.start();
        other.join();
        assertNull(failure[0]);
        assertEquals(List.of(), holding(id));
        assertEquals(callsBefore, PLAYER_CALLS.get(), "the retired callbacks made no call on the Player");
        tracker.assertCollected();
    }

    private Tracker enableTpsAndRamAndTrack(final UUID id) {
        final Player player = player(id);
        enable(player, 3, HudBars.DisplayMode.ACTIONBAR); // no perf bar: its own callback must not be what clears these
        return Reachability.track("Player proxy", player);
    }

    @Test
    void aRetiredPerfTaskAlsoReleasesTheTpsAndRamEntriesOfAPlayerWhoseOtherTasksAlreadyRan() {
        // A retire means the entity is gone: every bar of that player goes, not only the one whose
        // task happened to be the last in the queue.
        final UUID id = UUID.randomUUID();
        final Player player = player(id);
        enable(player, 3, HudBars.DisplayMode.ACTIONBAR);
        this.server.runAll(); // the TPS and RAM show tasks ran; nothing of theirs is queued any more
        HudBars.setPerf(player, true);
        assertEquals(Set.of("TPS_VIEWERS", "RAM_VIEWERS", "TPS_ACTIONBAR", "RAM_ACTIONBAR", "ANY", "PERF_VIEWERS"),
            Set.copyOf(holding(id)));

        this.server.retireAll();

        assertEquals(List.of(), holding(id));
    }

    @Test
    void aRetiredRefreshTaskReleasesTheViewerToo() throws Exception {
        // The updater queues a refresh per actionbar viewer on the viewer's own scheduler every second.
        // Once the show task ran, a refresh is the only task left in the queue; if the entity is removed
        // then, its retired callback is the only thing that can release the UUID before a quit.
        final UUID id = UUID.randomUUID();
        final Player player = player(id);
        enable(player, 3, HudBars.DisplayMode.ACTIONBAR);
        this.server.runAll();
        this.online.put(id, player);
        final Method refresh = HudBars.class.getDeclaredMethod("sendActionbars", Set.class, Map.class, Component.class);
        refresh.setAccessible(true);
        refresh.invoke(null, field("TPS_VIEWERS"), field("TPS_ACTIONBAR"), Component.text("tps"));
        refresh.invoke(null, field("RAM_VIEWERS"), field("RAM_ACTIONBAR"), Component.text("ram"));
        assertEquals(2, this.server.queued(), "one refresh per actionbar bar");
        assertFalse(holding(id).isEmpty());

        this.server.retireAll();

        assertEquals(List.of(), holding(id));
    }

    @Test
    void closeClearsEveryStructureStopsTheUpdaterAndNeverReusesASessionToken() {
        final List<Player> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Player p = player(UUID.randomUUID());
            enable(p, 7, HudBars.DisplayMode.ACTIONBAR);
            players.add(p);
        }
        assertEquals(1, this.updater.scheduled);
        assertEquals(21, entries(), "three players in seven structures each");
        final long oldToken = (Long) ((Map<?, ?>) field("TPS_ACTIONBAR")).get(players.get(0).getUniqueId());

        HudBars.close();

        assertEquals(0, entries());
        assertEquals(1, this.updater.cancelled, "the updater task was cancelled");
        HudBars.setMode(players.get(0), true, HudBars.DisplayMode.ACTIONBAR);
        final long newToken = (Long) ((Map<?, ?>) field("TPS_ACTIONBAR")).get(players.get(0).getUniqueId());
        assertTrue(newToken > oldToken, "tokens are never reused, even after close()");
        assertEquals(2, this.updater.scheduled, "a later toggle starts a fresh updater");
    }

    @Test
    void manyPlayersJoiningTogglingAndLeavingLeaveNothingBehind() {
        for (int i = 0; i < 300; i++) {
            final Player p = player(UUID.randomUUID());
            enable(p, 1 + i % 7, i % 2 == 0 ? HudBars.DisplayMode.ACTIONBAR : HudBars.DisplayMode.BOSSBAR);
            if (i % 5 == 0) HudBars.toggle(p, true);
            quit(p);
        }
        assertEquals(0, entries(), "every structure is empty once every player has left");
    }

    // ------------------------------------------------------------------ the Player object itself

    private Tracker joinEnableEverythingAndTrack(final boolean actionbar) {
        final Player player = player(UUID.randomUUID());
        enable(player, 7, actionbar ? HudBars.DisplayMode.ACTIONBAR : HudBars.DisplayMode.BOSSBAR);
        final Tracker tracker = Reachability.track("Player proxy", player);
        // control: the scheduled show/hide closures capture the player, and the server's queue holds them
        assertTrue(this.server.queued() > 0);
        tracker.assertStillReachable();
        return tracker;
    }

    private Tracker quitAfterTasksRan(final boolean actionbar) {
        final Player player = player(UUID.randomUUID());
        enable(player, 7, actionbar ? HudBars.DisplayMode.ACTIONBAR : HudBars.DisplayMode.BOSSBAR);
        final Tracker tracker = Reachability.track("Player proxy", player);
        tracker.assertStillReachable();
        this.server.runAll();
        quit(player);
        return tracker;
    }

    @Test
    void aPlayerIsNotRetainedByHudBarsAfterTheirTasksRanAndTheyQuit() throws Exception {
        for (final boolean actionbar : new boolean[] {false, true}) {
            final Tracker tracker = quitAfterTasksRan(actionbar);
            tracker.assertCollected();
        }
        assertEquals(0, entries());
    }

    @Test
    void aPlayerWhoseTasksNeverRanButWhoseEntityWasRemovedIsNotRetained() throws Exception {
        for (final boolean actionbar : new boolean[] {false, true}) {
            final Tracker tracker = joinEnableEverythingAndTrack(actionbar);
            // the player never quit through the listener: the scheduler drops the queued work and retires
            this.server.retireAll();
            tracker.assertCollected();
            HudBars.close();
        }
    }

    @Test
    void closeLetsGoOfEveryPlayerEvenWithoutAQuit() throws Exception {
        final Tracker tracker = joinEnableEverythingAndTrack(true);
        this.server.runAll();

        HudBars.close();

        tracker.assertCollected();
        assertEquals(0, entries());
    }
}
