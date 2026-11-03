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
 * <p>Co-op profiles (several players sharing a profile) are <b>not available</b> in this API; they are
 * planned for a later build. Build 51 adds the profile's immutable name and policy type
 * ({@link ProfileDetails}, {@link ProfileType}), which are stored but not yet used to restrict any
 * gameplay.</p>
 *
 * <p>The service is optional: plugins must handle {@code isEnabled() == false} and a missing service
 * registration, which is what a server without MultiProfile looks like.</p>
 */
@NullMarked
public interface AuroraProfiles {

    /** Whether the feature is enabled and ready. */
    boolean isEnabled();

    /**
     * Creates a profile for the account. Fails with {@link ProfileErrorCode#SLOT_LIMIT} when the account
     * has no free slot. Since Build 51 the request may also carry a {@linkplain ProfileType policy type}
     * and a name; they fail with {@link ProfileErrorCode#TYPE_NOT_ALLOWED}, {@link ProfileErrorCode#NAME_INVALID}
     * or {@link ProfileErrorCode#NAME_TAKEN}. The name and type of the created profile are available from
     * {@link #getProfileDetails(UUID)}; the returned {@link ProfileInfo} is unchanged.
     */
    CompletableFuture<ProfileInfo> createProfile(ProfileRequest request);

    /** Every profile of the account, in creation order. */
    CompletableFuture<List<ProfileInfo>> listProfiles(UUID accountId);

    CompletableFuture<Optional<ProfileInfo>> getProfile(UUID profileId);

    /**
     * The profile with its name and policy type (Build 51). Completes with an empty optional when the
     * profile does not exist. Default failure preserves compatibility with alternative service
     * implementations built before this API was added.
     */
    default CompletableFuture<Optional<ProfileDetails>> getProfileDetails(UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile details unavailable"));
    }

    /**
     * Every profile of the account with its name and policy type, in creation order (Build 51). Default
     * failure preserves compatibility with alternative service implementations built before this API was
     * added.
     */
    default CompletableFuture<List<ProfileDetails>> listProfileDetails(UUID accountId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile details unavailable"));
    }

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

    /**
     * Puts an inactive profile on administrative hold ({@code locked}: still listed, switching to it is
     * refused) or releases it. The active profile cannot be locked, and the call is refused during an
     * account transition or while a transition journal of the account is pending. The plugin must
     * authorize player requests itself. Default failure preserves compatibility with alternative service
     * implementations built before this API was added.
     */
    default CompletableFuture<Void> setProfileLocked(UUID profileId, boolean locked) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile locking unavailable"));
    }

    /**
     * Sets how many profiles an account may own, between 1 and the server's {@code max-slots}; completes
     * with the new limit. Profiles beyond a lowered limit are kept, only creating more is refused. Default
     * failure preserves compatibility with alternative service implementations built before this API was
     * added.
     */
    default CompletableFuture<Integer> setProfileSlotLimit(UUID accountId, int slots) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile slot limits unavailable"));
    }

    /** Which node owns the profile, if any. */
    CompletableFuture<Optional<ProfileOwner>> resolveProfileOwnership(UUID profileId);

    /**
     * Registers the plugin's adapter, or replaces the one that plugin registered under the same id.
     * {@link ProfileStateAdapter#version()} is read once, here; a plugin that changes it registers again.
     *
     * @throws IllegalArgumentException when the id is malformed, its {@code id:version} key is longer
     *     than 64 characters, the version is below 1, another enabled plugin registered the id, or 32
     *     adapters are registered already and the id is not one of them
     * @throws ProfileOperationException with {@link ProfileErrorCode#INVALID_REQUEST} when the plugin is
     *     not enabled
     */
    void registerProfileStateAdapter(Plugin plugin, ProfileStateAdapter adapter);

    void unregisterProfileStateAdapter(Plugin plugin, String adapterId);
}
