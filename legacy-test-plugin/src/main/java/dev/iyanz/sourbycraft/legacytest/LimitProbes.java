package dev.iyanz.sourbycraft.legacytest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Console probes for the bridge's optional per-plugin limits ({@code aurora.bridge.max-pending-tasks-per-plugin},
 * {@code max-running-async-tasks-per-plugin}) and for {@code /sourbycraft reload}. Manual/agent runs only; CI
 * does not send these commands. The limit under test is passed as an argument, because a plugin cannot read
 * the bridge's configuration; each probe prints {@code <MARKER>_OK} or {@code <MARKER>_WRONG}.
 *
 * <p>Delayed checks run on a plain JDK timer thread, not through the Bukkit scheduler: a check scheduled
 * through the bridge would itself take a pending slot and change what it measures.</p>
 */
final class LimitProbes {

    private static final Set<String> COMMANDS = Set.of("pending-limit", "async-limit", "hold", "held", "release",
        "busy", "route", "pending");

    private final LegacyBridgePlugin plugin;
    /** The current {@code hold} batch: handles and per-task body runs. */
    private volatile List<BukkitTask> held = List.of();
    private volatile AtomicIntegerArray heldRuns = new AtomicIntegerArray(0);

    LimitProbes(final LegacyBridgePlugin plugin) {
        this.plugin = plugin;
    }

    static boolean handles(final String sub) {
        return COMMANDS.contains(sub);
    }

