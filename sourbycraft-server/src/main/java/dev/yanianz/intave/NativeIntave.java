package dev.yanianz.intave;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Server-owned lifecycle boundary. A private engine is compiled in only by explicit build opt-in. */
public final class NativeIntave {
    public enum State { UNAVAILABLE, DISABLED, STARTING, ACTIVE, FAILED, STOPPING, STOPPED }

    public interface Engine extends AutoCloseable {
        void start() throws Exception;
        boolean ready();
        default CompletionStage<Void> initialization() { return CompletableFuture.completedFuture(null); }
        void ownerTick(Object player);
        /** Nonblocking admission gate; must be safe on a Netty or player owner thread. */
        void stopAdmission();
        @Override void close() throws Exception;
    }

    public record Status(State state, String detail) {
        /** Reports initialized hooks, not detection effectiveness or production qualification. */
        public boolean active() { return state == State.ACTIVE; }
    }

    private static final Logger LOGGER = Logger.getLogger("Intave Native");
    private static final Controller CONTROLLER = new Controller();
    private static final ThreadLocal<Boolean> PACKET_FILTERS = ThreadLocal.withInitial(() -> true);
    private NativeIntave() {}

    /** Called after world/plugin initialization. The provider never enters the plugin loader. */
    public static void start() {
        CONTROLLER.start(Boolean.getBoolean("sourbycraft.intave.enabled"), () -> {
            try {
                return (Engine) Class.forName("dev.yanianz.intave.integration.NativeEngineProvider")
                    .getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException absent) {
                return null;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not construct the private native engine", failure);
            }
        });
    }

    public static Status status() { return CONTROLLER.status(); }
    public static void ownerTick(Object player) { CONTROLLER.ownerTick(player); }
    public static void close() { CONTROLLER.close(); }
    public static void packetFailure(Throwable failure) { CONTROLLER.fail("Packet callback", failure); }
    public static boolean packetFiltersEnabled() { return PACKET_FILTERS.get(); }
    /** Invoked on the connection's event loop, after its normal ordered send queue. */
    public static void withoutPacketFilters(Runnable write) {
        boolean previous = PACKET_FILTERS.get();
        PACKET_FILTERS.set(false);
        try { write.run(); }
        finally { PACKET_FILTERS.set(previous); }
    }

    /** Kept independent of Bukkit/NMS so lifecycle and failure behavior can be tested in isolation. */
    public static final class Controller {
        private final AtomicReference<Status> status = new AtomicReference<>(
            new Status(State.UNAVAILABLE, "Private engine has not been started"));
        private volatile Engine engine;
        private boolean attempted;
        private boolean cleanupAttempted;
        private final ReentrantReadWriteLock callbacks = new ReentrantReadWriteLock();

        public Status status() { return this.status.get(); }

        public synchronized void start(boolean enabled, Supplier<Engine> factory) {
            if (this.attempted) return;
            this.attempted = true;
            if (!enabled) {
                this.status.set(new Status(State.DISABLED, "Restart required: -Dsourbycraft.intave.enabled=true"));
                return;
            }
            this.status.set(new Status(State.STARTING, "Initializing private native engine"));
            try {
                this.engine = factory.get();
                if (this.engine == null) {
                    this.status.set(new Status(State.UNAVAILABLE, "Build with -PincludePrivateIntave=true; no engine is bundled"));
                    LOGGER.warning(this.status().detail());
                    return;
                }
                this.engine.start();
                Engine starting = this.engine;
                starting.initialization().whenComplete((unused, failure) -> finishInitialization(starting, failure));
            } catch (Exception | LinkageError failure) {
                this.status.set(new Status(State.FAILED, failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                cleanup();
                LOGGER.log(Level.SEVERE, "Intave native initialization failed; no active protection", failure);
            }
        }

        private synchronized void finishInitialization(Engine starting, Throwable failure) {
            Status startingStatus = this.status();
            if (this.engine != starting || startingStatus.state() != State.STARTING) return;
            try {
                if (failure != null) throw new IllegalStateException("Deferred initialization failed", failure);
                if (!starting.ready()) throw new IllegalStateException("Native engine did not report ready");
                Status active = new Status(State.ACTIVE, "Native lifecycle and packet hooks initialized; effectiveness unverified");
                if (this.status.compareAndSet(startingStatus, active)) LOGGER.info(active.detail());
            } catch (RuntimeException | LinkageError problem) {
                // Completion may run on a region thread. Shutdown owns potentially blocking cleanup.
                fail("Initialization", problem);
            }
        }

        public void ownerTick(Object player) {
            if (!this.status().active() || !this.callbacks.readLock().tryLock()) return;
            try {
                Engine current = this.engine;
                if (!this.status().active() || current == null) return;
                current.ownerTick(Objects.requireNonNull(player));
            } catch (RuntimeException | LinkageError failure) {
                fail("Owner-tick callback", failure);
            } finally {
                this.callbacks.readLock().unlock();
            }
        }

        public void fail(String context, Throwable failure) {
            Status previous = this.status.get();
            while (previous.state() == State.ACTIVE || previous.state() == State.STARTING) {
                if (this.status.compareAndSet(previous, new Status(State.FAILED, context + " failure: " + failure.getMessage()))) {
                    stopAdmission();
                    LOGGER.log(Level.SEVERE, "Intave native callback failed; protection disabled", failure);
                    return;
                }
                previous = this.status.get();
            }
            // Never perform blocking cleanup on Netty/region threads; server shutdown owns it.
        }

        public synchronized void close() {
            if (this.status().state() == State.STOPPED || this.cleanupAttempted) return;
            this.attempted = true;
            this.status.set(new Status(State.STOPPING, "Closing private native engine"));
            String admissionFailure = stopAdmission();
            String failure;
            try {
                if (!this.callbacks.writeLock().tryLock(5, TimeUnit.SECONDS)) {
                    this.status.set(new Status(State.FAILED, "Owner callback did not drain; engine resources retained"));
                    LOGGER.severe(this.status().detail());
                    return;
                }
                try { failure = cleanup(); }
                finally { this.callbacks.writeLock().unlock(); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                this.status.set(new Status(State.FAILED, "Cleanup interrupted; engine resources retained"));
                return;
            }
            if (admissionFailure != null) failure = admissionFailure;
            this.status.set(new Status(failure == null ? State.STOPPED : State.FAILED,
                failure == null ? "Native engine cleanup completed" : "Cleanup failed: " + failure));
        }

        private String stopAdmission() {
            Engine current = this.engine;
            if (current == null) return null;
            try { current.stopAdmission(); return null; }
            catch (RuntimeException | LinkageError failure) {
                LOGGER.log(Level.SEVERE, "Could not stop native owner admission", failure);
                return failure.toString();
            }
        }

        private String cleanup() {
            Engine current = this.engine;
            this.engine = null;
            if (current == null) return null;
            this.cleanupAttempted = true;
            try {
                current.close();
                return null;
            } catch (Exception | LinkageError failure) {
                LOGGER.log(Level.SEVERE, "Intave native cleanup failed", failure);
                return failure.toString();
            }
        }
    }
}
