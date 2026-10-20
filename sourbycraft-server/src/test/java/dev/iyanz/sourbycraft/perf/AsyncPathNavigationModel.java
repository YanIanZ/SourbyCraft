package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.execution.Admission;
import dev.iyanz.sourbycraft.execution.OwnerHandoff;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A stand-in for the part of {@code PathNavigation} that minecraft feature patch 0006 adds
 * ({@code sourbyKickAsyncRecompute} and the periodic-recompute guard), written against the real
 * {@link AsyncPathProcessor}, {@link AsyncPathCompletion} and {@link AsyncPathValidity}.
 *
 * <p>The patched lambda itself cannot run here: it lives in {@code net.minecraft} code that needs a
 * bootstrapped server and a real {@code Mob}. So the pieces it is built from are the real ones and
 * the glue is mirrored line by line; {@link AsyncPathPatchShapeTest} pins the patch text so that
 * this mirror cannot drift silently. Nothing here claims to exercise the patch's own bytecode.</p>
 */
final class AsyncPathNavigationModel {

    /** What a solve returns: only the target matters to the apply rule. */
    record Route(Object target) {
    }

    /** The mob this navigation belongs to; identity is what {@link AsyncPathValidity#sameSubject} compares. */
    final Object mob = new Object();

    /** {@code PathNavigation.path}: replaced, never mutated, whenever a result would stop being valid. */
    volatile Route path;
    /** {@code PathNavigation.targetPos}. */
    volatile Object targetPos;
    /** {@code PathNavigation.sourbyAsyncPathPending}. */
    final AtomicBoolean pending = new AtomicBoolean();
    /** How many results were applied. */
    final AtomicInteger applied = new AtomicInteger();
    /** Released once for every execution of the {@code release} callback. */
    private final Semaphore releases = new Semaphore(0);
    final AtomicInteger releaseCount = new AtomicInteger();

    AsyncPathNavigationModel(final Route current, final Object target) {
        this.path = current;
        this.targetPos = target;
    }

    /**
     * The periodic recompute, mirroring {@code recomputePath}: offload only when a live path exists and
     * no solve is pending, otherwise run the synchronous recompute, which replaces the path.
     *
     * @return true if the offload was started
     */
    boolean recompute(final Supplier<Route> solve, final OwnerHandoff<Object> handoff,
                      final Supplier<Route> synchronousSolve) {
        if (AsyncPathProcessor.isEnabled() && this.path != null && !this.pending.get()
            && this.kick(solve, handoff)) {
            return true;
        }
        this.path = synchronousSolve.get();
        if (this.path != null) {
            this.targetPos = this.path.target();
        }
        return false;
    }

    /** Mirrors {@code sourbyKickAsyncRecompute} from the capacity check onward. */
    boolean kick(final Supplier<Route> solve, final OwnerHandoff<Object> handoff) {
        if (!AsyncPathProcessor.hasCapacity()) {
            return false;
        }
        final Route requested = this.path;
        this.pending.set(true);
        final CompletableFuture<Route> solving = AsyncPathProcessor.trySubmit(solve);
        if (solving == null) {
            this.pending.set(false);
            return false;
        }
        solving.whenComplete((result, err) -> AsyncPathCompletion.deliver(handoff, owner -> {
            if (!AsyncPathValidity.sameSubject(owner, this.mob)) {
                return;
            }
            if (!AsyncPathValidity.stillRequested(this.path, requested)) {
                return;
            }
            if (result != null && result.target() != null) {
                this.path = result;
                this.targetPos = result.target();
                this.applied.incrementAndGet();
            }
        }, () -> {
            this.pending.set(false);
            this.releaseCount.incrementAndGet();
            this.releases.release();
        }));
        return true;
    }

    /** Blocks until {@code count} more releases happened; false on timeout. */
    boolean awaitReleases(final int count) throws InterruptedException {
        return this.releases.tryAcquire(count, 10, TimeUnit.SECONDS);
    }

    /**
     * An owner whose "next owning tick" is driven by the test: nothing is delivered until {@link #tick()},
     * and {@link #retire()} behaves like {@code EntityScheduler.retire()} (queued work gets the retirement
     * callback, later submissions are refused).
     */
    static final class ManualOwner implements OwnerHandoff<Object> {
        private record Queued(Consumer<? super Object> delivery, Runnable retirement) {
        }

        private final ArrayDeque<Queued> queue = new ArrayDeque<>();
        private final Semaphore submissions = new Semaphore(0);
        private boolean retired;
        private Object current;
        int rejected;

        ManualOwner(final Object current) {
            this.current = current;
        }

        /** A dimension transfer: the live owner is now a different object. */
        synchronized void replaceOwner(final Object replacement) {
            this.current = replacement;
        }

        @Override
        public Admission submit(final Consumer<? super Object> delivery, final Runnable retirement) {
            try {
                synchronized (this) {
                    if (this.retired) {
                        this.rejected++;
                        return Admission.REJECTED;
                    }
                    this.queue.add(new Queued(delivery, retirement));
                    return Admission.ACCEPTED;
                }
            } finally {
                this.submissions.release();
            }
        }

        /** Blocks until the solve behind a submission reached this owner (accepted or refused). */
        boolean awaitSubmission() throws InterruptedException {
            return this.submissions.tryAcquire(10, TimeUnit.SECONDS);
        }

        /** The next owning tick: runs everything queued, with the owner as it is now. */
        void tick() {
            final ArrayDeque<Queued> batch;
            final Object owner;
            synchronized (this) {
                batch = new ArrayDeque<>(this.queue);
                this.queue.clear();
                owner = this.current;
            }
            batch.forEach(q -> q.delivery().accept(owner));
        }

        /** The owner is gone (entity removed, world unloaded): queued work is retired, not delivered. */
        void retire() {
            final ArrayDeque<Queued> batch;
            synchronized (this) {
                this.retired = true;
                batch = new ArrayDeque<>(this.queue);
                this.queue.clear();
            }
            batch.forEach(q -> q.retirement().run());
        }

        synchronized int queued() {
            return this.queue.size();
        }
    }
}