    boolean command(final String[] args) {
        try {
            switch (args[0]) {
                case "pending-limit" -> pendingLimit(Integer.parseInt(args[1]));
                case "async-limit" -> asyncLimit(Integer.parseInt(args[1]));
                case "hold" -> hold(Integer.parseInt(args[1]), Long.parseLong(args[2]));
                case "held" -> held();
                case "release" -> release();
                case "busy" -> busy(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                case "route" -> route();
                case "pending" -> this.plugin.getLogger().info("LEGACY_PENDING count=" + ownPending());
                default -> { return false; }
            }
            return true;
        } catch (final ArrayIndexOutOfBoundsException | NumberFormatException usage) {
            this.plugin.getLogger().warning("usage: legacybridge pending-limit <n> | async-limit <n> | hold <n> <delayTicks>"
                + " | held | release | busy <n> <seconds> | route | pending");
            return true;
        }
    }

    private BukkitScheduler scheduler() {
        return this.plugin.getServer().getScheduler();
    }

    private int ownPending() {
        return (int)scheduler().getPendingTasks().stream().filter(task -> task.getOwner() == this.plugin).count();
    }

    private static Executor after(final long millis) {
        return CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Pending limit {@code limit} ({@code 0} = unlimited). Fills the remaining room plus three, expects exactly the
     * room admitted and the three cancelled before scheduling (not queued, never run); a cancelled admitted task
     * frees its slot at once; after every admitted body ran (each exactly once) the plugin's pending count is
     * back to where it started and a new task is admitted again.
     */
    private void pendingLimit(final int limit) {
        final BukkitScheduler scheduler = scheduler();
        final int baseline = ownPending();
        if (limit == 0) {
            final int count = 300;
            final AtomicIntegerArray runs = new AtomicIntegerArray(count);
            int accepted = 0;
            for (int i = 0; i < count; i++) {
                final int index = i;
                if (!scheduler.runTaskLater(this.plugin, () -> runs.incrementAndGet(index), 20L).isCancelled()) accepted++;
            }
            final int admitted = accepted;
            CompletableFuture.runAsync(() -> {
                final int[] ran = ran(runs);
                this.plugin.check(admitted == count && ran[0] == count && ran[1] == 0 && ownPending() == baseline,
                    "LEGACY_LIMIT_PENDING_UNLIMITED", "accepted=" + admitted + " ran=" + ran[0] + " duplicates=" + ran[1]
                        + " pending=" + ownPending() + " baseline=" + baseline);
            }, after(4000));
            return;
        }
        final int room = Math.max(0, limit - baseline);
        final int count = room + 3;
        final AtomicIntegerArray runs = new AtomicIntegerArray(count + 1);
        final List<BukkitTask> accepted = new ArrayList<>();
        final List<BukkitTask> rejected = new ArrayList<>();
        final List<Integer> rejectedIndexes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            final BukkitTask task = scheduler.runTaskLater(this.plugin, () -> runs.incrementAndGet(index), 40L);
            if (task.isCancelled()) {
                rejected.add(task);
                rejectedIndexes.add(index);
            } else {
                accepted.add(task);
            }
        }
        final boolean rejectedClean = rejected.stream().allMatch(t -> !scheduler.isQueued(t.getTaskId()));
        boolean freed = true;
        int expectedRuns = accepted.size();
        String freeDetail = "";
        if (!accepted.isEmpty()) {
            // Ordinary cancellation frees a slot at once; the slot is then full again.
            final BukkitTask first = accepted.getFirst();
            first.cancel();
            final BukkitTask replacement = scheduler.runTaskLater(this.plugin, () -> runs.incrementAndGet(count), 40L);
            final BukkitTask overflow = scheduler.runTaskLater(this.plugin, () -> this.plugin.getLogger()
                .severe("LEGACY_LIMIT_PENDING_OVERFLOW_RAN_WRONG"), 40L);
            freed = !replacement.isCancelled() && overflow.isCancelled() && !scheduler.isQueued(overflow.getTaskId());
            freeDetail = " replacement=" + !replacement.isCancelled() + " overflowRejected=" + overflow.isCancelled();
        }
        this.plugin.check(accepted.size() == room && rejected.size() == 3 && rejectedClean && freed && ownPending() == limit
                || accepted.isEmpty() && rejected.size() == 3 && rejectedClean && baseline >= limit,
            "LEGACY_LIMIT_PENDING", "limit=" + limit + " baseline=" + baseline + " accepted=" + accepted.size()
                + " rejected=" + rejected.size() + " rejectedQueued=" + !rejectedClean + freeDetail + " pending=" + ownPending());
        final int cancelledIndex = accepted.isEmpty() ? -1 : indexOf(accepted.getFirst(), accepted, rejected);
        final int runsExpected = expectedRuns;
        CompletableFuture.runAsync(() -> {
            int ran = 0;
            int duplicates = 0;
            int wrong = 0;
            for (int i = 0; i <= count; i++) {
                final int r = runs.get(i);
                if (r > 1) duplicates++;
                if (r > 0 && (rejectedIndexes.contains(i) || i == cancelledIndex)) wrong++;
                if (r > 0) ran++;
            }
            final int pendingNow = ownPending();
            final BukkitTask probe = scheduler.runTaskLater(this.plugin, () -> { }, 1L);
            final boolean probeAdmitted = !probe.isCancelled();
            this.plugin.check(ran == runsExpected && duplicates == 0 && wrong == 0 && pendingNow == baseline
                    && probeAdmitted == (baseline < limit),
                "LEGACY_LIMIT_PENDING_RELEASED", "ran=" + ran + " expected=" + runsExpected + " duplicates=" + duplicates
                    + " rejectedOrCancelledRan=" + wrong + " pending=" + pendingNow + " baseline=" + baseline
                    + " probeAdmitted=" + probeAdmitted);
        }, after(4000));
    }

    /** Index in schedule order of {@code task} (accepted and rejected interleave by task ID). */
    private static int indexOf(final BukkitTask task, final List<BukkitTask> accepted, final List<BukkitTask> rejected) {
        final List<BukkitTask> all = new ArrayList<>(accepted);
        all.addAll(rejected);
        all.sort(java.util.Comparator.comparingInt(BukkitTask::getTaskId));
        return all.indexOf(task);
    }

    private static int[] ran(final AtomicIntegerArray runs) {
        int ran = 0;
        int duplicates = 0;
        for (int i = 0; i < runs.length(); i++) {
            if (runs.get(i) > 0) ran++;
            if (runs.get(i) > 1) duplicates++;
        }
        return new int[] {ran, duplicates};
    }

    /**
     * Running-async limit {@code limit} ({@code 0} = unlimited). Starts {@code limit + 3} async bodies that block
     * on a latch: exactly {@code limit} run, the rest are cancelled when their callback starts and never run.
     * Needs at least {@code limit + 3} BRIDGE_IO lane threads ({@code aurora.scheduler.bridge-io-threads}), or the
     * lane, not the limit, serializes them. Then: the slots are released when the bodies end (a new async task
     * runs), and a repeating async timer whose bodies overlap past the limit is cancelled (a rejected repeater
     * loses its future runs).
     */
    private void asyncLimit(final int limit) {
        final BukkitScheduler scheduler = scheduler();
        final int count = limit == 0 ? 6 : limit + 3;
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicIntegerArray started = new AtomicIntegerArray(count);
        final AtomicInteger finished = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final List<BukkitTask> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            tasks.add(scheduler.runTaskAsynchronously(this.plugin, () -> {
                started.incrementAndGet(index);
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                    finished.incrementAndGet();
                }
            }));
        }
        CompletableFuture.runAsync(() -> {
            int ran = 0;
            int rejectedClean = 0;
            for (int i = 0; i < count; i++) {
                if (started.get(i) > 0) ran++;
                else if (tasks.get(i).isCancelled() && !scheduler.isQueued(tasks.get(i).getTaskId())) rejectedClean++;
            }
            final int expectedRan = limit == 0 ? count : limit;
            final String marker = limit == 0 ? "LEGACY_LIMIT_ASYNC_UNLIMITED" : "LEGACY_LIMIT_ASYNC";
            this.plugin.check(ran == expectedRan && rejectedClean == count - expectedRan && peak.get() == expectedRan,
                marker, "limit=" + limit + " started=" + ran + " rejected=" + rejectedClean + " peak=" + peak.get());
            gate.countDown();
            if (limit == 0) return;
            CompletableFuture.runAsync(() -> {
                final AtomicInteger probeRuns = new AtomicInteger();
                final int finishedNow = finished.get();
                final int pendingNow = ownPending();
                scheduler.runTaskAsynchronously(this.plugin, probeRuns::incrementAndGet);
                CompletableFuture.runAsync(() -> {
                    this.plugin.check(finishedNow == expectedRan && probeRuns.get() == 1 && pendingNow == 0,
                        "LEGACY_LIMIT_ASYNC_RELEASED", "finished=" + finishedNow + " probeRuns=" + probeRuns.get()
                            + " pendingBeforeProbe=" + pendingNow);
                    overlappingTimer(limit);
                }, after(1000));
            }, after(1000));
        }, after(1500));
    }

    /** A repeating async timer whose body (300 ms) outlives its period (1 tick) overlaps past the limit. */
    private void overlappingTimer(final int limit) {
        final AtomicInteger runs = new AtomicInteger();
        final BukkitTask timer = scheduler().runTaskTimerAsynchronously(this.plugin, () -> {
            runs.incrementAndGet();
            try {
                Thread.sleep(300L);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, 1L, 1L);
        CompletableFuture.runAsync(() -> {
            final int first = runs.get();
            CompletableFuture.runAsync(() -> this.plugin.check(timer.isCancelled() && !scheduler().isQueued(timer.getTaskId())
                    && runs.get() == first && first >= limit && ownPending() == 0, "LEGACY_LIMIT_ASYNC_TIMER",
                "limit=" + limit + " runs=" + first + "->" + runs.get() + " cancelled=" + timer.isCancelled()
                    + " pending=" + ownPending()), after(1500));
        }, after(2000));
    }

    /** {@code hold <n> <delayTicks>}: n one-shot sync tasks kept for {@code held}. */
    private void hold(final int count, final long delay) {
        final AtomicIntegerArray runs = new AtomicIntegerArray(count);
        final List<BukkitTask> tasks = new ArrayList<>();
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            final int index = i;
            final BukkitTask task = scheduler().runTaskLater(this.plugin, () -> runs.incrementAndGet(index), delay);
            if (task.isCancelled()) rejected++;
            tasks.add(task);
        }
        this.heldRuns = runs;
        this.held = List.copyOf(tasks);
        this.plugin.getLogger().info("LEGACY_HOLD accepted=" + (count - rejected) + " rejected=" + rejected
            + " delayTicks=" + delay + " pending=" + ownPending());
    }

    /** Reports the {@code hold} batch: still queued, cancelled, ran, and any body that ran twice. */
    private void held() {
        final List<BukkitTask> tasks = this.held;
        final AtomicIntegerArray runs = this.heldRuns;
        int queued = 0;
        int cancelled = 0;
        final int[] ran = ran(runs);
        for (final BukkitTask task : tasks) {
            if (scheduler().isQueued(task.getTaskId())) queued++;
            if (task.isCancelled()) cancelled++;
        }
        this.plugin.getLogger().info("LEGACY_HELD total=" + tasks.size() + " queued=" + queued + " cancelled=" + cancelled
            + " ran=" + ran[0] + " duplicates=" + ran[1] + " pending=" + ownPending());
    }

    private void release() {
        int released = 0;
        for (final BukkitTask task : this.held) {
            if (!task.isCancelled() && scheduler().isQueued(task.getTaskId())) {
                task.cancel();
                released++;
            }
        }
        this.plugin.getLogger().info("LEGACY_RELEASED cancelled=" + released + " pending=" + ownPending());
    }

    /** {@code busy <n> <seconds>}: n async bodies that sleep; lowering a limit must not interrupt them. */
    private void busy(final int count, final int seconds) {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            scheduler().runTaskAsynchronously(this.plugin, () -> {
                started.incrementAndGet();
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (final InterruptedException stopped) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
                if (completed.incrementAndGet() == count) {
                    this.plugin.getLogger().info("LEGACY_BUSY_DONE completed=" + completed.get()
                        + " interrupted=" + interrupted.get());
                }
            });
        }
        CompletableFuture.runAsync(() -> this.plugin.getLogger().info("LEGACY_BUSY_STARTED started=" + started.get()
            + " of " + count), after(1000));
    }

    /** A legacy runTask from region context: where {@code sync-route} sends it (LIVE). */
    private void route() {
        final org.bukkit.World world = this.plugin.getServer().getWorlds().getFirst();
        Bukkit.getRegionScheduler().execute(this.plugin, world, 0, 0, () -> scheduler().runTask(this.plugin, () ->
            this.plugin.getLogger().info("LEGACY_ROUTE global=" + Bukkit.isGlobalTickThread() + " owned="
                + Bukkit.isOwnedByCurrentRegion(world, 0, 0) + " thread=" + Thread.currentThread().getName())));
    }
}
