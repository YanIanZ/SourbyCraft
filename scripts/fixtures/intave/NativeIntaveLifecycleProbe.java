import dev.yanianz.intave.NativeIntave;
import dev.yanianz.intave.NativeIntave.Controller;
import dev.yanianz.intave.NativeIntave.State;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dependency-free probes execute the real lifecycle controller, without a Bukkit/NMS mock. */
public final class NativeIntaveLifecycleProbe {
    static final class Fake implements NativeIntave.Engine {
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean ready = true;
        boolean startFailure, tickFailure, closeFailure;
        int starts, ticks, closes, admissionStops;
        Runnable tick = () -> {};
        Runnable readyCheck = () -> {};
        public void start() {
            starts++;
            if (startFailure) throw new IllegalStateException("start");
        }
        public boolean ready() { readyCheck.run(); return ready; }
        public CompletionStage<Void> initialization() { return completion; }
        public void ownerTick(Object player) {
            ticks++;
            tick.run();
            if (tickFailure) throw new IllegalStateException("tick");
        }
        public void close() {
            closes++;
            if (closeFailure) throw new IllegalStateException("close");
        }
        public void stopAdmission() { admissionStops++; }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void await(CountDownLatch latch) {
        try { check(latch.await(3, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
    }
    static Controller active(Fake engine) {
        engine.completion.complete(null);
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        check(controller.status().active(), "initialized engine must activate");
        return controller;
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "disabled": {
                Controller controller = new Controller();
                controller.start(false, () -> { throw new AssertionError("disabled engine constructed"); });
                check(controller.status().state() == State.DISABLED, "default disabled");
                controller.start(true, () -> { throw new AssertionError("in-process reactivation"); });
                controller.ownerTick(new Object());
                check(!controller.status().active(), "disabled is not active");
                break;
            }
            case "missing": {
                Controller controller = new Controller();
                controller.start(true, () -> null);
                check(controller.status().state() == State.UNAVAILABLE, "missing engine not active");
                controller.close();
                check(controller.status().state() == State.STOPPED, "empty cleanup");
                break;
            }
            case "deferred": {
                Fake engine = new Fake();
                Controller controller = new Controller();
                controller.start(true, () -> engine);
                controller.ownerTick(new Object());
                check(controller.status().state() == State.STARTING && engine.ticks == 0, "startup has no callbacks");
                engine.completion.complete(null);
                controller.ownerTick(new Object());
                check(controller.status().active() && engine.ticks == 1, "completion activates callbacks");
                controller.start(true, () -> { throw new AssertionError("second construction"); });
                controller.close(); controller.close();
                check(engine.starts == 1 && engine.closes == 1, "lifecycle exactly once");
                break;
            }
            case "start-failure": {
                Fake engine = new Fake(); engine.startFailure = true;
                Controller controller = new Controller(); controller.start(true, () -> engine);
                controller.close();
                check(controller.status().state() == State.FAILED && engine.closes == 1, "failed startup cleaned once");
                break;
            }
            case "not-ready": {
                Fake engine = new Fake(); engine.ready = false;
                Controller controller = new Controller(); controller.start(true, () -> engine);
                engine.completion.complete(null);
                check(controller.status().state() == State.FAILED, "no false ready");
                check(engine.closes == 0, "completion must not dispose on a region thread");
                controller.close(); check(engine.closes == 1, "shutdown disposes failed engine");
                break;
            }
            case "deferred-failure": {
                Fake engine = new Fake();
                Controller controller = new Controller(); controller.start(true, () -> engine);
                engine.completion.completeExceptionally(new IllegalStateException("late failure"));
                controller.ownerTick(new Object());
                check(controller.status().state() == State.FAILED && engine.ticks == 0, "failed completion has no protection");
                controller.close(); check(engine.closes == 1, "failed completion cleaned");
                break;
            }
            case "late-completion": {
                Fake engine = new Fake();
                Controller controller = new Controller(); controller.start(true, () -> engine);
                controller.close(); engine.completion.complete(null);
                controller.fail("late callback", new IllegalStateException());
                controller.start(true, () -> { throw new AssertionError("restart after stop"); });
                check(controller.status().state() == State.STOPPED && engine.closes == 1, "stop stays terminal");
                break;
            }
            case "failure-during-ready": {
                Fake engine = new Fake();
                Controller controller = new Controller();
                engine.readyCheck = () -> controller.fail("channel attachment", new IllegalStateException("pipeline absent"));
                controller.start(true, () -> engine);
                engine.completion.complete(null);
                check(controller.status().state() == State.FAILED, "readiness publication must not revive failed initialization");
                controller.close();
                break;
            }
            case "tick-failure": {
                Fake engine = new Fake(); engine.tickFailure = true;
                Controller controller = active(engine);
                controller.ownerTick(new Object()); controller.ownerTick(new Object());
                check(controller.status().state() == State.FAILED && engine.ticks == 1, "callback failure disables admission");
                check(engine.closes == 0, "owner tick never runs blocking cleanup");
                check(engine.admissionStops == 1, "failure must stop native API owner admission as well as controller callbacks");
                controller.close(); check(engine.closes == 1, "shutdown owns cleanup");
                break;
            }
            case "close-failure": {
                Fake engine = new Fake(); engine.closeFailure = true;
                Controller controller = active(engine); controller.close(); controller.close();
                check(controller.status().state() == State.FAILED && engine.closes == 1, "cleanup failure retained, no second disposal");
                break;
            }
            case "drain-owner": {
                Fake engine = new Fake();
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                engine.tick = () -> { entered.countDown(); await(release); };
                Controller controller = active(engine);
                Thread owner = new Thread(() -> controller.ownerTick(new Object())); owner.start(); await(entered);
                Thread closer = new Thread(controller::close); closer.start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (controller.status().state() != State.STOPPING && System.nanoTime() < deadline) Thread.onSpinWait();
                    check(controller.status().state() == State.STOPPING && engine.closes == 0, "resources retained while owner callback runs");
                    controller.ownerTick(new Object()); check(engine.ticks == 1, "no admission during stop");
                } finally { release.countDown(); owner.join(3000); closer.join(3000); }
                check(!owner.isAlive() && !closer.isAlive() && engine.closes == 1, "callback drains before cleanup");
                break;
            }
            case "filter-context": {
                check(NativeIntave.packetFiltersEnabled(), "filter default");
                AtomicBoolean threadDefault = new AtomicBoolean();
                try {
                    NativeIntave.withoutPacketFilters(() -> {
                        check(!NativeIntave.packetFiltersEnabled(), "bypass on Netty write");
                        NativeIntave.withoutPacketFilters(() -> check(!NativeIntave.packetFiltersEnabled(), "nested bypass"));
                        check(!NativeIntave.packetFiltersEnabled(), "restore nested context");
                        Thread other = new Thread(() -> threadDefault.set(NativeIntave.packetFiltersEnabled()));
                        other.start();
                        try { other.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
                        throw new IllegalStateException("write failure");
                    });
                    throw new AssertionError("failure swallowed");
                } catch (IllegalStateException expected) {}
                check(threadDefault.get() && NativeIntave.packetFiltersEnabled(), "context isolated/restored after failure");
                break;
            }
            default: throw new AssertionError(args[0]);
        }
    }
}
