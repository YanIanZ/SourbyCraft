package dev.iyanz.sourbycraft.api.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

/**
 * AWF MultiProfile: durable, isolated player profiles backed by Aurora World Fabric worlds.
 *
 * <p>Obtain it from Bukkit's services manager:</p>
 * <pre>{@code
 * AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
 * }</pre>
 *
 * <p>Every operation that persists or moves a player is asynchronous. Completion callbacks have no
 * guaranteed thread; never join a future on a region, entity or global-tick thread. A switch is
 * rejected while another switch for the same player is running, and it is recoverable: an incomplete
 * transition is recorded and can be finished with an operator command or at the player's next join.</p>
 *
 * <p>The service is optional: plugins must handle {@code isEnabled() == false} and a missing service
 * registration, which is what a server without MultiProfile looks like.</p>
 */
@NullMarked
public interface AuroraProfiles {

    /** Whether the feature is enabled and ready. */
    boolean isEnabled();

    /** Creates a profile for the account, or fails with {@link ProfileErrorCode#SLOT_LIMIT}. */
    CompletableFuture<ProfileInfo> createProfile(ProfileRequest request);

    /** Every profile of the account, in creation order. */
    CompletableFuture<List<ProfileInfo>> listProfiles(UUID accountId);

    CompletableFuture<Optional<ProfileInfo>> getProfile(UUID profileId);

    /** Marks the profile deleted and removes its snapshots and bindings; never deletes AWF worlds. */
    CompletableFuture<Boolean> deleteProfile(UUID profileId);

    CompletableFuture<Optional<ProfileInfo>> getActiveProfile(UUID accountId);

    /** Switches the player's active profile; the result carries the reason on failure. */
    CompletableFuture<ProfileSwitchResult> switchProfile(UUID playerId, UUID profileId);

    /** Captures and persists the profile's current state, if its owner is online. */
    CompletableFuture<Void> saveProfile(UUID profileId);

    /**
     * Replaces the complete binding of an inactive, unclaimed profile. Refused during an account
     * transition or for the active/deleted profile. Does not delete or immediately load worlds.
     * The plugin must authorize player requests itself. Default failure preserves compatibility
     * with alternative service implementations built before this API was added.
     */
    default CompletableFuture<Void> bindProfileWorlds(ProfileWorldBindingRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile world binding unavailable"));
    }

    CompletableFuture<List<ProfileWorldRef>> getProfileWorlds(UUID profileId);

    /** Which node owns the profile, if any. */
    CompletableFuture<Optional<ProfileOwner>> resolveProfileOwnership(UUID profileId);

    /** Registers a plugin adapter; replaces an existing adapter with the same id from that plugin. */
    void registerProfileStateAdapter(Plugin plugin, ProfileStateAdapter adapter);

    void unregisterProfileStateAdapter(Plugin plugin, String adapterId);
}
