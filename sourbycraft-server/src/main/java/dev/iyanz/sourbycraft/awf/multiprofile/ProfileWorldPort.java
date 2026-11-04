package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.concurrent.CompletableFuture;

/**
 * Makes a profile's AWF worlds ready, and unloads them when idle.
 *
 * <p>The only implementation in this tree calls the public {@code AuroraWorlds} API, so it inherits
 * the engine's lifecycle rules: creation and loading run on the global tick in stages, conflicts fail
 * with {@code WorldOperationBusyException} instead of queueing, and a world is saved before it is
 * unloaded. The port never calls {@code Bukkit.unloadWorld}.</p>
 */
public interface ProfileWorldPort {

    /** Creates missing worlds from the binding's template and loads every bound world. */
    CompletableFuture<Void> ensureReady(ProfileWorldBinding binding);

    /** Saves and unloads one world; refuses while a player is inside. */
    CompletableFuture<Void> saveAndUnload(String world);

    /** Whether the world is currently loaded. */
    boolean loaded(String world);

    /** Records that the world was used now, for idle unloading. */
    default void touch(final String world) {}

    /**
     * Whether idle unloading may unload the world at all: an AWF world, which is never the server's
     * main world. This default manages none, so a port that does not say otherwise is never asked to
     * unload a world idle.
     */
    default boolean managed(final String world) {
        return false;
    }

    /** Whether a player is in the world right now; non-blocking, callable from any thread. */
    default boolean occupied(final String world) {
        return false;
    }

    /**
     * The spawn point of a loaded world, or {@code null} when it is not loaded (or, in this default,
     * unknown); non-blocking, callable from any thread.
     */
    default ProfileState.Location spawnOf(final String world) {
        return null;
    }

    /** No-op port for a deployment without AWF worlds bound to profiles. */
    ProfileWorldPort NONE = new ProfileWorldPort() {
        @Override
        public CompletableFuture<Void> ensureReady(final ProfileWorldBinding binding) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> saveAndUnload(final String world) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean loaded(final String world) {
            return false;
        }
    };
}
