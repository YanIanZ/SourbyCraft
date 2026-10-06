package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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

    private final Map<String, ResourceState> active = new HashMap<>();

    final class Lease implements AutoCloseable {
        private final String operation;
        private final List<Resource> resources;
        private boolean closed;

        private Lease(final String operation, final List<Resource> resources) {
            this.operation = operation;
            this.resources = resources;
        }

        @Override
        public void close() {
            synchronized (WorldOperationGate.this) {
                if (this.closed) return;
                this.closed = true;
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
            requested.entrySet().stream().map(e -> new Resource(e.getKey(), e.getValue())).toList());
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
        final CompletableFuture<T> result = new CompletableFuture<>();
        source.whenComplete((value, failed) -> {
            lease.close();
            if (failed == null) result.complete(value);
            else result.completeExceptionally(failed);
        });
        return result;
    }
}
