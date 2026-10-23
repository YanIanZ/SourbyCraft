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

    /** World names currently loaded through this port that belong to no active profile. */
    default java.util.Set<String> loadedWorlds() {
        return java.util.Set.of();
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
