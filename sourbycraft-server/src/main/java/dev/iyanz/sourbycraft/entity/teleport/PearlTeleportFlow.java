package dev.iyanz.sourbycraft.entity.teleport;

import java.util.function.Consumer;

/** Lifecycle shared by the pearl impact thread and the owner's scheduled teleport. */
final class PearlTeleportFlow {
    interface Attempt<T, E> {
        /** Fires the event; null means cancelled or no destination. */
        T eventDestination();
        boolean valid();
        void transfer(T destination, Consumer<E> completion);
        void finish(E transferred);
    }

    private PearlTeleportFlow() {}

    static void hit(Runnable schedule, Runnable consume) {
        try {
            schedule.run();
        } finally {
            consume.run();
        }
    }

    static <T, E> void attempt(Attempt<T, E> attempt) {
        final T destination = attempt.eventDestination();
        if (destination == null || !attempt.valid()) return;
        attempt.transfer(destination, attempt::finish);
    }

    /** Called exclusively by a successful transfer's completion callback. */
    static void finish(float roll, boolean monsters, boolean peaceful, Runnable spawn, Runnable damage, Runnable sound) {
        if (roll < 0.05F && monsters && !peaceful) spawn.run();
        damage.run();
        sound.run();
    }
}
