package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Service-owned admission for world lifecycle work. The monitor protects only reservations;
 * no I/O, scheduling or plugin callback runs under it. Conflicts fail without a queue or wait.
 * Templates can have many readers (parallel clones), but deletion needs exclusive ownership.
 */
final class WorldOperationGate {
    record Resource(String key, boolean shared) {
        Resource {
            Objects.requireNonNull(key, "key");
        }
    }

    private static final class ResourceState {
        final Map<Lease, Boolean> owners = new HashMap<>();
        Lease writer;
    }

    /** How long a lease may be held before {@link #overdue} reports it. */
    static final long OVERDUE_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Map<String, ResourceState> active = new HashMap<>();
    /** Every open lease, for {@link #overdue}. */
    private final java.util.Set<Lease> open = new java.util.LinkedHashSet<>();
    /** True on the thread an operation's completion may still need (the global tick). */
    private final BooleanSupplier waitForbidden;
    private final java.util.function.LongSupplier nanoClock;

    WorldOperationGate() {
        this(() -> false);
    }

    /**
     * @param waitForbidden true on a thread that must not block on an unfinished operation,
     *     because finishing it needs that thread: {@code join}/{@code get} there throw instead
     */
    WorldOperationGate(final BooleanSupplier waitForbidden) {
        this(waitForbidden, System::nanoTime);
    }

    WorldOperationGate(final BooleanSupplier waitForbidden, final java.util.function.LongSupplier nanoClock) {
        this.waitForbidden = Objects.requireNonNull(waitForbidden, "waitForbidden");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Leases held longer than {@code thresholdNanos} and not reported before, described for a
     * log line. Each lease is reported once. An operation whose engine callback never came
     * (a lost save ticket, an unload that died) keeps its world locked against every later
     * operation until restart; this makes that visible instead of silent.
     */
    synchronized List<String> overdue(final long thresholdNanos) {
        final long now = this.nanoClock.getAsLong();
        final List<String> found = new java.util.ArrayList<>();
        for (final Lease lease : this.open) {
            if (lease.reported || now - lease.acquiredNanos < thresholdNanos) continue;
            lease.reported = true;
            found.add(lease.operation + " on " + lease.resources.stream().map(Resource::key).toList() + " for "
                + TimeUnit.NANOSECONDS.toSeconds(now - lease.acquiredNanos) + " s");
        }
        return found;
    }

    /**
     * An operation's future. World creation finishes on a later global tick, so blocking on it on
     * the global tick could never return; {@code join} and {@code get} refuse that with an
     * {@link IllegalStateException} instead of hanging the server. Futures derived from it
     * ({@code thenApply} and the like) refuse the same way; a finished future never refuses.
     */
    static final class OperationFuture<T> extends CompletableFuture<T> {
        private final BooleanSupplier waitForbidden;

        OperationFuture(final BooleanSupplier waitForbidden) {
            this.waitForbidden = waitForbidden;
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new OperationFuture<>(this.waitForbidden);
        }

        private void refuseBlockingHere() {
            if (!isDone() && this.waitForbidden.getAsBoolean()) {
                throw new IllegalStateException("an AWF world operation finishes on the global tick; waiting for it there"
                    + " would never return. Use a completion callback (whenComplete/thenAccept) instead");
            }
        }

        @Override
        public T join() {
            refuseBlockingHere();
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            refuseBlockingHere();
            return super.get();
        }

        @Override
        public T get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            refuseBlockingHere();
            return super.get(timeout, unit);
        }
    }

    final class Lease implements AutoCloseable {
        private final String operation;
        private final List<Resource> resources;
        private final long acquiredNanos;
        private boolean closed;
        private boolean reported;

        private Lease(final String operation, final List<Resource> resources, final long acquiredNanos) {
            this.operation = operation;
            this.resources = resources;
            this.acquiredNanos = acquiredNanos;
        }

        @Override
        public void close() {
            synchronized (WorldOperationGate.this) {
                if (this.closed) return;
                this.closed = true;
                open.remove(this);
                for (final Resource resource : this.resources) {
                    final ResourceState state = active.get(resource.key());
                    state.owners.remove(this);
                    if (state.writer == this) state.writer = null;
                    if (state.owners.isEmpty()) active.remove(resource.key());
                }
            }
        }
    }

    synchronized Lease acquire(final String operation, final List<Resource> resources) {
        // Merge duplicate resources, with exclusive access winning over shared access.
        final Map<String, Boolean> requested = new HashMap<>();
        for (final Resource resource : resources) requested.merge(resource.key(), resource.shared(), (a, b) -> a && b);
        for (final var request : requested.entrySet()) {
            final ResourceState state = this.active.get(request.getKey());
            if (state == null) continue;
            final Lease conflict = request.getValue() ? state.writer : state.owners.keySet().iterator().next();
            if (conflict != null) {
                throw new WorldOperationBusyException(request.getKey(), conflict.operation);
            }
        }
        final Lease lease = new Lease(operation,
            requested.entrySet().stream().map(e -> new Resource(e.getKey(), e.getValue())).toList(),
            this.nanoClock.getAsLong());
        this.open.add(lease);
        for (final Resource resource : lease.resources) {
            final ResourceState state = this.active.computeIfAbsent(resource.key(), ignored -> new ResourceState());
            state.owners.put(lease, resource.shared());
            if (!resource.shared()) state.writer = lease;
        }
        return lease;
    }

    <T> CompletableFuture<T> run(final String operation, final List<Resource> resources,
                                 final Supplier<CompletableFuture<T>> action) {
        final Lease lease;
        try {
            lease = acquire(operation, resources);
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        final CompletableFuture<T> source;
        try {
            source = Objects.requireNonNull(action.get(), "operation future");
        } catch (final Throwable failed) {
            lease.close();
            return CompletableFuture.failedFuture(failed);
        }
        // The caller can cancel/complete its future without releasing ownership while the
        // underlying engine/I/O work is still running. Release before publishing completion.
        final CompletableFuture<T> result = new OperationFuture<>(this.waitForbidden);
        source.whenComplete((value, failed) -> {
            lease.close();
            if (failed == null) result.complete(value);
            else result.completeExceptionally(failed);
        });
        return result;
    }
}
