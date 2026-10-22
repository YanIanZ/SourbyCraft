package dev.iyanz.sourbycraft.testplugin;

import java.util.ArrayList;
import java.util.List;

/**
 * What happened to every task the unload fixture queued, kept apart from the server so the rules can
 * be unit-tested. A task is registered with a kind, then reports whether its body ran, whether its
 * retired callback ran, or (region scheduler tasks, which have no retired callback) whether the
 * scheduler cancelled it. {@link #unloadCompleted()} is the moment the unload callback fired.
 *
 * <p>The rules, from {@code WorldUnloadDrain} (audit ENG-3): work queued on a world that is
 * unloading either ran before the unload completed or was drained (entity tasks retired, region
 * tasks cancelled), never both for a one-shot task, never neither, and never runs after the unload
 * completed.</p>
 */
final class UnloadLedger {

    /** How a task was scheduled; the {@code due} kinds cannot legitimately run before the unload. */
    enum Kind {
        /** Region scheduler delayed far beyond the fixture's lifetime. */
        REGION_DELAYED(true, true),
        /** Region scheduler fixed rate, first run far beyond the fixture's lifetime. */
        REGION_REPEATING(true, false),
        /** Region scheduler {@code run} queued in the same global tick as the unload. */
        REGION_QUEUED(false, true),
        /**
         * Region scheduler {@code execute} (the region's task queue, no handle) queued in the same
         * global tick as the unload. It has no state to read, so a task that never ran is counted as
         * {@code executeNotRun}, to be matched against what the engine says it cancelled.
         */
        REGION_EXECUTE(false, true),
        /** Entity scheduler delayed far beyond the fixture's lifetime. */
        ENTITY_DELAYED(true, true),
        /** Entity scheduler fixed rate, first run far beyond the fixture's lifetime. */
        ENTITY_REPEATING(true, false),
        /** Entity scheduler {@code run} queued in the same global tick as the unload. */
        ENTITY_QUEUED(false, true);

        final boolean farFuture;
        final boolean oneShot;

        Kind(final boolean farFuture, final boolean oneShot) {
            this.farFuture = farFuture;
            this.oneShot = oneShot;
        }

        boolean entity() {
            return this == ENTITY_DELAYED || this == ENTITY_REPEATING || this == ENTITY_QUEUED;
        }
    }

    private static final class Task {
        final Kind kind;
        int ran;
        /** Runs before the unload was requested, while it was in progress, and after it completed. */
        int ranBefore;
        int ranDuring;
        int ranAfterUnload;
        int retired;
        boolean cancelled;

        Task(final Kind kind) {
            this.kind = kind;
        }
    }

    /**
     * Totals over every task. {@code ran} counts tasks whose body ran at least once; {@code
     * ranDuringUnload} those that ran after the unload was requested and before it completed (the
     * drain, or a region's last tick); {@code farFutureRanAtUnload} the subset that was not due for an
     * hour, which can only have run because the unload ran it.
     */
    record Report(int tasks, int ran, int retired, int cancelled, int leaked, int ranAfterUnload,
                  int ranDuringUnload, int farFutureRanAtUnload, int executeNotRun, List<String> wrong) {
        boolean ok() {
            return this.wrong.isEmpty();
        }

        String summary() {
            return "tasks=" + this.tasks + " ran=" + this.ran + " retired=" + this.retired + " cancelled="
                + this.cancelled + " leaked=" + this.leaked + " ranDuringUnload=" + this.ranDuringUnload
                + " farFutureRanAtUnload=" + this.farFutureRanAtUnload + " executeNotRun=" + this.executeNotRun
                + " ranAfterUnload=" + this.ranAfterUnload;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private boolean started;
    private boolean unloaded;

    /** Registers a task before it is scheduled; the id goes to the callbacks. */
    synchronized int register(final Kind kind) {
        this.tasks.add(new Task(kind));
        return this.tasks.size() - 1;
    }

    /** The task body ran. */
    synchronized void ran(final int id) {
        final Task task = this.tasks.get(id);
        task.ran++;
        if (this.unloaded) task.ranAfterUnload++;
        else if (this.started) task.ranDuring++;
        else task.ranBefore++;
    }

    /** The entity scheduler's retired callback ran. */
    synchronized void retired(final int id) {
        this.tasks.get(id).retired++;
    }

    /** A region scheduler task ended in {@code CANCELLED} (read from its {@code ScheduledTask}). */
    synchronized void cancelled(final int id) {
        this.tasks.get(id).cancelled = true;
    }

    /** The unload was requested: what runs from here until {@link #unloadCompleted()} ran during it. */
    synchronized void unloadStarted() {
        this.started = true;
    }

    /** The unload callback fired: nothing may run from here on. */
    synchronized void unloadCompleted() {
        this.unloaded = true;
    }

    synchronized boolean started() {
        return this.started;
    }

    synchronized boolean unloaded() {
        return this.unloaded;
    }

    synchronized int size() {
        return this.tasks.size();
    }

    synchronized int count(final Kind kind) {
        int n = 0;
        for (final Task task : this.tasks) if (task.kind == kind) n++;
        return n;
    }

    /** Tasks whose body has not run (yet): what the unload has to drain. */
    synchronized int pendingNow() {
        int n = 0;
        for (final Task task : this.tasks) if (task.ran == 0) n++;
        return n;
    }

    /** Judges the ledger. Call after the unload completed and the region tasks' states were read. */
    synchronized Report report() {
        final List<String> wrong = new ArrayList<>();
        int ran = 0;
        int retired = 0;
        int cancelled = 0;
        int leaked = 0;
        int after = 0;
        int during = 0;
        int farDuring = 0;
        int executeNotRun = 0;
        for (int id = 0; id < this.tasks.size(); id++) {
            final Task task = this.tasks.get(id);
            final String name = task.kind + "#" + id;
            if (task.ran > 0) ran++;
            if (task.retired > 0) retired++;
            if (task.cancelled) cancelled++;
            if (task.ranDuring > 0) during++;
            if (task.kind.farFuture && task.ranDuring > 0) farDuring++;
            after += task.ranAfterUnload;
            final boolean drained = task.retired > 0 || task.cancelled;
            if (task.kind == Kind.REGION_EXECUTE && task.ran == 0) {
                executeNotRun++;
            } else if (task.ran == 0 && !drained) {
                leaked++;
                wrong.add(name + " leaked: neither ran, retired nor cancelled");
            }
            if (task.ranAfterUnload > 0) wrong.add(name + " ran " + task.ranAfterUnload + "x after the unload completed");
            if (task.retired > 1) wrong.add(name + " retired " + task.retired + "x");
            if (task.kind.farFuture && task.ranBefore > 0) wrong.add(name + " ran before it was due");
            // A far-future task can only end one way: drained, or run by the unload itself.
            if (task.kind.farFuture && !drained && task.ranDuring == 0) {
                wrong.add(name + " was not drained although it could not have run");
            }
            if (task.kind.oneShot && task.ran > 0 && drained) {
                wrong.add(name + " both ran and was " + (task.retired > 0 ? "retired" : "cancelled"));
            }
            if (task.kind.entity() && task.cancelled) wrong.add(name + " is an entity task but was marked cancelled");
            if (!task.kind.entity() && task.retired > 0) wrong.add(name + " is a region task but has a retired callback");
        }
        return new Report(this.tasks.size(), ran, retired, cancelled, leaked, after, during, farDuring, executeNotRun, List.copyOf(wrong));
    }
}
