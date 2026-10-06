package dev.iyanz.sourbycraft.awf.world;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A save fails only after every admitted region has finished, keeping lifecycle admission held. */
final class WorldSaveBarrier {
    private final AtomicInteger outstanding = new AtomicInteger(1); // fan-out itself
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    void admitted() {
        this.outstanding.incrementAndGet();
    }

    void failed(final Throwable thrown) {
        this.failure.compareAndSet(null, thrown);
    }

    void finished() {
        if (this.outstanding.decrementAndGet() != 0) return;
        final Throwable thrown = this.failure.get();
        if (thrown == null) this.completion.complete(null);
        else this.completion.completeExceptionally(thrown);
    }

    CompletableFuture<Void> completion() {
        return this.completion;
    }
}
