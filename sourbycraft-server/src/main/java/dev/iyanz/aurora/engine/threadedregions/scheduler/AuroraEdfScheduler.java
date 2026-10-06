// SPDX-License-Identifier: GPL-3.0-only
// Derived from Tuinity/LeafPile v1.2.0, commit 8c9fa2c5b8ac063075977efc048f6ec0299f24da.
// Original EDF implementation by Spottedleaf and Tuinity/LeafPile contributors.
package dev.iyanz.aurora.engine.threadedregions.scheduler;

import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;
import ca.spottedleaf.concurrentutil.scheduler.Scheduler;

import ca.spottedleaf.concurrentutil.set.LinkedSortedSet;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.concurrentutil.util.LazyRunnable;
import ca.spottedleaf.common.util.TimeUtil;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * LeafPile-derived EDF backend with SourbyCraft cancellation and handoff fixes.
 * This retains the original scheduling/parking policy; it is not an independent scheduling design.
 * <p>
 *     Intermediate task execution is not supported in this scheduler.
 * </p>
 * <p>
 *     NUMA aware scheduling is not supported in this scheduler.
 * </p>
 */
public final class AuroraEdfScheduler extends Scheduler {

    private static final Comparator<ScheduledState> TICK_COMPARATOR_BY_TIME = (final ScheduledState s1, final ScheduledState s2) -> {
        final SchedulableTick t1 = s1.tick;
        final SchedulableTick t2 = s2.tick;

        final int timeCompare = TimeUtil.compareTimes(t1.scheduledStart, t2.scheduledStart);
        if (timeCompare != 0) {
            return timeCompare;
        }

        return Long.signum(t1.id - t2.id);
    };

    private final TickThreadRunner[] runners;
    private final Thread[] threads;
    private final LinkedSortedSet<ScheduledState> awaiting = new LinkedSortedSet<>(TICK_COMPARATOR_BY_TIME);
    private final PriorityQueue<ScheduledState> queued = new PriorityQueue<>(TICK_COMPARATOR_BY_TIME);
    private final BitSet idleThreads;

    private final Object scheduleLock = new Object();

    private volatile boolean halted;

    /**
     * Creates, but does not start, a scheduler thread pool with the specified number of threads
     * created using the specified thread factory.
     * @param threads Specified number of threads
     * @param threadFactory Specified thread factory
     * @see #start()
     */
    public AuroraEdfScheduler(final int threads, final ThreadFactory threadFactory) {
        if (threads <= 0) throw new IllegalArgumentException("EDF thread count must be positive");
        final BitSet idleThreads = new BitSet(threads);
        for (int i = 0; i < threads; ++i) {
            idleThreads.set(i);
        }
        this.idleThreads = idleThreads;

        final TickThreadRunner[] runners = new TickThreadRunner[threads];
        final Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; ++i) {
            final LazyRunnable run = new LazyRunnable();
            final Thread thread = t[i] = threadFactory.newThread(run);

            run.setRunnable(runners[i] = new TickThreadRunner(thread, i, this));
        }

