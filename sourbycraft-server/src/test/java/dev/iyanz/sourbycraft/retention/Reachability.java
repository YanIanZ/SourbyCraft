package dev.iyanz.sourbycraft.retention;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * "Is this object still strongly reachable?" for retention tests, without sleeping a fixed time and
 * without reading the referent: the only signal is the {@link ReferenceQueue} a {@link WeakReference}
 * is registered with. The JVM enqueues the reference once the referent is collectable and collected, so
 * an enqueued reference is proof it was reachable from nothing.
 *
 * <p>Usage rules that keep it from being flaky or vacuous:</p>
 * <ul>
 *   <li>Create the tracked object inside a helper method and return only the {@link Tracker}; a local in
 *       the test method itself can keep it alive (interpreter frames, JIT liveness).</li>
 *   <li>Always pair {@link Tracker#assertCollected} with a control, {@link Tracker#assertStillReachable},
 *       taken <em>before</em> the release path runs: it shows the structure under test really did hold the
 *       object, so a pass cannot be an artefact of the object never being held.</li>
 *   <li>Collection is awaited against a deadline ({@value #DEADLINE_SECONDS} s) and polls the queue
 *       with {@code System.gc()} between polls; a timeout is a failure with the structure named.</li>
 * </ul>
 */
public final class Reachability {
    private static final int DEADLINE_SECONDS = 20;

    private Reachability() {
    }

    /** A weak handle on one object plus the queue that tells when it is gone. */
    public static final class Tracker {
        private final String what;
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final WeakReference<Object> reference;

        private Tracker(final String what, final Object referent) {
            this.what = what;
            this.reference = new WeakReference<>(referent, this.queue);
        }

        /**
         * Control: after a few collections the object is still there, because something (the structure
         * under test) holds it. Fails if it vanished, which would make a later "collected" meaningless.
         */
        public void assertStillReachable() {
            for (int i = 0; i < 3; i++) {
                System.gc();
                if (this.queue.poll() != null) {
                    throw new AssertionError(this.what + " was collected while it should still be held");
                }
            }
        }

        /** The release path ran: the object must become collectable within the deadline. */
        public void assertCollected() throws InterruptedException {
            final long deadline = System.nanoTime() + Duration.ofSeconds(DEADLINE_SECONDS).toNanos();
            while (System.nanoTime() < deadline) {
                System.gc();
                final Reference<?> collected = this.queue.remove(100L);
                if (collected == this.reference) {
                    return;
                }
            }
            throw new AssertionError(this.what + " is still strongly reachable " + DEADLINE_SECONDS
                + " s after its release path ran: something retains it");
        }
    }

    /** Tracks {@code referent}; call from a helper that does not keep the object in a local afterwards. */
    public static Tracker track(final String what, final Object referent) {
        return new Tracker(what, referent);
    }

    /** Convenience for several trackers at once. */
    public static void assertAllCollected(final List<Tracker> trackers) throws InterruptedException {
        for (final Tracker tracker : new ArrayList<>(trackers)) {
            tracker.assertCollected();
        }
    }
}