        this.threads = t;
        this.runners = runners;
    }

    /**
     * Starts the threads in this pool.
     */
    public void start() {
        for (final Thread thread : this.threads) {
            thread.start();
        }
    }

    @Override
    public void halt() {
        synchronized (this.scheduleLock) {
            this.halted = true;
        }
        for (final Thread thread : this.threads) {
            // force response to halt
            LockSupport.unpark(thread);
        }
    }

    @Override
    public boolean join(final long msToWait) {
        try {
            return this.join(msToWait, false);
        } catch (final InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public boolean joinInterruptable(final long msToWait) throws InterruptedException {
        return this.join(msToWait, true);
    }

    private boolean join(final long msToWait, final boolean interruptable) throws InterruptedException {
        final long nsToWait = TimeUnit.MILLISECONDS.toNanos(msToWait);
        final long start = System.nanoTime();
        final long deadline = start + nsToWait;
        boolean interrupted = false;
        try {
            for (final Thread thread : this.threads) {
                while (thread.isAlive()) {
                    try {
                        if (msToWait > 0L) {
                            final long current = System.nanoTime();
                            if (current - deadline >= 0L) {
                                return false;
                            }
                            thread.join(Duration.ofNanos(deadline - current));
                        } else {
                            thread.join();
                        }
                    } catch (final InterruptedException ex) {
                        if (interruptable) {
                            throw ex;
                        }
                        interrupted = true;
                    }
                }
            }

            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns an array of the underlying scheduling threads.
     */
    public Thread[] getThreads() {
        return this.threads.clone();
    }

    @Override
    public Thread[] getCoreThreads() {
        return this.getThreads();
    }

    @Override
    public Thread[] getAliveThreads() {
        final List<Thread> ret = new ArrayList<>(this.threads.length);
        for (final Thread thread : this.threads) {
            if (thread.isAlive()) {
                ret.add(thread);
            }
        }

        return ret.toArray(new Thread[0]);
    }

    private void insertFresh(final ScheduledState task) {
        final TickThreadRunner[] runners = this.runners;

        final int firstIdleThread = this.idleThreads.nextSetBit(0);

        if (firstIdleThread != -1) {
            // push to idle thread
            this.idleThreads.clear(firstIdleThread);
            final TickThreadRunner runner = runners[firstIdleThread];
            task.awaitingLink = this.awaiting.addLast(task);
            runner.acceptTask(task);
            return;
        }

        // try to replace the last awaiting task
        final ScheduledState last = this.awaiting.last();

        if (last != null && TICK_COMPARATOR_BY_TIME.compare(task, last) < 0) {
            // need to replace the last task
            this.awaiting.pollLast();
            last.awaitingLink = null;
            task.awaitingLink = this.awaiting.addLast(task);
            // need to add task to queue to be picked up later
            this.queued.add(last);

            final TickThreadRunner runner = last.ownedBy;
            runner.replaceTask(task);

            return;
        }

        // add to queue, will be picked up later
        this.queued.add(task);
    }

    private void takeTask(final TickThreadRunner runner, final ScheduledState tick) {
        if (!this.awaiting.remove(tick.awaitingLink)) {
            throw new IllegalStateException("Task is not in awaiting");
        }
        tick.awaitingLink = null;
    }

    private ScheduledState returnTask(final TickThreadRunner runner, final ScheduledState reschedule) {
        if (reschedule != null) {
            this.queued.add(reschedule);
        }
        final ScheduledState ret = this.pollAwaitingTask();
        if (ret == null) {
            this.idleThreads.set(runner.id);
        }

        return ret;
    }

    // All queued -> runner transfers must register the task in awaiting under scheduleLock.
    private ScheduledState pollAwaitingTask() {
        final ScheduledState task = this.queued.poll();
        if (task != null) task.awaitingLink = this.awaiting.addLast(task);
        return task;
    }

    @Override
    public void schedule(final SchedulableTick task) {
        synchronized (this.scheduleLock) {
            if (this.halted) throw new IllegalStateException("EDF scheduler is halted");
            if (task.getScheduledStart() == TimeUtil.DEADLINE_NOT_SET) {
                throw new IllegalStateException("Start must be set when scheduling");
            }
            final ScheduledState state = new ScheduledState(task, this);
            if (!task.setState(state)) {
                throw new IllegalStateException("Task " + task + " is already scheduled or cancelled");
            }

            if (!state.tryMarkScheduled()) {
                throw new IllegalStateException();
            }

            this.insertFresh(state);
        }
    }

    /**
     * Updates the tasks scheduled start to the maximum of its current scheduled start and the specified
     * new start. If the task is not scheduled, returns {@code false}. Otherwise, returns whether the
     * scheduled start was updated. Tasks owned by another executor, cancelled tasks and halted schedulers return false.
     * @param tick Specified task
     * @param newStart Specified new start
     */
    public boolean updateTickStartToMax(final SchedulableTick tick, final long newStart) {
        if (!(tick.state instanceof ScheduledState task)) return false;
        synchronized (this.scheduleLock) {
            if (this.halted || task.schedulerOwnedBy != this || !task.isScheduled()) return false;
            if (TimeUtil.compareTimes(newStart, task.tick.getScheduledStart()) <= 0) {
                return false;
            }
            if (this.queued.remove(task)) {
                task.tick.setScheduledStart(newStart);
                this.queued.add(task);
                return true;
            }
            if (task.awaitingLink != null) {
                this.awaiting.remove(task.awaitingLink);
                task.awaitingLink = null;

                // re-queue task
                task.tick.setScheduledStart(newStart);
                this.queued.add(task);

                // now we need to replace the task the runner was waiting for
                final TickThreadRunner runner = task.ownedBy;
                final ScheduledState replace = this.pollAwaitingTask();

                // replace cannot be null, since we have added a task to queued
                // Rearm even if it is the same task: the runner cached the previous deadline.
                runner.replaceTask(replace);

                return true;
            }

            return false;
        }
    }

    @Override
    public boolean cancel(final SchedulableTick task) {
        if (!(task.state instanceof ScheduledState state)) {
            return false;
        }

        if (state.schedulerOwnedBy != this) {
            return false;
        }

        synchronized (this.scheduleLock) {
            if (!state.isScheduled()) return false;
            if (this.queued.remove(state)) {
                // cancelled, and no runner owns it - so return
                return state.tryMarkCancelled();
            }
            if (state.awaitingLink != null) {
                state.tryMarkCancelled();
                this.awaiting.remove(state.awaitingLink);
                state.awaitingLink = null;
                // here we need to replace the task the runner was waiting for
                final TickThreadRunner runner = state.ownedBy;
                final ScheduledState replace = this.pollAwaitingTask();

                if (replace == null) {
                    // nothing to replace with, set to idle
                    this.idleThreads.set(runner.id);
                    runner.forceIdle();
                } else {
                    runner.replaceTask(replace);
                }

                return true;
            }

            // A running callback may finish, but cancellation fences its next reschedule.
            return state.ownedBy != null && state.tryMarkCancelled();
        }
    }

    @Override
    public void notifyTasks(final SchedulableTick task) {
        // Not implemented
    }

    private static final class ScheduledState {
        private final SchedulableTick tick;

        private static final int SCHEDULE_STATE_NOT_SCHEDULED = 0;
        private static final int SCHEDULE_STATE_SCHEDULED = 1;
        private static final int SCHEDULE_STATE_CANCELLED = 2;

        private final AtomicInteger scheduled = new AtomicInteger();
        private final AuroraEdfScheduler schedulerOwnedBy;
        private TickThreadRunner ownedBy;

        private LinkedSortedSet.Link<ScheduledState> awaitingLink;

        private ScheduledState(final SchedulableTick tick, final AuroraEdfScheduler scheduler) {
            this.tick = tick;
            this.schedulerOwnedBy = scheduler;
        }

        private boolean tryMarkScheduled() {
            return this.scheduled.compareAndSet(SCHEDULE_STATE_NOT_SCHEDULED, SCHEDULE_STATE_SCHEDULED);
        }

        private boolean tryMarkCancelled() {
            return this.scheduled.compareAndSet(SCHEDULE_STATE_SCHEDULED, SCHEDULE_STATE_CANCELLED);
        }

        private boolean isScheduled() {
            return this.scheduled.get() == SCHEDULE_STATE_SCHEDULED;
        }
    }

    private static final class TickThreadRunner implements Runnable {

        /**
         * There are no tasks in this thread's runqueue, so it is parked.
         * <p>
         * stateTarget = null
         * </p>
         */
        private static final int STATE_IDLE = 0;

        /**
         * The runner is waiting to tick a task, as it has no intermediate tasks to execute.
         * <p>
         * stateTarget = the task awaiting tick
         * </p>
         */
        private static final int STATE_AWAITING_TICK = 1;

        /**
         * The runner is executing a tick for one of the tasks that was in its runqueue.
         * <p>
         * stateTarget = the task being ticked
         * </p>
         */
        private static final int STATE_EXECUTING_TICK = 2;

        private final Thread thread;
        public final int id;
        public final AuroraEdfScheduler scheduler;

        private volatile TickThreadRunnerState state = new TickThreadRunnerState(null, STATE_IDLE);
        private static final VarHandle STATE_HANDLE = ConcurrentUtil.getVarHandle(TickThreadRunner.class, "state", TickThreadRunnerState.class);

        private void setStatePlain(final TickThreadRunnerState state) {
            STATE_HANDLE.set(this, state);
        }

        private void setStateVolatile(final TickThreadRunnerState state) {
            STATE_HANDLE.setVolatile(this, state);
        }

        private static record TickThreadRunnerState(ScheduledState stateTarget, int state) {}

        public TickThreadRunner(final Thread thread, final int id, final AuroraEdfScheduler scheduler) {
            this.thread = thread;
            this.id = id;
            this.scheduler = scheduler;
        }

        private Thread getRunnerThread() {
            return this.thread;
        }

        private void acceptTask(final ScheduledState task) {
            if (task.ownedBy != null) {
                throw new IllegalStateException("Already owned by another runner");
            }
            task.ownedBy = this;
            final TickThreadRunnerState state = this.state;
            if (state.state != STATE_IDLE) {
                throw new IllegalStateException("Cannot accept task in state " + state);
            }
            this.setStateVolatile(new TickThreadRunnerState(task, STATE_AWAITING_TICK));
            LockSupport.unpark(this.getRunnerThread());
        }

        private void replaceTask(final ScheduledState task) {
            final TickThreadRunnerState state = this.state;
            if (state.state != STATE_AWAITING_TICK) {
                throw new IllegalStateException("Cannot replace task in state " + state);
            }
            if (task.ownedBy != null && task != state.stateTarget) {
                throw new IllegalStateException("Already owned by another runner");
            }
            state.stateTarget.ownedBy = null;
            task.ownedBy = this;

            this.setStateVolatile(new TickThreadRunnerState(task, STATE_AWAITING_TICK));
            LockSupport.unpark(this.getRunnerThread());
        }

        private void forceIdle() {
            final TickThreadRunnerState state = this.state;
            if (state.state != STATE_AWAITING_TICK) {
                throw new IllegalStateException("Cannot replace task in state " + state);
            }
            state.stateTarget.ownedBy = null;
            this.setStateVolatile(new TickThreadRunnerState(null, STATE_IDLE));
            // Release the previous task's timed-park blocker instead of retaining it until its deadline.
            LockSupport.unpark(this.getRunnerThread());
        }

        private boolean takeTask(final TickThreadRunnerState state, final ScheduledState task) {
            synchronized (this.scheduler.scheduleLock) {
                if (this.scheduler.halted || this.state != state) {
                    return false;
                }
                this.setStatePlain(new TickThreadRunnerState(task, STATE_EXECUTING_TICK));
                this.scheduler.takeTask(this, task);
                return true;
            }
        }

        private void returnTask(final ScheduledState task, final boolean reschedule) {
            synchronized (this.scheduler.scheduleLock) {
                task.ownedBy = null;
                if (!reschedule) task.tryMarkCancelled(); // completed callbacks are terminal too

                final ScheduledState newWait = this.scheduler.returnTask(this, reschedule && task.isScheduled() ? task : null);
                if (newWait == null) {
                    this.setStatePlain(new TickThreadRunnerState(null, STATE_IDLE));
                } else {
                    if (newWait.ownedBy != null) {
                        throw new IllegalStateException("Already owned by another runner");
                    }
                    newWait.ownedBy = this;
                    this.setStatePlain(new TickThreadRunnerState(newWait, STATE_AWAITING_TICK));
                }
            }
        }

        @Override
        public void run() {
            main_state_loop:
            for (;;) {
                final TickThreadRunnerState startState = this.state;
                final int startStateType = startState.state;
                final ScheduledState startStateTask =  startState.stateTarget;

                if (this.scheduler.halted) {
                    return;
                }

                switch (startStateType) {
                    case STATE_IDLE: {
                        while (this.state.state == STATE_IDLE) {
                            Thread.interrupted();
                            LockSupport.park(this.scheduler); // distinguish idle waiting from a task deadline
                            if (this.scheduler.halted) {
                                return;
                            }
                        }
                        continue main_state_loop;
                    }

                    case STATE_AWAITING_TICK: {
                        final long deadline = startStateTask.tick.getScheduledStart();
                        for (;;) {
                            if (this.state != startState) {
                                continue main_state_loop;
                            }
                            Thread.interrupted();
                            final long diff = deadline - System.nanoTime();
                            if (diff <= 0L) {
                                break;
                            }
                            LockSupport.parkNanos(startState, diff);
                            if (this.scheduler.halted) {
                                return;
                            }
                        }

                        if (!this.takeTask(startState, startStateTask)) {
                            continue main_state_loop;
                        }

                        // TODO exception handling
                        final boolean reschedule = startStateTask.tick.runTick();

                        this.returnTask(startStateTask, reschedule);

                        continue main_state_loop;
                    }

                    case STATE_EXECUTING_TICK: {
                        throw new IllegalStateException("Tick execution must be set by runner thread, not by any other thread");
                    }

                    default: {
                        throw new IllegalStateException("Unknown state: " + startState);
                    }
                }
            }
        }
    }
}
